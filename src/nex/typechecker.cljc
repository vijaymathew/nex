(ns nex.typechecker
  "Static type checker for Nex language"
  (:require [clojure.string :as str]
            [clojure.set :as set]))

;;
;; Type Environment
;;

(def ^:dynamic *strict-undefined-targets*
  "When true, a member access / call on an unresolved bare-identifier target is a
   compile-time 'Undefined variable' error. Enabled for whole-program/file
   compilation; left off for the interactive REPL, whose incremental inputs may
   reference bindings from earlier inputs that this type-check env does not carry."
  false)

(defn make-type-env
  "Create a new type environment"
  ([] (make-type-env nil))
  ([parent]
   {:parent parent
    :vars (atom {})
    :methods (atom {})
    :classes (atom {})
    :type-aliases (atom {})
    :non-nil-vars (atom #{})
    :across-cursors (atom {})
    ;; Names this env's block has declared with `let` (per-env, not inherited), so
    ;; a second `let` of the same name in the *same* block is rejected while a
    ;; nested block may still shadow.
    :let-names (atom #{})
    ;; Non-fatal diagnostics surfaced to the user (e.g. equals/hash mismatch).
    ;; Lives on the root env; children share it via env-add-warning.
    :warnings (atom [])}))

(defn env-add-warning
  "Record a non-fatal type-checker warning on the root environment."
  [env msg]
  (let [root (loop [e env] (if (:parent e) (recur (:parent e)) e))]
    (when-let [warnings (:warnings root)]
      (swap! warnings conj msg))))

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

(defn env-add-type-alias
  [env name type-expr]
  (swap! (:type-aliases env) assoc name type-expr))

(defn env-lookup-type-alias
  [env name]
  (if-let [t (get @(:type-aliases env) name)]
    t
    (when (:parent env)
      (env-lookup-type-alias (:parent env) name))))

(declare normalize-type type-name-string)

(defn- merge-generic-constraint-entry
  [acc generic-name constraint]
  (let [generic-name (cond
                       (string? generic-name) generic-name
                       (symbol? generic-name) (name generic-name)
                       (keyword? generic-name) (name generic-name)
                       :else generic-name)
        constraint (cond
                     (string? constraint) constraint
                     (symbol? constraint) (name constraint)
                     (keyword? constraint) (name constraint)
                     :else constraint)]
    (cond
      (nil? generic-name) acc
      (not (contains? acc generic-name)) (assoc acc generic-name constraint)
      (and (nil? (get acc generic-name)) constraint) (assoc acc generic-name constraint)
      :else acc)))
(defn- infer-generic-constraints-from-type
  [class-lookup type-expr]
  (let [t (normalize-type type-expr)]
    (cond
      (string? t) {}
      (map? t)
      (let [base (:base-type t)
            args (or (:type-params t) (:type-args t) [])
            class-def (class-lookup base)
            param-constraints (reduce (fn [acc [{:keys [name constraint]} arg]]
                                        (let [acc' (if (and (string? arg)
                                                            (re-matches #"[A-Z][A-Za-z0-9_]*" arg))
                                                     (merge-generic-constraint-entry acc arg constraint)
                                                     acc)]
                                          (merge acc'
                                                 (infer-generic-constraints-from-type class-lookup arg))))
                                      {}
                                      (map vector (:generic-params class-def) args))]
        param-constraints)
      :else {})))

(defn- normalize-generic-params
  [generic-params constraint-map]
  (let [ordered-names (->> generic-params
                           (map (comp type-name-string :name))
                           (remove nil?)
                           distinct)]
    (mapv (fn [generic-name]
            {:name generic-name
             :constraint (get constraint-map generic-name)})
          ordered-names)))

(defn- normalize-function-def
  [class-lookup {:keys [params return-type class-def generic-params] :as fn-def}]
  (let [constraint-sources (concat
                            (map #(infer-generic-constraints-from-type class-lookup (:type %)) params)
                            [(infer-generic-constraints-from-type class-lookup return-type)]
                            [(reduce (fn [acc {:keys [name constraint]}]
                                       (merge-generic-constraint-entry acc name constraint))
                                     {}
                                     generic-params)])
        constraint-map (reduce (fn [acc source]
                                 (reduce-kv (fn [inner generic-name constraint]
                                              (merge-generic-constraint-entry inner generic-name constraint))
                                            acc
                                            source))
                               {}
                               constraint-sources)
        normalized-generic-params (normalize-generic-params generic-params constraint-map)
        normalized-class-def (assoc class-def :generic-params normalized-generic-params)]
    (-> fn-def
        (assoc :generic-params normalized-generic-params)
        (assoc :class-def normalized-class-def))))

(defn- normalize-function-defs
  [classes functions]
  (let [class-map (merge
                   (into {} (map (fn [class-def] [(:name class-def) class-def]) classes))
                   {"Array" {:name "Array" :generic-params [{:name "T"}]}
                    "Map" {:name "Map" :generic-params [{:name "K"} {:name "V"}]}
                    "Set" {:name "Set" :generic-params [{:name "T"}]}
                    "Task" {:name "Task" :generic-params [{:name "T"}]}
                    "Channel" {:name "Channel" :generic-params [{:name "T"}]}
                    "Min_Heap" {:name "Min_Heap" :generic-params [{:name "T"}]}
                    "Atomic_Reference" {:name "Atomic_Reference" :generic-params [{:name "T"}]}})
        class-lookup (fn [class-name] (get class-map class-name))]
    (mapv #(normalize-function-def class-lookup %) functions)))

(defn- class-defs-by-name-last-wins
  [class-defs]
  (->> class-defs
       (reduce (fn [acc class-def]
                 (assoc acc (:name class-def) class-def))
               {})
       vals
       vec))

(defn- function-class-defs
  [functions]
  (keep :class-def functions))

(defn- type-name-string
  [x]
  (cond
    (string? x) x
    (symbol? x) (name x)
    (keyword? x) (name x)
    :else x))

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
  #{"Integer" "Real" "Char" "Boolean" "String"
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
    (map? type-val)
    (let [base (:base-type type-val)
          param-types (:param-types type-val)
          return-type (:return-type type-val)
          params (or (:type-params type-val) (:type-args type-val))
          core (cond
                 param-types
                 (let [params-str (clojure.string/join ", "
                                    (map (fn [p]
                                           (if (:name p)
                                             (str (:name p) ": " (display-type (:type p)))
                                             (display-type (:type p))))
                                         param-types))
                       sig (str "Function(" params-str ")")]
                   (if return-type
                     (str sig ": " (display-type return-type))
                     sig))
                 (seq params)
                 (str base "[" (clojure.string/join ", " (map display-type params)) "]")
                 :else base)]
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
    (cond
      (:param-types type-expr)
      ;; Function type with explicit signature
      (cond-> {:base-type "Function"
               :param-types (mapv (fn [p] {:name (:name p) :type (normalize-type (:type p))})
                                  (:param-types type-expr))}
        (:return-type type-expr) (assoc :return-type (normalize-type (:return-type type-expr)))
        (:detachable type-expr) (assoc :detachable true))

      (:base-type type-expr)
      (let [params (or (:type-params type-expr) (:type-args type-expr))
            detachable? (true? (:detachable type-expr))]
        (cond-> {:base-type (:base-type type-expr)}
          params (assoc :type-params (mapv normalize-type params))
          detachable? (assoc :detachable true)))

      :else (str type-expr))
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
        (:type-params n) (update :type-params #(mapv attachable-type %))
        (:param-types n) (update :param-types #(mapv (fn [p] (update p :type attachable-type)) %))
        (:return-type n) (update :return-type attachable-type))
      n)))

(defn expand-type-aliases
  "Recursively expand declared type aliases in a type expression."
  [env type-expr]
  (cond
    (string? type-expr)
    (if-let [expanded (env-lookup-type-alias env type-expr)]
      (expand-type-aliases env expanded)
      type-expr)

    (map? type-expr)
    (cond
      (:param-types type-expr)
      (cond-> type-expr
        true (update :param-types #(mapv (fn [p] (update p :type (partial expand-type-aliases env))) %))
        (:return-type type-expr) (update :return-type (partial expand-type-aliases env)))

      (:base-type type-expr)
      (let [expanded-base (if-let [a (env-lookup-type-alias env (:base-type type-expr))]
                            (expand-type-aliases env a)
                            type-expr)]
        (if (not= expanded-base type-expr)
          expanded-base
          (cond-> type-expr
            (:type-params type-expr)
            (update :type-params #(mapv (partial expand-type-aliases env) %))
            (:type-args type-expr)
            (update :type-args #(mapv (partial expand-type-aliases env) %)))))

      :else type-expr)

    :else type-expr))

(defn reference-like-type?
  "Whether type is a reference-like (potentially detachable) object type."
  [t]
  (let [n (attachable-type t)
        base (cond
               (string? n) n
               (map? n) (:base-type n)
               :else nil)]
    (and (string? base)
         (not (#{"Integer" "Real" "Char" "Boolean"} base)))))

(defn- attached-non-scalar-type?
  "Whether a type is an attached, non-scalar return type that must not
   implicitly fall back to nil."
  [t]
  (let [n (normalize-type t)]
    (and (not (detachable-type? n))
         (reference-like-type? n))))

(defn is-generic-type-param?
  "Check if a type is a generic type parameter (single uppercase letter)."
  ([type]
   (let [t (normalize-type type)]
     (and (string? t)
          (re-matches #"[A-Z][A-Za-z0-9_]*" t))))
  ([env type]
   (let [t (normalize-type type)]
     (and (string? t)
          (re-matches #"[A-Z][A-Za-z0-9_]*" t)
          (not (env-lookup-class env t))
          (not (builtin-type? t))
          ;; A declared type alias names a concrete type, not a generic param.
          (not (env-lookup-type-alias env t))))))

(declare visible-class-defs)

(defn- declared-generic-param?
  [env type]
  (let [t (normalize-type type)
        current-class (some-> (env-lookup-var env "__current_class__")
                              type-name-string)
        current-class-def (when current-class
                            (env-lookup-class env current-class))
        current-class-generic? (some (fn [{:keys [name]}]
                                       (= (type-name-string name) t))
                                     (:generic-params current-class-def))
        visible-generic? (some (fn [class-def]
                                 (some (fn [{:keys [name]}]
                                         (= (type-name-string name) t))
                                       (:generic-params class-def)))
                               (visible-class-defs env))]
    (and (string? t)
         (re-matches #"[A-Z][A-Za-z0-9_]*" t)
         (or current-class-generic?
             visible-generic?))))

(defn- visible-class-defs
  [env]
  (let [here (vals @(:classes env))]
    (if-let [parent (:parent env)]
      (concat here (visible-class-defs parent))
      here)))

(defn- generic-param-constraint
  [env generic-name]
  (let [generic-name (type-name-string generic-name)
        current-class (env-lookup-var env "__current_class__")
        current-class-constraint (when current-class
                                   (some (fn [{:keys [name constraint]}]
                                           (when (= (type-name-string name) generic-name)
                                             (type-name-string constraint)))
                                         (:generic-params (env-lookup-class env current-class))))
        visible-constraints (->> (visible-class-defs env)
                                 (mapcat :generic-params)
                                 (filter #(= (type-name-string (:name %)) generic-name))
                                 (keep (comp type-name-string :constraint))
                                 distinct
                                 vec)]
    (or current-class-constraint
        (when (= 1 (count visible-constraints))
          (first visible-constraints)))))

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
         ;; Generic type parameters are compatible with any type (only when not a class)
         (or (and env (is-generic-type-param? env t1))
             (and env (is-generic-type-param? env t2))
             (and (nil? env) (is-generic-type-param? t1))
             (and (nil? env) (is-generic-type-param? t2)))
         ;; Function types are equal iff they have the same parameter types and
         ;; the same return type (parameter names are irrelevant). Their
         ;; conformance under subtyping -- contravariant parameters, covariant
         ;; return -- is handled separately by types-compatible?,
         ;; so two differing signatures must NOT be reported equal here on the
         ;; strength of a shared (empty) :type-params list.
         (and (map? t1) (map? t2)
              (= (:base-type t1) "Function") (= (:base-type t2) "Function")
              (= (mapv :type (:param-types t1)) (mapv :type (:param-types t2)))
              (= (:return-type t1) (:return-type t2)))
         ;; Handle other parameterized types
         (and (map? t1) (map? t2)
              (not= (:base-type t1) "Function")
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
      (let [sub (or (when-not (env-lookup-class env sub)
                      (generic-param-constraint env sub))
                    sub)
            super (or (when-not (env-lookup-class env super)
                        (generic-param-constraint env super))
                      super)]
        (letfn [(sub? [current seen]
                (if (contains? seen current)
                  false
                  (if-let [class-def (env-lookup-class env current)]
                    (let [parents (map :parent (:parents class-def))
                          seen (conj seen current)]
                      (or (some #(= % super) parents)
                          (some #(sub? % seen) parents)))
                    false)))]
          (sub? sub #{}))))))

(defn types-compatible?
  "Check if two types are compatible (including inheritance)."
  [env type1 type2]
  (let [type1 (expand-type-aliases env type1)
        type2 (expand-type-aliases env type2)
        t1 (normalize-type type1) ;; source type
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
      (or ;; Empty-collection element sentinel is compatible with any concrete type
          (contains? #{"__EmptyArrayElement" "__EmptyMapKey" "__EmptyMapValue" "__EmptySetElement"} (str a1))
          (contains? #{"__EmptyArrayElement" "__EmptyMapKey" "__EmptyMapValue" "__EmptySetElement"} (str a2))
          (and (map? a1) (map? a2)
               (= (:base-type a1) "Array")
               (= (:base-type a2) "Array")
               (or (= (:type-params a1) ["__EmptyArrayElement"])
                   (= (:type-params a2) ["__EmptyArrayElement"])))
          (and (map? a1) (map? a2)
               (= (:base-type a1) "Map")
               (= (:base-type a2) "Map")
               (or (= (:type-params a1) ["__EmptyMapKey" "__EmptyMapValue"])
                   (= (:type-params a2) ["__EmptyMapKey" "__EmptyMapValue"])))
          (and (map? a1) (map? a2)
               (= (:base-type a1) "Set")
               (= (:base-type a2) "Set")
               (or (= (:type-params a1) ["__EmptySetElement"])
                   (= (:type-params a2) ["__EmptySetElement"])))
          (types-equal? env a1 a2)
          ;; Function type with signature is compatible with bare Function
          (and (map? a1) (= (:base-type a1) "Function") (:param-types a1) (= a2 "Function"))
          ;; Two function signatures: parameters CONTRAVARIANT, return COVARIANT.
          ;; a1 is the source (value) type; a2 the target (expected) type. The
          ;; value conforms when it accepts at least what the target promises to
          ;; pass (params contravariant) and returns at most what the target
          ;; promises to deliver (return covariant).
          (and (map? a1) (map? a2)
               (= (:base-type a1) "Function") (= (:base-type a2) "Function")
               (:param-types a1) (:param-types a2)
               (= (count (:param-types a1)) (count (:param-types a2)))
               (every? true?
                       (map (fn [p1 p2]
                              ;; contravariant: target param must conform to source param
                              (types-compatible? env (:type p2) (:type p1)))
                            (:param-types a1) (:param-types a2)))
               (or (nil? (:return-type a1)) (nil? (:return-type a2))
                   ;; covariant: source return must conform to target return
                   (types-compatible? env (:return-type a1) (:return-type a2))))
          (and (string? a1) (string? a2) (class-subtype? env a1 a2))
          (and (map? a1) (string? a2) (class-subtype? env (:base-type a1) a2))
          ;; Generic class conformance. Function types are excluded here: their
          ;; conformance is decided solely by the function-signature branch above
          ;; (contravariant params, covariant return), so two distinct function
          ;; signatures are NOT silently accepted as a no-type-params class match.
          (and (map? a1) (map? a2)
               (not= (:base-type a1) "Function") (not= (:base-type a2) "Function")
               (class-subtype? env (:base-type a1) (:base-type a2))
               (= (:type-params a1) (:type-params a2)))
          (and (map? a1) (map? a2)
               (not= (:base-type a1) "Function") (not= (:base-type a2) "Function")
               (= (:base-type a1) (:base-type a2))
               (= (count (:type-params a1)) (count (:type-params a2)))
               (every? true? (map (fn [p1 p2]
                                    (or (= p2 "Any")
                                        (types-compatible? env p1 p2)))
                                  (:type-params a1) (:type-params a2))))))))

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
        (= t "Real"))))

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
      "Map" {:base-type "Array" :type-params ["Any"]}
      "Cursor" "Any"
      "Any")))

(defn- collect-generic-names-from-type
  [type-expr]
  (let [t (normalize-type type-expr)]
    (cond
      (string? t) #{t}
      (map? t) (reduce set/union #{}
                       (map collect-generic-names-from-type
                            (or (:type-params t) (:type-args t) [])))
      :else #{})))

(defn- generic-constraint-map
  [class-defs]
  (reduce (fn [acc {:keys [name generic-params]}]
            (if (or (builtin-type? name) (empty? generic-params))
              acc
              (reduce (fn [inner {:keys [name constraint]}]
                        (let [name (type-name-string name)
                              constraint (type-name-string constraint)]
                          (cond
                            (not (contains? inner name)) (assoc inner name constraint)
                            (and (nil? (get inner name)) constraint) (assoc inner name constraint)
                            :else inner)))
                      acc
                      generic-params)))
          {}
          class-defs))

(defn- register-generic-param-classes!
  [env generic-params]
  (doseq [{:keys [name constraint]} generic-params]
    (let [name (type-name-string name)
          constraint (type-name-string constraint)]
      (when (and name
               (not (builtin-type? name))
               (not (env-lookup-class env name)))
        (env-add-class env name
                       (cond-> {:name name
                                :deferred? true
                                :generic-params nil
                                :parents [{:parent "Any"}]
                                :body []}
                         constraint
                         (update :parents conj {:parent constraint})))))))

(defn- register-visible-generic-classes!
  [env class-defs var-types]
  (let [constraint-map (generic-constraint-map class-defs)
        visible-generic-names (reduce set/union #{}
                                      (map collect-generic-names-from-type (vals var-types)))]
    (doseq [generic-name visible-generic-names]
      (when (and (not (builtin-type? generic-name))
                 (not (env-lookup-class env generic-name)))
        (register-generic-param-classes!
         env
         [{:name generic-name :constraint (get constraint-map generic-name)}])))))

(defn integral-type?
  "Check if a type is an integral numeric type."
  [type]
  (= (normalize-type type) "Integer"))

(defn division-result-type
  "Infer the result type of division.
   Integral / integral stays integral; any non-integral operand yields Real."
  [left-type right-type]
  (if (and (integral-type? left-type) (integral-type? right-type))
    "Integer"
    "Real"))

(defn numeric-result-type
  "Infer a common numeric type for non-division arithmetic.
   Real wins over the integral types."
  [left-type right-type]
  (let [left (normalize-type left-type)
        right (normalize-type right-type)]
    (cond
      (or (= left "Real") (= right "Real")) "Real"
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
   (let [class-name (or (when-not (env-lookup-class env class-name)
                          (generic-param-constraint env class-name))
                        class-name)]
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
       (lookup-method class-name #{})))))

(defn lookup-class-method-any-arity
  "Look up a method name on a class and its parent chain, ignoring arity."
  [env class-name method-name caller-class-name]
  (let [class-name (or (when-not (env-lookup-class env class-name)
                         (generic-param-constraint env class-name))
                       class-name)]
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
      (lookup-method class-name #{}))))

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
       "Integer"

       (or (= klass Float/TYPE)
           (= klass java.lang.Float)
           (= klass Double/TYPE)
           (= klass java.lang.Double))
       "Real"

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
      (if (and (or (is-comparable-type? left-type)
                   (types-compatible? env left-type "Comparable"))
               (or (is-comparable-type? right-type)
                   (types-compatible? env right-type "Comparable"))
               (types-equal? env left-type right-type))
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
                        (types-compatible? env target-type value-type)
                        (declared-generic-param? env value-type)
                        (declared-generic-param? env target-type)
                        (is-generic-type-param? env value-type)
                        (is-generic-type-param? env target-type))]
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

(defn- invalid-bare-create-call-error
  [class-name]
  (ex-info (str "Invalid create syntax for " class-name)
           {:error (type-error
                    (str "Invalid create syntax for " class-name
                         ". Use 'create " class-name
                         "' for the default constructor or 'create "
                         class-name ".<ctor>(...)' for an explicit constructor."))}))
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
        ;; Resolve any declared type alias before deriving `base-type` etc., so
        ;; that a value typed through an alias (e.g. `declare type F =
        ;; Function(...)`) is treated as its underlying type. Without this a
        ;; call through a function-type alias misses the `Function` branch below
        ;; and fails with an opaque "Method not found: callN".
        target-type (expand-type-aliases
                     env
                     (if class-target
                       target-name
                       (if (string? target)
                         (or (env-lookup-var env target)
                             (when current-class
                               (lookup-class-field env current-class target)))
                         (check-expression env target))))
        normalized-target (normalize-type target-type)
        target-detachable? (detachable-type? normalized-target)
        guarded? (and (string? target) (env-var-non-nil? env target))
        base-type (if (map? target-type)
                    (:base-type target-type)
                    target-type)
        type-map (build-generic-type-map env target-type)]
    ;; A bare-identifier target that resolves to nothing — not a local, field,
    ;; class, across cursor, a parameterless routine of the current class, nor a
    ;; Java name inside `with java` — is an undefined variable. Without this the
    ;; member access slips through type-checking with a nil/Any target type and
    ;; fails later (cryptically in the JVM lowering, or only at runtime).
    (when (and *strict-undefined-targets*
               (string? target)
               (nil? target-type)
               (not across-item-type)
               (not with-java?)
               ;; `this`/`super`/`Current` are special call targets, not variables.
               (not (#{"this" "super" "Current"} target))
               (not (and current-class
                         (lookup-class-method env current-class target 0 current-class))))
      (throw (ex-info (str "Undefined variable: " target)
                      {:error (type-error (str "Undefined variable: " target))})))
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

      ;; Invoking a Function value that carries an explicit signature (e.g.
      ;; `f.call1(x)` where `f: Function(n: Integer): Integer`): the declared
      ;; return type is more precise than the generic callN result (Any).
      (and (= base-type "Function")
           (map? target-type)
           (:return-type target-type)
           (re-matches #"call\d+" (str method)))
      (do
        (doseq [arg args]
          (check-expression env arg))
        (:return-type target-type))

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

;; ---------------------------------------------------------------------------
;; Built-in free-function call checking.
;;
;; Most built-ins share a few shapes: a fixed arity, an optional uniform or
;; positional argument-type constraint, and a fixed result type.  Those are
;; expressed as data in `builtin-call-checkers`; the handful of irregular
;; built-ins (variadic, optional args, generic result inference) get bespoke
;; checker functions.  Each checker is `(fn [env args] -> type)` and is
;; responsible for arity/type validation, raising the same errors the original
;; inline `cond` produced.
;; ---------------------------------------------------------------------------

(defn- builtin-arg-noun [n]
  (if (= n 1) "argument" "arguments"))

(defn- assert-builtin-arity!
  [name n args]
  (when (not= (count args) n)
    (throw (ex-info (str name " expects exactly " n " " (builtin-arg-noun n))
                    {:error (type-error
                             (str name " expects " n " " (builtin-arg-noun n)
                                  ", got " (count args)))}))))

(defn- builtin-nullary
  "Arity 0, no argument checks; returns `ret`."
  [name ret]
  (fn [_env args]
    (assert-builtin-arity! name 0 args)
    ret))

(defn- builtin-checked-args
  "Arity `n`; type-checks each argument with no type constraint; returns `ret`."
  [name n ret]
  (fn [env args]
    (assert-builtin-arity! name n args)
    (doseq [arg args] (check-expression env arg))
    ret))

(defn- builtin-single-arg
  "Arity 1; the argument's attachable type must equal `arg-type`; returns `ret`."
  [name arg-type ret]
  (fn [env args]
    (assert-builtin-arity! name 1 args)
    (let [t (check-expression env (first args))]
      (when-not (= (attachable-type t) (attachable-type arg-type))
        (throw (ex-info (str name " argument must be " arg-type)
                        {:error (type-error
                                 (str name " argument must be " arg-type
                                      ", got " (display-type t)))}))))
    ret))

(defn- builtin-uniform-args
  "Arity `n`; every argument's attachable type must equal `arg-type` (plural
   \"arguments\" wording); returns `ret`."
  [name n arg-type ret]
  (fn [env args]
    (assert-builtin-arity! name n args)
    (doseq [arg args]
      (let [t (check-expression env arg)]
        (when-not (= (attachable-type t) (attachable-type arg-type))
          (throw (ex-info (str name " arguments must be " arg-type)
                          {:error (type-error
                                   (str name " arguments must be " arg-type
                                        ", got " (display-type t)))})))))
    ret))

(def ^:private builtin-ordinals
  ["first" "second" "third" "fourth" "fifth" "sixth"])

(defn- builtin-positional-args
  "Arity (count arg-types); type-checks all arguments first, then validates each
   position's attachable type against `arg-types` using ordinal wording;
   returns `ret`."
  [name arg-types ret]
  (let [n (count arg-types)]
    (fn [env args]
      (assert-builtin-arity! name n args)
      (let [types (mapv #(check-expression env %) args)]
        (doseq [[i t expected] (map vector (range) types arg-types)]
          (when-not (= (attachable-type t) (attachable-type expected))
            (throw (ex-info (str name " " (nth builtin-ordinals i) " argument must be " expected)
                            {:error (type-error
                                     (str name " " (nth builtin-ordinals i)
                                          " argument must be " expected
                                          ", got " (display-type t)))})))))
      ret)))

(defn- check-builtin-print [env args]
  (doseq [arg args]
    (check-expression env arg))
  "Void")

(defn- check-builtin-sleep [env args]
  (assert-builtin-arity! "sleep" 1 args)
  (let [arg-type (check-expression env (first args))]
    (when-not (types-compatible? env arg-type "Integer")
      (throw (ex-info "sleep argument must be Integer"
                      {:error (type-error
                               (str "sleep argument must be Integer, got "
                                    (display-type arg-type)))}))))
  "Void")

(defn- check-builtin-type-is [env args]
  (assert-builtin-arity! "type_is" 2 args)
  (let [target-type-type (check-expression env (first args))]
    (when-not (= (attachable-type target-type-type) "String")
      (throw (ex-info "type_is first argument must be String"
                      {:error (type-error
                               (str "type_is first argument must be String, got "
                                    (display-type target-type-type)))}))))
  (check-expression env (second args))
  "Boolean")

(defn- check-builtin-await-all [env args]
  (assert-builtin-arity! "await_all" 1 args)
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

(defn- check-builtin-await-any [env args]
  (assert-builtin-arity! "await_any" 1 args)
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

(defn- check-builtin-http-get [env args]
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

(defn- check-builtin-http-post [env args]
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

(defn- check-builtin-text-file-write [env args]
  (assert-builtin-arity! "text_file_write" 2 args)
  (check-expression env (first args))
  (let [text-type (check-expression env (second args))]
    (when-not (= (attachable-type text-type) "String")
      (throw (ex-info "text_file_write second argument must be String"
                      {:error (type-error
                               (str "text_file_write second argument must be String, got "
                                    (display-type text-type)))}))))
  "Void")

(defn- check-builtin-binary-file-read [env args]
  (assert-builtin-arity! "binary_file_read" 2 args)
  (check-expression env (first args))
  (let [count-type (check-expression env (second args))]
    (when-not (= (attachable-type count-type) "Integer")
      (throw (ex-info "binary_file_read second argument must be Integer"
                      {:error (type-error
                               (str "binary_file_read second argument must be Integer, got "
                                    (display-type count-type)))}))))
  {:base-type "Array" :type-params ["Integer"]})

(defn- check-builtin-binary-file-write [env args]
  (assert-builtin-arity! "binary_file_write" 2 args)
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

(defn- check-builtin-binary-file-seek [env args]
  (assert-builtin-arity! "binary_file_seek" 2 args)
  (check-expression env (first args))
  (let [offset-type (check-expression env (second args))]
    (when-not (= (attachable-type offset-type) "Integer")
      (throw (ex-info "binary_file_seek second argument must be Integer"
                      {:error (type-error
                               (str "binary_file_seek second argument must be Integer, got "
                                    (display-type offset-type)))}))))
  "Void")

(defn- check-builtin-http-server-route
  "http_server_get/post/put/delete: (handle, path:String, handler:Function) -> Void."
  [name]
  (fn [env args]
    (assert-builtin-arity! name 3 args)
    (check-expression env (first args))
    (let [path-type (check-expression env (second args))
          handler-type (check-expression env (nth args 2))]
      (when-not (= (attachable-type path-type) "String")
        (throw (ex-info (str name " path argument must be String")
                        {:error (type-error
                                 (str name " path argument must be String, got "
                                      (display-type path-type)))})))
      (when-not (types-compatible? env handler-type "Function")
        (throw (ex-info (str name " handler argument must be Function")
                        {:error (type-error
                                 (str name " handler argument must be Function, got "
                                      (display-type handler-type)))}))))
    "Void"))

(def ^:private builtin-call-checkers
  {"print"   check-builtin-print
   "println" check-builtin-print
   "sleep"   check-builtin-sleep
   "hint_spin"    (builtin-nullary "hint_spin" "Void")
   "random_real"  (builtin-nullary "random_real" "Real")
   "datetime_now" (builtin-nullary "datetime_now" "Integer")
   "type_of"  (builtin-checked-args "type_of" 1 "String")
   "type_is"  check-builtin-type-is
   "await_all" check-builtin-await-all
   "await_any" check-builtin-await-any

   ;; regex
   "regex_validate" (builtin-uniform-args "regex_validate" 2 "String" "Boolean")
   "regex_matches"  (builtin-uniform-args "regex_matches" 3 "String" "Boolean")
   "regex_find"     (builtin-uniform-args "regex_find" 3 "String" {:base-type "String" :detachable true})
   "regex_find_all" (builtin-uniform-args "regex_find_all" 3 "String" {:base-type "Array" :type-args ["String"]})
   "regex_replace"  (builtin-uniform-args "regex_replace" 4 "String" "String")
   "regex_split"    (builtin-uniform-args "regex_split" 3 "String" {:base-type "Array" :type-args ["String"]})

   ;; datetime
   "datetime_from_epoch_millis" (builtin-single-arg "datetime_from_epoch_millis" "Integer" "Integer")
   "datetime_parse_iso"  (builtin-single-arg "datetime_parse_iso" "String" "Integer")
   "datetime_make"       (builtin-uniform-args "datetime_make" 6 "Integer" "Integer")
   "datetime_year"       (builtin-single-arg "datetime_year" "Integer" "Integer")
   "datetime_month"      (builtin-single-arg "datetime_month" "Integer" "Integer")
   "datetime_day"        (builtin-single-arg "datetime_day" "Integer" "Integer")
   "datetime_weekday"    (builtin-single-arg "datetime_weekday" "Integer" "Integer")
   "datetime_day_of_year" (builtin-single-arg "datetime_day_of_year" "Integer" "Integer")
   "datetime_hour"       (builtin-single-arg "datetime_hour" "Integer" "Integer")
   "datetime_minute"     (builtin-single-arg "datetime_minute" "Integer" "Integer")
   "datetime_second"     (builtin-single-arg "datetime_second" "Integer" "Integer")
   "datetime_epoch_millis" (builtin-single-arg "datetime_epoch_millis" "Integer" "Integer")
   "datetime_add_millis"  (builtin-uniform-args "datetime_add_millis" 2 "Integer" "Integer")
   "datetime_diff_millis" (builtin-uniform-args "datetime_diff_millis" 2 "Integer" "Integer")
   "datetime_truncate_to_day"  (builtin-single-arg "datetime_truncate_to_day" "Integer" "Integer")
   "datetime_truncate_to_hour" (builtin-single-arg "datetime_truncate_to_hour" "Integer" "Integer")
   "datetime_format_iso" (builtin-single-arg "datetime_format_iso" "Integer" "String")

   ;; path
   "path_exists"       (builtin-single-arg "path_exists" "String" "Boolean")
   "path_is_file"      (builtin-single-arg "path_is_file" "String" "Boolean")
   "path_is_directory" (builtin-single-arg "path_is_directory" "String" "Boolean")
   "path_name"         (builtin-single-arg "path_name" "String" "String")
   "path_extension"    (builtin-single-arg "path_extension" "String" "String")
   "path_name_without_extension" (builtin-single-arg "path_name_without_extension" "String" "String")
   "path_absolute"     (builtin-single-arg "path_absolute" "String" "String")
   "path_normalize"    (builtin-single-arg "path_normalize" "String" "String")
   "path_size"         (builtin-single-arg "path_size" "String" "Integer")
   "path_modified_time" (builtin-single-arg "path_modified_time" "String" "Integer")
   "path_parent"       (builtin-single-arg "path_parent" "String" {:base-type "String" :detachable true})
   "path_child"        (builtin-positional-args "path_child" ["String" "String"] "String")
   "path_create_file"  (builtin-single-arg "path_create_file" "String" "Void")
   "path_create_directory"   (builtin-single-arg "path_create_directory" "String" "Void")
   "path_create_directories" (builtin-single-arg "path_create_directories" "String" "Void")
   "path_delete"       (builtin-single-arg "path_delete" "String" "Void")
   "path_delete_tree"  (builtin-single-arg "path_delete_tree" "String" "Void")
   "path_copy"         (builtin-positional-args "path_copy" ["String" "String"] "Void")
   "path_move"         (builtin-positional-args "path_move" ["String" "String"] "Void")
   "path_read_text"    (builtin-single-arg "path_read_text" "String" "String")
   "path_write_text"   (builtin-positional-args "path_write_text" ["String" "String"] "Void")
   "path_append_text"  (builtin-positional-args "path_append_text" ["String" "String"] "Void")
   "path_list"         (builtin-single-arg "path_list" "String" {:base-type "Array" :type-params ["String"]})

   ;; text files
   "text_file_open_read"   (builtin-single-arg "text_file_open_read" "String" "Any")
   "text_file_open_write"  (builtin-single-arg "text_file_open_write" "String" "Any")
   "text_file_open_append" (builtin-single-arg "text_file_open_append" "String" "Any")
   "text_file_read_line"   (builtin-checked-args "text_file_read_line" 1 {:base-type "String" :detachable true})
   "text_file_write"       check-builtin-text-file-write
   "text_file_close"       (builtin-checked-args "text_file_close" 1 "Void")

   ;; binary files
   "binary_file_open_read"   (builtin-single-arg "binary_file_open_read" "String" "Any")
   "binary_file_open_write"  (builtin-single-arg "binary_file_open_write" "String" "Any")
   "binary_file_open_append" (builtin-single-arg "binary_file_open_append" "String" "Any")
   "binary_file_read_all"    (builtin-checked-args "binary_file_read_all" 1 {:base-type "Array" :type-params ["Integer"]})
   "binary_file_read"        check-builtin-binary-file-read
   "binary_file_write"       check-builtin-binary-file-write
   "binary_file_position"    (builtin-checked-args "binary_file_position" 1 "Integer")
   "binary_file_seek"        check-builtin-binary-file-seek
   "binary_file_close"       (builtin-checked-args "binary_file_close" 1 "Void")

   ;; http client / json
   "http_get"  check-builtin-http-get
   "http_post" check-builtin-http-post
   "json_parse"     (builtin-single-arg "json_parse" "String" "Any")
   "json_stringify" (builtin-checked-args "json_stringify" 1 "String")

   ;; http server
   "http_server_create" (builtin-single-arg "http_server_create" "Integer" "Any")
   "http_server_get"    (check-builtin-http-server-route "http_server_get")
   "http_server_post"   (check-builtin-http-server-route "http_server_post")
   "http_server_put"    (check-builtin-http-server-route "http_server_put")
   "http_server_delete" (check-builtin-http-server-route "http_server_delete")
   "http_server_start"  (builtin-checked-args "http_server_start" 1 "Integer")
   "http_server_stop"   (builtin-checked-args "http_server_stop" 1 "Void")
   "http_server_is_running" (builtin-checked-args "http_server_is_running" 1 "Boolean")})

(defn- env-call-method-arities
  "Collect the arities at which `class-name` directly defines `callN` methods,
   walking the env parent chain (mirrors how env-lookup-method resolves). Used to
   turn an unmatched `callN` lookup into a clear function-arity error rather than
   an opaque \"Method not found: callN\"."
  [env class-name]
  (loop [e env
         acc #{}]
    (if e
      (recur (:parent e)
             (into acc
                   (mapcat (fn [[mname arities]]
                             (when (re-matches #"call\d+" (str mname))
                               (keys arities)))
                           (get @(:methods e) class-name))))
      (sort acc))))

(defn check-call
  "Check the type of a method call"
  [env {:keys [target method args has-parens] :as expr}]
  (if (and (map? target) (= :create (:type target)) (nil? method))
    (if (nil? (:constructor target))
      (throw (invalid-bare-create-call-error (:class-name target)))
      (check-create env (assoc target :args args)))
    (if target
      (check-target-call env expr)
      ;; Function call (built-in like print/type_of/type_is) or function object call
      (if-let [checker (get builtin-call-checkers method)]
        (checker env args)
      (if-let [var-type (expand-type-aliases env (env-lookup-var env method))]
      (let [base-type (if (map? var-type) (:base-type var-type) var-type)
            call-name (str "call" (count args))
            method-sig (env-lookup-method env base-type call-name (count args))
            class-def (env-lookup-class env base-type)]
        (when-not method-sig
          (let [call-arities (env-call-method-arities env base-type)]
            (if (= 1 (count call-arities))
              ;; A free function (or single-arity callable) invoked at the wrong
              ;; arity: report the function and the counts instead of `callN`.
              (let [expected (first call-arities)
                    given (count args)
                    msg (str "Function `" method "` takes " expected
                             (if (= 1 expected) " argument" " arguments")
                             ", " (when (< given expected) "only ") given " given")]
                (throw (ex-info msg {:error (type-error msg)})))
              (throw (ex-info (str "Method not found: " call-name)
                              {:error (type-error
                                       (str "Method not found: " call-name))})))))
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
          ;; A Function value carrying an explicit signature knows its own return
          ;; type; prefer it over the generic callN result (which is Any).
          (if (and (map? var-type)
                   (= "Function" (:base-type var-type))
                   (:return-type var-type))
            (:return-type var-type)
            (resolve-generic-type (:return-type method-sig) type-map))))
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
                        {:error (type-error "Atomic_Integer64.make expects exactly 1 Integer argument")})))
      (let [arg-type (check-expression env (first args))]
        (when-not (types-compatible? env arg-type "Integer")
          (throw (ex-info "Atomic_Integer64.make requires Integer initial value"
                          {:error (type-error
                                   (str "Atomic_Integer64.make expects Integer, got "
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
          :anonymous-function (let [class-def (:class-def expr)]
                                ;; Register the dynamic class definition in the type environment
                                (collect-class-info env class-def)
                                ;; Check the class (this will check the callN method body)
                                (check-class env class-def)
                                ;; Anonymous functions have distinct generated runtime classes,
                                ;; but their stable static type is Function.
                                "Function")
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
                      {:error (type-error (str "Cannot assign to constant: " target))})))
    (when-let [field-member (lookup-class-field-member env current-class target current-class)]
      (when (and (:once? field-member) (not (env-lookup-var env "__in_constructor__")))
        (throw (ex-info (str "Cannot assign to once field outside constructor: " target)
                        {:error (type-error (str "'" target "' is a once field and can only be assigned in a constructor"))})))))
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
  ;; No two `let` declarations in the same block may bind the same identifier.
  ;; `:let-names` is per-env, so a nested block (its own env) may still shadow.
  ;; Compiler-synthesised lets (e.g. an across cursor) are exempt.
  (when (and (not synthetic) (string? name) (:let-names env))
    (if (contains? @(:let-names env) name)
      (let [msg (str "Duplicate local variable '" name "' declared in the same block. "
                     "A nested block may shadow an outer binding, but two declarations "
                     "in one block may not share a name.")]
        (throw (ex-info msg {:error (type-error msg)})))
      (swap! (:let-names env) conj name)))
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

(defn- find-sealed-subclasses
  "Return the names of all classes in env that directly inherit from sealed-class-name."
  [env sealed-class-name]
  (->> (visible-class-defs env)
       (filter (fn [class-def]
                 (some #(= (:parent %) sealed-class-name) (:parents class-def))))
       (map :name)
       set))

(defn check-match
  "Type-check a match statement over a sealed type."
  [env {:keys [expr clauses else]}]
  (let [expr-type (check-expression env expr)
        base-type-name (if (map? expr-type) (:base-type expr-type) expr-type)
        class-def (env-lookup-class env base-type-name)
        sealed? (and class-def (:sealed? class-def))]
    (doseq [{:keys [class-name var-name body]} clauses]
      (when-not (or (= class-name base-type-name)
                    (class-subtype? env class-name base-type-name))
        (throw (ex-info (str "Match clause type " class-name
                             " is not a subclass of " base-type-name)
                        {:error (type-error
                                 (str class-name " is not a subclass of "
                                      base-type-name))})))
      (let [clause-env (make-type-env env)]
        (env-add-var clause-env var-name class-name)
        (env-mark-non-nil clause-env var-name)
        (doseq [s body]
          (check-statement clause-env s))))
    (when else
      (doseq [s else] (check-statement env s)))
    (when (and sealed? (not else))
      (let [covered (set (map :class-name clauses))
            known (find-sealed-subclasses env base-type-name)
            uncovered (set/difference known covered)]
        (when (seq uncovered)
          (throw (ex-info (str "Non-exhaustive match on sealed type " base-type-name)
                          {:error (type-error
                                   (str "Match on sealed type " base-type-name
                                        " does not cover all variants. Missing: "
                                        (str/join ", " (sort uncovered))))})))))))

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
          :match (check-match env stmt)
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
                _ (when (and (:once? field-member)
                             (not (env-lookup-var env "__in_constructor__")))
                    (throw (ex-info (str "Cannot assign to once field outside constructor: " field-name)
                                    {:error (type-error (str "'" field-name "' is a once field and can only be assigned in a constructor"))})))
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

(declare result-definitely-assigned-in-body?)
(declare body-may-complete-normally?)

(defn- result-definitely-assigned-after-stmt
  "Whether result is definitely assigned after executing stmt, assuming assigned? before it."
  [stmt assigned?]
  (case (:type stmt)
    :assign (if (#{"result" "Result"} (:target stmt)) true assigned?)
    :let (if (#{"result" "Result"} (:name stmt)) true assigned?)
    :if (let [;; A branch that cannot complete normally (it always raises or
              ;; retries) contributes no path that falls through to the rest of
              ;; the routine, so it need not assign result itself.
              branch-out (fn [body]
                           (or (not (body-may-complete-normally? body))
                               (result-definitely-assigned-in-body? body assigned?)))
              branch-outs (concat
                           [(branch-out (:then stmt))]
                           (map #(branch-out (:then %)) (:elseif stmt))
                           [(if (:else stmt)
                              (branch-out (:else stmt))
                              assigned?)])]
          (every? true? branch-outs))
    :loop (result-definitely-assigned-in-body? (:init stmt) assigned?)
    :select (let [clause-outs (map #(result-definitely-assigned-in-body? (:body %) assigned?) (:clauses stmt))
                  timeout-out (when-let [timeout (:timeout stmt)]
                                (result-definitely-assigned-in-body? (:body timeout) assigned?))
                  else-out (when-let [else-body (:else stmt)]
                             (result-definitely-assigned-in-body? else-body assigned?))
                  all-outs (concat clause-outs
                                   (when timeout-out [timeout-out])
                                   (when else-out [else-out]))]
              (if (seq all-outs)
                (every? true? all-outs)
                assigned?))
    :scoped-block (let [body-out (result-definitely-assigned-in-body? (:body stmt) assigned?)]
                    (if-let [rescue-body (:rescue stmt)]
                      ;; The block completes normally either by the body completing
                      ;; (result assigned iff body-out) or by the rescue completing
                      ;; normally. A rescue that always 'retry's (re-runs the body) or
                      ;; 're-raise's never falls through, so it adds no returning path
                      ;; and need not assign result itself.
                      (if (body-may-complete-normally? rescue-body)
                        (and body-out
                             (result-definitely-assigned-in-body? rescue-body assigned?))
                        body-out)
                      body-out))
    :with (result-definitely-assigned-in-body? (:body stmt) assigned?)
    :case (let [clause-outs (map #(result-definitely-assigned-in-body? (:body %) assigned?) (:clauses stmt))
                else-out (if-let [else-body (:else stmt)]
                           (result-definitely-assigned-in-body? else-body assigned?)
                           assigned?)]
            (every? true? (concat clause-outs [else-out])))
    :match (let [clause-outs (map #(result-definitely-assigned-in-body? (:body %) assigned?) (:clauses stmt))
                 else-out (if-let [else-body (:else stmt)]
                            (result-definitely-assigned-in-body? else-body assigned?)
                            assigned?)]
             (every? true? (concat clause-outs [else-out])))
    assigned?))

(defn- result-definitely-assigned-in-body?
  [body assigned?]
  (reduce (fn [acc stmt]
            (result-definitely-assigned-after-stmt stmt acc))
          assigned?
          body))

(declare body-may-complete-normally?)

(defn- stmt-may-complete-normally?
  [stmt]
  (case (:type stmt)
    :raise false
    ;; 'retry' transfers control back to the start of the protected body, so the
    ;; rescue clause containing it does not fall through to its own end.
    :retry false
    :if (let [branch-outs (concat
                           [(body-may-complete-normally? (:then stmt))]
                           (map #(body-may-complete-normally? (:then %)) (:elseif stmt))
                           [(if (:else stmt)
                              (body-may-complete-normally? (:else stmt))
                              true)])]
          (some true? branch-outs))
    :case (let [clause-outs (map #(body-may-complete-normally? (:body %)) (:clauses stmt))
                else-out (if-let [else-body (:else stmt)]
                           (body-may-complete-normally? else-body)
                           true)]
            (some true? (concat clause-outs [else-out])))
    :match (let [clause-outs (map #(body-may-complete-normally? (:body %)) (:clauses stmt))
                 else-out (if-let [else-body (:else stmt)]
                            (body-may-complete-normally? else-body)
                            true)]
             (some true? (concat clause-outs [else-out])))
    :scoped-block (or (body-may-complete-normally? (:body stmt))
                      (when-let [rescue-body (:rescue stmt)]
                        (body-may-complete-normally? rescue-body)))
    :with (body-may-complete-normally? (:body stmt))
    ;; Be conservative for constructs whose runtime completion depends on data/coordination.
    :loop true
    :select true
    true))

(defn- body-may-complete-normally?
  [body]
  (loop [stmts body]
    (if-let [stmt (first stmts)]
      (if (stmt-may-complete-normally? stmt)
        (recur (rest stmts))
        false)
      true)))

;; -----------------------------------------------------------------------------
;; Static structural restrictions (the Definition's "Syntactic Restrictions").
;; Diagnosed here, before evaluation, so a violation is a compile-time error even
;; on a code path that never executes.
;; -----------------------------------------------------------------------------

(defn- first-duplicate
  "The first value that occurs more than once in `coll`, or nil."
  [coll]
  (let [r (reduce (fn [seen x] (if (contains? seen x) (reduced x) (conj seen x)))
                  #{} coll)]
    (when-not (set? r) r)))

(defn- restriction-error!
  [msg]
  (throw (ex-info msg {:error (type-error msg)})))

(defn- check-distinct-parameters!
  "No two parameters of one routine may bind the same identifier."
  [params kind routine-name]
  (when-let [dup (first-duplicate (map :name params))]
    (restriction-error!
     (str "Duplicate parameter '" dup "' in " kind " '" routine-name
          "'. The parameters of a routine must have distinct names."))))

(defn- check-distinct-fields!
  "No two fields of one class may bind the same identifier."
  [class-name body]
  (let [field-names (->> body
                         (filter #(= :feature-section (:type %)))
                         (mapcat :members)
                         (filter #(= :field (:type %)))
                         (map :name))]
    (when-let [dup (first-duplicate field-names)]
      (restriction-error!
       (str "Duplicate field '" dup "' in class '" class-name
            "'. The fields of a class must have distinct names.")))))

(defn- collect-old-nodes
  "All `old` expression nodes within an AST fragment."
  [node]
  (cond
    (sequential? node) (mapcat collect-old-nodes node)
    (map? node) (if (= :old (:type node))
                  (cons node (collect-old-nodes (vals (dissoc node :type))))
                  (collect-old-nodes (vals (dissoc node :type))))
    :else nil))

(defn- collect-illegal-retry
  "`retry` nodes that are not enclosed in a rescue block."
  [node in-rescue?]
  (cond
    (sequential? node) (mapcat #(collect-illegal-retry % in-rescue?) node)
    (map? node)
    (case (:type node)
      :retry (when-not in-rescue? [node])
      ;; A nested `do ... rescue ... end`: its rescue arm is a valid retry context.
      :scoped-block (concat (collect-illegal-retry (:body node) in-rescue?)
                            (collect-illegal-retry (:rescue node) true))
      ;; Closures and spawn bodies start a fresh routine context.
      (:anonymous-function :spawn) nil
      (mapcat #(collect-illegal-retry % in-rescue?) (vals (dissoc node :type))))
    :else nil))

(defn- check-old-and-retry!
  "`old` may appear only in an `ensure` clause and must denote a field, not a
   parameter; `retry` may appear only inside a `rescue` block."
  [kind routine-name params require body ensure]
  (when (some #(seq (collect-old-nodes %)) (cons body (map :condition require)))
    (restriction-error!
     (str "'old' may appear only in an ensure (postcondition) clause; found it "
          "outside one in " kind " '" routine-name "'.")))
  (let [param-names (set (map :name params))]
    (doseq [assertion ensure
            o (collect-old-nodes (:condition assertion))]
      (let [e (:expr o)]
        (when (and (map? e) (= :identifier (:type e))
                   (contains? param-names (:name e)))
          (restriction-error!
           (str "'old' may not be applied to the parameter '" (:name e) "' in "
                kind " '" routine-name "'; it must denote a field of the current object."))))))
  (when (seq (concat (mapcat #(collect-illegal-retry (:condition %) false) require)
                     (collect-illegal-retry body false)
                     (mapcat #(collect-illegal-retry (:condition %) false) ensure)))
    (restriction-error!
     (str "'retry' may appear only inside a rescue block; found it elsewhere in "
          kind " '" routine-name "'."))))

(defn check-method
  "Check a method definition"
  [env class-name {:keys [name params return-type require body ensure rescue] :as method}]
  (check-distinct-parameters! params "routine" name)
  (check-old-and-retry! "routine" name params require body ensure)
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

    ;; Add parameters to method environment. Expand type aliases so a parameter
    ;; declared with an alias type (e.g. `f: Transformer`) resolves its methods.
    (doseq [param params]
      (env-add-var method-env (:name param)
                   (expand-type-aliases env (or (:type param) "Any"))))

    ;; Add Result variable for return type
    (when return-type
      (let [rt (expand-type-aliases env return-type)]
        (env-add-var method-env "Result" rt)
        (env-add-var method-env "result" rt)))

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

    ;; Check rescue clause
    (when rescue
      (let [rescue-env (make-type-env method-env)]
        (env-add-var rescue-env "exception" "Any")
        (doseq [stmt rescue]
          (check-statement rescue-env stmt))))

    (let [normal-path-returns? (body-may-complete-normally? body)
          rescue-path-returns? (when rescue (body-may-complete-normally? rescue))
          normal-path-inits? (result-definitely-assigned-in-body? body false)
          rescue-path-inits? (when rescue (result-definitely-assigned-in-body? rescue false))]
    (when (and return-type
               (attached-non-scalar-type? return-type)
               (or (and normal-path-returns? (not normal-path-inits?))
                   (and rescue-path-returns? (not rescue-path-inits?))))
      (throw (ex-info (str "Method " name " does not initialize result")
                      {:error (type-error
                               (str "Method '" name "' declares return type "
                                    (display-type return-type)
                                    " but does not definitely assign result on all returning paths. "
                                    "Use 'result :=' or declare the return type detachable."))})))
    )

    ;; Check postconditions
    (doseq [assertion ensure]
      (let [cond-type (check-expression method-env (:condition assertion))]
        (when-not (= cond-type "Boolean")
          (throw (ex-info (str "Postcondition must be Boolean in method " name)
                          {:error (type-error
                                   (str "Postcondition must be Boolean, got " cond-type))})))))))

(defn check-constructor
  "Check a constructor definition"
  [env class-name {:keys [name params require body ensure rescue] :as constructor}]
  (check-distinct-parameters! params "constructor" name)
  (check-old-and-retry! "constructor" name params require body ensure)
  (let [ctor-env (make-type-env env)]
    ;; Track current class for this/super resolution
    (env-add-var ctor-env "__current_class__" class-name)
    ;; Mark constructor context so once-field writes are permitted
    (env-add-var ctor-env "__in_constructor__" true)

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

(defn- substitute-method-types
  "Apply a generic substitution map to a method member's parameter and return
   types, so an inherited routine's signature is expressed in the heir's type
   context."
  [member subst]
  (if (empty? subst)
    member
    (-> member
        (update :params (fn [ps]
                          (mapv (fn [p]
                                  (if (:type p)
                                    (update p :type #(resolve-generic-type % subst))
                                    p))
                                ps)))
        (update :return-type #(when % (resolve-generic-type % subst))))))

(defn- inherited-method-member
  "Walk the parent chain and return the nearest ancestor's method member (a
   feature-member map) whose name and arity match, with its parameter and return
   types substituted into the heir's type context by resolving the generic type
   arguments supplied on each `inherit` clause. Returns nil if the routine is not
   inherited. This is the routine an override redefines."
  [env parents method-name arity]
  (letfn [(search [parent-entry subst visited]
            (let [cn (:parent parent-entry)]
              (when (and (string? cn) (not (contains? visited cn)))
                (let [class-def (env-lookup-class env cn)
                      ;; Map this class's own generic params to heir-context types,
                      ;; resolving the inherit-clause arguments through the
                      ;; substitution accumulated from classes below it.
                      args (mapv #(resolve-generic-type % subst)
                                 (or (:generic-args parent-entry) []))
                      subst' (or (build-generic-type-map env {:base-type cn :type-args args}) {})
                      visited' (conj visited cn)
                      own (when class-def
                            (some (fn [member]
                                    (when (and (= (:type member) :method)
                                               (= (:name member) method-name)
                                               (= (count (or (:params member) [])) arity))
                                      member))
                                  (feature-members class-def)))]
                  (or (when own (substitute-method-types own subst'))
                      (when class-def
                        (some (fn [pe] (search pe subst' visited'))
                              (:parents class-def))))))))]
    (some (fn [pe] (search pe {} #{})) parents)))

(defn- check-override-conformance
  "Enforce CONTRAVARIANT parameters and COVARIANT return for a method that
   overrides an inherited routine of the same name and arity. The inherited
   signature is first substituted into the heir's type context (so a method
   inherited from, say, Container[Integer] is compared with T resolved to
   Integer). A comparison is skipped only when, after that substitution, a type
   is still an unresolved generic parameter of the heir itself."
  [env class-name parents member]
  (when (and (= (:type member) :method) (seq parents))
    (let [m-name (:name member)
          m-params (or (:params member) [])
          arity (count m-params)
          parent-m (inherited-method-member env parents m-name arity)
          concrete? (fn [t] (and t (not (is-generic-type-param? env t))))]
      (when parent-m
        ;; Parameters are contravariant: each inherited parameter type must
        ;; conform to the overriding parameter type (the override must accept at
        ;; least what the inherited routine accepted).
        (doseq [[idx pp cp] (map vector (range) (or (:params parent-m) []) m-params)]
          (let [pt (:type pp) ct (:type cp)]
            (when (and pt ct (concrete? pt) (concrete? ct)
                       (not (types-compatible? env pt ct)))
              (throw (ex-info (str "Invalid override of '" m-name "'")
                              {:error (type-error
                                       (str "Override of '" m-name "' in class '" class-name
                                            "' narrows parameter " (inc idx) " from "
                                            (display-type pt) " to " (display-type ct)
                                            ". Parameters are contravariant: an overriding routine must "
                                            "accept at least what the inherited one accepts. Keep the wider "
                                            "type and narrow inside with convert/match, or use generics."))})))))
        ;; Return is covariant: the overriding return type must conform to the
        ;; inherited return type.
        (let [pr (:return-type parent-m) cr (:return-type member)]
          (when (and pr cr (concrete? pr) (concrete? cr)
                     (not (types-compatible? env cr pr)))
            (throw (ex-info (str "Invalid override of '" m-name "'")
                            {:error (type-error
                                     (str "Override of '" m-name "' in class '" class-name
                                          "' changes the return type from " (display-type pr)
                                          " to " (display-type cr) ", which does not conform. "
                                          "Returns are covariant: the overriding return type must "
                                          "conform to the inherited one."))}))))))))

(defn- class-defines-method?
  "True when the class body itself declares a method of the given name."
  [class-def method-name]
  (boolean
   (some (fn [section]
           (and (= (:type section) :feature-section)
                (some (fn [member]
                        (and (= (:type member) :method)
                             (= (:name member) method-name)))
                      (:members section))))
         (:body class-def))))

(defn- check-equals-hash-consistency
  "Equality and hashing must agree: equal objects must hash equal. Warn when a
   class redefines one of `equals`/`hash` without the other, since such a class
   misbehaves as a Set element or Map key."
  [env class-name class-def]
  (let [has-equals (class-defines-method? class-def "equals")
        has-hash (class-defines-method? class-def "hash")]
    (cond
      (and has-equals (not has-hash))
      (env-add-warning env
                       (str "Class '" class-name "' overrides 'equals' but not 'hash'. "
                            "A class that redefines equality should also redefine 'hash' so "
                            "that equal objects hash equal; otherwise it misbehaves as a Set "
                            "element or Map key."))

      (and has-hash (not has-equals))
      (env-add-warning env
                       (str "Class '" class-name "' overrides 'hash' but not 'equals'. "
                            "A custom 'hash' is only meaningful alongside a matching 'equals'.")))))

(defn check-class
  "Check a class definition"
  [env {:keys [name body invariant parents generic-params] :as class-def}]
  (let [class-def (or (env-lookup-class env name) class-def)
        body (:body class-def)
        invariant (:invariant class-def)
        parents (:parents class-def)
        class-env (make-type-env env)]
  (check-distinct-fields! name (:body class-def))
  (check-equals-hash-consistency env name class-def)
  (env-add-var class-env "__current_class__" name)
  (register-generic-param-classes! class-env generic-params)
  (bind-visible-class-fields! class-env env name)
  ;; A sealed class must be deferred. If it could be instantiated, a value of
  ;; the bare parent type would slip past an exhaustive `match` over its
  ;; subclasses (the very guarantee `sealed` exists to provide), failing at
  ;; runtime with no compile-time warning.
  (when (and (:sealed? class-def) (not (:deferred? class-def)))
    (throw (ex-info (str "Sealed class " name " must be deferred")
                    {:error (type-error
                             (str "Sealed class '" name "' must be declared 'sealed deferred'. "
                                  "A sealed class cannot be instantiated; otherwise an exhaustive "
                                  "match over its subclasses would not cover a bare " name " value."))})))
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
          (do
            (check-override-conformance env name parents member)
            (when-not (:declaration-only? member)
              (check-method class-env name member)))
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
            :match (reduce set/union #{}
                           (concat (mapcat #(map collect-assigned (:body %)) (:clauses stmt))
                                   (map collect-assigned (:else stmt))))
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
           "hash" {:params [] :return-type "Integer"}
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
  (doseq [scalar ["String" "Integer" "Real" "Boolean" "Char"]]
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
           "to_integer64" {:params [] :return-type "Integer"}
           "to_real" {:params [] :return-type "Real"}
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
           "to_integer64" {:params [] :return-type "Integer"}
           "to_real" {:params [] :return-type "Real"}
           "abs" {:params [] :return-type "Real"}
           "min" {:params [{:name "other" :type "Real"}] :return-type "Real"}
           "max" {:params [{:name "other" :type "Real"}] :return-type "Real"}
           "round"    {:params [] :return-type "Integer"}
           "to_fixed" {:params [{:name "places" :type "Integer"}] :return-type "Real"}
           "is_nan" {:params [] :return-type "Boolean"}
           "is_infinite" {:params [] :return-type "Boolean"}
           "is_finite" {:params [] :return-type "Boolean"}
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

  (env-add-method env "Integer" "to_char" {:params [] :return-type "Char"})

  (doseq [[method-name sig]
          {"to_string"   {:params [] :return-type "String"}
           "to_upper"    {:params [] :return-type "String"}
           "to_lower"    {:params [] :return-type "String"}
           "to_integer"  {:params [] :return-type "Integer"}}]
    (env-add-method env "Char" method-name sig))

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
           "to_integer64" {:params [] :return-type "Integer"}
           "to_real"     {:params [] :return-type "Real"}
           "contains"    {:params [{:name "substr" :type "String"}] :return-type "Boolean"}
           "starts_with" {:params [{:name "prefix" :type "String"}] :return-type "Boolean"}
           "ends_with"   {:params [{:name "suffix" :type "String"}] :return-type "Boolean"}
           "trim"        {:params [] :return-type "String"}
           "replace"     {:params [{:name "old" :type "String"} {:name "new" :type "String"}] :return-type "String"}
           "pad_end"     {:params [{:name "pad" :type "String"} {:name "count" :type "Integer"}] :return-type "String"}
           "pad_start"   {:params [{:name "pad" :type "String"} {:name "count" :type "Integer"}] :return-type "String"}
           "replicate"   {:params [{:name "n" :type "Integer"}] :return-type "String"}
           "char_at"     {:params [{:name "index" :type "Integer"}] :return-type "Char"}
           "chars"       {:params [] :return-type {:base-type "Array" :type-params ["Char"]}}
           "to_bytes"    {:params [] :return-type {:base-type "Array" :type-params ["Integer"]}}
           "compare"     {:params [{:name "a" :type "Any"}] :return-type "Integer"}
           "hash"        {:params [] :return-type "Integer"}
           "split"       {:params [{:name "delimiter" :type "String"}]
                          :return-type {:base-type "Array" :type-params ["String"]}}
           "join"        {:params [{:name "parts" :type {:base-type "Array" :type-params ["String"]}}]
                          :return-type "String"}}]
    (env-add-method env "String" method-name sig))
  (doseq [[method-name sig]
          {"print" {:params [{:name "msg" :type "String"}] :return-type "Void"}
           "print_line" {:params [{:name "msg" :type "String"}] :return-type "Void"}
           "error" {:params [{:name "msg" :type "String"}] :return-type "Void"}
           "new_line" {:params [] :return-type "Void"}
           "flush" {:params [] :return-type "Void"}
           "read_integer" {:params [] :return-type "Integer"}
           "read_real" {:params [] :return-type "Real"}}]
    (env-add-class env "Console" {:name "Console"
                                  :generic-params nil})
    (env-add-method env "Console" method-name sig))
  (env-add-method env "Console" "read_line"
                  {:params [] :return-type "String"})
  (env-add-method env "Console" "read_line"
                  {:params [{:name "prompt" :type "String"}] :return-type "String"})
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
    (env-add-class env "Process" {:name "Process"
                                  :generic-params nil})
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
           "add_at"      {:params [{:name "index" :type "Integer"} {:name "value" :type "T"}] :return-type "Void"}
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
           "take"        {:params [{:name "n" :type "Integer"}]
                          :return-type {:base-type "Array" :type-params ["T"]}}
           "drop"        {:params [{:name "n" :type "Integer"}]
                          :return-type {:base-type "Array" :type-params ["T"]}}
           "take_last"   {:params [{:name "n" :type "Integer"}]
                          :return-type {:base-type "Array" :type-params ["T"]}}
           "drop_last"   {:params [{:name "n" :type "Integer"}]
                          :return-type {:base-type "Array" :type-params ["T"]}}
           "concat"      {:params [{:name "other" :type {:base-type "Array" :type-params ["T"]}}]
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
           "to_array"             {:params [] :return-type {:base-type "Array" :type-params ["T"]}}
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
                  {:params [{:name "initial" :type "Integer"}]
                   :return-type "Atomic_Integer64"})
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
  ([{:keys [classes calls statements imports functions type-aliases duplicate-functions
            function-signature-conflicts] :as program} opts]
   (binding [*strict-undefined-targets* (boolean (:strict-undefined-targets? opts))]
   (let [env (make-type-env)
         normalized-functions (normalize-function-defs classes functions)
         visible-classes (class-defs-by-name-last-wins
                          (vec (concat classes (function-class-defs normalized-functions))))
         ;; Names whose bodies should be collected for resolution but not re-checked
         ;; (used by the REPL to avoid re-validating previously defined code).
         skip-body-names (or (:skip-class-body-names opts) #{})]
     (try
       ;; Reject duplicate free-function definitions before they are collapsed
       ;; last-wins (which would otherwise make the earlier definition silently
       ;; vanish and surface later as an obscure "Method not found: callN").
       (when-let [dup (first duplicate-functions)]
         (throw (ex-info (str "Duplicate function definition: " dup)
                         {:error (type-error
                                  (str "Function '" dup "' is defined more than once. "
                                       "Free-function names must be unique within a program; "
                                       "a later definition would silently replace the earlier one. "
                                       "Rename or remove the duplicate."))})))

       ;; A `declare function` signature must be matched exactly by its later
       ;; definition (the declaration is collapsed away, so an unchecked
       ;; mismatch would silently take the definition's signature).
       (when-let [conflict (first function-signature-conflicts)]
         (throw (ex-info (:message conflict)
                         {:error (type-error (:message conflict))})))

       ;; Register imported Java classes (as placeholders)
       (doseq [{:keys [qualified-name source]} imports]
         (when (nil? source)
           (let [simple-name (last (str/split qualified-name #"\."))]
             (env-add-class env simple-name {:name simple-name :body [] :import qualified-name}))))

       (register-builtin-methods env)

       ;; Register type aliases first so they are available throughout the program.
       (doseq [{:keys [name type-expr]} (or type-aliases [])]
         (env-add-type-alias env name type-expr))

       ;; First pass: collect all class definitions, allowing user classes to
       ;; override builtin placeholder names such as Task or Channel.
       (doseq [class-def visible-classes]
         (collect-class-info env class-def))

       ;; Inject pre-existing variable types (e.g., from REPL). Expand any type
       ;; aliases so a variable declared with an alias type (e.g. a REPL `let m:
       ;; Matrix := ...`) resolves its methods on later inputs.
       (doseq [[var-name var-type] (:var-types opts)]
         (env-add-var env var-name (expand-type-aliases env var-type)))

       ;; Register function variables (name -> generated class)
       (doseq [fn-def normalized-functions]
         (let [arity (count (:params fn-def))]
           (when (> arity 32)
             (throw (ex-info (str "Function " (:name fn-def)
                                  " must have at most 32 parameters")
                             {:error (type-error
                                      (str "Function " (:name fn-def)
                                           " must have at most 32 parameters"))}))))
         (env-add-var env (:name fn-def) (:class-name fn-def)))

       ;; Second pass: check class bodies, including normalized function classes.
       (doseq [class-def visible-classes]
         (when-not (contains? skip-body-names (:name class-def))
           (check-class env class-def)))

       ;; Check top-level statements in source order when available.
       ;; Fall back to legacy :calls-only programs.
       (if (seq statements)
         (doseq [stmt statements]
           (check-statement env stmt))
         (doseq [call calls]
           (check-expression env call)))

       {:success true
        :errors []
        :warnings (vec @(:warnings env))}

       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (let [error-data (ex-data e)]
           {:success false
            :errors [(or (:error error-data)
                        (type-error (ex-message e)))]
            :warnings (vec @(:warnings env))})))))))

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
    (let [env (make-type-env)
          normalized-functions (normalize-function-defs (:classes opts) (:functions opts))
          function-classes (vec (function-class-defs normalized-functions))
          visible-classes (vec (concat (:classes opts) function-classes))
          visible-var-types (or (:var-types opts) {})]
      (doseq [{:keys [qualified-name source]} (:imports opts)]
        (when (nil? source)
          (let [simple-name (last (str/split qualified-name #"\."))]
            (env-add-class env simple-name {:name simple-name :body [] :import qualified-name}))))
      (register-builtin-methods env)
      (doseq [class-def visible-classes]
        (collect-class-info env class-def))
      (register-visible-generic-classes! env visible-classes visible-var-types)
      (doseq [fn-def normalized-functions]
        (env-add-var env (:name fn-def) (:class-name fn-def)))
      (doseq [[var-name var-type] visible-var-types]
        (env-add-var env var-name var-type))
      (check-expression env expr))
    (catch #?(:clj Exception :cljs :default) _ nil)))
