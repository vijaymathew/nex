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
    :non-nil-vars (atom #{})
    :across-cursors (atom {})}))

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

(defn env-add-across-cursor
  "Associate a synthetic across cursor binding with its iterated item type."
  [env cursor-name item-type]
  (when-let [ac (:across-cursors env)]
    (swap! ac assoc cursor-name item-type)))

(defn env-lookup-across-cursor
  "Look up the iterated item type for a synthetic across cursor binding."
  [env cursor-name]
  (or (when-let [ac (:across-cursors env)]
        (get @ac cursor-name))
      (when (:parent env)
        (env-lookup-across-cursor (:parent env) cursor-name))))

;;
;; Built-in Types
;;

(def builtin-types
  #{"Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Min_Heap" "Atomic_Integer" "Atomic_Integer64" "Atomic_Boolean" "Atomic_Reference"
    "Task" "Channel" "Any" "Void" "Nil" "Console" "Process" "Function"
    "Cursor"})

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

(defn- location-from-node
  [node]
  (when (map? node)
    (let [line (:dbg/line node)
          column (:dbg/col node)]
      (when line
        {:line line :column column}))))

(defn- error-with-location
  [err node]
  (let [{:keys [line column]} (location-from-node node)]
    (if (and err line (nil? (:line err)))
      (cond-> (assoc err :line line)
        column (assoc :column column))
      err)))

(defn- annotate-type-exception
  [e node]
  (let [data (ex-data e)
        err (:error data)]
    (if-let [located (or (error-with-location err node)
                         (when-let [{:keys [line column]} (location-from-node node)]
                           (type-error (ex-message e) line column)))]
      (ex-info (ex-message e) (assoc data :error located) e)
      e)))

(defn- with-type-error-location
  [node f]
  (try
    (f)
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
      (throw (annotate-type-exception e node)))))

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
               (= (:base-type a1) "Array")
               (= (:base-type a2) "Array")
               (= (:type-params a1) ["__EmptyArrayElement"]))
          (and (map? a1) (map? a2)
               (= (:base-type a1) "Map")
               (= (:base-type a2) "Map")
               (= (:type-params a1) ["__EmptyMapKey" "__EmptyMapValue"]))
          (and (map? a1) (map? a2)
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

(defn sortable-array-element-type?
  [env elem-type]
  (let [t (attachable-type (normalize-type elem-type))]
    (or (= t "String")
        (= t "Char")
        (= t "Boolean")
        (is-numeric-type? t)
        (types-compatible? env t "Comparable"))))

(defn cursor-item-type
  "Return the static element type yielded when iterating over target-type."
  [target-type]
  (let [t (attachable-type (normalize-type target-type))
        base (if (map? t) (:base-type t) t)
        type-args (when (map? t) (or (:type-params t) (:type-args t)))]
    (case base
      "Array" (or (first type-args) "Any")
      "Set" (or (first type-args) "Any")
      "String" "Char"
      "Map" "Any"
      "Cursor" "Any"
      "Any")))

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

(defn numeric-result-type
  "Infer a common numeric type for non-division arithmetic.
   Real wins over Decimal, Decimal wins over integral types, Integer64 wins
   over Integer."
  [left-type right-type]
  (let [left (normalize-type left-type)
        right (normalize-type right-type)]
    (cond
      (or (= left "Real") (= right "Real")) "Real"
      (or (= left "Decimal") (= right "Decimal")) "Decimal"
      (or (= left "Integer64") (= right "Integer64")) "Integer64"
      :else "Integer")))

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
(declare convert-guard-binding)
(declare convert-guard-bindings)
(declare resolve-generic-type)
(declare lookup-class-field-member)

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

(declare feature-members)
(declare public-member?)

(defn lookup-class-method
  "Look up a method on a class and its parent chain"
  ([env class-name method-name]
   (lookup-class-method env class-name method-name nil class-name))
  ([env class-name method-name arity]
   (lookup-class-method env class-name method-name arity class-name))
  ([env class-name method-name arity caller-class-name]
   (letfn [(lookup-method [cn visited]
             (when (and cn (not (contains? visited cn)))
               (let [class-def (env-lookup-class env cn)
                     visited' (conj visited cn)
                     method-sig (env-lookup-method env cn method-name arity)
                     feature-member (when class-def
                                      (some (fn [member]
                                              (when (and (= (:type member) :method)
                                                         (= (:name member) method-name)
                                                         (or (nil? arity)
                                                             (= (count (or (:params member) [])) arity)))
                                                member))
                                            (feature-members class-def)))
                     own-method (when (and method-sig
                                           (or (nil? feature-member)
                                               (= caller-class-name cn)
                                               (public-member? feature-member)))
                                  method-sig)]
                 (or own-method
                     (when class-def
                       (some (fn [{:keys [parent]}]
                               (lookup-method parent visited'))
                             (:parents class-def)))))))]
     (lookup-method class-name #{}))))

(defn lookup-class-method-any-arity
  "Look up a method name on a class and its parent chain, ignoring arity."
  [env class-name method-name caller-class-name]
  (letfn [(lookup-method [cn visited]
            (when (and cn (not (contains? visited cn)))
              (let [class-def (env-lookup-class env cn)
                    visited' (conj visited cn)
                    feature-member (when class-def
                                     (some (fn [member]
                                             (when (and (= (:type member) :method)
                                                        (= (:name member) method-name))
                                               member))
                                           (feature-members class-def)))
                    method-sig (when (and feature-member
                                          (or (= caller-class-name cn)
                                              (public-member? feature-member)))
                                 (env-lookup-method env cn method-name))]
                (or method-sig
                    (when class-def
                      (some (fn [{:keys [parent]}]
                              (lookup-method parent visited'))
                            (:parents class-def)))))))]
    (lookup-method class-name #{})))

#?(:clj
   (defn- resolve-imported-java-class
     [env class-name]
     (when (string? class-name)
       (let [class-def (env-lookup-class env class-name)
             qualified-name (or (:import class-def)
                                (when (str/includes? class-name ".")
                                  class-name))]
         (when qualified-name
           (try
             (Class/forName qualified-name)
             (catch Exception _
               nil))))))
   :cljs
   (defn- resolve-imported-java-class
     [_ _]
     nil))

(defn- known-reference-type
  [env ^#?(:clj Class :cljs js/Object) klass]
  (let [simple-name #?(:clj (.getSimpleName klass) :cljs nil)]
    (cond
      (builtin-type? simple-name) simple-name
      (env-lookup-class env simple-name) simple-name
      :else "Any")))

#?(:clj
   (defn- java-class->nex-type
     [env ^Class klass]
     (cond
       (nil? klass) "Any"
       (= klass Void/TYPE) "Void"
       (= klass java.lang.Void) "Void"
       (.isArray klass)
       {:base-type "Array"
        :type-params [(java-class->nex-type env (.getComponentType klass))]}

       (= klass java.lang.String) "String"

       (or (= klass Byte/TYPE)
           (= klass java.lang.Byte)
           (= klass Short/TYPE)
           (= klass java.lang.Short)
           (= klass Integer/TYPE)
           (= klass java.lang.Integer))
       "Integer"

       (or (= klass Long/TYPE)
           (= klass java.lang.Long))
       "Integer64"

       (or (= klass Float/TYPE)
           (= klass java.lang.Float)
           (= klass Double/TYPE)
           (= klass java.lang.Double))
       "Real"

       (= klass java.math.BigDecimal) "Decimal"

       (or (= klass Boolean/TYPE)
           (= klass java.lang.Boolean))
       "Boolean"

       (or (= klass Character/TYPE)
           (= klass java.lang.Character))
       "Char"

       (= klass java.lang.Object) "Any"

       :else
       (known-reference-type env klass)))
   :cljs
   (defn- java-class->nex-type
     [_ _]
     "Any"))

#?(:clj
   (defn- reflected-java-method-signatures
     [env class-name method-name argc static?]
     (when-let [klass (resolve-imported-java-class env class-name)]
       (->> (.getMethods ^Class klass)
            (filter (fn [^java.lang.reflect.Method method]
                      (and (= (.getName method) method-name)
                           (= (java.lang.reflect.Modifier/isStatic (.getModifiers method)) static?)
                           (= (alength (.getParameterTypes method)) argc))))
            (mapv (fn [^java.lang.reflect.Method method]
                    {:params (mapv (fn [index ^Class param-type]
                                     {:name (str "arg" index)
                                      :type (java-class->nex-type env param-type)})
                                   (range argc)
                                   (.getParameterTypes method))
                     :return-type (java-class->nex-type env (.getReturnType method))})))))
   :cljs
   (defn- reflected-java-method-signatures
     [_ _ _ _ _]
     nil))

(defn- reflected-java-method-signature
  [env class-name method-name arg-types static?]
  (let [static? (boolean static?)]
    (some (fn [signature]
            (when (every? true?
                          (map (fn [arg-type param]
                                 (types-compatible? env arg-type (:type param)))
                               arg-types
                               (:params signature)))
              signature))
          (reflected-java-method-signatures env class-name method-name (count arg-types) static?))))

(defn lookup-class-field
  "Look up a field on a class and its parent chain."
  ([env class-name field-name]
   (lookup-class-field env class-name field-name class-name))
  ([env class-name field-name caller-class-name]
   (some-> (lookup-class-field-member env class-name field-name caller-class-name)
           :field-type)))

(defn lookup-class-field-member
  "Look up a field member on a class and its parent chain."
  ([env class-name field-name]
   (lookup-class-field-member env class-name field-name class-name))
  ([env class-name field-name caller-class-name]
   (letfn [(lookup-field [cn visited]
            (when (and cn (not (contains? visited cn)))
              (let [class-def (env-lookup-class env cn)
                    visited' (conj visited cn)
                    own-field
                    (when class-def
                      (some (fn [member]
                              (when (and (= (:type member) :field)
                                         (not (:constant? member))
                                         (= (:name member) field-name)
                                         (or (= caller-class-name cn)
                                             (public-member? member)))
                                (assoc member :declaring-class cn)))
                            (feature-members class-def)))]
                (or own-field
                    (when class-def
                      (some (fn [{:keys [parent]}]
                              (lookup-field parent visited'))
                            (:parents class-def)))))))]
     (lookup-field class-name #{}))))

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

(defn- field-write-error
  [field-name declaring-class]
  (type-error
   (str "Cannot assign to field " field-name
        " outside of class " declaring-class)))

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

(defn- bind-visible-class-fields!
  "Bind fields visible inside class-name into target-env.
   Own fields are always visible; inherited fields must be public."
  [target-env env class-name]
  (letfn [(bind-fields [cn visited inherited?]
            (when (and cn (not (contains? visited cn)))
              (when-let [class-def (env-lookup-class env cn)]
                (let [visited' (conj visited cn)]
                  (doseq [{:keys [parent]} (:parents class-def)]
                    (bind-fields parent visited' true))
                  (doseq [member (feature-members class-def)]
                    (when (and (= (:type member) :field)
                               (not (:constant? member))
                               (or (not inherited?)
                                   (public-member? member)))
                      (env-add-var target-env (:name member) (:field-type member))))))))]
    (bind-fields class-name #{} false)))

(defn check-identifier
  "Check the type of an identifier"
  [env {:keys [name] :as expr}]
  (if-let [var-type (env-lookup-var env name)]
    (if (and (env-var-non-nil? env name)
             (detachable-type? var-type))
      (attachable-type var-type)
      var-type)
    (if-let [current-class (env-lookup-var env "__current_class__")]
      (if-let [field-type (lookup-class-field env current-class name)]
        (if (and (env-var-non-nil? env name)
                 (detachable-type? field-type))
          (attachable-type field-type)
          field-type)
        (if-let [constant (lookup-class-constant env current-class name)]
          (:field-type constant)
          (if-let [method-sig (lookup-class-method env current-class name)]
            (or (:return-type method-sig) "Void")
            (throw (ex-info (str "Undefined variable: " name)
                            {:error (type-error (str "Undefined variable: " name))})))))
      (throw (ex-info (str "Undefined variable: " name)
                      {:error (type-error (str "Undefined variable: " name))})))))

(defn- check-call-signature
  [env method args method-sig type-map & {:keys [arg-types]}]
  (let [arg-types (or arg-types (mapv #(check-expression env %) args))
        params (:params method-sig)]
    (when (not= (count args) (count params))
      (throw (ex-info (str "Method " method " expects " (count params)
                           " arguments, got " (count args))
                      {:error (type-error
                               (str "Method " method " expects " (count params)
                                    " arguments, got " (count args)))})))
    (doseq [[arg-type param] (map vector arg-types params)]
      (let [param-type (resolve-generic-type (:type param) type-map)]
        (when-not (types-compatible? env arg-type param-type)
          (throw (ex-info (str "Argument type mismatch for method " method)
                          {:error (type-error
                                   (str "Expected " (display-type param-type)
                                        ", got " (display-type arg-type)))})))))
    (resolve-generic-type (:return-type method-sig) type-map)))

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
        (numeric-result-type left-type right-type)

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
        (numeric-result-type left-type right-type)
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

      ("=" "/=" "==" "!=")
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
  (let [base-name (cond
                    (map? target-type) (:base-type target-type)
                    (string? target-type) target-type
                    :else nil)
        type-args (when (map? target-type)
                    (or (:type-args target-type) (:type-params target-type)))
        class-def (when base-name
                    (env-lookup-class env base-name))]
    (when-let [generic-params (:generic-params class-def)]
      (into {}
            (map (fn [param arg]
                   [(:name param) (or arg "Any")])
                 generic-params
                 (concat type-args (repeat "Any")))))))

(defn- merge-inferred-generic-bindings
  [env left right]
  (reduce-kv
   (fn [acc generic-name inferred-type]
     (if-let [existing (get acc generic-name)]
       (if (or (types-equal? env existing inferred-type)
               (types-compatible? env inferred-type existing)
               (types-compatible? env existing inferred-type))
         acc
         (throw (ex-info (str "Conflicting inferred types for generic parameter " generic-name)
                         {:error (type-error
                                  (str "Conflicting inferred types for generic parameter "
                                       generic-name ": "
                                       (display-type existing)
                                       " and "
                                       (display-type inferred-type)))})))
       (assoc acc generic-name inferred-type)))
   left
   right))

(defn- infer-generic-type-map-from-arg
  [env generic-names param-type arg-type]
  (let [param-type (normalize-type param-type)
        arg-type (normalize-type arg-type)]
    (cond
      (and (string? param-type) (contains? generic-names param-type))
      {param-type arg-type}

      (and (map? param-type) (map? arg-type)
           (= (:base-type param-type) (:base-type arg-type)))
      (let [param-args (vec (or (:type-params param-type) (:type-args param-type)))
            arg-args (vec (or (:type-params arg-type) (:type-args arg-type)))]
        (if (= (count param-args) (count arg-args))
          (reduce (fn [acc [param-arg arg-arg]]
                    (merge-inferred-generic-bindings
                     env acc (infer-generic-type-map-from-arg env generic-names param-arg arg-arg)))
                  {}
                  (map vector param-args arg-args))
          {}))

      :else
      {})))

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

(defn guarded-else-non-nil-var
  "Extract variable name from condition of the form `x = nil` or `nil = x`,
   where the variable is proven non-nil in the else branch."
  [condition]
  (when (and (map? condition)
             (= :binary (:type condition))
             (= "=" (:operator condition)))
    (let [left (:left condition)
          right (:right condition)
          left-id (identifier-name left)
          right-id (identifier-name right)]
      (cond
        (and left-id (nil-literal? right)) left-id
        (and right-id (nil-literal? left)) right-id
        :else nil))))

(defn- apply-condition-branch-refinement!
  [env condition branch]
  (case branch
    :then
    (do
      (when-let [non-nil-var (guarded-non-nil-var condition)]
        (env-mark-non-nil env non-nil-var))
      (doseq [{:keys [name type]} (convert-guard-bindings condition)]
        (env-add-var env name type)
        (env-mark-non-nil env name))
      env)

    :else
    (do
      (when-let [non-nil-var (guarded-else-non-nil-var condition)]
        (env-mark-non-nil env non-nil-var))
      env)

    env))

(defn convert-guard-binding
  "Extract convert-bound variable info from condition of form:
   convert <expr> to <var>:<Type>"
  [condition]
  (when (and (map? condition) (= :convert (:type condition)))
    {:name (:var-name condition)
     :type (attachable-type (:target-type condition))}))

(defn convert-guard-bindings
  "Extract convert-bound variables that are guaranteed in a true condition.
   In `a and convert x to y: T`, both operands must be true, so y is attached
   in the then branch."
  [condition]
  (cond
    (nil? condition) []

    (and (map? condition) (= :convert (:type condition)))
    [(convert-guard-binding condition)]

    (and (map? condition)
         (= :binary (:type condition))
         (= "and" (:operator condition)))
    (vec (concat (convert-guard-bindings (:left condition))
                 (convert-guard-bindings (:right condition))))

    :else []))

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
      "cursor" (when (= argc 0)
                 {:params [] :return-type "Cursor"})
      "start" (when (= argc 0)
                {:params [] :return-type "Void"})
      "item" (when (= argc 0)
               {:params [] :return-type "Any"})
      "next" (when (= argc 0)
               {:params [] :return-type "Void"})
      "at_end" (when (= argc 0)
                 {:params [] :return-type "Boolean"})
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

    "Min_Heap"
    (let [elem-type (or (resolve-generic-type "T" type-map) "Any")]
      (case method
        "insert" (when (= argc 1)
                   {:params [{:name "value" :type elem-type}] :return-type "Void"})
        "extract_min" (when (= argc 0)
                        {:params [] :return-type elem-type})
        "try_extract_min" (when (= argc 0)
                            {:params [] :return-type (detachable-version elem-type)})
        "peek" (when (= argc 0)
                 {:params [] :return-type elem-type})
        "try_peek" (when (= argc 0)
                     {:params [] :return-type (detachable-version elem-type)})
        "size" (when (= argc 0) {:params [] :return-type "Integer"})
        "is_empty" (when (= argc 0) {:params [] :return-type "Boolean"})
        nil))

    "Atomic_Integer"
    (case method
      "load" (when (= argc 0) {:params [] :return-type "Integer"})
      "store" (when (= argc 1) {:params [{:name "value" :type "Integer"}] :return-type "Void"})
      "compare_and_set" (when (= argc 2)
                          {:params [{:name "expected" :type "Integer"}
                                    {:name "update" :type "Integer"}]
                           :return-type "Boolean"})
      "get_and_add" (when (= argc 1) {:params [{:name "delta" :type "Integer"}] :return-type "Integer"})
      "add_and_get" (when (= argc 1) {:params [{:name "delta" :type "Integer"}] :return-type "Integer"})
      "increment" (when (= argc 0) {:params [] :return-type "Integer"})
      "decrement" (when (= argc 0) {:params [] :return-type "Integer"})
      nil)

    "Atomic_Integer64"
    (case method
      "load" (when (= argc 0) {:params [] :return-type "Integer64"})
      "store" (when (= argc 1) {:params [{:name "value" :type "Integer64"}] :return-type "Void"})
      "compare_and_set" (when (= argc 2)
                          {:params [{:name "expected" :type "Integer64"}
                                    {:name "update" :type "Integer64"}]
                           :return-type "Boolean"})
      "get_and_add" (when (= argc 1) {:params [{:name "delta" :type "Integer64"}] :return-type "Integer64"})
      "add_and_get" (when (= argc 1) {:params [{:name "delta" :type "Integer64"}] :return-type "Integer64"})
      "increment" (when (= argc 0) {:params [] :return-type "Integer64"})
      "decrement" (when (= argc 0) {:params [] :return-type "Integer64"})
      nil)

    "Atomic_Boolean"
    (case method
      "load" (when (= argc 0) {:params [] :return-type "Boolean"})
      "store" (when (= argc 1) {:params [{:name "value" :type "Boolean"}] :return-type "Void"})
      "compare_and_set" (when (= argc 2)
                          {:params [{:name "expected" :type "Boolean"}
                                    {:name "update" :type "Boolean"}]
                           :return-type "Boolean"})
      nil)

    "Atomic_Reference"
    (let [elem-type (or (resolve-generic-type "T" type-map) "Any")
          maybe-elem (detachable-version elem-type)]
      (case method
        "load" (when (= argc 0) {:params [] :return-type maybe-elem})
        "store" (when (= argc 1) {:params [{:name "value" :type maybe-elem}] :return-type "Void"})
        "compare_and_set" (when (= argc 2)
                            {:params [{:name "expected" :type maybe-elem}
                                      {:name "update" :type maybe-elem}]
                             :return-type "Boolean"})
        nil))

    "Cursor"
    (case method
      "start" (when (= argc 0)
                {:params [] :return-type "Void"})
      "cursor" (when (= argc 0)
                 {:params [] :return-type "Cursor"})
      "item" (when (= argc 0)
               {:params [] :return-type "Any"})
      "next" (when (= argc 0)
               {:params [] :return-type "Void"})
      "at_end" (when (= argc 0)
                 {:params [] :return-type "Boolean"})
      nil)

    nil))

(defn- check-target-call
  [env {:keys [target method args has-parens]}]
  (let [target-name (when (string? target) target)
        across-item-type (and target-name
                              (env-lookup-across-cursor env target-name))
        with-java? (boolean (env-lookup-var env "__with_java__"))
        class-target (when target-name (env-lookup-class env target-name))
        current-class (env-lookup-var env "__current_class__")
        target-type (if class-target
                      target-name
                      (if (string? target)
                        (or (env-lookup-var env target)
                            (when current-class
                              (lookup-class-field env current-class target)))
                        (check-expression env target)))
        normalized-target (normalize-type target-type)
        target-detachable? (detachable-type? normalized-target)
        guarded? (and (string? target) (env-var-non-nil? env target))
        base-type (if (map? target-type)
                    (:base-type target-type)
                    target-type)
        type-map (build-generic-type-map env target-type)]
    (when (and (not class-target) target-detachable? (not guarded?))
      (throw (ex-info (str "Feature access on detachable target requires nil-check: " method)
                      {:error (type-error
                               (str "Cannot call feature '" method "' on detachable "
                                    (display-type normalized-target)
                                    ". Wrap with: if <obj> /= nil then <obj>." method "(...) end"))})))
    (cond
      across-item-type
      (case method
        "item" across-item-type
        "start" "Void"
        "next" "Void"
        "at_end" "Boolean"
        "cursor" "Cursor"
        nil)

      (and class-target (false? has-parens))
      (if-let [constant (lookup-class-constant env base-type method)]
        (resolve-generic-type (:field-type constant) type-map)
        (if-let [method-sig (and current-class
                                 (not= current-class base-type)
                                 (class-subtype? env current-class base-type)
                                 (lookup-class-method env base-type method 0 current-class))]
          (check-call-signature env method [] method-sig type-map :arg-types [])
          "Any"))

      (and (= base-type "Array") (= method "sort"))
      (do
        (let [elem-type (if (map? target-type)
                          (or (first (or (:type-params target-type) (:type-args target-type)))
                              "Any")
                          "Any")]
          (case (count args)
            0
            (do
              (when-not (sortable-array-element-type? env elem-type)
                (throw (ex-info "Array.sort requires Comparable element type"
                                {:error (type-error
                                         (str "Array.sort requires elements of a built-in sortable type or Comparable, got "
                                              (display-type elem-type)))})))
              (resolve-generic-type {:base-type "Array" :type-params ["T"]} type-map))

            1
            (let [compare-type (check-expression env (first args))]
              (when-not (types-compatible? env compare-type "Function")
                (throw (ex-info "Array.sort(compareFn) expects a Function argument"
                                {:error (type-error
                                         (str "Expected Function, got " (display-type compare-type)))})))
              (resolve-generic-type {:base-type "Array" :type-params ["T"]} type-map))

            (throw (ex-info "Method sort expects 0 or 1 arguments"
                            {:error (type-error
                                     (str "Method sort expects 0 or 1 arguments, got " (count args)))})))))

      :else
      (let [class-def (env-lookup-class env base-type)]
        (if-let [method-sig (or (builtin-method-signature base-type method (count args) type-map)
                                (builtin-method-signature "Any" method (count args) type-map)
                                (lookup-class-method env base-type method (count args) current-class))]
          (check-call-signature env method args method-sig type-map)
          (let [arg-types (mapv #(check-expression env %) args)]
            (if-let [java-method-sig (reflected-java-method-signature env base-type method arg-types class-target)]
              (check-call-signature env method args java-method-sig {} :arg-types arg-types)
              (if (false? has-parens)
                (if-let [field-type (lookup-class-field env base-type method current-class)]
                  (resolve-generic-type field-type type-map)
                  (if with-java?
                    "Any"
                    (if (and class-def (not (:import class-def)))
                      (if-let [method-sig (lookup-class-method-any-arity env base-type method current-class)]
                        (throw (ex-info (str "Method " method " on " base-type
                                             " requires " (count (:params method-sig))
                                             " argument(s); zero-argument access is invalid")
                                        {:error (type-error
                                                 (str "Method " method " on " base-type
                                                      " requires " (count (:params method-sig))
                                                      " argument(s); zero-argument access is invalid"))}))
                        (throw (ex-info (str "Undefined field: " method)
                                        {:error (type-error (str "Undefined field: " method))})))
                      "Any")))
                (if with-java?
                  "Any"
                  (if (and class-def (not (:import class-def)))
                  (throw (ex-info (str "Method not found: " method)
                                  {:error (type-error (str "Method not found: " method))}))
                  "Any"))))))))))

(defn check-call
  "Check the type of a method call"
  [env {:keys [target method args has-parens] :as expr}]
  (if (and (map? target) (= :create (:type target)) (nil? method))
    (check-create env (assoc target :args args))
    (if target
      (check-target-call env expr)
      ;; Function call (built-in like print/type_of/type_is) or function object call
      (cond
      (= method "print")
      (do
        (doseq [arg args]
          (check-expression env arg))
        "Void")

      (= method "println")
      (do
        (doseq [arg args]
          (check-expression env arg))
        "Void")

      (= method "sleep")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "sleep expects exactly 1 argument"
                          {:error (type-error
                                   (str "sleep expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (types-compatible? env arg-type "Integer")
            (throw (ex-info "sleep argument must be Integer"
                            {:error (type-error
                                     (str "sleep argument must be Integer, got "
                                         (display-type arg-type)))}))))
        "Void")

      (= method "hint_spin")
      (do
        (when (not= (count args) 0)
          (throw (ex-info "hint_spin expects exactly 0 arguments"
                          {:error (type-error
                                   (str "hint_spin expects 0 arguments, got " (count args)))})))
        "Void")

      (= method "random_real")
      (do
        (when (not= (count args) 0)
          (throw (ex-info "random_real expects exactly 0 arguments"
                          {:error (type-error
                                   (str "random_real expects 0 arguments, got " (count args)))})))
        "Real")

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

      (= method "path_exists")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_exists expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_exists expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_exists argument must be String"
                            {:error (type-error
                                     (str "path_exists argument must be String, got "
                                          (display-type path-type)))}))))
        "Boolean")

      (= method "datetime_now")
      (do
        (when (not= (count args) 0)
          (throw (ex-info "datetime_now expects exactly 0 arguments"
                          {:error (type-error
                                   (str "datetime_now expects 0 arguments, got " (count args)))})))
        "Integer64")

      (= method "regex_validate")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "regex_validate expects exactly 2 arguments"
                          {:error (type-error
                                   (str "regex_validate expects 2 arguments, got " (count args)))})))
        (doseq [arg args]
          (let [arg-type (check-expression env arg)]
            (when-not (= (attachable-type arg-type) "String")
              (throw (ex-info "regex_validate arguments must be String"
                              {:error (type-error
                                       (str "regex_validate arguments must be String, got "
                                            (display-type arg-type)))})))))
        "Boolean")

      (= method "regex_matches")
      (do
        (when (not= (count args) 3)
          (throw (ex-info "regex_matches expects exactly 3 arguments"
                          {:error (type-error
                                   (str "regex_matches expects 3 arguments, got " (count args)))})))
        (doseq [arg args]
          (let [arg-type (check-expression env arg)]
            (when-not (= (attachable-type arg-type) "String")
              (throw (ex-info "regex_matches arguments must be String"
                              {:error (type-error
                                       (str "regex_matches arguments must be String, got "
                                            (display-type arg-type)))})))))
        "Boolean")

      (= method "regex_find")
      (do
        (when (not= (count args) 3)
          (throw (ex-info "regex_find expects exactly 3 arguments"
                          {:error (type-error
                                   (str "regex_find expects 3 arguments, got " (count args)))})))
        (doseq [arg args]
          (let [arg-type (check-expression env arg)]
            (when-not (= (attachable-type arg-type) "String")
              (throw (ex-info "regex_find arguments must be String"
                              {:error (type-error
                                       (str "regex_find arguments must be String, got "
                                            (display-type arg-type)))})))))
        {:base-type "String" :detachable true})

      (= method "regex_find_all")
      (do
        (when (not= (count args) 3)
          (throw (ex-info "regex_find_all expects exactly 3 arguments"
                          {:error (type-error
                                   (str "regex_find_all expects 3 arguments, got " (count args)))})))
        (doseq [arg args]
          (let [arg-type (check-expression env arg)]
            (when-not (= (attachable-type arg-type) "String")
              (throw (ex-info "regex_find_all arguments must be String"
                              {:error (type-error
                                       (str "regex_find_all arguments must be String, got "
                                            (display-type arg-type)))})))))
        {:base-type "Array" :type-args ["String"]})

      (= method "regex_replace")
      (do
        (when (not= (count args) 4)
          (throw (ex-info "regex_replace expects exactly 4 arguments"
                          {:error (type-error
                                   (str "regex_replace expects 4 arguments, got " (count args)))})))
        (doseq [arg args]
          (let [arg-type (check-expression env arg)]
            (when-not (= (attachable-type arg-type) "String")
              (throw (ex-info "regex_replace arguments must be String"
                              {:error (type-error
                                       (str "regex_replace arguments must be String, got "
                                            (display-type arg-type)))})))))
        "String")

      (= method "regex_split")
      (do
        (when (not= (count args) 3)
          (throw (ex-info "regex_split expects exactly 3 arguments"
                          {:error (type-error
                                   (str "regex_split expects 3 arguments, got " (count args)))})))
        (doseq [arg args]
          (let [arg-type (check-expression env arg)]
            (when-not (= (attachable-type arg-type) "String")
              (throw (ex-info "regex_split arguments must be String"
                              {:error (type-error
                                       (str "regex_split arguments must be String, got "
                                            (display-type arg-type)))})))))
        {:base-type "Array" :type-args ["String"]})

      (= method "datetime_from_epoch_millis")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_from_epoch_millis expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_from_epoch_millis expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_from_epoch_millis argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_from_epoch_millis argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer64")

      (= method "datetime_parse_iso")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_parse_iso expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_parse_iso expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "String")
            (throw (ex-info "datetime_parse_iso argument must be String"
                            {:error (type-error
                                     (str "datetime_parse_iso argument must be String, got "
                                          (display-type arg-type)))}))))
        "Integer64")

      (= method "datetime_make")
      (do
        (when (not= (count args) 6)
          (throw (ex-info "datetime_make expects exactly 6 arguments"
                          {:error (type-error
                                   (str "datetime_make expects 6 arguments, got " (count args)))})))
        (doseq [arg args]
          (let [arg-type (check-expression env arg)]
            (when-not (= (attachable-type arg-type) "Integer")
              (throw (ex-info "datetime_make arguments must be Integer"
                              {:error (type-error
                                       (str "datetime_make arguments must be Integer, got "
                                            (display-type arg-type)))})))))
        "Integer64")

      (= method "datetime_year")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_year expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_year expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_year argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_year argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer")

      (= method "datetime_month")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_month expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_month expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_month argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_month argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer")

      (= method "datetime_day")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_day expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_day expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_day argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_day argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer")

      (= method "datetime_weekday")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_weekday expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_weekday expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_weekday argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_weekday argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer")

      (= method "datetime_day_of_year")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_day_of_year expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_day_of_year expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_day_of_year argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_day_of_year argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer")

      (= method "datetime_hour")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_hour expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_hour expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_hour argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_hour argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer")

      (= method "datetime_minute")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_minute expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_minute expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_minute argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_minute argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer")

      (= method "datetime_second")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_second expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_second expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_second argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_second argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer")

      (= method "datetime_epoch_millis")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_epoch_millis expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_epoch_millis expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_epoch_millis argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_epoch_millis argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer64")

      (= method "datetime_add_millis")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "datetime_add_millis expects exactly 2 arguments"
                          {:error (type-error
                                   (str "datetime_add_millis expects 2 arguments, got " (count args)))})))
        (doseq [arg args]
          (let [arg-type (check-expression env arg)]
            (when-not (= (attachable-type arg-type) "Integer64")
              (throw (ex-info "datetime_add_millis arguments must be Integer64"
                              {:error (type-error
                                       (str "datetime_add_millis arguments must be Integer64, got "
                                            (display-type arg-type)))})))))
        "Integer64")

      (= method "datetime_diff_millis")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "datetime_diff_millis expects exactly 2 arguments"
                          {:error (type-error
                                   (str "datetime_diff_millis expects 2 arguments, got " (count args)))})))
        (doseq [arg args]
          (let [arg-type (check-expression env arg)]
            (when-not (= (attachable-type arg-type) "Integer64")
              (throw (ex-info "datetime_diff_millis arguments must be Integer64"
                              {:error (type-error
                                       (str "datetime_diff_millis arguments must be Integer64, got "
                                            (display-type arg-type)))})))))
        "Integer64")

      (= method "datetime_truncate_to_day")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_truncate_to_day expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_truncate_to_day expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_truncate_to_day argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_truncate_to_day argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer64")

      (= method "datetime_truncate_to_hour")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_truncate_to_hour expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_truncate_to_hour expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_truncate_to_hour argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_truncate_to_hour argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "Integer64")

      (= method "datetime_format_iso")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "datetime_format_iso expects exactly 1 argument"
                          {:error (type-error
                                   (str "datetime_format_iso expects 1 argument, got " (count args)))})))
        (let [arg-type (check-expression env (first args))]
          (when-not (= (attachable-type arg-type) "Integer64")
            (throw (ex-info "datetime_format_iso argument must be Integer64"
                            {:error (type-error
                                     (str "datetime_format_iso argument must be Integer64, got "
                                          (display-type arg-type)))}))))
        "String")

      (= method "path_is_file")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_is_file expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_is_file expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_is_file argument must be String"
                            {:error (type-error
                                     (str "path_is_file argument must be String, got "
                                          (display-type path-type)))}))))
        "Boolean")

      (= method "path_is_directory")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_is_directory expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_is_directory expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_is_directory argument must be String"
                            {:error (type-error
                                     (str "path_is_directory argument must be String, got "
                                          (display-type path-type)))}))))
        "Boolean")

      (= method "path_name")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_name expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_name expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_name argument must be String"
                            {:error (type-error
                                     (str "path_name argument must be String, got "
                                         (display-type path-type)))}))))
        "String")

      (= method "path_extension")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_extension expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_extension expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_extension argument must be String"
                            {:error (type-error
                                     (str "path_extension argument must be String, got "
                                          (display-type path-type)))}))))
        "String")

      (= method "path_name_without_extension")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_name_without_extension expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_name_without_extension expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_name_without_extension argument must be String"
                            {:error (type-error
                                     (str "path_name_without_extension argument must be String, got "
                                          (display-type path-type)))}))))
        "String")

      (= method "path_absolute")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_absolute expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_absolute expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_absolute argument must be String"
                            {:error (type-error
                                     (str "path_absolute argument must be String, got "
                                          (display-type path-type)))}))))
        "String")

      (= method "path_normalize")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_normalize expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_normalize expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_normalize argument must be String"
                            {:error (type-error
                                     (str "path_normalize argument must be String, got "
                                          (display-type path-type)))}))))
        "String")

      (= method "path_size")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_size expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_size expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_size argument must be String"
                            {:error (type-error
                                     (str "path_size argument must be String, got "
                                          (display-type path-type)))}))))
        "Integer64")

      (= method "path_modified_time")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_modified_time expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_modified_time expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_modified_time argument must be String"
                            {:error (type-error
                                     (str "path_modified_time argument must be String, got "
                                          (display-type path-type)))}))))
        "Integer64")

      (= method "path_parent")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_parent expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_parent expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_parent argument must be String"
                            {:error (type-error
                                     (str "path_parent argument must be String, got "
                                          (display-type path-type)))}))))
        {:base-type "String" :detachable true})

      (= method "path_child")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "path_child expects exactly 2 arguments"
                          {:error (type-error
                                   (str "path_child expects 2 arguments, got " (count args)))})))
        (let [path-type (check-expression env (first args))
              child-type (check-expression env (second args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_child first argument must be String"
                            {:error (type-error
                                     (str "path_child first argument must be String, got "
                                          (display-type path-type)))})))
          (when-not (= (attachable-type child-type) "String")
            (throw (ex-info "path_child second argument must be String"
                            {:error (type-error
                                     (str "path_child second argument must be String, got "
                                          (display-type child-type)))}))))
        "String")

      (= method "path_create_file")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_create_file expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_create_file expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_create_file argument must be String"
                            {:error (type-error
                                     (str "path_create_file argument must be String, got "
                                          (display-type path-type)))}))))
        "Void")

      (= method "path_create_directory")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_create_directory expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_create_directory expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_create_directory argument must be String"
                            {:error (type-error
                                     (str "path_create_directory argument must be String, got "
                                          (display-type path-type)))}))))
        "Void")

      (= method "path_create_directories")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_create_directories expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_create_directories expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_create_directories argument must be String"
                            {:error (type-error
                                     (str "path_create_directories argument must be String, got "
                                          (display-type path-type)))}))))
        "Void")

      (= method "path_delete")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_delete expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_delete expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_delete argument must be String"
                            {:error (type-error
                                     (str "path_delete argument must be String, got "
                                         (display-type path-type)))}))))
        "Void")

      (= method "path_delete_tree")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_delete_tree expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_delete_tree expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_delete_tree argument must be String"
                            {:error (type-error
                                     (str "path_delete_tree argument must be String, got "
                                          (display-type path-type)))}))))
        "Void")

      (= method "path_copy")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "path_copy expects exactly 2 arguments"
                          {:error (type-error
                                   (str "path_copy expects 2 arguments, got " (count args)))})))
        (let [source-type (check-expression env (first args))
              target-type (check-expression env (second args))]
          (when-not (= (attachable-type source-type) "String")
            (throw (ex-info "path_copy first argument must be String"
                            {:error (type-error
                                     (str "path_copy first argument must be String, got "
                                          (display-type source-type)))})))
          (when-not (= (attachable-type target-type) "String")
            (throw (ex-info "path_copy second argument must be String"
                            {:error (type-error
                                     (str "path_copy second argument must be String, got "
                                          (display-type target-type)))}))))
        "Void")

      (= method "path_move")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "path_move expects exactly 2 arguments"
                          {:error (type-error
                                   (str "path_move expects 2 arguments, got " (count args)))})))
        (let [source-type (check-expression env (first args))
              target-type (check-expression env (second args))]
          (when-not (= (attachable-type source-type) "String")
            (throw (ex-info "path_move first argument must be String"
                            {:error (type-error
                                     (str "path_move first argument must be String, got "
                                          (display-type source-type)))})))
          (when-not (= (attachable-type target-type) "String")
            (throw (ex-info "path_move second argument must be String"
                            {:error (type-error
                                     (str "path_move second argument must be String, got "
                                          (display-type target-type)))}))))
        "Void")

      (= method "path_read_text")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_read_text expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_read_text expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_read_text argument must be String"
                            {:error (type-error
                                     (str "path_read_text argument must be String, got "
                                          (display-type path-type)))}))))
        "String")

      (= method "path_write_text")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "path_write_text expects exactly 2 arguments"
                          {:error (type-error
                                   (str "path_write_text expects 2 arguments, got " (count args)))})))
        (let [path-type (check-expression env (first args))
              text-type (check-expression env (second args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_write_text first argument must be String"
                            {:error (type-error
                                     (str "path_write_text first argument must be String, got "
                                          (display-type path-type)))})))
          (when-not (= (attachable-type text-type) "String")
            (throw (ex-info "path_write_text second argument must be String"
                            {:error (type-error
                                     (str "path_write_text second argument must be String, got "
                                          (display-type text-type)))}))))
        "Void")

      (= method "path_append_text")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "path_append_text expects exactly 2 arguments"
                          {:error (type-error
                                   (str "path_append_text expects 2 arguments, got " (count args)))})))
        (let [path-type (check-expression env (first args))
              text-type (check-expression env (second args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_append_text first argument must be String"
                            {:error (type-error
                                     (str "path_append_text first argument must be String, got "
                                          (display-type path-type)))})))
          (when-not (= (attachable-type text-type) "String")
            (throw (ex-info "path_append_text second argument must be String"
                            {:error (type-error
                                     (str "path_append_text second argument must be String, got "
                                          (display-type text-type)))}))))
        "Void")

      (= method "path_list")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "path_list expects exactly 1 argument"
                          {:error (type-error
                                   (str "path_list expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "path_list argument must be String"
                            {:error (type-error
                                     (str "path_list argument must be String, got "
                                          (display-type path-type)))}))))
        {:base-type "Array" :type-params ["String"]})

      (= method "text_file_open_read")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "text_file_open_read expects exactly 1 argument"
                          {:error (type-error
                                   (str "text_file_open_read expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "text_file_open_read argument must be String"
                            {:error (type-error
                                     (str "text_file_open_read argument must be String, got "
                                          (display-type path-type)))}))))
        "Any")

      (= method "text_file_open_write")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "text_file_open_write expects exactly 1 argument"
                          {:error (type-error
                                   (str "text_file_open_write expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "text_file_open_write argument must be String"
                            {:error (type-error
                                     (str "text_file_open_write argument must be String, got "
                                          (display-type path-type)))}))))
        "Any")

      (= method "text_file_open_append")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "text_file_open_append expects exactly 1 argument"
                          {:error (type-error
                                   (str "text_file_open_append expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "text_file_open_append argument must be String"
                            {:error (type-error
                                     (str "text_file_open_append argument must be String, got "
                                          (display-type path-type)))}))))
        "Any")

      (= method "text_file_read_line")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "text_file_read_line expects exactly 1 argument"
                          {:error (type-error
                                   (str "text_file_read_line expects 1 argument, got " (count args)))})))
        (check-expression env (first args))
        {:base-type "String" :detachable true})

      (= method "text_file_write")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "text_file_write expects exactly 2 arguments"
                          {:error (type-error
                                   (str "text_file_write expects 2 arguments, got " (count args)))})))
        (check-expression env (first args))
        (let [text-type (check-expression env (second args))]
          (when-not (= (attachable-type text-type) "String")
            (throw (ex-info "text_file_write second argument must be String"
                            {:error (type-error
                                     (str "text_file_write second argument must be String, got "
                                          (display-type text-type)))}))))
        "Void")

      (= method "text_file_close")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "text_file_close expects exactly 1 argument"
                          {:error (type-error
                                   (str "text_file_close expects 1 argument, got " (count args)))})))
        (check-expression env (first args))
        "Void")

      (= method "binary_file_open_read")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "binary_file_open_read expects exactly 1 argument"
                          {:error (type-error
                                   (str "binary_file_open_read expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "binary_file_open_read argument must be String"
                            {:error (type-error
                                     (str "binary_file_open_read argument must be String, got "
                                          (display-type path-type)))}))))
        "Any")

      (= method "binary_file_open_write")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "binary_file_open_write expects exactly 1 argument"
                          {:error (type-error
                                   (str "binary_file_open_write expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "binary_file_open_write argument must be String"
                            {:error (type-error
                                     (str "binary_file_open_write argument must be String, got "
                                          (display-type path-type)))}))))
        "Any")

      (= method "binary_file_open_append")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "binary_file_open_append expects exactly 1 argument"
                          {:error (type-error
                                   (str "binary_file_open_append expects 1 argument, got " (count args)))})))
        (let [path-type (check-expression env (first args))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "binary_file_open_append argument must be String"
                            {:error (type-error
                                     (str "binary_file_open_append argument must be String, got "
                                          (display-type path-type)))}))))
        "Any")

      (= method "binary_file_read_all")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "binary_file_read_all expects exactly 1 argument"
                          {:error (type-error
                                   (str "binary_file_read_all expects 1 argument, got " (count args)))})))
        (check-expression env (first args))
        {:base-type "Array" :type-params ["Integer"]})

      (= method "binary_file_read")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "binary_file_read expects exactly 2 arguments"
                          {:error (type-error
                                   (str "binary_file_read expects 2 arguments, got " (count args)))})))
        (check-expression env (first args))
        (let [count-type (check-expression env (second args))]
          (when-not (= (attachable-type count-type) "Integer")
            (throw (ex-info "binary_file_read second argument must be Integer"
                            {:error (type-error
                                     (str "binary_file_read second argument must be Integer, got "
                                          (display-type count-type)))}))))
        {:base-type "Array" :type-params ["Integer"]})

      (= method "binary_file_write")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "binary_file_write expects exactly 2 arguments"
                          {:error (type-error
                                   (str "binary_file_write expects 2 arguments, got " (count args)))})))
        (check-expression env (first args))
        (let [bytes-type (normalize-type (check-expression env (second args)))]
          (when-not (and (map? bytes-type)
                         (= (:base-type bytes-type) "Array")
                         (= (first (or (:type-params bytes-type) (:type-args bytes-type))) "Integer"))
            (throw (ex-info "binary_file_write second argument must be Array[Integer]"
                            {:error (type-error
                                     (str "binary_file_write second argument must be Array[Integer], got "
                                          (display-type bytes-type)))}))))
        "Void")

      (= method "binary_file_position")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "binary_file_position expects exactly 1 argument"
                          {:error (type-error
                                   (str "binary_file_position expects 1 argument, got " (count args)))})))
        (check-expression env (first args))
        "Integer")

      (= method "binary_file_seek")
      (do
        (when (not= (count args) 2)
          (throw (ex-info "binary_file_seek expects exactly 2 arguments"
                          {:error (type-error
                                   (str "binary_file_seek expects 2 arguments, got " (count args)))})))
        (check-expression env (first args))
        (let [offset-type (check-expression env (second args))]
          (when-not (= (attachable-type offset-type) "Integer")
            (throw (ex-info "binary_file_seek second argument must be Integer"
                            {:error (type-error
                                     (str "binary_file_seek second argument must be Integer, got "
                                          (display-type offset-type)))}))))
        "Void")

      (= method "binary_file_close")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "binary_file_close expects exactly 1 argument"
                          {:error (type-error
                                   (str "binary_file_close expects 1 argument, got " (count args)))})))
        (check-expression env (first args))
        "Void")

      (= method "http_get")
      (do
        (when-not (or (= (count args) 1) (= (count args) 2))
          (throw (ex-info "http_get expects 1 or 2 arguments"
                          {:error (type-error
                                   (str "http_get expects 1 or 2 arguments, got " (count args)))})))
        (let [url-type (check-expression env (first args))]
          (when-not (= (attachable-type url-type) "String")
            (throw (ex-info "http_get first argument must be String"
                            {:error (type-error
                                     (str "http_get first argument must be String, got "
                                          (display-type url-type)))}))))
        (when (= (count args) 2)
          (let [timeout-type (check-expression env (second args))]
            (when-not (= (attachable-type timeout-type) "Integer")
              (throw (ex-info "http_get timeout argument must be Integer"
                              {:error (type-error
                                       (str "http_get timeout argument must be Integer, got "
                                            (display-type timeout-type)))})))))
        "Http_Response")

      (= method "http_post")
      (do
        (when-not (or (= (count args) 2) (= (count args) 3))
          (throw (ex-info "http_post expects 2 or 3 arguments"
                          {:error (type-error
                                   (str "http_post expects 2 or 3 arguments, got " (count args)))})))
        (let [url-type (check-expression env (first args))
              body-type (check-expression env (second args))]
          (when-not (= (attachable-type url-type) "String")
            (throw (ex-info "http_post first argument must be String"
                            {:error (type-error
                                     (str "http_post first argument must be String, got "
                                          (display-type url-type)))})))
          (when-not (= (attachable-type body-type) "String")
            (throw (ex-info "http_post second argument must be String"
                            {:error (type-error
                                     (str "http_post second argument must be String, got "
                                          (display-type body-type)))}))))
        (when (= (count args) 3)
          (let [timeout-type (check-expression env (nth args 2))]
            (when-not (= (attachable-type timeout-type) "Integer")
              (throw (ex-info "http_post timeout argument must be Integer"
                              {:error (type-error
                                       (str "http_post timeout argument must be Integer, got "
                                            (display-type timeout-type)))})))))
        "Http_Response")

      (= method "json_parse")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "json_parse expects exactly 1 argument"
                          {:error (type-error
                                   (str "json_parse expects 1 argument, got " (count args)))})))
        (let [text-type (check-expression env (first args))]
          (when-not (= (attachable-type text-type) "String")
            (throw (ex-info "json_parse argument must be String"
                            {:error (type-error
                                     (str "json_parse argument must be String, got "
                                          (display-type text-type)))}))))
        "Any")

      (= method "json_stringify")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "json_stringify expects exactly 1 argument"
                          {:error (type-error
                                   (str "json_stringify expects 1 argument, got " (count args)))})))
        (check-expression env (first args))
        "String")

      (= method "http_server_create")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "http_server_create expects exactly 1 argument"
                          {:error (type-error
                                   (str "http_server_create expects 1 argument, got " (count args)))})))
        (let [port-type (check-expression env (first args))]
          (when-not (= (attachable-type port-type) "Integer")
            (throw (ex-info "http_server_create argument must be Integer"
                            {:error (type-error
                                     (str "http_server_create argument must be Integer, got "
                                          (display-type port-type)))}))))
        "Any")

      (= method "http_server_get")
      (do
        (when (not= (count args) 3)
          (throw (ex-info "http_server_get expects exactly 3 arguments"
                          {:error (type-error
                                   (str "http_server_get expects 3 arguments, got " (count args)))})))
        (check-expression env (first args))
        (let [path-type (check-expression env (second args))
              handler-type (check-expression env (nth args 2))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "http_server_get path argument must be String"
                            {:error (type-error
                                     (str "http_server_get path argument must be String, got "
                                          (display-type path-type)))})))
          (when-not (= (attachable-type handler-type) "Function")
            (throw (ex-info "http_server_get handler argument must be Function"
                            {:error (type-error
                                     (str "http_server_get handler argument must be Function, got "
                                          (display-type handler-type)))}))))
        "Void")

      (= method "http_server_post")
      (do
        (when (not= (count args) 3)
          (throw (ex-info "http_server_post expects exactly 3 arguments"
                          {:error (type-error
                                   (str "http_server_post expects 3 arguments, got " (count args)))})))
        (check-expression env (first args))
        (let [path-type (check-expression env (second args))
              handler-type (check-expression env (nth args 2))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "http_server_post path argument must be String"
                            {:error (type-error
                                     (str "http_server_post path argument must be String, got "
                                          (display-type path-type)))})))
          (when-not (= (attachable-type handler-type) "Function")
            (throw (ex-info "http_server_post handler argument must be Function"
                            {:error (type-error
                                     (str "http_server_post handler argument must be Function, got "
                                         (display-type handler-type)))}))))
        "Void")

      (= method "http_server_put")
      (do
        (when (not= (count args) 3)
          (throw (ex-info "http_server_put expects exactly 3 arguments"
                          {:error (type-error
                                   (str "http_server_put expects 3 arguments, got " (count args)))})))
        (check-expression env (first args))
        (let [path-type (check-expression env (second args))
              handler-type (check-expression env (nth args 2))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "http_server_put path argument must be String"
                            {:error (type-error
                                     (str "http_server_put path argument must be String, got "
                                          (display-type path-type)))})))
          (when-not (= (attachable-type handler-type) "Function")
            (throw (ex-info "http_server_put handler argument must be Function"
                            {:error (type-error
                                     (str "http_server_put handler argument must be Function, got "
                                          (display-type handler-type)))}))))
        "Void")

      (= method "http_server_delete")
      (do
        (when (not= (count args) 3)
          (throw (ex-info "http_server_delete expects exactly 3 arguments"
                          {:error (type-error
                                   (str "http_server_delete expects 3 arguments, got " (count args)))})))
        (check-expression env (first args))
        (let [path-type (check-expression env (second args))
              handler-type (check-expression env (nth args 2))]
          (when-not (= (attachable-type path-type) "String")
            (throw (ex-info "http_server_delete path argument must be String"
                            {:error (type-error
                                     (str "http_server_delete path argument must be String, got "
                                          (display-type path-type)))})))
          (when-not (= (attachable-type handler-type) "Function")
            (throw (ex-info "http_server_delete handler argument must be Function"
                            {:error (type-error
                                     (str "http_server_delete handler argument must be Function, got "
                                          (display-type handler-type)))}))))
        "Void")

      (= method "http_server_start")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "http_server_start expects exactly 1 argument"
                          {:error (type-error
                                   (str "http_server_start expects 1 argument, got " (count args)))})))
        (check-expression env (first args))
        "Integer")

      (= method "http_server_stop")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "http_server_stop expects exactly 1 argument"
                          {:error (type-error
                                   (str "http_server_stop expects 1 argument, got " (count args)))})))
        (check-expression env (first args))
        "Void")

      (= method "http_server_is_running")
      (do
        (when (not= (count args) 1)
          (throw (ex-info "http_server_is_running expects exactly 1 argument"
                          {:error (type-error
                                   (str "http_server_is_running expects 1 argument, got " (count args)))})))
        (check-expression env (first args))
        "Boolean")

      :else
      (if-let [var-type (env-lookup-var env method)]
      (let [base-type (if (map? var-type) (:base-type var-type) var-type)
            call-name (str "call" (count args))
            method-sig (env-lookup-method env base-type call-name (count args))
            class-def (env-lookup-class env base-type)]
        (when-not method-sig
          (throw (ex-info (str "Method not found: " call-name)
                          {:error (type-error
                                   (str "Method not found: " call-name))})))
        (let [generic-names (set (map :name (:generic-params class-def)))
              arg-types (mapv #(check-expression env %) args)
              inferred-type-map (reduce (fn [acc [arg-type param]]
                                          (merge-inferred-generic-bindings
                                           env
                                           acc
                                           (infer-generic-type-map-from-arg
                                            env generic-names (:type param) arg-type)))
                                        {}
                                        (map vector arg-types (:params method-sig)))
              type-map (merge (build-generic-type-map env var-type)
                              inferred-type-map)]
          (when (not= (count args) (count (:params method-sig)))
            (throw (ex-info (str "Method " call-name " expects " (count (:params method-sig))
                                 " arguments, got " (count args))
                            {:error (type-error
                                     (str "Method " call-name " expects " (count (:params method-sig))
                                          " arguments, got " (count args)))})))
          (doseq [[arg-type param] (map vector arg-types (:params method-sig))]
            (let [param-type (resolve-generic-type (:type param) type-map)]
            (when (and (is-generic-type-param? env param-type)
                       (not (contains? type-map param-type)))
              (throw (ex-info (str "Could not infer generic type parameter " param-type
                                   " for function " method)
                              {:error (type-error
                                       (str "Could not infer generic type parameter "
                                            param-type
                                            " for function "
                                            method))})))
              (when-not (types-compatible? env arg-type param-type)
                (throw (ex-info (str "Argument type mismatch for method " call-name)
                                {:error (type-error
                                         (str "Expected " (display-type param-type) ", got " (display-type arg-type)))})))))
          (resolve-generic-type (:return-type method-sig) type-map)))
        (if-let [current-class (env-lookup-var env "__current_class__")]
          (if-let [method-sig (lookup-class-method env current-class method (count args) current-class)]
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
            (do
              (doseq [arg args] (check-expression env arg))
              (throw (ex-info (str "Undefined function or method: " method)
                              {:error (type-error
                                       (str "Undefined function or method: " method))}))))
          (do
            (doseq [arg args] (check-expression env arg))
            (throw (ex-info (str "Undefined function: " method)
                            {:error (type-error
                                     (str "Undefined function: " method))})))))))))

(defn check-create
  "Check the type of a create expression"
  [env {:keys [class-name generic-args constructor args] :as expr}]
  (cond
    ;; Handle built-in Array type
    (= class-name "Array")
    (let [target-type (if (seq generic-args)
                        (do
                          (validate-generic-args env class-name generic-args)
                          {:base-type "Array" :type-args generic-args})
                        "Array")]
      (cond
        (nil? constructor)
        (do
          (when (seq args)
            (throw (ex-info "create Array expects no arguments"
                            {:error (type-error "create Array expects no arguments")})))
          target-type)

        (= constructor "filled")
        (do
          (when-not (= 2 (count args))
            (throw (ex-info "Array.filled expects 2 arguments"
                            {:error (type-error "Array.filled expects exactly 2 arguments")})))
          (let [size-type (check-expression env (first args))
                value-type (check-expression env (second args))
                elem-type (or (first generic-args) value-type)]
            (when-not (types-compatible? env size-type "Integer")
              (throw (ex-info "Array.filled requires Integer size"
                              {:error (type-error
                                       (str "Array.filled expects Integer size, got "
                                            (display-type size-type)))})))
            (when-not (types-compatible? env value-type elem-type)
              (throw (ex-info "Array.filled value type mismatch"
                              {:error (type-error
                                       (str "Array.filled expects "
                                            (display-type elem-type)
                                            " value, got "
                                            (display-type value-type)))})))
            {:base-type "Array" :type-args [elem-type]}))

        :else
        (throw (ex-info (str "Constructor not found: Array." constructor)
                        {:error (type-error (str "Constructor not found: Array." constructor))}))))

    ;; Handle built-in Console type
    (= class-name "Console") "Console"
    ;; Handle built-in Process type
    (= class-name "Process") "Process"
    ;; Handle built-in Min_Heap type
    (= class-name "Min_Heap")
    (let [target-type (if (seq generic-args)
                        (do
                          (validate-generic-args env class-name generic-args)
                          {:base-type "Min_Heap" :type-args generic-args})
                        "Min_Heap")]
      (case constructor
        nil
        (do
          (when (seq args)
            (throw (ex-info "create Min_Heap expects no arguments"
                            {:error (type-error "create Min_Heap expects no arguments")})))
          (when-let [elem-type (first generic-args)]
            (when-not (sortable-array-element-type? env elem-type)
              (throw (ex-info "Min_Heap.empty requires Comparable element type"
                              {:error (type-error
                                       (str "Min_Heap.empty requires a built-in sortable type or Comparable element type, got "
                                            (display-type elem-type)
                                            ". Use Min_Heap.from_comparator(...) instead."))}))))
          target-type)

        "empty"
        (do
          (when (seq args)
            (throw (ex-info "Min_Heap.empty expects no arguments"
                            {:error (type-error "Min_Heap.empty expects no arguments")})))
          (when-let [elem-type (first generic-args)]
            (when-not (sortable-array-element-type? env elem-type)
              (throw (ex-info "Min_Heap.empty requires Comparable element type"
                              {:error (type-error
                                       (str "Min_Heap.empty requires a built-in sortable type or Comparable element type, got "
                                            (display-type elem-type)
                                            ". Use Min_Heap.from_comparator(...) instead."))}))))
          target-type)

        "from_comparator"
        (do
          (when-not (= 1 (count args))
            (throw (ex-info "Min_Heap.from_comparator expects 1 argument"
                            {:error (type-error "Min_Heap.from_comparator expects exactly 1 Function argument")})))
          (let [compare-type (check-expression env (first args))]
            (when-not (types-compatible? env compare-type "Function")
              (throw (ex-info "Min_Heap.from_comparator requires a Function"
                              {:error (type-error
                                       (str "Min_Heap.from_comparator expects Function, got "
                                            (display-type compare-type)))}))))
          target-type)

        (throw (ex-info (str "Constructor not found: Min_Heap." constructor)
                        {:error (type-error (str "Constructor not found: Min_Heap." constructor))}))))

    (= class-name "Atomic_Integer")
    (do
      (when-not (= constructor "make")
        (throw (ex-info (str "Constructor not found: Atomic_Integer." constructor)
                        {:error (type-error (str "Constructor not found: Atomic_Integer." constructor))})))
      (when-not (= 1 (count args))
        (throw (ex-info "Atomic_Integer.make expects 1 argument"
                        {:error (type-error "Atomic_Integer.make expects exactly 1 Integer argument")})))
      (let [arg-type (check-expression env (first args))]
        (when-not (types-compatible? env arg-type "Integer")
          (throw (ex-info "Atomic_Integer.make requires Integer initial value"
                          {:error (type-error
                                   (str "Atomic_Integer.make expects Integer, got "
                                        (display-type arg-type)))}))))
      "Atomic_Integer")

    (= class-name "Atomic_Integer64")
    (do
      (when-not (= constructor "make")
        (throw (ex-info (str "Constructor not found: Atomic_Integer64." constructor)
                        {:error (type-error (str "Constructor not found: Atomic_Integer64." constructor))})))
      (when-not (= 1 (count args))
        (throw (ex-info "Atomic_Integer64.make expects 1 argument"
                        {:error (type-error "Atomic_Integer64.make expects exactly 1 Integer64 argument")})))
      (let [arg-type (check-expression env (first args))]
        (when-not (types-compatible? env arg-type "Integer64")
          (throw (ex-info "Atomic_Integer64.make requires Integer64 initial value"
                          {:error (type-error
                                   (str "Atomic_Integer64.make expects Integer64, got "
                                        (display-type arg-type)))}))))
      "Atomic_Integer64")

    (= class-name "Atomic_Boolean")
    (do
      (when-not (= constructor "make")
        (throw (ex-info (str "Constructor not found: Atomic_Boolean." constructor)
                        {:error (type-error (str "Constructor not found: Atomic_Boolean." constructor))})))
      (when-not (= 1 (count args))
        (throw (ex-info "Atomic_Boolean.make expects 1 argument"
                        {:error (type-error "Atomic_Boolean.make expects exactly 1 Boolean argument")})))
      (let [arg-type (check-expression env (first args))]
        (when-not (types-compatible? env arg-type "Boolean")
          (throw (ex-info "Atomic_Boolean.make requires Boolean initial value"
                          {:error (type-error
                                   (str "Atomic_Boolean.make expects Boolean, got "
                                        (display-type arg-type)))}))))
      "Atomic_Boolean")

    (= class-name "Atomic_Reference")
    (let [target-type (if (seq generic-args)
                        (do
                          (validate-generic-args env class-name generic-args)
                          {:base-type "Atomic_Reference" :type-args generic-args})
                        nil)]
      (when-not (= constructor "make")
        (throw (ex-info (str "Constructor not found: Atomic_Reference." constructor)
                        {:error (type-error (str "Constructor not found: Atomic_Reference." constructor))})))
      (when-not (= 1 (count args))
        (throw (ex-info "Atomic_Reference.make expects 1 argument"
                        {:error (type-error "Atomic_Reference.make expects exactly 1 argument")})))
      (let [arg-type (check-expression env (first args))
            elem-type (or (first generic-args)
                          (if (= (attachable-type arg-type) "Nil")
                            "Any"
                            (attachable-type arg-type)))
            maybe-elem (detachable-version elem-type)]
        (when-not (types-compatible? env arg-type maybe-elem)
          (throw (ex-info "Atomic_Reference.make initial value type mismatch"
                          {:error (type-error
                                   (str "Atomic_Reference.make expects "
                                        (display-type maybe-elem)
                                        ", got "
                                        (display-type arg-type)))})))
        (or target-type
            {:base-type "Atomic_Reference" :type-args [elem-type]})))

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
    {:base-type "Array" :type-params ["__EmptyArrayElement"]}
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
    {:base-type "Map" :type-params ["__EmptyMapKey" "__EmptyMapValue"]}
    (let [entry-types (mapv (fn [{:keys [key value]}]
                              {:key-type (check-expression env key)
                               :value-type (check-expression env value)})
                            entries)
          key-type (:key-type (first entry-types))
          value-types (mapv :value-type entry-types)]
      (doseq [{current-key-type :key-type} entry-types]
        (when-not (types-equal? env key-type current-key-type)
          (throw (ex-info "Map entries must have consistent key types"
                          {:error (type-error
                                   "Map entries must have consistent key types")}))))
      (let [value-type (reduce (fn [acc t]
                                 (if (types-equal? env acc t)
                                   acc
                                   "Any"))
                               (first value-types)
                               (rest value-types))]
        {:base-type "Map" :type-params [key-type value-type]}))))

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
  (with-type-error-location
    expr
    (fn []
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
                       cons-env (doto (make-type-env env)
                                  (apply-condition-branch-refinement! (:condition expr) :then))
                       alt-env (doto (make-type-env env)
                                 (apply-condition-branch-refinement! (:condition expr) :else))
                       cons-type (check-expression cons-env (:consequent expr))
                       alt-type (check-expression alt-env (:alternative expr))
                       cons-nil? (= (normalize-type cons-type) "Nil")
                       alt-nil? (= (normalize-type alt-type) "Nil")
                       result-type (cond
                                     (and cons-nil? alt-nil?) "Nil"
                                     cons-nil? (detachable-version alt-type)
                                     alt-nil? (detachable-version cons-type)
                                     :else cons-type)]
                   (when-not (types-compatible? env cond-type "Boolean")
                     (throw (ex-info "when condition must be Boolean"
                                     {:error (type-error
                                              (str "when condition has type " cond-type ", expected Boolean"))})))
                   (when-not (or cons-nil?
                                 alt-nil?
                                 (types-compatible? env cons-type alt-type)
                                 (types-compatible? env alt-type cons-type))
                     (throw (ex-info "when branches must have compatible types"
                                     {:error (type-error
                                              (str "when branches have incompatible types: "
                                                   (display-type cons-type) " and "
                                                   (display-type alt-type)))})))
                   result-type)
          :old (check-expression env (:expr expr))
          :convert (check-convert env expr)
          :spawn (check-spawn env expr)
          :this (or (env-lookup-var env "__current_class__") "Any")
          "Any")
        :else "Any"))))

;;
;; Statement Type Checking
;;

(declare check-statement)
(declare check-expression-with-expected)

(defn check-expression-with-expected
  "Check an expression against an expected type when contextual typing matters,
   especially for collection literals with annotated target types."
  [env expr expected-type]
  (let [expected-type (normalize-type expected-type)]
    (cond
      (and (map? expr)
           (= :array-literal (:type expr))
           (map? expected-type)
           (= (:base-type expected-type) "Array")
           (= 1 (count (:type-params expected-type))))
      (let [elem-type (first (:type-params expected-type))]
        (doseq [elem (:elements expr)]
          (let [actual-elem-type (check-expression-with-expected env elem elem-type)]
            (when-not (types-compatible? env actual-elem-type elem-type)
              (throw (ex-info "Array elements must have same type"
                              {:error (type-error
                                       (str "Array elements must have same type, got "
                                            (display-type elem-type) " and " (display-type actual-elem-type)))})))))
        expected-type)

      (and (map? expr)
           (= :map-literal (:type expr))
           (map? expected-type)
           (= (:base-type expected-type) "Map")
           (= 2 (count (:type-params expected-type))))
      (let [[expected-key-type expected-val-type] (:type-params expected-type)]
        (doseq [{:keys [key value]} (:entries expr)]
          (let [actual-key-type (check-expression-with-expected env key expected-key-type)
                actual-val-type (check-expression-with-expected env value expected-val-type)]
            (when-not (types-compatible? env actual-key-type expected-key-type)
              (throw (ex-info "Map keys must have consistent types"
                              {:error (type-error
                                       (str "Cannot assign " (display-type actual-key-type)
                                            " to map key type " (display-type expected-key-type)))})))
            (when-not (types-compatible? env actual-val-type expected-val-type)
              (throw (ex-info "Map values must have consistent types"
                              {:error (type-error
                                       (str "Cannot assign " (display-type actual-val-type)
                                            " to map value type " (display-type expected-val-type)))})))))
        expected-type)

      (and (map? expr)
           (= :set-literal (:type expr))
           (map? expected-type)
           (= (:base-type expected-type) "Set")
           (= 1 (count (:type-params expected-type))))
      (let [elem-type (first (:type-params expected-type))]
        (doseq [elem (:elements expr)]
          (let [actual-elem-type (check-expression-with-expected env elem elem-type)]
            (when-not (types-compatible? env actual-elem-type elem-type)
              (throw (ex-info "Set elements must have same type"
                              {:error (type-error
                                       (str "Set elements must have same type, got "
                                            (display-type elem-type) " and " (display-type actual-elem-type)))})))))
        expected-type)

      :else
      (check-expression env expr))))

(defn check-assignment
  "Check an assignment statement"
  [env {:keys [target value] :as stmt}]
  (when-let [current-class (env-lookup-var env "__current_class__")]
    (when (lookup-class-constant env current-class target)
      (throw (ex-info (str "Cannot assign to constant: " target)
                      {:error (type-error (str "Cannot assign to constant: " target))}))))
  (let [var-type (env-lookup-var env target)
        val-type (if var-type
                   (check-expression-with-expected env value var-type)
                   (check-expression env value))]
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
  (let [val-type (if var-type
                   (check-expression-with-expected env value var-type)
                   (check-expression env value))
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
    (when (and synthetic
               (string? name)
               (str/starts-with? name "__across_c_")
               (= :call (:type value))
               (= "cursor" (:method value))
               (empty? (:args value)))
      (env-add-across-cursor env name (cursor-item-type (check-expression env (:target value)))))
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
    (apply-condition-branch-refinement! then-env condition :then)
    (doseq [stmt then]
      (check-statement then-env stmt)))
  (let [else-chain-env (doto (make-type-env env)
                         (apply-condition-branch-refinement! condition :else))
        final-else-env
        (reduce
         (fn [residual-env clause]
           (let [ei-cond-type (check-expression residual-env (:condition clause))]
             (when-not (= ei-cond-type "Boolean")
               (throw (ex-info "Elseif condition must be Boolean"
                               {:error (type-error
                                        (str "Elseif condition must be Boolean, got " ei-cond-type))}))))
           (let [elseif-env (make-type-env residual-env)]
             (apply-condition-branch-refinement! elseif-env (:condition clause) :then)
             (doseq [stmt (:then clause)]
               (check-statement elseif-env stmt)))
           (doto (make-type-env residual-env)
             (apply-condition-branch-refinement! (:condition clause) :else)))
         else-chain-env
         elseif)]
  (when else
    (doseq [stmt else]
      (check-statement final-else-env stmt)))))

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
  (with-type-error-location
    stmt
    (fn []
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
          :with (if (= (:target stmt) "java")
                  (let [with-env (make-type-env env)]
                    (env-add-var with-env "__with_java__" true)
                    (doseq [s (:body stmt)]
                      (check-statement with-env s))
                    (doseq [[name type] @(:vars with-env)]
                      (when-not (= name "__with_java__")
                        (env-add-var env name type))))
                  (doseq [s (:body stmt)] (check-statement env s)))
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
                target-expr (or (:object stmt) {:type :this})
                target-type (check-expression env target-expr)
                base-target-type (attachable-type target-type)
                class-name (if (map? base-target-type)
                             (:base-type base-target-type)
                             base-target-type)
                current-class (env-lookup-var env "__current_class__")
                _ (when-not class-name
                    (throw (ex-info "Field assignment target must be an object"
                                    {:error (type-error "Field assignment target must be an object")})))
                _ (when (lookup-class-constant env class-name field-name)
                    (throw (ex-info (str "Cannot assign to constant: " field-name)
                                    {:error (type-error (str "Cannot assign to constant: " field-name))})))
                field-member (lookup-class-field-member env class-name field-name current-class)
                field-type (:field-type field-member)
                val-type (check-expression env (:value stmt))]
            (when-not field-type
              (throw (ex-info (str "Undefined field: " field-name)
                              {:error (type-error (str "Undefined field: " field-name))})))
            (when-not (= current-class (:declaring-class field-member))
              (throw (ex-info (str "Cannot assign to field " field-name)
                              {:error (field-write-error field-name (:declaring-class field-member))})))
            (when-not (types-compatible? env val-type field-type)
              (throw (ex-info (str "Type mismatch in assignment to " field-name)
                              {:error (type-error
                                       (str "Cannot assign " (display-type val-type)
                                            " to field of type " (display-type field-type)))}))))

          ;; Top-level REPL/program expression inputs are often parsed into
          ;; :statements, so fall back to expression checking for any remaining
          ;; expression-shaped node.
          (check-expression env stmt))))))

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
  (letfn [(cycle-path [start-parent]
            (letfn [(visit [current path seen]
                      (cond
                        (= current class-name)
                        (conj path current)

                        (contains? seen current)
                        nil

                        :else
                        (when-let [class-def (env-lookup-class env current)]
                          (let [seen' (conj seen current)
                                path' (conj path current)]
                            (some #(visit (:parent %) path' seen')
                                  (:parents class-def))))))]
              (visit start-parent [class-name] #{class-name})))]
  (doseq [{:keys [parent]} parents]
    ;; Check that parent class exists
    (when-not (or (env-lookup-class env parent) (builtin-type? parent))
      (throw (ex-info (str "Parent class " parent " not found for class " class-name)
                      {:error (type-error
                               (str "Undefined parent class: " parent))})))
    (when (= parent class-name)
      (throw (ex-info (str "Class " class-name " cannot inherit from itself")
                      {:error (type-error
                               (str "Class " class-name " cannot inherit from itself"))})))
    (when-let [path (cycle-path parent)]
      (throw (ex-info (str "Cyclic inheritance detected: " (str/join " -> " path))
                      {:error (type-error
                               (str "Cyclic inheritance detected: "
                                    (str/join " -> " path)))}))))))

(defn check-class
  "Check a class definition"
  [env {:keys [name body invariant parents] :as class-def}]
  (let [class-def (or (env-lookup-class env name) class-def)
        body (:body class-def)
        invariant (:invariant class-def)
        parents (:parents class-def)
        class-env (make-type-env env)]
  (env-add-var class-env "__current_class__" name)
  (bind-visible-class-fields! class-env env name)
  ;; Check inheritance
  (when parents
    (check-inheritance env name parents))

  ;; Check invariants
  (doseq [assertion invariant]
    (when (and assertion (:expr assertion))
      (let [inv-type (check-expression class-env (:expr assertion))]
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
          (when-not (:declaration-only? member)
            (check-method class-env name member))
          (= (:type member) :field)
          (when-not (:constant? member)
            (validate-type-annotation class-env (:field-type member)))))

      (= (:type section) :constructors)
      (doseq [ctor (:constructors section)]
        (check-constructor class-env name ctor))))

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
          {"to_string" {:params [] :return-type "String"}
           "to_integer" {:params [] :return-type "Integer"}
           "to_integer64" {:params [] :return-type "Integer64"}
           "to_real" {:params [] :return-type "Real"}
           "to_decimal" {:params [] :return-type "Decimal"}
           "abs" {:params [] :return-type "Integer"}
           "min" {:params [{:name "other" :type "Integer"}] :return-type "Integer"}
           "max" {:params [{:name "other" :type "Integer"}] :return-type "Integer"}
           "pick" {:params [] :return-type "Integer"}
           "plus" {:params [{:name "other" :type "Integer"}] :return-type "Integer"}
           "minus" {:params [{:name "other" :type "Integer"}] :return-type "Integer"}
           "times" {:params [{:name "other" :type "Integer"}] :return-type "Integer"}
           "divided_by" {:params [{:name "other" :type "Integer"}] :return-type "Real"}
           "equals" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "not_equals" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "less_than" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "less_than_or_equal" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "greater_than" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "greater_than_or_equal" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}}]
    (env-add-method env "Integer" method-name sig))

  (doseq [[method-name sig]
          {"to_string" {:params [] :return-type "String"}
           "to_integer" {:params [] :return-type "Integer"}
           "to_integer64" {:params [] :return-type "Integer64"}
           "to_real" {:params [] :return-type "Real"}
           "to_decimal" {:params [] :return-type "Decimal"}
           "abs" {:params [] :return-type "Integer64"}
           "min" {:params [{:name "other" :type "Integer64"}] :return-type "Integer64"}
           "max" {:params [{:name "other" :type "Integer64"}] :return-type "Integer64"}
           "plus" {:params [{:name "other" :type "Integer64"}] :return-type "Integer64"}
           "minus" {:params [{:name "other" :type "Integer64"}] :return-type "Integer64"}
           "times" {:params [{:name "other" :type "Integer64"}] :return-type "Integer64"}
           "divided_by" {:params [{:name "other" :type "Integer64"}] :return-type "Real"}
           "equals" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "not_equals" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "less_than" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "less_than_or_equal" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "greater_than" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "greater_than_or_equal" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}}]
    (env-add-method env "Integer64" method-name sig))

  (doseq [[method-name sig]
          {"to_string" {:params [] :return-type "String"}
           "to_integer" {:params [] :return-type "Integer"}
           "to_integer64" {:params [] :return-type "Integer64"}
           "to_real" {:params [] :return-type "Real"}
           "to_decimal" {:params [] :return-type "Decimal"}
           "abs" {:params [] :return-type "Real"}
           "min" {:params [{:name "other" :type "Real"}] :return-type "Real"}
           "max" {:params [{:name "other" :type "Real"}] :return-type "Real"}
           "round" {:params [] :return-type "Integer"}
           "plus" {:params [{:name "other" :type "Real"}] :return-type "Real"}
           "minus" {:params [{:name "other" :type "Real"}] :return-type "Real"}
           "times" {:params [{:name "other" :type "Real"}] :return-type "Real"}
           "divided_by" {:params [{:name "other" :type "Real"}] :return-type "Real"}
           "equals" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "not_equals" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "less_than" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "less_than_or_equal" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "greater_than" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "greater_than_or_equal" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}}]
    (env-add-method env "Real" method-name sig))

  (doseq [[method-name sig]
          {"to_string" {:params [] :return-type "String"}
           "to_integer" {:params [] :return-type "Integer"}
           "to_integer64" {:params [] :return-type "Integer64"}
           "to_real" {:params [] :return-type "Real"}
           "to_decimal" {:params [] :return-type "Decimal"}
           "abs" {:params [] :return-type "Decimal"}
           "min" {:params [{:name "other" :type "Decimal"}] :return-type "Decimal"}
           "max" {:params [{:name "other" :type "Decimal"}] :return-type "Decimal"}
           "round" {:params [] :return-type "Integer"}
           "plus" {:params [{:name "other" :type "Decimal"}] :return-type "Decimal"}
           "minus" {:params [{:name "other" :type "Decimal"}] :return-type "Decimal"}
           "times" {:params [{:name "other" :type "Decimal"}] :return-type "Decimal"}
           "divided_by" {:params [{:name "other" :type "Decimal"}] :return-type "Decimal"}
           "equals" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "not_equals" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "less_than" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "less_than_or_equal" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "greater_than" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}
           "greater_than_or_equal" {:params [{:name "other" :type "Any"}] :return-type "Boolean"}}]
    (env-add-method env "Decimal" method-name sig))

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
           "chars"       {:params [] :return-type {:base-type "Array" :type-params ["Char"]}}
           "to_bytes"    {:params [] :return-type {:base-type "Array" :type-params ["Integer"]}}
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
          {"getenv" {:params [{:name "name" :type "String"}] :return-type "String"}
           "setenv" {:params [{:name "name" :type "String"} {:name "value" :type "String"}] :return-type "Void"}
           "command_line" {:params [] :return-type {:base-type "Array" :type-params ["String"]}}}]
    (env-add-method env "Process" method-name sig))
  ;; Register Array[T] class and methods
  (env-add-class env "Array" {:name "Array"
                               :generic-params [{:name "T"}]})
  (env-add-method env "Array" "filled"
                  {:params [{:name "size" :type "Integer"}
                            {:name "value" :type "T"}]
                   :return-type {:base-type "Array" :type-params ["T"]}})
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
           "reverse"     {:params [] :return-type {:base-type "Array" :type-params ["T"]}}
           "sort"        {0 {:params [] :return-type {:base-type "Array" :type-params ["T"]}}
                          1 {:params [{:name "compareFn" :type "Function"}]
                             :return-type {:base-type "Array" :type-params ["T"]}}}
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
           "put"          {:params [{:name "key" :type "K"} {:name "value" :type "V"}] :return-type "Void"}
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

  ;; Register Min_Heap[T] class and methods
  (env-add-class env "Min_Heap" {:name "Min_Heap"
                                 :generic-params [{:name "T"}]})
  (env-add-method env "Min_Heap" "empty"
                  {:params []
                   :return-type {:base-type "Min_Heap" :type-params ["T"]}})
  (env-add-method env "Min_Heap" "from_comparator"
                  {:params [{:name "compare" :type "Function"}]
                   :return-type {:base-type "Min_Heap" :type-params ["T"]}})
  (doseq [[method-name sig]
          {"insert"          {:params [{:name "value" :type "T"}] :return-type "Void"}
           "extract_min"     {:params [] :return-type "T"}
           "try_extract_min" {:params [] :return-type {:base-type "T" :detachable true}}
           "peek"            {:params [] :return-type "T"}
           "try_peek"        {:params [] :return-type {:base-type "T" :detachable true}}
           "size"            {:params [] :return-type "Integer"}
           "is_empty"        {:params [] :return-type "Boolean"}}]
    (env-add-method env "Min_Heap" method-name sig))

  ;; Register atomic built-ins
  (env-add-class env "Atomic_Integer" {:name "Atomic_Integer"})
  (env-add-method env "Atomic_Integer" "make"
                  {:params [{:name "initial" :type "Integer"}]
                   :return-type "Atomic_Integer"})
  (doseq [[method-name sig]
          {"load" {:params [] :return-type "Integer"}
           "store" {:params [{:name "value" :type "Integer"}] :return-type "Void"}
           "compare_and_set" {:params [{:name "expected" :type "Integer"}
                                       {:name "update" :type "Integer"}]
                              :return-type "Boolean"}
           "get_and_add" {:params [{:name "delta" :type "Integer"}] :return-type "Integer"}
           "add_and_get" {:params [{:name "delta" :type "Integer"}] :return-type "Integer"}
           "increment" {:params [] :return-type "Integer"}
           "decrement" {:params [] :return-type "Integer"}}]
    (env-add-method env "Atomic_Integer" method-name sig))

  (env-add-class env "Atomic_Integer64" {:name "Atomic_Integer64"})
  (env-add-method env "Atomic_Integer64" "make"
                  {:params [{:name "initial" :type "Integer64"}]
                   :return-type "Atomic_Integer64"})
  (doseq [[method-name sig]
          {"load" {:params [] :return-type "Integer64"}
           "store" {:params [{:name "value" :type "Integer64"}] :return-type "Void"}
           "compare_and_set" {:params [{:name "expected" :type "Integer64"}
                                       {:name "update" :type "Integer64"}]
                              :return-type "Boolean"}
           "get_and_add" {:params [{:name "delta" :type "Integer64"}] :return-type "Integer64"}
           "add_and_get" {:params [{:name "delta" :type "Integer64"}] :return-type "Integer64"}
           "increment" {:params [] :return-type "Integer64"}
           "decrement" {:params [] :return-type "Integer64"}}]
    (env-add-method env "Atomic_Integer64" method-name sig))

  (env-add-class env "Atomic_Boolean" {:name "Atomic_Boolean"})
  (env-add-method env "Atomic_Boolean" "make"
                  {:params [{:name "initial" :type "Boolean"}]
                   :return-type "Atomic_Boolean"})
  (doseq [[method-name sig]
          {"load" {:params [] :return-type "Boolean"}
           "store" {:params [{:name "value" :type "Boolean"}] :return-type "Void"}
           "compare_and_set" {:params [{:name "expected" :type "Boolean"}
                                       {:name "update" :type "Boolean"}]
                              :return-type "Boolean"}}]
    (env-add-method env "Atomic_Boolean" method-name sig))

  (env-add-class env "Atomic_Reference" {:name "Atomic_Reference"
                                         :generic-params [{:name "T"}]})
  (env-add-method env "Atomic_Reference" "make"
                  {:params [{:name "initial" :type {:base-type "T" :detachable true}}]
                   :return-type {:base-type "Atomic_Reference" :type-params ["T"]}})
  (doseq [[method-name sig]
          {"load" {:params [] :return-type {:base-type "T" :detachable true}}
           "store" {:params [{:name "value" :type {:base-type "T" :detachable true}}] :return-type "Void"}
           "compare_and_set" {:params [{:name "expected" :type {:base-type "T" :detachable true}}
                                       {:name "update" :type {:base-type "T" :detachable true}}]
                              :return-type "Boolean"}}]
    (env-add-method env "Atomic_Reference" method-name sig))

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

       (register-builtin-methods env)

       ;; First pass: collect all class definitions, allowing user classes to
       ;; override builtin placeholder names such as Task or Channel.
       (doseq [class-def classes]
         (collect-class-info env class-def))

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
   opts: :classes - seq of class defs, :functions - seq of function defs,
   :var-types - {name type} map.
   Returns the type (string or map) or nil on failure."
  [expr opts]
  (try
    (let [env (make-type-env)]
      (doseq [{:keys [qualified-name source]} (:imports opts)]
        (when (nil? source)
          (let [simple-name (last (str/split qualified-name #"\."))]
            (env-add-class env simple-name {:name simple-name :body [] :import qualified-name}))))
      (register-builtin-methods env)
      (doseq [class-def (:classes opts)]
        (collect-class-info env class-def))
      (doseq [fn-def (:functions opts)]
        (env-add-var env (:name fn-def) (:class-name fn-def)))
      (doseq [[var-name var-type] (:var-types opts)]
        (env-add-var env var-name var-type))
      (check-expression env expr))
    (catch #?(:clj Exception :cljs :default) _ nil)))
