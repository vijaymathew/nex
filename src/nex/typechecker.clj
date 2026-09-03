(ns nex.typechecker
  "Static type checker for Nex language"
  (:require [clojure.string :as str]
            [clojure.set :as set]
            [nex.types.builtins :as bi]))

;;
;; Type Environment
;;

(declare env-lookup-type-alias)
(declare type-error)

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
    ;; Bare class names that resolve to more than one distinct interned
    ;; class ({name -> #{qualified-name ...}}), populated once by
    ;; check-program from ambiguous-class-names before any lookups happen.
    ;; Lives on the root env; children reach it via env-root, the same way
    ;; :globals/:warnings do. See env-lookup-class and
    ;; docs/proposals/namespaces.md (Phase 2).
    :ambiguous-classes (atom {})
    ;; Same idea as :ambiguous-classes, for free functions: bare names that
    ;; resolve to more than one distinct interned function
    ;; ({name -> #{qualified-name ...}}), populated once by check-program
    ;; from ambiguous-function-names. See check-call's bare-call branch,
    ;; which is where a reference to one of these names is actually
    ;; rejected (mirroring env-lookup-class's ambiguous-classes check) —
    ;; there is no single env-lookup-function to hook the way classes have
    ;; env-lookup-class, since a bare call resolves through the generic
    ;; env-lookup-var (a free function is registered there as a variable
    ;; naming its synthesized call-dispatch class).
    :ambiguous-functions (atom {})
    :type-aliases (atom {})
    :non-nil-vars (atom #{})
    :across-cursors (atom {})
    ;; Names this env's block has declared with `let` (per-env, not inherited), so
    ;; a second `let` of the same name in the *same* block is rejected while a
    ;; nested block may still shadow.
    :let-names (atom #{})
    ;; Non-fatal diagnostics surfaced to the user (e.g. equals/hash mismatch).
    ;; Lives on the root env; children share it via env-add-warning.
    :warnings (atom [])
    ;; Top-level `let` globals, readable (not assignable) from the static world
    ;; (function and class bodies). Populated by a pre-pass before body checking;
    ;; consulted via the root env by env-lookup-global. See §7 of the spec.
    :globals (atom {})}))

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

(defn- env-root
  [env]
  (loop [e env] (if (:parent e) (recur (:parent e)) e)))

(defn env-lookup-global
  "Look up a top-level global's type. Globals live on the root env and are
   readable from anywhere in the static world (function and class bodies)."
  [env name]
  (get @(:globals (env-root env)) name))

(defn env-add-global
  "Register a top-level global's type on the root env."
  [env name type]
  (swap! (:globals (env-root env)) assoc name type))

(defn env-set!
  "Update a variable type in the nearest environment where it is defined."
  [env name type]
  (if (contains? @(:vars env) name)
    (swap! (:vars env) assoc name type)
    (when-let [parent (:parent env)]
      (env-set! parent name type))))

(defn env-lookup-method
  "Look up a method signature in the environment. Falls back to resolving
   class-name as a type alias (see env-lookup-class) when no methods are
   registered under the literal name."
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
    (if (:parent env)
      (env-lookup-method (:parent env) class-name method-name arity)
      (when-let [aliased (env-lookup-type-alias env class-name)]
        (when (and (string? aliased) (not= aliased class-name))
          (env-lookup-method env aliased method-name arity)))))))

(defn env-add-method
  "Add a method signature to the environment"
  [env class-name method-name signature]
  (let [arity (count (or (:params signature) []))]
    (swap! (:methods env) update class-name
           (fn [class-methods]
             (assoc (or class-methods {})
                    method-name
                    (assoc (or (get class-methods method-name) {}) arity signature))))))

(defn- ambiguous-class-reference-error
  "Build the ex-info thrown when class-name resolves to more than one
   distinct interned class. qualified-names is the #{...} recorded for it in
   :ambiguous-classes (see ambiguous-class-names).

   Two escape hatches, both real fixes (docs/proposals/namespaces.md, Phase
   3): reference either one directly by its qualified name
   (`finance/Account`), or rename one on the way in — `intern billing/Account
   as Billing_Account` — PROVIDED the intern is path-qualified. A *bare*
   alias resolves no better than the bare name itself did (nothing to
   qualify), and — subtler — an aliased intern's :type-expr points at the
   qualified identity specifically (`nex.interpreter/resolve-interned*`), not
   the bare one: earlier, before that fix, the alias fell through
   env-lookup-class's alias fallback to the SAME (still-ambiguous) bare name
   a direct reference would hit, leaving `intern X as Y` unable to resolve a
   real collision at all despite looking like exactly the tool for the job."
  [class-name qualified-names]
  (let [sorted (sort qualified-names)
        msg (str "Ambiguous reference to '" class-name "': interned from "
                 (str/join " and " sorted) ". Reference one directly by its "
                 "qualified name (e.g. " (first sorted) "), or rename it on "
                 "the way in with a path-qualified `intern ... as`.")]
    (ex-info msg {:error (type-error msg)})))

(defn- ambiguous-function-reference-error
  "Build the ex-info thrown when a bare call resolves to more than one
   distinct interned function. qualified-names is the #{...} recorded for
   it in :ambiguous-functions (see ambiguous-function-names).

   Unlike a class, a function has no `intern ... as` escape hatch — the
   alias mechanism only ever matches against a file's :classes (see
   nex.interpreter/resolve-interned* and process-intern) — so the only
   route out is the qualified call itself
   (nex.walker/resolve-qualified-function-calls): `trade.ship(x)`."
  [fn-name qualified-names]
  (let [sorted (sort qualified-names)
        msg (str "Ambiguous reference to '" fn-name "': interned from "
                 (str/join " and " sorted) ". Call one directly by its "
                 "qualified name, e.g. " (first sorted) "(...).")]
    (ex-info msg {:error (type-error msg)})))

(defn env-lookup-class
  "Look up a class definition in the environment. Falls back to resolving
   class-name as a type alias (e.g. from `intern ... as`, which registers only
   an alias to the real class rather than a nominally distinct duplicate of
   it) when no class is registered under the literal name. Throws when
   class-name is registered in :ambiguous-classes on the root env — two or
   more interned classes share this bare name and neither the entry program
   nor an earlier REPL cell has a same-named class of its own to take
   precedence (see docs/proposals/namespaces.md, Phase 2)."
  [env class-name]
  (if-let [class-def (get @(:classes env) class-name)]
    (if-let [qualified-names (get @(:ambiguous-classes (env-root env)) class-name)]
      (throw (ambiguous-class-reference-error class-name qualified-names))
      class-def)
    (if (:parent env)
      (env-lookup-class (:parent env) class-name)
      (when-let [aliased (env-lookup-type-alias env class-name)]
        (when (and (string? aliased) (not= aliased class-name))
          (env-lookup-class env aliased))))))

(defn env-add-class
  "Add a class definition to the environment"
  [env class-name class-def]
  (swap! (:classes env) assoc class-name class-def))

(defn- env-raw-class
  "Fetch whatever is registered under class-name in this exact env's own
   :classes map — no parent-chain walk, no type-alias fallback, and
   critically no :ambiguous-classes check. For a caller re-reading a
   class-def it registered itself moments earlier under this same key (e.g.
   check-class re-fetching the enriched def collect-class-info just stored):
   that isn't a bare name someone is asking to *resolve*, so env-lookup-class's
   ambiguity check is a false trip there — this class-def is not in question,
   only its latest stored shape is being read back."
  [env class-name]
  (get @(:classes env) class-name))

(defn env-add-type-alias
  [env name type-expr]
  (swap! (:type-aliases env) assoc name type-expr))

(defn env-lookup-type-alias
  [env name]
  ;; Tolerate envs without a :type-aliases atom: the lowering layer calls
  ;; types-compatible?/types-equal? with its own env map, which carries class
  ;; and generic info but no alias registry.
  (if-let [t (when-let [aliases (:type-aliases env)]
               (get @aliases name))]
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
  "Deduplicate class defs by name (a later definition wins) while preserving the
   original ordering. Ordering matters: the first collection pass type-checks
   constant initializers eagerly and relies on a class's dependencies appearing
   before it — e.g. an `enum union` emits its variant classes before the parent
   whose constants read them (`P.Red = create Red.make()`). Reducing into a hash
   map and taking `vals` reordered classes into hash-bucket order, which broke
   that invariant for some class-name sets (the parent could land before its
   variants). Keep each name at its first appearance, but use the last-defined
   value for that name."
  [class-defs]
  (let [last-def (reduce (fn [m class-def]
                           (assoc m (:name class-def) class-def))
                         {} class-defs)]
    (->> class-defs
         (reduce (fn [[seen out] class-def]
                   (let [nm (:name class-def)]
                     (if (contains? seen nm)
                       [seen out]
                       [(conj seen nm) (conj out (last-def nm))])))
                 [#{} []])
         second)))

(defn- function-class-defs
  [functions]
  (keep :class-def functions))

(defn- ambiguous-class-names
  "Bare names that resolve to more than one distinct interned class, from the
   same pre-dedup list class-defs-by-name-last-wins collapses. A class-def
   carries :qualified-name (stamped by nex.interpreter/resolve-interned*)
   exactly when it came in through `intern`; one declared directly in this
   program, or already established by an earlier REPL cell, has no
   :qualified-name and always wins outright — so a bare name only counts as
   ambiguous when EVERY def sharing it is interned and at least two of them
   have different qualified names. Two `intern` paths that happen to resolve
   to the same file (a diamond dependency) never reach here as duplicates in
   the first place: resolve-interned* already dedupes those by canonical file
   path before returning. Returns {bare-name -> #{qualified-name ...}}, empty
   when nothing collides. See docs/proposals/namespaces.md, Phase 2."
  [class-defs]
  (->> class-defs
       (group-by :name)
       (keep (fn [[nm defs]]
               (when (every? :qualified-name defs)
                 (let [qualified-names (into #{} (map :qualified-name) defs)]
                   (when (> (count qualified-names) 1)
                     [nm qualified-names])))))
       (into {})))

(defn- ambiguous-function-names
  "The free-function analog of ambiguous-class-names: bare names that
   resolve to more than one distinct interned function, from the pre-dedup
   normalized-functions list (two interned files defining the same bare
   function name concatenate into it, unlike a same-file duplicate, which
   the walker already collapses to one def before this is even reached). A
   function declared directly in this program (or an earlier REPL cell) has
   no :qualified-name and always wins outright, exactly like a class."
  [fn-defs]
  (->> fn-defs
       (group-by :name)
       (keep (fn [[nm defs]]
               (when (every? :qualified-name defs)
                 (let [qualified-names (into #{} (map :qualified-name) defs)]
                   (when (> (count qualified-names) 1)
                     [nm qualified-names])))))
       (into {})))

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
  "Format a type error for display. :source-file (see with-source-file) is
   present only for an error inside an interned file's own body, never for
   one in the entry file the user directly ran — the file they're already
   looking at needs no name, but a line number 60-odd lines into a 7-line
   entry file, with nothing to say it is actually about a file it interned,
   is a diagnostic that actively misleads rather than merely under-informs."
  [{:keys [message line column source-file]}]
  (if line
    (str "Type error"
         (when source-file (str " in " source-file))
         " at line " line
         (when column (str ", column " column))
         ": " message)
    (str "Type error"
         (when source-file (str " in " source-file))
         ": " message)))

(defn- resolve-super-parent-class-name
  "The single direct parent of CURRENT-CLASS, i.e. what `super` resolves to —
   mirrors `single-super-parent-name` in `nex.lower` (the compiled backend) and
   `resolve-super-parent-name` in `nex.interpreter`, so all three agree.

   `super` is an ordinary identifier at parse time (see `check-target-call`'s
   exemption from the undefined-variable check below), so `super.field := v`
   needs this to resolve which class's field access rules to check against —
   without it, `check-expression` on a bare `super` throws \"Undefined
   variable: super\" before ever reaching the field-assignment checks."
  [env current-class]
  (when-not current-class
    (throw (ex-info "super used outside a method or constructor body"
                    {:error (type-error "super used outside a method or constructor body")})))
  (let [parents (mapv :parent (:parents (env-lookup-class env current-class)))]
    (case (count parents)
      1 (first parents)
      0 (throw (ex-info "super requires a direct parent"
                        {:error (type-error "super requires a direct parent")}))
      (throw (ex-info "super is ambiguous with multiple direct parents"
                      {:error (type-error "super is ambiguous with multiple direct parents")})))))

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
    (catch clojure.lang.ExceptionInfo e
      (throw (annotate-type-exception e node)))))

(defn- error-with-source-file
  [err source-file]
  (if (and err source-file (nil? (:source-file err)))
    (assoc err :source-file source-file)
    err))

(defn- annotate-type-exception-source-file
  "Stamp :source-file (unless already set — an inner class checked under its
   own source-file, e.g. by a NESTED with-source-file for a class this one's
   body itself refers into, wins) onto whichever TypeError(s) an exception
   carries — a single :error (the common case, most type errors) or a batch
   :errors (collect-undefined-type-errors, which gathers several at once —
   see with-source-file, below, for why that one still needs its own,
   different treatment)."
  [e source-file]
  (let [data (ex-data e)]
    (cond
      (:error data)
      (ex-info (ex-message e) (update data :error error-with-source-file source-file) e)

      (:errors data)
      (ex-info (ex-message e)
               (update data :errors (fn [errs] (mapv #(error-with-source-file % source-file) errs)))
               e)

      :else e)))

(defn- with-source-file
  "Run f, and — when source-file is given — stamp it onto any TypeError(s)
   an exception escaping f carries (see annotate-type-exception-source-file).
   check-program wraps each class/function it processes in this, using the
   :source-file resolve-interned* stamped on it (docs/proposals/
   namespaces.md): a class or function from an interned file otherwise
   carries no record of which file it came from once merged into one
   program, so a type error deep in its own body — a bad field type, a typo
   in a method — reports a line number with no file to go with it. A nil
   source-file (every class/function the entry file declares directly,
   which resolve-interned* never touches) makes this a no-op, exactly as
   before this existed."
  [source-file f]
  (if-not source-file
    (f)
    (try
      (f)
      (catch clojure.lang.ExceptionInfo e
        (throw (annotate-type-exception-source-file e source-file))))))

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
  "Return type with detachable marker removed (normalized). A simple named
   type (no type-params/param-types/return-type) collapses back to a bare
   string, matching how the walker represents a non-detachable, non-generic
   type — e.g. attachable-type of ?Integer's {:base-type \"Integer\"
   :detachable true} is the string \"Integer\", not {:base-type \"Integer\"},
   which is-numeric-type?/is-comparable-type? (plain string equality checks)
   would not recognize as numeric/comparable."
  [t]
  (let [n (normalize-type t)]
    (if (map? n)
      (let [m (cond-> (dissoc n :detachable)
                (:type-params n) (update :type-params #(mapv attachable-type %))
                (:param-types n) (update :param-types #(mapv (fn [p] (update p :type attachable-type)) %))
                (:return-type n) (update :return-type attachable-type))]
        (if (and (= (keys m) [:base-type]) (string? (:base-type m)))
          (:base-type m)
          m))
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

(defn- auto-initializable-collection-type?
  "Whether type is a builtin collection type (Array/Map/Set) that always has
   a sensible empty-value default (matching get-default-field-value in the
   interpreter and the equivalent literal-node init emitted by the JVM
   lowerer), so `result` need not be explicitly assigned on every path the
   way other reference-like types are."
  [t]
  (let [n (attachable-type t)
        base (cond
               (string? n) n
               (map? n) (:base-type n)
               :else nil)]
    (contains? #{"Array" "Map" "Set"} base)))

(defn- attached-non-scalar-type?
  "Whether a type is an attached, non-scalar return type that must not
   implicitly fall back to nil."
  [t]
  (let [n (normalize-type t)]
    (and (not (detachable-type? n))
         (reference-like-type? n)
         (not (auto-initializable-collection-type? n)))))

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
          ;; A generic param bound in scope (a class's own `[T]`, or a
          ;; `function reduce[T](...)`/`fn[T](...)`'s own) is registered as a
          ;; synthetic placeholder class by register-generic-param-classes!
          ;; so constraint/subtype checks have something to look up -- that
          ;; must NOT make it stop looking like a generic param here. Without
          ;; this, "T" reads as generic everywhere EXCEPT from inside the very
          ;; scope that declares it, which is backwards: comparing two
          ;; `Function(T, T): T`-shaped signatures from inside a generic
          ;; function's own body (e.g. an anonymous-function argument checked
          ;; against another generic function's unsubstituted parameter type)
          ;; is exactly where this predicate needs to say yes.
          (or (not (env-lookup-class env t))
              (:generic-param? (env-lookup-class env t)))
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

(defn- class-name-identity
  "Normalize a bare or qualified class-name string to a common identity for
   type-equality purposes — `Account` and `finance.Account`
   (docs/proposals/namespaces.md, Phase 3) name the SAME class whenever
   `Account` alone isn't ambiguous, and must compare equal: an interned
   class's actual type never changes depending on which spelling a caller
   happened to write. Resolves through env-lookup-class (the same choke
   point every other class-name resolution goes through) and prefers
   :true-name — the real bare identity check-program's qualified-class-defs
   stashes there — falling back to the resolved class-def's own :name.
   A lookup that throws (name not found, or ambiguous — env-lookup-class
   throws for that, see ambiguous-class-names) or a non-class string
   (builtins, generic param names) passes through unchanged; an ambiguous
   bare name reaching here at all means something upstream should already
   have thrown before two *values* of that type were ever being compared,
   so silently declining to normalize it here changes nothing observable."
  [env s]
  (if (and env (string? s))
    (if-let [cd (try (env-lookup-class env s) (catch Exception _ nil))]
      (or (:true-name cd) (:name cd) s)
      s)
    s))

(defn types-equal?
  "Check if two types are equal"
  ([type1 type2]
   (types-equal? nil type1 type2))
  ([env type1 type2]
   (let [t1 (normalize-type type1)
         t2 (normalize-type type2)]
     (or (= t1 t2)
         (and env (string? t1) (string? t2)
              (= (class-name-identity env t1) (class-name-identity env t2)))
         ;; Any is compatible with all types
         (or (= t1 "Any") (= t2 "Any"))
         ;; A generic type parameter is opaque: while checking a generic
         ;; declaration's own body in the abstract, it is equal only to
         ;; itself (already handled by `(= t1 t2)` above) -- never to a
         ;; different generic name (that let a G-typed value flow into an
         ;; unrelated T-typed slot) and never to a concrete type either (that
         ;; let `function f[G](x: G): Integer do result := x end` silently
         ;; accept any G as an Integer). A caller with an actual binding for a
         ;; generic name resolves it via resolve-generic-type BEFORE reaching
         ;; this comparison (see infer-free-function-return-type, the callN
         ;; argument-checking path, check-override-conformance, and the
         ;; Function-signature branch of types-compatible? below) -- so by the
         ;; time a bare generic name reaches here unresolved, there either is
         ;; no caller-provided binding to apply (the declaration-checking
         ;; case), or resolution has already happened and this name is
         ;; genuinely a mismatch.
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
         ;; Handle other parameterized types. Compare arguments element-wise so a
         ;; recursive `Any` acts as a wildcard (e.g. an inferred `Ok[Integer, Any]`
         ;; matches `Ok[Integer, String]` — the permissive-Any policy for partial
         ;; construction inference).
         (and (map? t1) (map? t2)
              (not= (:base-type t1) "Function")
              (= (:base-type t1) (:base-type t2))
              (= (count (:type-params t1)) (count (:type-params t2)))
              (every? (fn [[a b]] (types-equal? env a b))
                      (map vector (:type-params t1) (:type-params t2))))
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
                      super)
            ;; A parent recorded via a qualified `inherit` clause (`inherit
            ;; flex/Rule`, walked to the :parent string "flex.Rule") must
            ;; still match a SUPER written bare ("Rule") — the same identity
            ;; normalization types-equal? already applies to a direct
            ;; comparison (see class-name-identity), needed again here since
            ;; the ancestor walk compares :parent strings that types-equal?
            ;; never sees. Without it, a class inherited through its
            ;; qualified name was accepted everywhere BUT as an argument to a
            ;; parameter typed with the same class's bare name — "Expected
            ;; Rule, got Percent_Off" even though Percent_Off truly does
            ;; inherit Rule, just spelled "flex/Rule" at the inherit site.
            super-identity (class-name-identity env super)]
        (letfn [(sub? [current seen]
                (if (contains? seen current)
                  false
                  (if-let [class-def (env-lookup-class env current)]
                    (let [parents (map :parent (:parents class-def))
                          seen (conj seen current)]
                      (or (some #(= (class-name-identity env %) super-identity) parents)
                          (some #(sub? % seen) parents)))
                    false)))]
          (sub? sub #{}))))))

(declare types-compatible?)
(declare lookup-class-method)
(declare merge-inferred-generic-bindings)
(declare infer-generic-type-map-from-arg)
(declare generic-names-in-type)
(declare resolve-generic-type)

(defn- substitute-type-params
  "Replace generic parameter names in `t` using `subst` (param name -> type)."
  [t subst]
  (let [t (normalize-type t)]
    (cond
      (string? t) (get subst t t)
      (and (map? t) (:type-params t))
      (assoc t :type-params (mapv #(substitute-type-params % subst) (:type-params t)))
      :else t)))

(defn- ancestor-instantiation
  "Walk `sub`'s inheritance chain looking for `super-name`, substituting generic
   arguments through each `inherit` clause. Returns the type arguments `sub`
   supplies to `super-name` (a vector, possibly empty), or nil when `super-name`
   is not an ancestor.

   This is what lets a non-generic heir of an instantiated generic conform to its
   parent: `class Over_Amount inherit Spec[Draft]` carries no arguments of its
   own, yet instantiates `Spec` at `[Draft]`. It equally handles arguments that
   are threaded (`C[T] inherit P[T]`), reordered, or nested (`C[T] inherit
   P[Array[T]]`).

   Compares names through class-name-identity, not raw `=` — SUPER-NAME may
   be written bare while a step of the walk (SUB-NAME itself, or a `parent`
   string found along the way) is qualified, e.g. `inherit flex/Spec[T]`
   walked to \"flex.Spec\" against a parameter typed bare `Spec[Integer]`.
   Both denote the same class whenever the bare name isn't itself ambiguous
   (see class-name-identity) — without this normalization here, a generic
   heir reached through a qualified `inherit` clause was rejected as not
   conforming to its own parent's bare-named parameterized type, the exact
   generic-type analog of the fix class-subtype? needed for non-generic
   inheritance."
  [env sub-name sub-args super-name seen]
  (cond
    (= (class-name-identity env sub-name) (class-name-identity env super-name)) (vec sub-args)
    (contains? seen sub-name) nil
    :else
    (when-let [class-def (env-lookup-class env sub-name)]
      (let [gparams (map #(type-name-string (:name %)) (:generic-params class-def))
            subst (zipmap gparams sub-args)
            seen (conj seen sub-name)]
        (some (fn [{:keys [parent generic-args]}]
                (ancestor-instantiation
                 env parent
                 (mapv #(substitute-type-params % subst) generic-args)
                 super-name seen))
              (:parents class-def))))))

(defn- generic-class-conforms?
  "Does class type `a1` conform to the parameterized class type `a2`, given the
   generic arguments `a1` supplies to `a2`'s base class along its inheritance
   chain? `a1` may be a bare class name (a non-generic heir) or parameterized."
  [env a1 a2]
  (let [b1 (if (map? a1) (:base-type a1) a1)
        args1 (if (map? a1) (vec (:type-params a1)) [])
        b2 (:base-type a2)
        args2 (vec (:type-params a2))]
    (boolean
     (when (and (string? b1) (string? b2) (seq args2)
                (not= b1 "Function") (not= b2 "Function"))
       (when-let [inst (ancestor-instantiation env b1 args1 b2 #{})]
         (and (= (count inst) (count args2))
              (every? true? (map (fn [p1 p2]
                                   (or (= p1 "Any") (= p2 "Any")
                                       (types-compatible? env p1 p2)))
                                 inst args2))))))))

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
      (or (types-equal? env a1 a2)
          ;; A named class that implements the Function protocol — a free
          ;; function's generated `<name>_Function` wrapper (`inherit
          ;; Function`, a single `callN` method; see
          ;; `nex.walker/build-function-node`), or any user class declared
          ;; the same way — is compatible with a structural `Function(...)`
          ;; target when its own `callN` signature conforms. Passing a free
          ;; function by name (`filter_items(is_rare_or_legendary)`) resolves
          ;; to this generated class name as a bare string (`env-add-var`
          ;; binds a function's identifier to `(:class-name fn-def)`), which
          ;; none of the other branches here recognize: the string/string
          ;; branch below only accepts a bare "Function" target, and the
          ;; map/map branch only compares two already-structural types.
          ;; Reuses that map/map branch by building the same shape of
          ;; structural type for a1 from the class's callN method and
          ;; recursing, rather than duplicating the contravariant-params/
          ;; covariant-return comparison here.
          (and (string? a1) (map? a2) (= (:base-type a2) "Function") (:param-types a2)
               (class-subtype? env a1 "Function")
               (when-let [method-sig (lookup-class-method env a1 (str "call" (count (:param-types a2)))
                                                           (count (:param-types a2)))]
                 (types-compatible? env
                                    {:base-type "Function"
                                     :param-types (mapv (fn [p] {:name (:name p) :type (:type p)})
                                                        (:params method-sig))
                                     :return-type (:return-type method-sig)}
                                    a2)))
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
               ;; a2 (the target) may be written in terms of the callee's own,
               ;; still-unbound generic params (`Function(v: G): T`). Unify
               ;; those against a1's actual, concrete signature first and
               ;; compare against the SUBSTITUTED target -- rather than
               ;; comparing the raw generic names, which relied on a generic
               ;; name being treated as compatible with anything.
               (let [generic-names (generic-names-in-type env a2)
                     type-map (when (seq generic-names)
                                (reduce (fn [acc [d a]]
                                          (merge-inferred-generic-bindings
                                           env acc (infer-generic-type-map-from-arg env generic-names d a)))
                                        {}
                                        (cond-> (mapv vector (map :type (:param-types a2)) (map :type (:param-types a1)))
                                          (and (:return-type a2) (:return-type a1))
                                          (conj [(:return-type a2) (:return-type a1)]))))
                     a2' (if (seq type-map) (resolve-generic-type a2 type-map) a2)]
                 (and (every? true?
                              (map (fn [p1 p2]
                                     ;; contravariant: target param must conform to source param
                                     (types-compatible? env (:type p2) (:type p1)))
                                   (:param-types a1) (:param-types a2')))
                      (or (nil? (:return-type a1)) (nil? (:return-type a2'))
                          ;; covariant: source return must conform to target return
                          (types-compatible? env (:return-type a1) (:return-type a2'))))))
          (and (string? a1) (string? a2) (class-subtype? env a1 a2))
          (and (map? a1) (string? a2) (class-subtype? env (:base-type a1) a2))
          ;; Conformance to a parameterized target through an instantiated
          ;; generic parent. A non-generic heir (`class Over_Amount inherit
          ;; Spec[Draft]`) has a bare class name as its type but still conforms
          ;; to `Spec[Draft]`, so the source may be a string here; the arguments
          ;; come from the inherit clause rather than from the source type.
          (and (map? a2) (or (string? a1) (map? a1))
               (generic-class-conforms? env a1 a2))
          ;; Same-base-type conformance. Function types are excluded here: their
          ;; conformance is decided solely by the function-signature branch above
          ;; (contravariant params, covariant return), so two distinct function
          ;; signatures are NOT silently accepted as a no-type-params class match.
          ;; Inheritance between *different* base types is handled by the
          ;; generic-class-conforms? branch above, which resolves the heir's
          ;; arguments through its inherit clause instead of assuming they line up
          ;; positionally with the parent's.
          (and (map? a1) (map? a2)
               (not= (:base-type a1) "Function") (not= (:base-type a2) "Function")
               (= (:base-type a1) (:base-type a2))
               (= (count (:type-params a1)) (count (:type-params a2)))
               (every? true? (map (fn [p1 p2]
                                    (or (= p1 "Any") (= p2 "Any")
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
                                :generic-param? true
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
(declare check-expression-with-expected)
(declare any-into-concrete-without-convert?)
(declare throw-any-narrowing-error!)
(declare collect-class-info)
(declare check-class)
(declare check-method)
(declare convert-guard-binding)
(declare convert-guard-bindings)
(declare attached-test-guards)
(declare resolve-generic-type)
(declare build-generic-type-map)
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
                                  ;; Record where the routine was declared: an
                                  ;; inherited routine's signature is written in
                                  ;; its declaring class's generic parameters, so
                                  ;; callers need that class to resolve them.
                                  (assoc method-sig :declaring-class cn))]
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
                    method-sig (env-lookup-method env cn method-name)
                    feature-member (when class-def
                                     (some (fn [member]
                                             (when (and (= (:type member) :method)
                                                        (= (:name member) method-name))
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

(defn- known-reference-type
  [env ^Class klass]
  (let [simple-name (.getSimpleName klass)]
    (cond
      (builtin-type? simple-name) simple-name
      (env-lookup-class env simple-name) simple-name
      :else "Any")))

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

(defn- class-java-superclass-name
  "The name of the concrete Java class CLASS-NAME (or one of its Nex
   ancestors) extends via `inherit` (Phase 2, docs/proposals/java-interop.md),
   or nil. At most one exists anywhere in the chain — check-inheritance
   enforces that the JVM's single-inheritance rule holds."
  [env class-name]
  (letfn [(walk [cn visited]
            (when (and cn (not (contains? visited cn)))
              (when-let [class-def (env-lookup-class env cn)]
                (let [visited' (conj visited cn)]
                  (some (fn [{:keys [parent]}]
                          (let [parent-def (env-lookup-class env parent)
                                real-nex-class? (and parent-def (not (:import parent-def)))]
                            (if real-nex-class?
                              (walk parent visited')
                              (when-let [^Class klass (resolve-imported-java-class env parent)]
                                (when-not (.isInterface klass) parent)))))
                        (:parents class-def))))))]
    (walk class-name #{})))

(defn- reflected-java-constructor-arities
  "The arities of KLASS's public constructors — used by
   check-java-super-constructor-call, at the same name+arity precision as the
   rest of Phase 1/2's Java-interop checks (no full per-argument type
   verification, matching the settled simplification for interface method
   coverage)."
  [^Class klass]
  (->> (.getConstructors klass)
       (filter #(java.lang.reflect.Modifier/isPublic (.getModifiers ^java.lang.reflect.Constructor %)))
       (map #(alength (.getParameterTypes ^java.lang.reflect.Constructor %)))
       set))

(defn- java-super-constructor-call?
  "True when STMT is `super.new(args)` or `<JavaSuperclassName>.new(args)` —
   the reserved selector (docs/proposals/java-interop.md) for forwarding to a
   Java superclass's real constructor."
  [super-name stmt]
  (and (map? stmt)
       (= :call (:type stmt))
       (= "new" (:method stmt))
       (or (and (map? (:target stmt)) (= :super (:type (:target stmt))))
           (= super-name (:target stmt)))))

(defn- check-java-super-constructor-call
  "For a class extending a concrete Java class, each constructor must open
   with exactly one super.new(args)/<JavaClassName>.new(args) call, matching
   one of the Java class's public constructors by arity — or, if omitted, the
   Java class must have a public no-arg constructor (mirroring javac's own
   implicit super()). The JVM requires the superclass constructor to run
   before `this` is touched at all, hence the first-statement requirement."
  [env class-name constructors]
  (when-let [super-name (class-java-superclass-name env class-name)]
    (let [^Class klass (resolve-imported-java-class env super-name)
          valid-arities (reflected-java-constructor-arities klass)
          ;; A class with no explicit `create` block still needs a Java
          ;; super-constructor call to exist somewhere (implicitly, if the
          ;; Java class has a no-arg constructor) — checked as if there were
          ;; one constructor with an empty body.
          ctors (if (seq constructors) constructors [{:name "make" :body [] :params []}])]
      (doseq [{ctor-name :name ctor-body :body ctor-params :params} ctors]
        (let [first-stmt (first ctor-body)
              ;; A fresh per-constructor env with its own params bound, mirroring
              ;; check-constructor's own ctor-env — super.new(...)'s arguments may
              ;; reference the constructor's parameters (the common case: forwarding
              ;; a Nex constructor arg straight into the Java super-constructor).
              ctor-env (make-type-env env)]
          (doseq [param ctor-params]
            (env-add-var ctor-env (:name param) (or (:type param) "Any")))
          (cond
            (java-super-constructor-call? super-name first-stmt)
            (let [arg-types (mapv #(check-expression ctor-env %) (:args first-stmt))]
              (when-not (contains? valid-arities (count arg-types))
                (throw (ex-info (str "No matching constructor for " super-name)
                                {:error (type-error
                                         (str class-name "'s super.new(...) call in " ctor-name
                                              " passes " (count arg-types) " argument(s); " super-name
                                              " has no public constructor of that arity."))}))))

            (some #(java-super-constructor-call? super-name %) ctor-body)
            (throw (ex-info (str "super.new(...) must be the first statement in " ctor-name)
                            {:error (type-error
                                     (str "In " class-name "." ctor-name ": the call to " super-name
                                          "'s constructor (super.new(...) or " super-name ".new(...)) "
                                          "must be the first statement — the JVM requires the "
                                          "superclass constructor to run before `this` is touched "
                                          "at all."))}))

            :else
            (when-not (contains? valid-arities 0)
              (throw (ex-info (str class-name "." ctor-name " must call " super-name "'s constructor")
                              {:error (type-error
                                       (str class-name "." ctor-name " must open with super.new(...) "
                                            "or " super-name ".new(...): " super-name " has no public "
                                            "no-arg constructor to call implicitly."))})))))))))

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

(defn- visible-field-names
  "Names of the fields readable on CLASS-NAME from CALLER-CLASS-NAME, parents
   included — the same reachability `lookup-class-field-member` applies."
  [env class-name caller-class-name]
  (letfn [(walk [cn visited]
            (when (and cn (not (contains? visited cn)))
              (when-let [class-def (env-lookup-class env cn)]
                (concat
                 (keep (fn [m]
                         (when (and (= (:type m) :field)
                                    (not (:constant? m))
                                    (or (= caller-class-name cn)
                                        (public-member? m)))
                           (:name m)))
                       (feature-members class-def))
                 (mapcat (fn [{:keys [parent]}] (walk parent (conj visited cn)))
                         (:parents class-def))))))]
    (distinct (walk class-name #{}))))

(defn- undefined-field-message
  "\"Undefined field: q\" alone leaves the reader to guess what the field is
   called. Name the class and list what it does have. FROM-PATTERN adds the
   rule a `match` clause is most often tripping over: the name before the colon
   is a field, not a new binding.

   The listing is visibility-filtered, so it never discloses a private field to
   a caller that could not read one — hence \"accessible\" rather than a flat
   claim about what the class has: a class whose every field is private has
   fields, just not for this reader."
  [env class-name field-name caller-class-name from-pattern]
  (let [fields (visible-field-names env class-name caller-class-name)]
    (str "Undefined field: " field-name " on " class-name
         (if (seq fields)
           (str ". Accessible fields: " (str/join ", " (sort fields)) ".")
           ". It has no accessible fields.")
         (when from-pattern
           (str " In a pattern the name before `:` is a field of the variant;"
                " to bind a field to a local named `" field-name "`, write"
                " `<field> as " field-name "`.")))))

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

(defn- class-own-constructor
  "A constructor declared directly on class-name (not inherited) matching
   ctor-name + arity. Mirrors `nex.lower`'s `class-constructor-def`: a
   `super.ctor(...)` constructor-chaining call may only reach the *immediate*
   super parent's own constructor, never a grandparent's — lowering has no
   mechanism to skip past a parent lacking a matching constructor, so the
   typechecker must not accept a call that only resolves further up."
  [env class-name ctor-name arity]
  (when-let [class-def (env-lookup-class env class-name)]
    (some #(when (and (= (:name %) ctor-name)
                      (= (count (or (:params %) [])) arity))
             %)
          (->> (:body class-def)
               (filter #(= :constructors (:type %)))
               (mapcat :constructors)))))

(defn- lookup-super-feature-method
  "A genuine feature-section method reachable from class-name or an ancestor,
   by name + arity — mirrors `nex.lower`'s `class-method-def` +
   `inherited-method-def`, which a `super.method(...)` call must agree with.
   Unlike an ordinary call (`lookup-class-method`), this never matches a
   constructor registered under the same name further up the chain — such a
   match type-checks but has nothing for lowering to call, surfacing as an
   opaque internal-error crash instead of a type error."
  [env class-name method-name arity]
  (letfn [(walk [cn visited]
            (when (and cn (not (contains? visited cn)))
              (let [class-def (env-lookup-class env cn)
                    visited' (conj visited cn)
                    own (when class-def
                          (some (fn [m]
                                  (when (and (= (:type m) :method)
                                            (= (:name m) method-name)
                                            (= (count (or (:params m) [])) arity))
                                    m))
                                (feature-members class-def)))]
                (or own
                    (when class-def
                      (some (fn [{:keys [parent]}] (walk parent visited'))
                            (:parents class-def)))))))]
    (walk class-name #{})))

(defn- bind-visible-class-fields!
  "Bind fields visible inside class-name into target-env.
   Own fields are always visible; inherited fields must be public."
  [target-env env class-name]
  (letfn [(bind-fields [cn subst visited inherited?]
            (when (and cn (not (contains? visited cn)))
              (when-let [class-def (env-lookup-class env cn)]
                (let [visited' (conj visited cn)]
                  (doseq [{:keys [parent generic-args]} (:parents class-def)]
                    ;; Restate the parent's generic parameters in this class's terms
                    ;; before descending, so a field declared `first: A` in
                    ;; `Pair[A, B]` binds as `Y` inside `C[X, Y] inherit Pair[Y, X]`
                    ;; and as `Draft` inside `class C inherit Pair[Draft, Final]`.
                    (let [parent-def (env-lookup-class env parent)
                          parent-params (map #(type-name-string (:name %))
                                             (:generic-params parent-def))
                          parent-args (mapv #(substitute-type-params % subst)
                                            (or generic-args []))]
                      (bind-fields parent (zipmap parent-params parent-args)
                                   visited' true)))
                  (doseq [member (feature-members class-def)]
                    (when (and (= (:type member) :field)
                               (not (:constant? member))
                               (or (not inherited?)
                                   (public-member? member)))
                      (env-add-var target-env (:name member)
                                   (if (seq subst)
                                     (substitute-type-params (:field-type member) subst)
                                     (:field-type member)))))))))]
    (bind-fields class-name {} #{} false)))

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
            ;; Inside the static world, an otherwise-unknown name may be a
            ;; readable top-level global (§7).
            (if-let [global-type (env-lookup-global env name)]
              global-type
              (throw (ex-info (str "Undefined variable: " name)
                              {:error (type-error (str "Undefined variable: " name))}))))))
      (throw (ex-info (str "Undefined variable: " name)
                      {:error (type-error (str "Undefined variable: " name))})))))

(defn- check-call-signature
  [env method args method-sig type-map & {:keys [arg-types]}]
  ;; Deliberately does NOT check each argument with its positional parameter's
  ;; type as an expected-type hint (the way check-let does for a typed `let`).
  ;; That would let an unannotated `fn(item) do ... end` call argument
  ;; typecheck via the same inference `check-expression-with-expected`'s
  ;; `:anonymous-function` case supports — but the compiled backend's
  ;; anonymous-function class compilation runs as a whole-program pass before
  ;; any individual call site is lowered, and resolving a call argument's
  ;; expected type requires the call's *receiver* type (scope-aware: locals,
  ;; fields, `this`), not just a literal annotation sitting right there like a
  ;; `let`'s. Teaching lowering to do that is a much bigger, scope-tracking
  ;; rewrite (on the order of the existing closure-capture pass) that hasn't
  ;; been built yet. Supporting it here without lowering support would
  ;; typecheck fine and then crash with an opaque lowering error — so for now
  ;; the shorthand is `let`-context only; a call argument still needs an
  ;; explicit `fn(item: T): R do ... end`.
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
        (when (any-into-concrete-without-convert? env param-type arg-type)
          (throw-any-narrowing-error! (str "parameter '" (:name param) "' of " method) param-type))
        (when-not (types-compatible? env arg-type param-type)
          (throw (ex-info (str "Argument type mismatch for method " method)
                          {:error (type-error
                                   (str "Expected " (display-type param-type)
                                        ", got " (display-type arg-type)))})))))
    ;; A method with no return-type annotation stores :return-type as nil, not
    ;; the string "Void" — coalesce it here (same convention as other
    ;; return-type call sites) so a Void-returning user method's call
    ;; expression is recognizably Void to check-expression's Void-as-value
    ;; guard, not a bare nil that guard doesn't match.
    (or (resolve-generic-type (:return-type method-sig) type-map) "Void")))

(defn lookup-operator-alias
  "The feature on `class-name` (or an ancestor) bound to `operator` by an `alias`
   clause, as {:name … :sig …}. Only one-argument features can back a binary
   operator. Returns nil when the class aliases nothing to `operator` — callers
   then report the ordinary 'requires numeric operands' error."
  [env class-name operator]
  (letfn [(search [cn visited]
            (when (and cn (string? cn) (not (contains? visited cn)))
              (when-let [class-def (env-lookup-class env cn)]
                (or (some (fn [member]
                            (when (and (= (:type member) :method)
                                       (= (:alias member) operator)
                                       (= 1 (count (or (:params member) []))))
                              {:name (:name member)
                               :declaring-class cn
                               :sig (env-lookup-method env cn (:name member) 1)}))
                          (feature-members class-def))
                    (some (fn [{:keys [parent]}] (search parent (conj visited cn)))
                          (:parents class-def))))))]
    (search class-name #{})))

(defn operator-alias-target
  "Resolve `operator` against the left operand's type, or nil if that type is not
   a user class that aliases it. This is consulted only after the numeric and
   String paths have declined, so it can never shadow built-in arithmetic."
  [env left-type operator]
  (let [class-name (let [t (attachable-type left-type)]
                     (if (map? t) (:base-type t) t))]
    (when (and (string? class-name) (env-lookup-class env class-name))
      (lookup-operator-alias env class-name operator))))

(defn check-aliased-operator
  "Type an operator that a user class has bound to a feature with `alias`. The
   operator is exactly sugar for the call, so the argument is checked against the
   feature's parameter and the operator's type is the feature's return type.
   Returns nil when no alias applies, leaving the caller to raise its own error."
  [env expr operator left-type right]
  (when-let [{:keys [name sig]} (operator-alias-target env left-type operator)]
    (let [type-map (build-generic-type-map env left-type)]
      (check-call-signature env name [right] sig type-map))))

(defn check-binary-op
  "Check the type of a binary operation"
  [env {:keys [operator left right] :as expr}]
  (let [;; Expand aliases (incl. refinement types) so a `Quantity` operand is
        ;; seen as its base `Integer` for arithmetic/comparison.
        left-type (expand-type-aliases env (check-expression env left))
        right-type (expand-type-aliases env (check-expression env right))
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
        (or (check-aliased-operator env expr operator left-type right)
            (throw (ex-info (str "Operator " operator " requires numeric or String operands")
                            {:error (type-error
                                     (str "Operator " operator " requires numeric or String operands, got "
                                          (display-type left-type) " and " (display-type right-type)))}))))

      ("/")
      (if (and (is-numeric-type? left-type) (is-numeric-type? right-type))
        (division-result-type left-type right-type)
        (or (check-aliased-operator env expr operator left-type right)
            (throw (ex-info (str "Operator " operator " requires numeric operands")
                            {:error (type-error
                                     (str "Operator " operator " requires numeric operands, got "
                                          (display-type left-type) " and " (display-type right-type)))}))))

      ("-" "*" "%")
      (if (and (is-numeric-type? left-type) (is-numeric-type? right-type))
        (numeric-result-type left-type right-type)
        (or (check-aliased-operator env expr operator left-type right)
            (throw (ex-info (str "Operator " operator " requires numeric operands")
                            {:error (type-error
                                     (str "Operator " operator " requires numeric operands, got "
                                          (display-type left-type) " and " (display-type right-type)))}))))

      ("^")
      (if (and (is-numeric-type? left-type) (is-numeric-type? right-type))
        (power-result-type left-type right-type)
        (or (check-aliased-operator env expr operator left-type right)
            (throw (ex-info (str "Operator " operator " requires numeric operands")
                            {:error (type-error
                                     (str "Operator " operator " requires numeric operands, got "
                                          (display-type left-type) " and " (display-type right-type)))}))))

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
   E.g., with type-map {\"T\" \"Integer\"}, resolves \"T\" to \"Integer\".
   A Function-shaped type's generic-relevant substructure lives under
   :param-types/:return-type rather than :type-params/:type-args (those are
   how Array[T]/Map[K,V]/user generic classes are shaped), so both are
   substituted -- otherwise a declared `Function(v: G): T` is left with its
   raw, unsubstituted generic names even after a caller's G/T bindings are
   known."
  [param-type type-map]
  (cond
    (nil? type-map) param-type
    (string? param-type) (get type-map param-type param-type)
    (map? param-type) (-> param-type
                          (update :base-type #(get type-map % %))
                          (update :type-args #(when % (mapv (fn [t] (resolve-generic-type t type-map)) %)))
                          (update :type-params #(when % (mapv (fn [t] (resolve-generic-type t type-map)) %)))
                          (update :param-types #(when % (mapv (fn [p] (update p :type resolve-generic-type type-map)) %)))
                          (update :return-type #(when % (resolve-generic-type % type-map))))
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

(defn build-member-generic-type-map
  "Build the type-map for resolving a member declared in `declaring-class`, as seen
   through the receiver type `target-type`.

   An inherited member's signature is written in *its declaring class's* generic
   parameters, which need not line up with the heir's: `C[X, Y] inherit P[Y, X]`
   renames and reorders them, and `class C inherit P[Draft]` supplies them outright
   while having none of its own. Resolving the receiver's arguments along the
   inherit chain binds the declaring class's parameters to whatever the heir
   actually supplies. Falls back to the receiver's own map when the member is not
   inherited, or when the chain cannot be resolved."
  [env target-type declaring-class]
  (let [base-name (cond
                    (map? target-type) (:base-type target-type)
                    (string? target-type) target-type
                    :else nil)
        type-args (when (map? target-type)
                    (or (:type-args target-type) (:type-params target-type)))]
    (or (when (and declaring-class base-name (not= declaring-class base-name))
          (when-let [inst (ancestor-instantiation env base-name (vec type-args)
                                                  declaring-class #{})]
            (build-generic-type-map env {:base-type declaring-class
                                         :type-args inst})))
        (build-generic-type-map env target-type))))

(defn- member-type-map
  "The type-map to resolve `member`'s declared types against, given receiver type
   `target-type`. Builtin signatures carry no declaring class and keep `fallback`."
  [env target-type fallback member]
  (if-let [declaring-class (:declaring-class member)]
    (build-member-generic-type-map env target-type declaring-class)
    fallback))

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

(defn- generic-names-in-type
  "Every bare generic-parameter-shaped name reachable within a type
   expression -- recursing into type-params/type-args (Array[T]/Map[K,V]/user
   generic classes) and, for a Function type, param-types/return-type -- as
   judged by is-generic-type-param?, not just \"looks like a capitalized
   name\" (that would also match real classes like Comparable)."
  [env t]
  (let [t (normalize-type t)]
    (cond
      (and (string? t) (is-generic-type-param? env t)) #{t}
      (map? t) (reduce set/union #{}
                       (concat (map #(generic-names-in-type env %)
                                    (or (:type-params t) (:type-args t) []))
                               (map #(generic-names-in-type env (:type %)) (:param-types t))
                               (when (:return-type t) [(generic-names-in-type env (:return-type t))])))
      :else #{})))

(defn- infer-generic-type-map-from-arg
  [env generic-names param-type arg-type]
  (let [param-type (normalize-type param-type)
        arg-type (normalize-type arg-type)]
    (cond
      (and (string? param-type) (contains? generic-names param-type))
      {param-type arg-type}

      ;; The argument is a bare class name -- a free function passed by
      ;; reference, or an anonymous lambda's generated wrapper class (see the
      ;; analogous branch in types-compatible? above) -- rather than an
      ;; already-structural Function type. Resolve its callN method to an
      ;; equivalent structural shape before unifying against param-type.
      (and (map? param-type) (= (:base-type param-type) "Function") (:param-types param-type)
           (string? arg-type))
      (if-let [method-sig (lookup-class-method env arg-type
                                               (str "call" (count (:param-types param-type)))
                                               (count (:param-types param-type)))]
        (infer-generic-type-map-from-arg
         env generic-names param-type
         {:base-type "Function"
          :param-types (mapv (fn [p] {:name (:name p) :type (:type p)}) (:params method-sig))
          :return-type (:return-type method-sig)})
        {})

      ;; A Function-typed param/arg carries its generic-relevant substructure
      ;; under :param-types/:return-type, not :type-params/:type-args (those
      ;; are how Array[T]/Map[K,V]/user generic classes are shaped) -- so a
      ;; generic parameter appearing only in a Function value's parameter or
      ;; return position (e.g. `f: Function(v: G): T` matched against an
      ;; argument lambda `fn(v: Integer): String`) must be unified here
      ;; explicitly, or its binding is silently missed.
      (and (map? param-type) (map? arg-type)
           (= (:base-type param-type) (:base-type arg-type))
           (= (:base-type param-type) "Function"))
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
          {}))

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
      (doseq [{:keys [name value]} (attached-test-guards condition)]
        (env-add-var env name (attachable-type (check-expression env value)))
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

(defn attached-test-guards
  "Extract {:name :value} pairs for `?<expr> as <name>` guards that are
   guaranteed true in a true condition, decomposing `and` conjunctions the
   same way convert-guard-bindings does. Pure/syntactic like
   guarded-non-nil-var — unlike a `convert` guard, an attached-test's bound
   type isn't in the AST (no user-written target type), so it is left for
   the caller to resolve via its own type inference (check-expression here;
   lower.clj's infer-type for the compiled backend)."
  [condition]
  (cond
    (nil? condition) []

    (and (map? condition) (= :attached-test (:type condition)))
    [{:name (:var-name condition) :value (:value condition)}]

    (and (map? condition)
         (= :binary (:type condition))
         (= "and" (:operator condition)))
    (vec (concat (attached-test-guards (:left condition))
                 (attached-test-guards (:right condition))))

    :else []))

(defn detachable-version
  "Return a detachable type version for variable bindings that may be nil."
  [t]
  (let [n (normalize-type t)]
    (if (map? n)
      (assoc n :detachable true)
      {:base-type n :detachable true})))

(defn- check-pattern-type
  "A field pattern's `field: T` names a type to test against. When T names no
   type the likeliest cause is the pre-`as` rename spelling (`field: local`,
   which now means \"field must be a `local`\"), so name the fix. Only reachable
   for a convert the walker synthesized from a pattern."
  [env field target-type]
  (let [base (let [t (normalize-type target-type)]
               (if (map? t) (:base-type t) t))]
    ;; An unknown *capitalized* name is indistinguishable from a generic param
    ;; here (is-generic-type-param? accepts any), so only a name that cannot be
    ;; a type at all is diagnosed. That is the migration case: locals are
    ;; lowercase, types are not.
    (when (and (string? base)
               (not (env-lookup-class env base))
               (not (builtin-type? base))
               (not (env-lookup-type-alias env base))
               (not (is-generic-type-param? env base)))
      (throw (ex-info "Invalid pattern type"
                      {:error (type-error
                               (str "In the pattern for field `" field "`: `" base
                                    "` is not a type. "
                                    (if (= base "_")
                                      (str "Fields are matched by name, so omit `"
                                           field "` to ignore it.")
                                      (str "To bind the field to a local named `"
                                           base "`, write `" field " as " base "`."))))})))))

(defn check-convert
  "Type-check convert expression:
   convert <value> to <var>:<Type>
   Returns Boolean and binds <var> as detachable <Type> in current scope."
  [env {:keys [value var-name target-type from-pattern]}]
  (validate-type-annotation env target-type)
  (when from-pattern
    (check-pattern-type env from-pattern target-type))
  (let [value-type (check-expression env value)
        target-type (normalize-type target-type)
        base-name (fn [t] (if (map? t) (:base-type t) t))
        numeric? #{"Integer" "Real"}
        value-base (base-name value-type)
        target-base (base-name target-type)
        ;; convert never changes numeric representation: Integer and Real are
        ;; unrelated classes (spec §4.3), so a statically numeric-to-numeric
        ;; convert would always yield false at runtime. Reject it here.
        _ (when (and (numeric? value-base)
                     (numeric? target-base)
                     (not= value-base target-base))
            (throw (ex-info "Invalid convert type relation"
                            {:error (type-error
                                     (str "convert cannot change numeric representation ("
                                          (display-type value-type) " to " (display-type target-type)
                                          "); an Integer widens implicitly where a Real is"
                                          " expected, and Real.round() yields an Integer"))})))
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

(defn check-attached-test
  "Type-check `?<expr> as <name>` (object test): <expr> must be a detachable
   type. The expression itself is Boolean; <name>'s binding to <expr>'s
   attached value happens separately, in apply-condition-branch-refinement!
   via attached-test-guards, mirroring how a `convert` guard's binding is
   handled apart from its own Boolean type here."
  [env {:keys [value]}]
  (let [value-type (check-expression env value)]
    (when-not (detachable-type? (normalize-type value-type))
      (throw (ex-info "'?' test requires a detachable type"
                      {:error (type-error
                               (str "'?' test requires a detachable type, got "
                                    (display-type value-type)))})))
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

;; The universal protocol: what *every* value has, whatever its type. This
;; case is consulted for every receiver (see `check-target-call`), so a name
;; listed here typechecks against a class that never declares it — which is
;; a promise only worth making for names that have a default implementation
;; behind them. It must therefore stay in step with the two things that
;; provide those defaults: the "Any" protocol class registered in
;; `register-builtin-methods`, and `builtin-type-methods` :Any in
;; nex.types.builtins. All three now list the same names.
;;
;; The cursor protocol (`cursor`/`start`/`item`/`next`/`at_end`) used to be
;; here and is deliberately not: there is no universal default for it, so
;; listing it promised an iteration protocol on every value in the language
;; and delivered it on none — `p.cursor` on a plain class typechecked and
;; then failed at runtime ("Method not found") or refused to compile. Each
;; of those names is now owned by the types that actually implement it: the
;; "Cursor" case below, the `across` loop's cursor path in
;; `check-target-call`, Array/Map/Set/String in `register-builtin-methods`,
;; and any user class that declares its own.
;;
;; `hash` is intentionally absent even though the other two tables carry it:
;; a class opts into hashing by inheriting Hashable. Adding it here would
;; typecheck `p.hash` on any class, which the compiled backend cannot lower.
(defn- builtin-method-signature-any
  [method argc _type-map]
  (case method
    "to_string" (when (= argc 0)
                  {:params [] :return-type "String"})
    "equals" (when (= argc 1)
               {:params [{:name "other" :type "Any"}] :return-type "Boolean"})
    "clone" (when (= argc 0)
              {:params [] :return-type "Any"})
    nil))

(defn- builtin-method-signature-task
  [method argc type-map]
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
    nil))

(defn- builtin-method-signature-channel
  [method argc type-map]
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
      nil)))

(defn- builtin-method-signature-min-heap
  [method argc type-map]
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
      nil)))

;; Atomic_Integer and Atomic_Integer64 are both 64-bit atomics on the JVM
;; (see nex.types.builtins) and share this exact signature set — one
;; handler, used for both keys in the dispatch table below.
(defn- builtin-method-signature-atomic-numeric
  [method argc _type-map]
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
    nil))

(defn- builtin-method-signature-atomic-boolean
  [method argc _type-map]
  (case method
    "load" (when (= argc 0) {:params [] :return-type "Boolean"})
    "store" (when (= argc 1) {:params [{:name "value" :type "Boolean"}] :return-type "Void"})
    "compare_and_set" (when (= argc 2)
                        {:params [{:name "expected" :type "Boolean"}
                                  {:name "update" :type "Boolean"}]
                         :return-type "Boolean"})
    nil))

(defn- builtin-method-signature-atomic-reference
  [method argc type-map]
  (let [elem-type (or (resolve-generic-type "T" type-map) "Any")
        maybe-elem (detachable-version elem-type)]
    (case method
      "load" (when (= argc 0) {:params [] :return-type maybe-elem})
      "store" (when (= argc 1) {:params [{:name "value" :type maybe-elem}] :return-type "Void"})
      "compare_and_set" (when (= argc 2)
                          {:params [{:name "expected" :type maybe-elem}
                                    {:name "update" :type maybe-elem}]
                           :return-type "Boolean"})
      nil)))

(defn- builtin-method-signature-cursor
  [method argc _type-map]
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
    nil))

(def ^:private builtin-method-signature-dispatch
  "base-type -> `(fn [method argc type-map] -> signature-or-nil)`: the
   primary dispatch table for `builtin-method-signature`. Each handler
   keeps its own `case method`(+argc) dispatch internally — a method can be
   genuinely overloaded (Task.await, Channel.send/receive) — the map only
   replaces the outer `case base-type` that used to pick the handler out."
  {"Any" builtin-method-signature-any
   "Task" builtin-method-signature-task
   "Channel" builtin-method-signature-channel
   "Min_Heap" builtin-method-signature-min-heap
   "Atomic_Integer" builtin-method-signature-atomic-numeric
   "Atomic_Integer64" builtin-method-signature-atomic-numeric
   "Atomic_Boolean" builtin-method-signature-atomic-boolean
   "Atomic_Reference" builtin-method-signature-atomic-reference
   "Cursor" builtin-method-signature-cursor})

(defn- builtin-method-signature
  [base-type method argc type-map]
  (when-let [handler (get builtin-method-signature-dispatch base-type)]
    (handler method argc type-map)))


(defn- across-target-message
  "The diagnostic for an `across` whose target cannot be iterated. `cursor` is
   invented by the desugaring, so naming it would point at code the programmer
   never wrote."
  [base-type]
  (str "`across` needs an Array, Map, Set, String, or Cursor; this one is "
       base-type "."
       (when (= "Any" base-type)
         (str " Narrow it first, for example: if convert <expr> to"
              " items: Array[Integer] then across items as x do ... end end"))))

(defn- resolve-call-target-info
  "Resolve everything the branches below need about `target` itself, once,
   up front: what class/type it names, whether it's detachable and
   nil-guarded, and the generic type-param bindings visible through it.
   Returned as a map so each branch can destructure just the handful of
   keys it actually needs."
  [env {:keys [target]}]
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
                               (or (lookup-class-field env current-class target)
                                   (:field-type (lookup-class-constant env current-class target))
                                   ;; A readable top-level global (§7). Gated on
                                   ;; being in the static world so top-level
                                   ;; source-order threading is preserved.
                                   (env-lookup-global env target))))
                         (check-expression env target))))
        normalized-target (normalize-type target-type)
        target-detachable? (detachable-type? normalized-target)
        guarded? (and (string? target) (env-var-non-nil? env target))
        base-type (if (map? target-type)
                    (:base-type target-type)
                    target-type)
        type-map (build-generic-type-map env target-type)]
    {:target-name target-name
     :across-item-type across-item-type
     :with-java? with-java?
     :class-target class-target
     :current-class current-class
     :target-type target-type
     :normalized-target normalized-target
     :target-detachable? target-detachable?
     :guarded? guarded?
     :base-type base-type
     :type-map type-map}))

;; A bare-identifier target that resolves to nothing — not a local, field,
;; class, across cursor, a parameterless routine of the current class, nor a
;; Java name inside `with java` — is an undefined variable. Without this the
;; member access slips through type-checking with a nil/Any target type and
;; fails later (cryptically in the JVM lowering, or only at runtime).
;;
;; A call-shaped target (`target.method(...)`) gets a more specific message
;; when TARGET is itself a real intern-path prefix — some other qualified
;; function is registered as "target.something" — but "target.method" isn't:
;; that's a misspelled/nonexistent function under a real module, not a
;; missing variable, and "Undefined variable: transaction" for
;; `transaction.fullfill(...)` sent a user chasing the wrong identifier
;; (nex.walker/resolve-qualified-function-calls already rewrote any exact
;; "target.method" match to an ordinary call before this ever runs, so
;; finding no such var here means either name really is wrong). This must
;; NOT fire when "target.method" exactly matches a registered qualified name:
;; that combination reaching here at all means the rewrite pass deliberately
;; declined it (collect-possibly-bound-names saw TARGET used as a real
;; local/param somewhere in the program and conservatively refused to steal
;; its meaning) — "Undefined variable" is the correct, intentional message
;; for that shadowing case.
(defn- reject-undefined-target!
  [env {:keys [target method]} {:keys [across-item-type with-java? current-class target-type]}]
  (when (and *strict-undefined-targets*
             (string? target)
             (nil? target-type)
             (not across-item-type)
             (not with-java?)
             ;; `this`/`Current` are special call targets, not variables (both
             ;; reach here as their own node type, never as this bare-string
             ;; `target`, so the `string? target` guard above already excludes
             ;; them — kept only for `Current`, a vestigial alias with no
             ;; grammar token or handling of its own anywhere in the codebase.
             ;; `super` used to need the same treatment before it got its own
             ;; node type too; now `(string? target)` alone excludes it.
             (not (#{"this" "Current"} target))
             (not (and current-class
                       (lookup-class-method env current-class target 0 current-class))))
    (let [vars @(:vars env)
          qualified-name (when method (str target "." method))]
      (if (and qualified-name
               (not (contains? vars qualified-name))
               (some #(str/starts-with? % (str target ".")) (keys vars)))
        (throw (ex-info (str "Undefined function: " qualified-name)
                        {:error (type-error (str "Undefined function: " qualified-name))}))
        (throw (ex-info (str "Undefined variable: " target)
                        {:error (type-error (str "Undefined variable: " target))}))))))

(defn- reject-unguarded-detachable-target!
  [_env {:keys [method]} {:keys [class-target target-detachable? guarded? normalized-target]}]
  (when (and (not class-target) target-detachable? (not guarded?))
    (throw (ex-info (str "Feature access on detachable target requires nil-check: " method)
                    {:error (type-error
                             (str "Cannot call feature '" method "' on detachable "
                                  (display-type normalized-target)
                                  ". Wrap with: if <obj> /= nil then <obj>." method "(...) end"))}))))

;; `super.method(...)` / `super.method` resolve against the immediate super
;; parent only — never further up the chain for a constructor, and never
;; falling back to a universal `Any` protocol signature — because that is
;; exactly what `nex.lower` is able to call. `check-general-target-call`
;; (via `lookup-class-method`) walks the whole ancestor chain looking for
;; *any* matching-arity entry, constructors included; that lets an invalid
;; super call (wrong arity, or naming a constructor that only some ancestor
;; happens to have) pass type-checking, only to crash lowering with an
;; opaque "internal error" instead of a real type error.
(defn- check-super-call
  [env {:keys [method args has-parens]} {:keys [base-type current-class]}]
  (if-let [ctor-def (class-own-constructor env base-type method (count args))]
    (check-call-signature env method args
                          {:params (:params ctor-def) :return-type base-type}
                          {})
    (if-let [method-sig (lookup-super-feature-method env base-type method (count args))]
      (check-call-signature env method args method-sig {})
      (if-let [field-member (and (false? has-parens)
                                 (lookup-class-field-member env base-type method current-class))]
        (resolve-generic-type (:field-type field-member) {})
        ;; base-type names no Nex-declared member here — it may instead be
        ;; a Java interface/class parent (docs/proposals/java-interop.md,
        ;; Phase 2). `new` is the super-constructor selector
        ;; (super.new(args), validated separately against the Java
        ;; class's reflected constructors by
        ;; check-java-super-constructor-call); any other name may be a
        ;; real inherited Java method this override calls through to
        ;; (e.g. `super.paintComponent(g)`).
        (let [arg-types (mapv #(check-expression env %) args)]
          (cond
            (= method "new")
            "Any"

            (reflected-java-method-signature env base-type method arg-types false)
            (:return-type (reflected-java-method-signature env base-type method arg-types false))

            :else
            (let [msg (str "Method not found: " method " on " base-type
                           ". `super` only reaches " base-type
                           "'s own features and constructor.")]
              (throw (ex-info msg {:error (type-error msg)})))))))))

(defn- check-across-item-call
  [_env {:keys [method]} {:keys [across-item-type]}]
  (case method
    "item" across-item-type
    "start" "Void"
    "next" "Void"
    "at_end" "Boolean"
    "cursor" "Cursor"
    nil))

(defn- check-class-constant-access
  [env {:keys [method]} {:keys [base-type type-map current-class target-type]}]
  (if-let [constant (lookup-class-constant env base-type method)]
    (resolve-generic-type (:field-type constant) type-map)
    (if-let [method-sig (and current-class
                             (not= current-class base-type)
                             (class-subtype? env current-class base-type)
                             (lookup-class-method env base-type method 0 current-class))]
      (check-call-signature env method [] method-sig
                            (member-type-map env target-type type-map method-sig)
                            :arg-types [])
      "Any")))

(defn- check-array-sort-call
  [env {:keys [args]} {:keys [target-type type-map]}]
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
        (when (any-into-concrete-without-convert? env "Function" compare-type)
          (throw-any-narrowing-error! "the Array.sort comparator argument" "Function"))
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
(defn- check-typed-function-call
  [env {:keys [args]} {:keys [target-type]}]
  (doseq [arg args]
    (check-expression env arg))
  (:return-type target-type))

(defn- check-general-target-call
  [env {:keys [method args has-parens from-pattern from-across]}
   {:keys [base-type type-map current-class class-target target-type with-java?]}]
  (let [class-def (env-lookup-class env base-type)]
    ;; Resolution order is what makes an override an override. The receiver's
    ;; own declaration must beat the universal "Any" protocol, or a class
    ;; redefining a protocol member is checked against the *protocol's*
    ;; signature instead of its own: `clone: M` was typed by Any's
    ;; `clone: Any`, so `m.clone.v` — the whole point of overriding it —
    ;; failed with "Undefined field: v" while the override itself ran
    ;; correctly at runtime. `to_string`/`equals` hid the bug because the
    ;; signature you would override them with matches Any's already.
    ;;
    ;; The receiver's *builtin* signature still comes first: for Task,
    ;; Channel, Cursor and friends that table is the authority, and it is
    ;; keyed on the real base type rather than a fallback.
    (if-let [method-sig (or (builtin-method-signature base-type method (count args) type-map)
                            (lookup-class-method env base-type method (count args) current-class)
                            (builtin-method-signature "Any" method (count args) type-map))]
      (check-call-signature env method args method-sig
                            (member-type-map env target-type type-map method-sig))
      (let [arg-types (mapv #(check-expression env %) args)]
        ;; A method not declared/inherited anywhere in the Nex chain may
        ;; still be a real, inherited (non-overridden) member of a Java
        ;; class this class `extends` (Phase 2, docs/proposals/
        ;; java-interop.md) — e.g. `t.start()` on a class inheriting
        ;; Thread. Tried after base-type's own reflection (the ordinary
        ;; "bare imported Java object" case) so neither shadows the other.
        (if-let [java-method-sig (or (reflected-java-method-signature env base-type method arg-types class-target)
                                     (when-let [super-name (class-java-superclass-name env base-type)]
                                       (reflected-java-method-signature env super-name method arg-types false)))]
          (check-call-signature env method args java-method-sig {} :arg-types arg-types)
          (if (false? has-parens)
            (if-let [field-member (lookup-class-field-member env base-type method current-class)]
              (resolve-generic-type (:field-type field-member)
                                    (member-type-map env target-type type-map field-member))
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
                                    {:error (type-error
                                             (undefined-field-message
                                              env base-type method current-class
                                              from-pattern))})))
                  "Any")))
            (if with-java?
              "Any"
              (if (and class-def (not (:import class-def)))
                (if-let [method-sig (and (not (and from-across (= "cursor" method)))
                                         (lookup-class-method-any-arity env base-type method current-class))]
                  (let [msg (str "Method " method " on " base-type
                                 " expects " (count (:params method-sig))
                                 " argument(s), got " (count args))]
                    (throw (ex-info msg {:error (type-error msg)})))
                  (let [msg (if (and from-across (= "cursor" method))
                              (across-target-message base-type)
                              (str "Method not found: " method))]
                    (throw (ex-info msg {:error (type-error msg)}))))
                (let [msg (if (and from-across (= "cursor" method))
                            (across-target-message base-type)
                            (str "Method not found: " method))]
                  (if (and from-across (= "cursor" method))
                    (throw (ex-info msg {:error (type-error msg)}))
                    "Any"))))))))))

(defn- check-target-call
  [env {:keys [target method has-parens] :as expr}]
  (let [call-info (resolve-call-target-info env expr)]
    (reject-undefined-target! env expr call-info)
    (reject-unguarded-detachable-target! env expr call-info)
    (cond
      (and (map? target) (= :super (:type target)))
      (check-super-call env expr call-info)

      (:across-item-type call-info)
      (check-across-item-call env expr call-info)

      (and (:class-target call-info) (false? has-parens))
      (check-class-constant-access env expr call-info)

      (and (= (:base-type call-info) "Array") (= method "sort"))
      (check-array-sort-call env expr call-info)

      (and (= (:base-type call-info) "Function")
           (map? (:target-type call-info))
           (:return-type (:target-type call-info))
           ;; A nil method is the walker's shape for calling an expression's
           ;; result directly — `a(1)(2)(3)`: each call past the first has no
           ;; identifier to attach as `:method`, only a `:target` that is
           ;; itself the previous call (see :postfix's "Call on expression
           ;; result: (expr)(...)" case). That's exactly a callN invocation
           ;; on a Function-typed target, same as the explicit `f.call1(x)`
           ;; spelling below — just without the name.
           (or (nil? method) (re-matches #"call\d+" (str method))))
      (check-typed-function-call env expr call-info)

      :else
      (check-general-target-call env expr call-info))))

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
    (when (any-into-concrete-without-convert? env "Integer" arg-type)
      (throw-any-narrowing-error! "the sleep argument" "Integer"))
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
      (when (any-into-concrete-without-convert? env "Function" handler-type)
        (throw-any-narrowing-error! (str "the " name " handler argument") "Function"))
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
   "exit"    (builtin-single-arg "exit" "Integer" "Void")
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
      ;; A bare call to a name interned from more than one file — reject it
      ;; here, before either the builtin-checker or env-lookup-var lookup
      ;; below would silently resolve to whichever fn-def happened to
      ;; register last (see check-program's function-variable registration
      ;; loop). A builtin name is never in :ambiguous-functions (only
      ;; interned user fn-defs populate it), so this can't misfire there.
      (do
        (when-let [qualified-names (get @(:ambiguous-functions (env-root env)) method)]
          (throw (ambiguous-function-reference-error method qualified-names)))
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
        (let [generic-names (set (concat (map :name (:generic-params class-def))
                                         ;; A Function-typed variable holding an
                                         ;; anonymous function declared with its own
                                         ;; type params (`fn[T](x: T): T`) carries
                                         ;; those directly (see
                                         ;; check-expr-anonymous-function) rather
                                         ;; than through base-type's class-def --
                                         ;; "Function" itself has none -- so they
                                         ;; must be unioned in here too, or T below
                                         ;; is never recognized as inferable and
                                         ;; every call reports it as unresolved.
                                         (map :name (:generic-params var-type))))
              arg-types (mapv #(check-expression env %) args)
              ;; A Function-typed variable carries its own declared signature
              ;; (e.g. `Function(Dog): String`), which is more specific than
              ;; the generic call<N> method registered by
              ;; register-function-call-methods! (Any-typed params, used only
              ;; so *some* callN resolves for arbitrary arity). Prefer the
              ;; variable's own param types here so call-site arguments are
              ;; checked against the declared signature instead of Any.
              effective-params (if (and (map? var-type)
                                        (= "Function" (:base-type var-type))
                                        (:param-types var-type))
                                 (:param-types var-type)
                                 (:params method-sig))
              inferred-type-map (reduce (fn [acc [arg-type param]]
                                          (merge-inferred-generic-bindings
                                           env
                                           acc
                                           (infer-generic-type-map-from-arg
                                            env generic-names (:type param) arg-type)))
                                        {}
                                        (map vector arg-types effective-params))
              type-map (merge (build-generic-type-map env var-type)
                              inferred-type-map)]
          ;; An inferred binding must itself satisfy its generic parameter's
          ;; own declared constraint (`[G -> Animal]`) -- validate-generic-args
          ;; already does this for an EXPLICIT type argument (`Box[Dog]`), but
          ;; nothing did for a binding inferred from a call's own arguments
          ;; (`describe(42)` against `describe[G -> Animal](x: G)` type-checked
          ;; with G bound to Integer, which doesn't satisfy Animal).
          (doseq [{:keys [name constraint]} (concat (:generic-params class-def) (:generic-params var-type))
                  :when constraint
                  :let [gname (type-name-string name)
                        constraint (type-name-string constraint)
                        bound (get type-map gname)]
                  :when bound]
            (when-not (types-compatible? env bound constraint)
              (throw (ex-info (str "Argument type " (display-type bound)
                                   " does not satisfy constraint " constraint
                                   " for generic parameter " gname)
                              {:error (type-error
                                       (str "Argument type " (display-type bound)
                                            " does not satisfy constraint " constraint
                                            " for generic parameter " gname))}))))
          (when (not= (count args) (count effective-params))
            (throw (ex-info (str "Method " call-name " expects " (count effective-params)
                                 " arguments, got " (count args))
                            {:error (type-error
                                     (str "Method " call-name " expects " (count effective-params)
                                          " arguments, got " (count args)))})))
          (doseq [[arg-type param] (map vector arg-types effective-params)]
            (let [param-type (resolve-generic-type (:type param) type-map)]
            (when (and (is-generic-type-param? env param-type)
                       (not (contains? type-map param-type))
                       ;; Calling a Function(T)-typed *parameter* from inside
                       ;; the very generic scope that binds T (`each[T](a:
                       ;; Array[T], f: Function(T): T) do ... f(elem) ... end`,
                       ;; elem: T) needs no instantiation at all -- the
                       ;; argument already IS that scope's T, not some
                       ;; concrete type standing in for it. Only an argument
                       ;; whose type actually differs from the declared
                       ;; (still-unresolved) param type represents a real,
                       ;; failed inference.
                       (not= arg-type param-type))
              (throw (ex-info (str "Could not infer generic type parameter " param-type
                                   " for function " method)
                              {:error (type-error
                                       (str "Could not infer generic type parameter "
                                            param-type
                                            " for function "
                                            method))})))
              (when (any-into-concrete-without-convert? env param-type arg-type)
                (throw-any-narrowing-error! (str "parameter of " method) param-type))
              (when-not (types-compatible? env arg-type param-type)
                (throw (ex-info (str "Argument type mismatch for method " call-name)
                                {:error (type-error
                                         (str "Expected " (display-type param-type) ", got " (display-type arg-type)))})))))
          ;; A Function value carrying an explicit signature knows its own return
          ;; type; prefer it over the generic callN result (which is Any). Still
          ;; resolve it through type-map: when that signature is itself generic
          ;; (`let id := fn[T](x: T): T ...`), the raw declared return type is
          ;; the unbound "T", not this call's actual inferred type -- without
          ;; this, `let y: Integer := id(10)` reported the call's type as the
          ;; literal name "T" instead of the "Integer" this call resolved it to.
          (if (and (map? var-type)
                   (= "Function" (:base-type var-type))
                   (:return-type var-type))
            (resolve-generic-type (:return-type var-type) type-map)
            ;; Coalesce a missing :return-type to "Void" — see
            ;; check-call-signature's identical fix for why a bare nil here
            ;; would slip past check-expression's Void-as-value guard.
            (or (resolve-generic-type (:return-type method-sig) type-map) "Void"))))
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
                  (when (any-into-concrete-without-convert? env (:type param) arg-type)
                    (throw-any-narrowing-error! (str "parameter '" (:name param) "' of " method) (:type param)))
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
                                     (str "Undefined function: " method))}))))))))))

(defn- check-create-array
  [env {:keys [generic-args constructor args]}]
  (let [target-type (if (seq generic-args)
                      (do
                        (validate-generic-args env "Array" generic-args)
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
          (when (any-into-concrete-without-convert? env "Integer" size-type)
            (throw-any-narrowing-error! "the Array.filled size argument" "Integer"))
          (when-not (types-compatible? env size-type "Integer")
            (throw (ex-info "Array.filled requires Integer size"
                            {:error (type-error
                                     (str "Array.filled expects Integer size, got "
                                          (display-type size-type)))})))
          (when (any-into-concrete-without-convert? env elem-type value-type)
            (throw-any-narrowing-error! "the Array.filled value argument" elem-type))
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
                      {:error (type-error (str "Constructor not found: Array." constructor))})))))

(defn- check-create-console
  [_env _expr]
  "Console")

(defn- check-create-process
  [env {:keys [constructor args]}]
  (case constructor
    nil
    (do
      (when (seq args)
        (throw (ex-info "create Process expects no arguments"
                        {:error (type-error "create Process expects no arguments")})))
      "Process")

    "self"
    (do
      (when (seq args)
        (throw (ex-info "Process.self expects no arguments"
                        {:error (type-error "Process.self expects no arguments")})))
      "Process")

    "command"
    (do
      (when-not (<= 1 (count args) 2)
        (throw (ex-info "Process.command expects 1 or 2 arguments"
                        {:error (type-error "Process.command expects (String) or (String, Array[String])")})))
      (let [command-type (check-expression env (first args))]
        (when (any-into-concrete-without-convert? env "String" command-type)
          (throw-any-narrowing-error! "the Process.command command argument" "String"))
        (when-not (types-compatible? env command-type "String")
          (throw (ex-info "Process.command requires a String command"
                          {:error (type-error
                                   (str "Process.command expects String, got "
                                        (display-type command-type)))})))
        (when (= 2 (count args))
          (let [args-type (check-expression env (second args))
                expected {:base-type "Array" :type-params ["String"]}]
            (when (any-into-concrete-without-convert? env expected args-type)
              (throw-any-narrowing-error! "the Process.command args argument" expected))
            (when-not (types-compatible? env args-type expected)
              (throw (ex-info "Process.command requires Array[String] arguments"
                              {:error (type-error
                                       (str "Process.command expects Array[String], got "
                                            (display-type args-type)))}))))))
      "Process")

    (throw (ex-info (str "Constructor not found: Process." constructor)
                    {:error (type-error (str "Constructor not found: Process." constructor))}))))

(defn- check-create-min-heap
  [env {:keys [generic-args constructor args]}]
  (let [target-type (if (seq generic-args)
                      (do
                        (validate-generic-args env "Min_Heap" generic-args)
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
          (when (any-into-concrete-without-convert? env "Function" compare-type)
            (throw-any-narrowing-error! "the Min_Heap.from_comparator argument" "Function"))
          (when-not (types-compatible? env compare-type "Function")
            (throw (ex-info "Min_Heap.from_comparator requires a Function"
                            {:error (type-error
                                     (str "Min_Heap.from_comparator expects Function, got "
                                          (display-type compare-type)))}))))
        target-type)

      (throw (ex-info (str "Constructor not found: Min_Heap." constructor)
                      {:error (type-error (str "Constructor not found: Min_Heap." constructor))})))))

(defn- check-create-single-arg-atomic
  "Builds a `create <Class>.make(value)` checker for the non-generic atomic
   builtins (Atomic_Integer, Atomic_Integer64, Atomic_Boolean): all three
   share this one-arg-named-'make' shape, differing only in the class name
   (for error messages) and the expected argument type. Atomic_Reference is
   the odd one out (generic, detachable) and keeps its own checker below."
  [class-name expected-type]
  (fn [env {:keys [constructor args]}]
    (when-not (= constructor "make")
      (throw (ex-info (str "Constructor not found: " class-name "." constructor)
                      {:error (type-error (str "Constructor not found: " class-name "." constructor))})))
    (when-not (= 1 (count args))
      (throw (ex-info (str class-name ".make expects 1 argument")
                      {:error (type-error (str class-name ".make expects exactly 1 " expected-type " argument"))})))
    (let [arg-type (check-expression env (first args))]
      (when (any-into-concrete-without-convert? env expected-type arg-type)
        (throw-any-narrowing-error! (str "the " class-name ".make argument") expected-type))
      (when-not (types-compatible? env arg-type expected-type)
        (throw (ex-info (str class-name ".make requires " expected-type " initial value")
                        {:error (type-error
                                 (str class-name ".make expects " expected-type ", got "
                                      (display-type arg-type)))}))))
    class-name))

(defn- check-create-atomic-reference
  [env {:keys [generic-args constructor args]}]
  (let [target-type (if (seq generic-args)
                      (do
                        (validate-generic-args env "Atomic_Reference" generic-args)
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
      (when (any-into-concrete-without-convert? env maybe-elem arg-type)
        (throw-any-narrowing-error! "the Atomic_Reference.make argument" maybe-elem))
      (when-not (types-compatible? env arg-type maybe-elem)
        (throw (ex-info "Atomic_Reference.make initial value type mismatch"
                        {:error (type-error
                                 (str "Atomic_Reference.make expects "
                                      (display-type maybe-elem)
                                      ", got "
                                      (display-type arg-type)))})))
      (or target-type
          {:base-type "Atomic_Reference" :type-args [elem-type]}))))

(defn- check-create-channel
  [env {:keys [generic-args constructor args]}]
  (cond
    (nil? constructor)
    nil

    (= constructor "with_capacity")
    (do
      (when-not (= 1 (count args))
        (throw (ex-info "Channel.with_capacity expects 1 argument"
                        {:error (type-error "Channel.with_capacity expects exactly 1 Integer argument")})))
      (let [arg-type (check-expression env (first args))]
        (when (any-into-concrete-without-convert? env "Integer" arg-type)
          (throw-any-narrowing-error! "the Channel.with_capacity argument" "Integer"))
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

(def ^:private check-create-builtin-dispatch
  "class-name -> (fn [env expr] ...): the built-in-type half of
   `check-create`. A class name with no entry here falls through to
   `check-create-user-class`, which also handles Map/Set — registered as
   ordinary generic classes rather than special-cased here."
  {"Array"            check-create-array
   "Console"          check-create-console
   "Process"          check-create-process
   "Min_Heap"         check-create-min-heap
   "Atomic_Integer"   (check-create-single-arg-atomic "Atomic_Integer" "Integer")
   "Atomic_Integer64" (check-create-single-arg-atomic "Atomic_Integer64" "Integer")
   "Atomic_Boolean"   (check-create-single-arg-atomic "Atomic_Boolean" "Boolean")
   "Atomic_Reference" check-create-atomic-reference
   "Channel"          check-create-channel})

(defn- check-create-user-class
  [env {:keys [class-name generic-args constructor args]}]
  ;; Check if class exists
  (when-not (or (env-lookup-class env class-name) (builtin-type? class-name))
    (throw (ex-info (str "Undefined class: " class-name)
                    {:error (type-error (str "Undefined class: " class-name))})))
  (let [class-def (env-lookup-class env class-name)]
    (when (:deferred? class-def)
      (throw (ex-info (str "Cannot instantiate deferred class: " class-name)
                      {:error (type-error
                               (str "Cannot instantiate deferred class " class-name
                                    "; instantiate a concrete child class instead"))})))
    ;; Imported Java classes have no Nex constructor signatures; skip validation.
    (if (and class-def (:import class-def))
      (if (seq generic-args)
        (do (validate-generic-args env class-name generic-args)
            {:base-type class-name :type-args generic-args})
        class-name)
      (let [constructors (lookup-class-constructors env class-name)
            has-constructors? (seq constructors)
            ctor-name (or constructor "make")
            ctor-sig (lookup-class-method env class-name ctor-name)
            gparams (:generic-params class-def)
            arg-types (when (and (or constructor (seq args)) ctor-sig)
                        (mapv #(check-expression env %) args))
            ;; When type arguments are not written explicitly, infer them from
            ;; the constructor's argument types (`create Ok.make(5)` ->
            ;; Ok[Integer, Any]); parameters not mentioned by the constructor
            ;; stay `Any`. Explicit `[…]` remains authoritative.
            inferred-map (when (and (empty? generic-args) (seq gparams) ctor-sig (seq args))
                           (reduce (fn [acc [arg-type param]]
                                     (merge-inferred-generic-bindings
                                      env acc
                                      (infer-generic-type-map-from-arg
                                       env (set (map :name gparams)) (:type param) arg-type)))
                                   {}
                                   (map vector arg-types (:params ctor-sig))))
            target-type (cond
                          (seq generic-args)
                          (do (validate-generic-args env class-name generic-args)
                              {:base-type class-name :type-args generic-args})
                          (and (seq gparams) inferred-map)
                          {:base-type class-name
                           :type-args (mapv #(get inferred-map (:name %) "Any") gparams)}
                          :else class-name)
            type-map (build-generic-type-map env target-type)]
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
            (doseq [[arg-type param] (map vector arg-types params)]
              (let [param-type (resolve-generic-type (:type param) type-map)]
                (when (any-into-concrete-without-convert? env param-type arg-type)
                  (throw-any-narrowing-error! (str "parameter '" (:name param) "' of constructor "
                                                    class-name "." ctor-name)
                                              param-type))
                (when-not (types-compatible? env arg-type param-type)
                  (throw (ex-info (str "Argument type mismatch for constructor " class-name "." ctor-name)
                                  {:error (type-error
                                           (str "Expected " (display-type param-type) ", got " (display-type arg-type)))})))))))
        target-type))))

(defn check-create
  "Check the type of a create expression"
  [env {:keys [class-name] :as expr}]
  (if-let [handler (get check-create-builtin-dispatch class-name)]
    (handler env expr)
    (check-create-user-class env expr)))


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
    {:base-type "Set" :type-params ["Any"]}
    (let [first-type (check-expression env (first elements))]
      (doseq [elem (rest elements)]
        (let [elem-type (check-expression env elem)]
          (when-not (types-equal? env first-type elem-type)
            (throw (ex-info "Set elements must have same type"
                            {:error (type-error
                                     (str "Set elements must have same type, got "
                                          (display-type first-type) " and " (display-type elem-type)))})))))
      {:base-type "Set" :type-params [first-type]})))

(defn- check-expr-anonymous-function
  [env expr]
  (let [class-def (:class-def expr)
        ;; Written inside an instance method, this literal's `this`/bare
        ;; field or method access means the *enclosing* class's, not the
        ;; synthetic AnonymousFunction_N's own (which has none of those
        ;; members) — a plain independent check-class scopes
        ;; __current_class__ to the synthetic class and rejects it as
        ;; "Method not found". check-method already takes class-name as a
        ;; parameter distinct from the env it extends, so checking the
        ;; callN body directly under the enclosing class's name (exactly as
        ;; check-spawn already does for a spawn body, by simply not
        ;; overriding __current_class__ at all) resolves this with no new
        ;; machinery.
        enclosing-class (env-lookup-var env "__current_class__")]
    ;; Register the dynamic class definition in the type environment
    (collect-class-info env class-def)
    (if enclosing-class
      (check-method env enclosing-class
                    (some #(when (= :method (:type %)) %)
                          (feature-members class-def)))
      ;; No enclosing class (a top-level anonymous function): check the class
      ;; as before.
      (check-class env class-def))
    ;; Anonymous functions have distinct generated runtime classes, but their
    ;; stable static type is structural Function -- carrying the literal's own
    ;; param/return types (falling back to Any for anything left unannotated
    ;; and not patched in by an expected-type context) rather than collapsing
    ;; to the bare "Function" string. Erasing to bare "Function" here made
    ;; calling the value back (`transform(5)`, or a generic `id(10)`) type as
    ;; Any regardless of how fully-typed the literal actually was --
    ;; check-typed-function-call reads :return-type straight off this map, so
    ;; losing it here was strictly a precision gap, not a soundness escape
    ;; hatch: the signature was always known at the literal itself.
    (cond-> {:base-type "Function"
             :param-types (mapv (fn [p] {:name (:name p) :type (or (:type p) "Any")}) (:params expr))
             :return-type (or (:return-type expr) "Any")}
      (seq (:generic-params expr)) (assoc :generic-params (:generic-params expr)))))

(defn- patch-anonymous-function-types
  "Return EXPR (an `:anonymous-function` node) with every param whose `:type`
   is nil filled in from PARAM-TYPES (positional), and a nil `:return-type`
   filled in from RETURN-TYPE. An already-declared type is left untouched, so
   this is a no-op for a fully-annotated `fn(...)`. Patches both the node's
   own `:params`/`:return-type` and the mirrored copies inside its embedded
   `:class-def`'s single method member — `check-expr-anonymous-function`
   reads only the latter (via `collect-class-info`/`check-method`), so both
   must agree or the check would run against the stale, unpatched signature."
  [expr param-types return-type]
  (let [patched-params (mapv (fn [p t] (if (:type p) p (assoc p :type t)))
                             (:params expr) param-types)
        patched-return (or (:return-type expr) return-type)
        patch-method (fn [m] (assoc m :params patched-params :return-type patched-return))
        patch-class-def
        (fn [class-def]
          (update class-def :body
                  (fn [sections]
                    (mapv (fn [section]
                            (if (= :feature-section (:type section))
                              (update section :members
                                      #(mapv (fn [m] (cond-> m (= :method (:type m)) patch-method)) %))
                              section))
                          sections))))]
    (-> expr
        (assoc :params patched-params :return-type patched-return)
        (update :class-def patch-class-def))))

(defn- check-expr-when
  [env expr]
  (let [cond-type (check-expression env (:condition expr))
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
    result-type))

(defn- check-expr-old
  [env expr]
  (check-expression env (:expr expr)))

(defn- check-expr-this
  [env _expr]
  (or (env-lookup-var env "__current_class__") "Any"))

;; `super`'s type is its resolved parent class, so a call/field access
;; through it type-checks through the ordinary machinery for a value of a
;; known class type — the same one `this` uses above. Non-virtual dispatch
;; to that parent's own implementation (rather than whatever overrides it)
;; is a lowering/interpreter concern, not a typechecking one; see
;; `resolve-super-parent-class-name`.
(defn- check-expr-super
  [env _expr]
  (resolve-super-parent-class-name env (env-lookup-var env "__current_class__")))

(def ^:private check-expression-dispatch
  "AST node `:type` -> `(fn [env expr] -> type)`: the map-shaped half of
   `check-expression`'s dispatch, consulted once `expr` is known to be a
   map (see the outer `cond` in `check-expression`, which handles nil/
   string/number/boolean shapes first). All the delegate functions here are
   already defined above this point in the file, so — unlike
   `infer-type-dispatch`'s `:call` entry in lower.clj — none need wrapping
   to dodge an as-yet-Unbound forward declare. A `:type` with no entry here
   falls through to \"Any\", matching the case's original trailing default."
  {:integer            check-literal
   :real               check-literal
   :string             check-literal
   :char               check-literal
   :boolean            check-literal
   :nil                check-literal
   :identifier         check-identifier
   :binary             check-binary-op
   :unary              check-unary-op
   :call               check-call
   :create             check-create
   :array-literal      check-array-literal
   :set-literal        check-set-literal
   :map-literal        check-map-literal
   :anonymous-function check-expr-anonymous-function
   :when               check-expr-when
   :old                check-expr-old
   :convert            check-convert
   :attached-test      check-attached-test
   :spawn              check-spawn
   :this               check-expr-this
   :super              check-expr-super})

(defn- check-expression-value
  "The raw dispatch behind check-expression, with no Void guard. Used only by
   check-statement's :call case: a Void-returning method/function call used
   as a bare statement is the one legitimate way to produce Void in
   Nex — its result is deliberately discarded, never consumed as a value —
   so that case routes through this function directly instead of the
   Void-rejecting check-expression below."
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
        (if-let [handler (get check-expression-dispatch (:type expr))]
          (handler env expr)
          "Any")
        :else "Any"))))

(defn check-expression
  "Check the type of an expression used as a value. Rejects Void: a
   Void-returning call has nothing meaningful to hand back, and using one as
   a value (a print/call argument, a let/assignment source, an operand, ...)
   used to reach the compiled backend and crash it outright (a builtin or
   concurrency Void method reports a real stack value only when its
   Nex-level type isn't Void; see emit-array-method-remove! and
   emit-concurrency-return! in compiler/jvm/emit.clj) instead of failing
   here with a clear message. Every recursive call site inside the
   check-expression-dispatch handlers refers to this function by name, so
   the guard applies uniformly to every sub-expression, not just top-level
   ones. The sole exception is check-statement's :call case, which calls
   check-expression-value directly.

   The guard only fires for a real (non-nil) expr: check-expression-value's
   own `(nil? expr) \"Void\"` clause isn't reporting a Void-typed value at
   all — it's a sentinel for \"no expression here\" (e.g. a free function
   call's absent :target), and infer-expression-type/lower.clj's infer-type
   fallback depends on getting that \"Void\" back without an exception to
   keep inferring the rest of the expression. Rejecting it here turned that
   harmless absent-expression signal into a hard 'Unable to infer expression
   type during lowering' failure for perfectly valid programs (e.g. a safe
   call whose argument chains through a free function call)."
  [env expr]
  (let [t (check-expression-value env expr)]
    (when (and (some? expr) (= t "Void"))
      (throw (ex-info "Cannot use a Void expression as a value"
                      {:error (type-error "Cannot use a Void expression as a value")})))
    t))


;;
;; Statement Type Checking
;;

(declare check-statement)
(declare check-expression-with-expected)

(defn check-expression-with-expected
  "Check an expression against an expected type when contextual typing matters,
   especially for collection literals with annotated target types."
  [env expr expected-type]
  (let [expected-type (normalize-type (expand-type-aliases env expected-type))]
    (cond
      (and (map? expr)
           (= :array-literal (:type expr))
           (map? expected-type)
           (= (:base-type expected-type) "Array")
           (= 1 (count (:type-params expected-type))))
      (let [elem-type (first (:type-params expected-type))]
        (doseq [elem (:elements expr)]
          (let [actual-elem-type (check-expression-with-expected env elem elem-type)]
            (when (any-into-concrete-without-convert? env elem-type actual-elem-type)
              (throw-any-narrowing-error! "an array element" elem-type))
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
            (when (any-into-concrete-without-convert? env expected-key-type actual-key-type)
              (throw-any-narrowing-error! "a map key" expected-key-type))
            (when-not (types-compatible? env actual-key-type expected-key-type)
              (throw (ex-info "Map keys must have consistent types"
                              {:error (type-error
                                       (str "Cannot assign " (display-type actual-key-type)
                                            " to map key type " (display-type expected-key-type)))})))
            (when (any-into-concrete-without-convert? env expected-val-type actual-val-type)
              (throw-any-narrowing-error! "a map value" expected-val-type))
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
            (when (any-into-concrete-without-convert? env elem-type actual-elem-type)
              (throw-any-narrowing-error! "a set element" elem-type))
            (when-not (types-compatible? env actual-elem-type elem-type)
              (throw (ex-info "Set elements must have same type"
                              {:error (type-error
                                       (str "Set elements must have same type, got "
                                            (display-type elem-type) " and " (display-type actual-elem-type)))})))))
        expected-type)

      ;; `fn(item) do ... end` — an anonymous function with some or all of its
      ;; parameter types (and/or its return type) omitted, checked against a
      ;; structural `Function(...)` target. This is the only place such an
      ;; omission can be resolved: `fn`'s params/return-type are fixed once,
      ;; at the source-level literal, so the *first* (and, in practice, only)
      ;; context that ever sees this literal alongside a concrete expected
      ;; type is here — a typed `let` (`check-let` already calls this
      ;; function with the declared type) or a call argument (see
      ;; `check-call-signature`, updated to do the same per-argument). Once
      ;; checked, this returns EXPECTED-TYPE itself (matching the
      ;; array/map/set-literal branches above) rather than the generic bare
      ;; "Function" `check-expr-anonymous-function` would otherwise report,
      ;; both because that's already known compatible and because it carries
      ;; the concrete signature a caller further up (e.g. another `let` that
      ;; copies this one without its own annotation) might still need.
      (and (map? expr)
           (= :anonymous-function (:type expr))
           (map? expected-type)
           (= (:base-type expected-type) "Function")
           (:param-types expected-type))
      (let [expected-params (:param-types expected-type)
            own-params (:params expr)]
        (when (not= (count own-params) (count expected-params))
          (throw (ex-info "Anonymous function parameter count does not match expected Function type"
                          {:error (type-error
                                   (str "Expected a Function with " (count expected-params)
                                        " parameter" (if (= 1 (count expected-params)) "" "s")
                                        ", got one with " (count own-params) "."))})))
        (let [patched (patch-anonymous-function-types expr
                                                       (mapv :type expected-params)
                                                       (:return-type expected-type))
              ;; A param/return already declared on the literal itself is left
              ;; untouched by patch-anonymous-function-types (only nil slots
              ;; are filled from EXPECTED-TYPE) — so a fully- or partially-
              ;; typed `fn` can still disagree with the context it's being
              ;; checked against (e.g. a nested `Function(...)` return type
              ;; that doesn't match a scalar expected return). Comparing the
              ;; patched signature back against EXPECTED-TYPE here catches
              ;; that instead of silently trusting the arity check above.
              actual-type {:base-type "Function"
                          :param-types (mapv (fn [p] {:name (:name p) :type (:type p)})
                                             (:params patched))
                          :return-type (:return-type patched)}]
          (when-not (types-compatible? env actual-type expected-type)
            (throw (ex-info "Anonymous function type does not match expected Function type"
                            {:error (type-error
                                     (str "Expected " (display-type expected-type)
                                          ", got " (display-type actual-type)))})))
          (check-expr-anonymous-function env patched))
        expected-type)

      :else
      (check-expression env expr))))

(defn any-into-concrete-without-convert?
  "True when narrowing an `Any`-typed VAL-TYPE directly into a concrete
   scalar/class TARGET-TYPE would happen implicitly. `Any` is deliberately a
   wildcard everywhere else in the checker (TYPES-EQUAL?'s Any-matches-
   anything rule, generic partial inference like `Ok[Integer, Any]` matching
   `Ok[Integer, String]`, and the `Map[String, Any]`/`Array[Any]` container
   idiom used throughout JSON-shaped code) -- but at the point a value is
   actually bound to a *named* scalar or class type, accepting an Any value
   implicitly would silently defeat that type the same way an unchecked class
   downcast would (`let b: B := an_instance_of_a` is already rejected).
   Narrowing must go through the same explicit mechanism a class downcast
   already requires: `convert ... to ...: T` (or `?attached-test`).
   Compound/parameterized targets (Array[...], Map[...], a generic class) are
   deliberately left alone here -- they keep today's permissive Any-element
   behavior, since a read out of them still needs its own convert to reach a
   concrete type (this is exactly what makes `result := node.get(\"amount\")`
   unsound but `let m: Map[String, Any] := json.parse(text)` fine).
   A `with \"java\" do ... end` block is also exempt: everything unresolved
   inside one is deliberately typed Any (the java-interop dynamic escape
   hatch -- see `__with_java__` in check-statement's :with case), and its
   whole documented idiom is recovering a concrete type right there, e.g.
   `let t: Thread := Thread.new(task)`. That's a different kind of Any than
   `Map[String, Any].get(...)` -- an interop boundary standing in for a type
   the checker simply doesn't track, not a genuinely-unknown runtime shape --
   so narrowing it implicitly doesn't defeat anything the checker could have
   caught anyway."
  [env target-type val-type]
  (and (not (env-lookup-var env "__with_java__"))
       (= (normalize-type (expand-type-aliases env val-type)) "Any")
       (let [tt (normalize-type (expand-type-aliases env target-type))]
         (and (string? tt)
              (not= tt "Any")
              (not (is-generic-type-param? env tt))))))

(defn- throw-any-narrowing-error!
  [what target-type]
  (throw (ex-info (str "Cannot implicitly narrow Any to " what)
                  {:error (type-error
                           (str "Cannot assign Any to " what " of type "
                                (display-type target-type)
                                " without narrowing it first. Use `convert ... to "
                                "<name>: " (display-type target-type) " then ... end`."))})))

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
      (if (and (env-lookup-var env "__current_class__")
               (env-lookup-global env target))
        (throw (ex-info (str "Cannot assign to global: " target)
                        {:error (type-error
                                 (str "'" target "' is a top-level global and is read-only "
                                      "inside a function or class body."))}))
        (throw (ex-info (str "Undefined variable: " target)
                        {:error (type-error (str "Undefined variable: " target))}))))
    (when (any-into-concrete-without-convert? env var-type val-type)
      (throw-any-narrowing-error! (str "variable '" target "'") var-type))
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
    (when (any-into-concrete-without-convert? env inferred-type val-type)
      (throw-any-narrowing-error! (str "variable '" name "'") inferred-type))
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
          (when (any-into-concrete-without-convert? env elem-type arg-type)
            (throw-any-narrowing-error! (str "the Channel." method " argument") elem-type))
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
  "Return the names of all classes in env that directly inherit from
   sealed-class-name. Reads :true-name over :name (falling back when absent)
   so a class also reachable through a qualified-only registration (Phase 3,
   :name there is the qualified string — see check-program's
   qualified-class-defs) is counted once, under its real bare name, not as an
   extra variant.

   Matches a heir's :parent through class-name-identity, not raw `=` — a
   heir declared with a QUALIFIED `inherit` clause (`inherit flex/Shape`,
   walked to the :parent string \"flex.Shape\") is exactly as much a variant
   of bare `Shape` as one declared `inherit Shape` directly, the same
   identity class-subtype? and ancestor-instantiation already normalize for
   ordinary and generic subtyping. Without this, such a heir was silently
   missing from `known` here, so check-match's exhaustiveness check never
   flagged it as an uncovered variant — a `match` on a sealed type could
   omit a real variant entirely and still compile, so long as that variant
   happened to be reached through a qualified `inherit`."
  [env sealed-class-name]
  (let [sealed-identity (class-name-identity env sealed-class-name)]
    (->> (visible-class-defs env)
         (filter (fn [class-def]
                   (some #(= (class-name-identity env (:parent %)) sealed-identity)
                         (:parents class-def))))
         (map #(or (:true-name %) (:name %)))
         set)))

(defn match-clause-binding-type
  "Reconstruct a match clause's type with generic arguments carried over from the
  matched subject. Given subject `Parent[A…]` and clause class `C` declared
  `C[G…] inherit Parent[P…]`, map each `Gᵢ` to the subject arg at the position
  where `Pⱼ = Gᵢ`. Falls back to the bare class name when it cannot be resolved
  (raw subject, unknown class, or an indirect/ mismatched inherit)."
  [env subject-type class-name]
  (let [subject (normalize-type subject-type)
        subject-base (if (map? subject) (:base-type subject) subject)
        subject-args (when (map? subject) (or (:type-args subject) (:type-params subject)))
        class-def (when (string? class-name) (env-lookup-class env class-name))
        gparams (map :name (:generic-params class-def))]
    (if (and (seq subject-args) (seq gparams))
      (let [parent-entry (some #(when (= (:parent %) subject-base) %)
                               (:parents class-def))
            parent-args (map #(if (map? %) (:base-type %) %) (:generic-args parent-entry))]
        (if (and parent-entry (= (count parent-args) (count subject-args)))
          (let [name->arg (zipmap parent-args subject-args)]
            {:base-type class-name
             :type-args (mapv #(get name->arg % "Any") gparams)})
          class-name))
      class-name)))

(defn check-match
  "Type-check a match statement over a sealed type."
  [env {:keys [expr clauses else]}]
  (let [expr-type (check-expression env expr)
        base-type-name (if (map? expr-type) (:base-type expr-type) expr-type)
        class-def (env-lookup-class env base-type-name)
        sealed? (and class-def (:sealed? class-def))]
    (doseq [{:keys [class-name var-name bindings guard body generic-args]} clauses]
      (when-not (or (= class-name base-type-name)
                    (class-subtype? env class-name base-type-name))
        (throw (ex-info (str "Match clause type " class-name
                             " is not a subclass of " base-type-name)
                        {:error (type-error
                                 (str class-name " is not a subclass of "
                                      base-type-name))})))
      (let [clause-env (make-type-env env)
            ;; Carry the subject's type arguments onto the bound variable so
            ;; `o.field` resolves with the real element types. An explicit
            ;; `C[...]` on the clause wins over inference.
            binding-type (if (seq generic-args)
                           {:base-type class-name :type-args generic-args}
                           (match-clause-binding-type env expr-type class-name))]
        (env-add-var clause-env var-name binding-type)
        (env-mark-non-nil clause-env var-name)
        ;; Destructure bindings come into scope before the guard and body.
        (doseq [b bindings]
          (check-statement clause-env b))
        (when guard
          (let [guard-type (check-expression clause-env guard)]
            (when-not (types-compatible? clause-env guard-type "Boolean")
              (throw (ex-info "Match guard must be Boolean"
                              {:error (type-error
                                       (str "Match guard must be Boolean, got "
                                            (display-type guard-type)))})))
            ;; The body runs only when the guard held, so refine it: a
            ;; `convert … to v: T` guard makes `v` a non-nil `T` in the body
            ;; (this is what lets a nested pattern narrow a field).
            (apply-condition-branch-refinement! clause-env guard :then)))
        (doseq [s body]
          (check-statement clause-env s))))
    (when else
      (doseq [s else] (check-statement env s)))
    (when (and sealed? (not else))
      ;; A guarded clause may not fire, so it does not cover its variant.
      ;; Resolved through env-lookup-class rather than read off the clause's
      ;; :class-name literally, so a qualified clause (`when finance/Ok(...)`,
      ;; docs/proposals/namespaces.md Phase 3 — walked to "finance.Ok") is
      ;; normalized to the same true bare identity ("Ok") find-sealed-subclasses
      ;; already reports in `known`; otherwise a qualified clause that in fact
      ;; covers its variant would still be flagged missing.
      (let [covered (set (keep (fn [{:keys [class-name]}]
                                 (let [cd (env-lookup-class env class-name)]
                                   (or (:true-name cd) (:name cd) class-name)))
                               (remove :guard clauses)))
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
          ;; A bare-statement call is the one place a Void-returning call is
          ;; legitimate — its result is discarded, not used as a value — so
          ;; this goes through check-expression-value directly rather than
          ;; the Void-rejecting check-expression.
          :call (check-expression-value env stmt)
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
          :assert
          (doseq [{:keys [label condition]} (:assertions stmt)]
            (let [cond-type (check-expression env condition)]
              (when-not (= cond-type "Boolean")
                (throw (ex-info (str "assert condition must be Boolean"
                                     (when label (str " in '" label "'")))
                                {:error (type-error
                                         (str "assert condition must be Boolean, got "
                                              cond-type))})))))
          :member-assign
          (let [field-name (:field stmt)
                object-expr (:object stmt)
                current-class (env-lookup-var env "__current_class__")
                ;; `super.field := v` — `super` has its own node type, like
                ;; `this`. Detected directly here (rather than going through
                ;; the generic `check-expression env target-expr` call below,
                ;; which would now happily type it as the parent class — see
                ;; the `:super` case in `check-expression`) because the
                ;; field-access checks below must run as the resolved
                ;; *parent*, not the lexically current class — the point of
                ;; `super.field := v` is writing a field only that ancestor
                ;; could otherwise touch directly.
                super-target? (and (map? object-expr) (= :super (:type object-expr)))
                super-parent-name (when super-target?
                                    (resolve-super-parent-class-name env current-class))
                target-expr (or object-expr {:type :this})
                class-name (if super-target?
                             super-parent-name
                             (let [target-type (check-expression env target-expr)
                                   base-target-type (attachable-type target-type)]
                               (if (map? base-target-type)
                                 (:base-type base-target-type)
                                 base-target-type)))
                caller-class (if super-target? super-parent-name current-class)
                _ (when-not class-name
                    (throw (ex-info "Field assignment target must be an object"
                                    {:error (type-error "Field assignment target must be an object")})))
                _ (when (lookup-class-constant env class-name field-name)
                    (throw (ex-info (str "Cannot assign to constant: " field-name)
                                    {:error (type-error (str "Cannot assign to constant: " field-name))})))
                field-member (lookup-class-field-member env class-name field-name caller-class)
                _ (when (and (:once? field-member)
                             (not (env-lookup-var env "__in_constructor__")))
                    (throw (ex-info (str "Cannot assign to once field outside constructor: " field-name)
                                    {:error (type-error (str "'" field-name "' is a once field and can only be assigned in a constructor"))})))
                field-type (:field-type field-member)
                val-type (check-expression env (:value stmt))]
            (when-not field-type
              (throw (ex-info (str "Undefined field: " field-name)
                              {:error (type-error
                                       (undefined-field-message
                                        env class-name field-name caller-class nil))})))
            (when-not (= caller-class (:declaring-class field-member))
              (throw (ex-info (str "Cannot assign to field " field-name)
                              {:error (field-write-error field-name (:declaring-class field-member))})))
            (when (any-into-concrete-without-convert? env field-type val-type)
              (throw-any-narrowing-error! (str "field '" field-name "'") field-type))
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
  (let [;; A branch/clause that cannot complete normally (it always raises or
        ;; retries) contributes no path that falls through to the rest of the
        ;; routine, so it need not assign result itself. Shared by every
        ;; multi-branch construct below (:if, :select, :case, :match) so a
        ;; raising/retrying branch never has to also assign result.
        branch-out (fn [body]
                     (or (not (body-may-complete-normally? body))
                         (result-definitely-assigned-in-body? body assigned?)))]
    (case (:type stmt)
      :assign (if (#{"result" "Result"} (:target stmt)) true assigned?)
      :let (if (#{"result" "Result"} (:name stmt)) true assigned?)
      :if (let [branch-outs (concat
                             [(branch-out (:then stmt))]
                             (map #(branch-out (:then %)) (:elseif stmt))
                             [(if (:else stmt)
                                (branch-out (:else stmt))
                                assigned?)])]
            (every? true? branch-outs))
      :loop (result-definitely-assigned-in-body? (:init stmt) assigned?)
      :select (let [clause-outs (map #(branch-out (:body %)) (:clauses stmt))
                    timeout-out (when-let [timeout (:timeout stmt)]
                                  (branch-out (:body timeout)))
                    else-out (when-let [else-body (:else stmt)]
                               (branch-out else-body))
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
      :case (let [clause-outs (map #(branch-out (:body %)) (:clauses stmt))
                  else-out (if-let [else-body (:else stmt)]
                             (branch-out else-body)
                             assigned?)]
              (every? true? (concat clause-outs [else-out])))
      :match (let [clause-outs (map #(branch-out (:body %)) (:clauses stmt))
                   ;; A match with no `else` that type-checked is exhaustive over a
                   ;; sealed type (the exhaustiveness check rejects it otherwise), so
                   ;; there is no fall-through path — every value hits a clause.
                   else-out (if-let [else-body (:else stmt)]
                              (branch-out else-body)
                              true)]
               (every? true? (concat clause-outs [else-out])))
      assigned?)))

(defn- result-definitely-assigned-in-body?
  "BODY is usually a vector of statements, but a `case`/`match` clause (or its
   `else`) with no `do...end` is a single bare statement map, not a
   one-element vector (see `nex.walker`) — `reduce`ing a map directly walks
   its own key/value pairs as `[k v]` tuples instead of treating it as one
   statement, so `(:type stmt)` never matched anything and a clause's own
   `result := ...` was invisible to this analysis. Normalizing here, once,
   keeps every caller (and `body-may-complete-normally?` below) agnostic to
   which shape a given clause happened to parse as."
  [body assigned?]
  (reduce (fn [acc stmt]
            (result-definitely-assigned-after-stmt stmt acc))
          assigned?
          (if (map? body) [body] body)))

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
  "See `result-definitely-assigned-in-body?`'s docstring: BODY may be a single
   bare statement map (a `case`/`match` clause with no `do...end`), not a
   vector. Normalized the same way, or a one-statement `raise`/`retry` clause
   silently read as `true` (falls through, completing normally) instead of
   `false`, by walking the statement's own map entries instead of the
   statement."
  [body]
  (loop [stmts (if (map? body) [body] body)]
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

(defn- check-distinct-methods!
  "Nex dispatches methods by name and argument count, so no two routines (or
   two constructors) of one class may share both a name and an arity. Same-name
   routines that differ in arity are permitted; a type-based overload is not."
  [class-name body]
  (let [routines (->> body
                      (filter #(= :feature-section (:type %)))
                      (mapcat :members)
                      (filter #(= :method (:type %))))
        constructors (->> body
                          (filter #(= :constructors (:type %)))
                          (mapcat :constructors))
        signature (fn [m] [(:name m) (count (or (:params m) []))])]
    (doseq [[kind members] [["routine" routines] ["constructor" constructors]]]
      (when-let [dup (first-duplicate (map signature members))]
        (let [[dup-name dup-arity] dup]
          (restriction-error!
           (str "Duplicate " kind " '" dup-name "' taking " dup-arity
                (if (= 1 dup-arity) " argument" " arguments")
                " in class '" class-name "'. Nex dispatches by name and argument "
                "count, so two " kind "s cannot share both a name and an arity; "
                "give them different arities or names.")))))))

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

(defn- require-declared-param-types!
  "Throw unless every param has a non-nil `:type`. A param's type is nil only
   when the source omitted it (`nexlang.g4`'s `param` production allows a
   bare identifier everywhere a typed param is legal, and the walker no
   longer defaults the missing type to \"Any\" — see `nex.walker`'s `:param`
   transform). For an ordinary method/constructor/free-function this is
   always a real error: there is exactly one declaration site, so there is
   nothing to infer a type *from*. For an anonymous function (`fn(...)`)
   omitting a type is meaningful — its class-def reaches `check-method` here
   only after `check-expression-with-expected` has already tried to fill
   every nil in from a surrounding `Function(...)`-typed context (a typed
   `let`, or the matching parameter of a call target) and patched the
   class-def accordingly; a nil that survives to here means no such context
   was available, so this is the single place that failure surfaces, for
   every caller of `check-method`."
  [class-name owner-kind owner-name params]
  (doseq [{:keys [name type]} params]
    (when (nil? type)
      (let [anonymous? (.startsWith ^String class-name "AnonymousFunction_")]
        (throw (ex-info (str "Parameter '" name "' of " owner-kind " '" owner-name "' has no declared type")
                        {:error (type-error
                                 (if anonymous?
                                   (str "Cannot infer the type of parameter '" name
                                        "' for this anonymous function. Either declare it explicitly "
                                        "(`fn(" name ": SomeType) ...`), or use the function where a "
                                        "concrete `Function(...)` type can be inferred from context "
                                        "(a typed `let`, or a call argument whose parameter is "
                                        "`Function(...)`-typed).")
                                   (str "Parameter '" name "' of " owner-kind " '" owner-name
                                        "' must declare a type.")))}))))))

(defn check-method
  "Check a method definition"
  [env class-name {:keys [name params return-type require body ensure rescue] :as method}]
  (check-distinct-parameters! params "routine" name)
  (check-old-and-retry! "routine" name params require body ensure)
  (require-declared-param-types! class-name "method" name params)
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
  (require-declared-param-types! class-name "constructor" name params)
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

(defn- bind-inherited-constants!
  "Seed TARGET-ENV with the constants CLASS-DEF inherits, nearest parent last so
   it wins. A constant's initializer may name one — `class Derived inherit Base`
   with `C = B + 1` — which is the only reason an initializer needs to see
   anything beyond its own class. Constants only: an inherited *field* has no
   value at collect time and is not in scope here."
  [target-env env class-def]
  (letfn [(walk [cn visited]
            (when (and cn (not (contains? visited cn)))
              (when-let [cd (env-lookup-class env cn)]
                (let [visited' (conj visited cn)]
                  (doseq [{:keys [parent]} (:parents cd)]
                    (walk parent visited'))
                  (doseq [m (feature-members cd)]
                    (when (and (= (:type m) :field)
                               (:constant? m)
                               (public-member? m)
                               (:field-type m))
                      (env-add-var target-env (:name m) (:field-type m))))))))]
    (doseq [{:keys [parent]} (:parents class-def)]
      (walk parent #{}))))

(defn collect-class-info
  "Collect class information (first pass)"
  [env {:keys [name body] :as class-def}]
  (env-add-class env name class-def)

  ;; Collect fields/constants and infer constant types.
  ;;
  ;; A constant's initializer may name an earlier constant of the same class
  ;; (`feature A = 1  B = A + 1`), so the names must be in scope to infer its
  ;; type — but only *here*. Binding them into `env` would make every field name
  ;; in the program, private ones included, a readable and assignable global
  ;; initialized to nil: `class Account feature balance: Integer end` was enough
  ;; to make a bare `print(balance)` typecheck at top level and evaluate to nil,
  ;; which is exactly the void the type system exists to rule out. Field access
  ;; inside a class is bound properly by `bind-visible-class-fields!` in
  ;; `check-class`, which also honours inherited-field visibility and generic
  ;; substitution — neither of which this pass could.
  (let [const-env (doto (make-type-env env)
                    (bind-inherited-constants! env class-def))
        updated-body
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
                                          (let [inferred-type (check-expression const-env (:value member))
                                                final-type (or (:field-type member) inferred-type)]
                                            (when (:field-type member)
                                              (validate-type-annotation const-env (:field-type member))
                                              (when (any-into-concrete-without-convert? const-env (:field-type member) inferred-type)
                                                (throw-any-narrowing-error! (str "constant '" (:name member) "'") (:field-type member)))
                                              (when-not (types-compatible? const-env inferred-type (:field-type member))
                                                (throw (ex-info (str "Type mismatch in constant " (:name member))
                                                                {:error (type-error
                                                                         (str "Cannot assign " (display-type inferred-type)
                                                                              " to constant '" (:name member)
                                                                              "' of type "
                                                                              (display-type (:field-type member))))}))))
                                            (env-add-var const-env (:name member) final-type)
                                            (assoc member :field-type final-type))
                                          (do
                                            (env-add-var const-env (:name member) (:field-type member))
                                            member))
                                        member)))
                                  members)))
                  section))
              body)
        updated-class-def (assoc class-def :body updated-body)]
    (env-add-class env name updated-class-def)

  ;; Collect method signatures. Reads updated-class-def directly rather than
  ;; re-fetching it via (env-lookup-class env name): when `name` is a bare
  ;; name flagged ambiguous (docs/proposals/namespaces.md, Phase 2), that
  ;; lookup throws — and this pass runs unconditionally over every class in
  ;; `visible-classes`, including the one that happens to win the bare-name
  ;; collapse, regardless of whether the program ever actually references
  ;; the bare name. Re-fetching through env-lookup-class turned that into an
  ;; eager, presence-based throw for every ambiguous pair, defeating the
  ;; reference-time design Phase 2 was built around; reading the local
  ;; binding restores it — collect-class-info has this class-def already, it
  ;; doesn't need to ask the (possibly ambiguous) registry for it back.
  (doseq [section (:body updated-class-def)]
    (cond
      (= (:type section) :feature-section)
      (doseq [member (:members section)]
        (when (= (:type member) :method)
          (env-add-method env name (:name member)
                         {:params (:params member)
                          :return-type (:return-type member)
                          :alias (:alias member)})))

      (= (:type section) :constructors)
      (doseq [ctor (:constructors section)]
        (env-add-method env name (:name ctor)
                       {:params (:params ctor)
                        :return-type name}))))))

(defn- java-type-parent
  "The reflected Class for parent-name, when it names an imported Java type
   (interface or class) rather than a Nex class. Nil for a Nex class or an
   unresolvable name. resolve-imported-java-class already returns nil for a
   real Nex class (its class-def has no :import and its bare name has no
   dot), so no extra guard is needed here."
  [env parent-name]
  (resolve-imported-java-class env parent-name))

(def ^:private object-instance-methods
  "[name arity] of java.lang.Object's own public instance methods. Some
   interfaces (Comparator is the standard example) redeclare equals(Object)
   purely for documentation; reflection reports it as abstract on the
   interface, but javac never requires an implementor to write it, since
   every class already inherits a concrete equals from Object regardless of
   what the interface says. Excluded here for the same reason."
  #{["equals" 1] ["hashCode" 0] ["toString" 0]})

(defn- java-interface-abstract-methods
  "[name arity] pairs for KLASS's abstract instance methods — the members a
   Nex class inheriting this interface/class must provide. Every non-default,
   non-static interface method is necessarily abstract, which is why Phase
   1 (interfaces only) worked without an explicit Modifier/isAbstract check —
   but Phase 2 also reflects concrete Java classes, whose non-static methods
   are mostly *not* abstract (Thread has none), so the check is required here
   to avoid demanding an override of every inherited concrete method. Also
   excludes Object's own methods (always already provided, see
   object-instance-methods)."
  [^Class klass]
  (->> (.getMethods klass)
       (remove (fn [^java.lang.reflect.Method m]
                 (let [mods (.getModifiers m)]
                   (or (.isDefault m)
                       (java.lang.reflect.Modifier/isStatic mods)
                       (not (java.lang.reflect.Modifier/isAbstract mods))))))
       (map (fn [^java.lang.reflect.Method m]
              [(.getName m) (alength (.getParameterTypes m))]))
       (remove object-instance-methods)
       distinct))

(defn- class-provides-method?
  "True when CLASS-NAME declares, or inherits through its Nex parent chain, a
   method member named METHOD-NAME with exactly ARITY params. Java-interface/
   -class parents contribute nothing here (they have no Nex feature members);
   only the Nex side of the inheritance chain is walked."
  [env class-name method-name arity]
  (letfn [(walk [cn visited]
            (when (and cn (not (contains? visited cn)))
              (when-let [class-def (env-lookup-class env cn)]
                (or (some (fn [m]
                            (and (= (:type m) :method)
                                 (= (:name m) method-name)
                                 (= (count (or (:params m) [])) arity)))
                          (feature-members class-def))
                    (some (fn [{:keys [parent]}] (walk parent (conj visited cn)))
                          (:parents class-def))))))]
    (boolean (walk class-name #{}))))

(defn- check-java-interface-conformance
  "For each `inherit` entry that resolves to an imported Java type — an
   interface (Phase 1) or a concrete/abstract class (Phase 2) — require the
   class to provide every abstract method the Java type declares, matched by
   exact Java name and arity (see the java-interop proposal's settled naming
   convention — no snake_case/camelCase bridging). A no-op for a concrete
   class with no abstract methods (Thread, JFrame, ...) — the vast majority
   of Phase 2's use cases."
  [env class-name parents]
  (doseq [{:keys [parent]} parents]
    (when-let [^Class klass (java-type-parent env parent)]
      (let [missing (->> (java-interface-abstract-methods klass)
                         (remove (fn [[m-name arity]]
                                   (class-provides-method? env class-name m-name arity))))]
        (when (seq missing)
          (throw (ex-info (str "Class " class-name " does not implement " parent)
                          {:error (type-error
                                   (str "Class " class-name " inherits " parent
                                        " but does not implement its abstract member(s): "
                                        (str/join ", "
                                                  (map (fn [[m-name arity]]
                                                         (str m-name "(" arity
                                                              (if (= arity 1) " arg)" " args)")))
                                                       missing))
                                        ". Declare a method with the exact Java name and arity"
                                        " for each."))})))))))

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
    ;; Check that parent class exists: a Nex class, a builtin, or an imported
    ;; Java interface or class (docs/proposals/java-interop.md). The
    ;; at-most-one-concrete-Java-class constraint (JVM single inheritance) is
    ;; checked once below, across the whole parents list, not per entry here.
    (let [parent-def (env-lookup-class env parent)
          real-nex-class? (and parent-def (not (:import parent-def)))
          java-class (when-not real-nex-class? (resolve-imported-java-class env parent))]
      (when-not (or real-nex-class? (builtin-type? parent) java-class)
        (throw (ex-info (str "Parent class " parent " not found for class " class-name)
                        {:error (type-error
                                 (str "Undefined parent class: " parent))}))))
    (when (= parent class-name)
      (throw (ex-info (str "Class " class-name " cannot inherit from itself")
                      {:error (type-error
                               (str "Class " class-name " cannot inherit from itself"))})))
    (when-let [path (cycle-path parent)]
      (throw (ex-info (str "Cyclic inheritance detected: " (str/join " -> " path))
                      {:error (type-error
                               (str "Cyclic inheritance detected: "
                                    (str/join " -> " path)))}))))
  (let [concrete-java-parents (->> parents
                                   (map :parent)
                                   (filter (fn [parent]
                                             (let [parent-def (env-lookup-class env parent)
                                                   real-nex-class? (and parent-def (not (:import parent-def)))]
                                               (when-let [^Class klass (and (not real-nex-class?)
                                                                            (resolve-imported-java-class env parent))]
                                                 (not (.isInterface klass)))))))]
    (when (< 1 (count concrete-java-parents))
      (throw (ex-info (str "Class " class-name " cannot extend more than one Java class")
                      {:error (type-error
                               (str "Class " class-name " inherits more than one concrete Java class ("
                                    (str/join ", " concrete-java-parents)
                                    ") — the JVM allows extending only one."))}))))
  (check-java-interface-conformance env class-name parents)))

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

(defn- all-deferred-method-keys
  "[[name arity] ...], distinct, for every declaration-only (`deferred`)
   method declared anywhere in class-name's own body or its ancestor chain —
   regardless of whether some other ancestor already overrides it. Paired
   with `effective-method-member` below to check that each one actually has
   a body somewhere between class-name and that declaration.

   `Function` (`nex.types.bootstrap/build-function-base-class`) is excluded:
   it deliberately declares call0..call32 all deferred as a menu of arity
   overloads, not a contract every implementor must fully satisfy — every
   function-value class (from a top-level `function`, an anonymous `fn`, or
   a `spawn` closure) inherits it and implements only the single call<N>
   matching its own arity, by design, forever leaving the other 32 deferred."
  [env class-name]
  (letfn [(walk [cn visited]
            (when (and cn (not= cn "Function") (not (contains? visited cn)))
              (let [class-def (env-lookup-class env cn)
                    visited' (conj visited cn)]
                (when class-def
                  (concat
                   (keep (fn [member]
                           (when (and (= (:type member) :method)
                                      (:declaration-only? member))
                             [(:name member) (count (or (:params member) []))]))
                         (feature-members class-def))
                   (mapcat (fn [{:keys [parent]}] (walk parent visited'))
                           (:parents class-def)))))))]
    (distinct (walk class-name #{}))))

(defn- effective-method-member
  "The feature member that answers method-name/arity when called on an
   instance of class-name: its own definition if it has one, otherwise the
   nearest ancestor's (see `inherited-method-member`). `:declaration-only?`
   on the result means nothing between class-name and the declaring
   ancestor supplies a body."
  [env class-name method-name arity]
  (let [class-def (env-lookup-class env class-name)
        own (when class-def
              (some (fn [member]
                      (when (and (= (:type member) :method)
                                 (= (:name member) method-name)
                                 (= (count (or (:params member) [])) arity))
                        member))
                    (feature-members class-def)))]
    (or own
        (when class-def
          (inherited-method-member env (:parents class-def) method-name arity)))))

;; Only a `deferred class` may leave a feature unimplemented — Nex's
;; Eiffel-style abstract-method contract. Without this check, a concrete
;; class that skips an inherited deferred method (or declares its own
;; `deferred` without being a deferred class) compiles and instantiates
;; fine, and only fails the first time the missing method is actually
;; called — as a raw JVM "Internal error" naming a synthetic
;; `__method_<name>$arityN` symbol, with nothing pointing back at the
;; missing override.
(defn- direct-function-value-class?
  "True for a class whose own `inherit` clause names the builtin `Function`
   base directly -- the shape every function-value class has, since walker.clj
   and lower.clj are the only places that ever write `:parents [{:parent
   \"Function\"}]` (a top-level `function`, an anonymous `fn`, a `spawn`
   closure, and a `declare function` forward-declaration stub)."
  [class-def]
  (boolean (some #(= "Function" (:parent %)) (:parents class-def))))

(defn- check-deferred-methods-implemented!
  [env class-name class-def]
  (when (and (not (:deferred? class-def))
             ;; A function-value class's own call<N> is exempt for two
             ;; reasons: `all-deferred-method-keys` already excludes the 32
             ;; other call arities Function declares but this class will
             ;; never implement, by design (see its docstring) -- but a
             ;; `declare function` forward-declaration stub's *own* call<N> is
             ;; itself still declaration-only at this point (its real `function
             ;; ... end` body arrives in a later statement/cell), which is
             ;; exactly as legitimate, not a class that will never be
             ;; completed.
             (not (direct-function-value-class? class-def)))
    (doseq [[m-name arity] (all-deferred-method-keys env class-name)]
      (when (:declaration-only? (effective-method-member env class-name m-name arity))
        (throw (ex-info (str "Class " class-name " does not implement deferred method " m-name)
                        {:error (type-error
                                 (str "Class '" class-name "' does not implement deferred method '"
                                      m-name "'. Provide a body for '" m-name
                                      "', or declare '" class-name "' itself 'deferred'."))}))))))

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

(defn- constructor-statements
  "STMT and every statement nested inside it. Void-safety asks two questions of
   a constructor body — which fields it assigns, and which parent constructors it
   calls — and both mean \"anywhere the constructor might run\", so both walk
   this. Note a branch counts even when only one arm takes it: the check is a
   guard against forgetting to initialize a field, not a proof that every path
   does."
  [stmt]
  (cons stmt
        (case (:type stmt)
          :if (mapcat constructor-statements
                      (concat (:then stmt)
                              (mapcat :then (:elseif stmt))
                              (:else stmt)))
          :loop (mapcat constructor-statements (concat (:init stmt) (:body stmt)))
          :scoped-block (mapcat constructor-statements
                                (concat (:body stmt) (:rescue stmt)))
          :with (mapcat constructor-statements (:body stmt))
          ;; A case clause's body is a single statement (:body is one node); a
          ;; match clause's is also one statement, but its :body is wrapped in
          ;; a vector (see :matchClause in walker.clj).
          :case (mapcat constructor-statements
                        (concat (keep :body (:clauses stmt))
                                (when-let [e (:else stmt)] [e])))
          :match (mapcat constructor-statements
                         (concat (mapcat :body (:clauses stmt)) (:else stmt)))
          nil)))

(defn- attachable-init-field-names
  "Names of the fields declared in BODY that a constructor must initialize: the
   attached (non-detachable) ones of a user-defined class type. A builtin scalar
   has a zero value and a detachable field is allowed to be void, so neither
   needs one."
  [env body]
  (->> body
       (filter #(= :feature-section (:type %)))
       (mapcat :members)
       (filter #(and (= :field (:type %)) (not (:constant? %))))
       (filter (fn [{:keys [field-type]}]
                 ;; Expand a type alias (`declare type Id = Integer`, or an
                 ;; `intern ... as` class alias) before classifying: since
                 ;; env-lookup-class now itself falls back through aliases
                 ;; (so a class alias correctly counts as the real class
                 ;; here), leaving field-type unexpanded would classify by
                 ;; the alias's literal name instead of its target — e.g.
                 ;; `builtin-type?` on the un-expanded name "Id" misses,
                 ;; wrongly treating an aliased scalar as a user class field
                 ;; that requires constructor initialization.
                 (let [t (normalize-type (expand-type-aliases env field-type))
                       a (attachable-type t)
                       base (if (map? a) (:base-type a) a)]
                   (and (not (detachable-type? t))
                        (string? base)
                        (some? (env-lookup-class env base))
                        (not (builtin-type? base))))))
       (map :name)
       set))

(defn- constructor-delegation-calls
  "Every `this.ctor(...)` call statement anywhere in CTOR-BODY, as
   [method-name arg-count] pairs — the same-class constructor delegation
   feature (see [[nex.lower/lower-call-stmt]])."
  [ctor-body]
  (->> (mapcat constructor-statements ctor-body)
       (keep (fn [stmt]
               (when (and (= :call (:type stmt))
                          (map? (:target stmt))
                          (= :this (:type (:target stmt))))
                 [(:method stmt) (count (:args stmt))])))))

(defn- constructor-initialized-fields
  "Attachable fields CTOR-NAME/CTOR-BODY is guaranteed to initialize: its own
   direct `field := v` / `this.field := v` assignments, plus — transitively —
   whatever a `this.ctor(...)` delegation call reaches. Without this, a
   constructor that only initializes its fields by delegating to a sibling
   constructor (`rare(...) do this.with_stats(...) ... end`) is wrongly
   flagged as never initializing them at all, even though every field ends up
   set on every path.

   ALL-CTORS is every constructor declared on this class, for resolving what
   a delegation call names (matched by name *and* arity, since two
   constructors may share a name). VISITED guards a delegation cycle
   (`this.a` calls `this.b` calls `this.a`) from recursing forever; a cycle
   is a user bug the checker need not chase further, so it just stops
   contributing there rather than looping."
  [ctor-name ctor-body all-ctors visited]
  (if (contains? visited ctor-name)
    #{}
    (let [own (->> (mapcat constructor-statements ctor-body)
                   (keep (fn [stmt]
                           (case (:type stmt)
                             :assign (:target stmt)
                             :member-assign (:field stmt)
                             nil)))
                   set)
          visited' (conj visited ctor-name)]
      (reduce (fn [acc [delegated-name arg-count]]
                (if-let [delegated-ctor (some #(when (and (= (:name %) delegated-name)
                                                          (= (count (or (:params %) [])) arg-count))
                                                %)
                                              all-ctors)]
                  (into acc (constructor-initialized-fields delegated-name (:body delegated-ctor)
                                                            all-ctors visited'))
                  acc))
              own
              (constructor-delegation-calls ctor-body)))))

(defn- needs-constructor-init?
  "True when CLASS-NAME declares an attachable field, or inherits one. Used to
   decide whether a subclass constructor has to chain to a parent's."
  ([env class-name] (needs-constructor-init? env class-name #{}))
  ([env class-name seen]
   (boolean
    (when-not (contains? seen class-name)
      (when-let [class-def (env-lookup-class env class-name)]
        (or (seq (attachable-init-field-names env (:body class-def)))
            (some #(needs-constructor-init? env (:parent %) (conj seen class-name))
                  (:parents class-def))))))))

(defn check-class
  "Check a class definition"
  [env {:keys [name body invariant parents generic-params] :as class-def}]
  (let [class-def (or (env-raw-class env name) class-def)
        body (:body class-def)
        invariant (:invariant class-def)
        parents (:parents class-def)
        class-env (make-type-env env)]
  (check-distinct-fields! name (:body class-def))
  (check-distinct-methods! name (:body class-def))
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
    (check-inheritance env name parents)
    (check-java-super-constructor-call env name
                                       (->> body
                                            (filter #(= :constructors (:type %)))
                                            (mapcat :constructors))))
  (check-deferred-methods-implemented! env name class-def)

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
  (let [constructors (->> body
                          (filter #(= :constructors (:type %)))
                          (mapcat :constructors))
        required-fields (attachable-init-field-names env body)
        ;; Parents that hold attachable fields of their own. A subclass cannot
        ;; assign an inherited field directly ("Cannot assign to field a outside
        ;; of class B"), so the only way it can initialize one is by calling that
        ;; parent's constructor — and the parent's own check guarantees every one
        ;; of its constructors initializes its fields, which makes checking the
        ;; direct parents enough to cover the whole chain.
        parents-needing-init (->> parents
                                  (map :parent)
                                  (filter #(needs-constructor-init? env %))
                                  set)]
    (when (or (seq required-fields) (seq parents-needing-init))
      (when (and (seq required-fields) (empty? constructors))
        (throw (ex-info (str "Class " name " has attachable fields that require constructor initialization")
                        {:error (type-error
                                 (str "Attachable fields must be initialized by constructors in class "
                                      name ": " (str/join ", " (sort required-fields))))})))
      ;; A class that declares no constructors of its own inherits its parent's,
      ;; which already initialize the inherited fields — nothing to check.
      (doseq [{ctor-name :name ctor-body :body} constructors]
        (let [statements (mapcat constructor-statements ctor-body)
              assigned (constructor-initialized-fields ctor-name ctor-body constructors #{})
              missing (sort (seq (set/difference required-fields assigned)))
              called-parents (->> statements
                                  (keep (fn [stmt]
                                          (when (= :call (:type stmt))
                                            (:target stmt))))
                                  set)
              uninitialized-parents (sort (seq (set/difference parents-needing-init
                                                               called-parents)))]
          (when (seq missing)
            (throw (ex-info (str "Constructor " ctor-name " does not initialize all attachable fields")
                            {:error (type-error
                                     (str "Constructor " ctor-name " must initialize attachable fields: "
                                          (str/join ", " missing)))})))
          (when (seq uninitialized-parents)
            (throw (ex-info (str "Constructor " ctor-name " does not initialize its inherited attachable fields")
                            {:error (type-error
                                     (str "Constructor " ctor-name " in class " name
                                          " must call a constructor of "
                                          (str/join ", " uninitialized-parents)
                                          " to initialize inherited attachable field(s): "
                                          (str/join ", "
                                                    (sort (mapcat #(attachable-init-field-names
                                                                    env (:body (env-lookup-class env %)))
                                                                  uninitialized-parents)))))})))))))))

;;
;; Program Type Checking
;;

;; A builtin method's static signature (params + return type) lives as
;; `:signatures` metadata on its implementation in nex.types.builtins'
;; `builtin-type-methods` — the same place the runtime behaviour and (for
;; Array/Map/Set/Task/Channel) the JVM bytecode-emission gate live, so the
;; three no longer drift the way push/at/size/first/last once did. A method
;; can have more than one signature (Array.sort, Task.await, Channel.send
;; are each overloaded on arity), hence a vector.
(defn- register-builtin-type-signatures!
  [env type-name]
  (doseq [[method-name fn-val] (get bi/builtin-type-methods (keyword type-name))
          sig (:signatures (meta fn-val))]
    (env-add-method env type-name method-name sig)))

(defn- register-any-protocol!
  [env]
  (env-add-class env "Any" {:name "Any"
                            :deferred? false
                            :generic-params nil
                            :parents nil
                            :body []})
  (register-builtin-type-signatures! env "Any"))

(defn- register-comparable-protocol!
  [env]
  (env-add-class env "Comparable" {:name "Comparable"
                                   :deferred? true
                                   :generic-params nil
                                   :parents nil
                                   :body []})
  (env-add-method env "Comparable" "compare"
                  {:params [{:name "a" :type "Any"}]
                   :return-type "Integer"}))

(defn- register-hashable-protocol!
  [env]
  (env-add-class env "Hashable" {:name "Hashable"
                                 :deferred? true
                                 :generic-params nil
                                 :parents nil
                                 :body []})
  (env-add-method env "Hashable" "hash"
                  {:params []
                   :return-type "Integer"}))

;; Built-in scalar classes implement Comparable + Hashable
(defn- register-scalar-classes!
  [env]
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
                     :return-type "Integer"})))

(defn- register-integer-methods!
  [env]
  (register-builtin-type-signatures! env "Integer"))

(defn- register-real-methods!
  [env]
  (register-builtin-type-signatures! env "Real"))

(defn- register-char-methods!
  [env]
  (register-builtin-type-signatures! env "Char"))

(defn- register-string-methods!
  [env]
  (register-builtin-type-signatures! env "String"))

(defn- register-console-methods!
  [env]
  (env-add-class env "Console" {:name "Console"
                                :generic-params nil})
  (register-builtin-type-signatures! env "Console"))

(defn- register-task-methods!
  [env]
  (env-add-class env "Task" {:name "Task"
                             :generic-params [{:name "T"}]})
  (register-builtin-type-signatures! env "Task"))

(defn- register-process-methods!
  [env]
  (env-add-class env "Process" {:name "Process"
                                :generic-params nil})
  (register-builtin-type-signatures! env "Process"))

(defn- register-array-methods!
  [env]
  (env-add-class env "Array" {:name "Array"
                               :generic-params [{:name "T"}]})
  (env-add-method env "Array" "filled"
                  {:params [{:name "size" :type "Integer"}
                            {:name "value" :type "T"}]
                   :return-type {:base-type "Array" :type-params ["T"]}})
  (register-builtin-type-signatures! env "Array"))

(defn- register-map-methods!
  [env]
  (env-add-class env "Map" {:name "Map"
                             :generic-params [{:name "K"} {:name "V"}]})
  (register-builtin-type-signatures! env "Map"))

(defn- register-set-methods!
  [env]
  (env-add-class env "Set" {:name "Set"
                            :generic-params [{:name "T"}]})
  (env-add-method env "Set" "from_array"
                  {:params [{:name "values"
                             :type {:base-type "Array" :type-params ["T"]}}]
                   :return-type {:base-type "Set" :type-params ["T"]}})
  (register-builtin-type-signatures! env "Set"))

(defn- register-min-heap-methods!
  [env]
  (env-add-class env "Min_Heap" {:name "Min_Heap"
                                 :generic-params [{:name "T"}]})
  (env-add-method env "Min_Heap" "empty"
                  {:params []
                   :return-type {:base-type "Min_Heap" :type-params ["T"]}})
  (env-add-method env "Min_Heap" "from_comparator"
                  {:params [{:name "compare" :type "Function"}]
                   :return-type {:base-type "Min_Heap" :type-params ["T"]}})
  (register-builtin-type-signatures! env "Min_Heap"))

;; Atomic_Integer and Atomic_Integer64 are both 64-bit atomics on the JVM
;; (see nex.types.builtins) and share this exact method set — differing
;; only in the class name.
(defn- register-atomic-integer-like-methods!
  [env class-name]
  (env-add-class env class-name {:name class-name})
  (env-add-method env class-name "make"
                  {:params [{:name "initial" :type "Integer"}]
                   :return-type class-name})
  (register-builtin-type-signatures! env class-name))

(defn- register-atomic-boolean-methods!
  [env]
  (env-add-class env "Atomic_Boolean" {:name "Atomic_Boolean"})
  (env-add-method env "Atomic_Boolean" "make"
                  {:params [{:name "initial" :type "Boolean"}]
                   :return-type "Atomic_Boolean"})
  (register-builtin-type-signatures! env "Atomic_Boolean"))

(defn- register-atomic-reference-methods!
  [env]
  (env-add-class env "Atomic_Reference" {:name "Atomic_Reference"
                                         :generic-params [{:name "T"}]})
  (env-add-method env "Atomic_Reference" "make"
                  {:params [{:name "initial" :type {:base-type "T" :detachable true}}]
                   :return-type {:base-type "Atomic_Reference" :type-params ["T"]}})
  (register-builtin-type-signatures! env "Atomic_Reference"))

(defn- register-channel-methods!
  [env]
  (env-add-class env "Channel" {:name "Channel"
                                :generic-params [{:name "T"}]})
  (register-builtin-type-signatures! env "Channel"))

;; Built-in Function methods: call0..call32
(defn- register-function-call-methods!
  [env]
  (doseq [n (range 0 33)]
    (env-add-method env "Function"
                    (str "call" n)
                    {:params (mapv (fn [i] {:name (str "arg" i) :type "Any"})
                                   (range 1 (inc n)))
                     :return-type "Any"})))

(defn register-builtin-methods
  "Register method signatures for built-in types."
  [env]
  (register-any-protocol! env)
  (register-comparable-protocol! env)
  (register-hashable-protocol! env)
  (register-scalar-classes! env)
  (register-integer-methods! env)
  (register-real-methods! env)
  (register-char-methods! env)
  (register-string-methods! env)
  (register-console-methods! env)
  (register-task-methods! env)
  (register-process-methods! env)
  (register-array-methods! env)
  (register-map-methods! env)
  (register-set-methods! env)
  (register-min-heap-methods! env)
  (register-atomic-integer-like-methods! env "Atomic_Integer")
  (register-atomic-integer-like-methods! env "Atomic_Integer64")
  (register-atomic-boolean-methods! env)
  (register-atomic-reference-methods! env)
  (register-channel-methods! env)
  (register-function-call-methods! env))


;;
;; Undefined-type validation
;;
;; Every type annotation must name a type that some class, interned class,
;; imported host class, type alias, generic parameter, or builtin defines.
;; Without this, a typo'd type (`x: Trakcing_Id`) is silently treated as an
;; unchecked reference, which disables type checking for every use of that
;; value — a soundness hole, not just a missing diagnostic. The pass runs after
;; class/alias/import collection (so forward references resolve) and accumulates
;; up to a bound of errors instead of stopping at the first, so a single run
;; surfaces many typos.

(def default-max-undefined-type-errors
  "Upper bound on undefined-type errors collected in one check before the pass
   stops scanning. Keeps a file full of typos from producing unbounded output."
  100)

(defn- type-base-name-refs
  "Every class-name reference in a (possibly nested, detachable, or function)
   type expression, e.g. Array[?Map[K, Widget]] -> (\"Array\" \"Map\" \"K\" \"Widget\")."
  [type-expr]
  (let [t (normalize-type type-expr)]
    (cond
      (string? t) [t]
      (map? t) (concat (when-let [b (:base-type t)] [b])
                       (mapcat type-base-name-refs (:type-params t))
                       (mapcat type-base-name-refs (map :type (:param-types t)))
                       (when (:return-type t) (type-base-name-refs (:return-type t))))
      :else [])))

(defn- known-type-name?
  "Whether a bare type name resolves to something in scope: a builtin, a
   collected class (user/interned/imported-Java placeholder), a declared type
   alias, or a generic type parameter of some visible class/function."
  [env nm]
  (or (builtin-type? nm)
      (some? (env-lookup-class env nm))
      (some? (env-lookup-type-alias env nm))
      (declared-generic-param? env nm)))

(defn- typed-let-nodes
  "Every `let` node carrying a declared type anywhere within a body form,
   including lets nested in if/from/across blocks and anonymous functions."
  [form]
  (->> (tree-seq coll? seq form)
       (filter #(and (map? %) (some? (:var-type %))))))

(defn- anonymous-function-nodes
  "Every `fn(...) ... end` node anywhere within a body form, including ones
   nested inside if/from/across blocks, let initializers, and other anonymous
   functions. A param or return-type annotation here (`fn(item: Item): ...`)
   is not a `let`, so it is invisible to `typed-let-nodes` — without this, an
   undefined type named only in a lambda's signature isn't reported as such;
   it silently resolves to Any during expression checking, and the first
   member access it enables (or the first past one Any already tolerates)
   surfaces instead as a confusing downstream \"Undefined field ... on Any\"."
  [form]
  (->> (tree-seq coll? seq form)
       (filter #(and (map? %) (= :anonymous-function (:type %))))))

(defn collect-undefined-type-errors
  "Collect type annotations that name an undefined type, across every
   declaration position (generic constraints, parent type arguments, fields,
   method and constructor parameters, return types, local `let`s, and top-level
   `let`s). A bare undefined parent class is left to the dedicated inheritance
   check. Returns a vector of TypeError, deduplicated by message and capped at
   `max-n`. `env` must already have every class, type alias and import collected."
  [env classes functions statements max-n]
  (let [;; Each free function is also hoisted into `classes` as a generated
        ;; `<name>_Function` class. Walk the functions directly (for source-level
        ;; labels like "function 'f'") and skip their generated twins so a
        ;; function's annotations are not reported twice.
        fn-class-names (into #{} (keep #(get-in % [:class-def :name])) functions)
        errs (volatile! [])
        seen (volatile! #{})
        full? #(>= (count @errs) max-n)
        ;; The declaration currently being walked, by the two doseqs below —
        ;; unlike check-program's own class/function loops, this one function
        ;; walks every declaration in ONE pass and batches every error it
        ;; finds into a single :errors list (see check-program's "Undefined
        ;; type(s) referenced" throw), so there is no single class/function
        ;; boundary check-program's with-source-file could wrap; add! reads
        ;; this instead, at the point each error is actually recorded, to
        ;; stamp it with whichever declaration it came from — a class or
        ;; function from an interned file otherwise reports a line number
        ;; with no file to go with it (docs/proposals/namespaces.md). nil for
        ;; a top-level `let`/anonymous function, always from the entry file's
        ;; own :statements (resolve-interned* never touches those).
        current-source-file (volatile! nil)
        add! (fn [nm label line]
               (let [msg (str "Undefined type: " nm (when label (str " — " label)))]
                 (when-not (or (full?) (contains? @seen msg))
                   (vswap! seen conj msg)
                   (vswap! errs conj (error-with-source-file (type-error msg line) @current-source-file)))))
        ;; The names a class/function/lambda itself declares as generic
        ;; parameters (`[G, T]`) -- NOT every generic-param name visible
        ;; anywhere in the program. `known-type-name?`/`declared-generic-param?`
        ;; can't be reused for the generic-shaped case here: they treat a name
        ;; as a "declared" generic param as soon as it matches *some* visible
        ;; class's own `[...]` list, which would let an unrelated class's `[G]`
        ;; silently authorize a bare, undeclared `G` in a completely different
        ;; function. No synthetic generic-param placeholder classes have been
        ;; registered in `env` yet at this point in the pipeline (that happens
        ;; later, during body checking), so `env-lookup-class` can't be relied
        ;; on to distinguish "genuinely declared here" from "coincidentally
        ;; named the same as someone else's type variable" either.
        generic-param-names (fn [gparams]
                              (into #{} (keep (comp type-name-string :name)) gparams))
        known-here? (fn [nm local-generics]
                     (or (builtin-type? nm)
                         (some? (env-lookup-class env nm))
                         (some? (env-lookup-type-alias env nm))
                         (contains? local-generics nm)))
        check! (fn [label line type-expr local-generics]
                 (when (and type-expr (not (full?)))
                   (doseq [nm (distinct (type-base-name-refs type-expr))
                           :while (not (full?))]
                     (when-not (known-here? nm local-generics)
                       (add! nm label line)))))
        check-constraints! (fn [gparams owner line local-generics]
                             (doseq [{:keys [name constraint]} gparams
                                     :when constraint]
                               (check! (str "constraint on type parameter '"
                                            (type-name-string name) "' of " owner)
                                       line constraint local-generics)))
        check-params! (fn [params owner line local-generics]
                        (doseq [{pname :name ptype :type} params]
                          (check! (str "parameter '" pname "' of " owner) line ptype local-generics)))
        check-body-lets! (fn [body owner local-generics]
                           (doseq [{:keys [name var-type] line :dbg/line} (typed-let-nodes body)]
                             (check! (str "local variable '" name "' in " owner) line var-type local-generics))
                           (doseq [{:keys [params return-type generic-params] line :dbg/line}
                                   (anonymous-function-nodes body)]
                             (let [lambda-generics (into local-generics (generic-param-names generic-params))]
                               (check-params! params (str "anonymous function in " owner) line lambda-generics)
                               (check! (str "return type of anonymous function in " owner)
                                       line return-type lambda-generics))))]
    ;; Free functions.
    (doseq [{:keys [name params return-type generic-params body source-file] line :dbg/line} functions
            :while (not (full?))]
      (vreset! current-source-file source-file)
      (let [owner (str "function '" name "'")
            local-generics (generic-param-names generic-params)]
        (check-constraints! generic-params owner line local-generics)
        (check-params! params owner line local-generics)
        (check! (str "return type of " owner) line return-type local-generics)
        (check-body-lets! body owner local-generics)))
    ;; Classes (user + interned); generated function classes handled above.
    (doseq [{:keys [name generic-params parents body source-file] cline :dbg/line} classes
            :when (not (contains? fn-class-names name))
            :while (not (full?))]
      (vreset! current-source-file source-file)
      (let [cowner (str "class '" name "'")
            local-generics (generic-param-names generic-params)]
        (check-constraints! generic-params cowner cline local-generics)
        (doseq [{:keys [parent generic-args]} parents]
          ;; The bare parent name has a dedicated inheritance check with a
          ;; clearer "Undefined parent class" message; leave it to that pass and
          ;; only validate the parent's type arguments (otherwise unchecked).
          ;; Skip the arguments when the parent itself is undefined so the
          ;; dedicated check surfaces rather than a generic one.
          (when (known-type-name? env parent)
            (doseq [ga generic-args]
              (check! (str "type argument to parent of " cowner) cline ga local-generics))))
        (doseq [section body
                :when (= (:type section) :feature-section)
                member (:members section)
                :while (not (full?))]
          (let [mline (:dbg/line member)]
            (case (:type member)
              :field (check! (str "field '" (:name member) "' in " cowner)
                             mline (:field-type member) local-generics)
              :method (let [owner (str "method '" (:name member) "' in " cowner)]
                        (check-params! (:params member) owner mline local-generics)
                        (check! (str "return type of " owner) mline (:return-type member) local-generics)
                        (check-body-lets! (:body member) owner local-generics))
              nil)))
        (doseq [section body
                :when (= (:type section) :constructors)
                ctor (:constructors section)
                :while (not (full?))]
          (let [owner (str "constructor '" (:name ctor) "' in " cowner)]
            (check-params! (:params ctor) owner (:dbg/line ctor) local-generics)
            (check-body-lets! (:body ctor) owner local-generics)))))
    ;; Top-level `let` statements — always the entry file's own :statements
    ;; (resolve-interned* never touches those; see current-source-file above)
    ;; — never mind whichever :source-file the classes loop above left set.
    (vreset! current-source-file nil)
    (doseq [{:keys [name var-type] line :dbg/line} (typed-let-nodes statements)
            :while (not (full?))]
      (check! (str "variable '" name "'") line var-type #{}))
    ;; Top-level anonymous functions (`fn(...) ... end` used directly in a
    ;; top-level statement, e.g. as an argument, not bound through a typed
    ;; `let` above).
    (doseq [{:keys [params return-type generic-params] line :dbg/line} (anonymous-function-nodes statements)
            :while (not (full?))]
      (let [local-generics (generic-param-names generic-params)]
        (check-params! params "a top-level anonymous function" line local-generics)
        (check! "return type of a top-level anonymous function" line return-type local-generics)))
    @errs))

(defn- collect-top-level-globals!
  "Infer the type of each direct top-level `let` (a program global) and register
   it on the root env so function and class bodies can read it (§7). Runs before
   body checking, in source order. Uses a scratch env that shares the class,
   method and alias registries but keeps its own vars/warnings, so this
   inference neither pollutes top-level source-order threading nor double-reports
   diagnostics. Only *direct* top-level lets count as globals; lets nested inside
   top-level control flow are block-scoped and are not registered."
  [env statements]
  (let [scratch (assoc (make-type-env)
                       :classes (:classes env)
                       :methods (:methods env)
                       :type-aliases (:type-aliases env)
                       :globals (:globals env))]
    (doseq [stmt statements
            :when (and (map? stmt) (= :let (:type stmt)))]
      (let [nm (:name stmt)
            declared (:var-type stmt)
            ty (or (when declared (expand-type-aliases env (normalize-type declared)))
                   (try (check-expression scratch (:value stmt))
                        (catch Exception _ "Any")))]
        (env-add-var scratch nm (or ty "Any"))
        (env-add-global env nm (or ty "Any"))))))

(defn- body-let-names
  "Names bound by a `let` anywhere in `body` — locals that shadow a like-named
   global. Includes lets synthesized into a body (e.g. a refinement predicate
   inlined as `let s := t; if not (s...) ...`), so those do not read as globals."
  [body]
  (into #{}
        (comp (filter #(and (map? %) (= :let (:type %))))
              (keep :name))
        (tree-seq coll? seq body)))

(defn- body-bound-names
  "Names that shadow a global within `body`: its params, the enclosing class's
   field names, and any `let`-bound local. A shadowed name is not a global read."
  [params field-names body]
  (-> (set (map :name params))
      (into field-names)
      (into (body-let-names body))))

(defn- body-global-refs
  "Set of global names read anywhere in `body`, excluding names in `bound`.
   Reads appear either as a bare `:identifier` or as a string call receiver
   (`x.m(...)` parses the receiver `x` to a string target)."
  [globals bound body]
  (let [refs (volatile! #{})
        consider! (fn [nm]
                    (when (and (contains? globals nm)
                               (not (contains? bound nm)))
                      (vswap! refs conj nm)))]
    (doseq [node (tree-seq coll? seq body)]
      (when (map? node)
        (cond
          (and (= :call (:type node)) (string? (:target node)))
          (consider! (:target node))
          (= :identifier (:type node))
          (consider! (:name node)))))
    @refs))

(defn- class-field-names
  [class-def]
  (into #{}
        (for [section (:body class-def)
              :when (= (:type section) :feature-section)
              member (:members section)
              :when (= (:type member) :field)]
          (:name member))))

(defn- statement-enters-static-world?
  "True if a top-level statement can transfer control into user-written code:
   it calls a user free function, or creates a user-defined class (running its
   constructor). Method calls need not be considered: the receiver object must
   have been created first, so a `create` always precedes the first method call."
  [fn-names user-class-names stmt]
  (some (fn [node]
          (and (map? node)
               (or (and (= :call (:type node))
                        (nil? (:target node))
                        (contains? fn-names (:method node)))
                   (and (= :create (:type node))
                        (contains? user-class-names (:class-name node))))))
        (tree-seq coll? seq stmt)))

(defn- check-global-watermark
  "Enforce the def-before-use watermark for readable globals (§7): every global
   referenced by any function or class body must be initialized before the first
   top-level statement that enters user code. Returns a vector of TypeError."
  [statements normalized-functions classes globals-map]
  (let [global-names (set (keys globals-map))]
    (if (empty? global-names)
      []
      (let [fn-names (set (map :name normalized-functions))
            user-class-names (set (map :name classes))
            ;; Earliest top-level statement index that enters user code.
            watermark (first (keep-indexed
                              (fn [i s]
                                (when (statement-enters-static-world?
                                       fn-names user-class-names s)
                                  i))
                              statements))
            ;; Position (top-level statement index) where each global is defined.
            global-pos (reduce (fn [m [i s]]
                                 (if (and (map? s) (= :let (:type s))
                                          (contains? global-names (:name s))
                                          (not (contains? m (:name s))))
                                   (assoc m (:name s) i)
                                   m))
                               {}
                               (map-indexed vector statements))
            ;; Globals read anywhere in the static world.
            used (apply set/union
                        (concat
                         (for [f normalized-functions]
                           (body-global-refs global-names
                                             (body-bound-names (:params f) #{} (:body f))
                                             (:body f)))
                         (for [c classes
                               :let [fields (class-field-names c)]
                               section (:body c)
                               :when (#{:feature-section :constructors} (:type section))
                               member (concat (:members section) (:constructors section))
                               :when (:body member)]
                           (body-global-refs global-names
                                             (body-bound-names (:params member) fields (:body member))
                                             (:body member)))))]
        (if (nil? watermark)
          ;; No statement ever enters user code, so no body runs: nothing to check.
          []
          (vec (keep (fn [g]
                       (let [pos (get global-pos g)]
                         (when (or (nil? pos) (>= pos watermark))
                           (type-error
                            (str "Global '" g "' is read by a function or class body but is "
                                 "not initialized before the first call into user code"
                                 (when-let [wl (:dbg/line (nth statements watermark nil))]
                                   (str " (line " wl ")"))
                                 ". Move its `let` above that point.")))))
                     (sort used))))))))

(defn check-program
  "Type check a complete program.
   opts may include :var-types - a map of {var-name => type} for pre-existing variables."
  ([program] (check-program program {}))
  ([{:keys [classes calls statements imports functions type-aliases duplicate-functions
            function-signature-conflicts] :as program} opts]
   (binding [*strict-undefined-targets* (boolean (:strict-undefined-targets? opts))]
   (let [env (make-type-env)
         normalized-functions (normalize-function-defs classes functions)
         all-class-defs (vec (concat classes (function-class-defs normalized-functions)))
         visible-classes (class-defs-by-name-last-wins all-class-defs)
         ;; Every interned class-def, under its qualified identity (Phase 3,
         ;; docs/proposals/namespaces.md) — not just whichever one won the
         ;; bare-name collapse above. `:name` is swapped to the qualified
         ;; string so collect-class-info/check-class register its fields,
         ;; methods and constructors under that (always-unique) key instead
         ;; of the bare one, with zero risk of tripping the Phase 2 ambiguity
         ;; check along the way — that check only ever triggers on a bare
         ;; name. This is what makes `finance/Account` resolvable regardless
         ;; of whether `Account` alone is currently ambiguous.
         ;; :true-name preserves the class's real bare :name (e.g. "Ok")
         ;; underneath the qualified one now occupying :name (e.g.
         ;; "data.Ok") — anything that enumerates *all* registered classes
         ;; for identity rather than looking one up by a specific key (e.g.
         ;; find-sealed-subclasses' match-exhaustiveness check) needs the
         ;; former: this same underlying class is also reachable through its
         ;; ordinary bare-name registration, and enumerating it as a second,
         ;; differently-named entry would count it as an extra sealed variant
         ;; that does not exist.
         qualified-class-defs (->> all-class-defs
                                   (filter :qualified-name)
                                   (map (fn [cd] (assoc cd :name (:qualified-name cd) :true-name (:name cd))))
                                   (into [] (distinct)))
         ambiguous-classes (ambiguous-class-names all-class-defs)
         ;; The free-function analog — normalized-functions, not
         ;; all-class-defs: a function's OWN bare :name, not its
         ;; synthesized, always-unique :class-name (which ambiguous-classes
         ;; already covers and could never find ambiguous).
         ambiguous-functions (ambiguous-function-names normalized-functions)
         ;; Names whose bodies should be collected for resolution but not re-checked
         ;; (used by the REPL to avoid re-validating previously defined code).
         skip-body-names (or (:skip-class-body-names opts) #{})]
     ;; Populate ambiguity info before any lookup can happen — env-lookup-class
     ;; consults it on every resolved bare name (see ambiguous-class-names).
     (reset! (:ambiguous-classes env) ambiguous-classes)
     (reset! (:ambiguous-functions env) ambiguous-functions)
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

       ;; First pass: collect every interned class under its qualified
       ;; identity (see qualified-class-defs) — including one that will go on
       ;; to lose the bare-name slot below to an ambiguity or another interned
       ;; class, so `finance/Account` resolves fully even when `Account`
       ;; alone does not. Runs BEFORE the bare-name pass below deliberately: a
       ;; class constant's value is type-checked eagerly, inline, right here
       ;; in collect-class-info — not deferred to check-class like an
       ;; ordinary method body — so an own-file (bare-name-pass) class whose
       ;; constant references a qualified/aliased interned class (`default =
       ;; create finance/Account.make(...)`) needs that qualified registration
       ;; to already exist at the point its OWN constant is checked, not
       ;; merely by the time the whole program finishes elaborating.
       (doseq [class-def qualified-class-defs]
         (with-source-file (:source-file class-def)
           (fn [] (collect-class-info env class-def))))

       ;; Second pass: collect the rest of the program's class definitions —
       ;; the entry file's own classes, allowing them to override builtin
       ;; placeholder names such as Task or Channel, and every interned
       ;; class's ordinary bare-name registration. An entry whose bare name is
       ;; ambiguous is registered raw only, never run through
       ;; collect-class-info under that key: collect-class-info (and
       ;; check-class, below) walk a class's own fields/parent chain via
       ;; env-lookup-class on its own name — for an ambiguous name that
       ;; throws immediately, during ordinary registration, before any user
       ;; code has asked for anything (defeating the reference-time design
       ;; Phase 2 was built around). env-lookup-class still needs *something*
       ;; registered under the bare key so it notices the collision and
       ;; throws "Ambiguous reference" instead of falling through to a
       ;; misleading "Undefined class" — this raw registration is that, and
       ;; nothing more; the class's real processing already happened above,
       ;; under its qualified key, where no such collision exists.
       (doseq [class-def visible-classes]
         (if (contains? ambiguous-classes (:name class-def))
           (env-add-class env (:name class-def) class-def)
           (with-source-file (:source-file class-def)
             (fn [] (collect-class-info env class-def)))))

       ;; Undefined-type validation. Runs now that every class, alias and import
       ;; is collected, so forward references resolve. Collects up to a bound of
       ;; errors (not just the first) and reports them together; short-circuits
       ;; before body checking so an undefined type does not also spray unrelated
       ;; downstream noise. REPL-skipped classes were validated when first defined.
       (let [undefined-type-errors
             (collect-undefined-type-errors
              env
              (remove #(contains? skip-body-names (:name %)) classes)
              functions
              statements
              (or (:max-undefined-type-errors opts) default-max-undefined-type-errors))]
         (when (seq undefined-type-errors)
           (throw (ex-info "Undefined type(s) referenced"
                           {:errors undefined-type-errors}))))

       ;; Inject pre-existing variable types (e.g., from REPL). Expand any type
       ;; aliases so a variable declared with an alias type (e.g. a REPL `let m:
       ;; Matrix := ...`) resolves its methods on later inputs.
       (doseq [[var-name var-type] (:var-types opts)]
         (env-add-var env var-name (expand-type-aliases env var-type)))

       ;; Register function variables (name -> generated class): the bare
       ;; name, always (an ambiguous one just gets whichever fn-def visits
       ;; last — harmless, since a bare call to it is rejected below before
       ;; this registration is ever consulted, exactly how the analogous
       ;; ambiguous-class registration a few lines up is "raw, so
       ;; env-lookup-var finds *something* to be ambiguous about" rather
       ;; than a real pick), and additionally the qualified name for every
       ;; interned function — this is what makes `trade.ship(x)` resolvable
       ;; at all: nex.walker/resolve-qualified-function-calls already
       ;; rewrote it to an ordinary bare call naming "trade.ship" by the
       ;; time this program reaches check-program, so it needs a real var
       ;; registered under that exact key like any other free function.
       (doseq [fn-def normalized-functions]
         (let [arity (count (:params fn-def))]
           (when (> arity 32)
             (throw (ex-info (str "Function " (:name fn-def)
                                  " must have at most 32 parameters")
                             {:error (type-error
                                      (str "Function " (:name fn-def)
                                           " must have at most 32 parameters"))}))))
         (env-add-var env (:name fn-def) (:class-name fn-def))
         (when (:qualified-name fn-def)
           (env-add-var env (:qualified-name fn-def) (:class-name fn-def))))

       ;; Register top-level `let` globals so class and function bodies can read
       ;; them (§7), and enforce the def-before-use watermark before those bodies
       ;; are checked (so an undefined global surfaces as an init-order error,
       ;; not a downstream "Undefined variable").
       (when (seq statements)
         (collect-top-level-globals! env statements)
         (let [watermark-errors (check-global-watermark
                                 statements normalized-functions classes
                                 @(:globals env))]
           (when (seq watermark-errors)
             (throw (ex-info "Global initialized after first use"
                             {:errors watermark-errors})))))

       ;; Second pass: check every interned class's body under its qualified
       ;; identity, mirroring the collect-class-info ordering above (qualified
       ;; before bare-name) for consistency, though body-checking itself has
       ;; no known eager cross-dependency on registration order the way a
       ;; class constant's value does — undefined-type validation and global
       ;; registration have already completed for both passes by this point
       ;; regardless. skip-body-names is bare-name-shaped (a REPL concern; see
       ;; :skip-class-body-names), so it does not apply here — a small,
       ;; accepted extra REPL re-check cost, not a correctness issue.
       (doseq [class-def qualified-class-defs]
         (with-source-file (:source-file class-def)
           (fn [] (check-class env class-def))))

       ;; Third pass: check the rest of the program's class bodies. Skipped
       ;; for an ambiguous bare name for the same reason as the first pass
       ;; above — its body was already fully checked under its qualified key.
       (doseq [class-def visible-classes]
         (when-not (or (contains? skip-body-names (:name class-def))
                       (contains? ambiguous-classes (:name class-def)))
           (with-source-file (:source-file class-def)
             (fn [] (check-class env class-def)))))

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

       (catch clojure.lang.ExceptionInfo e
         (let [error-data (ex-data e)]
           {:success false
            ;; A pass may report several errors at once (e.g. undefined-type
            ;; validation) via :errors; otherwise fall back to the single :error.
            :errors (or (:errors error-data)
                        [(or (:error error-data)
                             (type-error (ex-message e)))])
            :warnings (vec @(:warnings env))}))

       ;; Any other exception is an internal type-checker fault (e.g. a shape
       ;; assumption broken by an unexpected AST). Surface it as a type error
       ;; instead of letting a raw JVM exception escape this entry point; callers
       ;; rely on type-check always returning a result map. (In cljs the `:default`
       ;; clause above already covers this, so this JVM-only clause is elided there.)
       (catch Exception e
            {:success false
             :errors [(type-error (str "Internal type checker error: " (ex-message e)))]
             :warnings (vec @(:warnings env))}))))))

(defn type-check
  "Type check Nex code (entry point).
   opts may include :var-types - a map of {var-name => type} for pre-existing variables."
  ([ast] (type-check ast {}))
  ([ast opts]
   (check-program ast opts)))

(defn infer-expression-type
  "Infer the type of an expression AST node.
   opts: :classes - seq of class defs, :functions - seq of function defs,
   :var-types - {name type} map, :type-aliases - {name type-expr} map,
   :current-class - name of the class whose method/feature body `expr` sits
   in, if any. Without it, a bare implicit-`this` call (a zero-arg feature
   invoked with no receiver and no parens, e.g. `rarity_multiplier` inside
   its own class) can't resolve, even though a bare *field* reference
   happens to work via the field-name-as-global leak in collect-class-info.
   Returns the type (string or map) or nil on failure."
  [expr opts]
  (try
    (let [env (make-type-env)
          normalized-functions (normalize-function-defs (:classes opts) (:functions opts))
          function-classes (vec (function-class-defs normalized-functions))
          visible-classes (vec (concat (:classes opts) function-classes))
          visible-var-types (or (:var-types opts) {})]
      ;; Registered before any class or expression is looked at: a declared
      ;; alias may name the type of a field or of a var in `visible-var-types`
      ;; (`let t: Tid := ...`), and an env whose alias registry is empty cannot
      ;; tell that `Tid` is a `String` — it resolves to no class and no builtin,
      ;; so every method on it infers as nil. The whole-program check registers
      ;; these from source order; a caller inferring one expression in isolation
      ;; (the compiler's lowering pass) has to supply them.
      (doseq [[alias-name type-expr] (:type-aliases opts)]
        (env-add-type-alias env alias-name type-expr))
      (doseq [{:keys [qualified-name source]} (:imports opts)]
        (when (nil? source)
          (let [simple-name (last (str/split qualified-name #"\."))]
            (env-add-class env simple-name {:name simple-name :body [] :import qualified-name}))))
      (register-builtin-methods env)
      ;; collect-class-info type-checks each constant initializer eagerly, and one
      ;; that reads another class (e.g. `= create Other.make(...)`) needs that
      ;; class already collected. The whole-program check gets this from source
      ;; order (dependencies first); here `visible-classes` arrives in arbitrary
      ;; order, so collecting a dependent class first would throw. Collect to a
      ;; fixpoint instead: keep the classes that fail and retry them while any
      ;; still make progress, so a dependency collected on a later pass unblocks
      ;; its dependents. Anything still failing is a genuine error, left for the
      ;; outer best-effort catch.
      (loop [pending (vec visible-classes)]
        (when (seq pending)
          (let [failed (reduce (fn [acc class-def]
                                 (try (collect-class-info env class-def) acc
                                      (catch Exception _ (conj acc class-def))))
                               [] pending)]
            (when (< (count failed) (count pending))
              (recur failed)))))
      (register-visible-generic-classes! env visible-classes visible-var-types)
      (doseq [fn-def normalized-functions]
        (env-add-var env (:name fn-def) (:class-name fn-def)))
      (doseq [[var-name var-type] visible-var-types]
        (env-add-var env var-name var-type))
      (when-let [current-class (:current-class opts)]
        (env-add-var env "__current_class__" current-class))
      ;; check-expression-value, not check-expression: this function is
      ;; lowering's best-effort "what type is this" utility (see lower.clj's
      ;; infer-type, which falls back to it for a call/target its own
      ;; primary dispatch doesn't resolve), not the typechecker's own
      ;; top-down pass over user code — a Void-returning call reached here
      ;; is very often exactly the right, expected answer (a concurrency
      ;; send/statement-position call lowering needs to type for its own
      ;; bookkeeping), not a user using Void as a value. That rejection
      ;; belongs solely to check-expression's ordinary recursive use during
      ;; check-statement/check-let/argument-checking, where "value needed"
      ;; is actually true; routing it through here as well turned a
      ;; legitimate Void inference into a swallowed exception (this function
      ;; catches everything and returns nil on failure) and then a
      ;; "Unable to infer expression type during lowering" crash once every
      ;; fallback was exhausted.
      (check-expression-value env expr))
    (catch Exception _ nil)))
