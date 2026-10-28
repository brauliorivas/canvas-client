(ns canvaslms.cli.commands-test
  (:require [canvaslms.cli.commands :as commands]
            [canvaslms.cli.main :as main]
            [canvaslms.cli.ui :as ui]
            [canvaslms.config :as config]
            [canvaslms.core.service :as service]
            [clojure.test :refer [deftest is]])
  (:import (java.io BufferedReader StringReader StringWriter)))

(defn capture [input f]
  (binding [*in* (BufferedReader. (StringReader. input)) *out* (StringWriter.)]
    (let [result (f)] {:result result :output (str *out*)})))

(def mapping {:id 1 :destination-name "Homework" :source-name "KC" :version 1})
(def preview {:mapping mapping :rows [{:user-id 1 :name "Alice" :email "a@example.edu"
                                      :current-grade 10M :grade "12.80" :scaled "16.00" :deduction "3.20"
                                      :action :write :overwrite? true}
                                     {:user-id 2 :name "Bob" :email "b@example.edu"
                                      :grade "20.00" :scaled "20.00" :action :write :overwrite? false}]})

(deftest confirmation-and-cancellation
  (is (false? (:result (capture "\n" #(ui/confirm! "Apply?")))))
  (is (true? (:result (capture "invalid\nyes\n" #(ui/confirm! "Apply?")))))
  (is (thrown? clojure.lang.ExceptionInfo (capture "" #(ui/ask! "Select")))))

(deftest invalid-id-reprompts
  (is (= {:id 5 :name "Course"}
         (:result (capture "nonsense\n6\n5\n"
                           #(ui/select! "Courses" [{:id 5 :name "Course"}] [:id :name]))))))

(deftest dry-run-and-declined-batch-never-apply
  (let [calls (atom [])]
    (with-redefs [service/mappings (fn [_] [mapping])
                  service/graded-mapping-ids (fn [_] #{})
                  service/grade-preview (fn [_ _] preview)
                  service/apply-grades! (fn [& args] (swap! calls conj args))]
      (capture "1\n" #(commands/grade! {:timezone "UTC"} true))
      (capture "1\ny\nn\n" #(commands/grade! {:timezone "UTC"} false))
      (is (empty? @calls))
      (is (thrown? clojure.lang.ExceptionInfo
                   (capture "1\nq\n" #(commands/grade! {:timezone "UTC"} false))))
      (is (empty? @calls)))))

(deftest each-overwrite-needs-approval-before-batch
  (let [approval (atom nil)]
    (with-redefs [service/mappings (fn [_] [mapping])
                  service/graded-mapping-ids (fn [_] #{})
                  service/grade-preview (fn [_ _] preview)
                  service/apply-grades! (fn [_ _ a] (reset! approval a)
                                          {:run-id "run" :status :complete :results []})]
      (capture "1\nn\ny\n" #(commands/grade! {:timezone "UTC"} false))
      (is (= {:confirmed? true :student-ids #{2}} @approval)))))

(deftest grade-menu-shows-history-without-changing-selected-mapping
  (let [other (assoc mapping :id 2 :destination-name "Another homework")
        selected (atom nil)]
    (with-redefs [service/mappings (fn [_] [mapping other])
                  service/graded-mapping-ids (fn [_] #{1})
                  service/grade-preview (fn [_ value] (reset! selected value) preview)]
      (let [{:keys [output]} (capture "2\n" #(commands/grade! {:timezone "UTC"} true))]
        (is (re-find #"Graded" output))
        (is (re-find #"(?m)^1\s+\|\s+Yes\s+\|" output))
        (is (re-find #"(?m)^2\s+\|\s+No\s+\|" output))
        (is (identical? other @selected))))))

(deftest help-does-not-require-credentials
  (with-redefs [config/load-config (fn [] (throw (AssertionError. "Should not read config")))]
    (is (= 0 (:result (capture "" #(main/run! ["--help"])))))
    (is (= 2 (:result (capture "" #(main/run! ["register" "--dry-run"])))))))

(deftest environment-config-and-timezone
  (let [values {"AULA_VIRTUAL_URL" "https://school.example.edu/" "AULA_VIRTUAL_TOKEN" "a"
                "AWS_ACADEMY_URL" "https://aws.example.edu" "AWS_ACADEMY_TOKEN" "b"}]
    (is (= "https://school.example.edu" (get-in (config/from-values values) [:av :url])))
    (is (= "America/Guayaquil" (:timezone (config/from-values values))))
    (is (thrown? clojure.lang.ExceptionInfo (config/from-values (dissoc values "AWS_ACADEMY_TOKEN"))))
    (is (thrown? clojure.lang.ExceptionInfo (config/normalize-url "http://insecure.example.edu"))))
  (is (= "2026-01-01T05:00:00Z" (ui/parse-cutoff "2026-01-01 00:00" "America/Guayaquil"))))
