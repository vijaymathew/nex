(ns nex.compiler.jvm.repl
  "Experimental compiled REPL execution path for a narrow expression subset."
  (:require [clojure.set :as set]
            [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.descriptor :as desc]
            [nex.compiler.jvm.emit :as emit]
            [nex.compiler.jvm.runtime :as rt]
            [nex.interpreter :as interp]
            [nex.lower :as lower]
            [nex.typechecker :as tc])
  (:import [java.util HashMap]))

(defn- clone-hash-map
  [m]
  (let [copy (HashMap.)]
    (doseq [[k v] m]
      (.put copy k v))
    copy))

(defn make-session
  []
  (let [ldr (loader/make-loader)]
    {:loader ldr
     :state (rt/make-repl-state ldr)
     :counter (atom 0)
     :compiled-classes (atom {})
     :function-asts (atom {})
     :class-asts (atom {})
     :import-asts (atom [])
     :intern-asts (atom [])}))

(defn reset-session
  []
  (make-session))

(def ^:private relational-ops
  #{"=" "/=" "<" "<=" ">" ">="})

(def ^:private builtin-function-names
  (set (keys interp/builtins)))

(declare supported-expr-in-ctx?)
(declare supported-stmt-in-ctx?)
(declare session-var-types)
(declare builtin-target-call-in-ctx?)
(declare user-target-call-in-ctx?)
(declare advance-eligibility-ctx)
(declare supported-stmt-block-with-ctx?)
(declare supported-convert-in-ctx?)

(def ^:private builtin-runtime-receiver-types
  #{"Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Cursor" "Task" "Channel" "Console" "Process"})

(defn- base-type-name
  [t]
  (cond
    (string? t) t
    (map? t) (:base-type t)
    :else nil))

(defn- normalize-call-target
  [target]
  (if (string? target)
    {:type :identifier :name target}
    target))

(defn- compiled-class-names
  [session]
  (set (keys @(:compiled-classes session))))

(defn- user-class-defs
  [ast]
  (let [synthetic-class-names (set (keep :class-name (:functions ast)))]
    (remove #(contains? synthetic-class-names (:name %))
            (:classes ast))))

(defn- supported-if-branches?
  [ctx branch]
  (and (= 1 (count branch))
       (supported-expr-in-ctx? ctx (first branch))))

(defn- supported-stmt-block?
  [ctx statements]
  (loop [ctx' ctx
         remaining statements]
    (if-let [stmt (first remaining)]
      (when (supported-stmt-in-ctx? ctx' stmt)
        (recur (advance-eligibility-ctx ctx' stmt) (rest remaining)))
      ctx')))

(defn- supported-stmt-block-with-ctx?
  [ctx statements]
  (supported-stmt-block? ctx statements))

(defn- infer-type-in-ctx
  [ctx expr]
  (tc/infer-expression-type expr {:classes (:classes ctx)
                                  :functions (:functions ctx)
                                  :imports (:imports ctx)
                                  :var-types (:var-types ctx)}))

(defn- builtin-target-call-in-ctx?
  [ctx expr]
  (let [target-expr (normalize-call-target (:target expr))]
    (and target-expr
         (supported-expr-in-ctx? ctx target-expr)
         (every? #(supported-expr-in-ctx? ctx %) (:args expr))
         (contains? builtin-runtime-receiver-types
                    (base-type-name (infer-type-in-ctx ctx target-expr))))))

(defn- class-method-in-ctx
  [ctx class-name method-name arity]
  (let [class-map (into {} (map (juxt :name identity) (:classes ctx)))]
    (letfn [(lookup-method [cn visited]
              (when (and cn (not (contains? visited cn)))
                (let [class-def (get class-map cn)
                      visited' (conj visited cn)
                      local-method (some #(when (and (= :method (:type %))
                                                     (= method-name (:name %))
                                                     (= arity (count (or (:params %) []))))
                                            %)
                                         (mapcat :members
                                                 (filter #(= :feature-section (:type %))
                                                         (:body class-def))))]
                  (or local-method
                      (some #(lookup-method (:parent %) visited')
                            (:parents class-def))))))]
      (lookup-method class-name #{}))))

(defn- user-target-call-in-ctx?
  [ctx expr]
  (let [raw-target (:target expr)
        class-target-def (when (string? raw-target)
                           (some #(when (= (:name %) raw-target) %) (:classes ctx)))
        target-expr (normalize-call-target raw-target)
        target-type (when (and target-expr (not class-target-def))
                      (infer-type-in-ctx ctx target-expr))
        base (or (:name class-target-def) (base-type-name target-type))
        class-def (or class-target-def
                      (some #(when (= (:name %) base) %) (:classes ctx)))
        field-name (:method expr)
        field-def (when (and class-def (false? (:has-parens expr)))
                    (some #(when (and (= :field (:type %))
                                      (if class-target-def
                                        (:constant? %)
                                        true)
                                      (= field-name (:name %)))
                             %)
                          (mapcat :members
                                  (filter #(= :feature-section (:type %))
                                          (:body class-def)))))
        method-def (when class-def
                     (class-method-in-ctx ctx (:name class-def) (:method expr) (count (:args expr))))]
    (and (or class-target-def target-expr)
         (or class-target-def (supported-expr-in-ctx? ctx target-expr))
         (contains? (:compiled-class-names ctx) base)
         (or field-def method-def))))

(defn- class-constructors-in-ctx
  [ctx class-name]
  (mapcat (fn [section]
            (when (= :constructors (:type section))
              (:constructors section)))
          (:body (some #(when (= (:name %) class-name) %) (:classes ctx)))))

(defn- known-constructor-in-ctx?
  [ctx class-name constructor-name arity]
  (let [class-map (into {} (map (juxt :name identity) (:classes ctx)))]
    (letfn [(lookup-ctor [cn visited]
              (when (and cn (not (contains? visited cn)))
                (let [class-def (get class-map cn)
                      visited' (conj visited cn)]
                  (or (some #(when (and (= (:name %) constructor-name)
                                        (= (count (or (:params %) [])) arity))
                               %)
                            (class-constructors-in-ctx ctx cn))
                      (some #(lookup-ctor (:parent %) visited')
                            (:parents class-def))))))]
      (lookup-ctor class-name #{}))))

(defn- class-def-in-ctx
  [ctx class-name]
  (some #(when (= (:name %) class-name) %) (:classes ctx)))

(defn normalize-program-ast
  "Normalize legacy program shapes so the compiled REPL path can reason about
   one top-level statement stream. Older ASTs may carry top-level calls only in
   :calls; newer parser output mirrors them into :statements as well."
  [ast]
  (if (and (= :program (:type ast))
           (empty? (:statements ast))
           (seq (:calls ast)))
    (assoc ast :statements (vec (:calls ast)))
    ast))

(defn- initial-eligibility-ctx
  [session ast]
  (let [actual-classes (vec (user-class-defs ast))]
    {:known-vars (set (concat (keys (session-var-types session))
                            (keys @(:values (:state session)))))
     :known-fns (set (concat builtin-function-names
                           (keys @(:function-asts session))
                           (map :name (:functions ast))))
     :var-types (merge (session-var-types session)
                     (into {}
                           (map (fn [fn-def]
                                  [(:name fn-def) (:class-name fn-def)]))
                           (concat (vals @(:function-asts session)) (:functions ast))))
     :functions (vec (concat (vals @(:function-asts session)) (:functions ast)))
     :classes (vec (concat (vals @(:class-asts session)) actual-classes))
     :compiled-class-names (set (concat (compiled-class-names session)
                                        (map :name actual-classes)))
     :imports @(:import-asts session)
     :retry-allowed? false}))

(defn supported-expr-in-ctx?
  [ctx expr]
  (case (:type expr)
    :integer true
    :real true
    :string true
    :char true
    :boolean true
    :nil true
    :array-literal (every? #(supported-expr-in-ctx? ctx %) (:elements expr))
    :map-literal (every? (fn [{:keys [key value]}]
                           (and (supported-expr-in-ctx? ctx key)
                                (supported-expr-in-ctx? ctx value)))
                         (:entries expr))
    :set-literal (every? #(supported-expr-in-ctx? ctx %) (:elements expr))
    :create (let [class-def (class-def-in-ctx ctx (:class-name expr))]
              (and (contains? (:compiled-class-names ctx) (:class-name expr))
                   class-def
                   (not (:deferred? class-def))
                   (every? #(supported-expr-in-ctx? ctx %) (:args expr))
                   (if-let [ctor (:constructor expr)]
                     (known-constructor-in-ctx? ctx (:class-name expr) ctor (count (:args expr)))
                     (empty? (:args expr)))))
    :identifier (contains? (:known-vars ctx) (:name expr))
    :call (if (nil? (:target expr))
            (and (every? #(supported-expr-in-ctx? ctx %) (:args expr))
                 (or (and (empty? (:args expr))
                          (not (:has-parens expr)))
                     (contains? (:known-fns ctx) (:method expr))))
            (or (builtin-target-call-in-ctx? ctx expr)
                (user-target-call-in-ctx? ctx expr)))
    :binary (and (contains? (into #{"+" "-" "*" "/" "%" "^" "and" "or"} relational-ops) (:operator expr))
                 (supported-expr-in-ctx? ctx (:left expr))
                 (supported-expr-in-ctx? ctx (:right expr)))
    :unary (supported-expr-in-ctx? ctx (:expr expr))
    :old (supported-expr-in-ctx? ctx (:expr expr))
    :if (and ((if (= :convert (:type (:condition expr)))
                supported-convert-in-ctx?
                supported-expr-in-ctx?)
              ctx
              (:condition expr))
             (supported-if-branches? ctx (:then expr))
             (if-let [clause (first (:elseif expr))]
               (supported-expr-in-ctx?
                ctx
                {:type :if
                 :condition (:condition clause)
                 :then (:then clause)
                 :elseif (vec (rest (:elseif expr)))
                 :else (:else expr)})
               (supported-if-branches? ctx (:else expr))))
    :when (and ((if (= :convert (:type (:condition expr)))
                  supported-convert-in-ctx?
                  supported-expr-in-ctx?)
                ctx
                (:condition expr))
               (supported-expr-in-ctx? ctx (:consequent expr))
               (supported-expr-in-ctx? ctx (:alternative expr)))
    false))

(defn supported-convert-in-ctx?
  [ctx expr]
  (and (= :convert (:type expr))
       (supported-expr-in-ctx? ctx (:value expr))
       (let [target-type (:target-type expr)
             base (if (map? target-type) (:base-type target-type) target-type)]
         (and (string? base)
              (not (contains? #{"T" "U" "V" "K" "KEY" "VALUE"} base))
              true))))

(defn supported-stmt-in-ctx?
  [ctx stmt]
  (case (:type stmt)
    :let (if (= :convert (:type (:value stmt)))
           (supported-convert-in-ctx? ctx (:value stmt))
           (supported-expr-in-ctx? ctx (:value stmt)))
    :assign (and (string? (:target stmt))
                 (contains? (:known-vars ctx) (:target stmt))
                 (supported-expr-in-ctx? ctx (:value stmt)))
    :member-assign (and (supported-expr-in-ctx? ctx (or (:object stmt) {:type :this}))
                        (supported-expr-in-ctx? ctx (:value stmt)))
    :call (supported-expr-in-ctx? ctx stmt)
    :convert (supported-convert-in-ctx? ctx stmt)
    :if (let [cond-ctx (if (= :convert (:type (:condition stmt)))
                         (-> ctx
                             (update :known-vars conj (:var-name (:condition stmt)))
                             (assoc-in [:var-types (:var-name (:condition stmt))]
                                       (tc/detachable-version (:target-type (:condition stmt)))))
                         ctx)]
          (and ((if (= :convert (:type (:condition stmt)))
                  supported-convert-in-ctx?
                  supported-expr-in-ctx?)
                ctx
                (:condition stmt))
               (supported-stmt-block? cond-ctx (:then stmt))
             (if-let [clause (first (:elseif stmt))]
               (supported-stmt-in-ctx?
                ctx
                {:type :if
                 :condition (:condition clause)
                 :then (:then clause)
                 :elseif (vec (rest (:elseif stmt)))
                 :else (:else stmt)})
               (supported-stmt-block? ctx (or (:else stmt) [])))))
    :case (and (supported-expr-in-ctx? ctx (:expr stmt))
               (every? #(every? (partial supported-expr-in-ctx? ctx) (:values %))
                       (:clauses stmt))
               (every? #(supported-stmt-in-ctx? ctx (:body %)) (:clauses stmt))
               (or (nil? (:else stmt))
                   (supported-stmt-in-ctx? ctx (:else stmt))))
    :raise (supported-expr-in-ctx? ctx (:value stmt))
    :retry (:retry-allowed? ctx)
    :loop (let [[init-ok? ctx-after-init]
                (reduce (fn [[ok? c] init-stmt]
                          (if (and ok? (supported-stmt-in-ctx? c init-stmt))
                            [true (advance-eligibility-ctx c init-stmt)]
                            (reduced [false c])))
                        [true ctx]
                        (:init stmt))]
            (and init-ok?
                 (every? #(supported-expr-in-ctx? ctx-after-init (:condition %))
                         (:invariant stmt))
                 (or (nil? (:variant stmt))
                     (supported-expr-in-ctx? ctx-after-init (:variant stmt)))
                 (supported-expr-in-ctx? ctx-after-init (:until stmt))
                 (boolean (supported-stmt-block? ctx-after-init (:body stmt)))))
    :scoped-block (if (:rescue stmt)
                    (and (supported-stmt-block? ctx (:body stmt))
                         (supported-stmt-block-with-ctx? (-> ctx
                                                             (assoc :retry-allowed? true)
                                                             (update :known-vars conj "exception")
                                                             (assoc-in [:var-types "exception"] "Any"))
                                                         (:rescue stmt)))
                    (and (nil? (:rescue stmt))
                         (supported-stmt-block? ctx (:body stmt))))
    (supported-expr-in-ctx? ctx stmt)))

(defn- advance-eligibility-ctx
  [ctx stmt]
  (case (:type stmt)
    :let (let [ctx' (if (= :convert (:type (:value stmt)))
                      (-> ctx
                          (update :known-vars conj (:var-name (:value stmt)))
                          (assoc-in [:var-types (:var-name (:value stmt))]
                                    (tc/detachable-version (:target-type (:value stmt)))))
                      ctx)
               nex-type (or (:var-type stmt)
                            (infer-type-in-ctx ctx' (:value stmt)))]
           (-> ctx'
               (update :known-vars conj (:name stmt))
               (assoc-in [:var-types (:name stmt)] nex-type)))
    :convert (-> ctx
                 (update :known-vars conj (:var-name stmt))
                 (assoc-in [:var-types (:var-name stmt)]
                           (tc/detachable-version (:target-type stmt))))
    ctx))

(defn eligible-ast?
  [session ast]
  (let [ast' (normalize-program-ast ast)
        actual-class-names (set (map :name (user-class-defs ast')))
        initial-ctx (initial-eligibility-ctx session ast')]
    (and (= :program (:type ast'))
         (empty? (:imports ast))
         (empty? (:interns ast))
         (empty? (set/intersection (compiled-class-names session) actual-class-names))
         (or (seq (:functions ast'))
             (seq (:statements ast'))
             (seq (:classes ast')))
         (reduce (fn [ctx stmt]
                   (when (and ctx (supported-stmt-in-ctx? ctx stmt))
                     (advance-eligibility-ctx ctx stmt)))
                 initial-ctx
                 (:statements ast')))))

(defn- next-class-name!
  [session]
  (format "nex/repl/Expr_%04d" (swap! (:counter session) inc)))

(defn- reset-runtime-state!
  [state]
  (reset! (:values state) (HashMap.))
  (reset! (:types state) (HashMap.))
  (reset! (:functions state) (HashMap.))
  (rt/clear-output! state)
  state)

(defn- session-function-name-set
  [session]
  (set (keys @(:function-asts session))))

(defn- allocate-compiled-class-metadata
  [session class-defs]
  (reduce (fn [acc class-def]
            (let [internal-name (format "nex/repl/%s_%04d"
                                        (:name class-def)
                                        (swap! (:counter session) inc))]
              (assoc acc (:name class-def)
                     {:name (:name class-def)
                      :internal-name internal-name
                      :jvm-name internal-name
                      :binary-name (desc/binary-class-name internal-name)})))
          {}
          class-defs))

(defn- canonical-compiled-class-meta
  [lowered-class]
  {:name (:name lowered-class)
   :jvm-name (:jvm-name lowered-class)
   :internal-name (:internal-name lowered-class)
   :binary-name (desc/binary-class-name (:jvm-name lowered-class))
   :deferred? (boolean (:deferred? lowered-class))
   :parents (:parents lowered-class)
   :composition-fields (:composition-fields lowered-class)
   :fields (:fields lowered-class)
   :constants (:constants lowered-class)
   :constructors (:constructors lowered-class)
   :methods (:methods lowered-class)})

(defn- compile-and-register-classes!
  [session ast]
  (let [actual-classes (vec (user-class-defs ast))]
    (when (seq actual-classes)
      (let [new-class-map (allocate-compiled-class-metadata session actual-classes)
          compiled-map (merge @(:compiled-classes session) new-class-map)
          visible-functions (vec (concat (vals @(:function-asts session)) (:functions ast)))
          visible-classes (vec (concat (vals @(:class-asts session))
                                       actual-classes
                                       (keep :class-def visible-functions)))
          visible-imports @(:import-asts session)
          lowered-classes
          (mapv (fn [class-def]
                  (lower/lower-class-def class-def {:compiled-classes compiled-map
                                                    :classes visible-classes
                                                    :functions visible-functions
                                                    :imports visible-imports}))
                actual-classes)]
        (doseq [class-def actual-classes]
          (let [lowered (some #(when (= (:name %) (:name class-def)) %) lowered-classes)
                bytecode (emit/compile-user-class->bytes lowered)]
            (loader/define-class! (:loader session)
                                  (desc/binary-class-name (:jvm-name lowered))
                                  bytecode)))
        (swap! (:compiled-classes session)
               merge
               (into {}
                     (map (fn [lowered]
                            [(:name lowered) (canonical-compiled-class-meta lowered)]))
                     lowered-classes)))))
  session)

(defn- replace-metadata-atoms!
  [session metadata]
  (reset! (:function-asts session) (:functions metadata))
  (reset! (:class-asts session) (:classes metadata))
  (reset! (:import-asts session) (:imports metadata))
  (reset! (:intern-asts session) (:interns metadata))
  session)

(defn session-metadata
  [session]
  {:functions @(:function-asts session)
   :classes @(:class-asts session)
   :imports @(:import-asts session)
   :interns @(:intern-asts session)})

(defn session-var-types
  [session]
  (into {} @(:types (:state session))))

(defn- sync-var-types-from-ast!
  [session ast]
  (doseq [stmt (:statements (normalize-program-ast ast))]
    (case (:type stmt)
      :let (do
             (when (= :convert (:type (:value stmt)))
               (rt/state-set-type! (:state session)
                                   (:var-name (:value stmt))
                                   (tc/detachable-version (:target-type (:value stmt)))))
             (let [nex-type (or (:var-type stmt)
                                (tc/infer-expression-type
                                 (:value stmt)
                                 {:classes (vals @(:class-asts session))
                                  :imports @(:import-asts session)
                                  :var-types (session-var-types session)}))]
             (when nex-type
               (rt/state-set-type! (:state session) (:name stmt) nex-type))))
      :assign (when-let [nex-type (or (get (session-var-types session) (:target stmt))
                                      (tc/infer-expression-type
                                       (:value stmt)
                                       {:classes (vals @(:class-asts session))
                                        :imports @(:import-asts session)
                                        :var-types (session-var-types session)}))]
                (rt/state-set-type! (:state session) (:target stmt) nex-type))
      :convert (rt/state-set-type! (:state session)
                                   (:var-name stmt)
                                   (tc/detachable-version (:target-type stmt)))
      nil))
  session)

(defn- merge-import-like-nodes
  [existing incoming]
  (let [seen (atom (set (map pr-str existing)))]
    (reduce (fn [acc node]
              (let [k (pr-str node)]
                (if (contains? @seen k)
                  acc
                  (do
                    (swap! seen conj k)
                    (conj acc node)))))
            (vec existing)
            incoming)))

(defn remember-top-level-ast!
  [session ast]
  (swap! (:function-asts session)
         (fn [m]
           (reduce (fn [acc fn-def]
                     (assoc acc (:name fn-def) fn-def))
                   m
                   (:functions ast))))
  (swap! (:class-asts session)
         (fn [m]
           (reduce (fn [acc class-def]
                     (assoc acc (:name class-def) class-def))
                   m
                   (user-class-defs ast))))
  (swap! (:import-asts session) merge-import-like-nodes (:imports ast))
  (swap! (:intern-asts session) merge-import-like-nodes (:interns ast))
  (rt/state-set-classes! (:state session) @(:class-asts session))
  (rt/state-set-imports! (:state session) @(:import-asts session))
  session)

(defn- compile-and-register-functions!
  [session ast]
  (when (seq (remove :declaration-only? (:functions ast)))
    (let [current-functions (vals @(:function-asts session))
          current-classes (vals @(:class-asts session))
          current-imports @(:import-asts session)
          replaced-names (set (map :name (:functions ast)))
          other-functions (remove #(contains? replaced-names (:name %)) current-functions)
          class-name (next-class-name! session)
          compile-ast {:type :program
                       :imports current-imports
                       :interns []
                       :classes current-classes
                       :functions (:functions ast)
                       :statements []
                       :calls []}
          {:keys [unit]} (lower/lower-repl-cell compile-ast
                                                {:name class-name
                                                 :compiled-classes @(:compiled-classes session)
                                                 :functions other-functions
                                                 :var-types (session-var-types session)})
          bytecode (emit/compile-unit->bytes unit)
          binary-name (desc/binary-class-name class-name)
          cls (loader/define-class! (:loader session) binary-name bytecode)
          state (:state session)
          method (.getMethod cls "eval" (into-array Class [(class state)]))]
      (.invoke method nil (object-array [state]))))
  session)

(defn sync-interpreter->session!
  "Copy top-level interpreter state into the compiled session and remember
   top-level AST metadata so later compiled cells can type/lower against it."
  [session ctx var-types ast]
  (remember-top-level-ast! session ast)
  (let [state (:state session)
        function-names (session-function-name-set session)]
    (reset-runtime-state! state)
    (doseq [[k v] @(:bindings (:globals ctx))
            :let [name (if (string? k) k (name k))]
            :when (not (contains? function-names name))]
      (rt/state-set-value! state name v))
    (doseq [[k t] var-types
            :when (not (contains? function-names k))]
      (rt/state-set-type! state k t))
    (compile-and-register-functions! session ast)
    session))

(defn sync-session->interpreter!
  "Materialize compiled-session top-level state into the interpreter context.
   Returns {:ctx ctx :var-types {..}} for the caller to update REPL globals."
  [session ctx]
  (reset! (:bindings (:globals ctx)) {})
  (reset! (:imports ctx) [])
  (reset! (:classes ctx) {})
  (doseq [import-node @(:import-asts session)]
    (interp/eval-node ctx import-node))
  (doseq [class-def (vals @(:class-asts session))]
    (interp/eval-node ctx class-def))
  (doseq [fn-def (vals @(:function-asts session))]
    (interp/eval-node ctx fn-def))
  (doseq [[k v] @(:values (:state session))]
    (interp/env-define (:globals ctx) k v))
  {:ctx ctx
   :var-types (merge
               (into {}
                     (map (fn [[name fn-def]]
                            [name (:class-name fn-def)]))
                     @(:function-asts session))
               (session-var-types session))})

(defn compile-and-eval!
  "Attempt compiled evaluation for a narrow REPL-safe top-level subset.
   Returns {:compiled? true :session .. :result ..} on success, nil when the
   input is outside the supported subset or lowering/emission declines it."
  [session ast]
  (let [ast' (normalize-program-ast ast)]
    (when (eligible-ast? session ast')
    (try
      (let [class-name (next-class-name! session)
            _ (compile-and-register-classes! session ast')
            _ (remember-top-level-ast! session ast')
            {:keys [unit]} (lower/lower-repl-cell ast' {:name class-name
                                                        :compiled-classes @(:compiled-classes session)
                                                        :classes (vals @(:class-asts session))
                                                        :functions (vals @(:function-asts session))
                                                        :var-types (session-var-types session)})
            bytecode (emit/compile-unit->bytes unit)
            binary-name (desc/binary-class-name class-name)
            cls (loader/define-class! (:loader session) binary-name bytecode)
            state (:state session)
            _ (rt/clear-output! state)
            method (.getMethod cls "eval" (into-array Class [(class state)]))
            result (.invoke method nil (object-array [state]))]
        (sync-var-types-from-ast! session ast')
        {:compiled? true
         :session session
         :output (rt/state-output state)
         :result result})
      (catch clojure.lang.ExceptionInfo e
        (let [msg (.getMessage e)]
          (when-not (or (.contains msg "Unsupported")
                        (.contains msg "Unable to infer expression type during lowering"))
            (throw e))
          nil))))))
