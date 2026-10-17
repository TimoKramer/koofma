(ns koofma.model
  "Pure operations on the shopping-list document. Every mutating fn takes the
  HLC stamp `t` as an argument — stamps are minted in one place (the event
  funnel), never here.

  Checking an item off deletes it (a tombstone), so there is no done flag."
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


(defn normalize
  "Case- and whitespace-insensitive key for comparing titles."
  [title]
  (str/lower-case (str/trim (or title ""))))


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


(defn rank-at-top
  [doc]
  (rank-between nil (some-> (first (items doc)) second (fval :rank))))


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


;; lookup ---------------------------------------------------------------------

(defn find-alive-by-title
  "Id of an item on the list whose title equals `title` (ignoring case and
  surrounding whitespace), or nil."
  [doc title]
  (let [k (normalize title)]
    (some (fn [[id item]] (when (= k (normalize (fval item :title))) id))
          (items doc))))


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
