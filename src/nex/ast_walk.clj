(ns nex.ast-walk
  "Fast walks over an AST (nested maps, vectors, seqs and sets).

   `(filter map? (tree-seq coll? seq x))` finds the same nodes, but steps
   through every map entry, key and scalar along the way and allocates a lazy
   seq at each step: on a typical program tree that is ~80x slower. These
   walk the collections directly and return the same items, in the same
   (depth-first, pre-order) order.")

(defn maps
  "Every map reachable from X, X itself included, in the order
   `(filter map? (tree-seq descend? seq x))` gives them. DESCEND? (default
   coll?) decides, for each collection reached, whether to look inside it;
   a map it refuses is still returned, just not walked into. Map entries are
   always looked into (none of tree-seq's usual branch predicates refuse a
   map entry), which is what makes walking each entry's key and value
   directly equivalent."
  ([x] (maps x coll?))
  ([x descend?]
   (let [out (transient [])]
     (letfn [(walk [x]
               (when (map? x) (conj! out x))
               (when (and (coll? x) (descend? x))
                 (if (map? x)
                   (reduce-kv (fn [_ k v]
                                (when (coll? k) (walk k))
                                (when (coll? v) (walk v)))
                              nil x)
                   (reduce (fn [_ v] (when (coll? v) (walk v))) nil x))))]
       (walk x))
     (persistent! out))))

(defn strings
  "Every string reachable from X, map keys included, in the order
   `(filter string? (tree-seq coll? seq x))` gives them."
  [x]
  (let [out (transient [])]
    (letfn [(walk [x]
              (cond
                (string? x) (conj! out x)
                (map? x) (reduce-kv (fn [_ k v] (walk k) (walk v)) nil x)
                (coll? x) (reduce (fn [_ v] (walk v)) nil x)))]
      (walk x))
    (persistent! out)))
