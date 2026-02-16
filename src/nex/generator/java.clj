(ns nex.generator.java
  "Translates Nex (Eiffel-based) code to Java"
  (:require [nex.parser :as p]
            [clojure.string :as str]))

;;
;; Type Mapping
;;

(defn nex-type-to-java-boxed
  "Convert Nex type to Java boxed type (for generics)"
  [nex-type]
  (case nex-type
    "Integer" "Integer"
    "Integer64" "Long"
    "Real" "Float"
    "Decimal" "Double"
    "Char" "Character"
    "Boolean" "Boolean"
    "String" "String"
    "Array" "ArrayList"
    "Map" "HashMap"
    nex-type))

(defn nex-type-to-java
  "Convert Nex type to Java type
   use-boxed? - if true, use boxed types for primitives (needed for generics)"
  ([nex-type] (nex-type-to-java nex-type false))
  ([nex-type use-boxed?]
   (cond
     ;; Handle parameterized types like {:base-type "List" :type-args ["Cat"]}
     (map? nex-type)
     (let [base-type (:base-type nex-type)
           ;; Convert Array/Map to ArrayList/HashMap
           java-base (case base-type
                      "Array" "ArrayList"
                      "Map" "HashMap"
                      base-type)
           args (:type-args nex-type)]
       (if args
         ;; Type arguments in generics must use boxed types
         (str java-base "<" (str/join ", " (map #(if (string? %)
                                                    (nex-type-to-java-boxed %)
                                                    (nex-type-to-java % true))
                                                 args)) ">")
         java-base))

     ;; Handle basic types
     (string? nex-type)
     (if use-boxed?
       (nex-type-to-java-boxed nex-type)
       (case nex-type
         "Integer" "int"
         "Integer64" "long"
         "Real" "float"
         "Decimal" "double"
         "Char" "char"
         "Boolean" "boolean"
         "String" "String"
         "Array" "ArrayList"
         "Map" "HashMap"
         nex-type))

     :else nex-type)))

(defn default-value
  "Get the default value for a Nex type"
  [nex-type]
  (cond
    ;; Handle parameterized types
    (map? nex-type)
    (let [base (:base-type nex-type)]
      (case base
        ;; Use Java diamond operator for type inference
        "Array" (str "new ArrayList<>()")
        "Map" (str "new HashMap<>()")
        "null"))

    ;; Handle basic types
    (string? nex-type)
    (case nex-type
      "Integer" "0"
      "Integer64" "0L"
      "Real" "0.0f"
      "Decimal" "0.0"
      "Char" "'\\0'"
      "Boolean" "false"
      "String" "null"
      "Array" "new ArrayList<>()"
      "Map" "new HashMap<>()"
      "null")

    :else "null"))

;;
;; Indentation Helpers
;;

(defn indent
  "Add indentation to code"
  [level code]
  (let [spaces (str/join (repeat (* 4 level) " "))]
    (str spaces code)))

(defn indent-lines
  "Add indentation to multiple lines"
  [level lines]
  (str/join "\n" (map #(indent level %) lines)))

;;
;; Expression Generation
;;

(defn generate-binary-op
  "Generate Java code for binary operator"
  [operator]
  (case operator
    "and" "&&"
    "or" "||"
    "=" "=="
    "/=" "!="
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
  "Generate Java code for binary expression"
  [{:keys [operator left right]}]
  (let [left-code (generate-expression left)
        right-code (generate-expression right)
        op (generate-binary-op operator)]
    (str "(" left-code " " op " " right-code ")")))

(defn generate-unary-expr
  "Generate Java code for unary expression"
  [{:keys [operator operand]}]
  (let [operand-code (generate-expression operand)
        op (case operator
             "not" "!"
             "-" "-"
             operator)]
    (str op operand-code)))

(defn generate-call-expr
  "Generate Java code for method call"
  [{:keys [target method args]}]
  (let [args-code (str/join ", " (map generate-expression args))]
    (if target
      (str target "." method "(" args-code ")")
      (str method "(" args-code ")"))))

(defn generate-create-expr
  "Generate Java code for create expression"
  [{:keys [class-name constructor args]}]
  (let [args-code (str/join ", " (map generate-expression args))]
    (str "new " class-name "(" args-code ")")))

(defn generate-subscript-expr
  "Generate Java code for subscript access (array/map access)"
  [{:keys [target index]}]
  (let [target-code (generate-expression target)
        index-code (generate-expression index)]
    (str target-code ".get(" index-code ")")))

(defn generate-array-literal
  "Generate Java code for array literal"
  [elements]
  (let [elements-code (str/join ", " (map generate-expression elements))]
    (str "new ArrayList<>(Arrays.asList(" elements-code "))")))

(defn generate-map-literal
  "Generate Java code for map literal"
  [entries]
  (let [entries-code (str/join "; "
                               (map (fn [{:keys [key value]}]
                                     (str "put("
                                          (generate-expression key) ", "
                                          (generate-expression value) ")"))
                                   entries))]
    (str "new HashMap<>() {{ " entries-code "; }}")))

(defn generate-expression
  "Generate Java code for an expression"
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
  "Generate Java code for assignment"
  [{:keys [target value]}]
  (str target " = " (generate-expression value) ";"))

(defn generate-let
  "Generate Java code for let (local variable declaration)"
  [{:keys [name var-type value]}]
  (if var-type
    ;; With type: "int x = 10;"
    (str (nex-type-to-java var-type) " " name " = " (generate-expression value) ";")
    ;; Without type: "x = 10;"
    (str name " = " (generate-expression value) ";")))

(defn generate-if
  "Generate Java code for if-then-else"
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
  "Generate Java code for scoped block"
  [level {:keys [body]}]
  (let [statements (map #(generate-statement (+ level 1) %) body)]
    (str/join "\n"
              [(indent level "{")
               (str/join "\n" statements)
               (indent level "}")])))

(defn generate-loop
  "Generate Java code for from-until-do loop"
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
  "Generate Java code for a statement"
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
  "Generate Java assertions for contracts"
  [level assertions contract-type opts]
  (when (and (seq assertions) (not (:skip-contracts opts)))
    (map (fn [{:keys [label condition]}]
           (indent level
                   (str "assert " (generate-expression condition)
                        " : \"" contract-type " violation: " label "\";")))
         assertions)))

;;
;; Visibility Conversion
;;

(defn visibility-to-java
  "Convert Nex visibility to Java visibility modifier"
  [visibility]
  (case (:type visibility)
    :private "private"
    :public "public"
    :selective (str "/* Visible to: " (str/join ", " (:classes visibility)) " */")
    "public"))

;;
;; Method Generation
;;

(defn generate-method
  "Generate Java code for a method"
  [level {:keys [name params return-type body require ensure visibility]} opts]
  (let [java-return (if return-type
                      (nex-type-to-java return-type)
                      "void")
        params-code (str/join ", "
                              (map (fn [{:keys [name type]}]
                                     (str (nex-type-to-java type) " " name))
                                   params))
        vis (if visibility
             (visibility-to-java visibility)
             "public")
        preconditions (generate-assertions (+ level 1) require "Precondition" opts)
        statements (map #(generate-statement (+ level 1) %) body)
        postconditions (generate-assertions (+ level 1) ensure "Postcondition" opts)]
    (str/join "\n"
              (concat
               [(indent level (str vis " " java-return " " name "(" params-code ") {"))]
               preconditions
               statements
               postconditions
               [(indent level "}")]))))

;;
;; Field Generation
;;

(defn generate-field
  "Generate Java code for a field with default initialization"
  [level {:keys [name field-type visibility]}]
  (let [;; Default fields to private, unless explicitly marked otherwise
        vis (if (and visibility (not= (:type visibility) :public))
             (visibility-to-java visibility)
             "private")
        java-type (nex-type-to-java field-type)
        init-value (default-value field-type)]
    (indent level (str vis " " java-type " " name " = " init-value ";"))))

;;
;; Constructor Generation
;;

(defn generate-constructor
  "Generate Java code for a constructor"
  [level class-name {:keys [params body require ensure]} opts]
  (let [params-code (str/join ", "
                              (map (fn [{:keys [name type]}]
                                     (str (nex-type-to-java type) " " name))
                                   params))
        preconditions (generate-assertions (+ level 1) require "Precondition" opts)
        statements (map #(generate-statement (+ level 1) %) body)
        postconditions (generate-assertions (+ level 1) ensure "Postcondition" opts)]
    (str/join "\n"
              (concat
               [(indent level (str "public " class-name "(" params-code ") {"))]
               preconditions
               statements
               postconditions
               [(indent level "}")]))))

;;
;; Inheritance Handling
;;

(defn analyze-inheritance
  "Analyze inheritance structure to determine extends/implements"
  [parents]
  (if (empty? parents)
    {:extends nil :implements []}
    (if (= 1 (count parents))
      {:extends (:parent (first parents))
       :implements []}
      ;; Multiple inheritance: first parent is extends, rest are interfaces
      {:extends (:parent (first parents))
       :implements (map :parent (rest parents))})))

(defn generate-generic-params
  "Generate Java generic parameters from Nex generic params"
  [generic-params]
  (when (seq generic-params)
    (let [params-str (str/join ", "
                               (map (fn [{:keys [name constraint]}]
                                     (if constraint
                                       (str name " extends " constraint)
                                       name))
                                   generic-params))]
      (str "<" params-str ">"))))

(defn generate-class-header
  "Generate Java class header with inheritance and generics"
  [class-name generic-params parents]
  (let [{:keys [extends implements]} (analyze-inheritance parents)
        generics (generate-generic-params generic-params)
        extends-clause (when extends (str " extends " extends))
        implements-clause (when (seq implements)
                           (str " implements " (str/join ", " implements)))]
    (str "public class " class-name generics extends-clause implements-clause " {")))

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
  "Generate Java code for a Nex class"
  ([class-def] (generate-class class-def {}))
  ([{:keys [name generic-params parents body invariant]} opts]
   (let [{:keys [fields methods constructors]} (extract-members body)
         class-header (generate-class-header name generic-params parents)
         invariant-comment (when (and invariant (not (:skip-contracts opts)))
                            (indent 1 (str "// Class invariant: "
                                          (str/join ", " (map :label invariant)))))
         fields-code (map #(generate-field 1 %) fields)
         constructors-code (map #(generate-constructor 1 name % opts) constructors)
         methods-code (map #(generate-method 1 % opts) methods)]
     (str/join "\n"
               (concat
                [class-header]
                (when invariant-comment [invariant-comment ""])
                fields-code
                (when (seq fields) [""])
                constructors-code
                (when (seq constructors) [""])
                methods-code
                ["}"])))))

;;
;; Main Translation Function
;;

(defn translate-ast
  "Translate a Nex AST to Java code

  Options:
    :skip-contracts - When true, omits all preconditions, postconditions,
                      and class invariants from generated code (useful for production)"
  ([ast] (translate-ast ast {}))
  ([ast opts]
   (let [classes (:classes ast)
         java-classes (map #(generate-class % opts) classes)]
     (str/join "\n\n" java-classes))))

(defn translate
  "Translate Nex source code to Java

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
  "Translate a Nex file to Java and optionally save it

  Options:
    :skip-contracts - When true, omits all contracts (useful for production)"
  ([nex-file] (translate-file nex-file nil {}))
  ([nex-file output-file] (translate-file nex-file output-file {}))
  ([nex-file output-file opts]
   (let [nex-code (slurp nex-file)
         java-code (translate nex-code opts)]
     (if output-file
       (spit output-file java-code)
       java-code))))

;;
;; Pretty Printing
;;

(defn print-translation
  "Print a nicely formatted translation with header"
  [nex-code]
  (println "╔════════════════════════════════════════════════════════════╗")
  (println "║                  NEX TO JAVA TRANSLATOR                    ║")
  (println "╚════════════════════════════════════════════════════════════╝")
  (println)
  (println "NEX CODE:")
  (println "─────────────────────────────────────────────────────────────")
  (println nex-code)
  (println)
  (println "JAVA CODE:")
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
