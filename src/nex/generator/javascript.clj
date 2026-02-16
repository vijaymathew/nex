(ns nex.generator.javascript
  "Translates Nex (Eiffel-based) code to JavaScript (ES6+)"
  (:require [nex.parser :as p]
            [clojure.string :as str]))

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
      "String" "null"
      "Array" "[]"
      "Map" "new Map()"
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

(defn generate-call-expr
  "Generate JavaScript code for method call"
  [{:keys [target method args]}]
  (let [args-code (str/join ", " (map generate-expression args))]
    (if target
      (str target "." method "(" args-code ")")
      (str method "(" args-code ")"))))

(defn generate-create-expr
  "Generate JavaScript code for create expression"
  [{:keys [class-name constructor args]}]
  (let [args-code (str/join ", " (map generate-expression args))]
    (str "new " class-name "(" args-code ")")))

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
    :identifier (:name expr)
    :binary (generate-binary-expr expr)
    :unary (generate-unary-expr expr)
    :call (generate-call-expr expr)
    :create (generate-create-expr expr)
    :subscript (generate-subscript-expr expr)
    :array-literal (generate-array-literal (:elements expr))
    :map-literal (generate-map-literal (:entries expr))
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
  "Generate JavaScript code for let (local variable declaration)"
  [{:keys [name var-type value]}]
  ;; Always use 'let' in JavaScript (no type annotations in vanilla JS)
  (str "let " name " = " (generate-expression value) ";"))

(defn generate-if
  "Generate JavaScript code for if-then-else"
  [level {:keys [condition then else]}]
  (let [cond-code (generate-expression condition)
        then-code (map #(generate-statement (+ level 1) %) then)
        else-code (map #(generate-statement (+ level 1) %) else)]
    (str/join "\n"
              [(indent level (str "if (" cond-code ") {"))
               (str/join "\n" then-code)
               (indent level "} else {")
               (str/join "\n" else-code)
               (indent level "}")])))

(defn generate-scoped-block
  "Generate JavaScript code for scoped block"
  [level {:keys [body]}]
  (let [statements (map #(generate-statement (+ level 1) %) body)]
    (str/join "\n"
              [(indent level "{")
               (str/join "\n" statements)
               (indent level "}")])))

(defn generate-loop
  "Generate JavaScript code for from-until-do loop"
  [level {:keys [init invariant variant until body]}]
  (let [init-stmts (map #(generate-statement level %) init)
        cond-code (str "!(" (generate-expression until) ")")
        body-stmts (map #(generate-statement (+ level 1) %) body)
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
  [level stmt]
  (case (:type stmt)
    :assign (indent level (generate-assignment stmt))
    :call (indent level (str (generate-call-expr stmt) ";"))
    :let (indent level (generate-let stmt))
    :if (generate-if level stmt)
    :scoped-block (generate-scoped-block level stmt)
    :loop (generate-loop level stmt)
    (indent level (str "/* Unknown statement: " (:type stmt) " */"))))

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

(defn generate-method
  "Generate JavaScript code for a method"
  [level {:keys [name params return-type body require ensure visibility]} opts]
  (let [params-code (str/join ", " (map :name params))
        ;; Apply visibility naming convention
        method-name (if visibility
                     (visibility-to-js visibility name)
                     name)
        ;; Initialize result variable if method has return type
        result-init (when return-type
                     [(indent (+ level 1)
                             (str "let result = " (default-value return-type) ";"))])
        preconditions (generate-assertions (+ level 1) require "Precondition" opts)
        statements (map #(generate-statement (+ level 1) %) body)
        postconditions (generate-assertions (+ level 1) ensure "Postcondition" opts)
        ;; Add return statement if method has return type
        return-stmt (when return-type
                     [(indent (+ level 1) "return result;")])
        ;; Generate JSDoc comment for type information
        jsdoc (when (or (seq params) return-type)
                (let [param-docs (map (fn [{:keys [name type]}]
                                       (str "   * @param {" (nex-type-to-js type) "} " name))
                                     params)
                      return-doc (when return-type
                                  [(str "   * @returns {" (nex-type-to-js return-type) "}")])]
                  (str/join "\n"
                           (concat
                            [(indent level "/**")]
                            param-docs
                            return-doc
                            [(indent level "   */")]))))]
    (str/join "\n"
              (concat
               (when jsdoc [jsdoc])
               [(indent level (str method-name "(" params-code ") {"))]
               result-init
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

(defn generate-constructor
  "Generate JavaScript code for a constructor"
  [level class-name fields {:keys [params body require ensure]} opts]
  (let [params-code (str/join ", " (map :name params))
        preconditions (generate-assertions (+ level 1) require "Precondition" opts)
        ;; First initialize all fields
        field-inits (map #(generate-field-init (+ level 1) %) fields)
        ;; Then execute constructor body
        statements (map #(generate-statement (+ level 1) %) body)
        postconditions (generate-assertions (+ level 1) ensure "Postcondition" opts)]
    (str/join "\n"
              (concat
               [(indent level (str "constructor(" params-code ") {"))]
               field-inits
               preconditions
               statements
               postconditions
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
  ([{:keys [name generic-params parents body invariant]} opts]
   (let [{:keys [fields methods constructors]} (extract-members body)
         generic-comment (generate-generic-comment generic-params)
         class-header (generate-class-header name generic-params parents)
         invariant-comment (when (and invariant (not (:skip-contracts opts)))
                            (indent 1 (str "// Class invariant: "
                                          (str/join ", " (map :label invariant)))))
         ;; JavaScript classes need at least one constructor
         default-constructor (when (empty? constructors)
                              (generate-constructor 1 name fields
                                                   {:params [] :body [] :require [] :ensure []}
                                                   opts))
         ;; Generate constructors - only one constructor in JS, others become static factory methods
         main-constructor (when (seq constructors)
                           (generate-constructor 1 name fields (first constructors) opts))
         ;; Other constructors become static factory methods
         factory-methods (when (> (count constructors) 1)
                          (map-indexed
                           (fn [idx ctor]
                             (when (> idx 0)
                               (let [factory-name (str "create" (if (:name ctor)
                                                                 (str/capitalize (str (:name ctor)))
                                                                 (str "Alternative" idx)))
                                     params-code (str/join ", " (map :name (:params ctor)))
                                     args-code (str/join ", " (map :name (:params ctor)))]
                                 (str (indent 1 (str "static " factory-name "(" params-code ") {"))
                                      "\n"
                                      (indent 2 (str "const instance = new " name "();"))
                                      "\n"
                                      (str/join "\n" (map #(generate-statement 2 %) (:body ctor)))
                                      "\n"
                                      (indent 2 "return instance;")
                                      "\n"
                                      (indent 1 "}")))))
                           constructors))
         methods-code (map #(generate-method 1 % opts) methods)]
     (str/join "\n"
               (concat
                (when generic-comment [generic-comment])
                [class-header]
                (when invariant-comment [invariant-comment ""])
                (when default-constructor [default-constructor ""])
                (when main-constructor [main-constructor ""])
                (filter some? factory-methods)
                (when (seq factory-methods) [""])
                methods-code
                ["}"])))))

;;
;; Main Translation Function
;;

(defn translate-ast
  "Translate a Nex AST to JavaScript code

  Options:
    :skip-contracts - When true, omits all preconditions, postconditions,
                      and class invariants from generated code (useful for production)"
  ([ast] (translate-ast ast {}))
  ([ast opts]
   (let [classes (:classes ast)
         js-classes (map #(generate-class % opts) classes)]
     (str/join "\n\n" js-classes))))

(defn translate
  "Translate Nex source code to JavaScript

  Options:
    :skip-contracts - When true, omits all preconditions, postconditions,
                      and class invariants from generated code (useful for production)

  Examples:
    (translate nex-code)                           ; With contracts
    (translate nex-code {:skip-contracts true})    ; Without contracts (production)"
  ([nex-code] (translate nex-code {}))
  ([nex-code opts]
   (let [ast (p/ast nex-code)]
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
