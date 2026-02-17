(ns nex.interpreter
  (:require [clojure.string :as str]
            #?(:clj [nex.parser :as parser])))

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

(defrecord Context [classes globals current-env output imports])

(defn make-context
  "Create a new runtime context."
  []
  (let [globals (make-env)]
    (->Context
     (atom {})           ; classes registry
     globals             ; global environment
     globals             ; current environment starts as global
     (atom [])           ; output accumulator
     (atom []))))        ; imports registry

(defn register-class
  "Register a class definition in the context."
  [ctx class-def]
  (swap! (:classes ctx) assoc (:name class-def) class-def))

(defn lookup-class
  "Look up a class definition by name."
  [ctx class-name]
  (or (get @(:classes ctx) class-name)
      (throw (ex-info (str "Undefined class: " class-name)
                      {:class-name class-name}))))

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

(defn check-assertions
  "Check a list of assertions. Throws exception if any fail."
  [ctx assertions contract-type]
  (doseq [{:keys [label condition]} assertions]
    (let [result (eval-node ctx condition)]
      (when-not result
        (throw (ex-info (str contract-type " violation: " label)
                        {:contract-type contract-type
                         :label label
                         :condition condition}))))))

(defn check-class-invariant
  "Check the class invariant for an object or class context."
  [ctx class-def]
  (when-let [invariant-assertions (:invariant class-def)]
    (check-assertions ctx invariant-assertions "Class invariant")))

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
      "Array" []
      "Map" {}
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
                      new-ctx (-> ctx
                                 (assoc :current-env method-env)
                                 (assoc :old-values old-values))
                      ;; Check pre-conditions
                      _ (when-let [require-assertions (:require method-def)]
                          (check-assertions new-ctx require-assertions "Precondition"))
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
                      (check-assertions new-ctx ensure-assertions "Postcondition"))
                    ;; Check class invariant
                    (check-class-invariant new-ctx class-def)
                    ;; Success: update object in parent environment
                    (env-set! (:current-env ctx) target updated-obj)
                    result
                    (catch #?(:clj Exception :cljs :default) e
                      ;; Postcondition or invariant failed: restore original object
                      (env-set! (:current-env ctx) target obj)
                      (throw e)))))
              (throw (ex-info (str "Method not found: " method)
                              {:object obj :method method}))))
          (throw (ex-info (str "Cannot call method on non-object: " target)
                          {:target target :value obj}))))

      ;; Global function call: method(args)
      (if-let [builtin (get builtins method)]
        (apply builtin ctx arg-values)
        (throw (ex-info (str "Undefined function: " method)
                        {:function method}))))))

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
      (check-assertions ctx invariant "Loop invariant"))

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
            (check-assertions ctx invariant "Loop invariant"))

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
  [ctx {:keys [class-name constructor args]}]
  ;; Get class definition
  (let [class-def (lookup-class ctx class-name)
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
                                     (check-assertions new-ctx require-assertions "Precondition"))
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
                                     (check-assertions new-ctx ensure-assertions "Postcondition"))]
                             updated-fields))
                         ;; No constructor: use default initialization
                         initial-field-map)
        ;; Create the final object
        obj (make-object class-name final-field-map)]

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
