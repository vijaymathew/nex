(ns nex.types.typeinfo
  (:require [nex.types.runtime :as rt]))

(defn get-type-name
  [value]
  (cond
    (string? value) :String
    #?(:clj (instance? java.math.BigDecimal value)
       :cljs false) :Decimal
    #?(:clj (or (double? value) (float? value))
       :cljs (and (number? value) (not (integer? value)))) :Real
    #?(:clj (ratio? value) :cljs false) :Real
    (integer? value) :Integer
    (rt/nex-char? value) :Char
    (boolean? value) :Boolean
    (rt/nex-array? value) :Array
    (rt/nex-map? value) :Map
    (rt/nex-set? value) :Set
    (rt/nex-console? value) :Console
    (rt/nex-process? value) :Process
    (rt/nex-task? value) :Task
    (rt/nex-channel? value) :Channel
    (rt/nex-min-heap? value) :Min_Heap
    (rt/nex-atomic-integer? value) :Atomic_Integer
    (rt/nex-atomic-integer64? value) :Atomic_Integer64
    (rt/nex-atomic-boolean? value) :Atomic_Boolean
    (rt/nex-atomic-reference? value) :Atomic_Reference
    (rt/nex-array-cursor? value) :ArrayCursor
    (rt/nex-string-cursor? value) :StringCursor
    (rt/nex-map-cursor? value) :MapCursor
    (rt/nex-set-cursor? value) :SetCursor
    :else nil))

(defn runtime-type-name
  [nex-object? get-type-name-fn value]
  (cond
    (nil? value) "Nil"
    (nex-object? value) (:class-name value)
    :else (some-> (get-type-name-fn value) name)))

(defn numeric-subtype-runtime?
  [runtime-type target-type]
  (or (and (= runtime-type "Integer")
           (#{"Integer64" "Real" "Decimal"} target-type))
      (and (= runtime-type "Integer64")
           (#{"Real" "Decimal"} target-type))
      (and (= runtime-type "Real")
           (= target-type "Decimal"))))

(defn cursor-subtype-runtime?
  [runtime-type target-type]
  (and (#{"ArrayCursor" "StringCursor" "MapCursor" "SetCursor"} runtime-type)
       (= target-type "Cursor")))

(defn runtime-type-is?
  [runtime-type-name-fn is-parent? ctx target-type value]
  (let [runtime-type (runtime-type-name-fn value)]
    (cond
      (not (string? target-type)) false
      (nil? runtime-type) false
      (= target-type "Any") true
      (= runtime-type target-type) true
      (numeric-subtype-runtime? runtime-type target-type) true
      (cursor-subtype-runtime? runtime-type target-type) true
      (and runtime-type (is-parent? ctx runtime-type target-type)) true
      :else false)))

(defn convert-compatible-runtime?
  [is-parent? ctx runtime-type target-type]
  (or (= target-type "Any")
      (= runtime-type target-type)
      (and runtime-type (is-parent? ctx runtime-type target-type))))
