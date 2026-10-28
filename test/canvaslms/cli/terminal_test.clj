(ns canvaslms.cli.terminal-test
  (:require [canvaslms.cli.picker :as picker]
            [canvaslms.cli.terminal :as terminal]
            [canvaslms.cli.ui :as ui]
            [clj-commons.ansi :as ansi]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream)
           (java.nio.charset StandardCharsets)
           (org.jline.terminal Size Terminal$Signal Terminal$SignalHandler)
           (org.jline.terminal.impl DumbTerminal)))

(def items [{:id 1 :name "Course 12"}
            {:id 12 :name "Álgebra"}
            {:id 27 :name "Quiz A"}
            {:id 28 :name "Quiz B"}])

(defn interact [input f]
  (let [output (ByteArrayOutputStream.)
        t (DumbTerminal. "test" "xterm" (ByteArrayInputStream. (.getBytes input StandardCharsets/UTF_8))
                         output StandardCharsets/UTF_8)
        result (atom nil)
        restored? (atom false)
        signals-restored? (atom false)
        sentinel (reify Terminal$SignalHandler (handle [_ _]))]
    (.setSize t (Size. 80 20))
    (.handle t Terminal$Signal/INT sentinel)
    (.handle t Terminal$Signal/WINCH sentinel)
    (binding [terminal/*open-terminal* (constantly t)]
      (terminal/with-session!
        #(let [original (str (.getAttributes t))]
           (try
             (reset! result {:value (f t)})
             (catch clojure.lang.ExceptionInfo e (reset! result {:error (:type (ex-data e))}))
             (finally
               (reset! restored? (= original (str (.getAttributes t))))
               (reset! signals-restored?
                       (and (identical? sentinel (.handle t Terminal$Signal/INT sentinel))
                            (identical? sentinel (.handle t Terminal$Signal/WINCH sentinel)))))))))
    (assoc @result :output (.toString output "UTF-8")
                   :restored? @restored? :signals-restored? @signals-restored?)))

(deftest live-selection-through-the-existing-interface
  (doseq [[input id] [["\t\r" 12]
                     ["12\r" 12]
                     ["12\t\r" 1]
                     ["algebra\r" 12]
                     ["quiz\t\r" 28]
                     ["quiz\u001b[Z\r" 28]
                     ["\u001b[B\r" 12]
                     ["q\r" 27]
                     ["unmatched\r\u001512\r" 12]
                     ["quiz\t\u007f\r" 27]]]
    (let [result (interact input (fn [_] (ui/select! "Courses" items [:id :name])))]
      (is (= id (get-in result [:value :id])) (pr-str input))
      (is (:restored? result))
      (is (:signals-restored? result)))))

(deftest cancellation-restores-terminal
  (doseq [input ["\u0003" "\u0004" "\u001b" ""]]
    (let [result (interact input (fn [_] (ui/select! "Courses" items [:id :name])))]
      (is (= :cancelled (:error result)) (pr-str input))
      (is (:restored? result))
      (is (:signals-restored? result)))))

(deftest display-failure-restores-terminal
  (with-redefs [terminal/frame (fn [& _] (throw (ex-info "Render failure" {:type :render-test})))]
    (let [result (interact "" (fn [_] (ui/select! "Courses" items [:id :name])))]
      (is (= :render-test (:error result)))
      (is (:restored? result))
      (is (:signals-restored? result)))))

(deftest picker-and-ordinary-prompts-share-input
  (let [result (interact "12\ryes\rquiz\r"
                         (fn [_] [(ui/select! "First" items [:id :name])
                                  (ui/confirm! "Continue?")
                                  (ui/select! "Second" items [:id :name])]))]
    (is (= [12 true 27] [(get-in result [:value 0 :id])
                         (get-in result [:value 1])
                         (get-in result [:value 2 :id])]))
    (is (:restored? result))))

(deftest bounded-frames-and-wide-unicode
  (let [state (picker/create (mapv #(hash-map :id % :name (str "Álgebra — 中文 " (apply str (repeat 80 "x"))))
                                  (range 100)) [:id :name])]
    (doseq [[width height] [[80 24] [30 8] [10 4] [2 2] [1 1]]
            query ["" (apply str (repeat 100 "中"))]]
      (let [frame (terminal/frame "Courses" [:id :name] (picker/search state query)
                                  width height false)]
        (is (<= (count (:lines frame)) height))
        (is (every? #(<= (.columnLength ^org.jline.utils.AttributedString %) (max 1 (dec width)))
                    (:lines frame)))
        (is (< (:cursor-row frame) height))
        (is (= (.cursorPos (Size. width height) (:cursor-row frame) (:cursor-column frame))
               (:cursor-position frame)))))))

(deftest terminal-unavailable-keeps-the-plain-selector
  (binding [terminal/*open-terminal* (constantly nil)
            ansi/*color-enabled* false
            *in* (java.io.BufferedReader. (java.io.StringReader. "12\n"))
            *out* (java.io.StringWriter.)]
    (terminal/with-session!
      #(do (is (not (terminal/interactive?)))
           (is (= 12 (:id (ui/select! "Courses" items [:id :name]))))
           (is (not (str/includes? (str *out*) "\u001b[")))))))
