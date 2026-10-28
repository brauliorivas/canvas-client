(ns canvaslms.core.grading-test
  (:require [canvaslms.core.grading :as grading]
            [canvaslms.core.matching :as matching]
            [canvaslms.model.domain :as domain]
            [clojure.test :refer [deftest is]]))

(def policy {:id 1 :version 1 :source-max 55M :destination-max 20M
             :penalty-within 20M :penalty-after 50M :source-name "Knowledge check"})
(def cutoff "2026-01-01T00:00:00Z")

(deftest conversion-and-penalty-boundaries
  (doseq [[timestamp expected tier]
          [[cutoff "16.00" :on-time]
           ["2025-12-31T23:59:59Z" "16.00" :on-time]
           ["2026-01-01T00:00:00.001Z" "12.80" :within-two-weeks]
           ["2026-01-15T00:00:00Z" "12.80" :within-two-weeks]
           ["2026-01-15T00:00:00.001Z" "8.00" :after-two-weeks]]]
    (let [result (grading/calculate policy 44M timestamp cutoff)]
      (is (= expected (:grade result)))
      (is (= tier (:tier result)))))
  (is (= "0.00" (:grade (grading/calculate policy 0 cutoff cutoff))))
  (is (= "20.00" (:grade (grading/calculate policy 99 cutoff cutoff))))
  (is (= "0.00" (:grade (grading/calculate policy -1 cutoff cutoff))))
  (is (= "0.67" (:grade (grading/calculate (assoc policy :source-max 3 :destination-max 2)
                                          1 cutoff cutoff))))
  (is (= "1.01" (:grade (grading/calculate (assoc policy :source-max 200 :destination-max 2)
                                          100.5M cutoff cutoff))))
  (is (= "1.00" (:grade (grading/calculate (assoc policy :source-max 1 :destination-max 1.005M)
                                          1 cutoff cutoff))))
  (is (= "16.00" (:grade (grading/calculate policy 44 "2025-12-31T19:00:00-05:00" cutoff)))))

(deftest validation
  (doseq [value [0 -1 "NaN" "Infinity" "" nil]]
    (is (thrown? clojure.lang.ExceptionInfo (domain/positive-decimal value))))
  (doseq [value [-1 101 "x" nil]]
    (is (thrown? clojure.lang.ExceptionInfo (domain/percentage value))))
  (is (= 100M (domain/percentage "100"))))

(deftest email-matching
  (let [av [{:id 1 :name "Official name" :login_id " Student@Example.edu "}
            {:id 2 :login_id "missing@example.edu"}]
        aws [{:id 100 :name "Different name" :login_id "student@example.edu"}
             {:id 900 :login_id "outsider@example.edu"}]
        matches (matching/match-students av aws)]
    (is (= [:matched :absent] (mapv :status matches)))
    (is (= "Official name" (get-in matches [0 :av :name])))
    (is (= 100 (get-in matches [0 :aws :id]))))
  (is (= :ambiguous-av-login
         (:status (first (matching/match-students [{:id 1 :login_id "a"} {:id 2 :login_id "A"}]
                                                  [{:id 3 :login_id "a"}])))))
  (is (= :ambiguous-aws-login
         (:status (first (matching/match-students [{:id 1 :login_id "a"}]
                                                  [{:id 3 :login_id "a"} {:id 4 :login_id "A"}])))))
  (is (= :missing-login (:status (first (matching/match-students [{:id 1}] [])))))
  (is (= :aws-logins-unavailable
         (:status (first (matching/match-students [{:id 1 :login_id "a"}] [{:id 3}]))))))

(deftest highest-attempt-with-its-own-time
  (let [first-attempt {:attempt 1 :score 50 :workflow_state "graded" :submitted_at cutoff}
        second-attempt {:attempt 2 :score 40 :workflow_state "graded"
                        :submitted_at "2026-02-01T00:00:00Z"}
        submission (assoc second-attempt :submission_history [first-attempt second-attempt])
        selected (:attempt (grading/select-attempt submission [] false))]
    (is (= 1 (:attempt selected)))
    (is (= cutoff (:submitted_at selected)))
    (is (= 1 (get-in (grading/select-attempt (assoc submission :score 50) [] false) [:attempt :attempt])))
    (is (= :incomplete-attempt-history (:reason (grading/select-attempt second-attempt [] false))))
    (is (= 1 (get-in (grading/select-attempt
                      (assoc submission :grade_matches_current_submission false)
                      [] false) [:attempt :attempt]))))
  (let [history [{:attempt 1 :score 30 :workflow_state "graded" :submitted_at cutoff}]
        quiz [{:attempt 1 :score 45 :finished_at cutoff :workflow_state "complete"}]
        aggregate {:attempt 1 :score 55 :submitted_at "2026-02-01T00:00:00Z"
                   :submission_history history}]
    (is (= 45 (get-in (grading/select-attempt aggregate quiz true) [:attempt :score])))
    (is (= cutoff (get-in (grading/select-attempt aggregate quiz true) [:attempt :submitted_at]))))
  (is (= :missing-submission-time
         (:reason (grading/select-attempt {:attempt 1 :score 5 :workflow_state "graded"} [] false))))
  (is (= :source-excused (:reason (grading/select-attempt {:excused true} [] false)))))

(deftest cutoff-overrides
  (let [student {:id 1 :section-ids #{10 11}}
        base {:course_id 7 :lock_at cutoff}
        section {:course_section_id 10 :lock_at "2026-01-10T00:00:00Z"}
        other {:course_section_id 11 :lock_at "2026-01-20T00:00:00Z"}
        individual {:student_ids [1] :lock_at "2026-01-05T00:00:00Z"}]
    (is (= cutoff (:cutoff (grading/effective-cutoff base student nil))))
    (is (= (:lock_at other) (:cutoff (grading/effective-cutoff (assoc base :overrides [section other]) student nil))))
    (is (= (:lock_at individual)
           (:cutoff (grading/effective-cutoff (assoc base :overrides [section individual]) student nil))))
    (is (= :missing-cutoff
           (:reason (grading/effective-cutoff (assoc base :overrides [(assoc section :lock_at nil)]) student nil))))
    (is (= :local (:cutoff-source (grading/effective-cutoff {:lock_at nil} student cutoff))))
    (is (= :not-assigned (:reason (grading/effective-cutoff (assoc base :only_visible_to_overrides true) student nil))))
    (is (= :unsupported-availability-overrides
           (:reason (grading/effective-cutoff (assoc base :overrides [{:group_id 9 :lock_at cutoff}]) student nil))))))

(def row-input {:mapping policy :destination {:lock_at cutoff}
                :match {:av {:id 1 :name "Student"} :aws {:id 101} :email "student@example.edu" :status :matched}
                :destination-submission {:score nil :workflow_state "unsubmitted"}
                :timezone "America/Guayaquil" :now "2026-02-01T00:00:00Z"})

(deftest missing-work-and-pending-grades
  (let [missing {:workflow_state "unsubmitted" :score nil
                 :submission_history [{:workflow_state "unsubmitted" :score nil}]}
        input (assoc row-input :source-submission missing)]
    (is (= "0.00" (:grade (grading/propose-row input))))
    (is (= :not-yet-closed (:reason (grading/propose-row (assoc input :now cutoff)))))
    (is (= :absent (:reason (grading/propose-row (assoc-in input [:match :status] :absent)))))
    (is (= :pending-or-unavailable-grade
           (:reason (grading/propose-row (assoc input :source-submission {:workflow_state "submitted"})))))))

(deftest penalty-comment-and-idempotence
  (let [row (grading/propose-row (assoc row-input :source-submission
                                       {:attempt 1 :score 44 :workflow_state "graded"
                                        :submitted_at "2026-01-02T00:00:00Z"}))
        current {:score 12.8M :late_policy_status "none"
                 :submission_comments [{:id 4 :comment (:comment row)}]}]
    (is (= "12.80" (:grade row)))
    (is (re-find #"20% de la nota obtenida" (:comment row)))
    (is (re-find #"Disponible hasta:" (:comment row)))
    (is (grading/applied? row current))
    (is (not (grading/applied? row (assoc current :score 12M))))
    (is (not (grading/applied? row (assoc current :submission_comments []))))
    (is (= :unchanged (:reason (grading/propose-row (assoc row-input :source-submission
                                                         {:attempt 1 :score 44 :workflow_state "graded"
                                                          :submitted_at "2026-01-02T00:00:00Z"}
                                                         :destination-submission current)))))))
