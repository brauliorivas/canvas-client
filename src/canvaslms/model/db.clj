(ns canvaslms.model.db
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.time Instant)
           (java.util UUID)))

(def opts {:builder-fn rs/as-unqualified-lower-maps})
(defn query [db sql] (jdbc/execute! db sql opts))
(defn one [db sql] (first (query db sql)))
(defn- unpack [row] (when row (merge (edn/read-string (:data row)) (dissoc row :data))))

(defn open!
  "One connection keeps foreign keys and transactions connection-scoped."
  [path]
  (when-not (= path ":memory:") (io/make-parents path))
  (let [db (jdbc/get-connection {:dbtype "sqlite" :dbname path})]
    (try
      (query db ["PRAGMA foreign_keys = ON"])
      (query db ["PRAGMA busy_timeout = 5000"])
      (query db ["CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY)"])
      (when-not (one db ["SELECT version FROM schema_migrations WHERE version = 1"])
        (jdbc/with-transaction [tx db]
          (doseq [sql (str/split (slurp (io/resource "migrations/001-initial.sql")) #"--;;")]
            (when-not (str/blank? sql) (query tx [sql])))
          (query tx ["INSERT INTO schema_migrations(version) VALUES (1)"])))
      db
      (catch Exception e (.close db) (throw e)))))

(defn registrations [db av-url aws-url]
  (mapv unpack (query db ["SELECT * FROM registrations WHERE av_url=? AND aws_url=? ORDER BY id"
                         av-url aws-url])))

(defn registration [db id] (unpack (one db ["SELECT * FROM registrations WHERE id=?" id])))

(defn save-registration! [db {:keys [av-url aws-url av-course aws-course] :as data} students members matches]
  (jdbc/with-transaction [tx db]
    (query tx ["INSERT INTO registrations(av_url,aws_url,av_course_id,aws_course_id,data) VALUES (?,?,?,?,?)
               ON CONFLICT(av_url,aws_url,av_course_id,aws_course_id) DO UPDATE SET data=excluded.data"
               av-url aws-url (:id av-course) (:id aws-course) (pr-str data)])
    (let [id (:id (one tx ["SELECT id FROM registrations WHERE av_url=? AND aws_url=? AND av_course_id=? AND aws_course_id=?"
                           av-url aws-url (:id av-course) (:id aws-course)]))]
      (query tx ["DELETE FROM students WHERE av_url=? AND course_id=?" av-url (:id av-course)])
      (doseq [student students]
        (query tx ["INSERT INTO students(av_url,course_id,user_id,data) VALUES (?,?,?,?)"
                   av-url (:id av-course) (:id student) (pr-str student)]))
      (query tx ["DELETE FROM memberships WHERE registration_id=?" id])
      (query tx ["DELETE FROM matches WHERE registration_id=?" id])
      (doseq [member members]
        (query tx ["INSERT INTO memberships(registration_id,user_id,data) VALUES (?,?,?)"
                   id (:id member) (pr-str member)]))
      (doseq [match matches]
        (query tx ["INSERT INTO matches(registration_id,av_user_id,status,data) VALUES (?,?,?,?)"
                   id (get-in match [:av :id]) (name (:status match)) (pr-str match)]))
      (registration tx id))))

(defn mappings [db av-url aws-url]
  (mapv unpack (query db ["SELECT * FROM mappings WHERE av_url=? AND aws_url=? ORDER BY id" av-url aws-url])))

(defn graded-mapping-ids
  "Mappings with any confirmed grading run, across all versions and run outcomes."
  [db av-url aws-url]
  (into #{} (map :mapping_id)
        (query db ["SELECT DISTINCT runs.mapping_id FROM runs
                    JOIN mappings ON mappings.id=runs.mapping_id
                    WHERE mappings.av_url=? AND mappings.aws_url=?" av-url aws-url])))

(defn mapping [db id] (unpack (one db ["SELECT * FROM mappings WHERE id=?" id])))

(defn save-mapping! [db {:keys [registration-id av-url aws-url av-course-id destination-id] :as data}]
  (jdbc/with-transaction [tx db]
    (let [old (one tx ["SELECT id,version FROM mappings WHERE av_url=? AND aws_url=? AND av_course_id=? AND destination_id=?"
                       av-url aws-url av-course-id destination-id])
          version (inc (or (:version old) 0))
          value (assoc data :version version)]
      (query tx ["INSERT INTO mappings(registration_id,av_url,aws_url,av_course_id,destination_id,version,data)
                 VALUES (?,?,?,?,?,?,?) ON CONFLICT(av_url,aws_url,av_course_id,destination_id)
                 DO UPDATE SET registration_id=excluded.registration_id,version=excluded.version,data=excluded.data"
                 registration-id av-url aws-url av-course-id destination-id version (pr-str value)])
      (let [id (:id (one tx ["SELECT id FROM mappings WHERE av_url=? AND aws_url=? AND av_course_id=? AND destination_id=?"
                             av-url aws-url av-course-id destination-id]))]
        (query tx ["INSERT INTO mapping_versions(mapping_id,version,data) VALUES (?,?,?)" id version (pr-str value)])
        (mapping tx id)))))

(defn start-run! [db mapping-id proposal approved-ids]
  (let [id (str (UUID/randomUUID))]
    (jdbc/with-transaction [tx db]
      (query tx ["INSERT INTO runs(id,mapping_id,status,created_at,data) VALUES (?,?,?,?,?)"
                 id mapping-id "running" (str (Instant/now)) (pr-str proposal)])
      (doseq [row (:rows proposal)]
        (query tx ["INSERT INTO run_rows(run_id,user_id,operation_id,status,proposal) VALUES (?,?,?,?,?)"
                   id (:user-id row) (:operation-id row)
                   (if (approved-ids (:user-id row)) "pending" "skipped") (pr-str row)])))
    id))

(defn record-result! [db run-id row status result]
  (query db ["UPDATE run_rows SET status=?,result=? WHERE run_id=? AND user_id=?"
             (name status) (pr-str result) run-id (:user-id row)]))

(defn finish-run! [db run-id status]
  (query db ["UPDATE runs SET status=? WHERE id=?" (name status) run-id]))

(defn operation-history [db operation-id]
  (mapv (fn [row] (-> row (update :proposal edn/read-string)
                          (update :result #(when % (edn/read-string %)))))
        (query db ["SELECT * FROM run_rows WHERE operation_id=? ORDER BY rowid DESC" operation-id])))
