(ns nex.repl
  "Interactive REPL for the Nex programming language"
  (:require [nex.parser :as p]
            [nex.interpreter :as interp]
            [clojure.string :as str])
  (:gen-class))

;;
;; REPL State
;;

(defonce ^:dynamic *repl-context* nil)

(defn init-repl-context
  "Initialize or reset the REPL context"
  []
  (interp/make-context))

;;
;; Input Reading
;;

(defn prompt [text]
  (print text)
  (flush))

(defn read-line-safe
  "Read a line, returning nil on EOF"
  []
  (try
    (read-line)
    (catch Exception _
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
        end-count (count (re-seq #"\bend\b" text))
        ;; In loops, 'do' is part of 'from...until...do...end', not a separate block
        ;; So subtract 'from-count' from 'do-count' to avoid double-counting
        standalone-do-count (max 0 (- do-count from-count))
        ;; Total blocks that need closing
        open-blocks (+ class-count standalone-do-count from-count if-count)]
    ;; Continue if we have more opens than closes
    (> open-blocks end-count)))

(defn read-input
  "Read potentially multi-line input from the user"
  []
  (prompt "nex> ")
  (when-let [first-line (read-line-safe)]
    (if (str/starts-with? first-line ":")
      ;; REPL command - single line
      first-line
      ;; Code input - may be multi-line
      (loop [lines [first-line]]
        (if (continue-reading? lines)
          (do
            (prompt "...  ")
            (if-let [next-line (read-line-safe)]
              (recur (conj lines next-line))
              ;; EOF during multi-line - return what we have
              (str/join "\n" lines)))
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
  (println)
  (println "Note: Empty lines are ignored. Press Enter on empty prompt to continue.")
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

(defn handle-command [ctx input]
  (case (str/lower-case input)
    (":help" ":h" ":?")
    (do (show-help) ctx)

    (":quit" ":q" ":exit")
    :quit

    (":clear" ":reset")
    (do
      (println "Context cleared.")
      (init-repl-context))

    ":classes"
    (do (show-classes ctx) ctx)

    ":vars"
    (do (show-vars ctx) ctx)

    ;; Unknown command
    (do
      (println (str "Unknown command: " input))
      (println "Type :help for available commands")
      ctx)))

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

(defn format-value
  "Format a value for REPL display"
  [value]
  (cond
    ;; Nex objects
    (instance? nex.interpreter.NexObject value)
    (str "#<" (:class-name value) " object>")

    ;; Strings - show without quotes for direct display
    (string? value)
    value

    ;; Numbers
    (number? value)
    (str value)

    ;; Booleans
    (boolean? value)
    (str value)

    ;; Nil
    (nil? value)
    "nil"

    ;; Collections
    (coll? value)
    (pr-str value)

    ;; Everything else
    :else
    (pr-str value)))

(defn looks-like-class?
  "Check if input looks like a class definition"
  [input]
  (re-find #"^\s*class\s+" input))

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
                                              [(p/ast code-to-parse) (not= code-to-parse input) false]
                                              (catch Exception e
                                                ;; If parsing failed and we haven't wrapped yet, try wrapping
                                                (if (= code-to-parse input)
                                                  (cond
                                                    ;; Check if it's a bare identifier - wrap in print
                                                    (looks-like-identifier? input)
                                                    [(p/ast (wrap-expression input)) true true]

                                                    ;; Try wrapping as expression
                                                    :else
                                                    (try
                                                      [(p/ast (wrap-expression input)) true true]
                                                      (catch Exception e2
                                                        ;; If expression wrapping fails, try as statement
                                                        [(p/ast (wrap-as-method input)) true false])))
                                                  (throw e))))]

      ;; Evaluate based on type
      (cond
        ;; If we wrapped the code, execute the temp method in GLOBAL context
        was-wrapped?
        (let [class-def (first (:classes ast))
              method-def (-> class-def :body first :members first)
              ;; Execute directly in the current context (preserves global vars)
              result (last (map #(interp/eval-node ctx %) (:body method-def)))
              output @(:output ctx)]
          ;; Show output from print statements
          (when (seq output)
            (doseq [line output]
              (println line)))
          ;; Show result if it's not nil and not from a print
          (when (and result (empty? output))
            (println (format-value result)))
          ;; Don't say "Class registered" for wrapped code
          ctx)

        ;; If it's a program, handle it based on content
        (= (:type ast) :program)
        (let [classes (:classes ast)
              calls (:calls ast)
              real-class-names (filter #(not= % "__ReplTemp__")
                                      (map :name (filter map? classes)))]
          ;; Evaluate the program
          (interp/eval-node ctx ast)
          ;; If there are calls/expressions and no classes, evaluate them to get result
          (let [result (when (and (seq calls) (empty? real-class-names))
                        (last (map #(interp/eval-node ctx %) calls)))
                output @(:output ctx)]
            ;; Show any output
            (when (seq output)
              (doseq [line output]
                (println line)))
            ;; Show result if it's not nil, not from a print, and no classes were defined
            (when (and result (empty? output) (empty? real-class-names))
              (println (format-value result)))
            ;; Only show "Class registered" message if there are real classes
            (when (and (seq real-class-names) (not (every? str/blank? real-class-names)))
              (println "Class(es) registered:" (str/join ", " real-class-names))))
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
          (when (and result (empty? output))
            (println (format-value result)))
          ctx)))

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
  (loop [ctx (init-repl-context)]
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
              (recur new-ctx)))))
      ;; Got nil (EOF/Ctrl+D) - exit gracefully
      nil)))

(defn start-repl
  "Start the Nex REPL"
  []
  (show-banner)
  (repl-loop)
  (println "\nGoodbye!"))

(defn -main
  "Entry point for standalone REPL"
  [& args]
  (start-repl)
  (System/exit 0))
