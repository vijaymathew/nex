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

    (and (map? value) (:nex-builtin-type value))
    (str "#<" (name (:nex-builtin-type value)) ">")

    (string? value)
    (str \" value \")

    #?(:clj (ratio? value) :cljs false)
    (str (double value))

    (number? value)
    (str value)

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
    #?(:clj (java.util.HashMap. (into {} (map (fn [[k v]] [(nex-clone-value nex-object? make-object k)
                                                           (nex-clone-value nex-object? make-object v)])
                                                 value)))
       :cljs (js/Map. (to-array (map (fn [[k v]] (to-array [(nex-clone-value nex-object? make-object k)
                                                            (nex-clone-value nex-object? make-object v)]))
                                     (es6-iterator-seq (.entries value))))))

    (rt/nex-set? value)
    #?(:clj (doto (java.util.LinkedHashSet.)
              (#(doseq [v value] (.add % (nex-clone-value nex-object? make-object v)))))
       :cljs (js/Set. (to-array (map (partial nex-clone-value nex-object? make-object)
                                     (es6-iterator-seq (.values value))))))

    (and (map? value) (:nex-builtin-type value))
    (into {} value)

    :else
    value))

(defn nex-map-entry-match?
  [nex-object? k1 v1 m2]
  (some (fn [[k2 v2]]
          (and (nex-deep-equals? nex-object? k1 k2)
               (nex-deep-equals? nex-object? v1 v2)))
        #?(:clj m2 :cljs (es6-iterator-seq (.entries m2)))))

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
                 #?(:clj a :cljs (es6-iterator-seq (.entries a)))))

    (and (rt/nex-set? a) (rt/nex-set? b))
    (and (= (rt/nex-set-size a) (rt/nex-set-size b))
         (every? (fn [v1]
                   (some #(nex-deep-equals? nex-object? v1 %)
                         #?(:clj b :cljs (es6-iterator-seq (.values b)))))
                 #?(:clj a :cljs (es6-iterator-seq (.values a)))))

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
