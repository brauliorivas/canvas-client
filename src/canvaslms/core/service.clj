(ns canvaslms.core.service
  (:require [canvaslms.api.canvas :as canvas]
            [canvaslms.core.grading :as grading]
            [canvaslms.core.matching :as matching]
            [canvaslms.model.db :as db]
            [canvaslms.model.domain :as domain]
            [clojure.set :as set])
  (:import (java.time Instant)))

(defn registrations [{:keys [db av aws]}]
  (db/registrations db (:url av) (:url aws)))

(defn mappings [{:keys [db av aws]}]
  (db/mappings db (:url av) (:url aws)))

(defn graded-mapping-ids [{:keys [db av aws]}]
  (db/graded-mapping-ids db (:url av) (:url aws)))

(defn courses [ctx side] (canvas/courses (get ctx side)))
(defn assignments [ctx side course-id] (canvas/assignments (get ctx side) course-id))
(defn assignment [ctx side course-id id] (canvas/assignment (get ctx side) course-id id))

(defn- student-data [student]
  (select-keys student [:id :name :sortable_name :login_id :email :section-ids]))

(defn registration-preview [{:keys [av aws]} av-course aws-course]
  (let [students (mapv student-data (canvas/roster av (:id av-course)))
        members (mapv student-data (canvas/roster aws (:id aws-course)))]
    {:registration {:av-url (:url av) :aws-url (:url aws)
                    :av-course (select-keys av-course [:id :name :course_code])
                    :aws-course (select-keys aws-course [:id :name :course_code])}
     :students students :members members
     :matches (matching/match-students students members)}))

(defn save-registration! [{:keys [db]} preview]
  (db/save-registration! db (:registration preview) (:students preview)
                         (:members preview) (:matches preview)))

(defn mapping-data [registration destination source settings]
  (merge {:registration-id (:id registration)
          :av-url (:av-url registration) :aws-url (:aws-url registration)
          :av-course-id (get-in registration [:av-course :id])
          :aws-course-id (get-in registration [:aws-course :id])
          :destination-id (:id destination) :destination-name (:name destination)
          :source-id (:id source) :source-name (:name source)
          :source-api-max (:points_possible source)
          :destination-api-max (:points_possible destination)}
         (domain/validate-mapping settings)))

(defn save-mapping! [{:keys [db]} value] (db/save-mapping! db value))

(defn gradeable? [assignment]
  (and (not= "not_graded" (:grading_type assignment))
       (not (:has_sub_assignments assignment))
       (not (:moderated_grading assignment))
       (or (nil? (:group_category_id assignment))
           (:grade_group_students_individually assignment))))

(defn- metadata-issues [mapping source destination]
  (cond-> []
    (not (gradeable? destination)) (conj "Destination requires unsupported group, checkpoint, or moderated grading.")
    (false? (:published destination)) (conj "Destination is unpublished.")
    (not= (domain/decimal (:source-api-max mapping)) (domain/decimal (:points_possible source)))
    (conj "AWS maximum points changed. Review and save this homework mapping again.")
    (not= (domain/decimal (:destination-api-max mapping)) (domain/decimal (:points_possible destination)))
    (conj "Aula Virtual maximum points changed. Review and save this homework mapping again.")))

(defn- history-state [database row destination]
  (let [history (db/operation-history database (:operation-id row))
        uncertain (some #(#{"sending" "unknown"} (:status %)) history)]
    (cond
      (and uncertain (grading/applied? row destination))
      (do (doseq [entry history :when (#{"sending" "unknown"} (:status entry))]
            (db/record-result! database (:run_id entry) row :succeeded
                               {:reconciled true :submission destination}))
          (assoc row :action :skip :reason :already-applied))
      (and uncertain (not (grading/applied? row destination)))
      (assoc row :action :skip :reason :unresolved-write)
      :else row)))

(defn grade-preview
  "Only reads Canvas. Local uncertain outcomes can be reconciled from remote evidence."
  [{:keys [db av aws timezone] :as ctx} mapping]
  (when-not (and (= (:url av) (:av-url mapping)) (= (:url aws) (:aws-url mapping)))
    (throw (ex-info "This mapping belongs to different Canvas instances." {:type :configuration})))
  (let [registration (db/registration db (:registration-id mapping))
        roster-preview (registration-preview ctx (:av-course registration) (:aws-course registration))
        source (canvas/assignment aws (:aws-course-id mapping) (:source-id mapping))
        destination (canvas/assignment av (:av-course-id mapping) (:destination-id mapping))
        source-subs (into {} (map (juxt :user_id identity)
                                 (canvas/submissions aws (:aws-course-id mapping) (:source-id mapping))))
        destination-subs (into {} (map (juxt :user_id identity)
                                      (canvas/submissions av (:av-course-id mapping) (:destination-id mapping))))
        quiz-attempts (if-let [quiz-id (:quiz_id source)]
                        (group-by :user_id (canvas/quiz-attempts aws (:aws-course-id mapping) quiz-id)) {})
        issues (metadata-issues mapping source destination)
        now (str (Instant/now))
        rows (mapv (fn [match]
                     (let [destination-sub (get destination-subs (get-in match [:av :id]))
                           row (grading/propose-row
                                {:mapping mapping :destination destination :match match
                                 :source-submission (get source-subs (get-in match [:aws :id]))
                                 :destination-submission destination-sub
                                 :quiz-attempts (get quiz-attempts (get-in match [:aws :id]))
                                 :classic-quiz? (some? (:quiz_id source)) :now now :timezone timezone})]
                       (cond
                         (seq issues) (assoc row :action :skip :reason :mapping-needs-review)
                         (nil? destination-sub) (assoc row :action :skip :reason :destination-submission-unavailable)
                         :else (history-state db row destination-sub)))) (:matches roster-preview))]
    {:mapping mapping :created-at now :issues issues :rows rows :roster roster-preview}))

(defn- apply-row! [{:keys [db av]} mapping run-id row fresh-row]
  (cond
    (or (nil? fresh-row) (not= (:operation-id row) (:operation-id fresh-row)))
    {:status :stale :reason "Source, membership, or cutoff changed; preview again."}

    (not= :write (:action fresh-row))
    {:status :skipped :reason (name (:reason fresh-row))}

    :else
    (let [current (canvas/submission av (:av-course-id mapping) (:destination-id mapping) (:user-id row))]
      (if (not= (:destination-state row) (grading/destination-state current))
        {:status :stale :reason "Destination changed; preview again."}
        (do
          ;; Commit sending before the HTTP request: a crash cannot cause a blind replay.
          (db/record-result! db run-id row :sending {:before (:destination-state row)})
          (try
            (let [comment (when-not (grading/comment-present? current (:operation-id row)) (:comment row))
                  response (canvas/update-grade! av (:av-course-id mapping) (:destination-id mapping)
                                                (:user-id row) (:grade row) comment)]
              (if (grading/applied? row response)
                {:status :succeeded :submission response}
                ;; Canvas may return incomplete data; verify with a fresh GET.
                (try
                  (let [verified (canvas/submission av (:av-course-id mapping) (:destination-id mapping) (:user-id row))]
                    (if (grading/applied? row verified)
                      {:status :succeeded :submission verified}
                      {:status :unknown :reason "Remote grade/comment does not match the approved result."}))
                  (catch Exception _
                    {:status :unknown :reason "Write returned, but verification failed; reconcile before retrying."}))))
            (catch clojure.lang.ExceptionInfo e
              {:status (case (:type (ex-data e))
                         :authentication :authentication-error
                         :http :failed
                         :unknown)
               :reason (.getMessage e)})
            (catch Exception _
              {:status :unknown :reason "Write or verification interrupted; reconcile before retrying."})))))))

(defn authorize-retry!
  "A separate, explicit operator decision is needed when remote evidence is inconclusive."
  [{:keys [db]} row confirmed?]
  (when-not (true? confirmed?)
    (throw (ex-info "Explicit retry acknowledgement is required." {:type :confirmation-required})))
  (doseq [entry (db/operation-history db (:operation-id row))
          :when (#{"sending" "unknown"} (:status entry))]
    (db/record-result! db (:run_id entry) row :retry-authorized
                       {:operator-acknowledged true :previous-result (:result entry)})))

(defn apply-grades!
  "Requires an explicit approval object from the presentation layer.
   All proposals are persisted before the first remote write."
  [{:keys [db] :as ctx} preview {:keys [confirmed? student-ids]}]
  (when-not (true? confirmed?)
    (throw (ex-info "Explicit batch confirmation is required." {:type :confirmation-required})))
  (let [approved (set student-ids)
        eligible (set (map :user-id (filter #(= :write (:action %)) (:rows preview))))
        mapping (:mapping preview)]
    (when-not (set/subset? approved eligible)
      (throw (ex-info "Approval contains students outside the displayed writable proposal."
                      {:type :validation})))
    (when-not (= mapping (db/mapping db (:id mapping)))
      (throw (ex-info "Homework mapping changed; preview again." {:type :stale})))
    (let [fresh (grade-preview ctx mapping)
          fresh-rows (into {} (map (juxt :user-id identity) (:rows fresh)))
          run-id (db/start-run! db (:id mapping) preview approved)]
      (save-registration! ctx (:roster fresh))
      (loop [remaining (:rows preview) results [] stopped? false]
        (if-let [row (first remaining)]
          (let [result (cond
                         (not (approved (:user-id row))) {:status :skipped :reason "Not approved."}
                         stopped? {:status :skipped :reason "Stopped after an authentication error or unknown write."}
                         :else
                         (try (apply-row! ctx mapping run-id row (get fresh-rows (:user-id row)))
                              (catch clojure.lang.ExceptionInfo e
                                {:status (case (:type (ex-data e))
                                           :authentication :authentication-error
                                           :unknown-write :unknown
                                           :failed)
                                 :reason (.getMessage e)})
                              (catch Exception _
                                ;; A failure after sending may be a successful remote write.
                                {:status :unknown :reason "Interrupted operation; reconcile before retrying."})))
                result (assoc result :user-id (:user-id row))]
            (db/record-result! db run-id row (:status result) result)
            (recur (next remaining) (conj results result)
                   (or stopped? (#{:authentication-error :unknown} (:status result)))))
          (let [status (if (every? #(#{:succeeded :skipped} (:status %)) results) :complete :partial)]
            (db/finish-run! db run-id status)
            {:run-id run-id :status status :results results}))))))
