(ns canvaslms.cli.ui
  (:require [canvaslms.cli.terminal :as terminal]
            [canvaslms.model.domain :as domain]
            [clj-commons.ansi :as ansi]
            [clj-commons.format.table :as table]
            [clojure.string :as str])
  (:import (java.time Instant LocalDateTime OffsetDateTime ZoneId)
           (java.time.format DateTimeFormatter)))

(defn safe-text [value]
  (str/replace (str (or value "—")) #"[\p{Cntrl}\u001b]" " "))

(defn message [color text] (ansi/pout [color (safe-text text)]))

(defn print-table [columns rows]
  (table/print-table
   {:style table/skinny-style
    :columns (mapv (fn [column]
                     (merge {:align :left :formatter safe-text}
                            (if (keyword? column) {:key column} column))) columns)}
   rows))

(defn ask! [prompt]
  (let [line (if (terminal/interactive?)
               (terminal/read-line! prompt)
               (do (print (str prompt " ")) (flush) (read-line)))]
    (when (or (nil? line) (= "q" (str/lower-case (str/trim line))))
      (throw (ex-info "Cancelled." {:type :cancelled})))
    (str/trim line)))

(defn confirm! [prompt]
  (loop []
    (let [answer (str/lower-case (ask! (str prompt " [y/N] (q to cancel):")))]
      (cond
        (#{"y" "yes"} answer) true
        (#{"" "n" "no"} answer) false
        :else (do (message :yellow "Enter yes or no.") (recur))))))

(defn- select-id! [title items columns]
  (message :cyan title)
  (print-table columns items)
  (loop []
    (let [answer (ask! "Select an ID (q to cancel):")
          item (some #(when (= answer (str (:id %))) %) items)]
      (if item item (do (message :yellow "Choose an ID shown in the table.") (recur))))))

(defn select! [title items columns]
  (when (empty? items)
    (throw (ex-info (str "No items available: " title ".") {:type :no-data})))
  (if (terminal/interactive?)
    (terminal/select! title items columns)
    (select-id! title items columns)))

(defn value! [prompt default parse]
  (loop []
    (let [answer (ask! (str prompt (when (some? default) (str " [" default "]")) ":"))
          result (try {:value (parse (if (and (str/blank? answer) (some? default)) default answer))}
                      (catch Exception e {:error (.getMessage e)}))]
      (if-let [error (:error result)]
        (do (message :yellow error) (recur))
        (:value result)))))

(defn parse-cutoff [text timezone]
  (try
    (str (.toInstant (OffsetDateTime/parse text)))
    (catch Exception _
      (try
        (str (.toInstant (.atZone (LocalDateTime/parse text
                                                      (DateTimeFormatter/ofPattern "uuuu-MM-dd HH:mm"))
                                 (ZoneId/of timezone))))
        (catch Exception _
          (throw (ex-info "Enter ISO 8601 with an offset, or YYYY-MM-DD HH:mm in your configured timezone."
                          {:type :validation})))))))

(defn format-time [text timezone]
  (when text (str (.atZone ^Instant (domain/instant text) (ZoneId/of timezone)))))
