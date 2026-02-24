(ns nex.generator.java
  "Translates Nex (Eiffel-based) code to Java"
  (:require [nex.parser :as p]
            [nex.typechecker :as tc]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.io :as io])
  (:import [java.util.jar JarOutputStream JarEntry Manifest]
           [java.io FileInputStream ByteArrayInputStream]))

(def ^:dynamic *function-names* #{})
(def ^:dynamic *this-name* "this")
(def ^:dynamic *class-registry* {})
(def ^:dynamic *current-parents* #{})
(def ^:dynamic *parent-field-map* {})   ;; field-name -> "_parent_X" prefix
(def ^:dynamic *own-fields* #{})        ;; field names defined on current class
(def ^:dynamic *local-names* #{})       ;; method params + loop vars (shadow parent fields)
(def ^:dynamic *all-method-names* #{})  ;; own + delegated parent method names
(def ^:dynamic *field-types* {})        ;; field-name -> type, own + parent

(defn class-name-to-local
  "Convert a class name to a local variable name (e.g., 'Point' -> 'point')"
  [class-name]
  (str (Character/toLowerCase ^char (first class-name)) (subs class-name 1)))

;;
;; Type Mapping
;;

(defn nex-type-to-java-boxed
  "Convert Nex type to Java boxed type (for generics)"
  [nex-type]
  (if (nil? nex-type)
    "Object"
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
      "Console" "Object"
      "File" "java.io.File"
      "Process" "Object"
      "Function" "Function"
      "Any" "Object"
      nex-type)))

(defn nex-type-to-java
  "Convert Nex type to Java type
   use-boxed? - if true, use boxed types for primitives (needed for generics)"
  ([nex-type] (nex-type-to-java nex-type false))
  ([nex-type use-boxed?]
   (cond
     (nil? nex-type) "Object"
     (= nex-type "Any") "Object"
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
        "Console" "Object"
        "File" "java.io.File"
        "Process" "Object"
        "Function" "Function"
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
      "Console" "new Object() /* Console */"
      "File" "null"
      "Process" "new Object() /* Process */"
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
    "%" "%"
    operator))

(declare generate-expression)
(declare is-parent-constructor?)
(declare generate-method)
(declare resolve-field-name)

(defn generate-binary-expr
  "Generate Java code for binary expression"
  [{:keys [operator left right]}]
  (let [left-code (generate-expression left)
        right-code (generate-expression right)
        op (generate-binary-op operator)]
    (str "(" left-code " " op " " right-code ")")))

(defn generate-unary-expr
  "Generate Java code for unary expression"
  [{:keys [operator expr]}]
  (let [operand-code (generate-expression expr)
        op (case operator
             "not" "!"
             "-" "-"
             operator)]
    (str op operand-code)))

(defn map-builtin-function
  "Map Nex built-in functions to Java equivalents"
  [method args-code num-args]
  (case method
    "print" (str "System.out.print(" args-code ")")
    "println" (str "System.out.println(" args-code ")")
    ;; Default: use as-is (regular method call)
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
    "remove"       (fn [target args] (str "(" target ".remove(" args "), " target ")"))}

   :Console
   {"print"        (fn [_ args] (str "System.out.print(" args ")"))
    "print_line"   (fn [_ args] (str "System.out.println(" args ")"))
    "read_line"    (fn [_ args] (if (empty? args)
                                  "new java.util.Scanner(System.in).nextLine()"
                                  (str "new java.util.Scanner(System.in).nextLine() /* prompt: " args " */")))
    "error"        (fn [_ args] (str "System.err.println(" args ")"))
    "new_line"     (fn [_ _] "System.out.println()")
    "read_integer" (fn [_ _] "Integer.parseInt(new java.util.Scanner(System.in).nextLine().trim())")
    "read_real"    (fn [_ _] "Double.parseDouble(new java.util.Scanner(System.in).nextLine().trim())")}

   :File
   {"read"   (fn [t _] (str "java.nio.file.Files.readString(" t ".toPath())"))
    "write"  (fn [t a] (str "java.nio.file.Files.writeString(" t ".toPath(), " a ")"))
    "append" (fn [t a] (str "java.nio.file.Files.writeString(" t ".toPath(), " a ", java.nio.file.StandardOpenOption.APPEND)"))
    "exists" (fn [t _] (str t ".exists()"))
    "delete" (fn [t _] (str t ".delete()"))
    "lines"  (fn [t _] (str "new ArrayList<>(java.nio.file.Files.readAllLines(" t ".toPath()))"))
    "close"  (fn [t _] (str "/* " t ".close() */"))}

   :Process
   {"getenv"       (fn [_ a] (str "System.getenv(" a ")"))
    "setenv"       (fn [_ a] (str "/* setenv not supported in Java: " a " */"))
    "command_line" (fn [_ _] "new ArrayList<>(java.util.Arrays.asList(args))")}})

(defn function-type?
  "Check if a type is Function (string or map with base-type Function)"
  [t]
  (or (= t "Function")
      (and (map? t) (= (:base-type t) "Function"))))

(defn resolve-identifier
  "Resolve an identifier in expression context.
   Resolution order: locals -> methods -> own fields -> parent fields -> functions -> bare name"
  [id-name]
  (cond
    (contains? *local-names* id-name) id-name
    (contains? *all-method-names* id-name)
    (if (= *this-name* "this")
      (str id-name "()")
      (str *this-name* "." id-name "()"))
    (contains? *own-fields* id-name) id-name
    (contains? *parent-field-map* id-name)
    (let [parent-prefix (get *parent-field-map* id-name)]
      (if (= *this-name* "this")
        (str parent-prefix "." id-name)
        (str *this-name* "." parent-prefix "." id-name)))
    (contains? *function-names* id-name) (str "NexGlobals." id-name)
    :else id-name))

(defn generate-call-expr
  "Generate Java code for method call.
   NOTE: For operator methods (plus, less_than, etc.) that exist on multiple types,
   we try Integer methods first since numeric operations are more common.
   For string operations, use string literals or string-specific methods."
  [{:keys [target method args] :as call-node}]
  (let [args-code (str/join ", " (map generate-expression args))
        num-args (count args)]
    (if target
      ;; Object method call
      (let [target-code (if (string? target) target (generate-expression target))
            this-target? (and (map? target) (= :this (:type target)))
            has-parens (:has-parens call-node)]
        (if (nil? method)
          ;; Calling an expression that returns a function
          (str target-code ".call" num-args "(" args-code ")")
          ;; Check if target is a parent class name (composition delegation)
          (if (and (string? target) (contains? *current-parents* target))
            ;; Parent-qualified call: delegate through composition field
            (let [prefix (if (= *this-name* "this")
                           ""
                           (str *this-name* "."))]
              (str prefix "_parent_" target "." method "(" args-code ")"))
            ;; Check for this-target with has-parens distinction
            (if this-target?
              (if (false? has-parens)
                ;; this.x (no parens): method -> call, field -> access
                (if (contains? *all-method-names* method)
                  (str target-code "." method "()")
                  (str target-code "." method))
                ;; this.x() or this.x(args) (with parens or absent)
                (if (and (not (contains? *all-method-names* method))
                         (function-type? (get *field-types* method)))
                  (str target-code "." method ".call" num-args "(" args-code ")")
                  (str target-code "." method "(" args-code ")")))
              ;; External object: try builtins, then default
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
               (str target-code "." method "(" args-code ")"))))))
      ;; No target: class method, function object field, global function, or builtin
      (cond
        ;; Class method (own or inherited via delegation)
        (and method (contains? *all-method-names* method))
        (if (= *this-name* "this")
          (str method "(" args-code ")")
          (str *this-name* "." method "(" args-code ")"))

        ;; Function-typed field: call the function object
        (and method (function-type? (get *field-types* method)))
        (let [field-ref (resolve-field-name method)]
          (str field-ref ".call" num-args "(" args-code ")"))

        ;; Global function
        (and method (contains? *function-names* method))
        (str "NexGlobals." method ".call" num-args "(" args-code ")")

        ;; Expression that returns a function
        (nil? method)
        (str (generate-expression target) ".call" num-args "(" args-code ")")

        ;; Builtin
        :else
        (map-builtin-function method args-code num-args)))))

(defn generate-create-expr
  "Generate Java code for create expression"
  [{:keys [class-name generic-args constructor args]}]
  (let [args-code (str/join ", " (map generate-expression args))
        type-params (when (seq generic-args)
                      (str "<" (str/join ", " (map nex-type-to-java-boxed generic-args)) ">"))]
    (case class-name
      "Console" "new Object() /* Console */"
      "File" (str "new java.io.File(" args-code ")")
      "Process" "new Object() /* Process */"
      (if constructor
        ;; Named constructor: static factory method call
        (str class-name (or type-params "") "." constructor "(" args-code ")")
        ;; Default: new ClassName()
        (str "new " class-name (or type-params "") "()")))))

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
    :nil "null"
    :identifier (resolve-identifier (:name expr))
    :binary (generate-binary-expr expr)
    :unary (generate-unary-expr expr)
    :call (generate-call-expr expr)
    :create (generate-create-expr expr)
    :subscript (generate-subscript-expr expr)
    :array-literal (generate-array-literal (:elements expr))
    :map-literal (generate-map-literal (:entries expr))
    :anonymous-function (let [class-def (:class-def expr)
                              ;; Extract the callN method definition
                              method-def (first (:members (first (:body class-def))))
                              num-args (count (:params method-def))
                              method-name (:name method-def)
                              params (:params method-def)
                              return-type (:return-type method-def)]
                          (str "(new Function() {\n"
                               (indent 1 (str "@Override public Object call" num-args "("
                                              (str/join ", " (map #(str "Object arg" %) (range 1 (inc num-args))))
                                              ") {\n"))
                               (indent 2 (str "return this." method-name "("
                                              (str/join ", " (map (fn [i p]
                                                                    (str "(" (nex-type-to-java-boxed (:type p)) ")arg" i))
                                                                  (range 1 (inc num-args))
                                                                  params))
                                              ");\n"))
                               (indent 1 "}\n")
                               (generate-method 1 method-def {})
                               "\n" (indent 0 "})")))
    :when (str "(" (generate-expression (:condition expr)) " ? " (generate-expression (:consequent expr)) " : " (generate-expression (:alternative expr)) ")")
    :old (str "old_" (generate-expression (:expr expr)))
    :this *this-name*
    (str "/* Unknown expression: " (:type expr) " */")))

;;
;; Statement Generation
;;

(declare generate-statement)

(defn generate-assignment
  "Generate Java code for assignment"
  [{:keys [target value]}]
  (str (resolve-field-name target) " = " (generate-expression value) ";"))

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
  "Generate Java code for if/elseif/else"
  [level {:keys [condition then elseif else]} var-names]
  (let [cond-code (generate-expression condition)
        then-code (map #(generate-statement (+ level 1) % var-names) then)
        elseif-parts (mapcat (fn [clause]
                               [(indent level (str "} else if (" (generate-expression (:condition clause)) ") {"))
                                (str/join "\n" (map #(generate-statement (+ level 1) % var-names) (:then clause)))])
                             elseif)
        else-part (when else
                    [(indent level "} else {")
                     (str/join "\n" (map #(generate-statement (+ level 1) % var-names) else))])]
    (str/join "\n"
              (concat
               [(indent level (str "if (" cond-code ") {"))
                (str/join "\n" then-code)]
               elseif-parts
               else-part
               [(indent level "}")]))))

(defn has-retry?
  "Check if statements contain a :retry node"
  [stmts]
  (some (fn [stmt]
          (case (:type stmt)
            :retry true
            :if (or (has-retry? (:then stmt))
                    (some #(has-retry? (:then %)) (:elseif stmt))
                    (has-retry? (:else stmt)))
            :scoped-block (has-retry? (:body stmt))
            false))
        stmts))

(defn ends-with-retry?
  "Check if the last statement is a :retry"
  [stmts]
  (= :retry (:type (last stmts))))

(defn generate-rescue
  "Generate Java try/catch with optional retry loop"
  [level body rescue var-names]
  (let [body-stmts (map #(generate-statement (+ level 2) % var-names) body)
        rescue-stmts (map #(generate-statement (+ level 2) % var-names) rescue)
        has-retry (has-retry? rescue)
        needs-throw (not (ends-with-retry? rescue))]
    (if has-retry
      ;; while(true) { try { body; break; } catch (...) { rescue; throw?; } }
      (str/join "\n"
        (concat
          [(indent level "while (true) {")]
          [(indent (+ level 1) "try {")]
          body-stmts
          [(indent (+ level 2) "break;")]
          [(indent (+ level 1) "} catch (Exception _nex_e) {")]
          [(indent (+ level 2) "var exception = _nex_e.getMessage();")]
          rescue-stmts
          (when needs-throw [(indent (+ level 2) "throw _nex_e;")])
          [(indent (+ level 1) "}")]
          [(indent level "}")]))
      ;; try { body; } catch (...) { rescue; throw; }
      (str/join "\n"
        (concat
          [(indent level "try {")]
          body-stmts
          [(indent level "} catch (Exception _nex_e) {")]
          [(indent (+ level 1) "var exception = _nex_e.getMessage();")]
          rescue-stmts
          [(indent (+ level 1) "throw _nex_e;")]
          [(indent level "}")])))))

(defn generate-scoped-block
  "Generate Java code for scoped block"
  [level {:keys [body rescue]} var-names]
  (if rescue
    (generate-rescue level body rescue var-names)
    (let [statements (map #(generate-statement (+ level 1) % var-names) body)]
      (str/join "\n"
                [(indent level "{")
                 (str/join "\n" statements)
                 (indent level "}")]))))

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
        loop-vars (extract-var-names init)]
    (binding [*local-names* (into *local-names* loop-vars)]
      (let [;; Generate init statements
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
               [(indent level "}")]))))))

(defn generate-case
  "Generate Java code for case statement as switch"
  [level {:keys [expr clauses else]} var-names]
  (let [expr-code (generate-expression expr)
        case-parts (mapcat (fn [{:keys [values body]}]
                             (let [labels (map #(indent (+ level 1)
                                                        (str "case " (generate-expression %) ":"))
                                               values)
                                   body-code (generate-statement (+ level 2) body var-names)]
                               (concat labels
                                       [body-code
                                        (indent (+ level 2) "break;")])))
                           clauses)
        default-part (when else
                       [(indent (+ level 1) "default:")
                        (generate-statement (+ level 2) else var-names)
                        (indent (+ level 2) "break;")])
        no-else-default (when-not else
                          [(indent (+ level 1) "default:")
                           (indent (+ level 2) "throw new RuntimeException(\"No matching case\");")])]
    (str/join "\n"
              (concat
               [(indent level (str "switch (" expr-code ") {"))]
               case-parts
               (or default-part no-else-default)
               [(indent level "}")]))))

(defn generate-statement
  "Generate Java code for a statement"
  ([level stmt] (generate-statement level stmt #{}))
  ([level stmt var-names]
   (case (:type stmt)
     :assign (indent level (generate-assignment stmt))
     :call (let [{:keys [target method]} stmt]
             (if (and (string? target)
                      (contains? *current-parents* target)
                      (not= *this-name* "this")
                      (or (is-parent-constructor? target method)
                          ;; When parent not in registry, assume constructor
                          ;; if we're inside a constructor body
                          (nil? (get *class-registry* target))))
               ;; Parent constructor call: reassign composition field
               (let [prefix (str *this-name* ".")
                     args-code (str/join ", " (map generate-expression (:args stmt)))]
                 (indent level (str prefix "_parent_" target " = "
                                    target "." method "(" args-code ");")))
               (indent level (str (generate-call-expr stmt) ";"))))
     :let (indent level (generate-let stmt var-names))
     :if (generate-if level stmt var-names)
     :case (generate-case level stmt var-names)
     :scoped-block (generate-scoped-block level stmt var-names)
     :loop (generate-loop level stmt)
     :with (when (= (:target stmt) "java")
             ;; Only include this block if target is "java"
             (str/join "\n" (map #(generate-statement level % var-names) (:body stmt))))
     :raise (indent level (str "throw new RuntimeException(String.valueOf("
                                (generate-expression (:value stmt)) "));"))
     :retry (indent level "continue;")
     :member-assign (let [field (:field stmt)]
                      (if (contains? *parent-field-map* field)
                        ;; Route through parent composition object
                        (let [parent-prefix (get *parent-field-map* field)]
                          (indent level
                            (str *this-name* "." parent-prefix "." field " = " (generate-expression (:value stmt)) ";")))
                        (indent level
                          (str *this-name* "." field " = " (generate-expression (:value stmt)) ";"))))
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
                                :unary (extract (:expr expr))
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
  [level {:keys [name params return-type body require ensure rescue visibility note]} opts]
  (let [param-names (set (map :name params))
        local-names (cond-> param-names
                      return-type (conj "result"))]
    (binding [*local-names* (into *local-names* local-names)]
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
                                       (str "var old_" field-name " = " (resolve-field-name field-name) ";")))
                              old-refs))
            preconditions (generate-assertions (+ level 1) require "Precondition" opts)
            statements (if rescue
                         [(generate-rescue (+ level 1) body rescue #{})]
                         (map #(generate-statement (+ level 1) %) body))
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
                   [(indent level "}")]))))))

;;
;; Field Generation
;;

(defn generate-field
  "Generate Java code for a field with default initialization"
  [level {:keys [name field-type visibility note]}]
  (let [vis (if (and visibility (= (:type visibility) :private))
             "private"
             "public")
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
  "Generate Java static factory method for a Nex constructor"
  [level class-name {:keys [name params body require ensure rescue]} opts]
  (let [local-name (class-name-to-local class-name)
        param-names (set (map :name params))
        params-code (str/join ", "
                              (map (fn [{:keys [name type]}]
                                     (str (nex-type-to-java type) " " name))
                                   params))
        preconditions (generate-assertions (+ level 1) require "Precondition" opts)
        statements (binding [*this-name* local-name
                             *local-names* (into *local-names* param-names)]
                     (if rescue
                       [(generate-rescue (+ level 1) body rescue #{})]
                       (mapv #(generate-statement (+ level 1) %) body)))
        postconditions (generate-assertions (+ level 1) ensure "Postcondition" opts)]
    (str/join "\n"
              (concat
               [(indent level (str "public static " class-name " " name "(" params-code ") {"))]
               [(indent (+ level 1) (str class-name " " local-name " = new " class-name "();"))]
               preconditions
               statements
               postconditions
               [(indent (+ level 1) (str "return " local-name ";"))]
               [(indent level "}")]))))

;;
;; Inheritance Handling
;;

(defn analyze-inheritance
  "Return the list of parent names for composition-based inheritance"
  [parents]
  (mapv :parent parents))

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
  "Generate Java class header (no extends/implements - uses composition)"
  [class-name generic-params _parents]
  (let [generics (generate-generic-params generic-params)]
    (str "public class " class-name generics " {")))

(declare extract-members)

(defn generate-composition-fields
  "Generate private composition fields for each parent class"
  [level parents]
  (mapv (fn [parent-name]
          (indent level (str "private " parent-name " _parent_" parent-name
                             " = new " parent-name "();")))
        (mapv :parent parents)))

(defn get-parent-methods
  "Get all method names from a parent class definition via the class registry"
  [parent-name]
  (when-let [parent-def (get *class-registry* parent-name)]
    (let [{:keys [methods]} (extract-members (:body parent-def))]
      methods)))

(defn get-parent-fields
  "Get all field names from a parent class definition via the class registry"
  [parent-name]
  (when-let [parent-def (get *class-registry* parent-name)]
    (let [{:keys [fields]} (extract-members (:body parent-def))]
      (map :name fields))))

(defn build-parent-field-map
  "Build a map of field-name -> '_parent_X' for all parent fields.
   E.g. {'x' '_parent_A', 'speed' '_parent_Vehicle'}"
  [parents]
  (reduce (fn [m parent-name]
            (let [fields (get-parent-fields parent-name)]
              (reduce (fn [m2 field-name]
                        (if (contains? m2 field-name)
                          m2  ;; first parent wins (no override)
                          (assoc m2 field-name (str "_parent_" parent-name))))
                      m fields)))
          {} parents))

(defn resolve-field-name
  "Resolve a field name: locals shadow own fields shadow parent fields.
   For parent fields, returns '_parent_X.name' (with this-name prefix in constructor context)."
  [field-name]
  (cond
    (contains? *local-names* field-name) field-name
    (contains? *own-fields* field-name) field-name
    (contains? *parent-field-map* field-name)
    (let [parent-prefix (get *parent-field-map* field-name)]
      (if (= *this-name* "this")
        (str parent-prefix "." field-name)
        (str *this-name* "." parent-prefix "." field-name)))
    :else field-name))

(defn get-all-method-names
  "Union of own method names + parent method names (delegation methods exist on the class)"
  [parent-names own-method-names]
  (into own-method-names
        (mapcat (fn [parent-name]
                  (map :name (get-parent-methods parent-name)))
                parent-names)))

(defn build-field-types
  "Build field-name -> type map from own + parent fields"
  [fields parent-names]
  (let [own (into {} (map (fn [f] [(:name f) (:field-type f)]) fields))
        parent-flds (reduce (fn [m parent-name]
                              (if-let [parent-def (get *class-registry* parent-name)]
                                (let [{pfields :fields} (extract-members (:body parent-def))]
                                  (reduce (fn [m2 f]
                                            (if (contains? m2 (:name f))
                                              m2
                                              (assoc m2 (:name f) (:field-type f))))
                                          m pfields))
                                m))
                            {} parent-names)]
    (merge parent-flds own)))

(defn generate-delegation-methods
  "Generate delegation methods for inherited methods not overridden by the child"
  [level parents own-method-names]
  (vec
   (mapcat
    (fn [{:keys [parent]}]
      (let [parent-methods (get-parent-methods parent)]
        (keep
         (fn [{:keys [name params return-type]}]
           (when-not (contains? own-method-names name)
             (let [java-return (if return-type
                                 (nex-type-to-java return-type)
                                 "void")
                   params-code (str/join ", "
                                        (map (fn [{:keys [name type]}]
                                               (str (nex-type-to-java type) " " name))
                                             params))
                   args-code (str/join ", " (map :name params))
                   call-str (str "_parent_" parent "." name "(" args-code ")")
                   body-str (if return-type
                              (str "return " call-str ";")
                              (str call-str ";"))]
               (str (indent level (str "public " java-return " " name "(" params-code ") {"))
                    "\n"
                    (indent (+ level 1) body-str)
                    "\n"
                    (indent level "}")))))
         parent-methods)))
    parents)))

(defn is-parent-constructor?
  "Check if a method name is a constructor on a given parent class"
  [parent-name method-name]
  (when-let [parent-def (get *class-registry* parent-name)]
    (let [{:keys [constructors]} (extract-members (:body parent-def))]
      (some #(= (:name %) method-name) constructors))))

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
         parent-names (mapv :parent parents)
         parent-fm (build-parent-field-map parent-names)
         own-flds (set (map :name fields))
         own-method-names (set (map :name methods))
         all-methods (get-all-method-names parent-names own-method-names)
         fld-types (build-field-types fields parent-names)]
    (binding [*current-parents* (set parent-names)
              *parent-field-map* parent-fm
              *own-fields* own-flds
              *all-method-names* all-methods
              *field-types* fld-types]
     (let [
           ;; Generate class Javadoc if note present
           class-javadoc (when note
                          [(generate-javadoc 0 note)])
           class-header (generate-class-header name generic-params parents)
           ;; Composition fields for parent classes
           composition-fields (when (seq parents)
                                (generate-composition-fields 1 parents))
           invariant-comment (when (and invariant (not (:skip-contracts opts)))
                              (indent 1 (str "// Class invariant: "
                                            (str/join ", " (map :label invariant)))))
           fields-code (map #(generate-field 1 %) fields)
           ;; Delegation methods for inherited methods not overridden
           delegation-methods (when (seq parents)
                                (generate-delegation-methods 1 parents own-method-names))
           constructors-code (map #(generate-constructor 1 name % opts) constructors)
           methods-code (map #(generate-method 1 % opts) methods)]
       (str/join "\n"
                 (concat
                  class-javadoc
                  [class-header]
                  (when invariant-comment [invariant-comment ""])
                  composition-fields
                  (when (seq composition-fields) [""])
                  fields-code
                  (when (seq fields) [""])
                  (when (seq delegation-methods) delegation-methods)
                  (when (seq delegation-methods) [""])
                  constructors-code
                  (when (seq constructors) [""])
                  methods-code
                  ["}"])))))))

(defn generate-function-base-class
  "Generate the built-in Function base class."
  []
  (let [method-lines
        (map (fn [n]
               (let [params (str/join ", " (map (fn [i] (str "Object arg" i))
                                                (range 1 (inc n))))]
                 (str "  public Object call" n "(" params ") { return null; }")))
             (range 0 33))]
    (str "public class Function {\n"
         (str/join "\n" method-lines)
         "\n}")))

(defn generate-function-globals
  "Generate a globals holder for function instances."
  [functions]
  (when (seq functions)
    (let [lines (map (fn [{:keys [name class-name]}]
                       (str "  public static final " class-name " " name
                            " = new " class-name "();"))
                     functions)]
      (str "public class NexGlobals {\n"
           (str/join "\n" lines)
           "\n}"))))

;;
;; Main Class Generation
;;

(defn generate-main
  "Generate a Main class that instantiates the last user-defined class.
   If the class has a no-arg named constructor, calls ClassName.ctorName().
   Otherwise calls new ClassName()."
  [ast]
  (let [classes (:classes ast)
        last-class (last classes)
        class-name (:name last-class)
        {:keys [constructors]} (extract-members (:body last-class))
        no-arg-ctor (first (filter #(empty? (:params %)) constructors))
        call (if no-arg-ctor
               (str class-name "." (:name no-arg-ctor) "()")
               (str "new " class-name "()"))]
    (str "public class Main {\n"
         "    public static void main(String[] args) {\n"
         "        " call ";\n"
         "    }\n"
         "}")))

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
         functions (:functions ast)
         function-names (set (map :name functions))
         java-imports (keep generate-import imports)
         function-base (generate-function-base-class)
         function-globals (generate-function-globals functions)]
     (binding [*function-names* function-names
               *class-registry* (into {} (map (juxt :name identity) classes))]
       (let [java-classes (map #(generate-class % opts) classes)
             parts (concat java-imports
                           (when (seq java-imports) [""])
                           [function-base]
                           (when function-globals [""])
                           (when function-globals [function-globals])
                           (when (seq java-classes) [""])
                           java-classes)]
         (str/join "\n" (remove empty? parts)))))))

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

(defn compile-jar
  "Compile .java files in dir with javac, package into a JAR with Main-Class
   manifest, then delete the .java and .class files.
   Returns the path to the created JAR."
  [dir jar-name]
  (let [java-files (vec (filter #(str/ends-with? (.getName %) ".java")
                                (.listFiles (io/file dir))))
        jar-file (io/file dir (str jar-name ".jar"))]
    ;; Compile
    (let [javac-args (into-array String
                       (concat ["-d" (.getPath (io/file dir))]
                               (map #(.getPath %) java-files)))
          compiler (javax.tools.ToolProvider/getSystemJavaCompiler)
          exit-code (.run compiler nil nil nil javac-args)]
      (when-not (zero? exit-code)
        (throw (ex-info "javac compilation failed" {:exit-code exit-code}))))
    ;; Build JAR with manifest
    (let [manifest-str "Manifest-Version: 1.0\nMain-Class: Main\n\n"
          manifest (Manifest. (ByteArrayInputStream. (.getBytes manifest-str)))
          class-files (vec (filter #(str/ends-with? (.getName %) ".class")
                                   (.listFiles (io/file dir))))]
      (with-open [jar-out (JarOutputStream. (java.io.FileOutputStream. jar-file) manifest)]
        (doseq [cf class-files]
          (.putNextEntry jar-out (JarEntry. (.getName cf)))
          (io/copy cf jar-out)
          (.closeEntry jar-out))))
    ;; Delete .java and .class files
    (doseq [f (.listFiles (io/file dir))]
      (when (or (str/ends-with? (.getName f) ".java")
                (str/ends-with? (.getName f) ".class"))
        (.delete f)))
    (.getPath jar-file)))

(defn translate-file
  "Translate a Nex file to Java, compile to a JAR, and clean up.

  When output-dir is provided:
    1. Writes .java files to a temp build directory
    2. Compiles them with javac
    3. Packages into <jar-name>.jar (default: derived from nex filename)
    4. Moves the JAR to output-dir and deletes the temp build directory

  Returns a map of {:files {filename -> code-string}, :jar jar-path}.
  When output-dir is nil, returns just the files map (no compilation).

  Options:
    :skip-contracts - When true, omits all contracts (useful for production)
    :skip-type-check - When true, skips static type checking
    :jar-name - Base name for the JAR file (without .jar extension)"
  ([nex-file] (translate-file nex-file nil {}))
  ([nex-file output-dir] (translate-file nex-file output-dir {}))
  ([nex-file output-dir opts]
   (let [nex-code (slurp nex-file)
         ast (p/ast nex-code)
         _ (when-not (:skip-type-check opts)
             (let [result (tc/type-check ast)]
               (when-not (:success result)
                 (throw (ex-info "Type checking failed"
                                 {:errors (map tc/format-type-error (:errors result))})))))
         classes (:classes ast)
         functions (:functions ast)
         function-names (set (map :name functions))
         function-base (generate-function-base-class)
         function-globals (generate-function-globals functions)
         main-code (generate-main ast)
         class-codes (binding [*function-names* function-names
                              *class-registry* (into {} (map (juxt :name identity) classes))]
                       (mapv (fn [cls] [(:name cls) (generate-class cls opts)]) classes))
         files (into {"Function.java" function-base}
                     (concat
                      (when function-globals
                        [["NexGlobals.java" function-globals]])
                      (map (fn [[name code]] [(str name ".java") code]) class-codes)
                      [["Main.java" main-code]]))]
     (if output-dir
       (let [jar-name (or (:jar-name opts)
                          (-> (io/file nex-file)
                              (.getName)
                              (str/replace #"\.nex$" "")))
             build-dir (io/file (System/getProperty "java.io.tmpdir")
                                (str "nex-build-" (System/nanoTime)))
             _ (.mkdirs build-dir)
             _ (doseq [[filename code] files]
                 (spit (io/file build-dir filename) code))
             _ (compile-jar build-dir jar-name)
             out-dir (io/file output-dir)
             _ (.mkdirs out-dir)
             final-jar (io/file out-dir (str jar-name ".jar"))]
         (io/copy (io/file build-dir (str jar-name ".jar")) final-jar)
         ;; Clean up entire temp build directory
         (doseq [f (reverse (file-seq build-dir))]
           (.delete f))
         {:files files :jar (.getPath final-jar)})
       files))))

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
    (println "Usage: nex compile java <input.nex> [output-dir]")
    (System/exit 1))

  (let [input-file (first args)
        output-dir (when (> (count args) 1) (second args))]
    (try
      (let [result (translate-file input-file output-dir)]
        (if output-dir
          (println (str "Compiled " input-file " -> " (:jar result)))
          (doseq [[filename code] result]
            (println (str "// === " filename " ==="))
            (println code)
            (println))))
      (System/exit 0)
      (catch Exception e
        (println "Error:" (.getMessage e))
        (System/exit 1)))))
