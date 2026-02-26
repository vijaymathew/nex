(ns nex.repl
  "Interactive REPL for the Nex programming language"
  (:require [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [clojure.string :as str]
            [clojure.java.io :as io])
  (:import [org.jline.reader LineReaderBuilder LineReader LineReader$Option Completer Candidate]
           [org.jline.reader.impl DefaultParser]
           [org.jline.terminal TerminalBuilder]
           [org.jline.reader.impl.history DefaultHistory]
           [java.io EOFException]
           [clj_antlr ParseError])
  (:gen-class))

;;
;; REPL State
;;

(defonce ^:dynamic *repl-context* nil)
(defonce ^:dynamic *type-checking-enabled* (atom false))
(defonce ^:dynamic *repl-var-types* (atom {}))

(def nex-keywords
  ["class" "feature" "inherit" "end" "do" "if" "then" "else" "elseif"
   "when" "from" "until" "invariant" "variant" "require" "ensure"
   "let" "create" "fn" "function" "and" "or" "old" "this" "note"
   "with" "import" "intern" "private" "raise" "rescue" "retry" "case" "of"
   "true" "false" "nil"
   ;; strictly 'result' is not a keyword, but a pre-defined variable name.
   "result"])

(def nex-types
  ["Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
   "Array" "Map" "Function" "Console" "File" "Process" "Window" "Turtle"])

(def nex-builtins ["print" "println"])

(def nex-repl-commands
  [":help" ":quit" ":exit" ":clear" ":reset" ":classes" ":vars"
   ":typecheck" ":load"])

(defonce ^:dynamic *completer-ctx* (atom nil))

(defn make-nex-parser
  "Create a DefaultParser that also splits words at parentheses and commas,
  so that tab-completion works inside function-call arguments."
  []
  (proxy [DefaultParser] []
    (isDelimiterChar [^CharSequence buffer ^long pos]
      (let [ch (.charAt buffer (int pos))]
        (or (Character/isWhitespace ch)
            (= ch \() (= ch \)) (= ch \,))))))

(defn make-nex-completer
  "Create a JLine Completer that provides completions from static word
  lists and the dynamic REPL context stored in *completer-ctx*."
  []
  (reify Completer
    (complete [_ _reader line candidates]
      (let [word (.word line)]
        (when (seq word)
          (let [ctx        @*completer-ctx*
                var-names  (when ctx
                             (map name (keys @(:bindings (:globals ctx)))))
                cls-names  (when ctx
                             (->> (keys @(:classes ctx))
                                  (remove #(or (= % "__ReplTemp__")
                                               (.startsWith ^String % "AnonymousFunction_")
                                               (= % "Function")))
                                  (map name)))
                all-words  (concat nex-keywords nex-types nex-builtins
                                   nex-repl-commands
                                   var-names cls-names)]
            (doseq [w all-words]
              (when (.startsWith ^String w word)
                (.add candidates (Candidate. w))))))))))

(defn init-repl-context
  "Initialize or reset the REPL context"
  []
  (interp/make-context))

;;
;; Input Reading
;;

(defn create-line-reader
  "Create a JLine LineReader with history support"
  []
  (let [terminal (-> (TerminalBuilder/builder)
                     (.system true)
                     (.build))
        history-file (io/file (System/getProperty "user.home") ".nex_history")]
    (-> (LineReaderBuilder/builder)
        (.terminal terminal)
        (.parser (make-nex-parser))
        (.variable LineReader/HISTORY_FILE (.getAbsolutePath history-file))
        (.option LineReader$Option/DISABLE_EVENT_EXPANSION true)
        (.completer (make-nex-completer))
        (.build))))

(defonce ^:dynamic *line-reader* nil)

(defn read-line-safe
  "Read a line using JLine, returning nil on EOF"
  [prompt-text]
  (try
    (when-not *line-reader*
      (throw (IllegalStateException. "Line reader not initialized")))
    (.readLine *line-reader* prompt-text)
    (catch EOFException _
      nil)
    (catch org.jline.reader.UserInterruptException _
      ;; Ctrl+C - return empty string to continue
      "")
    (catch org.jline.reader.EndOfFileException _
      ;; Ctrl+D - return nil to exit
      nil)
    (catch Exception e
      (println "Error reading input:" (.getMessage e))
      nil)))

(defn continue-reading?
  "Check if we need to continue reading (unclosed block)"
  [lines]
  (let [text (str/join "\n" lines)
        ;; Count keyword pairs that need to be closed
        class-count (count (re-seq #"\bclass\b" text))
        feature-count (count (re-seq #"\bfeature\b" text))
        do-count (count (re-seq #"\bdo\b" text))
        from-count (count (re-seq #"\bfrom\b" text))
        if-count (count (re-seq #"\bif\b" text))
        case-count (count (re-seq #"\bcase\b" text))
        end-count (count (re-seq #"\bend\b" text))
        ;; In loops, 'do' is part of 'from...until...do...end', not a separate block
        ;; So subtract 'from-count' from 'do-count' to avoid double-counting
        standalone-do-count (max 0 (- do-count from-count))
        ;; Total blocks that need closing
        open-blocks (+ class-count standalone-do-count from-count if-count case-count)]
    ;; Continue if we have more opens than closes
    (> open-blocks end-count)))

(defn read-input
  "Read potentially multi-line input from the user"
  []
  (when-let [first-line (read-line-safe "nex> ")]
    (if (str/starts-with? first-line ":")
      ;; REPL command - single line
      first-line
      ;; Code input - may be multi-line
      (loop [lines [first-line]]
        (if (continue-reading? lines)
          (if-let [next-line (read-line-safe "...  ")]
            (recur (conj lines next-line))
            ;; EOF during multi-line - return what we have
            (str/join "\n" lines))
          (str/join "\n" lines))))))

;;
;; REPL Commands
;;

(defn show-help []
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║                    NEX REPL HELP                           ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println)
  (println "REPL Commands:")
  (println "  :help, :h, :?     - Show this help message")
  (println "  :quit, :q, :exit  - Exit the REPL (or press Ctrl+D)")
  (println "  :clear, :reset    - Clear all definitions and reset context")
  (println "  :classes          - List all defined classes")
  (println "  :vars             - List all defined variables")
  (println "  :typecheck on     - Enable type checking (validates code before execution)")
  (println "  :typecheck off    - Disable type checking (default)")
  (println "  :typecheck status - Show current type checking status")
  (println "  :load <path>      - Load and evaluate a .nex file")
  (println)
  (println "Navigation:")
  (println "  Up/Down arrows    - Navigate command history")
  (println "  Ctrl+R            - Search command history")
  (println "  Ctrl+C            - Cancel current input")
  (println "  Ctrl+D            - Exit the REPL")
  (println)
  (println "Note: Command history is saved to ~/.nex_history")
  (println)
  (println "Language Features:")
  (println "  • Define classes with 'class ClassName ... end'")
  (println "  • Create objects and call methods")
  (println "  • Use Design by Contract (require, ensure, invariant)")
  (println "  • Loops with contracts (from...until...do...end)")
  (println "  • Local variables with 'let'")
  (println "  • Built-in functions: print(), println()")
  (println)
  (println "Examples:")
  (println "  42                    # Expressions are automatically displayed")
  (println "  1 + 2                 # Arithmetic")
  (println "  \"hello\"               # Strings")
  (println "  1 < 2                 # Comparisons (shows true/false)")
  (println "  let x := 10           # Define variables")
  (println "  x + 5                 # Use variables in expressions")
  (println "  print(x)              # Print variable values")
  (println "  :vars                 # List all variables and their values")
  (println)
  (println "Multi-line input:")
  (println "  Start a class definition and press Enter")
  (println "  Continue typing on the '...  ' prompt")
  (println "  The REPL automatically detects when input is complete")
  (println))

(defn show-classes [ctx]
  (let [classes @(:classes ctx)]
    (if (empty? classes)
      (println "No classes defined.")
      (do
        (println "Defined classes:")
        (doseq [[class-name class-def] classes]
          (let [parents (:parents class-def)
                parent-names (when (seq parents)
                              (str " (inherits: "
                                   (str/join ", " (map :parent parents))
                                   ")"))]
            (println (str "  • " class-name parent-names))))))))

(defn show-vars [ctx]
  (let [bindings @(:bindings (:globals ctx))]
    (if (empty? bindings)
      (println "No variables defined.")
      (do
        (println "Defined variables:")
        (doseq [[var-name value] bindings]
          (let [value-str (if (instance? nex.interpreter.NexObject value)
                           (str "#<" (:class-name value) " object>")
                           (pr-str value))]
            (println (str "  • " var-name " = " value-str))))))))

(declare eval-code)

(defn normalize-load-path [raw]
  (let [trimmed (str/trim raw)]
    (if (and (>= (count trimmed) 2)
             (or (and (.startsWith trimmed "\"") (.endsWith trimmed "\""))
                 (and (.startsWith trimmed "'") (.endsWith trimmed "'"))))
      (subs trimmed 1 (dec (count trimmed)))
      trimmed)))

(defn load-file-into-repl
  "Load and evaluate a .nex file, returning updated context."
  [ctx path]
  (let [path (normalize-load-path path)]
    (cond
      (str/blank? path)
      (do
        (println "Usage: :load <path-to-file.nex>")
        ctx)

      :else
      (let [;; Resolve relative paths against the user's original working directory
            ;; (NEX_USER_DIR is set by the nex shell script before cd-ing to NEX_HOME)
            file (let [f (io/file path)]
                   (if (.isAbsolute f)
                     f
                     (if-let [user-dir (System/getProperty "nex.user.dir")]
                       (io/file user-dir path)
                       f)))]
        (if-not (.exists file)
          (do
            (println (str "File not found: " (.getPath file)))
            ctx)
          (do
            (when-not (.endsWith (.getName file) ".nex")
              (println "Warning: expected a .nex file"))
            (eval-code ctx (slurp file))))))))

(defn handle-command [ctx input]
  (let [input-lower (str/lower-case input)]
    (cond
      ;; Help command
      (contains? #{":help" ":h" ":?"} input-lower)
      (do (show-help) ctx)

      ;; Quit command
      (contains? #{":quit" ":q" ":exit"} input-lower)
      :quit

      ;; Clear command
      (contains? #{":clear" ":reset"} input-lower)
      (do
        (println "Context cleared.")
        (reset! *repl-var-types* {})
        (init-repl-context))

      ;; Classes command
      (= input-lower ":classes")
      (do (show-classes ctx) ctx)

      ;; Vars command
      (= input-lower ":vars")
      (do (show-vars ctx) ctx)

      ;; Typecheck commands
      (= input-lower ":typecheck on")
      (do
        (reset! *type-checking-enabled* true)
        (println "Type checking enabled. Code will be validated before execution.")
        ctx)

      (= input-lower ":typecheck off")
      (do
        (reset! *type-checking-enabled* false)
        (println "Type checking disabled.")
        ctx)

      (or (= input-lower ":typecheck") (= input-lower ":typecheck status"))
      (do
        (println (str "Type checking is currently: "
                     (if @*type-checking-enabled* "ENABLED" "DISABLED")))
        ctx)

      ;; Load file command
      (str/starts-with? input-lower ":load")
      (let [path (subs input (min (count input) (count ":load")))]
        (load-file-into-repl ctx path))

      ;; Unknown command
      :else
      (do
        (println (str "Unknown command: " input))
        (println "Type :help for available commands")
        ctx))))

;;
;; Code Evaluation
;;

(defn- simplify-expected
  "Simplify ANTLR expected token sets into readable descriptions."
  [expected-str]
  (if-let [[_ tokens] (re-matches #"\{(.+)\}" expected-str)]
    (let [items (set (map str/trim (str/split tokens #",")))
          ;; Detect categories based on which tokens are in the expected set
          has-identifier (items "IDENTIFIER")
          has-literals (or (items "INTEGER") (items "REAL") (items "STRING"))
          has-class (items "'class'")
          has-function (or (items "'function'") (items "'fn'"))
          has-end (items "'end'")
          has-eof (items "<EOF>")
          has-create (items "'create'")
          ;; An "expression" context has identifiers + literals but not class/function at top level
          expression-context? (and has-identifier has-literals (not has-eof))]
      (cond
        ;; Inside a body expecting an expression (e.g., after :=, in argument list)
        expression-context?
        "an expression"

        ;; After if/elseif condition, expecting 'then'
        (items "'then'")
        "'then'"

        ;; After 'do' keyword, expecting block body or 'end'
        (and has-end (not has-eof) (not has-class))
        "'end'"

        ;; Top-level: class, function, EOF
        :else
        (let [readable (cond-> []
                         has-class (conj "class declaration")
                         has-function (conj "function declaration")
                         has-end (conj "'end'")
                         has-eof (conj "end of input"))]
          (if (seq readable)
            (str/join ", " readable)
            expected-str))))
    expected-str))

(defn format-parse-errors
  "Format parse errors from clj-antlr ParseError with source context and caret pointers."
  [^ParseError e source-code line-offset]
  (let [source-lines (str/split-lines source-code)
        num-lines (count source-lines)
        errors (.-errors e)
        ;; Adjust line numbers and filter out errors on wrapper lines
        adjusted (keep (fn [err]
                         (let [line (- (:line err) line-offset)]
                           (when (and (pos? line) (<= line num-lines))
                             (assoc err :adjusted-line line))))
                       errors)
        ;; Deduplicate by line to show only the first error per line
        seen-lines (atom #{})
        unique-errors (filter (fn [err]
                                (let [l (:adjusted-line err)]
                                  (when-not (@seen-lines l)
                                    (swap! seen-lines conj l)
                                    true)))
                              adjusted)
        ;; Limit to first 3 errors
        limited-errors (take 3 unique-errors)]
    (if (empty? limited-errors)
      ;; All errors were on wrapper lines — show a generic message pointing to the end
      (let [last-line (last source-lines)
            last-num num-lines]
        (println (str "  Line " last-num ": unexpected end of input"))
        (when last-line
          (println (str "  | " last-line))
          (println (str "  | " (apply str (repeat (count last-line) " ")) "^"))))
      ;; Show each error with source context
      (doseq [err limited-errors]
        (let [line (:adjusted-line err)
              col (:char err)
              msg (:message err)
              ;; Make the message more user-friendly
              friendly-msg (-> msg
                               (str/replace #"mismatched input '(.+?)' expecting (.+)"
                                            (fn [[_ token expected]]
                                              (str "unexpected '" token "', expected " (simplify-expected expected))))
                               (str/replace #"extraneous input '(.+?)' expecting (.+)"
                                            (fn [[_ token expected]]
                                              (str "unexpected '" token "', expected " (simplify-expected expected))))
                               (str/replace #"missing '(.+?)' at '(.+?)'"
                                            "missing '$1' before '$2'"))]
          (println (str "  Line " line ": " friendly-msg))
          ;; Show the source line and caret
          (let [src-line (nth source-lines (dec line))]
            (println (str "  | " src-line))
            (when (and col (>= col 0))
              (println (str "  | " (apply str (repeat col " ")) "^")))))))))

(defn wrap-as-method
  "Wrap code in a temporary class and method structure for parsing"
  [code]
  ;; For expressions, we need to use them as the result value
  (str "class __ReplTemp__\n"
       "  feature\n"
       "    __eval__() do\n"
       "      " code "\n"
       "    end\n"
       "end"))

(defn wrap-expression
  "Wrap an expression so it can be evaluated and its result returned"
  [expr]
  ;; Directly wrap in print() - this handles both expressions and identifiers
  (str "class __ReplTemp__\n"
       "  feature\n"
       "    __eval__() do\n"
       "      print(" expr ")\n"
       "    end\n"
       "end"))

(def format-value interp/nex-format-value)

(defn format-type
  "Format a type value for REPL display"
  [type-val]
  (cond
    (nil? type-val) nil
    (string? type-val) type-val
    (map? type-val) (let [base (:base-type type-val)
                          params (or (:type-params type-val) (:type-args type-val))]
                     (if (seq params)
                       (str base "[" (str/join ", " (map format-type params)) "]")
                       base))
    :else (str type-val)))

(defn infer-result-type
  "Infer and format the type of an expression in the REPL context.
   Returns a formatted type string, or nil if inference fails."
  [ctx expr-node]
  (when (and @*type-checking-enabled* expr-node)
    (when-let [t (tc/infer-expression-type
                   expr-node
                   {:classes (vals @(:classes ctx))
                    :imports @(:imports ctx)
                    :var-types @*repl-var-types*})]
      (format-type t))))

(defn looks-like-class?
  "Check if input looks like a class or function definition"
  [input]
  (or (re-find #"^\s*class\s+" input)
      (re-find #"^\s*function\s+" input)))

(defn looks-like-statement?
  "Check if input needs to be wrapped in a method"
  [input]
  (or (re-find #"^\s*let\s+" input)
      (re-find #"^\s*if\s+" input)
      (re-find #"^\s*from\s+" input)
      (re-find #"^\s*do\s+" input)))

(defn looks-like-identifier?
  "Check if input is a simple identifier (variable name)"
  [input]
  (re-matches #"^\s*[a-zA-Z_][a-zA-Z0-9_]*\s*$" input))

(defn looks-like-expression?
  "Check if input looks like a simple expression (not a statement)"
  [input]
  (not (or (looks-like-statement? input)
           (looks-like-class? input)
           (re-find #"^\s*print\(" input)
           (re-find #"^\s*create\s+" input))))

(defn eval-code [ctx input]
  (try
    ;; Clear output from previous evaluation
    (reset! (:output ctx) [])

    ;; Determine if we need to wrap the input
    (let [code-to-parse (cond
                          ;; Class definition - parse as is
                          (looks-like-class? input)
                          input

                          ;; Statement that needs wrapping
                          (looks-like-statement? input)
                          (wrap-as-method input)

                          ;; Try as a method call first, if it fails, wrap it
                          :else
                          input)

          ;; Try to parse, track if we wrapped
          [ast was-wrapped? is-expression?] (try
                                              ;; Bare identifiers parse as methodCall but should
                                              ;; evaluate as expressions (return their value)
                                              (if (and (= code-to-parse input)
                                                       (looks-like-identifier? input))
                                                [(p/ast (wrap-expression input)) true true]
                                                [(p/ast code-to-parse) (not= code-to-parse input) false])
                                              (catch Exception e
                                                ;; If parsing failed and we haven't wrapped yet, try wrapping
                                                (if (= code-to-parse input)
                                                  (try
                                                    [(p/ast (wrap-expression input)) true true]
                                                    (catch Exception _e2
                                                      ;; If expression wrapping fails, try as statement
                                                      (try
                                                        [(p/ast (wrap-as-method input)) true false]
                                                        (catch Exception _e3
                                                          ;; All attempts failed - throw the ORIGINAL error
                                                          ;; so line numbers reference the user's actual code
                                                          (throw e)))))
                                                  (throw e))))]
      ;; Type check if enabled
      (when (and @*type-checking-enabled*
                 (= (:type ast) :program)
                 (or (seq (:classes ast)) (seq (:calls ast))))
        ;; Create an augmented AST that includes previously defined classes
        ;; so the type checker knows about them
        (let [prev-classes (remove #(or (= "__ReplTemp__" (:name %))
                                       (.startsWith (:name %) "AnonymousFunction_"))
                                  (vals @(:classes ctx)))
              prev-imports @(:imports ctx)
              augmented-ast (cond
                              (and (seq prev-classes) (seq prev-imports))
                              (assoc ast
                                     :classes (concat prev-classes (:classes ast))
                                     :imports (concat prev-imports (:imports ast)))

                              (seq prev-classes)
                              (assoc ast :classes (concat prev-classes (:classes ast)))

                              (seq prev-imports)
                              (assoc ast :imports (concat prev-imports (:imports ast)))

                              :else
                              ast)
              result (tc/type-check augmented-ast {:var-types @*repl-var-types*})]
          (when-not (:success result)
            (doseq [error (:errors result)]
              (println (tc/format-type-error error)))
            (throw (ex-info "Type checking failed" {:errors (:errors result)})))))

      ;; Evaluate based on type
      (cond
        ;; If we wrapped the code, execute the temp method in GLOBAL context
        was-wrapped?
        (let [class-def (first (:classes ast))
              method-def (-> class-def :body first :members first)
              ;; Execute directly in the current context (preserves global vars)
              result (last (map #(interp/eval-node ctx %) (:body method-def)))
              output @(:output ctx)]
          ;; Persist variable types from let statements (for future type checking)
          (when @*type-checking-enabled*
            (doseq [stmt (:body method-def)]
              (when (and (map? stmt) (= (:type stmt) :let) (:var-type stmt))
                (swap! *repl-var-types* assoc (:name stmt) (:var-type stmt)))))
          ;; Infer type of the result expression when typechecking is on
          (let [type-str (when is-expression?
                           (infer-result-type ctx (-> method-def :body first :args first)))]
            ;; Show output from print statements
            (when (seq output)
              (if type-str
                (println (str type-str " " (first output)))
                (doseq [line output]
                  (println line))))
            ;; Show result if it's not nil and not from a print
            ;; Always show false/0 results too.
            (when (and (some? result) (empty? output))
              (if type-str
                (println (str type-str " " (format-value result)))
                (println (format-value result)))))
          ctx)

        ;; If it's a program, handle it based on content
        (= (:type ast) :program)
        (let [classes (:classes ast)
              functions (:functions ast)
              calls (:calls ast)
              real-class-names (filter #(not= % "__ReplTemp__")
                                      (map :name (filter map? classes)))
              function-names (map :name (filter map? functions))]
          ;; If there are classes or functions, evaluate the program to register them
          (when (or (seq real-class-names) (seq function-names))
            (interp/eval-node ctx ast)
            (when @*type-checking-enabled*
              (doseq [fn-def (filter map? functions)]
                (swap! *repl-var-types* assoc (:name fn-def) (:class-name fn-def)))))
          ;; If there are calls/expressions and no classes, evaluate them to get result
          (let [result (when (and (seq calls) (empty? real-class-names) (empty? function-names))
                        (last (map #(interp/eval-node ctx %) calls)))
                output @(:output ctx)]
            ;; Show any output
            (when (seq output)
              (doseq [line output]
                (println line)))
            ;; Show result if it's not nil, not from a print, and no classes were defined
            ;; Always show false/0 results too.
            (when (and (some? result) (empty? output) (empty? real-class-names) (empty? function-names))
              (if-let [type-str (when (seq calls)
                                  (infer-result-type ctx (last calls)))]
                (println (str type-str " " (format-value result)))
                (println (format-value result)))))
          ctx)

        ;; Single expression or statement
        :else
        (let [result (interp/eval-node ctx ast)
              output @(:output ctx)]
          ;; Show output from print statements
          (when (seq output)
            (doseq [line output]
              (println line)))
          ;; Show result if it's not nil and not from a print
          ;; Always show false/0 results too.
          (when (and (some? result) (empty? output))
            (if-let [type-str (infer-result-type ctx ast)]
              (println (str type-str " " (format-value result)))
              (println (format-value result))))
          ctx)))

    (catch ParseError e
      (println "Syntax error:")
      ;; When input was pre-wrapped as a statement, errors reference the wrapper
      ;; code (offset by 3 lines). Adjust accordingly.
      (let [pre-wrapped? (looks-like-statement? input)
            line-offset (if pre-wrapped? 3 0)]
        (format-parse-errors e input line-offset))
      ctx)

    (catch clojure.lang.ExceptionInfo e
      (println "Error:" (.getMessage e))
      (when-let [data (ex-data e)]
        (when (contains? data :line)
          (println "  at line" (:line data))))
      ctx)

    (catch Exception e
      (println "Error:" (.getMessage e))
      ctx)))

;;
;; Main REPL Loop
;;

(defn show-banner []
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║                   NEX REPL v0.1.0                          ║")
  (println "║     A high-level language for design and implementation    ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println)
  (println "Type :help for help, :quit to exit")
  (println))

(defn repl-loop
  "Main REPL loop"
  []
  (let [ctx (init-repl-context)]
    (reset! *completer-ctx* ctx)
    (loop [ctx ctx]
      (if-let [input (read-input)]
        ;; Got input (could be empty line, command, or code)
        (let [trimmed (str/trim input)]
          (if (str/blank? trimmed)
            ;; Empty line - just continue the loop
            (recur ctx)
            ;; Non-empty input - process it
            (let [new-ctx (if (str/starts-with? trimmed ":")
                           (handle-command ctx trimmed)
                           (eval-code ctx trimmed))]
              (if (= new-ctx :quit)
                ;; Exit requested via :quit command
                nil
                ;; Continue with new context
                (do (reset! *completer-ctx* new-ctx)
                    (recur new-ctx))))))
        ;; Got nil (EOF/Ctrl+D) - exit gracefully
        nil))))

(defn start-repl
  "Start the Nex REPL"
  []
  (show-banner)
  (binding [*line-reader* (create-line-reader)]
    (repl-loop)
    (println "\nGoodbye!")))

(defn -main
  "Entry point for standalone REPL"
  [& args]
  (start-repl)
  (System/exit 0))
