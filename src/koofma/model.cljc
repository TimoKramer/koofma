(ns koofma.model
  "Pure operations on the shopping-list document. Every mutating fn takes the
  HLC stamp `t` as an argument — stamps are minted in one place (the event
  funnel), never here.

  Checking an item off deletes it (a tombstone), so there is no done flag.

  Items belong to a list via their :list register. The default list exists
  without an entry in :lists (docs from before there were lists have no
  :lists and no :list registers) and gets one once it is renamed."
  (:require
    [clojure.string :as str]))


(def empty-doc {:items {} :lists {}})


(def default-list-id "default")
(def default-list-name "Shopping")


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


(defn- by-rank
  [entries]
  (sort-by (fn [[id e]] [(fval e :rank) (str id)]) entries))


(defn lists
  "Alive lists as [id list] pairs, sorted by [rank id]. Never empty: if every
  list was deleted (say concurrently on two devices), the default one shows."
  [doc]
  (let [default {:name {:v default-list-name} :rank {:v 0.0}}
        alive   (->> (merge-with merge {default-list-id default} (:lists doc))
                     (filter (fn [[_ l]] (alive? l)))
                     by-rank)]
    (if (seq alive) alive [[default-list-id default]])))


(defn list-name
  [doc list-id]
  (some (fn [[id l]] (when (= id list-id) (fval l :name))) (lists doc)))


(defn resolve-list
  "list-id if that list is alive, else the first list — the remembered
  current list may have been deleted on another device."
  [doc list-id]
  (let [ls (lists doc)]
    (or (some (fn [[id _]] (when (= id list-id) id)) ls)
        (ffirst ls))))


(defn item-list
  [item]
  (or (fval item :list) default-list-id))


(defn items
  "Alive items of a list as [id item] pairs, sorted by [rank id]."
  [doc list-id]
  (->> (:items doc)
       (filter (fn [[_ item]] (and (alive? item) (= list-id (item-list item)))))
       by-rank))


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
  [doc list-id]
  (rank-between nil (some-> (first (items doc list-id)) second (fval :rank))))


(defn list-rank-at-end
  [doc]
  (rank-between (some-> (last (lists doc)) second (fval :rank)) nil))


(defn rank-for-drop
  "Rank that places `id` immediately before (or, with `after?`, after) the
  item `target-id` of the same list. nil when the drop is a no-op or the
  target is unknown."
  [doc id target-id after?]
  (when (not= id target-id)
    (let [list-id (item-list (get-in doc [:items id]))
          others  (vec (remove (fn [[i _]] (= i id)) (items doc list-id)))
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
  [doc list-id title]
  (let [k (normalize title)]
    (some (fn [[id item]] (when (= k (normalize (fval item :title))) id))
          (items doc list-id))))


;; item operations -------------------------------------------------------------

(defn add-item
  [doc id list-id title rank t]
  (assoc-in doc [:items id]
            {:list    (reg list-id t)
             :title   (reg title t)
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


;; list operations -------------------------------------------------------------

(defn add-list
  [doc id name rank t]
  (assoc-in doc [:lists id]
            {:name    (reg name t)
             :rank    (reg rank t)
             :deleted (reg false t)}))


(defn- set-list-field
  [doc id k v t]
  (assoc-in doc [:lists id k] (reg v t)))


(defn rename-list
  [doc id name t]
  (set-list-field doc id :name name t))


(defn delete-list
  "Tombstones the list; its items stay as they are, just unreachable."
  [doc id t]
  (set-list-field doc id :deleted true t))


(defn undelete-list
  [doc id t]
  (set-list-field doc id :deleted false t))
