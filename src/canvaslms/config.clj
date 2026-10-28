(ns canvaslms.config
  (:require [clojure.string :as str])
  (:import (io.github.cdimascio.dotenv Dotenv)
           (java.net URI)
           (java.time ZoneId)))

(defn normalize-url [value]
  (let [s (str/replace (str/trim (or value "")) #"/+$" "")
        uri (try (URI. s) (catch Exception _ nil))]
    (when-not (and uri (= "https" (.getScheme uri)) (.getHost uri)
                   (nil? (.getUserInfo uri)) (nil? (.getQuery uri))
                   (nil? (.getFragment uri)) (str/blank? (.getPath uri)))
      (throw (ex-info "Canvas URLs must be HTTPS origins without paths or credentials."
                      {:type :configuration})))
    (str/lower-case s)))

(defn from-values
  "Read through an injected lookup function without logging credentials."
  [lookup]
  (let [required (fn [key]
                   (let [v (lookup key)]
                     (when (str/blank? v)
                       (throw (ex-info (str "Missing configuration: " key)
                                       {:type :configuration :key key})))
                     (str/trim v)))
        timezone (or (not-empty (lookup "CANVASLMS_TIMEZONE")) "America/Guayaquil")]
    (try (ZoneId/of timezone)
         (catch Exception _ (throw (ex-info "Invalid CANVASLMS_TIMEZONE."
                                            {:type :configuration}))))
    {:av {:url (normalize-url (required "AULA_VIRTUAL_URL"))
          :token (required "AULA_VIRTUAL_TOKEN")}
     :aws {:url (normalize-url (required "AWS_ACADEMY_URL"))
           :token (required "AWS_ACADEMY_TOKEN")}
     :db-path (or (not-empty (lookup "CANVASLMS_DB_PATH")) "./data/canvaslms.sqlite")
     :timezone timezone}))

(defn load-config []
  (let [dotenv (-> (Dotenv/configure) (.ignoreIfMissing) (.load))]
    (from-values #(.get dotenv %))))
