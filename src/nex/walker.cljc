(ns nex.walker
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

;;
;; Utilities
;;

(defn token-text
  "Extract text from a token node (strings pass through)."
  [node]
  (when (string? node) node))

;; Forward declaration for mutual recursion
(declare transform-node)

(defn walk-children
  "Walk all children of a node, skipping the node type tag."
  [node]
  (mapv transform-node (rest node)))

(defn- node-pos
  "Extract clj-antlr position metadata from a parse node."
  [node]
  (or (-> node meta :clj-antlr/position)
      (-> node meta :clj-antlr/position)))

(defn- attach-debug-pos
  "Attach 1-based debug line/column metadata to AST maps."
  [ast-node parse-node]
  (if (and (map? ast-node) (contains? ast-node :type))
    (if-let [{:keys [row column]} (node-pos parse-node)]
      (cond-> ast-node
        (integer? row) (assoc :dbg/line (inc row))
        (integer? column) (assoc :dbg/col (inc column)))
      ast-node)
    ast-node))

(def ^:private implicit-generic-builtins
  #{"Any" "Void" "Nil" "Boolean" "Integer" "Real"
    "String" "Char" "Array" "Map" "Set" "Function" "Console" "Process"
    "Cursor" "Task" "Channel" "Min_Heap" "Atomic_Integer" "Atomic_Integer64"
    "Atomic_Boolean" "Atomic_Reference"})

(defn- collect-implicit-generic-names
  [type-expr]
  (letfn [(collect* [t]
            (cond
              (string? t)
              (if (and (not (contains? implicit-generic-builtins t))
                       (re-matches #"[A-Z]" t))
                [t]
                [])

              (map? t)
              (vec (concat (collect* (:base-type t))
                           (mapcat collect* (or (:type-args t) (:type-params t) []))
                           (mapcat #(collect* (:type %)) (or (:param-types t) []))
                           (when-let [rt (:return-type t)] (collect* rt))))

              :else
              []))]
    (reduce (fn [acc generic-name]
              (if (some #(= (:name %) generic-name) acc)
                acc
                (conj acc {:name generic-name})))
            []
            (collect* type-expr))))

;;
;; Reusable transformation functions
;;

(defn make-binary-op-handler
  "Creates a handler for binary operators that handles operator precedence.
   For nodes with fixed operators (like 'and', 'or'), pass the operator string.
   For nodes with variable operators, pass nil."
  [fixed-operator]
  (fn [[_ left & rest]]
    (if (empty? rest)
      (transform-node left)
      (if fixed-operator
        ;; Fixed operator: rest is [operand1 operand2 ...], operator is constant
        (reduce
         (fn [acc operand]
           {:type :binary
            :operator fixed-operator
            :left acc
            :right (transform-node operand)})
         (transform-node left)
         (remove string? rest))  ; Filter out operator keywords
        ;; Variable operator: rest is [op1 operand1 op2 operand2 ...]
        (reduce
         (fn [acc [op operand]]
           {:type :binary
            :operator op
            :left acc
            :right (transform-node operand)})
         (transform-node left)
         (partition 2 rest))))))

(def ^:private special-char-codes
  {"nul" "\0"
   "space" " "
   "newline" "\n"
   "return" "\r"
   "tab" "\t"})

(defn- maybe-transform-special-char [v]
  (if (and (string? v) (> (count v) 1))
    (or (get special-char-codes v) 0)
    v))

(defn- parse-integer-literal [token]
  (let [clean (str/replace token "_" "")
        [radix digits] (cond
                         (str/starts-with? clean "0b") [2 (subs clean 2)]
                         (str/starts-with? clean "0o") [8 (subs clean 2)]
                         (str/starts-with? clean "0x") [16 (subs clean 2)]
                         :else [10 clean])]
    #?(:clj (Long/parseLong digits radix)
       :cljs (js/parseInt digits radix))))

(defn- codepoint->string [cp]
  #?(:clj (String/valueOf (Character/toChars cp))
     :cljs (js/String.fromCodePoint cp)))

(defn- parse-hex [s]
  #?(:clj (Integer/parseInt s 16) :cljs (js/parseInt s 16)))

(defn- interpret-string-escapes
  "Interpret the backslash escapes of a double-quoted string's content. The
   recognised escapes are \\n \\t \\r \\0 \\\\ \\\" and \\u{HHHH} (a Unicode
   code point in hexadecimal); any other backslash sequence is an error, so a
   literal backslash is written either as \\\\ or in a raw single-quoted string."
  [content]
  (let [n (count content)]
    (loop [i 0, out []]
      (if (>= i n)
        (apply str out)
        (let [c (nth content i)]
          (if (not= c \\)
            (recur (inc i) (conj out c))
            (let [e (when (< (inc i) n) (nth content (inc i)))]
              (case e
                \n (recur (+ i 2) (conj out \newline))
                \t (recur (+ i 2) (conj out \tab))
                \r (recur (+ i 2) (conj out \return))
                \0 (recur (+ i 2) (conj out (char 0)))
                \\ (recur (+ i 2) (conj out \\))
                \" (recur (+ i 2) (conj out \"))
                \u (let [open (+ i 2)]
                     (if (and (< open n) (= (nth content open) \{))
                       (if-let [close (str/index-of content "}" (inc open))]
                         (recur (inc close)
                                (conj out (codepoint->string
                                           (parse-hex (subs content (inc open) close)))))
                         (throw (ex-info "Unterminated \\u{...} escape in string literal"
                                         {:string content})))
                       (throw (ex-info "Invalid \\u escape; expected \\u{HHHH} in string literal"
                                       {:string content}))))
                (throw (ex-info (str "Invalid escape sequence \\" e " in string literal; "
                                     "write \\\\ for a literal backslash, or use a single-quoted raw string")
                                {:string content :escape e}))))))))))

(defn- string-literal-token?
  "True for a STRING token's text: it begins with a double or single quote."
  [s]
  (and (string? s) (pos? (count s))
       (let [q (nth s 0)] (or (= q \") (= q \')))))

(defn- string-literal-value
  "The value denoted by a STRING literal token. A double-quoted literal
   interprets the standard escapes; a single-quoted literal is raw."
  [token]
  (let [content (subs token 1 (dec (count token)))]
    (if (= (nth token 0) \")
      (interpret-string-escapes content)
      content)))

;;
;; Node handlers map (data-driven transformations)
;;

(def ^:private next-fn-id (atom 0))

(defn- generate-unique-fn-name []
  (str "AnonymousFunction_" (swap! next-fn-id inc)))

(defn- identifier-target-expr [target]
  (if (string? target)
    {:type :identifier :name target}
    target))

(defn- call-target [acc]
  (if (and (map? acc) (= :identifier (:type acc)))
    (:name acc)
    acc))

(defn- negate-numeric-call-chain
  "When a unary minus wraps a method call chain rooted at a numeric literal,
  restructures so the literal is negated and the call chain is preserved.
  Returns nil if the base is not a numeric literal (don't restructure)."
  [node]
  (cond
    (#{:integer :real} (:type node))
    (let [negated (update node :value -)]
      ;; Keep the transfer-safe :value-str (added for integer literals) in sync
      ;; with the negated :value, or eval would read the stale positive string.
      (if (:value-str negated)
        (assoc negated :value-str (str (:value negated)))
        negated))

    (and (map? node) (= :call (:type node)) (map? (:target node)))
    (when-let [negated-target (negate-numeric-call-chain (:target node))]
      (assoc node :target negated-target))

    :else nil))

(defn- desugar-safe-call [call-node]
  (if (and (:safe? call-node)
           (:target call-node)
           (:method call-node))
    (let [temp-name (str "__safe_receiver_" (swap! next-fn-id inc) "__")
          target-expr (identifier-target-expr (:target call-node))]
      {:type :scoped-block
       :body [{:type :let
               :name temp-name
               :synthetic true
               :value target-expr}
              {:type :if
               :condition {:type :binary
                           :operator "/="
                           :left {:type :identifier :name temp-name}
                           :right {:type :nil}}
               :then [(-> call-node
                          (dissoc :safe?)
                          (assoc :target temp-name))]
               :elseif []
               :else nil}]
       :rescue nil})
    call-node))

(defn- desugar-safe-expression-call [call-node]
  (if (and (:safe? call-node)
           (string? (:target call-node))
           (:method call-node))
    (let [target-name (:target call-node)]
      {:type :when
       :condition {:type :binary
                   :operator "/="
                   :left {:type :identifier :name target-name}
                   :right {:type :nil}}
       :consequent (dissoc call-node :safe?)
       :alternative {:type :nil}})
    call-node))

(defn- build-function-node
  [name rest declaration-only?]
  (let [cleaned (remove #(#{"(" ")" "do" "end" ":"} %) rest)
        generic-params (first (filter #(and (sequential? %)
                                            (= :genericParams (first %)))
                                      cleaned))
        params (first (filter #(and (sequential? %)
                                    (= :paramList (first %)))
                              cleaned))
        return-type (first (filter #(and (sequential? %)
                                         (= :type (first %)))
                                   cleaned))
        note-clause (first (filter #(and (sequential? %)
                                         (= :noteClause (first %)))
                                   cleaned))
        require-clause (first (filter #(and (sequential? %)
                                            (= :requireClause (first %)))
                                      cleaned))
        ensure-clause (first (filter #(and (sequential? %)
                                           (= :ensureClause (first %)))
                                     cleaned))
        rescue-clause (first (filter #(and (sequential? %)
                                           (= :rescueClause (first %)))
                                     cleaned))
        block (first (filter #(and (sequential? %)
                                   (= :block (first %)))
                             cleaned))
        declaration-only? (or declaration-only? (nil? block))
        params-v (when params (transform-node params))
        return-type-v (when return-type (transform-node return-type))
        body (when block (transform-node block))
        fn-name (token-text name)
        class-name (str fn-name "_Function")
        method-name (str "call" (count params-v))
        explicit-generic-params (when generic-params (transform-node generic-params))
        generic-params-v (or explicit-generic-params
                             (vec (reduce (fn [acc {:keys [type]}]
                                            (into acc (collect-implicit-generic-names type)))
                                          (collect-implicit-generic-names return-type-v)
                                          params-v)))
        method-def {:type :method
                    :name method-name
                    :params params-v
                    :return-type return-type-v
                    :declaration-only? declaration-only?
                    :note (when note-clause (transform-node note-clause))
                    :require (when require-clause (transform-node require-clause))
                    :body body
                    :ensure (when ensure-clause (transform-node ensure-clause))
                    :rescue (when rescue-clause (transform-node rescue-clause))}
        class-def {:type :class
                   :name class-name
                   :generic-params generic-params-v
                   :note nil
                   :parents [{:parent "Function"}]
                   :body [{:type :feature-section
                           :visibility {:type :public}
                           :members [method-def]}]
                   :invariant nil}]
    {:type :function
     :name fn-name
     :class-name class-name
     :generic-params generic-params-v
     :declaration-only? declaration-only?
     :params params-v
     :return-type return-type-v
     :body body
     :class-def class-def}))

(defn- function-signature
  "The type-relevant signature of a function node: ordered parameter types, the
   return type, and any generic parameters. Parameter names are excluded — calls
   are positional, so names do not participate in conformance."
  [f]
  {:params (mapv :type (or (:params f) []))
   :return-type (:return-type f)
   :generic-params (vec (or (:generic-params f) []))})

(defn- render-signature
  "Render a function signature for diagnostics, e.g. `f[T](Integer, String): Real`."
  [name {:keys [params return-type generic-params]}]
  (str name
       (when (seq generic-params) (str "[" (str/join ", " generic-params) "]"))
       "(" (str/join ", " params) ")"
       (when return-type (str ": " return-type))))

(defn- function-signature-conflicts
  "Each `declare function` announces a signature that the later definition must
   match exactly. The definitions collapse last-wins below, discarding the
   declarations, so detect any disagreement here and report it with a message
   the front-ends can surface verbatim."
  [fn-nodes]
  (let [decls (->> fn-nodes
                   (filter :declaration-only?)
                   (reduce (fn [m f] (if (contains? m (:name f)) m (assoc m (:name f) f))) {}))
        defs  (->> fn-nodes
                   (remove :declaration-only?)
                   (reduce (fn [m f] (assoc m (:name f) f)) {}))]
    (->> decls
         (keep (fn [[nm decl]]
                 (when-let [defn (get defs nm)]
                   (let [d-sig (function-signature decl)
                         f-sig (function-signature defn)]
                     (when (not= d-sig f-sig)
                       {:name nm
                        :declared d-sig
                        :defined f-sig
                        :message (str "Function '" nm "' is declared as "
                                      (render-signature nm d-sig)
                                      " but defined as " (render-signature nm f-sig)
                                      ". The later definition must match the earlier "
                                      "declaration exactly.")})))))
         vec)))

;;
;; union (concise sum type) desugaring
;;
;; A `union P[G…]` declaration is rewritten into the exact sealed-class AST Nex
;; already produces: a `sealed deferred class P[G…]` parent plus one ordinary
;; `class Vi[G…] inherit P[G…]` per variant, each with its payload fields and an
;; auto-generated `make` constructor. Everything downstream (type checker,
;; interpreter, JVM/JS backends) only ever sees `:class` nodes.

(defn- union-variant->class
  "Build the ordinary :class AST node for one union variant.

  variant-node is a parsed (:unionVariant IDENTIFIER '(' paramList? ')') tree.
  generic-params is the parent's already-transformed generic-param vector, shared
  verbatim by every variant so payload types can reference them (e.g. Ok[T])."
  [variant-node parent-name generic-params]
  (let [var-name (token-text (second variant-node))
        param-list-node (first (filter #(and (sequential? %)
                                             (= :paramList (first %)))
                                       (drop 2 variant-node)))
        payload (if param-list-node (transform-node param-list-node) [])
        ;; inherit P[G…] — parent generic args are the bare param names.
        gargs (mapv :name generic-params)
        parent-entry (cond-> {:parent parent-name}
                       (seq gargs) (assoc :generic-args gargs))
        fields (mapv (fn [{:keys [name type]}]
                       {:type :field
                        :name name
                        :field-type type
                        :once? false
                        :constant? false
                        :value nil
                        :note nil})
                     payload)
        ;; Constructor params are given internal names distinct from the field
        ;; names (payload construction is positional), so the field-init
        ;; `field := arg__i` is never ambiguous with the param in scope.
        ctor-params (vec (map-indexed
                          (fn [i {:keys [type]}]
                            {:name (str "arg__" (inc i))
                             :type type})
                          payload))
        ctor-body (vec (map-indexed
                        (fn [i {:keys [name]}]
                          {:type :assign
                           :target name
                           :value {:type :identifier :name (str "arg__" (inc i))}})
                        payload))
        feature-section (when (seq fields)
                          {:type :feature-section
                           :visibility {:type :public}
                           :members fields})
        constructor {:type :constructor
                     :name "make"
                     :params (when (seq ctor-params) ctor-params)
                     :require nil
                     :body ctor-body
                     :ensure nil
                     :rescue nil}
        constructors {:type :constructors
                      :constructors [constructor]}]
    {:type :class
     :name var-name
     :deferred? false
     :sealed? false
     :generic-params (when (seq generic-params) generic-params)
     :note nil
     :parents [parent-entry]
     :body (if feature-section
             [feature-section constructors]
             [constructors])
     :invariant nil}))

;;
;; refinement types (lightweight predicate-narrowed subtypes)
;;
;; `declare type Quantity = Integer where n: n > 0` is an alias to Integer for
;; type checking, plus a predicate checked wherever a value is *narrowed* into
;; the refinement. Narrowing sites reachable from the syntax alone are: a `let`
;; whose declared type is the refinement, a parameter of that type (checked at
;; entry), and a return of that type (checked on `result`). The check is emitted
;; as ordinary AST — a scoped block that binds the predicate's binder to the
;; value and raises when the predicate is false — so every backend runs it and
;; `skip-contracts` is out of scope here (a follow-up can gate it like `require`).

(defn- refinement-name
  "The refinement name a type annotation refers to, or nil. Only plain named
  types are handled; detachable/parameterized annotations are left unchecked."
  [type-expr]
  (when (string? type-expr) type-expr))

(defn- collect-refinements
  "Map of refinement name -> {:base :binder :predicate} from a program's aliases."
  [program]
  (reduce (fn [m {:keys [name type-expr refinement]}]
            (if refinement
              (assoc m name (assoc refinement :base type-expr))
              m))
          {}
          (:type-aliases program)))

(defn- make-refinement-check
  "AST for `do let <binder>: <base> := <value-var>  if not(<pred>) then raise ... end end`.
  Binding the predicate binder at the base type keeps its methods/operators
  resolvable on every backend (a refinement is otherwise opaque to lowering)."
  [rname {:keys [binder predicate base]} value-var]
  {:type :scoped-block
   :rescue nil
   :body [{:type :let :name binder :var-type base
           :value {:type :identifier :name value-var}}
          {:type :if
           :condition {:type :unary :operator "not" :expr predicate}
           :then [{:type :raise
                   :value {:type :string
                           :value (str "Refinement " rname " violated")}}]
           :elseif []
           :else nil}]})

(defn- expand-refinement-lets
  "Expand each `let x: R := …` in a statement vector into [the-let, check]."
  [stmts refinements]
  (vec (mapcat
        (fn [s]
          (if-let [r (and (map? s) (= :let (:type s))
                          (get refinements (refinement-name (:var-type s))))]
            [s (make-refinement-check (refinement-name (:var-type s)) r (:name s))]
            [s]))
        stmts)))

(defn- add-param-return-checks
  "Prepend a check for each refinement-typed parameter and append one for a
  refinement return type (on `result`)."
  [node refinements]
  (let [param-checks (keep (fn [p]
                             (when-let [r (get refinements (refinement-name (:type p)))]
                               (make-refinement-check (refinement-name (:type p)) r (:name p))))
                           (:params node))
        ret-name (refinement-name (:return-type node))
        ret-r (get refinements ret-name)
        ret-check (when ret-r (make-refinement-check ret-name ret-r "result"))]
    (if (or (seq param-checks) ret-check)
      (assoc node :body (vec (concat param-checks
                                     (or (:body node) [])
                                     (when ret-check [ret-check]))))
      node)))

(defn inject-refinement-checks
  "Rewrite a walked program, injecting predicate checks at every refinement
  narrowing site (let bindings, parameters, returns) throughout the tree."
  [program]
  (let [refinements (collect-refinements program)]
    (if (empty? refinements)
      program
      (walk/postwalk
       (fn [n]
         (if (map? n)
           (let [n (reduce (fn [m k]
                             (if (sequential? (get m k))
                               (assoc m k (expand-refinement-lets (get m k) refinements))
                               m))
                           n [:body :then :else :statements])]
             (if (#{:method :constructor :function} (:type n))
               (add-param-return-checks n refinements)
               n))
           n))
       program))))

(defn- process-field-patterns
  "Desugar a variant's field patterns against a bound variable `var-name`.
  Returns {:bindings :guards :body-binds}:
   - :bindings   safe `let`s (direct field binds), run before the guard;
   - :guards     boolean conjuncts (literal equalities, nested `convert`s);
   - :body-binds `let`s that depend on a nested `convert` succeeding, so they run
                 in the body after the guard.
  Nested patterns recurse: the field is narrowed with `convert … to __nest: T` in
  the guard, and the sub-pattern's binds move into :body-binds."
  [var-name field-patterns]
  (reduce
   (fn [acc {:keys [kind field] :as fp}]
     (let [fr {:type :call :target var-name :method field :args [] :has-parens false}]
       (case kind
         :bind (if (= "_" field)
                 acc
                 (update acc :bindings conj
                         {:type :let :name (:bind fp) :var-type nil :value fr}))
         :literal (update acc :guards conj
                          {:type :binary :operator "==" :left fr :right (:value fp)})
         :nested (let [sub-var (str "__nest_" (swap! next-fn-id inc) "__")
                       conv {:type :convert :value fr :var-name sub-var
                             :target-type (if (:generic-args fp)
                                            {:base-type (:type fp) :type-args (:generic-args fp)}
                                            (:type fp))}
                       sub (process-field-patterns sub-var (:subpatterns fp))]
                   (-> acc
                       (update :guards #(vec (concat % [conv] (:guards sub))))
                       (update :body-binds #(vec (concat % (:bindings sub) (:body-binds sub)))))))))
   {:bindings [] :guards [] :body-binds []}
   field-patterns))

(def node-handlers
  {:program
   (fn [[_ & nodes]]
     (let [cleaned-nodes (remove string? nodes) ; Filter out "<EOF>" token
           transformed (mapv transform-node cleaned-nodes)
           classes (filter #(= :class (:type %)) transformed)
           fn-nodes (filter #(= :function (:type %)) transformed)
           ;; Forward declarations (`declare function`) intentionally repeat a
           ;; name that a later definition fulfils, so only count real
           ;; definitions when looking for duplicates.
           defined-fn-names (->> fn-nodes (remove :declaration-only?) (map :name))
           defined-fn-freq (frequencies defined-fn-names)
           ;; Free-function names are collapsed last-wins below; record any name
           ;; defined more than once so the type checker can reject it instead of
           ;; letting the earlier definition vanish silently.
           duplicate-functions (->> defined-fn-names
                                    distinct
                                    (filter #(> (defined-fn-freq %) 1))
                                    vec)
           ;; A `declare function` signature must be matched exactly by its
           ;; later definition; record any that disagree before the collapse.
           signature-conflicts (function-signature-conflicts fn-nodes)
           functions (->> fn-nodes
                          (reduce (fn [m f] (assoc m (:name f) f)) {})
                          vals
                          vec)
           interns (filter #(= :intern (:type %)) transformed)
           imports (filter #(= :import (:type %)) transformed)
           type-aliases (filter #(= :type-alias (:type %)) transformed)
           statements (filter #(not (#{:class :union :function :intern :import :type-alias} (:type %))) transformed)
           calls (filter #(= :call (:type %)) statements)
           function-classes (mapv :class-def functions)
           ;; `union` declarations desugar to a sealed parent + variant classes.
           union-classes (mapcat :classes (filter #(= :union (:type %)) transformed))
           all-classes (vec (concat classes function-classes union-classes))]
       (inject-refinement-checks
        {:type :program
         :imports (vec imports)
         :interns (vec interns)
         :type-aliases (vec type-aliases)
         :classes all-classes
         :functions (vec functions)
         :duplicate-functions duplicate-functions
         :function-signature-conflicts signature-conflicts
         :statements (vec statements)
         :calls (vec calls)})))

   :internStmt
   (fn [[_ _intern-kw & tokens]]
     ;; Parse tokens: path1 / path2 / ClassName [as Alias]
     (let [;; Remove slash separators
           parts (remove #(= "/" %) tokens)
           ;; Check if "as" keyword exists
           has-alias? (some #(= "as" %) parts)
           ;; Split into path/class-name and optional alias
           main-parts (if has-alias?
                       (take-while #(not= "as" %) parts)
                       parts)
           alias (when has-alias?
                  (last parts))
           ;; Last part of main-parts is the class name
           class-name (last main-parts)
           ;; Everything before class name is the path
           path-parts (butlast main-parts)
           path (when (seq path-parts)
                 (clojure.string/join "/" path-parts))]
       {:type :intern
        :path path
        :class-name class-name
        :alias alias}))

   :importStmt
   (fn [[_ _import-kw & tokens]]
     ;; Parse tokens: package.name.Class [from 'path']
     (let [;; Check if "from" keyword exists
           has-from? (some #(= "from" %) tokens)
           ;; Split into qualified name and optional source path
           main-parts (if has-from?
                       (take-while #(not= "from" %) tokens)
                       tokens)
           source (when has-from? (last tokens))
           ;; Remove dot separators from main parts
           name-parts (remove #(= "." %) main-parts)
           ;; For JS imports: first part is the identifier, rest is ignored
           ;; For Java imports: join all parts with dots
           qualified-name (if has-from?
                           (first name-parts)  ; JS: just the identifier
                           (clojure.string/join "." name-parts))] ; Java: full qualified name
       {:type :import
        :qualified-name qualified-name
        :source source}))

   :classDecl
   (fn [[_ & tokens]]
     (let [[sealed? deferred? _class-kw name rest]
           (cond
             (and (= "sealed" (token-text (first tokens)))
                  (= "deferred" (token-text (second tokens))))
             [true true (nth tokens 2) (nth tokens 3) (drop 4 tokens)]
             (= "sealed" (token-text (first tokens)))
             [true false (second tokens) (nth tokens 2) (drop 3 tokens)]
             (= "deferred" (token-text (first tokens)))
             [false true (second tokens) (nth tokens 2) (drop 3 tokens)]
             :else
             [false false (first tokens) (second tokens) (drop 2 tokens)])
           ;; Filter out "end" keyword
           cleaned (remove #(= "end" %) rest)
           ;; Find different clauses
           generic-params (first (filter #(and (sequential? %)
                                              (= :genericParams (first %)))
                                        cleaned))
           note-clause (first (filter #(and (sequential? %)
                                            (= :noteClause (first %)))
                                     cleaned))
           inherit-clause (first (filter #(and (sequential? %)
                                               (= :inheritClause (first %)))
                                        cleaned))
           class-body (first (filter #(and (sequential? %)
                                          (= :classBody (first %)))
                                    cleaned))
           invariant-clause (first (filter #(and (sequential? %)
                                                  (= :invariantClause (first %)))
                                          cleaned))]
       {:type :class
        :name (token-text name)
        :deferred? deferred?
        :sealed? sealed?
        :generic-params (when generic-params (transform-node generic-params))
        :note (when note-clause (transform-node note-clause))
        :parents (when inherit-clause (transform-node inherit-clause))
        :body (walk-children class-body)
        :invariant (when invariant-clause (transform-node invariant-clause))}))

   :unionDecl
   (fn [[_ _union-kw name & rest]]
     (let [generic-params-node (first (filter #(and (sequential? %)
                                                    (= :genericParams (first %)))
                                              rest))
           note-clause (first (filter #(and (sequential? %)
                                            (= :noteClause (first %)))
                                      rest))
           generic-params (when generic-params-node (transform-node generic-params-node))
           variant-nodes (filter #(and (sequential? %)
                                       (= :unionVariant (first %)))
                                 rest)
           parent-name (token-text name)
           parent {:type :class
                   :name parent-name
                   :deferred? true
                   :sealed? true
                   :generic-params generic-params
                   :note (when note-clause (transform-node note-clause))
                   :parents nil
                   :body []
                   :invariant nil}
           variant-classes (mapv #(union-variant->class % parent-name generic-params)
                                 variant-nodes)]
       {:type :union
        :name parent-name
        :classes (into [parent] variant-classes)}))

   :functionDecl
   (fn [[_ _function-kw name & rest]]
     (build-function-node name rest false))

   :declareFunctionDecl
   (fn [[_ _declare-kw _function-kw name & rest]]
     (build-function-node name rest true))

   :anonymousFunction
   (fn [[_ _fn-kw & rest]]
     (let [cleaned (remove #(#{"(" ")" "do" "end" ":"} %) rest)
           generic-params (first (filter #(and (sequential? %)
                                               (= :genericParams (first %)))
                                         cleaned))
           params (first (filter #(and (sequential? %)
                                       (= :paramList (first %)))
                                 cleaned))
           return-type (first (filter #(and (sequential? %)
                                            (= :type (first %)))
                                      cleaned))
           block (first (filter #(and (sequential? %)
                                      (= :block (first %)))
                                cleaned))
           params-v (when params (transform-node params))
           return-type-v (when return-type (transform-node return-type))
           body (transform-node block)
           class-name (generate-unique-fn-name)
           method-name (str "call" (count params-v))
           explicit-generic-params (when generic-params (transform-node generic-params))
           generic-params-v (or explicit-generic-params
                                (vec (reduce (fn [acc {:keys [type]}]
                                               (into acc (collect-implicit-generic-names type)))
                                             (collect-implicit-generic-names return-type-v)
                                             params-v)))
           method-def {:type :method
                       :name method-name
                       :params params-v
                       :return-type return-type-v
                       :note nil
                       :require nil
                       :body body
                       :ensure nil}
           class-def {:type :class
                      :name class-name
                      :generic-params generic-params-v
                      :note nil
                      :parents [{:parent "Function"}]
                      :body [{:type :feature-section
                              :visibility {:type :public}
                              :members [method-def]}]
                      :invariant nil}]
       {:type :anonymous-function
        :class-name class-name
        :generic-params generic-params-v
        :params params-v
        :return-type return-type-v
        :body body
        :class-def class-def}))

   :inheritClause
   (fn [[_ _inherit-kw & entries]]
     (->> entries
          (filter #(and (sequential? %)
                        (= :inheritEntry (first %))))
          (mapv transform-node)))

   :typeName
   (fn [[_ name]]
     name)

   :inheritEntry
   (fn [[_ parent-name & rest]]
     (let [generic-args-node (first (filter #(and (sequential? %)
                                                  (= :typeArgs (first %)))
                                            rest))
           parent (if (sequential? parent-name)
                    (transform-node parent-name)
                    (token-text parent-name))]
       (cond-> {:parent parent}
         generic-args-node (assoc :generic-args (transform-node generic-args-node)))))

   :visibilityModifier
   (fn [node]
     ;; Return the node as-is, will be processed by featureSection
     node)

   :featureSection
   (fn [[_ first-elem & remaining]]
     ;; Structure: (:featureSection <visibility-modifier>? "feature" member*)
     ;; Check if first element is a visibility modifier or feature keyword
     (let [has-visibility? (and (sequential? first-elem)
                                (= :visibilityModifier (first first-elem)))
           visibility (when has-visibility?
                       (let [modifier first-elem]
                         (when (= "private" (token-text (second modifier)))
                           {:type :private})))
           ;; If has visibility: remaining = ("feature" member1 member2...)
           ;; If no visibility: remaining = (member1 member2...), first-elem = "feature"
           ;; In either case, we need to skip the "feature" keyword
           members-list (if has-visibility?
                         (drop 1 remaining)  ; Skip "feature" keyword
                         remaining)]          ; Already past "feature"
       {:type :feature-section
        :visibility (or visibility {:type :public})
        :members (mapv transform-node members-list)}))

   :featureMember
   (fn [[_ member]]
     (transform-node member))

   :constructorSection
   (fn [[_ _constructors-kw & ctors]]
     {:type :constructors
      :constructors (mapv transform-node ctors)})

   :fieldDecl
   (fn [[_ & tokens]]
     (let [once? (= "once" (token-text (first tokens)))
           tokens (if once? (rest tokens) tokens)
           name (first tokens)
           has-colon? (some #(= ":" %) tokens)
           eq-idx (first (keep-indexed (fn [i v] (when (= "=" v) i)) tokens))
           note-clause (first (filter #(and (sequential? %)
                                            (= :noteClause (first %)))
                                     tokens))]
       (if has-colon?
         (let [type-node (nth tokens 2)
               value-node (when eq-idx (nth tokens (inc eq-idx)))]
           {:type :field
            :name (token-text name)
            :field-type (transform-node type-node)
            :once? once?
            :constant? (boolean value-node)
            :value (when value-node (transform-node value-node))
            :note (when note-clause (transform-node note-clause))})
         (let [value-node (nth tokens 2)]
           {:type :field
            :name (token-text name)
            :field-type nil
            :once? false
            :constant? true
            :value (transform-node value-node)
            :note (when note-clause (transform-node note-clause))}))))

   :constructorDecl
   (fn [[_ name & rest]]
     (let [;; Filter out punctuation tokens
           cleaned (remove #(#{"(" ")" "do" "end"} %) rest)
           ;; Separate params, require, ensure, rescue, and block
           params (first (filter #(and (sequential? %)
                                       (= :paramList (first %)))
                                cleaned))
           require-clause (first (filter #(and (sequential? %)
                                               (= :requireClause (first %)))
                                        cleaned))
           ensure-clause (first (filter #(and (sequential? %)
                                              (= :ensureClause (first %)))
                                       cleaned))
           rescue-clause (first (filter #(and (sequential? %)
                                              (= :rescueClause (first %)))
                                       cleaned))
           block (first (filter #(and (sequential? %)
                                      (= :block (first %)))
                               cleaned))]
       {:type :constructor
        :name (token-text name)
        :params (when params (transform-node params))
        :require (when require-clause (transform-node require-clause))
        :body (transform-node block)
        :ensure (when ensure-clause (transform-node ensure-clause))
        :rescue (when rescue-clause (transform-node rescue-clause))}))

   :methodDecl
   (fn [[_ name & rest]]
     (let [;; Filter out punctuation tokens
           cleaned (remove #(#{"(" ")" "do" "end" ":" "deferred"} %) rest)
           ;; Separate params, return type, note, require, ensure, rescue, and block
           params (first (filter #(and (sequential? %)
                                       (= :paramList (first %)))
                                cleaned))
           return-type (first (filter #(and (sequential? %)
                                           (= :type (first %)))
                                     cleaned))
           note-clause (first (filter #(and (sequential? %)
                                            (= :noteClause (first %)))
                                     cleaned))
           require-clause (first (filter #(and (sequential? %)
                                               (= :requireClause (first %)))
                                        cleaned))
           ensure-clause (first (filter #(and (sequential? %)
                                              (= :ensureClause (first %)))
                                       cleaned))
           rescue-clause (first (filter #(and (sequential? %)
                                              (= :rescueClause (first %)))
                                       cleaned))
           block (first (filter #(and (sequential? %)
                                      (= :block (first %)))
                               cleaned))
           ;; A method is deferred when no body is present (body-less syntax)
           ;; or when the explicit 'deferred' keyword was used
           deferred? (or (nil? block) (some #(= "deferred" %) rest))]
       {:type :method
        :name (token-text name)
        :params (when params (transform-node params))
        :return-type (when return-type (transform-node return-type))
        :note (when note-clause (transform-node note-clause))
        :require (when require-clause (transform-node require-clause))
        :body (when-not deferred? (transform-node block))
        :declaration-only? (boolean deferred?)
        :ensure (when ensure-clause (transform-node ensure-clause))
        :rescue (when rescue-clause (transform-node rescue-clause))}))

   :paramList
   (fn [[_ & params]]
     (->> params
          (remove #(= "," %))
          (mapv transform-node)
          (apply concat)  ; Flatten since each param can now return multiple entries
          (vec)))

   :param
   (fn [[_ & parts]]
     ;; Parts can be: name1 "," name2 ":" type
     ;; or just: name ":" type
     ;; or just: name1 "," name2 (no type, defaults to Any)
     (let [;; Find type node (it's a sequential node)
           type-node (first (filter sequential? parts))
           ;; Everything before the colon is identifiers (filter out commas)
           identifiers (->> parts
                           (take-while #(not= ":" %))
                           (filter string?)
                           (remove #(= "," %)))
           param-type (if type-node (transform-node type-node) "Any")]
       ;; Return a vector of parameter maps, one for each identifier
       (mapv (fn [name]
               {:name (token-text name)
                :type param-type})
             identifiers)))

   :genericParams
   (fn [[_ _open-bracket & params]]
     ;; Filter out brackets and commas
     (let [param-nodes (filter #(and (sequential? %)
                                    (= :genericParam (first %)))
                              params)]
       (mapv transform-node param-nodes)))

   :genericParam
   (fn [[_ first-node & nodes]]
     ;; Structure: QMARK? param-name (ARROW constraint)?
     (let [[detachable? param-name tail]
           (if (= "?" first-node)
             [true (first nodes) (clojure.core/rest nodes)]
             [false first-node nodes])
           has-constraint? (some #(= "->" %) tail)
           constraint (when has-constraint?
                        ;; Get all elements after the arrow, filter for string identifiers
                        (let [after-arrow (drop-while #(not= "->" %) tail)]
                          (first (filter #(and (string? %)
                                               (not= "->" %))
                                         after-arrow))))]
       {:name (token-text param-name)
        :constraint constraint
        :detachable detachable?}))

   :genericArgs
   (fn [[_ _open-bracket & args]]
     (let [arg-nodes (filter #(and (sequential? %) (= :genericArg (first %))) args)]
       (mapv transform-node arg-nodes)))

   :genericArg
   (fn [[_ arg]]
     (if (sequential? arg)
       (transform-node arg)     ;; parameterized type like List[Integer]
       (token-text arg)))       ;; simple identifier like Integer

   :type
   (fn [[_ first-node & rest]]
     (cond
       ;; detachable type: ?T
       (= "?" first-node)
       (let [inner (transform-node (first rest))]
         (if (map? inner)
           (assoc inner :detachable true)
           {:base-type inner :detachable true}))

       ;; function type with signature: Function(...): T
       (and (sequential? first-node) (= :functionType (first first-node)))
       (transform-node first-node)

       ;; regular named type, optionally parameterized
       :else
       (let [type-name first-node
             type-args-node (first (filter #(and (sequential? %)
                                                 (= :typeArgs (first %)))
                                          rest))]
         (if type-args-node
           {:base-type (token-text type-name)
            :type-args (transform-node type-args-node)}
           (token-text type-name)))))

   :functionType
   (fn [[_ & tokens]]
     ;; tokens: "Function" optionally "(" functionTypeParams? ")" (":" type)?
     (let [has-sig? (some #(= "(" %) tokens)
           params-node (first (filter #(and (sequential? %) (= :functionTypeParams (first %))) tokens))
           return-type-node (first (filter #(and (sequential? %) (= :type (first %))) tokens))]
       (if has-sig?
         {:base-type "Function"
          :param-types (if params-node (transform-node params-node) [])
          :return-type (when return-type-node (transform-node return-type-node))}
         "Function")))

   :functionTypeParams
   (fn [[_ & tokens]]
     (->> tokens
          (remove #(= "," %))
          (filter #(and (sequential? %) (= :functionTypeParam (first %))))
          (mapv transform-node)))

   :functionTypeParam
   (fn [[_ & parts]]
     ;; Named: IDENTIFIER ":" type  →  {:name "a" :type "Integer"}
     ;; Positional: type             →  {:name nil :type "Integer"}
     (let [has-colon? (some #(= ":" %) parts)
           type-node (first (filter #(and (sequential? %) (= :type (first %))) parts))
           param-name (when has-colon? (first (filter string? parts)))]
       {:name param-name
        :type (when type-node (transform-node type-node))}))

   :declareTypeDecl
   (fn [[_ _declare-kw _type-kw name _eq type-node & where-rest]]
     ;; `declare type X = Base` is a structural alias. `... where n: <expr>`
     ;; makes it a refinement type: still an alias to Base for type checking, but
     ;; the predicate is recorded so the refinement pass can inject narrowing
     ;; checks. (Base is kept as :type-expr, so aliasing/transparency is free.)
     (let [base {:type :type-alias
                 :name (token-text name)
                 :type-expr (transform-node type-node)}]
       (if (and (seq where-rest)
                (= "where" (token-text (first where-rest))))
         (let [binder (token-text (nth where-rest 1))
               predicate (transform-node (last (filter sequential? where-rest)))]
           (assoc base :refinement {:binder binder :predicate predicate}))
         base)))

   :typeArgs
   (fn [[_ _open-bracket & args]]
     ;; Filter out brackets and commas, get type nodes
     (let [type-nodes (filter #(and (sequential? %)
                                   (= :type (first %)))
                             args)]
       (mapv transform-node type-nodes)))

   :block
   (fn [[_ & statements]]
     (mapv transform-node statements))

   :statement
   (fn [[_ stmt]]
     (transform-node stmt))

   :scopedBlock
   (fn [[_ _do-kw & rest]]
     (let [cleaned (remove #(#{"do" "end"} %) rest)
           rescue-clause (first (filter #(and (sequential? %) (= :rescueClause (first %))) cleaned))
           block (first (filter #(and (sequential? %) (= :block (first %))) cleaned))]
       {:type :scoped-block
        :body (transform-node block)
        :rescue (when rescue-clause (transform-node rescue-clause))}))

   :caseStatement
   (fn [[_ _case-kw expr _of-kw & rest]]
     ;; rest: caseClause+ ("else" statement)? "end"
     (let [tokens (vec rest)
           clauses (filterv #(and (sequential? %) (= :caseClause (first %))) tokens)
           ;; Check for else clause: "else" followed by a statement node
           has-else? (some #(= "else" %) tokens)
           else-stmt (when has-else?
                       (let [after-else (second (drop-while #(not= "else" %) tokens))]
                         (when (and (sequential? after-else) (not= "end" after-else))
                           (transform-node after-else))))]
       {:type :case
        :expr (transform-node expr)
       :clauses (mapv transform-node clauses)
       :else else-stmt}))

   :matchStatement
   (fn [[_ _match-kw expr _of-kw & rest]]
     (let [tokens (vec rest)
           clause-nodes (filterv #(and (sequential? %) (= :matchClause (first %))) tokens)
           transformed (mapv transform-node clause-nodes)
           ;; A `when _` clause is a catch-all; fold its body into `else`.
           wildcard (first (filter :wildcard? transformed))
           clauses (filterv #(not (:wildcard? %)) transformed)
           has-else? (some #(= "else" %) tokens)
           explicit-else (when has-else?
                           (let [after-else (drop-while #(not= "else" %) tokens)
                                 block-node (second after-else)]
                             (when (and (sequential? block-node) (= :block (first block-node)))
                               (transform-node block-node))))]
       {:type :match
        :expr (transform-node expr)
        :clauses clauses
        :else (or explicit-else (when wildcard (:body wildcard)))}))

   :fieldPattern
   (fn [[_ & toks]]
     ;; `id`         -> bind field `id` to local `id`
     ;; `id: x`      -> bind field `id` to local `x`
     ;; `id: 0`      -> require field `id` to equal the literal 0
     ;; `id: T(...)` -> require field `id` to be a `T`, matching the sub-patterns
     (let [field (token-text (first toks))
           has-colon? (some #(= ":" %) toks)
           after (rest (drop-while #(not= ":" %) toks))]
       (cond
         (not has-colon?)
         {:kind :bind :field field :bind field}

         (some #(= "(" %) toks)
         (let [tn (first after)
               type-name (if (sequential? tn) (transform-node tn) (token-text tn))
               type-args-node (first (filter #(and (sequential? %) (= :typeArgs (first %))) after))
               subpats (mapv transform-node
                             (filter #(and (sequential? %) (= :fieldPattern (first %))) after))]
           {:kind :nested :field field :type type-name
            :generic-args (when type-args-node (transform-node type-args-node))
            :subpatterns subpats})

         (some sequential? after)
         {:kind :literal :field field :value (transform-node (first (filter sequential? after)))}

         :else
         {:kind :bind :field field :bind (token-text (first (filter string? after)))})))

   :matchClause
   (fn [[_ _when-kw class-name & rest]]
     (let [tokens (vec rest)
           type-args-node (first (filter #(and (sequential? %) (= :typeArgs (first %))) tokens))
           generic-args (when type-args-node (transform-node type-args-node))
           field-patterns (mapv transform-node
                                (filter #(and (sequential? %) (= :fieldPattern (first %))) tokens))
           as-idx (first (keep-indexed (fn [i v] (when (= "as" v) i)) tokens))
           explicit-var (when as-idx (token-text (nth tokens (inc as-idx))))
           if-idx (first (keep-indexed (fn [i v] (when (= "if" v) i)) tokens))
           explicit-guard (when if-idx (transform-node (nth tokens (inc if-idx))))
           body-node (first (filter #(and (sequential? %) (= :block (first %))) tokens))
           body (transform-node body-node)
           resolved-class-name (if (sequential? class-name)
                                 (transform-node class-name)
                                 (token-text class-name))]
       (if (and (= resolved-class-name "_") (empty? field-patterns))
         ;; Top-level wildcard: handled as `else` by :matchStatement.
         {:wildcard? true :body body}
         ;; A synthetic binder lets a clause read destructured fields even when it
         ;; does not bind the whole value with `as`. Field patterns desugar to
         ;; `:bindings` (direct binds, before the guard), `:guard` conjuncts
         ;; (literal equalities and nested `convert`s, ANDed with any explicit
         ;; `if`), and body-prepended binds for nested sub-fields.
         (let [var-name (or explicit-var (str "__match_" (swap! next-fn-id inc) "__"))
               {:keys [bindings guards body-binds]} (process-field-patterns var-name field-patterns)
               guard-parts (concat guards (when explicit-guard [explicit-guard]))
               guard (when (seq guard-parts)
                       (reduce (fn [a b] {:type :binary :operator "and" :left a :right b})
                               guard-parts))]
           {:class-name resolved-class-name
            :generic-args generic-args
            :var-name var-name
            :bindings bindings
            :guard guard
            :body (into (vec body-binds) body)}))))

   :selectStatement
   (fn [[_ _select-kw & rest]]
     (let [tokens (vec rest)
           clauses (filterv #(and (sequential? %) (= :selectClause (first %))) tokens)
           timeout-clause (first (filter #(and (sequential? %) (= :timeoutClause (first %))) tokens))
           has-else? (some #(= "else" %) tokens)
           else-block (when has-else?
                        (let [after-else (second (drop-while #(not= "else" %) tokens))]
                          (when (and (sequential? after-else) (= :block (first after-else)))
                            (transform-node after-else))))]
       {:type :select
        :clauses (mapv transform-node clauses)
        :timeout (when timeout-clause (transform-node timeout-clause))
        :else else-block}))

   :selectClause
   (fn [[_ _when-kw expr & rest]]
     (let [tokens (vec rest)
           alias (when (some #(= "as" %) tokens)
                   (token-text (second (drop-while #(not= "as" %) tokens))))
           then-block (first (filter #(and (sequential? %) (= :block (first %))) tokens))]
       {:expr (transform-node expr)
        :alias alias
        :body (transform-node then-block)}))

   :timeoutClause
   (fn [[_ _timeout-kw duration _then-kw block]]
     {:duration (transform-node duration)
      :body (transform-node block)})

   :caseClause
   (fn [[_ & tokens]]
     ;; tokens: literal ("," literal)* "then" statement
     (let [parts (vec tokens)
           then-idx (first (keep-indexed (fn [i v] (when (= "then" v) i)) parts))
           literals (->> (subvec parts 0 then-idx)
                         (remove #(= "," %))
                         (mapv transform-node))
           body (transform-node (nth parts (inc then-idx)))]
       {:values literals
        :body body}))

   :ifStatement
   (fn [[_ _if-kw & rest]]
     ;; rest: condition "then" block ("elseif" condition "then" block)* ("else" block)? "end"
     (let [tokens (vec rest)
           ;; First condition and then-block
           condition (nth tokens 0)
           ;; skip "then" at index 1
           then-block (nth tokens 2)
           ;; Remaining tokens after the first then-block, before "end"
           remaining (subvec tokens 3)
           ;; Parse elseif clauses and optional else
           [elseif-clauses else-block]
           (loop [toks remaining
                  elseifs []]
             (cond
               ;; "end" - done
               (or (empty? toks) (= "end" (first toks)))
               [elseifs nil]
               ;; "elseif" condition "then" block
               (= "elseif" (first toks))
               (recur (subvec (vec toks) 4)
                      (conj elseifs {:condition (transform-node (nth toks 1))
                                     :then (transform-node (nth toks 3))}))
               ;; "else" block ["end"]
               (= "else" (first toks))
               [elseifs (transform-node (second toks))]
               ;; skip unexpected tokens
               :else
               (recur (rest toks) elseifs)))]
       {:type :if
        :condition (transform-node condition)
        :then (transform-node then-block)
        :elseif elseif-clauses
        :else else-block}))

   :loopStatement
   (fn [[_ _from-kw init-block & rest]]
     (let [;; Filter out keywords
           cleaned (remove #(#{"until" "do" "end"} %) rest)
           ;; Find optional clauses
           invariant-clause (first (filter #(and (sequential? %)
                                                  (= :invariantClause (first %)))
                                          cleaned))
           variant-clause (first (filter #(and (sequential? %)
                                               (= :variantClause (first %)))
                                        cleaned))
           ;; Find until condition and body
           until-expr (first (filter #(and (sequential? %)
                                          (= :expression (first %)))
                                    cleaned))
           body-block (first (filter #(and (sequential? %)
                                          (= :block (first %)))
                                    cleaned))]
       {:type :loop
        :init (transform-node init-block)
        :invariant (when invariant-clause (transform-node invariant-clause))
        :variant (when variant-clause (transform-node variant-clause))
        :until (transform-node until-expr)
        :body (transform-node body-block)}))

   :repeatStatement
   (fn [[_ _repeat-kw count-expr _do-kw body-block _end-kw]]
     (let [counter-name "__repeat_i__"
           counter-id {:type :identifier :name counter-name}
           count-ast (transform-node count-expr)
           body-stmts (transform-node body-block)]
       {:type :loop
        :init [{:type :let :name counter-name :value {:type :integer :value 0 :text "0"}}]
        :invariant nil
        :variant nil
        :until {:type :binary :operator "=" :left counter-id :right count-ast}
        :body (conj (vec body-stmts)
                    {:type :assign
                     :target counter-name
                     :value {:type :binary
                             :operator "+"
                             :left counter-id
                             :right {:type :integer :value 1 :text "1"}}})}))

   :acrossStatement
   (fn [[_ _across-kw collection-expr _as-kw alias-name _do-kw body-block _end-kw]]
     (let [cursor-name (str "__across_c_" (swap! next-fn-id inc) "__")
           cursor-id {:type :identifier :name cursor-name}
           collection-ast (transform-node collection-expr)
           alias (token-text alias-name)
           body-stmts (transform-node body-block)]
       {:type :loop
        :init [{:type :let
                :name cursor-name
                :synthetic true
                :value {:type :call
                        :target collection-ast
                        :method "cursor"
                        :args []}}
               {:type :call
                :target cursor-name
                :method "start"
                :args []}]
        :invariant nil
        :variant nil
        :until {:type :call
                :target cursor-name
                :method "at_end"
                :args []}
        :body (vec (concat
                    [{:type :let
                      :name alias
                      :synthetic true
                      :value {:type :call
                              :target cursor-name
                              :method "item"
                              :args []}}]
                    body-stmts
                    [{:type :call
                      :target cursor-name
                      :method "next"
                      :args []}]))}))

   :withStatement
   (fn [[_ _with-kw target-string _do-kw body-block _end-kw]]
     (let [target (string-literal-value (token-text target-string))]
       {:type :with
        :target target
        :body (transform-node body-block)}))

   :variantClause
   (fn [[_ _variant-kw expr]]
     (transform-node expr))

   :requireClause
   (fn [[_ _require-kw & assertions]]
     (mapv transform-node assertions))

   :ensureClause
   (fn [[_ _ensure-kw & assertions]]
     (mapv transform-node assertions))

   :rescueClause
   (fn [[_ _rescue-kw block]]
     (transform-node block))

   :raiseStatement
   (fn [[_ _raise-kw expr]]
     {:type :raise :value (transform-node expr)})

   :retryStatement
   (fn [[_ _retry-kw]]
     {:type :retry})

   :invariantClause
   (fn [[_ _invariant-kw & assertions]]
     (mapv transform-node assertions))

   :noteClause
   (fn [[_ _note-kw string-literal]]
     (string-literal-value (token-text string-literal)))

   :assertion
   (fn [[_ label _colon expr]]
     {:label (token-text label)
      :condition (transform-node expr)})

   :assignment
   (fn [[_ first-token & rest]]
     (if (= ":=" (first rest))
       ;; Simple assignment: IDENTIFIER := expression
       (let [[_assign expr] rest]
         {:type :assign
          :target (token-text first-token)
          :value (transform-node expr)})
       ;; Member assignment: target.field := expr
       ;; Tokens: primary "." IDENTIFIER ":=" expression
       (let [[_dot field-name _assign expr] rest
             object-expr (if (and (string? first-token)
                                  (not= first-token "this"))
                           {:type :identifier
                            :name (token-text first-token)}
                           (transform-node first-token))]
         {:type :member-assign
          :object object-expr
          :field (token-text field-name)
          :value (transform-node expr)})))

   :localVarDecl
   (fn [[_ _let name & rest]]
     ;; Handle optional type: "let x: Integer := 10" or "let x := 10"
     (let [has-type? (and (>= (count rest) 4)
                          (= ":" (first rest)))
           ;; Extract and transform type node if present
           var-type (when has-type?
                     (let [type-node (second rest)]
                       (if (sequential? type-node)
                         ;; Transform the type node (handles both simple and parameterized types)
                         (transform-node type-node)
                         type-node)))
           assign-idx (if has-type? 2 0)
           expr (nth rest (inc assign-idx))]
       {:type :let
        :name (token-text name)
        :var-type var-type
        :value (transform-node expr)}))

   :argumentList
   (fn [[_ & args]]
     (->> args
          (remove #(= "," %))
          (mapv transform-node)))

   :methodCall
   (fn [[_ & rest]]
     (cond
       ;; Parameterless call without parentheses: IDENTIFIER
       (and (= 1 (count rest))
            (string? (first rest)))
       {:type :call
        :target nil
        :method (first rest)
        :args []
        :has-parens false}

       ;; Chained call: primary callChain
       :else
       (let [[primary-node call-chain] rest
             base (transform-node primary-node)
             parts (transform-node call-chain)]
         (desugar-safe-call
          (reduce (fn [acc part]
                    (case (:type part)
                      :member-access
                      (cond-> {:type :call
                               :target (call-target acc)
                               :method (:name part)
                               :args (:args part)}
                        (some? (:has-parens part))
                        (assoc :has-parens (:has-parens part))
                        (:safe? part)
                        (assoc :safe? true))

                      :call-suffix
                      (cond
                        ;; Function call: f(...)
                        (and (map? acc) (= :identifier (:type acc)))
                        {:type :call
                         :target nil
                         :method (:name acc)
                         :args (:args part)
                         :has-parens true}

                        ;; Method call split as memberAccess + callSuffix: obj.m(...)
                        (and (map? acc)
                             (= :call (:type acc))
                             (some? (:method acc))
                             (not (:has-parens acc)))
                        (assoc acc
                               :args (:args part)
                               :has-parens true)

                        ;; Call on expression result: (expr)(...)
                        :else
                        {:type :call
                         :target acc
                         :method nil
                         :args (:args part)
                         :has-parens true})

                      acc))
                  base
                  parts)))))

   :callChain
   (fn [[_ & parts]]
     (->> parts
          (filter sequential?)
          (map transform-node)))

   :expression
   (fn [[_ expr]]
     (transform-node expr))

   ;; Binary operators (using our reusable handler)
   :addition (make-binary-op-handler nil)
   :multiplication (make-binary-op-handler nil)
   :comparison (make-binary-op-handler nil)
   :equality (make-binary-op-handler nil)
   :logicalAnd (make-binary-op-handler "and")
   :logicalOr (make-binary-op-handler "or")

   ;; Unary operators
   :unary
   (fn [[_ first-child & rest-children]]
     (cond
       (= first-child "-")
       (let [transformed (transform-node (first rest-children))]
         (if-let [restructured (and (= :call (:type transformed))
                                    (negate-numeric-call-chain transformed))]
           restructured
           {:type :unary
            :operator "-"
            :expr transformed}))
       (= first-child "not")
       {:type :unary
        :operator "not"
        :expr (transform-node (first rest-children))}
       :else
       (transform-node first-child)))

   :unaryMinus
   (fn [[_ _minus expr]]
     (let [transformed (transform-node expr)]
       (if-let [restructured (and (= :call (:type transformed))
                                  (negate-numeric-call-chain transformed))]
         restructured
         {:type :unary
          :operator "-"
          :expr transformed})))

   :unaryNot
   (fn [[_ _not expr]]
     {:type :unary
      :operator "not"
      :expr (transform-node expr)})

   :postfixExpr
   (fn [[_ postfix]]
     (transform-node postfix))

   :postfix
   (fn [[_ primary-node & parts]]
     (let [base (transform-node primary-node)
           parts (->> parts
                      (filter sequential?)
                      (map transform-node))]
       (reduce (fn [acc part]
                 (case (:type part)
                   :member-access
                   (desugar-safe-expression-call
                    (cond-> {:type :call
                             :target (call-target acc)
                             :method (:name part)
                             :args (:args part)}
                      (some? (:has-parens part))
                      (assoc :has-parens (:has-parens part))
                      (:safe? part)
                      (assoc :safe? true)))

                   :call-suffix
                   (cond
                     ;; Function call: f(...)
                     (and (map? acc) (= :identifier (:type acc)))
                     {:type :call
                      :target nil
                      :method (:name acc)
                      :args (:args part)
                      :has-parens true}

                     ;; Method call split as memberAccess + callSuffix: obj.m(...)
                     (and (map? acc)
                          (= :call (:type acc))
                          (some? (:method acc))
                          (not (:has-parens acc)))
                     (desugar-safe-expression-call
                      (assoc acc
                             :args (:args part)
                             :has-parens true))

                     ;; Call on expression result: (expr)(...)
                     :else
                     {:type :call
                      :target acc
                      :method nil
                      :args (:args part)
                      :has-parens true})

                   acc))
               base
               parts)))

   :postfixPart
   (fn [[_ part]]
     (transform-node part))

   :memberAccess
   (fn [[_ & children]]
     (let [safe? (boolean (some #(= "?" %) children))
           name (first (filter #(and (string? %)
                                     (not (#{"?" "." "(" ")"} %)))
                               children))
           rest (drop-while #(not= name %) children)
           has-parens (boolean (some #(= "(" %) rest))
           args-node (first (filter #(and (sequential? %)
                                         (= :argumentList (first %)))
                                    rest))]
       (cond-> {:type :member-access
                :name (token-text name)
                :has-parens has-parens
                :args (if args-node
                        (transform-node args-node)
                        [])}
         safe? (assoc :safe? true))))

   :callSuffix
   (fn [[_ & rest]]
     (let [args-node (first (filter #(and (sequential? %)
                                         (= :argumentList (first %)))
                                    rest))]
       {:type :call-suffix
        :args (if args-node
               (transform-node args-node)
               [])}))

   :primaryExpr
   (fn [[_ primary]]
     (transform-node primary))

   ;; Literals
   :integerLiteral
   (fn [[_ value]]
     (let [v (parse-integer-literal value)]
       {:type :integer
        :value v
        ;; Exact decimal string so the literal survives a JVM->JS AST transfer
        ;; without precision loss (see eval-node :integer, NUMERIC_TOWER.md).
        :value-str (str v)}))

   :realLiteral
   (fn [[_ value]]
     {:type :real
      :value #?(:clj (Double/parseDouble value)
                :cljs (js/parseFloat value))})

   :booleanLiteral
   (fn [[_ value]]
     {:type :boolean
      :value (= value "true")})

   :nilLiteral
   (fn [[_ _value]]
     {:type :nil})

   :charLiteral
   (fn [[_ value]]
     (let [v0 (subs value 1)
           is-code (re-matches #"\d+" v0)
           v (if is-code v0 (maybe-transform-special-char v0))]
       {:type :char
        :value (if is-code
                 #?(:clj (char (Integer/parseInt v))
                    :cljs (.fromCharCode js/String (js/parseInt v)))
                 (first v))}))

   :arrayLiteral
   (fn [[_ _open-bracket & elements]]
     ;; Filter out brackets and commas, get expression nodes
     (let [expr-nodes (filter #(and (sequential? %)
                                   (= :expression (first %)))
                             elements)]
       {:type :array-literal
        :elements (mapv transform-node expr-nodes)}))

   :setLiteral
   (fn [[_ _open-brace & elements]]
     (let [expr-nodes (filter #(and (sequential? %)
                                    (= :expression (first %)))
                              elements)]
       {:type :set-literal
        :elements (mapv transform-node expr-nodes)}))

   :mapLiteral
   (fn [[_ _open-brace & entries]]
     ;; Filter out braces and commas, get mapEntry nodes
     (let [entry-nodes (filter #(and (sequential? %)
                                    (= :mapEntry (first %)))
                              entries)]
       {:type :map-literal
        :entries (mapv transform-node entry-nodes)}))

   :mapEntry
   (fn [[_ key _colon value]]
     {:key (if (string? key)
            ;; String literal key (double- or single-quoted) or identifier key
            (if (string-literal-token? key)
              {:type :string :value (string-literal-value key)}
              {:type :string :value key})
            (transform-node key))
      :value (transform-node value)})

   :literal
   (fn [[_ lit]]
     (if (string? lit)
       ;; String literals (double- or single-quoted) reach here as token text
       (if (string-literal-token? lit)
         {:type :string
          :value (string-literal-value lit)}
         (transform-node lit))
       (transform-node lit)))

   :primary
   (fn [[_ & children]]
     (if (= 1 (count children))
       (let [child (first children)]
         (cond
           (= child "this") {:type :this}
           (and (string? child) (not (.startsWith child "\"")))
           ;; It's an identifier (not a string literal)
           {:type :identifier :name child}
           :else
           ;; Otherwise, transform normally
           (transform-node child)))
       ;; Handle parenthesized expressions
       (transform-node (second children))))

   :createExpression
   (fn [[_ _create-kw class-name & rest]]
     ;; Structure: "create" ClassName genericArgs? ("." ConstructorName "(" argumentList? ")")?
     (let [;; Extract generic args node if present
           generic-args-node (first (filter #(and (sequential? %) (= :genericArgs (first %))) rest))
           generic-args (when generic-args-node (transform-node generic-args-node))
           ;; Remove punctuation and genericArgs node
           cleaned (remove #(or (#{"." "(" ")"} %)
                               (and (sequential? %) (= :genericArgs (first %)))) rest)
           ;; Check if there's a constructor call
           has-constructor? (seq cleaned)
           constructor-name (when has-constructor? (first cleaned))
           ;; Find argument list if present
           args-node (first (filter #(and (sequential? %)
                                          (= :argumentList (first %)))
                                   rest))]
       {:type :create
        :class-name (token-text class-name)
        :generic-args generic-args
        :constructor (when has-constructor? constructor-name)
       :args (if args-node
               (transform-node args-node)
               [])}))

   :convertExpression
   (fn [[_ _convert-kw value-expr _to-kw var-name _colon type-expr]]
     {:type :convert
      :value (transform-node value-expr)
      :var-name (token-text var-name)
      :target-type (transform-node type-expr)})

   :spawnExpression
   (fn [[_ _spawn-kw _do-kw block _end-kw]]
     {:type :spawn
      :body (transform-node block)})

   :oldExpression
   (fn [[_ _old-kw expr]]
     {:type :old
      :expr (transform-node expr)})

   :whenExpression
   (fn [[_ _when-kw condition _then-kw consequent _else-kw alternative _end-kw]]
     {:type :when
      :condition (transform-node condition)
      :consequent (transform-node consequent)
      :alternative (transform-node alternative)})})

;;
;; Core transformation function (defined after node-handlers)
;;

(defn transform-node
  "Main tree transformation dispatcher. Uses a data-driven approach
   with the node-handlers map for transformation."
  [node]
  (cond
    ;; Leaf nodes (strings, numbers, etc.)
    (not (sequential? node))
    node

    ;; Empty nodes
    (empty? node)
    nil

    ;; Dispatch based on node type
    :else
    (let [node-type (first node)
          handler (get node-handlers node-type)]
      (if handler
        (attach-debug-pos (handler node) node)
        (throw (ex-info (str "Unhandled node type: " node-type)
                        {:node-type node-type
                         :node node}))))))

;;
;; Public API
;;

(defn walk-node
  "Transform an ANTLR parse tree into a clean AST.
   This is the main entry point for tree transformation."
  [parse-tree]
  (try
    (transform-node parse-tree)
    (catch #?(:clj Exception :cljs :default) e
      (let [err-message #?(:clj (.getMessage e)
                           :cljs (.-message e))]
        (throw (ex-info err-message
                        {:parse-tree parse-tree
                         :cause err-message}
                        e))))))
