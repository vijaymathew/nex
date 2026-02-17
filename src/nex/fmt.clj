(ns nex.fmt
  "Nex code formatter that follows standard indentation conventions"
  (:require [clojure.string :as str]
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
          params (:type-params type-expr)]
      (if params
        (str base " [" (str/join ", " (map format-type params)) "]")
        base))
    :else (str type-expr)))

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
      :real (str (:value expr))
      :string (str "\"" (:value expr) "\"")
      :char (str "'" (:value expr) "'")
      :boolean (str (:value expr))
      :identifier (:name expr)
      :old (str "old " (format-expression (:expr expr)))
      :binary (str (format-expression (:left expr))
                   " " (:operator expr) " "
                   (format-expression (:right expr)))
      :unary (str (:operator expr) (format-expression (:operand expr)))
      :call (if (:target expr)
              (str (format-expression (:target expr)) "." (:method expr)
                   (when (seq (:args expr))
                     (str "(" (str/join ", " (map format-expression (:args expr))) ")")))
              (str (:method expr) "(" (str/join ", " (map format-expression (:args expr))) ")"))
      :create (str "create " (:class-name expr) "." (:constructor expr)
                   "(" (str/join ", " (map format-expression (:args expr))) ")")
      :array-literal (str "[" (str/join ", " (map format-expression (:elements expr))) "]")
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
        :scoped-block (str/join "\n"
                                [(str ind "do")
                                 (str/join "\n" (map #(format-statement % (inc level)) (:body stmt)))
                                 (str ind "end")])
        ;; Default: return statement as-is
        (str ind stmt))
      :else (str ind stmt))))

(defn format-param
  "Format a parameter"
  [{:keys [name names type]}]
  ;; Methods and constructors now use same structure (individual params)
  (if name
    (str name ": " (format-type type))
    (str (str/join ", " names) ": " (format-type type))))

(defn format-assertion
  "Format an assertion"
  [{:keys [label expr]} level]
  (str (indent level) label ": " (format-expression expr)))

(defn format-method
  "Format a method declaration"
  [{:keys [name params return-type note require body ensure]} level]
  (let [ind (indent level)
        ;; Methods without params don't have parentheses, with params they do
        param-str (cond
                    (nil? params) "()"
                    (empty? params) "()"
                    :else (str "(" (str/join ", " (map format-param params)) ")"))
        ;; If there are contracts or notes, do goes on separate line; otherwise same line
        has-contracts-or-note? (or note require ensure)
        signature (str ind name param-str
                       (when return-type (str ": " (format-type return-type))))
        note-line (when note
                    (str (indent (inc level)) "note \"" note "\""))
        do-line (if has-contracts-or-note?
                  (str (indent (inc level)) "do")
                  (str signature " do"))
        first-line (if has-contracts-or-note? signature do-line)]
    (str/join "\n"
              (remove empty?
                      [first-line
                       note-line
                       (when require
                         (str (indent (inc level)) "require\n"
                              (str/join "\n" (map #(format-assertion % (+ level 2)) require))))
                       (when has-contracts-or-note?
                         (str (indent (inc level)) "do"))
                       (str/join "\n" (map #(format-statement % (+ level 2)) body))
                       (when ensure
                         (str (indent (inc level)) "ensure\n"
                              (str/join "\n" (map #(format-assertion % (+ level 2)) ensure))))
                       (str ind "end")]))))

(defn format-field
  "Format a field declaration"
  [{:keys [name field-type note]} level]
  (str (indent level) name ": " (format-type field-type)
       (when note (str " note \"" note "\""))))

(defn format-constructor
  "Format a constructor declaration"
  [{:keys [name params require body ensure]} level]
  (let [ind (indent level)
        ;; Use same param formatting as methods
        param-str (if (seq params)
                    (str/join ", " (map format-param params))
                    "")
        ;; If there are contracts, do goes on separate line; otherwise same line
        has-contracts? (or require ensure)
        signature (str ind name "(" param-str ")")
        do-line (if has-contracts?
                  (str (indent (inc level)) "do")
                  (str signature " do"))
        first-line (if has-contracts? signature do-line)]
    (str/join "\n"
              (remove empty?
                      [first-line
                       (when require
                         (str (indent (inc level)) "require\n"
                              (str/join "\n" (map #(format-assertion % (+ level 2)) require))))
                       (when has-contracts?
                         (str (indent (inc level)) "do"))
                       (str/join "\n" (map #(format-statement % (+ level 2)) body))
                       (when ensure
                         (str (indent (inc level)) "ensure\n"
                              (str/join "\n" (map #(format-assertion % (+ level 2)) ensure))))
                       (str ind "end")]))))

(defn format-feature-section
  "Format a feature section"
  [{:keys [visibility members]} level]
  (let [vis-str (format-visibility visibility)]
    (str/join "\n"
              [(str vis-str "feature")
               (str/join "\n"
                         (map (fn [member]
                                (case (:type member)
                                  :field (format-field member (inc level))
                                  :method (format-method member (inc level))
                                  (str (indent (inc level)) member)))
                              members))])))

(defn format-constructor-section
  "Format a constructor section"
  [{:keys [constructors]} level]
  (str/join "\n"
            ["constructors"
             (str/join "\n\n" (map #(format-constructor % (inc level)) constructors))]))

(defn format-generic-param
  "Format a generic parameter"
  [{:keys [name constraint]}]
  (if constraint
    (str name " -> " constraint)
    name))

(defn format-rename-mapping
  "Format a rename mapping"
  [{:keys [from to]} level]
  (str (indent (inc level)) from " as " to))

(defn format-inherit-entry
  "Format an inherit entry"
  [{:keys [parent rename redefine]} level]
  (let [ind (indent (inc level))]
    (str/join "\n"
              (remove empty?
                      [(str ind parent)
                       (when rename
                         (str (indent (+ level 2)) "rename\n"
                              (str/join "\n" (map #(format-rename-mapping % (+ level 2)) rename))))
                       (when redefine
                         (str (indent (+ level 2)) "redefine\n"
                              (str/join "\n" (map #(str (indent (+ level 3)) %) redefine))))
                       (str ind "end")]))))

(defn format-class
  "Format a class declaration"
  [{:keys [name generic-params note parents body invariant]}]
  (let [generic-str (when generic-params
                      (str " [" (str/join ", " (map format-generic-param generic-params)) "]"))
        note-str (when note
                   (str "\n  note \"" note "\""))
        parent-str (when parents
                     (str "\ninherit\n"
                          (str/join "\n\n" (map #(format-inherit-entry % 0) parents))))]
    (str/join "\n"
              (remove empty?
                      [(str "class " name generic-str note-str)
                       parent-str
                       (str/join "\n\n"
                                 (map (fn [section]
                                        (case (:type section)
                                          :feature-section (format-feature-section section 0)
                                          :constructors (format-constructor-section section 0)
                                          (str section)))
                                      body))
                       (when invariant
                         (str "invariant\n"
                              (str/join "\n" (map #(format-assertion % 1) invariant))))
                       "end"]))))

(defn format-import
  "Format an import statement"
  [{:keys [qualified-name source]}]
  (if source
    (str "import " qualified-name " from " source)
    (str "import " qualified-name)))

(defn format-intern
  "Format an intern statement"
  [{:keys [path class-name alias]}]
  (let [full-path (if path
                    (str path "/" class-name)
                    class-name)]
    (if alias
      (str "intern " full-path " as " alias)
      (str "intern " full-path))))

(defn format-program
  "Format a complete program"
  [{:keys [imports interns classes calls]}]
  (str/join "\n\n"
            (remove empty?
                    [(when (seq imports)
                       (str/join "\n" (map format-import imports)))
                     (when (seq interns)
                       (str/join "\n" (map format-intern interns)))
                     (when (seq classes)
                       (str/join "\n\n" (map format-class classes)))
                     (when (seq calls)
                       (str/join "\n" (map #(format-expression %) calls)))])))

(defn format-code
  "Format Nex code from string"
  [code]
  (try
    (let [ast (p/ast code)]
      (format-program ast))
    (catch Exception e
      (throw (ex-info "Failed to format code"
                      {:error (.getMessage e)
                       :cause e})))))

(defn format-file
  "Format a Nex file"
  [file-path]
  (let [code (slurp file-path)]
    (format-code code)))

(defn format-file-in-place
  "Format a Nex file and write back to the same file"
  [file-path]
  (let [formatted (format-file file-path)]
    (spit file-path formatted)
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
