(ns canvaslms.core.matching
  (:require [canvaslms.model.domain :as domain]))

(defn match-students [av-students aws-students]
  (let [av (group-by (comp domain/email-key :login_id) av-students)
        aws (group-by (comp domain/email-key :login_id) aws-students)
        hidden-aws? (some #(nil? (domain/email-key (:login_id %))) aws-students)]
    (mapv (fn [student]
            (let [email (domain/email-key (:login_id student))
                  candidates (get aws email)
                  status (cond
                           (nil? email) :missing-login
                           (> (count (get av email)) 1) :ambiguous-av-login
                           (> (count candidates) 1) :ambiguous-aws-login
                           (= 1 (count candidates)) :matched
                           hidden-aws? :aws-logins-unavailable
                           :else :absent)]
              (cond-> {:av student :email email :status status}
                (= status :matched) (assoc :aws (first candidates)))))
          av-students)))
