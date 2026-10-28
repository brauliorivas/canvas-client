(ns canvaslms.api.client-test
  (:require [canvaslms.api.canvas :as canvas]
            [canvaslms.api.client :as client]
            [clojure.test :refer [deftest is]]))

(def api {:url "https://canvas.example.edu" :token "secret-test-token"})

(deftest pagination-and-decimals
  (let [calls (atom [])]
    (binding [client/*request* (fn [request]
                                (swap! calls conj request)
                                (if (= 1 (count @calls))
                                  {:status 200 :headers {"LiNk" "<https://canvas.example.edu/api/v1/courses?opaque=abc>; rel=\"next\""}
                                   :body "[{\"id\":1,\"score\":12.345}]"}
                                  {:status 200 :body "[{\"id\":2}]"}))]
      (let [result (client/paged! api "/courses")]
        (is (= [1 2] (mapv :id result)))
        (is (instance? java.math.BigDecimal (:score (first result))))
        (is (= "https://canvas.example.edu/api/v1/courses?opaque=abc" (:url (second @calls))))
        (is (= {} (:query-params (second @calls))))
        (is (= "Bearer secret-test-token" (get-in @calls [0 :headers "Authorization"])))))))

(deftest refuse-external-links-and-cycles
  (doseq [url ["https://evil.example.edu/api/v1/courses"
               "https://user@canvas.example.edu/api/v1/courses"
               "https://canvas.example.edu/other"]]
    (binding [client/*request* (fn [_] (throw (AssertionError. "Must not send credentials")))]
      (is (thrown? clojure.lang.ExceptionInfo (client/get! api url {})))))
  (binding [client/*request* (fn [_] {:status 200 :body "[]"
                                    :headers {"link" "<https://canvas.example.edu/api/v1/courses>; rel=\"next\""}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cycle" (client/paged! api "/courses")))))

(deftest retry-only-safe-operations
  (let [calls (atom 0)]
    (binding [client/*sleep* (fn [_])
              client/*request* (fn [_] (if (= 1 (swap! calls inc))
                                        {:status 429 :headers {"Retry-After" "1"}}
                                        {:status 200 :body "[]"}))]
      (is (= [] (client/paged! api "/courses")))
      (is (= 2 @calls))))
  (let [calls (atom 0)]
    (binding [client/*sleep* (fn [_])
              client/*request* (fn [_] (swap! calls inc) (throw (Exception. "sensitive details")))]
      (try (client/request! api :put "/courses/1/assignments/2/submissions/3" {})
           (is false)
           (catch clojure.lang.ExceptionInfo e
             (is (= :unknown-write (:type (ex-data e))))
             (is (not (re-find #"sensitive|secret-test-token" (.getMessage e))))))
      (is (= 1 @calls))))
  (binding [client/*request* (fn [_] {:status 403 :body "secret-test-token"})]
    (try (client/get! api "/courses" {})
         (is false)
         (catch clojure.lang.ExceptionInfo e (is (= :authentication (:type (ex-data e))))))))

(deftest grade-and-comment-in-one-request
  (let [request (atom nil)]
    (binding [client/*request* (fn [r] (reset! request r) {:status 200 :body "{}"})]
      (canvas/update-grade! api 1 2 3 "12.80" "Penalización")
      (is (= :put (:method @request)))
      (is (= "12.80" (get-in @request [:form-params "submission[posted_grade]"])))
      (is (= "Penalización" (get-in @request [:form-params "comment[text_comment]"])))
      (is (= "none" (get-in @request [:form-params "submission[late_policy_status]"])))
      (is (false? (:follow-redirects @request))))))

(deftest roster-deduplicates-sections
  (with-redefs [client/paged! (fn [_ path _]
                               (if (.endsWith path "/users")
                                 [{:id 1 :name "Student" :login_id "s@example.edu"}
                                  {:id 1 :name "Student" :login_id "s@example.edu"}]
                                 [{:user_id 1 :course_section_id 5}
                                  {:user_id 1 :course_section_id 6}]))]
    (let [students (canvas/roster api 10)]
      (is (= 1 (count students)))
      (is (= #{5 6} (:section-ids (first students)))))))
