(ns nex.interpreter
  (:require [clojure.string :as str]
            #?(:clj [nex.parser :as parser])))

;;
;; Mutable Collections (platform abstraction)
;;

;; Array helpers
(defn nex-array [] #?(:clj (java.util.ArrayList.) :cljs #js []))
(defn nex-array-from [coll] #?(:clj (java.util.ArrayList. (vec coll)) :cljs (js/Array.from (to-array coll))))
(defn nex-array? [v] #?(:clj (instance? java.util.ArrayList v) :cljs (array? v)))
(defn nex-array-get [arr idx] #?(:clj (.get arr idx) :cljs (aget arr idx)))
(defn nex-array-add [arr val] #?(:clj (.add arr val) :cljs (.push arr val)))
(defn nex-array-add-at [arr idx val] #?(:clj (.add arr idx val) :cljs (.splice arr idx 0 val)))
(defn nex-array-set [arr idx val] #?(:clj (.set arr idx val) :cljs (aset arr idx val)))
(defn nex-array-size [arr] #?(:clj (.size arr) :cljs (.-length arr)))
(defn nex-array-empty? [arr] #?(:clj (.isEmpty arr) :cljs (zero? (.-length arr))))
(defn nex-array-contains [arr elem] #?(:clj (.contains arr elem) :cljs (.includes arr elem)))
(defn nex-array-index-of [arr elem] #?(:clj (.indexOf arr elem) :cljs (.indexOf arr elem)))
(defn nex-array-remove [arr idx] #?(:clj (.remove arr (int idx)) :cljs (.splice arr idx 1)))
(defn nex-array-reverse [arr] #?(:clj (java.util.ArrayList. (.reversed arr)) :cljs (js/Array.from (.reverse (.slice arr)))))
(defn nex-array-sort [arr] #?(:clj (.sort arr nil) :cljs (.sort arr)))
(defn nex-array-slice [arr start end] #?(:clj (.subList arr start end) :cljs (.slice arr start end)))

;; Map helpers
(defn nex-map [] #?(:clj (java.util.HashMap.) :cljs (js/Map.)))
(defn nex-map-from [pairs]
  #?(:clj (java.util.HashMap. (into {} pairs))
     :cljs (js/Map. (to-array (map to-array pairs)))))
(defn nex-map? [v] #?(:clj (instance? java.util.HashMap v) :cljs (instance? js/Map v)))
(defn nex-map-get [m key] #?(:clj (.get m key) :cljs (.get m key)))
(defn nex-map-put [m key val] #?(:clj (.put m key val) :cljs (.set m key val)))
(defn nex-map-size [m] #?(:clj (.size m) :cljs (.-size m)))
(defn nex-map-empty? [m] #?(:clj (.isEmpty m) :cljs (zero? (.-size m))))
(defn nex-map-contains-key [m key] #?(:clj (.containsKey m key) :cljs (.has m key)))
(defn nex-map-keys [m] #?(:clj (vec (.keySet m)) :cljs (vec (es6-iterator-seq (.keys m)))))
(defn nex-map-values [m] #?(:clj (vec (.values m)) :cljs (vec (es6-iterator-seq (.values m)))))
(defn nex-map-remove [m key] #?(:clj (.remove m key) :cljs (.delete m key)))

;; Math helpers
(defn nex-abs [n] #?(:clj (Math/abs (double n)) :cljs (js/Math.abs n)))
(defn nex-round [n] #?(:clj (Math/round (double n)) :cljs (js/Math.round n)))

;; Subscript helper (works on both Array and Map)
(defn nex-coll-get [coll idx]
  (cond
    (nex-array? coll) (nex-array-get coll idx)
    (nex-map? coll) (nex-map-get coll idx)
    :else #?(:clj (.get coll idx) :cljs (aget coll idx))))

;; Char detection helper
(defn nex-char? [v]
  #?(:clj (char? v)
     :cljs (and (string? v) (== (.-length v) 1))))

;;
;; Runtime Environment
;;

(defrecord Environment [bindings parent])

(defn make-env
  "Create a new environment, optionally with a parent scope."
  ([]
   (make-env nil))
  ([parent]
   (->Environment (atom {}) parent)))

(defn env-lookup
  "Look up a variable in the environment, searching parent scopes if needed."
  [env var-name]
  (let [bindings @(:bindings env)]
    (if (contains? bindings var-name)
      (get bindings var-name)
      (if-let [parent (:parent env)]
        (env-lookup parent var-name)
        (throw (ex-info (str "Undefined variable: " var-name)
                        {:var-name var-name}))))))

(defn env-define
  "Define a variable in the current environment."
  [env var-name value]
  (swap! (:bindings env) assoc var-name value)
  value)

(defn env-set!
  "Set a variable in the environment where it's defined."
  [env var-name value]
  (if (contains? @(:bindings env) var-name)
    (env-define env var-name value)
    (if-let [parent (:parent env)]
      (env-set! parent var-name value)
      (throw (ex-info (str "Cannot assign to undefined variable: " var-name)
                      {:var-name var-name})))))

;;
;; Runtime Context (holds classes, globals, current environment)
;;

(defrecord Context [classes globals current-env output imports specialized-classes])

(defn make-context
  "Create a new runtime context."
  []
  (let [globals (make-env)]
    (->Context
     (atom {})           ; classes registry
     globals             ; global environment
     globals             ; current environment starts as global
     (atom [])           ; output accumulator
     (atom [])           ; imports registry
     (atom {}))))        ; specialized classes cache

(defn register-class
  "Register a class definition in the context."
  [ctx class-def]
  (swap! (:classes ctx) assoc (:name class-def) class-def))

(defn lookup-class
  "Look up a class definition by name."
  [ctx class-name]
  (or (get @(:classes ctx) class-name)
      (get @(:specialized-classes ctx) class-name)
      (throw (ex-info (str "Undefined class: " class-name)
                      {:class-name class-name}))))

(defn register-specialized-class
  "Register a specialized (type-realized) class in the context."
  [ctx class-def]
  (swap! (:specialized-classes ctx) assoc (:name class-def) class-def))

(defn lookup-specialized-class
  "Look up a specialized class definition by name."
  [ctx class-name]
  (get @(:specialized-classes ctx) class-name))

;;
;; Generic Class Specialization
;;

(defn specialized-class-name
  "Build a specialized class name like Box[Integer]."
  [base-name type-args]
  (str base-name "[" (clojure.string/join "," type-args) "]"))

(defn substitute-type
  "Replace type parameter strings with concrete types using type-map."
  [type-expr type-map]
  (cond
    (string? type-expr)
    (get type-map type-expr type-expr)

    (map? type-expr)
    (-> type-expr
        (update :base-type #(get type-map % %))
        (update :type-args (fn [args]
                             (when args
                               (mapv #(substitute-type % type-map) args)))))

    :else type-expr))

(defn substitute-in-body
  "Walk class body sections, substituting types using type-map."
  [body type-map]
  (mapv (fn [section]
          (cond
            (= (:type section) :feature-section)
            (update section :members
                    (fn [members]
                      (mapv (fn [member]
                              (case (:type member)
                                :field
                                (update member :field-type #(substitute-type % type-map))

                                :method
                                (-> member
                                    (update :params
                                            (fn [params]
                                              (when params
                                                (mapv #(update % :type (fn [t] (substitute-type t type-map)))
                                                      params))))
                                    (update :return-type
                                            (fn [rt] (when rt (substitute-type rt type-map)))))

                                member))
                            members)))

            (= (:type section) :constructors)
            (update section :constructors
                    (fn [ctors]
                      (mapv (fn [ctor]
                              (update ctor :params
                                      (fn [params]
                                        (when params
                                          (mapv #(update % :type (fn [t] (substitute-type t type-map)))
                                                params)))))
                            ctors)))

            :else section))
        body))

(defn specialize-class
  "Create a specialized version of a generic class with concrete type args."
  [generic-class-def type-args]
  (let [generic-params (:generic-params generic-class-def)
        type-map (into {} (map (fn [param arg]
                                 [(:name param) arg])
                               generic-params type-args))
        spec-name (specialized-class-name (:name generic-class-def) type-args)]
    (-> generic-class-def
        (assoc :name spec-name)
        (assoc :template-name (:name generic-class-def))
        (assoc :generic-params nil)
        (update :body #(substitute-in-body % type-map)))))

(defn add-output
  "Add output to the context (for print statements)."
  [ctx value]
  (swap! (:output ctx) conj value))

;;
;; Object Representation
;;

(defrecord NexObject [class-name fields])

(defn make-object
  "Create a new object instance."
  [class-name field-values]
  (->NexObject class-name field-values))

;;
;; Forward declarations
;;

(declare eval-node)
(declare get-all-fields)

;;
;; Contract Checking
;;

;; Contract types
(def Precondition "Precondition")
(def Postcondition "Postcondition")
(def Loop-invariant "Loop invariant")
(def Class-invariant "Class invariant")

(defn report-contract-violation
  [contract-type label condition]
  (throw (ex-info (str contract-type " violation: " label)
                  {:contract-type contract-type
                   :label label
                   :condition condition})))

(defn check-assertions
  "Check a list of assertions. Throws exception if any fail."
  [ctx assertions contract-type]
  (doseq [{:keys [label condition]} assertions]
    (let [result (eval-node ctx condition)]
      (when-not result
        (report-contract-violation contract-type label condition)))))

(defn check-class-invariant
  "Check the class invariant for an object or class context."
  [ctx class-def]
  (when-let [invariant-assertions (:invariant class-def)]
    (check-assertions ctx invariant-assertions Class-invariant)))

;;
;; Inheritance Support
;;

(defn get-parent-classes
  "Get the list of parent class definitions for a class."
  [ctx class-def]
  (when-let [parents (:parents class-def)]
    (mapv (fn [parent-info]
            (let [parent-class (lookup-class ctx (:parent parent-info))]
              (assoc parent-info :class-def parent-class)))
          parents)))

(defn apply-renames
  "Apply rename mappings to a method name."
  [method-name renames]
  (if-let [rename-map (first (filter #(= (:new-name %) method-name) renames))]
    (:old-name rename-map)
    method-name))

(defn lookup-method-in-class
  "Look up a method in a specific class (without searching parents)."
  [class-def method-name]
  (->> (:body class-def)
       (mapcat (fn [section]
                 (cond
                   (= (:type section) :feature-section) (:members section)
                   (= (:type section) :method) [section]
                   :else [])))
       (filter #(= (:type %) :method))
       (filter #(= (:name %) method-name))
       first))

(defn lookup-method-with-inheritance
  "Look up a method in a class, searching parent classes if needed."
  [ctx class-def method-name]
  ;; First look in the current class
  (if-let [method (lookup-method-in-class class-def method-name)]
    {:method method :source-class class-def}
    ;; If not found, search parent classes
    (when-let [parents (get-parent-classes ctx class-def)]
      (some (fn [parent-info]
              (let [parent-class (:class-def parent-info)
                    ;; Apply renames: if method-name was renamed in this parent,
                    ;; look for the original name
                    original-name (apply-renames method-name (:renames parent-info))]
                (when-let [result (lookup-method-with-inheritance ctx parent-class original-name)]
                  result)))
            parents))))

(defn combine-assertions
  "Combine assertions from parent and child methods (for contracts)."
  [parent-assertions child-assertions]
  (vec (concat (or parent-assertions []) (or child-assertions []))))

;;
;; Built-in Functions
;;

(def builtins
  {"print"
   (fn [ctx & args]
     (let [output (str/join " " (map #(if (instance? NexObject %)
                                        (str "#<" (:class-name %) ">")
                                        (pr-str %))
                                     args))]
       (add-output ctx output)
       nil))

   "println"
   (fn [ctx & args]
     (let [output (str/join " " (map #(if (instance? NexObject %)
                                        (str "#<" (:class-name %) ">")
                                        (pr-str %))
                                     args))]
       (add-output ctx output)
       nil))})

;;
;; Operator Implementations
;;

(defn apply-binary-op
  "Apply a binary operator to two values."
  [op left right]
  (case op
    "+" (if (or (string? left) (string? right))
          ;; String concatenation
          (str left right)
          ;; Numeric addition
          (+ left right))
    "-" (- left right)
    "*" (* left right)
    "/" (if (zero? right)
          (throw (ex-info "Division by zero" {:left left :right right}))
          (/ left right))
    "=" (= left right)
    "/=" (not= left right)
    "<" (< left right)
    "<=" (<= left right)
    ">" (> left right)
    ">=" (>= left right)
    "and" (and left right)
    "or" (or left right)
    (throw (ex-info (str "Unknown binary operator: " op)
                    {:operator op}))))

(defn apply-unary-op
  "Apply a unary operator to a value."
  [op value]
  (case op
    "-" (- value)
    (throw (ex-info (str "Unknown unary operator: " op)
                    {:operator op}))))

(defn get-default-field-value
  "Get default value for a field type"
  [field-type]
  (cond
    ;; Handle parameterized types
    (map? field-type)
    (case (:base-type field-type)
      "Array" (nex-array)
      "Map" (nex-map)
      nil)

    ;; Handle simple types
    (string? field-type)
    (case field-type
      "Integer" 0
      "Integer64" 0
      "Real" 0.0
      "Decimal" 0.0
      "Char" \0
      "Boolean" false
      "String" ""
      nil)

    :else nil))

;;
;; Built-in Type Methods
;;

(def builtin-type-methods
  "Methods available on built-in types"
  {:String
   {"length"      (fn [s & _] (count s))
    "index_of"    (fn [s ch & _]
                    (let [idx (str/index-of s (str ch))]
                      (if idx idx -1)))
    "substring"   (fn [s start end & _] (subs s start end))
    "to_upper"    (fn [s & _] (str/upper-case s))
    "to_lower"    (fn [s & _] (str/lower-case s))
    "contains"    (fn [s substr & _] (str/includes? s substr))
    "starts_with" (fn [s prefix & _] (str/starts-with? s prefix))
    "ends_with"   (fn [s suffix & _] (str/ends-with? s suffix))
    "trim"        (fn [s & _] (str/trim s))
    "replace"     (fn [s old new & _] (str/replace s old new))
    "char_at"     (fn [s idx & _] (get s idx))
    "split"       (fn [s delim & _] (vec (str/split s (re-pattern delim))))
    ;; String operator methods
    "plus"        (fn [s other & _] (str s other))
    "equals"      (fn [s other & _] (= s other))
    "not_equals"  (fn [s other & _] (not= s other))
    "less_than"   (fn [s other & _] (neg? (compare s other)))
    "less_than_or_equal" (fn [s other & _] (<= (compare s other) 0))
    "greater_than" (fn [s other & _] (pos? (compare s other)))
    "greater_than_or_equal" (fn [s other & _] (>= (compare s other) 0))}

   :Integer
   {"to_string"         (fn [n & _] (str n))
    "abs"               (fn [n & _] (nex-abs n))
    "min"               (fn [n other & _] (min n other))
    "max"               (fn [n other & _] (max n other))
    ;; Arithmetic operator methods
    "plus"              (fn [n other & _] (+ n other))
    "minus"             (fn [n other & _] (- n other))
    "times"             (fn [n other & _] (* n other))
    "divided_by"        (fn [n other & _] (/ n other))
    ;; Comparison operator methods
    "equals"            (fn [n other & _] (= n other))
    "not_equals"        (fn [n other & _] (not= n other))
    "less_than"         (fn [n other & _] (< n other))
    "less_than_or_equal" (fn [n other & _] (<= n other))
    "greater_than"      (fn [n other & _] (> n other))
    "greater_than_or_equal" (fn [n other & _] (>= n other))}

   :Integer64
   {"to_string"         (fn [n & _] (str n))
    "abs"               (fn [n & _] (nex-abs n))
    "min"               (fn [n other & _] (min n other))
    "max"               (fn [n other & _] (max n other))
    ;; Arithmetic operator methods
    "plus"              (fn [n other & _] (+ n other))
    "minus"             (fn [n other & _] (- n other))
    "times"             (fn [n other & _] (* n other))
    "divided_by"        (fn [n other & _] (/ n other))
    ;; Comparison operator methods
    "equals"            (fn [n other & _] (= n other))
    "not_equals"        (fn [n other & _] (not= n other))
    "less_than"         (fn [n other & _] (< n other))
    "less_than_or_equal" (fn [n other & _] (<= n other))
    "greater_than"      (fn [n other & _] (> n other))
    "greater_than_or_equal" (fn [n other & _] (>= n other))}

   :Real
   {"to_string"         (fn [n & _] (str n))
    "abs"               (fn [n & _] (nex-abs n))
    "min"               (fn [n other & _] (min n other))
    "max"               (fn [n other & _] (max n other))
    "round"             (fn [n & _] (nex-round n))
    ;; Arithmetic operator methods
    "plus"              (fn [n other & _] (+ n other))
    "minus"             (fn [n other & _] (- n other))
    "times"             (fn [n other & _] (* n other))
    "divided_by"        (fn [n other & _] (/ n other))
    ;; Comparison operator methods
    "equals"            (fn [n other & _] (= n other))
    "not_equals"        (fn [n other & _] (not= n other))
    "less_than"         (fn [n other & _] (< n other))
    "less_than_or_equal" (fn [n other & _] (<= n other))
    "greater_than"      (fn [n other & _] (> n other))
    "greater_than_or_equal" (fn [n other & _] (>= n other))}

   :Decimal
   {"to_string"         (fn [n & _] (str n))
    "abs"               (fn [n & _] (nex-abs n))
    "min"               (fn [n other & _] (min n other))
    "max"               (fn [n other & _] (max n other))
    "round"             (fn [n & _] (nex-round n))
    ;; Arithmetic operator methods
    "plus"              (fn [n other & _] (+ n other))
    "minus"             (fn [n other & _] (- n other))
    "times"             (fn [n other & _] (* n other))
    "divided_by"        (fn [n other & _] (/ n other))
    ;; Comparison operator methods
    "equals"            (fn [n other & _] (= n other))
    "not_equals"        (fn [n other & _] (not= n other))
    "less_than"         (fn [n other & _] (< n other))
    "less_than_or_equal" (fn [n other & _] (<= n other))
    "greater_than"      (fn [n other & _] (> n other))
    "greater_than_or_equal" (fn [n other & _] (>= n other))}

   :Char
   {"to_string"   (fn [c & _] (str c))
    "to_upper"    (fn [c & _] (str/upper-case (str c)))
    "to_lower"    (fn [c & _] (str/lower-case (str c)))}

   :Boolean
   {"to_string"   (fn [b & _] (str b))
    ;; Boolean operator methods
    "and"         (fn [b other & _] (and b other))
    "or"          (fn [b other & _] (or b other))
    "not"         (fn [b & _] (not b))
    "equals"      (fn [b other & _] (= b other))
    "not_equals"  (fn [b other & _] (not= b other))}

   :Array
   {"get"         (fn [arr index & _] (nex-array-get arr index))
    "add"         (fn [arr value & _] (nex-array-add arr value))
    "at"          (fn [arr index value & _] (nex-array-add-at arr index value))
    "set"         (fn [arr index value & _] (nex-array-set arr index value))
    "length"      (fn [arr & _] (nex-array-size arr))
    "is_empty"    (fn [arr & _] (nex-array-empty? arr))
    "contains"    (fn [arr elem & _] (nex-array-contains arr elem))
    "index_of"    (fn [arr elem & _]
                    (let [idx (nex-array-index-of arr elem)]
                      (if (>= idx 0) idx -1)))
    "remove"      (fn [arr idx & _] (nex-array-remove arr idx))
    "reverse"     (fn [arr _] (nex-array-reverse arr))
    "sort"        (fn [arr & _] (nex-array-sort arr))
    "slice"       (fn [arr start end & _] (nex-array-slice arr start end))}

   :Map
   {"get"         (fn [m key & _]
                    (let [v (nex-map-get m key)]
                      (if (nil? v)
                        (report-contract-violation Precondition "key_must_exist" "has_key")
                        v)))
    "try_get"      (fn [m key default & _]
                    (let [v (nex-map-get m key)]
                      (if (nil? v)
                        default
                        v)))
    "at"           (fn [m key val & _] (nex-map-put m key val))
    "size"         (fn [m & _] (nex-map-size m))
    "is_empty"     (fn [m & _] (nex-map-empty? m))
    "contains_key" (fn [m key & _] (nex-map-contains-key m key))
    "keys"         (fn [m & _] (nex-map-keys m))
    "values"       (fn [m & _] (nex-map-values m))
    "remove"       (fn [m key & _] (nex-map-remove m key))}})

(defn get-type-name
  "Get the type name for a value"
  [value]
  (cond
    (string? value) :String
    (integer? value) :Integer
    (float? value) :Real
    (double? value) :Decimal
    (nex-char? value) :Char
    (boolean? value) :Boolean
    (nex-array? value) :Array
    (nex-map? value) :Map
    :else nil))

(defn call-builtin-method
  "Call a built-in method on a primitive value"
  [target value method-name args]
  (if-let [method-fn
           (when-let [type-name (get-type-name value)]
             (when-let [methods (get builtin-type-methods type-name)]
               (get methods method-name)))]
    (apply method-fn value args)
    (throw (ex-info (str "Method not found on type: " method-name)
                    {:target target :value value :method method-name}))))
;;
;; Node Evaluators
;;

(defmulti eval-node
  "Evaluate an AST node in the given context."
  (fn [ctx node]
    (cond
      (map? node) (:type node)
      :else :literal)))

#?(:clj
   (defn find-intern-file
     "Search for an intern file in the specified locations.
      Returns the absolute path if found, otherwise throws an exception."
     [path class-name]
     (let [filename (str class-name ".nex")
           ;; Search locations in order
           locations [(str "./" filename)                                          ; 1. Current directory
                      (str "./libs/" path "/src/" filename)                        ; 2. ./libs/path/src/
                      (str (System/getProperty "user.home") "/.nex/deps/"
                           path "/src/" filename)]                                 ; 3. ~/.nex/deps/path/src/
           found (first (filter #(-> % clojure.java.io/file .exists) locations))]
       (if found
         found
         (throw (ex-info (str "Cannot find intern file for " path "/" class-name)
                        {:path path
                         :class-name class-name
                         :searched-locations locations})))))
   :cljs
   (defn find-intern-file
     "Search for an intern file in the specified locations.
      Returns the absolute path if found, otherwise throws an exception."
     [path class-name]
     (let [fs (js/require "fs")
           path-module (js/require "path")
           filename (str class-name ".nex")
           home (or (.-HOME js/process.env) (.-USERPROFILE js/process.env) ".")
           ;; Search locations in order
           locations [(str "./" filename)
                      (str "./libs/" path "/src/" filename)
                      (str home "/.nex/deps/" path "/src/" filename)]
           found (first (filter #(.existsSync fs %) locations))]
       (if found
         found
         (throw (ex-info (str "Cannot find intern file for " path "/" class-name)
                        {:path path
                         :class-name class-name
                         :searched-locations locations}))))))

#?(:clj
   (defn process-intern
     "Load and interpret an external file, then register the class with the given alias."
     [ctx {:keys [path class-name alias]}]
     (let [file-path (find-intern-file path class-name)
           ;; Load and parse the external file
           file-content (slurp file-path)
           file-ast (parser/ast file-content)
           ;; Interpret the file to register its classes
           _ (eval-node ctx file-ast)
           ;; Look up the class that was registered
           registered-class (get @(:classes ctx) class-name)
           ;; Determine the name to use (alias or original)
           intern-name (or alias class-name)]
       (when-not registered-class
         (throw (ex-info (str "Class " class-name " not found in file " file-path)
                        {:file file-path :class-name class-name})))
       ;; Register the class under the new name (if alias is provided)
       (when alias
         (swap! (:classes ctx) assoc alias registered-class))
       ;; Return the class name that was registered
       intern-name))
   :cljs
   (defn process-intern
     "In ClojureScript, intern is not supported. Use registerClass instead."
     [ctx {:keys [path class-name alias]}]
     (throw (ex-info "intern is not supported in ClojureScript. Parse on the JVM and send the AST, or use registerClass to manually register classes."
                    {:path path :class-name class-name :alias alias}))))

(defmethod eval-node :program
  [ctx {:keys [imports interns classes calls]}]
  ;; First, store all import statements (for code generation)
  (doseq [import-node imports]
    (when (map? import-node)
      (swap! (:imports ctx) conj import-node)))

  ;; Then, process all intern statements
  (doseq [intern-node interns]
    (when (map? intern-node)
      (process-intern ctx intern-node)))

  ;; Register all class definitions
  (doseq [class-node classes]
    (when (map? class-node)
      (register-class ctx class-node)))

  ;; Finally, execute any top-level method calls
  (doseq [call-node calls]
    (when (map? call-node)
      (eval-node ctx call-node)))

  ;; Return the context for inspection
  ctx)

(defmethod eval-node :class
  [ctx class-def]
  ;; Classes are just registered, not executed
  (register-class ctx class-def)
  nil)

(defmethod eval-node :call
  [ctx {:keys [target method args]}]
  (let [;; Evaluate arguments
        arg-values (mapv #(eval-node ctx %) args)]

    (if target
      ;; Object method call: target.method(args)
      (let [obj (env-lookup (:current-env ctx) target)]
        (if (instance? NexObject obj)
          ;; Call method on object
          (let [class-def (lookup-class ctx (:class-name obj))
                method-lookup (lookup-method-with-inheritance ctx class-def method)]
            (if method-lookup
              (let [method-def (:method method-lookup)
                    source-class (:source-class method-lookup)
                    all-fields (get-all-fields ctx class-def)
                    has-postconditions? (seq (:ensure method-def))
                    ;; Snapshot old field values if postconditions exist
                    old-values (when has-postconditions? (:fields obj))]
                ;; Execute method with object context
                (let [method-env (make-env (:current-env ctx))
                      ;; Bind parameters
                      params (:params method-def)
                      _ (when params
                          (doseq [[param arg-val] (map vector params arg-values)]
                            (env-define method-env (:name param) arg-val)))
                      ;; Bind fields as local variables
                      _ (doseq [[field-name field-val] (:fields obj)]
                          (env-define method-env (name field-name) field-val))
                      ;; Initialize implicit 'result' variable
                      return-type (:return-type method-def)
                      default-result (if return-type
                                      (get-default-field-value return-type)
                                      nil)
                      _ (env-define method-env "result" default-result)
                      ;; Bind "Current" to the current object for self-calls
                      _ (env-define method-env "Current" obj)
                      new-ctx (-> ctx
                                 (assoc :current-env method-env)
                                 (assoc :current-object obj)
                                 (assoc :current-target target)
                                 (assoc :old-values old-values))
                      ;; Check pre-conditions
                      _ (when-let [require-assertions (:require method-def)]
                          (check-assertions new-ctx require-assertions Precondition))
                      ;; Execute method body
                      _ (doseq [stmt (:body method-def)]
                          (eval-node new-ctx stmt))
                      ;; Update object fields from modified environment
                      updated-fields (reduce (fn [m field]
                                              (let [field-name (:name field)
                                                    field-key (keyword field-name)]
                                                (if-let [val (try
                                                              (env-lookup method-env field-name)
                                                              (catch #?(:clj Exception :cljs :default) _ nil))]
                                                  (assoc m field-key val)
                                                  m)))
                                            (:fields obj)
                                            all-fields)
                      ;; Create updated object
                      updated-obj (make-object (:class-name obj) updated-fields)
                      ;; Get the final value of 'result'
                      result (env-lookup method-env "result")]
                  ;; Check post-conditions and invariant with rollback support
                  (try
                    ;; Check post-conditions
                    (when-let [ensure-assertions (:ensure method-def)]
                      (check-assertions new-ctx ensure-assertions Postcondition))
                    ;; Check class invariant
                    (check-class-invariant new-ctx class-def)
                    ;; Success: update object in parent environment
                    (env-set! (:current-env ctx) target updated-obj)
                    result
                    (catch #?(:clj Exception :cljs :default) e
                      ;; Postcondition or invariant failed: restore original object
                      (env-set! (:current-env ctx) target obj)
                      (throw e)))))
              ;; Method not found - check if it's a field (query access)
              (let [all-fields (get-all-fields ctx class-def)
                    field (first (filter #(= (:name %) method) all-fields))]
                (if (and field (empty? arg-values))
                  ;; Field access as query (uniform access principle)
                  (get (:fields obj) (keyword method))
                  ;; Not a field or has arguments
                  (throw (ex-info (str "Method not found: " method)
                                  {:object obj :method method}))))))
          ;; Not a NexObject - check if it's a primitive type with built-in methods
          (call-builtin-method target obj method arg-values)))

      ;; Method call without target: method(args)
      ;; First check if we're inside an object method (self-call)
      (if-let [current-obj (:current-object ctx)]
        ;; We're inside a method - call on current object
        (let [class-def (lookup-class ctx (:class-name current-obj))
              method-lookup (lookup-method-with-inheritance ctx class-def method)]
          (if method-lookup
            ;; Before making self-call, update object with current field values
            ;; so the called method sees the changes
            (let [all-fields (get-all-fields ctx class-def)
                  current-env (:current-env ctx)
                  ;; Read current field values from the environment
                  updated-fields (reduce (fn [m field]
                                          (let [field-name (:name field)
                                                field-key (keyword field-name)]
                                            (if-let [val (try
                                                          (env-lookup current-env field-name)
                                                          (catch #?(:clj Exception :cljs :default) _ nil))]
                                              (assoc m field-key val)
                                              m)))
                                        (:fields current-obj)
                                        all-fields)
                  ;; Create updated object
                  updated-obj (make-object (:class-name current-obj) updated-fields)
                  ;; Update in parent context
                  _ (env-set! (-> ctx :current-env :parent) (:current-target ctx) updated-obj)
                  ;; Make the method call
                  result (eval-node ctx {:type :call
                                        :target (:current-target ctx)
                                        :method method
                                        :args args})
                  ;; After call, read updated field values back
                  called-obj (env-lookup (-> ctx :current-env :parent) (:current-target ctx))
                  _ (doseq [[field-name field-val] (:fields called-obj)]
                      (env-set! current-env (name field-name) field-val))]
              result)
            ;; Method not found on current object - try builtin
            (if-let [builtin (get builtins method)]
              (apply builtin ctx arg-values)
              (throw (ex-info (str "Undefined method: " method)
                              {:function method :object current-obj})))))
        ;; Not inside a method - check for global function/builtin
        (if-let [builtin (get builtins method)]
          (apply builtin ctx arg-values)
          (throw (ex-info (str "Undefined function: " method)
                          {:function method})))))))

(defmethod eval-node :assign
  [ctx {:keys [target value]}]
  (let [val (eval-node ctx value)]
    ;; Assignment (without let) ONLY updates existing variables
    ;; It should fail if the variable doesn't exist
    (env-set! (:current-env ctx) target val)
    val))

(defmethod eval-node :let
  [ctx {:keys [name value]}]
  (let [val (eval-node ctx value)]
    ;; Always define a new binding in the current scope (can shadow outer scopes)
    (env-define (:current-env ctx) name val)
    val))

(defmethod eval-node :block
  [ctx statements]
  ;; Execute each statement and return the last value
  (when (sequential? statements)
    (last (map #(eval-node ctx %) statements))))

(defmethod eval-node :scoped-block
  [ctx {:keys [body]}]
  ;; Create a new lexical scope
  (let [new-env (make-env (:current-env ctx))
        new-ctx (assoc ctx :current-env new-env)]
    ;; Execute the block in the new scope
    (last (map #(eval-node new-ctx %) body))))

(defmethod eval-node :if
  [ctx {:keys [condition then else]}]
  ;; Evaluate condition and execute appropriate branch
  (let [cond-val (eval-node ctx condition)]
    (if cond-val
      (last (map #(eval-node ctx %) then))
      (last (map #(eval-node ctx %) else)))))

(defmethod eval-node :loop
  [ctx {:keys [init invariant variant until body]}]
  ;; Execute initialization statements
  (doseq [stmt init]
    (eval-node ctx stmt))

  ;; Loop until the 'until' condition becomes true
  (loop [last-result nil
         prev-variant nil
         iteration 0]
    ;; Check invariant before iteration (if present)
    (when invariant
      (check-assertions ctx invariant Loop-invariant))

    ;; Check exit condition
    (let [until-val (eval-node ctx until)]
      (if until-val
        ;; Exit loop
        last-result
        ;; Continue loop
        (let [;; Evaluate variant before body (if present)
              curr-variant (when variant (eval-node ctx variant))

              ;; Check variant decreases (if present and not first iteration)
              _ (when (and variant prev-variant)
                  (when-not (< curr-variant prev-variant)
                    (throw (ex-info "Loop variant must decrease"
                                    {:iteration iteration
                                     :previous-variant prev-variant
                                     :current-variant curr-variant}))))

              ;; Execute loop body in a NEW scope each iteration
              ;; This ensures 'let' creates shadowed variables that don't persist
              ;; while plain ':=' can still update variables in parent scopes
              body-env (make-env (:current-env ctx))
              body-ctx (assoc ctx :current-env body-env)
              result (last (map #(eval-node body-ctx %) body))]

          ;; Check invariant after iteration (if present)
          (when invariant
            (check-assertions ctx invariant Loop-invariant))

          ;; Recur with new state
          (recur result curr-variant (inc iteration)))))))

(defmethod eval-node :statement
  [ctx node]
  ;; Statement wrapper - just evaluate the inner node
  (eval-node ctx node))

(defmethod eval-node :binary
  [ctx {:keys [operator left right]}]
  (let [left-val (eval-node ctx left)
        right-val (eval-node ctx right)]
    (apply-binary-op operator left-val right-val)))

(defmethod eval-node :unary
  [ctx {:keys [operator expr]}]
  (let [val (eval-node ctx expr)]
    (apply-unary-op operator val)))

(defmethod eval-node :integer
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :real
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :boolean
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :char
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :string
  [_ctx {:keys [value]}]
  value)

(defmethod eval-node :array-literal
  [ctx {:keys [elements]}]
  ;; Evaluate all elements and return as a mutable array
  (nex-array-from (mapv #(eval-node ctx %) elements)))

(defmethod eval-node :map-literal
  [ctx {:keys [entries]}]
  ;; Evaluate all key-value pairs and return as a mutable map
  (nex-map-from (mapv (fn [{:keys [key value]}]
                        [(eval-node ctx key) (eval-node ctx value)])
                      entries)))

(defmethod eval-node :subscript
  [ctx {:keys [target index]}]
  ;; Evaluate target (array or map) and index, then access element
  (let [coll (eval-node ctx target)
        idx (eval-node ctx index)]
    (nex-coll-get coll idx)))

(defmethod eval-node :identifier
  [ctx {:keys [name]}]
  (env-lookup (:current-env ctx) name))

(defn get-all-fields
  "Get all fields from a class and its parents"
  [ctx class-def]
  (let [;; Get fields from parents first
        parent-fields (when-let [parents (get-parent-classes ctx class-def)]
                       (mapcat (fn [parent-info]
                                (get-all-fields ctx (:class-def parent-info)))
                              parents))
        ;; Get fields from current class
        current-fields (->> (:body class-def)
                           (mapcat (fn [section]
                                    (when (= (:type section) :feature-section)
                                      (:members section))))
                           (filter #(= (:type %) :field)))]
    (concat parent-fields current-fields)))

(defn lookup-constructor
  "Look up a constructor by name in a class"
  [class-def constructor-name]
  (->> (:body class-def)
       (mapcat (fn [section]
                 (when (= (:type section) :constructors)
                   (:constructors section))))
       (filter #(= (:name %) constructor-name))
       first))

(defmethod eval-node :create
  [ctx {:keys [class-name generic-args constructor args]}]
  ;; Resolve effective class name (handle generic specialization)
  (let [effective-class-name
        (if (seq generic-args)
          (let [spec-name (specialized-class-name class-name generic-args)]
            (if (lookup-specialized-class ctx spec-name)
              spec-name
              ;; Need to create the specialization
              (let [template (get @(:classes ctx) class-name)]
                (when-not template
                  (throw (ex-info (str "Undefined template class: " class-name)
                                  {:class-name class-name})))
                (when-not (:generic-params template)
                  (throw (ex-info (str "Class " class-name " is not generic")
                                  {:class-name class-name})))
                (when (not= (count (:generic-params template)) (count generic-args))
                  (throw (ex-info (str "Type argument count mismatch for " class-name
                                       ": expected " (count (:generic-params template))
                                       ", got " (count generic-args))
                                  {:class-name class-name
                                   :expected (count (:generic-params template))
                                   :got (count generic-args)})))
                (let [specialized (specialize-class template generic-args)]
                  (register-specialized-class ctx specialized)
                  spec-name))))
          class-name)
        class-def (lookup-class ctx effective-class-name)
        ;; Get all fields (including inherited)
        all-fields (get-all-fields ctx class-def)
        ;; Initialize fields with default values
        initial-field-map (reduce (fn [m field]
                                   (assoc m (keyword (:name field))
                                          (get-default-field-value (:field-type field))))
                                 {}
                                 all-fields)
        ;; If a constructor is specified, call it and update fields
        final-field-map (if constructor
                         (let [ctor-def (lookup-constructor class-def constructor)]
                           (when-not ctor-def
                             (throw (ex-info (str "Constructor not found: " constructor)
                                            {:class-name class-name :constructor constructor})))
                           ;; Create environment for constructor execution
                           (let [ctor-env (make-env (:current-env ctx))
                                 ;; Bind parameters
                                 params (:params ctor-def)
                                 arg-values (mapv #(eval-node ctx %) args)
                                 _ (when params
                                     (doseq [[param arg-val] (map vector params arg-values)]
                                       (env-define ctor-env (:name param) arg-val)))
                                 ;; Bind fields as local variables
                                 _ (doseq [[field-name field-val] initial-field-map]
                                     (env-define ctor-env (name field-name) field-val))
                                 new-ctx (assoc ctx :current-env ctor-env)
                                 ;; Check pre-conditions
                                 _ (when-let [require-assertions (:require ctor-def)]
                                     (check-assertions new-ctx require-assertions Precondition))
                                 ;; Execute constructor body
                                 _ (doseq [stmt (:body ctor-def)]
                                     (eval-node new-ctx stmt))
                                 ;; Update object fields from modified environment
                                 updated-fields (reduce (fn [m field]
                                                         (let [field-name (:name field)
                                                               field-key (keyword field-name)]
                                                           (if-let [val (try
                                                                         (env-lookup ctor-env field-name)
                                                                         (catch #?(:clj Exception :cljs :default) _ nil))]
                                                             (assoc m field-key val)
                                                             m)))
                                                       initial-field-map
                                                       all-fields)
                                 ;; Check post-conditions
                                 _ (when-let [ensure-assertions (:ensure ctor-def)]
                                     (check-assertions new-ctx ensure-assertions Postcondition))]
                             updated-fields))
                         ;; No constructor: use default initialization
                         initial-field-map)
        ;; Create the final object
        obj (make-object effective-class-name final-field-map)]

    ;; Check class invariant with object fields in scope
    (when-let [invariant (:invariant class-def)]
      (let [inv-env (make-env (:current-env ctx))
            _ (doseq [[field-name field-val] final-field-map]
                (env-define inv-env (name field-name) field-val))
            inv-ctx (assoc ctx :current-env inv-env)]
        (check-class-invariant inv-ctx class-def)))

    ;; Return the object
    obj))

(defmethod eval-node :literal
  [_ctx node]
  ;; Handle literal values that might be passed directly
  (cond
    (string? node) node
    (map? node) (:value node)
    :else node))

(defmethod eval-node :old
  [ctx {:keys [expr]}]
  ;; Look up the value from the old-values snapshot
  (if-let [old-values (:old-values ctx)]
    (if (and (map? expr) (= (:type expr) :identifier))
      ;; Simple identifier: look up in old values
      (let [var-name (:name expr)]
        (if (contains? old-values (keyword var-name))
          (get old-values (keyword var-name))
          (throw (ex-info (str "'old' can only be used on object fields in postconditions")
                         {:variable var-name}))))
      ;; More complex expression: evaluate it in an environment with old values
      (let [old-env (make-env (:current-env ctx))
            _ (doseq [[field-name field-val] old-values]
                (env-define old-env (name field-name) field-val))
            old-ctx (assoc ctx :current-env old-env)]
        (eval-node old-ctx expr)))
    (throw (ex-info "'old' can only be used in postconditions"
                   {:expr expr}))))

(defmethod eval-node :with
  [ctx {:keys [target body]}]
  ;; With statements are for code generation only, skip in interpreter
  ;; Could optionally evaluate for a specific target if needed
  nil)

(defmethod eval-node :default
  [ctx node]
  ;; If it's a plain string (identifier), look it up
  (if (string? node)
    (env-lookup (:current-env ctx) node)
    (throw (ex-info (str "Cannot evaluate node type: " (or (:type node) (type node)))
                    {:node node}))))

;;
;; Public API
;;

(defn interpret
  "Interpret an AST and return the context with results."
  [ast]
  (let [ctx (make-context)]
    (eval-node ctx ast)
    ctx))

(defn interpret-and-get-output
  "Interpret an AST and return the output as a vector of strings."
  [ast]
  (let [ctx (interpret ast)]
    @(:output ctx)))

(defn run
  "Convenience function to interpret and print output."
  [ast]
  (let [output (interpret-and-get-output ast)]
    (doseq [line output]
      (println line))
    output))
