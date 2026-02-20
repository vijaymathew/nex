(ns nex.generator.javascript
  "Translates Nex (Eiffel-based) code to JavaScript (ES6+)"
  (:require [nex.parser :as p]
            [nex.typechecker :as tc]
            [clojure.string :as str]
            [clojure.set :as set]))

(def ^:dynamic *function-names* #{})
(def ^:dynamic *this-name* "this")

(defn class-name-to-local
  "Convert a class name to a local variable name (e.g., 'Point' -> 'point')"
  [class-name]
  (str (Character/toLowerCase ^char (first class-name)) (subs class-name 1)))

;;
;; Type Mapping
;;

(defn nex-type-to-js
  "Convert Nex type to JavaScript type name (for JSDoc comments)"
  [nex-type]
  (cond
    ;; Handle parameterized types like {:base-type "List" :type-args ["Cat"]}
    (map? nex-type)
    (let [base-type (:base-type nex-type)
          ;; Convert Array/Map to JavaScript equivalents
          js-base (case base-type
                    "Array" "Array"
                    "Map" "Map"
                    base-type)
          args (:type-args nex-type)]
      (if args
        (str js-base "<" (str/join ", " (map #(if (string? %)
                                                 (nex-type-to-js %)
                                                 (nex-type-to-js %))
                                               args)) ">")
        js-base))

    ;; Handle basic types
    (string? nex-type)
    (case nex-type
      "Integer" "number"
      "Integer64" "number"
      "Real" "number"
      "Decimal" "number"
      "Char" "string"
      "Boolean" "boolean"
      "String" "string"
      "Array" "Array"
      "Map" "Map"
      "Console" "Object"
      "File" "Object"
      "Process" "Object"
      "Function" "Function"
      nex-type)

    :else nex-type))

(defn default-value
  "Get the default value for a Nex type"
  [nex-type]
  (cond
    ;; Handle parameterized types
    (map? nex-type)
    (let [base (:base-type nex-type)]
      (case base
        "Array" "[]"
        "Map" "new Map()"
        "null"))

    ;; Handle basic types
    (string? nex-type)
    (case nex-type
      "Integer" "0"
      "Integer64" "0"
      "Real" "0.0"
      "Decimal" "0.0"
      "Char" "'\\0'"
      "Boolean" "false"
      "String" "\"\""
      "Array" "[]"
      "Map" "new Map()"
      "Console" "({_type: 'Console'})"
      "File" "null"
      "Process" "({_type: 'Process'})"
      "null")

    :else "null"))

;;
;; Indentation Helpers
;;

(defn indent
  "Add indentation to code"
  [level code]
  (let [spaces (str/join (repeat (* 2 level) " "))]
    (str spaces code)))

(defn indent-lines
  "Add indentation to multiple lines"
  [level lines]
  (str/join "\n" (map #(indent level %) lines)))

(defn generate-jsdoc
  "Generate JSDoc comment for a note"
  [level note]
  (when note
    (str (indent level "/**")
         "\n"
         (indent level (str " * " note))
         "\n"
         (indent level " */"))))

;;
;; Expression Generation
;;

(defn generate-binary-op
  "Generate JavaScript code for binary operator"
  [operator]
  (case operator
    "and" "&&"
    "or" "||"
    "=" "==="
    "/=" "!=="
    ">=" ">="
    "<=" "<="
    ">" ">"
    "<" "<"
    "+" "+"
    "-" "-"
    "*" "*"
    "/" "/"
    operator))

(declare generate-expression)

(defn generate-binary-expr
  "Generate JavaScript code for binary expression"
  [{:keys [operator left right]}]
  (let [left-code (generate-expression left)
        right-code (generate-expression right)
        op (generate-binary-op operator)]
    (str "(" left-code " " op " " right-code ")")))

(defn generate-unary-expr
  "Generate JavaScript code for unary expression"
  [{:keys [operator operand]}]
  (let [operand-code (generate-expression operand)
        op (case operator
             "not" "!"
             "-" "-"
             operator)]
    (str op operand-code)))

(defn map-builtin-function
  "Map Nex built-in functions to JavaScript equivalents"
  [method args-code]
  (case method
    "print" (str "console.log(" args-code ")")
    "println" (str "console.log(" args-code ")")
    ;; Default: use as-is
    (str method "(" args-code ")")))

(def builtin-method-mappings
  "Map Nex built-in type methods to JavaScript equivalents"
  {:String
   {"length"      (fn [target _] (str target ".length"))
    "index_of"    (fn [target args] (str target ".indexOf(" args ")"))
    "substring"   (fn [target args] (str target ".substring(" args ")"))
    "to_upper"    (fn [target _] (str target ".toUpperCase()"))
    "to_lower"    (fn [target _] (str target ".toLowerCase()"))
    "contains"    (fn [target args] (str target ".includes(" args ")"))
    "starts_with" (fn [target args] (str target ".startsWith(" args ")"))
    "ends_with"   (fn [target args] (str target ".endsWith(" args ")"))
    "trim"        (fn [target _] (str target ".trim()"))
    "replace"     (fn [target args] (str target ".replace(" args ")"))
    "char_at"     (fn [target args] (str target ".charAt(" args ")"))
    "split"       (fn [target args] (str target ".split(" args ")"))
    ;; String operators
    "plus"        (fn [target args] (str "(" target " + " args ")"))
    "equals"      (fn [target args] (str "(" target " === " args ")"))
    "not_equals"  (fn [target args] (str "(" target " !== " args ")"))
    "less_than"   (fn [target args] (str "(" target ".localeCompare(" args ") < 0)"))
    "less_than_or_equal" (fn [target args] (str "(" target ".localeCompare(" args ") <= 0)"))
    "greater_than" (fn [target args] (str "(" target ".localeCompare(" args ") > 0)"))
    "greater_than_or_equal" (fn [target args] (str "(" target ".localeCompare(" args ") >= 0)"))}

   :Integer
   {"to_string" (fn [target _] (str target ".toString()"))
    "abs"       (fn [target _] (str "Math.abs(" target ")"))
    "min"       (fn [target args] (str "Math.min(" target ", " args ")"))
    "max"       (fn [target args] (str "Math.max(" target ", " args ")"))
    ;; Arithmetic operators
    "plus"      (fn [target args] (str "(" target " + " args ")"))
    "minus"     (fn [target args] (str "(" target " - " args ")"))
    "times"     (fn [target args] (str "(" target " * " args ")"))
    "divided_by" (fn [target args] (str "(" target " / " args ")"))
    ;; Comparison operators
    "equals"    (fn [target args] (str "(" target " === " args ")"))
    "not_equals" (fn [target args] (str "(" target " !== " args ")"))
    "less_than" (fn [target args] (str "(" target " < " args ")"))
    "less_than_or_equal" (fn [target args] (str "(" target " <= " args ")"))
    "greater_than" (fn [target args] (str "(" target " > " args ")"))
    "greater_than_or_equal" (fn [target args] (str "(" target " >= " args ")"))}

   :Array
   {"length"    (fn [target _] (str target ".length"))
    "get"       (fn [target args] (str target "[" args "]"))
    "at"        (fn [target args] (str target ".set(" args ")"))
    "add"       (fn [target args] (str target ".push(" args ")"))
    "set"       (fn [target args] (str target ".set(" args ")"))
    "is_empty"  (fn [target _] (str "(" target ".length === 0)"))
    "contains"  (fn [target args] (str target ".includes(" args ")"))
    "index_of"  (fn [target args] (str target ".indexOf(" args ")"))
    "remove"    (fn [target args] (str "(" target ".splice(" args ", 1), " target ")"))
    "reverse"   (fn [target _] (str "[..." target "].reverse()"))
    "sort"      (fn [target _] (str "[..." target "].sort()"))
    "slice"     (fn [target args] (str target ".slice(" args ")"))}

   :Map
   {"size"         (fn [target _] (str target ".size"))
    "is_empty"     (fn [target _] (str "(" target ".size === 0)"))
    "get"          (fn [target args] (str target ".get(" args ")"))
    "try_get"      (fn [target args] (str target ".get(" args ")"))
    "at"           (fn [target args] (str "(" target ".set(" args "), " target ")"))
    "contains_key" (fn [target args] (str target ".has(" args ")"))
    "keys"         (fn [target _] (str "Array.from(" target ".keys())"))
    "values"       (fn [target _] (str "Array.from(" target ".values())"))
    "remove"       (fn [target args] (str "(" target ".delete(" args "), " target ")"))}

   :Console
   {"print"        (fn [_ args] (str "process.stdout.write(String(" args "))"))
    "print_line"   (fn [_ args] (str "console.log(" args ")"))
    "read_line"    (fn [_ _] "require('readline-sync').question('')")
    "error"        (fn [_ args] (str "console.error(" args ")"))
    "new_line"     (fn [_ _] "console.log()")
    "read_integer" (fn [_ _] "parseInt(require('readline-sync').question(''), 10)")
    "read_real"    (fn [_ _] "parseFloat(require('readline-sync').question(''))")}

   :File
   {"read"   (fn [t _] (str "require('fs').readFileSync(" t ".path, 'utf8')"))
    "write"  (fn [t a] (str "require('fs').writeFileSync(" t ".path, " a ", 'utf8')"))
    "append" (fn [t a] (str "require('fs').appendFileSync(" t ".path, " a ", 'utf8')"))
    "exists" (fn [t _] (str "require('fs').existsSync(" t ".path)"))
    "delete" (fn [t _] (str "require('fs').unlinkSync(" t ".path)"))
    "lines"  (fn [t _] (str "require('fs').readFileSync(" t ".path, 'utf8').split('\\n')"))
    "close"  (fn [t _] (str "/* " t ".close() */"))}

   :Process
   {"getenv"       (fn [_ a] (str "process.env[" a "]"))
    "setenv"       (fn [_ a] (str "process.env[" a "]"))
    "command_line" (fn [_ _] "process.argv")}})

(defn generate-call-expr
  "Generate JavaScript code for method call.
   NOTE: For operator methods (plus, less_than, etc.) that exist on multiple types,
   we try Integer methods first since numeric operations are more common.
   For string operations, use string literals or string-specific methods."
  [{:keys [target method args]}]
  (let [args-code (str/join ", " (map generate-expression args))]
    (if target
      ;; Object method call
      (let [target-code (if (string? target) target (generate-expression target))]
        (or
         ;; Try Integer methods first (for operators, numeric is more common)
         (when-let [method-fn (get-in builtin-method-mappings [:Integer method])]
           (method-fn target-code args-code))
         ;; Try String methods
         (when-let [method-fn (get-in builtin-method-mappings [:String method])]
           (method-fn target-code args-code))
         ;; Try Array methods
         (when-let [method-fn (get-in builtin-method-mappings [:Array method])]
           (method-fn target-code args-code))
         ;; Try Map methods
         (when-let [method-fn (get-in builtin-method-mappings [:Map method])]
           (method-fn target-code args-code))
         ;; Default: regular method call
         (str target-code "." method "(" args-code ")")))
      ;; Global function call: function object or builtin
      (if (contains? *function-names* method)
        (str "NexGlobals." method ".call" (count args) "(" args-code ")")
        (map-builtin-function method args-code)))))

(defn generate-create-expr
  "Generate JavaScript code for create expression"
  [{:keys [class-name generic-args constructor args]}]
  ;; JS has no generics syntax, generic-args is accepted but not emitted
  (let [args-code (str/join ", " (map generate-expression args))]
    (case class-name
      "Console" "({_type: 'Console'})"
      "File" (str "({_type: 'File', path: " args-code "})")
      "Process" "({_type: 'Process'})"
      (if constructor
        ;; Named constructor: static factory method call
        (str class-name "." constructor "(" args-code ")")
        ;; Default: new ClassName()
        (str "new " class-name "()")))))

(defn generate-subscript-expr
  "Generate JavaScript code for subscript access (array/map access)"
  [{:keys [target index]}]
  (let [target-code (generate-expression target)
        index-code (generate-expression index)]
    ;; In JavaScript, we need to check if it's a Map or Array
    ;; For simplicity, we'll use a helper: use .get() for Maps, [] for Arrays
    ;; We'll detect Maps by checking if target is 'Map' type at runtime
    ;; For now, we'll generate code that works for both
    (str target-code ".get ? " target-code ".get(" index-code ") : "
         target-code "[" index-code "]")))

(defn generate-array-literal
  "Generate JavaScript code for array literal"
  [elements]
  (let [elements-code (str/join ", " (map generate-expression elements))]
    (str "[" elements-code "]")))

(defn generate-map-literal
  "Generate JavaScript code for map literal"
  [entries]
  (let [entries-code (str/join ", "
                               (map (fn [{:keys [key value]}]
                                     (str "[" (generate-expression key) ", "
                                          (generate-expression value) "]"))
                                   entries))]
    (str "new Map([" entries-code "])")))

(defn generate-expression
  "Generate JavaScript code for an expression"
  [expr]
  (case (:type expr)
    :integer (str (:value expr))
    :real (str (:value expr))
    :string (str "\"" (:value expr) "\"")
    :boolean (str (:value expr))
    :char (str "'" (:value expr) "'")
    :nil "null"
    :identifier (:name expr)
    :binary (generate-binary-expr expr)
    :unary (generate-unary-expr expr)
    :call (generate-call-expr expr)
    :create (generate-create-expr expr)
    :subscript (generate-subscript-expr expr)
    :array-literal (generate-array-literal (:elements expr))
    :map-literal (generate-map-literal (:entries expr))
    :old (str "old_" (generate-expression (:expr expr)))
    :this *this-name*
    :super "super"
    (str "/* Unknown expression: " (:type expr) " */")))

;;
;; Statement Generation
;;

(declare generate-statement)

(defn generate-assignment
  "Generate JavaScript code for assignment"
  [{:keys [target value]}]
  (str target " = " (generate-expression value) ";"))

(defn generate-let
  "Generate JavaScript code for let (local variable declaration).
   If var-names is provided and contains the variable name, generates assignment instead."
  ([let-node] (generate-let let-node #{}))
  ([{:keys [name var-type value]} var-names]
   (if (contains? var-names name)
     ;; Variable already declared in outer scope - generate assignment
     (str name " = " (generate-expression value) ";")
     ;; New variable - generate declaration
     (str "let " name " = " (generate-expression value) ";"))))

(defn generate-if
  "Generate JavaScript code for if-then-else"
  ([level node] (generate-if level node #{}))
  ([level {:keys [condition then else]} var-names]
   (let [cond-code (generate-expression condition)
         then-code (map #(generate-statement (+ level 1) % var-names) then)
         else-code (map #(generate-statement (+ level 1) % var-names) else)]
     (str/join "\n"
               [(indent level (str "if (" cond-code ") {"))
                (str/join "\n" then-code)
                (indent level "} else {")
                (str/join "\n" else-code)
                (indent level "}")]))))

(defn generate-scoped-block
  "Generate JavaScript code for scoped block"
  ([level node] (generate-scoped-block level node #{}))
  ([level {:keys [body]} var-names]
   (let [statements (map #(generate-statement (+ level 1) % var-names) body)]
     (str/join "\n"
               [(indent level "{")
                (str/join "\n" statements)
                (indent level "}")]))))

(defn extract-var-names-js
  "Extract variable names from let statements"
  [stmts]
  (set (keep (fn [stmt]
               (when (= (:type stmt) :let)
                 (:name stmt)))
             stmts)))

(defn generate-loop
  "Generate JavaScript code for from-until-do loop"
  [level {:keys [init invariant variant until body]}]
  (let [;; Extract variable names declared in init
        loop-vars (extract-var-names-js init)
        ;; Generate init statements
        init-stmts (map #(generate-statement level % #{}) init)
        cond-code (str "!(" (generate-expression until) ")")
        ;; Generate body statements with loop variables in scope
        body-stmts (map #(generate-statement (+ level 1) % loop-vars) body)
        invariant-comment (when invariant
                           (indent (+ level 1)
                                   (str "// Invariant: "
                                        (str/join ", "
                                                  (map :label invariant)))))
        variant-comment (when variant
                         (indent (+ level 1)
                                 (str "// Variant: " (generate-expression variant))))]
    (str/join "\n"
              (concat
               init-stmts
               [(indent level (str "while (" cond-code ") {"))]
               (when invariant-comment [invariant-comment])
               (when variant-comment [variant-comment])
               body-stmts
               [(indent level "}")]))))

(defn generate-statement
  "Generate JavaScript code for a statement"
  ([level stmt] (generate-statement level stmt #{}))
  ([level stmt var-names]
   (case (:type stmt)
     :assign (indent level (generate-assignment stmt))
     :call (indent level (str (generate-call-expr stmt) ";"))
     :let (indent level (generate-let stmt var-names))
     :if (generate-if level stmt var-names)
     :scoped-block (generate-scoped-block level stmt var-names)
     :loop (generate-loop level stmt)
     :with (when (= (:target stmt) "javascript")
             ;; Only include this block if target is "javascript"
             (str/join "\n" (map #(generate-statement level % var-names) (:body stmt))))
     :member-assign (indent level
                      (let [obj-str (if (= (:object-type stmt) :this) *this-name* "super")]
                        (str obj-str "." (:field stmt) " = " (generate-expression (:value stmt)) ";")))
     (indent level (str "/* Unknown statement: " (:type stmt) " */")))))

;;
;; Contract Generation
;;

(defn generate-assertions
  "Generate JavaScript assertions for contracts"
  [level assertions contract-type opts]
  (when (and (seq assertions) (not (:skip-contracts opts)))
    (map (fn [{:keys [label condition]}]
           (indent level
                   (str "if (!(" (generate-expression condition) ")) "
                        "throw new Error(\"" contract-type " violation: " label "\");")))
         assertions)))

;;
;; Visibility Conversion
;;

(defn visibility-to-js
  "Convert Nex visibility to JavaScript naming convention"
  [visibility member-name]
  (case (:type visibility)
    :private (str "_" member-name)  ;; Use underscore prefix for private
    :public member-name
    :selective member-name  ;; No selective visibility in JS, just public
    member-name))

;;
;; Method Generation
;;

(defn extract-old-references
  "Extract field names referenced with 'old' in assertions"
  [assertions]
  (let [extract-from-expr (fn extract [expr]
                            (cond
                              (nil? expr) #{}
                              (map? expr)
                              (case (:type expr)
                                :old (if (and (map? (:expr expr))
                                            (= :identifier (:type (:expr expr))))
                                      #{(:name (:expr expr))}
                                      #{})
                                :binary (set/union (extract (:left expr))
                                                  (extract (:right expr)))
                                :unary (extract (:operand expr))
                                :call (reduce set/union #{}
                                            (map extract (:args expr)))
                                #{})
                              :else #{}))]
    (reduce set/union #{}
            (map (fn [{:keys [condition]}]
                   (extract-from-expr condition))
                 assertions))))

(defn generate-method
  "Generate JavaScript code for a method"
  [level {:keys [name params return-type body require ensure visibility note]} opts]
  (let [params-code (str/join ", " (map :name params))
        ;; Apply visibility naming convention
        method-name (if visibility
                     (visibility-to-js visibility name)
                     name)
        ;; Initialize result variable if method has return type
        result-init (when return-type
                     [(indent (+ level 1)
                             (str "let result = " (default-value return-type) ";"))])
        ;; Extract old references and generate capture statements
        old-refs (when ensure (extract-old-references ensure))
        old-captures (when (seq old-refs)
                      (map (fn [field-name]
                            (indent (+ level 1)
                                   (str "let old_" field-name " = this." field-name ";")))
                          old-refs))
        preconditions (generate-assertions (+ level 1) require "Precondition" opts)
        statements (map #(generate-statement (+ level 1) %) body)
        postconditions (generate-assertions (+ level 1) ensure "Postcondition" opts)
        ;; Add return statement if method has return type
        return-stmt (when return-type
                     [(indent (+ level 1) "return result;")])
        ;; Generate JSDoc comment for type information and note
        jsdoc (when (or note (seq params) return-type)
                (let [note-line (when note [(str "   * " note)])
                      param-docs (map (fn [{:keys [name type]}]
                                       (str "   * @param {" (nex-type-to-js type) "} " name))
                                     params)
                      return-doc (when return-type
                                  [(str "   * @returns {" (nex-type-to-js return-type) "}")])]
                  (str/join "\n"
                           (concat
                            [(indent level "/**")]
                            note-line
                            param-docs
                            return-doc
                            [(indent level "   */")]))))]
    (str/join "\n"
              (concat
               (when jsdoc [jsdoc])
               [(indent level (str method-name "(" params-code ") {"))]
               result-init
               old-captures
               preconditions
               statements
               postconditions
               return-stmt
               [(indent level "}")]))))

;;
;; Field Generation
;;

(defn generate-field-init
  "Generate JavaScript code for field initialization in constructor"
  [level {:keys [name field-type visibility]}]
  (let [;; Apply visibility naming convention
        field-name (if visibility
                    (visibility-to-js visibility name)
                    name)
        init-value (default-value field-type)]
    (indent level (str "this." field-name " = " init-value ";"))))

;;
;; Constructor Generation
;;

(defn generate-default-constructor
  "Generate a JavaScript default no-arg constructor with field initialization"
  [level fields has-parent?]
  (let [super-call (when has-parent?
                     [(indent (+ level 1) "super();")])
        field-inits (map #(generate-field-init (+ level 1) %) fields)]
    (str/join "\n"
              (concat
               [(indent level "constructor() {")]
               super-call
               field-inits
               [(indent level "}")]))))

(defn generate-factory-constructor
  "Generate JavaScript static factory method for a Nex constructor"
  [level class-name {:keys [name params body require ensure]} opts]
  (let [local-name (class-name-to-local class-name)
        params-code (str/join ", " (map :name params))
        preconditions (generate-assertions (+ level 1) require "Precondition" opts)
        statements (binding [*this-name* local-name]
                     (mapv #(generate-statement (+ level 1) %) body))
        postconditions (generate-assertions (+ level 1) ensure "Postcondition" opts)]
    (str/join "\n"
              (concat
               [(indent level (str "static " name "(" params-code ") {"))]
               [(indent (+ level 1) (str "let " local-name " = new " class-name "();"))]
               preconditions
               statements
               postconditions
               [(indent (+ level 1) (str "return " local-name ";"))]
               [(indent level "}")]))))

;;
;; Inheritance Handling
;;

(defn analyze-inheritance
  "Analyze inheritance structure - JS only supports single inheritance"
  [parents]
  (if (empty? parents)
    {:extends nil}
    {:extends (:parent (first parents))}))

(defn generate-generic-comment
  "Generate JSDoc comment for generic parameters"
  [generic-params]
  (when (seq generic-params)
    (let [params-str (str/join ", "
                               (map (fn [{:keys [name constraint]}]
                                     (if constraint
                                       (str name " extends " constraint)
                                       name))
                                   generic-params))]
      (str "/**\n * @template " params-str "\n */"))))

(defn generate-class-header
  "Generate JavaScript class header with inheritance"
  [class-name generic-params parents]
  (let [{:keys [extends]} (analyze-inheritance parents)
        extends-clause (when extends (str " extends " extends))]
    (str "class " class-name extends-clause " {")))

;;
;; Class Generation
;;

(defn extract-members
  "Extract different types of members from class body, preserving visibility"
  [class-body]
  (let [constructor-sections (filter #(= (:type %) :constructors) class-body)
        feature-sections (filter #(= (:type %) :feature-section) class-body)
        ;; Add visibility to each member
        all-members (mapcat (fn [section]
                             (cond
                               (= (:type section) :constructors)
                               (:constructors section)
                               (= (:type section) :feature-section)
                               ;; Add visibility to each member
                               (map #(assoc % :visibility (:visibility section))
                                    (:members section))
                               :else []))
                           class-body)
        fields (filter #(= (:type %) :field) all-members)
        methods (filter #(= (:type %) :method) all-members)
        ctors (mapcat :constructors constructor-sections)]
    {:fields fields
     :methods methods
     :constructors ctors}))

(defn generate-class
  "Generate JavaScript code for a Nex class"
  ([class-def] (generate-class class-def {}))
  ([{:keys [name generic-params parents body invariant note]} opts]
   (let [{:keys [fields methods constructors]} (extract-members body)
         ;; Generate class JSDoc if note present
         class-jsdoc (when note
                      [(generate-jsdoc 0 note)])
         generic-comment (generate-generic-comment generic-params)
         class-header (generate-class-header name generic-params parents)
         invariant-comment (when (and invariant (not (:skip-contracts opts)))
                            (indent 1 (str "// Class invariant: "
                                          (str/join ", " (map :label invariant)))))
         ;; Always generate a default no-arg constructor for field initialization
         has-parent? (some? (:extends (analyze-inheritance parents)))
         default-constructor (generate-default-constructor 1 fields has-parent?)
         ;; All Nex constructors become static factory methods
         factory-methods (map #(generate-factory-constructor 1 name % opts) constructors)
         methods-code (map #(generate-method 1 % opts) methods)]
     (str/join "\n"
               (concat
                class-jsdoc
                (when generic-comment [generic-comment])
                [class-header]
                (when invariant-comment [invariant-comment ""])
                [default-constructor ""]
                factory-methods
                (when (seq factory-methods) [""])
                methods-code
                ["}"])))))

(defn generate-function-base-class
  "Generate the built-in Function base class."
  []
  (let [method-lines
        (map (fn [n]
               (let [params (str/join ", " (map (fn [i] (str "arg" i))
                                                (range 1 (inc n))))]
                 (str "  call" n "(" params ") { return null; }")))
             (range 1 33))]
    (str "class Function {\n"
         (str/join "\n" method-lines)
         "\n}")))

(defn generate-function-globals
  "Generate a globals holder for function instances."
  [functions]
  (when (seq functions)
    (let [lines (map (fn [{:keys [name class-name]}]
                       (str "  " name ": new " class-name "(),"))
                     functions)]
      (str "const NexGlobals = {\n"
           (str/join "\n" lines)
           "\n};"))))

;;
;; Main Translation Function
;;

(defn generate-import
  "Generate a JavaScript import statement"
  [{:keys [qualified-name source]}]
  ;; Only generate JS imports (those with a 'source' field)
  (when source
    ;; Remove quotes from source string
    (let [clean-source (if (and (string? source)
                               (or (.startsWith source "\"") (.startsWith source "'")))
                        (subs source 1 (dec (count source)))
                        source)]
      (str "import " qualified-name " from '" clean-source "';"))))

(defn translate-ast
  "Translate a Nex AST to JavaScript code

  Options:
    :skip-contracts - When true, omits all preconditions, postconditions,
                      and class invariants from generated code (useful for production)"
  ([ast] (translate-ast ast {}))
  ([ast opts]
   (let [imports (:imports ast)
         classes (:classes ast)
         functions (:functions ast)
         function-names (set (map :name functions))
         js-imports (keep generate-import imports)
         function-base (generate-function-base-class)
         function-globals (generate-function-globals functions)]
     (binding [*function-names* function-names]
       (let [js-classes (map #(generate-class % opts) classes)
             parts (concat js-imports
                           (when (seq js-imports) [""])
                           [function-base]
                           (when function-globals [""])
                           (when function-globals [function-globals])
                           (when (seq js-classes) [""])
                           js-classes)]
         (str/join "\n" (remove empty? parts)))))))

(defn translate
  "Translate Nex source code to JavaScript

  Options:
    :skip-contracts - When true, omits all preconditions, postconditions,
                      and class invariants from generated code (useful for production)
    :skip-type-check - When true, skips static type checking (not recommended)

  Examples:
    (translate nex-code)                           ; With contracts and type checking
    (translate nex-code {:skip-contracts true})    ; Without contracts (production)"
  ([nex-code] (translate nex-code {}))
  ([nex-code opts]
   (let [ast (p/ast nex-code)]
     ;; Run type checker unless explicitly skipped
     (when-not (:skip-type-check opts)
       (let [result (tc/type-check ast)]
         (when-not (:success result)
           (throw (ex-info "Type checking failed"
                           {:errors (map tc/format-type-error (:errors result))})))))
     (translate-ast ast opts))))

(defn translate-file
  "Translate a Nex file to JavaScript and optionally save it

  Options:
    :skip-contracts - When true, omits all contracts (useful for production)"
  ([nex-file] (translate-file nex-file nil {}))
  ([nex-file output-file] (translate-file nex-file output-file {}))
  ([nex-file output-file opts]
   (let [nex-code (slurp nex-file)
         js-code (translate nex-code opts)]
     (if output-file
       (spit output-file js-code)
       js-code))))

;;
;; Pretty Printing
;;

(defn print-translation
  "Print a nicely formatted translation with header"
  [nex-code]
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║              NEX TO JAVASCRIPT TRANSLATOR                  ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println)
  (println "NEX CODE:")
  (println "─────────────────────────────────────────────────────────────")
  (println nex-code)
  (println)
  (println "JAVASCRIPT CODE:")
  (println "─────────────────────────────────────────────────────────────")
  (println (translate nex-code))
  (println))

(comment
  ;; Example usage:

  ;; Simple class
  (print-translation
   "class Person
  feature
    name: String
    age: Integer

    greet() do
      print(\"Hello\")
    end
  end")

  ;; Class with inheritance
  (print-translation
   "class Animal
  feature
    speak() do
      print(\"Animal speaks\")
    end
  end

  class Dog
  inherit
    Animal
    end
  feature
    bark() do
      print(\"Woof!\")
    end
  end")

  ;; Class with contracts
  (print-translation
   "class Account
  feature
    balance: Integer

    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        let balance := balance + amount
      ensure
        increased: balance > 0
      end
  end")
  )



(defn -main
  "Main entry point for command-line compilation"
  [& args]
  (when (empty? args)
    (println "Usage: nex compile javascript <input.nex> [output.js]")
    (System/exit 1))
  
  (let [input-file (first args)
        output-file (when (> (count args) 1) (second args))]
    (try
      (if output-file
        (do
          (translate-file input-file output-file)
          (println (str "Compiled " input-file " -> " output-file)))
        (println (translate-file input-file)))
      (System/exit 0)
      (catch Exception e
        (println "Error:" (.getMessage e))
        (System/exit 1)))))
