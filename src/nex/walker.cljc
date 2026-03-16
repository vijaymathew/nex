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

(defn make-simple-container-handler
  "Creates a handler that wraps children in a typed map."
  [type-keyword children-key]
  (fn [[_ & children]]
    {type-keyword (mapv transform-node children)}))

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

;;
;; Node handlers map (data-driven transformations)
;;

(def ^:private next-fn-id (atom 0))

(defn- generate-unique-fn-name []
  (str "AnonymousFunction_" (swap! next-fn-id inc)))

(def node-handlers
  {:program
   (fn [[_ & nodes]]
     (let [cleaned-nodes (remove string? nodes) ; Filter out "<EOF>" token
           transformed (mapv transform-node cleaned-nodes)
           classes (filter #(= :class (:type %)) transformed)
           functions (->> transformed
                          (filter #(= :function (:type %)))
                          (reduce (fn [m f] (assoc m (:name f) f)) {})
                          vals
                          vec)
           interns (filter #(= :intern (:type %)) transformed)
           imports (filter #(= :import (:type %)) transformed)
           statements (filter #(not (#{:class :function :intern :import} (:type %))) transformed)
           calls (filter #(= :call (:type %)) statements)
           function-classes (mapv :class-def functions)
           all-classes (vec (concat classes function-classes))]
       {:type :program
        :imports (vec imports)
        :interns (vec interns)
        :classes all-classes
        :functions (vec functions)
        :statements (vec statements)
        :calls (vec calls)}))

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
     (let [[deferred? _class-kw name rest]
           (if (= "deferred" (token-text (first tokens)))
             [true (second tokens) (nth tokens 2) (drop 3 tokens)]
             [false (first tokens) (second tokens) (drop 2 tokens)])
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
        :generic-params (when generic-params (transform-node generic-params))
        :note (when note-clause (transform-node note-clause))
        :parents (when inherit-clause (transform-node inherit-clause))
        :body (walk-children class-body)
        :invariant (when invariant-clause (transform-node invariant-clause))}))

   :functionDecl
   (fn [[_ _function-kw name & rest]]
     (let [cleaned (remove #(#{"(" ")" "do" "end" ":"} %) rest)
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
           params-v (when params (transform-node params))
           return-type-v (when return-type (transform-node return-type))
           declaration-only? (nil? block)
           body (when block (transform-node block))
           fn-name (token-text name)
           class-name (str fn-name "_Function")
           method-name (str "call" (count params-v))
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
                      :generic-params nil
                      :note nil
                      :parents [{:parent "Function"}]
                      :body [{:type :feature-section
                              :visibility {:type :public}
                              :members [method-def]}]
                      :invariant nil}]
       {:type :function
        :name fn-name
        :class-name class-name
        :declaration-only? declaration-only?
        :params params-v
        :return-type return-type-v
        :body body
        :class-def class-def}))

   :anonymousFunction
   (fn [[_ _fn-kw & rest]]
     (let [cleaned (remove #(#{"(" ")" "do" "end" ":"} %) rest)
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
                      :generic-params nil
                      :note nil
                      :parents [{:parent "Function"}]
                      :body [{:type :feature-section
                              :visibility {:type :public}
                              :members [method-def]}]
                      :invariant nil}]
       {:type :anonymous-function
        :class-name class-name
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

   :inheritEntry
   (fn [[_ parent-name]]
     {:parent (token-text parent-name)})

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
                         (if (= "private" (token-text (second modifier)))
                           {:type :private}
                           ;; Selective visibility: extract class names from (:visibilityModifier "->" "Friend" "," "Helper")
                           ;; Filter out arrow and commas, keep only identifiers
                           (let [class-names (filter #(and (string? %)
                                                          (not (#{"->" ","} %)))
                                                    (rest modifier))]
                             {:type :selective
                              :classes (vec class-names)}))))
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
     (let [name (first tokens)
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
            :constant? (boolean value-node)
            :value (when value-node (transform-node value-node))
            :note (when note-clause (transform-node note-clause))})
         (let [value-node (nth tokens 2)]
           {:type :field
            :name (token-text name)
            :field-type nil
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
           cleaned (remove #(#{"(" ")" "do" "end" ":"} %) rest)
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
                               cleaned))]
       {:type :method
        :name (token-text name)
        :params (when params (transform-node params))
        :return-type (when return-type (transform-node return-type))
        :note (when note-clause (transform-node note-clause))
        :require (when require-clause (transform-node require-clause))
        :body (transform-node block)
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
     ;; detachable type: ?T
     (if (= "?" first-node)
       (let [inner (transform-node (first rest))]
         (if (map? inner)
           (assoc inner :detachable true)
           {:base-type inner :detachable true}))
       ;; regular type, optionally parameterized
       (let [type-name first-node
             type-args-node (first (filter #(and (sequential? %)
                                                 (= :typeArgs (first %)))
                                          rest))]
         (if type-args-node
           {:base-type (token-text type-name)
            :type-args (transform-node type-args-node)}
           (token-text type-name)))))

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
     (let [;; Extract target string, removing quotes
           target (let [s (token-text target-string)]
                   (subs s 1 (dec (count s))))]
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
     ;; Extract the string content, removing quotes
     (let [s (token-text string-literal)]
       (subs s 1 (dec (count s)))))

   :assertion
   (fn [[_ label _colon expr]]
     {:label (token-text label)
      :condition (transform-node expr)})

   :assignment
   (fn [[_ first-token & rest]]
     (if (= first-token "this")
       ;; Member assignment: this.field := expr
       ;; Tokens: THIS "." IDENTIFIER ":=" expression
       (let [[_dot field-name _assign expr] rest]
         {:type :member-assign
          :object-type :this
          :field (token-text field-name)
          :value (transform-node expr)})
       ;; Simple assignment: IDENTIFIER := expression
       (let [[_assign expr] rest]
         {:type :assign
          :target (token-text first-token)
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
         (reduce (fn [acc part]
                 (case (:type part)
                   :member-access
                   (cond-> {:type :call
                            :target (if (and (map? acc) (= :identifier (:type acc)))
                                      (:name acc)
                                      acc)
                            :method (:name part)
                            :args (:args part)}
                     (some? (:has-parens part))
                     (assoc :has-parens (:has-parens part)))

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
                 parts))))

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
       {:type :unary
        :operator "-"
        :expr (transform-node (first rest-children))}
       (= first-child "not")
       {:type :unary
        :operator "not"
        :expr (transform-node (first rest-children))}
       :else
       (transform-node first-child)))

   :unaryMinus
   (fn [[_ _minus expr]]
     {:type :unary
      :operator "-"
      :expr (transform-node expr)})

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
                   (cond-> {:type :call
                            :target (if (and (map? acc) (= :identifier (:type acc)))
                                      (:name acc)
                                      acc)
                            :method (:name part)
                            :args (:args part)}
                     (some? (:has-parens part))
                     (assoc :has-parens (:has-parens part)))

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
               parts)))

   :postfixPart
   (fn [[_ part]]
     (transform-node part))

   :memberAccess
   (fn [[_ _dot name & rest]]
     (let [has-parens (boolean (some #(= "(" %) rest))
           args-node (first (filter #(and (sequential? %)
                                         (= :argumentList (first %)))
                                    rest))]
       {:type :member-access
        :name (token-text name)
        :has-parens has-parens
        :args (if args-node
               (transform-node args-node)
               [])}))

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
     {:type :integer
      :value (parse-integer-literal value)})

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
            ;; String or identifier key
            (if (.startsWith key "\"")
              {:type :string :value (subs key 1 (dec (count key)))}
              {:type :string :value key})
            (transform-node key))
      :value (transform-node value)})

   :literal
   (fn [[_ lit]]
     (if (string? lit)
       ;; Handle string literals directly (they start with ")
       (if (.startsWith lit "\"")
         {:type :string
          :value (subs lit 1 (dec (count lit)))} ; Remove quotes
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
   (fn [[_ _when-kw condition consequent _else-kw alternative _end-kw]]
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
      (throw (ex-info "Failed to transform parse tree"
                      {:parse-tree parse-tree
                       :cause #?(:clj (.getMessage e)
                                :cljs (.-message e))}
                      e)))))
