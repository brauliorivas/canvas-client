(ns canvaslms.cli.picker-test
  (:require [canvaslms.cli.picker :as picker]
            [clojure.test :refer [are deftest is]]))

(def items [{:id 1 :name "Curso 12 — Álgebra" :code "MAT-101"}
            {:id 12 :name "AWS Academy Foundations" :code "AWS-101"}
            {:id 27 :name "AWS Academy Quiz" :code "AWS-102"}
            {:id 28 :name "AWS Academy Quiz" :code "AWS-103"}])
(def state (picker/create items [:id :name :code]))
(defn ids [state] (mapv :id (:matches state)))

(deftest normalized-and-multiword-search
  (is (= "algebra" (picker/normalize "ÁL-GÉBRA!")))
  (are [query expected] (= expected (ids (picker/search state query)))
    "álgebra" [1]
    "MAT101" [1]
    "academy AWS" [12 27 28]
    "quiz 102" [27]
    "  " [1 12 27 28]
    "---" [1 12 27 28]
    "unknown" []))

(deftest exact-id-priority-and-duplicate-names
  (is (= [12 1] (ids (picker/search state "12"))))
  (is (= 12 (:id (picker/selected (picker/search state "12")))))
  (is (= 1 (:id (picker/selected (picker/move (picker/search state "12") 1)))))
  (is (= [27 28] (ids (picker/search state "quiz"))))
  (let [with-custom-column (picker/create items [{:key :id :title "ID"}
                                                {:key #(str (:code %) " " (:name %)) :title "Activity"}])]
    (is (= [27] (ids (picker/search with-custom-column "aws102"))))))

(deftest tab-wraps-within-filtered-results
  (let [filtered (picker/search state "quiz")]
    (is (= 28 (:id (picker/selected (picker/keypress filtered :next nil)))))
    (is (= 27 (:id (picker/selected (-> filtered (picker/move 1) (picker/move 1))))))
    (is (= 28 (:id (picker/selected (picker/move filtered -1))))))
  (let [empty-state (picker/search state "unknown")]
    (is (= empty-state (picker/move empty-state 1)))
    (is (nil? (:chosen (picker/keypress empty-state :accept nil))))))

(deftest editing-resets-selection-and-clear-restores-every-option
  (let [filtered (-> state (picker/search "quiz") (picker/move 1))
        edited (picker/keypress filtered :text " 102")]
    (is (= 0 (:index edited)))
    (is (= 27 (:id (picker/selected edited))))
    (is (= items (:matches (picker/keypress edited :clear nil))))
    (is (= "quiz 10" (:query (picker/keypress edited :backspace nil)))))
  (is (= "" (:query (picker/keypress (picker/search state "😀") :backspace nil))))
  (is (= "" (:query (picker/keypress state :backspace nil))))
  (is (= "q" (:query (picker/keypress state :text "q"))))
  (is (:cancelled? (picker/keypress state :cancel nil))))

(deftest viewport-follows-highlight-and-respects-height
  (let [large (picker/create (mapv #(hash-map :id % :name (str "Course " %)) (range 100)) [:id :name])
        near-end (assoc large :index 98)
        window (picker/viewport near-end 20)
        small (picker/viewport near-end 8)]
    (is (= 10 (count (:items window))))
    (is (= 90 (:start window)))
    (is (= 3 (count (:items small))))
    (is (<= (:start small) 98 (dec (:end small))))
    (is (= 1 (:capacity (picker/viewport large 1))))))
