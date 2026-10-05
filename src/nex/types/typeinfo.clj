(ns nex.types.typeinfo
  (:require [nex.types.runtime :as rt]))

(defn get-type-name
  [value]
  (cond
    (string? value) :String
    ;; Integer is a long on the JVM and a BigInt on JS; check it before Real so
    ;; that on JS every remaining `number` classifies as Real (Integers are
    ;; BigInt now, so an integer-valued `number` is a Real value).
    ;; Byte is a java.lang.Short and `integer?` is true for it, so it must be
    ;; classified before Integer.
    (rt/nex-byte? value) :Byte
    (rt/nex-int16? value) :Integer16
    (rt/nex-int32? value) :Integer32
    (rt/nex-integer? value) :Integer
    (or (double? value) (float? value) (ratio? value)) :Real
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
    (rt/nex-map-entry? value) :Map_Entry
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
  (and (= runtime-type "Integer")
       (= target-type "Real")))

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

;; ---------------------------------------------------------------------------
;; Type arguments in a runtime type test
;;
;; `convert x to y: Some[String]`, and a `match` clause `Some[String](...)`,
;; test a generic class *with* its type arguments (Definition 4.7: arguments
;; are substituted, not erased; 5.x: the test succeeds only when the value's
;; class conforms to the target). Both backends test the class alone; these
;; add the arguments. A backend supplies the value's own arguments as base
;; type names -- an argument it cannot know (erased in generic code, "Any")
;; never makes the test fail, so this only rejects what is known not to match.
;; ---------------------------------------------------------------------------

(defn- type-base-name
  [t]
  (cond
    (string? t) t
    (map? t) (:base-type t)
    :else nil))

(defn- substitute-type-params
  [t subst]
  (cond
    (string? t) (get subst t t)
    (map? t) (cond-> (update t :base-type #(get subst % %))
               (:type-args t) (update :type-args (fn [as] (mapv #(substitute-type-params % subst) as)))
               (:type-params t) (update :type-params (fn [as] (mapv #(substitute-type-params % subst) as))))
    :else t))

(defn instantiation-at
  "The type arguments that CLASS-NAME, instantiated with ARGS, supplies to its
   ancestor TARGET-NAME through its `inherit` clauses, or nil when TARGET-NAME
   is not an ancestor. CLASS-LOOKUP maps a class name to its definition
   (:generic-params, :parents [{:parent :generic-args}])."
  ([class-lookup class-name args target-name]
   (instantiation-at class-lookup class-name args target-name #{}))
  ([class-lookup class-name args target-name seen]
   (cond
     (= class-name target-name) (vec args)
     (contains? seen class-name) nil
     :else
     (when-let [class-def (class-lookup class-name)]
       (let [subst (zipmap (map :name (:generic-params class-def)) args)]
         (some (fn [{:keys [parent generic-args]}]
                 (instantiation-at class-lookup parent
                                   (mapv #(substitute-type-params % subst) (or generic-args []))
                                   target-name (conj seen class-name)))
               (:parents class-def)))))))

(defn type-args-compatible?
  "False only when the value -- of class VALUE-CLASS with its own type
   arguments VALUE-ARGS -- is known to instantiate TARGET-CLASS with different
   arguments from TARGET-ARGS, compared by base type name. An argument for
   which KNOWN-TYPE? is false on either side (an erased \"Any\", a type
   parameter of generic code) is not known, and matches anything."
  [class-lookup known-type? value-class value-args target-class target-args]
  (or (empty? target-args)
      (let [actual (instantiation-at class-lookup value-class value-args target-class)]
        (or (nil? actual)
            (not= (count actual) (count target-args))
            (every? true?
                    (map (fn [a t]
                           (let [a (type-base-name a)
                                 t (type-base-name t)]
                             (or (not (known-type? a))
                                 (not (known-type? t))
                                 (= a t))))
                         actual target-args))))))

(def ^:private builtin-type-names
  #{"Integer" "Integer64" "Integer32" "Integer16" "Byte" "Real" "Char" "Boolean"
    "String" "Array" "Map" "Set" "Function" "Task" "Channel" "Cursor"
    "Comparable" "Hashable" "Console" "Process" "Min_Heap" "Map_Entry"
    "Atomic_Integer" "Atomic_Integer64" "Atomic_Boolean" "Atomic_Reference"})

(defn known-type-name-fn
  "A known-type? for type-args-compatible?: a builtin type, or a class
   CLASS-LOOKUP knows. Not \"Any\" (what an erased argument reads as), nor a
   type parameter of generic code."
  [class-lookup]
  (fn [n]
    (boolean (and (string? n)
                  (not= n "Any")
                  (or (contains? builtin-type-names n)
                      (class-lookup n))))))
