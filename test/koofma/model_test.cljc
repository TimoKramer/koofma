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


(deftest rank-at-end-appends
  (is (= 1.0 (m/rank-at-end m/empty-doc)))
  (is (= 3.0 (m/rank-at-end (build [:a "x"] [:b "y"])))))


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
