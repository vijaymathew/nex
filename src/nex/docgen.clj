(ns nex.docgen
  "Generate Markdown documentation from Nex source code"
  (:require [nex.parser :as p]
            [clojure.string :as str])
  (:gen-class))

(defn escape-markdown
  "Escape special Markdown characters"
  [text]
  (when text
    (-> text
        (str/replace #"\|" "\\|")
        (str/replace #"\*" "\\*")
        (str/replace #"_" "\\_")
        (str/replace #"\[" "\\[")
        (str/replace #"\]" "\\]"))))

(defn format-type
  "Format a type for display"
  [type-expr]
  (cond
    (nil? type-expr) "Void"
    (string? type-expr) type-expr
    (map? type-expr)
    (let [base (:base-type type-expr)
          params (:type-params type-expr)
          core (if params
                 (str base " [" (str/join ", " (map format-type params)) "]")
                 base)]
      (if (:detachable type-expr) (str "?" core) core))
    :else (str type-expr)))

(defn format-expression
  "Format an expression for display in documentation"
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
      :set-literal (str "#{" (str/join ", " (map format-expression (:elements expr))) "}")
      :map-literal (str "{" (str/join ", " (map (fn [{:keys [key value]}]
                                                   (str (format-expression key) ": "
                                                        (format-expression value)))
                                                 (:entries expr))) "}")
      ;; Default
      (pr-str expr))
    :else (str expr)))

(defn format-assertion
  "Format an assertion for display"
  [{:keys [label condition]}]
  (str "- **" label "**: `" (format-expression condition) "`"))

(defn format-visibility
  "Format visibility modifier"
  [visibility]
  (case (:type visibility)
    :private "🔒 Private"
    :selective (str "🔑 Visible to: " (str/join ", " (:classes visibility)))
    :public "🌐 Public"
    "🌐 Public"))

(defn format-parameter
  "Format a parameter"
  [{:keys [name type]}]
  (str "`" name ": " (format-type type) "`"))

(defn format-field
  "Generate documentation for a field"
  [{:keys [name field-type note visibility]}]
  (let [vis (format-visibility visibility)
        doc-note (when note (str "\n  " note))]
    (str "- **" name "**: `" (format-type field-type) "` " vis doc-note)))

(defn format-method
  "Generate documentation for a method"
  [{:keys [name params return-type note require ensure visibility]}]
  (let [param-list (if (seq params)
                     (str "(" (str/join ", " (map format-parameter params)) ")")
                     "()")
        return-str (if return-type
                     (str ": `" (format-type return-type) "`")
                     "")
        vis (format-visibility visibility)]
    (str/join "\n"
              (remove empty?
                      [(str "### " name param-list return-str)
                       ""
                       vis
                       ""
                       (when note
                         (str note "\n"))
                       (when (seq params)
                         (str "**Parameters:**\n"
                              (str/join "\n" (map (fn [{:keys [name type]}]
                                                   (str "- `" name "`: `" (format-type type) "`"))
                                                 params))
                              "\n"))
                       (when return-type
                         (str "**Returns:** `" (format-type return-type) "`\n"))
                       (when (seq require)
                         (str "**Pre-conditions:**\n"
                              (str/join "\n" (map format-assertion require))
                              "\n"))
                       (when (seq ensure)
                         (str "**Post-conditions:**\n"
                              (str/join "\n" (map format-assertion ensure))
                              "\n"))]))))

(defn format-constructor
  "Generate documentation for a constructor"
  [{:keys [name params require ensure]}]
  (let [param-list (if (seq params)
                     (str "(" (str/join ", " (map format-parameter params)) ")")
                     "()")]
    (str/join "\n"
              (remove empty?
                      [(str "### " name param-list)
                       ""
                       (when (seq params)
                         (str "**Parameters:**\n"
                              (str/join "\n" (map (fn [{:keys [name type]}]
                                                   (str "- `" name "`: `" (format-type type) "`"))
                                                 params))
                              "\n"))
                       (when (seq require)
                         (str "**Pre-conditions:**\n"
                              (str/join "\n" (map format-assertion require))
                              "\n"))
                       (when (seq ensure)
                         (str "**Post-conditions:**\n"
                              (str/join "\n" (map format-assertion ensure))
                              "\n"))]))))

(defn extract-members
  "Extract fields, methods, and constructors from class body with visibility"
  [body]
  (let [feature-sections (filter #(= :feature-section (:type %)) body)
        constructor-sections (filter #(= :constructors (:type %)) body)
        ;; Extract members and tag them with their section's visibility
        members-with-visibility (mapcat (fn [section]
                                         (let [visibility (:visibility section)]
                                           (map #(assoc % :visibility visibility)
                                                (:members section))))
                                       feature-sections)
        fields (filter #(= :field (:type %)) members-with-visibility)
        methods (filter #(= :method (:type %)) members-with-visibility)
        constructors (mapcat :constructors constructor-sections)]
    {:fields fields
     :methods methods
     :constructors constructors}))

(defn group-by-visibility
  "Group members by their visibility"
  [members]
  (group-by (fn [member]
              (let [vis (:visibility member)]
                (case (:type vis)
                  :private :private
                  :selective :selective
                  :public)))
            members))

(defn format-generic-params
  "Format generic parameters"
  [generic-params]
  (when (seq generic-params)
    (str " ["
         (str/join ", " (map (fn [{:keys [name constraint detachable]}]
                              (let [prefix (if detachable "?" "")]
                              (if constraint
                                (str prefix name " -> " constraint)
                                (str prefix name))))
                            generic-params))
         "]")))

(defn format-inheritance
  "Format inheritance information"
  [parents]
  (when (seq parents)
    (str "\n\n**Inherits from:** "
         (str/join ", " (map (fn [{:keys [parent]}]
                              (str "`" parent "`"))
                            parents))
         "\n")))

(defn generate-class-doc
  "Generate Markdown documentation for a single class"
  [{:keys [name generic-params note parents body invariant]}]
  (let [generic-str (format-generic-params generic-params)
        {:keys [fields methods constructors]} (extract-members body)
        public-fields (filter #(= :public (-> % :visibility :type)) fields)
        private-fields (filter #(= :private (-> % :visibility :type)) fields)
        selective-fields (filter #(= :selective (-> % :visibility :type)) fields)
        public-methods (filter #(= :public (-> % :visibility :type)) methods)
        private-methods (filter #(= :private (-> % :visibility :type)) methods)
        selective-methods (filter #(= :selective (-> % :visibility :type)) methods)]
    (str/join "\n"
              (remove empty?
                      [(str "# Class: " name generic-str)
                       ""
                       (when note
                         (str note "\n"))
                       (format-inheritance parents)
                       (when (seq invariant)
                         (str "## Class Invariants\n\n"
                              (str/join "\n" (map format-assertion invariant))
                              "\n"))
                       (when (seq fields)
                         (str "## Fields\n"))
                       (when (seq public-fields)
                         (str "\n### Public Fields\n\n"
                              (str/join "\n" (map format-field public-fields))
                              "\n"))
                       (when (seq private-fields)
                         (str "\n### Private Fields\n\n"
                              (str/join "\n" (map format-field private-fields))
                              "\n"))
                       (when (seq selective-fields)
                         (str "\n### Selectively Visible Fields\n\n"
                              (str/join "\n" (map format-field selective-fields))
                              "\n"))
                       (when (seq constructors)
                         (str "\n## Constructors\n\n"
                              (str/join "\n\n" (map format-constructor constructors))
                              "\n"))
                       (when (seq methods)
                         (str "\n## Methods\n"))
                       (when (seq public-methods)
                         (str "\n### Public Methods\n\n"
                              (str/join "\n\n" (map format-method public-methods))
                              "\n"))
                       (when (seq private-methods)
                         (str "\n### Private Methods\n\n"
                              (str/join "\n\n" (map format-method private-methods))
                              "\n"))
                       (when (seq selective-methods)
                         (str "\n### Selectively Visible Methods\n\n"
                              (str/join "\n\n" (map format-method selective-methods))
                              "\n"))]))))

(defn generate-file-doc
  "Generate Markdown documentation for a Nex source file"
  [source-code]
  (let [ast (p/ast source-code)
        classes (:classes ast)
        imports (:imports ast)
        interns (:interns ast)]
    (str/join "\n\n"
              (remove empty?
                      [(when (seq imports)
                         (str "## Imports\n\n"
                              (str/join "\n" (map (fn [{:keys [qualified-name source]}]
                                                   (str "- `" qualified-name "`"
                                                        (when source (str " from " source))))
                                                 imports))))
                       (when (seq interns)
                         (str "## Interns\n\n"
                              (str/join "\n" (map (fn [{:keys [path class-name alias]}]
                                                   (str "- `" (if path
                                                               (str path "/" class-name)
                                                               class-name) "`"
                                                        (when alias (str " as `" alias "`"))))
                                                 interns))))
                       (str/join "\n\n---\n\n" (map generate-class-doc classes))]))))

(defn generate-doc-from-file
  "Generate documentation from a Nex source file"
  [file-path]
  (let [source (slurp file-path)]
    (generate-file-doc source)))

(defn save-doc-to-file
  "Generate documentation and save to a Markdown file"
  [source-file output-file]
  (let [doc (generate-doc-from-file source-file)]
    (spit output-file doc)
    (println "Documentation generated:" output-file)))

(defn -main
  "Main entry point for the documentation generator"
  [& args]
  (if (empty? args)
    (do
      (println "Nex Documentation Generator")
      (println "Usage: clojure -M:docgen <source.nex> [output.md]")
      (println "")
      (println "If output file is not specified, prints to stdout")
      (System/exit 1))
    (let [source-file (first args)
          output-file (second args)]
      (try
        (if output-file
          (save-doc-to-file source-file output-file)
          (println (generate-doc-from-file source-file)))
        (catch Exception e
          (println "Error generating documentation:" (.getMessage e))
          (System/exit 1))))))
