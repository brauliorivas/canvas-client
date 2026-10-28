(ns canvaslms.model.db-test
  (:require [canvaslms.model.db :as db]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files)))

(def registration {:av-url "https://av.example.edu" :aws-url "https://aws.example.edu"
                   :av-course {:id 10 :name "School"} :aws-course {:id 20 :name "Academy"}})
(def student {:id 1 :name "Official name" :login_id "s@example.edu" :section-ids #{3}})

(deftest storage-refresh-versions-and-restart
  (let [path (str (Files/createTempFile "canvaslms-test-" ".sqlite" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (with-open [database (db/open! path)]
        (let [r (db/save-registration! database registration [student] [{:id 101}]
                                       [{:av student :aws {:id 101} :status :matched}])
              refreshed (db/save-registration! database registration [(assoc student :name "Updated name")]
                                               [] [{:av student :status :absent}])
              base {:registration-id (:id r) :av-url (:av-url registration) :aws-url (:aws-url registration)
                    :av-course-id 10 :destination-id 99 :source-max 55M :destination-max 20M}
              first-mapping (db/save-mapping! database base)
              second-mapping (db/save-mapping! database (assoc base :penalty-within 20M))]
          (is (empty? (db/graded-mapping-ids database (:av-url registration) (:aws-url registration))))
          (is (= (:id r) (:id refreshed)))
          (is (= 1 (count (db/registrations database (:av-url registration) (:aws-url registration)))))
          (is (= "Updated name" (:name (edn/read-string (:data (db/one database ["SELECT data FROM students"]))))))
          (is (empty? (db/query database ["SELECT * FROM memberships"])))
          (is (= (:id first-mapping) (:id second-mapping)))
          (is (= 2 (:version second-mapping)))
          (is (= 2 (count (db/query database ["SELECT * FROM mapping_versions"]))))
          (let [row {:user-id 1 :operation-id "op" :grade "12.80"}
                run-id (db/start-run! database (:id second-mapping) {:rows [row]} #{1})]
            (db/record-result! database run-id row :succeeded {:submission {:score 12.8M}})
            (db/finish-run! database run-id :complete)
            (is (= #{(:id second-mapping)}
                   (db/graded-mapping-ids database (:av-url registration) (:aws-url registration)))))))
      (with-open [database (db/open! path)]
        (let [versions (db/query database ["SELECT * FROM schema_migrations"])]
          (is (= 1 (count versions))))
        (is (= "succeeded" (:status (first (db/operation-history database "op")))))
        (is (= #{1} (db/graded-mapping-ids database (:av-url registration) (:aws-url registration))))
        (is (= 12.8M (get-in (first (db/operation-history database "op")) [:result :submission :score]))))
      (finally (Files/deleteIfExists (.toPath (java.io.File. path)))))))

(deftest grading-history-is-scoped-and-survives-mapping-edits
  (with-open [database (db/open! ":memory:")]
    (let [r (db/save-registration! database registration [] [] [])
          base {:registration-id (:id r) :av-url (:av-url registration) :aws-url (:aws-url registration)
                :av-course-id 10 :destination-id 99}
          graded (db/save-mapping! database base)
          ungraded (db/save-mapping! database (assoc base :destination-id 100))
          foreign-registration (assoc registration :aws-url "https://other.example.edu")
          foreign-r (db/save-registration! database foreign-registration [] [] [])
          foreign-mapping (db/save-mapping! database (assoc base :registration-id (:id foreign-r)
                                                               :aws-url (:aws-url foreign-registration)))
          run-id (db/start-run! database (:id graded) {:rows []} #{})]
      (db/finish-run! database run-id :partial)
      (db/start-run! database (:id foreign-mapping) {:rows []} #{})
      (db/save-mapping! database base)
      (let [ids (db/graded-mapping-ids database (:av-url registration) (:aws-url registration))]
        (is (= #{(:id graded)} ids))
        (is (not (contains? ids (:id ungraded)))))
      (is (= #{(:id foreign-mapping)}
             (db/graded-mapping-ids database (:av-url registration) (:aws-url foreign-registration)))))))
