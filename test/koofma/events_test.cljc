(ns koofma.events-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [koofma.events :as e]
    [koofma.hlc :as hlc]
    [koofma.model :as m]))


(defn- items* [doc] (m/items doc m/default-list-id))


(def db0 {:doc m/empty-doc :clock (hlc/init "A") :node-id "A"})


(defn- add [db id title] (e/handle db [:item/add {:id id :title title}] 1000))


(deftest add-stamps-and-inserts-at-top
  (let [db (-> db0 (add :a "milk") (add :b "eggs"))]
    (is (= [:b :a] (map first (items* (:doc db)))) "newest item first")
    (is (hlc/before? (:clock db0) (:clock db)))))


(deftest duplicates-are-rejected
  (let [db  (-> db0 (add :a "Milk"))
        dup (add db :b "  milk ")]
    (is (= (:doc db) (:doc dup)) "doc untouched")
    (is (= {:type :duplicate :title "milk"} (:flash dup)))
    (is (= (:clock db) (:clock dup)) "no stamp for a rejected add")
    (testing "a checked-off title can be added again"
      (let [gone (e/handle db [:item/check {:id :a}] 2000)]
        (is (= 1 (count (items* (:doc (add gone :c "milk"))))))))
    (testing "blank titles are ignored"
      (is (= (:doc db) (:doc (add db :d "   ")))))))


(deftest check-deletes-and-undo-restores
  (let [db      (add db0 :a "milk")
        checked (e/handle db [:item/check {:id :a}] 2000)
        undone  (e/handle checked [:item/undelete {:id :a}] 3000)]
    (is (empty? (items* (:doc checked))))
    (is (= {:type :undo-check :id :a :title "milk" :undo [:item/undelete {:id :a}]}
           (:flash checked)))
    (is (= [:a] (map first (items* (:doc undone)))))))


(deftest move-reorders-and-ignores-noops
  (let [db (-> db0 (add :a "a") (add :b "b") (add :c "c"))   ; list: c b a
        moved (e/handle db [:item/move {:id :a :target-id :c :after? false}] 2000)]
    (is (= [:c :b :a] (map first (items* (:doc db)))))
    (is (= [:a :c :b] (map first (items* (:doc moved)))))
    (is (= db (e/handle db [:item/move {:id :a :target-id :a :after? true}] 2000)))))


(deftest remote-merge-converges-and-advances-clock
  (let [a (-> db0 (add :a "milk"))
        b (-> (assoc db0 :clock (hlc/init "B")) (e/handle [:item/add {:id :b :title "eggs"}] 5000))
        a' (e/handle a [:remote/merged {:doc (:doc b)}] 1500)
        b' (e/handle b [:remote/merged {:doc (:doc a)}] 1500)]
    (is (= (:doc a') (:doc b')))
    (is (= 2 (count (items* (:doc a')))))
    (is (hlc/before? (:clock b) (:clock a')) "clock saw the remote stamp")))


(deftest ui-and-sync-events-dont-stamp
  (doseq [ev [[:sync/start] [:sync/success] [:ui/edit-item {:id :a}]
              [:ui/select-list {:id :x}] [:ui/list-dialog {:mode :new}]
              [:ui/drag-start {:id :a}] [:ui/drag-end] [:flash/clear]]]
    (is (= (:clock db0) (:clock (e/handle db0 ev 1000))) (str ev))))


(deftest lists-add-rename-delete-undo
  (let [db      (e/handle db0 [:list/add {:id :x :name " Hardware "}] 1000)
        db      (add db :s "screws")
        renamed (e/handle db [:list/rename {:id :x :name "DIY"}] 2000)
        deleted (e/handle renamed [:list/delete {:id :x}] 3000)
        undone  (e/handle deleted (:undo (:flash deleted)) 4000)]
    (is (= :x (:current-list db)) "a new list becomes current")
    (is (= [:s] (map first (m/items (:doc db) :x))) "items go to the current list")
    (is (empty? (items* (:doc db))))
    (is (= "DIY" (m/list-name (:doc renamed) :x)))
    (is (= m/default-list-id (e/current-list deleted)))
    (is (= {:type :undo-list-delete :id :x :title "DIY" :undo [:list/undelete {:id :x}]}
           (:flash deleted)))
    (is (= [:s] (map first (m/items (:doc undone) :x))) "undo brings the items back")
    (testing "the last list can't be deleted, blank names are ignored"
      (is (= db0 (e/handle db0 [:list/delete {:id m/default-list-id}] 1000)))
      (is (= db0 (e/handle db0 [:list/add {:name "  "}] 1000)))
      (is (= (:doc db) (:doc (e/handle db [:list/rename {:id :x :name ""}] 1000)))))))
