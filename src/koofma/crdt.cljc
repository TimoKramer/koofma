(ns koofma.crdt
  "Merge for documents of per-field LWW registers.
  A register is {:v value :t hlc}; a doc is {:items {item-id {field register}}}.")


(defn- latest
  "Pick the register with the greater HLC. Two distinct writes can never carry
  the same HLC (one stamping point per node, strictly monotonic ticks), but
  tie-break on the printed value anyway so merge is a total order on arbitrary
  input."
  [a b]
  (let [c (compare (:t a) (:t b))]
    (cond (pos? c) a
          (neg? c) b
          :else    (if (pos? (compare (pr-str (:v a)) (pr-str (:v b)))) a b))))


(defn merge-docs
  "Commutative, associative, idempotent merge of two item documents."
  [a b]
  {:items (merge-with #(merge-with latest %1 %2) (:items a) (:items b))})


(defn latest-stamp
  "Greatest HLC present in doc, or nil. Used to advance the local clock after
  merging remote data."
  [doc]
  (->> (:items doc)
       vals
       (mapcat vals)
       (map :t)
       (reduce (fn [m t] (if (and m (pos? (compare m t))) m t)) nil)))
