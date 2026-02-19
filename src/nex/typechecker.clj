(ns nex.typechecker
  "Static type checker for Nex language"
  (:require [clojure.string :as str]))

;;
;; Type Environment
;;

(defn make-type-env
  "Create a new type environment"
  ([] (make-type-env nil))
  ([parent]
   {:parent parent
    :vars (atom {})
    :methods (atom {})
    :classes (atom {})}))

(defn env-lookup-var
  "Look up a variable type in the environment"
  [env name]
  (if-let [type (get @(:vars env) name)]
    type
    (when (:parent env)
      (env-lookup-var (:parent env) name))))

(defn env-add-var
  "Add a variable to the environment"
  [env name type]
  (swap! (:vars env) assoc name type))

(defn env-lookup-method
  "Look up a method signature in the environment"
  [env class-name method-name]
  (if-let [class-methods (get @(:methods env) class-name)]
    (get class-methods method-name)
    (when (:parent env)
      (env-lookup-method (:parent env) class-name method-name))))

(defn env-add-method
  "Add a method signature to the environment"
  [env class-name method-name signature]
  (swap! (:methods env) update class-name assoc method-name signature))

(defn env-lookup-class
  "Look up a class definition in the environment"
  [env class-name]
  (if-let [class-def (get @(:classes env) class-name)]
    class-def
    (when (:parent env)
      (env-lookup-class (:parent env) class-name))))

(defn env-add-class
  "Add a class definition to the environment"
  [env class-name class-def]
  (swap! (:classes env) assoc class-name class-def))

;;
;; Built-in Types
;;

(def builtin-types
  #{"Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Any" "Void"})

(defn builtin-type? [type-name]
  (contains? builtin-types type-name))

;;
;; Type Checking Errors
;;

(defrecord TypeError [message line column])

(defn type-error
  "Create a type error"
  ([msg] (type-error msg nil nil))
  ([msg line] (type-error msg line nil))
  ([msg line column]
   (->TypeError msg line column)))

(defn format-type-error
  "Format a type error for display"
  [{:keys [message line column]}]
  (if line
    (str "Type error at line " line
         (when column (str ", column " column))
         ": " message)
    (str "Type error: " message)))

;;
;; Type Utilities
;;

(defn normalize-type
  "Normalize a type expression to a string or map"
  [type-expr]
  (cond
    (string? type-expr) type-expr
    (map? type-expr)
    (if (:base-type type-expr)
      type-expr
      (str type-expr))
    :else (str type-expr)))

(defn is-generic-type-param?
  "Check if a type is a generic type parameter (single uppercase letter)"
  [type]
  (let [t (normalize-type type)]
    (and (string? t)
         (re-matches #"[A-Z]" t))))

(defn types-equal?
  "Check if two types are equal"
  [type1 type2]
  (let [t1 (normalize-type type1)
        t2 (normalize-type type2)]
    (or (= t1 t2)
        ;; Integer and Integer64 are compatible
        (and (or (= t1 "Integer") (= t1 "Integer64"))
             (or (= t2 "Integer") (= t2 "Integer64")))
        ;; Real and Decimal are compatible
        (and (or (= t1 "Real") (= t1 "Decimal"))
             (or (= t2 "Real") (= t2 "Decimal")))
        ;; Generic type parameters are compatible with any type
        (or (is-generic-type-param? t1)
            (is-generic-type-param? t2))
        ;; Handle parameterized types with different keys
        (and (map? t1) (map? t2)
             (= (:base-type t1) (:base-type t2))
             (or (= (:type-params t1) (:type-params t2))
                 (= (:type-params t1) (:type-args t2))
                 (= (:type-args t1) (:type-params t2))
                 (= (:type-args t1) (:type-args t2))))
        ;; Allow base class name to match parameterized type (e.g., "Box" matches {:base-type "Box", ...})
        (or (and (string? t1) (map? t2) (= t1 (:base-type t2)))
            (and (map? t1) (string? t2) (= (:base-type t1) t2))))))

(defn is-numeric-type?
  "Check if a type is numeric"
  [type]
  (let [t (normalize-type type)]
    (or (= t "Integer")
        (= t "Integer64")
        (= t "Real")
        (= t "Decimal"))))

(defn is-comparable-type?
  "Check if a type supports comparison operators"
  [type]
  (let [t (normalize-type type)]
    (or (is-numeric-type? t)
        (= t "String")
        (= t "Char"))))

;;
;; Expression Type Checking
;;

(declare check-expression)

(defn check-literal
  "Check the type of a literal expression"
  [env expr]
  (case (:type expr)
    :integer "Integer"
    :real "Real"
    :string "String"
    :char "Char"
    :boolean "Boolean"
    (throw (ex-info "Unknown literal type" {:expr expr}))))

(defn check-identifier
  "Check the type of an identifier"
  [env {:keys [name] :as expr}]
  (if-let [var-type (env-lookup-var env name)]
    var-type
    (throw (ex-info (str "Undefined variable: " name)
                    {:error (type-error (str "Undefined variable: " name))}))))

(defn check-binary-op
  "Check the type of a binary operation"
  [env {:keys [operator left right] :as expr}]
  (let [left-type (check-expression env left)
        right-type (check-expression env right)]
    (case operator
      ("+" "-" "*" "/")
      (if (and (is-numeric-type? left-type) (is-numeric-type? right-type))
        left-type
        (throw (ex-info (str "Operator " operator " requires numeric operands")
                        {:error (type-error
                                 (str "Operator " operator " requires numeric operands, got "
                                      left-type " and " right-type))})))

      ("=" "/=")
      (if (or (types-equal? left-type right-type)
              ;; Allow comparisons with generic type parameters
              (is-generic-type-param? left-type)
              (is-generic-type-param? right-type))
        "Boolean"
        (throw (ex-info (str "Cannot compare " left-type " with " right-type)
                        {:error (type-error
                                 (str "Cannot compare " left-type " with " right-type))})))

      ("<" "<=" ">" ">=")
      (if (or (and (is-comparable-type? left-type)
                   (is-comparable-type? right-type)
                   (types-equal? left-type right-type))
              ;; Allow comparisons with generic type parameters
              (is-generic-type-param? left-type)
              (is-generic-type-param? right-type))
        "Boolean"
        (throw (ex-info (str "Cannot compare " left-type " with " right-type)
                        {:error (type-error
                                 (str "Comparison requires compatible types, got "
                                      left-type " and " right-type))})))

      ("and" "or")
      (if (and (= left-type "Boolean") (= right-type "Boolean"))
        "Boolean"
        (throw (ex-info (str "Operator " operator " requires Boolean operands")
                        {:error (type-error
                                 (str "Operator " operator " requires Boolean operands, got "
                                      left-type " and " right-type))})))

      (throw (ex-info (str "Unknown operator: " operator)
                      {:error (type-error (str "Unknown operator: " operator))})))))

(defn check-unary-op
  "Check the type of a unary operation"
  [env {:keys [operator operand] :as expr}]
  (let [operand-type (check-expression env operand)]
    (case operator
      "-" (if (is-numeric-type? operand-type)
            operand-type
            (throw (ex-info "Unary minus requires numeric operand"
                            {:error (type-error
                                     (str "Unary minus requires numeric operand, got "
                                          operand-type))})))

      "not" (if (= operand-type "Boolean")
              "Boolean"
              (throw (ex-info "Not operator requires Boolean operand"
                              {:error (type-error
                                       (str "Not operator requires Boolean operand, got "
                                            operand-type))})))

      (throw (ex-info (str "Unknown unary operator: " operator)
                      {:error (type-error (str "Unknown unary operator: " operator))})))))

(defn check-call
  "Check the type of a method call"
  [env {:keys [target method args] :as expr}]
  (if target
    ;; Method call on object
    (let [target-type (if (string? target)
                       (env-lookup-var env target)
                       (check-expression env target))]
      (if-let [method-sig (env-lookup-method env target-type method)]
        (do
          ;; Check argument types
          (when (not= (count args) (count (:params method-sig)))
            (throw (ex-info (str "Method " method " expects " (count (:params method-sig))
                                " arguments, got " (count args))
                            {:error (type-error
                                     (str "Method " method " expects " (count (:params method-sig))
                                          " arguments, got " (count args)))})))
          (doseq [[arg param] (map vector args (:params method-sig))]
            (let [arg-type (check-expression env arg)
                  param-type (:type param)]
              (when-not (types-equal? arg-type param-type)
                (throw (ex-info (str "Argument type mismatch for method " method)
                                {:error (type-error
                                         (str "Expected " param-type ", got " arg-type))})))))
          (:return-type method-sig))
        ;; Method not found - might be built-in method, return Any for now
        "Any"))
    ;; Function call (built-in like print) - check arguments
    (do
      (doseq [arg args]
        (check-expression env arg))
      "Void")))

(defn check-create
  "Check the type of a create expression"
  [env {:keys [class-name generic-args constructor args] :as expr}]
  ;; Check if class exists
  (when-not (or (env-lookup-class env class-name) (builtin-type? class-name))
    (throw (ex-info (str "Undefined class: " class-name)
                    {:error (type-error (str "Undefined class: " class-name))})))
  (if (seq generic-args)
    ;; Validate generic args against template class
    (let [class-def (env-lookup-class env class-name)]
      (when (and class-def (:generic-params class-def))
        (when (not= (count (:generic-params class-def)) (count generic-args))
          (throw (ex-info (str "Type argument count mismatch for " class-name)
                          {:error (type-error
                                   (str "Expected " (count (:generic-params class-def))
                                        " type arguments, got " (count generic-args)))}))))
      {:base-type class-name :type-args generic-args})
    class-name))

(defn check-array-literal
  "Check the type of an array literal"
  [env {:keys [elements] :as expr}]
  (if (empty? elements)
    {:base-type "Array" :type-params ["Any"]}
    (let [first-type (check-expression env (first elements))]
      ;; Check all elements have same type
      (doseq [elem (rest elements)]
        (let [elem-type (check-expression env elem)]
          (when-not (types-equal? first-type elem-type)
            (throw (ex-info "Array elements must have same type"
                            {:error (type-error
                                     (str "Array elements must have same type, got "
                                          first-type " and " elem-type))})))))
      {:base-type "Array" :type-params [first-type]})))

(defn check-map-literal
  "Check the type of a map literal"
  [env {:keys [entries] :as expr}]
  (if (empty? entries)
    {:base-type "Map" :type-params ["Any" "Any"]}
    (let [first-entry (first entries)
          key-type (check-expression env (:key first-entry))
          val-type (check-expression env (:value first-entry))]
      ;; Check all entries have same types
      (doseq [entry (rest entries)]
        (let [k-type (check-expression env (:key entry))
              v-type (check-expression env (:value entry))]
          (when-not (and (types-equal? key-type k-type)
                        (types-equal? val-type v-type))
            (throw (ex-info "Map entries must have consistent types"
                            {:error (type-error
                                     "Map entries must have consistent types")})))))
      {:base-type "Map" :type-params [key-type val-type]})))

(defn check-expression
  "Check the type of an expression"
  [env expr]
  (cond
    (nil? expr) "Void"
    (string? expr) (or (env-lookup-var env expr)
                      (throw (ex-info (str "Undefined variable: " expr)
                                      {:error (type-error (str "Undefined variable: " expr))})))
    (number? expr) "Integer"
    (boolean? expr) "Boolean"
    (map? expr)
    (case (:type expr)
      :integer (check-literal env expr)
      :real (check-literal env expr)
      :string (check-literal env expr)
      :char (check-literal env expr)
      :boolean (check-literal env expr)
      :identifier (check-identifier env expr)
      :binary (check-binary-op env expr)
      :unary (check-unary-op env expr)
      :call (check-call env expr)
      :create (check-create env expr)
      :array-literal (check-array-literal env expr)
      :map-literal (check-map-literal env expr)
      :subscript (check-expression env (:target expr))
      :old (check-expression env (:expr expr))
      "Any")
    :else "Any"))

;;
;; Statement Type Checking
;;

(declare check-statement)

(defn check-assignment
  "Check an assignment statement"
  [env {:keys [target value] :as stmt}]
  (let [var-type (env-lookup-var env target)
        val-type (check-expression env value)]
    (when-not var-type
      (throw (ex-info (str "Undefined variable: " target)
                      {:error (type-error (str "Undefined variable: " target))})))
    (when-not (types-equal? var-type val-type)
      (throw (ex-info (str "Type mismatch in assignment to " target)
                      {:error (type-error
                               (str "Cannot assign " val-type " to variable of type "
                                    var-type))})))))

(defn check-let
  "Check a let statement"
  [env {:keys [name var-type value] :as stmt}]
  (let [val-type (check-expression env value)]
    (when var-type
      (when-not (types-equal? var-type val-type)
        (throw (ex-info (str "Type mismatch in let binding for " name)
                        {:error (type-error
                                 (str "Cannot bind " val-type " to variable of type "
                                      var-type))}))))
    (env-add-var env name (or var-type val-type))))

(defn check-if
  "Check an if statement"
  [env {:keys [condition then else] :as stmt}]
  (let [cond-type (check-expression env condition)]
    (when-not (= cond-type "Boolean")
      (throw (ex-info "If condition must be Boolean"
                      {:error (type-error
                               (str "If condition must be Boolean, got " cond-type))}))))
  (doseq [stmt then] (check-statement env stmt))
  (doseq [stmt else] (check-statement env stmt)))

(defn check-loop
  "Check a loop statement"
  [env {:keys [init condition variant invariant body] :as stmt}]
  (let [loop-env (make-type-env env)]
    (doseq [s init] (check-statement loop-env s))
    (when condition
      (let [cond-type (check-expression loop-env condition)]
        (when-not (or (= cond-type "Boolean") (= cond-type "Void"))
          (throw (ex-info "Loop condition must be Boolean"
                          {:error (type-error
                                   (str "Loop condition must be Boolean, got " cond-type))})))))
    (doseq [stmt body] (check-statement loop-env stmt))))

(defn check-statement
  "Check a statement"
  [env stmt]
  (when (map? stmt)
    (case (:type stmt)
      :assign (check-assignment env stmt)
      :let (check-let env stmt)
      :call (check-expression env stmt)
      :if (check-if env stmt)
      :loop (check-loop env stmt)
      :scoped-block (doseq [s (:body stmt)] (check-statement env s))
      :with (doseq [s (:body stmt)] (check-statement env s))
      nil)))

;;
;; Method/Constructor Type Checking
;;

(defn check-method
  "Check a method definition"
  [env class-name {:keys [name params return-type require body ensure] :as method}]
  (let [method-env (make-type-env env)]
    ;; Add parameters to method environment
    (doseq [param params]
      (env-add-var method-env (:name param) (:type param)))

    ;; Add Result variable for return type
    (when return-type
      (env-add-var method-env "result" return-type))

    ;; Check preconditions
    (doseq [assertion require]
      (let [cond-type (check-expression method-env (:condition assertion))]
        (when-not (= cond-type "Boolean")
          (throw (ex-info (str "Precondition must be Boolean in method " name)
                          {:error (type-error
                                   (str "Precondition must be Boolean, got " cond-type))})))))

    ;; Check method body
    (doseq [stmt body]
      (check-statement method-env stmt))

    ;; Check postconditions
    (doseq [assertion ensure]
      (let [cond-type (check-expression method-env (:condition assertion))]
        (when-not (= cond-type "Boolean")
          (throw (ex-info (str "Postcondition must be Boolean in method " name)
                          {:error (type-error
                                   (str "Postcondition must be Boolean, got " cond-type))})))))))

(defn check-constructor
  "Check a constructor definition"
  [env class-name {:keys [name params require body ensure] :as constructor}]
  (let [ctor-env (make-type-env env)]
    ;; Add parameters
    (doseq [param params]
      (env-add-var ctor-env (:name param) (:type param)))

    ;; Check preconditions
    (doseq [assertion require]
      (when assertion
        (let [cond-type (check-expression ctor-env (:condition assertion))]
          (when-not (= cond-type "Boolean")
            (throw (ex-info (str "Precondition must be Boolean in constructor " name)
                            {:error (type-error
                                     (str "Precondition must be Boolean, got " cond-type))}))))))

    ;; Check body
    (doseq [stmt body]
      (check-statement ctor-env stmt))

    ;; Check postconditions
    (doseq [assertion ensure]
      (when assertion
        (let [cond-type (check-expression ctor-env (:condition assertion))]
          (when-not (= cond-type "Boolean")
            (throw (ex-info (str "Postcondition must be Boolean in constructor " name)
                            {:error (type-error
                                     (str "Postcondition must be Boolean, got " cond-type))}))))))))

;;
;; Class Type Checking
;;

(defn collect-class-info
  "Collect class information (first pass)"
  [env {:keys [name body] :as class-def}]
  (env-add-class env name class-def)

  ;; Collect fields
  (doseq [section body]
    (when (= (:type section) :feature-section)
      (doseq [member (:members section)]
        (when (= (:type member) :field)
          (env-add-var env (:name member) (:field-type member))))))

  ;; Collect method signatures
  (doseq [section body]
    (cond
      (= (:type section) :feature-section)
      (doseq [member (:members section)]
        (when (= (:type member) :method)
          (env-add-method env name (:name member)
                         {:params (:params member)
                          :return-type (:return-type member)})))

      (= (:type section) :constructors)
      (doseq [ctor (:constructors section)]
        (env-add-method env name (:name ctor)
                       {:params (:params ctor)
                        :return-type name})))))

(defn check-inheritance
  "Check that inheritance declarations are valid"
  [env class-name parents]
  (doseq [{:keys [parent renames redefines]} parents]
    ;; Check that parent class exists
    (when-not (or (env-lookup-class env parent) (builtin-type? parent))
      (throw (ex-info (str "Parent class " parent " not found for class " class-name)
                      {:error (type-error
                               (str "Undefined parent class: " parent))})))

    ;; Check that renamed methods exist in parent (if we have parent info)
    ;; Note: For now we skip this validation since we don't have full parent method info
    ;; in the type environment. This could be enhanced later.

    ;; Check that redefined methods exist in parent
    ;; Note: Similarly skipped for now
    ))

(defn check-class
  "Check a class definition"
  [env {:keys [name body invariant parents] :as class-def}]
  ;; Check inheritance
  (when parents
    (check-inheritance env name parents))

  ;; Check invariants
  (doseq [assertion invariant]
    (when (and assertion (:expr assertion))
      (let [inv-type (check-expression env (:expr assertion))]
        (when-not (or (= inv-type "Boolean") (= inv-type "Void"))
          (throw (ex-info (str "Invariant must be Boolean in class " name)
                          {:error (type-error
                                   (str "Invariant must be Boolean, got " inv-type))}))))))

  ;; Check each section
  (doseq [section body]
    (cond
      (= (:type section) :feature-section)
      (doseq [member (:members section)]
        (when (= (:type member) :method)
          (check-method env name member)))

      (= (:type section) :constructors)
      (doseq [ctor (:constructors section)]
        (check-constructor env name ctor)))))

;;
;; Program Type Checking
;;

(defn check-program
  "Type check a complete program"
  [{:keys [classes calls] :as program}]
  (let [env (make-type-env)]
    (try
      ;; First pass: collect all class definitions
      (doseq [class-def classes]
        (collect-class-info env class-def))

      ;; Second pass: check class bodies
      (doseq [class-def classes]
        (check-class env class-def))

      ;; Check top-level calls
      (doseq [call calls]
        (check-expression env call))

      {:success true
       :errors []}

      (catch clojure.lang.ExceptionInfo e
        (let [error-data (ex-data e)]
          {:success false
           :errors [(or (:error error-data)
                       (type-error (.getMessage e)))]})))))

(defn type-check
  "Type check Nex code (entry point)"
  [ast]
  (check-program ast))
