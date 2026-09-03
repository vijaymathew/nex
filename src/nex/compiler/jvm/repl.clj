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
  (:import [java.util HashMap]
           [java.lang.reflect InvocationTargetException]))

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
     :intern-asts (atom [])
     ;; name -> {:name .. :type-expr ..}, accumulated across cells the same
     ;; way :class-asts/:function-asts are — an `intern ... as` alias (or a
     ;; `declare type` alias) declared in an earlier cell must still resolve
     ;; in a later one that only uses it. See augment-ast-with-modules and
     ;; remember-top-level-ast!.
     :type-aliases (atom {})
     ;; The message of the last exception `compile-and-eval!` swallowed to fall
     ;; back to the interpreter for a plain statement/expression cell, or nil
     ;; after a cell that didn't need to. `nex.repl` reads this to print a
     ;; visible warning — see `deopt-compiled-exception?`.
     :last-decline-reason (atom nil)}))

(defn reset-session
  []
  (make-session))

(defn- declares-class-or-function?
  "True when AST introduces a new class or function definition, as opposed to
   a plain top-level statement/expression cell.

   The distinction matters because a class or function persists in the REPL
   session: once one of them fails to compile and silently falls back to
   `interp/eval-node` instead, it stays an *interpreted* definition for the
   rest of the session, and the two backends can disagree on real features
   (see docs/md/BACKEND_ALIGNMENT.md) — `super.method()` used to be exactly
   such a gap until the interpreter grew a real implementation of it (see
   `nex.this-super-test`). A one-off statement's fallback is contained to
   that single evaluation and has no such lingering cost, so it is still
   safe to swallow silently here."
  [ast]
  (boolean (or (seq (:classes ast)) (seq (:functions ast)))))

(defn- unwrap-reflective-exception
  "A class/function's `eval` method is invoked via `Method/invoke` (see
   `compile-and-eval!` below), which wraps whatever it throws — including a
   JVM linking failure that only surfaces the first time the generated
   bytecode is actually run — in an `InvocationTargetException` (or, for a
   failure during a class's static init, `ExceptionInInitializerError`) with
   no message of its own. Peels away those wrappers down to the real cause,
   same as `nex.repl/unwrap-user-visible-exception` and
   `nex.compiler.jvm.runtime/unwrap-reflective-exception` do elsewhere."
  [^Throwable t]
  (loop [t t]
    (if (and (or (instance? InvocationTargetException t)
                 (instance? ExceptionInInitializerError t))
             (.getCause t))
      (recur (.getCause t))
      t)))

(defn- deopt-compiled-exception?
  "True when `t` is a known compiled-backend gap safe to recover from by
   re-running the cell on the tree-walking interpreter. Never true for a
   class/function-declaring cell — see `declares-class-or-function?` — except
   for a `LinkageError`, which (like `nex.eval/run-ast`'s identical handling
   for whole-file execution) is always a backend defect rather than the
   program's own behavior, so it is always safe to recover from; and except
   when `redeclaring?` is true, since a *re*-declaration (the name already has
   a working definition from an earlier cell, interpreted or compiled) never
   leaves anything undefined on failure — it just keeps the prior definition's
   backend instead of also becoming/staying compiled, exactly like the
   best-effort recompile `sync-interpreter->session!` already does after every
   interpreter-run cell."
  [^Throwable t declaring? redeclaring?]
  (if (or redeclaring? (instance? LinkageError (unwrap-reflective-exception t)))
    true
    (let [msg (.getMessage t)]
      (boolean
       (and msg
            (not declaring?)
            (or (.contains msg "Unsupported")
                (.contains msg "Unable to infer expression type during lowering")
                (.contains msg "Only expression-shaped or result-assignment if branches are supported in lowering")
                (.contains msg "Only eq/neq object comparisons are supported")
                (.contains msg "Missing compiled class metadata during lowering")
                (.contains msg "Create of non-compiled class is not supported in lowering")))))))

(defn- decline-reason
  "User-facing explanation for `t` to store as `:last-decline-reason`, read by
   `nex.repl` to print a visible fallback warning."
  [^Throwable t]
  (let [cause (unwrap-reflective-exception t)]
    (if (instance? LinkageError cause)
      (str "compiled program failed to link (" (or (ex-message cause) (str cause)) ")")
      (ex-message t))))

(def ^:private relational-ops
  #{"=" "/=" "==" "!=" "<" "<=" ">" ">="})

(def ^:private builtin-function-names
  (set (keys interp/builtins)))

(declare supported-expr-in-ctx?)
(declare supported-stmt-in-ctx?)
(declare session-var-types)
(declare builtin-target-call-in-ctx?)
(declare user-target-call-in-ctx?)
(declare function-object-call-in-ctx?)
(declare advance-eligibility-ctx)
(declare supported-stmt-block-with-ctx?)
(declare supported-convert-in-ctx?)
(declare supported-anonymous-function-in-ctx?)
(declare supported-select-clause-in-ctx?)
(declare merge-import-like-nodes)
(declare class-def-in-ctx)

(def ^:private builtin-runtime-receiver-types
  #{"Any" "Comparable" "Integer" "Real" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Min_Heap" "Atomic_Integer" "Atomic_Integer64" "Atomic_Boolean" "Atomic_Reference"
    "Cursor" "Task" "Channel" "Console" "Process"})

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

(defn- java-host-class-root?
  [ctx name]
  (and (:with-java? ctx)
       (string? name)
       (not (contains? (:known-vars ctx) name))
       (re-matches #"[A-Z][A-Za-z0-9_]*" name)))

(defn- compiled-class-names
  [session]
  (set (keys @(:compiled-classes session))))

(defn- builtin-class-defs
  []
  (let [interp-builtins @(:classes (interp/make-context))
        env (tc/make-type-env)]
    (tc/register-builtin-methods env)
    (vals (merge interp-builtins @(:classes env)))))

(defn- import-placeholder-classes
  [imports]
  (->> imports
       (keep (fn [{:keys [qualified-name source]}]
               (when (and (nil? source) qualified-name)
                 (let [simple-name (last (clojure.string/split qualified-name #"\."))]
                   {:name simple-name :body [] :import qualified-name}))))
       vec))

(defn- user-class-defs
  [ast]
  (let [synthetic-class-names (set (keep :class-name (:functions ast)))]
    (remove #(contains? synthetic-class-names (:name %))
            (:classes ast))))

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

(defn- collect-generic-names-from-type
  [type-expr]
  (cond
    (string? type-expr) #{type-expr}
    (map? type-expr) (reduce set/union #{}
                             (map collect-generic-names-from-type
                                  (or (:type-params type-expr)
                                      (:type-args type-expr)
                                      [])))
    :else #{}))

(defn- generic-constraint-map
  [classes]
  (let [builtin-class-names (set (map :name (builtin-class-defs)))]
    (reduce (fn [acc {:keys [name generic-params]}]
              (if (or (contains? builtin-class-names name)
                      (empty? generic-params))
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
          classes)))

(defn- augment-ctx-with-visible-generic-classes
  [ctx]
  (let [builtin-class-names (set (map :name (builtin-class-defs)))
        constraints (generic-constraint-map (:classes ctx))
        visible-generic-names (reduce set/union #{}
                                      (map collect-generic-names-from-type
                                           (vals (:var-types ctx))))
        existing-class-names (set (map :name (:classes ctx)))
        synthetic-classes (->> visible-generic-names
                               (remove #(or (contains? builtin-class-names %)
                                            (contains? existing-class-names %)))
                               (map (fn [generic-name]
                                      (cond-> {:name generic-name
                                               :deferred? true
                                               :generic-params nil
                                               :parents [{:parent "Any"}]
                                               :body []}
                                        (get constraints generic-name)
                                        (update :parents conj {:parent (get constraints generic-name)}))))
                               vec)]
    (if (seq synthetic-classes)
      (update ctx :classes into synthetic-classes)
      ctx)))

(defn- anonymous-class-defs
  [ast]
  (lower/collect-anonymous-class-defs ast))

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
                                  :var-types (:var-types ctx)
                                  :type-aliases (:type-aliases ctx)}))

(defn- builtin-target-call-in-ctx?
  [ctx expr]
  (let [target-expr (normalize-call-target (:target expr))
        target-type (when target-expr
                      (infer-type-in-ctx ctx target-expr))
        base (base-type-name target-type)
        builtin-class (some #(when (= (:name %) base) %) (builtin-class-defs))
        visible-class (class-def-in-ctx ctx base)]
    (and target-expr
         (supported-expr-in-ctx? ctx target-expr)
         (every? #(supported-expr-in-ctx? ctx %) (:args expr))
         (contains? builtin-runtime-receiver-types base)
         (= builtin-class visible-class))))

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

(defn- class-field-in-ctx
  [ctx class-name field-name]
  (let [class-map (into {} (map (juxt :name identity) (:classes ctx)))]
    (letfn [(lookup-field [cn visited inherited?]
              (when (and cn (not (contains? visited cn)))
                (let [class-def (get class-map cn)
                      visited' (conj visited cn)
                      local-field (some #(when (and (= :field (:type %))
                                                    (= field-name (:name %))
                                                    (or (not inherited?)
                                                        (tc/public-member? %)))
                                           %)
                                        (tc/feature-members class-def))]
                  (or local-field
                      (some #(lookup-field (:parent %) visited' true)
                            (:parents class-def))))))]
      (lookup-field class-name #{} false))))

(defn- class-constant-in-ctx
  [ctx class-name constant-name]
  (let [class-map (into {} (map (juxt :name identity) (:classes ctx)))]
    (letfn [(lookup-constant [cn visited inherited?]
              (when (and cn (not (contains? visited cn)))
                (let [class-def (get class-map cn)
                      visited' (conj visited cn)
                      local-constant (some #(when (and (= :field (:type %))
                                                       (:constant? %)
                                                       (= constant-name (:name %))
                                                       (or (not inherited?)
                                                           (tc/public-member? %)))
                                              %)
                                           (tc/feature-members class-def))]
                  (or local-constant
                      (some #(lookup-constant (:parent %) visited' true)
                            (:parents class-def))))))]
      (lookup-constant class-name #{} false))))

(defn- user-target-call-in-ctx?
  [ctx expr]
  (let [raw-target (:target expr)
        class-target-def (when (string? raw-target)
                           (class-def-in-ctx ctx raw-target))
        target-expr (normalize-call-target raw-target)
        target-type (when (and target-expr (not class-target-def))
                      (infer-type-in-ctx ctx target-expr))
        base (or (:name class-target-def) (base-type-name target-type))
        class-def (or class-target-def
                      (class-def-in-ctx ctx base))
        field-name (:method expr)
        field-def (when (and class-def (false? (:has-parens expr)))
                    (if class-target-def
                      (class-constant-in-ctx ctx (:name class-def) field-name)
                      (class-field-in-ctx ctx (:name class-def) field-name)))
        method-def (when class-def
                     (class-method-in-ctx ctx (:name class-def) (:method expr) (count (:args expr))))]
    (and (or class-target-def target-expr)
         (or class-target-def (supported-expr-in-ctx? ctx target-expr))
         (contains? (:compiled-class-names ctx) base)
         (or field-def method-def))))

(defn- imported-java-target-call-in-ctx?
  [ctx expr]
  (let [class-target-def (when (string? (:target expr))
                           (class-def-in-ctx ctx (:target expr)))
        target-expr (normalize-call-target (:target expr))
        target-type (when target-expr
                      (infer-type-in-ctx ctx target-expr))
        base (base-type-name target-type)
        class-def (class-def-in-ctx ctx base)]
    (and target-expr
         (nil? class-target-def)
         (supported-expr-in-ctx? ctx target-expr)
         (every? #(supported-expr-in-ctx? ctx %) (:args expr))
         (:import class-def))))

(defn- class-constructors-in-ctx
  [ctx class-name]
  (mapcat (fn [section]
            (when (= :constructors (:type section))
              (:constructors section)))
          (:body (class-def-in-ctx ctx class-name))))

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
  (get (into {} (map (juxt :name identity) (:classes ctx))) class-name))

(defn- function-object-call-in-ctx?
  [ctx expr]
  (when (nil? (:target expr))
    (let [binding-type (get (:var-types ctx) (:method expr))
          base-type (base-type-name binding-type)
          class-def (class-def-in-ctx ctx base-type)]
      (and binding-type
           (every? #(supported-expr-in-ctx? ctx %) (:args expr))
           (or (= "Function" base-type)
               (and class-def
                    (class-method-in-ctx ctx base-type (str "call" (count (:args expr))) (count (:args expr)))))))))

(defn- supported-anonymous-function-in-ctx?
  [ctx expr]
  (let [params (or (:params expr) [])
        child-ctx (-> ctx
                      (update :known-vars into (concat (map :name params) ["result"]))
                      (update :var-types merge (into {}
                                                     (concat
                                                      (map (fn [{:keys [name type]}] [name type]) params)
                                                      [["result" (or (:return-type expr) "Any")]])))
                      (augment-ctx-with-visible-generic-classes))]
    (boolean (supported-stmt-block? child-ctx (:body expr)))))

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

(defn- augment-ast-with-modules
  [session source-id ast]
  (let [ast' (normalize-program-ast ast)
        ;; A module's classes/functions never change within a session, and
        ;; `intern` is idempotent — re-running it (directly, or transitively
        ;; because a different module also interns it) just brings the same
        ;; definitions back into scope. Drop any that a prior cell already
        ;; compiled+registered so they don't reach the class/function-defs
        ;; below: leaving them in makes `eligible-ast?` see a name collision
        ;; against the session's own compiled classes and decline the cell as
        ;; if it were a real redefinition, when nothing has actually changed.
        ;; They stay visible for typechecking/lowering regardless, via
        ;; `@(:class-asts session)`/`@(:function-asts session)`.
        intern-classes (remove #(contains? @(:class-asts session) (:name %))
                               (interp/resolve-interned-classes source-id ast'))
        ;; A module's free functions come into scope alongside its classes; the
        ;; cell that runs the `intern` must carry them so remember-top-level-ast!
        ;; records them for later cells (the file path does the same).
        intern-functions (remove #(contains? @(:function-asts session) (:name %))
                                 (interp/resolve-interned-functions source-id ast'))
        intern-imports (interp/resolve-interned-imports source-id ast')
        merged-imports (merge-import-like-nodes
                        (merge-import-like-nodes @(:import-asts session) intern-imports)
                        (:imports ast'))
        ;; An aliased intern (`intern X as Y`) resolves to a :type-aliases
        ;; entry, not a second class-def (see nex.interpreter/resolve-interned*)
        ;; — carried forward the same way class-asts/function-asts are, so a
        ;; later cell that only *uses* the alias (declared by an earlier cell)
        ;; still sees it.
        intern-type-aliases (interp/resolve-interned-type-aliases source-id ast')
        merged-type-aliases (vec (concat (vals @(:type-aliases session))
                                         intern-type-aliases
                                         (:type-aliases ast')))]
    (assoc ast'
           :imports merged-imports
           :classes (vec (concat intern-classes (:classes ast')))
           :functions (vec (concat intern-functions (:functions ast')))
           :type-aliases merged-type-aliases)))

(defn- initial-eligibility-ctx
  [session ast]
  (let [actual-classes (vec (concat (user-class-defs ast)
                                    (function-class-defs (:functions ast))
                                    (anonymous-class-defs ast)
                                    (function-class-defs (vals @(:function-asts session)))))
        imported-classes (import-placeholder-classes (:imports ast))
        compiled-fns (set (keys @(:functions (:state session))))]
    (augment-ctx-with-visible-generic-classes
     {:known-vars (set (concat (keys (session-var-types session))
                               (keys @(:values (:state session)))))
      :known-fns (set (concat builtin-function-names
                              compiled-fns
                              (map :name (:functions ast))))
      :var-types (merge (session-var-types session)
                        (into {}
                              (map (fn [fn-def]
                                     [(:name fn-def) (:class-name fn-def)]))
                              (concat (vals @(:function-asts session)) (:functions ast))))
      :functions (vec (concat (vals @(:function-asts session)) (:functions ast)))
      :classes (vec (concat (builtin-class-defs)
                            imported-classes
                            (vals @(:class-asts session))
                            actual-classes))
      :compiled-class-names (set (concat (compiled-class-names session)
                                         (map :name actual-classes)))
      :imports (:imports ast)
      ;; {name -> type-expr}, matching nex.lower's *type-aliases* shape, so
      ;; infer-type-in-ctx can resolve an `intern ... as` alias (or a
      ;; `declare type` alias) the same way full lowering does — without it,
      ;; a create-expression naming the alias infers as "Undefined class"
      ;; here, which this eligibility check treats as a hard type error
      ;; rather than the resolvable reference it actually is.
      :type-aliases (into {} (map (juxt :name :type-expr)) (:type-aliases ast))
      :retry-allowed? false})))

(defn supported-expr-in-ctx?
  [ctx expr]
  (if (string? expr)
    (or (contains? (:known-vars ctx) expr)
        (java-host-class-root? ctx expr))
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
      :anonymous-function (supported-anonymous-function-in-ctx? ctx expr)
      :create (if (= "Channel" (:class-name expr))
                (and (every? #(supported-expr-in-ctx? ctx %) (:args expr))
                     (case (:constructor expr)
                       nil (empty? (:args expr))
                       "with_capacity" (= 1 (count (:args expr)))
                       false))
                (let [class-def (class-def-in-ctx ctx (:class-name expr))]
                  (if (:import class-def)
                    (and (nil? (:constructor expr))
                         (every? #(supported-expr-in-ctx? ctx %) (:args expr)))
                    (and (contains? (:compiled-class-names ctx) (:class-name expr))
                         class-def
                         (not (:deferred? class-def))
                         (every? #(supported-expr-in-ctx? ctx %) (:args expr))
                         (if-let [ctor (:constructor expr)]
                           (known-constructor-in-ctx? ctx (:class-name expr) ctor (count (:args expr)))
                           (empty? (:args expr)))))))
      :identifier (or (contains? (:known-vars ctx) (:name expr))
                      (java-host-class-root? ctx (:name expr)))
      :spawn (boolean (supported-stmt-block? (-> ctx
                                                 (update :known-vars conj "result")
                                                 (assoc-in [:var-types "result"] "Any"))
                                             (:body expr)))
      :call (if (nil? (:target expr))
              (and (every? #(supported-expr-in-ctx? ctx %) (:args expr))
                   (or (and (empty? (:args expr))
                            (not (:has-parens expr)))
                       (contains? (:known-fns ctx) (:method expr))
                       (function-object-call-in-ctx? ctx expr)))
              (or (and (:with-java? ctx)
                       (supported-expr-in-ctx? ctx (normalize-call-target (:target expr)))
                       (every? #(supported-expr-in-ctx? ctx %) (:args expr)))
                  (builtin-target-call-in-ctx? ctx expr)
                  (user-target-call-in-ctx? ctx expr)
                  (imported-java-target-call-in-ctx? ctx expr)))
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
      false)))

(defn supported-convert-in-ctx?
  [ctx expr]
  (and (= :convert (:type expr))
       (supported-expr-in-ctx? ctx (:value expr))
       (let [target-type (:target-type expr)
             base (if (map? target-type) (:base-type target-type) target-type)]
         (string? base))))

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
    :match (and (supported-expr-in-ctx? ctx (:expr stmt))
                (every? #(every? (partial supported-stmt-in-ctx? ctx) (:body %)) (:clauses stmt))
                (or (nil? (:else stmt))
                    (every? (partial supported-stmt-in-ctx? ctx) (:else stmt))))
    :raise (supported-expr-in-ctx? ctx (:value stmt))
    :retry (:retry-allowed? ctx)
    :assert (every? #(supported-expr-in-ctx? ctx (:condition %)) (:assertions stmt))
    :select (and (every? #(supported-select-clause-in-ctx? ctx %) (:clauses stmt))
                 (or (nil? (:timeout stmt))
                     (and (supported-expr-in-ctx? ctx (get-in stmt [:timeout :duration]))
                          (supported-stmt-block? ctx (get-in stmt [:timeout :body]))))
                 (or (nil? (:else stmt))
                     (supported-stmt-block? ctx (:else stmt))))
    :with (if (= (:target stmt) "java")
            (boolean (supported-stmt-block-with-ctx? (assoc ctx :with-java? true)
                                                     (:body stmt)))
            true)
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

(defn- supported-select-clause-in-ctx?
  [ctx {:keys [expr alias body]}]
  (and (= :call (:type expr))
       (supported-expr-in-ctx? ctx expr)
       (let [body-ctx (if alias
                        (let [target-type (infer-type-in-ctx ctx (normalize-call-target (:target expr)))
                              alias-type (case (base-type-name target-type)
                                           "Task" (or (first (:type-args target-type))
                                                      (first (:type-params target-type))
                                                      "Any")
                                           "Channel" (or (first (:type-args target-type))
                                                         (first (:type-params target-type))
                                                         "Any")
                                           "Any")]
                          (-> ctx
                              (update :known-vars conj alias)
                              (assoc-in [:var-types alias] alias-type)))
                        ctx)]
         (supported-stmt-block? body-ctx body))))

(defn- advance-eligibility-ctx
  [ctx stmt]
  (augment-ctx-with-visible-generic-classes
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
     ctx)))

(defn eligible-ast?
  "Whether the compiled backend should attempt this AST at all. A class name
   colliding with one already compiled in this session is no longer grounds
   to decline outright — that used to force every class redeclaration onto
   the interpreter — `compile-and-register-classes!` recompiles a redeclared
   class under a fresh internal JVM name and its lowering context now drops
   the stale entry (see `other-classes` there), so a straightforward
   redeclaration can just compile directly."
  [session ast]
  (let [ast' (normalize-program-ast ast)
        initial-ctx (initial-eligibility-ctx session ast')]
    (and (= :program (:type ast'))
         (or (seq (:functions ast'))
             (seq (:statements ast'))
             (seq (:classes ast'))
             (seq (:imports ast'))
             (seq (:interns ast')))
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
  [session ast source-id]
  (let [actual-classes (vec (concat (user-class-defs ast)
                                    (anonymous-class-defs ast)))]
    (when (seq actual-classes)
      (let [compiled-class-defs (vec (remove :closure-runtime-object? actual-classes))
            new-class-map (allocate-compiled-class-metadata session compiled-class-defs)
          compiled-map (merge @(:compiled-classes session) new-class-map)
          visible-functions (vec (concat (vals @(:function-asts session)) (:functions ast)))
          ;; A class redeclared in this cell replaces its prior session
          ;; definition; drop the stale one from every name-based lookup below
          ;; so lowering (e.g. `ctx-class-def` in nex.lower, which returns the
          ;; *first* class matching a name) sees only the current shape rather
          ;; than whichever of the two entries happens to come first — mirrors
          ;; `compile-and-register-functions!`'s `other-functions` filtering.
          replaced-names (set (map :name actual-classes))
          other-classes (remove #(contains? replaced-names (:name %))
                                (vals @(:class-asts session)))
          visible-classes (vec (concat (builtin-class-defs)
                                       (import-placeholder-classes (:imports ast))
                                       other-classes
                                       actual-classes
                                       (keep :class-def visible-functions)))
          visible-imports (:imports ast)
          lowered-classes
          (mapv (fn [class-def]
                  (lower/lower-class-def class-def {:compiled-classes compiled-map
                                                    :classes visible-classes
                                                    :functions visible-functions
                                                    :imports visible-imports
                                                    :source-file source-id}))
                compiled-class-defs)
          ;; A class with an object-/collection-valued constant bootstraps a
          ;; session state in its <clinit> to build it, so it needs the same
          ;; class/import metadata the launcher gets — here the union of the
          ;; session's known classes and the batch being defined.
          bootstrap-edn {:classes-edn (pr-str (vec (concat other-classes
                                                           actual-classes)))
                         :imports-edn (pr-str visible-imports)}]
        (doseq [class-def compiled-class-defs]
          (let [lowered (some #(when (= (:name %) (:name class-def)) %) lowered-classes)
                bytecode (emit/compile-user-class->bytes lowered bootstrap-edn)]
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

(defn session-var-types
  [session]
  (into {} @(:types (:state session))))

(defn- inference-classes-for-session
  [session]
  (concat (vals @(:class-asts session))
          (keep :class-def (vals @(:function-asts session)))))

(defn- inference-type-aliases-for-session
  "{name -> type-expr}, matching nex.lower's *type-aliases* shape, for
   inference calls made outside a bound *type-aliases* — e.g. an `intern
   ... as` alias resolves here the same way a real class name does."
  [session]
  (into {} (map (fn [[k {:keys [type-expr]}]] [k type-expr])) @(:type-aliases session)))

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
                                ;; A closure literal's own signature is
                                ;; always fully determined by what's
                                ;; written on it, regardless of what its
                                ;; body references — checked BEFORE the
                                ;; infer-expression-type fallback below,
                                ;; not after, for the same reason
                                ;; nex.lower/box-let-type and
                                ;; rewrite-statement-for-closures read a
                                ;; closure-let's type this way: infer-
                                ;; expression-type type-checks the WHOLE
                                ;; body in an isolated, standalone env to
                                ;; infer a closure's type, and throws
                                ;; (silently swallowed, falling back to
                                ;; "Any" right here) the moment that body
                                ;; references a sibling closure elaborated
                                ;; alongside it — a self- or mutually-
                                ;; recursive closure-let is exactly that
                                ;; shape. Without this, THIS session's own
                                ;; cross-cell type registry (rt/state-set-
                                ;; type!) persisted "Any" for such a
                                ;; closure, so calling it from a LATER,
                                ;; separate cell dispatched against the
                                ;; erased type instead of its real
                                ;; Function(...) signature — "Method not
                                ;; found: call1" even though the very same
                                ;; call already worked correctly within
                                ;; the cell that defined it.
                                (lower/anonymous-function-signature-type (:value stmt))
                                (tc/infer-expression-type
                                 (:value stmt)
                                 {:classes (inference-classes-for-session session)
                                  :functions (vals @(:function-asts session))
                                  :imports @(:import-asts session)
                                  :var-types (session-var-types session)
                                  :type-aliases (inference-type-aliases-for-session session)})
                                "Any")]
             (when nex-type
               (rt/state-set-type! (:state session) (:name stmt) nex-type))))
      :assign (when-let [nex-type (or (get (session-var-types session) (:target stmt))
                                      (tc/infer-expression-type
                                       (:value stmt)
                                       {:classes (inference-classes-for-session session)
                                        :functions (vals @(:function-asts session))
                                        :imports @(:import-asts session)
                                        :var-types (session-var-types session)
                                        :type-aliases (inference-type-aliases-for-session session)})
                                      "Any")]
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
                   (concat (user-class-defs ast)
                           (anonymous-class-defs ast)))))
  (swap! (:import-asts session) merge-import-like-nodes (:imports ast))
  (swap! (:intern-asts session) merge-import-like-nodes (:interns ast))
  (swap! (:type-aliases session)
         (fn [m]
           (reduce (fn [acc {:keys [name] :as alias}]
                     (assoc acc name alias))
                   m
                   (:type-aliases ast))))
  (rt/state-set-classes! (:state session) @(:class-asts session))
  (rt/state-set-imports! (:state session) @(:import-asts session))
  session)

(defn- compile-and-register-functions!
  [session ast source-id]
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
          _ (compile-and-register-classes! session compile-ast source-id)
          {:keys [unit]} (lower/lower-repl-cell compile-ast
                                                {:name class-name
                                                 :source-file source-id
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

(defn- re-register-session-functions!
  [session source-id]
  (let [all-functions (vec (vals @(:function-asts session)))]
    (when (seq (remove :declaration-only? all-functions))
      (compile-and-register-functions!
       session
       {:type :program
        :imports @(:import-asts session)
        :interns []
        :classes (vec (vals @(:class-asts session)))
        :functions all-functions
       :statements []
       :calls []}
       source-id)))
  session)

(defn- re-register-session-classes!
  [session source-id]
  (let [all-classes (vec (vals @(:class-asts session)))]
    (when (seq all-classes)
      (compile-and-register-classes!
       session
       {:type :program
        :imports @(:import-asts session)
        :interns []
        :classes all-classes
        :functions (vec (vals @(:function-asts session)))
        :statements []
        :calls []}
       source-id)))
  session)

(defn sync-interpreter->session!
  "Copy top-level interpreter state into the compiled session and remember
   top-level AST metadata so later compiled cells can type/lower against it."
  ([session ctx var-types ast]
   (sync-interpreter->session! session ctx var-types ast nil))
  ([session ctx var-types ast source-id]
   (let [module-ast (augment-ast-with-modules session source-id ast)
         prepared-ast (lower/prepare-program-for-closures
                       module-ast
                       {:classes (vals @(:class-asts session))
                        :functions (vals @(:function-asts session))
                        :imports (:imports module-ast)
                        :var-types var-types})]
     (remember-top-level-ast! session prepared-ast)
     (let [state (:state session)
           function-names (session-function-name-set session)
           existing-fns (clone-hash-map @(:functions state))]
       (reset-runtime-state! state)
       (reset! (:functions state) existing-fns)
       (doseq [[k v] @(:bindings (:globals ctx))
               :let [name (if (string? k) k (name k))]
               :when (not (contains? function-names name))]
         (rt/state-set-value! state name v))
       (doseq [[k t] var-types
               :when (not (contains? function-names k))]
         (rt/state-set-type! state k t))
       ;; Interpreter-originated top-level lets still need their inferred or
       ;; annotated types persisted into compiled session state, even when the
       ;; REPL typechecker is off and `var-types` is therefore incomplete.
       (sync-var-types-from-ast! session prepared-ast)
       (try
         (when (seq (:classes prepared-ast))
           (re-register-session-classes! session source-id))
         (re-register-session-functions! session source-id)
         (catch clojure.lang.ExceptionInfo e
           ;; Unlike `compile-and-eval!`, a failure here is a best-effort *re*-
           ;; compile of a class/function the interpreter has already defined
           ;; and already run successfully — declining just means it stays
           ;; interpreted rather than also becoming compiled, not that it goes
           ;; undefined. So `declaring?` is always false here: this call never
           ;; needs the stricter, visible-failure behavior `compile-and-eval!`
           ;; uses for a definition's *first* attempt.
           (when-not (deopt-compiled-exception? e false false)
             (throw e))))
       session))))

(defn sync-session->interpreter!
  "Materialize compiled-session top-level state into the interpreter context.
   Returns {:ctx ctx :var-types {..}} for the caller to update REPL globals."
  [session ctx]
  (let [ctx' ctx]
    (when-let [compiled-state (:compiled-state ctx')]
      (reset! compiled-state (:state session)))
    (reset! (:bindings (:globals ctx')) {})
    (reset! (:imports ctx') [])
    (let [builtin-classes @(:classes (interp/make-context))]
      (reset! (:classes ctx') builtin-classes))
    (reset! (:imports ctx') (vec @(:import-asts session)))
    (doseq [class-def (vals @(:class-asts session))]
      (interp/eval-node ctx' class-def))
    (doseq [fn-def (vals @(:function-asts session))]
      (interp/eval-node ctx' fn-def))
    ;; An `intern ... as` alias is a session :type-alias entry, not a second
    ;; class-def (see nex.interpreter/resolve-interned*), so the classes just
    ;; re-registered above from :class-asts only cover the real names —
    ;; restore each alias as its own key onto the same class-def, matching
    ;; what nex.interpreter/process-intern does the first time an `intern`
    ;; runs. Without this, resetting :classes above to the rebuilt registry
    ;; drops any alias a prior cell's `intern` established.
    (doseq [[alias-name {:keys [type-expr]}] @(:type-aliases session)]
      (when-let [real-class (and (string? type-expr) (get @(:classes ctx') type-expr))]
        (swap! (:classes ctx') assoc alias-name real-class)))
    (doseq [[k v] @(:values (:state session))]
      (interp/env-define (:globals ctx') k v))
    {:ctx ctx'
     :var-types (merge
                 (into {}
                       (map (fn [[name fn-def]]
                              [name (:class-name fn-def)]))
                       @(:function-asts session))
                 (session-var-types session))}))

(defn- redeclares-existing-class-or-function?
  "True when `ast` declares a class or function name the session already has
   a working definition for (compiled or interpreted). Used to keep a failed
   *re*-compile attempt from surfacing as a hard error the way a brand-new
   definition's compile failure does — see `deopt-compiled-exception?`."
  [session ast]
  (boolean
   (or (some (set (map :name (user-class-defs ast)))
             (compiled-class-names session))
       (some (set (map :name (:functions ast)))
             (keys @(:function-asts session))))))

(defn compile-and-eval!
  "Attempt compiled evaluation for a narrow REPL-safe top-level subset.
   Returns {:compiled? true :session .. :result ..} on success, nil when the
   input is outside the supported subset or lowering/emission declines it."
  ([session ast]
   (compile-and-eval! session ast nil))
  ([session ast source-id]
   (reset! (:last-decline-reason session) nil)
   (let [module-ast (augment-ast-with-modules session source-id ast)
         prepared-ast (lower/prepare-program-for-closures
                       module-ast
                       {:classes (vals @(:class-asts session))
                        :functions (vals @(:function-asts session))
                        :imports (:imports module-ast)
                        :var-types (session-var-types session)})]
     (when (eligible-ast? session prepared-ast)
       (let [declaring? (declares-class-or-function? prepared-ast)
             redeclaring? (redeclares-existing-class-or-function? session prepared-ast)]
         (try
           (let [class-name (next-class-name! session)
                 _ (compile-and-register-classes! session prepared-ast source-id)
                 _ (remember-top-level-ast! session prepared-ast)
                 {:keys [unit]} (lower/lower-repl-cell prepared-ast {:name class-name
                                                                     :compiled-classes @(:compiled-classes session)
                                                                     :classes (vals @(:class-asts session))
                                                                     :functions (vals @(:function-asts session))
                                                                     :imports (:imports prepared-ast)
                                                                     :source-file source-id
                                                                     :var-types (session-var-types session)})
                 bytecode (emit/compile-unit->bytes unit)
                 binary-name (desc/binary-class-name class-name)
                 cls (loader/define-class! (:loader session) binary-name bytecode)
                 state (:state session)
                 _ (rt/clear-output! state)
                 method (.getMethod cls "eval" (into-array Class [(class state)]))
                 result (.invoke method nil (object-array [state]))]
             (sync-var-types-from-ast! session prepared-ast)
             (reset! (:last-decline-reason session) nil)
             {:compiled? true
              :session session
              :output (rt/state-output state)
              :result result})
           (catch clojure.lang.ExceptionInfo e
             (if (deopt-compiled-exception? e declaring? redeclaring?)
               (do (reset! (:last-decline-reason session) (decline-reason e))
                   nil)
               (throw e)))
           (catch Throwable t
             (if (deopt-compiled-exception? t declaring? redeclaring?)
               (do (reset! (:last-decline-reason session) (decline-reason t))
                   nil)
               (throw t)))))))))
