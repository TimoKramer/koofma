(ns koofma.events-test
  (:require
    [clojure.test :refer [deftest is]]
    [koofma.events :as e]
    [koofma.hlc :as hlc]
    [koofma.model :as m]))


(def db0 {:doc m/empty-doc :clock (hlc/init "A") :node-id "A"})


(defn- add [db id title] (e/handle db [:item/add {:id id :title title}] 1000))


(deftest add-stamps-and-appends
  (let [db (-> db0 (add :a "milk") (add :b "eggs"))]
    (is (= [:a :b] (map first (m/items (:doc db)))))
    (is (hlc/before? (:clock db0) (:clock db)))))


(deftest check-deletes-and-undo-restores
  (let [db      (add db0 :a "milk")
        checked (e/handle db [:item/check {:id :a}] 2000)
        undone  (e/handle checked [:item/undelete {:id :a}] 3000)]
    (is (empty? (m/items (:doc checked))))
    (is (= {:type :undo-check :id :a :title "milk"} (:flash checked)))
    (is (= [:a] (map first (m/items (:doc undone)))))))


(deftest move-reorders-and-ignores-noops
  (let [db (-> db0 (add :a "a") (add :b "b") (add :c "c"))
        moved (e/handle db [:item/move {:id :c :target-id :a :after? false}] 2000)]
    (is (= [:c :a :b] (map first (m/items (:doc moved)))))
    (is (= db (e/handle db [:item/move {:id :a :target-id :a :after? true}] 2000)))))


(deftest remote-merge-converges-and-advances-clock
  (let [a (-> db0 (add :a "milk"))
        b (-> (assoc db0 :clock (hlc/init "B")) (e/handle [:item/add {:id :b :title "eggs"}] 5000))
        a' (e/handle a [:remote/merged {:doc (:doc b)}] 1500)
        b' (e/handle b [:remote/merged {:doc (:doc a)}] 1500)]
    (is (= (:doc a') (:doc b')))
    (is (= 2 (count (m/items (:doc a')))))
    (is (hlc/before? (:clock b) (:clock a')) "clock saw the remote stamp")))


(deftest ui-and-sync-events-dont-stamp
  (doseq [ev [[:sync/start] [:sync/success] [:ui/edit-item {:id :a}]
              [:ui/drag-start {:id :a}] [:ui/drag-end] [:flash/clear]]]
    (is (= (:clock db0) (:clock (e/handle db0 ev 1000))) (str ev))))
