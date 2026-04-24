(ns nex.generator.javascript
  "Translates Nex (Eiffel-based) code to JavaScript (ES6+)"
  (:require [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.java.io :as io]))

(def ^:dynamic *function-names* #{})
(def ^:dynamic *this-name* "this")
(def ^:dynamic *current-class-name* nil)
(def ^:dynamic *class-registry* {})
(def ^:dynamic *all-method-names* #{})  ;; own + parent method names
(def ^:dynamic *own-fields* #{})        ;; field names defined on current class
(def ^:dynamic *constant-names* #{})    ;; accessible class constants
(def ^:dynamic *local-names* #{})       ;; method params + loop vars
(def ^:dynamic *local-types* {})        ;; local/param name -> Nex type
(def ^:dynamic *field-types* {})        ;; field-name -> type
(def ^:dynamic *class-invariants* [])   ;; effective class invariants (inherited + local, deduped)
(def ^:dynamic *spawn-result-flag* nil) ;; temp boolean flag in generated spawn bodies

(declare extract-members)
(declare get-accessible-constants-js)
(declare generate-statement)
(declare collect-convert-bindings-block)
(declare generate-convert-declarations)
(declare generate-call-expr)
(declare generate-expression)

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
                    "Set" "Set"
                    "Min_Heap" "Min_Heap"
                    "Atomic_Integer" "Atomic_Integer"
                    "Atomic_Integer64" "Atomic_Integer64"
                    "Atomic_Boolean" "Atomic_Boolean"
                    "Atomic_Reference" "Atomic_Reference"
                    "Task" "Task"
                    "Channel" "Channel"
                    base-type)
          args (or (:type-args nex-type) (:type-params nex-type))]
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
      "Set" "Set"
      "Min_Heap" "Min_Heap"
      "Atomic_Integer" "Atomic_Integer"
      "Atomic_Integer64" "Atomic_Integer64"
      "Atomic_Boolean" "Atomic_Boolean"
      "Atomic_Reference" "Atomic_Reference"
      "Task" "Task"
      "Channel" "Channel"
      "Console" "Object"
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
        "Set" "new Set()"
        "Min_Heap" "__nexMinHeap()"
        "Atomic_Integer" "null"
        "Atomic_Integer64" "null"
        "Atomic_Boolean" "null"
        "Atomic_Reference" "null"
        "Task" "null"
        "Channel" "null"
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
      "Set" "new Set()"
      "Min_Heap" "__nexMinHeap()"
      "Atomic_Integer" "null"
      "Atomic_Integer64" "null"
      "Atomic_Boolean" "null"
      "Atomic_Reference" "null"
      "Task" "null"
      "Channel" "null"
      "Console" "({_type: 'Console'})"
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
    "==" "==="
    "!=" "!=="
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
                ("=" "/=" "==" "!=" "<" "<=" ">" ">=" "and" "or") "Boolean"
                "+" (if (or (= lt "String") (= rt "String")) "String" lt)
                ("-" "*" "/" "%" "^") lt
                "Any"))
    :call (if (and (string? (:target expr)) (false? (:has-parens expr)))
            (get constants-by-name (:method expr) "Any")
            "Any")
    "Any"))

(defn builtin-dispatch-type
  "Normalize inferred types for builtin dispatch."
  [t]
  (cond
    (map? t) (:base-type t)
    :else t))

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
                  ("=" "/=" "==" "!=" "<" "<=" ">" ">=" "and" "or") "Boolean"
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
                  "hint_spin" "Void"
                  "random_real" "Real"
                  "regex_validate" "Boolean"
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
                  "binary_file_position" "Integer"
                  "binary_file_seek" "Void"
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
                "Min_Heap" (case (:method expr)
                             ("extract_min" "peek") (or (first type-args) "Any")
                             ("try_extract_min" "try_peek") {:base-type (or (first type-args) "Any") :detachable true}
                             "size" "Integer"
                             "is_empty" "Boolean"
                             "insert" "Void"
                             "Any")
                "Atomic_Integer" (case (:method expr)
                                   ("load" "get_and_add" "add_and_get" "increment" "decrement") "Integer"
                                   "store" "Void"
                                   "compare_and_set" "Boolean"
                                   "Any")
                "Atomic_Integer64" (case (:method expr)
                                     ("load" "get_and_add" "add_and_get" "increment" "decrement") "Integer64"
                                     "store" "Void"
                                     "compare_and_set" "Boolean"
                                     "Any")
                "Atomic_Boolean" (case (:method expr)
                                   "load" "Boolean"
                                   "store" "Void"
                                   "compare_and_set" "Boolean"
                                   "Any")
                "Atomic_Reference" (case (:method expr)
                                     "load" {:base-type (or (first type-args) "Any") :detachable true}
                                     "store" "Void"
                                     "compare_and_set" "Boolean"
                                     "Any")
                "Set" (case (:method expr)
                        ("contains" "is_empty") "Boolean"
                        "size" "Integer"
                        ("union" "difference" "intersection" "symmetric_difference") target-type
                        "Any")
                "Array" (case (:method expr)
                          "get" (or (first type-args) "Any")
                          ("length" "size" "index_of") "Integer"
                          "concat" target-type
                          "is_empty" "Boolean"
                          "Any")
                "Map" (case (:method expr)
                        "get" (or (second type-args) "Any")
                        "size" "Integer"
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

(defn resolve-identifier
  "Resolve an identifier in expression context for JavaScript.
   Resolution order: locals -> methods -> fields -> functions -> bare name"
  [id-name]
  (cond
    (contains? *local-names* id-name) id-name
    (contains? *all-method-names* id-name) (str *this-name* "." id-name "()")
    (contains? *constant-names* id-name) (str *current-class-name* "." id-name)
    (contains? *own-fields* id-name) (str *this-name* "." id-name)
    (contains? *function-names* id-name) (str "NexGlobals." id-name)
    :else id-name))

(defn generate-binary-expr
  "Generate JavaScript code for binary expression"
  [{:keys [operator left right]}]
  (let [left-code (generate-expression left)
        right-code (generate-expression right)
        left-type (builtin-dispatch-type (infer-expression-type left))
        right-type (builtin-dispatch-type (infer-expression-type right))]
    (cond
      (and (= operator "+")
           (or (= left-type "String") (= right-type "String")))
      (str "__nexConcat(" left-code ", " right-code ")")

      (contains? #{"/" "^"} operator)
      (let [left-type left-type
            right-type right-type]
        (case operator
          "/" (if (and (integral-dispatch-type? left-type)
                       (integral-dispatch-type? right-type))
                (str "__nexIntDiv(" left-code ", " right-code ")")
                (str "(" left-code " / " right-code ")"))
          "^" (if (and (integral-dispatch-type? left-type)
                       (integral-dispatch-type? right-type))
                (str "__nexIntPow(" left-code ", " right-code ")")
                (str "(" left-code " ** " right-code ")"))))

      :else
      (let [op (generate-binary-op operator)]
        (str "(" left-code " " op " " right-code ")")))))

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
  [method args num-args]
  (let [args-code (str/join ", " (map generate-expression args))
        print-args-code (str/join ", " (map #(str "__nexPrintValue(" (generate-expression %) ")") args))]
    (case method
      "print" (str "console.log(" print-args-code ")")
      "println" (str "console.log(" print-args-code ")")
      "type_of" (str "__nexTypeOf(" args-code ")")
      "type_is" (str "__nexTypeIs(" args-code ")")
      "sleep" (str "await __nexSleep(" args-code ")")
      "hint_spin" "__nexHintSpin()"
      "random_real" "Math.random()"
      "regex_validate" (str "__nexRegexValidate(" args-code ")")
      "regex_matches" (str "__nexRegexMatches(" args-code ")")
      "regex_find" (str "__nexRegexFind(" args-code ")")
      "regex_find_all" (str "__nexRegexFindAll(" args-code ")")
      "regex_replace" (str "__nexRegexReplace(" args-code ")")
      "regex_split" (str "__nexRegexSplit(" args-code ")")
      "datetime_now" (str "__nexDateTimeNow(" args-code ")")
      "datetime_from_epoch_millis" (str "__nexDateTimeFromEpochMillis(" args-code ")")
      "datetime_parse_iso" (str "__nexDateTimeParseIso(" args-code ")")
      "datetime_make" (str "__nexDateTimeMake(" args-code ")")
      "datetime_year" (str "__nexDateTimeYear(" args-code ")")
      "datetime_month" (str "__nexDateTimeMonth(" args-code ")")
      "datetime_day" (str "__nexDateTimeDay(" args-code ")")
      "datetime_weekday" (str "__nexDateTimeWeekday(" args-code ")")
      "datetime_day_of_year" (str "__nexDateTimeDayOfYear(" args-code ")")
      "datetime_hour" (str "__nexDateTimeHour(" args-code ")")
      "datetime_minute" (str "__nexDateTimeMinute(" args-code ")")
      "datetime_second" (str "__nexDateTimeSecond(" args-code ")")
      "datetime_epoch_millis" (str "__nexDateTimeEpochMillis(" args-code ")")
      "datetime_add_millis" (str "__nexDateTimeAddMillis(" args-code ")")
      "datetime_diff_millis" (str "__nexDateTimeDiffMillis(" args-code ")")
      "datetime_truncate_to_day" (str "__nexDateTimeTruncateToDay(" args-code ")")
      "datetime_truncate_to_hour" (str "__nexDateTimeTruncateToHour(" args-code ")")
      "datetime_format_iso" (str "__nexDateTimeFormatIso(" args-code ")")
      "path_exists" (str "__nexPathExists(" args-code ")")
      "path_is_file" (str "__nexPathIsFile(" args-code ")")
      "path_is_directory" (str "__nexPathIsDirectory(" args-code ")")
      "path_name" (str "__nexPathName(" args-code ")")
      "path_extension" (str "__nexPathExtension(" args-code ")")
      "path_name_without_extension" (str "__nexPathNameWithoutExtension(" args-code ")")
      "path_absolute" (str "__nexPathAbsolute(" args-code ")")
      "path_normalize" (str "__nexPathNormalize(" args-code ")")
      "path_size" (str "__nexPathSize(" args-code ")")
      "path_modified_time" (str "__nexPathModifiedTime(" args-code ")")
      "path_parent" (str "__nexPathParent(" args-code ")")
      "path_child" (str "__nexPathChild(" args-code ")")
      "path_create_file" (str "__nexPathCreateFile(" args-code ")")
      "path_create_directory" (str "__nexPathCreateDirectory(" args-code ")")
      "path_create_directories" (str "__nexPathCreateDirectories(" args-code ")")
      "path_delete" (str "__nexPathDelete(" args-code ")")
      "path_delete_tree" (str "__nexPathDeleteTree(" args-code ")")
      "path_copy" (str "__nexPathCopy(" args-code ")")
      "path_move" (str "__nexPathMove(" args-code ")")
      "path_read_text" (str "__nexPathReadText(" args-code ")")
      "path_write_text" (str "__nexPathWriteText(" args-code ")")
      "path_append_text" (str "__nexPathAppendText(" args-code ")")
      "path_list" (str "__nexPathList(" args-code ")")
      "text_file_open_read" (str "__nexTextFileOpenRead(" args-code ")")
      "text_file_open_write" (str "__nexTextFileOpenWrite(" args-code ")")
      "text_file_open_append" (str "__nexTextFileOpenAppend(" args-code ")")
      "text_file_read_line" (str "__nexTextFileReadLine(" args-code ")")
      "text_file_write" (str "__nexTextFileWrite(" args-code ")")
      "text_file_close" (str "__nexTextFileClose(" args-code ")")
      "binary_file_open_read" (str "__nexBinaryFileOpenRead(" args-code ")")
      "binary_file_open_write" (str "__nexBinaryFileOpenWrite(" args-code ")")
      "binary_file_open_append" (str "__nexBinaryFileOpenAppend(" args-code ")")
      "binary_file_read_all" (str "__nexBinaryFileReadAll(" args-code ")")
      "binary_file_read" (str "__nexBinaryFileRead(" args-code ")")
      "binary_file_write" (str "__nexBinaryFileWrite(" args-code ")")
      "binary_file_position" (str "__nexBinaryFilePosition(" args-code ")")
      "binary_file_seek" (str "__nexBinaryFileSeek(" args-code ")")
      "binary_file_close" (str "__nexBinaryFileClose(" args-code ")")
      "json_parse" (str "__nexJsonParse(" args-code ")")
      "json_stringify" (str "__nexJsonStringify(" args-code ")")
      "http_get" (str "await __nexHttpGet(" args-code ")")
      "http_post" (str "await __nexHttpPost(" args-code ")")
      "http_server_create" (str "__nexHttpServerCreate(" args-code ")")
      "http_server_get" (str "__nexHttpServerGet(" args-code ")")
      "http_server_post" (str "__nexHttpServerPost(" args-code ")")
      "http_server_put" (str "__nexHttpServerPut(" args-code ")")
      "http_server_delete" (str "__nexHttpServerDelete(" args-code ")")
      "http_server_start" (str "await __nexHttpServerStart(" args-code ")")
      "http_server_stop" (str "await __nexHttpServerStop(" args-code ")")
      "http_server_is_running" (str "__nexHttpServerIsRunning(" args-code ")")
      "await_any" (str "await __nexAwaitAny(" args-code ")")
      "await_all" (str "await __nexAwaitAll(" args-code ")")
      ;; Default: use as-is (regular method call)
      (str method "(" args-code ")"))))

(def builtin-method-mappings
  "Map Nex built-in type methods to JavaScript equivalents"
  {:Any
   {"to_string"  (fn [target _] (str "__nexToString(" target ")"))
    "equals"     (fn [target args] (str "(" target " === " args ")"))
    "clone"      (fn [target _] (str "__nexCloneValue(" target ")"))}

   :String
   {"length"      (fn [target _] (str target ".length"))
    "index_of"    (fn [target args] (str target ".indexOf(" args ")"))
    "substring"   (fn [target args] (str target ".substring(" args ")"))
    "to_upper"    (fn [target _] (str target ".toUpperCase()"))
    "to_lower"    (fn [target _] (str target ".toLowerCase()"))
    "to_integer"  (fn [target _] (str "__nexParseInt(" target ")"))
    "to_integer64" (fn [target _] (str "__nexParseLong(" target ")"))
    "to_real"     (fn [target _] (str "parseFloat(" target ".trim())"))
    "to_decimal"  (fn [target _] (str "parseFloat(" target ".trim())"))
    "contains"    (fn [target args] (str target ".includes(" args ")"))
    "starts_with" (fn [target args] (str target ".startsWith(" args ")"))
    "ends_with"   (fn [target args] (str target ".endsWith(" args ")"))
    "trim"        (fn [target _] (str target ".trim()"))
    "replace"     (fn [target args] (str target ".replace(" args ")"))
    "char_at"     (fn [target args] (str target ".charAt(" args ")"))
    "chars"       (fn [target _] (str target ".split(\"\")"))
    "to_bytes"    (fn [target _] (str "__nexStringToBytes(" target ")"))
    "compare"     (fn [target args] (str "__nexCompare(" target ", " args ")"))
    "hash"        (fn [target _] (str "__nexHash(" target ")"))
    "split"       (fn [target args] (str target ".split(" args ")"))
    "cursor"      (fn [target _] (str "__nexStringCursor(" target ")"))
    ;; String operators
    "plus"        (fn [target args] (str "__nexConcat(" target ", " args ")"))
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
    "bitwise_left_shift" (fn [target args] (str "(" target " << " args ")"))
    "bitwise_right_shift" (fn [target args] (str "(" target " >> " args ")"))
    "bitwise_logical_right_shift" (fn [target args] (str "(" target " >>> " args ")"))
    "bitwise_rotate_left" (fn [target args] (str "(((" target " << (" args " & 31)) | (" target " >>> (32 - (" args " & 31)))) | 0)"))
    "bitwise_rotate_right" (fn [target args] (str "(((" target " >>> (" args " & 31)) | (" target " << (32 - (" args " & 31)))) | 0)"))
    "bitwise_is_set" (fn [target args] (str "(((" target " >>> " args ") & 1) !== 0)"))
    "bitwise_set" (fn [target args] (str "(" target " | (1 << " args "))"))
    "bitwise_unset" (fn [target args] (str "(" target " & ~(1 << " args "))"))
    "bitwise_and" (fn [target args] (str "(" target " & " args ")"))
    "bitwise_or" (fn [target args] (str "(" target " | " args ")"))
    "bitwise_xor" (fn [target args] (str "(" target " ^ " args ")"))
    "bitwise_not" (fn [target _] (str "(~" target ")"))
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
    "add_at"    (fn [target args] (str "__nexArrayAddAt(" target ", " args ")"))
    "add"       (fn [target args] (str "__nexArrayAdd(" target ", " args ")"))
    "put"       (fn [target args] (str "__nexArrayPut(" target ", " args ")"))
    "set"       (fn [target args] (str "__nexArrayPut(" target ", " args ")"))
    "is_empty"  (fn [target _] (str "(" target ".length === 0)"))
    "contains"  (fn [target args] (str "__nexArrayContains(" target ", " args ")"))
    "index_of"  (fn [target args] (str "__nexArrayIndexOf(" target ", " args ")"))
    "remove"    (fn [target args] (str "(" target ".splice(" args ", 1), null)"))
    "reverse"   (fn [target _] (str "[..." target "].reverse()"))
    "sort"      (fn [target args]
                  (if (str/blank? args)
                    (str "__nexArraySort(" target ")")
                    (str "__nexArraySort(" target ", " args ")")))
    "slice"     (fn [target args] (str target ".slice(" args ")"))
    "concat"    (fn [target args] (str target ".concat(" args ")"))
    "to_string" (fn [target _] (str "__nexToString(" target ")"))
    "equals"    (fn [target args] (str "__nexDeepEquals(" target ", " args ")"))
    "clone"     (fn [target _] (str "__nexDeepClone(" target ")"))
    "cursor"    (fn [target _] (str "__nexArrayCursor(" target ")"))}

   :Map
   {"size"         (fn [target _] (str target ".size"))
    "is_empty"     (fn [target _] (str "(" target ".size === 0)"))
    "get"          (fn [target args] (str target ".get(" args ")"))
    "try_get"      (fn [target args] (str target ".get(" args ")"))
    "put"          (fn [target args] (str "__nexMapPut(" target ", " args ")"))
    "set"          (fn [target args] (str "__nexMapPut(" target ", " args ")"))
    "contains_key" (fn [target args] (str "__nexMapContainsKey(" target ", " args ")"))
    "keys"         (fn [target _] (str "Array.from(" target ".keys())"))
    "values"       (fn [target _] (str "Array.from(" target ".values())"))
    "remove"       (fn [target args] (str "(" target ".delete(" args "), null)"))
    "to_string"    (fn [target _] (str "__nexToString(" target ")"))
    "equals"       (fn [target args] (str "__nexDeepEquals(" target ", " args ")"))
    "clone"        (fn [target _] (str "__nexDeepClone(" target ")"))
    "cursor"       (fn [target _] (str "__nexMapCursor(" target ")"))}

   :Set
   {"contains"             (fn [target args] (str "__nexSetContains(" target ", " args ")"))
    "union"                (fn [target args] (str "__nexSetUnion(" target ", " args ")"))
    "difference"           (fn [target args] (str "__nexSetDifference(" target ", " args ")"))
    "intersection"         (fn [target args] (str "__nexSetIntersection(" target ", " args ")"))
    "symmetric_difference" (fn [target args] (str "__nexSetSymmetricDifference(" target ", " args ")"))
    "size"                 (fn [target _] (str target ".size"))
    "is_empty"             (fn [target _] (str "(" target ".size === 0)"))
    "to_string"            (fn [target _] (str "__nexToString(" target ")"))
    "equals"               (fn [target args] (str "__nexDeepEquals(" target ", " args ")"))
    "clone"                (fn [target _] (str "__nexDeepClone(" target ")"))
    "cursor"               (fn [target _] (str "__nexSetCursor(" target ")"))}

   :Min_Heap
   {"insert"          (fn [target args] (str "__nexMinHeapInsert(" target ", " args ")"))
    "extract_min"     (fn [target _] (str "__nexMinHeapExtractMin(" target ")"))
    "try_extract_min" (fn [target _] (str "__nexMinHeapTryExtractMin(" target ")"))
    "peek"            (fn [target _] (str "__nexMinHeapPeek(" target ")"))
    "try_peek"        (fn [target _] (str "__nexMinHeapTryPeek(" target ")"))
    "size"            (fn [target _] (str target ".data.length"))
    "is_empty"        (fn [target _] (str "(" target ".data.length === 0)"))}

   :Atomic_Integer
   {"load"            (fn [target _] (str "__nexAtomicLoad(" target ")"))
    "store"           (fn [target args] (str "__nexAtomicStore(" target ", " args ")"))
    "compare_and_set" (fn [target args] (str "__nexAtomicCompareAndSet(" target ", " args ")"))
    "get_and_add"     (fn [target args] (str "__nexAtomicGetAndAdd(" target ", " args ")"))
    "add_and_get"     (fn [target args] (str "__nexAtomicAddAndGet(" target ", " args ")"))
    "increment"       (fn [target _] (str "__nexAtomicAddAndGet(" target ", 1)"))
    "decrement"       (fn [target _] (str "__nexAtomicAddAndGet(" target ", -1)"))}

   :Atomic_Integer64
   {"load"            (fn [target _] (str "__nexAtomicLoad(" target ")"))
    "store"           (fn [target args] (str "__nexAtomicStore(" target ", " args ")"))
    "compare_and_set" (fn [target args] (str "__nexAtomicCompareAndSet(" target ", " args ")"))
    "get_and_add"     (fn [target args] (str "__nexAtomicGetAndAdd(" target ", " args ")"))
    "add_and_get"     (fn [target args] (str "__nexAtomicAddAndGet(" target ", " args ")"))
    "increment"       (fn [target _] (str "__nexAtomicAddAndGet(" target ", 1)"))
    "decrement"       (fn [target _] (str "__nexAtomicAddAndGet(" target ", -1)"))}

   :Atomic_Boolean
   {"load"            (fn [target _] (str "__nexAtomicLoad(" target ")"))
    "store"           (fn [target args] (str "__nexAtomicStore(" target ", " args ")"))
    "compare_and_set" (fn [target args] (str "__nexAtomicCompareAndSet(" target ", " args ")"))}

   :Atomic_Reference
   {"load"            (fn [target _] (str "__nexAtomicLoad(" target ")"))
    "store"           (fn [target args] (str "__nexAtomicStore(" target ", " args ")"))
    "compare_and_set" (fn [target args] (str "__nexAtomicCompareAndSet(" target ", " args ")"))}

   :Task
   {"await"    (fn [target args] (str "await " target ".await(" args ")"))
    "cancel"   (fn [target _] (str target ".cancel()"))
    "is_done"  (fn [target _] (str target ".is_done()"))
    "is_cancelled" (fn [target _] (str target ".is_cancelled()"))}

   :Channel
   {"send"        (fn [target args] (str "await " target ".send(" args ")"))
    "try_send"    (fn [target args] (str target ".try_send(" args ")"))
    "receive"     (fn [target args] (str "await " target ".receive(" args ")"))
    "try_receive" (fn [target _] (str target ".try_receive()"))
    "close"       (fn [target _] (str target ".close()"))
    "is_closed"   (fn [target _] (str target ".is_closed()"))
    "capacity"    (fn [target _] (str target ".capacity()"))
    "size"        (fn [target _] (str target ".size()"))}

   :Console
   {"print"        (fn [_ args] (str "process.stdout.write(String(" args "))"))
    "print_line"   (fn [_ args] (str "console.log(" args ")"))
    "read_line"    (fn [_ _] "require('readline-sync').question('')")
    "error"        (fn [_ args] (str "console.error(" args ")"))
    "new_line"     (fn [_ _] "console.log()")
    "flush"        (fn [_ _] "process.stdout.write('')")
    "read_integer" (fn [_ _] "__nexParseInt(require('readline-sync').question(''))")
    "read_real"    (fn [_ _] "parseFloat(require('readline-sync').question(''))")}

   :Process
   {"getenv"       (fn [_ a] (str "process.env[" a "]"))
   "setenv"       (fn [_ a] (str "process.env[" a "]"))
   "command_line" (fn [_ _] "process.argv")}})

(declare generate-create-expr)

(defn async-call?
  "Whether a call should be awaited in generated JavaScript."
  [{:keys [target method] :as call-node}]
  (cond
    (and (nil? method)
         (map? target)
         (= :create (:type target))
        (not (#{"Console" "Process" "Set" "Channel" "Min_Heap"
                "Atomic_Integer" "Atomic_Integer64" "Atomic_Boolean" "Atomic_Reference"}
              (:class-name target))))
    true

    (nil? method) false

    (nil? target)
    (and (not (#{"print" "println" "type_of" "type_is"} method))
         (or (contains? *all-method-names* method)
             (function-type? (get *field-types* method))
             (function-type? (get *local-types* method))
             (contains? *local-names* method)
             (contains? *function-names* method)))

    :else
    (let [target-type (builtin-dispatch-type (infer-expression-type target))]
      (cond
        (#{"Task" "Channel"} target-type) true
        (and (map? target) (= :this (:type target)) (contains? *all-method-names* method)) true
        (= target-type "Any") false
        (get *class-registry* target-type) true
        :else false))))

(defn maybe-await
  [call-node code]
  (if (async-call? call-node)
    (str "await " code)
    code))

(defn generate-spawn-expr
  "Generate JavaScript code for spawn expression."
  [{:keys [body]}]
  (let [convert-bindings (collect-convert-bindings-block body)
        convert-var-names (set (map :name convert-bindings))
        convert-var-types (into {} (map (juxt :name :target-type) convert-bindings))
        result-type (or (infer-spawn-result-type body) "Any")
        local-types (merge convert-var-types (extract-typed-locals body) {"result" result-type})
        result-flag (str (gensym "__nexSpawnHasResult"))]
    (binding [*local-names* (into *local-names* (conj convert-var-names "result"))
              *local-types* (merge *local-types* local-types)
              *spawn-result-flag* result-flag]
      (let [statement-lines (map #(generate-statement 1 %) body)]
        (str "__nexSpawn(async () => {\n"
             (indent 1 "let result = null;") "\n"
             (indent 1 (str "let " result-flag " = false;")) "\n"
             (str/join "\n" (generate-convert-declarations 1 convert-bindings))
             (when (seq convert-bindings) "\n")
             (str/join "\n" statement-lines)
             (when (seq statement-lines) "\n")
             (indent 1 (str "return " result-flag " ? result : null;")) "\n"
             "})")))))

(defn generate-call-expr
  "Generate JavaScript code for method call.
   NOTE: For operator methods (plus, less_than, etc.) that exist on multiple types,
   we try Integer methods first since numeric operations are more common.
   For string operations, use string literals or string-specific methods."
  [{:keys [target method args] :as call-node}]
  (let [args-code (str/join ", " (map generate-expression args))
        num-args (count args)]
    (if target
      (let [target-code (if (string? target) target (generate-expression target))
            this-target? (and (map? target) (= :this (:type target)))
            has-parens (:has-parens call-node)
            builtin-dispatch (keyword (builtin-dispatch-type (infer-expression-type target)))]
        (cond
          (nil? method)
          (if (and (map? target) (= :create (:type target)))
            (if (nil? (:constructor target))
              (throw (ex-info (str "Invalid create syntax for " (:class-name target))
                              {:class-name (:class-name target)
                               :message (str "Use 'create " (:class-name target)
                                             "' or 'create " (:class-name target) ".<ctor>(...)'.")}))
              (generate-create-expr (assoc target :args args)))
            (maybe-await call-node (str target-code ".call" num-args "(" args-code ")")))

          (and (string? target)
               (false? has-parens)
               (get *class-registry* target)
               (some #(= (:name %) method)
                     (get-accessible-constants-js (get *class-registry* target) *class-registry*)))
          (str target "." method)

          this-target?
          (if (false? has-parens)
            (if (contains? *all-method-names* method)
              (maybe-await call-node (str target-code "." method "()"))
              (str target-code "." method))
            (if (and (not (contains? *all-method-names* method))
                     (function-type? (get *field-types* method)))
              (maybe-await call-node (str target-code "." method ".call" num-args "(" args-code ")"))
              (if-let [method-fn (get-in builtin-method-mappings [:Any method])]
                (method-fn target-code args-code)
                (maybe-await call-node (str target-code "." method "(" args-code ")")))))

          :else
          (or
           (when-let [method-fn (get-in builtin-method-mappings [builtin-dispatch method])]
             (method-fn target-code args-code))
           (when-let [method-fn (get-in builtin-method-mappings [:Integer method])]
             (method-fn target-code args-code))
           (when-let [method-fn (get-in builtin-method-mappings [:Integer64 method])]
             (method-fn target-code args-code))
           (when-let [method-fn (get-in builtin-method-mappings [:Real method])]
             (method-fn target-code args-code))
           (when-let [method-fn (get-in builtin-method-mappings [:Decimal method])]
             (method-fn target-code args-code))
           (when-let [method-fn (get-in builtin-method-mappings [:String method])]
             (method-fn target-code args-code))
           (when-let [method-fn (get-in builtin-method-mappings [:Array method])]
             (method-fn target-code args-code))
           (when-let [method-fn (get-in builtin-method-mappings [:Map method])]
             (method-fn target-code args-code))
           (when-let [method-fn (get-in builtin-method-mappings [:Set method])]
             (method-fn target-code args-code))
           (when-let [method-fn (get-in builtin-method-mappings [:Any method])]
             (method-fn target-code args-code))
           (maybe-await call-node (str target-code "." method "(" args-code ")")))))
      ;; No target: class method, function object field, global function, or builtin
      (cond
        ;; Class method (own or inherited)
        (and method (contains? *all-method-names* method))
        (maybe-await call-node
                     (if (= *this-name* "this")
                       (str "this." method "(" args-code ")")
                       (str *this-name* "." method "(" args-code ")")))

        ;; Function-typed field: call the function object
        (and method (function-type? (get *field-types* method)))
        (maybe-await call-node (str "this." method ".call" num-args "(" args-code ")"))

        ;; Function-typed local/parameter/top-level binding
        (and method (function-type? (get *local-types* method)))
        (maybe-await call-node (str method ".call" num-args "(" args-code ")"))

        ;; Local/parameter/top-level binding shadows global functions and is callable
        (and method (contains? *local-names* method))
        (maybe-await call-node (str method ".call" num-args "(" args-code ")"))

        ;; Global function
        (and method (contains? *function-names* method))
        (maybe-await call-node (str "NexGlobals." method ".call" num-args "(" args-code ")"))

        ;; Expression that returns a function
        (nil? method)
        (maybe-await call-node (str (generate-expression target) ".call" num-args "(" args-code ")"))

        ;; Builtin
        :else
        (map-builtin-function method args num-args)))))

(defn generate-create-expr
  "Generate JavaScript code for create expression"
  [{:keys [class-name generic-args constructor args]}]
  ;; JS has no generics syntax, generic-args is accepted but not emitted
  (let [args-code (str/join ", " (map generate-expression args))]
    (case class-name
      "Console" "({_type: 'Console'})"
      "Process" "({_type: 'Process'})"
      "Array" (cond
                (nil? constructor) "[]"
                (= constructor "filled")
                (str "Array.from({ length: "
                     (generate-expression (first args))
                     " }, () => "
                     (generate-expression (second args))
                     ")")
                :else
                (throw (ex-info (str "Unsupported built-in Array constructor: " constructor)
                                {:constructor constructor})))
      "Map" "new Map()"
      "Min_Heap" (cond
                   (or (nil? constructor) (= constructor "empty")) "__nexMinHeap()"
                   (= constructor "from_comparator") (str "__nexMinHeap(" args-code ")")
                   :else
                   (throw (ex-info (str "Unsupported built-in Min_Heap constructor: " constructor)
                                   {:constructor constructor})))
      "Atomic_Integer" (cond
                         (= constructor "make") (str "__nexAtomicInteger(" args-code ")")
                         :else (throw (ex-info (str "Unsupported built-in Atomic_Integer constructor: " constructor)
                                               {:constructor constructor})))
      "Atomic_Integer64" (cond
                           (= constructor "make") (str "__nexAtomicInteger64(" args-code ")")
                           :else (throw (ex-info (str "Unsupported built-in Atomic_Integer64 constructor: " constructor)
                                                 {:constructor constructor})))
      "Atomic_Boolean" (cond
                         (= constructor "make") (str "__nexAtomicBoolean(" args-code ")")
                         :else (throw (ex-info (str "Unsupported built-in Atomic_Boolean constructor: " constructor)
                                               {:constructor constructor})))
      "Atomic_Reference" (cond
                           (= constructor "make") (str "__nexAtomicReference(" args-code ")")
                           :else (throw (ex-info (str "Unsupported built-in Atomic_Reference constructor: " constructor)
                                                 {:constructor constructor})))
      "Channel" (cond
                  (nil? constructor) "new __nexChannel()"
                  (= constructor "with_capacity") (str "new __nexChannel(" args-code ")")
                  :else (throw (ex-info (str "Unsupported built-in Channel constructor: " constructor)
                                        {:constructor constructor})))
      "Set" (if (= constructor "from_array")
              (str "new Set(" args-code ")")
              "new Set()")
      (if constructor
        ;; Named constructor: static factory method call
        (str "await " class-name "." constructor "(" args-code ")")
        ;; Default: new ClassName()
        (str "new " class-name "()")))))

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

(defn generate-set-literal
  "Generate JavaScript code for set literal"
  [elements]
  (str "new Set([" (str/join ", " (map generate-expression elements)) "])"))

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
    :array-literal (generate-array-literal (:elements expr))
    :set-literal (generate-set-literal (:elements expr))
    :map-literal (generate-map-literal (:entries expr))
    :convert (generate-convert-expr expr)
    :spawn (generate-spawn-expr expr)
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
  (if (and *spawn-result-flag* (= target "result"))
    (str target " = " (generate-expression value) "; " *spawn-result-flag* " = true;")
    (str target " = " (generate-expression value) ";")))

(defn generate-let
  "Generate JavaScript code for let (local variable declaration).
   If var-names is provided and contains the variable name, generates assignment instead."
  ([let-node] (generate-let let-node #{}))
  ([{:keys [name var-type value]} var-names]
   (if (contains? var-names name)
     ;; Variable already declared in outer scope - generate assignment
     (if (and *spawn-result-flag* (= name "result"))
       (str name " = " (generate-expression value) "; " *spawn-result-flag* " = true;")
       (str name " = " (generate-expression value) ";"))
     ;; New variable - generate declaration
     (let [stmt (str "let " name " = " (generate-expression value) ";")]
       (if (and *spawn-result-flag* (= name "result"))
         (str stmt " " *spawn-result-flag* " = true;")
         stmt)))))

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

(defn generate-rescue
  "Generate JavaScript try/catch with optional retry loop"
  [level body rescue var-names]
  (let [body-stmts (map #(generate-statement (+ level 2) % var-names) body)
        rescue-stmts (map #(generate-statement (+ level 2) % var-names) rescue)
        has-retry (has-retry? rescue)]
    (if has-retry
      ;; while(true) { try { body; break; } catch (...) { rescue; } }
      (str/join "\n"
        (concat
          [(indent level "while (true) {")]
          [(indent (+ level 1) "try {")]
          body-stmts
          [(indent (+ level 2) "break;")]
          [(indent (+ level 1) "} catch (_nex_e) {")]
          [(indent (+ level 2) "let exception = _nex_e;")]
          rescue-stmts
          [(indent (+ level 1) "}")]
          [(indent level "}")]))
      ;; try { body; } catch (...) { rescue; }
      (str/join "\n"
        (concat
          [(indent level "try {")]
          body-stmts
          [(indent level "} catch (_nex_e) {")]
          [(indent (+ level 1) "let exception = _nex_e;")]
          rescue-stmts
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

(defn- select-clause-call
  [clause]
  (:expr clause))

(defn- generate-select-clause-js
  [level clause var-names idx]
  (let [{:keys [target method args]} (select-clause-call clause)
        target-code (if (string? target) target (generate-expression target))
        arg-code (when (seq args) (generate-expression (first args)))
        body-var-names (cond-> var-names (:alias clause) (conj (:alias clause)))
        body-code (str/join "\n" (map #(generate-statement (+ level 3) % body-var-names) (:body clause)))
        temp-name (str "__selectValue" idx)]
    (cond
      (= method "receive")
      (str/join "\n"
                (concat
                 [(indent (+ level 2) "{")
                  (indent (+ level 3) (str "const " temp-name " = " target-code ".try_receive();"))
                  (indent (+ level 3) (str "if (" temp-name " !== null && " temp-name " !== undefined) {"))]
                 (when (:alias clause)
                   [(indent (+ level 4) (str "let " (:alias clause) " = " temp-name ";"))])
                 [body-code
                  (indent (+ level 4) "break;")
                  (indent (+ level 3) "}")
                  (indent (+ level 2) "}")]))

      (= method "try_receive")
      (str/join "\n"
                (concat
                 [(indent (+ level 2) "{")
                  (indent (+ level 3) (str "const " temp-name " = " target-code ".try_receive();"))
                  (indent (+ level 3) (str "if (" temp-name " !== null && " temp-name " !== undefined) {"))]
                 (when (:alias clause)
                   [(indent (+ level 4) (str "let " (:alias clause) " = " temp-name ";"))])
                 [body-code
                  (indent (+ level 4) "break;")
                  (indent (+ level 3) "}")
                  (indent (+ level 2) "}")]))

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
      (str/join "\n"
                (concat
                 [(indent (+ level 2) (str "if (" target-code ".is_done()) {"))]
                 (when (:alias clause)
                   [(indent (+ level 3) (str "const " temp-name " = await " target-code ".await();"))
                    (indent (+ level 3) (str "let " (:alias clause) " = " temp-name ";"))])
                 (when-not (:alias clause)
                   [(indent (+ level 3) (str "await " target-code ".await();"))])
                 [body-code
                  (indent (+ level 3) "break;")
                  (indent (+ level 2) "}")]))

      :else
      (indent (+ level 2) "/* Unsupported select clause */"))))

(defn generate-select
  [level {:keys [clauses else timeout]} var-names]
  (let [clause-code (map-indexed (fn [idx clause]
                                   (generate-select-clause-js level clause var-names idx))
                                 clauses)
        else-code (when else
                    [(str/join "\n" (map #(generate-statement (+ level 2) % var-names) else))
                     (indent (+ level 2) "break;")])
        timeout-code (when timeout
                       [(indent (+ level 1) (str "const __selectDeadline = Date.now() + " (generate-expression (:duration timeout)) ";"))])
        timeout-body (when timeout
                       [(indent (+ level 1) "if (Date.now() >= __selectDeadline) {")
                        (str/join "\n" (map #(generate-statement (+ level 2) % var-names) (:body timeout)))
                        (indent (+ level 2) "break;")
                        (indent (+ level 1) "}")]
                       )]
    (str/join "\n"
              (concat
               (when timeout [(indent level "{")])
               timeout-code
               [(indent level "while (true) {")]
               clause-code
               else-code
               (when-not else
                 (concat
                  timeout-body
                  [(indent (+ level 1) "await __nexSleep(0);")]))
               [(indent level "}")]
               (when timeout [(indent level "}")])))))

(defn generate-statement
  "Generate JavaScript code for a statement"
  ([level stmt] (generate-statement level stmt #{}))
  ([level stmt var-names]
   (case (:type stmt)
     :assign (indent level (generate-assignment stmt))
     :call (indent level (str (generate-call-expr stmt) ";"))
     :spawn (indent level (str (generate-expression stmt) ";"))
     :let (indent level (generate-let stmt var-names))
     :if (generate-if level stmt var-names)
     :case (generate-case level stmt var-names)
     :select (generate-select level stmt var-names)
     :scoped-block (generate-scoped-block level stmt var-names)
     :loop (generate-loop level stmt)
     :with (when (= (:target stmt) "javascript")
             ;; Only include this block if target is "javascript"
             (str/join "\n" (map #(generate-statement level % var-names) (:body stmt))))
     :raise (indent level (str "throw " (generate-expression (:value stmt)) ";"))
     :retry (indent level "continue;")
     :member-assign (let [target-expr (or (:object stmt) {:type :this})]
                      (indent level
                              (str (generate-expression target-expr) "." (:field stmt)
                                   " = " (generate-expression (:value stmt)) ";")))
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

(defn combine-precondition-groups
  "Combine inherited and local preconditions by OR-ing assertion groups, where
   each group's assertions are AND-ed together."
  [inherited-groups local-assertions]
  (let [groups (vec (concat (keep seq inherited-groups)
                            (when (seq local-assertions)
                              [(vec local-assertions)])))]
    (cond
      (empty? groups)
      nil

      (= 1 (count groups))
      (vec (first groups))

      :else
      [{:label "inherited_or_local_require"
        :condition (reduce (fn [acc group]
                             (let [group-condition (assertions->condition group)]
                               (if acc
                                 {:type :binary
                                  :operator "or"
                                  :left acc
                                  :right group-condition}
                                 group-condition)))
                           nil
                           groups)}])))

(defn combine-preconditions
  "Combine inherited and local preconditions as:
   (base-require) OR (local-require)."
  [base-assertions local-assertions]
  (combine-precondition-groups [base-assertions] local-assertions))

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
  [level {:keys [name params return-type body require ensure rescue visibility note declaration-only?]} opts]
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
            statements (cond
                         declaration-only?
                         [(indent (+ level 1)
                                  (str "throw new Error(\"Function or method declared but not defined: "
                                       name "\");"))]
                         rescue
                         [(generate-rescue (+ level 1) body rescue #{})]
                         :else
                         (map #(generate-statement (+ level 1) %) body))
            postconditions (generate-assertions (+ level 1) ensure "Postcondition" opts)
            class-invariant-checks (generate-assertions (+ level 1) *class-invariants* "Class invariant" opts)
            ;; Add return statement if method has return type
            return-stmt (when (and return-type (not declaration-only?))
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
                   [(indent level (str "async " method-name "(" params-code ") {"))]
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

(defn generate-class-constant-js
  [level class-name {:keys [name value]}]
  (indent level (str "static " name " = " (generate-expression value) ";")))

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
        param-types (into {} (map (juxt :name :type) params))
        convert-bindings (collect-convert-bindings-block (concat body (or rescue [])))
        convert-var-names (set (map :name convert-bindings))
        convert-var-types (into {} (map (juxt :name :target-type) convert-bindings))
        local-types (merge param-types
                           convert-var-types
                           (extract-typed-locals body)
                           (extract-typed-locals rescue))
        local-names (into param-names convert-var-names)
        params-code (str/join ", " (map :name params))
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
               [(indent level (str "static async " name "(" params-code ") {"))]
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
  (let [parents (vec (remove #(= "Any" (:parent %)) parents))]
    (if (empty? parents)
      {:extends nil}
      {:extends (:parent (first parents))})))

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

(defn get-parent-method-names
  "Get all method names from a parent class via the class registry (JS uses extends)"
  [parent-name classes-by-name]
  (when-let [parent-def (get classes-by-name parent-name)]
    (let [{:keys [methods]} (extract-members (:body parent-def))]
      (map :name methods))))

(defn get-parent-constants-js
  "Get recursively inherited public constants from a parent class."
  [parent-name classes-by-name]
  (when-let [parent-def (get classes-by-name parent-name)]
    (let [{:keys [constants]} (extract-members (:body parent-def))
          inherited (mapcat (fn [{:keys [parent]}]
                              (or (get-parent-constants-js parent classes-by-name) []))
                            (:parents parent-def))]
      (vals
       (reduce (fn [m constant]
                 (if (contains? m (:name constant))
                   m
                   (assoc m (:name constant) constant)))
               {}
               (concat (filter public-member? inherited)
                       (filter public-member? constants)))))))

(defn get-accessible-constants-js
  "Get inherited public constants plus local constants, with local override by name."
  [class-def classes-by-name]
  (let [{:keys [constants]} (extract-members (:body class-def))
        inherited (mapcat (fn [{:keys [parent]}]
                            (or (get-parent-constants-js parent classes-by-name) []))
                          (:parents class-def))]
    (vals
     (reduce (fn [m constant]
               (assoc m (:name constant) constant))
             {}
             (concat inherited constants)))))

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

(defn collect-inherited-method-contract-sources-js
  [class-def method-name classes-by-name]
  (letfn [(collect [cls seen]
            (let [class-name (:name cls)
                  already-seen? (and class-name (contains? seen class-name))
                  seen' (if class-name (conj seen class-name) seen)]
              (if already-seen?
                [[] seen]
                (let [[parent-sources seen'']
                      (if-let [parents (:parents cls)]
                        (reduce (fn [[acc seen-so-far] {:keys [parent]}]
                                  (if-let [parent-def (get classes-by-name parent)]
                                    (let [[sources seen-next] (collect parent-def seen-so-far)]
                                      [(into acc sources) seen-next])
                                    [acc seen-so-far]))
                                [[] seen']
                                parents)
                        [[] seen'])
                      {local-methods :methods} (extract-members (:body cls))
                      local-method (first (filter #(= (:name %) method-name) local-methods))
                      local-source (when (and local-method
                                              (public-member? local-method))
                                     [{:method local-method
                                       :source-class cls}])]
                  [(vec (concat parent-sources local-source)) seen'']))))]
    (if-let [parents (:parents class-def)]
      (first (reduce (fn [[acc seen] {:keys [parent]}]
                       (if-let [parent-def (get classes-by-name parent)]
                         (let [[sources seen'] (collect parent-def seen)]
                           [(into acc sources) seen'])
                         [acc seen]))
                     [[] #{}]
                     parents))
      [])))

(defn lookup-method-effective-contracts
  "Lookup method in class hierarchy and compute effective contracts:
   require = base OR local
   ensure = base AND local"
  [class-def method-name classes-by-name]
  (let [{:keys [methods]} (extract-members (:body class-def))
        local-method (first (filter #(= (:name %) method-name) methods))]
    (if local-method
      (let [inherited-sources (collect-inherited-method-contract-sources-js class-def
                                                                            method-name
                                                                            classes-by-name)
            effective-require (combine-precondition-groups
                               (mapv (fn [{:keys [method]}] (:require method))
                                     inherited-sources)
                               (:require local-method))
            effective-ensure (vec (concat (mapcat (fn [{:keys [method]}]
                                                    (or (:ensure method) []))
                                                  inherited-sources)
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
             runtime-parents (vec (remove #(= "Any" (:parent %)) parents))
             parent-names (mapv :parent runtime-parents)
             own-flds (set (map :name fields))
             all-constants (get-accessible-constants-js class-def classes-by-name)
             constant-names (set (map :name all-constants))
             own-method-names (set (map :name methods))
             all-methods (into own-method-names
                               (mapcat #(get-parent-method-names % classes-by-name)
                                       parent-names))
             fld-types (build-field-types-js fields parent-names classes-by-name)
             effective-invariants (collect-effective-class-invariants class-def classes-by-name)]
         (binding [*current-class-name* name
                   *class-registry* classes-by-name
                   *all-method-names* all-methods
                   *own-fields* own-flds
                   *constant-names* constant-names
                   *field-types* fld-types
                   *class-invariants* effective-invariants]
           (let [class-jsdoc (when note
                               [(generate-jsdoc 0 note)])
                 generic-comment (generate-generic-comment generic-params)
                 class-header (generate-class-header name generic-params runtime-parents)
                 invariant-comment (when (and (seq effective-invariants) (not (:skip-contracts opts)))
                                     (indent 1 (str "// Class invariant: "
                                                    (str/join ", " (map :label effective-invariants)))))
                 constants-code (map #(generate-class-constant-js 1 name %) all-constants)
                 has-parent? (some? (:extends (analyze-inheritance runtime-parents)))
                 default-constructor (generate-default-constructor 1 name fields has-parent? deferred?)
                 factory-methods (map #(generate-factory-constructor 1 name % opts) constructors)
                 inherited-constructor-shims (when (seq runtime-parents)
                                               (generate-inherited-constructor-shims-js 1 name runtime-parents (set (map :name constructors)) classes-by-name))
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
                        constants-code
                        (when (seq all-constants) [""])
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
(defn generate-runtime-helpers
  "Generate JavaScript runtime helper functions for built-in operations."
  []
  (str "function __nexTypeOf(v) {\n"
       "  if (v === null || v === undefined) return 'Nil';\n"
       "  if (typeof v === 'string') return (v.length === 1 ? 'Char' : 'String');\n"
       "  if (typeof v === 'boolean') return 'Boolean';\n"
       "  if (typeof v === 'number') return Number.isInteger(v) ? 'Integer' : 'Real';\n"
       "  if (Array.isArray(v)) return 'Array';\n"
       "  if (v instanceof Map) return 'Map';\n"
       "  if (v instanceof Set) return 'Set';\n"
       "  if (v && v._type) return String(v._type);\n"
       "  const ctor = v && v.constructor;\n"
       "  if (ctor && ctor.name) return ctor.name;\n"
       "  return 'Any';\n"
       "}\n"
       "function __nexToString(v) {\n"
       "  if (v === null || v === undefined) return 'nil';\n"
       "  if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') return String(v);\n"
       "  if (__nexTypeOf(v) === 'Char') return String(v);\n"
       "  if (Array.isArray(v)) return '[' + v.map(__nexToString).join(', ') + ']';\n"
       "  if (v instanceof Map) return '{' + Array.from(v.entries()).map(([k, val]) => __nexToString(k) + ': ' + __nexToString(val)).join(', ') + '}';\n"
       "  if (v instanceof Set) return '#{' + Array.from(v.values()).map(__nexToString).join(', ') + '}';\n"
       "  return `#<${__nexTypeOf(v)} object>`;\n"
       "}\n"
       "function __nexToConcatString(v) {\n"
       "  if (typeof v === 'string') return v;\n"
       "  if (v !== null && v !== undefined && typeof v.to_string === 'function') {\n"
       "    const out = v.to_string();\n"
       "    return typeof out === 'string' ? out : __nexToString(out);\n"
       "  }\n"
       "  return __nexToString(v);\n"
       "}\n"
       "function __nexPrintValue(v) {\n"
       "  if (v !== null && v !== undefined && typeof v.to_string === 'function') {\n"
       "    const out = v.to_string();\n"
       "    return typeof out === 'string' ? out : __nexToString(out);\n"
       "  }\n"
       "  return v;\n"
       "}\n"
       "function __nexConcat(a, b) {\n"
       "  return __nexToConcatString(a) + __nexToConcatString(b);\n"
       "}\n"
       "function __nexDeepEquals(a, b) {\n"
       "  if (a === b) return true;\n"
       "  if (a == null || b == null) return false;\n"
       "  if (typeof a === 'string' || typeof a === 'number' || typeof a === 'boolean') return a === b;\n"
       "  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => __nexDeepEquals(v, b[i]));\n"
       "  if (a instanceof Map && b instanceof Map) {\n"
       "    if (a.size !== b.size) return false;\n"
       "    outer: for (const [ka, va] of a.entries()) {\n"
       "      for (const [kb, vb] of b.entries()) {\n"
       "        if (__nexDeepEquals(ka, kb) && __nexDeepEquals(va, vb)) continue outer;\n"
       "      }\n"
       "      return false;\n"
       "    }\n"
       "    return true;\n"
       "  }\n"
       "  if (a instanceof Set && b instanceof Set) {\n"
       "    if (a.size !== b.size) return false;\n"
       "    outer: for (const va of a.values()) {\n"
       "      for (const vb of b.values()) {\n"
       "        if (__nexDeepEquals(va, vb)) continue outer;\n"
       "      }\n"
       "      return false;\n"
       "    }\n"
       "    return true;\n"
       "  }\n"
       "  if (typeof a === 'object' && typeof b === 'object' && a.constructor === b.constructor) {\n"
       "    const aKeys = Object.keys(a);\n"
       "    const bKeys = Object.keys(b);\n"
       "    if (aKeys.length !== bKeys.length) return false;\n"
       "    for (const key of aKeys) {\n"
       "      if (!Object.prototype.hasOwnProperty.call(b, key)) return false;\n"
       "      if (!__nexDeepEquals(a[key], b[key])) return false;\n"
       "    }\n"
       "    return true;\n"
       "  }\n"
       "  return a === b;\n"
       "}\n"
       "function __nexArrayContains(values, needle) {\n"
       "  return values.some(v => __nexDeepEquals(v, needle));\n"
       "}\n"
       "function __nexArrayIndexOf(values, needle) {\n"
       "  for (let i = 0; i < values.length; i++) if (__nexDeepEquals(values[i], needle)) return i;\n"
       "  return -1;\n"
       "}\n"
       "function __nexMapContainsKey(values, needle) {\n"
       "  for (const key of values.keys()) if (__nexDeepEquals(key, needle)) return true;\n"
       "  return false;\n"
       "}\n"
       "function __nexSetContains(values, needle) {\n"
       "  for (const value of values.values()) if (__nexDeepEquals(value, needle)) return true;\n"
       "  return false;\n"
       "}\n"
       "function __nexCompareValues(a, b) {\n"
       "  if (a === b) return 0;\n"
       "  if (a === null || a === undefined || b === null || b === undefined) throw new Error('Array.sort cannot compare null values');\n"
       "  const ta = typeof a;\n"
       "  const tb = typeof b;\n"
       "  if (ta === 'number' && tb === 'number') return a < b ? -1 : (a > b ? 1 : 0);\n"
       "  if (ta === 'string' && tb === 'string') return a < b ? -1 : (a > b ? 1 : 0);\n"
       "  if (ta === 'boolean' && tb === 'boolean') return a === b ? 0 : (a ? 1 : -1);\n"
       "  if (a && typeof a.compare === 'function') return a.compare(b);\n"
       "  throw new Error('Array.sort requires Comparable elements');\n"
       "}\n"
       "function __nexArraySort(values, compareFn = null) {\n"
       "  if (!compareFn) return [...values].sort((a, b) => __nexCompareValues(a, b));\n"
       "  return [...values].sort((a, b) => {\n"
       "    const result = (typeof compareFn === 'function') ? compareFn(a, b) : compareFn.call2(a, b);\n"
       "    if (!Number.isInteger(result)) throw new Error('Array.sort comparator must return Integer');\n"
       "    return result;\n"
       "  });\n"
       "}\n"
       "function __nexMinHeap(compare = null) {\n"
       "  return { _type: 'MinHeap', data: [], compare };\n"
       "}\n"
       "function __nexMinHeapCompare(heap, a, b) {\n"
       "  if (!heap.compare) return __nexCompareValues(a, b);\n"
       "  const result = (typeof heap.compare === 'function') ? heap.compare(a, b) : heap.compare.call2(a, b);\n"
       "  if (!Number.isInteger(result)) throw new Error('Min_Heap comparator must return Integer');\n"
       "  return result;\n"
       "}\n"
       "function __nexMinHeapSiftUp(heap, index) {\n"
       "  while (index > 0) {\n"
       "    const parent = Math.floor((index - 1) / 2);\n"
       "    if (__nexMinHeapCompare(heap, heap.data[index], heap.data[parent]) >= 0) break;\n"
       "    const tmp = heap.data[index];\n"
       "    heap.data[index] = heap.data[parent];\n"
       "    heap.data[parent] = tmp;\n"
       "    index = parent;\n"
       "  }\n"
       "}\n"
       "function __nexMinHeapSiftDown(heap, index) {\n"
       "  while (true) {\n"
       "    const left = index * 2 + 1;\n"
       "    const right = left + 1;\n"
       "    let smallest = index;\n"
       "    if (left < heap.data.length && __nexMinHeapCompare(heap, heap.data[left], heap.data[smallest]) < 0) smallest = left;\n"
       "    if (right < heap.data.length && __nexMinHeapCompare(heap, heap.data[right], heap.data[smallest]) < 0) smallest = right;\n"
       "    if (smallest === index) break;\n"
       "    const tmp = heap.data[index];\n"
       "    heap.data[index] = heap.data[smallest];\n"
       "    heap.data[smallest] = tmp;\n"
       "    index = smallest;\n"
       "  }\n"
       "}\n"
       "function __nexMinHeapInsert(heap, value) {\n"
       "  heap.data.push(value);\n"
       "  __nexMinHeapSiftUp(heap, heap.data.length - 1);\n"
       "}\n"
       "function __nexMinHeapTryPeek(heap) {\n"
       "  return heap.data.length === 0 ? null : heap.data[0];\n"
       "}\n"
       "function __nexMinHeapPeek(heap) {\n"
       "  if (heap.data.length === 0) throw new Error('Min_Heap is empty');\n"
       "  return heap.data[0];\n"
       "}\n"
       "function __nexMinHeapTryExtractMin(heap) {\n"
       "  if (heap.data.length === 0) return null;\n"
       "  const min = heap.data[0];\n"
       "  const last = heap.data.pop();\n"
       "  if (heap.data.length > 0) {\n"
       "    heap.data[0] = last;\n"
       "    __nexMinHeapSiftDown(heap, 0);\n"
       "  }\n"
       "  return min;\n"
       "}\n"
       "function __nexMinHeapExtractMin(heap) {\n"
       "  const value = __nexMinHeapTryExtractMin(heap);\n"
       "  if (value === null || value === undefined) throw new Error('Min_Heap is empty');\n"
       "  return value;\n"
       "}\n"
       "function __nexAtomicInteger(initial) { return { _type: 'Atomic_Integer', value: initial | 0 }; }\n"
       "function __nexAtomicInteger64(initial) { return { _type: 'Atomic_Integer64', value: Number(initial) }; }\n"
       "function __nexAtomicBoolean(initial) { return { _type: 'Atomic_Boolean', value: !!initial }; }\n"
       "function __nexAtomicReference(initial) { return { _type: 'Atomic_Reference', value: initial ?? null }; }\n"
       "function __nexAtomicLoad(cell) { return cell.value; }\n"
       "function __nexAtomicStore(cell, value) { cell.value = value; }\n"
       "function __nexAtomicCompareAndSet(cell, expected, update) {\n"
       "  if (__nexDeepEquals(cell.value, expected)) { cell.value = update; return true; }\n"
       "  return false;\n"
       "}\n"
       "function __nexAtomicGetAndAdd(cell, delta) {\n"
       "  const current = cell.value;\n"
       "  cell.value += delta;\n"
       "  return current;\n"
       "}\n"
       "function __nexAtomicAddAndGet(cell, delta) {\n"
       "  cell.value += delta;\n"
       "  return cell.value;\n"
       "}\n"
       "function __nexCloneValue(v) {\n"
       "  if (v === null || v === undefined || typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') return v;\n"
       "  if (Array.isArray(v)) return v.slice();\n"
       "  if (v instanceof Map) return new Map(v);\n"
       "  if (v instanceof Set) return new Set(v);\n"
       "  return Object.assign(Object.create(Object.getPrototypeOf(v)), v);\n"
       "}\n"
       "function __nexDeepClone(v) {\n"
       "  if (v === null || v === undefined || typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') return v;\n"
       "  if (Array.isArray(v)) return v.map(__nexDeepClone);\n"
       "  if (v instanceof Map) return new Map(Array.from(v.entries()).map(([k, val]) => [__nexDeepClone(k), __nexDeepClone(val)]));\n"
       "  if (v instanceof Set) return new Set(Array.from(v.values()).map(__nexDeepClone));\n"
       "  return __nexCloneValue(v);\n"
       "}\n"
       "function __nexIntDiv(a, b) {\n"
       "  if (b === 0) throw new Error('Division by zero');\n"
       "  return Math.trunc(a / b);\n"
       "}\n"
       "function __nexParseLong(raw) {\n"
       "  const trimmed = String(raw).trim();\n"
       "  const negative = trimmed.startsWith('-');\n"
       "  const unsigned = (negative ? trimmed.slice(1) : trimmed).replace(/_/g, '');\n"
       "  let radix = 10;\n"
       "  let digits = unsigned;\n"
       "  if (unsigned.startsWith('0b')) {\n"
       "    radix = 2;\n"
       "    digits = unsigned.slice(2);\n"
       "  } else if (unsigned.startsWith('0o')) {\n"
       "    radix = 8;\n"
       "    digits = unsigned.slice(2);\n"
       "  } else if (unsigned.startsWith('0x')) {\n"
       "    radix = 16;\n"
       "    digits = unsigned.slice(2);\n"
       "  }\n"
       "  const parsed = parseInt(digits, radix);\n"
       "  return negative ? -parsed : parsed;\n"
       "}\n"
       "function __nexParseInt(raw) {\n"
       "  return __nexParseLong(raw);\n"
       "}\n"
       "function __nexStringToBytes(text) {\n"
       "  return Array.from(new TextEncoder().encode(String(text)));\n"
       "}\n"
       "function __nexJsonToNex(value) {\n"
       "  if (value === null || value === undefined) return null;\n"
       "  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return value;\n"
       "  if (Array.isArray(value)) return value.map(__nexJsonToNex);\n"
       "  if (value instanceof Map) return new Map(Array.from(value.entries()).map(([k, v]) => [String(k), __nexJsonToNex(v)]));\n"
       "  const out = new Map();\n"
       "  for (const [k, v] of Object.entries(value)) out.set(String(k), __nexJsonToNex(v));\n"
       "  return out;\n"
       "}\n"
       "function __nexValueToJson(value) {\n"
       "  if (value === null || value === undefined || typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return value;\n"
       "  if (Array.isArray(value)) return value.map(__nexValueToJson);\n"
       "  if (value instanceof Map) {\n"
       "    const out = {};\n"
       "    for (const [k, v] of value.entries()) out[String(k)] = __nexValueToJson(v);\n"
       "    return out;\n"
       "  }\n"
       "  if (value instanceof Set) return Array.from(value.values()).map(__nexValueToJson);\n"
       "  throw new Error('Value is not JSON-serializable');\n"
       "}\n"
       "function __nexJsonParse(text) {\n"
       "  return __nexJsonToNex(JSON.parse(String(text)));\n"
       "}\n"
       "function __nexJsonStringify(value) {\n"
       "  return JSON.stringify(__nexValueToJson(value));\n"
       "}\n"
       "function __nexIntPow(a, b) {\n"
       "  if (b < 0) throw new Error('Integral exponentiation requires a non-negative exponent');\n"
       "  let acc = 1;\n"
       "  let base = a;\n"
       "  let exp = b;\n"
       "  while (exp > 0) {\n"
       "    if ((exp & 1) === 1) acc *= base;\n"
       "    base *= base;\n"
       "    exp = Math.trunc(exp / 2);\n"
       "  }\n"
       "  return acc;\n"
       "}\n"
       "function __nexTypeIs(typeName, v) {\n"
       "  if (typeof typeName !== 'string') return false;\n"
       "  const runtime = __nexTypeOf(v);\n"
       "  if (typeName === 'Any') return true;\n"
       "  if (runtime === typeName) return true;\n"
       "  if (runtime === 'Integer' && (typeName === 'Integer64' || typeName === 'Real' || typeName === 'Decimal')) return true;\n"
       "  if (runtime === 'Integer64' && (typeName === 'Real' || typeName === 'Decimal')) return true;\n"
       "  if (runtime === 'Real' && typeName === 'Decimal') return true;\n"
       "  if ((runtime === 'ArrayCursor' || runtime === 'StringCursor' || runtime === 'MapCursor' || runtime === 'SetCursor') && typeName === 'Cursor') return true;\n"
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
       "function __nexSleep(ms) {\n"
       "  return new Promise(resolve => setTimeout(resolve, ms));\n"
       "}\n"
       "function __nexHintSpin() {\n"
       "  return null;\n"
       "}\n"
       "function __nexRegexFlags(flags) {\n"
       "  let out = 'g';\n"
       "  if (flags && flags.includes('i')) out += 'i';\n"
       "  if (flags && flags.includes('m')) out += 'm';\n"
       "  return out;\n"
       "}\n"
       "function __nexRegex(pattern, flags) {\n"
       "  return new RegExp(pattern, __nexRegexFlags(flags));\n"
       "}\n"
       "function __nexRegexValidate(pattern, flags) { __nexRegex(pattern, flags); return true; }\n"
       "function __nexRegexMatches(pattern, flags, text) {\n"
       "  const anchored = new RegExp('^(?:' + pattern + ')$', __nexRegexFlags(flags).replace('g', ''));\n"
       "  return anchored.test(String(text));\n"
       "}\n"
       "function __nexRegexFind(pattern, flags, text) {\n"
       "  const m = String(text).match(__nexRegex(pattern, flags));\n"
       "  return m && m.length > 0 ? m[0] : null;\n"
       "}\n"
       "function __nexRegexFindAll(pattern, flags, text) {\n"
       "  const m = String(text).match(__nexRegex(pattern, flags));\n"
       "  return m ? Array.from(m) : [];\n"
       "}\n"
       "function __nexRegexReplace(pattern, flags, text, replacement) {\n"
       "  return String(text).replace(__nexRegex(pattern, flags), String(replacement));\n"
       "}\n"
       "function __nexRegexSplit(pattern, flags, text) {\n"
       "  return String(text).split(__nexRegex(pattern, flags));\n"
       "}\n"
       "function __nexDateTimeNow() { return Date.now(); }\n"
       "function __nexDateTimeFromEpochMillis(ms) { return Number(ms); }\n"
       "function __nexDateTimeParseIso(text) { return Date.parse(String(text)); }\n"
       "function __nexDateTimeMake(year, month, day, hour, minute, second) { return Date.UTC(year, month - 1, day, hour, minute, second); }\n"
       "function __nexDateTimeYear(epochMs) { return new Date(epochMs).getUTCFullYear(); }\n"
       "function __nexDateTimeMonth(epochMs) { return new Date(epochMs).getUTCMonth() + 1; }\n"
       "function __nexDateTimeDay(epochMs) { return new Date(epochMs).getUTCDate(); }\n"
       "function __nexDateTimeWeekday(epochMs) { const d = new Date(epochMs).getUTCDay(); return d === 0 ? 7 : d; }\n"
       "function __nexDateTimeDayOfYear(epochMs) { const d = new Date(epochMs); const start = Date.UTC(d.getUTCFullYear(), 0, 1); return Math.floor((epochMs - start) / 86400000) + 1; }\n"
       "function __nexDateTimeHour(epochMs) { return new Date(epochMs).getUTCHours(); }\n"
       "function __nexDateTimeMinute(epochMs) { return new Date(epochMs).getUTCMinutes(); }\n"
       "function __nexDateTimeSecond(epochMs) { return new Date(epochMs).getUTCSeconds(); }\n"
       "function __nexDateTimeEpochMillis(epochMs) { return Number(epochMs); }\n"
       "function __nexDateTimeAddMillis(epochMs, deltaMs) { return Number(epochMs) + Number(deltaMs); }\n"
       "function __nexDateTimeDiffMillis(leftMs, rightMs) { return Number(leftMs) - Number(rightMs); }\n"
       "function __nexDateTimeTruncateToDay(epochMs) { const d = new Date(epochMs); return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), 0, 0, 0, 0); }\n"
       "function __nexDateTimeTruncateToHour(epochMs) { const d = new Date(epochMs); return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), d.getUTCHours(), 0, 0, 0); }\n"
       "function __nexDateTimeFormatIso(epochMs) { return new Date(epochMs).toISOString(); }\n"
       "function __nexPathFs() {\n"
       "  return require('fs');\n"
       "}\n"
       "function __nexPathMod() {\n"
       "  return require('path');\n"
       "}\n"
       "function __nexPathExists(path) {\n"
       "  return __nexPathFs().existsSync(path);\n"
       "}\n"
       "function __nexPathIsFile(path) {\n"
       "  const fs = __nexPathFs();\n"
       "  return fs.existsSync(path) && fs.statSync(path).isFile();\n"
       "}\n"
       "function __nexPathIsDirectory(path) {\n"
       "  const fs = __nexPathFs();\n"
       "  return fs.existsSync(path) && fs.statSync(path).isDirectory();\n"
       "}\n"
       "function __nexPathName(path) {\n"
       "  return __nexPathMod().basename(path);\n"
       "}\n"
       "function __nexPathExtension(path) {\n"
       "  const name = __nexPathName(path);\n"
       "  const dot = name.lastIndexOf('.');\n"
       "  return (dot <= 0 || dot === name.length - 1) ? \"\" : name.substring(dot + 1);\n"
       "}\n"
       "function __nexPathNameWithoutExtension(path) {\n"
       "  const name = __nexPathName(path);\n"
       "  const dot = name.lastIndexOf('.');\n"
       "  return dot <= 0 ? name : name.substring(0, dot);\n"
       "}\n"
       "function __nexPathAbsolute(path) {\n"
       "  return __nexPathMod().resolve(path);\n"
       "}\n"
       "function __nexPathNormalize(path) {\n"
       "  return __nexPathMod().normalize(path);\n"
       "}\n"
       "function __nexPathSize(path) {\n"
       "  const fs = __nexPathFs();\n"
       "  return fs.existsSync(path) ? fs.statSync(path).size : 0;\n"
       "}\n"
       "function __nexPathModifiedTime(path) {\n"
       "  const fs = __nexPathFs();\n"
       "  return fs.existsSync(path) ? fs.statSync(path).mtimeMs : 0;\n"
       "}\n"
       "function __nexPathParent(path) {\n"
       "  const parent = __nexPathMod().dirname(path);\n"
       "  return parent === path ? null : parent;\n"
       "}\n"
       "function __nexPathChild(path, childName) {\n"
       "  return __nexPathMod().join(path, childName);\n"
       "}\n"
       "function __nexPathCreateFile(path) {\n"
       "  const fs = __nexPathFs();\n"
       "  if (!fs.existsSync(path)) fs.writeFileSync(path, '', 'utf8');\n"
       "  return null;\n"
       "}\n"
       "function __nexPathCreateDirectory(path) {\n"
       "  __nexPathFs().mkdirSync(path, {recursive: false});\n"
       "  return null;\n"
       "}\n"
       "function __nexPathCreateDirectories(path) {\n"
       "  __nexPathFs().mkdirSync(path, {recursive: true});\n"
       "  return null;\n"
       "}\n"
       "function __nexPathDelete(path) {\n"
       "  const fs = __nexPathFs();\n"
       "  if (!fs.existsSync(path)) return null;\n"
       "  if (fs.statSync(path).isDirectory()) throw new Error('path_delete does not remove directories');\n"
       "  fs.unlinkSync(path);\n"
       "  return null;\n"
       "}\n"
       "function __nexPathDeleteTree(path) {\n"
       "  const fs = __nexPathFs();\n"
       "  if (fs.existsSync(path)) fs.rmSync(path, {recursive: true, force: true});\n"
       "  return null;\n"
       "}\n"
       "function __nexPathCopy(src, dst) {\n"
       "  const fs = __nexPathFs();\n"
       "  const pathMod = __nexPathMod();\n"
       "  const copy = (source, target) => {\n"
       "    const stat = fs.statSync(source);\n"
       "    if (stat.isDirectory()) {\n"
       "      fs.mkdirSync(target, {recursive: true});\n"
       "      for (const name of fs.readdirSync(source)) copy(pathMod.join(source, name), pathMod.join(target, name));\n"
       "    } else {\n"
       "      fs.mkdirSync(pathMod.dirname(target), {recursive: true});\n"
       "      fs.copyFileSync(source, target);\n"
       "    }\n"
       "  };\n"
       "  copy(src, dst);\n"
       "  return null;\n"
       "}\n"
       "function __nexPathMove(src, dst) {\n"
       "  const fs = __nexPathFs();\n"
       "  const pathMod = __nexPathMod();\n"
       "  fs.mkdirSync(pathMod.dirname(dst), {recursive: true});\n"
       "  try {\n"
       "    fs.renameSync(src, dst);\n"
       "  } catch (err) {\n"
       "    __nexPathCopy(src, dst);\n"
       "    __nexPathDeleteTree(src);\n"
       "  }\n"
       "  return null;\n"
       "}\n"
       "function __nexPathReadText(path) {\n"
       "  return __nexPathFs().readFileSync(path, 'utf8').toString();\n"
       "}\n"
       "function __nexPathWriteText(path, text) {\n"
       "  __nexPathFs().writeFileSync(path, String(text), 'utf8');\n"
       "  return null;\n"
       "}\n"
       "function __nexPathAppendText(path, text) {\n"
       "  __nexPathFs().appendFileSync(path, String(text), 'utf8');\n"
       "  return null;\n"
       "}\n"
       "function __nexPathList(path) {\n"
       "  const fs = __nexPathFs();\n"
       "  const pathMod = __nexPathMod();\n"
       "  return fs.readdirSync(path).map(name => pathMod.join(path, name));\n"
       "}\n"
       "function __nexTextFileOpenRead(path) {\n"
       "  const content = __nexPathFs().readFileSync(path, 'utf8').toString();\n"
       "  return {_type: 'TextFileHandle', mode: 'read', lines: content.split(/\\r?\\n/), index: 0, path};\n"
       "}\n"
       "function __nexTextFileOpenWrite(path) {\n"
       "  __nexPathFs().writeFileSync(path, '', 'utf8');\n"
       "  return {_type: 'TextFileHandle', mode: 'write', path};\n"
       "}\n"
       "function __nexTextFileOpenAppend(path) {\n"
       "  return {_type: 'TextFileHandle', mode: 'append', path};\n"
       "}\n"
       "function __nexTextFileReadLine(handle) {\n"
       "  if (handle.index >= handle.lines.length) return null;\n"
       "  const line = handle.lines[handle.index];\n"
       "  handle.index += 1;\n"
       "  return line;\n"
       "}\n"
       "function __nexTextFileWrite(handle, text) {\n"
       "  __nexPathFs().appendFileSync(handle.path, String(text), 'utf8');\n"
       "  return null;\n"
       "}\n"
       "function __nexTextFileClose(handle) {\n"
       "  return null;\n"
       "}\n"
       "function __nexBytesToIntArray(buf) {\n"
       "  return Array.from(buf.values());\n"
       "}\n"
       "function __nexIntArrayToBuffer(values) {\n"
       "  return Buffer.from(values.map(v => {\n"
       "    if (v < 0 || v > 255) throw new Error('Binary byte values must be in range 0..255');\n"
       "    return v;\n"
       "  }));\n"
       "}\n"
       "function __nexBinaryFileFs() {\n"
       "  return __nexPathFs();\n"
       "}\n"
       "function __nexBinaryFileOpenRead(path) {\n"
       "  return {_type: 'BinaryFileHandle', mode: 'read', fd: __nexBinaryFileFs().openSync(path, 'r'), index: 0, path};\n"
       "}\n"
       "function __nexBinaryFileOpenWrite(path) {\n"
       "  return {_type: 'BinaryFileHandle', mode: 'write', fd: __nexBinaryFileFs().openSync(path, 'w+'), index: 0, path};\n"
       "}\n"
       "function __nexBinaryFileOpenAppend(path) {\n"
       "  const fs = __nexBinaryFileFs();\n"
       "  if (!fs.existsSync(path)) fs.writeFileSync(path, Buffer.alloc(0));\n"
       "  const fd = fs.openSync(path, 'r+');\n"
       "  const size = fs.fstatSync(fd).size;\n"
       "  return {_type: 'BinaryFileHandle', mode: 'append', fd, index: size, path};\n"
       "}\n"
       "function __nexBinaryFileReadAll(handle) {\n"
       "  const fs = __nexBinaryFileFs();\n"
       "  const size = fs.fstatSync(handle.fd).size;\n"
       "  const out = Buffer.alloc(size);\n"
       "  fs.readSync(handle.fd, out, 0, size, 0);\n"
       "  return __nexBytesToIntArray(out);\n"
       "}\n"
       "function __nexBinaryFileRead(handle, count) {\n"
       "  const fs = __nexBinaryFileFs();\n"
       "  const size = fs.fstatSync(handle.fd).size;\n"
       "  const bytesToRead = Math.max(0, Math.min(count, size - handle.index));\n"
       "  const out = Buffer.alloc(bytesToRead);\n"
       "  fs.readSync(handle.fd, out, 0, bytesToRead, handle.index);\n"
       "  handle.index += bytesToRead;\n"
       "  return __nexBytesToIntArray(out);\n"
       "}\n"
       "function __nexBinaryFileWrite(handle, values) {\n"
       "  const data = __nexIntArrayToBuffer(values);\n"
       "  __nexBinaryFileFs().writeSync(handle.fd, data, 0, data.length, handle.index);\n"
       "  handle.index += data.length;\n"
       "  return null;\n"
       "}\n"
       "function __nexBinaryFilePosition(handle) {\n"
       "  return handle.index;\n"
       "}\n"
       "function __nexBinaryFileSeek(handle, offset) {\n"
       "  if (offset < 0) throw new Error('binary file position must be non-negative');\n"
       "  handle.index = offset;\n"
       "  return null;\n"
       "}\n"
       "function __nexBinaryFileClose(handle) {\n"
       "  __nexBinaryFileFs().closeSync(handle.fd);\n"
       "  return null;\n"
       "}\n"
       "async function __nexHttpRequest(method, url, body_text = null, timeout_ms = null) {\n"
       "  let controller = null;\n"
       "  let timeoutId = null;\n"
       "  try {\n"
       "    const options = { method };\n"
       "    if (body_text !== null && body_text !== undefined) options.body = body_text;\n"
       "    if (timeout_ms !== null && timeout_ms !== undefined) {\n"
       "      controller = new AbortController();\n"
       "      options.signal = controller.signal;\n"
       "      timeoutId = setTimeout(() => controller.abort(), timeout_ms);\n"
       "    }\n"
       "    const response = await fetch(url, options);\n"
       "    const text = await response.text();\n"
       "    const headers = new Map(response.headers.entries());\n"
       "    return await Http_Response.make(response.status, text, headers);\n"
       "  } finally {\n"
       "    if (timeoutId !== null) clearTimeout(timeoutId);\n"
       "  }\n"
       "}\n"
       "async function __nexHttpGet(url, timeout_ms = null) {\n"
       "  return await __nexHttpRequest('GET', url, null, timeout_ms);\n"
       "}\n"
       "async function __nexHttpPost(url, body_text, timeout_ms = null) {\n"
       "  return await __nexHttpRequest('POST', url, body_text, timeout_ms);\n"
       "}\n"
       "function __nexHttpPathSegments(path) {\n"
       "  if (!path || path === '/') return [];\n"
       "  return path.split('/').filter(Boolean);\n"
       "}\n"
       "function __nexHttpUrlDecode(s) {\n"
       "  return decodeURIComponent(String(s ?? '').replace(/\\+/g, '%20'));\n"
       "}\n"
       "function __nexHttpParseQuery(query) {\n"
       "  const out = new Map();\n"
       "  if (!query) return out;\n"
       "  for (const part of String(query).split('&')) {\n"
       "    if (!part) continue;\n"
       "    const pieces = part.split('=', 2);\n"
       "    out.set(__nexHttpUrlDecode(pieces[0]), __nexHttpUrlDecode(pieces.length > 1 ? pieces[1] : ''));\n"
       "  }\n"
       "  return out;\n"
       "}\n"
       "function __nexHttpMatchRoute(pattern, path) {\n"
       "  const patternSegments = __nexHttpPathSegments(pattern);\n"
       "  const pathSegments = __nexHttpPathSegments(path);\n"
       "  const params = new Map();\n"
       "  let i = 0;\n"
       "  let j = 0;\n"
       "  while (i < patternSegments.length && j < pathSegments.length) {\n"
       "    const p = patternSegments[i];\n"
       "    const x = pathSegments[j];\n"
       "    if (p === '*') {\n"
       "      params.set('*', pathSegments.slice(j).join('/'));\n"
       "      return params;\n"
       "    }\n"
       "    if (p.startsWith(':')) {\n"
       "      params.set(p.slice(1), __nexHttpUrlDecode(x));\n"
       "      i += 1; j += 1;\n"
       "      continue;\n"
       "    }\n"
       "    if (p !== x) return null;\n"
       "    i += 1; j += 1;\n"
       "  }\n"
       "  if (i === patternSegments.length && j === pathSegments.length) return params;\n"
       "  if (i < patternSegments.length && patternSegments[i] === '*') {\n"
       "    params.set('*', pathSegments.slice(j).join('/'));\n"
       "    return params;\n"
       "  }\n"
       "  return null;\n"
       "}\n"
       "function __nexHttpFindRoute(handle, method, path) {\n"
       "  for (const route of (handle.routes[method] || [])) {\n"
       "    const params = __nexHttpMatchRoute(route.path, path);\n"
       "    if (params !== null) return {handler: route.handler, params};\n"
       "  }\n"
       "  return null;\n"
       "}\n"
       "function __nexHttpServerCreate(port) {\n"
       "  return {_type: 'HttpServerHandle', port, server: null, routes: {GET: [], POST: [], PUT: [], DELETE: []}};\n"
       "}\n"
       "function __nexHttpServerGet(handle, path, handler) {\n"
       "  handle.routes.GET.push({path, handler});\n"
       "  return null;\n"
       "}\n"
       "function __nexHttpServerPost(handle, path, handler) {\n"
       "  handle.routes.POST.push({path, handler});\n"
       "  return null;\n"
       "}\n"
       "function __nexHttpServerPut(handle, path, handler) {\n"
       "  handle.routes.PUT.push({path, handler});\n"
       "  return null;\n"
       "}\n"
       "function __nexHttpServerDelete(handle, path, handler) {\n"
       "  handle.routes.DELETE.push({path, handler});\n"
       "  return null;\n"
       "}\n"
       "function __nexReadRequestBody(req) {\n"
       "  return new Promise((resolve, reject) => {\n"
       "    let body = '';\n"
       "    req.on('data', chunk => { body += chunk; });\n"
       "    req.on('end', () => resolve(body));\n"
       "    req.on('error', reject);\n"
       "  });\n"
       "}\n"
       "async function __nexHttpServerStart(handle) {\n"
       "  const http = require('http');\n"
       "  handle.server = http.createServer(async (req, res) => {\n"
       "    const method = req.method || 'GET';\n"
       "    const url = new URL(req.url || '/', 'http://127.0.0.1');\n"
       "    const path = url.pathname;\n"
       "    const match = __nexHttpFindRoute(handle, method, path);\n"
       "    let response;\n"
       "    if (!match) {\n"
       "      response = await Http_Server_Response.with_status(404, 'Not Found');\n"
       "    } else {\n"
       "      const body = await __nexReadRequestBody(req);\n"
       "      const headers = new Map(Object.entries(req.headers || {}));\n"
       "      const request = await Http_Request.make(method, path, body, headers, match.params, __nexHttpParseQuery(url.search.length > 1 ? url.search.slice(1) : ''));\n"
       "      response = await match.handler.call1(request);\n"
       "      if (response === null || response === undefined) response = await Http_Server_Response.with_status(204, '');\n"
       "    }\n"
       "    const responseHeaders = await response.headers();\n"
       "    for (const [k, v] of responseHeaders.entries()) res.setHeader(String(k), String(v));\n"
       "    res.statusCode = await response.status();\n"
       "    res.end(await response.body());\n"
       "  });\n"
       "  return await new Promise(resolve => {\n"
       "    handle.server.listen(handle.port, '127.0.0.1', () => {\n"
       "      handle.port = handle.server.address().port;\n"
       "      resolve(handle.port);\n"
       "    });\n"
       "  });\n"
       "}\n"
       "async function __nexHttpServerStop(handle) {\n"
       "  if (!handle.server) return null;\n"
       "  const server = handle.server;\n"
       "  handle.server = null;\n"
       "  return await new Promise(resolve => server.close(() => resolve(null)));\n"
       "}\n"
       "function __nexHttpServerIsRunning(handle) {\n"
       "  return handle.server !== null;\n"
       "}\n"
       "class __nexTask {\n"
       "  constructor(promise) {\n"
       "    this._done = false;\n"
       "    this._cancelled = false;\n"
       "    this._cancelReject = null;\n"
       "    this._cancelPromise = new Promise((_, reject) => { this._cancelReject = reject; });\n"
       "    this._promise = Promise.race([Promise.resolve(promise), this._cancelPromise]).then(\n"
       "      value => { this._done = true; return value; },\n"
       "      err => { this._done = true; throw err; }\n"
       "    );\n"
       "  }\n"
       "  async await(timeoutMs) {\n"
       "    if (timeoutMs === undefined) return await this._promise;\n"
       "    return await Promise.race([\n"
       "      this._promise,\n"
       "      new Promise(resolve => setTimeout(() => resolve(null), timeoutMs))\n"
       "    ]);\n"
       "  }\n"
       "  cancel() {\n"
       "    if (this._done) return false;\n"
       "    this._cancelled = true;\n"
       "    this._done = true;\n"
       "    if (this._cancelReject) this._cancelReject(new Error('Task cancelled'));\n"
       "    return true;\n"
       "  }\n"
       "  is_done() { return this._done; }\n"
       "  is_cancelled() { return this._cancelled; }\n"
       "}\n"
       "function __nexSpawn(fn) {\n"
       "  return new __nexTask(Promise.resolve().then(fn));\n"
       "}\n"
       "async function __nexAwaitAll(tasks) {\n"
       "  const results = [];\n"
       "  for (const task of tasks) results.push(await task.await());\n"
       "  return results;\n"
       "}\n"
       "async function __nexAwaitAny(tasks) {\n"
       "  if (tasks.length === 0) throw new Error('await_any requires at least one task');\n"
       "  return await Promise.race(tasks.map(task => task.await()));\n"
       "}\n"
       "class __nexChannel {\n"
       "  constructor(capacity = 0) {\n"
       "    if (capacity < 0) throw new Error('Channel capacity must be non-negative');\n"
       "    this._closed = false;\n"
       "    this._capacity = capacity;\n"
       "    this._buffer = [];\n"
       "    this._senders = [];\n"
       "    this._receivers = [];\n"
       "  }\n"
       "  async send(value, timeoutMs) {\n"
       "    if (this._closed) throw new Error('Cannot send on a closed channel');\n"
       "    if (this._capacity === 0 && this._receivers.length > 0) {\n"
       "      const recv = this._receivers.shift();\n"
       "      recv.resolve(value);\n"
       "      return timeoutMs === undefined ? null : true;\n"
       "    }\n"
       "    if (this._buffer.length < this._capacity) {\n"
       "      this._buffer.push(value);\n"
       "      return timeoutMs === undefined ? null : true;\n"
       "    }\n"
       "    return await new Promise((resolve, reject) => {\n"
       "      const id = Symbol('send');\n"
       "      let timer = null;\n"
       "      const done = (result) => { if (timer !== null) clearTimeout(timer); resolve(result); };\n"
       "      const fail = (err) => { if (timer !== null) clearTimeout(timer); reject(err); };\n"
       "      this._senders.push({ id, value, resolve: () => done(timeoutMs === undefined ? null : true), reject: fail });\n"
       "      if (timeoutMs !== undefined) {\n"
       "        timer = setTimeout(() => {\n"
       "          this._senders = this._senders.filter(sender => sender.id !== id);\n"
       "          done(false);\n"
       "        }, timeoutMs);\n"
       "      }\n"
       "    });\n"
       "  }\n"
       "  try_send(value) {\n"
       "    if (this._closed) throw new Error('Cannot send on a closed channel');\n"
       "    if (this._capacity === 0 && this._receivers.length > 0) {\n"
       "      const recv = this._receivers.shift();\n"
       "      recv.resolve(value);\n"
       "      return true;\n"
       "    }\n"
       "    if (this._buffer.length < this._capacity) {\n"
       "      this._buffer.push(value);\n"
       "      return true;\n"
       "    }\n"
       "    return false;\n"
       "  }\n"
       "  async receive(timeoutMs) {\n"
       "    if (this._buffer.length > 0) {\n"
       "      const value = this._buffer.shift();\n"
       "      if (this._capacity > 0 && this._senders.length > 0) {\n"
       "        const sender = this._senders.shift();\n"
       "        this._buffer.push(sender.value);\n"
       "        sender.resolve(null);\n"
       "      }\n"
       "      return value;\n"
       "    }\n"
       "    if (this._senders.length > 0) {\n"
       "      const sender = this._senders.shift();\n"
       "      sender.resolve(null);\n"
       "      return sender.value;\n"
       "    }\n"
       "    if (this._closed) throw new Error('Cannot receive from a closed channel');\n"
       "    return await new Promise((resolve, reject) => {\n"
       "      const id = Symbol('receive');\n"
       "      let timer = null;\n"
       "      const done = (result) => { if (timer !== null) clearTimeout(timer); resolve(result); };\n"
       "      const fail = (err) => { if (timer !== null) clearTimeout(timer); reject(err); };\n"
       "      this._receivers.push({ id, resolve: done, reject: fail });\n"
       "      if (timeoutMs !== undefined) {\n"
       "        timer = setTimeout(() => {\n"
       "          this._receivers = this._receivers.filter(receiver => receiver.id !== id);\n"
       "          done(null);\n"
       "        }, timeoutMs);\n"
       "      }\n"
       "    });\n"
       "  }\n"
       "  try_receive() {\n"
       "    if (this._buffer.length > 0) {\n"
       "      const value = this._buffer.shift();\n"
       "      if (this._capacity > 0 && this._senders.length > 0) {\n"
       "        const sender = this._senders.shift();\n"
       "        this._buffer.push(sender.value);\n"
       "        sender.resolve(null);\n"
       "      }\n"
       "      return value;\n"
       "    }\n"
       "    if (this._senders.length > 0) {\n"
       "      const sender = this._senders.shift();\n"
       "      sender.resolve(null);\n"
       "      return sender.value;\n"
       "    }\n"
       "    return null;\n"
       "  }\n"
       "  close() {\n"
       "    if (!this._closed) {\n"
       "      this._closed = true;\n"
       "      const error = new Error('Channel is closed');\n"
       "      for (const sender of this._senders) sender.reject(error);\n"
       "      if (this._buffer.length === 0) {\n"
       "        for (const receiver of this._receivers) receiver.reject(error);\n"
       "        this._receivers = [];\n"
       "      }\n"
       "      this._senders = [];\n"
       "    }\n"
       "    return null;\n"
       "  }\n"
       "  is_closed() { return this._closed; }\n"
       "  capacity() { return this._capacity; }\n"
       "  size() { return this._buffer.length; }\n"
       "}\n"
       "function __nexSetUnion(a, b) {\n"
       "  return new Set([...a, ...b]);\n"
       "}\n"
       "function __nexSetDifference(a, b) {\n"
       "  return new Set([...a].filter(v => !b.has(v)));\n"
       "}\n"
       "function __nexSetIntersection(a, b) {\n"
       "  return new Set([...a].filter(v => b.has(v)));\n"
       "}\n"
       "function __nexSetSymmetricDifference(a, b) {\n"
       "  return new Set([...a].filter(v => !b.has(v)).concat([...b].filter(v => !a.has(v))));\n"
       "}\n"
       "function __nexArrayAdd(target, value) {\n"
       "  target.push(value);\n"
       "  return null;\n"
       "}\n"
       "function __nexArrayAddAt(target, index, value) {\n"
       "  target.splice(index, 0, value);\n"
       "  return null;\n"
       "}\n"
       "function __nexArrayPut(target, index, value) {\n"
       "  target[index] = value;\n"
       "  return null;\n"
       "}\n"
       "function __nexMapPut(target, key, value) {\n"
       "  target.set(key, value);\n"
       "  return null;\n"
       "}\n"
       "function __nexArrayCursor(source) {\n"
       "  return {_type: 'ArrayCursor', source, index: 0, start() { this.index = 0; }, item() { if (this.index >= this.source.length) throw new Error('Cursor is at end'); return this.source[this.index]; }, next() { if (this.index < this.source.length) this.index += 1; }, at_end() { return this.index >= this.source.length; }};\n"
       "}\n"
       "function __nexStringCursor(source) {\n"
       "  return {_type: 'StringCursor', source, index: 0, start() { this.index = 0; }, item() { if (this.index >= this.source.length) throw new Error('Cursor is at end'); return this.source[this.index]; }, next() { if (this.index < this.source.length) this.index += 1; }, at_end() { return this.index >= this.source.length; }};\n"
       "}\n"
       "function __nexMapCursor(source) {\n"
       "  return {_type: 'MapCursor', source, keys: Array.from(source.keys()), index: 0, start() { this.keys = Array.from(this.source.keys()); this.index = 0; }, item() { if (this.index >= this.keys.length) throw new Error('Cursor is at end'); const key = this.keys[this.index]; return [key, this.source.get(key)]; }, next() { if (this.index < this.keys.length) this.index += 1; }, at_end() { return this.index >= this.keys.length; }};\n"
       "}\n"
       "function __nexSetCursor(source) {\n"
       "  return {_type: 'SetCursor', source, values: Array.from(source.values()), index: 0, start() { this.values = Array.from(this.source.values()); this.index = 0; }, item() { if (this.index >= this.values.length) throw new Error('Cursor is at end'); return this.values[this.index]; }, next() { if (this.index < this.values.length) this.index += 1; }, at_end() { return this.index >= this.values.length; }};\n"
       "}\n"))

(defn generate-function-globals
  "Generate a globals holder for function instances."
  [functions]
  (let [functions (remove :declaration-only? functions)]
    (when (seq functions)
      (let [lines (map (fn [{:keys [name class-name]}]
                       (str "  " name ": new " class-name "(),"))
                     functions)]
        (str "const NexGlobals = {\n"
             (str/join "\n" lines)
             "\n};")))))

;;
;; Main Class Generation
;;

(defn generate-main
  "Generate main.js.
   If top-level statements exist, emit them in-order.
   Otherwise require and instantiate the last user-defined class (legacy behavior)."
  [ast]
  (let [statements (:statements ast)
        classes (:classes ast)]
    (if (seq statements)
      (let [top-level-vars (extract-var-names-js statements)
            top-level-types (extract-typed-locals statements)
            require-lines (concat
                           (when (seq *function-names*)
                             [(indent 1 "const { NexGlobals } = require('./NexGlobals');")])
                           (map (fn [cls]
                                  (indent 1 (str "const { " (:name cls) " } = require('./" (:name cls) "');")))
                                classes))
            statement-lines (binding [*local-names* (into *local-names* top-level-vars)
                                      *local-types* (merge *local-types* top-level-types)]
                              (mapv #(generate-statement 0 % #{}) statements))]
        (str "(async () => {\n"
             (when (seq require-lines)
               (str (str/join "\n" require-lines) "\n"))
             (indent 1 (str/join "\n" statement-lines))
             (when (seq statement-lines) "\n")
             "})();\n"))
      (let [last-class (last classes)
            class-name (:name last-class)
            {:keys [constructors]} (if last-class
                                     (extract-members (:body last-class))
                                     {:constructors []})
            no-arg-ctor (first (filter #(empty? (:params %)) constructors))
            call (cond
                   (and class-name no-arg-ctor)
                   (str "await " class-name "." (:name no-arg-ctor) "()")
                   (and class-name (empty? constructors))
                   (str "new " class-name "()")
                   :else
                   "/* no-op */")]
        (str "(async () => {\n"
             (when class-name
               (indent 1 (str "const { " class-name " } = require('./" class-name "');\n")))
             (indent 1 (str call ";")) "\n"
             "})();\n")))))

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
         runtime-helpers (generate-runtime-helpers)
         function-globals (generate-function-globals functions)]
     (binding [*function-names* function-names]
       (let [classes-by-name (into {} (map (juxt :name identity) classes))
             js-classes (map #(generate-class % opts classes-by-name) classes)
             parts (concat js-imports
                           (when (seq js-imports) [""])
                           [function-base]
                           ["" runtime-helpers]
                           (when (seq js-classes) [""])
                           js-classes
                           (when function-globals [""])
                           (when function-globals [function-globals]))]
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
   (let [source-id (or (:source-id opts) "<input>")
         ast (augment-ast-with-interns source-id (p/ast nex-code))]
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

  Writes: Function.js, NexGlobals.js (if functions exist),
          <ClassName>.js for each class, and main.js.

  Returns a map of {filename -> code-string}.

  Options:
    :skip-contracts - When true, omits all contracts (useful for production)
    :skip-type-check - When true, skips static type checking"
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
         main-code (binding [*function-names* function-names]
                     (generate-main ast))
         classes-by-name (into {} (map (juxt :name identity) classes))
         class-codes (binding [*function-names* function-names]
                       (mapv (fn [cls] [(:name cls) (generate-class cls opts classes-by-name)]) classes))
         files (into {"Function.js" function-base
                      "NexRuntime.js" runtime-helpers}
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
