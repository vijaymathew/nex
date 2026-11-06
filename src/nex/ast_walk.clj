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

(defn- update-maps-shared
  "X with F applied to every map inside it, innermost first, as
   clojure.walk/postwalk would, but keeping identity: a subtree F changes
   nothing in is returned as it was, and a subtree reached twice (a routine's
   body, shared by a function node and its class's method) becomes one new
   subtree, still shared. Passes that key on node identity (the type
   checker's :extension-calls) rely on both."
  [x f]
  (let [seen (java.util.IdentityHashMap.)]
    (letfn [(walk [x]
              (if-not (coll? x)
                x
                (if (.containsKey seen x)
                  (.get seen x)
                  (let [x' (cond
                             (map? x) (let [m (reduce-kv (fn [m k v]
                                                           (let [v' (walk v)]
                                                             (if (identical? v v') m (assoc m k v'))))
                                                         x x)]
                                        (f m))
                             (vector? x) (let [v (reduce-kv (fn [acc i e]
                                                              (let [e' (walk e)]
                                                                (if (identical? e e') acc (assoc acc i e'))))
                                                            x x)]
                                           v)
                             (seq? x) (let [es (doall (map walk x))]
                                        (if (every? true? (map identical? x es)) x (with-meta (apply list es) (meta x))))
                             :else x)]
                    (.put seen x x')
                    x'))))]
      (walk x))))

(defn stamp-closure-source-file
  "X with SOURCE-FILE set as the :source-file of the class of every `fn`
   (an :anonymous-function node) inside it that has none. A closure is
   compiled as a class of its own, which names the file it was written in
   only through this (see nex.lower/lower-class-def*). A no-op for a nil
   SOURCE-FILE."
  [x source-file]
  (if-not source-file
    x
    (update-maps-shared x (fn [n]
                            (if (and (= :anonymous-function (:type n)) (:class-def n)
                                     (nil? (get-in n [:class-def :source-file])))
                              (assoc-in n [:class-def :source-file] source-file)
                              n)))))
