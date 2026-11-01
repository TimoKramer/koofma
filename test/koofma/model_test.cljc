(ns koofma.model-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [koofma.hlc :as hlc]
    [koofma.model :as m]))


(def t0 (hlc/init "A"))


(defn- build
  "Doc from [id title] pairs, ranks 1.0, 2.0, …"
  [& pairs]
  (reduce (fn [d [i [id title]]] (m/add-item d id m/default-list-id title (double (inc i)) t0))
          m/empty-doc
          (map-indexed vector pairs)))


(def dl m/default-list-id)


(defn- ids [doc] (map first (m/items doc dl)))


(deftest items-sorted-by-rank-and-hide-deleted
  (let [doc (-> (build [:a "milk"] [:b "eggs"] [:c "bread"])
                (m/delete-item :b (hlc/tick t0 1)))]
    (is (= [:a :c] (ids doc)))
    (is (= [:a :b :c] (ids (m/undelete-item doc :b (hlc/tick t0 2)))))))


(deftest rank-at-top-prepends
  (is (= 1.0 (m/rank-at-top m/empty-doc dl)))
  (is (= 0.0 (m/rank-at-top (build [:a "x"] [:b "y"]) dl))))


(deftest find-alive-by-title-ignores-case-and-deleted
  (let [doc (-> (build [:a "Milk"] [:b "eggs"])
                (m/delete-item :b (hlc/tick t0 1)))]
    (is (= :a (m/find-alive-by-title doc dl " milk ")))
    (is (nil? (m/find-alive-by-title doc dl "eggs")) "checked off = not on the list")))


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


(deftest default-list-exists-without-an-entry
  (is (= [[dl "Shopping"]]
         (map (fn [[id l]] [id (m/fval l :name)]) (m/lists m/empty-doc))))
  (testing "items from before there were lists belong to it"
    (let [legacy {:items {:a {:title {:v "milk" :t t0} :rank {:v 1.0 :t t0}
                              :deleted {:v false :t t0}}}}]
      (is (= [:a] (map first (m/items legacy dl))))))
  (testing "renaming it keeps it first"
    (let [doc (-> m/empty-doc
                  (m/add-list :x "Hardware" 1.0 t0)
                  (m/rename-list dl "Groceries" (hlc/tick t0 1)))]
      (is (= [[dl "Groceries"] [:x "Hardware"]]
             (map (fn [[id l]] [id (m/fval l :name)]) (m/lists doc)))))))


(deftest items-are-per-list
  (let [doc (-> (build [:a "milk"])
                (m/add-list :x "Hardware" 1.0 t0)
                (m/add-item :b :x "screws" 1.0 t0))]
    (is (= [:a] (ids doc)))
    (is (= [:b] (map first (m/items doc :x))))
    (is (nil? (m/find-alive-by-title doc :x "milk")) "duplicates are per list")))


(deftest resolve-list-falls-back-when-deleted
  (let [doc (-> m/empty-doc
                (m/add-list :x "Hardware" 1.0 t0)
                (m/delete-list :x (hlc/tick t0 1)))]
    (is (= dl (m/resolve-list doc :x)))
    (is (= dl (m/resolve-list doc nil)))
    (testing "every list deleted still leaves the default one"
      (is (= dl (m/resolve-list (m/delete-list doc dl (hlc/tick t0 2)) :x))))))
