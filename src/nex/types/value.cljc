(ns nex.types.value
  (:require [nex.types.runtime :as rt]))

(declare nex-deep-equals?)

(defn- nex-char-literal
  [value]
  (case value
    \space "#space"
    \tab "#tab"
    \newline "#newline"
    \return "#return"
    \u0000 "#nul"
    (str "#" value)))

(defn nex-format-value
  [nex-object? formatter-map formatter-array formatter-set value]
  (cond
    (nex-object? value)
    (str "#<" (:class-name value) " object>")

    ;; Set and Map are tagged maps; format them as collections before the generic
    ;; builtin-type fallthrough below would render them as "#<NexSet>"/"#<NexMap>".
    (rt/nex-set? value)
    (formatter-set value)

    (rt/nex-map? value)
    (formatter-map value)

    (and (map? value) (:nex-builtin-type value))
    (str "#<" (name (:nex-builtin-type value)) ">")

    (string? value)
    (str \" value \")

    #?(:clj (ratio? value) :cljs false)
    (str (double value))

    ;; Nex Integer (a BigInt on JS, which `number?` does not recognize). `str`
    ;; renders it cleanly ("28"); the generic :else would emit "#object[BigInt 28]".
    (rt/nex-integer? value)
    (str value)

    ;; Real. On JS every remaining `number` is a Real; an integer-valued one
    ;; stringifies as "9", but the JVM renders the double as "9.0" — match it so
    ;; the two backends print identically.
    (number? value)
    #?(:clj (str value)
       :cljs (if (and (js/Number.isFinite value) (js/Number.isInteger value))
               (str value ".0")
               (str value)))

    (boolean? value)
    (str value)

    (nil? value)
    "nil"

    (rt/nex-map? value)
    (formatter-map value)

    (rt/nex-array? value)
    (formatter-array value)

    (rt/nex-set? value)
    (formatter-set value)

    (coll? value)
    (pr-str value)

    (char? value)
    (nex-char-literal value)

    :else
    (pr-str value)))

(defn nex-clone-value
  [nex-object? make-object value]
  (cond
    (nex-object? value)
    (make-object (:class-name value)
                 (into {} (map (fn [[k v]] [k (nex-clone-value nex-object? make-object v)]) (:fields value)))
                 (:closure-env value))

    (rt/nex-array? value)
    (rt/nex-array-from (map (partial nex-clone-value nex-object? make-object) #?(:clj value :cljs (array-seq value))))

    (rt/nex-map? value)
    (let [pairs (map (fn [[k v]] [(nex-clone-value nex-object? make-object k)
                                  (nex-clone-value nex-object? make-object v)])
                     (rt/nex-map-entries value))]
      ;; Preserve the map's representation: a portable map (Clojure map) clones to
      ;; a portable map, a host map (compiled HashMap/js.Map) to a host map.
      (if (map? value)
        (rt/nex-map-from pairs)
        (rt/nex-host-map-from pairs)))

    (rt/nex-set? value)
    (let [elems (map (partial nex-clone-value nex-object? make-object)
                     (rt/nex-set-seq value))]
      (if (map? value)
        (rt/nex-set-from elems)
        (rt/nex-host-set-from elems)))

    (and (map? value) (:nex-builtin-type value))
    (into {} value)

    :else
    value))

(defn nex-map-entry-match?
  [nex-object? k1 v1 m2]
  (some (fn [[k2 v2]]
          (and (nex-deep-equals? nex-object? k1 k2)
               (nex-deep-equals? nex-object? v1 v2)))
        (rt/nex-map-entries m2)))

(defn nex-deep-equals?
  [nex-object? a b]
  (cond
    (and (nex-object? a) (nex-object? b))
    (and (= (:class-name a) (:class-name b))
         (= (set (keys (:fields a))) (set (keys (:fields b))))
         (every? (fn [k] (nex-deep-equals? nex-object? (get (:fields a) k) (get (:fields b) k)))
                 (keys (:fields a))))

    (and (rt/nex-array? a) (rt/nex-array? b))
    (and (= (rt/nex-array-size a) (rt/nex-array-size b))
         (every? true? (map (partial nex-deep-equals? nex-object?)
                            #?(:clj a :cljs (array-seq a))
                            #?(:clj b :cljs (array-seq b)))))

    (and (rt/nex-map? a) (rt/nex-map? b))
    (and (= (rt/nex-map-size a) (rt/nex-map-size b))
         (every? (fn [[k v]] (nex-map-entry-match? nex-object? k v b))
                 (rt/nex-map-entries a)))

    (and (rt/nex-set? a) (rt/nex-set? b))
    (and (= (rt/nex-set-size a) (rt/nex-set-size b))
         (every? (fn [v1]
                   (some #(nex-deep-equals? nex-object? v1 %)
                         (rt/nex-set-seq b)))
                 (rt/nex-set-seq a)))

    :else
    (= a b)))

(defn nex-display-value
  [nex-object? formatter value]
  (if (or (rt/nex-map? value)
          (rt/nex-array? value)
          (rt/nex-set? value)
          (nil? value)
          (nex-object? value)
          (and (map? value) (:nex-builtin-type value)))
    (formatter value)
    (str value)))
