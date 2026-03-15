(ns nex.generator.java
  "Translates Nex (Eiffel-based) code to Java"
  (:require [nex.parser :as p]
            [nex.interpreter :as interp]
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
(def ^:dynamic *constant-names* #{})    ;; accessible class constants in current class
(def ^:dynamic *local-names* #{})       ;; method params + loop vars (shadow parent fields)
(def ^:dynamic *local-types* {})        ;; local/param name -> Nex type
(def ^:dynamic *all-method-names* #{})  ;; own + delegated parent method names
(def ^:dynamic *field-types* {})        ;; field-name -> type, own + parent
(def ^:dynamic *class-invariants* [])   ;; effective class invariants (inherited + local, deduped)
(def ^:dynamic *spawn-result-flag* nil) ;; temp boolean var name used inside generated spawn bodies

(declare extract-members)
(declare get-accessible-constants)

(defn- augment-ast-with-interns
  [source-id ast]
  (let [intern-classes (interp/resolve-interned-classes source-id ast)]
    (if (seq intern-classes)
      (update ast :classes #(vec (concat intern-classes %)))
      ast)))

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
      "Real" "Double"
      "Decimal" "java.math.BigDecimal"
      "Char" "Character"
      "Boolean" "Boolean"
      "String" "String"
      "Array" "ArrayList"
      "Map" "HashMap"
      "Set" "LinkedHashSet"
      "Task" "NexRuntime.Task"
      "Channel" "NexRuntime.Channel"
      "Console" "Object"
      "Process" "Object"
      "Function" "Function"
      "Window" "NexWindow"
      "Turtle" "NexTurtle"
      "Image" "NexImage"
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
                      "Set" "LinkedHashSet"
                      "Task" "NexRuntime.Task"
                      "Channel" "NexRuntime.Channel"
                      base-type)
           args (or (:type-args nex-type) (:type-params nex-type))]
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
         "Real" "double"
         "Decimal" "java.math.BigDecimal"
         "Char" "char"
         "Boolean" "boolean"
        "String" "String"
        "Array" "ArrayList"
        "Map" "HashMap"
        "Set" "LinkedHashSet"
        "Task" "NexRuntime.Task"
        "Channel" "NexRuntime.Channel"
        "Console" "Object"
        "Process" "Object"
        "Function" "Function"
        "Window" "NexWindow"
        "Turtle" "NexTurtle"
        "Image" "NexImage"
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
        "Set" (str "new LinkedHashSet<>()")
        "Task" "null"
        "Channel" "null"
        "null"))

    ;; Handle basic types
    (string? nex-type)
    (case nex-type
      "Integer" "0"
      "Integer64" "0L"
      "Real" "0.0"
      "Decimal" "java.math.BigDecimal.ZERO"
      "Char" "'\\0'"
      "Boolean" "false"
      "String" "\"\""
      "Array" "new ArrayList<>()"
      "Map" "new HashMap<>()"
      "Set" "new LinkedHashSet<>()"
      "Task" "null"
      "Channel" "null"
      "Console" "new Object() /* Console */"
      "Process" "new Object() /* Process */"
      "Window" "null"
      "Turtle" "null"
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

(defn object-arg-cast
  "Convert an Object bridge argument to the declared Java parameter type."
  [param-type arg-name]
  (let [t (if (map? param-type) (:base-type param-type) param-type)]
    (case t
      "Integer" (str "((Number)" arg-name ").intValue()")
      "Integer64" (str "((Number)" arg-name ").longValue()")
      "Real" (str "((Number)" arg-name ").doubleValue()")
      "Decimal" (str "(java.math.BigDecimal)" arg-name)
      "Boolean" (str "((Boolean)" arg-name ").booleanValue()")
      "Char" (str "((Character)" arg-name ").charValue()")
      (str "(" (nex-type-to-java-boxed param-type) ")" arg-name))))

(declare generate-expression)
(declare is-parent-constructor?)
(declare generate-method)
(declare resolve-field-name)
(declare builtin-dispatch-type)
(declare infer-expression-type)
(declare integral-dispatch-type?)

(defn generate-binary-expr
  "Generate Java code for binary expression"
  [{:keys [operator left right]}]
  (let [left-code (generate-expression left)
        right-code (generate-expression right)
        left-type (builtin-dispatch-type (infer-expression-type left))
        right-type (builtin-dispatch-type (infer-expression-type right))]
    (cond
      (and (= operator "+")
           (or (= left-type "String") (= right-type "String")))
      (str "NexRuntime.concatValues(" left-code ", " right-code ")")

      (= operator "^")
      (if (and (integral-dispatch-type? left-type)
               (integral-dispatch-type? right-type))
        (if (or (= left-type "Integer64") (= right-type "Integer64"))
          (str "NexRuntime.intPow64(" left-code ", " right-code ")")
          (str "NexRuntime.intPow(" left-code ", " right-code ")"))
        (str "(Math.pow(" left-code ", " right-code "))"))
      :else
      (let [op (generate-binary-op operator)]
        (str "(" left-code " " op " " right-code ")")))))

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
    "type_of" (str "NexRuntime.typeOf(" args-code ")")
    "type_is" (str "NexRuntime.typeIs(" args-code ")")
    "sleep" (str "NexRuntime.sleep(" args-code ")")
    "regex_validate" (str "NexRuntime.regexValidate(" args-code ")")
    "regex_matches" (str "NexRuntime.regexMatches(" args-code ")")
    "regex_find" (str "NexRuntime.regexFind(" args-code ")")
    "regex_find_all" (str "NexRuntime.regexFindAll(" args-code ")")
    "regex_replace" (str "NexRuntime.regexReplace(" args-code ")")
    "regex_split" (str "NexRuntime.regexSplit(" args-code ")")
    "datetime_now" (str "NexRuntime.datetimeNow(" args-code ")")
    "datetime_from_epoch_millis" (str "NexRuntime.datetimeFromEpochMillis(" args-code ")")
    "datetime_parse_iso" (str "NexRuntime.datetimeParseIso(" args-code ")")
    "datetime_make" (str "NexRuntime.datetimeMake(" args-code ")")
    "datetime_year" (str "NexRuntime.datetimeYear(" args-code ")")
    "datetime_month" (str "NexRuntime.datetimeMonth(" args-code ")")
    "datetime_day" (str "NexRuntime.datetimeDay(" args-code ")")
    "datetime_weekday" (str "NexRuntime.datetimeWeekday(" args-code ")")
    "datetime_day_of_year" (str "NexRuntime.datetimeDayOfYear(" args-code ")")
    "datetime_hour" (str "NexRuntime.datetimeHour(" args-code ")")
    "datetime_minute" (str "NexRuntime.datetimeMinute(" args-code ")")
    "datetime_second" (str "NexRuntime.datetimeSecond(" args-code ")")
    "datetime_epoch_millis" (str "NexRuntime.datetimeEpochMillis(" args-code ")")
    "datetime_add_millis" (str "NexRuntime.datetimeAddMillis(" args-code ")")
    "datetime_diff_millis" (str "NexRuntime.datetimeDiffMillis(" args-code ")")
    "datetime_truncate_to_day" (str "NexRuntime.datetimeTruncateToDay(" args-code ")")
    "datetime_truncate_to_hour" (str "NexRuntime.datetimeTruncateToHour(" args-code ")")
    "datetime_format_iso" (str "NexRuntime.datetimeFormatIso(" args-code ")")
    "path_exists" (str "NexRuntime.pathExists(" args-code ")")
    "path_is_file" (str "NexRuntime.pathIsFile(" args-code ")")
    "path_is_directory" (str "NexRuntime.pathIsDirectory(" args-code ")")
    "path_name" (str "NexRuntime.pathName(" args-code ")")
    "path_extension" (str "NexRuntime.pathExtension(" args-code ")")
    "path_name_without_extension" (str "NexRuntime.pathNameWithoutExtension(" args-code ")")
    "path_absolute" (str "NexRuntime.pathAbsolute(" args-code ")")
    "path_normalize" (str "NexRuntime.pathNormalize(" args-code ")")
    "path_size" (str "NexRuntime.pathSize(" args-code ")")
    "path_modified_time" (str "NexRuntime.pathModifiedTime(" args-code ")")
    "path_parent" (str "NexRuntime.pathParent(" args-code ")")
    "path_child" (str "NexRuntime.pathChild(" args-code ")")
    "path_create_file" (str "NexRuntime.pathCreateFile(" args-code ")")
    "path_create_directory" (str "NexRuntime.pathCreateDirectory(" args-code ")")
    "path_create_directories" (str "NexRuntime.pathCreateDirectories(" args-code ")")
    "path_delete" (str "NexRuntime.pathDelete(" args-code ")")
    "path_delete_tree" (str "NexRuntime.pathDeleteTree(" args-code ")")
    "path_copy" (str "NexRuntime.pathCopy(" args-code ")")
    "path_move" (str "NexRuntime.pathMove(" args-code ")")
    "path_read_text" (str "NexRuntime.pathReadText(" args-code ")")
    "path_write_text" (str "NexRuntime.pathWriteText(" args-code ")")
    "path_append_text" (str "NexRuntime.pathAppendText(" args-code ")")
    "path_list" (str "NexRuntime.pathList(" args-code ")")
    "text_file_open_read" (str "NexRuntime.textFileOpenRead(" args-code ")")
    "text_file_open_write" (str "NexRuntime.textFileOpenWrite(" args-code ")")
    "text_file_open_append" (str "NexRuntime.textFileOpenAppend(" args-code ")")
    "text_file_read_line" (str "NexRuntime.textFileReadLine(" args-code ")")
    "text_file_write" (str "NexRuntime.textFileWrite(" args-code ")")
    "text_file_close" (str "NexRuntime.textFileClose(" args-code ")")
    "binary_file_open_read" (str "NexRuntime.binaryFileOpenRead(" args-code ")")
    "binary_file_open_write" (str "NexRuntime.binaryFileOpenWrite(" args-code ")")
    "binary_file_open_append" (str "NexRuntime.binaryFileOpenAppend(" args-code ")")
    "binary_file_read_all" (str "NexRuntime.binaryFileReadAll(" args-code ")")
    "binary_file_read" (str "NexRuntime.binaryFileRead(" args-code ")")
    "binary_file_write" (str "NexRuntime.binaryFileWrite(" args-code ")")
    "binary_file_close" (str "NexRuntime.binaryFileClose(" args-code ")")
    "json_parse" (str "NexRuntime.jsonParse(" args-code ")")
    "json_stringify" (str "NexRuntime.jsonStringify(" args-code ")")
    "http_get" (str "NexRuntime.httpGet(" args-code ")")
    "http_post" (str "NexRuntime.httpPost(" args-code ")")
    "http_server_create" (str "NexRuntime.httpServerCreate(" args-code ")")
    "http_server_get" (str "NexRuntime.httpServerGet(" args-code ")")
    "http_server_post" (str "NexRuntime.httpServerPost(" args-code ")")
    "http_server_put" (str "NexRuntime.httpServerPut(" args-code ")")
    "http_server_delete" (str "NexRuntime.httpServerDelete(" args-code ")")
    "http_server_start" (str "NexRuntime.httpServerStart(" args-code ")")
    "http_server_stop" (str "NexRuntime.httpServerStop(" args-code ")")
    "http_server_is_running" (str "NexRuntime.httpServerIsRunning(" args-code ")")
    "await_any" (str "NexRuntime.awaitAny(" args-code ")")
    "await_all" (str "NexRuntime.awaitAll(" args-code ")")
    ;; Default: use as-is (regular method call)
    (str method "(" args-code ")")))

(def builtin-method-mappings
  "Map Nex built-in type methods to Java equivalents"
  {:Any
   {"to_string"  (fn [target _] (str "NexRuntime.toStringValue(" target ")"))
    "equals"     (fn [target args] (str "NexRuntime.anyEquals(" target ", " args ")"))
    "clone"      (fn [target _] (str "NexRuntime.cloneValue(" target ")"))}

   :String
   {"length"      (fn [target _] (str target ".length()"))
    "index_of"    (fn [target args] (str target ".indexOf(" args ")"))
    "substring"   (fn [target args] (str target ".substring(" args ")"))
    "to_upper"    (fn [target _] (str target ".toUpperCase()"))
    "to_lower"    (fn [target _] (str target ".toLowerCase()"))
    "to_integer"  (fn [target _] (str "NexRuntime.parseInt(" target ")"))
    "to_integer64" (fn [target _] (str "NexRuntime.parseLong(" target ")"))
    "to_real"     (fn [target _] (str "Double.parseDouble(" target ".trim())"))
    "to_decimal"  (fn [target _] (str "new java.math.BigDecimal(" target ".trim())"))
    "contains"    (fn [target args] (str target ".contains(" args ")"))
    "starts_with" (fn [target args] (str target ".startsWith(" args ")"))
    "ends_with"   (fn [target args] (str target ".endsWith(" args ")"))
    "trim"        (fn [target _] (str target ".trim()"))
    "replace"     (fn [target args] (str target ".replace(" args ")"))
    "char_at"     (fn [target args] (str target ".charAt(" args ")"))
    "compare"     (fn [target args] (str "Integer.signum(" target ".compareTo(String.valueOf(" args ")))"))
    "hash"        (fn [target _] (str target ".hashCode()"))
    "split"       (fn [target args] (str "new ArrayList<>(Arrays.asList(" target ".split(" args ")))"))
    "cursor"      (fn [target _] (str "NexRuntime.stringCursor(" target ")"))
    ;; String operators
    "plus"        (fn [target args] (str "NexRuntime.concatValues(" target ", " args ")"))
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
    "pick"      (fn [target _] (str "(int)(Math.random() * (" target " - 1))"))
    "bitwise_left_shift" (fn [target args] (str "((int)" target " << " args ")"))
    "bitwise_right_shift" (fn [target args] (str "((int)" target " >> " args ")"))
    "bitwise_logical_right_shift" (fn [target args] (str "((int)" target " >>> " args ")"))
    "bitwise_rotate_left" (fn [target args] (str "Integer.rotateLeft((int)" target ", " args ")"))
    "bitwise_rotate_right" (fn [target args] (str "Integer.rotateRight((int)" target ", " args ")"))
    "bitwise_is_set" (fn [target args] (str "((((int)" target " >>> " args ") & 1) != 0)"))
    "bitwise_set" (fn [target args] (str "((int)" target " | (1 << " args "))"))
    "bitwise_unset" (fn [target args] (str "((int)" target " & ~(1 << " args "))"))
    "bitwise_and" (fn [target args] (str "((int)" target " & (int)" args ")"))
    "bitwise_or" (fn [target args] (str "((int)" target " | (int)" args ")"))
    "bitwise_xor" (fn [target args] (str "((int)" target " ^ (int)" args ")"))
    "bitwise_not" (fn [target _] (str "(~(int)" target ")"))
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
    "greater_than_or_equal" (fn [target args] (str "(" target " >= " args ")"))
    "compare"    (fn [target args] (str "Integer.compare(" target ", ((Number)" args ").intValue())"))
    "hash"       (fn [target _] (str "Integer.hashCode(" target ")"))}

   :Integer64
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
    "greater_than_or_equal" (fn [target args] (str "(" target " >= " args ")"))
    "compare"    (fn [target args] (str "Long.compare(" target ", ((Number)" args ").longValue())"))
    "hash"       (fn [target _] (str "Long.hashCode(" target ")"))}

   :Real
   {"to_string" (fn [target _] (str "String.valueOf(" target ")"))
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
    "equals"    (fn [target args] (str "(" target " == " args ")"))
    "not_equals" (fn [target args] (str "(" target " != " args ")"))
    "less_than" (fn [target args] (str "(" target " < " args ")"))
    "less_than_or_equal" (fn [target args] (str "(" target " <= " args ")"))
    "greater_than" (fn [target args] (str "(" target " > " args ")"))
    "greater_than_or_equal" (fn [target args] (str "(" target " >= " args ")"))
    "compare"    (fn [target args] (str "Double.compare(" target ", ((Number)" args ").doubleValue())"))
    "hash"       (fn [target _] (str "Double.hashCode(" target ")"))}

   :Decimal
   {"to_string" (fn [target _] (str "String.valueOf(" target ")"))
    "abs"       (fn [target _] (str target ".abs()"))
    "min"       (fn [target args] (str target ".min(" args ")"))
    "max"       (fn [target args] (str target ".max(" args ")"))
    "round"     (fn [target _] (str target ".setScale(0, java.math.RoundingMode.HALF_UP)"))
    ;; Arithmetic operators (BigDecimal)
    "plus"      (fn [target args] (str target ".add(" args ")"))
    "minus"     (fn [target args] (str target ".subtract(" args ")"))
    "times"     (fn [target args] (str target ".multiply(" args ")"))
    "divided_by" (fn [target args] (str target ".divide(" args ", java.math.MathContext.DECIMAL128)"))
    ;; Comparison operators (BigDecimal)
    "equals"    (fn [target args] (str "(" target ".compareTo(" args ") == 0)"))
    "not_equals" (fn [target args] (str "(" target ".compareTo(" args ") != 0)"))
    "less_than" (fn [target args] (str "(" target ".compareTo(" args ") < 0)"))
    "less_than_or_equal" (fn [target args] (str "(" target ".compareTo(" args ") <= 0)"))
    "greater_than" (fn [target args] (str "(" target ".compareTo(" args ") > 0)"))
    "greater_than_or_equal" (fn [target args] (str "(" target ".compareTo(" args ") >= 0)"))
    "compare"    (fn [target args] (str "Integer.signum(" target ".compareTo((java.math.BigDecimal)" args "))"))
    "hash"       (fn [target _] (str target ".hashCode()"))}

   :Char
   {"to_string"  (fn [target _] (str "String.valueOf(" target ")"))
    "to_upper"   (fn [target _] (str "Character.toUpperCase(" target ")"))
    "to_lower"   (fn [target _] (str "Character.toLowerCase(" target ")"))
    "compare"    (fn [target args] (str "Character.compare(" target ", (char)" args ")"))
    "hash"       (fn [target _] (str "Character.hashCode(" target ")"))}

   :Boolean
   {"to_string"  (fn [target _] (str "String.valueOf(" target ")"))
    "and"        (fn [target args] (str "(" target " && " args ")"))
    "or"         (fn [target args] (str "(" target " || " args ")"))
    "not"        (fn [target _] (str "(!" target ")"))
    "equals"     (fn [target args] (str "(" target " == " args ")"))
    "not_equals" (fn [target args] (str "(" target " != " args ")"))
    "compare"    (fn [target args] (str "Boolean.compare(" target ", (boolean)" args ")"))
    "hash"       (fn [target _] (str "Boolean.hashCode(" target ")"))}

   :Array
   {"length"    (fn [target _] (str target ".size()"))
    "is_empty"  (fn [target _] (str target ".isEmpty()"))
    "get"       (fn [target args] (str target ".get(" args ")"))
    "add"       (fn [target args] (str target ".add(" args ")"))
    "add_at"    (fn [target args] (str target ".add(" args ")"))
    "put"       (fn [target args] (str target ".set(" args ")"))
    "contains"  (fn [target args] (str target ".contains(" args ")"))
    "index_of"  (fn [target args] (str target ".indexOf(" args ")"))
    "remove"    (fn [target args] (str "(" target ".remove((int)" args "), " target ")"))
    "reverse"   (fn [target _] (str "new ArrayList<>(" target ".reversed())"))
    "sort"      (fn [target _] (str "(Collections.sort(" target "), " target ")"))
    "slice"     (fn [target args] (str "new ArrayList<>(" target ".subList(" args "))"))
    "to_string" (fn [target _] (str "NexRuntime.toStringValue(" target ")"))
    "equals"    (fn [target args] (str "NexRuntime.deepEquals(" target ", " args ")"))
    "clone"     (fn [target _] (str "(ArrayList) NexRuntime.deepClone(" target ")"))
    "cursor"    (fn [target _] (str "NexRuntime.arrayCursor(" target ")"))}

   :Map
   {"get"          (fn [target args] (str target ".get(" args ")"))
    "try_get"      (fn [target args] (str target ".get(" args ")"))
    "put"          (fn [target args] (str "(" target ".put(" args "), " target ")"))
    "size"         (fn [target _] (str target ".size()"))
    "is_empty"     (fn [target _] (str target ".isEmpty()"))
    "contains_key" (fn [target args] (str target ".containsKey(" args ")"))
    "keys"         (fn [target _] (str "new ArrayList<>(" target ".keySet())"))
    "values"       (fn [target _] (str "new ArrayList<>(" target ".values())"))
    "remove"       (fn [target args] (str "(" target ".remove(" args "), " target ")"))
    "to_string"    (fn [target _] (str "NexRuntime.toStringValue(" target ")"))
    "equals"       (fn [target args] (str "NexRuntime.deepEquals(" target ", " args ")"))
    "clone"        (fn [target _] (str "(HashMap) NexRuntime.deepClone(" target ")"))
    "cursor"       (fn [target _] (str "NexRuntime.mapCursor(" target ")"))}

   :Set
   {"contains"             (fn [target args] (str target ".contains(" args ")"))
    "union"                (fn [target args] (str "NexRuntime.setUnion(" target ", " args ")"))
    "difference"           (fn [target args] (str "NexRuntime.setDifference(" target ", " args ")"))
    "intersection"         (fn [target args] (str "NexRuntime.setIntersection(" target ", " args ")"))
    "symmetric_difference" (fn [target args] (str "NexRuntime.setSymmetricDifference(" target ", " args ")"))
    "size"                 (fn [target _] (str target ".size()"))
    "is_empty"             (fn [target _] (str target ".isEmpty()"))
    "to_string"            (fn [target _] (str "NexRuntime.toStringValue(" target ")"))
    "equals"               (fn [target args] (str "NexRuntime.deepEquals(" target ", " args ")"))
    "clone"                (fn [target _] (str "(LinkedHashSet) NexRuntime.deepClone(" target ")"))
    "cursor"               (fn [target _] (str "NexRuntime.setCursor(" target ")"))}

   :Task
   {"await"    (fn [target args] (str target ".await(" args ")"))
    "cancel"   (fn [target _] (str target ".cancel()"))
    "is_done"  (fn [target _] (str target ".is_done()"))
    "is_cancelled" (fn [target _] (str target ".is_cancelled()"))}

   :Channel
   {"send"        (fn [target args] (str target ".send(" args ")"))
    "try_send"    (fn [target args] (str target ".try_send(" args ")"))
    "receive"     (fn [target args] (str target ".receive(" args ")"))
    "try_receive" (fn [target _] (str target ".try_receive()"))
    "close"       (fn [target _] (str target ".close()"))
    "is_closed"   (fn [target _] (str target ".is_closed()"))
    "capacity"    (fn [target _] (str target ".capacity()"))
    "size"        (fn [target _] (str target ".size()"))}

   :Image
   {"width"        (fn [target _] (str target ".width()"))
    "height"       (fn [target _] (str target ".height()"))}

   :Console
   {"print"        (fn [_ args] (str "System.out.print(" args ")"))
    "print_line"   (fn [_ args] (str "System.out.println(" args ")"))
    "read_line"    (fn [_ args] (if (empty? args)
                                  "new java.util.Scanner(System.in).nextLine()"
                                  (str "new java.util.Scanner(System.in).nextLine() /* prompt: " args " */")))
    "error"        (fn [_ args] (str "System.err.println(" args ")"))
    "new_line"     (fn [_ _] "System.out.println()")
    "read_integer" (fn [_ _] "NexRuntime.parseInt(new java.util.Scanner(System.in).nextLine())")
    "read_real"    (fn [_ _] "Double.parseDouble(new java.util.Scanner(System.in).nextLine().trim())")}

   :Process
   {"getenv"       (fn [_ a] (str "System.getenv(" a ")"))
    "setenv"       (fn [_ a] (str "/* setenv not supported in Java: " a " */"))
    "command_line" (fn [_ _] "new ArrayList<>(java.util.Arrays.asList(args))")}})

(defn function-type?
  "Check if a type is Function (string or map with base-type Function)"
  [t]
  (or (= t "Function")
      (and (map? t) (= (:base-type t) "Function"))))

(defn public-member?
  [member]
  (not= :private (-> member :visibility :type)))

(defn infer-constant-type
  "Infer a constant type from its value expression."
  [expr constants-by-name]
  (case (:type expr)
    :integer "Integer"
    :real "Real"
    :string "String"
    :boolean "Boolean"
    :char "Char"
    :nil "Any"
    :identifier (get constants-by-name (:name expr) "Any")
    :binary (let [lt (infer-constant-type (:left expr) constants-by-name)
                  rt (infer-constant-type (:right expr) constants-by-name)]
              (case (:operator expr)
                ("=" "/=" "<" "<=" ">" ">=" "and" "or") "Boolean"
                "+" (if (or (= lt "String") (= rt "String")) "String" lt)
                ("-" "*" "/" "%" "^") lt
                "Any"))
    :call (if (and (string? (:target expr)) (false? (:has-parens expr)))
            (get constants-by-name (:method expr) "Any")
            "Any")
    "Any"))

(declare builtin-dispatch-type)

(defn integral-dispatch-type?
  [t]
  (contains? #{"Integer" "Integer64"} t))

(defn division-dispatch-type
  [left-type right-type]
  (if (and (integral-dispatch-type? left-type)
           (integral-dispatch-type? right-type))
    (if (or (= left-type "Integer64") (= right-type "Integer64"))
      "Integer64"
      "Integer")
    "Real"))

(defn power-dispatch-type
  [left-type right-type]
  (division-dispatch-type left-type right-type))

(defn infer-expression-type
  "Infer a Nex type for code generation dispatch."
  [expr]
  (cond
    (string? expr) (or (get *local-types* expr)
                       (get *field-types* expr)
                       "Any")
    (not (map? expr)) "Any"
    :else
    (case (:type expr)
      :string "String"
      :integer "Integer"
      :real "Real"
      :boolean "Boolean"
      :char "Char"
      :binary (let [left-type (builtin-dispatch-type (infer-expression-type (:left expr)))
                    right-type (builtin-dispatch-type (infer-expression-type (:right expr)))]
                (case (:operator expr)
                  ("=" "/=" "<" "<=" ">" ">=" "and" "or") "Boolean"
                  "+" (if (or (= left-type "String") (= right-type "String")) "String" left-type)
                  "/" (division-dispatch-type left-type right-type)
                  "^" (power-dispatch-type left-type right-type)
                  ("-" "*" "%") left-type
                  "Any"))
      :call (let [target-type (when (:target expr)
                                (infer-expression-type (:target expr)))
                  normalized-target (builtin-dispatch-type target-type)
                  type-args (when (map? target-type)
                              (or (:type-args target-type) (:type-params target-type)))
                  first-arg-type (when-let [arg (first (:args expr))]
                                   (infer-expression-type arg))
                  first-arg-params (when (map? first-arg-type)
                                     (or (:type-args first-arg-type) (:type-params first-arg-type)))
                  nested-task-type (when (and (map? first-arg-type)
                                              (= (:base-type first-arg-type) "Array"))
                                     (first first-arg-params))
                  nested-task-params (when (map? nested-task-type)
                                       (or (:type-args nested-task-type) (:type-params nested-task-type)))]
              (if (nil? (:target expr))
                (case (:method expr)
                  "await_any" (or (first nested-task-params) "Any")
                  "await_all" {:base-type "Array" :type-params [(or (first nested-task-params) "Any")]}
                  "type_of" "String"
                  "type_is" "Boolean"
                  "sleep" "Void"
                  "regex_validate" "Void"
                  "regex_matches" "Boolean"
                  "regex_find" {:base-type "String" :detachable true}
                  "regex_find_all" {:base-type "Array" :type-params ["String"]}
                  "regex_replace" "String"
                  "regex_split" {:base-type "Array" :type-params ["String"]}
                  "datetime_now" "Integer64"
                  "datetime_from_epoch_millis" "Integer64"
                  "datetime_parse_iso" "Integer64"
                  "datetime_make" "Integer64"
                  "datetime_year" "Integer"
                  "datetime_month" "Integer"
                  "datetime_day" "Integer"
                  "datetime_weekday" "Integer"
                  "datetime_day_of_year" "Integer"
                  "datetime_hour" "Integer"
                  "datetime_minute" "Integer"
                  "datetime_second" "Integer"
                  "datetime_epoch_millis" "Integer64"
                  "datetime_add_millis" "Integer64"
                  "datetime_diff_millis" "Integer64"
                  "datetime_truncate_to_day" "Integer64"
                  "datetime_truncate_to_hour" "Integer64"
                  "datetime_format_iso" "String"
                  "path_exists" "Boolean"
                  "path_is_file" "Boolean"
                  "path_is_directory" "Boolean"
                  "path_name" "String"
                  "path_extension" "String"
                  "path_name_without_extension" "String"
                  "path_absolute" "String"
                  "path_normalize" "String"
                  "path_size" "Integer64"
                  "path_modified_time" "Integer64"
                  "path_parent" {:base-type "String" :detachable true}
                  "path_child" "String"
                  "path_create_file" "Void"
                  "path_create_directory" "Void"
                  "path_create_directories" "Void"
                  "path_delete" "Void"
                  "path_delete_tree" "Void"
                  "path_copy" "Void"
                  "path_move" "Void"
                  "path_read_text" "String"
                  "path_write_text" "Void"
                  "path_append_text" "Void"
                  "path_list" {:base-type "Array" :type-params ["String"]}
                  "text_file_open_read" "Any"
                  "text_file_open_write" "Any"
                  "text_file_open_append" "Any"
                  "text_file_read_line" {:base-type "String" :detachable true}
                  "text_file_write" "Void"
                  "text_file_close" "Void"
                  "binary_file_open_read" "Any"
                  "binary_file_open_write" "Any"
                  "binary_file_open_append" "Any"
                  "binary_file_read_all" {:base-type "Array" :type-params ["Integer"]}
                  "binary_file_read" {:base-type "Array" :type-params ["Integer"]}
                  "binary_file_write" "Void"
                  "binary_file_close" "Void"
                  "json_parse" "Any"
                  "json_stringify" "String"
                  "http_get" "Http_Response"
                  "http_post" "Http_Response"
                  "http_server_create" "Any"
                  "http_server_get" "Void"
                  "http_server_post" "Void"
                  "http_server_put" "Void"
                  "http_server_delete" "Void"
                  "http_server_start" "Integer"
                  "http_server_stop" "Void"
                  "http_server_is_running" "Boolean"
                  "Any")
                (case normalized-target
                "Task" (case (:method expr)
                         "await" (if (seq (:args expr))
                                   {:base-type (or (first type-args) "Any") :detachable true}
                                   (or (first type-args) "Any"))
                         "cancel" "Boolean"
                         "is_done" "Boolean"
                         "is_cancelled" "Boolean"
                         "Any")
                "Channel" (case (:method expr)
                            "receive" (if (seq (:args expr))
                                        {:base-type (or (first type-args) "Any") :detachable true}
                                        (or (first type-args) "Any"))
                            "try_receive" {:base-type (or (first type-args) "Any") :detachable true}
                            "is_closed" "Boolean"
                            "capacity" "Integer"
                            "size" "Integer"
                            "send" (if (= 2 (count (:args expr))) "Boolean" "Void")
                            "try_send" "Boolean"
                            "close" "Void"
                            "Any")
                "Set" (case (:method expr)
                        ("contains" "is_empty") "Boolean"
                        "size" "Integer"
                        ("union" "difference" "intersection" "symmetric_difference") target-type
                        "Any")
                "Array" (case (:method expr)
                          "get" (or (first type-args) "Any")
                          ("length" "size" "index_of") "Integer"
                          "is_empty" "Boolean"
                          "Any")
                "Map" (case (:method expr)
                        "get" (or (second type-args) "Any")
                        ("size") "Integer"
                        ("is_empty" "contains_key") "Boolean"
                        "Any")
                "String" (case (:method expr)
                           ("length" "index_of") "Integer"
                           "contains" "Boolean"
                           ("substring" "to_upper" "to_lower" "trim" "replace") "String"
                           "Any")
                "Any")))
      :array-literal "Array"
      :map-literal "Map"
      :set-literal "Set"
      :spawn "Task"
      :create (:class-name expr)
      :identifier (or (get *local-types* (:name expr))
                      (get *field-types* (:name expr))
                      "Any")
      "Any")))

(defn infer-spawn-result-type
  "Infer the result type assigned inside a spawn body."
  [stmts]
  (letfn [(collect-from-stmt [stmt]
            (when (map? stmt)
              (case (:type stmt)
                :assign (when (= (:target stmt) "result")
                          [(infer-expression-type (:value stmt))])
                :let (when (= (:name stmt) "result")
                       [(or (:var-type stmt) (infer-expression-type (:value stmt)))])
                :if (concat (mapcat collect-from-stmt (:then stmt))
                            (mapcat (fn [clause] (mapcat collect-from-stmt (:then clause))) (:elseif stmt))
                            (mapcat collect-from-stmt (:else stmt)))
                :loop (concat (mapcat collect-from-stmt (:init stmt))
                              (mapcat collect-from-stmt (:body stmt)))
                :scoped-block (concat (mapcat collect-from-stmt (:body stmt))
                                      (mapcat collect-from-stmt (:rescue stmt)))
                :with (mapcat collect-from-stmt (:body stmt))
                :case (concat (mapcat (fn [clause] (collect-from-stmt (:body clause))) (:clauses stmt))
                              (when-let [else-stmt (:else stmt)]
                                (collect-from-stmt else-stmt)))
                [])))]
    (let [types (remove #(or (nil? %) (= % "Void") (= % "Any"))
                        (mapcat collect-from-stmt stmts))]
      (first types))))

(defn builtin-dispatch-type
  "Normalize inferred types for builtin dispatch."
  [t]
  (cond
    (map? t) (:base-type t)
    :else t))

(defn extract-typed-locals
  "Collect typed local bindings from statements."
  [stmts]
  (reduce
   (fn [acc stmt]
     (if-not (map? stmt)
       acc
       (case (:type stmt)
         :let (if-let [t (:var-type stmt)]
                (assoc acc (:name stmt) t)
                acc)
         :if (merge acc
                    (extract-typed-locals (:then stmt))
                    (reduce merge {} (map #(extract-typed-locals (:then %)) (:elseif stmt)))
                    (extract-typed-locals (:else stmt)))
         :loop (merge acc
                      (extract-typed-locals (:init stmt))
                      (extract-typed-locals (:body stmt)))
         :scoped-block (merge acc
                              (extract-typed-locals (:body stmt))
                              (extract-typed-locals (:rescue stmt)))
         :with (merge acc (extract-typed-locals (:body stmt)))
         :case (merge acc
                      (reduce merge {} (map (fn [clause]
                                              (extract-typed-locals [(:body clause)]))
                                            (:clauses stmt)))
                      (extract-typed-locals (when-let [else-stmt (:else stmt)] [else-stmt])))
         acc)))
   {}
   (or stmts [])))

(defn get-parent-constants
  "Get recursively inherited public constants from a parent class."
  [parent-name]
  (when-let [parent-def (get *class-registry* parent-name)]
    (let [{:keys [constants]} (extract-members (:body parent-def))
          inherited (mapcat (fn [{:keys [parent]}] (or (get-parent-constants parent) []))
                            (:parents parent-def))]
      (vals
       (reduce (fn [m constant]
                 (if (contains? m (:name constant))
                   m
                   (assoc m (:name constant) constant)))
               {}
               (concat (filter public-member? inherited)
                       (filter public-member? constants)))))))

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
    (contains? *constant-names* id-name) id-name
    (contains? *own-fields* id-name) id-name
    (contains? *parent-field-map* id-name)
    (let [parent-prefix (get *parent-field-map* id-name)]
      (if (= *this-name* "this")
        (str parent-prefix "." id-name)
        (str *this-name* "." parent-prefix "." id-name)))
    (contains? *function-names* id-name) (str "NexGlobals." id-name)
    :else id-name))

(declare generate-create-expr)
(declare generate-statement)
(declare collect-convert-bindings-block)
(declare generate-convert-declarations)

(defn generate-spawn-expr
  "Generate Java code for spawn expression."
  [{:keys [body]}]
  (let [convert-bindings (collect-convert-bindings-block body)
        convert-var-names (set (map :name convert-bindings))
        convert-var-types (into {} (map (juxt :name :target-type) convert-bindings))
        result-type (or (infer-spawn-result-type body) "Any")
        java-result-type (if (= result-type "Any")
                           "Object"
                           (nex-type-to-java-boxed result-type))
        local-types (merge convert-var-types (extract-typed-locals body) {"result" result-type})
        result-flag (str (gensym "__nexSpawnHasResult"))]
    (binding [*local-names* (into *local-names* (conj convert-var-names "result"))
              *local-types* (merge *local-types* local-types)
              *spawn-result-flag* result-flag]
      (let [statement-lines (map #(generate-statement 1 %) body)]
        (str "NexRuntime.spawnTask(() -> {\n"
             (indent 1 (str java-result-type " result = null;")) "\n"
             (indent 1 (str "boolean " result-flag " = false;")) "\n"
             (str/join "\n" (generate-convert-declarations 1 convert-bindings))
             (when (seq convert-bindings) "\n")
             (str/join "\n" statement-lines)
             (when (seq statement-lines) "\n")
             (indent 1 (str "return " result-flag " ? result : null;")) "\n"
             "})")))))

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
          ;; Constructor-call syntax is parsed as a call whose target is :create and method is nil.
          (if (and (map? target) (= :create (:type target)))
            (generate-create-expr (assoc target :args args))
            ;; Calling an expression that returns a function
            (str target-code ".call" num-args "(" args-code ")"))
          ;; Check if target is a parent class name (composition delegation)
          (if (and (string? target) (contains? *current-parents* target))
            ;; Parent-qualified call: delegate through composition field
            (let [prefix (if (= *this-name* "this")
                           ""
                           (str *this-name* "."))]
              (str prefix "_parent_" target "." method "(" args-code ")"))
            (if (and (string? target)
                     (false? has-parens)
                     (get *class-registry* target)
                     (some #(= (:name %) method) (get-accessible-constants (get *class-registry* target))))
              (str target "." method)
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
                  (if-let [method-fn (get-in builtin-method-mappings [:Any method])]
                    (method-fn target-code args-code)
                    (str target-code "." method "(" args-code ")"))))
              ;; External object: try builtins, then default
              (let [dispatch-type (builtin-dispatch-type (infer-expression-type target))]
                (or
               (when-let [method-fn (get-in builtin-method-mappings [(keyword dispatch-type) method])]
                 (method-fn target-code args-code))
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
               ;; Try Array methods (ArrayList in Java)
               (when-let [method-fn (get-in builtin-method-mappings [:Array method])]
                 (method-fn target-code args-code))
               ;; Try Map methods
               (when-let [method-fn (get-in builtin-method-mappings [:Map method])]
                 (method-fn target-code args-code))
               ;; Try Set methods
               (when-let [method-fn (get-in builtin-method-mappings [:Set method])]
                 (method-fn target-code args-code))
               ;; Try Image methods
               (when-let [method-fn (get-in builtin-method-mappings [:Image method])]
                 (method-fn target-code args-code))
               ;; Root Any methods for arbitrary objects
               (when-let [method-fn (get-in builtin-method-mappings [:Any method])]
                 (method-fn target-code args-code))
               ;; Handle Java reserved words used as method names
               (when (= method "goto")
                 (str target-code ".goto_(" args-code ")"))
               ;; Default: regular method call
               (str target-code "." method "(" args-code ")"))))))))
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

        ;; Function-typed local/parameter/top-level binding
        (and method (function-type? (get *local-types* method)))
        (str method ".call" num-args "(" args-code ")")

        ;; Local/parameter/top-level binding shadows global functions and is callable
        (and method (contains? *local-names* method))
        (str method ".call" num-args "(" args-code ")")

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
      "Process" "new Object() /* Process */"
      "Map" "new HashMap<>()"
      "Channel" (cond
                  (nil? constructor)
                  (str "new NexRuntime.Channel" (or type-params "<>") "()")
                  (= constructor "with_capacity")
                  (str "new NexRuntime.Channel" (or type-params "<>") "(" args-code ")")
                  :else
                  (throw (ex-info (str "Unsupported built-in Channel constructor: " constructor)
                                  {:constructor constructor})))
      "Set" (if (= constructor "from_array")
              (str "NexRuntime.setFromArray(" args-code ")")
              "new LinkedHashSet<>()")
      "Window" (str "new NexWindow(" args-code ")")
      "Turtle" (str "new NexTurtle(" args-code ")")
      "Image" (if (= constructor "from_file")
                (str "NexImage.from_file(" args-code ")")
                (str "new NexImage(" args-code ")"))
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

(defn generate-set-literal
  "Generate Java code for set literal"
  [elements]
  (let [elements-code (str/join ", " (map generate-expression elements))]
    (str "NexRuntime.setOf(" elements-code ")")))

(defn java-convert-type
  "Return a nullable Java type suitable for convert bindings/casts."
  [target-type]
  (cond
    (map? target-type) (nex-type-to-java-boxed (:base-type target-type))
    (string? target-type) (nex-type-to-java-boxed target-type)
    :else "Object"))

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
  (map (fn [{:keys [name target-type]}]
         (indent level (str (java-convert-type target-type) " " name " = null;")))
       bindings))

(defn generate-convert-expr
  [{:keys [value var-name target-type]}]
  (let [value-code (generate-expression value)
        temp-name (str (gensym "__nex_conv_tmp_"))
        java-type (java-convert-type target-type)
        cond-code (if (any-type? target-type)
                    "true"
                    (str temp-name " instanceof " java-type))]
    (str "((java.util.function.Supplier<Boolean>) () -> { "
         "Object " temp-name " = " value-code "; "
         "if (" cond-code ") { "
         var-name " = (" java-type ") " temp-name "; return true; } "
         var-name " = null; return false; "
         "}).get()")))

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
    :set-literal (generate-set-literal (:elements expr))
    :map-literal (generate-map-literal (:entries expr))
    :convert (generate-convert-expr expr)
    :spawn (generate-spawn-expr expr)
    :anonymous-function (let [class-def (:class-def expr)
                              ;; Extract the callN method definition
                              method-def (first (:members (first (:body class-def))))
                              num-args (count (:params method-def))
                              method-name (:name method-def)
                              params (:params method-def)
                              return-type (:return-type method-def)
                              impl-name (str method-name "$impl")]
                          (str "(new Function() {\n"
                               (indent 1 (str "@Override public Object call" num-args "("
                                              (str/join ", " (map #(str "Object arg" %) (range 1 (inc num-args))))
                                              ") {\n"))
                               (indent 2 (str "return this." impl-name "("
                                              (str/join ", " (map (fn [i p]
                                                                    (object-arg-cast (:type p) (str "arg" i)))
                                                                  (range 1 (inc num-args))
                                                                  params))
                                              ");\n"))
                               (indent 1 "}\n")
                               (generate-method 1 (assoc method-def :name impl-name) {})
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
  (if (and *spawn-result-flag* (= target "result"))
    (str (resolve-field-name target) " = " (generate-expression value)
         "; " *spawn-result-flag* " = true;")
    (str (resolve-field-name target) " = " (generate-expression value) ";")))

(defn generate-let
  "Generate Java code for let (local variable declaration).
   If var-names is provided and contains the variable name, generates assignment instead."
  ([let-node] (generate-let let-node #{}))
  ([{:keys [name var-type value]} var-names]
   (if (contains? var-names name)
     ;; Variable already declared in outer scope - generate assignment
     (if (and *spawn-result-flag* (= name "result"))
       (str name " = " (generate-expression value) "; " *spawn-result-flag* " = true;")
       (str name " = " (generate-expression value) ";"))
     ;; New variable - generate declaration
     (if var-type
       ;; With type: "int x = 10;"
       (let [stmt (str (nex-type-to-java var-type) " " name " = " (generate-expression value) ";")]
         (if (and *spawn-result-flag* (= name "result"))
           (str stmt " " *spawn-result-flag* " = true;")
           stmt))
       ;; Without type: use 'var' for type inference (Java 10+)
       (let [stmt (str "var " name " = " (generate-expression value) ";")]
         (if (and *spawn-result-flag* (= name "result"))
           (str stmt " " *spawn-result-flag* " = true;")
           stmt))))))

(defn generate-if
  "Generate Java code for if/elseif/else"
  [level {:keys [condition then elseif else] :as node} var-names]
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
      statement-code)))

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
                      (indent level "}")])))))))

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

(defn- select-clause-call
  [clause]
  (:expr clause))

(defn- select-alias-java-type
  [expr]
  (let [target-type (infer-expression-type (:target expr))
        type-args (when (map? target-type)
                    (or (:type-args target-type) (:type-params target-type)))
        elem-type (if (= (builtin-dispatch-type target-type) "Task")
                    (or (first type-args) "Any")
                    (or (first type-args) "Any"))]
    (nex-type-to-java-boxed elem-type)))

(defn- generate-select-clause-java
  [level clause var-names idx]
  (let [{:keys [target method args]} (select-clause-call clause)
        target-code (if (string? target) target (generate-expression target))
        arg-code (when (seq args) (generate-expression (first args)))
        body-var-names (cond-> var-names (:alias clause) (conj (:alias clause)))
        body-code (str/join "\n" (map #(generate-statement (+ level 3) % body-var-names) (:body clause)))
        temp-name (str "__select_value_" idx)]
    (cond
      (= method "receive")
      (let [temp-type (select-alias-java-type (:expr clause))
            alias-lines (when (:alias clause)
                          [(indent (+ level 3) (str temp-type " " (:alias clause) " = " temp-name ";"))])]
        (str/join "\n"
                  (concat
                   [(indent (+ level 2) "{")
                    (indent (+ level 3) (str temp-type " " temp-name " = " target-code ".try_receive();"))
                    (indent (+ level 3) (str "if (" temp-name " != null) {"))]
                   alias-lines
                   [body-code
                    (indent (+ level 4) "break;")
                    (indent (+ level 3) "}")
                    (indent (+ level 2) "}")])))

      (= method "try_receive")
      (let [temp-type (select-alias-java-type (:expr clause))
            alias-lines (when (:alias clause)
                          [(indent (+ level 3) (str temp-type " " (:alias clause) " = " temp-name ";"))])]
        (str/join "\n"
                  (concat
                   [(indent (+ level 2) "{")
                    (indent (+ level 3) (str temp-type " " temp-name " = " target-code ".try_receive();"))
                    (indent (+ level 3) (str "if (" temp-name " != null) {"))]
                   alias-lines
                   [body-code
                    (indent (+ level 4) "break;")
                    (indent (+ level 3) "}")
                    (indent (+ level 2) "}")])))

      (= method "send")
      (str/join "\n"
                [(indent (+ level 2) (str "if (" target-code ".try_send(" arg-code ")) {"))
                 body-code
                 (indent (+ level 3) "break;")
                 (indent (+ level 2) "}")])

      (= method "try_send")
      (str/join "\n"
                [(indent (+ level 2) (str "if (" target-code ".try_send(" arg-code ")) {"))
                 body-code
                 (indent (+ level 3) "break;")
                 (indent (+ level 2) "}")])

      (= method "await")
      (let [temp-type (select-alias-java-type (:expr clause))
            alias-lines (when (:alias clause)
                          [(indent (+ level 3) (str temp-type " " (:alias clause) " = " temp-name ";"))])]
        (str/join "\n"
                  (concat
                   [(indent (+ level 2) (str "if (" target-code ".is_done()) {"))]
                   (when (:alias clause)
                     [(indent (+ level 3) (str temp-type " " temp-name " = " target-code ".await();"))])
                   (when-not (:alias clause)
                     [(indent (+ level 3) (str target-code ".await();"))])
                   alias-lines
                   [body-code
                    (indent (+ level 3) "break;")
                    (indent (+ level 2) "}")])))

      :else
      (indent (+ level 2) "/* Unsupported select clause */"))))

(defn generate-select
  [level {:keys [clauses else timeout]} var-names]
  (let [clause-code (map-indexed (fn [idx clause]
                                   (generate-select-clause-java level clause var-names idx))
                                 clauses)
        else-code (when else
                    [(str/join "\n" (map #(generate-statement (+ level 2) % var-names) else))
                     (indent (+ level 2) "break;")])
        timeout-code (when timeout
                       [(indent level "{")
                        (indent (+ level 1) (str "long __selectDeadline = System.currentTimeMillis() + " (generate-expression (:duration timeout)) ";"))])
        timeout-body (when timeout
                       [(indent (+ level 1) "if (System.currentTimeMillis() >= __selectDeadline) {")
                        (str/join "\n" (map #(generate-statement (+ level 2) % var-names) (:body timeout)))
                        (indent (+ level 2) "break;")
                        (indent (+ level 1) "}")])]
    (str/join "\n"
              (concat
               timeout-code
               [(indent level "while (true) {")]
               clause-code
               else-code
               (when-not else
                 (concat
                  timeout-body
                  [(indent (+ level 1) "try {")
                   (indent (+ level 2) "Thread.sleep(1);")
                   (indent (+ level 1) "} catch (InterruptedException e) {")
                   (indent (+ level 2) "Thread.currentThread().interrupt();")
                   (indent (+ level 2) "throw new RuntimeException(e);")
                   (indent (+ level 1) "}")]))
               [(indent level "}")]
               (when timeout [(indent level "}")])))))

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
     :spawn (indent level (str (generate-expression stmt) ";"))
     :let (indent level (generate-let stmt var-names))
     :if (generate-if level stmt var-names)
     :case (generate-case level stmt var-names)
     :select (generate-select level stmt var-names)
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
        param-types (into {} (map (juxt :name :type) params))
        convert-bindings (collect-convert-bindings-block (concat body (or rescue [])))
        convert-var-names (set (map :name convert-bindings))
        convert-var-types (into {} (map (juxt :name :target-type) convert-bindings))
        local-types (merge param-types
                           convert-var-types
                           (extract-typed-locals body)
                           (extract-typed-locals rescue)
                           (when return-type {"result" return-type}))
        local-names (cond-> (into param-names convert-var-names)
                      return-type (conj "result"))]
    (binding [*local-names* (into *local-names* local-names)
              *local-types* (merge *local-types* local-types)]
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
            convert-decls (generate-convert-declarations (+ level 1) convert-bindings)
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
            class-invariant-checks (generate-assertions (+ level 1) *class-invariants* "Class invariant" opts)
            ;; Add return statement if method has return type
            return-stmt (when return-type
                         [(indent (+ level 1) "return result;")])]
        (str/join "\n"
                  (concat
                   javadoc
                   [(indent level (str vis " " java-return " " name "(" params-code ") {"))]
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

(defn generate-field
  "Generate Java code for a field with default initialization"
  [level {:keys [name field-type visibility note constant? value]}]
  (let [vis (if (and visibility (= (:type visibility) :private))
             "private"
             "public")
        java-type (nex-type-to-java field-type)
        init-value (if constant?
                     (generate-expression value)
                     (default-value field-type))
        ;; Generate Javadoc if note present
        javadoc (when note
                 (generate-javadoc level note))]
    (if javadoc
      (str javadoc "\n" (indent level (str vis " "
                                          (when constant? "static final ")
                                          java-type " " name " = " init-value ";")))
      (indent level (str vis " "
                         (when constant? "static final ")
                         java-type " " name " = " init-value ";")))))

;;
;; Constructor Generation
;;

(defn generate-constructor
  "Generate Java static factory method for a Nex constructor"
  [level class-name {:keys [name params body require ensure rescue]} opts]
  (let [local-name (class-name-to-local class-name)
        param-names (set (map :name params))
        param-types (into {} (map (juxt :name :type) params))
        convert-bindings (collect-convert-bindings-block (concat body (or rescue [])))
        convert-var-names (set (map :name convert-bindings))
        convert-var-types (into {} (map (juxt :name :target-type) convert-bindings))
        local-types (merge param-types
                           convert-var-types
                           (extract-typed-locals body)
                           (extract-typed-locals rescue))
        local-names (into param-names convert-var-names)
        params-code (str/join ", "
                              (map (fn [{:keys [name type]}]
                                     (str (nex-type-to-java type) " " name))
                                   params))
        preconditions (binding [*this-name* local-name
                                *local-names* (into *local-names* local-names)
                                *local-types* (merge *local-types* local-types)]
                        (generate-assertions (+ level 1) require "Precondition" opts))
        statements (binding [*this-name* local-name
                             *local-names* (into *local-names* local-names)
                             *local-types* (merge *local-types* local-types)]
                     (if rescue
                       [(generate-rescue (+ level 1) body rescue #{})]
                       (mapv #(generate-statement (+ level 1) %) body)))
        postconditions (binding [*this-name* local-name
                                 *local-names* (into *local-names* local-names)
                                 *local-types* (merge *local-types* local-types)]
                         (generate-assertions (+ level 1) ensure "Postcondition" opts))
        class-invariant-checks (binding [*this-name* local-name
                                         *local-names* (into *local-names* local-names)
                                         *local-types* (merge *local-types* local-types)]
                                 (generate-assertions (+ level 1) *class-invariants* "Class invariant" opts))]
    (str/join "\n"
              (concat
               [(indent level (str "public static " class-name " " name "(" params-code ") {"))]
               [(indent (+ level 1) (str class-name " " local-name " = new " class-name "();"))]
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
  "Return the list of parent names for composition-based inheritance"
  [parents]
  (->> parents
       (remove #(= "Any" (:parent %)))
       (mapv :parent)))

(defn collect-effective-class-invariants
  "Collect effective class invariants as:
   inherited invariants from all parent chains (deduped by ancestor class) + local invariants."
  [class-def]
  (letfn [(collect [cls seen]
            (let [class-name (:name cls)
                  already-seen? (and class-name (contains? seen class-name))
                  seen' (if class-name (conj seen class-name) seen)]
              (if already-seen?
                [[] seen]
                (let [[parent-invariants seen'']
                      (if-let [parents (:parents cls)]
                        (reduce (fn [[acc seen-so-far] {:keys [parent]}]
                                  (if-let [parent-def (get *class-registry* parent)]
                                    (let [[inv seen-next] (collect parent-def seen-so-far)]
                                      [(into acc inv) seen-next])
                                    [acc seen-so-far]))
                                [[] seen']
                                parents)
                        [[] seen'])
                      local-invariants (or (:invariant cls) [])]
                  [(vec (concat parent-invariants local-invariants)) seen'']))))]
    (first (collect class-def #{}))))

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
  [class-name generic-params _parents deferred?]
  (let [generics (generate-generic-params generic-params)]
    (str "public " (when deferred? "abstract ") "class " class-name generics " {")))

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

(defn get-accessible-constants
  "Get inherited public constants plus local constants, with local override by name."
  [class-def]
  (let [{:keys [constants]} (extract-members (:body class-def))
        inherited (mapcat (fn [{:keys [parent]}]
                            (or (get-parent-constants parent) []))
                          (:parents class-def))]
    (vals
     (reduce (fn [m constant]
               (assoc m (:name constant) constant))
             {}
             (concat inherited constants)))))

(defn get-parent-constructors
  "Get constructor definitions from a direct parent class."
  [parent-name]
  (when-let [parent-def (get *class-registry* parent-name)]
    (let [{:keys [constructors]} (extract-members (:body parent-def))]
      constructors)))

(defn lookup-method-effective-contracts
  "Lookup method in class hierarchy and compute effective contracts:
   require = base OR local
   ensure = base AND local"
  [class-def method-name]
  (let [{:keys [methods]} (extract-members (:body class-def))
        local-method (first (filter #(= (:name %) method-name) methods))]
    (if local-method
      (let [base-lookup (when-let [parents (:parents class-def)]
                          (some (fn [{:keys [parent]}]
                                  (when-let [parent-def (get *class-registry* parent)]
                                    (lookup-method-effective-contracts parent-def method-name)))
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
                (when-let [parent-def (get *class-registry* parent)]
                  (lookup-method-effective-contracts parent-def method-name)))
              parents)))))

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
  [level parents own-method-names opts]
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
                   class-invariant-checks (generate-assertions (+ level 1) *class-invariants* "Class invariant" opts)
                   body-lines (if return-type
                                (concat
                                 [(indent (+ level 1) (str java-return " __result = " call-str ";"))]
                                 class-invariant-checks
                                 [(indent (+ level 1) "return __result;")])
                                (concat
                                 [(indent (+ level 1) (str call-str ";"))]
                                 class-invariant-checks))]
               (str (indent level (str "public " java-return " " name "(" params-code ") {"))
                    "\n"
                    (str/join "\n" body-lines)
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

(defn generate-inherited-constructor-shims
  "Generate child static factory constructors that forward to direct parent constructors."
  [level class-name parents own-constructor-names opts]
  (let [all-parent-ctors (mapcat (fn [{:keys [parent]}]
                                   (map (fn [ctor] {:parent parent :ctor ctor})
                                        (or (get-parent-constructors parent) [])))
                                 parents)
        ;; Avoid duplicates and do not override constructors declared on the child.
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
                  param-names (set (map :name params))
                  params-code (str/join ", "
                                        (map (fn [{:keys [name type]}]
                                               (str (nex-type-to-java type) " " name))
                                             params))
                  args-code (str/join ", " (map :name params))
                  class-invariant-checks (binding [*this-name* local-name
                                                   *local-names* (into *local-names* param-names)]
                                           (generate-assertions (+ level 1) *class-invariants* "Class invariant" opts))]
              (str/join "\n"
                        (concat
                         [(indent level (str "public static " class-name " " name "(" params-code ") {"))]
                         [(indent (+ level 1) (str class-name " " local-name " = new " class-name "();"))]
                         [(indent (+ level 1) (str local-name "._parent_" parent " = " parent "." name "(" args-code ");"))]
                         class-invariant-checks
                         [(indent (+ level 1) (str "return " local-name ";"))]
                         [(indent level "}")]))))
          selected))))

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
        fields (filter #(and (= (:type %) :field) (not (:constant? %))) all-members)
        constants (let [{:keys [constants]}
                        (reduce (fn [{:keys [types constants]} member]
                                  (if (and (= (:type member) :field) (:constant? member))
                                    (let [final-type (or (:field-type member)
                                                         (infer-constant-type (:value member) types))
                                          constant (assoc member :field-type final-type)]
                                      {:types (assoc types (:name member) final-type)
                                       :constants (conj constants constant)})
                                    {:types types :constants constants}))
                                {:types {} :constants []}
                                all-members)]
                    constants)
        methods (filter #(= (:type %) :method) all-members)
        ctors (mapcat :constructors constructor-sections)]
    {:fields fields
     :constants constants
     :methods methods
     :constructors ctors}))

(defn generate-class
  "Generate Java code for a Nex class"
  ([class-def] (generate-class class-def {}))
  ([class-def opts]
   (let [{:keys [name generic-params parents body note deferred?]} class-def]
     (let [{:keys [fields constants methods constructors]} (extract-members body)
             runtime-parents (vec (remove #(= "Any" (:parent %)) parents))
             parent-names (mapv :parent runtime-parents)
             parent-fm (build-parent-field-map parent-names)
             own-flds (set (map :name fields))
             all-constants (get-accessible-constants class-def)
             constant-names (set (map :name all-constants))
             own-method-names (set (map :name methods))
             all-methods (get-all-method-names parent-names own-method-names)
             fld-types (build-field-types fields parent-names)
             effective-invariants (collect-effective-class-invariants class-def)]
         (binding [*current-parents* (set parent-names)
                   *parent-field-map* parent-fm
                   *own-fields* own-flds
                   *constant-names* constant-names
                   *all-method-names* all-methods
                   *field-types* fld-types
                   *class-invariants* effective-invariants]
           (let [class-javadoc (when note
                                 [(generate-javadoc 0 note)])
                 class-header (generate-class-header name generic-params runtime-parents deferred?)
                 composition-fields (when (seq runtime-parents)
                                      (generate-composition-fields 1 runtime-parents))
                 invariant-comment (when (and (seq effective-invariants) (not (:skip-contracts opts)))
                                     (indent 1 (str "// Class invariant: "
                                                    (str/join ", " (map :label effective-invariants)))))
                 constants-code (map #(generate-field 1 (assoc % :constant? true)) all-constants)
                 fields-code (map #(generate-field 1 %) fields)
                 delegation-methods (when (seq runtime-parents)
                                      (generate-delegation-methods 1 runtime-parents own-method-names opts))
                 constructors-code (map #(generate-constructor 1 name % opts) constructors)
                 inherited-constructor-shims (when (seq runtime-parents)
                                               (generate-inherited-constructor-shims 1 name runtime-parents (set (map :name constructors)) opts))
                 methods-with-effective-contracts
                 (map (fn [m]
                        (let [effective (lookup-method-effective-contracts class-def (:name m))]
                          (assoc m
                                 :require (:effective-require effective)
                                 :ensure (:effective-ensure effective))))
                      methods)
                 methods-code (map #(generate-method 1 % opts) methods-with-effective-contracts)]
             (str/join "\n"
                       (concat
                        class-javadoc
                        [class-header]
                        (when invariant-comment [invariant-comment ""])
                        composition-fields
                        (when (seq composition-fields) [""])
                        constants-code
                        (when (seq all-constants) [""])
                        fields-code
                        (when (seq fields) [""])
                        (when (seq delegation-methods) delegation-methods)
                        (when (seq delegation-methods) [""])
                        constructors-code
                        (when (seq constructors) [""])
                        (when (seq inherited-constructor-shims) inherited-constructor-shims)
                        (when (seq inherited-constructor-shims) [""])
                        methods-code
                        ["}"]))))))))

(defn generate-nex-window-class
  "Generate Java source for NexWindow.java — Swing/AWT window with canvas."
  []
  "import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.CopyOnWriteArrayList;

public class NexWindow {
    private int width;
    private int height;
    private Color bgColor = Color.WHITE;
    private BufferedImage canvas;
    final CopyOnWriteArrayList<NexTurtle> turtles = new CopyOnWriteArrayList<>();
    private JFrame frame;
    private JLabel label;

    public NexWindow() { this(\"Nex Turtle Graphics\", 800, 600); }
    public NexWindow(String title) { this(title, 800, 600); }
    public NexWindow(String title, int w, int h) {
        this.width = w;
        this.height = h;
        canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = canvas.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, w, h);
        g2d.dispose();

        label = new JLabel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.drawImage(canvas, 0, 0, null);
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                     RenderingHints.VALUE_ANTIALIAS_ON);
                for (NexTurtle t : turtles) {
                    if (t.isVisible()) {
                        double cx = width / 2.0 + t.xpos();
                        double cy = height / 2.0 - t.ypos();
                        t.drawCursor(g2d, cx, cy);
                    }
                }
                g2d.dispose();
            }
        };
        label.setPreferredSize(new Dimension(w, h));
        frame = new JFrame(title);
        frame.getContentPane().add(label);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    }

    public int vw() { return width; }
    public int vh() { return height; }
    public BufferedImage getCanvas() { return canvas; }

    public Object show() {
        SwingUtilities.invokeLater(() -> frame.setVisible(true));
        return null;
    }

    public Object close() {
        SwingUtilities.invokeLater(() -> frame.dispose());
        return null;
    }

    public Object clear() {
        Graphics2D g2d = canvas.createGraphics();
        g2d.setColor(this.bgColor);
        g2d.fillRect(0, 0, width, height);
        g2d.dispose();
        label.repaint();
        return null;
    }

    public Object bgcolor(String colorStr) {
        Color c = NexTurtle.parseColor(colorStr);
        this.bgColor = c;
        this.clear();
        return null;
    }

    public Object draw_image(NexImage img, double x, double y) {
        Graphics2D g2d = canvas.createGraphics();
        g2d.drawImage(img.raw(), (int) x, (int) y, null);
        g2d.dispose();
        label.repaint();
        return null;
    }

    public Object draw_image_scaled(NexImage img, double x, double y, double w, double h) {
        Graphics2D g2d = canvas.createGraphics();
        g2d.drawImage(img.raw(), (int) x, (int) y, (int) w, (int) h, null);
        g2d.dispose();
        label.repaint();
        return null;
    }

    public Object draw_image_rotated(NexImage img, double x, double y, double angle) {
        Graphics2D g2d = canvas.createGraphics();
        BufferedImage raw = img.raw();
        double iw = img.width();
        double ih = img.height();
        double cx = x + iw / 2.0;
        double cy = y + ih / 2.0;
        java.awt.geom.AffineTransform saved = g2d.getTransform();
        g2d.translate(cx, cy);
        g2d.rotate(Math.toRadians(angle));
        g2d.drawImage(raw, (int) (-iw / 2.0), (int) (-ih / 2.0), null);
        g2d.setTransform(saved);
        g2d.dispose();
        label.repaint();
        return null;
    }

    public void repaintCanvas() { label.repaint(); }
}")

(defn generate-nex-image-class
  "Generate Java source for NexImage.java — image loading wrapper."
  []
  "import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;

public class NexImage {
    private BufferedImage image;

    public NexImage(String path) {
        try {
            this.image = ImageIO.read(new File(path));
            if (this.image == null) {
                throw new RuntimeException(\"Unable to load image: \" + path);
            }
        } catch (Exception e) {
            throw new RuntimeException(\"Unable to load image: \" + path, e);
        }
    }

    public static NexImage from_file(String path) { return new NexImage(path); }

    public int width() { return image.getWidth(); }
    public int height() { return image.getHeight(); }
    public BufferedImage raw() { return image; }
}")

(defn generate-nex-turtle-class
  "Generate Java source for NexTurtle.java — turtle graphics on a NexWindow canvas."
  []
  "import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;

public class NexTurtle {
    private NexWindow window;
    private double x = 0, y = 0;
    private double heading = 90;  // 90 = north
    private boolean penDown = true;
    private String colorName = \"black\";
    private int penSz = 1;
    private int spd = 6;
    private String shapeName = \"classic\";
    private boolean visible = true;
    private boolean filling = false;
    private java.util.List<double[]> fillPoints = new java.util.ArrayList<>();
    private String fillColor = \"black\";

    public NexTurtle(NexWindow win) {
        this.window = win;
        win.turtles.add(this);
        win.repaintCanvas();
    }

    // Accessors for NexWindow overlay painting
    public double xpos() { return x; }
    public double ypos() { return y; }
    public NexWindow surface() { return window; }
    public boolean isVisible() { return visible; }

    // Color parsing utility
    static Color parseColor(String s) {
        s = s.trim().toLowerCase();
        switch (s) {
            case \"black\":   return new Color(0, 0, 0);
            case \"white\":   return new Color(255, 255, 255);
            case \"red\":     return new Color(255, 0, 0);
            case \"green\":   return new Color(0, 128, 0);
            case \"blue\":    return new Color(0, 0, 255);
            case \"yellow\":  return new Color(255, 255, 0);
            case \"orange\":  return new Color(255, 165, 0);
            case \"purple\":  return new Color(128, 0, 128);
            case \"cyan\":    return new Color(0, 255, 255);
            case \"magenta\": return new Color(255, 0, 255);
            case \"brown\":   return new Color(139, 69, 19);
            case \"pink\":    return new Color(255, 192, 203);
            case \"gray\": case \"grey\": return new Color(128, 128, 128);
            default:
                try { return Color.decode(s); }
                catch (Exception e) { return Color.BLACK; }
        }
    }

    private int speedDelay() {
        if (spd <= 0) return 0;
        if (spd >= 10) return 5;
        return (int)(200.0 / spd);
    }

    private double[] canvasCoords(double tx, double ty) {
        return new double[] {
            window.vw() / 2.0 + tx,
            window.vh() / 2.0 - ty
        };
    }

    void drawCursor(Graphics2D g2d, double cx, double cy) {
        AffineTransform saved = g2d.getTransform();
        g2d.translate(cx, cy);
        g2d.rotate(Math.toRadians(-heading));
        g2d.setColor(parseColor(colorName));
        if (\"circle\".equals(shapeName)) {
            int r = 6;
            g2d.fill(new Ellipse2D.Double(-r, -r, 2*r, 2*r));
        } else {
            Path2D.Double path = new Path2D.Double();
            path.moveTo(12, 0);
            path.lineTo(-6, -7);
            path.lineTo(-6, 7);
            path.closePath();
            g2d.fill(path);
        }
        g2d.setTransform(saved);
    }

    public Object forward(double dist) {
        double rad = Math.toRadians(heading);
        double dx = dist * Math.cos(rad);
        double dy = dist * Math.sin(rad);
        double nx = x + dx, ny = y + dy;
        if (penDown) {
            BufferedImage canvas = window.getCanvas();
            Graphics2D g2d = canvas.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(parseColor(colorName));
            g2d.setStroke(new BasicStroke(penSz, BasicStroke.CAP_ROUND,
                                         BasicStroke.JOIN_ROUND));
            double[] s = canvasCoords(x, y);
            double[] e = canvasCoords(nx, ny);
            g2d.drawLine((int)s[0], (int)s[1], (int)e[0], (int)e[1]);
            g2d.dispose();
        }
        x = nx; y = ny;
        if (filling) fillPoints.add(new double[]{nx, ny});
        window.repaintCanvas();
        int delay = speedDelay();
        if (delay > 0) { try { Thread.sleep(delay); } catch (Exception ex) {} }
        return null;
    }

    public Object forward(int dist) { return forward((double) dist); }

    public Object backward(double dist) { return forward(-dist); }
    public Object backward(int dist) { return backward((double) dist); }

    public Object right(double angle) {
        heading -= angle;
        window.repaintCanvas();
        return null;
    }
    public Object right(int angle) { return right((double) angle); }

    public Object left(double angle) {
        heading += angle;
        window.repaintCanvas();
        return null;
    }
    public Object left(int angle) { return left((double) angle); }

    public Object penup() { penDown = false; return null; }
    public Object pendown() { penDown = true; return null; }

    public Object color(String c) {
        colorName = c;
        fillColor = c;
        return null;
    }

    public Object pensize(int s) { penSz = s; return null; }
    public Object pensize(double s) { penSz = (int) s; return null; }

    public Object speed(int s) { spd = s; return null; }
    public Object speed(double s) { spd = (int) s; return null; }

    public Object shape(String s) { shapeName = s.toLowerCase(); return null; }

    public Object goto_(double gx, double gy) {
        if (penDown) {
            BufferedImage canvas = window.getCanvas();
            Graphics2D g2d = canvas.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(parseColor(colorName));
            g2d.setStroke(new BasicStroke(penSz, BasicStroke.CAP_ROUND,
                                         BasicStroke.JOIN_ROUND));
            double[] s = canvasCoords(x, y);
            double[] e = canvasCoords(gx, gy);
            g2d.drawLine((int)s[0], (int)s[1], (int)e[0], (int)e[1]);
            g2d.dispose();
        }
        x = gx; y = gy;
        if (filling) fillPoints.add(new double[]{gx, gy});
        window.repaintCanvas();
        int delay = speedDelay();
        if (delay > 0) { try { Thread.sleep(delay); } catch (Exception ex) {} }
        return null;
    }

    public Object circle(double radius) {
        int segments = 36;
        double angleStep = 360.0 / segments;
        BufferedImage canvas = window.getCanvas();
        Graphics2D g2d = canvas.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                             RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setColor(parseColor(colorName));
        g2d.setStroke(new BasicStroke(penSz, BasicStroke.CAP_ROUND,
                                     BasicStroke.JOIN_ROUND));
        double cx = x, cy = y, h = heading;
        for (int i = 0; i < segments; i++) {
            double newH = h + angleStep;
            double stepLen = 2.0 * radius * Math.sin(Math.toRadians(angleStep / 2.0));
            double moveRad = Math.toRadians(h + angleStep / 2.0);
            double nx = cx + stepLen * Math.cos(moveRad);
            double ny = cy + stepLen * Math.sin(moveRad);
            if (penDown) {
                double[] s = canvasCoords(cx, cy);
                double[] e = canvasCoords(nx, ny);
                g2d.drawLine((int)s[0], (int)s[1], (int)e[0], (int)e[1]);
            }
            if (filling) fillPoints.add(new double[]{nx, ny});
            cx = nx; cy = ny; h = newH;
        }
        g2d.dispose();
        window.repaintCanvas();
        int delay = speedDelay();
        if (delay > 0) { try { Thread.sleep(delay); } catch (Exception ex) {} }
        return null;
    }
    public Object circle(int radius) { return circle((double) radius); }

    public Object begin_fill() {
        filling = true;
        fillPoints = new java.util.ArrayList<>();
        fillPoints.add(new double[]{x, y});
        fillColor = colorName;
        return null;
    }

    public Object end_fill() {
        if (filling && fillPoints.size() >= 3) {
            BufferedImage canvas = window.getCanvas();
            Graphics2D g2d = canvas.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                                 RenderingHints.VALUE_ANTIALIAS_ON);
            Path2D.Double path = new Path2D.Double();
            double[] first = fillPoints.get(0);
            double[] fc = canvasCoords(first[0], first[1]);
            path.moveTo(fc[0], fc[1]);
            for (int i = 1; i < fillPoints.size(); i++) {
                double[] pt = fillPoints.get(i);
                double[] pc = canvasCoords(pt[0], pt[1]);
                path.lineTo(pc[0], pc[1]);
            }
            path.closePath();
            g2d.setColor(parseColor(fillColor));
            g2d.fill(path);
            g2d.dispose();
            window.repaintCanvas();
        }
        filling = false;
        fillPoints = new java.util.ArrayList<>();
        return null;
    }

    public Object hide() {
        visible = false;
        window.repaintCanvas();
        return null;
    }

    public Object show() {
        visible = true;
        window.repaintCanvas();
        return null;
    }
}")

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

(defn generate-runtime-helpers
  "Generate runtime helpers for built-in global functions."
  []
  (str "public class NexRuntime {\n"
       "  private static final java.util.concurrent.ExecutorService TASK_EXECUTOR = java.util.concurrent.Executors.newCachedThreadPool();\n\n"
       "  private static boolean hasParentType(Object value, String targetType, java.util.IdentityHashMap<Object, Boolean> seen) {\n"
       "    if (value == null) return false;\n"
       "    if (seen.containsKey(value)) return false;\n"
       "    seen.put(value, Boolean.TRUE);\n"
       "    for (java.lang.reflect.Field field : value.getClass().getDeclaredFields()) {\n"
       "      String fieldName = field.getName();\n"
       "      if (fieldName.startsWith(\"_parent_\")) {\n"
       "        String parentType = fieldName.substring(\"_parent_\".length());\n"
       "        if (parentType.equals(targetType)) return true;\n"
       "        try {\n"
       "          field.setAccessible(true);\n"
       "          Object parentValue = field.get(value);\n"
       "          if (hasParentType(parentValue, targetType, seen)) return true;\n"
       "        } catch (Exception ignored) {\n"
       "        }\n"
       "      }\n"
       "    }\n"
       "    return false;\n"
       "  }\n\n"
       "  public static String typeOf(Object value) {\n"
       "    if (value == null) return \"Nil\";\n"
       "    if (value instanceof String) return \"String\";\n"
       "    if (value instanceof Character) return \"Char\";\n"
       "    if (value instanceof Boolean) return \"Boolean\";\n"
       "    if (value instanceof java.math.BigDecimal) return \"Decimal\";\n"
       "    if (value instanceof Byte || value instanceof Short || value instanceof Integer) return \"Integer\";\n"
       "    if (value instanceof Long) return \"Integer64\";\n"
       "    if (value instanceof Float || value instanceof Double) return \"Real\";\n"
       "    if (value instanceof java.util.ArrayList) return \"Array\";\n"
       "    if (value instanceof java.util.HashMap) return \"Map\";\n"
       "    if (value instanceof java.util.LinkedHashSet) return \"Set\";\n"
       "    String simple = value.getClass().getSimpleName();\n"
       "    return (simple == null || simple.isEmpty()) ? value.getClass().getName() : simple;\n"
       "  }\n\n"
       "  public static boolean typeIs(String targetType, Object value) {\n"
       "    if (targetType == null) return false;\n"
       "    String runtimeType = typeOf(value);\n"
       "    if (\"Any\".equals(targetType)) return true;\n"
       "    if (runtimeType.equals(targetType)) return true;\n"
       "    if (\"Integer\".equals(runtimeType) && (\"Integer64\".equals(targetType) || \"Real\".equals(targetType) || \"Decimal\".equals(targetType))) return true;\n"
       "    if (\"Integer64\".equals(runtimeType) && (\"Real\".equals(targetType) || \"Decimal\".equals(targetType))) return true;\n"
       "    if (\"Real\".equals(runtimeType) && \"Decimal\".equals(targetType)) return true;\n"
       "    if ((\"ArrayCursor\".equals(runtimeType) || \"StringCursor\".equals(runtimeType) || \"MapCursor\".equals(runtimeType) || \"SetCursor\".equals(runtimeType)) && \"Cursor\".equals(targetType)) return true;\n"
       "    if (value == null) return false;\n"
       "    if (hasParentType(value, targetType, new java.util.IdentityHashMap<>())) return true;\n"
       "    return false;\n"
       "  }\n\n"
       "  public static String toStringValue(Object value) {\n"
       "    if (value == null) return \"nil\";\n"
       "    if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character) return String.valueOf(value);\n"
       "    if (value instanceof java.math.BigDecimal) return ((java.math.BigDecimal) value).toPlainString();\n"
       "    if (value instanceof java.util.List) {\n"
       "      java.util.List<?> list = (java.util.List<?>) value;\n"
       "      java.util.ArrayList<String> parts = new java.util.ArrayList<>();\n"
       "      for (Object item : list) parts.add(toStringValue(item));\n"
       "      return \"[\" + String.join(\", \", parts) + \"]\";\n"
       "    }\n"
       "    if (value instanceof java.util.Map) {\n"
       "      java.util.Map<?, ?> map = (java.util.Map<?, ?>) value;\n"
       "      java.util.ArrayList<String> parts = new java.util.ArrayList<>();\n"
       "      for (java.util.Map.Entry<?, ?> e : map.entrySet()) parts.add(toStringValue(e.getKey()) + \": \" + toStringValue(e.getValue()));\n"
       "      return \"{\" + String.join(\", \", parts) + \"}\";\n"
       "    }\n"
       "    if (value instanceof java.util.Set) {\n"
       "      java.util.Set<?> set = (java.util.Set<?>) value;\n"
       "      java.util.ArrayList<String> parts = new java.util.ArrayList<>();\n"
       "      for (Object item : set) parts.add(toStringValue(item));\n"
       "      return \"#{\" + String.join(\", \", parts) + \"}\";\n"
       "    }\n"
       "    return \"#<\" + typeOf(value) + \" object>\";\n"
       "  }\n\n"
       "  public static String toConcatString(Object value) {\n"
       "    if (value instanceof String) return (String) value;\n"
       "    if (value != null) {\n"
       "      try {\n"
       "        java.lang.reflect.Method m = value.getClass().getMethod(\"to_string\");\n"
       "        if (m.getParameterCount() == 0) {\n"
       "          Object out = m.invoke(value);\n"
       "          return out instanceof String ? (String) out : toStringValue(out);\n"
       "        }\n"
       "      } catch (NoSuchMethodException e) {\n"
       "      } catch (Exception e) {\n"
       "        throw new RuntimeException(\"to_string failed for type: \" + typeOf(value), e);\n"
       "      }\n"
       "    }\n"
       "    return toStringValue(value);\n"
       "  }\n\n"
       "  public static String concatValues(Object left, Object right) {\n"
       "    return toConcatString(left) + toConcatString(right);\n"
       "  }\n\n"
       "  public static boolean anyEquals(Object a, Object b) {\n"
       "    return a == b;\n"
       "  }\n\n"
       "  public static Object cloneValue(Object value) {\n"
       "    if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof java.math.BigDecimal) return value;\n"
       "    if (value instanceof java.util.ArrayList) return new java.util.ArrayList<>((java.util.ArrayList<?>) value);\n"
       "    if (value instanceof java.util.HashMap) return new java.util.HashMap<>((java.util.HashMap<?, ?>) value);\n"
       "    if (value instanceof java.util.LinkedHashSet) return new java.util.LinkedHashSet<>((java.util.LinkedHashSet<?>) value);\n"
       "    try {\n"
       "      Class<?> cls = value.getClass();\n"
       "      Object copy = cls.getDeclaredConstructor().newInstance();\n"
       "      for (java.lang.reflect.Field field : cls.getDeclaredFields()) {\n"
       "        if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {\n"
       "          field.setAccessible(true);\n"
       "          field.set(copy, field.get(value));\n"
       "        }\n"
       "      }\n"
       "      return copy;\n"
       "    } catch (Exception e) {\n"
       "      throw new RuntimeException(\"Clone failed for type: \" + typeOf(value), e);\n"
       "    }\n"
       "  }\n\n"
       "  public static boolean deepEquals(Object a, Object b) {\n"
       "    if (a == b) return true;\n"
       "    if (a == null || b == null) return false;\n"
       "    if (a instanceof java.util.List && b instanceof java.util.List) {\n"
       "      java.util.List<?> la = (java.util.List<?>) a;\n"
       "      java.util.List<?> lb = (java.util.List<?>) b;\n"
       "      if (la.size() != lb.size()) return false;\n"
       "      for (int i = 0; i < la.size(); i++) if (!deepEquals(la.get(i), lb.get(i))) return false;\n"
       "      return true;\n"
       "    }\n"
       "    if (a instanceof java.util.Map && b instanceof java.util.Map) {\n"
       "      java.util.Map<?, ?> ma = (java.util.Map<?, ?>) a;\n"
       "      java.util.Map<?, ?> mb = (java.util.Map<?, ?>) b;\n"
       "      if (ma.size() != mb.size()) return false;\n"
       "      outer: for (java.util.Map.Entry<?, ?> ea : ma.entrySet()) {\n"
       "        for (java.util.Map.Entry<?, ?> eb : mb.entrySet()) {\n"
       "          if (deepEquals(ea.getKey(), eb.getKey()) && deepEquals(ea.getValue(), eb.getValue())) continue outer;\n"
       "        }\n"
       "        return false;\n"
       "      }\n"
       "      return true;\n"
       "    }\n"
       "    if (a instanceof java.util.Set && b instanceof java.util.Set) {\n"
       "      java.util.Set<?> sa = (java.util.Set<?>) a;\n"
       "      java.util.Set<?> sb = (java.util.Set<?>) b;\n"
       "      if (sa.size() != sb.size()) return false;\n"
       "      outer: for (Object va : sa) {\n"
       "        for (Object vb : sb) {\n"
       "          if (deepEquals(va, vb)) continue outer;\n"
       "        }\n"
       "        return false;\n"
       "      }\n"
       "      return true;\n"
       "    }\n"
       "    return java.util.Objects.equals(a, b);\n"
       "  }\n\n"
       "  public static Object deepClone(Object value) {\n"
       "    if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof java.math.BigDecimal) return value;\n"
       "    if (value instanceof java.util.List) {\n"
       "      java.util.ArrayList<Object> out = new java.util.ArrayList<>();\n"
       "      for (Object item : (java.util.List<?>) value) out.add(deepClone(item));\n"
       "      return out;\n"
       "    }\n"
       "    if (value instanceof java.util.Map) {\n"
       "      java.util.HashMap<Object, Object> out = new java.util.HashMap<>();\n"
       "      for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) value).entrySet()) out.put(deepClone(e.getKey()), deepClone(e.getValue()));\n"
       "      return out;\n"
       "    }\n"
       "    if (value instanceof java.util.Set) {\n"
       "      java.util.LinkedHashSet<Object> out = new java.util.LinkedHashSet<>();\n"
       "      for (Object item : (java.util.Set<?>) value) out.add(deepClone(item));\n"
       "      return out;\n"
       "    }\n"
       "    return cloneValue(value);\n"
       "  }\n\n"
       "  public static void sleep(int ms) {\n"
       "    try {\n"
       "      Thread.sleep(ms);\n"
       "    } catch (InterruptedException e) {\n"
       "      Thread.currentThread().interrupt();\n"
       "      throw new RuntimeException(e);\n"
       "    }\n"
       "  }\n\n"
       "  private static int regexFlags(String flags) {\n"
       "    int out = 0;\n"
       "    if (flags != null) {\n"
       "      if (flags.contains(\"i\")) out |= java.util.regex.Pattern.CASE_INSENSITIVE;\n"
       "      if (flags.contains(\"m\")) out |= java.util.regex.Pattern.MULTILINE;\n"
       "    }\n"
       "    return out;\n"
       "  }\n"
       "  private static java.util.regex.Pattern regexPattern(String pattern, String flags) {\n"
       "    return java.util.regex.Pattern.compile(pattern, regexFlags(flags));\n"
       "  }\n"
       "  public static void regexValidate(String pattern, String flags) { regexPattern(pattern, flags); }\n"
       "  public static boolean regexMatches(String pattern, String flags, String text) {\n"
       "    return regexPattern(pattern, flags).matcher(text).matches();\n"
       "  }\n"
       "  public static String regexFind(String pattern, String flags, String text) {\n"
       "    java.util.regex.Matcher m = regexPattern(pattern, flags).matcher(text);\n"
       "    return m.find() ? m.group() : null;\n"
       "  }\n"
       "  public static java.util.ArrayList<String> regexFindAll(String pattern, String flags, String text) {\n"
       "    java.util.regex.Matcher m = regexPattern(pattern, flags).matcher(text);\n"
       "    java.util.ArrayList<String> out = new java.util.ArrayList<>();\n"
       "    while (m.find()) out.add(m.group());\n"
       "    return out;\n"
       "  }\n"
       "  public static String regexReplace(String pattern, String flags, String text, String replacement) {\n"
       "    return regexPattern(pattern, flags).matcher(text).replaceAll(replacement);\n"
       "  }\n"
       "  public static java.util.ArrayList<String> regexSplit(String pattern, String flags, String text) {\n"
       "    return new java.util.ArrayList<>(java.util.Arrays.asList(regexPattern(pattern, flags).split(text)));\n"
       "  }\n"
       "  public static long datetimeNow() { return java.time.Instant.now().toEpochMilli(); }\n"
       "  public static long datetimeFromEpochMillis(long ms) { return ms; }\n"
       "  public static long datetimeParseIso(String text) {\n"
       "    try {\n"
       "      return java.time.Instant.parse(text).toEpochMilli();\n"
       "    } catch (Exception ex) {\n"
       "      java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(text);\n"
       "      return ldt.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();\n"
       "    }\n"
       "  }\n"
       "  public static long datetimeMake(int year, int month, int day, int hour, int minute, int second) {\n"
       "    return java.time.LocalDateTime.of(year, month, day, hour, minute, second)\n"
       "      .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();\n"
       "  }\n"
       "  private static java.time.ZonedDateTime datetimeUtc(long epochMs) {\n"
       "    return java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMs), java.time.ZoneOffset.UTC);\n"
       "  }\n"
       "  public static int datetimeYear(long epochMs) { return datetimeUtc(epochMs).getYear(); }\n"
       "  public static int datetimeMonth(long epochMs) { return datetimeUtc(epochMs).getMonthValue(); }\n"
       "  public static int datetimeDay(long epochMs) { return datetimeUtc(epochMs).getDayOfMonth(); }\n"
       "  public static int datetimeWeekday(long epochMs) { return datetimeUtc(epochMs).getDayOfWeek().getValue(); }\n"
       "  public static int datetimeDayOfYear(long epochMs) { return datetimeUtc(epochMs).getDayOfYear(); }\n"
       "  public static int datetimeHour(long epochMs) { return datetimeUtc(epochMs).getHour(); }\n"
       "  public static int datetimeMinute(long epochMs) { return datetimeUtc(epochMs).getMinute(); }\n"
       "  public static int datetimeSecond(long epochMs) { return datetimeUtc(epochMs).getSecond(); }\n"
       "  public static long datetimeEpochMillis(long epochMs) { return epochMs; }\n"
       "  public static long datetimeAddMillis(long epochMs, long deltaMs) { return epochMs + deltaMs; }\n"
       "  public static long datetimeDiffMillis(long leftMs, long rightMs) { return leftMs - rightMs; }\n"
       "  public static long datetimeTruncateToDay(long epochMs) {\n"
       "    return datetimeUtc(epochMs).withHour(0).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli();\n"
       "  }\n"
       "  public static long datetimeTruncateToHour(long epochMs) {\n"
       "    return datetimeUtc(epochMs).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli();\n"
       "  }\n"
       "  public static String datetimeFormatIso(long epochMs) { return java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.ofEpochMilli(epochMs)); }\n"
       "  public static boolean pathExists(String path) { return new java.io.File(path).exists(); }\n"
       "  public static boolean pathIsFile(String path) { return new java.io.File(path).isFile(); }\n"
       "  public static boolean pathIsDirectory(String path) { return new java.io.File(path).isDirectory(); }\n"
       "  public static String pathName(String path) { return new java.io.File(path).getName(); }\n"
       "  public static String pathExtension(String path) {\n"
       "    String name = pathName(path);\n"
       "    int dot = name.lastIndexOf('.');\n"
       "    return (dot <= 0 || dot == name.length() - 1) ? \"\" : name.substring(dot + 1);\n"
       "  }\n"
       "  public static String pathNameWithoutExtension(String path) {\n"
       "    String name = pathName(path);\n"
       "    int dot = name.lastIndexOf('.');\n"
       "    return (dot <= 0) ? name : name.substring(0, dot);\n"
       "  }\n"
       "  public static String pathAbsolute(String path) { return new java.io.File(path).getAbsolutePath(); }\n"
       "  public static String pathNormalize(String path) { return new java.io.File(path).toPath().normalize().toString(); }\n"
       "  public static long pathSize(String path) { return new java.io.File(path).length(); }\n"
       "  public static long pathModifiedTime(String path) { return new java.io.File(path).lastModified(); }\n"
       "  public static String pathParent(String path) { return new java.io.File(path).getParent(); }\n"
       "  public static String pathChild(String path, String childName) { return new java.io.File(path, childName).getPath(); }\n"
       "  public static void pathCreateFile(String path) {\n"
       "    try {\n"
       "      new java.io.File(path).createNewFile();\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static void pathCreateDirectory(String path) {\n"
       "    if (!new java.io.File(path).mkdir() && !new java.io.File(path).isDirectory()) throw new RuntimeException(\"Could not create directory: \" + path);\n"
       "  }\n"
       "  public static void pathCreateDirectories(String path) {\n"
       "    if (!new java.io.File(path).mkdirs() && !new java.io.File(path).isDirectory()) throw new RuntimeException(\"Could not create directories: \" + path);\n"
       "  }\n"
       "  public static void pathDelete(String path) {\n"
       "    java.io.File f = new java.io.File(path);\n"
       "    if (!f.exists()) return;\n"
       "    if (f.isDirectory()) throw new RuntimeException(\"path_delete does not remove directories\");\n"
       "    if (!f.delete()) throw new RuntimeException(\"Could not delete path: \" + path);\n"
       "  }\n"
       "  private static void deleteTree(java.io.File f) {\n"
       "    if (!f.exists()) return;\n"
       "    java.io.File[] children = f.listFiles();\n"
       "    if (children != null) for (java.io.File child : children) deleteTree(child);\n"
       "    if (!f.delete()) throw new RuntimeException(\"Could not delete path: \" + f.getPath());\n"
       "  }\n"
       "  public static void pathDeleteTree(String path) {\n"
       "    deleteTree(new java.io.File(path));\n"
       "  }\n"
       "  private static void copyTree(java.io.File source, java.io.File target) {\n"
       "    try {\n"
       "      if (source.isDirectory()) {\n"
       "        target.mkdirs();\n"
       "        java.io.File[] children = source.listFiles();\n"
       "        if (children != null) for (java.io.File child : children) copyTree(child, new java.io.File(target, child.getName()));\n"
       "      } else {\n"
       "        java.io.File parent = target.getParentFile();\n"
       "        if (parent != null) parent.mkdirs();\n"
       "        java.nio.file.Files.copy(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);\n"
       "      }\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static void pathCopy(String sourcePath, String targetPath) {\n"
       "    copyTree(new java.io.File(sourcePath), new java.io.File(targetPath));\n"
       "  }\n"
       "  public static void pathMove(String sourcePath, String targetPath) {\n"
       "    java.io.File source = new java.io.File(sourcePath);\n"
       "    java.io.File target = new java.io.File(targetPath);\n"
       "    java.io.File parent = target.getParentFile();\n"
       "    if (parent != null) parent.mkdirs();\n"
       "    if (!source.renameTo(target)) {\n"
       "      copyTree(source, target);\n"
       "      deleteTree(source);\n"
       "    }\n"
       "  }\n"
       "  public static String pathReadText(String path) {\n"
       "    try {\n"
       "      return java.nio.file.Files.readString(java.nio.file.Paths.get(path), java.nio.charset.StandardCharsets.UTF_8);\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static void pathWriteText(String path, String text) {\n"
       "    try {\n"
       "      java.nio.file.Files.writeString(java.nio.file.Paths.get(path), String.valueOf(text), java.nio.charset.StandardCharsets.UTF_8);\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static void pathAppendText(String path, String text) {\n"
       "    try {\n"
       "      java.nio.file.Files.writeString(java.nio.file.Paths.get(path), String.valueOf(text), java.nio.charset.StandardCharsets.UTF_8,\n"
       "        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static java.util.ArrayList<String> pathList(String path) {\n"
       "    java.io.File[] files = new java.io.File(path).listFiles();\n"
       "    java.util.ArrayList<String> out = new java.util.ArrayList<>();\n"
       "    if (files != null) for (java.io.File f : files) out.add(f.getPath());\n"
       "    return out;\n"
       "  }\n\n"
       "  public static class TextFileHandle {\n"
       "    public final java.io.BufferedReader reader;\n"
       "    public final java.io.BufferedWriter writer;\n"
       "    public TextFileHandle(java.io.BufferedReader reader, java.io.BufferedWriter writer) {\n"
       "      this.reader = reader;\n"
       "      this.writer = writer;\n"
       "    }\n"
       "  }\n"
       "  public static Object textFileOpenRead(String path) {\n"
       "    try {\n"
       "      return new TextFileHandle(new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(path), java.nio.charset.StandardCharsets.UTF_8)), null);\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static Object textFileOpenWrite(String path) {\n"
       "    try {\n"
       "      return new TextFileHandle(null, new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(path, false), java.nio.charset.StandardCharsets.UTF_8)));\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static Object textFileOpenAppend(String path) {\n"
       "    try {\n"
       "      return new TextFileHandle(null, new java.io.BufferedWriter(new java.io.OutputStreamWriter(new java.io.FileOutputStream(path, true), java.nio.charset.StandardCharsets.UTF_8)));\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static String textFileReadLine(Object handleObj) {\n"
       "    try {\n"
       "      return ((TextFileHandle) handleObj).reader.readLine();\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static void textFileWrite(Object handleObj, String text) {\n"
       "    try {\n"
       "      TextFileHandle handle = (TextFileHandle) handleObj;\n"
       "      handle.writer.write(String.valueOf(text));\n"
       "      handle.writer.flush();\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static void textFileClose(Object handleObj) {\n"
       "    try {\n"
       "      TextFileHandle handle = (TextFileHandle) handleObj;\n"
       "      if (handle.reader != null) handle.reader.close();\n"
       "      if (handle.writer != null) handle.writer.close();\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n\n"
       "  public static class BinaryFileHandle {\n"
       "    public final byte[] data;\n"
       "    public int index;\n"
       "    public final java.io.FileOutputStream out;\n"
       "    public BinaryFileHandle(byte[] data, int index, java.io.FileOutputStream out) {\n"
       "      this.data = data;\n"
       "      this.index = index;\n"
       "      this.out = out;\n"
       "    }\n"
       "  }\n"
       "  private static java.util.ArrayList<Integer> bytesToIntArray(byte[] bytes) {\n"
       "    java.util.ArrayList<Integer> out = new java.util.ArrayList<>();\n"
       "    for (byte b : bytes) out.add(b & 0xFF);\n"
       "    return out;\n"
       "  }\n"
       "  private static byte[] intArrayToBytes(java.util.ArrayList<Integer> values) {\n"
       "    byte[] out = new byte[values.size()];\n"
       "    for (int i = 0; i < values.size(); i++) {\n"
       "      int v = values.get(i);\n"
       "      if (v < 0 || v > 255) throw new RuntimeException(\"Binary byte values must be in range 0..255\");\n"
       "      out[i] = (byte) v;\n"
       "    }\n"
       "    return out;\n"
       "  }\n"
       "  public static Object binaryFileOpenRead(String path) {\n"
       "    try {\n"
       "      return new BinaryFileHandle(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)), 0, null);\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static Object binaryFileOpenWrite(String path) {\n"
       "    try {\n"
       "      return new BinaryFileHandle(null, 0, new java.io.FileOutputStream(path, false));\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static Object binaryFileOpenAppend(String path) {\n"
       "    try {\n"
       "      return new BinaryFileHandle(null, 0, new java.io.FileOutputStream(path, true));\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static java.util.ArrayList<Integer> binaryFileReadAll(Object handleObj) {\n"
       "    return bytesToIntArray(((BinaryFileHandle) handleObj).data);\n"
       "  }\n"
       "  public static java.util.ArrayList<Integer> binaryFileRead(Object handleObj, int count) {\n"
       "    BinaryFileHandle handle = (BinaryFileHandle) handleObj;\n"
       "    int end = Math.min(handle.index + count, handle.data.length);\n"
       "    byte[] out = java.util.Arrays.copyOfRange(handle.data, handle.index, end);\n"
       "    handle.index = end;\n"
       "    return bytesToIntArray(out);\n"
       "  }\n"
       "  public static void binaryFileWrite(Object handleObj, java.util.ArrayList<Integer> values) {\n"
       "    try {\n"
       "      BinaryFileHandle handle = (BinaryFileHandle) handleObj;\n"
       "      handle.out.write(intArrayToBytes(values));\n"
       "      handle.out.flush();\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static void binaryFileClose(Object handleObj) {\n"
       "    try {\n"
       "      BinaryFileHandle handle = (BinaryFileHandle) handleObj;\n"
       "      if (handle.out != null) handle.out.close();\n"
       "    } catch (java.io.IOException ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n\n"
       "  @SuppressWarnings(\"unchecked\")\n"
       "  private static <T> T httpRequest(String method, String url, String bodyText, Integer timeoutMs) {\n"
       "    try {\n"
       "      java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url));\n"
       "      if (timeoutMs != null) builder.timeout(java.time.Duration.ofMillis(timeoutMs.longValue()));\n"
       "      java.net.http.HttpRequest.BodyPublisher publisher = \"POST\".equals(method)\n"
       "        ? java.net.http.HttpRequest.BodyPublishers.ofString(bodyText == null ? \"\" : bodyText)\n"
       "        : java.net.http.HttpRequest.BodyPublishers.noBody();\n"
       "      if (\"POST\".equals(method)) builder.POST(publisher); else builder.GET();\n"
       "      java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newBuilder().build()\n"
       "        .send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());\n"
       "      java.util.HashMap<String, String> headers = new java.util.HashMap<>();\n"
       "      response.headers().map().forEach((k, v) -> headers.put(k, v.isEmpty() ? \"\" : String.valueOf(v.get(0))));\n"
       "      Class<?> responseClass = Class.forName(\"Http_Response\");\n"
       "      java.lang.reflect.Method make = responseClass.getMethod(\"make\", int.class, String.class, java.util.HashMap.class);\n"
       "      return (T) make.invoke(null, response.statusCode(), response.body(), headers);\n"
       "    } catch (Exception ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n\n"
       "  public static <T> T httpGet(String url) { return httpRequest(\"GET\", url, null, null); }\n"
       "  public static <T> T httpGet(String url, int timeoutMs) { return httpRequest(\"GET\", url, null, timeoutMs); }\n"
       "  public static <T> T httpPost(String url, String bodyText) { return httpRequest(\"POST\", url, bodyText, null); }\n"
       "  public static <T> T httpPost(String url, String bodyText, int timeoutMs) { return httpRequest(\"POST\", url, bodyText, timeoutMs); }\n\n"
       "  public static class HttpServerRoute {\n"
       "    public final String pathPattern;\n"
       "    public final Function handler;\n"
       "    public HttpServerRoute(String pathPattern, Function handler) {\n"
       "      this.pathPattern = pathPattern;\n"
       "      this.handler = handler;\n"
       "    }\n"
       "  }\n\n"
       "  public static class HttpServerMatch {\n"
       "    public final Function handler;\n"
       "    public final java.util.Map<String, String> params;\n"
       "    public HttpServerMatch(Function handler, java.util.Map<String, String> params) {\n"
       "      this.handler = handler;\n"
       "      this.params = params;\n"
       "    }\n"
       "  }\n\n"
       "  public static class HttpServerHandle {\n"
       "    public int port;\n"
       "    public com.sun.net.httpserver.HttpServer server;\n"
       "    public java.util.Map<String, java.util.List<HttpServerRoute>> routes = new java.util.HashMap<>();\n"
       "    public HttpServerHandle(int port) {\n"
       "      this.port = port;\n"
       "      this.routes.put(\"GET\", new java.util.ArrayList<>());\n"
       "      this.routes.put(\"POST\", new java.util.ArrayList<>());\n"
       "      this.routes.put(\"PUT\", new java.util.ArrayList<>());\n"
       "      this.routes.put(\"DELETE\", new java.util.ArrayList<>());\n"
       "    }\n"
       "  }\n\n"
       "  private static java.util.List<String> httpPathSegments(String path) {\n"
       "    java.util.ArrayList<String> out = new java.util.ArrayList<>();\n"
       "    if (path == null || path.isEmpty() || \"/\".equals(path)) return out;\n"
       "    for (String part : path.split(\"/\")) if (!part.isEmpty()) out.add(part);\n"
       "    return out;\n"
       "  }\n"
       "  private static String httpUrlDecode(String s) {\n"
       "    try {\n"
       "      return java.net.URLDecoder.decode(String.valueOf(s == null ? \"\" : s), java.nio.charset.StandardCharsets.UTF_8);\n"
       "    } catch (Exception ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  private static java.util.Map<String, String> httpParseQuery(String query) {\n"
       "    java.util.HashMap<String, String> out = new java.util.HashMap<>();\n"
       "    if (query == null || query.isEmpty()) return out;\n"
       "    for (String part : query.split(\"&\")) {\n"
       "      if (part.isEmpty()) continue;\n"
       "      String[] pieces = part.split(\"=\", 2);\n"
       "      String key = httpUrlDecode(pieces[0]);\n"
       "      String value = httpUrlDecode(pieces.length > 1 ? pieces[1] : \"\");\n"
       "      out.put(key, value);\n"
       "    }\n"
       "    return out;\n"
       "  }\n"
       "  private static java.util.Map<String, String> httpMatchRoute(String pattern, String path) {\n"
       "    java.util.List<String> patternSegments = httpPathSegments(pattern);\n"
       "    java.util.List<String> pathSegments = httpPathSegments(path);\n"
       "    java.util.HashMap<String, String> params = new java.util.HashMap<>();\n"
       "    int i = 0;\n"
       "    int j = 0;\n"
       "    while (i < patternSegments.size() && j < pathSegments.size()) {\n"
       "      String p = patternSegments.get(i);\n"
       "      String x = pathSegments.get(j);\n"
       "      if (\"*\".equals(p)) {\n"
       "        params.put(\"*\", String.join(\"/\", pathSegments.subList(j, pathSegments.size())));\n"
       "        return params;\n"
       "      }\n"
       "      if (p.startsWith(\":\")) {\n"
       "        params.put(p.substring(1), httpUrlDecode(x));\n"
       "        i++; j++;\n"
       "        continue;\n"
       "      }\n"
       "      if (!p.equals(x)) return null;\n"
       "      i++; j++;\n"
       "    }\n"
       "    if (i == patternSegments.size() && j == pathSegments.size()) return params;\n"
       "    if (i < patternSegments.size() && \"*\".equals(patternSegments.get(i))) {\n"
       "      params.put(\"*\", String.join(\"/\", pathSegments.subList(j, pathSegments.size())));\n"
       "      return params;\n"
       "    }\n"
       "    return null;\n"
       "  }\n"
       "  private static HttpServerMatch httpFindRoute(HttpServerHandle handle, String method, String path) {\n"
       "    for (HttpServerRoute route : handle.routes.getOrDefault(method, java.util.Collections.emptyList())) {\n"
       "      java.util.Map<String, String> params = httpMatchRoute(route.pathPattern, path);\n"
       "      if (params != null) return new HttpServerMatch(route.handler, params);\n"
       "    }\n"
       "    return null;\n"
       "  }\n\n"
       "  public static Object httpServerCreate(int port) { return new HttpServerHandle(port); }\n"
       "  public static void httpServerGet(Object handleObj, String path, Function handler) {\n"
       "    HttpServerHandle handle = (HttpServerHandle) handleObj;\n"
       "    handle.routes.get(\"GET\").add(new HttpServerRoute(path, handler));\n"
       "  }\n"
       "  public static void httpServerPost(Object handleObj, String path, Function handler) {\n"
       "    HttpServerHandle handle = (HttpServerHandle) handleObj;\n"
       "    handle.routes.get(\"POST\").add(new HttpServerRoute(path, handler));\n"
       "  }\n"
       "  public static void httpServerPut(Object handleObj, String path, Function handler) {\n"
       "    HttpServerHandle handle = (HttpServerHandle) handleObj;\n"
       "    handle.routes.get(\"PUT\").add(new HttpServerRoute(path, handler));\n"
       "  }\n"
       "  public static void httpServerDelete(Object handleObj, String path, Function handler) {\n"
       "    HttpServerHandle handle = (HttpServerHandle) handleObj;\n"
       "    handle.routes.get(\"DELETE\").add(new HttpServerRoute(path, handler));\n"
       "  }\n"
       "  public static int httpServerStart(Object handleObj) {\n"
       "    HttpServerHandle handle = (HttpServerHandle) handleObj;\n"
       "    try {\n"
       "      com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(\"127.0.0.1\", handle.port), 0);\n"
       "      server.createContext(\"/\", exchange -> {\n"
       "        String method = exchange.getRequestMethod();\n"
       "        java.net.URI uri = exchange.getRequestURI();\n"
       "        String path = uri.getPath();\n"
       "        HttpServerMatch match = httpFindRoute(handle, method, path);\n"
       "        try {\n"
       "          Object response;\n"
       "          if (match == null) {\n"
       "            Class<?> responseClass = Class.forName(\"Http_Server_Response\");\n"
       "            java.lang.reflect.Method withStatus = responseClass.getMethod(\"with_status\", int.class, String.class);\n"
       "            response = withStatus.invoke(null, 404, \"Not Found\");\n"
       "          } else {\n"
       "            String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);\n"
       "            java.util.HashMap<String, String> headers = new java.util.HashMap<>();\n"
       "            exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.isEmpty() ? \"\" : String.valueOf(v.get(0))));\n"
       "            Class<?> requestClass = Class.forName(\"Http_Request\");\n"
       "            java.lang.reflect.Method makeRequest = requestClass.getMethod(\"make\", String.class, String.class, String.class, java.util.HashMap.class, java.util.Map.class, java.util.Map.class);\n"
       "            Object request = makeRequest.invoke(null, method, path, body, headers, match.params, httpParseQuery(uri.getRawQuery()));\n"
       "            Object out = match.handler.call1(request);\n"
       "            if (out == null) {\n"
       "              Class<?> responseClass = Class.forName(\"Http_Server_Response\");\n"
       "              java.lang.reflect.Method withStatus = responseClass.getMethod(\"with_status\", int.class, String.class);\n"
       "              response = withStatus.invoke(null, 204, \"\");\n"
       "            } else {\n"
       "              response = out;\n"
       "            }\n"
       "          }\n"
       "          java.lang.reflect.Method headersMethod = response.getClass().getMethod(\"headers\");\n"
       "          java.util.Map<?, ?> responseHeaders = (java.util.Map<?, ?>) headersMethod.invoke(response);\n"
       "          responseHeaders.forEach((k, v) -> exchange.getResponseHeaders().add(String.valueOf(k), String.valueOf(v)));\n"
       "          java.lang.reflect.Method bodyMethod = response.getClass().getMethod(\"body\");\n"
       "          String responseBody = String.valueOf(bodyMethod.invoke(response));\n"
       "          byte[] bytes = responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);\n"
       "          java.lang.reflect.Method statusMethod = response.getClass().getMethod(\"status\");\n"
       "          int status = ((Number) statusMethod.invoke(response)).intValue();\n"
       "          exchange.sendResponseHeaders(status, bytes.length);\n"
       "          try (java.io.OutputStream os = exchange.getResponseBody()) { os.write(bytes); }\n"
       "        } catch (Exception ex) {\n"
       "          throw new RuntimeException(ex);\n"
       "        }\n"
       "      });\n"
       "      server.start();\n"
       "      handle.server = server;\n"
       "      handle.port = server.getAddress().getPort();\n"
       "      return handle.port;\n"
       "    } catch (Exception ex) {\n"
       "      throw new RuntimeException(ex);\n"
       "    }\n"
       "  }\n"
       "  public static void httpServerStop(Object handleObj) {\n"
       "    HttpServerHandle handle = (HttpServerHandle) handleObj;\n"
       "    if (handle.server != null) {\n"
       "      handle.server.stop(0);\n"
       "      handle.server = null;\n"
       "    }\n"
       "  }\n"
       "  public static boolean httpServerIsRunning(Object handleObj) {\n"
       "    HttpServerHandle handle = (HttpServerHandle) handleObj;\n"
       "    return handle.server != null;\n"
       "  }\n\n"
       "  public static long parseLong(String raw) {\n"
       "    String s = raw.trim().replace(\"_\", \"\");\n"
       "    boolean negative = s.startsWith(\"-\");\n"
       "    String unsigned = negative ? s.substring(1) : s;\n"
       "    int radix = 10;\n"
       "    String digits = unsigned;\n"
       "    if (unsigned.startsWith(\"0b\")) {\n"
       "      radix = 2;\n"
       "      digits = unsigned.substring(2);\n"
       "    } else if (unsigned.startsWith(\"0o\")) {\n"
       "      radix = 8;\n"
       "      digits = unsigned.substring(2);\n"
       "    } else if (unsigned.startsWith(\"0x\")) {\n"
       "      radix = 16;\n"
       "      digits = unsigned.substring(2);\n"
       "    }\n"
       "    long parsed = Long.parseLong(digits, radix);\n"
       "    return negative ? -parsed : parsed;\n"
       "  }\n\n"
       "  public static int parseInt(String raw) {\n"
       "    return (int) parseLong(raw);\n"
       "  }\n\n"
       "  private static String jsonEscape(String s) {\n"
       "    StringBuilder out = new StringBuilder();\n"
       "    for (int i = 0; i < s.length(); i++) {\n"
       "      char c = s.charAt(i);\n"
       "      switch (c) {\n"
       "        case '\"': out.append(\"\\\\\\\"\"); break;\n"
       "        case '\\\\': out.append(\"\\\\\\\\\"); break;\n"
       "        case '\\b': out.append(\"\\\\b\"); break;\n"
       "        case '\\f': out.append(\"\\\\f\"); break;\n"
       "        case '\\n': out.append(\"\\\\n\"); break;\n"
       "        case '\\r': out.append(\"\\\\r\"); break;\n"
       "        case '\\t': out.append(\"\\\\t\"); break;\n"
       "        default:\n"
       "          if (c < 0x20) out.append(String.format(\"\\\\u%04x\", (int) c));\n"
       "          else out.append(c);\n"
       "      }\n"
       "    }\n"
       "    return out.toString();\n"
       "  }\n"
       "  private static String jsonStringifyValue(Object value) {\n"
       "    if (value == null) return \"null\";\n"
       "    if (value instanceof String || value instanceof Character) return \"\\\"\" + jsonEscape(String.valueOf(value)) + \"\\\"\";\n"
       "    if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Double || value instanceof Float || value instanceof Short || value instanceof Byte || value instanceof java.math.BigDecimal) return String.valueOf(value);\n"
       "    if (value instanceof java.util.Map<?, ?> map) {\n"
       "      StringBuilder out = new StringBuilder(\"{\");\n"
       "      boolean first = true;\n"
       "      for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {\n"
       "        if (!first) out.append(',');\n"
       "        first = false;\n"
       "        out.append(\"\\\"\").append(jsonEscape(String.valueOf(entry.getKey()))).append(\"\\\":\").append(jsonStringifyValue(entry.getValue()));\n"
       "      }\n"
       "      out.append('}');\n"
       "      return out.toString();\n"
       "    }\n"
       "    if (value instanceof java.util.Collection<?> coll) {\n"
       "      StringBuilder out = new StringBuilder(\"[\");\n"
       "      boolean first = true;\n"
       "      for (Object item : coll) {\n"
       "        if (!first) out.append(',');\n"
       "        first = false;\n"
       "        out.append(jsonStringifyValue(item));\n"
       "      }\n"
       "      out.append(']');\n"
       "      return out.toString();\n"
       "    }\n"
       "    throw new RuntimeException(\"Value is not JSON-serializable: \" + value.getClass().getName());\n"
       "  }\n"
       "  public static String jsonStringify(Object value) {\n"
       "    return jsonStringifyValue(value);\n"
       "  }\n"
       "  private static final class JsonParser {\n"
       "    private final String src;\n"
       "    private int idx;\n"
       "    JsonParser(String src) { this.src = src; this.idx = 0; }\n"
       "    Object parse() {\n"
       "      skipWs();\n"
       "      Object value = parseValue();\n"
       "      skipWs();\n"
       "      if (idx != src.length()) throw new RuntimeException(\"Unexpected trailing JSON content\");\n"
       "      return value;\n"
       "    }\n"
       "    private void skipWs() {\n"
       "      while (idx < src.length()) {\n"
       "        char c = src.charAt(idx);\n"
       "        if (c == ' ' || c == '\\n' || c == '\\r' || c == '\\t') idx++;\n"
       "        else break;\n"
       "      }\n"
       "    }\n"
       "    private Object parseValue() {\n"
       "      skipWs();\n"
       "      if (idx >= src.length()) throw new RuntimeException(\"Unexpected end of JSON input\");\n"
       "      char c = src.charAt(idx);\n"
       "      if (c == '{') return parseObject();\n"
       "      if (c == '[') return parseArray();\n"
       "      if (c == '\"') return parseString();\n"
       "      if (c == 't' || c == 'f') return parseBoolean();\n"
       "      if (c == 'n') return parseNull();\n"
       "      return parseNumber();\n"
       "    }\n"
       "    private java.util.Map<String, Object> parseObject() {\n"
       "      idx++;\n"
       "      java.util.HashMap<String, Object> out = new java.util.HashMap<>();\n"
       "      skipWs();\n"
       "      if (idx < src.length() && src.charAt(idx) == '}') { idx++; return out; }\n"
       "      while (true) {\n"
       "        skipWs();\n"
       "        String key = parseString();\n"
       "        skipWs();\n"
       "        expect(':');\n"
       "        Object value = parseValue();\n"
       "        out.put(key, value);\n"
       "        skipWs();\n"
       "        if (idx < src.length() && src.charAt(idx) == '}') { idx++; return out; }\n"
       "        expect(',');\n"
       "      }\n"
       "    }\n"
       "    private java.util.ArrayList<Object> parseArray() {\n"
       "      idx++;\n"
       "      java.util.ArrayList<Object> out = new java.util.ArrayList<>();\n"
       "      skipWs();\n"
       "      if (idx < src.length() && src.charAt(idx) == ']') { idx++; return out; }\n"
       "      while (true) {\n"
       "        out.add(parseValue());\n"
       "        skipWs();\n"
       "        if (idx < src.length() && src.charAt(idx) == ']') { idx++; return out; }\n"
       "        expect(',');\n"
       "      }\n"
       "    }\n"
       "    private String parseString() {\n"
       "      expect('\"');\n"
       "      StringBuilder out = new StringBuilder();\n"
       "      while (idx < src.length()) {\n"
       "        char c = src.charAt(idx++);\n"
       "        if (c == '\"') return out.toString();\n"
       "        if (c == '\\\\') {\n"
       "          if (idx >= src.length()) throw new RuntimeException(\"Invalid JSON escape\");\n"
       "          char e = src.charAt(idx++);\n"
       "          switch (e) {\n"
       "            case '\"': out.append('\"'); break;\n"
       "            case '\\\\': out.append('\\\\'); break;\n"
       "            case '/': out.append('/'); break;\n"
       "            case 'b': out.append('\\b'); break;\n"
       "            case 'f': out.append('\\f'); break;\n"
       "            case 'n': out.append('\\n'); break;\n"
       "            case 'r': out.append('\\r'); break;\n"
       "            case 't': out.append('\\t'); break;\n"
       "            case 'u':\n"
       "              if (idx + 4 > src.length()) throw new RuntimeException(\"Invalid unicode escape\");\n"
       "              out.append((char) Integer.parseInt(src.substring(idx, idx + 4), 16));\n"
       "              idx += 4;\n"
       "              break;\n"
       "            default: throw new RuntimeException(\"Invalid JSON escape: \" + e);\n"
       "          }\n"
       "        } else {\n"
       "          out.append(c);\n"
       "        }\n"
       "      }\n"
       "      throw new RuntimeException(\"Unterminated JSON string\");\n"
       "    }\n"
       "    private Object parseBoolean() {\n"
       "      if (src.startsWith(\"true\", idx)) { idx += 4; return true; }\n"
       "      if (src.startsWith(\"false\", idx)) { idx += 5; return false; }\n"
       "      throw new RuntimeException(\"Invalid JSON boolean\");\n"
       "    }\n"
       "    private Object parseNull() {\n"
       "      if (src.startsWith(\"null\", idx)) { idx += 4; return null; }\n"
       "      throw new RuntimeException(\"Invalid JSON null\");\n"
       "    }\n"
       "    private Object parseNumber() {\n"
       "      int start = idx;\n"
       "      if (src.charAt(idx) == '-') idx++;\n"
       "      while (idx < src.length() && Character.isDigit(src.charAt(idx))) idx++;\n"
       "      boolean real = false;\n"
       "      if (idx < src.length() && src.charAt(idx) == '.') {\n"
       "        real = true;\n"
       "        idx++;\n"
       "        while (idx < src.length() && Character.isDigit(src.charAt(idx))) idx++;\n"
       "      }\n"
       "      if (idx < src.length() && (src.charAt(idx) == 'e' || src.charAt(idx) == 'E')) {\n"
       "        real = true;\n"
       "        idx++;\n"
       "        if (idx < src.length() && (src.charAt(idx) == '+' || src.charAt(idx) == '-')) idx++;\n"
       "        while (idx < src.length() && Character.isDigit(src.charAt(idx))) idx++;\n"
       "      }\n"
       "      String token = src.substring(start, idx);\n"
       "      if (real) return Double.parseDouble(token);\n"
       "      long n = Long.parseLong(token);\n"
       "      return (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) ? (int) n : n;\n"
       "    }\n"
       "    private void expect(char c) {\n"
       "      if (idx >= src.length() || src.charAt(idx) != c) throw new RuntimeException(\"Expected '\" + c + \"' in JSON input\");\n"
       "      idx++;\n"
       "    }\n"
       "  }\n"
       "  public static Object jsonParse(String text) {\n"
       "    return new JsonParser(String.valueOf(text)).parse();\n"
       "  }\n\n"
       "  public static int intPow(int base, int exponent) {\n"
       "    if (exponent < 0) throw new RuntimeException(\"Integral exponentiation requires a non-negative exponent\");\n"
       "    int acc = 1;\n"
       "    int b = base;\n"
       "    int e = exponent;\n"
       "    while (e > 0) {\n"
       "      if ((e & 1) == 1) acc *= b;\n"
       "      b *= b;\n"
       "      e /= 2;\n"
       "    }\n"
       "    return acc;\n"
       "  }\n\n"
       "  public static long intPow64(long base, long exponent) {\n"
       "    if (exponent < 0L) throw new RuntimeException(\"Integral exponentiation requires a non-negative exponent\");\n"
       "    long acc = 1L;\n"
       "    long b = base;\n"
       "    long e = exponent;\n"
       "    while (e > 0L) {\n"
       "      if ((e & 1L) == 1L) acc *= b;\n"
       "      b *= b;\n"
       "      e /= 2L;\n"
       "    }\n"
       "    return acc;\n"
       "  }\n\n"
       "  public static class Task<T> {\n"
       "    private final java.util.concurrent.CompletableFuture<T> future;\n"
       "    public Task(java.util.concurrent.CompletableFuture<T> future) { this.future = future; }\n"
       "    public T await() {\n"
       "      try {\n"
       "        return future.get();\n"
       "      } catch (java.util.concurrent.ExecutionException e) {\n"
       "        Throwable cause = e.getCause();\n"
       "        if (cause instanceof RuntimeException) throw (RuntimeException) cause;\n"
       "        throw new RuntimeException(cause != null ? cause : e);\n"
       "      } catch (InterruptedException e) {\n"
       "        Thread.currentThread().interrupt();\n"
       "        throw new RuntimeException(e);\n"
       "      }\n"
       "    }\n"
       "    public T await(int timeoutMs) {\n"
       "      try {\n"
       "        return future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);\n"
       "      } catch (java.util.concurrent.TimeoutException e) {\n"
       "        return null;\n"
       "      } catch (java.util.concurrent.CancellationException e) {\n"
       "        throw new RuntimeException(\"Task cancelled\");\n"
       "      } catch (java.util.concurrent.ExecutionException e) {\n"
       "        Throwable cause = e.getCause();\n"
       "        if (cause instanceof RuntimeException) throw (RuntimeException) cause;\n"
       "        throw new RuntimeException(cause != null ? cause : e);\n"
       "      } catch (InterruptedException e) {\n"
       "        Thread.currentThread().interrupt();\n"
       "        throw new RuntimeException(e);\n"
       "      }\n"
       "    }\n"
       "    public boolean cancel() { return future.cancel(true); }\n"
       "    public boolean is_done() { return future.isDone(); }\n"
       "    public boolean is_cancelled() { return future.isCancelled(); }\n"
       "  }\n\n"
       "  public static <T> Task<T> spawnTask(java.util.function.Supplier<T> supplier) {\n"
       "    return new Task<>(java.util.concurrent.CompletableFuture.supplyAsync(supplier, TASK_EXECUTOR));\n"
       "  }\n\n"
       "  public static <T> java.util.ArrayList<T> awaitAll(java.util.List<Task<T>> tasks) {\n"
       "    java.util.ArrayList<T> results = new java.util.ArrayList<>();\n"
       "    for (Task<T> task : tasks) {\n"
       "      results.add(task.await());\n"
       "    }\n"
       "    return results;\n"
       "  }\n\n"
       "  public static <T> T awaitAny(java.util.List<Task<T>> tasks) {\n"
       "    if (tasks.isEmpty()) throw new RuntimeException(\"await_any requires at least one task\");\n"
       "    while (true) {\n"
       "      for (Task<T> task : tasks) {\n"
       "        if (task.is_done()) {\n"
       "          return task.await();\n"
       "        }\n"
       "      }\n"
       "      try {\n"
       "        Thread.sleep(1);\n"
       "      } catch (InterruptedException e) {\n"
       "        Thread.currentThread().interrupt();\n"
       "        throw new RuntimeException(e);\n"
       "      }\n"
       "    }\n"
       "  }\n\n"
       "  public static class Channel<T> {\n"
       "    private static final class PendingSend<T> {\n"
       "      private final T value;\n"
       "      private boolean delivered;\n"
       "      private PendingSend(T value) { this.value = value; this.delivered = false; }\n"
       "    }\n\n"
       "    private final int capacity;\n"
       "    private final java.util.ArrayDeque<T> buffer = new java.util.ArrayDeque<>();\n"
       "    private final java.util.ArrayDeque<PendingSend<T>> senders = new java.util.ArrayDeque<>();\n"
       "    private int waitingReceivers = 0;\n"
       "    private boolean closed = false;\n\n"
       "    public Channel() { this(0); }\n"
       "    public Channel(int capacity) {\n"
       "      if (capacity < 0) throw new RuntimeException(\"Channel capacity must be non-negative\");\n"
       "      this.capacity = capacity;\n"
       "    }\n\n"
       "    public synchronized void send(T value) {\n"
       "      if (closed) throw new RuntimeException(\"Cannot send on a closed channel\");\n"
       "      if (capacity == 0) {\n"
        "        PendingSend<T> pending = new PendingSend<>(value);\n"
        "        senders.addLast(pending);\n"
       "        notifyAll();\n"
       "        while (!pending.delivered) {\n"
       "          if (closed) {\n"
       "            senders.remove(pending);\n"
       "            throw new RuntimeException(\"Cannot send on a closed channel\");\n"
       "          }\n"
       "          try {\n"
       "            wait();\n"
       "          } catch (InterruptedException e) {\n"
       "            senders.remove(pending);\n"
       "            Thread.currentThread().interrupt();\n"
       "            throw new RuntimeException(e);\n"
       "          }\n"
       "        }\n"
       "        return;\n"
       "      }\n"
       "      if (buffer.size() < capacity) {\n"
       "        buffer.addLast(value);\n"
       "        notifyAll();\n"
       "        return;\n"
       "      }\n"
       "      PendingSend<T> pending = new PendingSend<>(value);\n"
       "      senders.addLast(pending);\n"
       "      notifyAll();\n"
       "      while (!pending.delivered) {\n"
       "        if (closed) {\n"
       "          senders.remove(pending);\n"
       "          throw new RuntimeException(\"Cannot send on a closed channel\");\n"
       "        }\n"
       "        try {\n"
       "          wait();\n"
       "        } catch (InterruptedException e) {\n"
       "          senders.remove(pending);\n"
       "          Thread.currentThread().interrupt();\n"
       "          throw new RuntimeException(e);\n"
       "        }\n"
       "      }\n"
       "    }\n\n"
       "    public synchronized boolean send(T value, int timeoutMs) {\n"
       "      if (closed) throw new RuntimeException(\"Cannot send on a closed channel\");\n"
       "      long deadline = System.currentTimeMillis() + timeoutMs;\n"
       "      if (capacity == 0 && waitingReceivers > 0) {\n"
       "        PendingSend<T> pending = new PendingSend<>(value);\n"
       "        pending.delivered = true;\n"
       "        senders.addLast(pending);\n"
       "        notifyAll();\n"
       "        return true;\n"
       "      }\n"
       "      if (capacity > 0 && buffer.size() < capacity) {\n"
       "        buffer.addLast(value);\n"
       "        notifyAll();\n"
       "        return true;\n"
       "      }\n"
       "      PendingSend<T> pending = new PendingSend<>(value);\n"
       "      senders.addLast(pending);\n"
       "      notifyAll();\n"
       "      while (!pending.delivered) {\n"
       "        if (closed) {\n"
       "          senders.remove(pending);\n"
       "          throw new RuntimeException(\"Cannot send on a closed channel\");\n"
       "        }\n"
       "        long remaining = deadline - System.currentTimeMillis();\n"
       "        if (remaining <= 0) {\n"
       "          senders.remove(pending);\n"
       "          return false;\n"
       "        }\n"
       "        try {\n"
       "          wait(remaining);\n"
       "        } catch (InterruptedException e) {\n"
       "          senders.remove(pending);\n"
       "          Thread.currentThread().interrupt();\n"
       "          throw new RuntimeException(e);\n"
       "        }\n"
       "      }\n"
       "      return true;\n"
       "    }\n\n"
       "    public synchronized boolean try_send(T value) {\n"
       "      if (closed) throw new RuntimeException(\"Cannot send on a closed channel\");\n"
       "      if (capacity == 0) {\n"
       "        if (waitingReceivers > 0) {\n"
       "          PendingSend<T> pending = new PendingSend<>(value);\n"
       "          pending.delivered = true;\n"
       "          senders.addLast(pending);\n"
       "          notifyAll();\n"
       "          return true;\n"
       "        }\n"
       "        return false;\n"
       "      }\n"
       "      if (buffer.size() < capacity) {\n"
       "        buffer.addLast(value);\n"
       "        notifyAll();\n"
       "        return true;\n"
       "      }\n"
       "      return false;\n"
       "    }\n\n"
       "    public synchronized T receive() {\n"
       "      while (buffer.isEmpty() && senders.isEmpty()) {\n"
       "        if (closed) throw new RuntimeException(\"Cannot receive from a closed channel\");\n"
       "        waitingReceivers++;\n"
        "        try {\n"
        "          wait();\n"
        "        } catch (InterruptedException e) {\n"
          "          Thread.currentThread().interrupt();\n"
          "          throw new RuntimeException(e);\n"
       "        } finally {\n"
       "          waitingReceivers--;\n"
        "        }\n"
        "      }\n"
       "      if (!buffer.isEmpty()) {\n"
       "        T value = buffer.removeFirst();\n"
       "        if (!senders.isEmpty() && buffer.size() < capacity) {\n"
       "          PendingSend<T> pending = senders.removeFirst();\n"
       "          buffer.addLast(pending.value);\n"
       "          pending.delivered = true;\n"
       "        }\n"
       "        notifyAll();\n"
       "        return value;\n"
       "      }\n"
       "      PendingSend<T> pending = senders.removeFirst();\n"
       "      pending.delivered = true;\n"
       "      notifyAll();\n"
       "      return pending.value;\n"
       "    }\n\n"
       "    public synchronized T receive(int timeoutMs) {\n"
       "      long deadline = System.currentTimeMillis() + timeoutMs;\n"
       "      while (buffer.isEmpty() && senders.isEmpty()) {\n"
       "        if (closed) throw new RuntimeException(\"Cannot receive from a closed channel\");\n"
       "        long remaining = deadline - System.currentTimeMillis();\n"
       "        if (remaining <= 0) return null;\n"
       "        waitingReceivers++;\n"
       "        try {\n"
       "          wait(remaining);\n"
       "        } catch (InterruptedException e) {\n"
       "          Thread.currentThread().interrupt();\n"
       "          throw new RuntimeException(e);\n"
       "        } finally {\n"
       "          waitingReceivers--;\n"
       "        }\n"
       "      }\n"
       "      if (!buffer.isEmpty()) {\n"
       "        T value = buffer.removeFirst();\n"
       "        if (!senders.isEmpty() && buffer.size() < capacity) {\n"
       "          PendingSend<T> pending = senders.removeFirst();\n"
       "          buffer.addLast(pending.value);\n"
       "          pending.delivered = true;\n"
       "        }\n"
       "        notifyAll();\n"
       "        return value;\n"
       "      }\n"
       "      PendingSend<T> pending = senders.removeFirst();\n"
       "      pending.delivered = true;\n"
       "      notifyAll();\n"
       "      return pending.value;\n"
       "    }\n\n"
       "    public synchronized T try_receive() {\n"
       "      if (!buffer.isEmpty()) {\n"
       "        T value = buffer.removeFirst();\n"
       "        if (!senders.isEmpty() && buffer.size() < capacity) {\n"
       "          PendingSend<T> pending = senders.removeFirst();\n"
       "          buffer.addLast(pending.value);\n"
       "          pending.delivered = true;\n"
       "        }\n"
       "        notifyAll();\n"
       "        return value;\n"
       "      }\n"
       "      if (!senders.isEmpty()) {\n"
       "        PendingSend<T> pending = senders.removeFirst();\n"
       "        pending.delivered = true;\n"
       "        notifyAll();\n"
       "        return pending.value;\n"
       "      }\n"
       "      return null;\n"
       "    }\n\n"
       "    public synchronized void close() {\n"
       "      if (!closed) {\n"
       "        closed = true;\n"
       "        senders.clear();\n"
       "        notifyAll();\n"
       "      }\n"
       "    }\n\n"
       "    public synchronized boolean is_closed() { return closed; }\n"
       "    public synchronized int capacity() { return capacity; }\n"
       "    public synchronized int size() { return buffer.size(); }\n"
       "  }\n\n"
       "  @SafeVarargs\n"
       "  public static <T> java.util.LinkedHashSet<T> setOf(T... values) {\n"
       "    java.util.LinkedHashSet<T> out = new java.util.LinkedHashSet<>();\n"
       "    for (T value : values) out.add(value);\n"
       "    return out;\n"
       "  }\n\n"
       "  public static <T> java.util.LinkedHashSet<T> setFromArray(java.util.Collection<T> values) {\n"
       "    return new java.util.LinkedHashSet<>(values);\n"
       "  }\n\n"
       "  public static <T> java.util.LinkedHashSet<T> setUnion(java.util.Set<T> a, java.util.Set<T> b) {\n"
       "    java.util.LinkedHashSet<T> out = new java.util.LinkedHashSet<>(a);\n"
       "    out.addAll(b);\n"
       "    return out;\n"
       "  }\n\n"
       "  public static <T> java.util.LinkedHashSet<T> setDifference(java.util.Set<T> a, java.util.Set<T> b) {\n"
       "    java.util.LinkedHashSet<T> out = new java.util.LinkedHashSet<>(a);\n"
       "    out.removeAll(b);\n"
       "    return out;\n"
       "  }\n\n"
       "  public static <T> java.util.LinkedHashSet<T> setIntersection(java.util.Set<T> a, java.util.Set<T> b) {\n"
       "    java.util.LinkedHashSet<T> out = new java.util.LinkedHashSet<>(a);\n"
       "    out.retainAll(b);\n"
       "    return out;\n"
       "  }\n\n"
       "  public static <T> java.util.LinkedHashSet<T> setSymmetricDifference(java.util.Set<T> a, java.util.Set<T> b) {\n"
       "    java.util.LinkedHashSet<T> out = new java.util.LinkedHashSet<>();\n"
       "    for (T value : a) if (!b.contains(value)) out.add(value);\n"
       "    for (T value : b) if (!a.contains(value)) out.add(value);\n"
       "    return out;\n"
       "  }\n\n"
       "  public static class ArrayCursor<T> {\n"
       "    private final java.util.List<T> source;\n"
       "    private int index = 0;\n"
       "    public ArrayCursor(java.util.List<T> source) { this.source = source; }\n"
       "    public void start() { index = 0; }\n"
       "    public T item() { if (index >= source.size()) throw new RuntimeException(\"Cursor is at end\"); return source.get(index); }\n"
       "    public void next() { if (index < source.size()) index++; }\n"
       "    public boolean at_end() { return index >= source.size(); }\n"
       "  }\n\n"
       "  public static class StringCursor {\n"
       "    private final String source;\n"
       "    private int index = 0;\n"
       "    public StringCursor(String source) { this.source = source; }\n"
       "    public void start() { index = 0; }\n"
       "    public char item() { if (index >= source.length()) throw new RuntimeException(\"Cursor is at end\"); return source.charAt(index); }\n"
       "    public void next() { if (index < source.length()) index++; }\n"
       "    public boolean at_end() { return index >= source.length(); }\n"
       "  }\n\n"
       "  public static class MapCursor<K, V> {\n"
       "    private final java.util.Map<K, V> source;\n"
       "    private java.util.ArrayList<K> keys;\n"
       "    private int index = 0;\n"
       "    public MapCursor(java.util.Map<K, V> source) { this.source = source; this.keys = new java.util.ArrayList<>(source.keySet()); }\n"
       "    public void start() { this.keys = new java.util.ArrayList<>(source.keySet()); index = 0; }\n"
       "    public java.util.ArrayList<Object> item() { if (index >= keys.size()) throw new RuntimeException(\"Cursor is at end\"); K key = keys.get(index); return new java.util.ArrayList<>(java.util.Arrays.asList(key, source.get(key))); }\n"
       "    public void next() { if (index < keys.size()) index++; }\n"
       "    public boolean at_end() { return index >= keys.size(); }\n"
       "  }\n\n"
       "  public static class SetCursor<T> {\n"
       "    private final java.util.Set<T> source;\n"
       "    private java.util.ArrayList<T> values;\n"
       "    private int index = 0;\n"
       "    public SetCursor(java.util.Set<T> source) { this.source = source; this.values = new java.util.ArrayList<>(source); }\n"
       "    public void start() { this.values = new java.util.ArrayList<>(source); index = 0; }\n"
       "    public T item() { if (index >= values.size()) throw new RuntimeException(\"Cursor is at end\"); return values.get(index); }\n"
       "    public void next() { if (index < values.size()) index++; }\n"
       "    public boolean at_end() { return index >= values.size(); }\n"
       "  }\n\n"
       "  public static <T> ArrayCursor<T> arrayCursor(java.util.List<T> source) { return new ArrayCursor<>(source); }\n"
       "  public static StringCursor stringCursor(String source) { return new StringCursor(source); }\n"
       "  public static <K, V> MapCursor<K, V> mapCursor(java.util.Map<K, V> source) { return new MapCursor<>(source); }\n"
       "  public static <T> SetCursor<T> setCursor(java.util.Set<T> source) { return new SetCursor<>(source); }\n"
       "}"))

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
  "Generate a Main class.
   If top-level statements exist, execute them in-order.
   Otherwise instantiate the last user-defined class (legacy behavior)."
  [ast]
  (let [statements (:statements ast)
        classes (:classes ast)]
    (if (seq statements)
      (let [top-level-vars (extract-var-names statements)
            top-level-types (extract-typed-locals statements)
            statement-lines (binding [*local-names* (into *local-names* top-level-vars)
                                      *local-types* (merge *local-types* top-level-types)]
                              (mapv #(generate-statement 2 % #{}) statements))]
        (str "public class Main {\n"
             "    public static void main(String[] args) {\n"
             (if (seq statement-lines)
               (str/join "\n" statement-lines)
               "        // no-op")
             "\n"
             "    }\n"
             "}"))
      (let [last-class (last classes)
            class-name (:name last-class)
            {:keys [constructors]} (if last-class
                                     (extract-members (:body last-class))
                                     {:constructors []})
            no-arg-ctor (first (filter #(empty? (:params %)) constructors))
            call (cond
                   (and class-name no-arg-ctor)
                   (str class-name "." (:name no-arg-ctor) "()")
                   (and class-name (empty? constructors))
                   (str "new " class-name "()")
                   :else
                   "/* no-op */")]
        (str "public class Main {\n"
             "    public static void main(String[] args) {\n"
             "        " call ";\n"
             "    }\n"
             "}")))))

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
         runtime-helpers (generate-runtime-helpers)
         function-globals (generate-function-globals functions)]
     (binding [*function-names* function-names
               *class-registry* (into {} (map (juxt :name identity) classes))]
       (let [java-classes (map #(generate-class % opts) classes)
             parts (concat java-imports
                           (when (seq java-imports) [""])
                           [function-base]
                           ["" runtime-helpers]
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
   (let [source-id (or (:source-id opts) "<input>")
         ast (augment-ast-with-interns source-id (p/ast nex-code))]
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
  [dir jar-name main-class]
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
    (let [manifest-str (str "Manifest-Version: 1.0\nMain-Class: " main-class "\n\n")
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
         ast (augment-ast-with-interns nex-file (p/ast nex-code))
         _ (when-not (:skip-type-check opts)
             (let [result (tc/type-check ast)]
               (when-not (:success result)
                 (throw (ex-info "Type checking failed"
                                 {:errors (map tc/format-type-error (:errors result))})))))
         classes (:classes ast)
         functions (:functions ast)
         function-names (set (map :name functions))
         function-base (generate-function-base-class)
         runtime-helpers (generate-runtime-helpers)
         function-globals (generate-function-globals functions)
         launcher-class (if (some #(= "Main" (:name %)) classes) "NexProgram" "Main")
         main-code (binding [*function-names* function-names
                             *class-registry* (into {} (map (juxt :name identity) classes))]
                     (generate-main ast))
         class-codes (binding [*function-names* function-names
                              *class-registry* (into {} (map (juxt :name identity) classes))]
                       (mapv (fn [cls] [(:name cls) (generate-class cls opts)]) classes))
         files (into {"Function.java" function-base
                      "NexRuntime.java" runtime-helpers
                      "NexWindow.java" (generate-nex-window-class)
                      "NexImage.java" (generate-nex-image-class)
                      "NexTurtle.java" (generate-nex-turtle-class)}
                     (concat
                      (when function-globals
                        [["NexGlobals.java" function-globals]])
                      (map (fn [[name code]] [(str name ".java") code]) class-codes)
                      [[(str launcher-class ".java")
                        (if (= launcher-class "Main")
                          main-code
                          (str/replace main-code #"public class Main" (str "public class " launcher-class)) )]]))]
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
             _ (compile-jar build-dir jar-name launcher-class)
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
