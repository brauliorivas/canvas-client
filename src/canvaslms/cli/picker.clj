(ns canvaslms.cli.picker
  "Pure search, navigation, and viewport state for every option selector."
  (:require [clojure.string :as str])
  (:import (java.text Normalizer Normalizer$Form)
           (java.util Locale)))

(defn normalize [value]
  (-> (Normalizer/normalize (str (or value "")) Normalizer$Form/NFKD)
      (.toLowerCase Locale/ROOT)
      (str/replace #"[^\p{L}\p{N}]" "")))

(defn- column-key [column] (if (keyword? column) column (:key column)))

(defn create [items columns]
  {:entries (mapv (fn [item]
                    {:item item
                     :text (str/join " " (map #(normalize ((column-key %) item)) columns))}) items)
   :query "" :matches (vec items) :index 0})

(defn search [state query]
  (let [tokens (->> (str/split query #"\s+") (map normalize) (remove str/blank?))
        exact-id (str/trim query)
        matches (->> (:entries state)
                     (filter (fn [{:keys [item text]}]
                               (or (= exact-id (str (:id item)))
                                   (every? #(str/includes? text %) tokens))))
                     (mapv :item))
        exact (filterv #(= exact-id (str (:id %))) matches)
        others (filterv #(not= exact-id (str (:id %))) matches)]
    (assoc state :query query :matches (into exact others) :index 0)))

(defn move [state direction]
  (if (seq (:matches state))
    (update state :index #(mod (+ % direction) (count (:matches state))))
    state))

(defn selected [state] (get (:matches state) (:index state)))

(defn keypress [state event text]
  (case event
    :next (move state 1)
    :previous (move state -1)
    :clear (search state "")
    :backspace (let [^String query (:query state)]
                 (if (empty? query) state
                     (search state (subs query 0 (.offsetByCodePoints query (.length query) -1)))))
    :text (search state (str (:query state) (str/replace text #"\p{Cntrl}" "")))
    :accept (if-let [item (selected state)] (assoc state :chosen item) state)
    :cancel (assoc state :cancelled? true)
    state))

(defn viewport [state height]
  (let [capacity (max 1 (min 10 (- height 5)))
        count-matches (count (:matches state))
        start (max 0 (min (- count-matches capacity)
                         (- (:index state) (quot capacity 2))))
        end (min count-matches (+ start capacity))]
    {:start start :end end :capacity capacity
     :items (subvec (:matches state) start end)}))
