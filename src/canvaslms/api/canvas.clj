(ns canvaslms.api.canvas
  (:require [canvaslms.api.client :as client]))

(defn courses [api]
  (client/paged! api "/courses" {"include[]" ["term" "permissions"]}))

(defn roster [api course-id]
  (let [users (client/paged! api (str "/courses/" course-id "/users")
                             {"enrollment_type[]" ["student"]
                              "enrollment_state[]" ["active" "invited" "creation_pending"]
                              "include[]" ["enrollments" "email"]})
        enrollments (client/paged! api (str "/courses/" course-id "/enrollments")
                                   {"type[]" ["StudentEnrollment"]
                                    "state[]" ["active" "invited" "creation_pending"]})
        by-user (group-by :user_id enrollments)]
    (->> users
         (group-by :id)
         (map (fn [[id records]]
                (assoc (first records) :section-ids
                       (set (keep :course_section_id (get by-user id))))))
         (sort-by (juxt :sortable_name :id)) vec)))

(defn assignments [api course-id]
  (client/paged! api (str "/courses/" course-id "/assignments")
                 {"override_assignment_dates" false}))

(defn assignment [api course-id assignment-id]
  (let [path (str "/courses/" course-id "/assignments/" assignment-id)
        a (client/get! api path {"override_assignment_dates" false})]
    (assoc a :overrides (if (:has_overrides a)
                         (client/paged! api (str path "/overrides")) []))))

(defn submissions [api course-id assignment-id]
  (client/paged! api (str "/courses/" course-id "/assignments/" assignment-id "/submissions")
                 {"include[]" ["submission_history" "submission_comments" "visibility"]}))

(defn submission [api course-id assignment-id user-id]
  (client/get! api (str "/courses/" course-id "/assignments/" assignment-id "/submissions/" user-id)
               {"include[]" ["submission_history" "submission_comments" "visibility"]}))

(defn quiz-attempts [api course-id quiz-id]
  (client/paged! api (str "/courses/" course-id "/quizzes/" quiz-id "/submissions")
                 {} :quiz_submissions))

(defn update-grade! [api course-id assignment-id user-id grade comment]
  (:body (client/request! api :put
                         (str "/courses/" course-id "/assignments/" assignment-id "/submissions/" user-id)
                         (cond-> {"submission[posted_grade]" grade
                                  "prefer_points_over_scheme" true
                                  "submission[late_policy_status]" "none"}
                           comment (assoc "comment[text_comment]" comment)))))
