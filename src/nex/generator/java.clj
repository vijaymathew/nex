(ns nex.generator.java
  "Translates Nex (Eiffel-based) code to Java"
  (:require [nex.parser :as p]
            [nex.typechecker :as tc]
            [clojure.string :as str]
            [clojure.set :as set]))

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
      "String" "\"\""
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

(defn generate-javadoc
  "Generate Javadoc comment for a note"
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

(defn map-builtin-function
  "Map Nex built-in functions to Java equivalents"
  [method args-code]
  (case method
    "print" (str "System.out.print(" args-code ")")
    "println" (str "System.out.println(" args-code ")")
    ;; Default: use as-is
    (str method "(" args-code ")")))

(def builtin-method-mappings
  "Map Nex built-in type methods to Java equivalents"
  {:String
   {"length"      (fn [target _] (str target ".length()"))
    "index_of"    (fn [target args] (str target ".indexOf(" args ")"))
    "substring"   (fn [target args] (str target ".substring(" args ")"))
    "to_upper"    (fn [target _] (str target ".toUpperCase()"))
    "to_lower"    (fn [target _] (str target ".toLowerCase()"))
    "contains"    (fn [target args] (str target ".contains(" args ")"))
    "starts_with" (fn [target args] (str target ".startsWith(" args ")"))
    "ends_with"   (fn [target args] (str target ".endsWith(" args ")"))
    "trim"        (fn [target _] (str target ".trim()"))
    "replace"     (fn [target args] (str target ".replace(" args ")"))
    "char_at"     (fn [target args] (str target ".charAt(" args ")"))
    "split"       (fn [target args] (str "new ArrayList<>(Arrays.asList(" target ".split(" args ")))"))
    ;; String operators
    "plus"        (fn [target args] (str "(" target " + " args ")"))
    "equals"      (fn [target args] (str target ".equals(" args ")"))
    "not_equals"  (fn [target args] (str "!" target ".equals(" args ")"))
    "less_than"   (fn [target args] (str "(" target ".compareTo(" args ") < 0)"))
    "less_than_or_equal" (fn [target args] (str "(" target ".compareTo(" args ") <= 0)"))
    "greater_than" (fn [target args] (str "(" target ".compareTo(" args ") > 0)"))
    "greater_than_or_equal" (fn [target args] (str "(" target ".compareTo(" args ") >= 0)"))}

   :Integer
   {"to_string" (fn [target _] (str "String.valueOf(" target ")"))
    "abs"       (fn [target _] (str "Math.abs(" target ")"))
    "min"       (fn [target args] (str "Math.min(" target ", " args ")"))
    "max"       (fn [target args] (str "Math.max(" target ", " args ")"))
    ;; Arithmetic operators
    "plus"      (fn [target args] (str "(" target " + " args ")"))
    "minus"     (fn [target args] (str "(" target " - " args ")"))
    "times"     (fn [target args] (str "(" target " * " args ")"))
    "divided_by" (fn [target args] (str "(" target " / " args ")"))
    ;; Comparison operators
    "equals"    (fn [target args] (str "(" target " == " args ")"))
    "not_equals" (fn [target args] (str "(" target " != " args ")"))
    "less_than" (fn [target args] (str "(" target " < " args ")"))
    "less_than_or_equal" (fn [target args] (str "(" target " <= " args ")"))
    "greater_than" (fn [target args] (str "(" target " > " args ")"))
    "greater_than_or_equal" (fn [target args] (str "(" target " >= " args ")"))}

   :Array
   {"length"    (fn [target _] (str target ".size()"))
    "is_empty"  (fn [target _] (str target ".isEmpty()"))
    "get"       (fn [target args] (str target ".get(" args ")"))
    "add"       (fn [target args] (str target ".add(" args ")"))
    "at"        (fn [target args] (str target ".add(" args ")"))
    "set"       (fn [target args] (str target ".set(" args ")"))
    "contains"  (fn [target args] (str target ".contains(" args ")"))
    "index_of"  (fn [target args] (str target ".indexOf(" args ")"))
    "remove"    (fn [target args] (str "(" target ".remove((int)" args "), " target ")"))
    "reverse"   (fn [target _] (str "new ArrayList<>(" target ".reversed())"))
    "sort"      (fn [target _] (str "(Collections.sort(" target "), " target ")"))
    "slice"     (fn [target args] (str "new ArrayList<>(" target ".subList(" args "))"))}

   :Map
   {"get"          (fn [target args] (str target ".get(" args ")"))
    "try_get"      (fn [target args] (str target ".get(" args ")"))
    "at"           (fn [target args] (str "(" target ".put(" args "), " target ")"))
    "size"         (fn [target _] (str target ".size()"))
    "is_empty"     (fn [target _] (str target ".isEmpty()"))
    "contains_key" (fn [target args] (str target ".containsKey(" args ")"))
    "keys"         (fn [target _] (str "new ArrayList<>(" target ".keySet())"))
    "values"       (fn [target _] (str "new ArrayList<>(" target ".values())"))
    "remove"       (fn [target args] (str "(" target ".remove(" args "), " target ")"))}})

(defn generate-call-expr
  "Generate Java code for method call.
   NOTE: For operator methods (plus, less_than, etc.) that exist on multiple types,
   we try Integer methods first since numeric operations are more common.
   For string operations, use string literals or string-specific methods."
  [{:keys [target method args] :as call-node}]
  (let [args-code (str/join ", " (map generate-expression args))]
    (if target
      ;; Object method call
      (let [target-code (if (string? target) target (generate-expression {:type :identifier :name target}))]
        (or
         ;; Try Integer methods first (for operators, numeric is more common)
         (when-let [method-fn (get-in builtin-method-mappings [:Integer method])]
           (method-fn target-code args-code))
         ;; Try String methods
         (when-let [method-fn (get-in builtin-method-mappings [:String method])]
           (method-fn target-code args-code))
         ;; Try Array methods (ArrayList in Java)
         (when-let [method-fn (get-in builtin-method-mappings [:Array method])]
           (method-fn target-code args-code))
         ;; Try Map methods
         (when-let [method-fn (get-in builtin-method-mappings [:Map method])]
           (method-fn target-code args-code))
         ;; Default: regular method call
         (str target-code "." method "(" args-code ")")))
      ;; Global function call: map builtins
      (map-builtin-function method args-code))))

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
    :old (str "old_" (generate-expression (:expr expr)))
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
  "Generate Java code for let (local variable declaration).
   If var-names is provided and contains the variable name, generates assignment instead."
  ([let-node] (generate-let let-node #{}))
  ([{:keys [name var-type value]} var-names]
   (if (contains? var-names name)
     ;; Variable already declared in outer scope - generate assignment
     (str name " = " (generate-expression value) ";")
     ;; New variable - generate declaration
     (if var-type
       ;; With type: "int x = 10;"
       (str (nex-type-to-java var-type) " " name " = " (generate-expression value) ";")
       ;; Without type: use 'var' for type inference (Java 10+)
       (str "var " name " = " (generate-expression value) ";")))))

(defn generate-if
  "Generate Java code for if-then-else"
  [level {:keys [condition then else]} var-names]
  (let [cond-code (generate-expression condition)
        then-code (map #(generate-statement (+ level 1) % var-names) then)
        else-code (map #(generate-statement (+ level 1) % var-names) else)]
    (str/join "\n"
              [(indent level (str "if (" cond-code ") {"))
               (str/join "\n" then-code)
               (indent level "} else {")
               (str/join "\n" else-code)
               (indent level "}")])))

(defn generate-scoped-block
  "Generate Java code for scoped block"
  [level {:keys [body]} var-names]
  (let [statements (map #(generate-statement (+ level 1) % var-names) body)]
    (str/join "\n"
              [(indent level "{")
               (str/join "\n" statements)
               (indent level "}")])))

(defn extract-var-names
  "Extract variable names from let statements"
  [stmts]
  (set (keep (fn [stmt]
               (when (= (:type stmt) :let)
                 (:name stmt)))
             stmts)))

(defn generate-loop
  "Generate Java code for from-until-do loop"
  [level {:keys [init invariant variant until body]}]
  (let [;; Extract variable names declared in init
        loop-vars (extract-var-names init)
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
  "Generate Java code for a statement"
  ([level stmt] (generate-statement level stmt #{}))
  ([level stmt var-names]
   (case (:type stmt)
     :assign (indent level (generate-assignment stmt))
     :call (indent level (str (generate-call-expr stmt) ";"))
     :let (indent level (generate-let stmt var-names))
     :if (generate-if level stmt var-names)
     :scoped-block (generate-scoped-block level stmt var-names)
     :loop (generate-loop level stmt)
     :with (when (= (:target stmt) "java")
             ;; Only include this block if target is "java"
             (str/join "\n" (map #(generate-statement level % var-names) (:body stmt))))
     (indent level (str "/* Unknown statement: " (:type stmt) " */")))))

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
  "Generate Java code for a method"
  [level {:keys [name params return-type body require ensure visibility note]} opts]
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
        ;; Generate Javadoc if note present
        javadoc (when note
                 [(generate-javadoc level note)])
        ;; Initialize result variable if method has return type
        result-init (when return-type
                     [(indent (+ level 1)
                             (str java-return " result = " (default-value return-type) ";"))])
        ;; Extract old references and generate capture statements
        old-refs (when ensure (extract-old-references ensure))
        old-captures (when (seq old-refs)
                      (map (fn [field-name]
                            (indent (+ level 1)
                                   (str "var old_" field-name " = " field-name ";")))
                          old-refs))
        preconditions (generate-assertions (+ level 1) require "Precondition" opts)
        statements (map #(generate-statement (+ level 1) %) body)
        postconditions (generate-assertions (+ level 1) ensure "Postcondition" opts)
        ;; Add return statement if method has return type
        return-stmt (when return-type
                     [(indent (+ level 1) "return result;")])]
    (str/join "\n"
              (concat
               javadoc
               [(indent level (str vis " " java-return " " name "(" params-code ") {"))]
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

(defn generate-field
  "Generate Java code for a field with default initialization"
  [level {:keys [name field-type visibility note]}]
  (let [;; Default fields to private, unless explicitly marked otherwise
        vis (if (and visibility (not= (:type visibility) :public))
             (visibility-to-java visibility)
             "private")
        java-type (nex-type-to-java field-type)
        init-value (default-value field-type)
        ;; Generate Javadoc if note present
        javadoc (when note
                 (generate-javadoc level note))]
    (if javadoc
      (str javadoc "\n" (indent level (str vis " " java-type " " name " = " init-value ";")))
      (indent level (str vis " " java-type " " name " = " init-value ";")))))

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
  ([{:keys [name generic-params parents body invariant note]} opts]
   (let [{:keys [fields methods constructors]} (extract-members body)
         ;; Generate class Javadoc if note present
         class-javadoc (when note
                        [(generate-javadoc 0 note)])
         class-header (generate-class-header name generic-params parents)
         invariant-comment (when (and invariant (not (:skip-contracts opts)))
                            (indent 1 (str "// Class invariant: "
                                          (str/join ", " (map :label invariant)))))
         fields-code (map #(generate-field 1 %) fields)
         constructors-code (map #(generate-constructor 1 name % opts) constructors)
         methods-code (map #(generate-method 1 % opts) methods)]
     (str/join "\n"
               (concat
                class-javadoc
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

(defn generate-import
  "Generate a Java import statement"
  [{:keys [qualified-name source]}]
  ;; Only generate Java imports (those without a 'source' field)
  (when-not source
    (str "import " qualified-name ";")))

(defn translate-ast
  "Translate a Nex AST to Java code

  Options:
    :skip-contracts - When true, omits all preconditions, postconditions,
                      and class invariants from generated code (useful for production)"
  ([ast] (translate-ast ast {}))
  ([ast opts]
   (let [imports (:imports ast)
         classes (:classes ast)
         java-imports (keep generate-import imports)
         java-classes (map #(generate-class % opts) classes)
         parts (concat java-imports [""] java-classes)] ; Empty string adds blank line after imports
     (str/join "\n" (remove empty? parts)))))

(defn translate
  "Translate Nex source code to Java

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


(defn -main
  "Main entry point for command-line compilation"
  [& args]
  (when (empty? args)
    (println "Usage: nex compile java <input.nex> [output.java]")
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
