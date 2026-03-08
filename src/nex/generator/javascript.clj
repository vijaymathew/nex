(ns nex.generator.javascript
  "Translates Nex (Eiffel-based) code to JavaScript (ES6+)"
  (:require [nex.parser :as p]
            [nex.typechecker :as tc]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.io :as io]))

(def ^:dynamic *function-names* #{})
(def ^:dynamic *this-name* "this")
(def ^:dynamic *all-method-names* #{})  ;; own + parent method names
(def ^:dynamic *own-fields* #{})        ;; field names defined on current class
(def ^:dynamic *local-names* #{})       ;; method params + loop vars
(def ^:dynamic *field-types* {})        ;; field-name -> type
(def ^:dynamic *class-invariants* [])   ;; effective class invariants (inherited + local, deduped)

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
    (nil? nex-type) "*"
    (= nex-type "Any") "*"
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
      "Window" "NexWindow"
      "Turtle" "NexTurtle"
      "Image" "NexImage"
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
      "Window" "null"
      "Turtle" "null"
      "Image" "null"
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
    "%" "%"
    "^" "**"
    operator))

(declare generate-expression)
(declare generate-method)

(defn function-type?
  "Check if a type is Function (string or map with base-type Function)"
  [t]
  (or (= t "Function")
      (and (map? t) (= (:base-type t) "Function"))))

(defn resolve-identifier
  "Resolve an identifier in expression context for JavaScript.
   Resolution order: locals -> methods -> fields -> functions -> bare name"
  [id-name]
  (cond
    (contains? *local-names* id-name) id-name
    (contains? *all-method-names* id-name) (str *this-name* "." id-name "()")
    (contains? *own-fields* id-name) (str *this-name* "." id-name)
    (contains? *function-names* id-name) (str "NexGlobals." id-name)
    :else id-name))

(defn generate-binary-expr
  "Generate JavaScript code for binary expression"
  [{:keys [operator left right]}]
  (let [left-code (generate-expression left)
        right-code (generate-expression right)
        op (generate-binary-op operator)]
    (str "(" left-code " " op " " right-code ")")))

(defn generate-unary-expr
  "Generate JavaScript code for unary expression"
  [{:keys [operator expr]}]
  (let [operand-code (generate-expression expr)
        op (case operator
             "not" "!"
             "-" "-"
             operator)]
    (str op operand-code)))

(defn map-builtin-function
  "Map Nex built-in functions to JavaScript equivalents"
  [method args-code num-args]
  (case method
    "print" (str "console.log(" args-code ")")
    "println" (str "console.log(" args-code ")")
    "type_of" (str "__nexTypeOf(" args-code ")")
    "type_is" (str "__nexTypeIs(" args-code ")")
    ;; Default: use as-is (regular method call)
    (str method "(" args-code ")")))

(def builtin-method-mappings
  "Map Nex built-in type methods to JavaScript equivalents"
  {:String
   {"length"      (fn [target _] (str target ".length"))
    "index_of"    (fn [target args] (str target ".indexOf(" args ")"))
    "substring"   (fn [target args] (str target ".substring(" args ")"))
    "to_upper"    (fn [target _] (str target ".toUpperCase()"))
    "to_lower"    (fn [target _] (str target ".toLowerCase()"))
    "to_integer"  (fn [target _] (str "parseInt(" target ".trim(), 10)"))
    "to_integer64" (fn [target _] (str "parseInt(" target ".trim(), 10)"))
    "to_real"     (fn [target _] (str "parseFloat(" target ".trim())"))
    "to_decimal"  (fn [target _] (str "parseFloat(" target ".trim())"))
    "contains"    (fn [target args] (str target ".includes(" args ")"))
    "starts_with" (fn [target args] (str target ".startsWith(" args ")"))
    "ends_with"   (fn [target args] (str target ".endsWith(" args ")"))
    "trim"        (fn [target _] (str target ".trim()"))
    "replace"     (fn [target args] (str target ".replace(" args ")"))
    "char_at"     (fn [target args] (str target ".charAt(" args ")"))
    "compare"     (fn [target args] (str "__nexCompare(" target ", " args ")"))
    "hash"        (fn [target _] (str "__nexHash(" target ")"))
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
    "pick"      (fn [target _] (str "Math.floor(Math.random() * " target")"))
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
    "greater_than_or_equal" (fn [target args] (str "(" target " >= " args ")"))
    "compare"   (fn [target args] (str "__nexCompare(" target ", " args ")"))
    "hash"      (fn [target _] (str "__nexHash(" target ")"))}

   :Integer64
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
    "greater_than_or_equal" (fn [target args] (str "(" target " >= " args ")"))
    "compare"   (fn [target args] (str "__nexCompare(" target ", " args ")"))
    "hash"      (fn [target _] (str "__nexHash(" target ")"))}

   :Real
   {"to_string" (fn [target _] (str target ".toString()"))
    "abs"       (fn [target _] (str "Math.abs(" target ")"))
    "min"       (fn [target args] (str "Math.min(" target ", " args ")"))
    "max"       (fn [target args] (str "Math.max(" target ", " args ")"))
    "round"     (fn [target _] (str "Math.round(" target ")"))
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
    "greater_than_or_equal" (fn [target args] (str "(" target " >= " args ")"))
    "compare"   (fn [target args] (str "__nexCompare(" target ", " args ")"))
    "hash"      (fn [target _] (str "__nexHash(" target ")"))}

   :Decimal
   {"to_string" (fn [target _] (str target ".toString()"))
    "abs"       (fn [target _] (str "Math.abs(" target ")"))
    "min"       (fn [target args] (str "Math.min(" target ", " args ")"))
    "max"       (fn [target args] (str "Math.max(" target ", " args ")"))
    "round"     (fn [target _] (str "Math.round(" target ")"))
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
    "greater_than_or_equal" (fn [target args] (str "(" target " >= " args ")"))
    "compare"   (fn [target args] (str "__nexCompare(" target ", " args ")"))
    "hash"      (fn [target _] (str "__nexHash(" target ")"))}

   :Char
   {"to_string" (fn [target _] (str "String(" target ")"))
    "to_upper"  (fn [target _] (str "String(" target ").toUpperCase()"))
    "to_lower"  (fn [target _] (str "String(" target ").toLowerCase()"))
    "compare"   (fn [target args] (str "__nexCompare(" target ", " args ")"))
    "hash"      (fn [target _] (str "__nexHash(" target ")"))}

   :Boolean
   {"to_string" (fn [target _] (str target ".toString()"))
    "and"       (fn [target args] (str "(" target " && " args ")"))
    "or"        (fn [target args] (str "(" target " || " args ")"))
    "not"       (fn [target _] (str "(!" target ")"))
    "equals"    (fn [target args] (str "(" target " === " args ")"))
    "not_equals" (fn [target args] (str "(" target " !== " args ")"))
    "compare"   (fn [target args] (str "__nexCompare(" target ", " args ")"))
    "hash"      (fn [target _] (str "__nexHash(" target ")"))}

   :Array
   {"length"    (fn [target _] (str target ".length"))
    "get"       (fn [target args] (str target "[" args "]"))
    "add_at"    (fn [target args] (str target ".set(" args ")"))
    "add"       (fn [target args] (str target ".push(" args ")"))
    "put"       (fn [target args] (str target ".set(" args ")"))
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
    "put"          (fn [target args] (str "(" target ".set(" args "), " target ")"))
    "contains_key" (fn [target args] (str target ".has(" args ")"))
    "keys"         (fn [target _] (str "Array.from(" target ".keys())"))
    "values"       (fn [target _] (str "Array.from(" target ".values())"))
    "remove"       (fn [target args] (str "(" target ".delete(" args "), " target ")"))}

   :Image
   {"width"        (fn [target _] (str target ".width"))
    "height"       (fn [target _] (str target ".height"))}

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
             ;; Try Integer64 methods
             (when-let [method-fn (get-in builtin-method-mappings [:Integer64 method])]
               (method-fn target-code args-code))
             ;; Try Real methods
             (when-let [method-fn (get-in builtin-method-mappings [:Real method])]
               (method-fn target-code args-code))
             ;; Try Decimal methods
             (when-let [method-fn (get-in builtin-method-mappings [:Decimal method])]
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
             ;; Try Image methods
             (when-let [method-fn (get-in builtin-method-mappings [:Image method])]
               (method-fn target-code args-code))
             ;; Default: regular method call
             (str target-code "." method "(" args-code ")")))))
      ;; No target: class method, function object field, global function, or builtin
      (cond
        ;; Class method (own or inherited)
        (and method (contains? *all-method-names* method))
        (if (= *this-name* "this")
          (str "this." method "(" args-code ")")
          (str *this-name* "." method "(" args-code ")"))

        ;; Function-typed field: call the function object
        (and method (function-type? (get *field-types* method)))
        (str "this." method ".call" num-args "(" args-code ")")

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
  "Generate JavaScript code for create expression"
  [{:keys [class-name generic-args constructor args]}]
  ;; JS has no generics syntax, generic-args is accepted but not emitted
  (let [args-code (str/join ", " (map generate-expression args))]
    (case class-name
      "Console" "({_type: 'Console'})"
      "File" (str "({_type: 'File', path: " args-code "})")
      "Process" "({_type: 'Process'})"
      "Window" (str "new NexWindow(" args-code ")")
      "Turtle" (str "new NexTurtle(" args-code ")")
      "Image" (if (= constructor "from_file")
                (str "NexImage.from_file(" args-code ")")
                (str "new NexImage(" args-code ")"))
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

(defn any-type?
  [t]
  (or (= t "Any")
      (and (map? t) (= (:base-type t) "Any"))))

(defn dedupe-convert-bindings
  "Dedupe convert bindings by variable name, preserving first occurrence order."
  [bindings]
  (second
   (reduce (fn [[seen acc] {:keys [name] :as binding}]
             (if (contains? seen name)
               [seen acc]
               [(conj seen name) (conj acc binding)]))
           [#{} []]
           bindings)))

(defn collect-convert-bindings-expr
  "Collect convert target bindings used by an expression."
  [expr]
  (letfn [(walk [e]
            (cond
              (nil? e) []
              (not (map? e)) []
              :else
              (case (:type e)
                :convert (into (walk (:value e))
                               [{:name (:var-name e) :target-type (:target-type e)}])
                :binary (into (walk (:left e)) (walk (:right e)))
                :unary (walk (:expr e))
                :call (into (if (map? (:target e)) (walk (:target e)) [])
                            (mapcat walk (:args e)))
                :create (mapcat walk (:args e))
                :subscript (into (walk (:target e)) (walk (:index e)))
                :array-literal (mapcat walk (:elements e))
                :map-literal (mapcat (fn [{:keys [key value]}]
                                       (into (walk key) (walk value)))
                                     (:entries e))
                :when (concat (walk (:condition e))
                              (walk (:consequent e))
                              (walk (:alternative e)))
                :old (walk (:expr e))
                [])))]
    (dedupe-convert-bindings (walk expr))))

(declare collect-convert-bindings-stmt)

(defn collect-convert-bindings-block
  "Collect convert bindings from statements in the current lexical block."
  [stmts]
  (dedupe-convert-bindings (mapcat collect-convert-bindings-stmt stmts)))

(defn collect-convert-bindings-stmt
  "Collect convert bindings from statement expressions in the current scope."
  [stmt]
  (case (:type stmt)
    :let (collect-convert-bindings-expr (:value stmt))
    :assign (collect-convert-bindings-expr (:value stmt))
    :member-assign (collect-convert-bindings-expr (:value stmt))
    :call (mapcat collect-convert-bindings-expr (:args stmt))
    :loop (concat
           (collect-convert-bindings-block (:init stmt))
           (collect-convert-bindings-expr (:until stmt))
           (collect-convert-bindings-block (:body stmt)))
    :case (concat
           (collect-convert-bindings-expr (:expr stmt))
           (mapcat (fn [{:keys [values body]}]
                     (concat (mapcat collect-convert-bindings-expr values)
                             (collect-convert-bindings-stmt body)))
                   (:clauses stmt))
           (when-let [else-stmt (:else stmt)]
             (collect-convert-bindings-stmt else-stmt)))
    ;; If guard convert variables are scoped to the if-construct itself.
    :if (concat
         (collect-convert-bindings-block (:then stmt))
         (mapcat (fn [clause] (collect-convert-bindings-block (:then clause)))
                 (:elseif stmt))
         (when-let [else-block (:else stmt)]
           (collect-convert-bindings-block else-block)))
    ;; Nested scoped blocks manage their own convert declarations.
    :scoped-block []
    :with (collect-convert-bindings-block (:body stmt))
    []))

(defn collect-if-guard-convert-bindings
  "Collect convert bindings introduced by an if/elseif condition chain."
  [{:keys [condition elseif]}]
  (dedupe-convert-bindings
   (concat (collect-convert-bindings-expr condition)
           (mapcat (fn [clause]
                     (collect-convert-bindings-expr (:condition clause)))
                   elseif))))

(defn generate-convert-declarations
  [level bindings]
  (map (fn [{:keys [name]}]
         (indent level (str "let " name " = null;")))
       bindings))

(defn js-convert-check
  [temp-name target-type]
  (if (map? target-type)
    (js-convert-check temp-name (:base-type target-type))
    (case target-type
      "Any" "true"
      "Integer" (str "(typeof " temp-name " === \"number\")")
      "Integer64" (str "(typeof " temp-name " === \"number\")")
      "Real" (str "(typeof " temp-name " === \"number\")")
      "Decimal" (str "(typeof " temp-name " === \"number\")")
      "String" (str "(typeof " temp-name " === \"string\")")
      "Boolean" (str "(typeof " temp-name " === \"boolean\")")
      "Char" (str "((typeof " temp-name " === \"string\") && " temp-name ".length === 1)")
      (str temp-name " instanceof " target-type))))

(defn generate-convert-expr
  [{:keys [value var-name target-type]}]
  (let [value-code (generate-expression value)
        temp-name (str (gensym "__nex_conv_tmp_"))
        cond-code (if (any-type? target-type)
                    "true"
                    (js-convert-check temp-name target-type))]
    (str "(() => { "
         "const " temp-name " = " value-code "; "
         "if (" cond-code ") { "
         var-name " = " temp-name "; return true; } "
         var-name " = null; return false; "
         "})()")))

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
    :identifier (resolve-identifier (:name expr))
    :binary (generate-binary-expr expr)
    :unary (generate-unary-expr expr)
    :call (generate-call-expr expr)
    :create (generate-create-expr expr)
    :subscript (generate-subscript-expr expr)
    :array-literal (generate-array-literal (:elements expr))
    :map-literal (generate-map-literal (:entries expr))
    :convert (generate-convert-expr expr)
    :anonymous-function (let [class-def (:class-def expr)
                              ;; Extract the callN method definition
                              method-def (first (:members (first (:body class-def))))]
                          (str "(new class extends Function {\n"
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
  "Generate JavaScript code for if/elseif/else"
  ([level node] (generate-if level node #{}))
  ([level {:keys [condition then elseif else] :as node} var-names]
   (let [guard-bindings (collect-if-guard-convert-bindings node)
         guard-var-names (set (map :name guard-bindings))
         statement-code
         (binding [*local-names* (into *local-names* guard-var-names)]
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
                        [(indent level "}")]))))]
     (if (seq guard-bindings)
       (str/join "\n"
                 (concat
                  [(indent level "{")]
                  (generate-convert-declarations (+ level 1) guard-bindings)
                  [statement-code
                   (indent level "}")]))
       statement-code))))

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
  "Generate JavaScript try/catch with optional retry loop"
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
          [(indent (+ level 1) "} catch (_nex_e) {")]
          [(indent (+ level 2) "let exception = _nex_e;")]
          rescue-stmts
          (when needs-throw [(indent (+ level 2) "throw _nex_e;")])
          [(indent (+ level 1) "}")]
          [(indent level "}")]))
      ;; try { body; } catch (...) { rescue; throw; }
      (str/join "\n"
        (concat
          [(indent level "try {")]
          body-stmts
          [(indent level "} catch (_nex_e) {")]
          [(indent (+ level 1) "let exception = _nex_e;")]
          rescue-stmts
          [(indent (+ level 1) "throw _nex_e;")]
          [(indent level "}")])))))

(defn generate-scoped-block
  "Generate JavaScript code for scoped block"
  ([level node] (generate-scoped-block level node #{}))
  ([level {:keys [body rescue]} var-names]
   (let [convert-bindings (collect-convert-bindings-block (concat body (or rescue [])))
         convert-names (set (map :name convert-bindings))]
     (binding [*local-names* (into *local-names* convert-names)]
       (if rescue
         (str/join "\n"
                   (concat
                    [(indent level "{")]
                    (generate-convert-declarations (+ level 1) convert-bindings)
                    [(generate-rescue (+ level 1) body rescue var-names)
                     (indent level "}")]))
         (let [statements (map #(generate-statement (+ level 1) % var-names) body)]
           (str/join "\n"
                     (concat
                      [(indent level "{")]
                      (generate-convert-declarations (+ level 1) convert-bindings)
                      [(str/join "\n" statements)
                       (indent level "}")]))))))))

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
        loop-vars (extract-var-names-js init)]
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
  "Generate JavaScript code for case statement as switch"
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
                           (indent (+ level 2) "throw \"No matching case\";")])]
    (str/join "\n"
              (concat
               [(indent level (str "switch (" expr-code ") {"))]
               case-parts
               (or default-part no-else-default)
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
     :case (generate-case level stmt var-names)
     :scoped-block (generate-scoped-block level stmt var-names)
     :loop (generate-loop level stmt)
     :with (when (= (:target stmt) "javascript")
             ;; Only include this block if target is "javascript"
             (str/join "\n" (map #(generate-statement level % var-names) (:body stmt))))
     :raise (indent level (str "throw " (generate-expression (:value stmt)) ";"))
     :retry (indent level "continue;")
     :member-assign (indent level
                      (str *this-name* "." (:field stmt) " = " (generate-expression (:value stmt)) ";"))
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

(defn assertions->condition
  "Collapse a list of assertions into a single condition using logical AND."
  [assertions]
  (when (seq assertions)
    (reduce (fn [acc {:keys [condition]}]
              (if acc
                {:type :binary
                 :operator "and"
                 :left acc
                 :right condition}
                condition))
            nil
            assertions)))

(defn combine-preconditions
  "Combine inherited and local preconditions as:
   (base-require) OR (local-require)."
  [base-assertions local-assertions]
  (let [base-assertions (seq base-assertions)
        local-assertions (seq local-assertions)]
    (cond
      (and base-assertions local-assertions)
      [{:label "inherited_or_local_require"
        :condition {:type :binary
                    :operator "or"
                    :left (assertions->condition base-assertions)
                    :right (assertions->condition local-assertions)}}]

      base-assertions
      (vec base-assertions)

      local-assertions
      (vec local-assertions)

      :else
      nil)))

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
  "Generate JavaScript code for a method"
  [level {:keys [name params return-type body require ensure rescue visibility note]} opts]
  (let [param-names (set (map :name params))
        convert-bindings (collect-convert-bindings-block (concat body (or rescue [])))
        convert-var-names (set (map :name convert-bindings))
        local-names (cond-> (into param-names convert-var-names)
                      return-type (conj "result"))]
    (binding [*local-names* (into *local-names* local-names)]
      (let [params-code (str/join ", " (map :name params))
            ;; Apply visibility naming convention
            method-name (if visibility
                         (visibility-to-js visibility name)
                         name)
            ;; Initialize result variable if method has return type
            result-init (when return-type
                         [(indent (+ level 1)
                                 (str "let result = " (default-value return-type) ";"))])
            convert-decls (generate-convert-declarations (+ level 1) convert-bindings)
            ;; Extract old references and generate capture statements
            old-refs (when ensure (extract-old-references ensure))
            old-captures (when (seq old-refs)
                          (map (fn [field-name]
                                (indent (+ level 1)
                                       (str "let old_" field-name " = this." field-name ";")))
                              old-refs))
            preconditions (generate-assertions (+ level 1) require "Precondition" opts)
            statements (if rescue
                         [(generate-rescue (+ level 1) body rescue #{})]
                         (map #(generate-statement (+ level 1) %) body))
            postconditions (generate-assertions (+ level 1) ensure "Postcondition" opts)
            class-invariant-checks (generate-assertions (+ level 1) *class-invariants* "Class invariant" opts)
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
                   convert-decls
                   old-captures
                   preconditions
                   statements
                   postconditions
                   class-invariant-checks
                   return-stmt
                   [(indent level "}")]))))))

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
  [level class-name fields has-parent? deferred?]
  (let [super-call (when has-parent?
                     [(indent (+ level 1) "super();")])
        deferred-guard (when deferred?
                         [(indent (+ level 1) (str "if (new.target === " class-name ") {"))
                          (indent (+ level 2) (str "throw new Error(\"Cannot instantiate deferred class: " class-name "\");"))
                          (indent (+ level 1) "}")])
        field-inits (map #(generate-field-init (+ level 1) %) fields)]
    (str/join "\n"
              (concat
               [(indent level "constructor() {")]
               super-call
               deferred-guard
               field-inits
               [(indent level "}")]))))

(defn generate-factory-constructor
  "Generate JavaScript static factory method for a Nex constructor"
  [level class-name {:keys [name params body require ensure rescue]} opts]
  (let [local-name (class-name-to-local class-name)
        param-names (set (map :name params))
        convert-bindings (collect-convert-bindings-block (concat body (or rescue [])))
        convert-var-names (set (map :name convert-bindings))
        local-names (into param-names convert-var-names)
        params-code (str/join ", " (map :name params))
        preconditions (binding [*this-name* local-name
                                *local-names* (into *local-names* local-names)]
                        (generate-assertions (+ level 1) require "Precondition" opts))
        statements (binding [*this-name* local-name
                             *local-names* (into *local-names* local-names)]
                     (if rescue
                       [(generate-rescue (+ level 1) body rescue #{})]
                       (mapv #(generate-statement (+ level 1) %) body)))
        postconditions (binding [*this-name* local-name
                                 *local-names* (into *local-names* local-names)]
                         (generate-assertions (+ level 1) ensure "Postcondition" opts))
        class-invariant-checks (binding [*this-name* local-name
                                         *local-names* (into *local-names* local-names)]
                                 (generate-assertions (+ level 1) *class-invariants* "Class invariant" opts))]
    (str/join "\n"
              (concat
               [(indent level (str "static " name "(" params-code ") {"))]
               [(indent (+ level 1) (str "let " local-name " = new " class-name "();"))]
               (generate-convert-declarations (+ level 1) convert-bindings)
               preconditions
               statements
               postconditions
               class-invariant-checks
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

(defn collect-effective-class-invariants
  "Collect effective class invariants as:
   inherited invariants from all parent chains (deduped by ancestor class) + local invariants."
  [class-def classes-by-name]
  (letfn [(collect [cls seen]
            (let [class-name (:name cls)
                  already-seen? (and class-name (contains? seen class-name))
                  seen' (if class-name (conj seen class-name) seen)]
              (if already-seen?
                [[] seen]
                (let [[parent-invariants seen'']
                      (if-let [parents (:parents cls)]
                        (reduce (fn [[acc seen-so-far] {:keys [parent]}]
                                  (if-let [parent-def (get classes-by-name parent)]
                                    (let [[inv seen-next] (collect parent-def seen-so-far)]
                                      [(into acc inv) seen-next])
                                    [acc seen-so-far]))
                                [[] seen']
                                parents)
                        [[] seen'])
                      local-invariants (or (:invariant cls) [])]
                  [(vec (concat parent-invariants local-invariants)) seen'']))))]
    (first (collect class-def #{}))))

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

(defn get-parent-method-names
  "Get all method names from a parent class via the class registry (JS uses extends)"
  [parent-name classes-by-name]
  (when-let [parent-def (get classes-by-name parent-name)]
    (let [{:keys [methods]} (extract-members (:body parent-def))]
      (map :name methods))))

(defn get-parent-constructors-js
  "Get constructor definitions from a direct parent class."
  [parent-name classes-by-name]
  (when-let [parent-def (get classes-by-name parent-name)]
    (let [{:keys [constructors]} (extract-members (:body parent-def))]
      constructors)))

(defn generate-inherited-constructor-shims-js
  "Generate child static factory constructors that forward to direct parent constructors."
  [level class-name parents own-constructor-names classes-by-name]
  (let [all-parent-ctors (mapcat (fn [{:keys [parent]}]
                                   (map (fn [ctor] {:parent parent :ctor ctor})
                                        (or (get-parent-constructors-js parent classes-by-name) [])))
                                 parents)
        selected (vals (reduce (fn [m {:keys [ctor] :as entry}]
                                 (let [ctor-name (:name ctor)]
                                   (if (or (contains? own-constructor-names ctor-name)
                                           (contains? m ctor-name))
                                     m
                                     (assoc m ctor-name entry))))
                               {}
                               all-parent-ctors))]
    (vec
     (map (fn [{:keys [parent ctor]}]
            (let [{:keys [name params]} ctor
                  local-name (class-name-to-local class-name)
                  params-code (str/join ", " (map :name params))
                  args-code (str/join ", " (map :name params))]
              (str/join "\n"
                        [(indent level (str "static " name "(" params-code ") {"))
                         (indent (+ level 1) (str "let __parent = " parent "." name "(" args-code ");"))
                         (indent (+ level 1) (str "let " local-name " = new " class-name "();"))
                         (indent (+ level 1) (str "Object.assign(" local-name ", __parent);"))
                         (indent (+ level 1) (str "return " local-name ";"))
                         (indent level "}")])))
          selected))))

(defn lookup-method-effective-contracts
  "Lookup method in class hierarchy and compute effective contracts:
   require = base OR local
   ensure = base AND local"
  [class-def method-name classes-by-name]
  (let [{:keys [methods]} (extract-members (:body class-def))
        local-method (first (filter #(= (:name %) method-name) methods))]
    (if local-method
      (let [base-lookup (when-let [parents (:parents class-def)]
                          (some (fn [{:keys [parent]}]
                                  (when-let [parent-def (get classes-by-name parent)]
                                    (lookup-method-effective-contracts parent-def method-name classes-by-name)))
                                parents))
            effective-require (combine-preconditions (:effective-require base-lookup)
                                                     (:require local-method))
            effective-ensure (vec (concat (or (:effective-ensure base-lookup) [])
                                          (or (:ensure local-method) [])))]
        {:method local-method
         :effective-require effective-require
         :effective-ensure effective-ensure})
      (when-let [parents (:parents class-def)]
        (some (fn [{:keys [parent]}]
                (when-let [parent-def (get classes-by-name parent)]
                  (lookup-method-effective-contracts parent-def method-name classes-by-name)))
              parents)))))

(defn build-field-types-js
  "Build field-name -> type map from own + parent fields"
  [fields parent-names classes-by-name]
  (let [own (into {} (map (fn [f] [(:name f) (:field-type f)]) fields))
        parent-flds (reduce (fn [m parent-name]
                              (if-let [parent-def (get classes-by-name parent-name)]
                                (let [{pfields :fields} (extract-members (:body parent-def))]
                                  (reduce (fn [m2 f]
                                            (if (contains? m2 (:name f))
                                              m2
                                              (assoc m2 (:name f) (:field-type f))))
                                          m pfields))
                                m))
                            {} parent-names)]
    (merge parent-flds own)))

(defn generate-class
  "Generate JavaScript code for a Nex class"
  ([class-def] (generate-class class-def {} {}))
  ([class-def opts] (generate-class class-def opts {}))
  ([class-def opts classes-by-name]
   (let [{:keys [name generic-params parents body note deferred?]} class-def
         {:keys [fields methods constructors]} (extract-members body)
         parent-names (mapv :parent parents)
         own-flds (set (map :name fields))
         own-method-names (set (map :name methods))
         all-methods (into own-method-names
                           (mapcat #(get-parent-method-names % classes-by-name)
                                   parent-names))
         fld-types (build-field-types-js fields parent-names classes-by-name)
         effective-invariants (collect-effective-class-invariants class-def classes-by-name)]
     (binding [*all-method-names* all-methods
               *own-fields* own-flds
               *field-types* fld-types
               *class-invariants* effective-invariants]
       (let [;; Generate class JSDoc if note present
             class-jsdoc (when note
                          [(generate-jsdoc 0 note)])
             generic-comment (generate-generic-comment generic-params)
             class-header (generate-class-header name generic-params parents)
             invariant-comment (when (and (seq effective-invariants) (not (:skip-contracts opts)))
                                (indent 1 (str "// Class invariant: "
                                              (str/join ", " (map :label effective-invariants)))))
             ;; Always generate a default no-arg constructor for field initialization
             has-parent? (some? (:extends (analyze-inheritance parents)))
             default-constructor (generate-default-constructor 1 name fields has-parent? deferred?)
             ;; All Nex constructors become static factory methods
             factory-methods (map #(generate-factory-constructor 1 name % opts) constructors)
             inherited-constructor-shims (when (seq parents)
                                           (generate-inherited-constructor-shims-js 1 name parents (set (map :name constructors)) classes-by-name))
             methods-with-effective-contracts
             (map (fn [m]
                    (let [effective (lookup-method-effective-contracts class-def (:name m) classes-by-name)]
                      (assoc m
                             :require (:effective-require effective)
                             :ensure (:effective-ensure effective))))
                  methods)
             methods-code (map #(generate-method 1 % opts) methods-with-effective-contracts)]
         (str/join "\n"
                   (concat
                    class-jsdoc
                    (when generic-comment [generic-comment])
                    [class-header]
                    (when invariant-comment [invariant-comment ""])
                    [default-constructor ""]
                    factory-methods
                    (when (seq factory-methods) [""])
                    (when (seq inherited-constructor-shims) inherited-constructor-shims)
                    (when (seq inherited-constructor-shims) [""])
                    methods-code
                    ["}"])))))))

(defn generate-function-base-class
  "Generate the built-in Function base class."
  []
  (let [method-lines
        (map (fn [n]
               (let [params (str/join ", " (map (fn [i] (str "arg" i))
                                                (range 1 (inc n))))]
                 (str "  call" n "(" params ") { return null; }")))
             (range 0 33))]
    (str "class Function {\n"
         (str/join "\n" method-lines)
         "\n}")))

(defn generate-graphics-runtime
  "Generate browser graphics runtime classes for Window/Turtle/Image built-ins."
  []
  (str "function __nexTypeOf(v) {\n"
       "  if (v === null || v === undefined) return 'Nil';\n"
       "  if (typeof v === 'string') return (v.length === 1 ? 'Char' : 'String');\n"
       "  if (typeof v === 'boolean') return 'Boolean';\n"
       "  if (typeof v === 'number') return Number.isInteger(v) ? 'Integer' : 'Real';\n"
       "  if (Array.isArray(v)) return 'Array';\n"
       "  if (v instanceof Map) return 'Map';\n"
       "  if (v && v._type) return String(v._type);\n"
       "  const ctor = v && v.constructor;\n"
       "  if (ctor && ctor.name) return ctor.name;\n"
       "  return 'Any';\n"
       "}\n"
       "function __nexTypeIs(typeName, v) {\n"
       "  if (typeof typeName !== 'string') return false;\n"
       "  const runtime = __nexTypeOf(v);\n"
       "  if (typeName === 'Any') return true;\n"
       "  if (runtime === typeName) return true;\n"
       "  if (runtime === 'Integer' && (typeName === 'Integer64' || typeName === 'Real' || typeName === 'Decimal')) return true;\n"
       "  if (runtime === 'Integer64' && (typeName === 'Real' || typeName === 'Decimal')) return true;\n"
       "  if (runtime === 'Real' && typeName === 'Decimal') return true;\n"
       "  if ((runtime === 'ArrayCursor' || runtime === 'StringCursor' || runtime === 'MapCursor') && typeName === 'Cursor') return true;\n"
       "  let cur = v;\n"
       "  while (cur) {\n"
       "    const ctor = cur.constructor;\n"
       "    if (ctor && ctor.name === typeName) return true;\n"
       "    cur = Object.getPrototypeOf(cur);\n"
       "  }\n"
       "  const seen = new Set();\n"
       "  const stack = [v];\n"
       "  while (stack.length > 0) {\n"
       "    const obj = stack.pop();\n"
       "    if (!obj || typeof obj !== 'object' || seen.has(obj)) continue;\n"
       "    seen.add(obj);\n"
       "    for (const key of Object.keys(obj)) {\n"
       "      if (key.startsWith('_parent_')) {\n"
       "        const parentType = key.substring('_parent_'.length);\n"
       "        if (parentType === typeName) return true;\n"
       "        stack.push(obj[key]);\n"
       "      }\n"
       "    }\n"
       "  }\n"
       "  return false;\n"
       "}\n"
       "function __nexCompare(a, b) {\n"
       "  if (a === b) return 0;\n"
       "  if (a < b) return -1;\n"
       "  if (a > b) return 1;\n"
       "  const sa = String(a), sb = String(b);\n"
       "  if (sa === sb) return 0;\n"
       "  return sa < sb ? -1 : 1;\n"
       "}\n"
       "function __nexHash(v) {\n"
       "  const s = String(v);\n"
       "  let h = 0;\n"
       "  for (let i = 0; i < s.length; i++) {\n"
       "    h = ((h << 5) - h) + s.charCodeAt(i);\n"
       "    h |= 0;\n"
       "  }\n"
       "  return h;\n"
       "}\n"
       "const __nexHasDom = (typeof document !== 'undefined');\n"
       "function __nexParseColor(s) {\n"
       "  const named = {black:'#000000',white:'#ffffff',red:'#ff0000',green:'#008000',blue:'#0000ff',yellow:'#ffff00',orange:'#ffa500',purple:'#800080',cyan:'#00ffff',magenta:'#ff00ff',brown:'#8b4513',pink:'#ffc0cb',gray:'#808080',grey:'#808080'};\n"
       "  const key = String(s).trim().toLowerCase();\n"
       "  return named[key] || String(s);\n"
       "}\n"
       "function __nexSpeedDelay(spd) {\n"
       "  if (spd <= 0) return 0;\n"
       "  if (spd >= 10) return 5;\n"
       "  return Math.floor(200 / spd);\n"
       "}\n"
       "class NexWindow {\n"
       "  constructor(a, b, c) {\n"
       "    if (!__nexHasDom) throw 'Window/Turtle requires a browser DOM environment';\n"
       "    let title = 'Nex Turtle Graphics', w = 800, h = 600;\n"
       "    if (typeof a === 'string' && b === undefined) { title = a; }\n"
       "    else if (typeof a === 'string' && typeof b === 'number' && typeof c === 'number') { title = a; w = b; h = c; }\n"
       "    else if (typeof a === 'number' && typeof b === 'number' && c === undefined) { w = a; h = b; }\n"
       "    this.width = w; this.height = h; this.bgColor = '#ffffff'; this.drawColor = '#000000'; this.fontSize = 14;\n"
       "    this.canvas = document.createElement('canvas'); this.canvas.width = w; this.canvas.height = h;\n"
       "    this.overlay = document.createElement('canvas'); this.overlay.width = w; this.overlay.height = h;\n"
       "    this.ctx = this.canvas.getContext('2d'); this.overlayCtx = this.overlay.getContext('2d'); this.turtles = [];\n"
       "    this.container = document.createElement('div');\n"
       "    this.container.style.position = 'relative'; this.container.style.width = w + 'px'; this.container.style.height = h + 'px';\n"
       "    this.container.style.border = '1px solid #d0d0d0'; this.container.style.margin = '8px 0';\n"
       "    this.canvas.style.position = 'absolute'; this.canvas.style.left = '0'; this.canvas.style.top = '0';\n"
       "    this.overlay.style.position = 'absolute'; this.overlay.style.left = '0'; this.overlay.style.top = '0'; this.overlay.style.pointerEvents = 'none';\n"
       "    this.container.appendChild(this.canvas); this.container.appendChild(this.overlay);\n"
       "    this.ctx.fillStyle = '#ffffff'; this.ctx.fillRect(0, 0, w, h);\n"
       "    this.title = title;\n"
       "  }\n"
       "  vw() { return this.width; }\n"
       "  vh() { return this.height; }\n"
       "  show() { if (!this.container.isConnected) document.body.appendChild(this.container); document.title = this.title; this.repaintCanvas(); return null; }\n"
       "  close() { if (this.container.isConnected) this.container.remove(); return null; }\n"
       "  clear() { this.ctx.fillStyle = this.bgColor; this.ctx.fillRect(0, 0, this.width, this.height); this.repaintCanvas(); return null; }\n"
       "  bgcolor(colorStr) { this.bgColor = __nexParseColor(colorStr); return this.clear(); }\n"
       "  refresh() { this.repaintCanvas(); return null; }\n"
       "  set_color(color) { this.drawColor = __nexParseColor(color); return null; }\n"
       "  set_font_size(size) { this.fontSize = size | 0; return null; }\n"
       "  draw_line(x1, y1, x2, y2) { this.ctx.strokeStyle = this.drawColor; this.ctx.beginPath(); this.ctx.moveTo(x1, y1); this.ctx.lineTo(x2, y2); this.ctx.stroke(); return null; }\n"
       "  draw_rect(x, y, w, h) { this.ctx.strokeStyle = this.drawColor; this.ctx.strokeRect(x, y, w, h); return null; }\n"
       "  fill_rect(x, y, w, h) { this.ctx.fillStyle = this.drawColor; this.ctx.fillRect(x, y, w, h); return null; }\n"
       "  draw_circle(x, y, r) { this.ctx.strokeStyle = this.drawColor; this.ctx.beginPath(); this.ctx.arc(x, y, r, 0, Math.PI * 2); this.ctx.stroke(); return null; }\n"
       "  fill_circle(x, y, r) { this.ctx.fillStyle = this.drawColor; this.ctx.beginPath(); this.ctx.arc(x, y, r, 0, Math.PI * 2); this.ctx.fill(); return null; }\n"
       "  draw_text(text, x, y) { this.ctx.fillStyle = this.drawColor; this.ctx.font = this.fontSize + 'px sans-serif'; this.ctx.fillText(String(text), x, y); return null; }\n"
       "  draw_image(img, x, y) { this.ctx.drawImage(img.image, x, y); return null; }\n"
       "  draw_image_scaled(img, x, y, w, h) { this.ctx.drawImage(img.image, x, y, w, h); return null; }\n"
       "  draw_image_rotated(img, x, y, angle) { const iw = img.width || 0, ih = img.height || 0, cx = x + iw / 2, cy = y + ih / 2; this.ctx.save(); this.ctx.translate(cx, cy); this.ctx.rotate(angle * Math.PI / 180); this.ctx.drawImage(img.image, -iw / 2, -ih / 2); this.ctx.restore(); return null; }\n"
       "  sleep(ms) { const end = Date.now() + ms; while (Date.now() < end) {} return null; }\n"
       "  repaintCanvas() {\n"
       "    const g = this.overlayCtx; g.clearRect(0, 0, this.width, this.height);\n"
       "    for (const t of this.turtles) {\n"
       "      if (!t.visible) continue;\n"
       "      const cx = this.width / 2 + t.x, cy = this.height / 2 - t.y;\n"
       "      g.save(); g.translate(cx, cy); g.rotate(-t.heading * Math.PI / 180); g.fillStyle = __nexParseColor(t.colorName);\n"
       "      if (t.shapeName === 'circle') { g.beginPath(); g.arc(0, 0, 6, 0, Math.PI * 2); g.fill(); }\n"
       "      else { g.beginPath(); g.moveTo(12, 0); g.lineTo(-6, -7); g.lineTo(-6, 7); g.closePath(); g.fill(); }\n"
       "      g.restore();\n"
       "    }\n"
       "  }\n"
       "}\n"
       "class NexImage {\n"
       "  constructor(src) {\n"
       "    this.image = new Image(); this.width = 0; this.height = 0;\n"
       "    this.image.onload = () => { this.width = this.image.naturalWidth; this.height = this.image.naturalHeight; };\n"
       "    if (src !== undefined) this.image.src = String(src);\n"
       "  }\n"
       "  static from_file(path) { return new NexImage(path); }\n"
       "}\n"
       "class NexTurtle {\n"
       "  constructor(win) {\n"
       "    this.window = win; this.x = 0; this.y = 0; this.heading = 90; this.penDown = true; this.colorName = 'black';\n"
       "    this.penSz = 1; this.spd = 6; this.shapeName = 'classic'; this.visible = true; this.filling = false; this.fillPoints = []; this.fillColor = 'black';\n"
       "    win.turtles.push(this); win.repaintCanvas();\n"
       "  }\n"
       "  xpos() { return this.x; }\n"
       "  ypos() { return this.y; }\n"
       "  surface() { return this.window; }\n"
       "  __coords(x, y) { return [this.window.vw() / 2 + x, this.window.vh() / 2 - y]; }\n"
       "  __line(x1, y1, x2, y2) { const g = this.window.ctx; g.strokeStyle = __nexParseColor(this.colorName); g.lineWidth = this.penSz; g.lineCap = 'round'; g.lineJoin = 'round'; g.beginPath(); g.moveTo(x1, y1); g.lineTo(x2, y2); g.stroke(); }\n"
       "  __delay() { const d = __nexSpeedDelay(this.spd); if (d > 0) this.window.sleep(d); }\n"
       "  forward(dist) { const r = this.heading * Math.PI / 180; const nx = this.x + dist * Math.cos(r); const ny = this.y + dist * Math.sin(r); const [sx, sy] = this.__coords(this.x, this.y); const [ex, ey] = this.__coords(nx, ny); if (this.penDown) this.__line(sx, sy, ex, ey); this.x = nx; this.y = ny; if (this.filling) this.fillPoints.push([nx, ny]); this.window.repaintCanvas(); this.__delay(); return null; }\n"
       "  backward(dist) { return this.forward(-dist); }\n"
       "  right(angle) { this.heading -= angle; this.window.repaintCanvas(); return null; }\n"
       "  left(angle) { this.heading += angle; this.window.repaintCanvas(); return null; }\n"
       "  penup() { this.penDown = false; return null; }\n"
       "  pendown() { this.penDown = true; return null; }\n"
       "  color(c) { this.colorName = String(c); this.fillColor = String(c); this.window.repaintCanvas(); return null; }\n"
       "  pensize(s) { this.penSz = s; return null; }\n"
       "  speed(s) { this.spd = s; return null; }\n"
       "  shape(s) { this.shapeName = (String(s).toLowerCase() === 'circle') ? 'circle' : 'classic'; this.window.repaintCanvas(); return null; }\n"
       "  goto(x, y) { const [sx, sy] = this.__coords(this.x, this.y); const [ex, ey] = this.__coords(x, y); if (this.penDown) this.__line(sx, sy, ex, ey); this.x = x; this.y = y; if (this.filling) this.fillPoints.push([x, y]); this.window.repaintCanvas(); this.__delay(); return null; }\n"
       "  circle(r) { if (this.penDown) { const [cx, cy] = this.__coords(this.x, this.y); const g = this.window.ctx; g.strokeStyle = __nexParseColor(this.colorName); g.lineWidth = this.penSz; g.beginPath(); g.arc(cx, cy, Math.abs(r), 0, Math.PI * 2); g.stroke(); } this.window.repaintCanvas(); this.__delay(); return null; }\n"
       "  begin_fill() { this.filling = true; this.fillPoints = [[this.x, this.y]]; this.fillColor = this.colorName; return null; }\n"
       "  end_fill() { if (this.filling && this.fillPoints.length >= 3) { const g = this.window.ctx; g.fillStyle = __nexParseColor(this.fillColor); const [fx, fy] = this.fillPoints[0]; const [sx, sy] = this.__coords(fx, fy); g.beginPath(); g.moveTo(sx, sy); for (let i = 1; i < this.fillPoints.length; i++) { const [px, py] = this.fillPoints[i]; const [cx, cy] = this.__coords(px, py); g.lineTo(cx, cy); } g.closePath(); g.fill(); } this.filling = false; this.fillPoints = []; this.window.repaintCanvas(); return null; }\n"
       "  hide() { this.visible = false; this.window.repaintCanvas(); return null; }\n"
       "  show() { this.visible = true; this.window.repaintCanvas(); return null; }\n"
       "}\n"))

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
;; Main Class Generation
;;

(defn generate-main
  "Generate a main.js that requires and instantiates the last user-defined class.
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
    (str "const { " class-name " } = require('./" class-name "');\n"
         call ";\n")))

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
         graphics-runtime (generate-graphics-runtime)
         function-globals (generate-function-globals functions)]
     (binding [*function-names* function-names]
       (let [classes-by-name (into {} (map (juxt :name identity) classes))
             js-classes (map #(generate-class % opts classes-by-name) classes)
             parts (concat js-imports
                           (when (seq js-imports) [""])
                           [function-base]
                           ["" graphics-runtime]
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
           (doseq [err (:errors result)]
             (println (tc/format-type-error err)))
           (throw (ex-info "Type checking failed"
                           {:errors (map tc/format-type-error (:errors result))})))))
     (translate-ast ast opts))))

(defn translate-file
  "Translate a Nex file to JavaScript, writing one file per class to output-dir.

  Writes: Function.js, NexWindow.js (graphics runtime), NexGlobals.js (if functions exist),
          <ClassName>.js for each class, and main.js.

  Returns a map of {filename -> code-string}.

  Options:
    :skip-contracts - When true, omits all contracts (useful for production)
    :skip-type-check - When true, skips static type checking"
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
         graphics-runtime (generate-graphics-runtime)
         function-globals (generate-function-globals functions)
         main-code (generate-main ast)
         classes-by-name (into {} (map (juxt :name identity) classes))
         class-codes (binding [*function-names* function-names]
                       (mapv (fn [cls] [(:name cls) (generate-class cls opts classes-by-name)]) classes))
         files (into {"Function.js" function-base
                      "NexWindow.js" graphics-runtime}
                     (concat
                      (when function-globals
                        [["NexGlobals.js" function-globals]])
                      (map (fn [[name code]] [(str name ".js") code]) class-codes)
                      [["main.js" main-code]]))]
     (when output-dir
       (let [dir (io/file output-dir)]
         (.mkdirs dir)
         (doseq [[filename code] files]
           (spit (io/file dir filename) code))))
     files)))

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
    (println "Usage: nex compile javascript <input.nex> [output-dir]")
    (System/exit 1))

  (let [input-file (first args)
        output-dir (when (> (count args) 1) (second args))]
    (try
      (let [files (translate-file input-file output-dir)]
        (if output-dir
          (println (str "Compiled " input-file " -> " output-dir "/ (" (count files) " files)"))
          (doseq [[filename code] files]
            (println (str "// === " filename " ==="))
            (println code)
            (println))))
      (System/exit 0)
      (catch Exception e
        (println "Error:" (.getMessage e))
        (System/exit 1)))))
