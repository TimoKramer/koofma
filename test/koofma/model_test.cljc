(ns koofma.model-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [koofma.hlc :as hlc]
    [koofma.model :as m]))


(def t0 (hlc/init "A"))


(defn- build
  "Doc from [id title] pairs, ranks 1.0, 2.0, …"
  [& pairs]
  (reduce (fn [d [i [id title]]] (m/add-item d id title (double (inc i)) t0))
          m/empty-doc
          (map-indexed vector pairs)))


(defn- ids [doc] (map first (m/items doc)))


(deftest items-sorted-by-rank-and-hide-deleted
  (let [doc (-> (build [:a "milk"] [:b "eggs"] [:c "bread"])
                (m/delete-item :b (hlc/tick t0 1)))]
    (is (= [:a :c] (ids doc)))
    (is (= [:a :b :c] (ids (m/undelete-item doc :b (hlc/tick t0 2)))))))


(deftest rank-at-top-prepends
  (is (= 1.0 (m/rank-at-top m/empty-doc)))
  (is (= 0.0 (m/rank-at-top (build [:a "x"] [:b "y"])))))


(deftest find-alive-by-title-ignores-case-and-deleted
  (let [doc (-> (build [:a "Milk"] [:b "eggs"])
                (m/delete-item :b (hlc/tick t0 1)))]
    (is (= :a (m/find-alive-by-title doc " milk ")))
    (is (nil? (m/find-alive-by-title doc "eggs")) "checked off = not on the list")))


(deftest matching-suggestions-filter-and-order
  (let [doc (-> (build [:a "Apfelsaft"] [:b "Saft"] [:c "Brot"] [:d "Sahne"])
                (m/delete-item :a (hlc/tick t0 1))
                (m/delete-item :b (hlc/tick t0 1))
                (m/delete-item :c (hlc/tick t0 1))
                (m/delete-item :d (hlc/tick t0 1)))]
    (is (= ["Saft" "Sahne" "Apfelsaft"] (take 3 (m/matching-suggestions doc "sa" 6))))
    (is (= ["Saft" "Apfelsaft"] (m/matching-suggestions doc "saf" 6)) "prefix matches first")
    (is (= [] (m/matching-suggestions doc "  " 6)))
    (is (= [] (m/matching-suggestions doc nil 6)) "no draft yet")
    (is (= 1 (count (m/matching-suggestions doc "sa" 1))))))


(deftest rank-for-drop-places-relative-to-target
  (let [doc (build [:a "a"] [:b "b"] [:c "c"])
        move (fn [id target after?]
               (ids (m/move-item doc id (m/rank-for-drop doc id target after?) (hlc/tick t0 1))))]
    (is (= [:b :c :a] (move :a :c true)))
    (is (= [:b :a :c] (move :a :c false)))
    (is (= [:c :a :b] (move :c :a false)))
    (is (= [:a :c :b] (move :c :b false)))
    (testing "no-ops"
      (is (nil? (m/rank-for-drop doc :a :a true)))
      (is (nil? (m/rank-for-drop doc :a :zzz true))))))


(deftest repeated-drops-keep-order-strict
  (let [doc (build [:a "a"] [:b "b"])
        step (fn [d i]
               (let [r (m/rank-for-drop d :b :a false)]
                 (m/move-item d :b r (hlc/tick t0 i))))]
    (is (= [:b :a] (ids (step doc 1))))))


(deftest suggestions-from-history
  (let [doc (-> (build [:a "Milk"] [:b "eggs"] [:c "milk"] [:d "Bread"])
                (m/delete-item :c (hlc/tick t0 1))
                (m/delete-item :b (hlc/tick t0 1)))]
    (is (= ["eggs"] (m/suggestions doc))
        "milk and bread are on the list (case-insensitive); eggs was checked off")
    (testing "frequency first"
      (let [doc (-> doc
                    (m/add-item :e "Eggs" 9.0 (hlc/tick t0 5))
                    (m/delete-item :e (hlc/tick t0 6)))]
        (is (= ["Eggs"] (m/suggestions doc)) "one entry per distinct title")))))
