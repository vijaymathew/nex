(ns nex.repl
  "Interactive REPL for the Nex programming language"
  (:require [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.repl :as compiled-repl]
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
           [clj_antlr ParseError])
  (:gen-class))

;;
;; REPL State
;;

(defonce ^:dynamic *repl-context* nil)
(defonce ^:dynamic *type-checking-enabled* (atom false))
(defonce ^:dynamic *repl-var-types* (atom {}))
(defonce ^:dynamic *repl-backend* (atom :compiled))
(defonce ^:dynamic *compiled-repl-session* (atom (compiled-repl/make-session)))

(def nex-keywords
  ["class" "deferred" "feature" "inherit" "end" "do" "if" "then" "else" "elseif"
   "when" "from" "until" "invariant" "variant" "require" "ensure"
   "let" "create" "convert" "to" "fn" "function" "and" "or" "old" "this" "note"
   "with" "import" "intern" "private" "raise" "rescue" "retry" "spawn" "select" "timeout" "repeat" "across" "case" "of"
   "true" "false" "nil"
   ;; strictly 'result' is not a keyword, but a pre-defined variable name.
   "result"])

(def nex-types
  ["Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
   "Array" "Map" "Set" "Task" "Channel" "Function" "Cursor" "Console" "Process"])

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

                    :else
                    (do (.append out ch)
                        (recur (inc i) false false out))))))))
        text (->> lines
                  (map sanitize-line)
                  (str/join "\n"))
        ;; Count keyword pairs that need to be closed
        class-count (count (re-seq #"\bclass\b" text))
        feature-count (count (re-seq #"\bfeature\b" text))
        do-count (count (re-seq #"\bdo\b" text))
        from-count (count (re-seq #"\bfrom\b" text))
        repeat-count (count (re-seq #"\brepeat\b" text))
        across-count (count (re-seq #"\bacross\b" text))
        if-count (count (re-seq #"\bif\b" text))
        case-count (count (re-seq #"\bcase\b" text))
        end-count (count (re-seq #"\bend\b" text))
        ;; In loops, 'do' is part of 'from...until...do...end', 'repeat...do...end', or 'across...do...end'
        ;; So subtract from-count, repeat-count, and across-count from do-count to avoid double-counting
        standalone-do-count (max 0 (- do-count from-count repeat-count across-count))
        ;; Total blocks that need closing
        open-blocks (+ class-count standalone-do-count from-count repeat-count across-count if-count case-count)]
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
        (reset! *compiled-repl-session* (compiled-repl/reset-session))
        (dbg/reset-run-state!)
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

      (= input-lower ":backend interpreter")
      (do
        (sync-compiled-session-into-interpreter! ctx)
        (reset! *repl-backend* :interpreter)
        (println "REPL backend set to INTERPRETER.")
        ctx)

      (= input-lower ":backend compiled")
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
        ctx)

      (or (= input-lower ":backend") (= input-lower ":backend status"))
      (do
        (println (str "REPL backend is currently: " (-> @*repl-backend* name str/upper-case)))
        ctx)

      ;; Load file command
      (str/starts-with? input-lower ":load")
      (let [path (subs input (min (count input) (count ":load")))]
        (load-file-into-repl ctx path))

      ;; Debugger commands
      (= input-lower ":debug on")
      (do
        (dbg/set-enabled! true)
        (println "Debugger enabled.")
        ctx)

      (= input-lower ":debug off")
      (do
        (dbg/set-enabled! false)
        (println "Debugger disabled.")
        ctx)

      (or (= input-lower ":debug") (= input-lower ":debug status"))
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
        ctx)

      (str/starts-with? input-lower ":break ")
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
            ctx)))

      (str/starts-with? input-lower ":tbreak ")
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
            ctx)))

      (= input-lower ":breaks")
      (do
        (if (seq (dbg/breakpoint-entries))
          (do
            (println "Breakpoints:")
            (doseq [entry (dbg/breakpoint-entries)]
              (println (str "  " (dbg/breakpoint-entry->str entry)))))
          (println "No breakpoints set."))
        ctx)

      (str/starts-with? input-lower ":clearbreak")
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
                ctx)))))

      (str/starts-with? input-lower ":enable ")
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
              ctx))))

      (str/starts-with? input-lower ":disable ")
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
              ctx))))

      (str/starts-with? input-lower ":watch ")
      (let [parsed (dbg/parse-watch-command (subs input (count ":watch ")))]
        (if-not parsed
          (do
            (println "Usage: :watch <expr> [if <expr>]")
            ctx)
          (let [id (dbg/add-watchpoint! parsed)]
            (println (str "Added watchpoint [" id "] " (:expr parsed)
                          (when-let [cond-expr (:condition parsed)]
                            (str " if " cond-expr))))
            ctx)))

      (= input-lower ":watches")
      (do
        (if (seq (dbg/watchpoint-entries))
          (do
            (println "Watchpoints:")
            (doseq [entry (dbg/watchpoint-entries)]
              (println (str "  " (dbg/watchpoint-entry->str entry)))))
          (println "No watchpoints set."))
        ctx)

      (str/starts-with? input-lower ":clearwatch")
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
              ctx))))

      (str/starts-with? input-lower ":enablewatch ")
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
              ctx))))

      (str/starts-with? input-lower ":disablewatch ")
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
              ctx))))

      (str/starts-with? input-lower ":ignore ")
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
            ctx)))

      (str/starts-with? input-lower ":every ")
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
            ctx)))

      (or (= input-lower ":breakon") (= input-lower ":breakon status"))
      (let [{:keys [exception contract]} (dbg/break-on-status)
            filters (dbg/break-on-filters)
            exf (:exception filters)
            cf (:contract filters)]
        (println (str "break-on exception: " (if exception "on" "off")))
        (println (str "  exception filter: " (or exf "none")))
        (println (str "break-on contract: " (if contract "on" "off")))
        (println (str "  contract filter: " (or cf "none")))
        ctx)

      (str/starts-with? input-lower ":breakon ")
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
            ctx)))

      (str/starts-with? input-lower ":breaksave ")
      (let [path (normalize-load-path (subs input (count ":breaksave ")))
            file (resolve-user-path path)]
        (if (str/blank? path)
          (do
            (println "Usage: :breaksave <path>")
            ctx)
          (do
            (spit file (pr-str (dbg/snapshot-breakpoints-and-watchpoints)))
            (println (str "Saved breakpoints/watchpoints to " (.getPath file)))
            ctx)))

      (str/starts-with? input-lower ":breakload ")
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
              ctx))))

      (or (= input-lower ":debugscript") (= input-lower ":debugscript status"))
      (do
        (println (str "debugscript: " (if (dbg/debug-script-active?) "loaded" "off")))
        ctx)

      (str/starts-with? input-lower ":debugscript ")
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
                ctx)))))

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
      (when-let [t (tc/infer-expression-type
                     expr-node
                     {:classes (vals @(:classes ctx))
                      :imports @(:imports ctx)
                      :var-types @*repl-var-types*})]
        (format-type t))
      (catch Exception _
        nil))))

(def ordered-comparison-ops
  #{"<" "<=" ">" ">="})

(def builtin-sortable-types
  #{"Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"})

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
          target-type (try (tc/infer-expression-type (:target node) env)
                           (catch Exception _ nil))
          type-params (when (map? target-type)
                        (normalized-type-params target-type))
          element-type (first type-params)]
      (and (= "Array" (:base-type target-type))
           (string? element-type)
           (not (contains? builtin-sortable-types element-type))))))

(defn- string-ordered-comparison?
  [ctx node]
  (when (and (map? node)
             (= :binary-op (:type node))
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
          (= "String" right-type)))))

(defn- ast-needs-interpreter-fallback?
  [ctx ast]
  (let [top-nodes (concat (:statements ast) (:calls ast))
        nodes (tree-seq coll? seq top-nodes)]
    (boolean
     (some (fn [node]
             (or (string-ordered-comparison? ctx node)
                 (nonbuiltin-array-sort? ctx node)))
           nodes))))

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

(defn- sync-interpreter-back-into-compiled-session!
  [ctx ast source-id]
  (when (= :compiled @*repl-backend*)
    (reset! *compiled-repl-session*
            (compiled-repl/sync-interpreter->session!
             @*compiled-repl-session*
             ctx
             @*repl-var-types*
             ast
             source-id))))

(defn looks-like-class?
  "Check if input looks like a top-level declaration"
  [input]
  (or (re-find #"^\s*class\s+" input)
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

(defn looks-like-expression?
  "Check if input looks like a simple expression (not a statement)"
  [input]
  (not (or (looks-like-statement? input)
           (looks-like-class? input)
           (re-find #"^\s*print\(" input)
           (re-find #"^\s*create\s+" input))))

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
                                       (when (compiled-repl/eligible-ast?
                                              @*compiled-repl-session*
                                              raw-ast)
                                         [raw-ast false false]))
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
                        (throw e))))))]
        (sync-compiled-session-into-interpreter! exec-ctx)
        ;; Type check if enabled
        (when (and @*type-checking-enabled*
                 (= (:type ast) :program)
                 (or (seq (:classes ast)) (seq (:functions ast))
                     (seq (:statements ast)) (seq (:calls ast))))
        ;; Create an augmented AST that includes previously defined classes
        ;; so the type checker knows about them
        (let [prev-classes (remove #(= "__ReplTemp__" (:name %))
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
              result (tc/type-check augmented-ast {:var-types @*repl-var-types*})]
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
        (format-parse-errors e input line-offset))
      ctx)

    (catch clojure.lang.ExceptionInfo e
      (println "Error:" (.getMessage e))
      (when-let [data (ex-data e)]
        (when (contains? data :line)
          (println "  at line" (:line data))))
      (dbg/maybe-break-on-error! @exec-ctx* e {:read-line-fn read-line-safe
                                               :wrap-expression-fn wrap-expression})
      ctx)

    (catch Exception e
      (dbg/maybe-break-on-error! @exec-ctx* e {:read-line-fn read-line-safe
                                               :wrap-expression-fn wrap-expression})
      (println "Error:" (.getMessage e))
      ctx)))))

;;
;; Main REPL Loop
;;

(defn show-banner []
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║                   NEX REPL v0.1.0                          ║")
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
