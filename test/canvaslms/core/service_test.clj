(ns canvaslms.core.service-test
  (:require [canvaslms.api.canvas :as canvas]
            [canvaslms.cli.commands :as commands]
            [canvaslms.core.service :as service]
            [canvaslms.model.db :as db]
            [canvaslms.model.domain :as domain]
            [clojure.test :refer [deftest is]])
  (:import (java.io BufferedReader StringReader StringWriter)))

(def av-url "https://av.example.edu")
(def aws-url "https://aws.example.edu")
(def av-course {:id 10 :name "School"})
(def aws-course {:id 20 :name "Academy"})
(def students [{:id 1 :name "Alice" :login_id "alice@example.edu" :section-ids #{3}}
               {:id 2 :name "Bob" :login_id "bob@example.edu" :section-ids #{3}}])
(def members [{:id 101 :name "AWS Alice" :login_id "alice@example.edu"}
              {:id 102 :name "AWS Bob" :login_id "bob@example.edu"}])
(def source {:id 30 :name "KC" :points_possible 55M :published true :grading_type "points"})
(def destination {:id 40 :course_id 10 :name "Homework" :points_possible 20M
                  :published true :grading_type "points" :lock_at "2026-01-01T00:00:00Z"})

(defn with-system [f]
  (with-open [database (db/open! ":memory:")]
    (let [ctx {:db database :av {:url av-url} :aws {:url aws-url} :timezone "UTC"}
          writes (atom [])
          remote (atom {:students students :members members :source source :destination destination
                        :source-subs {101 {:user_id 101 :attempt 1 :score 44M :workflow_state "graded"
                                          :submitted_at "2026-01-02T00:00:00Z"}
                                      102 {:user_id 102 :attempt 1 :score 55M :workflow_state "graded"
                                          :submitted_at "2025-12-31T00:00:00Z"}}
                        :destination-subs {1 {:user_id 1 :score nil :late_policy_status nil}
                                           2 {:user_id 2 :score nil :late_policy_status nil}}})]
      (with-redefs [canvas/roster (fn [api _] (get @remote (if (= av-url (:url api)) :students :members)))
                    canvas/assignment (fn [api _ _] (get @remote (if (= av-url (:url api)) :destination :source)))
                    canvas/submissions (fn [api _ _] (vec (vals (get @remote (if (= av-url (:url api))
                                                                            :destination-subs :source-subs)))))
                    canvas/submission (fn [_ _ _ id] (get-in @remote [:destination-subs id]))
                    canvas/update-grade! (fn [_ _ _ id grade comment]
                                           (swap! writes conj {:user-id id :grade grade :comment comment})
                                           (swap! remote update-in [:destination-subs id]
                                                  #(cond-> (assoc % :score (domain/decimal grade) :late_policy_status "none")
                                                     comment (update :submission_comments (fnil conj [])
                                                                     {:id (+ 100 id) :comment comment})))
                                           (get-in @remote [:destination-subs id]))]
        (let [r (service/save-registration! ctx (service/registration-preview ctx av-course aws-course))
              mapping (service/save-mapping! ctx (service/mapping-data
                                                   r destination source
                                                   {:source-max 55M :destination-max 20M
                                                    :penalty-within 20M :penalty-after 50M}))]
          (f ctx mapping remote writes))))))

(deftest confirmed-transfer-and-rerun
  (with-system
    (fn [ctx mapping _ writes]
      (let [preview (service/grade-preview ctx mapping)]
        (is (= ["12.80" "20.00"] (mapv :grade (:rows preview))))
        (is (thrown? clojure.lang.ExceptionInfo
                     (service/apply-grades! ctx preview {:student-ids #{1 2}})))
        (is (empty? @writes))
        (is (thrown? clojure.lang.ExceptionInfo
                     (service/apply-grades! ctx preview {:confirmed? true :student-ids #{99}})))
        (let [result (service/apply-grades! ctx preview {:confirmed? true :student-ids #{1 2}})]
          (is (= :complete (:status result)))
          (is (= [:succeeded :succeeded] (mapv :status (:results result))))
          (is (= 2 (count @writes)))
          (is (= 1 (count (filter :comment @writes))))
          (is (every? #(= :skip (:action %)) (:rows (service/grade-preview ctx mapping)))))))))

(deftest changed-source-destination-and-mapping
  (with-system
    (fn [ctx mapping remote writes]
      (let [preview (service/grade-preview ctx mapping)]
        (swap! remote assoc-in [:source-subs 101 :score] 40M)
        (swap! remote assoc-in [:destination-subs 2 :score] 5M)
        (let [result (service/apply-grades! ctx preview {:confirmed? true :student-ids #{1 2}})]
          (is (= [:stale :stale] (mapv :status (:results result))))
          (is (empty? @writes)))
        (service/save-mapping! ctx mapping)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mapping changed"
                              (service/apply-grades! ctx preview {:confirmed? true :student-ids #{1}})))))))

(deftest unknown-outcome-is-not-blindly-retried
  (with-system
    (fn [ctx mapping remote writes]
      (let [preview (service/grade-preview ctx mapping)
            original canvas/update-grade!]
        (with-redefs [canvas/update-grade! (fn [& args]
                                            (apply original args)
                                            (throw (ex-info "timeout" {:type :unknown-write}))) ]
          (let [result (service/apply-grades! ctx preview {:confirmed? true :student-ids #{1 2}})]
            (is (= [:unknown :skipped] (mapv :status (:results result))))))
        (is (= 1 (count @writes)))
        (let [reconciled (service/grade-preview ctx mapping)]
          (is (= :already-applied (get-in reconciled [:rows 0 :reason])))
          (is (= :write (get-in reconciled [:rows 1 :action])))
          (service/apply-grades! ctx reconciled {:confirmed? true :student-ids #{2}}))
        (is (= 2 (count @writes)))
        (is (= 1 (count (get-in @remote [:destination-subs 1 :submission_comments]))))))))

(deftest unresolved-write-requires-extra-acknowledgement
  (with-system
    (fn [ctx mapping _ writes]
      (let [preview (service/grade-preview ctx mapping)]
        (with-redefs [canvas/update-grade! (fn [& _] (throw (ex-info "timeout" {:type :unknown-write})))]
          (service/apply-grades! ctx preview {:confirmed? true :student-ids #{1}}))
        (let [unresolved (service/grade-preview ctx mapping)
              row (first (:rows unresolved))]
          (is (= :unresolved-write (:reason row)))
          (is (empty? @writes))
          (is (thrown? clojure.lang.ExceptionInfo (service/authorize-retry! ctx row false)))
          (service/authorize-retry! ctx row true)
          (let [retry-preview (service/grade-preview ctx mapping)]
            (is (= :write (get-in retry-preview [:rows 0 :action])))
            (service/apply-grades! ctx retry-preview {:confirmed? true :student-ids #{1}}))
          (is (= 1 (count @writes))))))))

(deftest metadata-change-and-missing-membership
  (with-system
    (fn [ctx mapping remote writes]
      (swap! remote assoc :members [(first members)])
      (is (= :absent (get-in (service/grade-preview ctx mapping) [:rows 1 :reason])))
      (swap! remote assoc-in [:source :points_possible] 100M)
      (let [preview (service/grade-preview ctx mapping)]
        (is (seq (:issues preview)))
        (is (every? #(= :skip (:action %)) (:rows preview))))
      (is (empty? @writes)))))

(deftest failed-verification-after-a-write-stays-uncertain
  (with-system
    (fn [ctx mapping _ writes]
      (let [preview (service/grade-preview ctx mapping)
            get-original canvas/submission
            update-original canvas/update-grade!
            sent? (atom false)]
        (with-redefs [canvas/update-grade! (fn [& args]
                                            (apply update-original args)
                                            (reset! sent? true)
                                            {})
                      canvas/submission (fn [& args]
                                          (if @sent?
                                            (throw (ex-info "Not available" {:type :http :status 404}))
                                            (apply get-original args)))]
          (let [result (service/apply-grades! ctx preview {:confirmed? true :student-ids #{1 2}})]
            (is (= [:unknown :skipped] (mapv :status (:results result))))
            (is (= 1 (count @writes)))
            (is (= "unknown" (:status (first (db/operation-history (:db ctx)
                                                                      (get-in preview [:rows 0 :operation-id]))))))))))))

(defn interact [input f]
  (binding [*in* (BufferedReader. (StringReader. input)) *out* (StringWriter.)]
    (f)
    (str *out*)))

(deftest full-interactive-workflow
  (with-system
    (fn [ctx _ _ writes]
      (with-redefs [canvas/courses (fn [api] [(if (= av-url (:url api)) av-course aws-course)])
                    canvas/assignments (fn [api _] [(if (= av-url (:url api)) destination source)])]
        (is (re-find #"Saved registration" (interact "10\n20\ny\nn\n" #(commands/register! ctx))))
        (is (= 1 (count (service/registrations ctx))))
        (is (re-find #"Saved homework" (interact "10\n40\n1\n30\n\n\n20\n50\ny\n" #(commands/homework! ctx))))
        (is (= 2 (:version (first (service/mappings ctx)))))
        (is (re-find #"Dry run complete" (interact "1\n" #(commands/grade! ctx true))))
        (is (empty? @writes))
        (is (re-find #"Results saved to SQLite" (interact "1\ny\n" #(commands/grade! ctx false))))
        (is (= 2 (count @writes)))
        (is (re-find #"No grade changes selected" (interact "1\n" #(commands/grade! ctx false))))
        (is (= 2 (count @writes)))))))

(deftest interactive-local-fallback-for-a-removed-student-cutoff
  (with-system
    (fn [ctx _ remote _]
      (swap! remote assoc-in [:destination :overrides] [{:student_ids [1] :lock_at nil}])
      (with-redefs [canvas/assignments (fn [api _] [(if (= av-url (:url api)) destination source)])]
        (interact "10\n40\n1\n30\n\n\n20\n50\n2026-01-03 00:00\ny\n" #(commands/homework! ctx))
        (let [mapping (first (service/mappings ctx))
              preview (service/grade-preview ctx mapping)]
          (is (= "2026-01-03T00:00:00Z" (:local-cutoff mapping)))
          (is (= :local (get-in preview [:rows 0 :cutoff-source])))
          (is (= :canvas (get-in preview [:rows 1 :cutoff-source]))))))))

(deftest authentication-errors-stop-the-batch
  (with-system
    (fn [ctx mapping _ writes]
      (let [preview (service/grade-preview ctx mapping)]
        (with-redefs [canvas/update-grade! (fn [& _]
                                            (throw (ex-info "Permission denied" {:type :authentication :status 403})))]
          (let [result (service/apply-grades! ctx preview {:confirmed? true :student-ids #{1 2}})]
            (is (= [:authentication-error :skipped] (mapv :status (:results result))))
            (is (empty? @writes))))))))

(deftest rejected-student-write-does-not-lose-other-results
  (with-system
    (fn [ctx mapping _ writes]
      (let [preview (service/grade-preview ctx mapping)
            original canvas/update-grade!]
        (with-redefs [canvas/update-grade! (fn [api course assignment user grade comment]
                                            (if (= user 1)
                                              (throw (ex-info "Grade rejected" {:type :http :status 422}))
                                              (original api course assignment user grade comment)))]
          (let [result (service/apply-grades! ctx preview {:confirmed? true :student-ids #{1 2}})]
            (is (= [:failed :succeeded] (mapv :status (:results result))))
            (is (= [2] (mapv :user-id @writes)))))))))
