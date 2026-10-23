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
