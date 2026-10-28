(ns canvaslms.cli.commands
  (:require [canvaslms.cli.ui :as ui]
            [canvaslms.core.service :as service]
            [canvaslms.model.domain :as domain]))

(def course-columns [:id :name :course_code])
(def assignment-columns [:id :name :points_possible :lock_at :grading_type])

(defn register! [ctx]
  (loop []
    (let [av-courses (service/courses ctx :av)
          aws-courses (service/courses ctx :aws)
          av (ui/select! "Aula Virtual courses" av-courses course-columns)
          aws (ui/select! "AWS Academy courses" aws-courses course-columns)
          preview (service/registration-preview ctx av aws)]
      (ui/print-table [:av-id :name :email :aws-id :aws-name :status]
                      (mapv (fn [{:keys [av aws email status]}]
                              {:av-id (:id av) :name (:name av) :email email
                               :aws-id (:id aws) :aws-name (:name aws) :status (name status)})
                            (:matches preview)))
      (ui/message :cyan (str "Match summary: " (frequencies (map :status (:matches preview)))))
      (when (ui/confirm! "Save these course relationships and student matches locally?")
        (let [saved (service/save-registration! ctx preview)]
          (ui/message :green (str "Saved registration #" (:id saved) "."))))
      (when (ui/confirm! "Register or refresh another AWS course?") (recur)))))

(defn- registered-course! [ctx]
  (let [registrations (service/registrations ctx)]
    (when (empty? registrations)
      (throw (ex-info "Run register before configuring homework." {:type :no-data})))
    (ui/select! "Registered Aula Virtual courses"
                (->> registrations (map :av-course) (group-by :id) vals (mapv first))
                course-columns)))

(defn homework! [ctx]
  (let [saved (service/mappings ctx)
        _ (when (seq saved)
            (ui/message :cyan "Saved homework mappings (select their destination below to edit)")
            (ui/print-table [:id :destination-name :source-name :version] saved))
        av-course (registered-course! ctx)
        destination-choice (ui/select! "Aula Virtual assignments"
                                       (filterv service/gradeable?
                                                (service/assignments ctx :av (:id av-course)))
                                       assignment-columns)
        destination (service/assignment ctx :av (:id av-course) (:id destination-choice))
        old (some #(when (and (= (:av-course-id %) (:id av-course))
                              (= (:destination-id %) (:id destination))) %) saved)
        _ (when old (ui/message :yellow (str "Editing mapping #" (:id old)
                                             "; the previous configuration is retained in history.")))
        registration (ui/select! "Registered AWS courses"
                                (mapv #(assoc % :course-id (get-in % [:aws-course :id])
                                                :name (get-in % [:aws-course :name]))
                                      (filterv #(= (:id av-course) (get-in % [:av-course :id]))
                                               (service/registrations ctx)))
                                [{:key :id :title "Registration ID"} :course-id :name])
        source-choice (ui/select! "AWS Academy gradeable activities"
                                  (filterv service/gradeable?
                                           (service/assignments ctx :aws (get-in registration [:aws-course :id])))
                                  assignment-columns)
        source (service/assignment ctx :aws (get-in registration [:aws-course :id]) (:id source-choice))
        same-source? (and (= (:registration-id old) (:id registration))
                          (= (:source-id old) (:id source)))
        _ (ui/message :cyan "Conversion: source score / AWS maximum × Aula Virtual maximum.")
        source-max (ui/value! "AWS maximum points"
                              (if same-source? (:source-max old) (:points_possible source))
                              domain/positive-decimal)
        destination-max (ui/value! "Aula Virtual maximum points"
                                   (or (:destination-max old) (:points_possible destination))
                                   domain/positive-decimal)
        _ (ui/message :cyan "A 20% late penalty deducts 20% of the earned converted grade: 80 becomes 64.")
        within (ui/value! "Penalty percent when late by up to 14 days (inclusive)"
                          (:penalty-within old) domain/percentage)
        after (ui/value! "Penalty percent when late by more than 14 days"
                         (:penalty-after old) domain/percentage)
        needs-fallback? (or (nil? (:lock_at destination))
                            (some #(and (contains? % :lock_at) (nil? (:lock_at %)))
                                  (:overrides destination)))
        local-cutoff (if needs-fallback?
                       (do (ui/message :yellow (str "An Available until date is missing. Enter a local fallback in "
                                                   (:timezone ctx) "; Canvas will not be changed."))
                           (ui/value! "Local cutoff (YYYY-MM-DD HH:mm or ISO 8601)"
                                       (:local-cutoff old) #(ui/parse-cutoff % (:timezone ctx))))
                       (:local-cutoff old))
        value (service/mapping-data registration destination source
                                    {:source-max source-max :destination-max destination-max
                                     :penalty-within within :penalty-after after :local-cutoff local-cutoff})]
    (ui/print-table [:destination-name :source-name :source-max :destination-max
                     :penalty-within :penalty-after]
                    [value])
    (ui/message :cyan (str "Available until: " (or (ui/format-time (:lock_at destination) (:timezone ctx))
                                                 "individual override / local fallback")
                          "; local fallback: " (or (ui/format-time local-cutoff (:timezone ctx)) "none") "."))
    (when (seq (:overrides destination))
      (ui/message :yellow "Student/section overrides will be resolved per student in the grade preview."))
    (when (ui/confirm! "Save this homework mapping locally?")
      (let [result (service/save-mapping! ctx value)]
        (ui/message :green (str "Saved homework #" (:id result) ", version " (:version result) "."))))))

(defn show-preview! [ctx preview approved]
  (doseq [issue (:issues preview)] (ui/message :red issue))
  (ui/print-table [:user-id :name :email :attempt :source-score :current :scaled :deduction :proposed :decision]
                  (mapv (fn [row]
                          (assoc row :current (domain/points (:current-grade row))
                                     :proposed (:grade row)
                                     :decision (cond
                                                 (= :skip (:action row)) (name (:reason row))
                                                 (nil? approved) "proposed"
                                                 (approved (:user-id row)) "approved"
                                                 :else "declined"))) (:rows preview)))
  (doseq [row (:rows preview)]
    (println (str "Student " (:user-id row) ": submitted "
                  (or (ui/format-time (:submitted-at row) (:timezone ctx)) "—")
                  "; cutoff " (or (ui/format-time (:cutoff row) (:timezone ctx)) "—")
                  " (" (or (some-> (:cutoff-source row) name) "unavailable") ")."))
    (when-let [comment (:comment row)]
      (println (str "  Comment: " (ui/safe-text comment)))))
  (ui/message :yellow "Each approved write sets this submission's late policy status to 'none' to avoid a second Canvas deduction. Course grade-posting policy is preserved.")
  (when (some #(= :unresolved-write (:reason %)) (:rows preview))
    (ui/message :yellow "An earlier write has an uncertain outcome. Inspect its grade/comment in Canvas; it will not be resent automatically.")))

(defn grade! [ctx dry-run?]
  (let [graded-ids (service/graded-mapping-ids ctx)
        mapping (ui/select! "Saved homework mappings" (service/mappings ctx)
                            [:id {:key #(if (contains? graded-ids (:id %)) "Yes" "No") :title "Graded"}
                             :destination-name :source-name :version])
        _ (ui/message :cyan "Refreshing rosters, attempts, cutoffs, and current grades...")
        initial-preview (service/grade-preview ctx mapping)
        _ (show-preview! ctx initial-preview nil)
        retries (when-not dry-run?
                  (filterv (fn [row]
                             (and (= :unresolved-write (:reason row))
                                  (ui/confirm! (str "After inspecting Canvas for " (ui/safe-text (:name row))
                                                     ", authorize retry of the unresolved operation?"
                                                     " This only enables a new preview; final confirmation is still required."))))
                           (:rows initial-preview)))
        _ (doseq [row retries] (service/authorize-retry! ctx row true))
        preview (if (seq retries) (service/grade-preview ctx mapping) initial-preview)]
    (when (seq retries) (show-preview! ctx preview nil))
    (if dry-run?
      (ui/message :cyan "Dry run complete. No grades or comments were written.")
      (let [approved (into #{} (keep (fn [row]
                                       (when (and (= :write (:action row))
                                                  (or (not (:overwrite? row))
                                                      (ui/confirm! (str "Replace " (ui/safe-text (:name row))
                                                                         " (" (ui/safe-text (:email row)) ") grade "
                                                                         (domain/points (:current-grade row))
                                                                         " with " (:grade row) "?"))))
                                         (:user-id row))) (:rows preview)))]
        (if (empty? approved)
          (ui/message :cyan "No grade changes selected.")
          (do
            (show-preview! ctx preview approved)
            (when (ui/confirm! (str "Apply exactly these " (count approved) " approved student changes?"))
              (ui/message :cyan "Rechecking the proposal, then applying approved changes...")
              (let [result (service/apply-grades! ctx preview {:confirmed? true :student-ids approved})]
                (ui/print-table [:user-id :status :reason] (:results result))
                (ui/message (if (= :complete (:status result)) :green :yellow)
                            (str "Run " (:run-id result) ": " (name (:status result))
                                 ". Results saved to SQLite."))))))))))
