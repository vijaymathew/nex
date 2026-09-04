(ns nex.lower
  "First lowering pass from typed Nex AST to compiler IR.

  This pass is intentionally narrow. It currently supports:

  - literals
  - identifiers
  - binary expressions
  - let
  - assignment
  - top-level variable access

  Unsupported nodes fail fast with ex-info."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [nex.compiler.jvm.descriptor :as desc]
            [nex.types.builtins :as bi]
            [nex.types.bootstrap :as bootstrap]
            [nex.ir :as ir]
            [nex.parser :as parser]
            [nex.typechecker :as tc])
  (:import [org.objectweb.asm Type]))

(declare lower-expression)
(declare lower-statement)
(declare lower-statements)
(declare lower-create-expr)
(declare lower-call-expr)
(declare lower-member-assign-stmt)
(declare lower-call-stmt)
(declare lower-loop-stmt)
(declare lower-class-def)
(declare class-self-registration-name)
(declare if-branch-expression)
(declare current-class-def)
(declare class-method-def)
(declare class-field-def)
(declare elseif->else-expr)
(declare visible-class-map)
(declare generic-type-map)
(declare normalize-call-target)
(declare function-return-type)
(declare normalized-function-def)
(declare ensure-convert-binding)
(declare carrier-path-target-ir)
(declare lower-convert-expression)
(declare lookup-class-constant)
(declare constant-nex-type)
(declare resolve-parent-metas)
(declare method-override?)
(declare class-jvm-meta)
(declare inherited-method-def)
(declare accessible-field-def)
(declare accessible-method-def)
(declare single-super-parent-name)
(declare infer-call-type
         infer-free-function-return-type)
(declare collect-anonymous-class-defs)
(declare refine-condition-branch-env)
(declare lower-boolean-condition)
(declare convert-binding-init-stmts)
(declare function-object-call?)
(declare function-object-binding-type)
(declare lower-select)
(declare java-superclass-parent)
(declare java-super-constructor-call?)
(declare ctor-forwards-java-super-args?)
(declare resolve-imported-java-type)
(declare resolve-java-class-by-name)
(declare resolve-java-call-target)
(declare resolved-param-classes-joined)
(declare resolved-varargs-descriptor)

(defn- invalid-bare-create-call-ex
  [class-name]
  (ex-info (str "Invalid create syntax for " class-name)
           {:class-name class-name
            :message (str "Use 'create " class-name
                          "' or 'create " class-name ".<ctor>(...)'.")}))

(defn- imported-java-qualified-name
  [env class-name]
  (some (fn [{:keys [qualified-name source]}]
          (when (and (nil? source)
                     qualified-name
                     (= class-name (last (str/split qualified-name #"\."))))
            qualified-name))
        (:imports env)))

(defn- builtin-class-defs
  []
  (let [interp-builtins (into {}
                              (map (fn [class-def] [(:name class-def) class-def]))
                              (concat [(bootstrap/build-any-base-class)
                                       (bootstrap/build-function-base-class)
                                       (bootstrap/build-cursor-base-class)
                                       (bootstrap/build-comparable-base-class)
                                       (bootstrap/build-hashable-base-class)]
                                      (map bootstrap/build-builtin-scalar-class
                                           ["String" "Integer" "Real" "Boolean" "Char"])))
        env (tc/make-type-env)]
    (tc/register-builtin-methods env)
    (vals (merge interp-builtins @(:classes env)))))

(defn- merge-visible-classes
  [& class-groups]
  (->> class-groups
       (apply concat)
       (reduce (fn [acc class-def]
                 (if (and (map? class-def) (:name class-def))
                   (assoc acc (:name class-def) class-def)
                   acc))
               {})
       vals
       vec))

(def ^:private expression-node-types
  #{:integer :real :string :char :boolean :nil :identifier :binary :unary
    :call :if :when :this :array-literal :map-literal :set-literal
    :anonymous-function :spawn :create})

(def ^:private builtin-function-names
  (set (keys bi/builtins)))

(defn- builtin-method-names
  [type-name]
  (set (keys (get bi/builtin-type-methods type-name))))

(def ^:private builtin-runtime-receiver-types
  #{"Any" "Comparable" "Integer" "Real" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Min_Heap" "Atomic_Integer" "Atomic_Integer64" "Atomic_Boolean" "Atomic_Reference"
    "Cursor" "Task" "Channel" "Console" "Process"})

;; The only method names the compiled backend actually implements a
;; "builtin-method:Any:*" runtime wrapper for (see the `def-builtin-method-
;; wrapper builtin-method-any-*` forms in `nex.compiler.jvm.runtime`). Any
;; other method call on an `Any`-typed receiver is not a Nex Any-protocol
;; call — most commonly, in a with-"java" block, an actual Java method on
;; whatever object the `Any` is holding at runtime — and must not be routed
;; through the same table, or it resolves to a Var that was never defined.
(def ^:private any-protocol-method-names
  #{"to_string" "equals" "clone" "cursor" "start" "item" "next" "at_end" "get" "length"})

(def ^:private next-synthetic-closure-id (atom 0))

(def ^:private direct-integer-bitwise-method->op
  {"bitwise_left_shift" :bit-shl
   "bitwise_right_shift" :bit-shr
   "bitwise_logical_right_shift" :bit-ushr
   "bitwise_rotate_left" :bit-rotl
   "bitwise_rotate_right" :bit-rotr
   "bitwise_is_set" :bit-test
   "bitwise_set" :bit-set
   "bitwise_unset" :bit-unset
   "bitwise_and" :bit-and
   "bitwise_or" :bit-or
   "bitwise_xor" :bit-xor
   "bitwise_not" :bit-not})

(def ^:private direct-array-methods
  (builtin-method-names :Array))

(def ^:private direct-map-methods
  (builtin-method-names :Map))

(def ^:private direct-set-methods
  (builtin-method-names :Set))

(def ^:private direct-task-methods
  (builtin-method-names :Task))

(def ^:private direct-channel-methods
  (builtin-method-names :Channel))

(defn- base-type-name
  [t]
  (cond
    (string? t) t
    (map? t) (:base-type t)
    :else nil))

;; Program-global `declare type` aliases, bound once at the lowering entry point.
;; Aliases are transparent, so lowering must see through them when it infers the
;; type of a value (e.g. deciding that a variable typed via a function-type alias
;; is callable). Threaded as a dynamic var rather than through every env so that
;; nested function/method/constructor lowering picks it up automatically.
(def ^:dynamic *type-aliases* {})

;; Program top-level `let` globals (name -> nex-type), bound once at the lowering
;; entry point. Readable from the static world (§7): an otherwise-unknown
;; identifier in a non-top-level body that names a global lowers to a `top-get`
;; against the live session state. Threaded as a dynamic var (like *type-aliases*)
;; so nested function/method/constructor lowering picks it up without every call
;; site passing it through.
(def ^:dynamic *top-level-globals* {})

;; Program-wide `--skip-contracts` default, bound once at the lowering entry
;; point (nex.compiler.jvm.file/compile-ast, via lower-repl-cell's opts).
;; Threaded the same way as *top-level-globals* so nested function/method/
;; constructor/invariant-method lowering all pick it up automatically.
(def ^:dynamic *skip-contracts?* false)

(defn- unsupported
  "An ex-info marking a construct the compiled backend does not implement yet.

   The distinction this draws is the one the user needs: by the time lowering
   runs, the typechecker has already accepted the program. So a failure here is
   either

   - a *gap* — the program is valid and the backend is merely incomplete, in
     which case `--interpret` is a genuine workaround and there is nothing to
     fix in the program. Those throw through this fn; and
   - a *defect* — an invariant broke, or the typechecker admitted something it
     should have rejected (`Constructor not found`, `Unknown local`, `Unable to
     infer expression type`: all of these describe a program the typechecker
     already validated, so reaching them means the compiler is wrong, not the
     program). Those stay plain `ex-info`.

   Reporting both as \"your program uses an unsupported construct\" sends the
   reader hunting for a workaround to a bug that should be reported instead.
   Unmarked is the safer default: an unreported gap gets told \"please report
   this\", which is how it comes to be marked."
  [msg data]
  (ex-info msg (assoc data :nex/unsupported true)))

(defn- resolve-type-alias
  "Expand a declared type alias to its underlying type expression, following
   chains (`H -> G -> Function(...)`). Non-alias types are returned unchanged."
  [t]
  (loop [t t
         seen #{}]
    (if (and (string? t)
             (contains? *type-aliases* t)
             (not (contains? seen t)))
      (recur (get *type-aliases* t) (conj seen t))
      t)))

(defn- builtin-runtime-receiver-type?
  [env t]
  (let [base (base-type-name t)
        builtin-class (some #(when (= (:name %) base) %) (builtin-class-defs))
        visible-class (get (visible-class-map env) base)]
    (and (contains? builtin-runtime-receiver-types base)
         (= builtin-class visible-class))))

(defn- generic-type-args
  [t]
  (or (:type-args t) (:type-params t) []))

(defn- array-type-of
  [elem-type]
  {:base-type "Array" :type-params [elem-type]})

(defn- map-type-of
  [key-type value-type]
  {:base-type "Map" :type-params [key-type value-type]})

(defn- set-type-of
  [elem-type]
  {:base-type "Set" :type-params [elem-type]})

(defn- resolve-collection-return-marker
  "Resolve a return-type marker from `bi/builtin-type-method-return-type`
   against the receiver's actual generic arguments. Concrete type names pass
   through unchanged."
  [target-type marker]
  (let [base (base-type-name target-type)
        [a b] (generic-type-args target-type)]
    (case marker
      :element (or a "Any")
      :value (or b "Any")
      :array-of-element (array-type-of (or a "Any"))
      :array-of-value (array-type-of (or b "Any"))
      :self (or target-type
                (case base
                  "Array" (array-type-of (or a "Any"))
                  "Map" (map-type-of (or a "Any") (or b "Any"))
                  "Set" (set-type-of (or a "Any"))
                  nil))
      marker)))

(defn- collection-method-return-type
  [target-type method]
  (some->> (bi/builtin-type-method-return-type
            (keyword (base-type-name target-type)) method)
           (resolve-collection-return-marker target-type)))

(defn- direct-collection-method?
  [target-type method]
  (case (base-type-name target-type)
    "Array" (contains? direct-array-methods method)
    "Map" (contains? direct-map-methods method)
    "Set" (contains? direct-set-methods method)
    false))

(defn- direct-concurrency-method?
  [env target-type method]
  (case (when (builtin-runtime-receiver-type? env target-type)
          (base-type-name target-type))
    "Task" (contains? direct-task-methods method)
    "Channel" (contains? direct-channel-methods method)
    false))

(defn- normalize-call-target
  [target]
  (if (string? target)
    {:type :identifier :name target}
    target))

(defn- declared-alias-operators
  "The set of operators bound by an `alias` clause anywhere in `class-defs`.
   Empty for every program that does not use the feature, which is what keeps
   built-in arithmetic free of any lookup cost."
  [class-defs]
  (into #{}
        (comp (mapcat tc/feature-members)
              (filter #(= (:type %) :method))
              (keep :alias))
        class-defs))

(defn make-lowering-env
  "Create the first lowering environment.

  Keys:
  - `:locals`     map of local-name -> {:slot .. :nex-type .. :jvm-type ..}
  - `:top-level?` whether names should lower to REPL state access when not local
  - `:repl?`      whether lowering is for REPL cells
  - `:state-slot` local slot holding NexReplState in compiled REPL methods
  - `:next-slot`  next free JVM local slot
  - `:classes`    visible class defs for type inference
  - `:functions`  visible top-level function defs for type inference/lowering
  - `:imports`    visible imports for type inference
  - `:var-types`  visible variable types
  - `:compiled-classes` map of Nex class-name -> emitted JVM class metadata
  - `:current-class` current Nex class name when lowering instance methods
  - `:fields` map of field-name -> {:owner .. :nex-type .. :jvm-type ..}
  - `:this-type` current receiver type when lowering instance methods
  - `:scoped-locals?` force `let` bindings to lower to JVM locals even in top-level code
  - `:retry-allowed?` whether `retry` is legal in the current lowering scope
  - `:old-field-locals` snapshot locals for `old` in postconditions
  - `:generic-param-names` visible generic parameter identifiers lowered as JVM Object
  - `:generic-runtime-values` map of generic parameter name -> IR expression that yields
    the runtime type token string for that parameter
  - `:with-java?` whether unresolved target calls should lower as JVM host interop
  - `:skip-contracts?` whether require/ensure/invariant checks lower to no-ops"
  ([] (make-lowering-env {}))
  ([{:keys [locals top-level? repl? state-slot next-slot classes functions imports var-types
            compiled-classes current-class fields this-type old-field-locals
            generic-param-names generic-param-constraints generic-runtime-values
            with-java? across-cursors globals skip-contracts?] :as opts}]
   {:locals (or locals {})
    :top-level? (if (contains? opts :top-level?) top-level? true)
    :repl? (if (contains? opts :repl?) repl? true)
    :state-slot (or state-slot 0)
    :next-slot (or next-slot 1)
    :classes (vec (or classes []))
    ;; Which operators any visible class binds with `alias`. Computed once here so
    ;; that lowering an ordinary `a + b` on Integer costs one set membership test
    ;; — and, in the overwhelming case of a program that aliases nothing, the set
    ;; is empty and no operand type is ever inferred for this purpose.
    :aliased-operators (declared-alias-operators (or classes []))
    :functions (vec (or functions []))
    :imports (vec (or imports []))
    :var-types (or var-types {})
    :compiled-classes (or compiled-classes {})
    :current-class current-class
    :fields (or fields {})
    :this-type this-type
    :scoped-locals? false
    :retry-allowed? false
    :old-field-locals (or old-field-locals {})
    :generic-param-names (set generic-param-names)
    :generic-param-constraints (or generic-param-constraints {})
    :generic-runtime-values (or generic-runtime-values {})
    :with-java? (boolean with-java?)
    :across-cursors (or across-cursors {})
    ;; Top-level `let` globals (name -> nex-type), readable from the static world
    ;; (§7). In a non-top-level body an otherwise-unknown identifier that names a
    ;; global lowers to a `top-get` against the live session state. Defaults to the
    ;; program-wide dynamic var so nested body envs pick globals up automatically.
    :globals (or globals *top-level-globals*)
    :skip-contracts? (boolean (or skip-contracts? *skip-contracts?*))}))

(defn- string-jvm-type
  []
  (ir/object-jvm-type "java/lang/String"))

(defn- generic-runtime-field-name
  [generic-name]
  (str "__generic_type_" generic-name))

(defn- generic-runtime-param-name
  [generic-name]
  (str "__generic_type_arg_" generic-name))

(defn- resolve-jvm-type
  [env nex-type]
  (let [nex-type (resolve-type-alias nex-type)
        base (base-type-name nex-type)]
    (cond
      (contains? (:generic-param-names env) base)
      (ir/object-jvm-type "java/lang/Object")

      (if-let [compiled (get (:compiled-classes env) base)] true false)
      (ir/object-jvm-type "java/lang/Object")

      (true? (:closure-runtime-object? (get (visible-class-map env) base)))
      (ir/object-jvm-type "java/lang/Object")

      (imported-java-qualified-name env base)
      (ir/object-jvm-type (desc/internal-class-name (imported-java-qualified-name env base)))

      :else
      (desc/nex-type->jvm-type nex-type))))

(defn- exact-class-jvm-type
  [env class-name]
  (ir/object-jvm-type (:internal-name (class-jvm-meta env class-name))))

(defn- generic-runtime-field-ir
  [env class-name generic-name]
  (ir/field-get-node (:internal-name (class-jvm-meta env class-name))
                     (generic-runtime-field-name generic-name)
                     (ir/this-node class-name
                                   (exact-class-jvm-type env class-name))
                     "String"
                     (string-jvm-type)))

(defn- generic-runtime-field-bindings
  [env class-name generic-params]
  (if (and (seq generic-params)
           (get (:compiled-classes env) class-name))
    (into {}
          (map (fn [{:keys [name]}]
                 [name (generic-runtime-field-ir env class-name name)]))
          generic-params)
    {}))

(defn- runtime-type-token-ir
  [env nex-type]
  (let [base (base-type-name nex-type)]
    (cond
      (and (string? base)
           (contains? (:generic-param-names env) base))
      (or (get (:generic-runtime-values env) base)
          ;; Top-level helper functions can use free generic names such as K/V
          ;; without having a reified receiver object to carry runtime type
          ;; tokens. Those generics are erased for runtime construction.
          (ir/const-node "Any" "String" (string-jvm-type)))

      (string? base)
      (ir/const-node base "String" (string-jvm-type))

      :else
      (ir/const-node "Any" "String" (string-jvm-type)))))

(defn- class-generic-runtime-args
  [env class-def target-type]
  (let [generic-params (:generic-params class-def)
        target-args (vec (or (generic-type-args target-type) []))]
    (mapv (fn [idx _]
            (runtime-type-token-ir env (or (nth target-args idx nil) "Any")))
          (range (count generic-params))
          generic-params)))

(defn- parent-generic-runtime-args
  [env class-def parent-name]
  (let [parent-ref (some #(when (= parent-name (:parent %)) %) (:parents class-def))]
    (mapv #(runtime-type-token-ir env %)
          (or (:generic-args parent-ref) []))))

(defn- own-class-generic-runtime-args
  "Runtime type-witness args for a call that stays on `this`'s own class (a
   sibling-constructor delegation, `this.ctor(...)`) — as opposed to
   `parent-generic-runtime-args`, which translates through an `inherit
   Parent[...]` clause's generic-args mapping into the *parent's* params.
   There is no such mapping to walk here: the callee is the exact same
   (possibly generic) class as the caller, so each of its own params' runtime
   token is simply whatever `this` is already carrying for that name in
   `(:generic-runtime-values env)`, looked up via `runtime-type-token-ir`."
  [env class-def]
  (mapv #(runtime-type-token-ir env (:name %)) (:generic-params class-def)))

(defn- java-host-class-root-name
  [env expr]
  (when (and (:with-java? env)
             (= :identifier (:type expr))
             (not (get (:locals env) (:name expr)))
             (not (get (:fields env) (:name expr)))
             (not (contains? (:var-types env) (:name expr))))
    (or (imported-java-qualified-name env (:name expr))
        (when (re-matches #"[A-Z][A-Za-z0-9_]*" (:name expr))
          (:name expr)))))

(defn- with-stmt-debug
  [ir-node stmt]
  (ir/with-debug ir-node stmt))

(defn- env-visible-var-types
  [env]
  (merge (:var-types env)
         (into {}
               (map (fn [[name {:keys [nex-type]}]]
                      [name nex-type])
                    (:locals env)))))

(declare infer-type)

(defn- infer-type-or-any
  "`infer-type`, but an expression it cannot type widens to Any instead of
   aborting the compile. Used where a type is being *compared* rather than
   relied on: one entry of a map literal whose type is unknown cannot be shown
   to agree with the others, and Any is exactly that conclusion."
  [env expr]
  (try
    (infer-type env expr)
    (catch clojure.lang.ExceptionInfo _ "Any")))

(defn- convert-branch-env
  "`env` narrowed by a `convert x to name: T` condition: `name` reads as `T`
   for the rest of the branch it guards. A no-op for any other condition
   shape."
  [env' condition]
  (if (= :convert (:type condition))
    (assoc-in env' [:var-types (:var-name condition)] (:target-type condition))
    env'))

(defn- infer-type-identifier
  [env expr]
  (or (get-in (:locals env) [(:name expr) :nex-type])
      (get-in (:fields env) [(:name expr) :nex-type])
      (some-> (current-class-def env)
              (class-field-def (:name expr))
              :field-type)
      (some-> (current-class-def env)
              ((fn [class-def]
                 (or (class-method-def class-def (:name expr) 0)
                     (inherited-method-def env class-def (:name expr) 0))))
              function-return-type)
      (some-> (and (:current-class env)
                   (lookup-class-constant env (:current-class env) (:name expr)))
              (#(constant-nex-type env %)))
      (get (:var-types env) (:name expr))
      ;; A readable top-level global (§7).
      (get (:globals env) (:name expr))))

(defn- infer-type-create
  [_env expr]
  (if (seq (:generic-args expr))
    {:base-type (:class-name expr) :type-args (:generic-args expr)}
    (:class-name expr)))

(defn- infer-type-this
  [env _expr]
  (:this-type env))

;; Falling through to the generic tc/infer-expression-type fallback below (as
;; this used to) builds a fresh env with no current-class context (same gap
;; :when's own handler works around) — a `this`/bare field or method
;; reference inside the body fails to resolve there. It also has no notion of
;; the spawn body's *own* top-level `let` bindings, so a `result := total`
;; closing over an earlier `let total := ...` in the same body fails to
;; resolve either way. Track those bindings here instead, against *this* env
;; (so :this-type/:fields context carries through), and infer result's type
;; directly from its `result := ...` assignment — the same "Any" when there
;; is none matches make-synthetic-anonymous-function-expr's declared return
;; type for a spawn with no result.
(defn- infer-type-spawn
  [env expr]
  (let [local-var-types
        (reduce (fn [acc stmt]
                  (if (= :let (:type stmt))
                    (assoc acc (:name stmt)
                           (or (:var-type stmt)
                               (infer-type (update env :var-types merge acc) (:value stmt))))
                    acc))
                {}
                (:body expr))
        result-env (update env :var-types merge local-var-types)]
    (if-let [result-assign (some #(when (and (= :assign (:type %))
                                             (= "result" (:target %)))
                                    %)
                                 (:body expr))]
      {:base-type "Task" :type-params [(infer-type result-env (:value result-assign))]}
      "Task")))

(defn- infer-type-binary
  [env expr]
  (let [op (:operator expr)]
    (cond
      (#{"-" "*" "/" "%"} op) (let [left-type (infer-type env (:left expr))
                                    right-type (infer-type env (:right expr))]
                                (if (and (tc/is-numeric-type? left-type)
                                         (tc/is-numeric-type? right-type))
                                  (tc/numeric-result-type left-type right-type)
                                  left-type))
      (= "+" op) (let [left-type (infer-type env (:left expr))
                       right-type (infer-type env (:right expr))]
                   (if (or (= "String" (base-type-name left-type))
                           (= "String" (base-type-name right-type)))
                     "String"
                     (if (and (tc/is-numeric-type? left-type)
                              (tc/is-numeric-type? right-type))
                       (tc/numeric-result-type left-type right-type)
                       left-type)))
      (= "^" op) (tc/power-result-type (infer-type env (:left expr))
                                       (infer-type env (:right expr)))
      (#{"and" "or" "=" "/=" "==" "!=" "<" "<=" ">" ">="} op) "Boolean"
      :else nil)))

(defn- infer-type-unary
  [env expr]
  (case (:operator expr)
    "-" (infer-type env (:expr expr))
    "not" "Boolean"
    nil))

;; `old e` is e's value on entry, so it is e's type. Without this the fallback
;; below had to infer `old balance` from an env with no class context, and
;; only resolved the field because collect-class-info leaked every field name
;; into it as a global.
(defn- infer-type-old
  [env expr]
  (infer-type env (:expr expr)))

(defn- infer-type-array-literal
  [env expr]
  (let [elements (:elements expr)
        elem-type (or (some->> elements first (infer-type env))
                      "Any")]
    (array-type-of elem-type)))

;; A map literal's value type is the entries' common type, widening to Any as
;; soon as two disagree — the rule check-map-literal enforces. Taking the
;; first entry's type instead lowered `{"label": "Total", "amount": 0}` as a
;; Map[String, String], and every later read of it was typed as though the
;; value were a String.
;;
;; Array and Set literals need no such widening: the typechecker rejects a
;; mixed one outright, so the first element speaks for all.
(defn- infer-type-map-literal
  [env expr]
  (let [entries (:entries expr)
        key-type (or (some->> entries first :key (infer-type env))
                     "Any")
        value-type (or (reduce (fn [acc entry]
                                 (let [t (infer-type-or-any env (:value entry))]
                                   (if (= (tc/normalize-type acc) (tc/normalize-type t))
                                     acc
                                     (reduced "Any"))))
                               (some->> entries first :value (infer-type-or-any env))
                               (rest entries))
                       "Any")]
    (map-type-of key-type value-type)))

(defn- infer-type-set-literal
  [env expr]
  (let [elements (:elements expr)
        elem-type (or (some->> elements first (infer-type env))
                      "Any")]
    (set-type-of elem-type)))

(defn- infer-type-if
  [env expr]
  (let [then-env (refine-condition-branch-env (convert-branch-env env (:condition expr))
                                              (:condition expr)
                                              :then)
        else-env (refine-condition-branch-env env (:condition expr) :else)]
    (or (some-> (:then expr) (if-branch-expression then-env) (infer-type then-env))
        (some-> (:else expr) (if-branch-expression else-env) (infer-type else-env)))))

;; A `when ... then ... else ... end` expression's branches are plain
;; expressions (not blocks), unlike `:if`'s `:then`/`:else` — no
;; `if-branch-expression` unwrapping needed. Falling through to the generic
;; `tc/infer-expression-type` fallback below used to be the only path here,
;; but that fallback builds a fresh env with no current-class context, so a
;; bare field reference in the condition or either branch (`total_seconds`
;; meaning `this.total_seconds`) couldn't resolve and the whole inference
;; silently failed.
(defn- infer-type-when
  [env expr]
  (let [then-env (refine-condition-branch-env (convert-branch-env env (:condition expr))
                                              (:condition expr)
                                              :then)
        else-env (refine-condition-branch-env env (:condition expr) :else)
        cons-type (infer-type-or-any then-env (:consequent expr))
        alt-type (infer-type-or-any else-env (:alternative expr))]
    (cond
      (= alt-type "Nil") cons-type
      (= cons-type "Nil") alt-type
      :else cons-type)))

(def ^:private infer-type-dispatch
  "AST node `:type` -> `(fn [env expr] ...)`: the primary (non-fallback) half
   of `infer-type`. A literal's type is context-free — resolved directly
   here rather than through tc/infer-expression-type, whose best-effort env
   can throw (and silently yield nil) on unrelated classes, e.g. a sibling
   constant that forward-references a not-yet-collected class. A node type
   with no entry here (or whose handler returns nil) falls through to the
   generic fallback in `infer-type`."
  {:integer            (constantly "Integer")
   :real               (constantly "Real")
   :string             (constantly "String")
   :boolean            (constantly "Boolean")
   :char               (constantly "Char")
   :identifier         infer-type-identifier
   :create             infer-type-create
   :this               infer-type-this
   ;; A bare "Function" string here (the pre-fix shape) discards the
   ;; lambda's own signature — so a `let good_enough := fn(g: Real):
   ;; Boolean do ... end` local's inferred nex-type carried no
   ;; :return-type, and a later `good_enough(guess)` call
   ;; (infer-call-type -> function-object-binding-type) fell into the "no
   ;; map, so Any" branch, lowering the call's :jvm-type as Object instead
   ;; of :boolean. Used directly as an `if`/`when` test, that Object value
   ;; reached emit-stmt-if!'s boolean check unconverted and crashed with
   ;; "If statement test did not lower to boolean" — a real bug only in
   ;; the compiled backend (the interpreter has no such static jvm-type
   ;; step). Mirrors the richer shape
   ;; nex.typechecker/anonymous-function-provisional-signature already
   ;; uses for the identical reason on the typechecking side.
   :anonymous-function (fn [_ expr]
                         {:base-type "Function"
                          :param-types (mapv (fn [p] {:name (:name p) :type (or (:type p) "Any")})
                                             (:params expr))
                          :return-type (or (:return-type expr) "Any")})
   :spawn              infer-type-spawn
   :binary             infer-type-binary
   :unary              infer-type-unary
   :old                infer-type-old
   :array-literal      infer-type-array-literal
   :map-literal        infer-type-map-literal
   :set-literal        infer-type-set-literal
   :if                 infer-type-if
   :when               infer-type-when
   ;; infer-call-type is only forward-declared this early in the file (its
   ;; own defn comes later) — a bare reference here would capture the
   ;; declare's Unbound placeholder instead of the real function, since a
   ;; map literal's values are dereferenced immediately. Wrapping it defers
   ;; that lookup to call time, by which point the whole file has loaded.
   :call               (fn [env expr] (infer-call-type env expr))})

(defn- infer-type
  [env expr]
  (let [handler (get infer-type-dispatch (:type expr))
        direct-type (when handler (handler env expr))]
    (or direct-type
        (tc/infer-expression-type expr {:classes (:classes env)
                                        :functions (:functions env)
                                        :imports (:imports env)
                                        ;; Globals (§7) are visible to this fallback
                                        ;; too, so a chain rooted at a global
                                        ;; receiver (`con.read_line.to_integer`)
                                        ;; whose tail the primary path can't type
                                        ;; still resolves. Locals win over globals.
                                        :var-types (merge (:globals env)
                                                          (env-visible-var-types env))
                                        ;; Without these an aliased receiver
                                        ;; (`let t: Tid := ...`; `Tid = String`)
                                        ;; infers as nil here and lowering fails
                                        ;; with "Unable to infer expression type".
                                        :type-aliases *type-aliases*
                                        ;; Without this, a bare implicit-`this`
                                        ;; call nested inside an expression that
                                        ;; reaches this fallback (e.g. the target
                                        ;; of a builtin scalar method this
                                        ;; namespace's own primary path doesn't
                                        ;; know, like `(a * mult).round`) can't
                                        ;; resolve `mult` and the whole inference
                                        ;; fails with "Unable to infer expression
                                        ;; type during lowering".
                                        :current-class (:this-type env)})
        ;; A bare identifier that names an imported Java class resolves fine
        ;; as a call target inside `with "java"` (java-host-class-root-name
        ;; requires :with-java?, by design — see docs/proposals/java-interop.md
        ;; and the java-interop chapter of the language Definition), but
        ;; outside one it is just an unresolved identifier, and reaches this
        ;; generic fallback. The typechecker accepts such a program regardless
        ;; (its own fallback for an unresolved call is looser than lowering's,
        ;; which must pick a concrete emission strategy, not just a type), so
        ;; this is not a compiler defect in the sense the rest of this
        ;; function's callers are — it is a real, nameable gap with a real
        ;; workaround, and is reported as one rather than as "please report
        ;; this bug".
        (if (and (= (:type expr) :identifier)
                 (imported-java-qualified-name env (:name expr)))
          (throw (unsupported
                  (str "`" (:name expr) "` is an imported Java class; used here, "
                       "outside a `with \"java\"` block, it cannot be resolved as "
                       "a call target. Wrap this code in `with \"java\" do ... end`.")
                  {:expr expr}))
          (throw (ex-info "Unable to infer expression type during lowering"
                          {:expr expr}))))))

(defn- infer-super-call-type
  [env expr]
  (let [parent-name (single-super-parent-name env)
        parent-def (get (visible-class-map env) parent-name)]
    (if (false? (:has-parens expr))
      (or (some-> (class-field-def parent-def (:method expr))
                  :field-type)
          (some-> (class-method-def parent-def (:method expr) 0)
                  function-return-type)
          (some-> (inherited-method-def env parent-def (:method expr) 0)
                  function-return-type))
      (or (some-> (class-method-def parent-def (:method expr) (count (:args expr)))
                  function-return-type)
          (some-> (inherited-method-def env parent-def (:method expr) (count (:args expr)))
                  function-return-type)))))

(defn- infer-instance-call-type
  [env expr class-target-name across-item-type target-expr]
  (let [java-static-owner (java-host-class-root-name env target-expr)
          ;; Through the alias: a receiver declared with an alias or a
          ;; refinement (`declare type Tracking_Id = String where ...`) carries
          ;; the *alias* name here, which names no class and no builtin. The
          ;; underlying type is what owns the method, and what the value
          ;; actually is at runtime — refinements are erased by lowering time,
          ;; their checks already inserted at the narrowing sites.
        target-type (when (and (not class-target-name)
                               (not java-static-owner))
                      (resolve-type-alias (infer-type env target-expr)))
        base-type (base-type-name target-type)
        class-def (or (when class-target-name
                        (get (visible-class-map env) class-target-name))
                      (get (visible-class-map env) base-type))
        field-def (when (and class-def (false? (:has-parens expr)))
                    (if (= (:type target-expr) :this)
                      (class-field-def class-def (:method expr))
                      (accessible-field-def env class-def (:method expr))))
        method-def (when class-def
                     (if (= (:type target-expr) :this)
                       (or (class-method-def class-def (:method expr) (count (:args expr)))
                           (inherited-method-def env class-def (:method expr) (count (:args expr))))
                       (accessible-method-def env class-def (:method expr) (count (:args expr)))))
          ;; Built once the member is known: an inherited member's types are stated
          ;; in its declaring class's generic params, not the receiver's.
        type-map (generic-type-map env target-type
                                   (:declaring-class (or method-def field-def)))]
    (or
     (when across-item-type
       (case (:method expr)
         "item" across-item-type
         "start" "Void"
         "next" "Void"
         "at_end" "Boolean"
         "cursor" "Cursor"
         nil))
     (when (or java-static-owner (:with-java? env))
       "Any")
     (when class-def
       (if (and class-target-name (false? (:has-parens expr)))
         (some-> (lookup-class-constant env class-target-name (:method expr))
                 (#(constant-nex-type env %)))
         (if (:import class-def)
           "Any"
           (if (false? (:has-parens expr))
             (or (some-> field-def
                         :field-type
                         (#(tc/resolve-generic-type % type-map)))
                 (some-> method-def
                         function-return-type
                         (#(tc/resolve-generic-type % type-map))))
             (some-> method-def
                     function-return-type
                     (#(tc/resolve-generic-type % type-map)))))))
     (when (direct-collection-method? target-type (:method expr))
       (collection-method-return-type target-type (:method expr)))
     (when (contains? #{"Console" "Process"} base-type)
       (bi/builtin-type-method-return-type (keyword base-type) (:method expr)))
       ;; Generic type parameter with constraint - look up method on constraint type
     (when-let [constraint (get (:generic-param-constraints env) base-type)]
       (case constraint
         "Comparable"
         (case (:method expr)
           "compare" "Integer"
           nil)
         "Hashable"
         (case (:method expr)
           "hash" "Integer"
           nil)
         nil))
       ;; The Any/Comparable/Hashable protocols (spec B.1): every value renders
       ;; with to_string and compares with equals, so their types are known even
       ;; when the receiver is a builtin scalar with no per-method type table.
     (case (:method expr)
       "to_string" "String"
       "equals" "Boolean"
       "not_equals" "Boolean"
       "hash" "Integer"
       "compare" "Integer"
       nil))))

(defn- infer-target-call-type
  [env expr class-target-name across-item-type target-expr]
  (if (= :super (:type target-expr))
    (infer-super-call-type env expr)
    (infer-instance-call-type env expr class-target-name across-item-type target-expr)))

(def ^:private builtin-free-function-return-types
  "Return types of the builtin free functions. Mirrors the typechecker's
   builtin-call-checkers table (typechecker.cljc) — keep the two in sync."
  {"print" "Void"
   "println" "Void"
   "sleep" "Void"
   "hint_spin" "Void"
   "exit" "Void"
   "random_real" "Real"
   "type_of" "String"
   "type_is" "Boolean"
   ;; regex
   "regex_validate" "Boolean"
   "regex_matches" "Boolean"
   "regex_find" {:base-type "String" :detachable true}
   "regex_find_all" {:base-type "Array" :type-params ["String"]}
   "regex_replace" "String"
   "regex_split" {:base-type "Array" :type-params ["String"]}
   ;; datetime
   "datetime_now" "Integer"
   "datetime_from_epoch_millis" "Integer"
   "datetime_parse_iso" "Integer"
   "datetime_make" "Integer"
   "datetime_year" "Integer"
   "datetime_month" "Integer"
   "datetime_day" "Integer"
   "datetime_weekday" "Integer"
   "datetime_day_of_year" "Integer"
   "datetime_hour" "Integer"
   "datetime_minute" "Integer"
   "datetime_second" "Integer"
   "datetime_epoch_millis" "Integer"
   "datetime_add_millis" "Integer"
   "datetime_diff_millis" "Integer"
   "datetime_truncate_to_day" "Integer"
   "datetime_truncate_to_hour" "Integer"
   "datetime_format_iso" "String"
   ;; path
   "path_exists" "Boolean"
   "path_is_file" "Boolean"
   "path_is_directory" "Boolean"
   "path_name" "String"
   "path_extension" "String"
   "path_name_without_extension" "String"
   "path_absolute" "String"
   "path_normalize" "String"
   "path_size" "Integer"
   "path_modified_time" "Integer"
   "path_parent" {:base-type "String" :detachable true}
   "path_child" "String"
   "path_create_file" "Void"
   "path_create_directory" "Void"
   "path_create_directories" "Void"
   "path_delete" "Void"
   "path_delete_tree" "Void"
   "path_copy" "Void"
   "path_move" "Void"
   "path_read_text" "String"
   "path_write_text" "Void"
   "path_append_text" "Void"
   "path_list" {:base-type "Array" :type-params ["String"]}
   ;; text files
   "text_file_open_read" "Any"
   "text_file_open_write" "Any"
   "text_file_open_append" "Any"
   "text_file_read_line" {:base-type "String" :detachable true}
   "text_file_write" "Void"
   "text_file_close" "Void"
   ;; binary files
   "binary_file_open_read" "Any"
   "binary_file_open_write" "Any"
   "binary_file_open_append" "Any"
   "binary_file_read_all" {:base-type "Array" :type-params ["Integer"]}
   "binary_file_read" {:base-type "Array" :type-params ["Integer"]}
   "binary_file_write" "Void"
   "binary_file_position" "Integer"
   "binary_file_seek" "Void"
   "binary_file_close" "Void"
   ;; http client / json
   "json_parse" "Any"
   "json_stringify" "String"
   ;; http server
   "http_server_create" "Any"
   "http_server_get" "Void"
   "http_server_post" "Void"
   "http_server_put" "Void"
   "http_server_delete" "Void"
   "http_server_start" "Integer"
   "http_server_stop" "Void"
   "http_server_is_running" "Boolean"})

(defn- infer-call-type
  [env expr]
  (let [raw-target (:target expr)
        class-target-name (when (string? raw-target)
                            (some #(when (= (:name %) raw-target)
                                     (:name %))
                                  (:classes env)))
        across-item-type (and (string? raw-target)
                              (get (:across-cursors env) raw-target))
        target-expr (normalize-call-target raw-target)]
    (if (nil? target-expr)
      (or
       ;; A local/parameter binding (e.g. a `Function`-typed parameter) shadows
       ;; a same-named, same-arity top-level free function, exactly like plain
       ;; lexical scoping everywhere else — so this must be checked before the
       ;; top-level-function lookup below. Otherwise a call to a Function-typed
       ;; parameter whose name happens to collide with an unrelated top-level
       ;; function of the same arity infers the top-level function's return
       ;; type instead of the parameter's own, silently mis-lowering the call's
       ;; `:jvm-type` (codegen still invokes the right value at runtime, so
       ;; this only surfaces as a bad cast/unbox on the call's result).
       (when (function-object-call? env (:method expr) (count (:args expr)))
         (let [binding-type (function-object-binding-type env (:method expr))
               base-type (base-type-name binding-type)
               call-name (str "call" (count (:args expr)))]
           (if (= "Function" base-type)
             ;; A Function value that carries an explicit signature knows its
             ;; own return type; use it rather than the generic `Any` so calls
             ;; like `if pred(x)` lower with a concrete (e.g. Boolean) type.
             (or (and (map? binding-type) (:return-type binding-type)) "Any")
             (some-> (get (visible-class-map env) base-type)
                     (class-method-def call-name (count (:args expr)))
                     function-return-type))))
       (get builtin-free-function-return-types (:method expr))
       ;; A bare call whose :method is a dot-qualified name
       ;; (nex.walker/resolve-qualified-function-calls already rewrote
       ;; `trade.ship(x)` to :method "trade.ship" before this ever runs)
       ;; only matches an interned fn-def's :qualified-name, never its bare
       ;; :name — check both, mirroring nex.typechecker/check-program's own
       ;; dual var registration for the same reason.
       (when-let [fn-def (some (fn [fn-def]
                                 (when (and (or (= (:name fn-def) (:method expr))
                                                (= (:qualified-name fn-def) (:method expr)))
                                            (= (count (or (:params fn-def) []))
                                               (count (:args expr))))
                                   fn-def))
                               (:functions env))]
         (infer-free-function-return-type env fn-def (:args expr)))
       (when (:this-type env)
         (some-> (or (class-method-def (current-class-def env) (:method expr) (count (:args expr)))
                     (inherited-method-def env (current-class-def env) (:method expr) (count (:args expr))))
                 function-return-type)))
      (infer-target-call-type env expr class-target-name across-item-type target-expr))))

(defn- env-add-local
  [env name nex-type]
  (let [jvm-type (resolve-jvm-type env nex-type)
        slot (:next-slot env)
        width (if (contains? #{:long :double} jvm-type) 2 1)]
    [(assoc env
            :locals (assoc (:locals env)
                           name
                           {:name name
                            :slot slot
                            :nex-type nex-type
                            :jvm-type jvm-type})
            :var-types (assoc (:var-types env) name nex-type)
            :next-slot (+ slot width))
     {:name name
      :slot slot
      :nex-type nex-type
      :jvm-type jvm-type}]))

(defn- lowered-function-method-name
  [fn-def]
  (str "__repl_fn_" (:name fn-def) "$arity" (count (:params fn-def))))

(defn- lowered-instance-method-name
  [method-def]
  (str "__method_" (:name method-def) "$arity" (count (:params method-def))))

(defn- lowered-top-level-function-emitted-name
  "lowered-instance-method-name for a top-level FN-DEF specifically — every
   top-level function is emitted as a direct method on the single shared
   Program class (unlike an ordinary class's methods, each already
   namespaced by living inside its own class), so two interned files
   declaring the same bare function name would otherwise emit the identical
   JVM method name here — a separate collision from the *wrapper class's*
   own name (nex.walker/qualify-interned-function-class-names).

   Only mangles the name (:qualified-name, sanitized: a JVM method name
   cannot contain '.') when qualify-interned-function-class-names actually
   renamed this fn-def's :class-name — i.e. only on a genuine collision,
   the same gating it applies and for the same reason: mangling every
   interned function's method name unconditionally, not just a colliding
   one's, broke generic free functions like data/Result's result_map (see
   qualify-interned-function-class-names's docstring). :class-name differing
   from the plain `<name>_Function` parse-time default is how a caller here
   (which only ever sees this one fn-def, not the whole merged list
   qualify-interned-function-class-names compared against) can tell that
   happened."
  [fn-def]
  (if (and (:qualified-name fn-def)
           (:class-name fn-def)
           (not= (:class-name fn-def) (str (:name fn-def) "_Function")))
    (lowered-instance-method-name
     (assoc fn-def :name (str/replace (:qualified-name fn-def) "." "_")))
    (lowered-instance-method-name fn-def)))

(defn- lowered-constructor-method-name
  [ctor-def]
  (str "__ctor_" (:name ctor-def) "$arity" (count (:params ctor-def))))

(defn- generic-init-method-name
  []
  "__generic_init$arity0")

(defn- function-return-type
  [fn-def]
  (or (:return-type fn-def) "Void"))

(defn- top-level-function-callable
  [fn-def]
  (when-let [class-def (:class-def fn-def)]
    (some (fn [member]
            (when (and (= :method (:type member))
                       (= (count (or (:params member) []))
                          (count (or (:params fn-def) []))))
              member))
          (mapcat :members
                  (filter #(= :feature-section (:type %))
                          (:body class-def))))))

(defn- normalized-function-def
  [fn-def]
  (if-let [callable (and (= :function (:type fn-def))
                         (top-level-function-callable fn-def))]
    (merge callable fn-def)
    fn-def))

(defn- type-name-set
  [t]
  (cond
    (string? t) #{t}
    (map? t) (set (concat (when-let [base (:base-type t)] [base])
                          (mapcat type-name-set (or (:type-args t) (:type-params t) []))))
    :else #{}))

(defn- generic-param-constraint-map
  "Build a map from generic parameter name to its constraint type.
   E.g. for [T -> Comparable] returns {\"T\" \"Comparable\"}."
  [generic-params]
  (into {} (keep (fn [{:keys [name constraint]}]
                   (when constraint
                     [name constraint]))
                 generic-params)))

(defn- free-function-generic-param-names
  [visible-classes fn-def]
  (let [class-names (set (keep :name visible-classes))
        names (set (concat (mapcat (comp type-name-set :type) (:params fn-def))
                           (type-name-set (:return-type fn-def))))]
    (set (remove #(or (contains? class-names %)
                      (tc/builtin-type? %)
                      (str/starts-with? % "__"))
                 names))))

(defn- merge-inferred-generic-bindings
  [env left right]
  (reduce-kv
   (fn [acc generic-name inferred-type]
     (if-let [existing (get acc generic-name)]
       (if (or (tc/types-equal? env existing inferred-type)
               (tc/types-compatible? env inferred-type existing)
               (tc/types-compatible? env existing inferred-type))
         acc
         left)
       (assoc acc generic-name inferred-type)))
   left
   right))

(defn- lower-ancestor-instantiation
  "Like nex.typechecker's own (private) ancestor-instantiation, adapted to
   this file's env shape (visible-class-map, not the typechecker's own
   atom-backed env-lookup-class): walk SUB-NAME's inheritance chain looking
   for SUPER-NAME, substituting generic arguments through each `inherit`
   clause (handling arguments that are threaded, reordered, or nested), so
   a subclass with a differently-parameterized ancestor can still be
   matched against that ancestor's own type. Returns the type arguments
   SUB-NAME's chain supplies to SUPER-NAME, or nil when SUPER-NAME is not
   an ancestor."
  ([env sub-name sub-args super-name]
   (lower-ancestor-instantiation env sub-name sub-args super-name #{}))
  ([env sub-name sub-args super-name seen]
   (cond
     (= sub-name super-name) (vec sub-args)
     (contains? seen sub-name) nil
     :else
     (when-let [class-def (get (visible-class-map env) sub-name)]
       (let [gparams (map :name (:generic-params class-def))
             subst (zipmap gparams sub-args)
             seen (conj seen sub-name)]
         (some (fn [{:keys [parent generic-args]}]
                 (lower-ancestor-instantiation
                  env parent
                  (mapv #(tc/resolve-generic-type % subst) generic-args)
                  super-name seen))
               (:parents class-def)))))))

(declare infer-generic-type-map-from-arg)

;; The argument is a bare class name -- a free function passed by
;; reference, or an anonymous lambda's generated wrapper class (see the
;; analogous branch in nex.typechecker/types-compatible?) -- rather than
;; an already-structural Function type. Resolve its callN method to an
;; equivalent structural shape before unifying against param-type.
(defn- infer-generic-map-from-function-ref-arg
  [env generic-names param-type arg-type]
  (if-let [method-sig (class-method-def (get (visible-class-map env) arg-type)
                                        (str "call" (count (:param-types param-type)))
                                        (count (:param-types param-type)))]
    (infer-generic-type-map-from-arg
     env generic-names param-type
     {:base-type "Function"
      :param-types (mapv (fn [p] {:name (:name p) :type (:type p)}) (:params method-sig))
      :return-type (:return-type method-sig)})
    {}))

;; A Function-typed param/arg carries its generic-relevant substructure
;; under :param-types/:return-type, not :type-params/:type-args (those
;; are how Array[T]/Map[K,V]/user generic classes are shaped) -- so a
;; generic parameter appearing only in a Function value's parameter or
;; return position (e.g. `f: Function(v: G): T` matched against an
;; argument lambda `fn(v: Integer): String`) must be unified here
;; explicitly, or its binding is silently missed. A missed return-type
;; binding leaves T unsubstituted in infer-free-function-return-type,
;; which then leaks the literal generic name "T" into codegen as if it
;; were a real class -- the jar compiles but crashes with
;; NoClassDefFoundError: T at run time.
(defn- infer-generic-map-from-function-arg
  [env generic-names param-type arg-type]
  (let [param-params (or (:param-types param-type) [])
        arg-params (or (:param-types arg-type) [])]
    (if (= (count param-params) (count arg-params))
      (reduce (fn [acc [pt at]]
                (merge-inferred-generic-bindings
                 env acc (infer-generic-type-map-from-arg env generic-names pt at)))
              {}
              (cond-> (mapv (fn [pp ap] [(:type pp) (:type ap)]) param-params arg-params)
                (and (:return-type param-type) (:return-type arg-type))
                (conj [(:return-type param-type) (:return-type arg-type)])))
      {})))

(defn- infer-generic-map-from-parameterized-arg
  [env generic-names param-type arg-type]
  (let [param-args (vec (or (:type-params param-type) (:type-args param-type)))
        arg-args (vec (or (:type-params arg-type) (:type-args arg-type)))]
    (if (= (count param-args) (count arg-args))
      (reduce (fn [acc [param-arg arg-arg]]
                (merge-inferred-generic-bindings
                 env acc (infer-generic-type-map-from-arg env generic-names param-arg arg-arg)))
              {}
              (map vector param-args arg-args))
      {})))

;; The argument's own class differs from the declared parameter's --
;; `first_field[T](b: Base[T, Any])` called with a `Mid[String,
;; Integer]` argument, where `Mid[P, Q] inherit Base[Q, P]` -- so T
;; can still be inferred, but only by walking ARG-TYPE's ancestor
;; chain up to PARAM-TYPE's class (ancestor-instantiation, already
;; used for the identical reasoning in generic subtype conformance),
;; substituting through however each `inherit` clause reorders or
;; nests its own generic arguments along the way, and unifying
;; PARAM-TYPE's own args against what that walk resolves to instead
;; of ARG-TYPE's own (unrelated) ones. Without this, an unrelated
;; base-type mismatch here fell straight to the final `:else {}` —
;; no binding at all for T — which left the literal generic name "T"
;; unsubstituted in infer-free-function-return-type's caller, and
;; that leaked into codegen as if "T" were a real class name: the
;; jar compiled but crashed with NoClassDefFoundError: T at run time.
(defn- infer-generic-map-from-inherited-arg
  [env generic-names param-type arg-type]
  (let [param-args (vec (or (:type-params param-type) (:type-args param-type)))
        arg-base (if (map? arg-type) (:base-type arg-type) arg-type)
        arg-args (if (map? arg-type) (vec (or (:type-params arg-type) (:type-args arg-type))) [])
        inherited-args (lower-ancestor-instantiation env arg-base arg-args
                                                     (:base-type param-type) #{})]
    (if (and inherited-args (= (count inherited-args) (count param-args)))
      (reduce (fn [acc [param-arg inherited-arg]]
                (merge-inferred-generic-bindings
                 env acc (infer-generic-type-map-from-arg env generic-names param-arg inherited-arg)))
              {}
              (map vector param-args inherited-args))
      {})))

(defn- infer-generic-type-map-from-arg
  [env generic-names param-type arg-type]

  (let [param-type (tc/normalize-type param-type)
        arg-type (tc/normalize-type arg-type)]
    (cond

      (and (string? param-type) (contains? generic-names param-type))
      {param-type arg-type}

      (and (map? param-type) (= (:base-type param-type) "Function") (:param-types param-type)
           (string? arg-type))
      (infer-generic-map-from-function-ref-arg env generic-names param-type arg-type)

      (and (map? param-type) (map? arg-type)
           (= (:base-type param-type) (:base-type arg-type))
           (= (:base-type param-type) "Function"))
      (infer-generic-map-from-function-arg env generic-names param-type arg-type)

      (and (map? param-type) (map? arg-type)
           (= (:base-type param-type) (:base-type arg-type)))
      (infer-generic-map-from-parameterized-arg env generic-names param-type arg-type)

      (and (map? param-type)
           (string? (:base-type param-type))
           ;; ARG-TYPE is a bare string, not a map, whenever the argument's
           ;; own class isn't itself generic (`Leaf`, with no `[...]` of
           ;; its own, inheriting a concrete `Mid[String, Integer]`) --
           ;; unlike PARAM-TYPE, which is a map here precisely because it
           ;; IS parameterized (`Base[T, Any]`). Treated as that same class
           ;; with zero type-args of its own, same as a map-shaped
           ;; arg-type with a nil/empty :type-args would be.
           (or (map? arg-type) (string? arg-type))
           (string? (if (map? arg-type) (:base-type arg-type) arg-type))
           (not (contains? generic-names (if (map? arg-type) (:base-type arg-type) arg-type))))
      (infer-generic-map-from-inherited-arg env generic-names param-type arg-type)

      :else
      {})))

(defn- argument-type-for-generic-inference
  "Like infer-type, but for an inline `fn(...) ... end` argument this returns
   its own declared Function shape (param types + return type) instead of
   infer-type's blanket \"Function\" string (see the :anonymous-function case
   of infer-type-*, which erases the signature entirely). Without this, a
   generic parameter that only appears in the lambda's own signature -- e.g.
   `f: Function(v: G): T` matched against an argument `fn(v: Integer): String
   ...` -- can never be unified by infer-generic-type-map-from-arg, because
   there is nothing left in \"Function\" (the string) to recurse into."
  [env arg]
  (if (and (map? arg) (= :anonymous-function (:type arg)))
    {:base-type "Function"
     :param-types (mapv (fn [p] {:name (:name p) :type (:type p)}) (or (:params arg) []))
     :return-type (:return-type arg)}
    (infer-type env arg)))

(defn- infer-free-function-return-type
  [env fn-def args]
  (let [fn-def (normalized-function-def fn-def)
        generic-names (set (concat (map :name (:generic-params fn-def))
                                   (free-function-generic-param-names (:classes env) fn-def)))
        type-map (reduce (fn [acc [param arg]]
                           (merge-inferred-generic-bindings
                            env
                            acc
                            (infer-generic-type-map-from-arg
                             env
                             generic-names
                             (:type param)
                             (argument-type-for-generic-inference env arg))))
                         {}
                         (map vector (:params fn-def) args))]
    (tc/resolve-generic-type (function-return-type fn-def) type-map)))

(declare implicit-if-expression?)

(defn- if-branch-expression
  [env branch]
  (when (= 1 (count branch))
    (let [stmt (first branch)]
      (cond
        (and (contains? expression-node-types (:type stmt))
             (or (not= :if (:type stmt))
                 (implicit-if-expression? env stmt))
             (not= "Void" (infer-type env stmt)))
        stmt

        (and (= :assign (:type stmt))
             (= "result" (:target stmt)))
        (:value stmt)

        :else
        nil))))

(defn- implicit-if-expression?
  [env stmt]
  (when (= :if (:type stmt))
    (let [then-env (refine-condition-branch-env
                    (if (= :convert (get-in stmt [:condition :type]))
                      (assoc-in env [:var-types (get-in stmt [:condition :var-name])]
                                (get-in stmt [:condition :target-type]))
                      env)
                    (:condition stmt)
                    :then)
          else-env (refine-condition-branch-env env (:condition stmt) :else)]
      (and (some? (if-branch-expression then-env (:then stmt)))
           (if-let [clause (first (:elseif stmt))]
             (implicit-if-expression?
              else-env
              {:type :if
               :condition (:condition clause)
               :then (:then clause)
               :elseif (vec (rest (:elseif stmt)))
               :else (:else stmt)})
             (some? (if-branch-expression else-env (:else stmt))))))))

(defn- scoped-env
  [env child-env]
  (assoc env :next-slot (:next-slot child-env)))

(defn- scoped-child-env
  [env]
  (assoc env :scoped-locals? true))

(defn- alloc-temp-slot
  [env]
  (let [slot (:next-slot env)]
    [(update env :next-slot inc) slot]))

(defn- env-add-local-alias
  [env alias {:keys [slot nex-type jvm-type]}]
  (-> env
      (assoc-in [:locals alias] {:name alias
                                 :slot slot
                                 :nex-type nex-type
                                 :jvm-type jvm-type})
      (assoc-in [:var-types alias] nex-type)))

(defn- references-old?
  [node]
  (cond
    (nil? node) false
    (sequential? node) (boolean (some references-old? node))
    (map? node) (or (= :old (:type node))
                    (boolean (some references-old? (vals node))))
    :else false))

(defn- snapshot-source-ir
  [env {:keys [owner field carrier-path nex-type jvm-type]}]
  (ir/field-get-node (:internal-name (class-jvm-meta env owner))
                     field
                     (carrier-path-target-ir env carrier-path
                                             (ir/this-node (:this-type env)
                                                           (exact-class-jvm-type env (:this-type env))))
                     nex-type
                     jvm-type))

(defn- assertion-ir
  "Lower a require/ensure/invariant/class-invariant clause to an assert IR
   node, or to a no-op when :skip-contracts? is set. Bare `assert` (kind
   :assert) is never elided here — per spec, \"there is no mode that strips
   them\" — callers for it pass a distinct kind precisely so this guard
   leaves it alone."
  [env kind {:keys [label condition]}]
  (if (and (:skip-contracts? env) (not= kind :assert))
    (ir/block-node [])
    (ir/assert-node kind label (lower-expression env condition))))

(defn- function-root-class?
  [env class-name]
  (let [class-def (get (visible-class-map env) class-name)]
    (boolean
     (and class-def
          (seq (:parents class-def))
          (every? #(= "Function" (:parent %)) (:parents class-def))))))

(defn- validate-object-state-ir
  [env class-name object-ir nex-type]
  (if (function-root-class? env class-name)
    object-ir
    (ir/call-runtime-node "validate-object-state"
                          [(ir/const-node class-name
                                          "String"
                                          (ir/object-jvm-type "java/lang/String"))
                           object-ir]
                          nex-type
                          (resolve-jvm-type env nex-type))))

(defn- default-const-node
  [nex-type jvm-type]
  (case jvm-type
    :int (ir/const-node 0 nex-type :int)
    :long (ir/const-node 0 nex-type :long)
    :double (ir/const-node 0.0 nex-type :double)
    :boolean (ir/const-node false "Boolean" :boolean)
    :char (ir/const-node (char 0) "Char" :char)
    (ir/const-node nil "Any" (ir/object-jvm-type "java/lang/Object"))))

(defn- add-old-field-snapshots
  [env assertions]
  (if (some #(references-old? (:condition %)) assertions)
    (do
      (when-not (and (:this-type env) (seq (:fields env)))
        (throw (ex-info "'old' is only supported for compiled instance-field postconditions"
                        {:assertions assertions
                         :current-class (:current-class env)})))
      (reduce (fn [[env' stmts snapshots] field-name]
                (let [field-info (get (:fields env') field-name)
                      snapshot-name (str "__old_" field-name)
                      [env'' local] (env-add-local env' snapshot-name (:nex-type field-info))
                      field-read-ir (snapshot-source-ir env' field-info)
                      ;; An Array (a real java.util.ArrayList) or Map (a real
                      ;; java.util.HashMap on this backend) is a genuinely
                      ;; mutable container: storing the field's current
                      ;; reference straight into the new snapshot local just
                      ;; copies the *reference*, and a later in-place
                      ;; `.add`/`.put` in the same method body is then visible
                      ;; through the "old" snapshot too, the same reference-
                      ;; vs-value bug env-replace-object-aliases! exists to
                      ;; work around on the interpreter side (`old
                      ;; items.length = items.length - 1` failed because both
                      ;; sides read the SAME, already-mutated ArrayList).
                      ;; shallow-copy-collection freezes the container's own
                      ;; membership at snapshot time without deep-copying
                      ;; elements (unlike Nex-level `.clone()`), so an
                      ;; object-valued element's identity is unaffected.
                      snapshot-ir (if (#{"Array" "Map"} (base-type-name (:nex-type field-info)))
                                    (ir/call-runtime-node "shallow-copy-collection"
                                                          [field-read-ir]
                                                          (:nex-type local)
                                                          (:jvm-type local))
                                    field-read-ir)
                      stmt (ir/set-local-node (:slot local)
                                              snapshot-ir
                                              (:nex-type local)
                                              (:jvm-type local))]
                  [env''
                   (conj stmts stmt)
                   (assoc snapshots field-name local)]))
              [env [] {}]
              (sort (keys (:fields env)))))
    [env [] (:old-field-locals env)]))

(defn- init-new-locals-stmts
  [outer-env inner-env]
  (let [outer-locals (:locals outer-env)
        inner-locals (:locals inner-env)]
    (->> inner-locals
         vals
         (remove #(contains? outer-locals (:name %)))
         (sort-by :slot)
         (mapv (fn [{:keys [slot nex-type jvm-type]}]
                 (ir/set-local-node slot
                                    (default-const-node nex-type jvm-type)
                                    nex-type
                                    jvm-type))))))

(defn- old-env
  [env]
  (if (seq (:old-field-locals env))
    (let [shadowed (into {}
                         (map (fn [[field-name {:keys [slot nex-type jvm-type]}]]
                                [field-name {:name field-name
                                             :slot slot
                                             :nex-type nex-type
                                             :jvm-type jvm-type}]))
                         (:old-field-locals env))]
      (-> env
          (update :locals merge shadowed)
          (update :var-types merge (into {}
                                         (map (fn [[field-name {:keys [nex-type]}]]
                                                [field-name nex-type]))
                                         (:old-field-locals env)))))
    (throw (ex-info "'old' can only be used in compiled postconditions with field snapshots"
                    {:env-keys (keys env)}))))

(defn- lower-body-with-rescue
  [pre-body-env body-env body rescue]
  (let [lowered-body (if (every? ir/ir-node? body)
                       (vec body)
                       (second (lower-statements pre-body-env body)))]
    (if rescue
      (let [local-init-stmts (init-new-locals-stmts pre-body-env body-env)
            [env1 throwable-slot] (alloc-temp-slot body-env)
            [env2 rescue-throwable-slot] (alloc-temp-slot env1)
            env-after-body body-env
            rescue-env0 (assoc (scoped-child-env env2) :retry-allowed? true)
            [rescue-env1 exception-local] (env-add-local rescue-env0 "exception" "Any")
            [rescue-env2 lowered-rescue] (lower-statements rescue-env1 rescue)
            final-env (scoped-env env-after-body rescue-env2)]
        [final-env
         (into local-init-stmts
               [(ir/try-node lowered-body
                             lowered-rescue
                             throwable-slot
                             rescue-throwable-slot
                             (:slot exception-local))])])
      [body-env lowered-body])))

(defn- lower-scoped-statements
  [env statements]
  (let [[child-env lowered] (lower-statements (scoped-child-env env) statements)]
    [(scoped-env env child-env) lowered]))

(defn- elseif->else-expr
  [env elseif else-branch]
  (if-let [clause (first elseif)]
    {:type :if
     :condition (:condition clause)
     :then (:then clause)
     :elseif (vec (rest elseif))
     :else else-branch}
    (let [else-body (or else-branch [])]
      (when-not (= 1 (count else-body))
        (throw (unsupported "Only expression-shaped or result-assignment if branches are supported in lowering"
                            {:branch else-body})))
      (if-branch-expression env else-body))))

(defn- case-clause-test-expr
  [env local literal-exprs]
  (letfn [(eq-expr [literal-expr]
            (ir/compare-node :eq
                             (ir/local-node (:name local) (:slot local) (:nex-type local) (:jvm-type local))
                             (lower-expression env literal-expr)
                             "Boolean"
                             :boolean))
          (combine [exprs]
            (if (= 1 (count exprs))
              (eq-expr (first exprs))
              (ir/if-node (eq-expr (first exprs))
                          [(ir/const-node true "Boolean" :boolean)]
                          [(combine (rest exprs))]
                          "Boolean"
                          :boolean)))]
    (combine literal-exprs)))

(defn- lower-case-clauses
  [env local clauses else-stmts]
  (if-let [clause (first clauses)]
    (let [[then-env then-body] (lower-scoped-statements env [(:body clause)])
          [else-env else-body] (lower-case-clauses (scoped-env env then-env) local (rest clauses) else-stmts)
          test-expr (case-clause-test-expr env local (:values clause))]
      [(scoped-env env else-env)
       [(ir/if-stmt-node test-expr then-body else-body)]])
    (lower-scoped-statements env else-stmts)))

(defn- match-clause-binding-type
  "Compute a match clause's binding type, carrying the subject's generic
  arguments onto the bound variable (so `s.value` resolves with the real
  element type). Mirrors the typechecker so the compiled backend agrees with
  --interpret; an explicit `C[...]` on the clause wins over inference."
  [env subject-type class-name generic-args]
  (if (seq generic-args)
    {:base-type class-name :type-args generic-args}
    (let [type-env (tc/make-type-env)]
      (doseq [[cn cd] (visible-class-map env)]
        (tc/env-add-class type-env cn cd))
      (tc/match-clause-binding-type type-env subject-type class-name))))

(defn- lower-match-clauses
  "Lower match clauses as a chain of convert-based instanceof checks. Destructure
  `:bindings` run in the matched scope; a `:guard`, when present, is tested after
  them and falls through to the remaining clauses when false."
  [env match-tmp-name clauses else-stmts]
  (if-let [clause (first clauses)]
    (let [{:keys [var-name body generic-args bindings guard]} clause
          ;; Runtime dispatch (nex.compiler.jvm.runtime/runtime-compatible-with?)
          ;; compares plain Nex type-name *strings* against the value's own
          ;; embedded runtime type name — not JVM identities, so
          ;; class-self-registration-name's earlier fix (which only reaches
          ;; JVM internal names) doesn't cover this by itself. A qualified
          ;; clause (`when finance/Ok(...)`, docs/proposals/namespaces.md
          ;; Phase 3 — walked to "finance.Ok") must still normalize to
          ;; whatever bare/qualified string a non-colliding class's compiled
          ;; objects actually carry as their runtime type name, the same
          ;; normalization class-self-registration-name applies for JVM
          ;; identity — otherwise a real Ok value's runtime name ("Ok") never
          ;; string-equals the clause's unresolved "finance.Ok" and the
          ;; clause silently never matches.
          class-name (if-let [cd (get (visible-class-map env) (:class-name clause))]
                       (class-self-registration-name (:compiled-classes env) cd)
                       (:class-name clause))
          bindings (vec (or bindings []))
          subject-type (infer-type env {:type :identifier :name match-tmp-name})
          target-type (match-clause-binding-type env subject-type class-name generic-args)
          synthetic-convert {:type :convert
                             :value {:type :identifier :name match-tmp-name}
                             :var-name var-name
                             :target-type target-type}
          [env1 _binding] (ensure-convert-binding env synthetic-convert)
          [_ convert-ir] (lower-convert-expression env1 synthetic-convert)]
      (if guard
        ;; `if (convert…) then { bindings; init; if guard then body else rest } else rest`.
        ;; The guard is lowered as a boolean condition so a `convert … to v: T`
        ;; guard allocates/initializes v and refines it non-nil in the body
        ;; (this is what makes a nested pattern's field-narrowing compile).
        (let [child (scoped-child-env env1)
              [env-b lowered-bindings] (lower-statements child bindings)
              [cond-env guard-ir] (lower-boolean-condition env-b guard)
              guard-init-stmts (convert-binding-init-stmts cond-env guard)
              body-env (refine-condition-branch-env cond-env guard :then)
              [env-body lowered-body] (lower-statements body-env body)
              [env3 else-body] (lower-match-clauses (scoped-env env env-body)
                                                    match-tmp-name (rest clauses) else-stmts)
              then-branch (vec (concat lowered-bindings
                                       guard-init-stmts
                                       [(ir/if-stmt-node guard-ir lowered-body else-body)]))]
          [(scoped-env env env3)
           [(ir/if-stmt-node convert-ir then-branch else-body)]])
        (let [[env2 lowered-body] (lower-scoped-statements env1 (into bindings body))
              [env3 else-body] (lower-match-clauses (scoped-env env env2)
                                                    match-tmp-name (rest clauses) else-stmts)]
          [(scoped-env env env3)
           [(ir/if-stmt-node convert-ir lowered-body else-body)]])))
    (lower-scoped-statements env else-stmts)))

(defn- select-clause-value-type
  [env {:keys [expr]}]
  (let [{:keys [target method]} expr
        target-type (infer-type env (normalize-call-target target))
        base (base-type-name target-type)
        type-args (generic-type-args target-type)]
    (case base
      "Task" (or (first type-args) "Any")
      "Channel" (case method
                  ("receive" "try_receive") (or (first type-args) "Any")
                  nil)
      nil)))

(defn- select-done-set-stmt
  "The `__select_done := true` marker appended to a fired select clause's body."
  [done-local]
  (ir/set-local-node (:slot done-local)
                     (ir/const-node true "Boolean" :boolean)
                     "Boolean"
                     :boolean))

(defn- lower-select-task-clause
  [env done-local not-done clause target-expr]
  (let [{:keys [alias body]} clause
        ready-expr {:type :call :target target-expr :method "is_done"
                    :args [] :has-parens false}
        value-expr {:type :call :target target-expr :method "await"
                    :args [] :has-parens true}
        [env1 alias-local] (if alias
                             (env-add-local env alias (select-clause-value-type env clause))
                             [env nil])
        [env2 lowered-body] (lower-scoped-statements env1 body)
        then-body (vec (concat
                        (when alias-local
                          [(ir/set-local-node (:slot alias-local)
                                              (lower-expression env2 value-expr)
                                              (:nex-type alias-local)
                                              (:jvm-type alias-local))])
                        lowered-body
                        [(select-done-set-stmt done-local)]))]
    [env2
     (ir/if-stmt-node (ir/binary-node :and
                                      not-done
                                      (lower-expression env ready-expr)
                                      "Boolean"
                                      :boolean)
                      then-body
                      [])]))

(defn- lower-select-channel-receive-clause
  [env done-local not-done clause target-expr]
  (let [{:keys [alias body]} clause
        value-type (select-clause-value-type env clause)
        temp-type (tc/detachable-version value-type)
        [env1 temp-local] (env-add-local env (str "__select_value_" (:next-slot env)) temp-type)
        [env2 alias-local] (if alias
                             (env-add-local env1 alias value-type)
                             [env1 nil])
        [env3 lowered-body] (lower-scoped-statements env2 body)
        receive-expr {:type :call :target target-expr :method "try_receive"
                      :args [] :has-parens true}
        temp-node (ir/local-node "__select_value"
                                 (:slot temp-local)
                                 (:nex-type temp-local)
                                 (:jvm-type temp-local))
        then-body (vec (concat
                        (when alias-local
                          [(ir/set-local-node (:slot alias-local)
                                              temp-node
                                              (:nex-type alias-local)
                                              (:jvm-type alias-local))])
                        lowered-body
                        [(select-done-set-stmt done-local)]))]
    [env3
     (ir/block-node
      [(ir/set-local-node (:slot temp-local)
                          (lower-expression env3 receive-expr)
                          (:nex-type temp-local)
                          (:jvm-type temp-local))
       (ir/if-stmt-node (ir/binary-node :and
                                        not-done
                                        (ir/compare-node :neq
                                                         temp-node
                                                         (ir/const-node nil "Any" (ir/object-jvm-type "java/lang/Object"))
                                                         "Boolean"
                                                         :boolean)
                                        "Boolean"
                                        :boolean)
                        then-body
                        [])])]))

(defn- lower-select-channel-send-clause
  [env done-local not-done clause target-expr]
  (let [{:keys [expr body]} clause
        send-expr {:type :call :target target-expr :method "try_send"
                   :args [(first (:args expr))] :has-parens true}
        [env1 lowered-body] (lower-scoped-statements env body)
        then-body (vec (concat lowered-body [(select-done-set-stmt done-local)]))]
    [env1
     (ir/if-stmt-node (ir/binary-node :and
                                      not-done
                                      (lower-expression env1 send-expr)
                                      "Boolean"
                                      :boolean)
                      then-body
                      [])]))

(defn- lower-select-clause
  [env done-local clause]
  (let [done-node (ir/local-node "__select_done"
                                 (:slot done-local)
                                 (:nex-type done-local)
                                 (:jvm-type done-local))
        not-done (ir/unary-node :not done-node "Boolean" :boolean)
        {:keys [target method]} (:expr clause)
        target-expr (normalize-call-target target)]
    (case (base-type-name (infer-type env target-expr))
      "Task"
      (lower-select-task-clause env done-local not-done clause target-expr)

      "Channel"
      (case method
        ("receive" "try_receive")
        (lower-select-channel-receive-clause env done-local not-done clause target-expr)

        ("send" "try_send")
        (lower-select-channel-send-clause env done-local not-done clause target-expr)

        (throw (unsupported "Unsupported select channel clause during lowering"
                            {:clause clause})))

      (throw (unsupported "Unsupported select clause target during lowering"
                          {:clause clause})))))

(defn lower-select
  [env stmt]
  (let [[env1 done-local] (env-add-local env "__select_done" "Boolean")
        [env2 deadline-local] (if-let [_timeout (:timeout stmt)]
                                (env-add-local env1 "__select_deadline" "Integer")
                                [env1 nil])
        init-stmts (vec (concat
                         [(ir/set-local-node (:slot done-local)
                                             (ir/const-node false "Boolean" :boolean)
                                             "Boolean"
                                             :boolean)]
                         (when-let [timeout (:timeout stmt)]
                           [(ir/set-local-node (:slot deadline-local)
                                               (ir/call-runtime-node "select-deadline"
                                                                     [(lower-expression env2 (:duration timeout))]
                                                                     "Integer"
                                                                     :long)
                                               "Integer"
                                               :long)])))
        [env3 clause-stmts] (reduce (fn [[e acc] clause]
                                      (let [[e' stmt'] (lower-select-clause e done-local clause)]
                                        [e' (conj acc stmt')]))
                                    [env2 []]
                                    (:clauses stmt))
        [env4 else-body] (if-let [else-stmts (:else stmt)]
                           (let [[e body] (lower-scoped-statements env3 else-stmts)]
                             [e body])
                           [env3 []])
        [env5 timeout-body] (if-let [timeout (:timeout stmt)]
                              (let [[e body] (lower-scoped-statements env4 (:body timeout))]
                                [e body])
                              [env4 []])
        done-node (ir/local-node "__select_done" (:slot done-local) "Boolean" :boolean)
        loop-body (vec (concat
                        clause-stmts
                        (when (seq else-body)
                          [(ir/if-stmt-node (ir/unary-node :not done-node "Boolean" :boolean)
                                            (vec (concat else-body
                                                         [(ir/set-local-node (:slot done-local)
                                                                             (ir/const-node true "Boolean" :boolean)
                                                                             "Boolean"
                                                                             :boolean)]))
                                            [])])
                        (when-let [_timeout (:timeout stmt)]
                          [(ir/if-stmt-node (ir/binary-node :and
                                                            (ir/unary-node :not done-node "Boolean" :boolean)
                                                            (ir/call-runtime-node "deadline-expired?"
                                                                                  [(ir/local-node "__select_deadline"
                                                                                                  (:slot deadline-local)
                                                                                                  "Integer"
                                                                                                  :long)]
                                                                                  "Boolean"
                                                                                  :boolean)
                                                            "Boolean"
                                                            :boolean)
                                            (vec (concat timeout-body
                                                         [(ir/set-local-node (:slot done-local)
                                                                             (ir/const-node true "Boolean" :boolean)
                                                                             "Boolean"
                                                                             :boolean)]))
                                            [])])
                        [(ir/pop-node (ir/call-runtime-node "select-sleep-step" [] "Void" :void))]))]
    [env5
     (ir/block-node
      (conj init-stmts
            (ir/loop-node [] done-node loop-body)))]))

(defn- visible-class-map
  "Name -> class-def, including every name an `intern ... as` alias resolves
   to. An aliased intern registers only a `*type-aliases*` entry pointing at
   the real class (see `nex.interpreter/resolve-interned*`), not a second,
   nominally distinct class-def — so the alias name is added here as an extra
   key onto the *same* class-def value, keeping both names resolvable to one
   compiled class. Also keyed by every interned class-def's :qualified-name
   (docs/proposals/namespaces.md, Phase 3), alongside the ordinary bare-name
   keys, so a qualified reference (`finance/Account`, walked to the string
   \"finance.Account\") resolves to its own class-def here even when the bare
   name `Account` collapses (last-wins, via `into {}` below) to a different
   one.

   A class-def that lost the bare-name collapse may not be present in
   `(:classes env)` at all: every nested lowering scope (a constructor, a
   method, a generic-init method, ...) builds its own :classes list
   independently via its own make-lowering-env call, and none of them thread
   a separate \"everyone who lost the collapse\" list through — env's own
   :classes is simply whatever that one call site happened to pass. Falls
   back to `(:compiled-classes env)`, which — unlike :classes — does reach
   every scope reliably (lowering cannot proceed at all without it), and
   embeds each class-def alongside its JVM identity
   (`nex.compiler.jvm.file/class-metadata-entry`) for exactly this recovery."
  [env]
  (let [by-bare-name (into {} (map (juxt :name identity) (:classes env)))
        by-qualified-name (into {}
                                (comp (filter :qualified-name)
                                      (map (juxt :qualified-name identity)))
                                (:classes env))
        by-qualified-name-from-compiled (into {}
                                              (keep (fn [[qn meta]]
                                                      (when (and (:class-def meta)
                                                                 (not (contains? by-qualified-name qn)))
                                                        [qn (:class-def meta)])))
                                              (:compiled-classes env))
        base (merge by-bare-name by-qualified-name by-qualified-name-from-compiled)]
    (reduce-kv (fn [m alias-name _]
                 (if-let [class-def (get base (resolve-type-alias alias-name))]
                   (assoc m alias-name class-def)
                   m))
               base
               *type-aliases*)))

(defn- lowering-type-env
  [env]
  (let [type-env (tc/make-type-env)]
    (doseq [[class-name class-def] (visible-class-map env)]
      (tc/env-add-class type-env class-name class-def))
    type-env))

(defn- operator-alias-feature
  "The feature that the static type of `left` binds to `operator` with an `alias`
   clause, or nil. Gated on the env's precomputed alias set, so a program that
   aliases nothing (every program written before this feature existed) pays only
   a set-membership test per binary node and never infers an operand type here."
  [env operator left]
  (when (contains? (:aliased-operators env) operator)
    (let [class-map (visible-class-map env)]
      (letfn [(search [cn seen]
                (when-let [class-def (and (string? cn)
                                          (not (contains? seen cn))
                                          (get class-map cn))]
                  (or (->> (tc/feature-members class-def)
                           (filter #(and (= (:type %) :method)
                                         (= (:alias %) operator)
                                         (= 1 (count (or (:params %) [])))))
                           first
                           :name)
                      (some (fn [{:keys [parent]}] (search parent (conj seen cn)))
                            (:parents class-def)))))]
        (search (base-type-name (infer-type env left)) #{})))))

(defn- cursor-compatible-type?
  [env nex-type]
  (let [base-type (base-type-name nex-type)]
    (and (string? base-type)
         (tc/class-subtype? (lowering-type-env env) base-type "Cursor"))))

(defn- across-cursor-binding
  [env stmt]
  (when (and (:synthetic stmt)
             (string? (:name stmt))
             (str/starts-with? (:name stmt) "__across_c_")
             (= :call (get-in stmt [:value :type]))
             (= "cursor" (get-in stmt [:value :method]))
             (empty? (get-in stmt [:value :args])))
    (let [target-expr (normalize-call-target (get-in stmt [:value :target]))
          target-type (and target-expr (infer-type env target-expr))]
      (when (cursor-compatible-type? env target-type)
        {:target-expr target-expr
         :target-type target-type}))))

(defn- generic-type-map
  "Type-map for resolving a member's declared types against receiver `target-type`.
   When the member is inherited, `declaring-class` names the class that declared it;
   its generic parameters are resolved through the inherit chain rather than assumed
   to line up with the heir's."
  ([env target-type] (generic-type-map env target-type nil))
  ([env target-type declaring-class]
   (let [type-env (tc/make-type-env)]
     (doseq [[class-name class-def] (visible-class-map env)]
       (tc/env-add-class type-env class-name class-def))
     (if declaring-class
       (tc/build-member-generic-type-map type-env target-type declaring-class)
       (tc/build-generic-type-map type-env target-type)))))

(defn- current-class-def
  [env]
  (get (visible-class-map env) (:current-class env)))

(defn- class-members
  [class-def]
  (mapcat (fn [section]
            (case (:type section)
              :feature-section (:members section)
              :constructors (:constructors section)
              []))
          (:body class-def)))

(defn- class-fields
  [class-def]
  (filter #(= :field (:type %)) (class-members class-def)))

(defn- feature-members
  [class-def]
  (mapcat (fn [section]
            (when (= (:type section) :feature-section)
              (map #(if (:visibility %)
                      %
                      (assoc % :visibility (:visibility section)))
                   (:members section))))
          (:body class-def)))

(defn- public-member?
  [member]
  (not= :private (-> member :visibility :type)))

(defn- member-visible?
  [env member declaring-class-name]
  (or (= (:this-type env) declaring-class-name)
      (public-member? member)))

(defn- class-methods
  [class-def]
  (filter #(= :method (:type %)) (class-members class-def)))

(defn- class-constructors
  [class-def]
  (filter #(= :constructor (:type %)) (class-members class-def)))

(defn- class-field-def
  [class-def field-name]
  (some #(when (= (:name %) field-name) %) (class-fields class-def)))

(defn- accessible-field-def
  [env class-def field-name]
  (let [class-map (visible-class-map env)]
    (letfn [(lookup-field [cn visited]
              (when (and cn (not (contains? visited cn)))
                (let [class-def (get class-map cn)
                      visited' (conj visited cn)
                      own-field (when class-def
                                  (some (fn [member]
                                          (when (and (= (:type member) :field)
                                                     (not (:constant? member))
                                                     (= (:name member) field-name)
                                                     (member-visible? env member cn))
                                            (assoc member :declaring-class cn)))
                                        (feature-members class-def)))]
                  (or own-field
                      (when class-def
                        (some (fn [{:keys [parent]}]
                                (lookup-field parent visited'))
                              (:parents class-def)))))))]
      ;; Prefer :qualified-name (always unique) over the bare :name — the
      ;; latter may belong, in class-map, to a *different* class-def than
      ;; class-def itself when two interned classes share a bare name
      ;; (docs/proposals/namespaces.md, Phase 3): class-map's own bare-name
      ;; slot holds whichever one won merge-visible-classes' collapse, which
      ;; is not necessarily this one.
      (lookup-field (or (:qualified-name class-def) (:name class-def)) #{}))))

(defn- field-write-error-message
  [field-name declaring-class]
  (str "Cannot assign to field " field-name
       " outside of class " declaring-class))

(defn- lookup-class-constant
  [env class-name constant-name]
  (let [class-map (visible-class-map env)]
    (letfn [(lookup-constant [cn visited inherited?]
              (when (and cn (not (contains? visited cn)))
                (let [class-def (get class-map cn)
                      visited' (conj visited cn)
                      own-constant (when class-def
                                     (some (fn [member]
                                             (when (and (= (:type member) :field)
                                                        (:constant? member)
                                                        (= (:name member) constant-name)
                                                        (or (not inherited?)
                                                            (public-member? member)))
                                               (assoc member :declaring-class cn)))
                                           (feature-members class-def)))]
                  (or own-constant
                      (when class-def
                        (some (fn [{:keys [parent]}]
                                (lookup-constant parent visited' true))
                              (:parents class-def)))))))]
      (lookup-constant class-name #{} false))))

(defn- class-method-def
  [class-def method-name arity]
  (some #(when (and (= (:name %) method-name)
                    (= (count (or (:params %) [])) arity))
           %)
        (class-methods class-def)))

(defn- accessible-method-def
  [env class-def method-name arity]
  (let [class-map (visible-class-map env)]
    (letfn [(lookup-method [cn visited]
              (when (and cn (not (contains? visited cn)))
                (let [class-def (get class-map cn)
                      visited' (conj visited cn)
                      own-method (when class-def
                                   (some (fn [member]
                                           (when (and (= (:type member) :method)
                                                      (= (:name member) method-name)
                                                      (= (count (or (:params member) [])) arity)
                                                      (member-visible? env member cn))
                                             ;; Remember the declaring class: an
                                             ;; inherited routine's types are
                                             ;; written in *its* generic params.
                                             (assoc member :declaring-class cn)))
                                         (feature-members class-def)))]
                  (or own-method
                      (when class-def
                        (some (fn [{:keys [parent]}]
                                (lookup-method parent visited'))
                              (:parents class-def)))))))]
      ;; See accessible-field-def just above for why :qualified-name wins.
      (lookup-method (or (:qualified-name class-def) (:name class-def)) #{}))))

(defn- class-constructor-def
  [class-def constructor-name arity]
  (some #(when (and (= (:name %) constructor-name)
                    (= (count (or (:params %) [])) arity))
           %)
        (class-constructors class-def)))

(defn- parent-field-name
  "JVM field name backing PARENT's composition slot on a heir class -- see
   direct-parent-field-map's docstring for what that slot is. PARENT is an
   ordinary Nex class-name string, but may be a qualified one ('flex.Rule',
   from an `inherit flex/Rule` clause resolved per docs/proposals/namespaces.md
   Phase 3) -- a '.' is illegal in a JVM field name, so it is mangled to '_'
   the same way an interned free function's :qualified-name already is
   (function-jvm-name, above). Every call site that builds this field's name
   goes through here, so a lookup by name (composition-fields, reflectively
   scanning by the \"_parent_\" prefix) never needs to reverse the mangling --
   it only ever needs the field's declared name to match what was written."
  [parent]
  (str "_parent_" (str/replace parent "." "_")))

(defn- resolve-parent-metas
  [env class-def]
  (->> (:parents class-def)
       (remove #(contains? #{"Any" "Function"} (:parent %)))
       (mapv (fn [{:keys [parent]}]
               (when-let [parent-def (get (visible-class-map env) parent)]
                 (when-let [compiled (get (:compiled-classes env) parent)]
                   {:nex-name parent
                    :jvm-name (:jvm-name compiled)
                    :internal-name (:internal-name compiled)
                    :binary-name (:binary-name compiled)
                    :composition-field (parent-field-name parent)
                    :deferred? (boolean (:deferred? parent-def))}))))
       (remove nil?)
       vec))

(defn- carrier-path-target-ir
  "Build the target-ir for reaching a field through CARRIER-PATH -- a vector of
   `{:owner :field :ancestor :ancestor-jvm-type}` hops from THIS-IR, outermost
   first (see `direct-parent-field-map`) -- as a chain of nested field-gets,
   one GETFIELD per composition level. Empty CARRIER-PATH (the field is on
   the caller's own class) just returns THIS-IR unchanged; callers pass their
   own `this`-node so a per-site jvm-type quirk on it isn't disturbed."
  [env carrier-path this-ir]
  (reduce (fn [target-ir {:keys [owner field ancestor ancestor-jvm-type]}]
            (ir/field-get-node (:internal-name (class-jvm-meta env owner))
                               field
                               target-ir
                               ancestor
                               ancestor-jvm-type))
          this-ir
          carrier-path))

(defn- direct-parent-field-map
  "Every field class-def can reach through its `inherit` clauses, own
   declarations excluded -- including a grandparent's, great-grandparent's,
   etc., not just a direct parent's. Recurses into each direct parent's own
   `direct-parent-field-map` for anything *it* inherits, prepending the hop
   into that parent's composition field to the path recorded there, so e.g. a
   field two `inherit`s up carries `:carrier-path [hop-to-parent hop-to-
   grandparent]` -- see `carrier-path-target-ir`, which walks it as nested
   GETFIELDs. Before this, a field's `:carrier-path` (then a single
   `:carrier-owner`/`:carrier-field` pair) only ever reached a *direct*
   parent's own fields, so a class assigning or reading a field declared two
   or more `inherit`s above it hit `(:fields env)` lookups that came up empty
   -- \"Assignment target is not a known local\" for a bare `field := value` in
   a constructor, or an analogous failure for a bare read."
  [env class-def]
  (reduce (fn [m {:keys [parent generic-args]}]
            (if-let [parent-def (and (get (:compiled-classes env) parent)
                                     (get (visible-class-map env) parent))]
              (let [composition-field (parent-field-name parent)
                    parent-params (mapv :name (:generic-params parent-def))
                    ;; An inherited field is declared in the parent's generic
                    ;; parameters, which need not match the heir's: restate it in
                    ;; this class's terms, so `first: A` of `Pair[A, B]` is a `Y`
                    ;; inside `C[X, Y] inherit Pair[Y, X]` and a `Draft` inside
                    ;; `class C inherit Pair[Draft, Final]`.
                    subst (zipmap parent-params (or generic-args []))
                    ;; The JVM descriptor, though, stays the one the parent really
                    ;; declares: inside `Pair` its own parameters are erased to
                    ;; Object, and GETFIELD/PUTFIELD must name that descriptor to
                    ;; link. Resolving with the parent's parameters in scope keeps
                    ;; them erased instead of emitting a phantom class named `A`.
                    parent-env (update env :generic-param-names
                                       #(into (set %) parent-params))
                    hop {:owner (:name class-def)
                         :field composition-field
                         :ancestor parent
                         :ancestor-jvm-type (exact-class-jvm-type env parent)}
                    m-with-own-fields
                    (reduce (fn [m2 field]
                              (if (or (:constant? field)
                                      (contains? m2 (:name field)))
                                m2
                                (assoc m2
                                       (:name field)
                                       {:owner parent
                                        :field (:name field)
                                        :carrier-path [hop]
                                        :nex-type (if (seq subst)
                                                    (tc/resolve-generic-type (:field-type field) subst)
                                                    (:field-type field))
                                        :jvm-type (resolve-jvm-type parent-env (:field-type field))})))
                            m
                            (class-fields parent-def))
                    ;; Fields `parent` itself only reaches through *its own*
                    ;; `inherit` clauses (a grandparent's, etc.) -- restate their
                    ;; types in class-def's terms too (substitutions compose the
                    ;; same way down the chain) and prepend this hop to reach
                    ;; them from class-def.
                    inherited-by-parent (direct-parent-field-map parent-env parent-def)]
                (reduce-kv (fn [m2 field-name info]
                             (if (contains? m2 field-name)
                               m2
                               (assoc m2 field-name
                                      (-> info
                                          (update :nex-type
                                                  #(if (seq subst) (tc/resolve-generic-type % subst) %))
                                          (update :carrier-path
                                                  (fn [path] (into [hop] path)))))))
                           m-with-own-fields
                           inherited-by-parent))
              m))
          {}
          (remove #(contains? #{"Any" "Function"} (:parent %)) (:parents class-def))))

(defn- inherited-method-def
  [env class-def method-name arity]
  (let [class-map (visible-class-map env)]
    (letfn [(lookup-method [parents visited]
              (some (fn [{:keys [parent]}]
                      (when-not (contains? visited parent)
                        (let [parent-def (get class-map parent)
                              visited' (conj visited parent)]
                          (or (when parent-def
                                (some-> (class-method-def parent-def method-name arity)
                                        (assoc :declaring-class parent)))
                              (when parent-def
                                (lookup-method (:parents parent-def) visited'))))))
                    parents))]
      (lookup-method (:parents class-def) #{}))))

(defn- collect-inherited-method-contract-sources
  [env class-def method-name arity]
  (let [class-map (visible-class-map env)]
    (letfn [(collect [cls seen]
              (let [class-name (:name cls)
                    already-seen? (and class-name (contains? seen class-name))
                    seen' (if class-name (conj seen class-name) seen)]
                (if already-seen?
                  [[] seen]
                  (let [[parent-sources seen'']
                        (if-let [parents (:parents cls)]
                          (reduce (fn [[acc seen-so-far] {:keys [parent]}]
                                    (if-let [parent-def (get class-map parent)]
                                      (let [[sources seen-next] (collect parent-def seen-so-far)]
                                        [(into acc sources) seen-next])
                                      [acc seen-so-far]))
                                  [[] seen']
                                  parents)
                          [[] seen'])
                        local-method (class-method-def cls method-name arity)
                        local-source (when (and local-method
                                                (public-member? local-method))
                                       [{:source-class class-name
                                         :method-def local-method}])]
                    [(vec (concat parent-sources local-source)) seen'']))))]
      (if-let [parents (:parents class-def)]
        (first (reduce (fn [[acc seen] {:keys [parent]}]
                         (if-let [parent-def (get class-map parent)]
                           (let [[sources seen'] (collect parent-def seen)]
                             [(into acc sources) seen'])
                           [acc seen]))
                       [[] #{}]
                       parents))
        []))))

(defn- assertions->condition
  [assertions]
  (when (seq assertions)
    (reduce (fn [acc {:keys [condition]}]
              (if acc
                {:type :binary
                 :operator "and"
                 :left acc
                 :right condition}
                condition))
            nil
            assertions)))

(defn- combine-precondition-groups
  [inherited-groups local-assertions]
  (let [groups (vec (concat (keep seq inherited-groups)
                            (when (seq local-assertions)
                              [(vec local-assertions)])))]
    (cond
      (empty? groups)
      nil

      (= 1 (count groups))
      (vec (first groups))

      :else
      [{:label "inherited_or_local_require"
        :condition (reduce (fn [acc group]
                             (let [group-condition (assertions->condition group)]
                               (if acc
                                 {:type :binary
                                  :operator "or"
                                  :left acc
                                  :right group-condition}
                                 group-condition)))
                           nil
                           groups)}])))

(defn- effective-method-contracts
  [env class-def method-def]
  (let [inherited-sources (collect-inherited-method-contract-sources env
                                                                     class-def
                                                                     (:name method-def)
                                                                     (count (or (:params method-def) [])))]
    {:effective-require (combine-precondition-groups
                         (mapv (fn [{:keys [method-def]}] (:require method-def))
                               inherited-sources)
                         (:require method-def))
     :effective-ensure (vec (concat (mapcat (fn [{:keys [method-def]}]
                                              (or (:ensure method-def) []))
                                            inherited-sources)
                                    (or (:ensure method-def) [])))}))

(defn- method-override?
  [env class-def method-def]
  (boolean
   (inherited-method-def env
                         class-def
                         (:name method-def)
                         (count (or (:params method-def) [])))))

(defn- constant-nex-type
  [env constant]
  (or (:field-type constant)
      (when-let [value-expr (:value constant)]
        ;; An unannotated constant's type comes from its initializer, and that
        ;; initializer may name a sibling or inherited constant (`B = A + 5`).
        ;; Such a name resolves only in the class that declares it — never in
        ;; whatever scope happens to be *reading* the constant, which for
        ;; `print(Base.B)` is the top level. `lookup-class-constant` records the
        ;; declaring class; infer there.
        (infer-type (if-let [owner (:declaring-class constant)]
                      (assoc env :current-class owner)
                      env)
                    value-expr))))

(defn- lowered-deferred-method?
  [class-def method-def]
  (or (:deferred? method-def)
      (:declaration-only? method-def)
      (and (:deferred? class-def)
           (empty? (vec (:body method-def))))))

(defn- field-info-map
  [env class-def]
  (merge
   (direct-parent-field-map env class-def)
   (into {}
         (map (fn [field]
                [(:name field)
                 {:owner (:name class-def)
                  :field (:name field)
                  :nex-type (:field-type field)
                  :jvm-type (resolve-jvm-type env (:field-type field))}]))
         (remove :constant? (class-fields class-def)))))

(defn- field-type-map
  [class-def]
  (into {}
        (map (fn [field]
               [(:name field) (:field-type field)]))
        (remove :constant? (class-fields class-def))))

(defn- inherited-constructor-def
  [env class-def constructor-name arity]
  (let [class-map (visible-class-map env)]
    (letfn [(lookup-ctor [parents visited]
              (some (fn [{:keys [parent]}]
                      (when-not (contains? visited parent)
                        (let [parent-def (get class-map parent)
                              visited' (conj visited parent)]
                          (or (when parent-def
                                (class-constructor-def parent-def constructor-name arity))
                              (when parent-def
                                (lookup-ctor (:parents parent-def) visited'))))))
                    parents))]
      (lookup-ctor (:parents class-def) #{}))))

(defn- own-or-inherited-constructor-def
  [env class-def constructor-name arity]
  (or (class-constructor-def class-def constructor-name arity)
      (inherited-constructor-def env class-def constructor-name arity)))

(defn- direct-parent-method-map
  [env class-def]
  (reduce (fn [m {:keys [parent]}]
            (if-let [parent-def (and (get (:compiled-classes env) parent)
                                     (get (visible-class-map env) parent))]
              (let [parent-meta (class-jvm-meta env parent)
                    composition-field (parent-field-name parent)]
                (reduce (fn [m2 method-def]
                          (if (contains? m2 [(:name method-def) (count (or (:params method-def) []))])
                            m2
                            (assoc m2
                                   [(:name method-def) (count (or (:params method-def) []))]
                                   {:source-class parent
                                    :carrier-owner (:name class-def)
                                    :carrier-field composition-field
                                    :owner-internal-name (:internal-name parent-meta)
                                    :method-def method-def
                                    :carrier-jvm-type (exact-class-jvm-type env parent)})))
                        m
                        (class-methods parent-def)))
              m))
          {}
          (remove #(contains? #{"Any" "Function"} (:parent %)) (:parents class-def))))

(defn- direct-parent-names
  [class-def]
  (mapv :parent (remove #(contains? #{"Any" "Function"} (:parent %)) (:parents class-def))))

(defn- function-object-binding-type
  [env name]
  (resolve-type-alias
   (or (get-in (:locals env) [name :nex-type])
       (get (:var-types env) name))))

(defn- function-object-call?
  [env name arity]
  (when-let [binding-type (function-object-binding-type env name)]
    (let [base-type (base-type-name binding-type)
          call-name (str "call" arity)]
      (or (= "Function" base-type)
          (boolean
           (some-> (get (visible-class-map env) base-type)
                   (class-method-def call-name arity)))))))

(defn- single-super-parent-name
  [env]
  (let [parents (direct-parent-names (current-class-def env))]
    (case (count parents)
      1 (first parents)
      0 (throw (ex-info "super requires a direct parent in compiled lowering"
                        {:current-class (:current-class env)}))
      (throw (ex-info "super is ambiguous with multiple direct parents in compiled lowering"
                      {:current-class (:current-class env)
                       :parents parents})))))

(defn- lookup-convert-binding
  [env var-name]
  (or (when-let [{:keys [slot nex-type jvm-type]} (get (:locals env) var-name)]
        {:kind :local
         :name var-name
         :slot slot
         :nex-type nex-type
         :jvm-type jvm-type})
      (when (and (:top-level? env)
                 (contains? (:var-types env) var-name))
        (let [nex-type (get (:var-types env) var-name)]
          {:kind :top
           :name var-name
           :nex-type nex-type
           :jvm-type (resolve-jvm-type env nex-type)}))))

(defn- ensure-convert-binding
  [env {:keys [var-name target-type type]}]
  (let [bound-type (or target-type type)]
    (when-not bound-type
      (throw (ex-info "convert binding is missing a target type"
                      {:var-name var-name})))
    ;; The bound variable is detachable (the typechecker binds it as ?T): a
    ;; failed convert stores nil, so the slot must be a reference type — a raw
    ;; scalar target like Real would otherwise get a primitive slot and the
    ;; nil store would NPE at the convert site.
    (let [bound-type (if (map? bound-type)
                       (assoc bound-type :detachable true)
                       {:base-type bound-type :detachable true})]
      (if-let [binding (lookup-convert-binding env var-name)]
        [env binding]
        (if (and (:top-level? env) (not (:scoped-locals? env)))
          (let [env' (update env :var-types assoc var-name bound-type)]
            [env' {:kind :top
                   :name var-name
                   :nex-type bound-type
                   :jvm-type (resolve-jvm-type env' bound-type)}])
          (let [[env' local] (env-add-local env var-name bound-type)]
            [env' {:kind :local
                   :name var-name
                   :slot (:slot local)
                   :nex-type (:nex-type local)
                   :jvm-type (:jvm-type local)}]))))))

(defn- lower-convert-expression
  [env {:keys [value var-name target-type] :as expr}]
  (let [target-name (if (map? target-type) (:base-type target-type) target-type)
        target-runtime (runtime-type-token-ir env target-type)
        binding (or (lookup-convert-binding env var-name)
                    (throw (ex-info "convert binding must exist before lowering expression"
                                    {:expr expr
                                     :var-name var-name})))
        [env' temp-slot] (alloc-temp-slot env)]
    [(assoc env :next-slot (:next-slot env'))
     (ir/convert-node (lower-expression env value)
                      binding
                      target-name
                      target-runtime
                      "Boolean"
                      :boolean
                      temp-slot)]))

(defn- lower-attached-test-expression
  [env {:keys [value var-name] :as expr}]
  (let [binding (or (lookup-convert-binding env var-name)
                    (throw (ex-info "attached-test binding must exist before lowering expression"
                                    {:expr expr
                                     :var-name var-name})))
        [env' temp-slot] (alloc-temp-slot env)]
    [(assoc env :next-slot (:next-slot env'))
     (ir/attached-test-node (lower-expression env value)
                            binding
                            "Boolean"
                            :boolean
                            temp-slot)]))

(declare lower-expression)

(defn- ensure-convert-bindings
  [env condition]
  (reduce (fn [[env' bindings] {:keys [name] :as binding}]
            (let [[env'' lowered-binding] (ensure-convert-binding env'
                                                                  (assoc binding :var-name name))]
              [env'' (conj bindings lowered-binding)]))
          [env []]
          (tc/convert-guard-bindings condition)))

;; Attached-test bindings reuse ensure-convert-binding's slot-allocation (a
;; detachable/reference slot the JVM verifier accepts a nil store into,
;; narrowed by refine-condition-branch-env once inside the guarded branch —
;; same VerifyError concern refine-var-non-nil documents for convert): unlike
;; a `convert` guard, there's no user-written target type in the AST, so it's
;; inferred here from <value> with lower's own infer-type-or-any.
(defn- ensure-attached-test-bindings
  [env condition]
  (reduce (fn [[env' bindings] {:keys [name value]}]
            (let [[env'' lowered-binding] (ensure-convert-binding
                                           env'
                                           {:var-name name
                                            :type (tc/attachable-type (infer-type-or-any env' value))})]
              [env'' (conj bindings lowered-binding)]))
          [env []]
          (tc/attached-test-guards condition)))

(defn- lower-boolean-condition
  [env expr]
  (if (and (map? expr)
           (= :binary (:type expr)))
    (case (:operator expr)
      "and"
      (let [[left-env left-ir] (lower-boolean-condition env (:left expr))
            right-input-env (refine-condition-branch-env left-env (:left expr) :then)
            [right-env right-ir] (lower-boolean-condition right-input-env (:right expr))]
        [right-env
         (ir/binary-node :and left-ir right-ir "Boolean" :boolean)])

      "or"
      (let [[left-env left-ir] (lower-boolean-condition env (:left expr))
            [right-env right-ir] (lower-boolean-condition left-env (:right expr))]
        [right-env
         (ir/binary-node :or left-ir right-ir "Boolean" :boolean)])

      (let [[env' _] (ensure-convert-bindings (scoped-child-env env) expr)
            [env'' _] (ensure-attached-test-bindings env' expr)]
        [env'' (lower-expression env'' expr)]))
    (let [[env' _] (ensure-convert-bindings (scoped-child-env env) expr)
          [env'' _] (ensure-attached-test-bindings env' expr)]
      [env'' (lower-expression env'' expr)])))

(defn- convert-binding-init-stmts
  [env condition]
  (->> (concat (tc/convert-guard-bindings condition)
               (tc/attached-test-guards condition))
       (keep (fn [{:keys [name]}]
               (when-let [{:keys [kind slot nex-type jvm-type]} (lookup-convert-binding env name)]
                 (when (= kind :local)
                   (ir/set-local-node slot
                                      (default-const-node nex-type jvm-type)
                                      nex-type
                                      jvm-type)))))
       vec))

(defn- refine-var-non-nil
  [env var-name]
  (let [current-type (or (get-in env [:locals var-name :nex-type])
                         (get (:var-types env) var-name))]
    (if current-type
      (let [refined-type (tc/attachable-type current-type)]
        ;; Refine only the Nex type. The slot's JVM type is fixed at
        ;; allocation (detachable bindings live in reference slots); re-resolving
        ;; the attached type here can flip it to a primitive and emit a
        ;; primitive load from a reference slot (VerifyError).
        (cond-> env
          (get-in env [:locals var-name])
          (assoc-in [:locals var-name :nex-type] refined-type)
          true
          (assoc-in [:var-types var-name] refined-type)))
      env)))

(defn- refine-condition-branch-env
  [env condition branch]
  (case branch
    :then
    (let [env' (if-let [var-name (tc/guarded-non-nil-var condition)]
                 (refine-var-non-nil env var-name)
                 env)]
      (reduce (fn [acc {:keys [name type]}]
                (refine-var-non-nil
                 (cond
                   (get-in acc [:locals name])
                   (let [refined-type (tc/attachable-type type)]
                     ;; Refine only the Nex type: the slot was allocated for the
                     ;; detachable (reference) binding and its JVM type cannot
                     ;; change per-branch — a primitive re-resolve here would
                     ;; emit DLOAD from an ASTORE'd slot (VerifyError).
                     (-> acc
                         (assoc-in [:locals name :nex-type] refined-type)
                         (assoc-in [:var-types name] refined-type)))

                   (:top-level? acc)
                   (assoc-in acc [:var-types name] (tc/attachable-type type))

                   :else acc)
                 name))
              env'
              ;; convert-guard-bindings' :type comes straight from the AST
              ;; (the user-written target type); an attached-test guard has
              ;; none, so its current (pre-refinement) type is looked up from
              ;; the binding ensure-attached-test-bindings already allocated
              ;; in `env'` — see lower-boolean-condition.
              (concat (tc/convert-guard-bindings condition)
                      (keep (fn [{:keys [name]}]
                              (when-let [t (or (get-in env' [:locals name :nex-type])
                                               (get (:var-types env') name))]
                                {:name name :type t}))
                            (tc/attached-test-guards condition)))))

    :else
    (if-let [var-name (tc/guarded-else-non-nil-var condition)]
      (refine-var-non-nil env var-name)
      env)

    env))

(defn- class-jvm-meta
  [env class-name]
  (or (get (:compiled-classes env) class-name)
      (throw (ex-info "Missing compiled class metadata during lowering"
                      {:class-name class-name}))))

(defn- class-self-registration-name
  "The name class-def should be treated as *its own identity* under during
   lowering — its JVM internal name, and everything self-referential inside
   its own body (:current-class, :this-type, runtime type tags — see
   lower-class-def). Bare :name, unless that name is a genuine collision:
   compiled-classes' bare-name slot (docs/proposals/namespaces.md, Phase 3/4)
   holds whichever class won merge-visible-classes'/file-class-metadata's
   bare-name collapse, which is this same class-def for the overwhelming
   majority of interned classes (only a real same-bare-name collision loses
   it to a *different* class-def) — file-class-metadata already gives a
   winner the SAME internal name under both its bare and qualified keys, so
   comparing the two tells us which case this is without needing the full
   class list. Preferring :qualified-name unconditionally here would lower
   every interned class — not just colliding ones — under a JVM identity
   none of its ordinary *bare* references (a plain `let x: Circle`, another
   class's `inherit Circle`, a bare match clause) resolve to: a LinkageError
   or a runtime type-tag mismatch for programs that never had a collision at
   all.

   Guards separately against a registry that never computed a qualified
   entry at all (compiled-classes here comes from whichever caller built it —
   nex.compiler.jvm.file/file-class-metadata is one, but not the only one;
   the REPL's own class-compilation path, nex.compiler.jvm.repl, builds its
   own and was not updated to add qualified-key entries the way
   file-class-metadata was). There, `(get compiled-classes qualified)` is
   simply absent — nil, not a differently-valued entry — and treating a
   missing lookup the same as a genuinely different one wrongly reads
   \"entirely unregistered\" as \"a collision\", sending an ordinary,
   non-colliding class down the qualified path into a
   \"Missing compiled class metadata\" error instead of falling back to the
   bare name, the only one such a registry actually has metadata for."
  [compiled-classes class-def]
  (let [bare (:name class-def)
        qualified (:qualified-name class-def)
        qualified-meta (when qualified (get compiled-classes qualified))]
    (if (and qualified-meta
             (not= (:internal-name qualified-meta)
                   (:internal-name (get compiled-classes bare))))
      qualified
      bare)))

(defn- user-class-defs
  [program]
  (let [synthetic-class-names (set (keep :class-name (:functions program)))]
    (remove #(contains? synthetic-class-names (:name %))
            (:classes program))))

(defn- infer-prepass-type
  [ctx local-types expr]
  (or (tc/infer-expression-type expr {:classes (:classes ctx)
                                      :functions (:functions ctx)
                                      :imports (:imports ctx)
                                      :var-types (merge (:var-types ctx) local-types)})
      "Any"))

(defn- synthetic-capture-field
  [{:keys [name type]}]
  {:type :field
   :name name
   :field-type type
   :note nil
   :constant? false
   :synthetic? true})

(defn- next-synthetic-closure-class-name
  []
  ;; Distinct prefix from walker.clj's generate-unique-fn-name (also
  ;; "AnonymousFunction_N", used for source-level `fn(...) do ... end`
  ;; closures at parse time): that counter and this one are separate atoms,
  ;; so sharing a format let a `spawn` block's synthesized wrapper closure
  ;; land on the same name as an unrelated user-written closure whenever both
  ;; counters happened to reach the same N. Two closures with the same class
  ;; name silently collapse to one entry wherever the compiler collects
  ;; anonymous class-defs into a name-keyed table (e.g.
  ;; nex.compiler.jvm.file/collect-anonymous-class-defs' dedup-by-name), so
  ;; the loser's method body vanishes and calling it fails at runtime with an
  ;; opaque "Method not found: callN" — see this project's radar_service
  ;; postmortem for the reproduction.
  (str "AnonymousSpawn_" (swap! next-synthetic-closure-id inc)))

(defn- make-synthetic-anonymous-function-expr
  [params return-type body]
  (let [class-name (next-synthetic-closure-class-name)
        method-name (str "call" (count (or params [])))
        method-def {:type :method
                    :name method-name
                    :params params
                    :return-type return-type
                    :note nil
                    :require nil
                    :body body
                    :ensure nil}
        class-def {:type :class
                   :name class-name
                   :generic-params nil
                   :note nil
                   :parents [{:parent "Function"}]
                   :body [{:type :feature-section
                           :visibility {:type :public}
                           :members [method-def]}]
                   :invariant nil}]
    {:type :anonymous-function
     :class-name class-name
     :params params
     :return-type return-type
     :body body
     :class-def class-def}))

(defn- attach-capture-fields
  [class-def captures runtime-object?]
  (let [capture-members (mapv synthetic-capture-field captures)
        feature-sections (filter #(= :feature-section (:type %)) (:body class-def))
        first-section (first feature-sections)]
    (cond-> class-def
      true
      (assoc :closure-runtime-object? (boolean runtime-object?))

      (seq captures)
      (assoc :body
             (if first-section
               (mapv (fn [section]
                       (if (identical? section first-section)
                         (update section :members #(vec (concat capture-members %)))
                         section))
                     (:body class-def))
               (vec (cons {:type :feature-section
                           :visibility {:type :public}
                           :members capture-members}
                          (:body class-def))))))))

(declare rewrite-expression-for-closures)
(declare rewrite-statement-for-closures)
(declare rewrite-statements-for-closures)
(declare anonymous-function-signature-type)
(declare rewrite-statements-for-closures*)
(declare sync-callable-into-class-def)

(defn- capture-reference!
  [captures local-types outer-var-types name]
  (when (and (not (contains? local-types name))
             (contains? outer-var-types name))
    (swap! captures assoc name (get outer-var-types name))))

;; Synthetic capture name for the enclosing instance method's `this`, used by
;; a spawn/anonymous-function body that references `this` (bare field/method
;; access or an explicit `this.` prefix). Reserved: no Nex identifier can
;; start with a double underscore, so this can never collide with a real
;; capture. A spawn/anonymous-function body with any capture (this one
;; included) is dispatched to the tree-walking interpreter at runtime rather
;; than running as compiled bytecode (nex.compiler.jvm.runtime/make-captured-
;; function-object) — so every reference to `this` inside such a body is
;; rewritten here into an ordinary field/method access on this captured
;; identifier, the same shape an outer captured *other* object's field/method
;; access already uses, rather than left as `this` (which the interpreter
;; would resolve against the closure's own synthesized class, not the
;; enclosing one).
(def ^:private closure-this-capture-name "__closure_this__")

(defn- ctx-class-def
  [ctx class-name]
  (some #(when (= (:name %) class-name) %) (:classes ctx)))

;; All of this-type-field?/this-type-method?/capture-closure-this! require
;; :inside-closure? in addition to :this-type: :this-type is set once, on
;; ctx, for every method of a class — including the plain (non-closure) body
;; of the method itself — while :inside-closure? is only set on the
;; nested-ctx built for a spawn/anonymous-function's *own* body (see the
;; :anonymous-function and :spawn cases below). Gating on :this-type alone
;; rewrote an ordinary `this.field := v`/bare `field := v` in the method's own
;; (non-closure) statements too, pointing it at a capture
;; (closure-this-capture-name) that only the nested closure's env would ever
;; define — an ordinary method body needs no such rewriting; `this` already
;; resolves correctly there.
(defn- capture-closure-this!
  [captures ctx]
  (when (and (:this-type ctx) (:inside-closure? ctx))
    (swap! captures assoc closure-this-capture-name (:this-type ctx))))

(defn- this-type-field?
  [ctx name]
  (boolean (and (:this-type ctx)
                (:inside-closure? ctx)
                (accessible-field-def ctx (ctx-class-def ctx (:this-type ctx)) name))))

(defn- this-type-method?
  [ctx name arity]
  (boolean (and (:this-type ctx)
                (:inside-closure? ctx)
                (accessible-method-def ctx (ctx-class-def ctx (:this-type ctx)) name arity))))

;; A bare field read of one of the enclosing method's own fields (`count`,
;; meaning `this.count`): a spawn/anonymous-function body is dispatched to
;; the tree-walking interpreter at runtime (nex.compiler.jvm.runtime/make-
;; captured-function-object), with `this` captured like any other outer
;; variable under closure-this-capture-name. Rewriting the bare read into
;; an explicit field-get on that captured identifier — the same shape an
;; ordinary captured *other* object's field read already uses — means the
;; interpreter needs no special "this" handling for it at all.
(defn- rewrite-identifier-for-closures
  [ctx local-types captures expr]
  (if (and (not (contains? local-types (:name expr)))
           (not (contains? (:var-types ctx) (:name expr)))
           (this-type-field? ctx (:name expr)))
    (do (capture-closure-this! captures ctx)
        {:type :call
         :target {:type :identifier :name closure-this-capture-name}
         :method (:name expr)
         :args []
         :has-parens false})
    (do
      (capture-reference! captures local-types (:var-types ctx) (:name expr))
      expr)))

(defn- rewrite-anonymous-function-for-closures
  [ctx local-types captures expr]
  (let [params (or (:params expr) [])
        fn-locals (into {"result" (or (:return-type expr) "Any")}
                        (map (fn [{:keys [name type]}] [name type]))
                        params)
        nested-ctx (assoc ctx :var-types (merge (:var-types ctx) local-types) :inside-closure? true)
        [rewritten-body _ nested-captures]
        (rewrite-statements-for-closures nested-ctx fn-locals (:body expr))
        capture-vec (->> nested-captures
                         (map (fn [[name type]] {:name name :type type}))
                         (sort-by :name)
                         vec)
      ;; A name this nested closure captures is free from *this* scope's
      ;; point of view too, unless `local-types` (this level's own
      ;; params/lets) already supplies it. When it isn't, the enclosing
      ;; closure must also capture it — it has to hold the value in a
      ;; field of its own so it can hand it to the nested closure's
      ;; constructor at the point it builds it. Without this, only
      ;; directly-referenced names ever reach `captures` here, so a
      ;; capture two (or more) closures deep never propagates outward:
      ;; the enclosing closure compiles as if it captured nothing, and
      ;; lowering the nested closure's construction inside it then can't
      ;; resolve the name at all.
        _ (doseq [{:keys [name]} capture-vec]
            (capture-reference! captures local-types (:var-types ctx) name))
        runtime-object? (seq capture-vec)
      ;; (:class-def expr) still holds the call<N> method's *original*
      ;; body — attach-capture-fields only adds capture fields, it never
      ;; touches method bodies. Ordinarily that staleness is harmless (a
      ;; plain captured-variable reference rewrites to itself, unchanged),
      ;; but a `this` reference above rewrites into a genuinely different
      ;; node shape. interp/make-object (nex.compiler.jvm.runtime/make-
      ;; captured-function-object) runs *this* class-def's method body at
      ;; call time — so without this sync, the interpreter would still see
      ;; the pre-rewrite `this`/bare field or method reference and try to
      ;; resolve it against the closure's own (fieldless, methodless)
      ;; class instead of the captured original.
        call-method-name (str "call" (count params))
        original-call-method (some #(when (and (= call-method-name (:name %))
                                               (= (count params) (count (or (:params %) []))))
                                      %)
                                   (class-methods (:class-def expr)))]
    (assoc expr
           :body rewritten-body
           :captures capture-vec
           :class-def (attach-capture-fields
                       (sync-callable-into-class-def
                        (:class-def expr)
                        (assoc original-call-method :body rewritten-body))
                       capture-vec runtime-object?))))

;; A method call or explicit field access reaching `this` — bare
;; (`bump()`/`count` as a no-parens access), or via an explicit `this.`
;; prefix — is rewritten the same way: route it through the captured
;; `this` identifier, exactly like calling/reading through any other
;; captured object reference (`other.bump()`), which already works.
(defn- rewrite-call-for-closures
  [ctx local-types captures expr]
  (let [target (:target expr)
        method (:method expr)
        bare-target? (nil? target)
      ;; Explicit `this.foo` is only ever rewritten to the captured-`this`
      ;; identifier *inside* a closure — outside one, `this` resolves
      ;; correctly on its own (this is the ordinary, non-nested case: an
      ;; instance method's own body referencing its own `this`), and
      ;; substituting it unconditionally broke exactly that: `this.value`
      ;; in a plain method (no spawn/anonymous-function involved at all)
      ;; was rewritten into a call on a capture that is only ever bound
      ;; inside a synthesized closure class.
        this-target? (and (map? target) (= :this (:type target))
                          (:inside-closure? ctx))
        is-field? (false? (:has-parens expr))
        implicit-this-member?
        (and bare-target?
             (not (contains? local-types method))
             (not (contains? (:var-types ctx) method))
             (or (this-type-method? ctx method (count (:args expr)))
                 (and is-field? (this-type-field? ctx method))))
        _ (when (string? target)
            (capture-reference! captures local-types (:var-types ctx) target))
        _ (when (or implicit-this-member? this-target?)
            (capture-closure-this! captures ctx))
        args (mapv #(rewrite-expression-for-closures ctx local-types captures %)
                   (:args expr))
        new-target (cond
                     (or implicit-this-member? this-target?)
                     {:type :identifier :name closure-this-capture-name}

                     target
                     (rewrite-expression-for-closures ctx local-types captures target)

                     :else nil)]
    (when (and bare-target?
               (not implicit-this-member?)
               (contains? (:var-types ctx) method)
               (not (contains? local-types method)))
      (swap! captures assoc method (get (:var-types ctx) method)))
    (assoc expr :target new-target :args args)))

(defn- rewrite-if-for-closures
  [ctx local-types captures expr]
  (assoc expr
         :condition (rewrite-expression-for-closures ctx local-types captures (:condition expr))
         :then (first (rewrite-statements-for-closures* ctx local-types captures (:then expr)))
         :elseif (mapv (fn [clause]
                         (assoc clause
                                :condition (rewrite-expression-for-closures ctx local-types captures (:condition clause))
                                :then (first (rewrite-statements-for-closures* ctx local-types captures (:then clause)))))
                       (:elseif expr))
         :else (first (rewrite-statements-for-closures* ctx local-types captures (:else expr)))))

(defn- rewrite-spawn-for-closures
  [ctx local-types captures expr]
  (let [nested-ctx (assoc ctx :var-types (merge (:var-types ctx) local-types) :inside-closure? true)
        fn-expr (make-synthetic-anonymous-function-expr
                 []
                 "Any"
                 (:body expr))
        rewritten-fn (rewrite-expression-for-closures nested-ctx {} (atom {}) fn-expr)]
    (assoc expr :fn-expr rewritten-fn)))

(defn- rewrite-expression-for-closures
  [ctx local-types captures expr]
  (cond
    (not (map? expr))
    expr

    (= :this (:type expr))
    (if (and (:this-type ctx) (:inside-closure? ctx))
      (do (capture-closure-this! captures ctx)
          {:type :identifier :name closure-this-capture-name})
      expr)

    (= :identifier (:type expr))
    (rewrite-identifier-for-closures ctx local-types captures expr)

    (= :anonymous-function (:type expr))
    (rewrite-anonymous-function-for-closures ctx local-types captures expr)

    (= :call (:type expr))
    (rewrite-call-for-closures ctx local-types captures expr)

    (= :binary (:type expr))
    (assoc expr
           :left (rewrite-expression-for-closures ctx local-types captures (:left expr))
           :right (rewrite-expression-for-closures ctx local-types captures (:right expr)))

    (= :unary (:type expr))
    (assoc expr :expr (rewrite-expression-for-closures ctx local-types captures (:expr expr)))

    (= :array-literal (:type expr))
    (assoc expr :elements (mapv #(rewrite-expression-for-closures ctx local-types captures %)
                                (:elements expr)))

    (= :set-literal (:type expr))
    (assoc expr :elements (mapv #(rewrite-expression-for-closures ctx local-types captures %)
                                (:elements expr)))

    (= :map-literal (:type expr))
    (assoc expr :entries (mapv (fn [{:keys [key value]}]
                                 {:key (rewrite-expression-for-closures ctx local-types captures key)
                                  :value (rewrite-expression-for-closures ctx local-types captures value)})
                               (:entries expr)))

    (= :if (:type expr))
    (rewrite-if-for-closures ctx local-types captures expr)

    (= :when (:type expr))
    (assoc expr
           :condition (rewrite-expression-for-closures ctx local-types captures (:condition expr))
           :consequent (rewrite-expression-for-closures ctx local-types captures (:consequent expr))
           :alternative (rewrite-expression-for-closures ctx local-types captures (:alternative expr)))

    (= :old (:type expr))
    (assoc expr :expr (rewrite-expression-for-closures ctx local-types captures (:expr expr)))

    (= :convert (:type expr))
    (assoc expr :value (rewrite-expression-for-closures ctx local-types captures (:value expr)))

    (= :attached-test (:type expr))
    (assoc expr :value (rewrite-expression-for-closures ctx local-types captures (:value expr)))

    (= :create (:type expr))
    (assoc expr :args (mapv #(rewrite-expression-for-closures ctx local-types captures %) (:args expr)))

    (= :spawn (:type expr))
    (rewrite-spawn-for-closures ctx local-types captures expr)

    :else expr))

(defn- rewrite-self-recursive-calls
  "Rewrite a bare, self-recursive call to SELF-NAME (`fact(n-1)` inside the
   body of `let fact := fn(n) do ... fact(n-1) ... end`) into a bare call
   to `callN` instead — the exact shape a plain `self_method(...)` call
   already lowers through (nex.lower/lower-call-without-target's first
   branch, `class-method-def (current-class-def env) ...`), since callN IS
   a real member of this closure's own class-def (attach-capture-fields).
   No new AST/IR node type, no env threading into the later lowering
   pass — the rename alone is enough, because JVM `this` inside a
   closure's own callN method already refers to the closure instance
   itself.

   A plain recursive-descent walk, not clojure.walk/postwalk: it must NOT
   descend into a nested `:anonymous-function`'s own :body at all — a
   deeper closure reached inside this one (a call argument, say) has its
   own, unrelated scope, and if IT also happens to bare-call something
   named SELF-NAME, that reference is not this closure calling itself.
   Stopping at that boundary is what keeps this rewrite correctly scoped
   without any ctx-threading (and its leak risk) between closures at
   different nesting depths."
  [self-name node]
  (cond
    (and (map? node) (= :anonymous-function (:type node)))
    node

    (and (map? node) (= :call (:type node)) (nil? (:target node)) (= self-name (:method node)))
    (assoc node
           :method (str "call" (count (:args node)))
           :args (mapv #(rewrite-self-recursive-calls self-name %) (:args node)))

    (map? node)
    (into (empty node) (map (fn [[k v]] [k (rewrite-self-recursive-calls self-name v)])) node)

    (vector? node)
    (mapv #(rewrite-self-recursive-calls self-name %) node)

    (seq? node)
    (map #(rewrite-self-recursive-calls self-name %) node)

    :else node))

(defn- rewrite-let-stmt-for-closures
  [ctx local-types captures stmt]
  (let [value (if (and (map? (:value stmt)) (= :anonymous-function (:type (:value stmt))))
                (update (:value stmt) :body #(rewrite-self-recursive-calls (:name stmt) %))
                (:value stmt))
        value' (rewrite-expression-for-closures ctx local-types captures value)
        stmt' (assoc stmt :value value')
      ;; anonymous-function-signature-type first, from the ORIGINAL
      ;; (pre-rewrite) `value` — not infer-prepass-type on the
      ;; already-rewritten `value'` — for the identical reason
      ;; box-let-type reads a boxed closure-let's type the same way:
      ;; infer-prepass-type type-checks the whole body in an
      ;; isolated, standalone env, which throws (silently swallowed,
      ;; falling back to "Any") the moment that body references a
      ;; sibling closure elaborated together with this one (self- or
      ;; mutual recursion) — a name that isolated check knows nothing
      ;; about. Without this, an untyped mutually-recursive closure's
      ;; own LOCAL-TYPES entry (what a SIBLING closure sees when IT
      ;; captures this one) silently erased to "Any" too, one call
      ;; site short of box-let-type's own (the backward-referenced
      ;; half of a mutual pair is never boxed, so it relied on this
      ;; exact var-type instead) — a lowering-time crash or, further
      ;; downstream (a REPL session persisting this var-type across
      ;; cells), a "Method not found" call dispatched against the
      ;; erased "Any" type in a later, separate cell.
        var-type (or (:var-type stmt)
                     (anonymous-function-signature-type value)
                     (infer-prepass-type ctx local-types value'))]
    [stmt' (assoc local-types (:name stmt) var-type)]))

;; A bare `count := v` inside a spawn/anonymous-function body means
;; `this.count := v`. Since the closure body runs on the interpreter (see
;; the :identifier case above), rewrite it into an explicit member-assign
;; through the captured `this` identifier — the same shape an ordinary
;; captured *other* object's field write already uses (`other.count := v`,
;; unlike the untouched form, does not depend on `this` being resolvable
;; inside a class the interpreter never sees as the original enclosing
;; class).
(defn- rewrite-assign-stmt-for-closures
  [ctx local-types captures stmt]
  (if (and (not (contains? local-types (:target stmt)))
           (not (contains? (:var-types ctx) (:target stmt)))
           (this-type-field? ctx (:target stmt)))
    (do (capture-closure-this! captures ctx)
        [{:type :member-assign
          :object {:type :identifier :name closure-this-capture-name}
          :field (:target stmt)
          :value (rewrite-expression-for-closures ctx local-types captures (:value stmt))}
         local-types])
    [(assoc stmt :value (rewrite-expression-for-closures ctx local-types captures (:value stmt)))
     local-types]))

(defn- rewrite-member-assign-stmt-for-closures
  [ctx local-types captures stmt]
  (let [this-object? (or (nil? (:object stmt))
                         (= :this (:type (:object stmt))))
        rewrite-to-capture? (and this-object? (:this-type ctx) (:inside-closure? ctx))]
    (when rewrite-to-capture?
      (capture-closure-this! captures ctx))
    [(assoc stmt
            :object (cond
                      rewrite-to-capture? {:type :identifier :name closure-this-capture-name}
                      (:object stmt) (rewrite-expression-for-closures ctx local-types captures (:object stmt))
                      :else nil)
            :value (rewrite-expression-for-closures ctx local-types captures (:value stmt)))
     local-types]))

(defn- rewrite-if-stmt-for-closures
  [ctx local-types captures stmt]
  [(assoc stmt
          :condition (rewrite-expression-for-closures ctx local-types captures (:condition stmt))
          :then (first (rewrite-statements-for-closures* ctx local-types captures (:then stmt)))
          :elseif (mapv (fn [clause]
                          (assoc clause
                                 :condition (rewrite-expression-for-closures ctx local-types captures (:condition clause))
                                 :then (first (rewrite-statements-for-closures* ctx local-types captures (:then clause)))))
                        (:elseif stmt))
          :else (first (rewrite-statements-for-closures* ctx local-types captures (:else stmt))))
   local-types])

(defn- rewrite-case-stmt-for-closures
  [ctx local-types captures stmt]
  [(assoc stmt
          :expr (rewrite-expression-for-closures ctx local-types captures (:expr stmt))
          :clauses (mapv (fn [clause]
                           (assoc clause
                                  :values (mapv #(rewrite-expression-for-closures ctx local-types captures %)
                                                (:values clause))
                                  :body (first (rewrite-statement-for-closures ctx local-types captures (:body clause)))))
                         (:clauses stmt))
          :else (when (:else stmt)
                  (first (rewrite-statement-for-closures ctx local-types captures (:else stmt)))))
   local-types])

(defn- rewrite-match-stmt-for-closures
  [ctx local-types captures stmt]
  [(assoc stmt
          :expr (rewrite-expression-for-closures ctx local-types captures (:expr stmt))
          :clauses (mapv (fn [clause]
                           (let [clause-local-types (assoc local-types (:var-name clause) (:class-name clause))]
                             (assoc clause
                                    :body (first (rewrite-statements-for-closures* ctx clause-local-types captures (:body clause))))))
                         (:clauses stmt))
          :else (when (:else stmt)
                  (first (rewrite-statements-for-closures* ctx local-types captures (:else stmt)))))
   local-types])

(defn- rewrite-loop-stmt-for-closures
  [ctx local-types captures stmt]
  (let [[init' local-types'] (rewrite-statements-for-closures* ctx local-types captures (:init stmt))]
    [(assoc stmt
            :init init'
            :until (rewrite-expression-for-closures ctx local-types' captures (:until stmt))
            :variant (when (:variant stmt)
                       (rewrite-expression-for-closures ctx local-types' captures (:variant stmt)))
            :invariant (mapv (fn [inv]
                               (assoc inv :condition (rewrite-expression-for-closures ctx local-types' captures (:condition inv))))
                             (:invariant stmt))
            :body (first (rewrite-statements-for-closures* ctx local-types' captures (:body stmt))))
     local-types]))

(defn- rewrite-select-stmt-for-closures
  [ctx local-types captures stmt]
  [(assoc stmt
          :clauses (mapv (fn [{:keys [expr alias body] :as clause}]
                           (assoc clause
                                  :expr (rewrite-expression-for-closures ctx local-types captures expr)
                                  :body (first (rewrite-statements-for-closures* ctx
                                                                                 (cond-> local-types
                                                                                   alias (assoc alias "Any"))
                                                                                 captures
                                                                                 body))))
                         (:clauses stmt))
          :timeout (when-let [timeout (:timeout stmt)]
                     (assoc timeout
                            :duration (rewrite-expression-for-closures ctx local-types captures (:duration timeout))
                            :body (first (rewrite-statements-for-closures* ctx local-types captures (:body timeout)))))
          :else (when (:else stmt)
                  (first (rewrite-statements-for-closures* ctx local-types captures (:else stmt)))))
   local-types])

(defn- rewrite-statement-for-closures
  [ctx local-types captures stmt]
  (case (:type stmt)
    :let
    (rewrite-let-stmt-for-closures ctx local-types captures stmt)

    :assign
    (rewrite-assign-stmt-for-closures ctx local-types captures stmt)

    :member-assign
    (rewrite-member-assign-stmt-for-closures ctx local-types captures stmt)

    :call
    [(rewrite-expression-for-closures ctx local-types captures stmt)
     local-types]

    :convert
    (let [value' (rewrite-expression-for-closures ctx local-types captures (:value stmt))
          stmt' (assoc stmt :value value')]
      [stmt' (assoc local-types
                    (:var-name stmt)
                    (tc/detachable-version (:target-type stmt)))])

    :if
    (rewrite-if-stmt-for-closures ctx local-types captures stmt)

    :case
    (rewrite-case-stmt-for-closures ctx local-types captures stmt)

    :match
    (rewrite-match-stmt-for-closures ctx local-types captures stmt)

    :loop
    (rewrite-loop-stmt-for-closures ctx local-types captures stmt)

    :select
    (rewrite-select-stmt-for-closures ctx local-types captures stmt)

    :scoped-block
    [(assoc stmt
            :body (first (rewrite-statements-for-closures* ctx local-types captures (:body stmt)))
            :rescue (when (:rescue stmt)
                      (first (rewrite-statements-for-closures* ctx (assoc local-types "exception" "Any") captures (:rescue stmt)))))
     local-types]

    :raise
    [(assoc stmt :value (rewrite-expression-for-closures ctx local-types captures (:value stmt)))
     local-types]

    [stmt local-types]))

(defn- rewrite-statements-for-closures*
  [ctx local-types captures statements]
  (loop [remaining (vec statements)
         current-local-types local-types
         rewritten []]
    (if-let [stmt (first remaining)]
      (let [[stmt' next-local-types] (rewrite-statement-for-closures ctx current-local-types captures stmt)]
        (recur (subvec remaining 1) next-local-types (conj rewritten stmt')))
      [rewritten current-local-types @captures])))

(defn- rewrite-statements-for-closures
  [ctx local-types statements]
  (rewrite-statements-for-closures* ctx local-types (atom {}) statements))

(defn- sync-callable-into-class-def
  [class-def callable]
  (let [callable-name (:name callable)
        callable-arity (count (or (:params callable) []))]
    (update class-def :body
            (fn [sections]
              (mapv (fn [section]
                      (if (= :feature-section (:type section))
                        (update section :members
                                (fn [members]
                                  (mapv (fn [member]
                                          (if (and (= :method (:type member))
                                                   (= callable-name (:name member))
                                                   (= callable-arity (count (or (:params member) []))))
                                            callable
                                            member))
                                        members)))
                        section))
                    sections)))))

(defn- rewrite-callable-for-closures
  [ctx callable initial-var-types]
  (let [params (or (:params callable) [])
        local-types (into {"result" (or (:return-type callable) "Any")}
                          (map (fn [{:keys [name type]}] [name type]))
                          params)
        [body _ _] (rewrite-statements-for-closures (assoc ctx :var-types initial-var-types)
                                                    local-types
                                                    (:body callable))
        rewritten-callable (cond-> (assoc callable :body body)
                             (:rescue callable)
                             (assoc :rescue (first (rewrite-statements-for-closures (assoc ctx :var-types initial-var-types)
                                                                                    (assoc local-types "exception" "Any")
                                                                                    (:rescue callable)))))]
    (cond-> rewritten-callable
      (:class-def callable)
      (assoc :class-def (sync-callable-into-class-def (:class-def callable) rewritten-callable)))))

(defn- rewrite-class-for-closures
  [ctx class-def]
  ;; :this-type (not field-type-map merged into var-types, as a prior version
  ;; of this function did) is what lets a nested spawn/anonymous-function body
  ;; tell "this is one of the enclosing instance's own fields/methods" apart
  ;; from an ordinary outer local capture — see this-type-field?/this-type-
  ;; method?. Fields are deliberately left out of var-types now: folding them
  ;; in there made a bare `count` inside a closure look like any other
  ;; captured local, so the closure got its own private copy of the *value*
  ;; instead of a live reference to the enclosing object, silently dropping
  ;; mutations the moment the closure ran (spawn/anonymous-function bodies
  ;; could read a field but never durably write one).
  (let [ctx (assoc ctx :this-type (:name class-def))]
    (update class-def :body
            (fn [sections]
              (mapv (fn [section]
                      (case (:type section)
                        :feature-section
                        (update section :members
                                (fn [members]
                                  (mapv (fn [member]
                                          (if (= :method (:type member))
                                            (rewrite-callable-for-closures ctx member {})
                                            member))
                                        members)))

                        :constructors
                        (update section :constructors
                                (fn [ctors]
                                  (mapv #(rewrite-callable-for-closures ctx % {}) ctors)))

                        section))
                    sections)))))

(defn- patch-anonymous-function-class-def
  "Rebuild CLASS-DEF's single method member with PATCHED-PARAMS/PATCHED-RETURN
   — the mirrored copy of an :anonymous-function node's own :params/
   :return-type that `nex.lower`'s class-compilation actually reads."
  [class-def patched-params patched-return]
  (let [patch-section
        (fn [section]
          (if (= :feature-section (:type section))
            (let [patch-member (fn [m]
                                 (if (= :method (:type m))
                                   (assoc m :params patched-params :return-type patched-return)
                                   m))]
              (update section :members #(mapv patch-member %)))
            section))]
    (update class-def :body #(mapv patch-section %))))

(defn- patch-anonymous-function-types-for-let
  "Infer an untyped `fn(...)`'s params/return-type from a typed `let`'s
   declared `Function(...)` type — the same shorthand
   `nex.typechecker/patch-anonymous-function-types` resolves for typechecking
   — but resolved *here* too, structurally, because the compiled backend
   compiles every anonymous-function class as a whole-program pass
   (`collect-anonymous-class-defs`) before any individual statement is
   lowered: by the time expression-lowering would otherwise see this literal,
   its (still nil-typed) class-def has already been compiled into bytecode
   with the wrong descriptor. Returns VALUE unchanged unless it is an
   `:anonymous-function` node with a nil param or return type and VAR-TYPE is
   a matching-arity structural `Function(...)` (arity mismatches are left for
   the typechecker's own, already-correct error — this pass only fills gaps,
   it does not validate). VAR-TYPE is resolved through `resolve-type-alias`
   first so a `declare type Pred = Function(...)` alias used as the `let`'s
   annotation is seen through to its underlying structural shape, the same
   way the typechecker's own `check-expression-with-expected` now does via
   `expand-type-aliases`."
  [var-type value]
  (let [var-type (resolve-type-alias var-type)
        needs-patch? (and (map? var-type) (= (:base-type var-type) "Function")
                          (:param-types var-type)
                          (map? value) (= :anonymous-function (:type value))
                          (= (count (:params value)) (count (:param-types var-type)))
                          (or (some (comp nil? :type) (:params value))
                              (nil? (:return-type value))))]
    (if-not needs-patch?
      value
      (let [patched-params (mapv (fn [p t] (if (:type p) p (assoc p :type (:type t))))
                                 (:params value) (:param-types var-type))
            patched-return (or (:return-type value) (:return-type var-type))]
        (-> value
            (assoc :params patched-params :return-type patched-return)
            (update :class-def #(patch-anonymous-function-class-def % patched-params patched-return)))))))

(defn- record-let-type
  "Extend VAR-TYPES with STMT's own binding if STMT is a typed `:let` — used
   to track declared-`let` types across a statement sequence so a later plain
   `x := fn(...)` reassignment can still recover x's declared signature."
  [var-types stmt]
  (if (and (map? stmt) (= :let (:type stmt)) (:var-type stmt) (string? (:name stmt)))
    (assoc var-types (:name stmt) (:var-type stmt))
    var-types))

(declare resolve-anonymous-function-types-in-stmts)

(defn- resolve-anonymous-function-types-in-stmt
  "Walk STMT (and every nested statement inside if/loop/scoped-block/with/
   case/match bodies — the same shape `constructor-statements` above walks)
   applying `patch-anonymous-function-types-for-let`, both to a `:let`'s own
   value (via its own :var-type, as before) and to a later plain `x :=
   fn(...)` reassignment of a variable whose declared type is already known
   — looked up in VAR-TYPES (local variable name -> declared `let`/parameter
   type accumulated so far in this scope), since a bare `:assign` carries no
   type annotation of its own to patch from."
  [stmt var-types]
  (let [stmt (cond
               (and (map? stmt) (= :let (:type stmt)) (:var-type stmt))
               (update stmt :value #(patch-anonymous-function-types-for-let (:var-type stmt) %))

               (and (map? stmt) (= :assign (:type stmt)) (string? (:target stmt))
                    (contains? var-types (:target stmt)))
               (update stmt :value #(patch-anonymous-function-types-for-let
                                     (get var-types (:target stmt)) %))

               :else stmt)
        walk (fn [stmts] (first (resolve-anonymous-function-types-in-stmts stmts var-types)))]
    (if-not (map? stmt)
      stmt
      (case (:type stmt)
        :if (let [stmt (assoc stmt
                              :then (walk (:then stmt))
                              :elseif (mapv (fn [c] (assoc c :then (walk (:then c)))) (:elseif stmt)))]
              (if (:else stmt) (assoc stmt :else (walk (:else stmt))) stmt))
        :loop (assoc stmt :init (walk (:init stmt)) :body (walk (:body stmt)))
        :scoped-block (let [stmt (assoc stmt :body (walk (:body stmt)))]
                        (if (:rescue stmt) (assoc stmt :rescue (walk (:rescue stmt))) stmt))
        :with (assoc stmt :body (walk (:body stmt)))
        :case (let [stmt (assoc stmt :clauses
                                (mapv (fn [c] (update c :body #(resolve-anonymous-function-types-in-stmt % var-types)))
                                      (:clauses stmt)))]
                (if (:else stmt)
                  (assoc stmt :else (resolve-anonymous-function-types-in-stmt (:else stmt) var-types))
                  stmt))
        :match (let [stmt (assoc stmt :clauses
                                 (mapv (fn [c] (update c :body walk)) (:clauses stmt)))]
                 (if (:else stmt) (assoc stmt :else (walk (:else stmt))) stmt))
        stmt))))

(defn- resolve-anonymous-function-types-in-stmts
  "Walk STMTS in source order, threading VAR-TYPES (see
   `resolve-anonymous-function-types-in-stmt`) forward as each `:let` with a
   declared type is reached. Returns [patched-stmts final-var-types]."
  [stmts var-types]
  (reduce (fn [[patched var-types] stmt]
            (let [stmt (resolve-anonymous-function-types-in-stmt stmt var-types)]
              [(conj patched stmt) (record-let-type var-types stmt)]))
          [[] var-types]
          stmts))

(defn- initial-var-types
  "Seed a var-types map from PARAMS ({:name :type} entries, as on a function
   or method) so a parameter reassigned with a bare `fn(...)` can also have
   its type inferred from the parameter's own declared type."
  [params]
  (into {} (map (juxt :name :type)) (or params [])))

(defn- resolve-functions-anonymous-function-context-types
  [fns]
  (mapv (fn [f] (update f :body #(first (resolve-anonymous-function-types-in-stmts
                                         % (initial-var-types (:params f))))))
        fns))

(defn- resolve-class-anonymous-function-context-types
  [class-def]
  (let [patch-section
        (fn [section]
          (case (:type section)
            :feature-section
            (update section :members
                    (fn [members]
                      (mapv (fn [m]
                              (if (= :method (:type m))
                                (update m :body #(first (resolve-anonymous-function-types-in-stmts
                                                         % (initial-var-types (:params m)))))
                                m))
                            members)))
            :constructors
            (update section :constructors
                    (fn [ctors]
                      (mapv (fn [c] (update c :body #(first (resolve-anonymous-function-types-in-stmts
                                                             % (initial-var-types (:params c))))))
                            ctors)))
            section))]
    (update class-def :body #(mapv patch-section %))))

(defn- resolve-anonymous-function-context-types
  "Apply `resolve-anonymous-function-types-in-stmt` across the whole program:
   top-level statements, every free function's body, and every class's every
   method/constructor body."
  [program]
  (-> program
      (update :statements #(first (resolve-anonymous-function-types-in-stmts % {})))
      (update :functions resolve-functions-anonymous-function-context-types)
      (update :classes #(mapv resolve-class-anonymous-function-context-types %))))

;; --- Shared mutable captures ---
;;
;; A closure's captures are ordinary VALUE snapshots taken at construction
;; time (see lower-expr-anonymous-function/make-captured-function-object):
;; each `fn(...)` literal gets its own private copy of whatever an outer
;; variable currently holds. That is correct for a captured variable no
;; closure ever reassigns, and even for ONE closure that both reads and
;; writes its own capture (repeat calls to that SAME closure instance see
;; its own prior writes, since it's the same object's own field). It breaks
;; the moment TWO SEPARATE closures are meant to share one mutable variable
;; — `let total := 0 / let add := fn(x) do total := total + x end / let
;; peek := fn() do result := total end` — each closure snapshots its own
;; copy of `total` at its own construction, so `add`'s writes are invisible
;; to `peek`, and to the enclosing scope's own later reads of `total`.
;;
;; The fix below does not touch the capture-passing machinery at all —
;; captures already pass whatever VALUE an identifier currently holds, and
;; an object reference is already correctly shared by every closure (and
;; the enclosing scope) that holds it. Instead, a `:let`-declared local
;; that is (a) reassigned anywhere reachable from its scope (directly, or
;; inside a nested closure) and (b) referenced inside at least one nested
;; closure, is rewritten so the VALUE it holds is a tiny synthetic boxed
;; object (Closure_Mut_Box[T], one `value: T` field) instead of the bare
;; scalar/value — every bare read of that name becomes a `.value` field
;; read, every `:=` write becomes a `.value :=` field write, and the
;; closure's existing by-reference capture semantics do the rest.
(def ^:private closure-mut-box-class-name "Closure_Mut_Box")

(def ^:private closure-mut-box-class-def
  "The Closure_Mut_Box[T] class-def, parsed once from real Nex source (not
   hand-built AST) so its shape always matches whatever the parser/walker
   currently produce for an ordinary generic class."
  (delay
    (first (:classes (parser/ast
                      (str "class " closure-mut-box-class-name "[T]\n"
                           "create\n"
                           "  make(v: T) do value := v end\n"
                           "feature\n"
                           "  value: T\n"
                           "  set(v: T) do value := v end\n"
                           "end"))))))

(defn- box-target-names
  "Every name a bare `:=` reassigns anywhere reachable in STMTS (including
   inside a nested closure's own body) — a candidate for boxing must be
   reassigned somewhere, or there is nothing for sibling closures (or the
   enclosing scope) to ever see change."
  [stmts]
  (into #{}
        (comp (filter map?) (filter #(= :assign (:type %))) (keep :target))
        (tree-seq coll? seq stmts)))

(defn- names-touched-inside-closures
  "Every name read (`:identifier`) or written (`:assign` target) inside any
   `:anonymous-function` OR `:spawn` body reachable in STMTS — a boxing
   candidate must actually be visible to some closure, or boxing it only
   adds overhead to ordinary same-scope mutation (a loop counter, an
   accumulator no closure ever touches) that never needed sharing.

   `:spawn` is included alongside `:anonymous-function` for the same
   reason: nex.lower/rewrite-expression-for-closures' own `:spawn` case
   wraps a spawn body into a synthetic anonymous-function LATER, during
   the ordinary closure-capture rewrite — a plain `:spawn` node here, at
   this earlier detection pass, is not yet that shape, so without this it
   was invisible to boxing entirely: `let total := 0 / spawn do total :=
   total + 5 end / t.await / print(total)` silently kept reading the
   pre-spawn value even after `.await` — which blocks until the task
   finishes — guarantees the mutation already happened."
  [stmts]
  (into #{}
        (comp (filter map?)
              (filter #(contains? #{:anonymous-function :spawn} (:type %)))
              (mapcat (fn [fn-node]
                        (into []
                              (comp (filter map?)
                                    (mapcat (fn [n]
                                              (case (:type n)
                                                :identifier [(:name n)]
                                                :assign [(:target n)]
                                                nil))))
                              (tree-seq coll? seq (:body fn-node))))))
        (tree-seq coll? seq stmts)))

(defn- shadowed-anywhere-names
  "Every name declared more than once anywhere in STMTS — as a :let (at
   any depth: a top-level one, or one nested inside an if/loop/match/case/
   scoped-block/closure body) or as an :anonymous-function's own param —
   found via a flat count across the whole subtree. rewrite-boxed-
   references has no scope tracking of its own (a single shape-agnostic
   postwalk, deliberately, to stay simple): it cannot tell a nested
   re-declaration of a boxed name (a closure's own same-named parameter, or
   an unrelated inner `let total := ...` shadowing the outer boxed one)
   from a genuine reference to the boxed variable, and would rewrite the
   shadowing occurrence's every read/write into a `.value` field access
   too — a lowering-time crash the moment that occurrence is used as its
   own (unboxed) type. Excluding any such name from boxing entirely is the
   safe fallback: it leaves the pre-existing snapshot-per-closure behavior
   in place for that one name (no worse than before this fix), rather than
   emitting AST a shadowed occurrence cannot type-check against."
  [stmts]
  (->> (tree-seq coll? seq stmts)
       (filter map?)
       (keep (fn [n]
               (case (:type n)
                 :let (:name n)
                 :anonymous-function (seq (keep :name (:params n)))
                 nil)))
       (mapcat (fn [n] (if (coll? n) n [n])))
       frequencies
       (keep (fn [[name n]] (when (> n 1) name)))
       set))

(defn- direct-let-declarations
  "Every `:let` this pass treats as \"directly\" in STMTS: a plain top-level
   one, plus — the one deliberate, narrow exception to the \"not nested
   inside an if/loop/etc.\" rule box-candidate-lets otherwise holds to — a
   `from`-loop's own control variable, declared in a top-level `:loop`
   node's `:init` rather than as an ordinary statement (`from let i := 0
   until ... do ... end` parses `i`'s :let into the :loop node's :init, not
   as a sibling statement; see nex.walker). That variable is exactly as
   legitimate a boxing target as any other mutated-and-closed-over :let —
   `from let i := 0 until i = n do spawn do result := i end ... end` needs
   `i` boxed for the same reason a `let total := 0` does — but it lives one
   field deeper, so box-candidate-lets' own plain `filter` over STMTS never
   saw it without this. A loop nested inside another loop/if/etc. is still
   out of scope, same as before: only a :loop directly in STMTS is looked
   into."
  [stmts]
  (mapcat (fn [s]
            (cond
              (and (map? s) (= :let (:type s))) [s]
              (and (map? s) (= :loop (:type s))) (filter #(and (map? %) (= :let (:type %))) (:init s))
              :else nil))
          stmts))

(defn- box-candidate-lets
  "The {name -> let-stmt} map of every `:let` appearing directly (not
   nested inside an if/loop/etc. — see direct-let-declarations for the one
   exception) in STMTS whose name needs boxing per box-target-names/names-
   touched-inside-closures, excluding any name shadowed anywhere in STMTS
   (see shadowed-anywhere-names)."
  [stmts]
  (let [reassigned (box-target-names stmts)
        touched-in-closure (names-touched-inside-closures stmts)
        shadowed (shadowed-anywhere-names stmts)]
    (into {}
          (comp (filter #(and (contains? reassigned (:name %))
                              (contains? touched-in-closure (:name %))
                              (not (contains? shadowed (:name %)))))
                (map (juxt :name identity)))
          (direct-let-declarations stmts))))

(defn anonymous-function-signature-type
  "A Function(...) type read directly off an :anonymous-function EXPR's
   own :params/:return-type — nil for any other node shape. Mirrors
   nex.typechecker/anonymous-function-provisional-signature exactly, and
   exists for the identical reason: EXPR's signature is always fully
   determined by what's written on the literal itself, regardless of
   what its body references — unlike infer-prepass-type (via
   tc/infer-expression-type), which type-checks the WHOLE body in an
   isolated, standalone env to infer a closure's type, and so throws
   (silently, swallowed by its own try/catch, falling back to \"Any\")
   the moment that body references a sibling closure the isolated check
   knows nothing about — exactly the shape of a mutually- or forward-
   referencing closure. Callers needing an untyped closure-let's type use
   this FIRST, falling back to infer-prepass-type only when EXPR isn't a
   closure literal at all."
  [expr]
  (when (and (map? expr) (= :anonymous-function (:type expr)))
    {:base-type "Function"
     :param-types (mapv (fn [p] {:name (:name p) :type (or (:type p) "Any")}) (:params expr))
     :return-type (or (:return-type expr) "Any")}))

(defn- box-let-type
  "The Nex type to instantiate Closure_Mut_Box[T] at for a boxed :let —
   its own declared :var-type when present, otherwise its
   anonymous-function-signature-type (see there for why this can't go
   through the ordinary infer-prepass-type path other boxed :lets use),
   or inferred the ordinary way for any other kind of value. Falls back
   to \"Any\" only when inference itself cannot determine one; T is
   erased to Object on the JVM regardless, so \"Any\" here costs a
   convert at an unusual, untyped-let use site, never a lowering
   failure."
  [ctx local-types let-stmt]
  (let [value (:value let-stmt)]
    (or (:var-type let-stmt)
        (anonymous-function-signature-type value)
        (infer-prepass-type ctx local-types value)
        "Any")))

(defn- box-read
  "A bare read of a boxed name — `total` -> `total.value` — as the same
   shape an ordinary bare field access already lowers through (see
   rewrite-expression-for-closures' own `this`-field-read rewrite)."
  [name]
  {:type :call :target {:type :identifier :name name} :method "value"
   :args [] :has-parens false})

(defn- box-write
  "A `:=` write to a boxed name — `total := v` -> `total.set(v)` — a
   METHOD call, not a direct `.value := v` field write: Nex enforces that
   a field is writable only from within its own declaring class (see
   nex.lower/lower-member-assign-stmt's encapsulation check), and this
   write can happen from code that is not Closure_Mut_Box itself and is
   not always inside a closure body either — a forward-referenced
   closure-let's box (see box-forward-referenced-closures) is filled in
   by a plain top-level/function/method statement, nowhere near any
   closure's own runtime-object bypass for that same check. A public
   method has no such restriction, so routing every box write through one
   (set(v), declared alongside the field) sidesteps it uniformly, for a
   write from inside a closure body or from ordinary surrounding code
   alike."
  [name value]
  {:type :call :target {:type :identifier :name name} :method "set" :args [value] :has-parens true})

(defn- rewrite-boxed-references
  "Rewrite every bare read/write of a name in BOXED-NAMES throughout STMTS
   (including inside nested closures) into the field access box-read/
   box-write build, then wrap each boxing :let's own initializer in a
   Closure_Mut_Box construction. A single clojure.walk/postwalk handles
   every :identifier/:assign occurrence regardless of which statement or
   expression shape it sits inside — the same technique
   nex.walker/resolve-qualified-function-calls uses for its own
   whole-program, shape-agnostic rewrite."
  [boxed-names stmts]
  (walk/postwalk
   (fn [n]
     (cond
       (and (map? n) (= :identifier (:type n)) (contains? boxed-names (:name n)))
       (box-read (:name n))

       (and (map? n) (= :assign (:type n)) (contains? boxed-names (:target n)))
       (box-write (:target n) (:value n))

       :else n))
   stmts))

;; --- Mutually recursive closures ---
;;
;; `let is_even := fn(n) do ... is_odd(n - 1) ... end / let is_odd :=
;; fn(n) do ... is_even(n - 1) ... end` — is_even's own construction
;; happens before is_odd's `:let` is even reached, so an ordinary capture
;; of "is_odd" (a snapshot of whatever that name currently holds) would
;; capture nothing at all. Unlike the shared-mutable-capture case above,
;; there is no existing value to box in place: the box itself must be
;; created (holding a placeholder nil) BEFORE either closure is built, so
;; is_even can capture a reference to the box rather than to is_odd
;; itself, and is_odd's own construction later fills that same box in.
;; Reuses the identical Closure_Mut_Box[T]/box-read/box-write machinery
;; the mutation case already established — the "tie the knot" trick is
;; entirely in how the box gets hoisted and split from its original `:let`
;; below, not in any new box representation.

(defn- closure-let-names
  "The {name -> let-stmt} map of every DIRECT closure-literal :let in
   STMTS — the same direct-only scope box-candidate-lets uses."
  [stmts]
  (into {}
        (comp (filter #(and (map? %) (= :let (:type %))))
              (filter #(and (map? (:value %)) (= :anonymous-function (:type (:value %)))))
              (map (juxt :name identity)))
        stmts))

(defn- bare-references-in
  "Every name read (:identifier) or bare-called (:call with a nil target)
   inside NODE — not descending into a nested :anonymous-function's own
   :body, matching names-touched-inside-closures' own scope-stopping walk,
   but applied to a single closure's body instead of a whole statement
   list."
  [node]
  (into #{}
        (comp (filter map?)
              (mapcat (fn [n]
                        (cond
                          (= :identifier (:type n)) [(:name n)]
                          (and (= :call (:type n)) (nil? (:target n)) (:method n)) [(:method n)]
                          :else nil))))
        (tree-seq (fn [x] (and (coll? x) (not (and (map? x) (= :anonymous-function (:type x))))))
                  seq
                  node)))

(defn- forward-boxed-closure-names
  "Names of STMTS' own direct closure-lets that are referenced (a bare
   read or bare call) inside a SIBLING closure-let declared EARLIER in
   STMTS — the set that needs Closure_Mut_Box treatment so the earlier
   closure can hold an indirect reference to something that does not
   exist yet at its own construction time.

   Excludes any name shadowed elsewhere in STMTS (see shadowed-anywhere-
   names — the same guard box-candidate-lets already applies to the
   mutation-boxing case) for the identical reason: rewrite-forward-
   references, like rewrite-boxed-references, is a single shape-agnostic
   postwalk with no scope tracking of its own, so it cannot tell a
   shadowing occurrence (a different closure's own same-named parameter,
   or an unrelated `let` reusing the name) from a genuine forward
   reference. Without this, a name doing double duty as both a mutual-
   recursion participant and a plain unrelated parameter elsewhere in the
   same block hit a lowering-time crash the moment that occurrence was
   rewritten into a box read it cannot type-check against — the same
   crash class shadowed-anywhere-names was introduced to close off for
   mutation boxing. Excluding the name here means self- and mutual
   recursion between closures simply do not apply for that one name, with
   the ordinary (already-correct) ambient behavior taking over — never a
   crash."
  [stmts]
  (let [lets (closure-let-names stmts)
        shadowed (shadowed-anywhere-names stmts)
        name->index (into {}
                          (keep-indexed (fn [i s]
                                          (when (and (contains? lets (:name s))
                                                     (not (contains? shadowed (:name s))))
                                            [(:name s) i])))
                          stmts)]
    (into #{}
          (mapcat (fn [[name let-stmt]]
                    (let [i (get name->index name)
                          refs (bare-references-in (:body (:value let-stmt)))]
                      (keep (fn [ref-name]
                              (when (and (contains? name->index ref-name)
                                         (> (get name->index ref-name) i))
                                ref-name))
                            refs))))
          (select-keys lets (keys name->index)))))

(defn- rewrite-forward-references
  "Rewrite every bare read/call/write of a name in BOXED-NAMES throughout
   STMTS into the box-read/box-write/invoke-through-the-box shape — the
   forward-reference analog of rewrite-boxed-references, with one more
   case: a bare CALL to a boxed name (`is_odd(n - 1)`) becomes an
   invocation of whatever the box currently holds (`is_odd.value(n - 1)`,
   built as target=box-read, method=nil, has-parens=true — the same shape
   `(expr)(...)`/a chained call already parses to), not a plain field
   read: the box holds a Function value, and this closure means to CALL
   it, not merely observe it."
  [boxed-names stmts]
  (walk/postwalk
   (fn [n]
     (cond
       (and (map? n) (= :call (:type n)) (nil? (:target n)) (contains? boxed-names (:method n)))
       {:type :call :target (box-read (:method n)) :method nil :args (:args n) :has-parens true}

       (and (map? n) (= :assign (:type n)) (contains? boxed-names (:target n)))
       (box-write (:target n) (:value n))

       (and (map? n) (= :identifier (:type n)) (contains? boxed-names (:name n)))
       (box-read (:name n))

       :else n))
   stmts))

(defn- box-forward-referenced-closures
  "Entry point: given CTX/LOCAL-TYPES (as box-mutable-closure-captures)
   and STMTS, rewrite every forward-referenced closure-let (see
   forward-boxed-closure-names) into a Closure_Mut_Box hoisted to the top
   of STMTS — filled in later, at the boxed closure's own original
   position, by a plain `:=` write instead of a `:let` — so an earlier
   sibling closure can capture the box (and thus, indirectly, whatever
   gets written into it) before the real value exists. Runs BEFORE
   box-mutable-closure-captures: once this rewrite replaces a forward-
   referenced name's :identifier/:assign occurrences with :call/:member-
   assign shapes, that pass's own :identifier/:assign-based detection
   naturally finds nothing left to re-box for the same name."
  [ctx local-types stmts]
  (let [boxed (forward-boxed-closure-names stmts)]
    (if (empty? boxed)
      stmts
      (let [lets (closure-let-names stmts)
            box-types (into {}
                            (map (fn [name] [name (box-let-type ctx local-types (get lets name))]))
                            boxed)
            split-stmts (mapv (fn [s]
                                (if (and (map? s) (= :let (:type s)) (contains? boxed (:name s)))
                                  {:type :assign :target (:name s) :value (:value s)}
                                  s))
                              stmts)
            hoisted (mapv (fn [name]
                            {:type :let
                             :name name
                             :var-type {:base-type closure-mut-box-class-name :type-args [(get box-types name)]}
                             :value {:type :create
                                     :class-name closure-mut-box-class-name
                                     :generic-args [(get box-types name)]
                                     :constructor "make"
                                     :args [{:type :nil}]}})
                          boxed)]
        (rewrite-forward-references boxed (vec (concat hoisted split-stmts)))))))

(defn- box-mutable-closure-captures
  "Entry point: given CTX (the same shape rewrite-callable-for-closures
   builds) and LOCAL-TYPES (params already in scope), rewrite STMTS so
   every :let a closure needs to share mutably with a sibling closure — or
   with the enclosing scope's own later reads — is backed by a
   Closure_Mut_Box instead of a bare value. Runs once per scope (top-level
   statements, a function body, a method/constructor body) BEFORE the
   ordinary closure-capture rewrite, which needs no changes of its own:
   capturing a boxed name already captures the shared box object by
   reference, exactly like capturing any other Nex object."
  [ctx local-types stmts]
  (let [candidates (box-candidate-lets stmts)]
    (if (empty? candidates)
      stmts
      (let [boxed-names (set (keys candidates))
            box-wrap (fn [s t]
                       (assoc s
                              :var-type {:base-type closure-mut-box-class-name :type-args [t]}
                              :value {:type :create
                                      :class-name closure-mut-box-class-name
                                      :generic-args [t]
                                      :constructor "make"
                                      :args [(:value s)]}))
            ;; One :let (top-level, or nested one level into a top-level
            ;; :loop's own :init — see direct-let-declarations) threaded
            ;; through the same lt/acc update either kind gets when it is
            ;; a plain statement: box-typed and recorded when it is a
            ;; boxing candidate, otherwise just folded into lt so a LATER
            ;; boxed let's initializer can still resolve its type.
            thread-let (fn [[lt acc] s]
                         (if (contains? candidates (:name s))
                           (let [t (box-let-type ctx lt s)]
                             [(assoc lt (:name s) t) (assoc acc (:name s) t)])
                           [(assoc lt (:name s) (or (:var-type s) (infer-prepass-type ctx lt (:value s)))) acc]))
            ;; Types are resolved against the ORIGINAL (pre-rewrite) lets,
            ;; threading local-types forward exactly like the ordinary
            ;; closure-rewrite :let case does, so a later boxed let's own
            ;; initializer can still refer to an earlier one's declared type.
            box-types (second
                       (reduce (fn [[lt acc] s]
                                 (cond
                                   (and (map? s) (= :let (:type s)) (:name s))
                                   (thread-let [lt acc] s)

                                   (and (map? s) (= :loop (:type s)))
                                   (reduce (fn [state init-let]
                                             (if (and (map? init-let) (= :let (:type init-let)) (:name init-let))
                                               (thread-let state init-let)
                                               state))
                                           [lt acc]
                                           (:init s))

                                   :else [lt acc]))
                               [local-types {}]
                               stmts))
            rewritten (rewrite-boxed-references boxed-names stmts)]
        (mapv (fn [s]
                (cond
                  (and (map? s) (= :let (:type s)) (contains? boxed-names (:name s)))
                  (box-wrap s (get box-types (:name s)))

                  (and (map? s) (= :loop (:type s)))
                  (assoc s :init
                         (mapv (fn [init-let]
                                 (if (and (map? init-let) (= :let (:type init-let))
                                          (contains? boxed-names (:name init-let)))
                                   (box-wrap init-let (get box-types (:name init-let)))
                                   init-let))
                               (:init s)))

                  :else s))
              rewritten)))))

(defn prepare-program-for-closures
  [program opts]
  (let [program (binding [*type-aliases* (merge *type-aliases*
                                                (into {} (map (juxt :name :type-expr)
                                                              (:type-aliases program))))]
                  (resolve-anonymous-function-context-types program))
        visible-functions (vec (concat (:functions program) (:functions opts)))
        visible-classes (merge-visible-classes (builtin-class-defs)
                                               (:classes program)
                                               (:classes opts)
                                               (keep :class-def visible-functions))
        ctx {:classes visible-classes
             :functions visible-functions
             :imports (:imports program)
             :var-types (:var-types opts)}
        any-boxed? (atom false)
        box (fn [local-types stmts]
              (let [forward-boxed (forward-boxed-closure-names stmts)
                    stmts (if (seq forward-boxed)
                            (do (reset! any-boxed? true)
                                (box-forward-referenced-closures ctx local-types stmts))
                            stmts)
                    candidates (box-candidate-lets stmts)]
                (when (seq candidates) (reset! any-boxed? true))
                (box-mutable-closure-captures ctx local-types stmts)))
        boxed-functions (mapv (fn [f]
                                (update f :body #(box (initial-var-types (:params f)) %)))
                              (:functions program))
        rewritten-functions (mapv #(rewrite-callable-for-closures ctx % (:var-types opts))
                                  boxed-functions)
        boxed-statements (box (:var-types opts) (:statements program))
        [rewritten-statements _ _]
        (rewrite-statements-for-closures
         (assoc ctx :functions (vec (concat rewritten-functions (:functions opts))))
         (:var-types opts)
         boxed-statements)
        boxed-classes (mapv (fn [class-def]
                              (update class-def :body
                                      (fn [sections]
                                        (mapv (fn [section]
                                                (case (:type section)
                                                  :feature-section
                                                  (update section :members
                                                          (fn [members]
                                                            (mapv (fn [member]
                                                                    (if (= :method (:type member))
                                                                      (update member :body #(box (initial-var-types (:params member)) %))
                                                                      member))
                                                                  members)))
                                                  :constructors
                                                  (update section :constructors
                                                          (fn [ctors]
                                                            (mapv #(update % :body (fn [b] (box (initial-var-types (:params %)) b))) ctors)))
                                                  section))
                                              sections))))
                            (:classes program))
        rewritten-classes (mapv #(rewrite-class-for-closures ctx %) boxed-classes)]
    (cond-> program
      true (assoc :functions rewritten-functions
                  :statements rewritten-statements
                  :classes rewritten-classes)
      @any-boxed? (update :classes #(conj % @closure-mut-box-class-def)))))

(defn collect-anonymous-class-defs
  [node]
  (let [seen-order (atom [])
        found (atom {})]
    (letfn [(walk [x]
              (cond
                (map? x)
                (do
                  (when (= :anonymous-function (:type x))
                    (let [class-def (:class-def x)
                          class-name (:name class-def)]
                      (when-not (contains? @found class-name)
                        (swap! seen-order conj class-name))
                      (swap! found
                             (fn [m]
                               (let [existing (get m class-name)]
                                 (assoc m
                                        class-name
                                        (if (and existing
                                                 (not (:closure-runtime-object? class-def))
                                                 (:closure-runtime-object? existing))
                                          existing
                                          class-def)))))))
                  (doseq [v (vals x)]
                    (walk v)))

                (sequential? x)
                (doseq [v x]
                  (walk v))

                :else nil))]
      (walk node)
      (mapv @found @seen-order))))

(defn- lower-instance-this-carrier-field-get
  "`this.field` (bare, no parens) where FIELD is one of the current class's own
   fields reached through its composition carrier path."
  [env method]
  (let [{:keys [owner field carrier-path nex-type jvm-type]} (get (:fields env) method)]
    (ir/field-get-node (:internal-name (class-jvm-meta env owner))
                       field
                       (carrier-path-target-ir env carrier-path
                                               (ir/this-node (:this-type env)
                                                             (exact-class-jvm-type env (:this-type env))))
                       nex-type
                       jvm-type)))

(defn- lower-instance-user-field-get
  [env target-expr method base-type target-ir field-def type-map]
  (let [nex-type (tc/resolve-generic-type (:field-type field-def) type-map)
        jvm-type (resolve-jvm-type env nex-type)]
    (if (= (:type target-expr) :this)
      (ir/field-get-node (:internal-name (class-jvm-meta env base-type))
                         method
                         target-ir
                         nex-type
                         jvm-type)
      (ir/call-runtime-node (str "user-field-get:" method)
                            [target-ir]
                            nex-type
                            jvm-type))))

(defn- lower-instance-user-method-call
  [env target-expr method args target-ir method-def type-map]
  (let [nex-type (tc/resolve-generic-type (function-return-type method-def) type-map)
        jvm-type (resolve-jvm-type env nex-type)]
    (if (= (:type target-expr) :this)
      ;; Dispatch self-calls through __outer__ for proper dynamic dispatch.
      ;; When this object is a composition parent, __outer__ points to the
      ;; child that contains it, so overridden methods are called correctly.
      (let [outer-ir (ir/field-get-node (:internal-name (class-jvm-meta env (:this-type env)))
                                        "__outer__"
                                        (ir/this-node (:this-type env)
                                                      (exact-class-jvm-type env (:this-type env)))
                                        "Any"
                                        (ir/object-jvm-type "java/lang/Object"))]
        (ir/call-runtime-node (str "user-method:" method)
                              (into [outer-ir] (mapv #(lower-expression env %) args))
                              nex-type
                              jvm-type))
      (ir/call-runtime-node (str "user-method:" method)
                            (into [target-ir] (mapv #(lower-expression env %) args))
                            nex-type
                            jvm-type))))

(defn- lower-instance-inherited-parent-call
  "`this.m(...)` where M is not declared/inherited on the class itself but is a
   method of a directly-composed parent — dispatched virtually through that
   parent's carrier field."
  [env method args]
  (let [entry (get (direct-parent-method-map env (current-class-def env))
                   [method (count args)])
        {:keys [owner-internal-name method-def carrier-owner carrier-field carrier-jvm-type]} entry
        nex-type (function-return-type method-def)
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/call-virtual-node owner-internal-name
                          (lowered-instance-method-name method-def)
                          (desc/repl-instance-method-descriptor)
                          (ir/field-get-node (:internal-name (class-jvm-meta env carrier-owner))
                                             carrier-field
                                             (ir/this-node (:this-type env)
                                                           (exact-class-jvm-type env (:this-type env)))
                                             (:source-class entry)
                                             carrier-jvm-type)
                          (mapv #(lower-expression env %) args)
                          nex-type
                          jvm-type)))

(defn- lower-instance-dispatch
  [env target-expr method args has-parens]
  (let [target-type (resolve-type-alias (infer-type env target-expr))
        base-type (base-type-name target-type)
        target-ir (lower-expression env target-expr)
        class-def (get (visible-class-map env) base-type)
        field-def (when (and class-def (false? has-parens))
                    (if (= (:type target-expr) :this)
                      (class-field-def class-def method)
                      (accessible-field-def env class-def method)))
        method-def (when class-def
                     (if (= (:type target-expr) :this)
                       (or (class-method-def class-def method (count args))
                           (inherited-method-def env class-def method (count args)))
                       (accessible-method-def env class-def method (count args))))
        ;; Built once the member is known: an inherited member's types are stated
        ;; in its declaring class's generic params, not the receiver's.
        type-map (generic-type-map env target-type
                                   (:declaring-class (or method-def field-def)))]
    (cond
      (and (= (:type target-expr) :this)
           (get (:fields env) method)
           (false? has-parens))
      (lower-instance-this-carrier-field-get env method)

      field-def
      (lower-instance-user-field-get env target-expr method base-type target-ir field-def type-map)

      method-def
      (lower-instance-user-method-call env target-expr method args target-ir method-def type-map)

      (and (= (:type target-expr) :this)
           (get (direct-parent-method-map env (current-class-def env))
                [method (count args)]))
      (lower-instance-inherited-parent-call env method args)

      ;; A universal method the class does not declare. The typechecker admits
      ;; the Any protocol on *every* receiver (the "Any" case of its
      ;; `builtin-method-signature`), so `p.total.to_string` typechecks against
      ;; a class that never defines `to_string` and must therefore also lower.
      ;;
      ;; Also covers a receiver whose static type is an *unconstrained* generic
      ;; parameter (e.g. `first: F` in `Pair[F, S]` calling `first.to_string`):
      ;; the typechecker admits the same Any protocol there (there is no class
      ;; to look up), so `class-def` is nil but the receiver still needs the
      ;; same runtime dispatch — without this branch such a call fell through
      ;; every case here and threw "Unsupported target call expression for
      ;; lowering" at compile time.
      ;;
      ;; Only the members the compiled object model actually implements are
      ;; routed here. `clone` and `hash` are deliberately excluded: their :Any
      ;; defaults (`bi/nex-clone-value`, `bi/nex-structural-hash`) only
      ;; understand the interpreter's object maps and would quietly return the
      ;; *same* object from `clone` rather than a copy. Falling through to the
      ;; error below keeps them an honest "not supported yet" — which is what
      ;; they were before this branch existed — instead of a silent wrong
      ;; answer. Same for the typechecker's `cursor`/`start`/`item`/`next`/
      ;; `at_end`, which have no Any default at all.
      (and (or class-def (contains? (:generic-param-names env) base-type))
           (contains? #{"to_string" "equals"} method))
      (let [nex-type (or (bi/builtin-type-method-return-type :Any method) "Any")
            jvm-type (resolve-jvm-type env nex-type)]
        (ir/call-runtime-node (str "any:" method)
                              (into [target-ir] (mapv #(lower-expression env %) args))
                              nex-type
                              jvm-type))

      ;; A method not declared/inherited anywhere in the Nex chain, on a
      ;; receiver whose class extends a Java class (Phase 2, docs/proposals/
      ;; java-interop.md) — e.g. `t.start()` where My_Thread inherits Thread.
      ;; The receiver is already a real instance of that Java type at the
      ;; bytecode level (real `extends`), so the same reflective dispatch the
      ;; "bare imported Java object" branch above uses (java-call-method)
      ;; reaches it correctly, without hand-emitting a real-descriptor
      ;; INVOKEVIRTUAL.
      (and class-def has-parens (java-superclass-parent env class-def))
      (let [nex-type "Any"
            jvm-type (resolve-jvm-type env nex-type)]
        (ir/call-runtime-node "java-call-method"
                              (into [(ir/const-node method "String" (ir/object-jvm-type "java/lang/String"))
                                     target-ir]
                                    (mapv #(lower-expression env %) args))
                              nex-type
                              jvm-type))

      class-def
      (throw (unsupported "Unsupported user-defined target access during lowering"
                          ;; `:expr` carries the receiver only so the diagnostic
                          ;; can find a line number (see `debug-location`); the
                          ;; call itself is not threaded down here, and the two
                          ;; are on the same line in any case.
                          {:expr target-expr
                           :target-type target-type
                           :method method
                           :has-parens has-parens}))

      :else
      nil)))

(declare lower-function)

(defn- lower-expr-array-literal
  [env expr]
  (let [nex-type (infer-type env expr)
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/array-literal-node (mapv #(lower-expression env %) (:elements expr))
                           nex-type
                           jvm-type)))

(defn- lower-expr-map-literal
  [env expr]
  (let [nex-type (infer-type env expr)
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/map-literal-node (mapv (fn [{:keys [key value]}]
                                 {:key (lower-expression env key)
                                  :value (lower-expression env value)})
                               (:entries expr))
                         nex-type
                         jvm-type)))

(defn- lower-expr-set-literal
  [env expr]
  (let [nex-type (infer-type env expr)
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/set-literal-node (mapv #(lower-expression env %) (:elements expr))
                         nex-type
                         jvm-type)))

(defn- lower-expr-identifier
  [env expr]
  (if-let [{:keys [slot nex-type jvm-type]} (get (:locals env) (:name expr))]
    (ir/local-node (:name expr) slot nex-type jvm-type)
    (if-let [{:keys [owner field carrier-path nex-type jvm-type]}
             (get (:fields env) (:name expr))]
      (ir/field-get-node (:internal-name (class-jvm-meta env owner))
                         field
                         (carrier-path-target-ir env carrier-path
                                                 (ir/this-node (:this-type env)
                                                               (resolve-jvm-type env (:this-type env))))
                         nex-type
                         jvm-type)
      (if-let [constant (and (:current-class env)
                             (lookup-class-constant env (:current-class env) (:name expr)))]
        (let [owner (:declaring-class constant)
              nex-type (constant-nex-type env constant)
              jvm-type (resolve-jvm-type env nex-type)]
          (ir/static-field-get-node (:internal-name (class-jvm-meta env owner))
                                    (:name constant)
                                    nex-type
                                    jvm-type))
        (if-let [method-def (some-> (current-class-def env)
                                    ((fn [class-def]
                                       (or (class-method-def class-def (:name expr) 0)
                                           (inherited-method-def env class-def (:name expr) 0)))))]
          (lower-expression env {:type :call
                                 :target {:type :this}
                                 :method (:name expr)
                                 :args []
                                 :has-parens true})
          ;; A bare reference to a free function's own name (no call parens) —
          ;; passing it as a `Function(...)`-typed value, e.g.
          ;; `filter_items(is_rare_or_legendary)`. The `<name>_Function` class
          ;; every `function` decl is hoisted into (`nex.walker/build-function-
          ;; node`; matched structurally against a `Function(...)` target by
          ;; `nex.typechecker/types-compatible?`) exists only for the
          ;; typechecker's bookkeeping — the standalone/REPL compilers
          ;; (`nex.compiler.jvm.file`/`repl`) deliberately never emit it as a
          ;; real class, since the ordinary "call it directly" path needs no
          ;; wrapper object. So this can't `new` an instance of it (no such
          ;; class exists to load). Without this branch at all the name fell
          ;; through to the global/top-get case below, which finds no such
          ;; global and silently lowers to a null read — type-checks fine,
          ;; then crashes at the first call with "Cannot invoke Void as a
          ;; function". Fixed instead by reusing the runtime's existing
          ;; function-by-name registry (`function-value-for-name`, backed by
          ;; the same registration every top-level function already gets for
          ;; deoptimized-closure callbacks) rather than inventing a new
          ;; compiled-class path.
          (if (some #(= (:name %) (:name expr)) (:functions env))
            (ir/call-runtime-node "function-value-for-name"
                                  [(ir/const-node (:name expr)
                                                  "String"
                                                  (ir/object-jvm-type "java/lang/String"))]
                                  "Function"
                                  (ir/object-jvm-type "java/lang/Object"))
            (let [global? (contains? (:globals env) (:name expr))
                  nex-type (or (get (:var-types env) (:name expr))
                               (get (:globals env) (:name expr))
                               (infer-type env expr))
                  jvm-type (resolve-jvm-type env nex-type)]
              ;; A readable top-level global (§7) lowers to a `top-get` against
              ;; the live session state even inside a method/function body.
              (if (or (:top-level? env) global?)
                (ir/top-get-node (:name expr) nex-type jvm-type)
                (throw (ex-info "Unknown local in non-top-level lowering"
                                {:name (:name expr)}))))))))))

(defn- lower-expr-this
  [env expr]
  (if (:this-type env)
    (ir/this-node (:this-type env)
                  (exact-class-jvm-type env (:this-type env)))
    (throw (ex-info "this is only valid in instance-method lowering"
                    {:expr expr}))))

(defn- lower-expr-binary
  [env expr]
  ;; An arithmetic operator whose left operand is a class that aliased it is
  ;; sugar for the call: lower the invocation it stands for, contracts and all.
  ;; The check runs only for :binary nodes and short-circuits on the operator
  ;; set, so built-in Integer/Real arithmetic is unaffected.
  (if-let [aliased (operator-alias-feature env (:operator expr) (:left expr))]
    (lower-expression env {:type :call
                           :target (:left expr)
                           :method aliased
                           :args [(:right expr)]})
    (let [op (:operator expr)
          [left-ir right-ir]
          (case op
            "and"
            (let [[left-env left-ir] (lower-boolean-condition env (:left expr))
                  right-input-env (refine-condition-branch-env left-env (:left expr) :then)
                  [_right-env right-ir] (lower-boolean-condition right-input-env (:right expr))]
              [left-ir right-ir])

            "or"
            (let [[_left-env left-ir] (lower-boolean-condition env (:left expr))
                  [_right-env right-ir] (lower-boolean-condition env (:right expr))]
              [left-ir right-ir])

            [(lower-expression env (:left expr))
             (lower-expression env (:right expr))])
          inferred-type (infer-type env expr)
          nex-type (if (= "Any" inferred-type)
                     (cond
                       (#{"+" "-" "*" "/" "%"} (:operator expr))
                       (:nex-type left-ir)

                       (#{"and" "or" "=" "/=" "==" "!=" "<" "<=" ">" ">="} (:operator expr))
                       "Boolean"

                       :else inferred-type)
                     inferred-type)
          jvm-type (resolve-jvm-type env nex-type)]
      (cond
        (and (= "+" op) (= "String" nex-type))
        (ir/call-runtime-node "op:string-concat" [left-ir right-ir] nex-type jvm-type)

        (= "^" op)
        (ir/call-runtime-node (case jvm-type
                                :int "op:pow-int"
                                :long "op:pow-long"
                                :double "op:pow-double"
                                (throw (unsupported "Unsupported power lowering type"
                                                    {:expr expr :jvm-type jvm-type})))
                              [left-ir right-ir]
                              nex-type
                              jvm-type)

        ;; Integer / and % go through checked runtime helpers (like op:pow-*):
        ;; raw LDIV/LREM would leak the host's "/ by zero" message and silently
        ;; wrap MIN_LONG / -1 instead of raising like the interpreter.
        (and (#{"/" "%"} op) (#{:int :long} jvm-type))
        (ir/call-runtime-node (case [op jvm-type]
                                ["/" :int] "op:div-int"
                                ["/" :long] "op:div-long"
                                ["%" :int] "op:mod-int"
                                ["%" :long] "op:mod-long")
                              [left-ir right-ir]
                              nex-type
                              jvm-type)

        (#{"+" "-" "*" "/" "%" "and" "or"} op)
        (ir/binary-node (get {"+" :add
                              "-" :sub
                              "*" :mul
                              "/" :div
                              "%" :mod
                              "and" :and
                              "or" :or}
                             op)
                        left-ir right-ir nex-type jvm-type)

        :else
        (ir/compare-node (get {">" :gt
                               ">=" :gte
                               "<" :lt
                               "<=" :lte
                               "=" :eq
                               "/=" :neq
                               "==" :ident-eq
                               "!=" :ident-neq}
                              op)
                         left-ir right-ir nex-type jvm-type)))))

(defn- lower-expr-unary
  [env expr]
  (let [operand-ir (lower-expression env (:expr expr))
        nex-type (infer-type env expr)
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/unary-node (get {"-" :neg
                         "not" :not}
                        (:operator expr))
                   operand-ir
                   nex-type
                   jvm-type)))

(defn- lower-expr-if
  [env expr]
  (let [elseif (:elseif expr)
        then-branch (:then expr)
        else-branch (:else expr)]
    (let [[cond-env test-ir] (lower-boolean-condition env (:condition expr))
          then-env (refine-condition-branch-env cond-env (:condition expr) :then)
          else-env (refine-condition-branch-env env (:condition expr) :else)
          then-expr (if-branch-expression then-env then-branch)
          else-expr (elseif->else-expr else-env elseif else-branch)]
      (when (or (nil? then-expr)
                (nil? else-expr))
        (throw (unsupported "Only expression-shaped or result-assignment if branches are supported in lowering"
                            {:expr expr})))
      (let [then-ir (lower-expression then-env then-expr)
            else-ir (lower-expression else-env else-expr)
            nex-type (infer-type env expr)
            jvm-type (resolve-jvm-type env nex-type)]
        (ir/if-node test-ir [then-ir] [else-ir] nex-type jvm-type)))))

(defn- lower-expr-when
  [env expr]
  (let [[cond-env test-ir] (lower-boolean-condition env (:condition expr))
        then-ir (lower-expression (refine-condition-branch-env cond-env (:condition expr) :then) (:consequent expr))
        else-ir (lower-expression (refine-condition-branch-env env (:condition expr) :else) (:alternative expr))
        nex-type (infer-type env expr)
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/if-node test-ir [then-ir] [else-ir] nex-type jvm-type)))

(defn- lower-expr-old
  [env expr]
  (lower-expression (old-env env) (:expr expr)))

(defn- lower-expr-convert
  [env expr]
  (second (lower-convert-expression env expr)))

(defn- lower-expr-attached-test
  [env expr]
  (second (lower-attached-test-expression env expr)))

(defn- lower-expr-anonymous-function
  [env expr]
  (let [class-name (:class-name expr)
        compiled (get (:compiled-classes env) class-name)
        nex-type (infer-type env expr)
        captures (:captures expr)]
    (if (seq captures)
      (ir/call-runtime-node "make-captured-function-object"
                            (into [(ir/const-node class-name
                                                  "String"
                                                  (ir/object-jvm-type "java/lang/String"))]
                                  (mapcat (fn [{:keys [name]}]
                                            [(ir/const-node name
                                                            "String"
                                                            (ir/object-jvm-type "java/lang/String"))
                                             ;; The closure-this capture has no
                                             ;; identifier of its own at the
                                             ;; instantiation site — it names
                                             ;; the enclosing method's `this`.
                                             (lower-expression env (if (= name closure-this-capture-name)
                                                                     {:type :this}
                                                                     {:type :identifier
                                                                      :name name}))])
                                          captures))
                            nex-type
                            (ir/object-jvm-type "java/lang/Object"))
      (do
        (when-not compiled
          (throw (ex-info "Anonymous function class has not been compiled during lowering"
                          {:expr expr
                           :class-name class-name})))
        (ir/new-node (:internal-name compiled)
                     class-name
                     nex-type
                     (exact-class-jvm-type env class-name))))))

(defn- lower-expr-spawn
  [env expr]
  (let [fn-expr (or (:fn-expr expr)
                    (make-synthetic-anonymous-function-expr [] "Any" (:body expr)))
        fn-ir (lower-expression env fn-expr)
        nex-type (infer-type env expr)]
    (ir/call-runtime-node "spawn-function-object"
                          [fn-ir]
                          nex-type
                          (resolve-jvm-type env nex-type))))

(def ^:private lower-expression-dispatch
  "AST node `:type` -> `(fn [env expr] ...)`: the primary dispatch table for
   `lower-expression`. A node type with no entry here is unsupported and
   raises. `lower-create-expr`/`lower-call-expr` are only forward-declared
   this early in the file (their own defns come later) — a bare reference
   here would capture the declare's Unbound placeholder instead of the real
   function, since a map literal's values are dereferenced immediately (the
   same issue and fix as `infer-type-dispatch`'s `:call` entry)."
  {:integer            (fn [_env expr] (ir/const-node (:value expr) "Integer" (desc/nex-type->jvm-type "Integer")))
   :real               (fn [_env expr] (ir/const-node (:value expr) "Real" :double))
   :string             (fn [_env expr] (ir/const-node (:value expr) "String" (ir/object-jvm-type "java/lang/String")))
   :char               (fn [_env expr] (ir/const-node (:value expr) "Char" :char))
   :boolean            (fn [_env expr] (ir/const-node (:value expr) "Boolean" :boolean))
   :nil                (fn [_env _expr] (ir/const-node nil "Nil" (ir/object-jvm-type "java/lang/Object")))
   :array-literal      lower-expr-array-literal
   :map-literal        lower-expr-map-literal
   :set-literal        lower-expr-set-literal
   :identifier         lower-expr-identifier
   :this               lower-expr-this
   :binary             lower-expr-binary
   :unary              lower-expr-unary
   :if                 lower-expr-if
   :when               lower-expr-when
   :old                lower-expr-old
   :convert            lower-expr-convert
   :attached-test      lower-expr-attached-test
   :create             (fn [env expr] (lower-create-expr env expr))
   :anonymous-function lower-expr-anonymous-function
   :spawn              lower-expr-spawn
   :call               (fn [env expr] (lower-call-expr env expr))})

(defn lower-expression
  "The primary entry point for lowering an AST expression node to IR — every
   recursive descent into a sub-expression goes through here, so tagging the
   result with EXPR's source position here (rather than at each of the ~20
   `lower-expr-*`/`lower-*-call` producers individually) gives every
   expression-level IR node a `:dbg/line`/`:dbg/col` for free, the same way
   `lower-statement` already does for statement-level nodes via
   `with-stmt-debug`. Without this, an emit-time failure deep in a sub-
   expression (e.g. `binary-opcode`'s \"Unsupported binary opcode emission\")
   had no source location to report — see `format-exception-diagnostics` in
   `nex.compiler.jvm.file`, which already knew how to turn a `:dbg/line`-
   bearing node into \"At line N, column C\" but had nothing to find it on."
  [env expr]
  (if-let [handler (get lower-expression-dispatch (:type expr))]
    (ir/with-debug (handler env expr) expr)
    (throw (unsupported "Unsupported expression node for lowering"
                        {:expr expr :node-type (:type expr)}))))

(defn- lower-create-console
  [env expr]
  (let [nex-type (infer-type env expr)]
    (when (or (:constructor expr) (seq (:args expr)))
      (throw (ex-info "create Console takes no constructor or arguments in compiled lowering"
                      {:expr expr})))
    (ir/call-runtime-node "create-console"
                          []
                          nex-type
                          (resolve-jvm-type env nex-type))))

(defn- lower-create-process
  [env expr]
  (let [nex-type (infer-type env expr)
        jvm-type (resolve-jvm-type env nex-type)]
    (case (:constructor expr)
      (nil "self")
      (do
        (when (seq (:args expr))
          (throw (ex-info "create Process takes no arguments in compiled lowering"
                          {:expr expr})))
        (ir/call-runtime-node "create-process" [] nex-type jvm-type))

      "command"
      (do
        (when-not (<= 1 (count (:args expr)) 2)
          (throw (ex-info "Process.command expects 1 or 2 arguments in compiled lowering"
                          {:expr expr})))
        (ir/call-runtime-node "create-process-command"
                              (mapv #(lower-expression env %) (:args expr))
                              nex-type
                              jvm-type))

      (throw (unsupported "Unsupported Process constructor in compiled lowering"
                          {:expr expr
                           :constructor (:constructor expr)})))))

(defn- lower-create-channel
  [env expr]
  (let [nex-type (infer-type env expr)]
    (case (:constructor expr)
      nil
      (do
        (when (seq (:args expr))
          (throw (ex-info "create Channel takes no arguments in compiled lowering"
                          {:expr expr})))
        (ir/call-runtime-node "create-channel"
                              []
                              nex-type
                              (resolve-jvm-type env nex-type)))

      "with_capacity"
      (do
        (when-not (= 1 (count (:args expr)))
          (throw (ex-info "Channel.with_capacity expects exactly 1 argument in compiled lowering"
                          {:expr expr})))
        (ir/call-runtime-node "create-channel"
                              [(lower-expression env (first (:args expr)))]
                              nex-type
                              (resolve-jvm-type env nex-type)))

      (throw (unsupported "Unsupported Channel constructor in compiled lowering"
                          {:expr expr
                           :constructor (:constructor expr)})))))

(defn- lower-create-array
  [env expr]
  (let [nex-type (infer-type env expr)]
    (case (:constructor expr)
      nil
      (do
        (when (seq (:args expr))
          (throw (ex-info "create Array takes no arguments in compiled lowering"
                          {:expr expr})))
        (ir/call-runtime-node "create-array"
                              []
                              nex-type
                              (resolve-jvm-type env nex-type)))

      "filled"
      (do
        (when-not (= 2 (count (:args expr)))
          (throw (ex-info "Array.filled expects exactly 2 arguments in compiled lowering"
                          {:expr expr})))
        (ir/call-runtime-node "create-array-filled"
                              [(lower-expression env (first (:args expr)))
                               (lower-expression env (second (:args expr)))]
                              nex-type
                              (resolve-jvm-type env nex-type)))

      (throw (unsupported "Unsupported Array constructor in compiled lowering"
                          {:expr expr
                           :constructor (:constructor expr)})))))

(defn- lower-create-min-heap
  [env expr]
  (let [nex-type (infer-type env expr)]
    (case (:constructor expr)
      nil
      (do
        (when (seq (:args expr))
          (throw (ex-info "create Min_Heap takes no arguments in compiled lowering"
                          {:expr expr})))
        (ir/call-runtime-node "create-min-heap-empty"
                              []
                              nex-type
                              (resolve-jvm-type env nex-type)))

      "empty"
      (do
        (when (seq (:args expr))
          (throw (ex-info "Min_Heap.empty takes no arguments in compiled lowering"
                          {:expr expr})))
        (ir/call-runtime-node "create-min-heap-empty"
                              []
                              nex-type
                              (resolve-jvm-type env nex-type)))

      "from_comparator"
      (do
        (when-not (= 1 (count (:args expr)))
          (throw (ex-info "Min_Heap.from_comparator expects exactly 1 argument in compiled lowering"
                          {:expr expr})))
        (ir/call-runtime-node "create-min-heap-from-comparator"
                              [(lower-expression env (first (:args expr)))]
                              nex-type
                              (resolve-jvm-type env nex-type)))

      (throw (unsupported "Unsupported Min_Heap constructor in compiled lowering"
                          {:expr expr
                           :constructor (:constructor expr)})))))

(defn- lower-create-single-arg-atomic
  "Builds a `create <Class>.make(value)` lowering handler for the atomic
   builtins: all four (Atomic_Integer, Atomic_Integer64, Atomic_Boolean,
   Atomic_Reference) share this one-arg-named-'make' shape, differing only
   in the class name (for error messages) and the runtime-call node name."
  [class-name runtime-fn-name]
  (fn [env expr]
    (let [nex-type (infer-type env expr)]
      (when-not (= "make" (:constructor expr))
        (throw (unsupported (str "Unsupported " class-name " constructor in compiled lowering")
                            {:expr expr :constructor (:constructor expr)})))
      (when-not (= 1 (count (:args expr)))
        (throw (ex-info (str class-name ".make expects exactly 1 argument in compiled lowering")
                        {:expr expr})))
      (ir/call-runtime-node runtime-fn-name
                            [(lower-expression env (first (:args expr)))]
                            nex-type
                            (resolve-jvm-type env nex-type)))))

(defn- lower-create-map
  [env expr]
  (let [nex-type (infer-type env expr)]
    (if (nil? (:constructor expr))
      (do
        (when (seq (:args expr))
          (throw (ex-info "create Map takes no arguments in compiled lowering"
                          {:expr expr})))
        (ir/map-literal-node [] nex-type (resolve-jvm-type env nex-type)))
      (throw (unsupported "Unsupported Map constructor in compiled lowering"
                          {:expr expr :constructor (:constructor expr)})))))

(defn- lower-create-set
  [env expr]
  (let [nex-type (infer-type env expr)]
    (case (:constructor expr)
      nil
      (do
        (when (seq (:args expr))
          (throw (ex-info "create Set takes no arguments in compiled lowering"
                          {:expr expr})))
        (ir/set-literal-node [] nex-type (resolve-jvm-type env nex-type)))

      "from_array"
      (do
        (when-not (= 1 (count (:args expr)))
          (throw (ex-info "Set.from_array expects exactly 1 argument in compiled lowering"
                          {:expr expr})))
        (ir/call-runtime-node "create-set-from-array"
                              [(lower-expression env (first (:args expr)))]
                              nex-type
                              (resolve-jvm-type env nex-type)))

      (throw (unsupported "Unsupported Set constructor in compiled lowering"
                          {:expr expr :constructor (:constructor expr)})))))

(def ^:private lower-create-builtin-dispatch
  "class-name -> (fn [env expr] ...): the built-in-type half of
   `lower-create-expr`. A class name with no entry here falls through to
   the Java-import check, then to `lower-user-create` for an ordinary Nex
   class."
  {"Console"          lower-create-console
   "Process"          lower-create-process
   "Channel"          lower-create-channel
   "Array"            lower-create-array
   "Min_Heap"         lower-create-min-heap
   "Atomic_Integer"   (lower-create-single-arg-atomic "Atomic_Integer" "create-atomic-integer")
   "Atomic_Integer64" (lower-create-single-arg-atomic "Atomic_Integer64" "create-atomic-integer64")
   "Atomic_Boolean"   (lower-create-single-arg-atomic "Atomic_Boolean" "create-atomic-boolean")
   "Atomic_Reference" (lower-create-single-arg-atomic "Atomic_Reference" "create-atomic-reference")
   "Map"              lower-create-map
   "Set"              lower-create-set})

;; A constructor name on an imported Java class is decorative: the
;; interpreter's java-create-object ignores it and reflectively invokes
;; the host constructor with the arguments, so lowering does the same.
(defn- lower-java-create
  [env expr class-name]
  (let [nex-type (infer-type env expr)
        jvm-type (resolve-jvm-type env nex-type)
        arg-irs (mapv #(lower-expression env %) (:args expr))
        ^Class klass (resolve-java-class-by-name env class-name)
        resolution (when klass
                     (resolve-java-call-target
                      env
                      (.getConstructors klass)
                      (mapv :nex-type arg-irs)
                      (str "create " class-name)))]
    (cond
      (and (vector? resolution) (= :fixed (first resolution)))
      (ir/call-runtime-node "java-create-object-resolved"
                            (into [(ir/const-node (.getName klass)
                                                  "String"
                                                  (ir/object-jvm-type "java/lang/String"))
                                   (ir/const-node (resolved-param-classes-joined (second resolution))
                                                  "String"
                                                  (ir/object-jvm-type "java/lang/String"))]
                                  arg-irs)
                            nex-type
                            jvm-type)

      (and (vector? resolution) (= :varargs (first resolution)))
      (let [[fixed-classes-joined component-class-name] (resolved-varargs-descriptor (second resolution))]
        (ir/call-runtime-node "java-create-object-resolved-varargs"
                              (into [(ir/const-node (.getName klass)
                                                    "String"
                                                    (ir/object-jvm-type "java/lang/String"))
                                     (ir/const-node fixed-classes-joined
                                                    "String"
                                                    (ir/object-jvm-type "java/lang/String"))
                                     (ir/const-node component-class-name
                                                    "String"
                                                    (ir/object-jvm-type "java/lang/String"))]
                                    arg-irs)
                              nex-type
                              jvm-type))

      :else
      (ir/call-runtime-node "java-create-object"
                            (into [(ir/const-node class-name
                                                  "String"
                                                  (ir/object-jvm-type "java/lang/String"))]
                                  arg-irs)
                            nex-type
                            jvm-type))))

(defn- lower-user-create
  [env expr class-name class-def compiled]
  (when-not compiled
    (throw (unsupported "Create of non-compiled class is not supported in lowering"
                        {:expr expr :class-name class-name})))
  (when (:deferred? class-def)
    (throw (ex-info "Unsupported create of deferred class in compiled lowering"
                    {:expr expr :class-name class-name})))
  (let [created-type (infer-type env expr)
        runtime-generic-args (class-generic-runtime-args env class-def created-type)]
    (if-let [constructor-name (:constructor expr)]
      (let [ctor-def (own-or-inherited-constructor-def env class-def constructor-name (count (:args expr)))]
        (when-not ctor-def
          (throw (ex-info "Constructor not found during lowering"
                          {:expr expr
                           :class-name class-name
                           :constructor constructor-name
                           :arity (count (:args expr))})))
        (let [lowered-args (mapv #(lower-expression env %) (:args expr))
              {:keys [nex-name]} (java-superclass-parent env class-def)
              new-ir (if (and nex-name (ctor-forwards-java-super-args? nex-name ctor-def))
                       ;; This constructor's own <init> overload (see
                       ;; java-super-ctor-forward-spec) forwards real
                       ;; arguments into the Java superclass constructor —
                       ;; its descriptor is built from ctor-def's own Nex
                       ;; param types (matching that overload exactly), fed
                       ;; the SAME lowered-args also passed to the ordinary
                       ;; ctor-method call below (evaluated once).
                       (ir/new-node (:internal-name compiled)
                                    class-name
                                    (desc/method-descriptor
                                     (mapv #(resolve-jvm-type env (:type %)) (:params ctor-def))
                                     :void)
                                    lowered-args
                                    created-type
                                    (exact-class-jvm-type env class-name))
                       (ir/new-node (:internal-name compiled)
                                    class-name
                                    created-type
                                    (exact-class-jvm-type env class-name)))]
          (ir/call-virtual-node (:internal-name compiled)
                                (lowered-constructor-method-name ctor-def)
                                (desc/repl-instance-method-descriptor)
                                new-ir
                                (into lowered-args runtime-generic-args)
                                created-type
                                (resolve-jvm-type env created-type))))
      (do
        (when (seq (:args expr))
          (throw (unsupported "Only create ClassName or create ClassName.ctor(...) is supported in compiled lowering"
                              {:expr expr})))
        (if (seq (:generic-params class-def))
          (ir/call-virtual-node (:internal-name compiled)
                                (generic-init-method-name)
                                (desc/repl-instance-method-descriptor)
                                (ir/new-node (:internal-name compiled)
                                             class-name
                                             created-type
                                             (resolve-jvm-type env created-type))
                                runtime-generic-args
                                created-type
                                (resolve-jvm-type env created-type))
          (validate-object-state-ir env
                                    class-name
                                    (ir/new-node (:internal-name compiled)
                                                 class-name
                                                 created-type
                                                 (resolve-jvm-type env created-type))
                                    created-type))))))

(defn- lower-create-expr [env expr]
  (let [class-name (:class-name expr)
        compiled (get (:compiled-classes env) class-name)
        class-def (get (visible-class-map env) class-name)]
    (if-let [handler (get lower-create-builtin-dispatch class-name)]
      (handler env expr)
      (if (and class-def (:import class-def))
        (lower-java-create env expr class-name)
        (lower-user-create env expr class-name class-def compiled)))))

(defn- java-object-valued?
  "True when `expr`, used as a call target, is known at lowering time to hold
   a raw (reflection-backed) Java object at runtime rather than a Nex value —
   either because it names something declared with an imported Java type, or
   because it is itself a call chained off of one (`socket.getInetAddress()`
   is Any-typed since lower.clj never reflects into a Java method's return
   type, but its receiver `.getHostAddress()` still needs reflective dispatch
   rather than the fixed Any-protocol runtime table). Recurses through call
   chains of arbitrary depth so `a.b().c().d()` resolves correctly as long as
   `a` roots in a Java import — outside a `with \"java\"` block, where the
   same need is already handled by the with-java? branch below."
  [env expr]
  (let [expr (normalize-call-target expr)
        ;; An identifier naming a known Nex class (e.g. `Palette` in
        ;; `Palette.RED`) is a static-member access target, not a variable —
        ;; infer-type has no binding for it and throws. Recognized and
        ;; excluded up front, the same way infer-target-call-type's own
        ;; class-target-name check does, rather than reached via infer-type.
        names-known-class? (fn [e]
                             (and (map? e)
                                  (= :identifier (:type e))
                                  (some #(= (:name %) (:name e)) (:classes env))))]
    (cond
      (nil? expr) false

      (names-known-class? expr) false

      (= :call (:type expr))
      (let [target-expr (normalize-call-target (:target expr))]
        (boolean
         (or (java-host-class-root-name env target-expr)
             (when-not (names-known-class? target-expr)
               (let [target-type (resolve-type-alias (infer-type env target-expr))
                     base-type (base-type-name target-type)]
                 (or (imported-java-qualified-name env base-type)
                     (:import (get (visible-class-map env) base-type)))))
             (java-object-valued? env target-expr))))

      :else
      (let [target-type (resolve-type-alias (infer-type env expr))
            base-type (base-type-name target-type)]
        (boolean (:import (get (visible-class-map env) base-type)))))))

(defn- lower-implicit-self-call
  [env expr arg-irs]
  (let [own-method-def (class-method-def (current-class-def env) (:method expr) (count (:args expr)))
        method-def (or own-method-def
                       (inherited-method-def env (current-class-def env) (:method expr) (count (:args expr))))
        nex-type (function-return-type method-def)
        jvm-type (resolve-jvm-type env nex-type)]
    (cond
      ;; Declared with a real body on this exact class: INVOKEVIRTUAL against
      ;; it links fine, since the bytecode for it lives right here.
      (and own-method-def (not (lowered-deferred-method? (current-class-def env) own-method-def)))
      (ir/call-virtual-node (:internal-name (class-jvm-meta env (:this-type env)))
                            (lowered-instance-method-name method-def)
                            (desc/repl-instance-method-descriptor)
                            (ir/this-node (:this-type env)
                                          (exact-class-jvm-type env (:this-type env)))
                            arg-irs
                            nex-type
                            jvm-type)

      ;; Declared on this class but deferred (no body compiled here) — e.g. a
      ;; deferred class's own routine calling one of its sibling deferred
      ;; features without `this.`. INVOKEVIRTUAL against this class has no
      ;; method to link to, so dispatch through __outer__/reflection instead,
      ;; the same way an explicit `this.` call resolves an overridden method.
      own-method-def
      (let [outer-ir (ir/field-get-node (:internal-name (class-jvm-meta env (:this-type env)))
                                        "__outer__"
                                        (ir/this-node (:this-type env)
                                                      (exact-class-jvm-type env (:this-type env)))
                                        "Any"
                                        (ir/object-jvm-type "java/lang/Object"))]
        (ir/call-runtime-node (str "user-method:" (:method expr))
                              (into [outer-ir] arg-irs)
                              nex-type
                              jvm-type))

      :else
      (let [{:keys [owner-internal-name carrier-owner carrier-field carrier-jvm-type]}
            (get (direct-parent-method-map env (current-class-def env))
                 [(:method expr) (count (:args expr))])]
        (ir/call-virtual-node owner-internal-name
                              (lowered-instance-method-name method-def)
                              (desc/repl-instance-method-descriptor)
                              (ir/field-get-node (:internal-name (class-jvm-meta env carrier-owner))
                                                 carrier-field
                                                 (ir/this-node (:this-type env)
                                                               (exact-class-jvm-type env (:this-type env)))
                                                 (:source-class (get (direct-parent-method-map env (current-class-def env))
                                                                     [(:method expr) (count (:args expr))]))
                                                 carrier-jvm-type)
                              arg-irs
                              nex-type
                              jvm-type)))))

(defn- lower-call-without-target
  "A call with no receiver: an implicit call on `this` inside the currently
   executing method, a Function-valued local invoked as `f(...)`, the
   await_all/await_any builtins, another builtin free function, or (falling
   through) a REPL top-level function."
  [env expr arg-irs]
  (cond
    (and (:this-type env)
         (or (class-method-def (current-class-def env) (:method expr) (count (:args expr)))
             (inherited-method-def env (current-class-def env) (:method expr) (count (:args expr)))))
    (lower-implicit-self-call env expr arg-irs)

    (function-object-call? env (:method expr) (count (:args expr)))
    (let [nex-type (infer-type env expr)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/call-function-node (lower-expression env {:type :identifier
                                                    :name (:method expr)})
                             arg-irs
                             nex-type
                             jvm-type))

    (#{"await_all" "await_any"} (:method expr))
    (let [nex-type (infer-type env expr)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/call-runtime-node (if (= "await_all" (:method expr))
                              "op:await-all"
                              "op:await-any")
                            arg-irs
                            nex-type
                            jvm-type))

    (contains? builtin-function-names (:method expr))
    (let [nex-type (infer-type env expr)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/call-runtime-node (:method expr) arg-irs nex-type jvm-type))

    :else
    (let [nex-type (infer-type env expr)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/call-repl-fn-node (:method expr) arg-irs nex-type jvm-type))))

(defn- java-numeric-param-class?
  [^Class param-class]
  (contains? #{Integer/TYPE Long/TYPE Float/TYPE Double/TYPE Byte/TYPE Short/TYPE
               Integer Long Float Double Byte Short}
             param-class))

(defn- java-param-compatible?
  "Whether a Nex argument of ARG-NEX-TYPE may be coerced into a Java
   parameter of PARAM-CLASS — arity-and-family matching (numeric/boolean/
   char/String families, else any non-primitive/non-wrapper reference type),
   not full per-argument type inference. Used to disambiguate same-arity
   overloads without guessing: see select-java-callable."
  [arg-nex-type ^Class param-class]
  (let [base (base-type-name arg-nex-type)]
    (cond
      (#{"Integer" "Real"} base) (java-numeric-param-class? param-class)
      (= "Boolean" base) (contains? #{Boolean/TYPE Boolean} param-class)
      (= "Char" base) (contains? #{Character/TYPE Character} param-class)
      (= "String" base) (= String param-class)
      :else (and (not (.isPrimitive param-class))
                 (not (contains? #{String Boolean Character Integer Long Float Double Byte Short}
                                 param-class))))))

(defn- select-java-callable
  "Resolve the single java.lang.reflect.Constructor/Method (both expose
   getParameterTypes(), so CANDIDATES may be either) matching ARITY among
   CANDIDATES whose parameter types are each compatible (java-param-
   compatible?) with the corresponding ARG-NEX-TYPES — or throw a clear,
   honest error. Mirrors the checked-in Phase 1/2 policy of arity-only
   matching where that is unambiguous (nex.typechecker/reflected-java-
   constructor-arities), falling back to family-based type matching only to
   break a same-arity tie (Thread(String)/Thread(Runnable), say) — and never
   guessing when that still leaves more than one plausible candidate.
   KIND-LABEL/TARGET-LABEL are used only for the error message."
  [candidates arity arg-nex-types kind-label target-label]
  (let [by-arity (filter #(= arity (alength (.getParameterTypes ^java.lang.reflect.Executable %)))
                         candidates)]
    (when (empty? by-arity)
      (throw (unsupported
              (str "No " kind-label " on " target-label " with " arity " argument(s)")
              {:target target-label :arity arity})))
    (if (= 1 (count by-arity))
      (first by-arity)
      (let [plausible (filter (fn [^java.lang.reflect.Executable c]
                                (every? true? (map java-param-compatible?
                                                   arg-nex-types (.getParameterTypes c))))
                              by-arity)]
        (case (count plausible)
          1 (first plausible)
          0 (throw (unsupported
                    (str "No " kind-label " on " target-label " matches these argument types")
                    {:target target-label :arg-types (vec arg-nex-types)}))
          (throw (unsupported
                  (str "Ambiguous " kind-label " overload on " target-label
                       " — cannot resolve statically which one to call")
                  {:target target-label :arg-types (vec arg-nex-types)})))))))

;; Phase 2 (docs/proposals/java-interop.md): super.<method>(...) reaching the
;; real Java superclass implementation, bypassing this class's own override
;; of it if any (see ir/call-super-java-node). `new` never reaches here —
;; it's stripped from the constructor body earlier, in lower-constructor.
(defn- lower-java-super-call
  [env expr parent-name ^Class java-super-klass arg-irs]
  (let [arg-nex-types (mapv #(infer-type env %) (:args expr))
        candidates (filter #(= (.getName ^java.lang.reflect.Method %) (:method expr))
                           (.getMethods java-super-klass))
        ^java.lang.reflect.Method m (select-java-callable candidates (count (:args expr)) arg-nex-types
                                                          "method"
                                                          (str (.getName java-super-klass) "." (:method expr)))
        boxed-args (mapv ir/java-arg-box-node arg-irs (.getParameterTypes m))
        nex-type "Any"
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/call-super-java-node (desc/internal-class-name (.getName java-super-klass))
                             (:method expr)
                             (Type/getMethodDescriptor m)
                             (ir/this-node (:this-type env) (exact-class-jvm-type env (:this-type env)))
                             boxed-args
                             (.getReturnType m)
                             nex-type
                             jvm-type)))

(defn- lower-nex-super-call
  [env expr parent-name parent-def arg-irs]
  (let [parent-meta (class-jvm-meta env parent-name)
        target-ir (ir/field-get-node (:internal-name (class-jvm-meta env (:this-type env)))
                                     (parent-field-name parent-name)
                                     (ir/this-node (:this-type env)
                                                   (exact-class-jvm-type env (:this-type env)))
                                     parent-name
                                     (exact-class-jvm-type env parent-name))]
    (if (false? (:has-parens expr))
      (if-let [field-def (or (class-field-def parent-def (:method expr))
                             (when-let [field-info (get (direct-parent-field-map env (current-class-def env))
                                                        (:method expr))]
                               {:field-type (:nex-type field-info)}))]
        (let [nex-type (:field-type field-def)
              jvm-type (resolve-jvm-type env nex-type)]
          (ir/call-runtime-node (str "user-field-get:" (:method expr))
                                [target-ir]
                                nex-type
                                jvm-type))
        (let [method-def (or (class-method-def parent-def (:method expr) 0)
                             (inherited-method-def env parent-def (:method expr) 0))]
          (when-not method-def
            (throw (ex-info "Undefined super feature access during lowering"
                            {:expr expr
                             :parent parent-name})))
          (let [nex-type (function-return-type method-def)
                jvm-type (resolve-jvm-type env nex-type)]
            (ir/call-virtual-node (:internal-name parent-meta)
                                  (lowered-instance-method-name method-def)
                                  (desc/repl-instance-method-descriptor)
                                  target-ir
                                  []
                                  nex-type
                                  jvm-type))))
      (let [method-def (or (class-method-def parent-def (:method expr) (count (:args expr)))
                           (inherited-method-def env parent-def (:method expr) (count (:args expr))))]
        (when-not method-def
          (throw (ex-info "Undefined super method call during lowering"
                          {:expr expr
                           :parent parent-name})))
        (let [nex-type (function-return-type method-def)
              jvm-type (resolve-jvm-type env nex-type)]
          (ir/call-virtual-node (:internal-name parent-meta)
                                (lowered-instance-method-name method-def)
                                (desc/repl-instance-method-descriptor)
                                target-ir
                                arg-irs
                                nex-type
                                jvm-type))))))

(defn- lower-super-call
  [env expr arg-irs]
  (let [parent-name (single-super-parent-name env)
        parent-def (get (visible-class-map env) parent-name)
        java-super-klass (when (:import parent-def)
                           (let [^Class klass (resolve-imported-java-type env parent-name)]
                             (when (and klass (not (.isInterface klass))) klass)))]
    (if java-super-klass
      (lower-java-super-call env expr parent-name java-super-klass arg-irs)
      (lower-nex-super-call env expr parent-name parent-def arg-irs))))

(defn- lower-parent-qualified-call
  [env expr class-target-name arg-irs]
  (let [parent-meta (class-jvm-meta env class-target-name)
        method-def (class-method-def (get (visible-class-map env) class-target-name)
                                     (:method expr)
                                     (count (:args expr)))
        nex-type (function-return-type method-def)
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/call-virtual-node (:internal-name parent-meta)
                          (lowered-instance-method-name method-def)
                          (desc/repl-instance-method-descriptor)
                          (ir/field-get-node (:internal-name (class-jvm-meta env (:this-type env)))
                                             (parent-field-name class-target-name)
                                             (ir/this-node (:this-type env)
                                                           (exact-class-jvm-type env (:this-type env)))
                                             class-target-name
                                             (exact-class-jvm-type env class-target-name))
                          arg-irs
                          nex-type
                          jvm-type)))

;; ---------------------------------------------------------------------------
;; Compile-time Java overload resolution
;;
;; Every Java interop call -- instance method, static method, constructor --
;; used to dispatch at runtime through clojure.lang.Reflector, on both
;; backends, which performs no real overload resolution: when more than one
;; method shares a name and arity, Reflector reliably prefers whichever
;; candidate takes the widest reference type (Object) over a more specific
;; primitive/numeric one. java.util.ArrayList.remove(int)/(Object) is the
;; textbook case: `list.remove(1)` silently ran as "remove the value 1", not
;; "remove index 1" -- and even the *correctly* chosen Method still fails
;; reflectively, since Nex's Integer is uniformly a boxed Long and reflective
;; unboxing requires an exact wrapper-class match per parameter (a boxed Long
;; does not narrow to `int`, even reflectively).
;;
;; The compiled backend can do better than the interpreter here: lowering
;; runs after type checking, so every argument's *static* Nex type is
;; already known, and Nex's imports already carry the real java.lang.Class
;; of every Java type it interops with -- enough, in the common case, to
;; resolve the call to a single Method/Constructor here, at compile time,
;; the same way javac itself would (a deliberately simplified form of JLS
;; 15.12.2's applicability + most-specific-method rules). lower-java-
;; instance-call/-static-owner-call/-create below emit a "-resolved" runtime
;; node carrying that Method's exact parameter-type descriptor, so
;; nex.compiler.jvm.runtime's java-call-method-resolved/-static-resolved/
;; java-create-object-resolved do one unambiguous getMethod/getConstructor
;; lookup at runtime -- with the right coercion for each parameter's exact
;; type -- instead of Reflector's own guesswork.
;;
;; This pass is deliberately conservative in two ways:
;;
;;  1. It only ever engages when there are 2+ non-varargs candidates sharing
;;     a name/arity in the first place -- an actual overload-resolution
;;     situation. A single matching method (the overwhelming majority of
;;     real calls) or a genuinely unresolved one (arity mismatch, varargs,
;;     no such method) is untouched: it falls straight through to the
;;     existing runtime-reflective dispatch, unchanged, with its existing
;;     error messages.
;;  2. Within that, it resolves (falling back to :bail, never throwing) the
;;     moment anything isn't precise enough to reason about safely: an
;;     argument whose Nex type is Any/a generic parameter/a Function
;;     type/anything else this pass doesn't model, or a candidate set with
;;     no applicable match at all (this pass's own type modeling being
;;     incomplete is a reason to defer to the old path, not to fail the
;;     build). The only new failure mode this pass can ever introduce is a
;;     compile-time error for a genuine, remaining tie between two equally
;;     specific candidates -- which a real Java caller would find just as
;;     ambiguous, and is far better caught at compile time than silently
;;     mis-resolved at runtime.
;;
;; Nex Integer carries no compile-time value-range information (a `let n:
;; Integer` and a numeric literal have the same static type), so `byte`/
;; `short` parameters are deliberately never considered applicable targets
;; here -- only int/long/float/double. An overload set differentiated only
;; by byte/short still falls back to the pre-existing runtime dispatch,
;; unchanged from today.

(def ^:private integer-primitive-family
  "[^Class rank] pairs a Nex Integer argument can supply, most specific
   first (int before long before float before double) -- see the byte/short
   note above for why those are absent."
  [[Integer/TYPE 0] [Integer 0]
   [Long/TYPE 1] [Long 1]
   [Float/TYPE 2] [Float 2]
   [Double/TYPE 3] [Double 3]])

(def ^:private real-primitive-family
  "[^Class rank] pairs a Nex Real argument can supply, most specific first."
  [[Float/TYPE 0] [Float 0]
   [Double/TYPE 1] [Double 1]])

(defn- class-and-supertypes
  "KLASS plus every superclass and (transitively) every interface it or they
   implement, in breadth-first order -- so a candidate parameter type's
   index in the result is its specificity distance from KLASS itself (0 =
   KLASS exactly, larger = less specific)."
  [^Class klass]
  (loop [frontier [klass] seen #{} order []]
    (if (empty? frontier)
      order
      (let [seen' (into seen frontier)
            next-frontier (->> frontier
                               (mapcat (fn [^Class k]
                                         (remove nil? (conj (vec (.getInterfaces k))
                                                            (.getSuperclass k)))))
                               distinct
                               (remove seen'))]
        (recur next-frontier seen' (into order frontier))))))

(defn- nex-scalar-natural-class
  "The Java class a Nex scalar value of this base type is boxed as at
   runtime, on the compiled backend. nil for anything this pass doesn't
   model as a plain scalar (including Any)."
  ^Class [base]
  (case base
    "Integer" Long
    "Real" Double
    "Boolean" Boolean
    "Char" Character
    "String" String
    nil))

(defn- nex-numeric-family
  [base]
  (case base
    "Integer" integer-primitive-family
    "Real" real-primitive-family
    nil))

(defn- nex-container-natural-class
  "The concrete JVM class Nex's own Array/Map/Set are backed by on the
   compiled backend -- java.util.ArrayList (see arraylist-internal-name in
   emit.clj), and, since the Map order-preservation fix, LinkedHashMap/
   LinkedHashSet rather than HashMap/plain sets."
  ^Class [base]
  (case base
    "Array" java.util.ArrayList
    "Map" java.util.LinkedHashMap
    "Set" java.util.LinkedHashSet
    nil))

(defn- resolve-java-class-by-name
  "Best-effort ^Class for a Java class name as it can appear at a lowering
   call site: already fully qualified (java-host-class-root-name, or an
   imported class's own qualified name), a bare imported name, or (mirroring
   nex.compiler.jvm.runtime/resolve-java-host-class's identical fallback) a
   bare java.lang name used without an explicit import. nil when none
   resolves -- never throws, since failing to resolve here just means the
   caller bails to the existing runtime dispatch."
  ^Class [env class-name]
  (or (try (Class/forName class-name) (catch Exception _ nil))
      (when-let [qn (imported-java-qualified-name env class-name)]
        (try (Class/forName qn) (catch Exception _ nil)))
      (try (Class/forName (str "java.lang." class-name)) (catch Exception _ nil))
      (resolve-imported-java-type env class-name)))

(defn- arg-class-ranks
  "{^Class -> rank} of every Java class/interface a value of NEX-TYPE (as it
   appears on an already-lowered argument IR node's :nex-type) can be passed
   as, rank 0 = most specific. nil when NEX-TYPE isn't precise enough to
   reason about safely (Any, a generic type parameter, a Function type, a
   Nex class this pass doesn't resolve to a Java class, ...) -- the caller
   bails to the existing runtime-reflective dispatch in that case."
  [env nex-type]
  (let [base (if (map? nex-type) (:base-type nex-type) nex-type)]
    (when (and (string? base) (not= base "Any"))
      (if-let [family (nex-numeric-family base)]
        (let [natural (nex-scalar-natural-class base)
              base-rank (count family)
              ref-part (into {} (map-indexed (fn [i k] [k (+ base-rank i)])
                                             (class-and-supertypes natural)))]
          (merge ref-part (into {} family)))
        (when-let [natural (or (nex-scalar-natural-class base)
                               (nex-container-natural-class base)
                               (resolve-java-class-by-name env base))]
          (into {} (map-indexed (fn [i k] [k i]) (class-and-supertypes natural))))))))

(defn- applicable-params?
  [params arg-ranks]
  (every? (fn [[ranks ^Class param]] (contains? ranks param))
          (map vector arg-ranks params)))

(defn- params-rank-vector
  [params arg-ranks]
  (mapv (fn [ranks ^Class param] (get ranks param))
        arg-ranks params))

(defn- dominates?
  "True when RANKS-A is at least as specific as RANKS-B in every argument
   position and strictly more specific in at least one -- strict Pareto
   dominance over per-argument specificity rank (lower = more specific)."
  [ranks-a ranks-b]
  (and (every? true? (map <= ranks-a ranks-b))
       (some true? (map < ranks-a ranks-b))))

(defn- pick-most-specific
  "The single most-specific applicable candidate among CANDIDATES for
   arguments of the given Nex ARG-TYPES, in call order -- shared by the
   fixed-arity resolver (resolve-java-overload) and the varargs resolver
   (resolve-java-varargs-overload) below, which differ only in how a
   candidate's own per-argument Class list is computed (PARAMS-FN).

   Returns the winning candidate, or :bail -- fall back to the existing
   runtime-reflective dispatch, unchanged -- whenever an argument's Nex
   type isn't precise enough to reason about safely, or nothing this pass
   modeled turns out to be applicable. Throws only for a genuine, remaining
   tie between two-or-more equally specific applicable candidates -- see
   the namespace-level comment above for why that's the one case this pass
   reports as a compile-time error rather than bailing."
  [env candidates params-fn arg-types call-desc]
  (let [arg-ranks (mapv #(arg-class-ranks env %) arg-types)]
    (if (some nil? arg-ranks)
      :bail
      (let [applicable (filter #(applicable-params? (params-fn %) arg-ranks) candidates)]
        (if (empty? applicable)
          :bail
          (let [ranked (map (fn [c] [c (params-rank-vector (params-fn c) arg-ranks)]) applicable)
                maximal (filter (fn [[c ranks]]
                                  (not-any? (fn [[c2 ranks2]]
                                              (and (not= c c2) (dominates? ranks2 ranks)))
                                            ranked))
                                ranked)]
            (case (count maximal)
              1 (ffirst maximal)
              (throw (ex-info
                      (str "Ambiguous overload of " call-desc
                           ": " (count maximal) " equally specific Java "
                           "candidates match the given argument types — "
                           (str/join ", " (map (comp str first) maximal)))
                      {:call call-desc
                       :candidates (mapv (comp str first) maximal)})))))))))

(defn- dedupe-candidates
  "Deduped by parameter-type signature, not identity: `getMethods` can
   return more than one Method for what a caller would consider one
   overload -- most commonly a covariant-return bridge method
   (StringBuilder.append(String) really does appear twice: the declared one
   returning StringBuilder, and a synthetic bridge inherited from
   AbstractStringBuilder returning it, same params). Two candidates with
   identical parameter types are never a real ambiguity, whichever survives
   the dedupe calls identically."
  [candidates]
  (->> candidates
       (remove #(and (instance? java.lang.reflect.Method %)
                     (.isBridge ^java.lang.reflect.Method %)))
       (map (fn [^java.lang.reflect.Executable e]
              [(vec (.getParameterTypes e)) e]))
       (into {})
       vals))

(defn- resolve-java-overload
  "Resolve the single most-specific applicable *fixed-arity* candidate
   among CANDIDATES (java.lang.reflect.Method or Constructor, already
   filtered to the same name/arity/static-ness by the caller). :bail
   whenever there isn't real ambiguity to resolve (fewer than 2
   candidates) -- see pick-most-specific for the rest."
  [env candidates arg-types call-desc]
  (let [non-varargs (dedupe-candidates (remove #(.isVarArgs ^java.lang.reflect.Executable %) candidates))]
    (if (< (count non-varargs) 2)
      :bail
      (pick-most-specific env non-varargs
                          (fn [^java.lang.reflect.Executable e] (vec (.getParameterTypes e)))
                          arg-types call-desc))))

;; ---------------------------------------------------------------------------
;; Varargs
;;
;; clojure.lang.Reflector -- the mechanism every Java interop call still
;; ultimately dispatches through, even a compile-time-resolved one -- does
;; no varargs collapsing at all: `Arrays.asList(1, 2, 3)` fails outright
;; ("No matching method asList found taking 3 args"), since Reflector
;; matches by *declared* arity only. The only shape it accepts for a
;; varargs method is a pre-built array passed as the single trailing
;; argument -- which Nex has no way to construct, having no first-class
;; Java array type of its own (Array[T] is backed by ArrayList, not T[]).
;; So unlike the fixed-arity overload fix above (which only had to pick the
;; *right* Method among ones Reflector could already reach), varargs
;; support has to make these calls reachable at all: resolve which varargs
;; candidate applies, then actually build the trailing array at the call
;; site (see java-call-method-resolved-varargs et al. in
;; nex.compiler.jvm.runtime).
;;
;; Real Java overload resolution only ever considers a varargs candidate in
;; its third, least-preferred phase -- a fixed-arity match always wins when
;; one applies, full stop, never compared on specificity against a varargs
;; one. resolve-java-call-target below encodes that as a strict priority
;; order rather than one flat comparison pool: try resolve-java-overload
;; over the fixed-arity candidates first, and only when that finds nothing
;; to work with does resolve-java-varargs-overload get tried at all.

(defn- vararg-effective-params
  "CANDIDATE's per-argument Class list against an actual call of
   ACTUAL-ARG-COUNT arguments: its own fixed leading parameters unchanged,
   plus its trailing array parameter's component type repeated once for
   every argument beyond the fixed prefix."
  [^java.lang.reflect.Executable candidate actual-arg-count]
  (let [declared (.getParameterTypes candidate)
        fixed-count (dec (alength declared))
        ^Class array-type (aget declared fixed-count)
        component (.getComponentType array-type)
        trailing-count (- actual-arg-count fixed-count)]
    (into (vec (take fixed-count declared)) (repeat trailing-count component))))

(defn- resolve-java-varargs-overload
  [env candidates arg-types call-desc]
  (let [actual-arg-count (count arg-types)
        candidates (dedupe-candidates candidates)]
    (pick-most-specific env candidates
                        #(vararg-effective-params % actual-arg-count)
                        arg-types call-desc)))

(defn- resolve-java-call-target
  "Resolve a Java call -- instance method, static method, or constructor --
   against ALL-CANDIDATES (every Method/Constructor on the owning class
   with the right name and static-ness, arity unfiltered) for arguments of
   the given Nex ARG-TYPES. A fixed-arity match is always tried first and
   always preferred when it applies (see the varargs section above);
   varargs candidates are only even considered when no fixed-arity
   candidate does.

   Returns :bail, [:fixed <Executable>], or [:varargs <Executable>]; throws
   only for a genuine tie -- see pick-most-specific."
  [env all-candidates arg-types call-desc]
  (let [actual-arg-count (count arg-types)
        fixed (filter (fn [^java.lang.reflect.Executable e]
                        (and (not (.isVarArgs e))
                             (= (alength (.getParameterTypes e)) actual-arg-count)))
                      all-candidates)
        vararg (filter (fn [^java.lang.reflect.Executable e]
                         (and (.isVarArgs e)
                              (<= (dec (alength (.getParameterTypes e))) actual-arg-count)))
                       all-candidates)]
    (cond
      (>= (count fixed) 2)
      (let [r (resolve-java-overload env fixed arg-types call-desc)]
        (if (= r :bail) :bail [:fixed r]))

      ;; A single fixed-arity candidate, no varargs alternative at this
      ;; arity: today's plain "not enough candidates to bother" case,
      ;; unchanged -- Reflector already dispatches this correctly on its
      ;; own.
      (and (= (count fixed) 1) (empty? vararg))
      :bail

      ;; A fixed-arity candidate AND one-or-more varargs candidates both
      ;; textually apply at this exact arity. JLS would still prefer the
      ;; fixed one, but replaying that precisely needs its own
      ;; applicability check on the fixed candidate here too -- a class
      ;; overloading a plain method against a varargs one at the exact
      ;; same call arity is rare enough that this is left as a bail
      ;; (deferring to the existing runtime dispatch) rather than adding
      ;; that check for a corner this narrow.
      (and (= (count fixed) 1) (seq vararg))
      :bail

      (empty? vararg)
      :bail

      :else
      (let [r (resolve-java-varargs-overload env vararg arg-types call-desc)]
        (if (= r :bail) :bail [:varargs r])))))

(defn- resolved-param-classes-joined
  [^java.lang.reflect.Executable resolved]
  (str/join "," (map #(.getName ^Class %) (.getParameterTypes resolved))))

(defn- resolved-varargs-descriptor
  "[fixed-param-classes-joined component-class-name] for a resolved
   varargs Executable -- the encoding java-call-method-resolved-varargs et
   al. (nex.compiler.jvm.runtime) expect: the fixed leading parameters
   exactly like a fixed-arity resolution, plus the single component class
   every trailing actual argument gets collected against."
  [^java.lang.reflect.Executable resolved]
  (let [declared (.getParameterTypes resolved)
        fixed-count (dec (alength declared))
        ^Class array-type (aget declared fixed-count)]
    [(str/join "," (map #(.getName ^Class %) (take fixed-count declared)))
     (.getName (.getComponentType array-type))]))

(defn- lower-class-constant-or-static-field
  [env expr class-target-name]
  (if-let [constant (lookup-class-constant env class-target-name (:method expr))]
    (let [owner (:declaring-class constant)
          nex-type (constant-nex-type env constant)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/static-field-get-node (:internal-name (class-jvm-meta env owner))
                                (:name constant)
                                nex-type
                                jvm-type))
    ;; class-target-name matches any entry in `(:classes env)`, including
    ;; imported-Java-class placeholders (empty :body, so lookup-class-constant
    ;; above always misses them). Fall through to the same java-get-static-field
    ;; runtime call `lower-java-static-owner-call` already uses for the
    ;; has-parens (static method) case, rather than treating an imported class
    ;; as an unsupported target.
    (if-let [java-owner (:import (get (visible-class-map env) class-target-name))]
      (let [nex-type (or (infer-call-type env expr) "Any")
            jvm-type (resolve-jvm-type env nex-type)]
        (ir/call-runtime-node "java-get-static-field"
                              [(ir/const-node java-owner
                                              "String"
                                              (ir/object-jvm-type "java/lang/String"))
                               (ir/const-node (:method expr)
                                              "String"
                                              (ir/object-jvm-type "java/lang/String"))]
                              nex-type
                              jvm-type))
      (throw (unsupported "Unsupported class-target access during lowering"
                          {:expr expr
                           :target-class class-target-name})))))

(defn- lower-java-static-owner-call
  [env expr java-static-owner arg-irs]
  (let [nex-type (or (infer-call-type env expr) "Any")
        jvm-type (resolve-jvm-type env nex-type)]
    (if (:has-parens expr)
      (let [method-name (:method expr)
            ^Class owner-class (resolve-java-class-by-name env java-static-owner)
            same-name-static (when owner-class
                               (->> (.getMethods owner-class)
                                    (filter (fn [^java.lang.reflect.Method m]
                                              (and (= (.getName m) method-name)
                                                   (java.lang.reflect.Modifier/isStatic (.getModifiers m)))))))
            resolution (when owner-class
                         (resolve-java-call-target
                          env same-name-static (mapv :nex-type arg-irs)
                          (str java-static-owner "." method-name)))]
        (cond
          (and (vector? resolution) (= :fixed (first resolution)))
          (ir/call-runtime-node "java-call-static-resolved"
                                (into [(ir/const-node java-static-owner
                                                      "String"
                                                      (ir/object-jvm-type "java/lang/String"))
                                       (ir/const-node method-name
                                                      "String"
                                                      (ir/object-jvm-type "java/lang/String"))
                                       (ir/const-node (resolved-param-classes-joined (second resolution))
                                                      "String"
                                                      (ir/object-jvm-type "java/lang/String"))]
                                      arg-irs)
                                nex-type
                                jvm-type)

          (and (vector? resolution) (= :varargs (first resolution)))
          (let [[fixed-classes-joined component-class-name] (resolved-varargs-descriptor (second resolution))]
            (ir/call-runtime-node "java-call-static-resolved-varargs"
                                  (into [(ir/const-node java-static-owner
                                                        "String"
                                                        (ir/object-jvm-type "java/lang/String"))
                                         (ir/const-node method-name
                                                        "String"
                                                        (ir/object-jvm-type "java/lang/String"))
                                         (ir/const-node fixed-classes-joined
                                                        "String"
                                                        (ir/object-jvm-type "java/lang/String"))
                                         (ir/const-node component-class-name
                                                        "String"
                                                        (ir/object-jvm-type "java/lang/String"))]
                                        arg-irs)
                                  nex-type
                                  jvm-type))

          :else
          (ir/call-runtime-node "java-call-static"
                                (into [(ir/const-node java-static-owner
                                                      "String"
                                                      (ir/object-jvm-type "java/lang/String"))
                                       (ir/const-node method-name
                                                      "String"
                                                      (ir/object-jvm-type "java/lang/String"))]
                                      arg-irs)
                                nex-type
                                jvm-type)))
      (ir/call-runtime-node "java-get-static-field"
                            [(ir/const-node java-static-owner
                                            "String"
                                            (ir/object-jvm-type "java/lang/String"))
                             (ir/const-node (:method expr)
                                            "String"
                                            (ir/object-jvm-type "java/lang/String"))]
                            nex-type
                            jvm-type))))

(defn- lower-direct-bitwise-call
  [env expr target-expr arg-irs]
  (let [target-ir (lower-expression env target-expr)
        nex-type (infer-type env expr)
        jvm-type (resolve-jvm-type env nex-type)
        direct-op (get direct-integer-bitwise-method->op (:method expr))]
    (if (= :bit-not direct-op)
      (ir/unary-node direct-op target-ir nex-type jvm-type)
      (ir/binary-node direct-op
                      target-ir
                      (first arg-irs)
                      nex-type
                      jvm-type))))

(defn- lower-direct-collection-call
  [env expr target-expr target-type arg-irs]
  (let [target-ir (lower-expression env target-expr)
        nex-type (or (collection-method-return-type target-type (:method expr))
                     (infer-type env expr))
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/collection-method-node (keyword (.toLowerCase ^String (base-type-name target-type)))
                               (:method expr)
                               target-ir
                               arg-irs
                               nex-type
                               jvm-type)))

(defn- lower-direct-concurrency-call
  [env expr target-expr target-type arg-irs]
  (let [target-ir (lower-expression env target-expr)
        nex-type (infer-type env expr)
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/concurrency-method-node (keyword (.toLowerCase ^String (base-type-name target-type)))
                                (:method expr)
                                target-ir
                                arg-irs
                                nex-type
                                jvm-type)))

;; Shared by both an imported-Java-typed receiver (`imported-java-qualified-
;; name`) and a receiver only known through host interop (`with "java"` or a
;; call chain rooted in one) — both dispatch through the same reflective
;; runtime calls.
(defn- lower-java-instance-call
  [env expr target-expr arg-irs]
  (let [target-ir (lower-expression env target-expr)
        nex-type (or (infer-call-type env expr) "Any")
        jvm-type (resolve-jvm-type env nex-type)]
    (if (:has-parens expr)
      (let [method-name (:method expr)
            ;; Known only when the receiver's own static Nex type names a
            ;; real, resolvable Java class -- the common `with "java"` idiom
            ;; of routing everything through an `Any`-typed local (there is
            ;; no way for the typechecker to know a real Java type there,
            ;; see lower-general-receiver-call) leaves this nil, and
            ;; resolve-java-call-target is never even attempted.
            receiver-base (base-type-name (resolve-type-alias (infer-type-or-any env target-expr)))
            ^Class receiver-class (resolve-java-class-by-name env receiver-base)
            same-name-instance (when receiver-class
                                 (->> (.getMethods receiver-class)
                                      (filter (fn [^java.lang.reflect.Method m]
                                                (and (= (.getName m) method-name)
                                                     (not (java.lang.reflect.Modifier/isStatic (.getModifiers m))))))))
            resolution (when receiver-class
                         (resolve-java-call-target
                          env same-name-instance (mapv :nex-type arg-irs)
                          (str receiver-base "." method-name)))]
        (cond
          (and (vector? resolution) (= :fixed (first resolution)))
          (ir/call-runtime-node "java-call-method-resolved"
                                (into [(ir/const-node method-name
                                                      "String"
                                                      (ir/object-jvm-type "java/lang/String"))
                                       (ir/const-node (.getName receiver-class)
                                                      "String"
                                                      (ir/object-jvm-type "java/lang/String"))
                                       (ir/const-node (resolved-param-classes-joined (second resolution))
                                                      "String"
                                                      (ir/object-jvm-type "java/lang/String"))
                                       target-ir]
                                      arg-irs)
                                nex-type
                                jvm-type)

          (and (vector? resolution) (= :varargs (first resolution)))
          (let [[fixed-classes-joined component-class-name] (resolved-varargs-descriptor (second resolution))]
            (ir/call-runtime-node "java-call-method-resolved-varargs"
                                  (into [(ir/const-node method-name
                                                        "String"
                                                        (ir/object-jvm-type "java/lang/String"))
                                         (ir/const-node (.getName receiver-class)
                                                        "String"
                                                        (ir/object-jvm-type "java/lang/String"))
                                         (ir/const-node fixed-classes-joined
                                                        "String"
                                                        (ir/object-jvm-type "java/lang/String"))
                                         (ir/const-node component-class-name
                                                        "String"
                                                        (ir/object-jvm-type "java/lang/String"))
                                         target-ir]
                                        arg-irs)
                                  nex-type
                                  jvm-type))

          :else
          (ir/call-runtime-node "java-call-method"
                                (into [(ir/const-node method-name
                                                      "String"
                                                      (ir/object-jvm-type "java/lang/String"))
                                       target-ir]
                                      arg-irs)
                                nex-type
                                jvm-type)))
      (ir/call-runtime-node "java-get-field"
                            [(ir/const-node (:method expr)
                                            "String"
                                            (ir/object-jvm-type "java/lang/String"))
                             target-ir]
                            nex-type
                            jvm-type))))

(defn- lower-builtin-receiver-call
  [env expr target-expr target-type arg-irs]
  (let [target-ir (lower-expression env target-expr)
        base-type (base-type-name target-type)
        nex-type (infer-type env expr)
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/call-runtime-node (str "builtin-method:" base-type ":" (:method expr))
                          (into [target-ir] arg-irs)
                          nex-type
                          jvm-type)))

(defn- lower-generic-builtin-constrained-call
  [env expr target-expr target-type arg-irs]
  (let [target-ir (lower-expression env target-expr)
        constraint (get (:generic-param-constraints env)
                        (base-type-name target-type))
        nex-type (or (infer-type env expr) "Any")
        jvm-type (resolve-jvm-type env nex-type)]
    (ir/call-runtime-node (str "method:" (:method expr))
                          (into [target-ir] arg-irs)
                          nex-type
                          jvm-type)))

(defn- lower-generic-user-constrained-call
  [env expr target-expr target-type arg-irs]
  (let [constraint-def (->> (get (:generic-param-constraints env)
                                 (base-type-name target-type))
                            (get (visible-class-map env)))
        method-def (accessible-method-def env constraint-def (:method expr)
                                          (count (:args expr)))
        field-def (when-not method-def
                    (accessible-field-def env constraint-def (:method expr)))
        target-ir (lower-expression env target-expr)
        nex-type (or (if method-def
                       (function-return-type method-def)
                       (:field-type field-def))
                     (infer-call-type env expr)
                     "Any")
        jvm-type (resolve-jvm-type env nex-type)]
    ;; A routine of the bound dispatches as a routine even when written
    ;; without parentheses (Nex allows `x.describe` for a no-arg call), so
    ;; resolution — not punctuation — decides method vs field.
    (if method-def
      (ir/call-runtime-node (str "user-method:" (:method expr))
                            (into [target-ir] arg-irs)
                            nex-type
                            jvm-type)
      (ir/call-runtime-node (str "user-field-get:" (:method expr))
                            [target-ir]
                            nex-type
                            jvm-type))))

(defn- lower-general-receiver-call
  [env expr target-expr arg-irs]
  (let [java-static-owner (java-host-class-root-name env target-expr)
        ;; Resolved through aliases for the same reason as in
        ;; `infer-target-call-type`: the branches below choose a dispatch
        ;; strategy by receiver type, and an alias/refinement name matches
        ;; none of them.
        target-type (when-not java-static-owner
                      (resolve-type-alias (infer-type env target-expr)))]
    (cond
      ;; `a(1)(2)(3)` — invoking the result of a call/expression directly,
      ;; no member name to dispatch on. The walker gives every call past the
      ;; first a nil `:method` and a `:target` that is itself the previous
      ;; call (see :postfix's "Call on expression result: (expr)(...)"
      ;; case); the typechecker's matching branch in `check-target-call`
      ;; treats this exactly like the named `f.call1(x)` spelling. Lower it
      ;; the same way the no-target `f(...)` case already does for a
      ;; Function-valued identifier (`lower-call-without-target`'s
      ;; `function-object-call?` branch) — just with TARGET-EXPR's own IR in
      ;; place of an identifier lookup.
      (and (nil? (:method expr)) (= "Function" (base-type-name target-type)))
      (let [nex-type (or (:return-type target-type) "Any")
            jvm-type (resolve-jvm-type env nex-type)]
        (ir/call-function-node (lower-expression env target-expr) arg-irs nex-type jvm-type))

      java-static-owner
      (lower-java-static-owner-call env expr java-static-owner arg-irs)

      (if-let [direct-op (and (= "Integer" (base-type-name target-type))
                              (get direct-integer-bitwise-method->op (:method expr)))]
        direct-op
        false)
      (lower-direct-bitwise-call env expr target-expr arg-irs)

      (direct-collection-method? target-type (:method expr))
      (lower-direct-collection-call env expr target-expr target-type arg-irs)

      (direct-concurrency-method? env target-type (:method expr))
      (lower-direct-concurrency-call env expr target-expr target-type arg-irs)

      (imported-java-qualified-name env (base-type-name target-type))
      (lower-java-instance-call env expr target-expr arg-irs)

      ;; Host interop is only for *unresolved* targets (see the env
      ;; docstring): a with-"java" block still contains ordinary Nex calls
      ;; (Console, collections, user classes), which must keep their normal
      ;; dispatch rather than fall into reflection.
      ;;
      ;; `Any` is a builtin-runtime-receiver-type too, and even a registered
      ;; (synthetic) entry in `visible-class-map` — but it is also the
      ;; static type every Java interop value carries (there is no way for
      ;; the typechecker to know a real Java type), so the ordinary
      ;; "unresolved target" tests below would wrongly send every interop
      ;; call — `builder.append(s)` on a `builder: Any` holding a live
      ;; `java.lang.StringBuilder`, say — into the fixed Any-protocol
      ;; dispatch table instead, which only understands the handful of
      ;; names in `any-protocol-method-names` and crashes on anything else
      ;; with an unbound-Var error at runtime. Inside a with-"java" block,
      ;; always treat any other method name on an `Any` receiver as host
      ;; interop, regardless of those other tests. Outside such a block,
      ;; the same call shape can still arise from a call chain rooted in an
      ;; imported Java type (`socket.getInetAddress().getHostAddress()`) —
      ;; java-object-valued? recognizes that case from the target
      ;; expression's own shape, no with-java? needed.
      (and (or (:with-java? env) (java-object-valued? env target-expr))
           (or (and (= "Any" (base-type-name target-type))
                    (not (contains? any-protocol-method-names (:method expr))))
               (and (not (builtin-runtime-receiver-type? env target-type))
                    (not (get (visible-class-map env) (base-type-name target-type)))
                    (not (get (:compiled-classes env) (base-type-name target-type))))))
      (lower-java-instance-call env expr target-expr arg-irs)

      (builtin-runtime-receiver-type? env target-type)
      (lower-builtin-receiver-call env expr target-expr target-type arg-irs)

      ;; Generic type parameter constrained by a *user* class (e.g. `[T ->
      ;; Addable]`). The receiver is an ordinary Nex object at runtime, so
      ;; dispatch dynamically the way any user-class call does; the
      ;; constraint supplies the routine's declared signature, which is
      ;; what the typechecker already checked the call against.
      ;;
      ;; Checked BEFORE the builtin-constrained branch below, not after: a
      ;; bound's name is not reserved just because it happens to collide
      ;; with a builtin-runtime-receiver-type's own name (`Comparable`,
      ;; `Task`, `Channel`, ...) — a user's own `deferred class Comparable`
      ;; is exactly as overridable here as it already is everywhere else a
      ;; user definition shadows a builtin placeholder (see check-program's
      ;; own "an entry file's own classes... override builtin placeholder
      ;; names such as Task or Channel"). Checking user-constrained first
      ;; means a real match here — the constraint resolves to a REAL class
      ;; in visible-class-map that actually declares the called method or
      ;; field — always wins; the builtin branch is reached only when no
      ;; such user class exists, exactly the ordinary (and far more common)
      ;; case of a genuinely builtin bound like the checked-in `T ->
      ;; Comparable` example that compares with `>`. Before this fix, the
      ;; builtin branch ran first and matched unconditionally on the name
      ;; alone, so a user's own same-named class was silently never
      ;; reached at all — its own real method looked up via the wrong
      ;; (builtin, reflection-free) dispatch and failing at runtime with
      ;; "Method not found on type", even though the identical call
      ;; compiled and ran correctly the moment the class was renamed to
      ;; anything that didn't collide with a builtin name.
      (when-let [constraint-def (some->> (get (:generic-param-constraints env)
                                              (base-type-name target-type))
                                         (get (visible-class-map env)))]
        (or (accessible-method-def env constraint-def (:method expr)
                                   (count (:args expr)))
            (accessible-field-def env constraint-def (:method expr))))
      (lower-generic-user-constrained-call env expr target-expr target-type arg-irs)

      ;; Generic type parameter with a constraint (e.g. T -> Comparable)
      ;; Dispatch through the constraint type's builtin methods at runtime
      (when-let [constraint (get (:generic-param-constraints env)
                                 (base-type-name target-type))]
        (contains? builtin-runtime-receiver-types constraint))
      (lower-generic-builtin-constrained-call env expr target-expr target-type arg-irs)

      :else
      (or (lower-instance-dispatch env target-expr (:method expr) (:args expr) (:has-parens expr))
          (throw (unsupported "Unsupported target call expression for lowering"
                              {:expr expr
                               :target-type target-type}))))))

(defn- lower-call-with-target
  [env expr target-expr class-target-name arg-irs]
  (cond
    (= :super (:type target-expr))
    (lower-super-call env expr arg-irs)

    (and class-target-name
         (:this-type env)
         (some #(= class-target-name (:parent %))
               (:parents (current-class-def env)))
         (if-let [parent-def (get (visible-class-map env) class-target-name)]
           (class-method-def parent-def (:method expr) (count (:args expr)))
           false))
    (lower-parent-qualified-call env expr class-target-name arg-irs)

    (and class-target-name (false? (:has-parens expr)))
    (lower-class-constant-or-static-field env expr class-target-name)

    :else
    (lower-general-receiver-call env expr target-expr arg-irs)))

(defn- lower-call-expr [env expr]
  (if (and (nil? (:target expr))
           (empty? (:args expr))
           (not (:has-parens expr)))
    (lower-expression env {:type :identifier
                           :name (:method expr)})
    (let [raw-target (:target expr)
          class-target-name (when (string? raw-target)
                              (some #(when (= (:name %) raw-target)
                                       (:name %))
                                    (:classes env)))
          target-expr (normalize-call-target raw-target)
          arg-irs (mapv #(lower-expression env %) (:args expr))]
      (if (and (map? target-expr)
               (= :create (:type target-expr))
               (nil? (:method expr)))
        (if (nil? (:constructor target-expr))
          (throw (invalid-bare-create-call-ex (:class-name target-expr)))
          (lower-expression env (assoc target-expr :args (:args expr))))
        (if (nil? target-expr)
          (lower-call-without-target env expr arg-irs)
          (lower-call-with-target env expr target-expr class-target-name arg-irs))))))

(defn- lower-stmt-let
  [env stmt]
  (let [across-binding (across-cursor-binding env stmt)
        [env0 value-ir] (cond
                          across-binding
                          [env (lower-expression env (:target-expr across-binding))]

                          (= :convert (:type (:value stmt)))
                          (let [[env' _] (ensure-convert-binding env (:value stmt))
                                [env'' convert-ir] (lower-convert-expression env' (:value stmt))]
                            [env'' convert-ir])

                          (= :attached-test (:type (:value stmt)))
                          (let [attached-node (:value stmt)
                                [env' _] (ensure-convert-binding
                                          env
                                          {:var-name (:var-name attached-node)
                                           :type (tc/attachable-type
                                                  (infer-type-or-any env (:value attached-node)))})
                                [env'' attached-ir] (lower-attached-test-expression env' attached-node)]
                            [env'' attached-ir])

                          :else
                          [env (lower-expression env (:value stmt))])
        nex-type (or (:var-type stmt)
                     (:target-type across-binding)
                     (infer-type env0 (:value stmt)))
        env1 (if (and (:synthetic stmt)
                      (string? (:name stmt))
                      (str/starts-with? (:name stmt) "__across_c_")
                      (= :call (get-in stmt [:value :type]))
                      (= "cursor" (get-in stmt [:value :method]))
                      (empty? (get-in stmt [:value :args])))
               (let [target-type (infer-type env0 (get-in stmt [:value :target]))]
                 (assoc-in env0 [:across-cursors (:name stmt)]
                           (tc/cursor-item-type target-type)))
               env0)]
    (if (and (:top-level? env) (not (:scoped-locals? env)))
      [(update env1 :var-types assoc (:name stmt) nex-type)
       (ir/top-set-node (:name stmt) value-ir nex-type (resolve-jvm-type env1 nex-type))]
      (let [[env' local] (env-add-local env1 (:name stmt) nex-type)]
        [env' (ir/set-local-node (:slot local) value-ir (:nex-type local) (:jvm-type local))]))))

(defn- lower-stmt-assign
  [env stmt]
  (let [value-ir (lower-expression env (:value stmt))
        target-name (:target stmt)]
    (if-let [{:keys [slot nex-type jvm-type]} (get (:locals env) target-name)]
      [env (ir/set-local-node slot value-ir nex-type jvm-type)]
      (if-let [{:keys [owner field nex-type jvm-type carrier-path]} (get (:fields env) target-name)]
        (let [target-ir (carrier-path-target-ir env carrier-path
                                                (ir/this-node (:this-type env)
                                                              (exact-class-jvm-type env (:this-type env))))]
          [env (ir/field-set-node (:internal-name (class-jvm-meta env owner))
                                  field
                                  target-ir
                                  value-ir
                                  nex-type
                                  jvm-type)])
        (let [nex-type (or (get (:var-types env) target-name)
                           (infer-type env {:type :identifier :name target-name}))
              jvm-type (resolve-jvm-type env nex-type)]
          (if (:top-level? env)
            [(update env :var-types assoc target-name nex-type)
             (ir/top-set-node target-name value-ir nex-type jvm-type)]
            (throw (ex-info "Assignment target is not a known local"
                            {:target target-name}))))))))

(defn- lower-stmt-convert
  [env stmt]
  (let [[env' _] (ensure-convert-binding env stmt)
        [env'' convert-ir] (lower-convert-expression env' stmt)]
    [env'' (ir/pop-node convert-ir)]))

(defn- lower-stmt-with
  [env stmt]
  (if (= "java" (:target stmt))
    (let [[env' lowered] (lower-statements (assoc env :with-java? true) (:body stmt))]
      [(assoc env' :with-java? (:with-java? env))
       (with-stmt-debug (ir/block-node lowered) stmt)])
    [env (with-stmt-debug (ir/block-node []) stmt)]))

(defn- lower-stmt-if
  [env stmt]
  (let [[cond-env test-ir] (lower-boolean-condition env (:condition stmt))
        condition-init-stmts (convert-binding-init-stmts cond-env (:condition stmt))
        [then-env then-body] (lower-scoped-statements (refine-condition-branch-env cond-env (:condition stmt) :then)
                                                      (:then stmt))
        [else-env else-body]
        (if-let [clause (first (:elseif stmt))]
          (lower-scoped-statements
           (scoped-env env then-env)
           [{:type :if
             :condition (:condition clause)
             :then (:then clause)
             :elseif (vec (rest (:elseif stmt)))
             :else (:else stmt)}])
          (lower-scoped-statements (refine-condition-branch-env (scoped-env env then-env)
                                                                (:condition stmt)
                                                                :else)
                                   (or (:else stmt) [])))]
    [(scoped-env env else-env)
     (if (seq condition-init-stmts)
       (ir/block-node (conj condition-init-stmts
                            (ir/if-stmt-node test-ir then-body else-body)))
       (ir/if-stmt-node test-ir then-body else-body))]))

(defn- lower-stmt-scoped-block
  [env stmt]
  (if-let [rescue (:rescue stmt)]
    (let [[env1 throwable-slot] (alloc-temp-slot env)
          [env2 rescue-throwable-slot] (alloc-temp-slot env1)
          [body-env lowered-body] (lower-statements (scoped-child-env env2) (:body stmt))
          local-init-stmts (init-new-locals-stmts env2 body-env)
          env-after-body (scoped-env env2 body-env)
          rescue-env0 (assoc (scoped-child-env env-after-body) :retry-allowed? true)
          [rescue-env1 exception-local] (env-add-local rescue-env0 "exception" "Any")
          [rescue-env2 lowered-rescue] (lower-statements rescue-env1 rescue)
          final-env (scoped-env env-after-body rescue-env2)]
      [final-env
       (if (seq local-init-stmts)
         (ir/block-node (conj local-init-stmts
                              (ir/try-node lowered-body
                                           lowered-rescue
                                           throwable-slot
                                           rescue-throwable-slot
                                           (:slot exception-local))))
         (ir/try-node lowered-body
                      lowered-rescue
                      throwable-slot
                      rescue-throwable-slot
                      (:slot exception-local)))])
    (let [[env' lowered] (lower-scoped-statements env (:body stmt))]
      [env' (ir/block-node lowered)])))

(defn- lower-stmt-case
  [env stmt]
  (let [case-env (scoped-child-env env)
        [env' local] (env-add-local case-env (str "__case_tmp_" (:next-slot env) "__")
                                    (infer-type env (:expr stmt)))
        init-local (ir/set-local-node (:slot local)
                                      (lower-expression env (:expr stmt))
                                      (:nex-type local)
                                      (:jvm-type local))
        [env'' lowered-clauses] (lower-case-clauses env' local (:clauses stmt)
                                                    (if-let [else-stmt (:else stmt)]
                                                      [else-stmt]
                                                      []))]
    [(scoped-env env env'')
     (ir/block-node (into [init-local] lowered-clauses))]))

(defn- lower-stmt-match
  [env stmt]
  (let [match-env (scoped-child-env env)
        tmp-name (str "__match_tmp_" (:next-slot env) "__")
        [env' local] (env-add-local match-env tmp-name
                                    (infer-type env (:expr stmt)))
        init-local (ir/set-local-node (:slot local)
                                      (lower-expression env (:expr stmt))
                                      (:nex-type local)
                                      (:jvm-type local))
        else-stmts (if-let [else-body (:else stmt)]
                     else-body
                     [{:type :raise
                       :value {:type :string :value "No matching clause in match"}}])
        [env'' lowered-clauses] (lower-match-clauses env' tmp-name (:clauses stmt) else-stmts)]
    [(scoped-env env env'')
     (ir/block-node (into [init-local] lowered-clauses))]))

(defn- lower-stmt-raise
  [env stmt]
  [env (ir/raise-node (lower-expression env (:value stmt)))])

(defn- lower-stmt-retry
  [env stmt]
  (if (:retry-allowed? env)
    [env (ir/retry-node)]
    (throw (ex-info "retry is only supported in compiled rescue blocks"
                    {:stmt stmt}))))

;; Several assertions under one `assert` lower to one check each, in order.
;; A bare `assert expr` has no label, so carry the statement's line down to
;; each check for the failure message.
(defn- lower-stmt-assert
  [env stmt]
  [env (ir/block-node
        (mapv (fn [assertion]
                (cond-> (assertion-ir env :assert assertion)
                  (:dbg/line stmt) (assoc :dbg/line (:dbg/line stmt))))
              (:assertions stmt)))])

(defn- lower-expr-statement
  [env stmt]
  [env (ir/pop-node (lower-expression env stmt))])

(def ^:private lower-statement-dispatch
  "AST node `:type` -> `(fn [env stmt] -> [env' ir-node])`: the primary
   dispatch table for `lower-statement`. `lower-member-assign-stmt`,
   `lower-call-stmt`, and `lower-loop-stmt` are only forward-declared this
   early in the file (their own defns come later) — wrapped for the same
   reason `infer-type-dispatch`'s `:call` entry is. `lower-select` is
   already defined above this point, so it's referenced bare. A statement
   type with no entry here falls through to `expression-node-types` in
   `lower-statement` — expressions used as bare statements pop their value."
  {:let           lower-stmt-let
   :assign        lower-stmt-assign
   :member-assign (fn [env stmt] (lower-member-assign-stmt env stmt))
   :call          (fn [env stmt] (lower-call-stmt env stmt))
   :convert       lower-stmt-convert
   :with          lower-stmt-with
   :if            lower-stmt-if
   :scoped-block  lower-stmt-scoped-block
   :case          lower-stmt-case
   :match         lower-stmt-match
   :loop          (fn [env stmt] (lower-loop-stmt env stmt))
   :select        lower-select
   :raise         lower-stmt-raise
   :retry         lower-stmt-retry
   :assert        lower-stmt-assert})

(defn lower-statement
  [env stmt]
  (let [[env' lowered]
        (if-let [handler (get lower-statement-dispatch (:type stmt))]
          (handler env stmt)
          (if (contains? expression-node-types (:type stmt))
            (lower-expr-statement env stmt)
            (throw (unsupported "Unsupported statement node for lowering"
                                {:stmt stmt :node-type (:type stmt)}))))]
    [env' (with-stmt-debug lowered stmt)]))

;; `super.field := v` writes the same underlying object as `this` would (the
;; composition carrier already reaches the parent's storage), but is only
;; writable when the field's owner is the resolved *parent* — the whole point
;; being to assign a field the current (sub)class alone isn't allowed to touch
;; directly. Sharing this path with `:this` (rather than the unconditional throw
;; this used to be, before `super` field access could even reach lowering — see
;; `resolve-super-parent-name` in `nex.interpreter` and `nex.typechecker` for the
;; matching fix there) keeps the writability check and IR shape identical to
;; `this.field`.
(defn- lower-this-or-super-field-set
  [env stmt target-expr super-target? value-ir]
  (let [field-name (:field stmt)
        expected-owner (if super-target? (single-super-parent-name env) (:current-class env))
        field-info (get (:fields env) field-name)
        writable? (= (:owner field-info) expected-owner)]
    (when-not writable?
      (throw (ex-info (field-write-error-message field-name (:owner field-info))
                      {:field field-name
                       :declaring-class (:owner field-info)
                       :target target-expr})))
    (let [target-ir (carrier-path-target-ir env (:carrier-path field-info)
                                            (ir/this-node (:this-type env)
                                                          (exact-class-jvm-type env (:this-type env))))]
      [env (ir/field-set-node (:internal-name (class-jvm-meta env (:owner field-info)))
                              field-name
                              target-ir
                              value-ir
                              (:nex-type field-info)
                              (:jvm-type field-info))])))

(defn- lower-member-assign-stmt [env stmt]
  (let [field-name (:field stmt)
        target-expr (or (:object stmt) {:type :this})
        super-target? (= :super (:type target-expr))
        target-type (when-not super-target? (infer-type env target-expr))
        owner (base-type-name target-type)
        class-def (get (visible-class-map env) owner)
        field-def (when class-def (accessible-field-def env class-def field-name))
        value-ir (lower-expression env (:value stmt))]
    (cond
      (and (or super-target? (= (:type target-expr) :this))
           (get (:fields env) field-name))
      (lower-this-or-super-field-set env stmt target-expr super-target? value-ir)

      super-target?
      (let [parent-name (single-super-parent-name env)]
        (throw (ex-info (field-write-error-message field-name parent-name)
                        {:field field-name
                         :declaring-class parent-name
                         :target target-expr})))

      field-def
      (if (or (= (:current-class env) (:declaring-class field-def))
              ;; A spawn/anonymous-function body with captures never actually
              ;; runs as this lowered bytecode — it is re-dispatched to the
              ;; tree-walking interpreter via make-captured-function-object
              ;; (nex.compiler.jvm.runtime), and this class's IR is discarded
              ;; before emission (see emitted-anonymous-classes). The
              ;; encapsulation check below exists to keep *real* compiled
              ;; classes honest; applying it to a body that will never run as
              ;; this bytecode only turns an ordinary captured-object field
              ;; write (`other.count := v`, `this.count := v` once `this` is
              ;; rewritten to a capture) into a hard compile failure.
              (:closure-runtime-object? (current-class-def env)))
        [env (ir/call-runtime-node (str "user-field-set:" field-name)
                                   [(lower-expression env target-expr) value-ir]
                                   "Void"
                                   :void)]
        (throw (ex-info (field-write-error-message field-name (:declaring-class field-def))
                        {:field field-name
                         :declaring-class (:declaring-class field-def)
                         :target target-expr})))

      (or (imported-java-qualified-name env owner) (:with-java? env))
      [env (ir/call-runtime-node "java-set-field"
                                 [(ir/const-node field-name
                                                 "String"
                                                 (ir/object-jvm-type "java/lang/String"))
                                  (lower-expression env target-expr)
                                  value-ir]
                                 "Void"
                                 :void)]

      :else
      (throw (ex-info "Unknown field in member assignment during lowering"
                      {:field field-name
                       :target target-expr
                       :target-type target-type})))))

(defn- lower-call-stmt [env stmt]
  ;; `own-class?` distinguishes the third case below (`this.ctor(...)`,
  ;; delegating to a sibling constructor of the *same* class) from the first
  ;; two (delegating to a parent's constructor, by name or via `super`): a
  ;; sibling constructor runs directly on `this` — no `_parent_X` field to
  ;; step through, and no generic-argument translation, since the callee is
  ;; the exact same (possibly generic) class as the caller.
  (if-let [{:keys [owner own-class?]}
           (cond
             (and (:this-type env)
                  (string? (:target stmt))
                  (some #(= (:target stmt) (:parent %))
                        (:parents (current-class-def env)))
                  (class-constructor-def (get (visible-class-map env) (:target stmt))
                                         (:method stmt)
                                         (count (:args stmt))))
             {:owner (:target stmt) :own-class? false}

             (and (:this-type env)
                  (map? (:target stmt))
                  (= :super (:type (:target stmt)))
                  (class-constructor-def (get (visible-class-map env) (single-super-parent-name env))
                                         (:method stmt)
                                         (count (:args stmt))))
             {:owner (single-super-parent-name env) :own-class? false}

             (and (:this-type env)
                  (map? (:target stmt))
                  (= :this (:type (:target stmt)))
                  (class-constructor-def (current-class-def env)
                                         (:method stmt)
                                         (count (:args stmt))))
             {:owner (:this-type env) :own-class? true}

             :else nil)]
    (let [ctor-def (class-constructor-def (get (visible-class-map env) owner)
                                          (:method stmt)
                                          (count (:args stmt)))
          owner-meta (class-jvm-meta env owner)
          runtime-args (if own-class?
                         (own-class-generic-runtime-args env (current-class-def env))
                         (parent-generic-runtime-args env (current-class-def env) owner))
          receiver-ir (if own-class?
                        (ir/this-node (:this-type env) (exact-class-jvm-type env (:this-type env)))
                        (ir/field-get-node (:internal-name (class-jvm-meta env (:this-type env)))
                                           (parent-field-name owner)
                                           (ir/this-node (:this-type env)
                                                         (exact-class-jvm-type env (:this-type env)))
                                           owner
                                           (exact-class-jvm-type env owner)))
          call-ir (ir/call-virtual-node (:internal-name owner-meta)
                                        (lowered-constructor-method-name ctor-def)
                                        (desc/repl-instance-method-descriptor)
                                        receiver-ir
                                        (into (mapv #(lower-expression env %) (:args stmt))
                                              runtime-args)
                                        owner
                                        (resolve-jvm-type env owner))]
      [env (ir/pop-node call-ir)])
    [env (ir/pop-node (lower-expression env stmt))]))

(defn- lower-loop-stmt [env stmt]
  (let [loop-env (scoped-child-env env)
        [env-after-init lowered-init] (lower-statements loop-env (:init stmt))
        invariant-start-stmts (mapv #(assertion-ir env-after-init :invariant %) (:invariant stmt))
        [env-with-variant variant-init-stmts variant-prefix]
        (if-let [variant-expr (:variant stmt)]
          (let [variant-type (infer-type env-after-init variant-expr)
                hidden-id (:next-slot env-after-init)
                prev-name (str "__loop_variant_prev_" hidden-id)
                curr-name (str "__loop_variant_curr_" hidden-id)
                seen-name (str "__loop_variant_seen_" hidden-id)
                [env1 prev-local] (env-add-local env-after-init prev-name variant-type)
                [env2 curr-local] (env-add-local env1 curr-name variant-type)
                [env3 seen-local] (env-add-local env2 seen-name "Boolean")
                curr-node (ir/local-node curr-name
                                         (:slot curr-local)
                                         (:nex-type curr-local)
                                         (:jvm-type curr-local))
                prev-node (ir/local-node prev-name
                                         (:slot prev-local)
                                         (:nex-type prev-local)
                                         (:jvm-type prev-local))
                seen-node (ir/local-node seen-name
                                         (:slot seen-local)
                                         (:nex-type seen-local)
                                         (:jvm-type seen-local))
                compare-node (ir/compare-node :lt curr-node prev-node "Boolean" :boolean)]
            [env3
             [(ir/set-local-node (:slot prev-local)
                                 (default-const-node (:nex-type prev-local) (:jvm-type prev-local))
                                 (:nex-type prev-local)
                                 (:jvm-type prev-local))
              (ir/set-local-node (:slot seen-local)
                                 (ir/const-node false "Boolean" :boolean)
                                 "Boolean"
                                 :boolean)]
             [(ir/set-local-node (:slot curr-local)
                                 (lower-expression env3 variant-expr)
                                 (:nex-type curr-local)
                                 (:jvm-type curr-local))
              (ir/if-stmt-node seen-node
                               [(ir/assert-node :variant "must decrease" compare-node)]
                               [])
              (ir/set-local-node (:slot prev-local)
                                 curr-node
                                 (:nex-type prev-local)
                                 (:jvm-type prev-local))
              (ir/set-local-node (:slot seen-local)
                                 (ir/const-node true "Boolean" :boolean)
                                 "Boolean"
                                 :boolean)]])
          [env-after-init [] []])
        test-ir (lower-expression env-with-variant (:until stmt))
        [env-after-body lowered-body] (lower-statements env-with-variant (:body stmt))
        invariant-end-stmts (mapv #(assertion-ir env-after-body :invariant %) (:invariant stmt))
        loop-body (vec (concat variant-prefix lowered-body invariant-end-stmts))]
    [(scoped-env env env-after-body)
     (ir/block-node
      (vec (concat lowered-init
                   invariant-start-stmts
                   variant-init-stmts
                   [(ir/loop-node [] test-ir loop-body)])))]))

(defn lower-statements
  [env statements]
  (reduce (fn [[env' out] stmt]
            (let [[next-env lowered] (lower-statement env' stmt)]
              [next-env (conj out lowered)]))
          [env []]
          statements))

(defn- add-generic-runtime-param-locals
  [env generic-params]
  (reduce (fn [[env acc] {:keys [name]}]
            (let [[env' local] (env-add-local env
                                              (generic-runtime-param-name name)
                                              "String")]
              [env' (conj acc (assoc local :arg-index (count acc)))]))
          [env []]
          generic-params))

(defn- generic-runtime-field-set-stmts
  [env class-name generic-params runtime-params]
  (mapv (fn [{:keys [name]} {:keys [slot]}]
          (ir/field-set-node (:internal-name (class-jvm-meta env class-name))
                             (generic-runtime-field-name name)
                             (ir/this-node class-name
                                           (exact-class-jvm-type env class-name))
                             (ir/local-node (generic-runtime-param-name name)
                                            slot
                                            "String"
                                            (string-jvm-type))
                             "String"
                             (string-jvm-type)))
        generic-params
        runtime-params))

(defn- lower-repl-tail
  [env stmt]
  (cond
    (= :if (:type stmt))
    [env [] (lower-expression env stmt)]

    (contains? expression-node-types (:type stmt))
    [env [] (lower-expression env stmt)]

    (= :call (:type stmt))
    [env [] (lower-expression env stmt)]

    (= :let (:type stmt))
    (let [[env' lowered] (lower-statement env stmt)
          nex-type (or (:var-type stmt) (infer-type env (:value stmt)))
          jvm-type (resolve-jvm-type env' nex-type)]
      [env' [lowered] (if (:top-level? env')
                        (ir/top-get-node (:name stmt) nex-type jvm-type)
                        (ir/local-node (:name stmt)
                                       (:slot (get (:locals env') (:name stmt)))
                                       nex-type
                                       jvm-type))])

    (= :assign (:type stmt))
    (let [[env' lowered] (lower-statement env stmt)
          nex-type (or (get (:var-types env') (:target stmt))
                       (infer-type env' {:type :identifier :name (:target stmt)}))
          jvm-type (resolve-jvm-type env' nex-type)]
      [env' [lowered] (if-let [{:keys [slot]} (get (:locals env') (:target stmt))]
                        (ir/local-node (:target stmt) slot nex-type jvm-type)
                        (ir/top-get-node (:target stmt) nex-type jvm-type))])

    (= :convert (:type stmt))
    (let [[env' _] (ensure-convert-binding env stmt)
          [env'' convert-ir] (lower-convert-expression env' stmt)]
      [env'' [] convert-ir])

    :else
    [env [] nil]))

(defn- repl-tail-returns-value?
  [env stmt]
  (cond
    (= :if (:type stmt))
    (let [then-env (refine-condition-branch-env
                    (if (= :convert (get-in stmt [:condition :type]))
                      (assoc-in env [:var-types (get-in stmt [:condition :var-name])]
                                (get-in stmt [:condition :target-type]))
                      env)
                    (:condition stmt)
                    :then)
          else-env (refine-condition-branch-env env (:condition stmt) :else)]
      (and (some? (if-branch-expression then-env (:then stmt)))
           (some? (elseif->else-expr else-env (:elseif stmt) (:else stmt)))))

    (contains? expression-node-types (:type stmt))
    true

    (= :call (:type stmt))
    true

    (= :let (:type stmt))
    true

    (= :assign (:type stmt))
    true

    (= :convert (:type stmt))
    true

    :else
    false))

(defn- body-assigns-result?
  "Whether any statement anywhere in `stmts` — however deeply nested inside
   if/match/case/loop/etc. bodies — is an explicit `result := ...` (or
   `let result := ...`) assignment. Used to suppress the implicit-tail-
   expression-as-result sugar in `lower-function`: once a function's body
   has already committed to an explicit assignment somewhere earlier, a
   later statement kept purely for its side effect (most commonly a bare
   call) must never be reinterpreted as the function's return value."
  [stmts]
  (letfn [(walk [node]
            (cond
              (and (map? node)
                   (or (and (= :assign (:type node)) (#{"result" "Result"} (:target node)))
                       (and (= :let (:type node)) (#{"result" "Result"} (:name node)))))
              true

              (map? node) (some walk (vals node))
              (sequential? node) (some walk node)
              :else false))]
    (boolean (walk stmts))))

(defn- result-local-init-stmt
  "Zero-value initializer for a routine's `result` local: the primitive default
   for a scalar return, an empty collection for a non-detachable Array/Map/Set
   return (so a body that only mutates `result` without an explicit
   `result := ...` still has a real object — matching get-default-field-value in
   the interpreter), and null otherwise (detachable collection returns
   included)."
  [result-local]
  (when result-local
    (let [jvm-type (:jvm-type result-local)
          nex-type (:nex-type result-local)
          base-type (when (map? nex-type) (:base-type nex-type))
          detachable? (and (map? nex-type) (:detachable nex-type))
          scalar-default (cond
                           (= :int jvm-type) 0
                           (= :long jvm-type) 0
                           (= :double jvm-type) 0.0
                           (= :boolean jvm-type) false
                           (= :char jvm-type) 0
                           :else nil)
          init-expr (cond
                      (some? scalar-default)
                      (ir/const-node scalar-default nex-type jvm-type)

                      (and (not detachable?) (= base-type "Array"))
                      (ir/array-literal-node [] nex-type jvm-type)

                      (and (not detachable?) (= base-type "Map"))
                      (ir/map-literal-node [] nex-type jvm-type)

                      (and (not detachable?) (= base-type "Set"))
                      (ir/set-literal-node [] nex-type jvm-type)

                      :else
                      (ir/const-node nil nex-type jvm-type))]
      (ir/set-local-node (:slot result-local) init-expr nex-type jvm-type))))

(defn- lower-function-env
  "The initial lowering env for a routine body: visible classes/functions/
   imports, the enclosing class's field and generic-parameter context, and the
   REPL-unit slot layout (`this` in slot 1, first free local at slot 3)."
  [visible-functions visible-imports fn-def]
  (let [visible-classes (vec (concat (:visible-classes fn-def)
                                     [(:class-def fn-def)]
                                     (keep :class-def visible-functions)))
        current-class (:class-name fn-def)
        generic-param-names (set (concat (map :name (:generic-params (:class-def fn-def)))
                                         (free-function-generic-param-names visible-classes fn-def)))
        generic-param-constraints (generic-param-constraint-map (:generic-params (:class-def fn-def)))]
    (make-lowering-env {:classes visible-classes
                        :functions visible-functions
                        :imports visible-imports
                        :var-types (field-type-map (:class-def fn-def))
                        :compiled-classes (:compiled-classes fn-def)
                        :current-class current-class
                        :generic-param-names generic-param-names
                        :generic-param-constraints generic-param-constraints
                        :generic-runtime-values (generic-runtime-field-bindings
                                                 {:compiled-classes (:compiled-classes fn-def)}
                                                 current-class
                                                 (:generic-params (:class-def fn-def)))
                        :fields (field-info-map {:compiled-classes (:compiled-classes fn-def)
                                                 :classes visible-classes
                                                 :imports visible-imports
                                                 :generic-param-names generic-param-names}
                                                (:class-def fn-def))
                        :this-type current-class
                        :top-level? false
                        :repl? true
                        :state-slot 1
                        :next-slot 3})))

(defn- lower-function-body-stmts
  "Lower a routine body, returning `[env lowered-stmts]`. When the routine
   returns a value and its final statement is an expression (or a `result :=`
   / call / convert), that tail is lowered as an implicit assignment to
   `result`; otherwise the body is lowered as-is and must assign `result`
   itself."
  [env body result-local fn-def]
  (if (:return-type fn-def)
    (if (empty? body)
      [env []]
      (let [leading-statements (butlast body)
            final-stmt (last body)]
        (if (and (not (body-assigns-result? leading-statements))
                 (or (and (= :assign (:type final-stmt))
                          (= "result" (:target final-stmt)))
                     (and (contains? expression-node-types (:type final-stmt))
                          (or (not= :if (:type final-stmt))
                              (implicit-if-expression? env final-stmt)))
                     (= :call (:type final-stmt))
                     (= :convert (:type final-stmt))))
          (let [[env' lowered-leading] (lower-statements env leading-statements)
                implicit-result-expr? (and (not (and (= :assign (:type final-stmt))
                                                     (= "result" (:target final-stmt))))
                                           (not= "Void" (infer-type env' final-stmt)))
                final-expr (when implicit-result-expr? final-stmt)
                [env'' lowered-tail]
                (if final-expr
                  [env' (with-stmt-debug
                          (ir/set-local-node (:slot result-local)
                                             (lower-expression env' final-expr)
                                             (:nex-type result-local)
                                             (:jvm-type result-local))
                          final-stmt)]
                  (lower-statement env' final-stmt))]
            [env'' (conj lowered-leading lowered-tail)])
          ;; Statement-shaped tails are valid as long as they assign to `result`
          ;; somewhere in the lowered body.
          (lower-statements env body))))
    (lower-statements env body)))

(defn lower-function
  [unit-name visible-functions visible-imports fn-def]
  (let [fn-def (normalized-function-def fn-def)
        return-type (function-return-type fn-def)
        current-class (:class-name fn-def)
        env0 (lower-function-env visible-functions visible-imports fn-def)
        [env-with-params params]
        (reduce (fn [[env acc] {:keys [name type]}]
                  (let [[env' local] (env-add-local env name type)]
                    [env' (conj acc (assoc local :arg-index (count acc)))]))
                [env0 []]
                (:params fn-def))
        [env-with-result result-local]
        (if (:return-type fn-def)
          (let [[env' local] (env-add-local env-with-params "result" return-type)]
            [(env-add-local-alias env' "Result" local) local])
          [env-with-params nil])
        result-init-stmt (result-local-init-stmt result-local)
        effective-require (or (:effective-require fn-def) (:require fn-def))
        effective-ensure (or (:effective-ensure fn-def) (:ensure fn-def))
        [env-with-old old-snapshot-stmts old-field-locals]
        (add-old-field-snapshots env-with-result effective-ensure)
        env-with-old (assoc env-with-old :old-field-locals old-field-locals)
        body (vec (:body fn-def))]
    (if (or (:declaration-only? fn-def)
            (:deferred? fn-def))
      (ir/fn-node {:name (:name fn-def)
                   :qualified-name (:qualified-name fn-def)
                   :owner unit-name
                   :emitted-name (if (:class-name fn-def)
                                   (lowered-top-level-function-emitted-name fn-def)
                                   (lowered-function-method-name fn-def))
                   :params params
                   :return-type return-type
                   :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                   :locals (vec (vals (:locals env-with-params)))
                   :body []
                   :deferred? true
                   :override? (boolean (:override? fn-def))})
      (let [body-stmts
            (lower-function-body-stmts env-with-old body result-local fn-def)
            [env-after-body raw-body-stmts] body-stmts
            [env-after-rescue lowered-body] (lower-body-with-rescue env-with-old env-after-body raw-body-stmts (:rescue fn-def))
            require-stmts (mapv #(assertion-ir env-with-old :require %) effective-require)
            ensure-env (assoc env-after-rescue :old-field-locals old-field-locals)
            ensure-stmts (mapv #(assertion-ir ensure-env :ensure %) effective-ensure)
            class-validation-stmts (if (and (:class-def fn-def)
                                            (get (:compiled-classes fn-def) current-class))
                                     [(ir/pop-node
                                       (validate-object-state-ir ensure-env
                                                                 current-class
                                                                 (ir/this-node current-class
                                                                               (exact-class-jvm-type ensure-env current-class))
                                                                 current-class))]
                                     [])
            return-stmt (if (:return-type fn-def)
                          (with-stmt-debug
                            (ir/return-node (ir/local-node "result"
                                                           (:slot result-local)
                                                           (:nex-type result-local)
                                                           (:jvm-type result-local))
                                            return-type
                                            (ir/object-jvm-type "java/lang/Object"))
                            (last body))
                          nil)]
        (ir/fn-node {:name (:name fn-def)
                     :qualified-name (:qualified-name fn-def)
                     :owner unit-name
                     :emitted-name (if (:class-name fn-def)
                                     (lowered-top-level-function-emitted-name fn-def)
                                     (lowered-function-method-name fn-def))
                     :params params
                     :return-type return-type
                     :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                     :locals (vec (vals (:locals env-after-rescue)))
                     :body (cond-> (into []
                                         (concat (when result-init-stmt [result-init-stmt])
                                                 old-snapshot-stmts
                                                 require-stmts
                                                 lowered-body
                                                 ensure-stmts
                                                 class-validation-stmts))
                             return-stmt
                             (conj return-stmt))
                     :deferred? (boolean (:deferred? fn-def))
                     :override? (boolean (:override? fn-def))})))))

(defn- super-ctor-arg-nex-type
  "The Nex type of a super.new(...)/<Super>.new(...) argument AST node when
   it is simple enough to evaluate twice safely — a literal, or a reference
   to one of CTOR-PARAMS — nil otherwise. A real argument must be safe to
   re-evaluate because the compiled backend needs the same value both inside
   the generated <init> (to forward it into the Java superclass constructor)
   and again, immediately after, for the ordinary Nex constructor method."
  [ctor-params arg]
  (case (:type arg)
    :string "String"
    :integer "Integer"
    :real "Real"
    :boolean "Boolean"
    :char "Char"
    :identifier (some #(when (= (:name %) (:name arg)) (:type %)) ctor-params)
    nil))

(defn- resolve-super-ctor-call
  "Validate and resolve a non-empty-argument super.new(args)/<Super>.new(args)
   call: every argument must be simple (super-ctor-arg-nex-type), and the
   overall arg list must resolve to exactly one of JAVA-SUPER-KLASS's public
   constructors (select-java-callable — arity, then family-based type
   matching to break a same-arity tie). Returns {:java-constructor ...}, or
   throws a clear, honest ex-info otherwise — never guesses."
  [^Class java-super-klass super-name class-name ctor-def args]
  (let [arg-nex-types (mapv (fn [arg]
                              (or (super-ctor-arg-nex-type (:params ctor-def) arg)
                                  (throw (unsupported
                                          (str class-name "." (:name ctor-def)
                                               ": super.new(...) (extending " super-name
                                               ") arguments must be one of the constructor's own "
                                               "parameters or a literal constant for now — assign "
                                               "a computed value to a `let` first")
                                          {:class-name class-name :constructor (:name ctor-def)}))))
                            args)
        ctor (select-java-callable (.getConstructors java-super-klass) (count args) arg-nex-types
                                   "constructor" super-name)]
    {:java-constructor ctor}))

(defn- ctor-forwards-java-super-args?
  "Whether CTOR-DEF opens with an explicit, real-argument super.new(args)/
   <Super>.new(args) call — the case needing a dedicated <init> overload
   (see java-super-ctor-forward-spec) rather than the shared zero-arg one."
  [super-name ctor-def]
  (let [first-stmt (first (:body ctor-def))]
    (and (java-super-constructor-call? super-name first-stmt)
         (seq (:args first-stmt)))))

(defn- java-super-ctor-forward-spec
  "For CTOR-DEF on CLASS-DEF — whose Java superclass parent (java-
   superclass-parent) is JAVA-SUPER-KLASS, named SUPER-NAME in `inherit` —
   resolve+lower a real-argument super.new(args)/SUPER-NAME.new(args) call
   into everything a dedicated <init> overload needs to forward those
   arguments into the real Java superclass constructor: nil when CTOR-DEF
   has no such call (implicit, zero-arg — the shared <init> already handles
   both). <init>'s own params are lowered in a fresh, minimal env, matching
   the JVM's own constraint that super()'s arguments may only reference the
   constructor's own parameters — nothing else exists on `this` yet."
  [visible-functions visible-imports visible-classes compiled-classes class-def
   ^Class java-super-klass super-name ctor-def]
  (when (ctor-forwards-java-super-args? super-name ctor-def)
    (let [{:keys [java-constructor]} (resolve-super-ctor-call java-super-klass super-name
                                                              (:name class-def) ctor-def
                                                              (:args (first (:body ctor-def))))
          env0 (make-lowering-env {:classes visible-classes
                                   :functions visible-functions
                                   :imports visible-imports
                                   :compiled-classes compiled-classes
                                   :generic-param-names (set (map :name (:generic-params class-def)))
                                   :generic-param-constraints (generic-param-constraint-map (:generic-params class-def))
                                   :this-type (:name class-def)
                                   :top-level? false
                                   :repl? true
                                   :state-slot 0
                                   :next-slot 1})
          [env-with-params _] (reduce (fn [[e acc] {:keys [name type]}]
                                        (let [[e' local] (env-add-local e name type)]
                                          [e' (conj acc local)]))
                                      [env0 []]
                                      (:params ctor-def))
          own-param-jvm-types (mapv #(resolve-jvm-type env0 (:type %)) (:params ctor-def))
          boxed-args (mapv (fn [arg ^Class pc]
                             (ir/java-arg-box-node (lower-expression env-with-params arg) pc))
                           (:args (first (:body ctor-def)))
                           (.getParameterTypes ^java.lang.reflect.Constructor java-constructor))]
      {:ctor-name (:name ctor-def)
       :arity (count (:params ctor-def))
       :own-descriptor (desc/method-descriptor own-param-jvm-types :void)
       :super-descriptor (Type/getConstructorDescriptor ^java.lang.reflect.Constructor java-constructor)
       :boxed-args boxed-args})))

(defn- strip-java-super-ctor-call
  "Drop a leading explicit Java-super constructor call from CTOR-DEF's body: the
   real forwarding lives in a dedicated <init> overload (java-super-ctor-forward-
   spec, emitted separately by lower-class-def), so in the ordinary ctor-method
   body a zero-arg call is just the implicit case and a real-argument call is
   already handled. Arguments are still validated eagerly here (throws on a
   non-simple argument or an unresolvable overload)."
  [visible-classes class-def ctor-def]
  (if-let [{:keys [nex-name klass]} (java-superclass-parent {:classes visible-classes} class-def)]
    (let [first-stmt (first (:body ctor-def))]
      (if (java-super-constructor-call? nex-name first-stmt)
        (do
          (when (seq (:args first-stmt))
            (resolve-super-ctor-call klass nex-name (:name class-def) ctor-def
                                     (:args first-stmt)))
          (update ctor-def :body rest))
        ctor-def))
    ctor-def))

(defn- shim-parent-super-call-ir
  "The virtual call an inherited-constructor shim makes to the real constructor
   on its composed parent, forwarding the shim's own params plus the parent's
   generic-runtime args."
  [env class-def ctor-def compiled-classes class-name shim-parent]
  (let [cc {:compiled-classes compiled-classes}
        parent-meta (class-jvm-meta cc shim-parent)
        target-ir (ir/field-get-node (:internal-name (class-jvm-meta cc class-name))
                                     (parent-field-name shim-parent)
                                     (ir/this-node class-name (exact-class-jvm-type cc class-name))
                                     shim-parent
                                     (exact-class-jvm-type cc shim-parent))]
    (ir/call-virtual-node (:internal-name parent-meta)
                          (lowered-constructor-method-name ctor-def)
                          (desc/repl-instance-method-descriptor)
                          target-ir
                          (into (mapv (fn [{:keys [name]}]
                                        (let [{:keys [slot nex-type jvm-type]} (get (:locals env) name)]
                                          (ir/local-node name slot nex-type jvm-type)))
                                      (:params ctor-def))
                                (parent-generic-runtime-args env class-def shim-parent))
                          shim-parent
                          (resolve-jvm-type cc shim-parent))))

(defn- constructor-fn-node
  "Assemble a lowered constructor method: runtime-type field sets, `old`
   snapshots and `require` checks, then CORE-BODY (the lowered ctor statements,
   or a shim's super-call), then `ensure` checks and the class-invariant
   validation `return`."
  [{:keys [unit-name class-name ctor-def params compiled-classes locals
           runtime-field-set-stmts old-snapshot-stmts require-env ensure-env core-body]}]
  (ir/fn-node {:name (:name ctor-def)
               :owner unit-name
               :emitted-name (lowered-constructor-method-name ctor-def)
               :params params
               :return-type class-name
               :return-jvm-type (ir/object-jvm-type "java/lang/Object")
               :locals locals
               :body (vec (concat runtime-field-set-stmts
                                  old-snapshot-stmts
                                  (map #(assertion-ir require-env :require %) (:require ctor-def))
                                  core-body
                                  (map #(assertion-ir ensure-env :ensure %) (:ensure ctor-def))
                                  [(ir/return-node
                                    (validate-object-state-ir {:compiled-classes compiled-classes}
                                                              class-name
                                                              (ir/this-node class-name
                                                                            (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                                                              class-name)
                                    class-name
                                    (ir/object-jvm-type "java/lang/Object"))]))}))

(defn- lower-constructor
  [unit-name visible-functions visible-imports visible-classes class-def ctor-def compiled-classes]
  (let [ctor-def (strip-java-super-ctor-call visible-classes class-def ctor-def)
        class-name (:name class-def)
        generic-param-names (set (map :name (:generic-params class-def)))
        generic-param-constraints (generic-param-constraint-map (:generic-params class-def))
        env0 (make-lowering-env {:classes visible-classes
                                 :functions visible-functions
                                 :imports visible-imports
                                 :var-types (field-type-map class-def)
                                 :compiled-classes compiled-classes
                                 :current-class class-name
                                 :generic-param-names generic-param-names
                                 :generic-param-constraints generic-param-constraints
                                 :generic-runtime-values (generic-runtime-field-bindings
                                                          {:compiled-classes compiled-classes}
                                                          class-name
                                                          (:generic-params class-def))
                                 :fields (field-info-map {:compiled-classes compiled-classes
                                                          :classes visible-classes
                                                          :imports visible-imports
                                                          :generic-param-names generic-param-names}
                                                         class-def)
                                 :this-type class-name
                                 :top-level? false
                                 :repl? true
                                 :state-slot 1
                                 :next-slot 3})
        [env-with-params params]
        (reduce (fn [[env acc] {:keys [name type]}]
                  (let [[env' local] (env-add-local env name type)]
                    [env' (conj acc (assoc local :arg-index (count acc)))]))
                [env0 []]
                (:params ctor-def))
        [env-with-runtime runtime-params]
        (add-generic-runtime-param-locals env-with-params (:generic-params class-def))
        params (vec (concat params
                            (map-indexed (fn [idx local]
                                           (assoc local :arg-index (+ (count params) idx)))
                                         runtime-params)))
        runtime-field-set-stmts (generic-runtime-field-set-stmts env-with-runtime
                                                                 class-name
                                                                 (:generic-params class-def)
                                                                 runtime-params)
        [env-with-old old-snapshot-stmts old-field-locals]
        (add-old-field-snapshots env-with-runtime (:ensure ctor-def))
        env-with-old (assoc env-with-old :old-field-locals old-field-locals)]
    (if-let [shim-parent (:shim-parent ctor-def)]
      (constructor-fn-node
       {:unit-name unit-name :class-name class-name :ctor-def ctor-def :params params
        :compiled-classes compiled-classes
        :locals (vec (vals (:locals env-with-old)))
        :runtime-field-set-stmts runtime-field-set-stmts
        :old-snapshot-stmts old-snapshot-stmts
        :require-env env-with-old :ensure-env env-with-old
        :core-body [(ir/pop-node (shim-parent-super-call-ir
                                  env-with-runtime class-def ctor-def compiled-classes
                                  class-name shim-parent))]})
      (let [[env-after-body raw-body] (lower-statements env-with-old (vec (:body ctor-def)))
            [env-after-rescue lowered-body] (lower-body-with-rescue env-with-old env-after-body raw-body (:rescue ctor-def))]
        (constructor-fn-node
         {:unit-name unit-name :class-name class-name :ctor-def ctor-def :params params
          :compiled-classes compiled-classes
          :locals (vec (vals (:locals env-after-rescue)))
          :runtime-field-set-stmts runtime-field-set-stmts
          :old-snapshot-stmts old-snapshot-stmts
          :require-env env-with-old
          :ensure-env (assoc env-after-rescue :old-field-locals old-field-locals)
          :core-body lowered-body})))))

(defn- lower-generic-init-method
  [unit-name visible-functions visible-imports visible-classes class-def compiled-classes]
  (let [class-name (:name class-def)
        generic-param-names (set (map :name (:generic-params class-def)))
        generic-param-constraints (generic-param-constraint-map (:generic-params class-def))
        env0 (make-lowering-env {:classes visible-classes
                                 :functions visible-functions
                                 :imports visible-imports
                                 :var-types (field-type-map class-def)
                                 :compiled-classes compiled-classes
                                 :current-class class-name
                                 :generic-param-names generic-param-names
                                 :generic-param-constraints generic-param-constraints
                                 :generic-runtime-values (generic-runtime-field-bindings
                                                          {:compiled-classes compiled-classes}
                                                          class-name
                                                          (:generic-params class-def))
                                 :fields (field-info-map {:compiled-classes compiled-classes
                                                          :classes visible-classes
                                                          :imports visible-imports
                                                          :generic-param-names generic-param-names}
                                                         class-def)
                                 :this-type class-name
                                 :top-level? false
                                 :repl? true
                                 :state-slot 1
                                 :next-slot 3})
        [env-with-runtime runtime-params]
        (add-generic-runtime-param-locals env0 (:generic-params class-def))
        params (vec (map-indexed (fn [idx local]
                                   (assoc local :arg-index idx))
                                 runtime-params))
        runtime-field-set-stmts (generic-runtime-field-set-stmts env-with-runtime
                                                                 class-name
                                                                 (:generic-params class-def)
                                                                 runtime-params)]
    (ir/fn-node {:name (generic-init-method-name)
                 :owner unit-name
                 :emitted-name (generic-init-method-name)
                 :params params
                 :return-type class-name
                 :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                 :locals (vec (vals (:locals env-with-runtime)))
                 :body (vec (concat runtime-field-set-stmts
                                    [(ir/return-node
                                      (validate-object-state-ir {:compiled-classes compiled-classes}
                                                                class-name
                                                                (ir/this-node class-name
                                                                              (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                                                                class-name)
                                      class-name
                                      (ir/object-jvm-type "java/lang/Object"))]))})))

(defn- make-delegation-method-node
  [env class-meta class-name compiled-classes
   {:keys [source-class carrier-owner carrier-field owner-internal-name method-def carrier-jvm-type]}]
  (let [;; method-def is declared by source-class, so its parameter/return types
        ;; may name the *parent's* generic params — resolve with both the
        ;; subclass's generics (env) and the declaring class's in scope, or a
        ;; bare `T` becomes a literal class name (CHECKCAST T → CNFE at run time).
        resolve-env {:compiled-classes compiled-classes
                     :generic-param-names (into (set (:generic-param-names env))
                                                (map :name
                                                     (:generic-params
                                                      (get (visible-class-map env) source-class))))}
        return-type (function-return-type method-def)
        params (map-indexed (fn [idx {:keys [name type]}]
                              {:name name
                               :slot (+ 2 idx)
                               :arg-index idx
                               :nex-type type
                               :jvm-type (resolve-jvm-type resolve-env type)})
                            (:params method-def))
        result-slot (+ 2 (reduce + (map (fn [{:keys [jvm-type]}]
                                          (if (#{:long :double} jvm-type) 2 1))
                                        params)))
        call-args (mapv (fn [{:keys [name slot nex-type jvm-type]}]
                          (ir/local-node name slot nex-type jvm-type))
                        params)
        target-ir (ir/field-get-node (:internal-name (class-jvm-meta env carrier-owner))
                                     carrier-field
                                     (ir/this-node class-name
                                                   (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                                     source-class
                                     carrier-jvm-type)
        call-ir (ir/call-virtual-node owner-internal-name
                                      (lowered-instance-method-name method-def)
                                      (desc/repl-instance-method-descriptor)
                                      target-ir
                                      call-args
                                      return-type
                                      (resolve-jvm-type resolve-env return-type))
        class-validation (ir/pop-node
                          (validate-object-state-ir {:compiled-classes compiled-classes}
                                                    class-name
                                                    (ir/this-node class-name
                                                                  (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                                                    class-name))]
    (ir/fn-node {:name (:name method-def)
                 :owner (:jvm-name class-meta)
                 :emitted-name (lowered-instance-method-name method-def)
                 :params params
                 :return-type return-type
                 :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                 :locals (vec params)
                 :body (if (:return-type method-def)
                         [(ir/set-local-node result-slot
                                             call-ir
                                             return-type
                                             (resolve-jvm-type resolve-env return-type))
                          class-validation
                          (ir/return-node (ir/local-node "__result"
                                                         result-slot
                                                         return-type
                                                         (resolve-jvm-type resolve-env return-type))
                                          return-type
                                          (ir/object-jvm-type "java/lang/Object"))]
                         [(ir/pop-node call-ir)
                          class-validation])
                 :override? false})))

(def ^:private invariant-method-def
  "The synthetic, no-argument method each class with invariants carries; its body
   validates the class's own invariant clauses (and chains to invariant-bearing
   parents). Compiled by the ordinary method pipeline, so it uses native field
   access, calls, and structural `=` — no interpreter round-trip.

   The name contains `$`, which the grammar forbids in identifiers
   (`[a-zA-Z_][a-zA-Z_0-9]*`), so it cannot collide with any user-declared
   feature — a user 0-arg feature named e.g. `__invariant` mangles to a
   different JVM method (`__method___invariant$arity0`) and is left an
   ordinary method (see runtime `has-invariant-method?`)."
  {:name "$invariant" :params []})

(defn- class-declares-invariant-in-hierarchy?
  "Whether `class-name` or any ancestor resolvable in `class-map` (name ->
   class-def) declares a class invariant — the set the runtime must validate."
  [class-map class-name]
  (letfn [(walk [name seen]
            (boolean
             (when-let [cd (and name (not (contains? seen name)) (get class-map name))]
               (or (seq (:invariant cd))
                   (some #(walk (:parent %) (conj seen name)) (:parents cd))))))]
    (walk class-name #{})))

(defn- lower-invariant-method
  "Synthesize the `__invariant` instance method for a class that has invariants
   (its own and/or inherited). Body: call each invariant-bearing parent's
   `__invariant` on its composition carrier (parent-first, mirroring the
   interpreter's collect-invariants), then assert this class's own clauses via
   the shared assertion lowering. Built as a bare fn-node (not through
   lower-function) so it is not itself wrapped with an invariant check — no
   self-recursion."
  [unit-name visible-functions visible-imports visible-classes class-def compiled-classes]
  (let [class-name (:name class-def)
        generic-param-names (set (map :name (:generic-params class-def)))
        env (make-lowering-env {:classes visible-classes
                                :functions visible-functions
                                :imports visible-imports
                                :var-types (field-type-map class-def)
                                :compiled-classes compiled-classes
                                :current-class class-name
                                :generic-param-names generic-param-names
                                :generic-param-constraints (generic-param-constraint-map (:generic-params class-def))
                                :generic-runtime-values (generic-runtime-field-bindings
                                                         {:compiled-classes compiled-classes}
                                                         class-name
                                                         (:generic-params class-def))
                                :fields (field-info-map {:compiled-classes compiled-classes
                                                         :classes visible-classes
                                                         :imports visible-imports
                                                         :generic-param-names generic-param-names}
                                                        class-def)
                                :this-type class-name
                                :top-level? false
                                :repl? true
                                :state-slot 1
                                :next-slot 3})
        class-internal (:internal-name (class-jvm-meta env class-name))
        this-jvm (exact-class-jvm-type {:compiled-classes compiled-classes} class-name)
        class-map (visible-class-map env)
        invariant-descriptor (desc/repl-instance-method-descriptor)
        invariant-emitted-name (lowered-instance-method-name invariant-method-def)
        parent-calls
        (->> (resolve-parent-metas env class-def)
             (filter (fn [{:keys [nex-name]}]
                       (class-declares-invariant-in-hierarchy? class-map nex-name)))
             (mapv (fn [{:keys [nex-name internal-name composition-field]}]
                     (let [carrier-jvm (exact-class-jvm-type {:compiled-classes compiled-classes} nex-name)]
                       (ir/pop-node
                        (ir/call-virtual-node internal-name
                                              invariant-emitted-name
                                              invariant-descriptor
                                              (ir/field-get-node class-internal
                                                                 composition-field
                                                                 (ir/this-node class-name this-jvm)
                                                                 nex-name
                                                                 carrier-jvm)
                                              []
                                              nex-name
                                              (ir/object-jvm-type "java/lang/Object")))))))
        own-asserts (mapv #(assertion-ir env :class-invariant %) (:invariant class-def))
        body (vec (concat parent-calls
                          own-asserts
                          [(ir/return-node (ir/this-node class-name this-jvm)
                                           class-name
                                           (ir/object-jvm-type "java/lang/Object"))]))]
    (ir/fn-node {:name (:name invariant-method-def)
                 :owner unit-name
                 :emitted-name invariant-emitted-name
                 :params []
                 :return-type class-name
                 :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                 :locals (vec (vals (:locals env)))
                 :body body})))

(def ^:private java-interop-object-method-arities
  "[name arity] of Java-interface members that redeclare one of Object's own
   methods (Comparator's equals(Object) is the standard example — see the
   typechecker's identically-named exclusion, object-instance-methods).
   These never need a bridge: every compiled class already emits a real
   equals/hashCode (see user-class-spec) and inherits a real toString from
   Object, either of which already satisfies the interface."
  #{["equals" 1] ["hashCode" 0] ["toString" 0]})

(defn- resolve-imported-java-type
  "The reflected Class for parent-name, when the `inherit` entry resolves to
   any imported Java type — an interface (Phase 1) or a concrete/abstract
   class (Phase 2, docs/proposals/java-interop.md). Nil for a Nex class or an
   unresolvable name."
  [env parent-name]
  (let [parent-def (get (visible-class-map env) parent-name)]
    (when (:import parent-def)
      (try
        (Class/forName (:import parent-def))
        (catch Exception _ nil)))))

(defn- resolve-imported-java-interface
  "resolve-imported-java-type, narrowed to an interface — nil for a
   concrete/abstract Java class."
  [env parent-name]
  (when-let [^Class klass (resolve-imported-java-type env parent-name)]
    (when (.isInterface klass) klass)))

(defn- java-interface-parents
  "Reflected Class objects for this class-def's Java-*interface* `inherit`
   entries. Nex parents, and a Java-*class* parent (java-superclass-parent),
   are excluded."
  [env class-def]
  (->> (:parents class-def)
       (keep (fn [{:keys [parent]}] (resolve-imported-java-interface env parent)))
       distinct))

(defn- java-superclass-parent
  "{:klass ... :nex-name ...} for the concrete Java class this class-def's
   `inherit` chain extends (Phase 2), walking through Nex ancestors — mirrors
   nex.typechecker/class-java-superclass-name. nex-name is the literal name
   written in `inherit` (the parent-qualified super-constructor-call form,
   <JavaClassName>.new(args), matches against it). At most one exists
   anywhere in the chain; check-inheritance enforces the JVM's
   single-inheritance rule. Nil when class-def has no Java-class parent."
  [env class-def]
  (letfn [(walk [cn visited]
            (when-let [cd (and (not (contains? visited cn)) (get (visible-class-map env) cn))]
              (let [visited' (conj visited cn)]
                (some (fn [{:keys [parent]}]
                        (let [parent-cd (get (visible-class-map env) parent)]
                          (if (and parent-cd (not (:import parent-cd)))
                            (walk parent visited')
                            (when-let [^Class klass (resolve-imported-java-type env parent)]
                              (when-not (.isInterface klass) {:klass klass :nex-name parent})))))
                      (:parents cd)))))]
    (walk (:name class-def) #{})))

(defn- bridge-jvm-kind
  "Collapse a resolved Nex jvm-type to the keyword emit-interface-bridge-
   method! needs: one of Nex's four primitive-shaped types, or :object for
   everything reference-shaped (Nex has no other primitives — nex-type->jvm-
   type never produces :int/:float/:byte/:short, only :long/:double for
   Integer/Real)."
  [jvm-type]
  (if (#{:long :double :boolean :char} jvm-type) jvm-type :object))

(defn- java-super-constructor-call?
  "True when STMT is `super.new(args)` or `<JavaSuperclassName>.new(args)` —
   the reserved selector (docs/proposals/java-interop.md) for forwarding to a
   Java superclass's real constructor. Mirrors
   nex.typechecker/java-super-constructor-call?, which already validated this
   shape and position by the time lowering runs."
  [super-nex-name stmt]
  (and (map? stmt)
       (= :call (:type stmt))
       (= "new" (:method stmt))
       (or (and (map? (:target stmt)) (= :super (:type (:target stmt))))
           (= super-nex-name (:target stmt)))))

(defn- java-interface-bridge-methods
  "One entry per abstract method (across all implemented interfaces) that a
   compiled class needs a real-Java-descriptor bridge for, so the JVM's own
   dispatch (a Java caller invoking the interface method) reaches the already-
   emitted internal Nex method. Deduped by (name, descriptor): two interfaces
   sharing a method signature (rare, but legal) need only one bridge. See
   emit-interface-bridge-method! in emit.clj for the codegen this feeds."
  [env class-def java-interfaces]
  (->> java-interfaces
       (mapcat (fn [^Class klass]
                 (->> (.getMethods klass)
                      (remove (fn [^java.lang.reflect.Method m]
                                (or (.isDefault m)
                                    (java.lang.reflect.Modifier/isStatic (.getModifiers m))
                                    (contains? java-interop-object-method-arities
                                               [(.getName m) (alength (.getParameterTypes m))])))))))
       (map (fn [^java.lang.reflect.Method m] [[(.getName m) (Type/getMethodDescriptor m)] m]))
       (into {})
       vals
       (mapv (fn [^java.lang.reflect.Method m]
               (let [arity (alength (.getParameterTypes m))
                     method-def (accessible-method-def env class-def (.getName m) arity)]
                 (when-not method-def
                   (throw (ex-info (str "Class " (:name class-def)
                                        " has no method matching Java interface member "
                                        (.getName m) "/" arity " during lowering")
                                   {:class-name (:name class-def)
                                    :java-method (.getName m)
                                    :arity arity})))
                 {:java-name (.getName m)
                  :descriptor (Type/getMethodDescriptor m)
                  :target-method-name (lowered-instance-method-name method-def)
                  :param-classes (vec (.getParameterTypes m))
                  :return-class (.getReturnType m)
                  ;; The Nex method's own declared param/return types, in the
                  ;; representation its already-emitted body actually uses
                  ;; (Nex Integer is a boxed Long regardless of what the Java
                  ;; interface's primitive return/param type is) — the bridge
                  ;; must box/unbox against *this*, not against whatever
                  ;; wrapper the Java descriptor's own primitive would
                  ;; naturally suggest, then separately widen/narrow to the
                  ;; Java primitive if the two differ (e.g. Integer's long
                  ;; vs. Comparator.compare's int).
                  :param-nex-kinds (mapv (fn [{:keys [type]}]
                                           (bridge-jvm-kind (resolve-jvm-type env type)))
                                         (:params method-def))
                  :return-nex-kind (bridge-jvm-kind
                                    (resolve-jvm-type env (function-return-type method-def)))})))))

(defn- java-superclass-override-bridge-methods
  "One bridge entry per (name, arity) the Nex class's own declared methods
   share with an inherited Java superclass method — the real vtable slot a
   Java caller dispatches through polymorphically (Thread.start() calling
   this.run(), say; docs/proposals/java-interop.md Phase 2). Unlike
   java-interface-bridge-methods (bridges every abstract interface method),
   this only bridges methods the class actually redefines: the superclass's
   other, untouched methods already work correctly through ordinary JVM
   inheritance and need no bridge. Excludes static and final superclass
   methods (a final method can't be overridden; a matching Nex method name
   is coincidental, not an override)."
  [env class-def super-klass]
  (if-not super-klass
    []
    (->> (class-methods class-def)
         (mapcat (fn [method-def]
                   (let [arity (count (or (:params method-def) []))]
                     (->> (.getMethods ^Class super-klass)
                          (filter (fn [^java.lang.reflect.Method m]
                                    (let [mods (.getModifiers m)]
                                      (and (= (.getName m) (:name method-def))
                                           (= (alength (.getParameterTypes m)) arity)
                                           (not (java.lang.reflect.Modifier/isStatic mods))
                                           (not (java.lang.reflect.Modifier/isFinal mods))))))
                          (map (fn [^java.lang.reflect.Method m]
                                 {:key [(.getName m) (Type/getMethodDescriptor m)]
                                  :java-name (.getName m)
                                  :descriptor (Type/getMethodDescriptor m)
                                  :target-method-name (lowered-instance-method-name method-def)
                                  :param-classes (vec (.getParameterTypes m))
                                  :return-class (.getReturnType m)
                                  :param-nex-kinds (mapv (fn [{:keys [type]}]
                                                           (bridge-jvm-kind (resolve-jvm-type env type)))
                                                         (:params method-def))
                                  :return-nex-kind (bridge-jvm-kind
                                                    (resolve-jvm-type env (function-return-type method-def)))}))))))
         (map (fn [b] [(:key b) b]))
         (into {})
         vals
         (mapv #(dissoc % :key)))))

(defn- assert-distinct-lowered-methods!
  "Nex mangles routine and constructor names by name+arity, so two of them that
   share both would emit duplicate JVM methods and fail at `defineClass`. Reject
   the collision here with a clear message; this guards every compilation path,
   including the REPL and `compile jvm`, where the typechecker may be off."
  [class-name class-def]
  (doseq [[kind members] [["routine" (class-methods class-def)]
                          ["constructor" (class-constructors class-def)]]]
    (let [sigs (map (fn [m] [(:name m) (count (or (:params m) []))]) members)]
      (when-let [dup (->> (frequencies sigs)
                          (filter (fn [[_ n]] (> n 1)))
                          ffirst)]
        (let [[dup-name dup-arity] dup]
          (throw (ex-info
                  (str "Duplicate " kind " '" dup-name "' taking " dup-arity
                       (if (= 1 dup-arity) " argument" " arguments")
                       " in class '" class-name "'. Nex dispatches by name and "
                       "argument count, so two " kind "s cannot share both a name "
                       "and an arity; give them different arities or names.")
                  {:nex-error :duplicate-method
                   :class class-name :name dup-name :arity dup-arity})))))))

(defn- lower-class-instance-fields
  "The lowered non-constant instance fields of CLASS-DEF. Resolves each JVM type
   through ENV (not an ad-hoc map) so an imported-Java-typed field qualifies to
   its real internal name — without :imports, resolve-jvm-type would emit a
   descriptor the JVM can never link (NoClassDefFoundError at link time)."
  [env class-def]
  (mapv (fn [field]
          {:name (:name field)
           :nex-type (:field-type field)
           :jvm-type (resolve-jvm-type env (:field-type field))})
        (remove :constant? (class-fields class-def))))

(defn- lower-class-constants
  "The lowered constant fields of CLASS-DEF, each with its value expression
   lowered in a fresh class-scoped env (which carries :imports, so an
   imported-Java-typed constant resolves the same way instance fields do)."
  [class-def class-name visible-functions visible-imports compiled-classes visible-classes]
  (mapv (fn [field]
          (let [constant-env (make-lowering-env {:classes visible-classes
                                                 :functions visible-functions
                                                 :imports visible-imports
                                                 :compiled-classes compiled-classes
                                                 :generic-param-names (set (map :name (:generic-params class-def)))
                                                 :generic-param-constraints (generic-param-constraint-map (:generic-params class-def))
                                                 :current-class class-name
                                                 :this-type class-name
                                                 :top-level? false
                                                 :repl? true})
                nex-type (or (:field-type field)
                             (infer-type constant-env (:value field)))]
            {:name (:name field)
             :nex-type nex-type
             :jvm-type (resolve-jvm-type constant-env nex-type)
             :value (lower-expression constant-env (:value field))}))
        (filter :constant? (class-fields class-def))))

(defn- inherited-constructor-shims
  "Constructors a class inherits verbatim from its composed parents: for each
   parent constructor whose name the class does not itself declare, a copy
   tagged with `:shim-parent` so lower-constructor forwards it to that parent."
  [class-def visible-classes own-ctor-names]
  (->> (:parents class-def)
       (remove #(= "Any" (:parent %)))
       (mapcat (fn [{:keys [parent]}]
                 (let [parent-def (get (visible-class-map {:classes visible-classes}) parent)]
                   (for [ctor-def (class-constructors parent-def)
                         :when (not (contains? own-ctor-names (:name ctor-def)))]
                     (assoc ctor-def :shim-parent parent)))))
       vec))

(defn- lower-own-methods
  [env class-def class-name class-meta visible-functions visible-imports visible-classes compiled-classes]
  (mapv (fn [method-def]
          (lower-function (:jvm-name class-meta)
                          visible-functions
                          visible-imports
                          (merge method-def
                                 {:class-name class-name
                                  :class-def class-def
                                  :visible-classes visible-classes
                                  :deferred? (lowered-deferred-method? class-def method-def)
                                  :override? (method-override? env class-def method-def)
                                  :compiled-classes compiled-classes}
                                 (effective-method-contracts env class-def method-def))))
        (class-methods class-def)))

(defn- lower-delegation-methods
  "Forwarding stubs for parent methods a composed class exposes but does not
   itself override."
  [env class-def class-name class-meta compiled-classes own-method-names]
  (->> (direct-parent-method-map env class-def)
       vals
       (remove (fn [{:keys [method-def]}]
                 (contains? own-method-names
                            [(:name method-def) (count (or (:params method-def) []))])))
       (mapv #(make-delegation-method-node env class-meta class-name compiled-classes %))))

(defn lower-class-def
  [class-def opts]
  (assert-distinct-lowered-methods! (:name class-def) class-def)
  (let [compiled-classes (:compiled-classes opts)
        class-name (class-self-registration-name compiled-classes class-def)
        ;; Every helper this function calls below (lower-constructor,
        ;; field-info-map, direct-parent-method-map, lower-generic-init-method,
        ;; lower-invariant-method, lower-function via own-methods, ...) takes
        ;; class-def directly and independently re-derives "this class's own
        ;; identity" from its :name — there is no one choke point to fix
        ;; instead, unlike env-lookup-class/visible-class-map. Rebinding :name
        ;; to the same qualified class-name here, once, propagates the fix to
        ;; all of them at the source, the same way check-program's
        ;; qualified-class-defs does for the typechecker. Confined to this
        ;; function's own local `class-def` — never written back to the
        ;; shared class list `env`'s :classes was built from — so it cannot
        ;; recreate the bulk-enumeration-by-:name hazard that bit
        ;; find-sealed-subclasses in the typechecker (see ambiguous-class-names).
        class-def (assoc class-def :name class-name)
        class-meta (class-jvm-meta {:compiled-classes compiled-classes} class-name)
        env (make-lowering-env {:classes (:classes opts)
                                :functions (:functions opts)
                                :imports (:imports opts)
                                :compiled-classes compiled-classes
                                :generic-param-names (set (map :name (:generic-params class-def)))
                                :generic-param-constraints (generic-param-constraint-map (:generic-params class-def))})
        visible-functions (vec (:functions opts))
        visible-imports (vec (:imports opts))
        own-ctor-names (set (map :name (class-constructors class-def)))
        inherited-shims (inherited-constructor-shims class-def (:classes opts) own-ctor-names)
        constructors (->> (concat (class-constructors class-def) inherited-shims)
                          (mapv (fn [ctor-def]
                                  (lower-constructor (:jvm-name class-meta)
                                                     visible-functions
                                                     visible-imports
                                                     (:classes opts)
                                                     class-def
                                                     ctor-def
                                                     compiled-classes))))
        ;; A dedicated <init> overload per own (not inherited-shim — see
        ;; java-super-ctor-forward-spec) constructor that forwards real
        ;; arguments into a Java superclass constructor; empty when this
        ;; class has no Java superclass parent or none of its constructors
        ;; do that.
        java-super-ctor-forwards (when-let [{:keys [klass nex-name]} (java-superclass-parent env class-def)]
                                   (vec (keep #(java-super-ctor-forward-spec
                                                visible-functions visible-imports (:classes opts)
                                                compiled-classes class-def klass nex-name %)
                                              (class-constructors class-def))))
        constructors (cond-> constructors
                       (seq (:generic-params class-def))
                       (conj (lower-generic-init-method (:jvm-name class-meta)
                                                        visible-functions
                                                        visible-imports
                                                        (:classes opts)
                                                        class-def
                                                        compiled-classes)))
        own-methods (lower-own-methods env class-def class-name class-meta
                                       visible-functions visible-imports (:classes opts) compiled-classes)
        own-method-names (set (map (fn [m] [(:name m) (count (:params m))]) (class-methods class-def)))
        delegation-methods (lower-delegation-methods env class-def class-name class-meta
                                                     compiled-classes own-method-names)
        invariant-methods (when (class-declares-invariant-in-hierarchy? (visible-class-map env) class-name)
                            [(lower-invariant-method (:jvm-name class-meta)
                                                     visible-functions
                                                     visible-imports
                                                     (:classes opts)
                                                     class-def
                                                     compiled-classes)])
        methods (vec (concat own-methods delegation-methods invariant-methods))
        fields (lower-class-instance-fields env class-def)
        runtime-type-fields (mapv (fn [{:keys [name]}]
                                    {:name (generic-runtime-field-name name)
                                     :nex-type "String"
                                     :jvm-type (string-jvm-type)})
                                  (:generic-params class-def))
        constants (lower-class-constants class-def class-name visible-functions
                                         visible-imports compiled-classes (:classes opts))]
    {:name class-name
     :jvm-name (:jvm-name class-meta)
     :internal-name (:internal-name class-meta)
     :source-file (:source-file opts)
     :deferred? (boolean (:deferred? class-def))
     :parents (resolve-parent-metas env class-def)
     :composition-fields (mapv (fn [{:keys [nex-name internal-name composition-field deferred?]}]
                                 {:name composition-field
                                  :parent nex-name
                                  :deferred? deferred?
                                  :jvm-type (exact-class-jvm-type {:compiled-classes compiled-classes} nex-name)})
                               (resolve-parent-metas env class-def))
     :interfaces (mapv (fn [^Class klass] (desc/internal-class-name (.getName klass)))
                       (java-interface-parents env class-def))
     :java-super-class (when-let [{:keys [^Class klass]} (java-superclass-parent env class-def)]
                         (desc/internal-class-name (.getName klass)))
     :java-bridge-methods (into (java-interface-bridge-methods env class-def
                                                               (java-interface-parents env class-def))
                                (java-superclass-override-bridge-methods
                                 env class-def
                                 (:klass (java-superclass-parent env class-def))))
     :fields fields
     :runtime-type-fields runtime-type-fields
     :constants constants
     :constructors constructors
     :java-super-ctor-forwards java-super-ctor-forwards
     :methods methods}))

(defn- compute-top-level-globals
  "Infer the nex-type of each direct top-level `let` (a program global), in source
   order, so the static world can read them (§7). Seeds with `seed-var-types`
   (prior top-level state, e.g. earlier REPL cells) so cross-cell globals resolve
   too. Only *direct* top-level lets are globals, matching the typechecker."
  [base-env seed-var-types statements]
  (loop [ss statements
         vt (or seed-var-types {})
         acc (or seed-var-types {})]
    (if (empty? ss)
      acc
      (let [stmt (first ss)]
        (if (and (map? stmt) (= :let (:type stmt)))
          (let [e (assoc base-env :var-types vt :top-level? true)
                ty (or (:var-type stmt)
                       (try (infer-type e (:value stmt)) (catch Exception _ nil))
                       "Any")]
            (recur (rest ss) (assoc vt (:name stmt) ty) (assoc acc (:name stmt) ty)))
          (recur (rest ss) vt acc))))))

(defn- repl-cell-body-with-return
  "Append the REPL cell's trailing `return` to its lowered body: the tail
   expression's value when it has one (popping first, and returning nil, when
   that value is Void), else a bare `return nil`."
  [lowered-body final-expr-ir tail-stmt]
  (let [nil-return (ir/return-node (ir/const-node nil "Any"
                                                  (ir/object-jvm-type "java/lang/Object"))
                                   "Any"
                                   (ir/object-jvm-type "java/lang/Object"))]
    (cond
      (and final-expr-ir (= "Void" (:nex-type final-expr-ir)))
      (conj lowered-body
            (with-stmt-debug (ir/pop-node final-expr-ir) tail-stmt)
            (with-stmt-debug nil-return tail-stmt))

      final-expr-ir
      (conj lowered-body
            (with-stmt-debug
              (ir/return-node final-expr-ir
                              (:nex-type final-expr-ir)
                              (ir/object-jvm-type "java/lang/Object"))
              tail-stmt))

      :else
      (conj lowered-body nil-return))))

(defn lower-repl-cell
  "Lower a narrow REPL/program body to a first compiler unit."
  [program opts]
  (binding [*type-aliases* (merge *type-aliases*
                                  (into {} (map (juxt :name :type-expr)
                                                (:type-aliases program))))
            *skip-contracts?* (boolean (:skip-contracts? opts))]
    (let [unit-name (or (:name opts) "nex/repl/Cell_0001")
          actual-classes (vec (user-class-defs program))
          anonymous-classes (vec (collect-anonymous-class-defs program))
          emitted-anonymous-classes (vec (remove :closure-runtime-object? anonymous-classes))
          visible-imports (vec (or (:imports opts) (:imports program)))
          imported-classes (->> visible-imports
                                (keep (fn [{:keys [qualified-name source]}]
                                        (when (and (nil? source) qualified-name)
                                          {:name (last (str/split qualified-name #"\."))
                                           :body []
                                           :import qualified-name})))
                                vec)
          visible-functions (vec (concat (:functions program) (:functions opts)))
          visible-classes (merge-visible-classes (builtin-class-defs)
                                                 imported-classes
                                                 actual-classes
                                                 anonymous-classes
                                                 (:classes opts)
                                                 (keep :class-def visible-functions))
          env (make-lowering-env {:classes visible-classes
                                  :functions visible-functions
                                  :imports visible-imports
                                  :compiled-classes (:compiled-classes opts)
                                  :var-types (:var-types opts)
                                  :top-level? true
                                  :repl? true
                                  :state-slot 0
                                  :next-slot 1})
          statements (vec (:statements program))
          globals-map (compute-top-level-globals env (:var-types opts) statements)
          tail-stmt (last statements)
          return-tail? (repl-tail-returns-value? env tail-stmt)
          leading-statements (if return-tail? (pop statements) statements)
          [env' lowered-body] (lower-statements env leading-statements)
          [env'' tail-stmts final-expr-ir] (if return-tail?
                                             (lower-repl-tail env' tail-stmt)
                                             [env' [] nil])
          lowered-body' (if return-tail?
                          (into lowered-body tail-stmts)
                          lowered-body)
          lowered-body'' (repl-cell-body-with-return lowered-body' final-expr-ir tail-stmt)]
      {:env env''
       :unit (binding [*top-level-globals* globals-map]
               (ir/unit {:name (or (:name opts) "nex/repl/Cell_0001")
                         :kind :repl-cell
                         :source-file (:source-file opts)
                         :locals (vec (vals (:locals env'')))
                         :classes (mapv #(lower-class-def % {:compiled-classes (:compiled-classes opts)
                                                             :classes visible-classes
                                                             :functions visible-functions
                                                             :imports visible-imports
                                                             :source-file (:source-file opts)})
                                        (concat actual-classes emitted-anonymous-classes))
                         :functions (mapv #(lower-function unit-name
                                                           visible-functions
                                                           visible-imports
                                                           (assoc %
                                                                  :visible-classes visible-classes
                                                                  :compiled-classes (:compiled-classes opts)))
                                          (remove :declaration-only? (:functions program)))
                         :body lowered-body''
                         :result-jvm-type (ir/object-jvm-type "java/lang/Object")}))})))
