(ns canvaslms.core.grading
  (:require [canvaslms.model.domain :as domain]
            [clojure.string :as str])
  (:import (java.math BigDecimal RoundingMode)
           (java.security MessageDigest)
           (java.time Duration Instant ZoneId)))

(defn fingerprint [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (pr-str value) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and 0xff %)) digest))))

(defn- round-points [value]
  (let [r (rationalize value)]
    (if (ratio? r)
      (.toPlainString (.divide (bigdec (numerator r)) (bigdec (denominator r))
                              2 RoundingMode/HALF_UP))
      (domain/points r))))

(defn calculate [mapping score submitted-at cutoff]
  (let [{:keys [source-max destination-max penalty-within penalty-after]}
        (domain/validate-mapping mapping)
        submitted (domain/instant submitted-at)
        deadline (domain/instant cutoff)
        late (Duration/between deadline submitted)
        tier (cond (not (.isAfter submitted deadline)) :on-time
                   (not (pos? (.compareTo late (Duration/ofDays 14)))) :within-two-weeks
                   :else :after-two-weeks)
        penalty (case tier :on-time 0M :within-two-weeks penalty-within penalty-after)
        scaled (* (/ (rationalize (domain/decimal score)) (rationalize source-max))
                  (rationalize destination-max))
        final (max 0 (min (rationalize destination-max)
                          (* scaled (- 1 (/ (rationalize penalty) 100)))))
        ;; Even a maximum with more than two decimals must not be exceeded by rounding.
        cap (.setScale ^BigDecimal destination-max 2 RoundingMode/FLOOR)
        grade (domain/points (min cap (domain/decimal (round-points final))))]
    {:scaled (round-points scaled) :grade grade
     :deduction (round-points (max 0 (- scaled final)))
     :penalty penalty :tier tier :seconds-late (max 0 (.getSeconds late))}))

(defn effective-cutoff
  "Individual lock overrides take priority; other applicable locks use the most lenient date.
   Unknown group/differentiation overrides are deliberately not guessed."
  [assignment student local-cutoff]
  (let [overrides (:overrides assignment)
        individual (filter #(some #{(:id student)} (:student_ids %)) overrides)
        sections (filter #((or (:section-ids student) #{}) (:course_section_id %)) overrides)
        course (filter #(and (some? (:course_id %))
                             (= (:course_id assignment) (:course_id %))
                             (not (:course_section_id %)) (not (:student_ids %))
                             (not (:group_id %))) overrides)
        applicable (concat individual sections course)
        individual-lock (filter #(contains? % :lock_at) individual)
        locks (filter #(contains? % :lock_at) applicable)
        unsupported? (some #(or (:group_id %) (:unassign_item %) (:context_module_id %)) overrides)
        cutoff (cond
                 (seq individual-lock) (:lock_at (first individual-lock))
                 (seq locks) (when-not (some #(nil? (:lock_at %)) locks)
                               (last (sort-by domain/instant (map :lock_at locks))))
                 :else (:lock_at assignment))]
    (cond
      (> (count individual-lock) 1) {:reason :ambiguous-cutoff}
      unsupported? {:reason :unsupported-availability-overrides}
      (and (:only_visible_to_overrides assignment) (empty? applicable)) {:reason :not-assigned}
      (or cutoff local-cutoff) {:cutoff (str (domain/instant (or cutoff local-cutoff)))
                               :cutoff-source (if cutoff :canvas :local)}
      :else {:reason :missing-cutoff})))

(defn- completed? [attempt]
  (and (some? (:score attempt))
       (not (:excused attempt))
       (not (false? (:grade_matches_current_submission attempt)))
       (not (#{"pending_review" "untaken" "preview" "settings_only" "pending"}
              (:workflow_state attempt)))))

(defn select-attempt
  "Return a score and timestamp from the same highest graded attempt.
   Never pair the aggregate quiz score with the most recent quiz timestamp."
  [submission quiz-attempts classic-quiz?]
  (let [history (:submission_history submission)
        records (concat history quiz-attempts
                        (when-not classic-quiz? [submission]))
        ;; Later records replace revisions of the same attempt. Quiz API records
        ;; override history snapshots and the current non-quiz record overrides history.
        attempts (vals (reduce (fn [acc a]
                                 (if (and a (:attempt a))
                                   (assoc acc (:attempt a)
                                          (assoc a :submitted_at (or (:finished_at a) (:submitted_at a))))
                                   acc)) {} records))
        graded (filter completed? attempts)
        maximum-attempt (apply max 0 (keep :attempt (conj (vec attempts) submission)))
        missing-history? (and (> maximum-attempt 1)
                              (< (count (set (keep :attempt attempts))) maximum-attempt))]
    (cond
      (:excused submission) {:reason :source-excused}
      missing-history? {:reason :incomplete-attempt-history}
      (some #(nil? (:submitted_at %)) graded) {:reason :missing-submission-time}
      (seq graded)
      (try
        {:attempt (first (sort (fn [a b]
                                (let [by-score (compare (domain/decimal (:score b))
                                                        (domain/decimal (:score a)))]
                                  (if (zero? by-score)
                                    (compare (domain/instant (:submitted_at a))
                                             (domain/instant (:submitted_at b)))
                                    by-score))) graded))}
        (catch Exception _ {:reason :invalid-attempt-data}))
      (and submission (= "unsubmitted" (:workflow_state submission))
           (nil? (:score submission))
           (every? #(and (= "unsubmitted" (:workflow_state %))
                         (nil? (:score %)) (nil? (:submitted_at %))) history)
           (empty? quiz-attempts)) {:reason :unsubmitted}
      :else {:reason :pending-or-unavailable-grade})))

(defn destination-state [submission]
  (select-keys submission [:score :entered_score :grade :excused :late_policy_status
                           :points_deducted :attempt :submitted_at :graded_at
                           :grade_matches_current_submission :assignment_visible :posted_at]))

(defn comment-present? [submission operation-id]
  (some #(str/includes? (or (:comment %) "") (str "[canvaslms:" operation-id "]"))
        (:submission_comments submission)))

(defn applied? [row submission]
  (and (some? (:score submission))
       (= (domain/decimal (:grade row)) (domain/decimal (:score submission)))
       (= "none" (:late_policy_status submission))
       (or (nil? (:comment row)) (comment-present? submission (:operation-id row)))))

(defn- local-time [timestamp timezone]
  (str (.atZone ^Instant (domain/instant timestamp) (ZoneId/of timezone))))

(defn- penalty-comment [mapping row timezone]
  (str "Calificación transferida desde AWS Academy (" (:source-name mapping) "). "
       "Nota original: " (domain/points (:source-score row)) "/" (domain/points (:source-max mapping))
       "; equivalencia: " (:scaled row) "/" (domain/points (:destination-max mapping)) ". "
       "Disponible hasta: " (local-time (:cutoff row) timezone) ". "
       "Entrega: " (local-time (:submitted-at row) timezone) ". "
       "Penalización por atraso " (if (= :within-two-weeks (:tier row))
                                    "de hasta dos semanas (inclusive)" "de más de dos semanas")
       ": " (:penalty row) "% de la nota obtenida (" (:deduction row) " puntos). "
       "Nota final: " (:grade row) "/" (domain/points (:destination-max mapping)) "."))

(defn propose-row
  [{:keys [mapping destination match source-submission destination-submission quiz-attempts
           classic-quiz? now timezone]}]
  (let [student (:av match)
        cutoff-info (effective-cutoff destination student (:local-cutoff mapping))
        selection (when (= :matched (:status match))
                    (select-attempt source-submission quiz-attempts classic-quiz?))
        cutoff (:cutoff cutoff-info)
        zero? (and (= :unsubmitted (:reason selection)) cutoff
                   (.isAfter ^Instant (domain/instant now) (domain/instant cutoff)))
        reason (cond
                 (not= :matched (:status match)) (:status match)
                 (:excused destination-submission) :destination-excused
                 (false? (:assignment_visible destination-submission)) :not-assigned
                 (:reason cutoff-info) (:reason cutoff-info)
                 (and (:reason selection) (not zero?))
                 (if (= :unsubmitted (:reason selection)) :not-yet-closed (:reason selection)))
        attempt (:attempt selection)
        calculation (cond reason nil
                          zero? {:grade "0.00" :scaled "0.00" :deduction "0.00" :penalty 0M :tier :missing}
                          :else (calculate mapping (:score attempt) (:submitted_at attempt) cutoff))
        row (merge {:user-id (:id student) :aws-user-id (get-in match [:aws :id])
                    :name (:name student) :email (:email match)
                    :cutoff cutoff :cutoff-source (:cutoff-source cutoff-info)
                    :attempt (:attempt attempt) :source-score (:score attempt)
                    :submitted-at (:submitted_at attempt)
                    :current-grade (:score destination-submission)
                    :destination-state (destination-state destination-submission)} calculation)
        operation-id (fingerprint [(:id mapping) (:version mapping) (:user-id row)
                                   (:aws-user-id row) (:attempt row) (:source-score row)
                                   (:submitted-at row) cutoff calculation timezone])
        text (cond
               reason nil
               zero? (str "No se registra una entrega en AWS Academy después de la fecha límite ("
                          (local-time cutoff timezone) "). Nota asignada: 0.00/"
                          (domain/points (:destination-max mapping)) ".")
               (pos? (:penalty row)) (penalty-comment mapping row timezone))
        row (assoc row :operation-id operation-id
                       :comment (when text (str text " [canvaslms:" operation-id "]")))
        unchanged? (and (not reason)
                        (= (domain/decimal (:grade row))
                           (domain/decimal (:score destination-submission)))
                        (or (nil? (:comment row)) (comment-present? destination-submission operation-id)))]
    (assoc row :action (if (or reason unchanged?) :skip :write)
               :reason (or reason (when unchanged? :unchanged))
               :overwrite? (and (= :write (if (or reason unchanged?) :skip :write))
                                (some? (:score destination-submission))
                                (not= (domain/decimal (:grade row))
                                      (domain/decimal (:score destination-submission)))))))
