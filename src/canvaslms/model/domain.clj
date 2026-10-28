(ns canvaslms.model.domain
  (:require [clojure.string :as str])
  (:import (java.math BigDecimal RoundingMode)
           (java.time Instant)))

(defn decimal [x]
  (when (some? x)
    (try (bigdec x)
         (catch Exception _ (throw (ex-info "Expected a finite decimal number."
                                            {:type :validation}))))))

(defn positive-decimal [x]
  (let [n (decimal x)]
    (when-not (and n (pos? n))
      (throw (ex-info "Maximum points must be greater than zero." {:type :validation})))
    n))

(defn percentage [x]
  (let [n (decimal x)]
    (when-not (and n (<= 0M n 100M))
      (throw (ex-info "Penalty must be between 0 and 100 percent." {:type :validation})))
    n))

(defn points [x]
  (when-let [n (decimal x)]
    (.toPlainString (.setScale ^BigDecimal n 2 RoundingMode/HALF_UP))))

(defn instant [value]
  (when value
    (if (instance? Instant value) value (Instant/parse (str value)))))

(defn email-key [login]
  (when-not (str/blank? login) (str/lower-case (str/trim login))))

(defn validate-mapping [mapping]
  (-> mapping
      (update :source-max positive-decimal)
      (update :destination-max positive-decimal)
      (update :penalty-within percentage)
      (update :penalty-after percentage)))
