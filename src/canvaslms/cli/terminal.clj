(ns canvaslms.cli.terminal
  "One command-scoped terminal session for the picker and ordinary prompts."
  (:require [canvaslms.cli.picker :as picker]
            [clojure.string :as str])
  (:import (org.jline.keymap BindingReader KeyMap)
           (org.jline.reader EndOfFileException LineReaderBuilder UserInterruptException)
           (org.jline.terminal Size Terminal TerminalBuilder Terminal$Signal Terminal$SignalHandler)
           (org.jline.utils AttributedString AttributedStyle Display InfoCmp$Capability)))

(def ^:dynamic *session* nil)

(defn- open-terminal []
  ;; Do not open /dev/tty behind a pipe or compete with Leiningen's stdin forwarding.
  (when (and (System/console) (not= "dumb" (System/getenv "TERM")))
    (try
      (let [terminal (-> (TerminalBuilder/builder) (.system true) (.dumb false) (.build))]
        (if (and (not (str/starts-with? (.getType terminal) "dumb"))
                 (.getStringCapability terminal InfoCmp$Capability/cursor_up))
          terminal
          (do (.close terminal) nil)))
      (catch Exception _ nil))))

(def ^:dynamic *open-terminal* open-terminal)

(defn cancel! [] (throw (ex-info "Cancelled." {:type :cancelled})))

(defn- restore-cursor! [^Terminal terminal]
  (.puts terminal InfoCmp$Capability/cursor_normal (object-array 0))
  (.flush terminal))

(defn with-session! [f]
  (if *session*
    (f)
    (if-let [terminal (*open-terminal*)]
      (with-open [^Terminal terminal terminal]
        (let [original (.getAttributes terminal)
              reader (-> (LineReaderBuilder/builder) (.terminal terminal) (.build))]
          (binding [*session* {:terminal terminal :reader reader}]
            (try (f)
                 (finally
                   (.setAttributes terminal original)
                   (restore-cursor! terminal))))))
      (f))))

(defn interactive? [] (some? *session*))

(defn read-line! [prompt]
  (try (.readLine ^org.jline.reader.LineReader (:reader *session*) (str prompt " "))
       (catch EndOfFileException _ (cancel!))
       (catch UserInterruptException _ (cancel!))))

(defn- safe-text [value]
  (str/replace (str (or value "—")) #"\p{Cntrl}" " "))

(defn- cell-key [column] (if (keyword? column) column (:key column)))

(defn- label [item columns]
  (str/join " | " (map #(safe-text ((cell-key %) item)) columns)))

(defn- heading [columns]
  (str/join " | " (map #(or (when (map? %) (:title %))
                             (some-> (cell-key %) name (str/replace "-" " ") str/capitalize)) columns)))

(defn- clip [text width]
  (let [value (AttributedString. (safe-text text))]
    (if (<= (.columnLength value) width) (str value)
        (str (.columnSubSequence value 0 (max 0 (dec width))) "…"))))

(defn frame
  "Build a bounded frame using terminal cell widths, including wide Unicode text."
  [title columns state width height color?]
  (let [terminal-width (max 1 width)
        width (max 1 (dec terminal-width))
        height (max 1 height)
        compact? (< height 8)
        {:keys [start end items]} (picker/viewport state (if compact? (+ height 3) height))
        input (AttributedString. (str "Search / ID: " (:query state)))
        input-width (.columnLength input)
        input-text (str (.columnSubSequence input (max 0 (- input-width width)) input-width))
        line (fn [text style] (AttributedString. (clip text width)
                                               (if color? style AttributedStyle/DEFAULT)))
        option-lines (if (seq items)
                       (map-indexed (fn [offset item]
                                      (let [selected? (= (+ start offset) (:index state))]
                                        (line (str (if selected? "> " "  ") (label item columns))
                                              (if selected? (.inverse (.bold AttributedStyle/DEFAULT))
                                                  AttributedStyle/DEFAULT)))) items)
                       [(line "  No matches — edit your search." AttributedStyle/DEFAULT)])
        footer (str (count (:matches state)) " / " (count (:entries state)) " matches"
                    (when (seq items) (str " · " (inc start) "–" end)))
        header (when-not compact?
                 [(line title (.bold AttributedStyle/DEFAULT))
                  (line input-text AttributedStyle/DEFAULT)
                  (line (str "  " (heading columns)) AttributedStyle/DEFAULT)])
        lines (cond
                (= height 1) [(line input-text AttributedStyle/DEFAULT)]
                (= height 2) [(line input-text AttributedStyle/DEFAULT) (first option-lines)]
                compact? (concat [(line input-text AttributedStyle/DEFAULT)] option-lines
                                 [(line footer AttributedStyle/DEFAULT)])
                :else (concat header option-lines [(line footer AttributedStyle/DEFAULT)
                                                   (line "Tab/↓ next · Shift+Tab/↑ previous · Enter select · Ctrl+U clear · Esc cancel"
                                                         AttributedStyle/DEFAULT)]))
        row (if compact? 0 1)
        column (min width (.columnLength (AttributedString. input-text)))]
    {:lines (vec lines) :cursor-row row :cursor-column column
     ;; JLine includes an extra position per row for delayed wrapping.
     :cursor-position (.cursorPos (Size. terminal-width height) row column)}))

(defn- keymap [^Terminal terminal]
  (let [keys (doto (KeyMap.) (.setUnicode :text) (.setNomatch :ignore) (.setAmbiguousTimeout 100))
        bind! (fn [event sequences]
                (doseq [^CharSequence sequence (remove nil? sequences)] (.bind keys event sequence)))]
    (doseq [code (range 32 127)] (bind! :text [(str (char code))]))
    (bind! :next ["\t" "\u001b[B" "\u001bOB" (KeyMap/key terminal InfoCmp$Capability/key_down)])
    (bind! :previous ["\u001b[Z" "\u001b[A" "\u001bOA"
                      (KeyMap/key terminal InfoCmp$Capability/key_btab)
                      (KeyMap/key terminal InfoCmp$Capability/key_up)])
    (bind! :backspace ["\u007f" "\b"])
    (bind! :clear ["\u0015"])
    (bind! :accept ["\r" "\n"])
    (bind! :cancel ["\u001b" "\u0003" "\u0004"])
    keys))

(defn select! [title items columns]
  (let [^Terminal terminal (:terminal *session*)
        reader (BindingReader. (.reader terminal))
        keys (keymap terminal)
        display (Display. terminal false)
        cancelled? (atom false)
        resized? (atom true)
        interrupt-handler (.handle terminal Terminal$Signal/INT
                                    (reify Terminal$SignalHandler (handle [_ _] (reset! cancelled? true))))
        resize-handler (.handle terminal Terminal$Signal/WINCH
                                 (reify Terminal$SignalHandler (handle [_ _] (reset! resized? true))))
        original (.enterRawMode terminal)
        color? (str/blank? (System/getenv "NO_COLOR"))
        result (try
                 (loop [state (picker/create items columns) dirty? true]
                   (when (or @cancelled? (:cancelled? state)) (cancel!))
                   (if-let [chosen (:chosen state)]
                     chosen
                     (do
                       (when (or dirty? @resized?)
                         (reset! resized? false)
                         (let [size (.getSize terminal)
                               width (max 1 (.getColumns size))
                               height (max 1 (.getRows size))
                               {:keys [lines cursor-position]} (frame title columns state width height color?)]
                           (.resize display height width)
                           (.update display lines (int cursor-position))))
                       (let [next-character (.peekCharacter reader 100)]
                         (cond
                           (= -1 next-character) (cancel!)
                           (= -2 next-character) (recur state false)
                           :else (let [event (.readBinding reader keys)
                                       next-state (picker/keypress state (or event :cancel) (.getLastBinding reader))]
                                   (recur next-state (not= state next-state))))))))
                 (finally
                   (try (.update display [] 0)
                        (finally
                          (.setAttributes terminal original)
                          (.handle terminal Terminal$Signal/INT interrupt-handler)
                          (.handle terminal Terminal$Signal/WINCH resize-handler)
                          (restore-cursor! terminal)))))]
    (.println (.writer terminal) (clip (str (safe-text title) ": " (label result columns))
                                      (max 1 (dec (.getWidth terminal)))))
    (.flush terminal)
    result))
