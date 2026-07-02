(ns nex.repl
  "Interactive REPL for the Nex programming language"
  (:require [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.compiler.jvm.runtime :as compiled-runtime]
            [nex.debugger :as dbg]
            [nex.typechecker :as tc]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [org.jline.reader LineReaderBuilder LineReader LineReader$Option Completer Candidate]
           [org.jline.reader.impl DefaultParser]
           [org.jline.terminal TerminalBuilder]
           [org.jline.reader.impl.history DefaultHistory]
           [java.io EOFException]
           [java.lang.reflect InvocationTargetException]
           [clj_antlr ParseError])
  (:gen-class))

;;
;; REPL State
;;

(defonce ^:dynamic *repl-context* nil)
(defonce ^:dynamic *type-checking-enabled* (atom false))
(defonce ^:dynamic *repl-var-types* (atom {}))
;; Type aliases (`declare type Name = ...`) declared in earlier inputs, kept by
;; name so later lines and the type checker can resolve them.
(defonce ^:dynamic *repl-type-aliases* (atom {}))
(defonce ^:dynamic *repl-backend* (atom :compiled))
(defonce ^:dynamic *compiled-repl-session* (atom (compiled-repl/make-session)))

(def nex-keywords
  ["class" "deferred" "declare" "feature" "inherit" "end" "do" "if" "then" "else" "elseif"
   "when" "from" "until" "invariant" "variant" "require" "ensure"
   "let" "create" "convert" "to" "fn" "function" "and" "or" "old" "this" "note"
   "with" "import" "intern" "private" "raise" "rescue" "retry" "spawn" "select" "timeout" "repeat" "across" "case" "of"
   "true" "false" "nil"
   ;; strictly 'result' is not a keyword, but a pre-defined variable name.
   "result"])

(def nex-types
  ["Integer" "Real" "Char" "Boolean" "String"
   "Array" "Map" "Set" "Min_Heap" "Atomic_Integer" "Atomic_Integer64" "Atomic_Boolean" "Atomic_Reference"
   "Task" "Channel" "Function" "Cursor" "Console" "Process"])

(def nex-builtins ["print" "println" "type_of" "type_is"])

(def nex-repl-commands
  [":help" ":quit" ":exit" ":clear" ":reset" ":classes" ":vars"
   ":typecheck" ":load" ":backend"
   ":debug" ":break" ":tbreak" ":breaks" ":clearbreak" ":breakon"
   ":enable" ":disable" ":watch" ":watches" ":clearwatch"
   ":enablewatch" ":disablewatch"
   ":ignore" ":every"
   ":breaksave" ":breakload" ":debugscript"])

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
  (reset! *repl-var-types* {})
  (reset! *repl-type-aliases* {})
  (reset! *compiled-repl-session* (compiled-repl/make-session))
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
  (let [sanitize-line
        (fn [line]
          (let [n (count line)]
            (loop [i 0
                   in-string? false
                   escaped? false
                   out (StringBuilder.)]
              (if (>= i n)
                (str out)
                (let [ch (.charAt ^String line i)
                      next-ch (when (< (inc i) n) (.charAt ^String line (inc i)))]
                  (cond
                    in-string?
                    (cond
                      escaped? (do (.append out \space)
                                   (recur (inc i) true false out))
                      (= ch \\) (do (.append out \space)
                                    (recur (inc i) true true out))
                      (= ch \") (do (.append out \space)
                                    (recur (inc i) false false out))
                      :else (do (.append out \space)
                                (recur (inc i) true false out)))

                    (and (= ch \-) (= next-ch \-))
                    ;; Rest of the line is a comment.
                    (str out)

                    (= ch \")
                    (do (.append out \space)
                        (recur (inc i) true false out))

                    (and (= ch \#) next-ch (not= next-ch \{))
                    ;; Character literals like #), #], or #} should not affect
                    ;; delimiter balancing. Set literals start with #{, so keep
                    ;; those characters visible to the delimiter pass.
                    (let [j (cond
                              (Character/isLetterOrDigit next-ch)
                              (loop [j (inc i)]
                                (if (and (< j n)
                                         (Character/isLetterOrDigit (.charAt ^String line j)))
                                  (recur (inc j))
                                  j))

                              :else
                              (+ i 2))]
                      (.append out (apply str (repeat (- j i) \space)))
                      (recur j false false out))

                    :else
                    (do (.append out ch)
                        (recur (inc i) false false out))))))))
        raw-text (str/join "\n" lines)
        text (->> lines
                  (map sanitize-line)
                  (str/join "\n"))
        ;; A `when` is either a value-producing when-expression
        ;; (`when c then x else y end`, which opens its own `end`-terminated
        ;; block) or a clause of `match`/`select` (`when p then block`, which
        ;; does not). Since both now share the `then` keyword, they are told
        ;; apart by context: a `when` is a clause when its innermost enclosing
        ;; construct is a `match`/`select` guard. Only when-expressions count
        ;; toward the open-block balance.
        count-when-expressions
        (fn [text]
          (let [tokens (re-seq #"\bclass\b|\bdo\b|\bfrom\b|\brepeat\b|\bacross\b|\bif\b|\bcase\b|\bmatch\b|\bselect\b|\bwhen\b|\bthen\b|\belse\b|\bend\b" text)]
            (loop [tokens tokens
                   stack '()
                   count 0]
              (if-let [token (first tokens)]
                (let [top (peek stack)]
                  (case token
                    ("class" "do" "from" "repeat" "across" "if" "case")
                    (recur (rest tokens) (conj stack :block) count)

                    ("match" "select")
                    (recur (rest tokens) (conj stack :guard) count)

                    "when"
                    ;; A sibling clause closes the previous clause body first.
                    (let [stack (if (= top :clausebody) (pop stack) stack)]
                      (if (= (peek stack) :guard)
                        (recur (rest tokens) (conj stack :clause) count)
                        (recur (rest tokens) (conj stack :when) (inc count))))

                    "then"
                    ;; `then` after a clause's pattern opens that clause's body.
                    (recur (rest tokens)
                           (if (= top :clause) (conj (pop stack) :clausebody) stack)
                           count)

                    "else"
                    ;; A trailing `else` closes the guard's last clause body.
                    (recur (rest tokens)
                           (if (= top :clausebody) (pop stack) stack)
                           count)

                    "end"
                    (recur (rest tokens)
                           (cond
                             (= top :clausebody) (pop (pop stack)) ;; clause body + its guard
                             (seq stack) (pop stack)
                             :else stack)
                           count)

                    (recur (rest tokens) stack count)))
                count))))
        delimiter-balance
        (reduce (fn [balance ch]
                  (case ch
                    \( (update balance :parens inc)
                    \) (update balance :parens dec)
                    \[ (update balance :brackets inc)
                    \] (update balance :brackets dec)
                    \{ (update balance :braces inc)
                    \} (update balance :braces dec)
                    balance))
                {:parens 0 :brackets 0 :braces 0}
                text)
        open-delimiters? (some pos? (vals delimiter-balance))
        ;; Count keyword pairs that need to be closed
        class-count (count (re-seq #"\bclass\b" text))
        feature-count (count (re-seq #"\bfeature\b" text))
        do-count (count (re-seq #"\bdo\b" text))
        from-count (count (re-seq #"\bfrom\b" text))
        repeat-count (count (re-seq #"\brepeat\b" text))
        across-count (count (re-seq #"\bacross\b" text))
        if-count (count (re-seq #"\bif\b" text))
        when-count (count-when-expressions text)
        case-count (count (re-seq #"\bcase\b" text))
        match-count (count (re-seq #"\bmatch\b" text))
        select-count (count (re-seq #"\bselect\b" text))
        end-count (count (re-seq #"\bend\b" text))
        ;; In loops, 'do' is part of 'from...until...do...end', 'repeat...do...end', or 'across...do...end'
        ;; So subtract from-count, repeat-count, and across-count from do-count to avoid double-counting
        standalone-do-count (max 0 (- do-count from-count repeat-count across-count))
        ;; Total blocks that need closing
        open-blocks (+ class-count standalone-do-count from-count repeat-count
                       across-count if-count when-count case-count match-count select-count)
        lines (vec (str/split-lines text))
        bare-function-header?
        (boolean
         (some identity
               (map-indexed
                (fn [idx line]
                  (when (and (re-find #"^\s*function\b" line)
                             (not (re-find #"\bdo\b" line)))
                    (not-any? #(re-find #"\bdo\b" %)
                              (subvec lines (inc idx)))))
                lines)))
        ;; A line ending on a dangling binary operator (e.g. `:=`, `+`, `and`)
        ;; needs a right-hand side, so keep reading. String and char literals
        ;; are collapsed to a placeholder first so that a literal ending in an
        ;; operator (e.g. `let s := "x :="`) is not mistaken for a dangling one.
        trailing-operator?
        (let [masked (-> raw-text
                         (str/replace #"\"(?:[^\"\\]|\\.)*\"" "0")
                         (str/replace #"#\w+" "0"))]
          (boolean
           (re-find #"(?::=|/=|<=|>=|\+|-|\*|/|%|\^|=|<|>|,|\band\b|\bor\b)$"
                    (str/trimr masked))))]
    ;; Continue if we have more opens than closes
    (or bare-function-header?
        open-delimiters?
        trailing-operator?
        (> open-blocks end-count))))

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
  (println "  :backend compiled    - Use the JVM-compiled REPL path (default)")
  (println "  :backend interpreter - Use the tree-walking interpreter fallback/escape hatch")
  (println "  :backend status      - Show current backend")
  (println "  :debug on|off     - Enable/disable debugger")
  (println "  :debug status     - Show debugger status")
  (println "  :break <spec>     - Breakpoint: n | Class.method | Class.method:line | file.nex:line | field:name | Class#field")
  (println "  :tbreak <spec>    - Temporary breakpoint (auto-clears after first hit)")
  (println "  :breaks           - List breakpoints")
  (println "  :clearbreak <id|spec>- Remove one/matching breakpoints")
  (println "  :clearbreak all   - Remove all breakpoints")
  (println "  :enable <id|all>  - Enable one/all breakpoints")
  (println "  :disable <id|all> - Disable one/all breakpoints")
  (println "  :watch <expr>     - Add watchpoint (pause when value changes)")
  (println "  :watches          - List watchpoints")
  (println "  :clearwatch <id|all> - Remove watchpoint(s)")
  (println "  :enablewatch <id|all>  - Enable watchpoint(s)")
  (println "  :disablewatch <id|all> - Disable watchpoint(s)")
  (println "  :ignore <id> <n> - Ignore first n hits for a breakpoint")
  (println "  :every <id> <n>  - Break every n-th hit for a breakpoint")
  (println "  :breakon exception on|off - Pause when an exception is raised")
  (println "  :breakon contract on|off  - Pause on contract violations")
  (println "  :breakon <kind> filter <value> - Set break-on filter")
  (println "  :breakon <kind> clear         - Clear break-on filter")
  (println "  :breakon status           - Show break-on toggles")
  (println "  :breaksave <path> - Save breakpoints/watchpoints")
  (println "  :breakload <path> - Load breakpoints/watchpoints")
  (println "  :debugscript <path|off|status> - Script dbg prompt commands")
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
(declare sync-compiled-session-into-interpreter!)

(defn normalize-load-path [raw]
  (let [trimmed (str/trim raw)]
    (if (and (>= (count trimmed) 2)
             (or (and (.startsWith trimmed "\"") (.endsWith trimmed "\""))
                 (and (.startsWith trimmed "'") (.endsWith trimmed "'"))))
      (subs trimmed 1 (dec (count trimmed)))
      trimmed)))

(defn resolve-user-path [path]
  (let [f (io/file path)]
    (if (.isAbsolute f)
      f
      (if-let [user-dir (System/getProperty "nex.user.dir")]
        (io/file user-dir path)
        f))))

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
            file (resolve-user-path path)]
        (if-not (.exists file)
          (do
            (println (str "File not found: " (.getPath file)))
            ctx)
          (do
            (when-not (.endsWith (.getName file) ".nex")
              (println "Warning: expected a .nex file"))
            (eval-code ctx (slurp file) (.getPath file))))))))

(defn parse-contract-filter [s]
  (let [v (str/lower-case (str/trim s))]
    (case v
      "pre" :pre
      "post" :post
      "invariant" :invariant
      s)))

(defn- repl-cmd-help [ctx input]
  (do (show-help) ctx))

(defn- repl-cmd-quit [ctx input]
  :quit)

(defn- repl-cmd-clear [ctx input]
  (do
    (println "Context cleared.")
    (reset! *repl-var-types* {})
    (reset! *repl-type-aliases* {})
    (reset! *compiled-repl-session* (compiled-repl/reset-session))
    (dbg/reset-run-state!)
    (init-repl-context)))

(defn- repl-cmd-classes [ctx input]
  (do (show-classes ctx) ctx))

(defn- repl-cmd-vars [ctx input]
  (do (show-vars ctx) ctx))

(defn- repl-cmd-typecheck-on [ctx input]
  (do
    (reset! *type-checking-enabled* true)
    (println "Type checking enabled. Code will be validated before execution.")
    ctx))

(defn- repl-cmd-typecheck-off [ctx input]
  (do
    (reset! *type-checking-enabled* false)
    (println "Type checking disabled.")
    ctx))

(defn- repl-cmd-typecheck-status [ctx input]
  (do
    (println (str "Type checking is currently: "
                 (if @*type-checking-enabled* "ENABLED" "DISABLED")))
    ctx))

(defn- repl-cmd-backend-interpreter [ctx input]
  (do
    (sync-compiled-session-into-interpreter! ctx)
    (reset! *repl-backend* :interpreter)
    (println "REPL backend set to INTERPRETER.")
    ctx))

(defn- repl-cmd-backend-compiled [ctx input]
  (do
    (reset! *repl-backend* :compiled)
    (let [session (compiled-repl/make-session)]
      (compiled-repl/sync-interpreter->session! session
                                                ctx
                                                @*repl-var-types*
                                                {:type :program
                                                 :imports []
                                                 :interns []
                                                 :classes []
                                                 :functions []
                                                 :statements []
                                                 :calls []})
      (reset! *compiled-repl-session* session))
    (println "REPL backend set to COMPILED. Unsupported inputs will fall back to the interpreter.")
    ctx))

(defn- repl-cmd-backend-status [ctx input]
  (do
    (println (str "REPL backend is currently: " (-> @*repl-backend* name str/upper-case)))
    ctx))

(defn- repl-cmd-load [ctx input]
  (let [path (subs input (min (count input) (count ":load")))]
    (load-file-into-repl ctx path)))

(defn- repl-cmd-debug-on [ctx input]
  (do
    (dbg/set-enabled! true)
    (println "Debugger enabled.")
    ctx))

(defn- repl-cmd-debug-off [ctx input]
  (do
    (dbg/set-enabled! false)
    (println "Debugger disabled.")
    ctx))

(defn- repl-cmd-debug-status [ctx input]
  (do
    (println (str "Debugger is currently: " (if (dbg/enabled?) "ENABLED" "DISABLED")))
    (println (str "Breakpoints: "
                  (if (seq (dbg/breakpoint-entries))
                    (str/join ", " (map dbg/breakpoint-entry->str (dbg/breakpoint-entries)))
                    "(none)")))
    (println (str "Watchpoints: "
                  (if (seq (dbg/watchpoint-entries))
                    (str/join ", " (map dbg/watchpoint-entry->str (dbg/watchpoint-entries)))
                    "(none)")))
    (println (str "Debug script: " (if (dbg/debug-script-active?) "loaded" "off")))
    ctx))

(defn- repl-cmd-break [ctx input]
  (let [parsed (dbg/parse-break-command (subs input (count ":break ")))]
    (if parsed
      (do
        (let [bp (cond-> (:spec parsed)
                   (:condition parsed) (assoc :condition (:condition parsed)))
              id (dbg/add-breakpoint! bp)]
          (println (str "Added breakpoint [" id "] " (dbg/breakpoint->str bp)
                        (when-let [cond-expr (:condition bp)]
                          (str " if " cond-expr)))))
        ctx)
      (do
        (println "Usage: :break <spec> [if <expr>]")
        (println "  spec: n | Class.method | Class.method:line | file.nex:line | field:name | Class#field")
        ctx))))

(defn- repl-cmd-tbreak [ctx input]
  (let [parsed (dbg/parse-break-command (subs input (count ":tbreak ")))]
    (if parsed
      (do
        (let [bp (cond-> (assoc (:spec parsed) :temporary true)
                   (:condition parsed) (assoc :condition (:condition parsed)))
              id (dbg/add-breakpoint! bp)]
          (println (str "Added temporary breakpoint [" id "] " (dbg/breakpoint->str bp)
                        (when-let [cond-expr (:condition bp)]
                          (str " if " cond-expr)))))
        ctx)
      (do
        (println "Usage: :tbreak <spec> [if <expr>]")
        (println "  spec: n | Class.method | Class.method:line | file.nex:line | field:name | Class#field")
        ctx))))

(defn- repl-cmd-breaks [ctx input]
  (do
    (if (seq (dbg/breakpoint-entries))
      (do
        (println "Breakpoints:")
        (doseq [entry (dbg/breakpoint-entries)]
          (println (str "  " (dbg/breakpoint-entry->str entry)))))
      (println "No breakpoints set."))
    ctx))

(defn- repl-cmd-clearbreak [ctx input]
  (let [arg (str/trim (subs input (count ":clearbreak")))]
    (cond
      (= (str/lower-case arg) "all")
      (do (dbg/clear-breakpoints!)
          (println "Cleared all breakpoints.")
          ctx)
      :else
      (if-let [id (dbg/parse-positive-int arg)]
        (let [removed (dbg/remove-breakpoint! id)]
          (if (pos? removed)
            (println (str "Cleared breakpoint [" id "]"))
            (println (str "No breakpoint with id [" id "]")))
          ctx)
        (if-let [parsed (dbg/parse-break-command arg)]
          (let [bp (cond-> (:spec parsed)
                     (:condition parsed) (assoc :condition (:condition parsed)))
                removed (dbg/remove-breakpoint! bp)]
            (println (str "Cleared " removed " breakpoint(s) matching " (dbg/breakpoint->str bp)
                          (when-let [cond-expr (:condition bp)]
                            (str " if " cond-expr))))
            ctx)
          (do
            (println "Usage: :clearbreak <id|spec|all>")
            (println "  spec: n | Class.method | Class.method:line | file.nex:line | field:name | Class#field")
            ctx))))))

(defn- repl-cmd-enable [ctx input]
  (let [arg (str/trim (subs input (count ":enable ")))
        arg-lower (str/lower-case arg)]
    (cond
      (= arg-lower "all")
      (do
        (dbg/set-all-breakpoints-enabled! true)
        (println "Enabled all breakpoints.")
        ctx)
      :else
      (if-let [id (dbg/parse-positive-int arg)]
        (do
          (if (dbg/set-breakpoint-enabled! id true)
            (println (str "Enabled breakpoint [" id "]"))
            (println (str "No breakpoint with id [" id "]")))
          ctx)
        (do
          (println "Usage: :enable <id|all>")
          ctx)))))

(defn- repl-cmd-disable [ctx input]
  (let [arg (str/trim (subs input (count ":disable ")))
        arg-lower (str/lower-case arg)]
    (cond
      (= arg-lower "all")
      (do
        (dbg/set-all-breakpoints-enabled! false)
        (println "Disabled all breakpoints.")
        ctx)
      :else
      (if-let [id (dbg/parse-positive-int arg)]
        (do
          (if (dbg/set-breakpoint-enabled! id false)
            (println (str "Disabled breakpoint [" id "]"))
            (println (str "No breakpoint with id [" id "]")))
          ctx)
        (do
          (println "Usage: :disable <id|all>")
          ctx)))))

(defn- repl-cmd-watch [ctx input]
  (let [parsed (dbg/parse-watch-command (subs input (count ":watch ")))]
    (if-not parsed
      (do
        (println "Usage: :watch <expr> [if <expr>]")
        ctx)
      (let [id (dbg/add-watchpoint! parsed)]
        (println (str "Added watchpoint [" id "] " (:expr parsed)
                      (when-let [cond-expr (:condition parsed)]
                        (str " if " cond-expr))))
        ctx))))

(defn- repl-cmd-watches [ctx input]
  (do
    (if (seq (dbg/watchpoint-entries))
      (do
        (println "Watchpoints:")
        (doseq [entry (dbg/watchpoint-entries)]
          (println (str "  " (dbg/watchpoint-entry->str entry)))))
      (println "No watchpoints set."))
    ctx))

(defn- repl-cmd-clearwatch [ctx input]
  (let [arg (str/trim (subs input (count ":clearwatch")))]
    (cond
      (= (str/lower-case arg) "all")
      (do
        (dbg/clear-watchpoints!)
        (println "Cleared all watchpoints.")
        ctx)
      :else
      (if-let [id (dbg/parse-positive-int arg)]
        (do
          (if (pos? (dbg/remove-watchpoint! id))
            (println (str "Cleared watchpoint [" id "]"))
            (println (str "No watchpoint with id [" id "]")))
          ctx)
        (do
          (println "Usage: :clearwatch <id|all>")
          ctx)))))

(defn- repl-cmd-enablewatch [ctx input]
  (let [arg (str/trim (subs input (count ":enablewatch ")))
        arg-lower (str/lower-case arg)]
    (cond
      (= arg-lower "all")
      (do
        (dbg/set-all-watchpoints-enabled! true)
        (println "Enabled all watchpoints.")
        ctx)
      :else
      (if-let [id (dbg/parse-positive-int arg)]
        (do
          (if (dbg/set-watchpoint-enabled! id true)
            (println (str "Enabled watchpoint [" id "]"))
            (println (str "No watchpoint with id [" id "]")))
          ctx)
        (do
          (println "Usage: :enablewatch <id|all>")
          ctx)))))

(defn- repl-cmd-disablewatch [ctx input]
  (let [arg (str/trim (subs input (count ":disablewatch ")))
        arg-lower (str/lower-case arg)]
    (cond
      (= arg-lower "all")
      (do
        (dbg/set-all-watchpoints-enabled! false)
        (println "Disabled all watchpoints.")
        ctx)
      :else
      (if-let [id (dbg/parse-positive-int arg)]
        (do
          (if (dbg/set-watchpoint-enabled! id false)
            (println (str "Disabled watchpoint [" id "]"))
            (println (str "No watchpoint with id [" id "]")))
          ctx)
        (do
          (println "Usage: :disablewatch <id|all>")
          ctx)))))

(defn- repl-cmd-ignore [ctx input]
  (let [parts (remove str/blank? (str/split (str/trim (subs input (count ":ignore "))) #"\s+"))
        id (dbg/parse-positive-int (first parts))
        n (try
            (let [v (Integer/parseInt (or (second parts) ""))]
              (when (>= v 0) v))
            (catch Exception _ nil))]
    (if (and id (some? n))
      (do
        (if (dbg/set-breakpoint-after! id n)
          (println (str "Breakpoint [" id "] will ignore first " n " hit(s)."))
          (println (str "No breakpoint with id [" id "]")))
        ctx)
      (do
        (println "Usage: :ignore <id> <n>")
        ctx))))

(defn- repl-cmd-every [ctx input]
  (let [parts (remove str/blank? (str/split (str/trim (subs input (count ":every "))) #"\s+"))
        id (dbg/parse-positive-int (first parts))
        n (dbg/parse-positive-int (second parts))]
    (if (and id n)
      (do
        (if (dbg/set-breakpoint-every! id n)
          (println (str "Breakpoint [" id "] will break every " n " hit(s)."))
          (println (str "No breakpoint with id [" id "]")))
        ctx)
      (do
        (println "Usage: :every <id> <n>")
        ctx))))

(defn- repl-cmd-breakon-status [ctx input]
  (let [{:keys [exception contract]} (dbg/break-on-status)
        filters (dbg/break-on-filters)
        exf (:exception filters)
        cf (:contract filters)]
    (println (str "break-on exception: " (if exception "on" "off")))
    (println (str "  exception filter: " (or exf "none")))
    (println (str "break-on contract: " (if contract "on" "off")))
    (println (str "  contract filter: " (or cf "none")))
    ctx))

(defn- repl-cmd-breakon [ctx input]
  (let [rest-arg (str/trim (subs input (count ":breakon ")))
        parts (remove str/blank? (str/split rest-arg #"\s+"))
        kind (first parts)
        flag (second parts)
        extra (str/join " " (drop 2 parts))]
    (cond
      (and (#{"exception" "contract"} (str/lower-case (or kind "")))
           (#{"on" "off"} (str/lower-case (or flag "")))
           (or (str/blank? extra) (= "on" (str/lower-case (or flag "")))))
      (let [k (keyword (str/lower-case kind))
            on? (= "on" (str/lower-case flag))]
        (dbg/set-break-on! k on?)
        (when (and on? (not (str/blank? extra)))
          (dbg/set-break-on-filter! k (if (= k :contract) (parse-contract-filter extra) extra)))
        (println (str "break-on " (name k) " set to " (if on? "on" "off")))
        ctx)
      (and (#{"exception" "contract"} (str/lower-case (or kind "")))
           (= "filter" (str/lower-case (or flag "")))
           (not (str/blank? extra)))
      (let [k (keyword (str/lower-case kind))
            filter-val (if (= k :contract) (parse-contract-filter extra) extra)]
        (dbg/set-break-on-filter! k filter-val)
        (println (str "break-on " (name k) " filter set to " filter-val))
        ctx)
      (and (#{"exception" "contract"} (str/lower-case (or kind "")))
           (= "clear" (str/lower-case (or flag ""))))
      (let [k (keyword (str/lower-case kind))]
        (dbg/clear-break-on-filter! k)
        (println (str "break-on " (name k) " filter cleared"))
        ctx)
      :else
      (do
        (println "Usage: :breakon <exception|contract> <on|off> [filter]")
        (println "   or: :breakon <exception|contract> filter <value>")
        (println "   or: :breakon <exception|contract> clear")
        ctx))))

(defn- repl-cmd-breaksave [ctx input]
  (let [path (normalize-load-path (subs input (count ":breaksave ")))
        file (resolve-user-path path)]
    (if (str/blank? path)
      (do
        (println "Usage: :breaksave <path>")
        ctx)
      (do
        (spit file (pr-str (dbg/snapshot-breakpoints-and-watchpoints)))
        (println (str "Saved breakpoints/watchpoints to " (.getPath file)))
        ctx))))

(defn- repl-cmd-breakload [ctx input]
  (let [path (normalize-load-path (subs input (count ":breakload ")))
        file (resolve-user-path path)]
    (cond
      (str/blank? path)
      (do
        (println "Usage: :breakload <path>")
        ctx)
      (not (.exists file))
      (do
        (println (str "File not found: " (.getPath file)))
        ctx)
      :else
      (try
        (let [snapshot (edn/read-string (slurp file))]
          (if (map? snapshot)
            (do
              (dbg/restore-breakpoints-and-watchpoints! snapshot)
              (println (str "Loaded breakpoints/watchpoints from " (.getPath file)))
              ctx)
            (do
              (println "Invalid breakpoint snapshot format.")
              ctx)))
        (catch Exception e
          (println (str "Error loading snapshot: " (.getMessage e)))
          ctx)))))

(defn- repl-cmd-debugscript-status [ctx input]
  (do
    (println (str "debugscript: " (if (dbg/debug-script-active?) "loaded" "off")))
    ctx))

(defn- repl-cmd-debugscript [ctx input]
  (let [arg (str/trim (subs input (count ":debugscript ")))
        arg-lower (str/lower-case arg)]
    (cond
      (= arg-lower "off")
      (do
        (dbg/clear-debug-script!)
        (println "Debugger script cleared.")
        ctx)
      (= arg-lower "status")
      (do
        (println (str "debugscript: " (if (dbg/debug-script-active?) "loaded" "off")))
        ctx)
      :else
      (let [file (resolve-user-path (normalize-load-path arg))]
        (if-not (.exists file)
          (do
            (println (str "File not found: " (.getPath file)))
            ctx)
          (do
            (dbg/set-debug-script! (str/split-lines (slurp file)))
            (println (str "Loaded debugger script: " (.getPath file)))
            ctx))))))

(def ^:private repl-commands
  "Ordered REPL command table; first match wins (mirrors the original cond order).
   :names = exact/alias match on the lower-cased input; :prefix = str/starts-with?."
  [
   {:names #{":help" ":h" ":?"} :handler repl-cmd-help}
   {:names #{":quit" ":q" ":exit"} :handler repl-cmd-quit}
   {:names #{":clear" ":reset"} :handler repl-cmd-clear}
   {:names #{":classes"} :handler repl-cmd-classes}
   {:names #{":vars"} :handler repl-cmd-vars}
   {:names #{":typecheck on"} :handler repl-cmd-typecheck-on}
   {:names #{":typecheck off"} :handler repl-cmd-typecheck-off}
   {:names #{":typecheck" ":typecheck status"} :handler repl-cmd-typecheck-status}
   {:names #{":backend interpreter"} :handler repl-cmd-backend-interpreter}
   {:names #{":backend compiled"} :handler repl-cmd-backend-compiled}
   {:names #{":backend" ":backend status"} :handler repl-cmd-backend-status}
   {:prefix ":load" :handler repl-cmd-load}
   {:names #{":debug on"} :handler repl-cmd-debug-on}
   {:names #{":debug off"} :handler repl-cmd-debug-off}
   {:names #{":debug" ":debug status"} :handler repl-cmd-debug-status}
   {:prefix ":break " :handler repl-cmd-break}
   {:prefix ":tbreak " :handler repl-cmd-tbreak}
   {:names #{":breaks"} :handler repl-cmd-breaks}
   {:prefix ":clearbreak" :handler repl-cmd-clearbreak}
   {:prefix ":enable " :handler repl-cmd-enable}
   {:prefix ":disable " :handler repl-cmd-disable}
   {:prefix ":watch " :handler repl-cmd-watch}
   {:names #{":watches"} :handler repl-cmd-watches}
   {:prefix ":clearwatch" :handler repl-cmd-clearwatch}
   {:prefix ":enablewatch " :handler repl-cmd-enablewatch}
   {:prefix ":disablewatch " :handler repl-cmd-disablewatch}
   {:prefix ":ignore " :handler repl-cmd-ignore}
   {:prefix ":every " :handler repl-cmd-every}
   {:names #{":breakon" ":breakon status"} :handler repl-cmd-breakon-status}
   {:prefix ":breakon " :handler repl-cmd-breakon}
   {:prefix ":breaksave " :handler repl-cmd-breaksave}
   {:prefix ":breakload " :handler repl-cmd-breakload}
   {:names #{":debugscript" ":debugscript status"} :handler repl-cmd-debugscript-status}
   {:prefix ":debugscript " :handler repl-cmd-debugscript}])

(defn handle-command [ctx input]
  (let [input-lower (str/lower-case input)]
    (if-let [{:keys [handler]} (some (fn [{:keys [names prefix] :as cmd}]
                                       (when (if names
                                               (contains? names input-lower)
                                               (str/starts-with? input-lower prefix))
                                         cmd))
                                     repl-commands)]
      (handler ctx input)
      (do
        (println (str "Unknown command: " input))
        (println "Type :help for available commands")
        ctx))))

;;
;; Code Evaluation
;;


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

(defn- top-level-safe-call-when-expr
  "A bare safe call typed at the REPL (e.g. `b?.get_value`) parses in statement
   position and desugars to a value-discarding scoped-block, so the REPL has no
   value to echo. When the parsed program is exactly such a safe call on a simple
   identifier receiver, rebuild it as the value-bearing `when` expression (the
   same shape used in expression position) so the result is echoed. Returns the
   rewritten AST, or the original AST when it does not match."
  [ast]
  (let [stmts (:statements ast)
        stmt (first stmts)
        [binding guard] (:body stmt)]
    (if (and (= 1 (count stmts))
             (= :scoped-block (:type stmt))
             (nil? (:rescue stmt))
             (= 2 (count (:body stmt)))
             (= :let (:type binding))
             (:synthetic binding)
             (string? (:name binding))
             (str/starts-with? (:name binding) "__safe_receiver_")
             (= :identifier (:type (:value binding)))
             (= :if (:type guard))
             (= 1 (count (:then guard)))
             (= :call (:type (first (:then guard))))
             (nil? (:else guard)))
      (let [target-expr (:value binding)
            call (first (:then guard))]
        (assoc ast :statements
               [{:type :when
                 :condition {:type :binary
                             :operator "/="
                             :left target-expr
                             :right {:type :nil}}
                 :consequent (assoc call :target (:name target-expr))
                 :alternative {:type :nil}}]))
      ast)))

(defn- compiled-object-class-name
  [value]
  (when (and value (= :compiled @*repl-backend*))
    (let [session @*compiled-repl-session*
          known-classes @(:classes (:state session))
          simple-name (some-> value .getClass .getName (str/split #"\.") last)]
      (cond
        (contains? known-classes simple-name)
        simple-name

        :else
        (when-let [[_ candidate] (and simple-name
                                      (re-matches #"(.+)_\d{4}" simple-name))]
          (when (contains? known-classes candidate)
            candidate))))))

(defn format-value
  [value]
  (if-let [class-name (compiled-object-class-name value)]
    (str "#<" class-name " object>")
    (interp/nex-format-value value)))

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
    (try
      (when-let [t (case (:type expr-node)
                     :let (or (:var-type expr-node)
                              (get @*repl-var-types* (:name expr-node))
                              (when-let [value (:value expr-node)]
                                (tc/infer-expression-type
                                 value
                                 {:classes (vals @(:classes ctx))
                                  :imports @(:imports ctx)
                                  :var-types @*repl-var-types*})))
                     (tc/infer-expression-type
                      expr-node
                      {:classes (vals @(:classes ctx))
                       :imports @(:imports ctx)
                       :var-types @*repl-var-types*}))]
        (format-type t))
      (catch Exception _
        nil))))

(def ordered-comparison-ops
  #{"<" "<=" ">" ">="})

(def builtin-sortable-types
  #{"Integer" "Real" "Char" "Boolean" "String"})

(defn- normalized-type-params
  [t]
  (or (:type-params t) (:type-args t)))

(defn- nonbuiltin-array-sort?
  [ctx node]
  (when (and (map? node)
             (= :call (:type node))
             (= "sort" (:method node))
             (:target node))
    (let [env {:classes (vals @(:classes ctx))
               :imports @(:imports ctx)
               :var-types @*repl-var-types*}
          tc-env (let [e (tc/make-type-env)]
                   (doseq [class-def (vals @(:classes ctx))]
                     (tc/env-add-class e (:name class-def) class-def))
                   e)
          target-type (try (tc/infer-expression-type (:target node) env)
                           (catch Exception _ nil))
          type-params (when (map? target-type)
                        (normalized-type-params target-type))
          element-type (first type-params)]
      (and (= "Array" (:base-type target-type))
           (string? element-type)
           (not (contains? builtin-sortable-types element-type))
           (not (tc/types-compatible? tc-env element-type "Comparable"))))))

(defn- string-ordered-comparison?
  [ctx node]
  (when (and (map? node)
             (#{:binary :binary-op} (:type node))
             (contains? ordered-comparison-ops (:operator node))
             @*type-checking-enabled*)
    (let [env {:classes (vals @(:classes ctx))
               :imports @(:imports ctx)
               :var-types @*repl-var-types*}
          left-type (try (tc/infer-expression-type (:left node) env)
                         (catch Exception _ nil))
          right-type (try (tc/infer-expression-type (:right node) env)
                          (catch Exception _ nil))]
      (or (= "String" left-type)
          (= "String" right-type)
          (nil? left-type)
          (nil? right-type)
          (not (contains? builtin-sortable-types left-type))
          (not (contains? builtin-sortable-types right-type))))))

(defn- uncompiled-user-function-call?
  [node]
  (when (and (= :compiled @*repl-backend*)
             (map? node)
             (= :call (:type node))
             (nil? (:target node)))
    (let [function-name (:method node)
          binding-type (get @*repl-var-types* function-name)
          compiled-function-names (when-let [session @*compiled-repl-session*]
                                    (set (keys @(:functions (:state session)))))]
      (and binding-type
           (not (contains? compiled-function-names function-name))))))

(defn- type-expr-type-names
  "Every type name referenced by a type annotation, which may be a bare string
  (`\"Integer\"`) or a structured type (`{:base-type .. :type-args .. :param-types
  .. :return-type ..}`)."
  [t]
  (cond
    (string? t) [t]
    (map? t) (concat (when-let [b (:base-type t)] [b])
                     (mapcat type-expr-type-names (:type-args t))
                     (mapcat (comp type-expr-type-names :type) (:param-types t))
                     (type-expr-type-names (:return-type t)))
    :else nil))

(defn- references-type-alias?
  "True when any type annotation in `ast` names one of `alias-names`. Used to
  steer alias-typed inputs to the interpreter, which ignores type annotations,
  since the compiled backend's lowering cannot resolve declared aliases."
  [alias-names ast]
  (and (seq alias-names)
       (let [annotation-keys [:var-type :target-type :return-type :field-type
                              :element-type :value-type :key-type]]
         (boolean
          (some (fn [node]
                  (when (map? node)
                    (let [from-keys (mapcat #(type-expr-type-names (get node %))
                                            annotation-keys)
                          ;; a parameter's `:type` is a type, but a node's `:type`
                          ;; is its kind keyword — only the former is a string/map.
                          from-param (let [t (:type node)]
                                       (when (or (string? t) (and (map? t) (:base-type t)))
                                         (type-expr-type-names t)))]
                      (some alias-names (concat from-keys from-param)))))
                (tree-seq coll? seq ast))))))

(defn- ast-needs-interpreter-fallback?
  [ctx ast]
  (or (references-type-alias? (set (keys @*repl-type-aliases*)) ast)
      (let [top-nodes (concat (:statements ast)
                              (:calls ast)
                              (:functions ast))
            nodes (tree-seq coll? seq top-nodes)]
        (boolean
         (some (fn [node]
                 (or (string-ordered-comparison? ctx node)
                     (nonbuiltin-array-sort? ctx node)
                     (uncompiled-user-function-call? node)))
               nodes)))))

(defn- fallback-eligible-compiled-error?
  [^clojure.lang.ExceptionInfo e]
  (contains? #{"Only eq/neq object comparisons are supported"
               "Array.sort requires Comparable elements"}
             (.getMessage e)))

(defn- sync-compiled-session-into-interpreter!
  [ctx]
  (when (= :compiled @*repl-backend*)
    (let [{:keys [var-types]} (compiled-repl/sync-session->interpreter!
                               @*compiled-repl-session*
                               ctx)]
      (reset! *repl-var-types* var-types))))

(defn- repl-alias-env
  "A minimal type-checker environment carrying just the active REPL type
  aliases, suitable for `tc/expand-type-aliases`."
  []
  {:type-aliases (atom (into {}
                             (map (fn [[k v]] [k (:type-expr v)]))
                             @*repl-type-aliases*))
   :parent nil})

(defn- expand-alias-type
  [env t]
  (if (and t (or (string? t) (map? t)))
    (tc/expand-type-aliases env t)
    t))

(defn- expand-type-aliases-in-ast
  "Rewrite every declared-type annotation in `ast` through the active REPL type
  aliases. The compiled backend is alias-agnostic: a variable recorded with an
  alias type (e.g. `Goods`) would later be treated as an unknown user class and
  fail to resolve to a JVM type, so the underlying type must be substituted
  before the AST reaches the compiled session."
  [ast]
  (if (empty? @*repl-type-aliases*)
    ast
    (let [env (repl-alias-env)
          ;; A node's own `:type` is its kind keyword; only a parameter's
          ;; `:type` (a string or `:base-type` map) is an annotation.
          type-key? (fn [k v]
                      (or (contains? #{:var-type :target-type :return-type :field-type
                                       :element-type :value-type :key-type} k)
                          (and (= k :type)
                               (or (string? v) (and (map? v) (:base-type v))))))
          walk (fn walk [node]
                 (cond
                   (map? node)
                   (reduce-kv (fn [m k v]
                                (assoc m k (if (type-key? k v)
                                             (expand-alias-type env v)
                                             (walk v))))
                              {} node)
                   (sequential? node) (mapv walk node)
                   :else node))]
      (walk ast))))

(defn- expand-type-aliases-in-var-types
  [var-types]
  (if (empty? @*repl-type-aliases*)
    var-types
    (let [env (repl-alias-env)]
      (into {} (map (fn [[k v]] [k (expand-alias-type env v)])) var-types))))

(defn- sync-interpreter-back-into-compiled-session!
  [ctx ast source-id]
  (when (= :compiled @*repl-backend*)
    (reset! *compiled-repl-session*
            (compiled-repl/sync-interpreter->session!
             @*compiled-repl-session*
             ctx
             (expand-type-aliases-in-var-types @*repl-var-types*)
             (expand-type-aliases-in-ast ast)
             source-id))))

(defn- unwrap-user-visible-exception
  [e]
  (loop [e e]
    (if (instance? InvocationTargetException e)
      (if-let [cause (.getCause ^InvocationTargetException e)]
        (recur cause)
        e)
      e)))

(defn- flush-compiled-output-on-error!
  []
  (when-let [session @*compiled-repl-session*]
    (when-let [state (:state session)]
      (let [output (compiled-runtime/state-output state)]
        (when (seq output)
          (doseq [line output]
            (println line))
          (compiled-runtime/clear-output! state))))))

(defn looks-like-class?
  "Check if input looks like a top-level declaration"
  [input]
  (or (re-find #"^\s*class\s+" input)
      (re-find #"^\s*declare\s+function\s+" input)
      (re-find #"^\s*function\s+" input)
      (re-find #"^\s*import\s+" input)
      (re-find #"^\s*intern\s+" input)))

(defn looks-like-statement?
  "Check if input needs to be wrapped in a method"
  [input]
  (or (re-find #"^\s*let\s+" input)
      (re-find #"^\s*if\s+" input)
      (re-find #"^\s*from\s+" input)
      (re-find #"^\s*repeat\s+" input)
      (re-find #"^\s*across\s+" input)
      (re-find #"^\s*do\s+" input)))

(defn looks-like-top-level-mutation?
  "Check if input is a top-level let/assignment shape that should get a raw
   compiled-path parse attempt before falling back to wrapper-based execution."
  [input]
  (or (re-find #"^\s*let\s+" input)
      (re-find #"^\s*[a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)?\s*:=" input)))

(defn looks-like-raw-compiled-statement?
  "Check if input is a top-level statement shape that should get a raw
   compiled-path parse attempt before wrapper fallback."
  [input]
  (or (looks-like-top-level-mutation? input)
      (re-find #"^\s*if\b" input)
      (re-find #"^\s*from\b" input)
      (re-find #"^\s*repeat\b" input)
      (re-find #"^\s*across\b" input)
      (re-find #"^\s*do\b" input)
      (re-find #"^\s*case\b" input)
      (re-find #"^\s*raise\b" input)
      (re-find #"^\s*retry\b" input)
      (re-find #"^\s*convert\b" input)
      (re-find #"^\s*with\b" input)
      (re-find #"^\s*select\b" input)))

(defn looks-like-identifier?
  "Check if input is a simple identifier (variable name)"
  [input]
  (re-matches #"^\s*[a-zA-Z_][a-zA-Z0-9_]*\s*$" input))

(defn eval-code
  ([ctx input]
   (eval-code ctx input "<repl>"))
  ([ctx input source-id]
  (let [exec-ctx* (atom ctx)]
    (try
    ;; Clear output from previous evaluation
    (reset! (:output ctx) [])
    (dbg/reset-run-state!)
	    (let [base-ctx (assoc ctx
	                          :debug-source source-id
	                          :debug-stack [{:class "<repl>"
	                                         :method "<top>"
	                                         :env (:current-env ctx)
	                                         :source source-id}])
          exec-ctx (if (dbg/enabled?)
                     (assoc base-ctx
                            :debug-hook (dbg/make-debug-hook {:read-line-fn read-line-safe
                                                              :wrap-expression-fn wrap-expression})
                            :debug-depth 0)
                     base-ctx)]
      (reset! exec-ctx* exec-ctx)

      ;; Determine if we need to wrap the input. In compiled REPL mode, give
      ;; top-level let/assignment inputs a raw parse + compiled-eligibility
      ;; attempt before falling back to wrapper-based execution.
      (let [compiled-raw-candidate? (and (= :compiled @*repl-backend*)
                                         (not (dbg/enabled?))
                                         (looks-like-raw-compiled-statement? input))
            raw-compiled-attempt (when compiled-raw-candidate?
                                   (try
                                     (let [raw-ast (p/ast input)]
                                       [raw-ast false false])
                                     (catch Exception _
                                       nil)))
            [ast was-wrapped? is-expression?]
            (or raw-compiled-attempt
                (let [code-to-parse (cond
                                      ;; Class definition - parse as is
                                      (looks-like-class? input)
                                      input

                                      ;; Statement that needs wrapping
                                      (looks-like-statement? input)
                                      (wrap-as-method input)

                                      ;; Try as a method call first, if it fails, wrap it
                                      :else
                                      input)]
                  (try
                    ;; Bare identifiers parse as methodCall but should
                    ;; evaluate as expressions (return their value)
                    (if (and (= code-to-parse input)
                             (looks-like-identifier? input))
                      [(p/ast (wrap-expression input)) true true]
                      (let [parsed (p/ast code-to-parse)]
                        [(if (= code-to-parse input)
                           (top-level-safe-call-when-expr parsed)
                           parsed)
                         (not= code-to-parse input) false]))
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
                        (throw e))))))]
        (sync-compiled-session-into-interpreter! exec-ctx)
        ;; Persist any type aliases declared in this input so later REPL lines
        ;; (and the type checker) can resolve them by name.
        (doseq [alias (:type-aliases ast)]
          (swap! *repl-type-aliases* assoc (:name alias) alias))
        ;; Type check if enabled
        (when (and @*type-checking-enabled*
                 (= (:type ast) :program)
                 (or (seq (:classes ast)) (seq (:functions ast))
                     (seq (:statements ast)) (seq (:calls ast))))
        ;; Create an augmented AST that includes previously defined classes
        ;; so the type checker knows about them
        (let [prev-functions (vals @(:function-asts @*compiled-repl-session*))
              synthetic-function-class-names (set (map :class-name prev-functions))
              referenced-anonymous-class-names (->> (vals @*repl-var-types*)
                                                    (filter string?)
                                                    (filter #(str/starts-with? % "AnonymousFunction_"))
                                                    set)
              prev-classes (remove #(or (= "__ReplTemp__" (:name %))
                                        (contains? synthetic-function-class-names (:name %))
                                        (and (str/starts-with? (str (:name %)) "AnonymousFunction_")
                                             (not (contains? referenced-anonymous-class-names (:name %)))))
                                   (vals @(:classes ctx)))
              intern-classes (interp/resolve-interned-classes source-id ast)
              prev-imports @(:imports ctx)
              augmented-ast (cond
                              (and (seq prev-classes) (seq prev-imports) (seq intern-classes))
                              (assoc ast
                                     :classes (concat prev-classes intern-classes (:classes ast))
                                     :imports (concat prev-imports (:imports ast)))

                              (and (seq prev-classes) (seq prev-imports))
                              (assoc ast
                                     :classes (concat prev-classes (:classes ast))
                                     :imports (concat prev-imports (:imports ast)))

                              (and (seq prev-classes) (seq intern-classes))
                              (assoc ast :classes (concat prev-classes intern-classes (:classes ast)))

                              (and (seq prev-imports) (seq intern-classes))
                              (assoc ast
                                     :classes (concat intern-classes (:classes ast))
                                     :imports (concat prev-imports (:imports ast)))

                              (seq intern-classes)
                              (assoc ast :classes (concat intern-classes (:classes ast)))

                              (seq prev-classes)
                              (assoc ast :classes (concat prev-classes (:classes ast)))

                              (seq prev-imports)
                              (assoc ast :imports (concat prev-imports (:imports ast)))

                              :else
                              ast)
              augmented-ast (if (seq prev-functions)
                              (update augmented-ast :functions #(vec (concat prev-functions %)))
                              augmented-ast)
              ;; Make every type alias declared so far in the session visible to
              ;; the checker, not just any declared in the current input.
              augmented-ast (assoc augmented-ast
                                   :type-aliases (vec (vals @*repl-type-aliases*)))
              ;; Previously defined classes/functions are included above only so the
              ;; type checker can resolve references from the *current* input. Their
              ;; bodies must not be re-validated: redefining one class can legitimately
              ;; invalidate an older, unrelated definition (e.g. a subclass that calls a
              ;; method this redefinition removed), and that stale code should not block
              ;; the new input. Skip body-checking for previously defined names that are
              ;; not part of the current input.
              current-class-names (set (concat (map :name (filter map? (:classes ast)))
                                               (keep :class-name (filter map? (:functions ast)))))
              skip-class-body-names (->> (concat (map :name prev-classes)
                                                 (keep :class-name prev-functions))
                                         (remove current-class-names)
                                         set)
              result (tc/type-check augmented-ast
                                    {:var-types @*repl-var-types*
                                     :skip-class-body-names skip-class-body-names})]
          (when-not (:success result)
            (doseq [error (:errors result)]
              (println (tc/format-type-error error)))
            (throw (ex-info "Type checking failed" {:errors (:errors result)})))))

        ;; Evaluate based on type
        (cond
        ;; Experimental compiled path for narrow expression-shaped program inputs.
        (and (= (:type ast) :program)
             (not was-wrapped?)
             (not (dbg/enabled?))
             (= :compiled @*repl-backend*)
             (not (ast-needs-interpreter-fallback? exec-ctx ast)))
        (if-let [{:keys [session result output]}
                 (try
                   (compiled-repl/compile-and-eval! @*compiled-repl-session*
                                                    ast
                                                    source-id)
                   (catch clojure.lang.ExceptionInfo e
                     (if (fallback-eligible-compiled-error? e)
                       nil
                       (throw e))))]
          (do
            (reset! *compiled-repl-session* session)
            (let [{:keys [var-types]} (compiled-repl/sync-session->interpreter! session exec-ctx)]
              (reset! *repl-var-types* var-types))
            (when (seq output)
              (doseq [line output]
                (println line)))
            (when (some? result)
              (if-let [type-str (and (empty? output)
                                     (infer-result-type exec-ctx (first (:statements ast))))]
                (println (str type-str " " (format-value result)))
                (println (format-value result))))
            exec-ctx)
          (let [classes (:classes ast)
                functions (:functions ast)
                interns (:interns ast)
                imports (:imports ast)
                statements (:statements ast)
                calls (:calls ast)
                real-class-names (filter #(not= % "__ReplTemp__")
                                         (map :name (filter map? classes)))
                function-names (map :name (filter map? functions))]
            (when (or (seq imports) (seq interns) (seq real-class-names) (seq function-names))
              (interp/eval-node exec-ctx ast)
              (when @*type-checking-enabled*
                (doseq [fn-def (filter map? functions)]
                  (swap! *repl-var-types* assoc (:name fn-def) (:class-name fn-def)))))
            (let [top-nodes (if (seq statements) statements calls)
                  result (when (and (seq top-nodes) (empty? real-class-names) (empty? function-names))
                           (last (map #(interp/eval-node exec-ctx %) top-nodes)))
                  output @(:output exec-ctx)]
              (when (seq output)
                (doseq [line output]
                  (println line)))
              (when (and (some? result) (empty? output) (empty? real-class-names) (empty? function-names))
                (if-let [type-str (when (seq calls)
                                    (infer-result-type exec-ctx (last calls)))]
                  (println (str type-str " " (format-value result)))
                  (println (format-value result)))))
            (sync-interpreter-back-into-compiled-session! exec-ctx ast source-id)
            exec-ctx))

        ;; If we wrapped the code, execute the temp method in GLOBAL context
        was-wrapped?
        (let [class-def (first (:classes ast))
              method-def (-> class-def :body first :members first)
              ;; Execute directly in the current context (preserves global vars)
              result (last (map #(interp/eval-node exec-ctx %) (:body method-def)))
              output @(:output exec-ctx)]
          ;; Persist variable types from let statements (for future type checking)
          (when @*type-checking-enabled*
            (doseq [stmt (:body method-def)]
              (when (and (map? stmt) (= (:type stmt) :let))
                (let [remembered-type (or (:var-type stmt)
                                          (tc/infer-expression-type
                                           (:value stmt)
                                           {:classes (vals @(:classes exec-ctx))
                                            :imports @(:imports exec-ctx)
                                            :var-types @*repl-var-types*}))]
                  (when remembered-type
                    (swap! *repl-var-types* assoc (:name stmt) remembered-type))))))
          ;; Infer type of the result expression when typechecking is on
          (let [type-str (when is-expression?
                           (infer-result-type exec-ctx (-> method-def :body first :args first)))]
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
          (sync-interpreter-back-into-compiled-session!
           exec-ctx
           {:type :program
            :imports []
            :interns []
            :classes []
            :functions []
            :statements (:body method-def)
            :calls []}
           source-id)
          exec-ctx)

        ;; If it's a program, handle it based on content
        (= (:type ast) :program)
        (let [classes (:classes ast)
              functions (:functions ast)
              interns (:interns ast)
              imports (:imports ast)
              statements (:statements ast)
              calls (:calls ast)
              real-class-names (filter #(not= % "__ReplTemp__")
                                      (map :name (filter map? classes)))
              function-names (map :name (filter map? functions))]
          ;; If there are imports/interns/classes/functions, evaluate the program
          ;; as a whole so registration side effects happen.
          (when (or (seq imports) (seq interns) (seq real-class-names) (seq function-names))
            (interp/eval-node exec-ctx ast)
            (when @*type-checking-enabled*
              (doseq [fn-def (filter map? functions)]
                (swap! *repl-var-types* assoc (:name fn-def) (:class-name fn-def)))))
          ;; If there are top-level statements and no classes/functions, evaluate them in order.
          ;; Fall back to legacy :calls-only programs when :statements is absent.
          (let [top-nodes (if (seq statements) statements calls)
                result (when (and (seq top-nodes) (empty? real-class-names) (empty? function-names))
                         (last (map #(interp/eval-node exec-ctx %) top-nodes)))
                output @(:output exec-ctx)]
            ;; Show any output
            (when (seq output)
              (doseq [line output]
                (println line)))
            ;; Show result if it's not nil, not from a print, and no classes were defined
            ;; Always show false/0 results too.
            (when (and (some? result) (empty? output) (empty? real-class-names) (empty? function-names))
              (if-let [type-str (when (seq calls)
                                  (infer-result-type exec-ctx (last calls)))]
                (println (str type-str " " (format-value result)))
                (println (format-value result)))))
          (sync-interpreter-back-into-compiled-session! exec-ctx ast source-id)
          exec-ctx)

        ;; Single expression or statement
        :else
        (let [result (interp/eval-node exec-ctx ast)
              output @(:output exec-ctx)]
          ;; Show output from print statements
          (when (seq output)
            (doseq [line output]
              (println line)))
          ;; Show result if it's not nil and not from a print
          ;; Always show false/0 results too.
          (when (and (some? result) (empty? output))
            (if-let [type-str (infer-result-type exec-ctx ast)]
              (println (str type-str " " (format-value result)))
              (println (format-value result))))
          (sync-interpreter-back-into-compiled-session!
           exec-ctx
           {:type :program
            :imports []
            :interns []
            :classes []
            :functions []
            :statements [ast]
            :calls []}
           source-id)
          exec-ctx))))

    (catch ParseError e
      (println "Syntax error:")
      ;; When input was pre-wrapped as a statement, errors reference the wrapper
      ;; code (offset by 3 lines). Adjust accordingly.
      (let [pre-wrapped? (looks-like-statement? input)
            line-offset (if pre-wrapped? 3 0)]
        (p/format-parse-errors e input line-offset))
      ctx)

    (catch clojure.lang.ExceptionInfo e
      (let [e (unwrap-user-visible-exception e)]
        (flush-compiled-output-on-error!)
        (println "Error:" (interp/nex-error-message e))
        (when-let [data (ex-data e)]
          (when (contains? data :line)
            (println "  at line" (:line data))))
        (dbg/maybe-break-on-error! @exec-ctx* e {:read-line-fn read-line-safe
                                                 :wrap-expression-fn wrap-expression})
        ctx))

    (catch Exception e
      (let [e (unwrap-user-visible-exception e)]
        (flush-compiled-output-on-error!)
        (dbg/maybe-break-on-error! @exec-ctx* e {:read-line-fn read-line-safe
                                                 :wrap-expression-fn wrap-expression})
        (println "Error:" (interp/nex-error-message e))
        ctx))

    ;; Final safety net: the REPL must survive any evaluation failure, including
    ;; JVM `Error`s that are not `Exception`s — e.g. a `ClassFormatError` from
    ;; bytecode generation, a `StackOverflowError` from runaway recursion, or an
    ;; `AssertionError`. Report it and keep the session alive rather than letting
    ;; it unwind and kill the loop. (Read-side control flow — EOF / Ctrl-C — is
    ;; handled separately in `read-input`, so it is unaffected.)
    (catch Throwable e
      (let [e (unwrap-user-visible-exception e)]
        (flush-compiled-output-on-error!)
        (println "Internal error:" (interp/nex-error-message e))
        ctx))))))

;;
;; Main REPL Loop
;;

(defn show-banner []
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║                   NEX REPL v0.1.1                          ║")
  (println "║     A high-level language for design and implementation    ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println)
  (println "Default backend: COMPILED (unsupported inputs fall back to the interpreter)")
  (println "Use :backend interpreter for the tree-walking fallback/escape hatch")
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
            ;; Non-empty input - process it. `eval-code` guards its own
            ;; evaluation, but wrap the whole step in a final Throwable net so a
            ;; failure in a command handler (`handle-command`) or anywhere else
            ;; cannot unwind and kill the session; on error, keep the prior ctx.
            (let [new-ctx (try
                            (if (str/starts-with? trimmed ":")
                              (handle-command ctx trimmed)
                              (eval-code ctx trimmed))
                            (catch Throwable e
                              (println "Internal error:"
                                       (interp/nex-error-message
                                        (unwrap-user-visible-exception e)))
                              ctx))]
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
