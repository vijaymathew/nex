(ns nex.types.bootstrap)

(defn build-function-base-class
  []
  (let [make-method (fn [n]
                      {:type :method
                       :name (str "call" n)
                       :params (if (zero? 0)
                                 []
                                 (mapv (fn [i]
                                         {:name (str "arg" i) :type "Any"})
                                       (range 1 (inc n))))
                       :return-type "Any"
                       :declaration-only? true
                       :note nil
                       :require nil
                       :body []
                       :ensure nil})
        methods (vec (cons (make-method 0) (mapv make-method (range 1 33))))]
    {:type :class
     :name "Function"
     :generic-params nil
     :note nil
     :parents nil
     :body [{:type :feature-section
             :visibility {:type :public}
             :members methods}]
     :invariant nil}))

(defn- deferred-method
  [name return-type]
  {:type :method :name name :params nil :return-type return-type
   :declaration-only? true
   :note nil :require nil :body [] :ensure nil})

(defn build-iterable-base-class
  "`deferred class Iterable [T]`: anything `across` can walk, by asking it
   for a `cursor(): Cursor[T]`. Array, Set, String and Map are Iterable, and so
   is every Cursor."
  []
  {:type :class
   :name "Iterable"
   :deferred? true
   :generic-params [{:name "T" :constraint nil :detachable false}]
   :note nil
   :parents nil
   :body [{:type :feature-section
           :visibility {:type :public}
           :members [(deferred-method "cursor" {:base-type "Cursor" :type-args ["T"]})]}]
   :invariant nil})

(defn build-cursor-base-class
  "`deferred class Cursor [T] inherit Iterable[T]`: a walk over some elements,
   `start`, then `item` and `next` until `at_end`. A cursor is itself
   Iterable: its `cursor` is the cursor."
  []
  {:type :class
   :name "Cursor"
   :deferred? true
   :generic-params [{:name "T" :constraint nil :detachable false}]
   :note nil
   :parents [{:parent "Iterable" :generic-args ["T"]}]
   :body [{:type :feature-section
           :visibility {:type :public}
           :members [(deferred-method "start" nil)
                     {:type :method :name "cursor" :params nil
                      :return-type {:base-type "Cursor" :type-args ["T"]}
                      :note nil :require nil
                      :body [{:type :assign
                              :target "result"
                              :value {:type :this}}]
                      :ensure nil}
                     (deferred-method "item" "T")
                     (deferred-method "next" nil)
                     (deferred-method "at_end" "Boolean")]}]
   :invariant nil})

(defn build-comparable-base-class
  []
  {:type :class
   :name "Comparable"
   :deferred? true
   :generic-params nil
   :note nil
   :parents nil
   :body [{:type :feature-section
           :visibility {:type :public}
           :members [{:type :method :name "compare"
                      :params [{:name "a" :type "Any"}]
                      :return-type "Integer"
                      :declaration-only? true
                      :note nil :require nil :body [] :ensure nil}]}]
   :invariant nil})

(defn build-any-base-class
  []
  {:type :class
   :name "Any"
   :deferred? false
   :generic-params nil
   :note nil
   :parents nil
   :body []
   :invariant nil})

(defn build-builtin-scalar-class
  [name]
  {:type :class
   :name name
   :deferred? false
   :generic-params nil
   :note nil
   :parents [{:parent "Any"} {:parent "Comparable"}]
   :body []
   :invariant nil})

(def builtin-scalar-class-names
  ["String" "Integer" "Byte" "Integer16" "Integer32" "Real" "Boolean" "Char"])

(defn base-class-defs
  "The standard environment's base classes, in registration order (each
   class's parents precede it): Any, Function, Iterable, Cursor, Comparable,
   then the builtin scalars. Shared by every engine that needs the builtin
   roots of the class hierarchy."
  []
  (into [(build-any-base-class)
         (build-function-base-class)
         (build-iterable-base-class)
         (build-cursor-base-class)
         (build-comparable-base-class)]
        (map build-builtin-scalar-class builtin-scalar-class-names)))

(def ^:private non-creatable-builtin-types
  "Built-in types whose values never come from `create`, each with how a
   value of that type is obtained instead. Integer, Real, Char, Boolean and
   Function are keywords, so `create` on them is already a syntax error.
   (Map_Entry is not here: it has `make(key, value)`, and a bare
   `create Map_Entry` is rejected by that constructor's own checks.)"
  {"Task"      "a Task comes from `spawn`"
   "Byte"      "write a literal such as `65u8`, or convert with `n.to_byte()`"
   "Integer16" "write an Integer and convert it with `n.to_integer16()`"
   "Integer32" "write an Integer and convert it with `n.to_integer32()`"
   "Any"       "Any is the root of every type; create an instance of a concrete class instead"
   "Void"      "Void has no values"
   "Nil"       "the only Nil value is `nil`"})

(def ^:private base-class-def-set (delay (set (base-class-defs))))

(defn non-creatable-message
  "The error for `create CLASS-NAME` when CLASS-NAME is a built-in type that
   has no constructor, or nil when it can be created. CLASS-DEF is what the
   engine has registered under that name (nil if nothing): a program may
   define its own class with a built-in name (`class Task`), and that one is
   created as usual. Engine registrations of built-ins are either these base
   class defs or carry no `:type :class`; a parsed class always does."
  [class-name class-def]
  (when-let [hint (get non-creatable-builtin-types class-name)]
    (when (or (not= :class (:type class-def))
              (contains? @base-class-def-set class-def))
      (str "Cannot create " class-name "; " hint))))
