(ns canvaslms.cli.main
  (:refer-clojure :exclude [run!])
  (:require [canvaslms.cli.commands :as commands]
            [canvaslms.cli.terminal :as terminal]
            [canvaslms.cli.ui :as ui]
            [canvaslms.config :as config]
            [canvaslms.model.db :as db]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]])
  (:import (java.io RandomAccessFile)
           (java.nio.channels OverlappingFileLockException)))

(def options [["-h" "--help" "Show usage"]
              [nil "--dry-run" "Preview grade changes without writing to Canvas"]])

(defn usage [summary]
  (str "Canvas grade transfer\n\n"
       "  lein cli register          Match course rosters by login ID\n"
       "  lein cli homework          Create or edit a homework mapping\n"
       "  lein cli grade             Preview and confirm grade changes\n"
       "  lein cli grade --dry-run   Preview only\n\n"
       "Pickers: type a name or ID; Tab/Shift+Tab or arrows navigate; Enter selects.\n"
       "Esc/Ctrl+C cancels a picker. Other prompts accept q to cancel.\n"
       "Confirmations default to No. lein run and redirected input retain plain ID prompts.\n\n" summary))

(defn- with-database [configuration f]
  (let [path (:db-path configuration)]
    (io/make-parents path)
    ;; A process lock covers previews as well as writes, avoiding local concurrent runs.
    (with-open [file (RandomAccessFile. (str path ".lock") "rw")
                channel (.getChannel file)]
      (let [lock (try (.tryLock channel) (catch OverlappingFileLockException _ nil))]
        (when-not lock
          (throw (ex-info "Another canvaslms process is using this database." {:type :busy})))
        (with-open [_held lock
                    database (db/open! path)]
          (f (assoc configuration :db database)))))))

(defn run! [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args canvaslms.cli.main/options)
        command (first arguments)]
    (cond
      (:help options) (do (println (usage summary)) 0)
      (or errors (> (count arguments) 1)
          (not (#{"register" "homework" "grade"} command))
          (and (:dry-run options) (not= command "grade")))
      (do (when errors (ui/message :red (str/join "\n" errors)))
          (println (usage summary)) (if (empty? args) 0 2))
      :else
      (try
        (with-database (config/load-config)
          (fn [ctx]
            (terminal/with-session!
              #(case command
                 "register" (commands/register! ctx)
                 "homework" (commands/homework! ctx)
                 "grade" (commands/grade! ctx (:dry-run options))))))
        0
        (catch clojure.lang.ExceptionInfo e
          (if (= :cancelled (:type (ex-data e)))
            (do (ui/message :yellow "Cancelled. No unconfirmed changes were applied.") 0)
            (do (ui/message :red (.getMessage e)) 1)))
        (catch InterruptedException _
          (.interrupt (Thread/currentThread))
          (ui/message :yellow "Interrupted. Inspect the saved run before retrying.")
          130)
        (catch Exception _
          (ui/message :red "Operation failed. Check configuration, database access, and saved run history before retrying.")
          1)))))
