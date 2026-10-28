(ns canvaslms.api.client
  (:require [cheshire.core :as json]
            [cheshire.parse :as json-parse]
            [clj-http.client :as http]
            [clojure.string :as str])
  (:import (java.net URI)))

(def ^:dynamic *request* http/request)
(def ^:dynamic *sleep* #(Thread/sleep %))

(defn header [response header-name]
  (some (fn [[k v]] (when (= (str/lower-case (name k))
                             (str/lower-case header-name)) v)) (:headers response)))

(defn- same-origin? [base target]
  (let [a (URI. base) b (URI. target)]
    (and (= (.getScheme a) (.getScheme b))
         (= (.getHost a) (.getHost b))
         (= (.getPort a) (.getPort b))
         (nil? (.getUserInfo b)) (nil? (.getFragment b)))))

(defn request!
  "Only GET and explicit 429 responses are retried; never retry an ambiguous write."
  [client method path params]
  (let [url (if (str/starts-with? path "https://") path (str (:url client) "/api/v1" path))]
    (when-not (and (same-origin? (:url client) url)
                   (str/starts-with? (.getPath (URI. url)) "/api/v1/"))
      (throw (ex-info "Refusing a Canvas link outside the configured API origin."
                      {:type :unsafe-link})))
    (loop [attempt 0]
      (let [response (try
                       (*request* (merge {:method method :url url
                                          :headers {"Authorization" (str "Bearer " (:token client))
                                                    "Accept" "application/json"}
                                          :throw-exceptions false :follow-redirects false
                                          :as :text :conn-timeout 15000 :socket-timeout 30000}
                                         (if (= method :get)
                                           {:query-params params}
                                           {:form-params params})))
                       (catch InterruptedException e (.interrupt (Thread/currentThread)) (throw e))
                       (catch Exception _ {:transport-error true}))
            status (:status response)
            retry? (and (< attempt 3)
                        (or (= status 429)
                            (and (= method :get)
                                 (or (:transport-error response)
                                     (#{500 502 503 504} status)))))]
        (if retry?
          (do (*sleep* (long (* 1000 (min 30 (max (Math/pow 2 attempt)
                                                  (try (Double/parseDouble (str (header response "retry-after")))
                                                       (catch Exception _ 0)))))))
              (recur (inc attempt)))
          (cond
            (:transport-error response)
            (throw (ex-info (if (= method :get) "Canvas could not be reached."
                                "Canvas write outcome is unknown; reconcile it before retrying.")
                            {:type (if (= method :get) :network :unknown-write)}))
            (not (<= 200 (or status 0) 299))
            (throw (ex-info (str "Canvas request failed (HTTP " status ")."
                                 (when (#{401 403} status) " Check token permissions."))
                            {:type (if (#{401 403} status) :authentication
                                       (if (and (not= method :get) (<= 500 (or status 0)))
                                         :unknown-write :http))
                             :status status}))
            :else
            (assoc response :body
                   (try (if (str/blank? (:body response)) nil
                            (binding [json-parse/*use-bigdecimals?* true]
                              (json/parse-string-strict (:body response) true)))
                        (catch Exception _
                          (throw (ex-info "Canvas returned an unreadable response."
                                          {:type (if (= method :get) :response :unknown-write)})))))))))))

(defn get! [client path params] (:body (request! client :get path params)))

(defn paged!
  "Follow opaque Link URLs, rejecting cycles and cross-origin links."
  ([client path] (paged! client path {} nil))
  ([client path params] (paged! client path params nil))
  ([client path params collection-key]
   (loop [next-path path query (merge {"per_page" 100} params) seen #{} result []]
     (when (seen next-path)
       (throw (ex-info "Canvas pagination contains a cycle." {:type :response})))
     (let [response (request! client :get next-path query)
           body (:body response)
           rows (if collection-key (get body collection-key) body)
           _ (when-not (sequential? rows)
               (throw (ex-info "Expected a Canvas list response." {:type :response})))
           link (str (header response "link"))
           next-url (some (fn [part]
                            (second (re-find #"<([^>]+)>;\s*rel=\"next\"" part)))
                          (str/split link #",\s*(?=<)"))
           accumulated (into result rows)]
       (if next-url (recur next-url {} (conj seen next-path) accumulated) accumulated)))))
