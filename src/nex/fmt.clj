(ns nex.fmt
  "`nex format`, and Nex source text for an expression (used in error
   messages)."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [nex.parser :as p])
  (:gen-class))

(def indent-size 2)

(defn indent
  "Create indentation string for given level"
  [level]
  (str/join (repeat (* level indent-size) " ")))

(defn format-visibility
  "Format visibility modifier"
  [{:keys [type classes]}]
  (case type
    :private "private "
    :selective (str "-> " (str/join ", " classes) " ")
    :public ""))

(defn format-type
  "Format a type expression"
  [type-expr]
  (cond
    (string? type-expr) type-expr
    (map? type-expr)
    (let [base (:base-type type-expr)
          params (:type-params type-expr)
          core (if params
                 (str base " [" (str/join ", " (map format-type params)) "]")
                 base)]
      (if (:detachable type-expr) (str "?" core) core))
    :else (str type-expr)))

(declare format-statement)

(defn format-expression
  "Format an expression"
  [expr]
  (cond
    (nil? expr) "nil"
    (string? expr) expr
    (number? expr) (str expr)
    (boolean? expr) (str expr)
    (map? expr)
    (case (:type expr)
      :integer (str (:value expr))
      :byte (str (:value expr) "u8")
      :int16 (str (:value expr) "i16")
      :int32 (str (:value expr) "i32")
      :real (or (:source expr) (str (:value expr)))
      :string (str "\"" (:value expr) "\"")
      :char (str "'" (:value expr) "'")
      :boolean (str (:value expr))
      :nil "nil"
      :identifier (:name expr)
      :old (str "old " (format-expression (:expr expr)))
      :spawn (str "spawn do\n"
                  (str/join "\n" (map #(format-statement % 1) (:body expr)))
                  "\nend")
      :convert (str "convert "
                    (format-expression (:value expr))
                    " to "
                    (:var-name expr)
                    ":"
                    (format-type (:target-type expr)))
      :attached-test (str "?" (format-expression (:value expr)) " as " (:var-name expr))
      :binary (str (format-expression (:left expr))
                   " " (:operator expr) " "
                   (format-expression (:right expr)))
      :unary (let [op (:operator expr)]
               (str op (when (= "not" op) " ") (format-expression (:expr expr))))
      :when (str "when " (format-expression (:condition expr))
                 " then " (format-expression (:consequent expr))
                 " else " (format-expression (:alternative expr))
                 " end")
      :call (if (:target expr)
              (str (format-expression (:target expr)) "." (:method expr)
                   (when (seq (:args expr))
                     (str "(" (str/join ", " (map format-expression (:args expr))) ")")))
              (str (:method expr) "(" (str/join ", " (map format-expression (:args expr))) ")"))
      :create (str "create " (:class-name expr) "." (:constructor expr)
                   "(" (str/join ", " (map format-expression (:args expr))) ")")
      :array-literal (str "[" (str/join ", " (map format-expression (:elements expr))) "]")
      :set-literal (str "#{" (str/join ", " (map format-expression (:elements expr))) "}")
      :map-literal (str "{" (str/join ", " (map (fn [{:keys [key value]}]
                                                  (str (format-expression key) " : "
                                                       (format-expression value)))
                                                (:entries expr))) "}")
      ;; Default: just return the expression as-is
      (str expr))
    :else (str expr)))

(defn format-statement
  "Format a statement at the given indentation level"
  [stmt level]
  (let [ind (indent level)]
    (cond
      (nil? stmt) ""
      (map? stmt)
      (case (:type stmt)
        :assign (str ind (:target stmt) " := " (format-expression (:value stmt)))
        :let (str ind "let " (:name stmt) " := " (format-expression (:value stmt)))
        :call (str ind (format-expression stmt))
        :if (str/join "\n"
                      [(str ind "if " (format-expression (:condition stmt)) " then")
                       (str/join "\n" (map #(format-statement % (inc level)) (:then stmt)))
                       (when (seq (:else stmt))
                         (str ind "else\n"
                              (str/join "\n" (map #(format-statement % (inc level)) (:else stmt)))))
                       (str ind "end")])
        :select (str/join "\n"
                          (concat
                           [(str ind "select")]
                           (mapcat (fn [{:keys [expr alias body]}]
                                     [(str (indent (inc level)) "when " (format-expression expr)
                                           (when alias (str " as " alias))
                                           " then")
                                      (str/join "\n" (map #(format-statement % (+ level 2)) body))])
                                   (:clauses stmt))
                           (when-let [timeout (:timeout stmt)]
                             [(str (indent (inc level)) "timeout " (format-expression (:duration timeout)) " then")
                              (str/join "\n" (map #(format-statement % (+ level 2)) (:body timeout)))])
                           (when (seq (:else stmt))
                             [(str ind "else")
                              (str/join "\n" (map #(format-statement % (inc level)) (:else stmt)))])
                           [(str ind "end")]))
        :loop (str/join "\n"
                        [(str ind "from")
                         (str/join "\n" (map #(format-statement % (inc level)) (:init stmt)))
                         (when (:invariant stmt)
                           (str (indent (inc level)) "invariant\n"
                                (str/join "\n" (map #(str (indent (+ level 2))
                                                          (:label %) ": " (format-expression (:expr %)))
                                                    (:invariant stmt)))))
                         (when (:variant stmt)
                           (str (indent (inc level)) "variant\n"
                                (str (indent (+ level 2)) (format-expression (:variant stmt)))))
                         (str (indent (inc level)) "until " (format-expression (:condition stmt)))
                         (str (indent (inc level)) "do")
                         (str/join "\n" (map #(format-statement % (+ level 2)) (:body stmt)))
                         (str ind "end")])
        :with (str/join "\n"
                        [(str ind "with \"" (:target stmt) "\" do")
                         (str/join "\n" (map #(format-statement % (inc level)) (:body stmt)))
                         (str ind "end")])
        :scoped-block (str/join "\n"
                                [(str ind "do")
                                 (str/join "\n" (map #(format-statement % (inc level)) (:body stmt)))
                                 (str ind "end")])
        :assert (let [assertions (:assertions stmt)
                      one-line (fn [{:keys [label condition]}]
                                 (str (when label (str label ": "))
                                      (format-expression condition)))]
                  ;; A single assertion stays on the `assert` line; several line
                  ;; up underneath it, the way `require` and `ensure` read.
                  (if (= 1 (count assertions))
                    (str ind "assert " (one-line (first assertions)))
                    (str/join "\n"
                              (cons (str ind "assert")
                                    (map #(str (indent (inc level)) (one-line %))
                                         assertions)))))
        ;; Default: return statement as-is
        (str ind stmt))
      :else (str ind stmt))))

;; ---------------------------------------------------------------------------
;; Formatting a file
;;
;; `nex format` only changes indentation (and strips trailing spaces). Every
;; token, comment and blank line stays as written, so formatting can never
;; change what a program means; format-code also checks that before it
;; returns.
;;
;; A line's indentation comes from the blocks around it: class sections,
;; routine clauses (require / do / ensure / rescue), loop and if bodies, and
;; the clauses of case, match and select. A line that continues a statement
;; (inside brackets, after a trailing operator, or starting with one) keeps
;; its offset from the statement's first line. A comment line moves with the
;; next line of code.
;; ---------------------------------------------------------------------------

(defn- word-start? [c] (or (Character/isLetter (char c)) (= \_ c)))
(defn- word-char? [c] (or (Character/isLetterOrDigit (char c)) (= \_ c)))

(defn- tokenize
  "The tokens of SRC as {:tokens [{:text :line :kind}] :string-lines #{}},
   :kind being :word, :string, :char, :comment, :punct or :other, and lines
   counted from 0. :string-lines holds the lines that end inside a string."
  [^String src]
  (let [n (count src)
        at (fn [i] (when (< i n) (.charAt src i)))]
    (loop [i 0 line 0 tokens (transient []) string-lines (transient #{})]
      (if (>= i n)
        {:tokens (persistent! tokens) :string-lines (persistent! string-lines)}
        (let [c (.charAt src i)
              tok (fn [kind end] (conj! tokens {:text (subs src i end) :line line :kind kind}))]
          (cond
            (= c \newline) (recur (inc i) (inc line) tokens string-lines)
            (Character/isWhitespace c) (recur (inc i) line tokens string-lines)

            (and (= c \-) (= (at (inc i)) \-))
            (let [end (or (str/index-of src "\n" i) n)]
              (recur end line (tok :comment end) string-lines))

            (or (= c \") (= c \'))
            (let [[end lines] (loop [j (inc i) lines []]
                                (let [d (at j)]
                                  (cond
                                    (nil? d) [n lines]
                                    (= d \\) (recur (+ j 2) (if (= (at (inc j)) \newline)
                                                              (conj lines (+ line (count lines)))
                                                              lines))
                                    (= d c) [(inc j) lines]
                                    (= d \newline) (recur (inc j) (conj lines (+ line (count lines))))
                                    :else (recur (inc j) lines))))]
              (recur end (+ line (count lines)) (tok :string end)
                     (reduce conj! string-lines lines)))

            (= c \#)
            (let [d (at (inc i))
                  end (cond
                        (= d \{) (+ i 2)
                        (and d (word-char? d)) (loop [j (+ i 2)]
                                                 (if (and (at j) (word-char? (at j))) (recur (inc j)) j))
                        (and d (not= d \newline)) (+ i 2)
                        :else (inc i))]
              (recur end line (tok (if (= d \{) :punct :char) end) string-lines))

            (word-start? c)
            (let [end (loop [j (inc i)] (if (and (at j) (word-char? (at j))) (recur (inc j)) j))]
              (recur end line (tok :word end) string-lines))

            (Character/isDigit c)
            (let [end (loop [j (inc i)]
                        (let [d (at j)]
                          (cond
                            (nil? d) j
                            (word-char? d) (recur (inc j))
                            (and (= d \.) (at (inc j)) (Character/isDigit (char (at (inc j))))) (recur (inc j))
                            :else j)))]
              (recur end line (tok :other end) string-lines))

            :else
            (let [two (when (< (inc i) n) (subs src i (+ i 2)))
                  end (if (#{":=" "/=" "<=" ">=" "->" "?."} two) (+ i 2) (inc i))]
              (recur end line (tok :punct end) string-lines))))))))

(def ^:private continuing-ends
  "A line ending with one of these continues on the next line."
  #{"+" "-" "*" "/" "%" "^" "=" "/=" "<" ">" "<=" ">=" ":=" "," "." "?." "->"
    ":" "and" "or" "not"})

(def ^:private continuing-starts
  "A line starting with one of these continues the line before."
  #{"and" "or" "." "?." "+" "*" "/" "%" "^" "=" "/=" "<" ">" "<=" ">=" ":="})

(def ^:private clause-words
  "For each kind of block, the words that start one of its clauses. A clause
   line sits at the block's clause level, and the lines after it one deeper."
  {:class #{"feature" "private" "create" "invariant"}
   :routine #{"require" "do" "ensure" "rescue" "deferred"}
   :do #{"ensure" "rescue"}
   :if #{"then" "elseif" "else"}
   :loop #{"invariant" "variant" "until" "do" "rescue"}
   :block-loop #{"do" "rescue"}
   :case #{"else"}
   :select #{"when" "timeout" "else"}})

(defn- open-frame
  "A block opened on a line indented AT columns. A block's clauses go at
   column :c, its lines at :body, and its `end` at :end."
  [kind at & {:as more}]
  (merge {:kind kind :c at :body (+ at indent-size) :end at} more))

(defn- indent-width
  [^String line]
  (loop [i 0 w 0]
    (if (< i (count line))
      (case (.charAt line i)
        \space (recur (inc i) (inc w))
        \tab (recur (inc i) (+ w indent-size))
        w)
      w)))

(defn- assertion-line?
  "Whether TOKS start like `label: expression`."
  [toks]
  (and (= :word (:kind (first toks))) (= ":" (:text (second toks)))))

(defn- code-indents
  "For LINES, a map from line number to the line's code tokens:
   {:indent {line new-indentation} :after {line indentation}}, :after being
   where a statement would go on the line after (none inside an
   expression)."
  [lines]
  (let [order (vec (sort (keys lines)))
        first-word (fn [i] (let [t (first (get lines i))] (when (= :word (:kind t)) (:text t))))]
    (loop [k 0
           stack [(open-frame :top 0 :body 0)]
           prev nil          ; last code token of the previous code line
           head 0            ; new indentation of the current statement's first line
           head-old 0        ; and its indentation before
           delta 0           ; how far that line moved
           pending? false    ; whether the previous statement may be a routine header
           prev-line nil     ; {:old :new :continues?} of the previous code line
           out {:indent {} :after {}}]
      (if (= k (count order))
        out
        (let [i (order k)
              toks (get lines i)
              old (:old (meta toks))
              t0 (first toks)
              w0 (when (= :word (:kind t0)) (:text t0))
              continues? (or (#{:bracket :when-expr} (:kind (peek stack)))
                             ;; a routine's `note "..."` and `alias "-"` belong
                             ;; to its header
                             (and pending? (#{"note" "alias"} w0)
                                  (= :string (:kind (second toks))))
                             (contains? continuing-ends (:text prev))
                             (contains? continuing-starts (:text t0)))
              ;; A lone `assert` takes the `label: expression` lines after it.
              stack (if (and (= :assert (:kind (peek stack))) (not continues?)
                             (not (assertion-line? toks)))
                      (pop stack)
                      stack)
              ;; A header followed by `require` or `do` on a line of its own
              ;; is a routine. Its clauses stay flush with the header, or one
              ;; level in, as the first one is written.
              stack (if (and (not continues?) pending?
                             (#{"require" "do" "deferred"} w0)
                             (not (:await-do (peek stack))))
                      (let [c (if (> old head-old) (+ head indent-size) head)]
                        (conj stack (open-frame :routine c :await-do true)))
                      stack)
              ;; A class's sections stay flush with `class`, or one level in,
              ;; as the first one is written.
              stack (let [top (peek stack)]
                      (if (and (not continues?) (= :class (:kind top)) (nil? (:c top))
                               (contains? (clause-words :class) w0))
                        (conj (pop stack)
                              (assoc top :c (if (> old (:old-at top))
                                              (+ (:end top) indent-size)
                                              (:end top))))
                        stack))
              top (peek stack)
              ;; `do` in a routine or loop body starts a block, unless the
              ;; routine or loop is still waiting for its own `do`.
              clause? (and (not continues?)
                           (contains? (clause-words (:kind top)) w0)
                           (or (not= "do" w0) (:await-do top)
                               (not (#{:routine :loop :block-loop} (:kind top)))))
              case-level (when (= :case (:kind top))
                           (if (or (:stmt? top) (:else-block? top)) (+ (:c top) indent-size) (:c top)))
              indent (cond
                       ;; A continuation keeps its offset from the statement's first
                       ;; line. A line level with the one above, when that one
                       ;; continues the statement or ends a list item, lines up
                       ;; with it; otherwise a continuation goes deeper than the
                       ;; statement's first line unless it closes something.
                       continues? (let [w (max 0 (+ old delta))]
                                    (cond
                                      (#{")" "]" "}" "else" "elseif" "end" "then"} (:text t0)) w
                                      (and (= old (:old prev-line))
                                           (or (:continues? prev-line) (= "," (:text prev))))
                                      (:new prev-line)
                                      (<= w head) (+ head indent-size)
                                      :else w))
                       (= "end" w0) (:end top)
                       clause? (:c top)
                       (and (= :class (:kind top)) (= "note" w0)) (+ (:end top) indent-size)
                       ;; `inherit` stays flush with `class` or one level in, as written.
                       (and (= :class (:kind top)) (= "inherit" w0))
                       (if (> old (:old-at top)) (+ (:end top) indent-size) (:end top))
                       case-level case-level
                       :else (:body top))
              clause-line? (and (not continues?) (= :case (:kind top))
                                (= indent (:c top)) (not= "else" w0))
              stack (cond-> stack
                      ;; The parents listed under `inherit` go one level deeper.
                      (and (not continues?) (= :class (:kind top)) (= "inherit" w0))
                      (-> pop (conj (assoc top :body (+ indent indent-size))))
                      ;; A clause sets the level of the lines after it.
                      (and clause? (not= :case (:kind top)))
                      (-> pop (conj (assoc top :body (+ (:c top) indent-size))))
                      ;; A case or match clause's statement ends at the line's end.
                      (= :case (:kind top))
                      (-> pop (conj (dissoc top :stmt?))))
              head (if continues? head indent)
              head-old (if continues? head-old old)
              delta (if continues? delta (- indent old))
              n (count toks)
              ;; Blocks opened on a statement's first line, or outside any
              ;; bracket, line up with that line; one opened inside brackets
              ;; on a later line lines up with its own line.
              anchor (fn [stack] (if (= :bracket (:kind (peek stack))) indent head))
              stack (loop [j 0 stack stack seen-then? false]
                      (if (= j n)
                        stack
                        (let [{:keys [text kind]} (toks j)
                              after-dot? (and (pos? j) (#{"." "?."} (:text (toks (dec j)))))
                              last? (= j (dec n))
                              top (peek stack)
                              at (anchor stack)
                              word (when (and (= :word kind) (not after-dot?)) text)]
                          (recur
                           (inc j)
                           (cond
                             (#{"(" "[" "{" "#{"} text) (conj stack {:kind :bracket})
                             (#{")" "]" "}"} text)
                             (let [s (loop [s stack]
                                       (if (and (> (count s) 1) (not= :bracket (:kind (peek s))))
                                         (recur (pop s))
                                         s))]
                               (if (> (count s) 1) (pop s) s))
                             (nil? word) stack
                             (= "class" word) (conj stack (open-frame :class at :c nil :old-at head-old))
                             (and (= "union" word) (#{"union" "enum"} w0))
                             (conj stack (open-frame :union at :c (+ at indent-size)))
                             (= "if" word) (if (and clause-line? (not seen-then?))
                                             stack ; a match clause's guard
                                             (conj stack (open-frame :if at)))
                             (and (= "from" word) (not= "import" w0))
                             (conj stack (open-frame :loop at :await-do true))
                             (#{"repeat" "across" "with"} word)
                             (conj stack (open-frame :block-loop at :await-do true))
                             (#{"case" "match"} word)
                             (conj stack (open-frame :case at :c (+ at indent-size)))
                             (= "select" word)
                             (conj stack (open-frame :select at :c (+ at indent-size) :body (+ at (* 2 indent-size))))
                             ;; `when` starting a line of a select is a clause;
                             ;; anywhere else it starts a `when ... end` expression
                             (= "when" word) (if (and (= :select (:kind top)) (zero? j))
                                               stack
                                               (conj stack {:kind :when-expr}))
                             (= "do" word) (if (:await-do top)
                                             (-> stack pop (conj (assoc top :await-do false
                                                                        :body (+ (:c top) indent-size))))
                                             (conj stack (open-frame :do at)))
                             (and (= "then" word) (= :case (:kind top)))
                             (if last? (-> stack pop (conj (assoc top :stmt? true))) stack)
                             (and (= "else" word) (= :case (:kind top)) last?)
                             (-> stack pop (conj (assoc top :else-block? true)))
                             (= "end" word) (if (> (count stack) 1) (pop stack) stack)
                             (and (= "deferred" word) (zero? j) (= :routine (:kind top))
                                  (not= "ensure" (some-> (get order (inc k)) first-word)))
                             (pop stack) ; a deferred routine without `ensure` has no `end`
                             (and (= "assert" word) (zero? j) (= 1 n))
                             (conj stack {:kind :assert :c indent :body (+ indent indent-size) :end indent})
                             :else stack)
                           (or seen-then? (= "then" word))))))
              words (keep #(when (= :word (:kind %)) (:text %)) toks)
              no-do-after-fn? (when-let [fn-at (first (keep-indexed #(when (= "fn" %2) %1) words))]
                                (not (some #{"do"} (drop fn-at words))))
              pending? (if continues?
                         (and pending? (not (some #{"do"} words)))
                         (boolean
                          (or (and (= "function" w0) (not (some #{"do"} words)))
                              ;; a member, maybe after its section's word
                              ;; (`create make(x: Integer)`)
                              (and (= :class (:kind (last (remove #(= :bracket (:kind %)) stack))))
                                   (= :word (:kind t0))
                                   (not (some #{"class"} words))
                                   (some #(not (contains? (clause-words :class) %)) words)
                                   (not (#{"note" "inherit"} w0)))
                              no-do-after-fn?)))]
          (recur (inc k) stack (peek toks) head head-old delta pending?
                 {:old old :new indent :continues? continues?}
                 (let [top (peek stack)
                       after (case (:kind top)
                               (:bracket :when-expr :assert) nil
                               :case (if (or (:stmt? top) (:else-block? top))
                                       (+ (:c top) indent-size)
                                       (:c top))
                               (:body top))]
                   (cond-> (assoc-in out [:indent i] indent)
                     after (assoc-in [:after i] after)))))))))

(defn- reindent
  "SRC with each line indented by the blocks around it."
  [^String src]
  (let [crlf? (str/includes? src "\r\n")
        src (if crlf? (str/replace src "\r\n" "\n") src)
        shebang (re-find #"^#![^\n]*" src)
        ;; Blank out the `#!` line so its text isn't read as code.
        {:keys [tokens string-lines]} (tokenize (if shebang
                                                  (str (str/join (repeat (count shebang) " "))
                                                       (subs src (count shebang)))
                                                  src))
        text-lines (str/split src #"\n" -1)
        starts-in-string (set (map inc string-lines))
        ;; A line that starts inside a string belongs to the line where the
        ;; string started.
        logical (fn [i] (loop [i i] (if (starts-in-string i) (recur (dec i)) i)))
        by-line (group-by (comp logical :line) tokens)
        code (into {}
                   (for [[i toks] by-line
                         :let [code-toks (vec (remove #(= :comment (:kind %)) toks))]
                         :when (seq code-toks)]
                     [i (with-meta code-toks {:old (indent-width (text-lines i))})]))
        {indents :indent after :after} (code-indents code)
        code-lines (vec (sort (keys indents)))
        ;; A comment line moves by as much as the next line of code does,
        ;; unless it is deeper than that line: then it ends the block above
        ;; and goes where a statement there would.
        delta-of (fn [j] (- (indents j) (indent-width (text-lines j))))
        comment-indent (fn [i]
                         (let [old (indent-width (text-lines i))
                               next-code (first (filter #(> % i) code-lines))
                               prev-code (last (filter #(< % i) code-lines))
                               next-old (some-> next-code text-lines indent-width)]
                           (cond
                             (and next-code (<= old next-old))
                             (max 0 (+ old (- (indents next-code) next-old)))
                             ;; far to the right: a note lined up with end-of-line
                             ;; comments above, so it moves with the line above
                             (and prev-code (> old (+ (indent-width (text-lines prev-code))
                                                      (* 2 indent-size))))
                             (max 0 (+ old (delta-of prev-code)))
                             (and prev-code (after prev-code)) (after prev-code)
                             next-code (max 0 (+ old (- (indents next-code) next-old)))
                             prev-code (max 0 (+ old (delta-of prev-code)))
                             :else old)))
        lines (map-indexed
               (fn [i ^String line]
                 (let [keep-end? (string-lines i)
                       body (str/triml line)
                       body (if keep-end? body (str/trimr body))]
                   (cond
                     (starts-in-string i) (if keep-end? line (str/trimr line))
                     (and shebang (zero? i)) line
                     (str/blank? body) ""
                     :else (let [width (or (indents i) (comment-indent i))]
                             (str (str/join (repeat width " ")) body)))))
               text-lines)
        out (str/join "\n" lines)]
    (if crlf? (str/replace out "\n" "\r\n") out)))

(defn- without-positions
  "AST without source positions, and with the names the parser makes up
   (`__across_c_1__`, `AnonymousFunction_2`) unnumbered, since each parse
   numbers them anew."
  [ast]
  (walk/postwalk #(cond
                    (map? %) (dissoc % :dbg/line :dbg/col)
                    (string? %) (-> %
                                    (str/replace #"^(__.*_)\d+__$" "$1__")
                                    (str/replace #"^AnonymousFunction_\d+$" "AnonymousFunction_"))
                    :else %)
                 ast))

(defn format-code
  "CODE with its indentation fixed. Throws when CODE does not parse, or (a
   bug in the formatter) when the result would mean something else."
  [code]
<<<<<<< Updated upstream
  (try
    (let [ast (p/ast code)
          formatted (format-program ast)]
      ;; The parser skips a `#!` line; put it back.
      (if-let [shebang (p/shebang-line code)]
        (str shebang "\n" formatted)
        formatted))
    (catch Exception e
      (throw (ex-info "Failed to format code"
                      {:error (.getMessage e)
                       :cause e})))))
=======
  (let [before (try
                 (p/ast code)
                 (catch Exception e
                   (throw (ex-info "Failed to format code" {:error (.getMessage e) :cause e}))))
        formatted (reindent code)]
    (when-not (= (without-positions before)
                 (without-positions (try (p/ast formatted) (catch Exception _ nil))))
      (throw (ex-info "Failed to format code: the result would change the program, so the file was left as it is"
                      {:error "formatting would change the program"})))
    formatted))
>>>>>>> Stashed changes

(defn format-file
  "The formatted text of the Nex file at FILE-PATH."
  [file-path]
  (format-code (slurp file-path)))

(defn format-file-in-place
  "Format the Nex file at FILE-PATH, writing it back only if it changes."
  [file-path]
  (let [original (slurp file-path)
        formatted (format-code original)]
    (when (not= original formatted)
      (spit file-path formatted))
    (println "Formatted:" file-path)))

(defn -main
  "Main entry point for the formatter CLI"
  [& args]
  (if (empty? args)
    (do
      (println "Nex Code Formatter")
      (println "Usage: clojure -M -m nex.fmt <file.nex> [<file2.nex> ...]")
      (println "       clojure -M -m nex.fmt --check <file.nex>  (check formatting only)")
      (System/exit 1))
    (let [check-only? (= "--check" (first args))
          files (if check-only? (rest args) args)]
      (doseq [file files]
        (try
          (if check-only?
            (let [original (slurp file)
                  formatted (format-file file)]
              (if (= original formatted)
                (println "✓" file "is properly formatted")
                (do
                  (println "✗" file "needs formatting")
                  (System/exit 1))))
            (format-file-in-place file))
          (catch Exception e
            (println "Error formatting" file ":" (.getMessage e))
            (System/exit 1)))))))
