(ns nex.typechecker
  "Static type checker for Nex language"
  (:require [clojure.string :as str]
            [clojure.set :as set]))

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
    :classes (atom {})
    :non-nil-vars (atom #{})}))

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

(defn env-set!
  "Update a variable type in the nearest environment where it is defined."
  [env name type]
  (if (contains? @(:vars env) name)
    (swap! (:vars env) assoc name type)
    (when-let [parent (:parent env)]
      (env-set! parent name type))))

(defn env-lookup-method
  "Look up a method signature in the environment"
  ([env class-name method-name]
   (env-lookup-method env class-name method-name nil))
  ([env class-name method-name arity]
  (if-let [class-methods (get @(:methods env) class-name)]
    (let [method-entry (get class-methods method-name)]
      (cond
        (nil? method-entry) nil
        (nil? arity) (if (map? method-entry)
                       (or (get method-entry 0) (val (first method-entry)))
                       method-entry)
        (map? method-entry) (get method-entry arity)
        :else method-entry))
    (when (:parent env)
      (env-lookup-method (:parent env) class-name method-name arity)))))

(defn env-add-method
  "Add a method signature to the environment"
  [env class-name method-name signature]
  (let [arity (count (or (:params signature) []))]
    (swap! (:methods env) update class-name
           (fn [class-methods]
             (assoc (or class-methods {})
                    method-name
                    (assoc (or (get class-methods method-name) {}) arity signature))))))

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

(defn env-mark-non-nil
  "Mark a variable as proven non-nil in this environment scope."
  [env var-name]
  (when-let [nn (:non-nil-vars env)]
    (swap! nn conj var-name)))

(defn env-var-non-nil?
  "Check whether a variable is proven non-nil in this env chain."
  [env var-name]
  (or (and (:non-nil-vars env)
           (contains? @(:non-nil-vars env) var-name))
      (when (:parent env)
        (env-var-non-nil? (:parent env) var-name))))

;;
;; Built-in Types
;;

(def builtin-types
  #{"Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Task" "Channel" "Any" "Void" "Nil" "Console" "File" "Process" "Function"
    "Cursor" "Window" "Turtle" "Image"})

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

(defn display-type
  "Format a type value for human-readable display."
  [type-val]
  (cond
    (string? type-val) type-val
    (map? type-val) (let [base (:base-type type-val)
                          params (or (:type-params type-val) (:type-args type-val))
                          core (if (seq params)
                                 (str base "[" (clojure.string/join ", " (map display-type params)) "]")
                                 base)]
                     (if (:detachable type-val)
                       (str "?" core)
                       core))
    :else (str type-val)))

;;
;; Type Utilities
;;

(defn normalize-type
  "Normalize a type expression to a string or map.
   Canonicalizes :type-args to :type-params so that inferred types
   (which use :type-params) and declared types (which use :type-args)
   can be compared with simple equality."
  [type-expr]
  (cond
    (string? type-expr) type-expr
    (map? type-expr)
    (if (:base-type type-expr)
      (let [params (or (:type-params type-expr) (:type-args type-expr))
            detachable? (true? (:detachable type-expr))]
        (cond-> {:base-type (:base-type type-expr)}
          params (assoc :type-params (mapv normalize-type params))
          detachable? (assoc :detachable true)))
      (str type-expr))
    :else (str type-expr)))

(defn detachable-type?
  "Check whether a normalized type is detachable."
  [t]
  (and (map? t) (true? (:detachable t))))

(defn attachable-type
  "Return type with detachable marker removed (normalized)."
  [t]
  (let [n (normalize-type t)]
    (if (map? n)
      (cond-> (dissoc n :detachable)
        (:type-params n) (update :type-params #(mapv attachable-type %)))
      n)))

(defn reference-like-type?
  "Whether type is a reference-like (potentially detachable) object type."
  [t]
  (let [n (attachable-type t)
        base (cond
               (string? n) n
               (map? n) (:base-type n)
               :else nil)]
    (and (string? base)
         (not (#{"Integer" "Integer64" "Real" "Decimal" "Char" "Boolean"} base)))))

(defn is-generic-type-param?
  "Check if a type is a generic type parameter (single uppercase letter)."
  ([type]
   (let [t (normalize-type type)]
     (and (string? t)
          (re-matches #"[A-Z]" t))))
  ([env type]
   (let [t (normalize-type type)]
     (and (string? t)
          (re-matches #"[A-Z]" t)
          (not (env-lookup-class env t))
          (not (builtin-type? t))))))

(defn types-equal?
  "Check if two types are equal"
  ([type1 type2]
   (types-equal? nil type1 type2))
  ([env type1 type2]
   (let [t1 (normalize-type type1)
         t2 (normalize-type type2)]
     (or (= t1 t2)
         ;; Any is compatible with all types
         (or (= t1 "Any") (= t2 "Any"))
         ;; Integer and Integer64 are compatible
         (and (or (= t1 "Integer") (= t1 "Integer64"))
              (or (= t2 "Integer") (= t2 "Integer64")))
         ;; Real and Decimal are compatible
         (and (or (= t1 "Real") (= t1 "Decimal"))
              (or (= t2 "Real") (= t2 "Decimal")))
         ;; Generic type parameters are compatible with any type (only when not a class)
         (or (and env (is-generic-type-param? env t1))
             (and env (is-generic-type-param? env t2))
             (and (nil? env) (is-generic-type-param? t1))
             (and (nil? env) (is-generic-type-param? t2)))
         ;; Handle parameterized types
         (and (map? t1) (map? t2)
              (= (:base-type t1) (:base-type t2))
              (= (:type-params t1) (:type-params t2)))
         ;; Allow base class name to match parameterized type (e.g., "Box" matches {:base-type "Box", ...})
         (or (and (string? t1) (map? t2) (= t1 (:base-type t2)))
             (and (map? t1) (string? t2) (= (:base-type t1) t2)))))))

(defn class-subtype?
  "Check if sub is the same as or a subclass of super."
  [env sub super]
  (let [sub (normalize-type sub)
        super (normalize-type super)]
    (cond
      (or (nil? sub) (nil? super)) false
      (= super "Any") true
      (= sub super) true
      (not (and (string? sub) (string? super))) false
      :else
      (letfn [(sub? [current seen]
                (if (contains? seen current)
                  false
                  (if-let [class-def (env-lookup-class env current)]
                    (let [parents (map :parent (:parents class-def))
                          seen (conj seen current)]
                      (or (some #(= % super) parents)
                          (some #(sub? % seen) parents)))
                    false)))]
        (sub? sub #{})))))

(defn types-compatible?
  "Check if two types are compatible (including inheritance)."
  [env type1 type2]
  (let [t1 (normalize-type type1) ;; source type
        t2 (normalize-type type2) ;; target type
        d1 (detachable-type? t1)
        d2 (detachable-type? t2)
        a1 (attachable-type t1)
        a2 (attachable-type t2)]
    (cond
      ;; Nil can only flow into detachable/reference-like targets.
      (= t1 "Nil")
      (or d2
          (= a2 "Any"))

      ;; Detachable value must not flow into attachable target.
      (and d1 (not d2))
      false

      :else
      (or (and (map? a1) (map? a2)
               (= (:base-type a1) "Set")
               (= (:base-type a2) "Set")
               (= (:type-params a1) ["__EmptySetElement"]))
          (types-equal? env a1 a2)
          (and (string? a1) (string? a2) (class-subtype? env a1 a2))
          (and (map? a1) (string? a2) (class-subtype? env (:base-type a1) a2))
          (and (map? a1) (map? a2)
               (class-subtype? env (:base-type a1) (:base-type a2))
               (= (:type-params a1) (:type-params a2)))))))

(defn validate-generic-args
  "Validate generic arguments against a class's generic constraints."
  [env class-name generic-args]
  (when (seq generic-args)
    (let [class-def (env-lookup-class env class-name)]
      (when (and class-def (:generic-params class-def))
        (when (not= (count (:generic-params class-def)) (count generic-args))
          (throw (ex-info (str "Type argument count mismatch for " class-name)
                          {:error (type-error
                                   (str "Expected " (count (:generic-params class-def))
                                        " type arguments, got " (count generic-args)))})))
        (doseq [[param arg] (map vector (:generic-params class-def) generic-args)]
          (when-let [constraint (:constraint param)]
            (when-not (types-compatible? env arg constraint)
              (throw (ex-info (str "Type argument " arg " does not satisfy constraint " constraint)
                              {:error (type-error
                                       (str "Type argument " arg " does not satisfy constraint " constraint))})))))))))

(defn validate-type-annotation
  "Validate parameterized type annotations against generic constraints."
  [env type-expr]
  (let [t (normalize-type type-expr)]
    (when (map? t)
      (let [base (:base-type t)
            args (or (:type-args t) (:type-params t))]
        (validate-generic-args env base args)
        (doseq [arg args]
          (validate-type-annotation env arg))))))

(defn is-numeric-type?
  "Check if a type is numeric"
  [type]
  (let [t (normalize-type type)]
    (or (= t "Integer")
        (= t "Integer64")
        (= t "Real")
        (= t "Decimal"))))

(defn integral-type?
  "Check if a type is an integral numeric type."
  [type]
  (let [t (normalize-type type)]
    (or (= t "Integer")
        (= t "Integer64"))))

(defn division-result-type
  "Infer the result type of division.
   Integral / integral stays integral; any non-integral operand yields Real."
  [left-type right-type]
  (cond
    (and (integral-type? left-type) (integral-type? right-type))
    (if (or (= (normalize-type left-type) "Integer64")
            (= (normalize-type right-type) "Integer64"))
      "Integer64"
      "Integer")

    :else
    "Real"))

(defn power-result-type
  "Infer the result type of exponentiation.
   Integral ^ integral stays integral; any non-integral operand yields Real."
  [left-type right-type]
  (division-result-type left-type right-type))

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
(declare collect-class-info)
(declare check-class)

(defn check-literal
  "Check the type of a literal expression"
  [env expr]
  (case (:type expr)
    :integer "Integer"
    :real "Real"
    :string "String"
    :char "Char"
    :boolean "Boolean"
    :nil "Nil"
    (throw (ex-info "Unknown literal type" {:expr expr}))))

(defn lookup-class-method
  "Look up a method on a class and its parent chain"
  ([env class-name method-name]
   (lookup-class-method env class-name method-name nil))
  ([env class-name method-name arity]
  (or (env-lookup-method env class-name method-name arity)
      (when-let [class-def (env-lookup-class env class-name)]
        (some (fn [{:keys [parent]}]
                (lookup-class-method env parent method-name arity))
              (:parents class-def))))))

(defn lookup-class-field
  "Look up a field on a class and its parent chain."
  [env class-name field-name]
  (letfn [(lookup-field [cn visited]
            (when (and cn (not (contains? visited cn)))
              (let [class-def (env-lookup-class env cn)
                    visited' (conj visited cn)
                    own-field-type
                    (when class-def
                      (some (fn [section]
                              (when (= (:type section) :feature-section)
                                (some (fn [member]
                                        (when (and (= (:type member) :field)
                                                   (not (:constant? member))
                                                   (= (:name member) field-name))
                                          (:field-type member)))
                                      (:members section))))
                            (:body class-def)))]
                (or own-field-type
                    (when class-def
                      (some (fn [{:keys [parent]}]
                              (lookup-field parent visited'))
                            (:parents class-def)))))))]
    (lookup-field class-name #{})))

(defn feature-members
  "Return feature members with section visibility copied onto each member."
  [class-def]
  (mapcat (fn [section]
            (when (= (:type section) :feature-section)
              (map #(if (:visibility %)
                      %
                      (assoc % :visibility (:visibility section)))
                   (:members section))))
          (:body class-def)))

(defn public-member?
  [member]
  (not= :private (-> member :visibility :type)))

(defn lookup-class-constant
  "Look up a constant on a class and its parent chain.
   Local constants always apply; inherited constants must be public."
  [env class-name constant-name]
  (letfn [(lookup-constant [cn visited inherited?]
            (when (and cn (not (contains? visited cn)))
              (let [class-def (env-lookup-class env cn)
                    visited' (conj visited cn)
                    own-constant (when class-def
                                   (some (fn [member]
                                           (when (and (= (:type member) :field)
                                                      (:constant? member)
                                                      (= (:name member) constant-name)
                                                      (or (not inherited?)
                                                          (public-member? member)))
                                             member))
                                         (feature-members class-def)))]
                (or own-constant
                    (when class-def
                      (some (fn [{:keys [parent]}]
                              (lookup-constant parent visited' true))
                            (:parents class-def)))))))]
    (lookup-constant class-name #{} false)))

(defn lookup-class-constructors
  "Collect constructors declared on a class and inherited parent chain."
  [env class-name]
  (letfn [(collect-ctors [cn visited]
            (if (contains? visited cn)
              []
              (let [class-def (env-lookup-class env cn)
                    visited' (conj visited cn)
                    own (if class-def
                          (->> (:body class-def)
                               (filter #(= :constructors (:type %)))
                               (mapcat :constructors))
                          [])
                    inherited (if class-def
                                (mapcat (fn [{:keys [parent]}]
                                          (collect-ctors parent visited'))
                                        (:parents class-def))
                                [])]
                (concat own inherited))))]
    (collect-ctors class-name #{})))

(defn check-identifier
  "Check the type of an identifier"
  [env {:keys [name] :as expr}]
  (if-let [var-type (env-lookup-var env name)]
    var-type
    (if-let [current-class (env-lookup-var env "__current_class__")]
      (if-let [constant (lookup-class-constant env current-class name)]
        (:field-type constant)
        (if-let [method-sig (lookup-class-method env current-class name)]
          (or (:return-type method-sig) "Void")
          (throw (ex-info (str "Undefined variable: " name)
                          {:error (type-error (str "Undefined variable: " name))}))))
      (throw (ex-info (str "Undefined variable: " name)
                      {:error (type-error (str "Undefined variable: " name))})))))

(defn check-binary-op
  "Check the type of a binary operation"
  [env {:keys [operator left right] :as expr}]
  (let [left-type (check-expression env left)
        right-type (check-expression env right)
        left-base (let [t (attachable-type left-type)]
                    (if (map? t) (:base-type t) t))
        right-base (let [t (attachable-type right-type)]
                     (if (map? t) (:base-type t) t))]
    (case operator
      "+"
      (cond
        ;; Runtime supports string concatenation if either side is a string.
        (or (= left-base "String") (= right-base "String"))
        "String"

        (and (is-numeric-type? left-type) (is-numeric-type? right-type))
        left-type

        :else
        (throw (ex-info (str "Operator " operator " requires numeric or String operands")
                        {:error (type-error
                                 (str "Operator " operator " requires numeric or String operands, got "
                                      (display-type left-type) " and " (display-type right-type)))})))

      ("/")
      (if (and (is-numeric-type? left-type) (is-numeric-type? right-type))
        (division-result-type left-type right-type)
        (throw (ex-info (str "Operator " operator " requires numeric operands")
                        {:error (type-error
                                 (str "Operator " operator " requires numeric operands, got "
                                      (display-type left-type) " and " (display-type right-type)))})))

      ("-" "*" "%")
      (if (and (is-numeric-type? left-type) (is-numeric-type? right-type))
        left-type
        (throw (ex-info (str "Operator " operator " requires numeric operands")
                        {:error (type-error
                                 (str "Operator " operator " requires numeric operands, got "
                                      (display-type left-type) " and " (display-type right-type)))})))

      ("^")
      (if (and (is-numeric-type? left-type) (is-numeric-type? right-type))
        (power-result-type left-type right-type)
        (throw (ex-info (str "Operator " operator " requires numeric operands")
                        {:error (type-error
                                 (str "Operator " operator " requires numeric operands, got "
                                      (display-type left-type) " and " (display-type right-type)))})))

      ("=" "/=")
      (if (or (= left-type "Nil")
              (= right-type "Nil")
              (types-compatible? env left-type right-type)
              (types-compatible? env right-type left-type)
              ;; Allow comparisons with generic type parameters
              (is-generic-type-param? env left-type)
              (is-generic-type-param? env right-type))
        "Boolean"
        (throw (ex-info (str "Cannot compare " left-type " with " right-type)
                        {:error (type-error
                                 (str "Cannot compare " left-type " with " right-type))})))

      ("<" "<=" ">" ">=")
      (if (or (and (is-comparable-type? left-type)
                   (is-comparable-type? right-type)
                   (types-equal? env left-type right-type))
              ;; Allow comparisons with generic type parameters
              (is-generic-type-param? env left-type)
              (is-generic-type-param? env right-type))
        "Boolean"
        (throw (ex-info (str "Cannot compare " left-type " with " right-type)
                        {:error (type-error
                                 (str "Comparison requires compatible types, got "
                                      (display-type left-type) " and " (display-type right-type)))})))

      ("and" "or")
      (if (and (= left-type "Boolean") (= right-type "Boolean"))
        "Boolean"
        (throw (ex-info (str "Operator " operator " requires Boolean operands")
                        {:error (type-error
                                 (str "Operator " operator " requires Boolean operands, got "
                                      (display-type left-type) " and " (display-type right-type)))})))

      (throw (ex-info (str "Unknown operator: " operator)
                      {:error (type-error (str "Unknown operator: " operator))})))))

(defn check-unary-op
  "Check the type of a unary operation"
  [env {:keys [operator operand expr] :as unary-expr}]
  (let [operand-node (or operand expr)
        operand-type (check-expression env operand-node)]
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

(defn resolve-generic-type
  "Substitute generic type parameters using a type-map.
   E.g., with type-map {\"T\" \"Integer\"}, resolves \"T\" to \"Integer\"."
  [param-type type-map]
  (cond
    (nil? type-map) param-type
    (string? param-type) (get type-map param-type param-type)
    (map? param-type) (-> param-type
                          (update :base-type #(get type-map % %))
                          (update :type-args #(when % (mapv (fn [t] (resolve-generic-type t type-map)) %)))
                          (update :type-params #(when % (mapv (fn [t] (resolve-generic-type t type-map)) %))))
    :else param-type))

(defn build-generic-type-map
  "Build a type-map from a class's generic params and a parameterized target type.
   E.g., class Box[T] with target-type Box[Integer] => {\"T\" \"Integer\"}."
  [env target-type]
  (when (map? target-type)
    (let [base-name (:base-type target-type)
          type-args (or (:type-args target-type) (:type-params target-type))
          class-def (env-lookup-class env base-name)]
      (when (and class-def (:generic-params class-def) type-args)
        (into {} (map (fn [param arg]
                        [(:name param) arg])
                      (:generic-params class-def) type-args))))))

(defn nil-literal?
  "Whether an expression node is a nil literal."
  [expr]
  (or (= expr "nil")
      (and (map? expr) (= :nil (:type expr)))))

(defn identifier-name
  "Extract identifier name from expression if it is a direct identifier."
  [expr]
  (cond
    (string? expr) expr
    (and (map? expr) (= :identifier (:type expr))) (:name expr)
    :else nil))

(defn guarded-non-nil-var
  "Extract variable name from condition of the form `x /= nil` or `nil /= x`."
  [condition]
  (when (and (map? condition)
             (= :binary (:type condition))
             (= "/=" (:operator condition)))
    (let [left (:left condition)
          right (:right condition)
          left-id (identifier-name left)
          right-id (identifier-name right)]
      (cond
        (and left-id (nil-literal? right)) left-id
        (and right-id (nil-literal? left)) right-id
        :else nil))))

(defn convert-guard-binding
  "Extract convert-bound variable info from condition of form:
   convert <expr> to <var>:<Type>"
  [condition]
  (when (and (map? condition) (= :convert (:type condition)))
    {:name (:var-name condition)
     :type (attachable-type (:target-type condition))}))

(defn detachable-version
  "Return a detachable type version for variable bindings that may be nil."
  [t]
  (let [n (normalize-type t)]
    (if (map? n)
      (assoc n :detachable true)
      {:base-type n :detachable true})))

(defn check-convert
  "Type-check convert expression:
   convert <value> to <var>:<Type>
   Returns Boolean and binds <var> as detachable <Type> in current scope."
  [env {:keys [value var-name target-type]}]
  (validate-type-annotation env target-type)
  (let [value-type (check-expression env value)
        target-type (normalize-type target-type)
        compatible? (or (types-compatible? env value-type target-type)
                        (types-compatible? env target-type value-type))]
    (when-not compatible?
      (throw (ex-info "Invalid convert type relation"
                      {:error (type-error
                               (str "convert requires related types, got "
                                    (display-type value-type)
                                    " and "
                                    (display-type target-type)))})))
    ;; convert may fail at runtime, so variable is detachable in this scope.
    (env-add-var env var-name (detachable-version target-type))
    "Boolean"))

(declare check-create)
(declare check-statement)

(defn- maybe-update-spawn-result!
  [env value-type]
  (when (env-lookup-var env "__spawn_result_type__")
    (let [current (env-lookup-var env "__spawn_result_type__")]
      (cond
        (= current "Void")
        (env-set! env "__spawn_result_type__" value-type)

        (types-compatible? env value-type current)
        nil

        (types-compatible? env current value-type)
        (env-set! env "__spawn_result_type__" value-type)

        :else
        (throw (ex-info "Inconsistent result types in spawn body"
                        {:error (type-error
                                 (str "Spawn body assigns incompatible result types: "
                                      (display-type current) " and " (display-type value-type)))}))))))

(defn check-spawn
  [env {:keys [body]}]
  (let [spawn-env (make-type-env env)]
    (env-add-var spawn-env "result" "Any")
    (env-add-var spawn-env "__spawn_result_type__" "Void")
    (doseq [stmt body]
      (check-statement spawn-env stmt))
    (let [result-type (env-lookup-var spawn-env "__spawn_result_type__")]
      (if (= result-type "Void")
        "Task"
        {:base-type "Task" :type-params [result-type]}))))

(defn- builtin-method-signature
  [base-type method argc type-map]
  (case base-type
    "Any"
    (case method
      "to_string" (when (= argc 0)
                    {:params [] :return-type "String"})
      "equals" (when (= argc 1)
                 {:params [{:name "other" :type "Any"}] :return-type "Boolean"})
      "clone" (when (= argc 0)
                {:params [] :return-type "Any"})
      nil)

    "Task"
    (case method
      "await" (case argc
                0 {:params [] :return-type (resolve-generic-type "T" type-map)}
                1 {:params [{:name "timeout_ms" :type "Integer"}]
                   :return-type (detachable-version (resolve-generic-type "T" type-map))}
                nil)
      "cancel" (when (= argc 0)
                 {:params [] :return-type "Boolean"})
      "is_done" (when (= argc 0)
                  {:params [] :return-type "Boolean"})
      "is_cancelled" (when (= argc 0)
                       {:params [] :return-type "Boolean"})
      nil)

    "Channel"
    (let [elem-type (or (resolve-generic-type "T" type-map) "Any")]
      (case method
        "send" (case argc
                 1 {:params [{:name "value" :type elem-type}] :return-type "Void"}
                 2 {:params [{:name "value" :type elem-type}
                             {:name "timeout_ms" :type "Integer"}]
                    :return-type "Boolean"}
                 nil)
        "try_send" (when (= argc 1)
                     {:params [{:name "value" :type elem-type}] :return-type "Boolean"})
        "receive" (case argc
                    0 {:params [] :return-type elem-type}
                    1 {:params [{:name "timeout_ms" :type "Integer"}]
                       :return-type (detachable-version elem-type)}
                    nil)
        "try_receive" (when (= argc 0)
                        {:params [] :return-type (detachable-version elem-type)})
        "close" (when (= argc 0) {:params [] :return-type "Void"})
        "is_closed" (when (= argc 0) {:params [] :return-type "Boolean"})
        "capacity" (when (= argc 0) {:params [] :return-type "Integer"})
        "size" (when (= argc 0) {:params [] :return-type "Integer"})
        nil))

    nil))

(defn check-call
  "Check the type of a method call"
  [env {:keys [target method args has-parens] :as expr}]
  (if (and (map? target) (= :create (:type target)) (nil? method))
    (check-create env (assoc target :args args))
    (if target
    ;; Method call on object
    (let [target-name (when (string? target) target)
          class-target (when target-name (env-lookup-class env target-name))
          target-type (if class-target
                        target-name
                        (if (string? target)
                          (env-lookup-var env target)
                          (check-expression env target)))
          normalized-target (normalize-type target-type)
          target-detachable? (detachable-type? normalized-target)
          guarded? (and (string? target) (env-var-non-nil? env target))
          ;; For parameterized types like Box[Integer], look up methods on the base class
          base-type (if (map? target-type)
                      (:base-type target-type)
                      target-type)
          ;; Build type-map for generic substitution
          type-map (build-generic-type-map env target-type)]
      (when (and (not class-target) target-detachable? (not guarded?))
        (throw (ex-info (str "Feature access on detachable target requires nil-check: " method)
                        {:error (type-error
                                 (str "Cannot call feature '" method "' on detachable "
                                      (display-type normalized-target)
                                      ". Wrap with: if <obj> /= nil then <obj>." method "(...) end"))})))
      (cond
        (and class-target (false? has-parens))
        (if-let [constant (lookup-class-constant env base-type method)]
          (resolve-generic-type (:field-type constant) type-map)
          "Any")

        :else
        (if-let [method-sig (or (builtin-method-signature base-type method (count args) type-map)
                                (builtin-method-signature "Any" method (count args) type-map)
                                (lookup-class-method env base-type method (count args)))]
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
                    ;; Resolve generic params (e.g., T -> Integer)
                    param-type (resolve-generic-type (:type param) type-map)]
                (when-not (types-compatible? env arg-type param-type)
                  (throw (ex-info (str "Argument type mismatch for method " method)
                                  {:error (type-error
                                           (str "Expected " (display-type param-type) ", got " (display-type arg-type)))})))))
            (resolve-generic-type (:return-type method-sig) type-map))
          ;; If this is member access without parens, attempt field lookup.
          (if (false? has-parens)
            (if-let [field-type (lookup-class-field env base-type method)]
              (resolve-generic-type field-type type-map)
              "Any")
            ;; Method not found - might be built-in method, return Any for now
            "Any"))))
    ;; Function call (built-in like print/type_of/type_is) or function object call
    (cond
      (= method "type_of")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "type_of expects exactly 1 argument"
                          {:error (type-error
                                   (str "type_of expects 1 argument, got " (count args)))})))
        (check-expression env (first args))
        "String")

      (= method "type_is")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "type_is expects exactly 2 arguments"
                          {:error (type-error
                                   (str "type_is expects 2 arguments, got " (count args)))})))
        (let [target-type-type (check-expression env (first args))]
          (when-not (= (attachable-type target-type-type) "String")
            (throw (ex-info "type_is first argument must be String"
                            {:error (type-error
                                     (str "type_is first argument must be String, got "
                                          (display-type target-type-type)))}))))
        (check-expression env (second args))
        "Boolean")

      (= method "await_all")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "await_all expects exactly 1 argument"
                          {:error (type-error
                                   (str "await_all expects 1 argument, got " (count args)))})))
        (let [tasks-type (normalize-type (check-expression env (first args)))
              task-type (when (map? tasks-type)
                          (let [base-type (:base-type tasks-type)
                                type-args (or (:type-params tasks-type) (:type-args tasks-type))]
                            (when (= base-type "Array")
                              (first type-args))))]
          (when-not (and task-type
                         (= (if (map? (attachable-type task-type))
                              (:base-type (attachable-type task-type))
                              (attachable-type task-type))
                            "Task"))
            (throw (ex-info "await_all expects Array[Task[T]]"
                            {:error (type-error
                                     (str "await_all expects Array[Task[T]], got "
                                          (display-type tasks-type)))})))
          {:base-type "Array"
           :type-params [(or (first (or (:type-params task-type) (:type-args task-type))) "Any")]}))

      (= method "await_any")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "await_any expects exactly 1 argument"
                          {:error (type-error
                                   (str "await_any expects 1 argument, got " (count args)))})))
        (let [tasks-type (normalize-type (check-expression env (first args)))
              task-type (when (map? tasks-type)
                          (let [base-type (:base-type tasks-type)
                                type-args (or (:type-params tasks-type) (:type-args tasks-type))]
                            (when (= base-type "Array")
                              (first type-args))))]
          (when-not (and task-type
                         (= (if (map? (attachable-type task-type))
                              (:base-type (attachable-type task-type))
                              (attachable-type task-type))
                            "Task"))
            (throw (ex-info "await_any expects Array[Task[T]]"
                            {:error (type-error
                                     (str "await_any expects Array[Task[T]], got "
                                          (display-type tasks-type)))})))
          (or (first (or (:type-params task-type) (:type-args task-type)))
              "Any")))

      :else
      (if-let [var-type (env-lookup-var env method)]
      (let [base-type (if (map? var-type) (:base-type var-type) var-type)
            call-name (str "call" (count args))
            method-sig (env-lookup-method env base-type call-name (count args))
            type-map (build-generic-type-map env var-type)]
        (when-not method-sig
          (throw (ex-info (str "Method not found: " call-name)
                          {:error (type-error
                                   (str "Method not found: " call-name))})))
        (when (not= (count args) (count (:params method-sig)))
          (throw (ex-info (str "Method " call-name " expects " (count (:params method-sig))
                               " arguments, got " (count args))
                          {:error (type-error
                                   (str "Method " call-name " expects " (count (:params method-sig))
                                        " arguments, got " (count args)))})))
        (doseq [[arg param] (map vector args (:params method-sig))]
          (let [arg-type (check-expression env arg)
                param-type (resolve-generic-type (:type param) type-map)]
            (when-not (types-compatible? env arg-type param-type)
              (throw (ex-info (str "Argument type mismatch for method " call-name)
                              {:error (type-error
                                       (str "Expected " (display-type param-type) ", got " (display-type arg-type)))})))))
        (resolve-generic-type (:return-type method-sig) type-map))
        (if-let [current-class (env-lookup-var env "__current_class__")]
          (if-let [method-sig (lookup-class-method env current-class method (count args))]
            (do
              (when (not= (count args) (count (:params method-sig)))
                (throw (ex-info (str "Method " method " expects " (count (:params method-sig))
                                     " arguments, got " (count args))
                                {:error (type-error
                                         (str "Method " method " expects " (count (:params method-sig))
                                              " arguments, got " (count args)))})))
              (doseq [[arg param] (map vector args (:params method-sig))]
                (let [arg-type (check-expression env arg)]
                  (when-not (types-compatible? env arg-type (:type param))
                    (throw (ex-info (str "Argument type mismatch for method " method)
                                    {:error (type-error
                                             (str "Expected " (:type param) ", got " arg-type))})))))
              (or (:return-type method-sig) "Void"))
            (do (doseq [arg args] (check-expression env arg)) "Void"))
          (do (doseq [arg args] (check-expression env arg)) "Void")))))))

(defn check-create
  "Check the type of a create expression"
  [env {:keys [class-name generic-args constructor args] :as expr}]
  (cond
    ;; Handle built-in Console type
    (= class-name "Console") "Console"
    ;; Handle built-in Process type
    (= class-name "Process") "Process"
    ;; Handle built-in Channel type
    (= class-name "Channel")
    (do
      (cond
        (nil? constructor)
        nil

        (= constructor "with_capacity")
        (do
          (when-not (= 1 (count args))
            (throw (ex-info "Channel.with_capacity expects 1 argument"
                            {:error (type-error "Channel.with_capacity expects exactly 1 Integer argument")})))
          (let [arg-type (check-expression env (first args))]
            (when-not (types-compatible? env arg-type "Integer")
              (throw (ex-info "Channel.with_capacity requires Integer capacity"
                              {:error (type-error
                                       (str "Channel.with_capacity expects Integer, got "
                                            (display-type arg-type)))})))))

        :else
        (throw (ex-info (str "Constructor not found: Channel." constructor)
                        {:error (type-error (str "Constructor not found: Channel." constructor))})))
      (if (seq generic-args)
        {:base-type "Channel" :type-args generic-args}
        "Channel"))
    ;; Handle built-in Window type
    (= class-name "Window") "Window"
    ;; Handle built-in Turtle type
    (= class-name "Turtle") "Turtle"
    ;; Handle built-in Image type
    (= class-name "Image") "Image"
    ;; Handle built-in File type
    (= class-name "File")
    (do
      (when (= constructor "open")
        (doseq [arg args]
          (let [arg-type (check-expression env arg)]
            (when-not (= arg-type "String")
              (throw (ex-info "File.open requires a String path argument"
                              {:error (type-error "File.open requires a String path argument")}))))))
      "File")
    :else
    (do
      ;; Check if class exists
      (when-not (or (env-lookup-class env class-name) (builtin-type? class-name))
        (throw (ex-info (str "Undefined class: " class-name)
                        {:error (type-error (str "Undefined class: " class-name))})))
      (let [class-def (env-lookup-class env class-name)
            target-type (if (seq generic-args)
                          (do
                            (validate-generic-args env class-name generic-args)
                            {:base-type class-name :type-args generic-args})
                          class-name)]
        (when (:deferred? class-def)
          (throw (ex-info (str "Cannot instantiate deferred class: " class-name)
                          {:error (type-error
                                   (str "Cannot instantiate deferred class " class-name
                                        "; instantiate a concrete child class instead"))})))
        ;; Imported Java classes have no Nex constructor signatures; skip validation.
        (if (and class-def (:import class-def))
          target-type
          (do
            (let [constructors (lookup-class-constructors env class-name)
                  has-constructors? (seq constructors)
                  type-map (build-generic-type-map env target-type)
                  ctor-name (or constructor "make")
                  ctor-sig (lookup-class-method env class-name ctor-name)]
              ;; If class defines constructors, disallow implicit default create.
              (when (and has-constructors?
                         (nil? constructor)
                         (empty? args))
                (throw (ex-info (str "Constructor required for class " class-name)
                                {:error (type-error
                                         (str "Class " class-name
                                              " defines constructors; use an explicit constructor call, e.g. create "
                                              class-name ".<ctor>(...)"))})))
              (when (or constructor (seq args))
                (when-not ctor-sig
                  (throw (ex-info (str "Constructor not found: " class-name "." ctor-name)
                                  {:error (type-error
                                           (str "Constructor not found: " class-name "." ctor-name))})))
                (let [params (:params ctor-sig)]
                  (when (not= (count params) (count args))
                    (throw (ex-info (str "Constructor argument count mismatch for " class-name "." ctor-name)
                                    {:error (type-error
                                             (str "Expected " (count params) " args, got "
                                                  (count args)))})))
                  (doseq [[arg param] (map vector args params)]
                    (let [arg-type (check-expression env arg)
                          param-type (resolve-generic-type (:type param) type-map)]
                      (when-not (types-compatible? env arg-type param-type)
                        (throw (ex-info (str "Argument type mismatch for constructor " class-name "." ctor-name)
                                        {:error (type-error
                                                 (str "Expected " (display-type param-type) ", got " (display-type arg-type)))}))))))))
            target-type))))))

(defn check-array-literal
  "Check the type of an array literal"
  [env {:keys [elements] :as expr}]
  (if (empty? elements)
    {:base-type "Array" :type-params ["Any"]}
    (let [first-type (check-expression env (first elements))]
      ;; Check all elements have same type
      (doseq [elem (rest elements)]
        (let [elem-type (check-expression env elem)]
          (when-not (types-equal? env first-type elem-type)
            (throw (ex-info "Array elements must have same type"
                            {:error (type-error
                                     (str "Array elements must have same type, got "
                                          (display-type first-type) " and " (display-type elem-type)))})))))
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
          (when-not (and (types-equal? env key-type k-type)
                        (types-equal? env val-type v-type))
            (throw (ex-info "Map entries must have consistent types"
                            {:error (type-error
                                     "Map entries must have consistent types")})))))
      {:base-type "Map" :type-params [key-type val-type]})))

(defn check-set-literal
  "Check the type of a set literal"
  [env {:keys [elements] :as expr}]
  (if (empty? elements)
    {:base-type "Set" :type-params ["__EmptySetElement"]}
    (let [first-type (check-expression env (first elements))]
      (doseq [elem (rest elements)]
        (let [elem-type (check-expression env elem)]
          (when-not (types-equal? env first-type elem-type)
            (throw (ex-info "Set elements must have same type"
                            {:error (type-error
                                     (str "Set elements must have same type, got "
                                          (display-type first-type) " and " (display-type elem-type)))})))))
      {:base-type "Set" :type-params [first-type]})))

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
      :nil (check-literal env expr)
      :identifier (check-identifier env expr)
      :binary (check-binary-op env expr)
      :unary (check-unary-op env expr)
      :call (check-call env expr)
      :create (check-create env expr)
      :array-literal (check-array-literal env expr)
      :set-literal (check-set-literal env expr)
      :map-literal (check-map-literal env expr)
      :subscript (let [target-type (check-expression env (:target expr))]
                       (if (map? target-type)
                         (let [type-params (or (:type-params target-type) (:type-args target-type))]
                           (cond
                             (= (:base-type target-type) "Array") (first type-params)
                             (= (:base-type target-type) "Map") (second type-params)
                             :else target-type))
                         target-type))
      :anonymous-function (let [class-def (:class-def expr)
                                 class-name (:class-name expr)]
                            ;; Register the dynamic class definition in the type environment
                            (collect-class-info env class-def)
                            ;; Check the class (this will check the callN method body)
                            (check-class env class-def)
                            ;; Return the class name.
                            ;; Since it inherits from Function, it will support callN methods.
                            class-name)
      :when (let [cond-type (check-expression env (:condition expr))
                   cons-type (check-expression env (:consequent expr))
                   alt-type (check-expression env (:alternative expr))]
               (when-not (types-compatible? env cond-type "Boolean")
                 (throw (ex-info "when condition must be Boolean"
                                 {:error (type-error
                                          (str "when condition has type " cond-type ", expected Boolean"))})))
               cons-type)
      :old (check-expression env (:expr expr))
      :convert (check-convert env expr)
      :spawn (check-spawn env expr)
      :this (or (env-lookup-var env "__current_class__") "Any")
      "Any")
    :else "Any"))

;;
;; Statement Type Checking
;;

(declare check-statement)

(defn check-assignment
  "Check an assignment statement"
  [env {:keys [target value] :as stmt}]
  (when-let [current-class (env-lookup-var env "__current_class__")]
    (when (lookup-class-constant env current-class target)
      (throw (ex-info (str "Cannot assign to constant: " target)
                      {:error (type-error (str "Cannot assign to constant: " target))}))))
  (let [var-type (env-lookup-var env target)
        val-type (check-expression env value)]
    (when-not var-type
      (throw (ex-info (str "Undefined variable: " target)
                      {:error (type-error (str "Undefined variable: " target))})))
    (when-not (types-compatible? env val-type var-type)
      (throw (ex-info (str "Type mismatch in assignment to " target)
                      {:error (type-error
                               (str "Cannot assign " (display-type val-type)
                                    " to variable of type " (display-type var-type)))})))
    (when (= target "result")
      (maybe-update-spawn-result! env val-type))))

(defn check-let
  "Check a let statement"
  [env {:keys [name var-type value synthetic] :as stmt}]
  (let [val-type (check-expression env value)
        inferred-type (or var-type val-type)]
    (when-not inferred-type
      (throw (ex-info (str "Type annotation required for variable '" name "'")
                      {:error (type-error
                               (str "Type annotation required for variable '" name
                                    "'. Use: let " name ": <Type> := ..."))})))
    (when var-type
      (validate-type-annotation env var-type))
    (when-not (types-compatible? env val-type inferred-type)
      (throw (ex-info (str "Type mismatch in let binding for " name)
                      {:error (type-error
                               (str "Cannot assign " (display-type val-type)
                                    " to variable '" name "' of type "
                                    (display-type inferred-type)))})))
    (env-add-var env name inferred-type)
    (when (= name "result")
      (maybe-update-spawn-result! env inferred-type))))

(defn check-if
  "Check an if statement"
  [env {:keys [condition then elseif else] :as stmt}]
  (let [cond-type (check-expression env condition)]
    (when-not (= cond-type "Boolean")
      (throw (ex-info "If condition must be Boolean"
                      {:error (type-error
                               (str "If condition must be Boolean, got " cond-type))}))))
  (let [then-env (make-type-env env)]
    (when-let [non-nil-var (guarded-non-nil-var condition)]
      (env-mark-non-nil then-env non-nil-var))
    (when-let [{:keys [name type]} (convert-guard-binding condition)]
      (env-add-var then-env name type)
      (env-mark-non-nil then-env name))
    (doseq [stmt then]
      (check-statement then-env stmt)))
  (doseq [clause elseif]
    (let [ei-cond-type (check-expression env (:condition clause))]
      (when-not (= ei-cond-type "Boolean")
        (throw (ex-info "Elseif condition must be Boolean"
                        {:error (type-error
                                 (str "Elseif condition must be Boolean, got " ei-cond-type))}))))
    (let [elseif-env (make-type-env env)]
      (when-let [non-nil-var (guarded-non-nil-var (:condition clause))]
        (env-mark-non-nil elseif-env non-nil-var))
      (when-let [{:keys [name type]} (convert-guard-binding (:condition clause))]
        (env-add-var elseif-env name type)
        (env-mark-non-nil elseif-env name))
      (doseq [stmt (:then clause)]
        (check-statement elseif-env stmt))))
  (when else
    (let [else-env (make-type-env env)]
      (doseq [stmt else] (check-statement else-env stmt)))))

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

(defn- select-clause-op
  [expr]
  (when (and (map? expr) (= :call (:type expr)))
    expr))

(defn- check-select-clause
  [env {:keys [expr alias body]}]
  (let [{:keys [target method args]} (or (select-clause-op expr)
                                         (throw (ex-info "select clause must be a channel or task operation"
                                                         {:error (type-error "select clause must be a channel send/receive call or task await call")})))
        target-type (check-expression env target)
        normalized-target (normalize-type target-type)
        base-type (if (map? normalized-target) (:base-type normalized-target) normalized-target)
        type-args (when (map? normalized-target)
                    (or (:type-params normalized-target) (:type-args normalized-target)))]
    (case base-type
      "Task"
      (do
        (when-not (= method "await")
          (throw (ex-info "select task clauses support only Task.await"
                          {:error (type-error "select task clauses support only Task.await")})))
        (when (seq args)
          (throw (ex-info "Task.await in select takes no arguments"
                          {:error (type-error "Task.await in select takes no arguments")})))
        (let [body-env (make-type-env env)]
          (when alias
            (env-add-var body-env alias (or (first type-args) "Any")))
          (doseq [stmt body]
            (check-statement body-env stmt))))

      "Channel"
      (case method
      ("receive" "try_receive")
      (do
        (cond
          (= method "try_receive")
          (when (seq args)
            (throw (ex-info "Channel.try_receive takes no arguments"
                            {:error (type-error "Channel.try_receive takes no arguments")})))

          (= method "receive")
          (when (> (count args) 1)
            (throw (ex-info "Channel.receive expects 0 or 1 arguments"
                            {:error (type-error "Channel.receive expects 0 or 1 arguments")}))))
        (when (= 1 (count args))
          (let [timeout-type (check-expression env (first args))]
            (when-not (= (attachable-type timeout-type) "Integer")
              (throw (ex-info "Channel.receive timeout must be Integer"
                              {:error (type-error
                                       (str "Channel.receive timeout must be Integer, got "
                                            (display-type timeout-type)))})))))
        (let [body-env (make-type-env env)]
          (when alias
            (env-add-var body-env alias (or (first type-args) "Any")))
          (doseq [stmt body]
            (check-statement body-env stmt))))

      ("send" "try_send")
      (do
        (cond
          (= method "try_send")
          (when-not (= 1 (count args))
            (throw (ex-info "Channel.try_send expects 1 argument"
                            {:error (type-error "Channel.try_send expects 1 argument")})))

          (= method "send")
          (when-not (<= 1 (count args) 2)
            (throw (ex-info "Channel.send expects 1 or 2 arguments"
                            {:error (type-error "Channel.send expects 1 or 2 arguments")}))))
        (when alias
          (throw (ex-info "send clauses cannot bind a value"
                          {:error (type-error "send clauses cannot use 'as <name>'")})))
        (let [arg-type (check-expression env (first args))
              elem-type (or (first type-args) "Any")]
          (when-not (types-compatible? env arg-type elem-type)
            (throw (ex-info (str "Channel." method " argument type mismatch")
                            {:error (type-error
                                     (str "Expected " (display-type elem-type)
                                          ", got " (display-type arg-type)))})))
          (when (= 2 (count args))
            (let [timeout-type (check-expression env (second args))]
              (when-not (= (attachable-type timeout-type) "Integer")
                (throw (ex-info "Channel.send timeout must be Integer"
                                {:error (type-error
                                         (str "Channel.send timeout must be Integer, got "
                                              (display-type timeout-type)))})))))
          (let [body-env (make-type-env env)]
            (doseq [stmt body]
              (check-statement body-env stmt)))))

      (throw (ex-info "select clauses support only Channel send/receive or Task.await operations"
                      {:error (type-error
                               "select clauses support only send, try_send, receive, try_receive, and Task.await")})))

      (throw (ex-info "select clause target must be a Channel or Task"
                      {:error (type-error
                               (str "select clause target must be Channel or Task, got "
                                    (display-type normalized-target)))})))))

(defn check-select
  [env {:keys [clauses else timeout]}]
  (doseq [clause clauses]
    (check-select-clause env clause))
  (when timeout
    (let [duration-type (check-expression env (:duration timeout))]
      (when-not (= (attachable-type duration-type) "Integer")
        (throw (ex-info "select timeout must be Integer"
                        {:error (type-error
                                 (str "select timeout must be Integer, got "
                                      (display-type duration-type)))})))
      (let [timeout-env (make-type-env env)]
        (doseq [stmt (:body timeout)]
          (check-statement timeout-env stmt)))))
  (when else
    (let [else-env (make-type-env env)]
      (doseq [stmt else]
        (check-statement else-env stmt)))))

(defn check-statement
  "Check a statement"
  [env stmt]
  (when (map? stmt)
    (case (:type stmt)
      :assign (check-assignment env stmt)
      :let (check-let env stmt)
      :call (check-expression env stmt)
      :convert (check-expression env stmt)
      :spawn (check-expression env stmt)
      :if (check-if env stmt)
      :loop (check-loop env stmt)
      :select (check-select env stmt)
      :scoped-block (do
                      (let [block-env (make-type-env env)]
                        (doseq [s (:body stmt)] (check-statement block-env s)))
                      (when-let [rescue (:rescue stmt)]
                        (let [rescue-env (make-type-env env)]
                          (env-add-var rescue-env "exception" "Any")
                          (doseq [s rescue] (check-statement rescue-env s)))))
      :with (doseq [s (:body stmt)] (check-statement env s))
      :case (do
              (check-expression env (:expr stmt))
              (doseq [clause (:clauses stmt)]
                (check-statement env (:body clause)))
              (when-let [else-stmt (:else stmt)]
                (check-statement env else-stmt)))
      :raise (check-expression env (:value stmt))
      :retry nil
      :member-assign
      (let [field-name (:field stmt)
            _ (when-let [current-class (env-lookup-var env "__current_class__")]
                (when (lookup-class-constant env current-class field-name)
                  (throw (ex-info (str "Cannot assign to constant: " field-name)
                                  {:error (type-error (str "Cannot assign to constant: " field-name))}))))
            field-type (env-lookup-var env field-name)
            val-type (check-expression env (:value stmt))]
        (when (and field-type val-type)
          (when-not (types-compatible? env val-type field-type)
            (throw (ex-info (str "Type mismatch in assignment to " field-name)
                            {:error (type-error
                                     (str "Cannot assign " (display-type val-type)
                                          " to field of type " (display-type field-type)))})))))
      nil)))

;;
;; Method/Constructor Type Checking
;;

(defn references-result?
  "Check if an AST node or any of its descendants references 'result' or 'Result'."
  [node]
  (cond
    (nil? node) false
    (string? node) (or (= node "result") (= node "Result"))
    (sequential? node) (some references-result? node)
    (map? node)
    (case (:type node)
      :assign (or (= (:target node) "result") (= (:target node) "Result")
                  (references-result? (:value node)))
      :let (or (= (:name node) "result") (= (:name node) "Result")
               (references-result? (:value node)))
      :identifier (or (= (:name node) "result") (= (:name node) "Result"))
      :anonymous-function false ;; Skip anonymous functions, they have their own Result scope
      :spawn false              ;; Spawn bodies have their own result scope
      ;; Walk all map values for other node types
      (some references-result? (vals node)))
    :else false))

(defn check-method
  "Check a method definition"
  [env class-name {:keys [name params return-type require body ensure rescue] :as method}]
  ;; Validate parameter and return type annotations (generic constraints)
  (doseq [param params]
    (when (:type param)
      (validate-type-annotation env (:type param))))
  (when return-type
    (validate-type-annotation env return-type))
  ;; Check that methods using Result declare a return type
  (when (and (not return-type)
             (or (some references-result? body)
                 (some #(references-result? (:condition %)) ensure)))
    (throw (ex-info (str "Return type required for method '" name "' because it uses Result")
                    {:error (type-error
                             (str "Method '" name "' uses Result but does not declare a return type. "
                                  "Use: " name "(...): <ReturnType>"))})))

  (let [method-env (make-type-env env)]
    ;; Track current class for this/super resolution
    (env-add-var method-env "__current_class__" class-name)

    ;; Add parameters to method environment
    (doseq [param params]
      (env-add-var method-env (:name param) (or (:type param) "Any")))

    ;; Add Result variable for return type
    (when return-type
      (env-add-var method-env "Result" return-type)
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
                                   (str "Postcondition must be Boolean, got " cond-type))})))))

    ;; Check rescue clause
    (when rescue
      (let [rescue-env (make-type-env method-env)]
        (env-add-var rescue-env "exception" "Any")
        (doseq [stmt rescue]
          (check-statement rescue-env stmt))))))

(defn check-constructor
  "Check a constructor definition"
  [env class-name {:keys [name params require body ensure rescue] :as constructor}]
  (let [ctor-env (make-type-env env)]
    ;; Track current class for this/super resolution
    (env-add-var ctor-env "__current_class__" class-name)

    ;; Validate parameter type annotations (generic constraints)
    (doseq [param params]
      (when (:type param)
        (validate-type-annotation env (:type param))))
    ;; Add parameters
    (doseq [param params]
      (env-add-var ctor-env (:name param) (or (:type param) "Any")))

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
                                     (str "Postcondition must be Boolean, got " cond-type))}))))))

    ;; Check rescue clause
    (when rescue
      (let [rescue-env (make-type-env ctor-env)]
        (env-add-var rescue-env "exception" "Any")
        (doseq [stmt rescue]
          (check-statement rescue-env stmt))))))

;;
;; Class Type Checking
;;

(defn collect-class-info
  "Collect class information (first pass)"
  [env {:keys [name body] :as class-def}]
  (env-add-class env name class-def)

  ;; Collect fields/constants and infer constant types.
  (let [updated-body
        (mapv (fn [section]
                (if (= (:type section) :feature-section)
                  (update section :members
                          (fn [members]
                            (mapv (fn [member]
                                    (let [member (if (:visibility member)
                                                   member
                                                   (assoc member :visibility (:visibility section)))]
                                      (if (= (:type member) :field)
                                        (if (:constant? member)
                                          (let [inferred-type (check-expression env (:value member))
                                                final-type (or (:field-type member) inferred-type)]
                                            (when (:field-type member)
                                              (validate-type-annotation env (:field-type member))
                                              (when-not (types-compatible? env inferred-type (:field-type member))
                                                (throw (ex-info (str "Type mismatch in constant " (:name member))
                                                                {:error (type-error
                                                                         (str "Cannot assign " (display-type inferred-type)
                                                                              " to constant '" (:name member)
                                                                              "' of type "
                                                                              (display-type (:field-type member))))}))))
                                            (env-add-var env (:name member) final-type)
                                            (assoc member :field-type final-type))
                                          (do
                                            (env-add-var env (:name member) (:field-type member))
                                            member))
                                        member)))
                                  members)))
                  section))
              body)
        updated-class-def (assoc class-def :body updated-body)]
    (env-add-class env name updated-class-def))

  ;; Collect method signatures
  (doseq [section (:body (env-lookup-class env name))]
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
  (doseq [{:keys [parent]} parents]
    ;; Check that parent class exists
    (when-not (or (env-lookup-class env parent) (builtin-type? parent))
      (throw (ex-info (str "Parent class " parent " not found for class " class-name)
                      {:error (type-error
                               (str "Undefined parent class: " parent))})))))

(defn check-class
  "Check a class definition"
  [env {:keys [name body invariant parents] :as class-def}]
  (let [class-def (or (env-lookup-class env name) class-def)
        body (:body class-def)
        invariant (:invariant class-def)
        parents (:parents class-def)]
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
        (cond
          (= (:type member) :method)
          (check-method env name member)
          (= (:type member) :field)
          (when-not (:constant? member)
            (validate-type-annotation env (:field-type member)))))

      (= (:type section) :constructors)
      (doseq [ctor (:constructors section)]
        (check-constructor env name ctor))))

  ;; Void-safety: attachable class-object fields must be initialized by all ctors.
  (let [fields (->> body
                    (filter #(= :feature-section (:type %)))
                    (mapcat :members)
                    (filter #(and (= :field (:type %))
                                  (not (:constant? %)))))
        constructors (->> body
                          (filter #(= :constructors (:type %)))
                          (mapcat :constructors))
        required-fields
        (->> fields
             (filter (fn [{:keys [field-type]}]
                       (let [t (normalize-type field-type)
                             a (attachable-type t)
                             base (if (map? a) (:base-type a) a)]
                         (and (not (detachable-type? t))
                              (string? base)
                              ;; Enforce for user-defined class objects.
                              (some? (env-lookup-class env base))
                              (not (builtin-type? base))))))
             (map :name)
             set)
        collect-assigned
        (fn collect-assigned [stmt]
          (case (:type stmt)
            :assign #{(:target stmt)}
            :member-assign #{(:field stmt)}
            :if (reduce set/union #{}
                        (concat
                         (map collect-assigned (:then stmt))
                         (mapcat #(map collect-assigned (:then %)) (:elseif stmt))
                         (map collect-assigned (:else stmt))))
            :loop (reduce set/union #{}
                          (concat (map collect-assigned (:init stmt))
                                  (map collect-assigned (:body stmt))))
            :scoped-block (reduce set/union #{}
                                  (concat (map collect-assigned (:body stmt))
                                          (map collect-assigned (:rescue stmt))))
            :with (reduce set/union #{} (map collect-assigned (:body stmt)))
            :case (reduce set/union #{}
                          (concat (map #(collect-assigned (:body %)) (:clauses stmt))
                                  (when-let [e (:else stmt)] [(collect-assigned e)])))
            #{}))]
    (when (seq required-fields)
      (when (empty? constructors)
        (throw (ex-info (str "Class " name " has attachable fields that require constructor initialization")
                        {:error (type-error
                                 (str "Attachable fields must be initialized by constructors in class "
                                      name ": " (str/join ", " (sort required-fields))))})))
      (doseq [{:keys [name body]} constructors]
        (let [assigned (reduce set/union #{} (map collect-assigned body))
              missing (sort (seq (set/difference required-fields assigned)))]
          (when (seq missing)
            (throw (ex-info (str "Constructor " name " does not initialize all attachable fields")
                            {:error (type-error
                                     (str "Constructor " name " must initialize attachable fields: "
                                          (str/join ", " missing)))})))))))))

;;
;; Program Type Checking
;;

(defn register-builtin-methods
  "Register method signatures for built-in types."
  [env]
  ;; Built-in deferred protocol classes
  (env-add-class env "Any" {:name "Any"
                            :deferred? false
                            :generic-params nil
                            :parents nil
                            :body []})
  (doseq [[method-name sig]
          {"to_string" {:params [] :return-type "String"}
           "equals" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "clone" {:params [] :return-type "Any"}}]
    (env-add-method env "Any" method-name sig))

  (env-add-class env "Comparable" {:name "Comparable"
                                   :deferred? true
                                   :generic-params nil
                                   :parents nil
                                   :body []})
  (env-add-method env "Comparable" "compare"
                  {:params [{:name "a" :type "Any"}]
                   :return-type "Integer"})

  (env-add-class env "Hashable" {:name "Hashable"
                                 :deferred? true
                                 :generic-params nil
                                 :parents nil
                                 :body []})
  (env-add-method env "Hashable" "hash"
                  {:params []
                   :return-type "Integer"})

  ;; Built-in scalar classes implement Comparable + Hashable
  (doseq [scalar ["String" "Integer" "Integer64" "Real" "Decimal" "Boolean" "Char"]]
    (env-add-class env scalar {:name scalar
                               :deferred? false
                               :generic-params nil
                               :parents [{:parent "Any"} {:parent "Comparable"} {:parent "Hashable"}]
                               :body []})
    (env-add-method env scalar "compare"
                    {:params [{:name "a" :type "Any"}]
                     :return-type "Integer"})
    (env-add-method env scalar "hash"
                    {:params []
                     :return-type "Integer"}))

  (doseq [[method-name sig]
          {"bitwise_left_shift" {:params [{:name "n" :type "Integer"}] :return-type "Integer"}
           "bitwise_right_shift" {:params [{:name "n" :type "Integer"}] :return-type "Integer"}
           "bitwise_logical_right_shift" {:params [{:name "n" :type "Integer"}] :return-type "Integer"}
           "bitwise_rotate_left" {:params [{:name "n" :type "Integer"}] :return-type "Integer"}
           "bitwise_rotate_right" {:params [{:name "n" :type "Integer"}] :return-type "Integer"}
           "bitwise_is_set" {:params [{:name "n" :type "Integer"}] :return-type "Boolean"}
           "bitwise_set" {:params [{:name "n" :type "Integer"}] :return-type "Integer"}
           "bitwise_unset" {:params [{:name "n" :type "Integer"}] :return-type "Integer"}
           "bitwise_and" {:params [{:name "x" :type "Integer"}] :return-type "Integer"}
           "bitwise_or" {:params [{:name "x" :type "Integer"}] :return-type "Integer"}
           "bitwise_xor" {:params [{:name "x" :type "Integer"}] :return-type "Integer"}
           "bitwise_not" {:params [] :return-type "Integer"}}]
    (env-add-method env "Integer" method-name sig))

  (doseq [[method-name sig]
          {"length"      {:params [] :return-type "Integer"}
           "index_of"    {:params [{:name "substr" :type "String"}] :return-type "Integer"}
           "substring"   {:params [{:name "start" :type "Integer"} {:name "end" :type "Integer"}] :return-type "String"}
           "to_upper"    {:params [] :return-type "String"}
           "to_lower"    {:params [] :return-type "String"}
           "to_integer"  {:params [] :return-type "Integer"}
           "to_integer64" {:params [] :return-type "Integer64"}
           "to_real"     {:params [] :return-type "Real"}
           "to_decimal"  {:params [] :return-type "Decimal"}
           "contains"    {:params [{:name "substr" :type "String"}] :return-type "Boolean"}
           "starts_with" {:params [{:name "prefix" :type "String"}] :return-type "Boolean"}
           "ends_with"   {:params [{:name "suffix" :type "String"}] :return-type "Boolean"}
           "trim"        {:params [] :return-type "String"}
           "replace"     {:params [{:name "old" :type "String"} {:name "new" :type "String"}] :return-type "String"}
           "char_at"     {:params [{:name "index" :type "Integer"}] :return-type "Char"}
           "compare"     {:params [{:name "a" :type "Any"}] :return-type "Integer"}
           "hash"        {:params [] :return-type "Integer"}
           "split"       {:params [{:name "delimiter" :type "String"}]
                          :return-type {:base-type "Array" :type-params ["String"]}}}]
    (env-add-method env "String" method-name sig))
  (doseq [[method-name sig]
          {"print" {:params [{:name "msg" :type "String"}] :return-type "Void"}
           "print_line" {:params [{:name "msg" :type "String"}] :return-type "Void"}
           "read_line" {:params [] :return-type "String"}
           "error" {:params [{:name "msg" :type "String"}] :return-type "Void"}
           "new_line" {:params [] :return-type "Void"}
           "read_integer" {:params [] :return-type "Integer"}
           "read_real" {:params [] :return-type "Real"}}]
    (env-add-method env "Console" method-name sig))
  (env-add-class env "Task" {:name "Task"
                             :generic-params [{:name "T"}]})
  (doseq [[method-name sig]
          {"await"   {:params [] :return-type "T"}
           "cancel"  {:params [] :return-type "Boolean"}
           "is_done" {:params [] :return-type "Boolean"}
           "is_cancelled" {:params [] :return-type "Boolean"}}]
    (env-add-method env "Task" method-name sig))
  (doseq [[method-name sig]
          {"read" {:params [] :return-type "String"}
           "write" {:params [{:name "content" :type "String"}] :return-type "Void"}
           "append" {:params [{:name "content" :type "String"}] :return-type "Void"}
           "exists" {:params [] :return-type "Boolean"}
           "delete" {:params [] :return-type "Void"}
           "lines" {:params [] :return-type {:base-type "Array" :type-params ["String"]}}
           "close" {:params [] :return-type "Void"}}]
    (env-add-method env "File" method-name sig))
  (doseq [[method-name sig]
          {"getenv" {:params [{:name "name" :type "String"}] :return-type "String"}
           "setenv" {:params [{:name "name" :type "String"} {:name "value" :type "String"}] :return-type "Void"}
           "command_line" {:params [] :return-type {:base-type "Array" :type-params ["String"]}}}]
    (env-add-method env "Process" method-name sig))
  (doseq [[method-name sig]
          {"show"          {:params [] :return-type "Void"}
           "close"         {:params [] :return-type "Void"}
           "clear"         {:params [] :return-type "Void"}
           "bgcolor"       {:params [{:name "color" :type "String"}] :return-type "Void"}
           "refresh"       {:params [] :return-type "Void"}
           "set_color"     {:params [{:name "color" :type "String"}] :return-type "Void"}
           "set_font_size" {:params [{:name "size" :type "Integer"}] :return-type "Void"}
           "draw_line"     {:params [{:name "x1" :type "Real"} {:name "y1" :type "Real"}
                                     {:name "x2" :type "Real"} {:name "y2" :type "Real"}] :return-type "Void"}
           "draw_rect"     {:params [{:name "x" :type "Real"} {:name "y" :type "Real"}
                                     {:name "w" :type "Real"} {:name "h" :type "Real"}] :return-type "Void"}
           "fill_rect"     {:params [{:name "x" :type "Real"} {:name "y" :type "Real"}
                                     {:name "w" :type "Real"} {:name "h" :type "Real"}] :return-type "Void"}
           "draw_circle"   {:params [{:name "x" :type "Real"} {:name "y" :type "Real"}
                                     {:name "r" :type "Real"}] :return-type "Void"}
           "fill_circle"   {:params [{:name "x" :type "Real"} {:name "y" :type "Real"}
                                     {:name "r" :type "Real"}] :return-type "Void"}
           "draw_text"     {:params [{:name "text" :type "String"} {:name "x" :type "Real"}
                                     {:name "y" :type "Real"}] :return-type "Void"}
           "draw_image"    {:params [{:name "img" :type "Image"} {:name "x" :type "Real"}
                                     {:name "y" :type "Real"}] :return-type "Void"}
           "draw_image_scaled"  {:params [{:name "img" :type "Image"} {:name "x" :type "Real"}
                                          {:name "y" :type "Real"} {:name "w" :type "Real"}
                                          {:name "h" :type "Real"}] :return-type "Void"}
           "draw_image_rotated" {:params [{:name "img" :type "Image"} {:name "x" :type "Real"}
                                          {:name "y" :type "Real"} {:name "angle" :type "Real"}] :return-type "Void"}
           "sleep"         {:params [{:name "ms" :type "Integer"}] :return-type "Void"}}]
    (env-add-method env "Window" method-name sig))
  (doseq [[method-name sig]
          {"width"  {:params [] :return-type "Integer"}
           "height" {:params [] :return-type "Integer"}}]
    (env-add-method env "Image" method-name sig))
  (doseq [[method-name sig]
          {"forward"    {:params [{:name "distance" :type "Real"}] :return-type "Void"}
           "backward"   {:params [{:name "distance" :type "Real"}] :return-type "Void"}
           "right"      {:params [{:name "angle" :type "Real"}] :return-type "Void"}
           "left"       {:params [{:name "angle" :type "Real"}] :return-type "Void"}
           "penup"      {:params [] :return-type "Void"}
           "pendown"    {:params [] :return-type "Void"}
           "color"      {:params [{:name "color" :type "String"}] :return-type "Void"}
           "pensize"    {:params [{:name "size" :type "Integer"}] :return-type "Void"}
           "speed"      {:params [{:name "speed" :type "Integer"}] :return-type "Void"}
           "shape"      {:params [{:name "shape" :type "String"}] :return-type "Void"}
           "goto"       {:params [{:name "x" :type "Real"} {:name "y" :type "Real"}] :return-type "Void"}
           "circle"     {:params [{:name "radius" :type "Real"}] :return-type "Void"}
           "begin_fill" {:params [] :return-type "Void"}
           "end_fill"   {:params [] :return-type "Void"}
           "hide"       {:params [] :return-type "Void"}
           "show"       {:params [] :return-type "Void"}}]
    (env-add-method env "Turtle" method-name sig))

  ;; Register Array[T] class and methods
  (env-add-class env "Array" {:name "Array"
                               :generic-params [{:name "T"}]})
  (doseq [[method-name sig]
          {"get"         {:params [{:name "index" :type "Integer"}] :return-type "T"}
           "add"         {:params [{:name "value" :type "T"}] :return-type "Void"}
           "push"        {:params [{:name "value" :type "T"}] :return-type "Void"}
           "at"          {:params [{:name "index" :type "Integer"} {:name "value" :type "T"}] :return-type "Void"}
           "set"         {:params [{:name "index" :type "Integer"} {:name "value" :type "T"}] :return-type "Void"}
           "length"      {:params [] :return-type "Integer"}
           "size"        {:params [] :return-type "Integer"}
           "is_empty"    {:params [] :return-type "Boolean"}
           "contains"    {:params [{:name "elem" :type "T"}] :return-type "Boolean"}
           "index_of"    {:params [{:name "elem" :type "T"}] :return-type "Integer"}
           "remove"      {:params [{:name "index" :type "Integer"}] :return-type "Void"}
           "reverse"     {:params [] :return-type "Void"}
           "sort"        {:params [] :return-type "Void"}
           "slice"       {:params [{:name "start" :type "Integer"} {:name "end" :type "Integer"}]
                          :return-type {:base-type "Array" :type-params ["T"]}}
           "first"       {:params [] :return-type "T"}
           "last"        {:params [] :return-type "T"}
           "join"        {:params [{:name "sep" :type "String"}] :return-type "String"}
           "to_string"   {:params [] :return-type "String"}
           "equals"      {:params [{:name "other" :type {:base-type "Array" :type-params ["T"]}}] :return-type "Boolean"}
           "clone"       {:params [] :return-type {:base-type "Array" :type-params ["T"]}}
           "cursor"      {:params [] :return-type "Cursor"}}]
    (env-add-method env "Array" method-name sig))

  ;; Register Map[K, V] class and methods
  (env-add-class env "Map" {:name "Map"
                             :generic-params [{:name "K"} {:name "V"}]})
  (doseq [[method-name sig]
          {"get"          {:params [{:name "key" :type "K"}] :return-type "V"}
           "try_get"      {:params [{:name "key" :type "K"} {:name "default" :type "V"}] :return-type "V"}
           "at"           {:params [{:name "key" :type "K"} {:name "value" :type "V"}] :return-type "Void"}
           "set"          {:params [{:name "key" :type "K"} {:name "value" :type "V"}] :return-type "Void"}
           "size"         {:params [] :return-type "Integer"}
           "is_empty"     {:params [] :return-type "Boolean"}
           "contains_key" {:params [{:name "key" :type "K"}] :return-type "Boolean"}
           "keys"         {:params [] :return-type {:base-type "Array" :type-params ["K"]}}
           "values"       {:params [] :return-type {:base-type "Array" :type-params ["V"]}}
           "remove"       {:params [{:name "key" :type "K"}] :return-type "Void"}
           "to_string"    {:params [] :return-type "String"}
           "equals"       {:params [{:name "other" :type {:base-type "Map" :type-params ["K" "V"]}}] :return-type "Boolean"}
           "clone"        {:params [] :return-type {:base-type "Map" :type-params ["K" "V"]}}
           "cursor"       {:params [] :return-type "Cursor"}}]
    (env-add-method env "Map" method-name sig))

  ;; Register Set[T] class and methods
  (env-add-class env "Set" {:name "Set"
                            :generic-params [{:name "T"}]})
  (env-add-method env "Set" "from_array"
                  {:params [{:name "values"
                             :type {:base-type "Array" :type-params ["T"]}}]
                   :return-type {:base-type "Set" :type-params ["T"]}})
  (doseq [[method-name sig]
          {"contains"             {:params [{:name "value" :type "T"}] :return-type "Boolean"}
           "union"                {:params [{:name "other" :type {:base-type "Set" :type-params ["T"]}}]
                                   :return-type {:base-type "Set" :type-params ["T"]}}
           "difference"           {:params [{:name "other" :type {:base-type "Set" :type-params ["T"]}}]
                                   :return-type {:base-type "Set" :type-params ["T"]}}
           "intersection"         {:params [{:name "other" :type {:base-type "Set" :type-params ["T"]}}]
                                   :return-type {:base-type "Set" :type-params ["T"]}}
           "symmetric_difference" {:params [{:name "other" :type {:base-type "Set" :type-params ["T"]}}]
                                   :return-type {:base-type "Set" :type-params ["T"]}}
           "size"                 {:params [] :return-type "Integer"}
           "is_empty"             {:params [] :return-type "Boolean"}
           "to_string"            {:params [] :return-type "String"}
           "equals"               {:params [{:name "other" :type {:base-type "Set" :type-params ["T"]}}] :return-type "Boolean"}
           "clone"                {:params [] :return-type {:base-type "Set" :type-params ["T"]}}
           "cursor"               {:params [] :return-type "Cursor"}}]
    (env-add-method env "Set" method-name sig))

  ;; Register Channel[T] class and methods
  (env-add-class env "Channel" {:name "Channel"
                                :generic-params [{:name "T"}]})
  (doseq [[method-name sig]
          {"send"        {:params [{:name "value" :type "T"}] :return-type "Void"}
           "try_send"    {:params [{:name "value" :type "T"}] :return-type "Boolean"}
           "receive"     {:params [] :return-type "T"}
           "try_receive" {:params [] :return-type {:base-type "T" :detachable true}}
           "close"       {:params [] :return-type "Void"}
           "is_closed"   {:params [] :return-type "Boolean"}
           "capacity"    {:params [] :return-type "Integer"}
           "size"        {:params [] :return-type "Integer"}}]
    (env-add-method env "Channel" method-name sig))

  ;; Built-in Function methods: call0..call32
  (doseq [n (range 0 33)]
    (env-add-method env "Function"
                    (str "call" n)
                    {:params (mapv (fn [i] {:name (str "arg" i) :type "Any"})
                                   (range 1 (inc n)))
                     :return-type "Any"})))

(defn check-program
  "Type check a complete program.
   opts may include :var-types - a map of {var-name => type} for pre-existing variables."
  ([program] (check-program program {}))
  ([{:keys [classes calls statements imports functions] :as program} opts]
   (let [env (make-type-env)]
     (try
       ;; Register imported Java classes (as placeholders)
       (doseq [{:keys [qualified-name source]} imports]
         (when (nil? source)
           (let [simple-name (last (str/split qualified-name #"\."))]
             (env-add-class env simple-name {:name simple-name :body [] :import qualified-name}))))

       ;; First pass: collect all class definitions
       (doseq [class-def classes]
         (collect-class-info env class-def))

       (register-builtin-methods env)

       ;; Inject pre-existing variable types (e.g., from REPL)
       (doseq [[var-name var-type] (:var-types opts)]
         (env-add-var env var-name var-type))

       ;; Register function variables (name -> generated class)
       (doseq [fn-def functions]
         (let [arity (count (:params fn-def))]
           (when (> arity 32)
             (throw (ex-info (str "Function " (:name fn-def)
                                  " must have at most 32 parameters")
                             {:error (type-error
                                      (str "Function " (:name fn-def)
                                           " must have at most 32 parameters"))}))))
         (env-add-var env (:name fn-def) (:class-name fn-def)))

       ;; Second pass: check class bodies
       (doseq [class-def classes]
         (check-class env class-def))

       ;; Check top-level statements in source order when available.
       ;; Fall back to legacy :calls-only programs.
       (if (seq statements)
         (doseq [stmt statements]
           (check-statement env stmt))
         (doseq [call calls]
           (check-expression env call)))

       {:success true
        :errors []}

       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (let [error-data (ex-data e)]
           {:success false
            :errors [(or (:error error-data)
                        (type-error (ex-message e)))]}))))))

(defn type-check
  "Type check Nex code (entry point).
   opts may include :var-types - a map of {var-name => type} for pre-existing variables."
  ([ast] (type-check ast {}))
  ([ast opts]
   (check-program ast opts)))

(defn infer-expression-type
  "Infer the type of an expression AST node.
   opts: :classes - seq of class defs, :var-types - {name type} map.
   Returns the type (string or map) or nil on failure."
  [expr opts]
  (try
    (let [env (make-type-env)]
      (doseq [{:keys [qualified-name source]} (:imports opts)]
        (when (nil? source)
          (let [simple-name (last (str/split qualified-name #"\."))]
            (env-add-class env simple-name {:name simple-name :body [] :import qualified-name}))))
      (doseq [class-def (:classes opts)]
        (collect-class-info env class-def))
      (register-builtin-methods env)
      (doseq [[var-name var-type] (:var-types opts)]
        (env-add-var env var-name var-type))
      (check-expression env expr))
    (catch #?(:clj Exception :cljs :default) _ nil)))
