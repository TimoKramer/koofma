(ns koofma.model
  "Pure operations on the shopping-list document. Every mutating fn takes the
  HLC stamp `t` as an argument — stamps are minted in one place (the event
  funnel), never here.

  Checking an item off deletes it (a tombstone), so there is no done flag; the
  tombstoned titles double as the history that feeds input suggestions."
  (:require
    [clojure.string :as str]))


(def empty-doc {:items {}})


(defn- reg
  [v t]
  {:v v :t t})


(defn fval
  "Current value of an item's field."
  [item k]
  (get-in item [k :v]))


(defn- set-field
  [doc id k v t]
  (assoc-in doc [:items id k] (reg v t)))


(defn alive?
  [item]
  (not (fval item :deleted)))


(defn items
  "Alive items as [id item] pairs, sorted by [rank id]."
  [doc]
  (->> (:items doc)
       (filter (fn [[_ item]] (alive? item)))
       (sort-by (fn [[id item]] [(fval item :rank) (str id)]))))


;; ranks ---------------------------------------------------------------------

(defn rank-between
  "A rank strictly between lo and hi; either side may be nil (open end)."
  [lo hi]
  (cond
    (and lo hi) (/ (+ lo hi) 2.0)
    lo          (inc lo)
    hi          (dec hi)
    :else       1.0))


(defn rank-at-end
  [doc]
  (rank-between (some-> (last (items doc)) second (fval :rank)) nil))


(defn rank-for-drop
  "Rank that places `id` immediately before (or, with `after?`, after) the
  item `target-id`. nil when the drop is a no-op or the target is unknown."
  [doc id target-id after?]
  (when (not= id target-id)
    (let [others (vec (remove (fn [[i _]] (= i id)) (items doc)))
          idx    (first (keep-indexed (fn [i [i-id _]] (when (= i-id target-id) i)) others))]
      (when idx
        (let [[lo-i hi-i] (if after? [idx (inc idx)] [(dec idx) idx])
              rank-at     #(some-> (get others %) second (fval :rank))]
          (rank-between (when (<= 0 lo-i) (rank-at lo-i))
                        (rank-at hi-i)))))))


;; item operations -------------------------------------------------------------

(defn add-item
  [doc id title rank t]
  (assoc-in doc [:items id]
            {:title   (reg title t)
             :rank    (reg rank t)
             :deleted (reg false t)}))


(defn set-title
  [doc id title t]
  (set-field doc id :title title t))


(defn delete-item
  [doc id t]
  (set-field doc id :deleted true t))


(defn undelete-item
  [doc id t]
  (set-field doc id :deleted false t))


(defn move-item
  [doc id rank t]
  (set-field doc id :rank rank t))


;; suggestions -----------------------------------------------------------------

(defn- normalize
  [title]
  (str/lower-case (str/trim title)))


(defn suggestions
  "Distinct titles from every item ever entered — alive or checked off — that
  are not currently on the list, most frequently used first, ties broken by
  most recent. Keeps the casing of the latest entry."
  [doc]
  (let [alive-titles (into #{} (map (comp normalize #(fval % :title) second)) (items doc))]
    (->> (vals (:items doc))
         (filter #(some? (fval % :title)))
         (group-by (comp normalize #(fval % :title)))
         (remove (fn [[k _]] (contains? alive-titles k)))
         (map (fn [[_ group]]
                (let [latest (reduce #(if (neg? (compare (get-in %1 [:title :t]) (get-in %2 [:title :t]))) %2 %1) group)]
                  [(count group)
                   (get-in latest [:title :t])
                   (fval latest :title)])))
         (sort (fn [[n1 t1 _] [n2 t2 _]]
                 (let [c (compare n2 n1)]
                   (if (zero? c) (compare t2 t1) c))))
         (map #(nth % 2)))))
