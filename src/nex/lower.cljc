(ns nex.lower
  "First lowering pass from typed Nex AST to compiler IR.

  This pass is intentionally narrow. It currently supports:

  - literals
  - identifiers
  - binary expressions
  - let
  - assignment
  - top-level variable access

  Unsupported nodes fail fast with ex-info."
  (:require [clojure.string :as str]
            [nex.compiler.jvm.descriptor :as desc]
            [nex.interpreter :as interp]
            [nex.ir :as ir]
            [nex.typechecker :as tc]))

(declare lower-expression)
(declare lower-statement)
(declare lower-statements)
(declare lower-class-def)
(declare if-branch-expression)
(declare current-class-def)
(declare class-method-def)
(declare class-field-def)
 (declare elseif->else-expr)
(declare visible-class-map)
(declare generic-type-map)
(declare normalize-call-target)
(declare function-return-type)
(declare lookup-class-constant)
(declare constant-nex-type)
(declare resolve-parent-metas)
(declare method-override?)
(declare class-jvm-meta)
(declare inherited-method-def)
(declare single-super-parent-name)
(declare infer-call-type)
(declare collect-anonymous-class-defs)
(declare refine-condition-branch-env)
(declare function-object-call?)
(declare function-object-binding-type)
(declare lower-select)

(defn- imported-java-qualified-name
  [env class-name]
  (some (fn [{:keys [qualified-name source]}]
          (when (and (nil? source)
                     qualified-name
                     (= class-name (last (str/split qualified-name #"\."))))
            qualified-name))
        (:imports env)))

(defn- builtin-class-defs
  []
  (vals @(:classes (interp/make-context))))

(defn- merge-visible-classes
  [& class-groups]
  (->> class-groups
       (apply concat)
       (reduce (fn [acc class-def]
                 (if (and (map? class-def) (:name class-def))
                   (assoc acc (:name class-def) class-def)
                   acc))
               {})
       vals
       vec))

(def ^:private expression-node-types
  #{:integer :real :string :char :boolean :nil :identifier :binary :unary
    :call :if :when :this :array-literal :map-literal :set-literal
    :anonymous-function :spawn})

(def ^:private builtin-function-names
  (set (keys interp/builtins)))

(def ^:private builtin-runtime-receiver-types
  #{"Any" "Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Cursor" "Task" "Channel" "Console" "Process"})

(def ^:private next-synthetic-closure-id (atom 0))

(def ^:private direct-integer-bitwise-method->op
  {"bitwise_left_shift" :bit-shl
   "bitwise_right_shift" :bit-shr
   "bitwise_logical_right_shift" :bit-ushr
   "bitwise_rotate_left" :bit-rotl
   "bitwise_rotate_right" :bit-rotr
   "bitwise_is_set" :bit-test
   "bitwise_set" :bit-set
   "bitwise_unset" :bit-unset
   "bitwise_and" :bit-and
   "bitwise_or" :bit-or
   "bitwise_xor" :bit-xor
   "bitwise_not" :bit-not})

(def ^:private direct-array-methods
  #{"get" "add" "push" "add_at" "at" "put" "set" "length" "size" "is_empty"
    "contains" "index_of" "remove" "reverse" "slice" "sort" "first" "last"
    "to_string" "equals" "clone" "join" "cursor"})

(def ^:private direct-map-methods
  #{"get" "try_get" "put" "at" "set" "size" "is_empty" "contains_key"
    "keys" "values" "remove" "to_string" "equals" "clone" "cursor"})

(def ^:private direct-set-methods
  #{"contains" "union" "difference" "intersection" "symmetric_difference"
    "size" "is_empty" "to_string" "equals" "clone" "cursor"})

(def ^:private direct-task-methods
  #{"await" "cancel" "is_done" "is_cancelled"})

(def ^:private direct-channel-methods
  #{"send" "try_send" "receive" "try_receive" "close" "is_closed" "capacity" "size"})

(defn- base-type-name
  [t]
  (cond
    (string? t) t
    (map? t) (:base-type t)
    :else nil))

(defn- builtin-runtime-receiver-type?
  [t]
  (contains? builtin-runtime-receiver-types (base-type-name t)))

(defn- generic-type-args
  [t]
  (or (:type-args t) (:type-params t) []))

(defn- array-type-of
  [elem-type]
  {:base-type "Array" :type-params [elem-type]})

(defn- map-type-of
  [key-type value-type]
  {:base-type "Map" :type-params [key-type value-type]})

(defn- set-type-of
  [elem-type]
  {:base-type "Set" :type-params [elem-type]})

(defn- collection-method-return-type
  [target-type method]
  (let [base (base-type-name target-type)
        [a b] (generic-type-args target-type)]
    (case base
      "Array"
      (case method
        ("get" "first" "last") (or a "Any")
        ("add" "push" "add_at" "at" "put" "set" "remove") "Void"
        ("length" "size" "index_of") "Integer"
        ("is_empty" "contains" "equals") "Boolean"
        ("reverse" "slice" "sort" "clone") (or target-type (array-type-of (or a "Any")))
        ("to_string" "join") "String"
        "cursor" "Cursor"
        nil)

      "Map"
      (case method
        ("get" "try_get") (or b "Any")
        ("put" "at" "set" "remove") "Void"
        "size" "Integer"
        ("is_empty" "contains_key" "equals") "Boolean"
        "keys" (array-type-of (or a "Any"))
        "values" (array-type-of (or b "Any"))
        "to_string" "String"
        "clone" (or target-type (map-type-of (or a "Any") (or b "Any")))
        "cursor" "Cursor"
        nil)

      "Set"
      (case method
        ("contains" "is_empty" "equals") "Boolean"
        "size" "Integer"
        ("union" "difference" "intersection" "symmetric_difference" "clone")
        (or target-type (set-type-of (or a "Any")))
        "to_string" "String"
        "cursor" "Cursor"
        nil)
      nil)))

(defn- direct-collection-method?
  [target-type method]
  (case (base-type-name target-type)
    "Array" (contains? direct-array-methods method)
    "Map" (contains? direct-map-methods method)
    "Set" (contains? direct-set-methods method)
    false))

(defn- direct-concurrency-method?
  [target-type method]
  (case (base-type-name target-type)
    "Task" (contains? direct-task-methods method)
    "Channel" (contains? direct-channel-methods method)
    false))

(defn- normalize-call-target
  [target]
  (if (string? target)
    {:type :identifier :name target}
    target))

(defn make-lowering-env
  "Create the first lowering environment.

  Keys:
  - `:locals`     map of local-name -> {:slot .. :nex-type .. :jvm-type ..}
  - `:top-level?` whether names should lower to REPL state access when not local
  - `:repl?`      whether lowering is for REPL cells
  - `:state-slot` local slot holding NexReplState in compiled REPL methods
  - `:next-slot`  next free JVM local slot
  - `:classes`    visible class defs for type inference
  - `:functions`  visible top-level function defs for type inference/lowering
  - `:imports`    visible imports for type inference
  - `:var-types`  visible variable types
  - `:compiled-classes` map of Nex class-name -> emitted JVM class metadata
  - `:current-class` current Nex class name when lowering instance methods
  - `:fields` map of field-name -> {:owner .. :nex-type .. :jvm-type ..}
  - `:this-type` current receiver type when lowering instance methods
  - `:scoped-locals?` force `let` bindings to lower to JVM locals even in top-level code
  - `:retry-allowed?` whether `retry` is legal in the current lowering scope
  - `:old-field-locals` snapshot locals for `old` in postconditions
  - `:generic-param-names` visible generic parameter identifiers lowered as JVM Object
  - `:with-java?` whether unresolved target calls should lower as JVM host interop"
  ([] (make-lowering-env {}))
  ([{:keys [locals top-level? repl? state-slot next-slot classes functions imports var-types
            compiled-classes current-class fields this-type old-field-locals
            generic-param-names with-java? across-cursors] :as opts}]
   {:locals (or locals {})
    :top-level? (if (contains? opts :top-level?) top-level? true)
    :repl? (if (contains? opts :repl?) repl? true)
    :state-slot (or state-slot 0)
    :next-slot (or next-slot 1)
    :classes (vec (or classes []))
    :functions (vec (or functions []))
    :imports (vec (or imports []))
    :var-types (or var-types {})
    :compiled-classes (or compiled-classes {})
    :current-class current-class
    :fields (or fields {})
    :this-type this-type
    :scoped-locals? false
    :retry-allowed? false
    :old-field-locals (or old-field-locals {})
    :generic-param-names (set generic-param-names)
    :with-java? (boolean with-java?)
    :across-cursors (or across-cursors {})}))

(defn- resolve-jvm-type
  [env nex-type]
  (let [base (base-type-name nex-type)]
    (cond
      (contains? (:generic-param-names env) base)
      (ir/object-jvm-type "java/lang/Object")

      (if-let [compiled (get (:compiled-classes env) base)] true false)
      (ir/object-jvm-type "java/lang/Object")

      (true? (:closure-runtime-object? (get (visible-class-map env) base)))
      (ir/object-jvm-type "java/lang/Object")

      (imported-java-qualified-name env base)
      (ir/object-jvm-type (desc/internal-class-name (imported-java-qualified-name env base)))

      :else
      (desc/nex-type->jvm-type nex-type))))

(defn- exact-class-jvm-type
  [env class-name]
  (ir/object-jvm-type (:internal-name (class-jvm-meta env class-name))))

(defn- java-host-class-root-name
  [env expr]
  (when (and (:with-java? env)
             (= :identifier (:type expr))
             (not (get (:locals env) (:name expr)))
             (not (get (:fields env) (:name expr)))
             (not (contains? (:var-types env) (:name expr))))
    (or (imported-java-qualified-name env (:name expr))
        (when (re-matches #"[A-Z][A-Za-z0-9_]*" (:name expr))
          (:name expr)))))

(defn- with-stmt-debug
  [ir-node stmt]
  (ir/with-debug ir-node stmt))

(defn- env-visible-var-types
  [env]
  (merge (:var-types env)
         (into {}
               (map (fn [[name {:keys [nex-type]}]]
                      [name nex-type])
                    (:locals env)))))

(defn- infer-type
  [env expr]
  (let [convert-branch-env (fn [env' condition]
                             (if (= :convert (:type condition))
                               (assoc-in env' [:var-types (:var-name condition)] (:target-type condition))
                               env'))
        direct-type
        (case (:type expr)
          :identifier
          (or (get-in (:locals env) [(:name expr) :nex-type])
              (get-in (:fields env) [(:name expr) :nex-type])
              (some-> (current-class-def env)
                      (class-field-def (:name expr))
                      :field-type)
              (some-> (current-class-def env)
                      ((fn [class-def]
                         (or (class-method-def class-def (:name expr) 0)
                             (inherited-method-def env class-def (:name expr) 0))))
                      function-return-type)
              (some-> (and (:current-class env)
                           (lookup-class-constant env (:current-class env) (:name expr)))
                      (#(constant-nex-type env %)))
              (get (:var-types env) (:name expr)))

          :create
          (if (seq (:generic-args expr))
            {:base-type (:class-name expr) :type-args (:generic-args expr)}
            (:class-name expr))

          :this
          (:this-type env)

          :binary
          (let [op (:operator expr)]
            (cond
              (#{"-" "*" "/" "%"} op) (infer-type env (:left expr))
              (= "+" op) (let [left-type (infer-type env (:left expr))
                               right-type (infer-type env (:right expr))]
                           (if (or (= "String" (base-type-name left-type))
                                   (= "String" (base-type-name right-type)))
                             "String"
                             left-type))
              (= "^" op) (tc/power-result-type (infer-type env (:left expr))
                                               (infer-type env (:right expr)))
              (#{"and" "or" "=" "/=" "<" "<=" ">" ">="} op) "Boolean"
              :else nil))

          :unary
          (case (:operator expr)
            "-" (infer-type env (:expr expr))
            "not" "Boolean"
            nil)

          :array-literal
          (let [elements (:elements expr)
                elem-type (or (some-> elements first (infer-type env))
                              "Any")]
            (array-type-of elem-type))

          :map-literal
          (let [entries (:entries expr)
                key-type (or (some-> entries first :key (infer-type env))
                             "Any")
                value-type (or (some-> entries first :value (infer-type env))
                               "Any")]
            (map-type-of key-type value-type))

          :set-literal
          (let [elements (:elements expr)
                elem-type (or (some-> elements first (infer-type env))
                              "Any")]
            (set-type-of elem-type))

          :if
          (let [then-env (refine-condition-branch-env (convert-branch-env env (:condition expr))
                                                      (:condition expr)
                                                      :then)
                else-env (refine-condition-branch-env env (:condition expr) :else)]
            (or (some-> (:then expr) (if-branch-expression then-env) (infer-type then-env))
                (some-> (:else expr) (if-branch-expression else-env) (infer-type else-env))))

          :call
          (infer-call-type env expr)

          nil)]
    (or direct-type
        (tc/infer-expression-type expr {:classes (:classes env)
                                        :functions (:functions env)
                                        :imports (:imports env)
                                        :var-types (env-visible-var-types env)})
        (throw (ex-info "Unable to infer expression type during lowering"
                        {:expr expr})))))

(defn- expr-jvm-type
  [env expr]
  (resolve-jvm-type env (infer-type env expr)))

(defn- infer-call-type
  [env expr]
  (let [raw-target (:target expr)
        class-target-name (when (string? raw-target)
                            (some #(when (= (:name %) raw-target)
                                     (:name %))
                                  (:classes env)))
        across-item-type (and (string? raw-target)
                              (get (:across-cursors env) raw-target))
        target-expr (normalize-call-target raw-target)]
    (if (nil? target-expr)
      (or
       (when (function-object-call? env (:method expr) (count (:args expr)))
         (let [binding-type (function-object-binding-type env (:method expr))
               base-type (base-type-name binding-type)
               call-name (str "call" (count (:args expr)))]
           (if (= "Function" base-type)
             "Any"
             (some-> (get (visible-class-map env) base-type)
                     (class-method-def call-name (count (:args expr)))
                     function-return-type))))
       (when (:this-type env)
         (some-> (or (class-method-def (current-class-def env) (:method expr) (count (:args expr)))
                     (inherited-method-def env (current-class-def env) (:method expr) (count (:args expr))))
                 function-return-type)))
      (if (and (= :identifier (:type target-expr))
               (= "super" (:name target-expr)))
        (let [parent-name (single-super-parent-name env)
              parent-def (get (visible-class-map env) parent-name)]
          (if (false? (:has-parens expr))
            (or (some-> (class-field-def parent-def (:method expr))
                        :field-type)
                (some-> (class-method-def parent-def (:method expr) 0)
                        function-return-type)
                (some-> (inherited-method-def env parent-def (:method expr) 0)
                        function-return-type))
            (or (some-> (class-method-def parent-def (:method expr) (count (:args expr)))
                        function-return-type)
                (some-> (inherited-method-def env parent-def (:method expr) (count (:args expr)))
                        function-return-type))))
        (let [java-static-owner (java-host-class-root-name env target-expr)
              target-type (when (and (not class-target-name)
                                     (not java-static-owner))
                            (infer-type env target-expr))
              base-type (base-type-name target-type)
              type-map (generic-type-map env target-type)
              class-def (or (when class-target-name
                              (get (visible-class-map env) class-target-name))
                            (get (visible-class-map env) base-type))]
          (or
           (when across-item-type
             (case (:method expr)
               "item" across-item-type
               "start" "Void"
               "next" "Void"
               "at_end" "Boolean"
               "cursor" "Cursor"
               nil))
           (when (or java-static-owner (:with-java? env))
             "Any")
           (when class-def
             (if (and class-target-name (false? (:has-parens expr)))
               (some-> (lookup-class-constant env class-target-name (:method expr))
                       (#(constant-nex-type env %)))
               (if (:import class-def)
                 "Any"
               (if (false? (:has-parens expr))
                 (or (some-> (class-field-def class-def (:method expr))
                             :field-type
                             (#(tc/resolve-generic-type % type-map)))
                     (some-> (class-method-def class-def (:method expr) (count (:args expr)))
                             function-return-type
                             (#(tc/resolve-generic-type % type-map))))
                 (some-> (class-method-def class-def (:method expr) (count (:args expr)))
                         function-return-type
                         (#(tc/resolve-generic-type % type-map))))))
           (when (direct-collection-method? target-type (:method expr))
             (collection-method-return-type target-type (:method expr))))))))))

(defn- env-add-local
  [env name nex-type]
  (let [jvm-type (resolve-jvm-type env nex-type)
        slot (:next-slot env)
        width (if (contains? #{:long :double} jvm-type) 2 1)]
    [(assoc env
            :locals (assoc (:locals env)
                           name
                           {:name name
                            :slot slot
                            :nex-type nex-type
                            :jvm-type jvm-type})
            :var-types (assoc (:var-types env) name nex-type)
            :next-slot (+ slot width))
     {:name name
      :slot slot
      :nex-type nex-type
      :jvm-type jvm-type}]))

(defn- lowered-function-method-name
  [fn-def]
  (str "__repl_fn_" (:name fn-def) "$arity" (count (:params fn-def))))

(defn- lowered-instance-method-name
  [method-def]
  (str "__method_" (:name method-def) "$arity" (count (:params method-def))))

(defn- lowered-constructor-method-name
  [ctor-def]
  (str "__ctor_" (:name ctor-def) "$arity" (count (:params ctor-def))))

(defn- function-return-type
  [fn-def]
  (or (:return-type fn-def) "Any"))

(defn- top-level-function-callable
  [fn-def]
  (when-let [class-def (:class-def fn-def)]
    (some (fn [member]
            (when (and (= :method (:type member))
                       (= (count (or (:params member) []))
                          (count (or (:params fn-def) []))))
              member))
          (mapcat :members
                  (filter #(= :feature-section (:type %))
                          (:body class-def))))))

(defn- normalized-function-def
  [fn-def]
  (if-let [callable (and (= :function (:type fn-def))
                         (top-level-function-callable fn-def))]
    (merge callable fn-def)
    fn-def))

(defn- if-branch-expression
  [env branch]
  (when (= 1 (count branch))
    (let [stmt (first branch)]
      (cond
        (and (contains? expression-node-types (:type stmt))
             (not= "Void" (infer-type env stmt)))
        stmt

        (and (= :assign (:type stmt))
             (= "result" (:target stmt)))
        (:value stmt)

        :else
        nil))))

(defn- implicit-if-expression?
  [env stmt]
  (when (= :if (:type stmt))
    (let [then-env (refine-condition-branch-env
                    (if (= :convert (get-in stmt [:condition :type]))
                      (assoc-in env [:var-types (get-in stmt [:condition :var-name])]
                                (get-in stmt [:condition :target-type]))
                      env)
                    (:condition stmt)
                    :then)
          else-env (refine-condition-branch-env env (:condition stmt) :else)]
      (and (some? (if-branch-expression then-env (:then stmt)))
           (some? (elseif->else-expr else-env (:elseif stmt) (:else stmt)))))))

(defn- scoped-env
  [env child-env]
  (assoc env :next-slot (:next-slot child-env)))

(defn- scoped-child-env
  [env]
  (assoc env :scoped-locals? true))

(defn- alloc-temp-slot
  [env]
  (let [slot (:next-slot env)]
    [(update env :next-slot inc) slot]))

(defn- env-add-local-alias
  [env alias {:keys [slot nex-type jvm-type]}]
  (-> env
      (assoc-in [:locals alias] {:name alias
                                 :slot slot
                                 :nex-type nex-type
                                 :jvm-type jvm-type})
      (assoc-in [:var-types alias] nex-type)))

(defn- references-old?
  [node]
  (cond
    (nil? node) false
    (sequential? node) (boolean (some references-old? node))
    (map? node) (or (= :old (:type node))
                    (boolean (some references-old? (vals node))))
    :else false))

(defn- snapshot-source-ir
  [env {:keys [owner field carrier-owner carrier-field carrier-jvm-type nex-type jvm-type]}]
  (let [this-ir (ir/this-node (:this-type env)
                              (exact-class-jvm-type env (:this-type env)))
        target-ir (if carrier-field
                    (ir/field-get-node (:internal-name (class-jvm-meta env carrier-owner))
                                       carrier-field
                                       this-ir
                                       owner
                                       carrier-jvm-type)
                    this-ir)]
    (ir/field-get-node (:internal-name (class-jvm-meta env owner))
                       field
                       target-ir
                       nex-type
                       jvm-type)))

(defn- assertion-ir
  [env kind {:keys [label condition]}]
  (ir/assert-node kind label (lower-expression env condition)))

(defn- function-root-class?
  [env class-name]
  (let [class-def (get (visible-class-map env) class-name)]
    (boolean
     (and class-def
          (seq (:parents class-def))
          (every? #(= "Function" (:parent %)) (:parents class-def))))))

(defn- validate-object-state-ir
  [env class-name object-ir nex-type]
  (if (function-root-class? env class-name)
    object-ir
    (ir/call-runtime-node "validate-object-state"
                          [(ir/const-node class-name
                                          "String"
                                          (ir/object-jvm-type "java/lang/String"))
                           object-ir]
                          nex-type
                          (resolve-jvm-type env nex-type))))

(defn- default-const-node
  [nex-type jvm-type]
  (case jvm-type
    :int (ir/const-node 0 nex-type :int)
    :long (ir/const-node 0 nex-type :long)
    :double (ir/const-node 0.0 nex-type :double)
    :boolean (ir/const-node false "Boolean" :boolean)
    :char (ir/const-node (char 0) "Char" :char)
    (ir/const-node nil "Any" (ir/object-jvm-type "java/lang/Object"))))

(defn- add-old-field-snapshots
  [env assertions]
  (if (some #(references-old? (:condition %)) assertions)
    (do
      (when-not (and (:this-type env) (seq (:fields env)))
        (throw (ex-info "'old' is only supported for compiled instance-field postconditions"
                        {:assertions assertions
                         :current-class (:current-class env)})))
      (reduce (fn [[env' stmts snapshots] field-name]
                (let [field-info (get (:fields env') field-name)
                      snapshot-name (str "__old_" field-name)
                      [env'' local] (env-add-local env' snapshot-name (:nex-type field-info))
                      stmt (ir/set-local-node (:slot local)
                                              (snapshot-source-ir env' field-info)
                                              (:nex-type local)
                                              (:jvm-type local))]
                  [env''
                   (conj stmts stmt)
                   (assoc snapshots field-name local)]))
              [env [] {}]
              (sort (keys (:fields env)))))
    [env [] (:old-field-locals env)]))

(defn- old-env
  [env]
  (if (seq (:old-field-locals env))
    (let [shadowed (into {}
                         (map (fn [[field-name {:keys [slot nex-type jvm-type]}]]
                                [field-name {:name field-name
                                             :slot slot
                                             :nex-type nex-type
                                             :jvm-type jvm-type}]))
                         (:old-field-locals env))]
      (-> env
          (update :locals merge shadowed)
          (update :var-types merge (into {}
                                         (map (fn [[field-name {:keys [nex-type]}]]
                                                [field-name nex-type]))
                                         (:old-field-locals env)))))
    (throw (ex-info "'old' can only be used in compiled postconditions with field snapshots"
                    {:env-keys (keys env)}))))

(defn- lower-body-with-rescue
  [env body rescue]
  (let [[body-env lowered-body]
        (if (every? ir/ir-node? body)
          [env (vec body)]
          (lower-statements env body))]
    (if rescue
      (let [[env1 throwable-slot] (alloc-temp-slot body-env)
            [env2 rescue-throwable-slot] (alloc-temp-slot env1)
          env-after-body body-env
          rescue-env0 (assoc (scoped-child-env env2) :retry-allowed? true)
          [rescue-env1 exception-local] (env-add-local rescue-env0 "exception" "Any")
          [rescue-env2 lowered-rescue] (lower-statements rescue-env1 rescue)
          final-env (scoped-env env-after-body rescue-env2)]
        [final-env
         [(ir/try-node lowered-body
                       lowered-rescue
                       throwable-slot
                       rescue-throwable-slot
                       (:slot exception-local))]])
      [body-env lowered-body])))

(defn- lower-scoped-statements
  [env statements]
  (let [[child-env lowered] (lower-statements (scoped-child-env env) statements)]
    [(scoped-env env child-env) lowered]))

(defn- elseif->else-expr
  [env elseif else-branch]
  (if-let [clause (first elseif)]
    {:type :if
     :condition (:condition clause)
     :then (:then clause)
     :elseif (vec (rest elseif))
     :else else-branch}
    (let [else-body (or else-branch [])]
      (when-not (= 1 (count else-body))
        (throw (ex-info "Only expression-shaped or result-assignment if branches are supported in lowering"
                        {:branch else-body})))
      (if-branch-expression env else-body))))

(defn- case-clause-test-expr
  [env local literal-exprs]
  (letfn [(eq-expr [literal-expr]
            (ir/compare-node :eq
                             (ir/local-node (:name local) (:slot local) (:nex-type local) (:jvm-type local))
                             (lower-expression env literal-expr)
                             "Boolean"
                             :boolean))
          (combine [exprs]
            (if (= 1 (count exprs))
              (eq-expr (first exprs))
              (ir/if-node (eq-expr (first exprs))
                          [(ir/const-node true "Boolean" :boolean)]
                          [(combine (rest exprs))]
                          "Boolean"
                          :boolean)))]
    (combine literal-exprs)))

(defn- lower-case-clauses
  [env local clauses else-stmts]
  (if-let [clause (first clauses)]
    (let [[then-env then-body] (lower-scoped-statements env [(:body clause)])
          [else-env else-body] (lower-case-clauses (scoped-env env then-env) local (rest clauses) else-stmts)
          test-expr (case-clause-test-expr env local (:values clause))]
      [(scoped-env env else-env)
       [(ir/if-stmt-node test-expr then-body else-body)]])
    (lower-scoped-statements env else-stmts)))

(defn- select-clause-value-type
  [env {:keys [expr]}]
  (let [{:keys [target method]} expr
        target-type (infer-type env (normalize-call-target target))
        base (base-type-name target-type)
        type-args (generic-type-args target-type)]
    (case base
      "Task" (or (first type-args) "Any")
      "Channel" (case method
                  ("receive" "try_receive") (or (first type-args) "Any")
                  nil)
      nil)))

(defn- lower-select-clause
  [env done-local clause]
  (let [{:keys [expr alias body]} clause
        done-node (ir/local-node "__select_done"
                                 (:slot done-local)
                                 (:nex-type done-local)
                                 (:jvm-type done-local))
        not-done (ir/unary-node :not done-node "Boolean" :boolean)
        {:keys [target method args]} expr
        target-expr (normalize-call-target target)]
    (case (base-type-name (infer-type env target-expr))
      "Task"
      (let [ready-expr {:type :call
                        :target target-expr
                        :method "is_done"
                        :args []
                        :has-parens false}
            value-expr {:type :call
                        :target target-expr
                        :method "await"
                        :args []
                        :has-parens true}
            [env1 alias-local] (if alias
                                 (env-add-local env alias (select-clause-value-type env clause))
                                 [env nil])
            [env2 lowered-body] (lower-scoped-statements env1 body)
            then-body (vec (concat
                            (when alias-local
                              [(ir/set-local-node (:slot alias-local)
                                                  (lower-expression env2 value-expr)
                                                  (:nex-type alias-local)
                                                  (:jvm-type alias-local))])
                            lowered-body
                            [(ir/set-local-node (:slot done-local)
                                                (ir/const-node true "Boolean" :boolean)
                                                "Boolean"
                                                :boolean)]))]
        [env2
         (ir/if-stmt-node (ir/binary-node :and
                                          not-done
                                          (lower-expression env ready-expr)
                                          "Boolean"
                                          :boolean)
                          then-body
                          [])])

      "Channel"
      (case method
        ("receive" "try_receive")
        (let [value-type (select-clause-value-type env clause)
              temp-type (tc/detachable-version value-type)
              [env1 temp-local] (env-add-local env (str "__select_value_" (:next-slot env)) temp-type)
              [env2 alias-local] (if alias
                                   (env-add-local env1 alias value-type)
                                   [env1 nil])
              [env3 lowered-body] (lower-scoped-statements env2 body)
              receive-expr {:type :call
                            :target target-expr
                            :method "try_receive"
                            :args []
                            :has-parens true}
              temp-node (ir/local-node "__select_value"
                                       (:slot temp-local)
                                       (:nex-type temp-local)
                                       (:jvm-type temp-local))
              then-body (vec (concat
                              (when alias-local
                                [(ir/set-local-node (:slot alias-local)
                                                    temp-node
                                                    (:nex-type alias-local)
                                                    (:jvm-type alias-local))])
                              lowered-body
                              [(ir/set-local-node (:slot done-local)
                                                  (ir/const-node true "Boolean" :boolean)
                                                  "Boolean"
                                                  :boolean)]))]
          [env3
           (ir/block-node
            [(ir/set-local-node (:slot temp-local)
                                (lower-expression env3 receive-expr)
                                (:nex-type temp-local)
                                (:jvm-type temp-local))
             (ir/if-stmt-node (ir/binary-node :and
                                              not-done
                                              (ir/compare-node :neq
                                                               temp-node
                                                               (ir/const-node nil "Any" (ir/object-jvm-type "java/lang/Object"))
                                                               "Boolean"
                                                               :boolean)
                                              "Boolean"
                                              :boolean)
                             then-body
                             [])])])

        ("send" "try_send")
        (let [send-expr {:type :call
                         :target target-expr
                         :method "try_send"
                         :args [(first args)]
                         :has-parens true}
              [env1 lowered-body] (lower-scoped-statements env body)
              then-body (vec (concat lowered-body
                                     [(ir/set-local-node (:slot done-local)
                                                         (ir/const-node true "Boolean" :boolean)
                                                         "Boolean"
                                                         :boolean)]))]
          [env1
           (ir/if-stmt-node (ir/binary-node :and
                                            not-done
                                            (lower-expression env1 send-expr)
                                            "Boolean"
                                            :boolean)
                            then-body
                            [])])

        (throw (ex-info "Unsupported select channel clause during lowering"
                        {:clause clause})))

      (throw (ex-info "Unsupported select clause target during lowering"
                      {:clause clause})))))

(defn lower-select
  [env stmt]
  (let [[env1 done-local] (env-add-local env "__select_done" "Boolean")
        [env2 deadline-local] (if-let [_timeout (:timeout stmt)]
                                (env-add-local env1 "__select_deadline" "Integer64")
                                [env1 nil])
        init-stmts (vec (concat
                         [(ir/set-local-node (:slot done-local)
                                             (ir/const-node false "Boolean" :boolean)
                                             "Boolean"
                                             :boolean)]
                         (when-let [timeout (:timeout stmt)]
                           [(ir/set-local-node (:slot deadline-local)
                                               (ir/call-runtime-node "select-deadline"
                                                                     [(lower-expression env2 (:duration timeout))]
                                                                     "Integer64"
                                                                     :long)
                                               "Integer64"
                                               :long)])))
        [env3 clause-stmts] (reduce (fn [[e acc] clause]
                                     (let [[e' stmt'] (lower-select-clause e done-local clause)]
                                       [e' (conj acc stmt')]))
                                   [env2 []]
                                   (:clauses stmt))
        [env4 else-body] (if-let [else-stmts (:else stmt)]
                           (let [[e body] (lower-scoped-statements env3 else-stmts)]
                             [e body])
                           [env3 []])
        [env5 timeout-body] (if-let [timeout (:timeout stmt)]
                              (let [[e body] (lower-scoped-statements env4 (:body timeout))]
                                [e body])
                              [env4 []])
        done-node (ir/local-node "__select_done" (:slot done-local) "Boolean" :boolean)
        loop-body (vec (concat
                        clause-stmts
                        (when (seq else-body)
                          [(ir/if-stmt-node (ir/unary-node :not done-node "Boolean" :boolean)
                                            (vec (concat else-body
                                                         [(ir/set-local-node (:slot done-local)
                                                                             (ir/const-node true "Boolean" :boolean)
                                                                             "Boolean"
                                                                             :boolean)]))
                                            [])])
                        (when-let [_timeout (:timeout stmt)]
                          [(ir/if-stmt-node (ir/binary-node :and
                                                           (ir/unary-node :not done-node "Boolean" :boolean)
                                                           (ir/call-runtime-node "deadline-expired?"
                                                                                 [(ir/local-node "__select_deadline"
                                                                                                 (:slot deadline-local)
                                                                                                 "Integer64"
                                                                                                 :long)]
                                                                                 "Boolean"
                                                                                 :boolean)
                                                           "Boolean"
                                                           :boolean)
                                           (vec (concat timeout-body
                                                        [(ir/set-local-node (:slot done-local)
                                                                            (ir/const-node true "Boolean" :boolean)
                                                                            "Boolean"
                                                                            :boolean)]))
                                           [])])
                        [(ir/pop-node (ir/call-runtime-node "select-sleep-step" [] "Void" :void))]))]
    [env5
     (ir/block-node
      (conj init-stmts
            (ir/loop-node [] done-node loop-body)))]))

(defn- visible-class-map
  [env]
  (into {} (map (juxt :name identity) (:classes env))))

(defn- lowering-type-env
  [env]
  (let [type-env (tc/make-type-env)]
    (doseq [[class-name class-def] (visible-class-map env)]
      (tc/env-add-class type-env class-name class-def))
    type-env))

(defn- cursor-compatible-type?
  [env nex-type]
  (let [base-type (base-type-name nex-type)]
    (and (string? base-type)
         (tc/class-subtype? (lowering-type-env env) base-type "Cursor"))))

(defn- across-cursor-binding
  [env stmt]
  (when (and (:synthetic stmt)
             (string? (:name stmt))
             (str/starts-with? (:name stmt) "__across_c_")
             (= :call (get-in stmt [:value :type]))
             (= "cursor" (get-in stmt [:value :method]))
             (empty? (get-in stmt [:value :args])))
    (let [target-expr (normalize-call-target (get-in stmt [:value :target]))
          target-type (and target-expr (infer-type env target-expr))]
      (when (cursor-compatible-type? env target-type)
        {:target-expr target-expr
         :target-type target-type}))))

(defn- generic-type-map
  [env target-type]
  (let [type-env (tc/make-type-env)]
    (doseq [[class-name class-def] (visible-class-map env)]
      (tc/env-add-class type-env class-name class-def))
    (tc/build-generic-type-map type-env target-type)))

(defn- current-class-def
  [env]
  (get (visible-class-map env) (:current-class env)))

(defn- class-members
  [class-def]
  (mapcat (fn [section]
            (case (:type section)
              :feature-section (:members section)
              :constructors (:constructors section)
              []))
          (:body class-def)))

(defn- class-fields
  [class-def]
  (filter #(= :field (:type %)) (class-members class-def)))

(defn- feature-members
  [class-def]
  (mapcat (fn [section]
            (when (= (:type section) :feature-section)
              (map #(if (:visibility %)
                      %
                      (assoc % :visibility (:visibility section)))
                   (:members section))))
          (:body class-def)))

(defn- public-member?
  [member]
  (not= :private (-> member :visibility :type)))

(defn- class-methods
  [class-def]
  (filter #(= :method (:type %)) (class-members class-def)))

(defn- class-constructors
  [class-def]
  (filter #(= :constructor (:type %)) (class-members class-def)))

(defn- class-field-def
  [class-def field-name]
  (some #(when (= (:name %) field-name) %) (class-fields class-def)))

(defn- lookup-class-constant
  [env class-name constant-name]
  (let [class-map (visible-class-map env)]
    (letfn [(lookup-constant [cn visited inherited?]
              (when (and cn (not (contains? visited cn)))
                (let [class-def (get class-map cn)
                      visited' (conj visited cn)
                      own-constant (when class-def
                                     (some (fn [member]
                                             (when (and (= (:type member) :field)
                                                        (:constant? member)
                                                        (= (:name member) constant-name)
                                                        (or (not inherited?)
                                                            (public-member? member)))
                                               (assoc member :declaring-class cn)))
                                           (feature-members class-def)))]
                  (or own-constant
                      (when class-def
                        (some (fn [{:keys [parent]}]
                                (lookup-constant parent visited' true))
                              (:parents class-def)))))))]
      (lookup-constant class-name #{} false))))

(defn- class-method-def
  [class-def method-name arity]
  (some #(when (and (= (:name %) method-name)
                    (= (count (or (:params %) [])) arity))
           %)
        (class-methods class-def)))

(defn- class-constructor-def
  [class-def constructor-name arity]
  (some #(when (and (= (:name %) constructor-name)
                    (= (count (or (:params %) [])) arity))
           %)
        (class-constructors class-def)))

(defn- resolve-parent-metas
  [env class-def]
  (->> (:parents class-def)
       (remove #(contains? #{"Any" "Function"} (:parent %)))
       (mapv (fn [{:keys [parent]}]
               (when-let [parent-def (get (visible-class-map env) parent)]
                 (when-let [compiled (get (:compiled-classes env) parent)]
                   {:nex-name parent
                    :jvm-name (:jvm-name compiled)
                    :internal-name (:internal-name compiled)
                    :binary-name (:binary-name compiled)
                    :composition-field (str "_parent_" parent)
                    :deferred? (boolean (:deferred? parent-def))}))))
       (remove nil?)
       vec))

(defn- direct-parent-field-map
  [env class-def]
  (reduce (fn [m {:keys [parent]}]
            (if-let [parent-def (and (get (:compiled-classes env) parent)
                                     (get (visible-class-map env) parent))]
              (let [composition-field (str "_parent_" parent)]
                (reduce (fn [m2 field]
                          (if (or (:constant? field)
                                  (contains? m2 (:name field)))
                            m2
                            (assoc m2
                                   (:name field)
                                   {:owner parent
                                    :field (:name field)
                                    :carrier-owner (:name class-def)
                                    :carrier-field composition-field
                                    :nex-type (:field-type field)
                                    :jvm-type (resolve-jvm-type env (:field-type field))
                                    :carrier-jvm-type (exact-class-jvm-type env parent)})))
                        m
                        (class-fields parent-def)))
              m))
          {}
          (remove #(contains? #{"Any" "Function"} (:parent %)) (:parents class-def))))

(defn- inherited-method-def
  [env class-def method-name arity]
  (let [class-map (visible-class-map env)]
    (letfn [(lookup-method [parents visited]
              (some (fn [{:keys [parent]}]
                      (when-not (contains? visited parent)
                        (let [parent-def (get class-map parent)
                              visited' (conj visited parent)]
                          (or (when parent-def
                                (class-method-def parent-def method-name arity))
                              (when parent-def
                                (lookup-method (:parents parent-def) visited'))))))
                    parents))]
      (lookup-method (:parents class-def) #{}))))

(defn- method-override?
  [env class-def method-def]
  (boolean
   (inherited-method-def env
                         class-def
                         (:name method-def)
                         (count (or (:params method-def) [])))))

(defn- constant-nex-type
  [env constant]
  (or (:field-type constant)
      (when-let [value-expr (:value constant)]
        (infer-type env value-expr))))

(defn- lowered-deferred-method?
  [class-def method-def]
  (or (:deferred? method-def)
      (:declaration-only? method-def)
      (and (:deferred? class-def)
           (empty? (vec (:body method-def))))))

(defn- field-info-map
  [env class-def]
  (merge
   (direct-parent-field-map env class-def)
   (into {}
         (map (fn [field]
                [(:name field)
                 {:owner (:name class-def)
                  :field (:name field)
                  :nex-type (:field-type field)
                  :jvm-type (resolve-jvm-type env (:field-type field))}]))
         (remove :constant? (class-fields class-def)))))

(defn- field-type-map
  [class-def]
  (into {}
        (map (fn [field]
               [(:name field) (:field-type field)]))
        (remove :constant? (class-fields class-def))))

(defn- inherited-constructor-def
  [env class-def constructor-name arity]
  (let [class-map (visible-class-map env)]
    (letfn [(lookup-ctor [parents visited]
              (some (fn [{:keys [parent]}]
                      (when-not (contains? visited parent)
                        (let [parent-def (get class-map parent)
                              visited' (conj visited parent)]
                          (or (when parent-def
                                (class-constructor-def parent-def constructor-name arity))
                              (when parent-def
                                (lookup-ctor (:parents parent-def) visited'))))))
                    parents))]
      (lookup-ctor (:parents class-def) #{}))))

(defn- own-or-inherited-constructor-def
  [env class-def constructor-name arity]
  (or (class-constructor-def class-def constructor-name arity)
      (inherited-constructor-def env class-def constructor-name arity)))

(defn- direct-parent-method-map
  [env class-def]
  (reduce (fn [m {:keys [parent]}]
            (if-let [parent-def (and (get (:compiled-classes env) parent)
                                     (get (visible-class-map env) parent))]
              (let [parent-meta (class-jvm-meta env parent)
                    composition-field (str "_parent_" parent)]
                (reduce (fn [m2 method-def]
                          (if (contains? m2 [(:name method-def) (count (or (:params method-def) []))])
                            m2
                            (assoc m2
                                   [(:name method-def) (count (or (:params method-def) []))]
                                   {:source-class parent
                                    :carrier-owner (:name class-def)
                                    :carrier-field composition-field
                                    :owner-internal-name (:internal-name parent-meta)
                                    :method-def method-def
                                    :carrier-jvm-type (exact-class-jvm-type env parent)})))
                        m
                        (class-methods parent-def)))
              m))
          {}
          (remove #(contains? #{"Any" "Function"} (:parent %)) (:parents class-def))))

(defn- direct-parent-names
  [class-def]
  (mapv :parent (remove #(contains? #{"Any" "Function"} (:parent %)) (:parents class-def))))

(defn- function-object-binding-type
  [env name]
  (or (get-in (:locals env) [name :nex-type])
      (get (:var-types env) name)))

(defn- function-object-call?
  [env name arity]
  (when-let [binding-type (function-object-binding-type env name)]
    (let [base-type (base-type-name binding-type)
          call-name (str "call" arity)]
      (or (= "Function" base-type)
          (boolean
           (some-> (get (visible-class-map env) base-type)
                   (class-method-def call-name arity)))))))

(defn- single-super-parent-name
  [env]
  (let [parents (direct-parent-names (current-class-def env))]
    (case (count parents)
      1 (first parents)
      0 (throw (ex-info "super requires a direct parent in compiled lowering"
                        {:current-class (:current-class env)}))
      (throw (ex-info "super is ambiguous with multiple direct parents in compiled lowering"
                      {:current-class (:current-class env)
                       :parents parents})))))

(defn- lookup-convert-binding
  [env var-name]
  (or (when-let [{:keys [slot nex-type jvm-type]} (get (:locals env) var-name)]
        {:kind :local
         :name var-name
         :slot slot
         :nex-type nex-type
         :jvm-type jvm-type})
      (when (and (:top-level? env)
                 (contains? (:var-types env) var-name))
        (let [nex-type (get (:var-types env) var-name)]
          {:kind :top
           :name var-name
           :nex-type nex-type
           :jvm-type (resolve-jvm-type env nex-type)}))))

(defn- ensure-convert-binding
  [env {:keys [var-name target-type]}]
  (if-let [binding (lookup-convert-binding env var-name)]
    [env binding]
    (let [bound-type (tc/detachable-version target-type)]
      (if (and (:top-level? env) (not (:scoped-locals? env)))
        (let [env' (update env :var-types assoc var-name bound-type)]
          [env' {:kind :top
                 :name var-name
                 :nex-type bound-type
                 :jvm-type (resolve-jvm-type env' bound-type)}])
        (let [[env' local] (env-add-local env var-name bound-type)]
          [env' {:kind :local
                 :name var-name
                 :slot (:slot local)
                 :nex-type (:nex-type local)
                 :jvm-type (:jvm-type local)}])))))

(defn- lower-convert-expression
  [env {:keys [value var-name target-type] :as expr}]
  (let [target-name (if (map? target-type) (:base-type target-type) target-type)
        _ (when (contains? (:generic-param-names env) target-name)
            (throw (ex-info "convert to generic parameter is not yet supported in compiled lowering"
                            {:expr expr
                             :target-type target-type})))
        binding (or (lookup-convert-binding env var-name)
                    (throw (ex-info "convert binding must exist before lowering expression"
                                    {:expr expr
                                     :var-name var-name})))
        [env' temp-slot] (alloc-temp-slot env)]
    [(assoc env :next-slot (:next-slot env'))
     (ir/convert-node (lower-expression env value)
                      binding
                      target-name
                      "Boolean"
                      :boolean
                      temp-slot)]))

(defn- refine-var-non-nil
  [env var-name]
  (let [current-type (or (get-in env [:locals var-name :nex-type])
                         (get (:var-types env) var-name))]
    (if current-type
      (let [refined-type (tc/attachable-type current-type)]
        (cond-> env
          (get-in env [:locals var-name])
          (-> (assoc-in [:locals var-name :nex-type] refined-type)
              (assoc-in [:locals var-name :jvm-type] (resolve-jvm-type env refined-type)))
          true
          (assoc-in [:var-types var-name] refined-type)))
      env)))

(defn- refine-condition-branch-env
  [env condition branch]
  (case branch
    :then
    (let [env' (if-let [var-name (tc/guarded-non-nil-var condition)]
                 (refine-var-non-nil env var-name)
                 env)]
      (if-let [{:keys [name type]} (tc/convert-guard-binding condition)]
        (refine-var-non-nil
         (cond
           (get-in env' [:locals name])
           (let [refined-type (tc/attachable-type type)]
             (-> env'
                 (assoc-in [:locals name :nex-type] refined-type)
                 (assoc-in [:locals name :jvm-type] (resolve-jvm-type env' refined-type))
                 (assoc-in [:var-types name] refined-type)))

           (:top-level? env')
           (assoc-in env' [:var-types name] (tc/attachable-type type))

           :else env')
         name)
        env'))

    :else
    (if-let [var-name (tc/guarded-else-non-nil-var condition)]
      (refine-var-non-nil env var-name)
      env)

    env))

(defn- class-jvm-meta
  [env class-name]
  (or (get (:compiled-classes env) class-name)
      (throw (ex-info "Missing compiled class metadata during lowering"
                      {:class-name class-name}))))

(defn- user-class-defs
  [program]
  (let [synthetic-class-names (set (keep :class-name (:functions program)))]
    (remove #(contains? synthetic-class-names (:name %))
            (:classes program))))

(defn- infer-prepass-type
  [ctx local-types expr]
  (or (tc/infer-expression-type expr {:classes (:classes ctx)
                                      :functions (:functions ctx)
                                      :imports (:imports ctx)
                                      :var-types (merge (:var-types ctx) local-types)})
      "Any"))

(defn- synthetic-capture-field
  [{:keys [name type]}]
  {:type :field
   :name name
   :field-type type
   :note nil
   :constant? false
   :synthetic? true})

(defn- next-synthetic-closure-class-name
  []
  (str "AnonymousFunction_" (swap! next-synthetic-closure-id inc)))

(defn- make-synthetic-anonymous-function-expr
  [params return-type body]
  (let [class-name (next-synthetic-closure-class-name)
        method-name (str "call" (count (or params [])))
        method-def {:type :method
                    :name method-name
                    :params params
                    :return-type return-type
                    :note nil
                    :require nil
                    :body body
                    :ensure nil}
        class-def {:type :class
                   :name class-name
                   :generic-params nil
                   :note nil
                   :parents [{:parent "Function"}]
                   :body [{:type :feature-section
                           :visibility {:type :public}
                           :members [method-def]}]
                   :invariant nil}]
    {:type :anonymous-function
     :class-name class-name
     :params params
     :return-type return-type
     :body body
     :class-def class-def}))

(defn- attach-capture-fields
  [class-def captures runtime-object?]
  (let [capture-members (mapv synthetic-capture-field captures)
        feature-sections (filter #(= :feature-section (:type %)) (:body class-def))
        first-section (first feature-sections)]
    (cond-> class-def
      true
      (assoc :closure-runtime-object? (boolean runtime-object?))

      (seq captures)
      (assoc :body
             (if first-section
               (mapv (fn [section]
                       (if (identical? section first-section)
                         (update section :members #(vec (concat capture-members %)))
                         section))
                     (:body class-def))
               (vec (cons {:type :feature-section
                           :visibility {:type :public}
                           :members capture-members}
                          (:body class-def))))))))

(declare rewrite-expression-for-closures)
(declare rewrite-statement-for-closures)
(declare rewrite-statements-for-closures)
(declare rewrite-statements-for-closures*)

(defn- capture-reference!
  [captures local-types outer-var-types name]
  (when (and (not (contains? local-types name))
             (contains? outer-var-types name))
    (swap! captures assoc name (get outer-var-types name))))

(defn- rewrite-expression-for-closures
  [ctx local-types captures expr]
  (cond
    (not (map? expr)) expr

    (= :identifier (:type expr))
    (do
      (capture-reference! captures local-types (:var-types ctx) (:name expr))
      expr)

    (= :anonymous-function (:type expr))
    (let [params (or (:params expr) [])
          fn-locals (into {"result" (or (:return-type expr) "Any")}
                          (map (fn [{:keys [name type]}] [name type]))
                          params)
          nested-ctx (assoc ctx :var-types (merge (:var-types ctx) local-types))
          [rewritten-body _ nested-captures]
          (rewrite-statements-for-closures nested-ctx fn-locals (:body expr))
          capture-vec (->> nested-captures
                           (map (fn [[name type]] {:name name :type type}))
                           (sort-by :name)
                           vec)
          runtime-object? (seq capture-vec)]
      (assoc expr
             :body rewritten-body
             :captures capture-vec
             :class-def (attach-capture-fields (:class-def expr) capture-vec runtime-object?)))

    (= :call (:type expr))
    (let [target (:target expr)
          method (:method expr)
          args (mapv #(rewrite-expression-for-closures ctx local-types captures %)
                     (:args expr))
          expr' (assoc expr
                       :target (when target
                                 (rewrite-expression-for-closures ctx local-types captures target))
                       :args args)]
      (when (and (nil? target)
                 (contains? (:var-types ctx) method)
                 (not (contains? local-types method)))
        (swap! captures assoc method (get (:var-types ctx) method)))
      expr')

    (= :binary (:type expr))
    (assoc expr
           :left (rewrite-expression-for-closures ctx local-types captures (:left expr))
           :right (rewrite-expression-for-closures ctx local-types captures (:right expr)))

    (= :unary (:type expr))
    (assoc expr :expr (rewrite-expression-for-closures ctx local-types captures (:expr expr)))

    (= :array-literal (:type expr))
    (assoc expr :elements (mapv #(rewrite-expression-for-closures ctx local-types captures %)
                                (:elements expr)))

    (= :set-literal (:type expr))
    (assoc expr :elements (mapv #(rewrite-expression-for-closures ctx local-types captures %)
                                (:elements expr)))

    (= :map-literal (:type expr))
    (assoc expr :entries (mapv (fn [{:keys [key value]}]
                                 {:key (rewrite-expression-for-closures ctx local-types captures key)
                                  :value (rewrite-expression-for-closures ctx local-types captures value)})
                               (:entries expr)))

    (= :if (:type expr))
    (assoc expr
           :condition (rewrite-expression-for-closures ctx local-types captures (:condition expr))
           :then (first (rewrite-statements-for-closures* ctx local-types captures (:then expr)))
           :elseif (mapv (fn [clause]
                           (assoc clause
                                  :condition (rewrite-expression-for-closures ctx local-types captures (:condition clause))
                                  :then (first (rewrite-statements-for-closures* ctx local-types captures (:then clause)))))
                         (:elseif expr))
           :else (first (rewrite-statements-for-closures* ctx local-types captures (:else expr))))

    (= :when (:type expr))
    (assoc expr
           :condition (rewrite-expression-for-closures ctx local-types captures (:condition expr))
           :consequent (rewrite-expression-for-closures ctx local-types captures (:consequent expr))
           :alternative (rewrite-expression-for-closures ctx local-types captures (:alternative expr)))

    (= :old (:type expr))
    (assoc expr :expr (rewrite-expression-for-closures ctx local-types captures (:expr expr)))

    (= :convert (:type expr))
    (assoc expr :value (rewrite-expression-for-closures ctx local-types captures (:value expr)))

    (= :create (:type expr))
    (assoc expr :args (mapv #(rewrite-expression-for-closures ctx local-types captures %) (:args expr)))

    (= :spawn (:type expr))
    (let [nested-ctx (assoc ctx :var-types (merge (:var-types ctx) local-types))
          fn-expr (make-synthetic-anonymous-function-expr
                   []
                   "Any"
                   (:body expr))
          rewritten-fn (rewrite-expression-for-closures nested-ctx {} (atom {}) fn-expr)]
      (assoc expr :fn-expr rewritten-fn))

    :else expr))

(defn- rewrite-statement-for-closures
  [ctx local-types captures stmt]
  (case (:type stmt)
    :let
    (let [value' (rewrite-expression-for-closures ctx local-types captures (:value stmt))
          stmt' (assoc stmt :value value')
          var-type (or (:var-type stmt)
                       (infer-prepass-type ctx local-types value'))]
      [stmt' (assoc local-types (:name stmt) var-type)])

    :assign
    [(assoc stmt :value (rewrite-expression-for-closures ctx local-types captures (:value stmt)))
     local-types]

    :member-assign
    [(assoc stmt
            :object (when (:object stmt)
                      (rewrite-expression-for-closures ctx local-types captures (:object stmt)))
            :value (rewrite-expression-for-closures ctx local-types captures (:value stmt)))
     local-types]

    :call
    [(rewrite-expression-for-closures ctx local-types captures stmt)
     local-types]

    :convert
    (let [value' (rewrite-expression-for-closures ctx local-types captures (:value stmt))
          stmt' (assoc stmt :value value')]
      [stmt' (assoc local-types
                    (:var-name stmt)
                    (tc/detachable-version (:target-type stmt)))])

    :if
    [(assoc stmt
            :condition (rewrite-expression-for-closures ctx local-types captures (:condition stmt))
            :then (first (rewrite-statements-for-closures* ctx local-types captures (:then stmt)))
            :elseif (mapv (fn [clause]
                            (assoc clause
                                   :condition (rewrite-expression-for-closures ctx local-types captures (:condition clause))
                                   :then (first (rewrite-statements-for-closures* ctx local-types captures (:then clause)))))
                          (:elseif stmt))
            :else (first (rewrite-statements-for-closures* ctx local-types captures (:else stmt))))
     local-types]

    :case
    [(assoc stmt
            :expr (rewrite-expression-for-closures ctx local-types captures (:expr stmt))
            :clauses (mapv (fn [clause]
                             (assoc clause
                                    :values (mapv #(rewrite-expression-for-closures ctx local-types captures %)
                                                  (:values clause))
                                    :body (first (rewrite-statement-for-closures ctx local-types captures (:body clause)))))
                           (:clauses stmt))
            :else (when (:else stmt)
                    (first (rewrite-statement-for-closures ctx local-types captures (:else stmt)))))
     local-types]

    :loop
    [(assoc stmt
            :init (first (rewrite-statements-for-closures* ctx local-types captures (:init stmt)))
            :until (rewrite-expression-for-closures ctx local-types captures (:until stmt))
            :variant (when (:variant stmt)
                       (rewrite-expression-for-closures ctx local-types captures (:variant stmt)))
            :invariant (mapv (fn [inv]
                               (assoc inv :condition (rewrite-expression-for-closures ctx local-types captures (:condition inv))))
                             (:invariant stmt))
            :body (first (rewrite-statements-for-closures* ctx local-types captures (:body stmt))))
     local-types]

    :select
    [(assoc stmt
            :clauses (mapv (fn [{:keys [expr alias body] :as clause}]
                             (assoc clause
                                    :expr (rewrite-expression-for-closures ctx local-types captures expr)
                                    :body (first (rewrite-statements-for-closures* ctx
                                                                                   (cond-> local-types
                                                                                     alias (assoc alias "Any"))
                                                                                   captures
                                                                                   body))))
                           (:clauses stmt))
            :timeout (when-let [timeout (:timeout stmt)]
                       (assoc timeout
                              :duration (rewrite-expression-for-closures ctx local-types captures (:duration timeout))
                              :body (first (rewrite-statements-for-closures* ctx local-types captures (:body timeout)))))
            :else (when (:else stmt)
                    (first (rewrite-statements-for-closures* ctx local-types captures (:else stmt)))))
     local-types]

    :scoped-block
    [(assoc stmt
            :body (first (rewrite-statements-for-closures* ctx local-types captures (:body stmt)))
            :rescue (when (:rescue stmt)
                      (first (rewrite-statements-for-closures* ctx (assoc local-types "exception" "Any") captures (:rescue stmt)))))
     local-types]

    :raise
    [(assoc stmt :value (rewrite-expression-for-closures ctx local-types captures (:value stmt)))
     local-types]

    [stmt local-types]))

(defn- rewrite-statements-for-closures*
  [ctx local-types captures statements]
  (loop [remaining (vec statements)
         current-local-types local-types
         rewritten []]
    (if-let [stmt (first remaining)]
      (let [[stmt' next-local-types] (rewrite-statement-for-closures ctx current-local-types captures stmt)]
        (recur (subvec remaining 1) next-local-types (conj rewritten stmt')))
      [rewritten current-local-types @captures])))

(defn- rewrite-statements-for-closures
  [ctx local-types statements]
  (rewrite-statements-for-closures* ctx local-types (atom {}) statements))

(defn- sync-callable-into-class-def
  [class-def callable]
  (let [callable-name (:name callable)
        callable-arity (count (or (:params callable) []))]
    (update class-def :body
            (fn [sections]
              (mapv (fn [section]
                      (if (= :feature-section (:type section))
                        (update section :members
                                (fn [members]
                                  (mapv (fn [member]
                                          (if (and (= :method (:type member))
                                                   (= callable-name (:name member))
                                                   (= callable-arity (count (or (:params member) []))))
                                            callable
                                            member))
                                        members)))
                        section))
                    sections)))))

(defn- rewrite-callable-for-closures
  [ctx callable initial-var-types]
  (let [params (or (:params callable) [])
        local-types (into {"result" (or (:return-type callable) "Any")}
                          (map (fn [{:keys [name type]}] [name type]))
                          params)
        [body _ _] (rewrite-statements-for-closures (assoc ctx :var-types initial-var-types)
                                                    local-types
                                                    (:body callable))
        rewritten-callable (cond-> (assoc callable :body body)
                             (:rescue callable)
                             (assoc :rescue (first (rewrite-statements-for-closures (assoc ctx :var-types initial-var-types)
                                                                                    (assoc local-types "exception" "Any")
                                                                                    (:rescue callable)))))]
    (cond-> rewritten-callable
      (:class-def callable)
      (assoc :class-def (sync-callable-into-class-def (:class-def callable) rewritten-callable)))))

(defn- rewrite-class-for-closures
  [ctx class-def]
  (let [field-types (field-type-map class-def)]
    (update class-def :body
            (fn [sections]
              (mapv (fn [section]
                      (case (:type section)
                        :feature-section
                        (update section :members
                                (fn [members]
                                  (mapv (fn [member]
                                          (if (= :method (:type member))
                                            (rewrite-callable-for-closures ctx member field-types)
                                            member))
                                        members)))

                        :constructors
                        (update section :constructors
                                (fn [ctors]
                                  (mapv #(rewrite-callable-for-closures ctx % field-types) ctors)))

                        section))
                    sections)))))

(defn prepare-program-for-closures
  [program opts]
  (let [visible-functions (vec (concat (:functions program) (:functions opts)))
        visible-classes (merge-visible-classes (builtin-class-defs)
                                               (:classes program)
                                               (:classes opts)
                                               (keep :class-def visible-functions))
        ctx {:classes visible-classes
             :functions visible-functions
             :imports (:imports program)
             :var-types (:var-types opts)}
        rewritten-functions (mapv #(rewrite-callable-for-closures ctx % (:var-types opts))
                                  (:functions program))
        [rewritten-statements _ _] (rewrite-statements-for-closures (assoc ctx :functions (vec (concat rewritten-functions (:functions opts))))
                                                                    (:var-types opts)
                                                                    (:statements program))
        rewritten-classes (mapv #(rewrite-class-for-closures ctx %) (:classes program))]
    (assoc program
           :functions rewritten-functions
           :statements rewritten-statements
           :classes rewritten-classes)))

(defn collect-anonymous-class-defs
  [node]
  (let [seen-order (atom [])
        found (atom {})]
    (letfn [(walk [x]
              (cond
                (map? x)
                (do
                  (when (= :anonymous-function (:type x))
                    (let [class-def (:class-def x)
                          class-name (:name class-def)]
                      (when-not (contains? @found class-name)
                        (swap! seen-order conj class-name))
                      (swap! found
                             (fn [m]
                               (let [existing (get m class-name)]
                                 (assoc m
                                        class-name
                                        (if (and existing
                                                 (not (:closure-runtime-object? class-def))
                                                 (:closure-runtime-object? existing))
                                          existing
                                          class-def)))))))
                  (doseq [v (vals x)]
                    (walk v)))

                (sequential? x)
                (doseq [v x]
                  (walk v))

                :else nil))]
      (walk node)
      (mapv @found @seen-order))))

(defn- lower-instance-dispatch
  [env target-expr method args has-parens]
  (let [target-type (infer-type env target-expr)
        base-type (base-type-name target-type)
        type-map (generic-type-map env target-type)
        target-ir (lower-expression env target-expr)
        class-def (get (visible-class-map env) base-type)
        field-def (when (and class-def (false? has-parens))
                    (class-field-def class-def method))
        method-def (when class-def
                     (or (class-method-def class-def method (count args))
                         (inherited-method-def env class-def method (count args))))]
    (cond
      (and (= (:type target-expr) :this)
           (if-let [{:keys [owner field carrier-owner carrier-field nex-type jvm-type carrier-jvm-type]}
                    (get (:fields env) method)]
             (false? has-parens)
             false))
      (let [{:keys [owner field carrier-owner carrier-field nex-type jvm-type carrier-jvm-type]}
            (get (:fields env) method)
            target' (if carrier-field
                      (ir/field-get-node (:internal-name (class-jvm-meta env carrier-owner))
                                         carrier-field
                                         (ir/this-node (:this-type env)
                                                       (exact-class-jvm-type env (:this-type env)))
                                         owner
                                         carrier-jvm-type)
                      (ir/this-node (:this-type env)
                                    (exact-class-jvm-type env (:this-type env))))]
        (ir/field-get-node (:internal-name (class-jvm-meta env owner))
                           field
                           target'
                           nex-type
                           jvm-type))

      field-def
      (let [nex-type (tc/resolve-generic-type (:field-type field-def) type-map)
            jvm-type (resolve-jvm-type env nex-type)]
        (if (= (:type target-expr) :this)
          (ir/field-get-node (:internal-name (class-jvm-meta env base-type))
                             method
                             target-ir
                             nex-type
                             jvm-type)
          (ir/call-runtime-node (str "user-field-get:" method)
                                [target-ir]
                                nex-type
                                jvm-type)))

      method-def
      (let [nex-type (tc/resolve-generic-type (function-return-type method-def) type-map)
            jvm-type (resolve-jvm-type env nex-type)]
        (if (= (:type target-expr) :this)
          (ir/call-virtual-node (:internal-name (class-jvm-meta env base-type))
                                (lowered-instance-method-name method-def)
                                (desc/repl-instance-method-descriptor)
                                target-ir
                                (mapv #(lower-expression env %) args)
                                nex-type
                                jvm-type)
          (ir/call-runtime-node (str "user-method:" method)
                                (into [target-ir] (mapv #(lower-expression env %) args))
                                nex-type
                                jvm-type)))

      (and (= (:type target-expr) :this)
           (get (direct-parent-method-map env (current-class-def env))
                [method (count args)]))
      (let [{:keys [owner-internal-name method-def carrier-owner carrier-field carrier-jvm-type]}
            (get (direct-parent-method-map env (current-class-def env))
                 [method (count args)])
            nex-type (function-return-type method-def)
            jvm-type (resolve-jvm-type env nex-type)]
        (ir/call-virtual-node owner-internal-name
                              (lowered-instance-method-name method-def)
                              (desc/repl-instance-method-descriptor)
                              (ir/field-get-node (:internal-name (class-jvm-meta env carrier-owner))
                                                 carrier-field
                                                 (ir/this-node (:this-type env)
                                                               (exact-class-jvm-type env (:this-type env)))
                                                 (:source-class (get (direct-parent-method-map env (current-class-def env))
                                                                     [method (count args)]))
                                                 carrier-jvm-type)
                              (mapv #(lower-expression env %) args)
                              nex-type
                              jvm-type))

      class-def
      (throw (ex-info "Unsupported user-defined target access during lowering"
                      {:target-type target-type :method method :has-parens has-parens}))

      :else
      nil)))

(declare lower-function)

(defn lower-expression
  [env expr]
  (case (:type expr)
    :integer (ir/const-node (:value expr) "Integer" :int)
    :real (ir/const-node (:value expr) "Real" :double)
    :string (ir/const-node (:value expr) "String" (ir/object-jvm-type "java/lang/String"))
    :char (ir/const-node (:value expr) "Char" :char)
    :boolean (ir/const-node (:value expr) "Boolean" :boolean)
    :nil (ir/const-node nil "Nil" (ir/object-jvm-type "java/lang/Object"))

    :array-literal
    (let [nex-type (infer-type env expr)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/array-literal-node (mapv #(lower-expression env %) (:elements expr))
                             nex-type
                             jvm-type))

    :map-literal
    (let [nex-type (infer-type env expr)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/map-literal-node (mapv (fn [{:keys [key value]}]
                                   {:key (lower-expression env key)
                                    :value (lower-expression env value)})
                                 (:entries expr))
                           nex-type
                           jvm-type))

    :set-literal
    (let [nex-type (infer-type env expr)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/set-literal-node (mapv #(lower-expression env %) (:elements expr))
                           nex-type
                           jvm-type))

    :identifier
    (if-let [{:keys [slot nex-type jvm-type]} (get (:locals env) (:name expr))]
      (ir/local-node (:name expr) slot nex-type jvm-type)
      (if-let [{:keys [owner field carrier-owner carrier-field nex-type jvm-type carrier-jvm-type]}
               (get (:fields env) (:name expr))]
        (let [target-ir (if carrier-field
                          (ir/field-get-node (:internal-name (class-jvm-meta env carrier-owner))
                                             carrier-field
                                             (ir/this-node (:this-type env)
                                                           (resolve-jvm-type env (:this-type env)))
                                             owner
                                             carrier-jvm-type)
                          (ir/this-node (:this-type env)
                                        (resolve-jvm-type env (:this-type env))))]
          (ir/field-get-node (:internal-name (class-jvm-meta env owner))
                             field
                             target-ir
                             nex-type
                             jvm-type))
        (if-let [constant (and (:current-class env)
                               (lookup-class-constant env (:current-class env) (:name expr)))]
          (let [owner (:declaring-class constant)
                nex-type (constant-nex-type env constant)
                jvm-type (resolve-jvm-type env nex-type)]
            (ir/static-field-get-node (:internal-name (class-jvm-meta env owner))
                                      (:name constant)
                                      nex-type
                                      jvm-type))
          (if-let [method-def (some-> (current-class-def env)
                                      ((fn [class-def]
                                         (or (class-method-def class-def (:name expr) 0)
                                             (inherited-method-def env class-def (:name expr) 0)))))]
            (lower-expression env {:type :call
                                   :target {:type :this}
                                   :method (:name expr)
                                   :args []
                                   :has-parens true})
            (let [nex-type (or (get (:var-types env) (:name expr))
                               (infer-type env expr))
                  jvm-type (resolve-jvm-type env nex-type)]
              (if (:top-level? env)
                (ir/top-get-node (:name expr) nex-type jvm-type)
                (throw (ex-info "Unknown local in non-top-level lowering"
                                {:name (:name expr)}))))))))

    :this
    (if (:this-type env)
      (ir/this-node (:this-type env)
                    (exact-class-jvm-type env (:this-type env)))
      (throw (ex-info "this is only valid in instance-method lowering"
                      {:expr expr})))

    :binary
    (let [left-ir (lower-expression env (:left expr))
          right-ir (lower-expression env (:right expr))
          inferred-type (infer-type env expr)
          nex-type (if (= "Any" inferred-type)
                     (cond
                       (#{"+" "-" "*" "/" "%"} (:operator expr))
                       (:nex-type left-ir)

                       (#{"and" "or" "=" "/=" "<" "<=" ">" ">="} (:operator expr))
                       "Boolean"

                       :else inferred-type)
                     inferred-type)
          jvm-type (resolve-jvm-type env nex-type)
          op (:operator expr)]
      (cond
        (and (= "+" op) (= "String" nex-type))
        (ir/call-runtime-node "op:string-concat" [left-ir right-ir] nex-type jvm-type)

        (= "^" op)
        (ir/call-runtime-node (case jvm-type
                                :int "op:pow-int"
                                :long "op:pow-long"
                                :double "op:pow-double"
                                (throw (ex-info "Unsupported power lowering type"
                                                {:expr expr :jvm-type jvm-type})))
                              [left-ir right-ir]
                              nex-type
                              jvm-type)

        (#{"+" "-" "*" "/" "%" "and" "or"} op)
        (ir/binary-node (get {"+" :add
                              "-" :sub
                              "*" :mul
                              "/" :div
                              "%" :mod
                              "and" :and
                              "or" :or}
                             op)
                        left-ir right-ir nex-type jvm-type)

        :else
        (ir/compare-node (get {">" :gt
                               ">=" :gte
                               "<" :lt
                               "<=" :lte
                               "=" :eq
                               "/=" :neq}
                              op)
                         left-ir right-ir nex-type jvm-type)))

    :unary
    (let [operand-ir (lower-expression env (:expr expr))
          nex-type (infer-type env expr)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/unary-node (get {"-" :neg
                           "not" :not}
                          (:operator expr))
                     operand-ir
                     nex-type
                     jvm-type))

    :if
    (let [elseif (:elseif expr)
          then-branch (:then expr)
          else-branch (:else expr)]
      (let [[cond-env test-ir] (if (= :convert (:type (:condition expr)))
                                 (let [[cond-env _] (ensure-convert-binding (scoped-child-env env) (:condition expr))
                                       [cond-env' convert-ir] (lower-convert-expression cond-env (:condition expr))]
                                   [cond-env' convert-ir])
                                 [env (lower-expression env (:condition expr))])
            then-env (refine-condition-branch-env cond-env (:condition expr) :then)
            else-env (refine-condition-branch-env env (:condition expr) :else)
            then-expr (if-branch-expression then-env then-branch)
            else-expr (elseif->else-expr else-env elseif else-branch)]
        (when (or (nil? then-expr)
                  (nil? else-expr))
          (throw (ex-info "Only expression-shaped or result-assignment if branches are supported in lowering"
                          {:expr expr})))
        (let [then-ir (lower-expression then-env then-expr)
              else-ir (lower-expression else-env else-expr)
              nex-type (infer-type env expr)
              jvm-type (resolve-jvm-type env nex-type)]
          (ir/if-node test-ir [then-ir] [else-ir] nex-type jvm-type))))

    :when
    (let [[cond-env test-ir] (if (= :convert (:type (:condition expr)))
                               (let [[cond-env _] (ensure-convert-binding (scoped-child-env env) (:condition expr))
                                     [cond-env' convert-ir] (lower-convert-expression cond-env (:condition expr))]
                                 [cond-env' convert-ir])
                               [env (lower-expression env (:condition expr))])
          then-ir (lower-expression (refine-condition-branch-env cond-env (:condition expr) :then) (:consequent expr))
          else-ir (lower-expression (refine-condition-branch-env env (:condition expr) :else) (:alternative expr))
          nex-type (infer-type env expr)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/if-node test-ir [then-ir] [else-ir] nex-type jvm-type))

    :old
    (lower-expression (old-env env) (:expr expr))

    :convert
    (second (lower-convert-expression env expr))

    :create
    (let [class-name (:class-name expr)
          compiled (get (:compiled-classes env) class-name)
          class-def (get (visible-class-map env) class-name)]
      (if (= class-name "Channel")
        (let [nex-type (infer-type env expr)]
          (case (:constructor expr)
            nil
            (do
              (when (seq (:args expr))
                (throw (ex-info "create Channel takes no arguments in compiled lowering"
                                {:expr expr})))
              (ir/call-runtime-node "create-channel"
                                    []
                                    nex-type
                                    (resolve-jvm-type env nex-type)))

            "with_capacity"
            (do
              (when-not (= 1 (count (:args expr)))
                (throw (ex-info "Channel.with_capacity expects exactly 1 argument in compiled lowering"
                                {:expr expr})))
              (ir/call-runtime-node "create-channel"
                                    [(lower-expression env (first (:args expr)))]
                                    nex-type
                                    (resolve-jvm-type env nex-type)))

            (throw (ex-info "Unsupported Channel constructor in compiled lowering"
                            {:expr expr
                             :constructor (:constructor expr)}))))
        (cond
          (and class-def (:import class-def))
          (do
            (when (:constructor expr)
              (throw (ex-info "Imported Java classes do not support named constructors on the compiled path"
                              {:expr expr
                               :class-name class-name
                               :constructor (:constructor expr)})))
            (let [nex-type (infer-type env expr)]
              (ir/call-runtime-node "java-create-object"
                                    (into [(ir/const-node class-name
                                                          "String"
                                                          (ir/object-jvm-type "java/lang/String"))]
                                          (mapv #(lower-expression env %) (:args expr)))
                                    nex-type
                                    (resolve-jvm-type env nex-type))))

          :else
          (do
            (when-not compiled
              (throw (ex-info "Create of non-compiled class is not supported in lowering"
                              {:expr expr :class-name class-name})))
            (when (:deferred? class-def)
              (throw (ex-info "Unsupported create of deferred class in compiled lowering"
                              {:expr expr :class-name class-name})))
            (if-let [constructor-name (:constructor expr)]
              (let [ctor-def (own-or-inherited-constructor-def env class-def constructor-name (count (:args expr)))]
                (when-not ctor-def
                  (throw (ex-info "Constructor not found during lowering"
                                  {:expr expr
                                   :class-name class-name
                                   :constructor constructor-name
                                   :arity (count (:args expr))})))
                (ir/call-virtual-node (:internal-name compiled)
                                      (lowered-constructor-method-name ctor-def)
                                      (desc/repl-instance-method-descriptor)
                                      (ir/new-node (:internal-name compiled)
                                                   class-name
                                                   (infer-type env expr)
                                                   (exact-class-jvm-type env class-name))
                                      (mapv #(lower-expression env %) (:args expr))
                                      (infer-type env expr)
                                      (resolve-jvm-type env (infer-type env expr))))
              (do
                (when (seq (:args expr))
                  (throw (ex-info "Only create ClassName or create ClassName.ctor(...) is supported in compiled lowering"
                                  {:expr expr})))
                (let [nex-type (infer-type env expr)]
                  (validate-object-state-ir env
                                            class-name
                                            (ir/new-node (:internal-name compiled)
                                                         class-name
                                                         nex-type
                                                         (resolve-jvm-type env nex-type))
                                            nex-type))))))))

    :anonymous-function
    (let [class-name (:class-name expr)
          compiled (get (:compiled-classes env) class-name)
          nex-type (infer-type env expr)
          captures (:captures expr)]
      (if (seq captures)
        (ir/call-runtime-node "make-captured-function-object"
                              (into [(ir/const-node class-name
                                                    "String"
                                                    (ir/object-jvm-type "java/lang/String"))]
                                    (mapcat (fn [{:keys [name]}]
                                              [(ir/const-node name
                                                              "String"
                                                              (ir/object-jvm-type "java/lang/String"))
                                               (lower-expression env {:type :identifier
                                                                      :name name})])
                                            captures))
                              nex-type
                              (ir/object-jvm-type "java/lang/Object"))
        (do
          (when-not compiled
            (throw (ex-info "Anonymous function class has not been compiled during lowering"
                            {:expr expr
                             :class-name class-name})))
          (ir/new-node (:internal-name compiled)
                       class-name
                       nex-type
                       (exact-class-jvm-type env class-name)))))

    :spawn
    (let [fn-expr (or (:fn-expr expr)
                      (make-synthetic-anonymous-function-expr [] "Any" (:body expr)))
          fn-ir (lower-expression env fn-expr)
          nex-type (infer-type env expr)]
      (ir/call-runtime-node "spawn-function-object"
                            [fn-ir]
                            nex-type
                            (resolve-jvm-type env nex-type)))

    :call
    (if (and (nil? (:target expr))
             (empty? (:args expr))
             (not (:has-parens expr)))
      (lower-expression env {:type :identifier
                             :name (:method expr)})
      (let [raw-target (:target expr)
            class-target-name (when (string? raw-target)
                                (some #(when (= (:name %) raw-target)
                                         (:name %))
                                      (:classes env)))
            target-expr (normalize-call-target raw-target)
            arg-irs (mapv #(lower-expression env %) (:args expr))]
        (if (and (map? target-expr)
                 (= :create (:type target-expr))
                 (nil? (:method expr)))
          (lower-expression env (assoc target-expr :args (:args expr)))
        (if (nil? target-expr)
          (cond
            (and (:this-type env)
                 (or (class-method-def (current-class-def env) (:method expr) (count (:args expr)))
                     (inherited-method-def env (current-class-def env) (:method expr) (count (:args expr)))))
            (let [method-def (or (class-method-def (current-class-def env) (:method expr) (count (:args expr)))
                                 (inherited-method-def env (current-class-def env) (:method expr) (count (:args expr))))
                  nex-type (function-return-type method-def)
                  jvm-type (resolve-jvm-type env nex-type)]
              (if (class-method-def (current-class-def env) (:method expr) (count (:args expr)))
                (ir/call-virtual-node (:internal-name (class-jvm-meta env (:this-type env)))
                                      (lowered-instance-method-name method-def)
                                      (desc/repl-instance-method-descriptor)
                                      (ir/this-node (:this-type env)
                                                    (exact-class-jvm-type env (:this-type env)))
                                      arg-irs
                                      nex-type
                                      jvm-type)
                (let [{:keys [owner-internal-name carrier-owner carrier-field carrier-jvm-type]}
                      (get (direct-parent-method-map env (current-class-def env))
                           [(:method expr) (count (:args expr))])]
                  (ir/call-virtual-node owner-internal-name
                                        (lowered-instance-method-name method-def)
                                        (desc/repl-instance-method-descriptor)
                                        (ir/field-get-node (:internal-name (class-jvm-meta env carrier-owner))
                                                           carrier-field
                                                           (ir/this-node (:this-type env)
                                                                         (exact-class-jvm-type env (:this-type env)))
                                                           (:source-class (get (direct-parent-method-map env (current-class-def env))
                                                                               [(:method expr) (count (:args expr))]))
                                                           carrier-jvm-type)
                                        arg-irs
                                        nex-type
                                        jvm-type))))

            (function-object-call? env (:method expr) (count (:args expr)))
            (let [nex-type (infer-type env expr)
                  jvm-type (resolve-jvm-type env nex-type)]
              (ir/call-function-node (lower-expression env {:type :identifier
                                                            :name (:method expr)})
                                     arg-irs
                                     nex-type
                                     jvm-type))

            (#{"await_all" "await_any"} (:method expr))
            (let [nex-type (infer-type env expr)
                  jvm-type (resolve-jvm-type env nex-type)]
              (ir/call-runtime-node (if (= "await_all" (:method expr))
                                      "op:await-all"
                                      "op:await-any")
                                    arg-irs
                                    nex-type
                                    jvm-type))

            (contains? builtin-function-names (:method expr))
            (let [nex-type (infer-type env expr)
                  jvm-type (resolve-jvm-type env nex-type)]
              (ir/call-runtime-node (:method expr) arg-irs nex-type jvm-type))

            :else
            (let [nex-type (infer-type env expr)
                  jvm-type (resolve-jvm-type env nex-type)]
              (ir/call-repl-fn-node (:method expr) arg-irs nex-type jvm-type)))
          (cond
            (and (= :identifier (:type target-expr))
                 (= "super" (:name target-expr)))
            (let [parent-name (single-super-parent-name env)
                  parent-def (get (visible-class-map env) parent-name)
                  parent-meta (class-jvm-meta env parent-name)
                  target-ir (ir/field-get-node (:internal-name (class-jvm-meta env (:this-type env)))
                                               (str "_parent_" parent-name)
                                               (ir/this-node (:this-type env)
                                                             (exact-class-jvm-type env (:this-type env)))
                                               parent-name
                                               (exact-class-jvm-type env parent-name))]
              (if (false? (:has-parens expr))
                (if-let [field-def (or (class-field-def parent-def (:method expr))
                                       (when-let [field-info (get (direct-parent-field-map env (current-class-def env))
                                                                  (:method expr))]
                                         {:field-type (:nex-type field-info)}))]
                  (let [nex-type (:field-type field-def)
                        jvm-type (resolve-jvm-type env nex-type)]
                    (ir/call-runtime-node (str "user-field-get:" (:method expr))
                                          [target-ir]
                                          nex-type
                                          jvm-type))
                  (let [method-def (or (class-method-def parent-def (:method expr) 0)
                                       (inherited-method-def env parent-def (:method expr) 0))]
                    (when-not method-def
                      (throw (ex-info "Undefined super feature access during lowering"
                                      {:expr expr
                                       :parent parent-name})))
                    (let [nex-type (function-return-type method-def)
                          jvm-type (resolve-jvm-type env nex-type)]
                      (ir/call-virtual-node (:internal-name parent-meta)
                                            (lowered-instance-method-name method-def)
                                            (desc/repl-instance-method-descriptor)
                                            target-ir
                                            []
                                            nex-type
                                            jvm-type))))
                (let [method-def (or (class-method-def parent-def (:method expr) (count (:args expr)))
                                     (inherited-method-def env parent-def (:method expr) (count (:args expr))))]
                  (when-not method-def
                    (throw (ex-info "Undefined super method call during lowering"
                                    {:expr expr
                                     :parent parent-name})))
                  (let [nex-type (function-return-type method-def)
                        jvm-type (resolve-jvm-type env nex-type)]
                    (ir/call-virtual-node (:internal-name parent-meta)
                                          (lowered-instance-method-name method-def)
                                          (desc/repl-instance-method-descriptor)
                                          target-ir
                                          arg-irs
                                          nex-type
                                          jvm-type)))))

            (and class-target-name
                 (:this-type env)
                 (some #(= class-target-name (:parent %))
                       (:parents (current-class-def env)))
                 (if-let [parent-def (get (visible-class-map env) class-target-name)]
                   (class-method-def parent-def (:method expr) (count (:args expr)))
                   false))
            (let [parent-meta (class-jvm-meta env class-target-name)
                  method-def (class-method-def (get (visible-class-map env) class-target-name)
                                               (:method expr)
                                               (count (:args expr)))
                  nex-type (function-return-type method-def)
                  jvm-type (resolve-jvm-type env nex-type)]
              (ir/call-virtual-node (:internal-name parent-meta)
                                    (lowered-instance-method-name method-def)
                                    (desc/repl-instance-method-descriptor)
                                    (ir/field-get-node (:internal-name (class-jvm-meta env (:this-type env)))
                                                       (str "_parent_" class-target-name)
                                                       (ir/this-node (:this-type env)
                                                                     (exact-class-jvm-type env (:this-type env)))
                                                       class-target-name
                                                       (exact-class-jvm-type env class-target-name))
                                    arg-irs
                                    nex-type
                                    jvm-type))

            (and class-target-name (false? (:has-parens expr)))
            (if-let [constant (lookup-class-constant env class-target-name (:method expr))]
              (let [owner (:declaring-class constant)
                    nex-type (constant-nex-type env constant)
                    jvm-type (resolve-jvm-type env nex-type)]
                (ir/static-field-get-node (:internal-name (class-jvm-meta env owner))
                                          (:name constant)
                                          nex-type
                                          jvm-type))
              (throw (ex-info "Unsupported class-target access during lowering"
                              {:expr expr
                               :target-class class-target-name})))

            :else
            (let [java-static-owner (java-host-class-root-name env target-expr)
                  target-type (when-not java-static-owner
                                (infer-type env target-expr))]
              (cond
                java-static-owner
                (let [nex-type (or (infer-call-type env expr) "Any")
                      jvm-type (resolve-jvm-type env nex-type)]
                  (if (:has-parens expr)
                    (ir/call-runtime-node "java-call-static"
                                          (into [(ir/const-node java-static-owner
                                                                "String"
                                                                (ir/object-jvm-type "java/lang/String"))
                                                 (ir/const-node (:method expr)
                                                                "String"
                                                                (ir/object-jvm-type "java/lang/String"))]
                                                arg-irs)
                                          nex-type
                                          jvm-type)
                    (ir/call-runtime-node "java-get-static-field"
                                          [(ir/const-node java-static-owner
                                                          "String"
                                                          (ir/object-jvm-type "java/lang/String"))
                                           (ir/const-node (:method expr)
                                                          "String"
                                                          (ir/object-jvm-type "java/lang/String"))]
                                          nex-type
                                          jvm-type)))

                (if-let [direct-op (and (= "Integer" (base-type-name target-type))
                                        (get direct-integer-bitwise-method->op (:method expr)))]
                  direct-op
                  false)
                (let [target-ir (lower-expression env target-expr)
                      nex-type (infer-type env expr)
                      jvm-type (resolve-jvm-type env nex-type)
                      direct-op (get direct-integer-bitwise-method->op (:method expr))]
                  (if (= :bit-not direct-op)
                    (ir/unary-node direct-op target-ir nex-type jvm-type)
                    (ir/binary-node direct-op
                                    target-ir
                                    (first arg-irs)
                                    nex-type
                                    jvm-type)))

                (direct-collection-method? target-type (:method expr))
                (let [target-ir (lower-expression env target-expr)
                      nex-type (or (collection-method-return-type target-type (:method expr))
                                   (infer-type env expr))
                      jvm-type (resolve-jvm-type env nex-type)]
                  (ir/collection-method-node (keyword (.toLowerCase ^String (base-type-name target-type)))
                                             (:method expr)
                                             target-ir
                                             arg-irs
                                             nex-type
                                             jvm-type))

                (direct-concurrency-method? target-type (:method expr))
                (let [target-ir (lower-expression env target-expr)
                      nex-type (infer-type env expr)
                      jvm-type (resolve-jvm-type env nex-type)]
                  (ir/concurrency-method-node (keyword (.toLowerCase ^String (base-type-name target-type)))
                                              (:method expr)
                                              target-ir
                                              arg-irs
                                              nex-type
                                              jvm-type))

                (imported-java-qualified-name env (base-type-name target-type))
                (let [target-ir (lower-expression env target-expr)
                      nex-type (or (infer-call-type env expr) "Any")
                      jvm-type (resolve-jvm-type env nex-type)]
                  (if (:has-parens expr)
                    (ir/call-runtime-node "java-call-method"
                                          (into [(ir/const-node (:method expr)
                                                                "String"
                                                                (ir/object-jvm-type "java/lang/String"))
                                                 target-ir]
                                                arg-irs)
                                          nex-type
                                          jvm-type)
                    (ir/call-runtime-node "java-get-field"
                                          [(ir/const-node (:method expr)
                                                          "String"
                                                          (ir/object-jvm-type "java/lang/String"))
                                           target-ir]
                                          nex-type
                                          jvm-type)))

                (:with-java? env)
                (let [target-ir (lower-expression env target-expr)
                      nex-type (or (infer-call-type env expr) "Any")
                      jvm-type (resolve-jvm-type env nex-type)]
                  (if (:has-parens expr)
                    (ir/call-runtime-node "java-call-method"
                                          (into [(ir/const-node (:method expr)
                                                                "String"
                                                                (ir/object-jvm-type "java/lang/String"))
                                                 target-ir]
                                                arg-irs)
                                          nex-type
                                          jvm-type)
                    (ir/call-runtime-node "java-get-field"
                                          [(ir/const-node (:method expr)
                                                          "String"
                                                          (ir/object-jvm-type "java/lang/String"))
                                           target-ir]
                                          nex-type
                                          jvm-type)))

                (builtin-runtime-receiver-type? target-type)
                (let [target-ir (lower-expression env target-expr)
                      base-type (base-type-name target-type)
                      nex-type (infer-type env expr)
                      jvm-type (resolve-jvm-type env nex-type)]
                  (ir/call-runtime-node (str "builtin-method:" base-type ":" (:method expr))
                                        (into [target-ir] arg-irs)
                                        nex-type
                                        jvm-type))

                :else
                (or (lower-instance-dispatch env target-expr (:method expr) (:args expr) (:has-parens expr))
                    (throw (ex-info "Unsupported target call expression for lowering"
                                    {:expr expr
                                     :target-type target-type}))))))))))

    (throw (ex-info "Unsupported expression node for lowering"
                    {:expr expr :node-type (:type expr)}))))

(defn lower-statement
  [env stmt]
  (let [[env' lowered]
        (cond
          (= :let (:type stmt))
          (let [across-binding (across-cursor-binding env stmt)
                [env0 value-ir] (cond
                                  across-binding
                                  [env (lower-expression env (:target-expr across-binding))]

                                  (= :convert (:type (:value stmt)))
                                  (let [[env' _] (ensure-convert-binding env (:value stmt))
                                        [env'' convert-ir] (lower-convert-expression env' (:value stmt))]
                                    [env'' convert-ir])

                                  :else
                                  [env (lower-expression env (:value stmt))])
                nex-type (or (:var-type stmt)
                             (:target-type across-binding)
                             (infer-type env0 (:value stmt)))
                env1 (if (and (:synthetic stmt)
                              (string? (:name stmt))
                              (str/starts-with? (:name stmt) "__across_c_")
                              (= :call (get-in stmt [:value :type]))
                              (= "cursor" (get-in stmt [:value :method]))
                              (empty? (get-in stmt [:value :args])))
                       (let [target-type (infer-type env0 (get-in stmt [:value :target]))]
                         (assoc-in env0 [:across-cursors (:name stmt)]
                                   (tc/cursor-item-type target-type)))
                       env0)]
            (if (and (:top-level? env) (not (:scoped-locals? env)))
              [(update env1 :var-types assoc (:name stmt) nex-type)
               (ir/top-set-node (:name stmt) value-ir nex-type (resolve-jvm-type env1 nex-type))]
              (let [[env' local] (env-add-local env1 (:name stmt) nex-type)]
                [env' (ir/set-local-node (:slot local) value-ir (:nex-type local) (:jvm-type local))])))

          (= :assign (:type stmt))
          (let [value-ir (lower-expression env (:value stmt))
                target-name (:target stmt)]
            (if-let [{:keys [slot nex-type jvm-type]} (get (:locals env) target-name)]
              [env (ir/set-local-node slot value-ir nex-type jvm-type)]
              (if-let [{:keys [owner field nex-type jvm-type]} (get (:fields env) target-name)]
                (let [field-info (get (:fields env) target-name)
                      target-ir (if-let [carrier-field (:carrier-field field-info)]
                                  (ir/field-get-node (:internal-name (class-jvm-meta env (:carrier-owner field-info)))
                                                     carrier-field
                                                     (ir/this-node (:this-type env)
                                                                   (exact-class-jvm-type env (:this-type env)))
                                                     owner
                                                     (:carrier-jvm-type field-info))
                                  (ir/this-node (:this-type env)
                                                (exact-class-jvm-type env (:this-type env))))]
                  [env (ir/field-set-node (:internal-name (class-jvm-meta env owner))
                                          field
                                          target-ir
                                          value-ir
                                          nex-type
                                          jvm-type)])
                (let [nex-type (or (get (:var-types env) target-name)
                                   (infer-type env {:type :identifier :name target-name}))
                      jvm-type (resolve-jvm-type env nex-type)]
                  (if (:top-level? env)
                    [(update env :var-types assoc target-name nex-type)
                     (ir/top-set-node target-name value-ir nex-type jvm-type)]
                    (throw (ex-info "Assignment target is not a known local"
                                    {:target target-name})))))))

          (= :member-assign (:type stmt))
          (let [field-name (:field stmt)
                target-expr (or (:object stmt) {:type :this})
                super-target? (and (= :identifier (:type target-expr))
                                   (= "super" (:name target-expr)))
                target-type (when-not super-target? (infer-type env target-expr))
                owner (base-type-name target-type)
                class-def (get (visible-class-map env) owner)
                field-def (when class-def (class-field-def class-def field-name))
                value-ir (lower-expression env (:value stmt))]
            (cond
              super-target?
              (let [parent-name (single-super-parent-name env)
                    target-ir (ir/field-get-node (:internal-name (class-jvm-meta env (:this-type env)))
                                                 (str "_parent_" parent-name)
                                                 (ir/this-node (:this-type env)
                                                               (exact-class-jvm-type env (:this-type env)))
                                                 parent-name
                                                 (exact-class-jvm-type env parent-name))]
                [env (ir/call-runtime-node (str "user-field-set:" field-name)
                                           [target-ir value-ir]
                                           "Void"
                                           :void)])

              (and (= (:type target-expr) :this)
                   (get (:fields env) field-name))
              (let [field-info (get (:fields env) field-name)
                    target-ir (if-let [carrier-field (:carrier-field field-info)]
                                (ir/field-get-node (:internal-name (class-jvm-meta env (:carrier-owner field-info)))
                                                   carrier-field
                                                   (ir/this-node (:this-type env)
                                                                 (exact-class-jvm-type env (:this-type env)))
                                                   (:owner field-info)
                                                   (:carrier-jvm-type field-info))
                                (ir/this-node (:this-type env)
                                              (exact-class-jvm-type env (:this-type env))))]
                [env (ir/field-set-node (:internal-name (class-jvm-meta env (:owner field-info)))
                                        field-name
                                        target-ir
                                        value-ir
                                        (:nex-type field-info)
                                        (:jvm-type field-info))])

              field-def
              [env (ir/call-runtime-node (str "user-field-set:" field-name)
                                         [(lower-expression env target-expr) value-ir]
                                         "Void"
                                         :void)]

              (imported-java-qualified-name env owner)
              [env (ir/call-runtime-node "java-set-field"
                                         [(ir/const-node field-name
                                                         "String"
                                                         (ir/object-jvm-type "java/lang/String"))
                                          (lower-expression env target-expr)
                                          value-ir]
                                         "Void"
                                         :void)]

              (:with-java? env)
              [env (ir/call-runtime-node "java-set-field"
                                         [(ir/const-node field-name
                                                         "String"
                                                         (ir/object-jvm-type "java/lang/String"))
                                          (lower-expression env target-expr)
                                          value-ir]
                                         "Void"
                                         :void)]

              :else
              (throw (ex-info "Unknown field in member assignment during lowering"
                              {:field field-name
                               :target target-expr
                               :target-type target-type}))))

          (= :call (:type stmt))
          (if-let [parent-name (cond
                           (and (:this-type env)
                                (string? (:target stmt))
                                (some #(= (:target stmt) (:parent %))
                                      (:parents (current-class-def env)))
                                (class-constructor-def (get (visible-class-map env) (:target stmt))
                                                       (:method stmt)
                                                       (count (:args stmt))))
                           (:target stmt)

                           (and (:this-type env)
                                (= "super" (:target stmt))
                                (class-constructor-def (get (visible-class-map env) (single-super-parent-name env))
                                                       (:method stmt)
                                                       (count (:args stmt))))
                           (single-super-parent-name env)

                           (and (:this-type env)
                                (map? (:target stmt))
                                (= :identifier (:type (:target stmt)))
                                (= "super" (:name (:target stmt)))
                                (class-constructor-def (get (visible-class-map env) (single-super-parent-name env))
                                                       (:method stmt)
                                                       (count (:args stmt))))
                           (single-super-parent-name env)

                           :else nil)]
            (let [ctor-def (class-constructor-def (get (visible-class-map env) parent-name)
                                                  (:method stmt)
                                                  (count (:args stmt)))
                  parent-meta (class-jvm-meta env parent-name)
                  call-ir (ir/call-virtual-node (:internal-name parent-meta)
                                                (lowered-constructor-method-name ctor-def)
                                                (desc/repl-instance-method-descriptor)
                                                (ir/field-get-node (:internal-name (class-jvm-meta env (:this-type env)))
                                                                   (str "_parent_" parent-name)
                                                                   (ir/this-node (:this-type env)
                                                                                 (exact-class-jvm-type env (:this-type env)))
                                                                   parent-name
                                                                   (exact-class-jvm-type env parent-name))
                                                (mapv #(lower-expression env %) (:args stmt))
                                                parent-name
                                                (resolve-jvm-type env parent-name))]
              [env (ir/pop-node call-ir)])
            [env (ir/pop-node (lower-expression env stmt))])

          (= :convert (:type stmt))
          (let [[env' _] (ensure-convert-binding env stmt)
                [env'' convert-ir] (lower-convert-expression env' stmt)]
            [env'' (ir/pop-node convert-ir)])

          (= :with (:type stmt))
          (if (= "java" (:target stmt))
            (let [[env' lowered] (lower-statements (assoc env :with-java? true) (:body stmt))]
              [env' (with-stmt-debug (ir/block-node lowered) stmt)])
            [env (with-stmt-debug (ir/block-node []) stmt)])

          (= :if (:type stmt))
          (let [[cond-env test-ir] (if (= :convert (:type (:condition stmt)))
                                     (let [[cond-env _] (ensure-convert-binding (scoped-child-env env) (:condition stmt))
                                           [cond-env' convert-ir] (lower-convert-expression cond-env (:condition stmt))]
                                       [cond-env' convert-ir])
                                     [env (lower-expression env (:condition stmt))])
                [then-env then-body] (lower-scoped-statements (refine-condition-branch-env cond-env (:condition stmt) :then)
                                                              (:then stmt))
                [else-env else-body]
                (if-let [clause (first (:elseif stmt))]
                  (lower-scoped-statements
                   (scoped-env env then-env)
                   [{:type :if
                     :condition (:condition clause)
                     :then (:then clause)
                     :elseif (vec (rest (:elseif stmt)))
                     :else (:else stmt)}])
                  (lower-scoped-statements (refine-condition-branch-env (scoped-env env then-env)
                                                                        (:condition stmt)
                                                                        :else)
                                           (or (:else stmt) [])))]
            [(scoped-env env else-env)
             (ir/if-stmt-node test-ir then-body else-body)])

          (= :scoped-block (:type stmt))
          (if-let [rescue (:rescue stmt)]
            (let [[env1 throwable-slot] (alloc-temp-slot env)
                  [env2 rescue-throwable-slot] (alloc-temp-slot env1)
                  [body-env lowered-body] (lower-statements (scoped-child-env env2) (:body stmt))
                  env-after-body (scoped-env env2 body-env)
                  rescue-env0 (assoc (scoped-child-env env-after-body) :retry-allowed? true)
                  [rescue-env1 exception-local] (env-add-local rescue-env0 "exception" "Any")
                  [rescue-env2 lowered-rescue] (lower-statements rescue-env1 rescue)
                  final-env (scoped-env env-after-body rescue-env2)]
              [final-env
               (ir/try-node lowered-body
                            lowered-rescue
                            throwable-slot
                            rescue-throwable-slot
                            (:slot exception-local))])
            (let [[env' lowered] (lower-scoped-statements env (:body stmt))]
              [env' (ir/block-node lowered)]))

          (= :case (:type stmt))
          (let [case-env (scoped-child-env env)
                [env' local] (env-add-local case-env (str "__case_tmp_" (:next-slot env) "__")
                                            (infer-type env (:expr stmt)))
                init-local (ir/set-local-node (:slot local)
                                              (lower-expression env (:expr stmt))
                                              (:nex-type local)
                                              (:jvm-type local))
                [env'' lowered-clauses] (lower-case-clauses env' local (:clauses stmt)
                                                            (if-let [else-stmt (:else stmt)]
                                                              [else-stmt]
                                                              []))]
            [(scoped-env env env'')
             (ir/block-node (into [init-local] lowered-clauses))])

          (= :loop (:type stmt))
          (let [loop-env (scoped-child-env env)
                [env-after-init lowered-init] (lower-statements loop-env (:init stmt))
                invariant-start-stmts (mapv #(assertion-ir env-after-init :invariant %) (:invariant stmt))
                [env-with-variant variant-init-stmts variant-prefix]
                (if-let [variant-expr (:variant stmt)]
                  (let [variant-type (infer-type env-after-init variant-expr)
                        hidden-id (:next-slot env-after-init)
                        prev-name (str "__loop_variant_prev_" hidden-id)
                        curr-name (str "__loop_variant_curr_" hidden-id)
                        seen-name (str "__loop_variant_seen_" hidden-id)
                        [env1 prev-local] (env-add-local env-after-init prev-name variant-type)
                        [env2 curr-local] (env-add-local env1 curr-name variant-type)
                        [env3 seen-local] (env-add-local env2 seen-name "Boolean")
                        curr-node (ir/local-node curr-name
                                                 (:slot curr-local)
                                                 (:nex-type curr-local)
                                                 (:jvm-type curr-local))
                        prev-node (ir/local-node prev-name
                                                 (:slot prev-local)
                                                 (:nex-type prev-local)
                                                 (:jvm-type prev-local))
                        seen-node (ir/local-node seen-name
                                                 (:slot seen-local)
                                                 (:nex-type seen-local)
                                                 (:jvm-type seen-local))
                        compare-node (ir/compare-node :lt curr-node prev-node "Boolean" :boolean)]
                    [env3
                     [(ir/set-local-node (:slot prev-local)
                                         (default-const-node (:nex-type prev-local) (:jvm-type prev-local))
                                         (:nex-type prev-local)
                                         (:jvm-type prev-local))
                      (ir/set-local-node (:slot seen-local)
                                         (ir/const-node false "Boolean" :boolean)
                                         "Boolean"
                                         :boolean)]
                     [(ir/set-local-node (:slot curr-local)
                                         (lower-expression env3 variant-expr)
                                         (:nex-type curr-local)
                                         (:jvm-type curr-local))
                      (ir/if-stmt-node seen-node
                                       [(ir/assert-node :variant "must decrease" compare-node)]
                                       [])
                      (ir/set-local-node (:slot prev-local)
                                         curr-node
                                         (:nex-type prev-local)
                                         (:jvm-type prev-local))
                      (ir/set-local-node (:slot seen-local)
                                         (ir/const-node true "Boolean" :boolean)
                                         "Boolean"
                                         :boolean)]])
                  [env-after-init [] []])
                test-ir (lower-expression env-with-variant (:until stmt))
                [env-after-body lowered-body] (lower-statements env-with-variant (:body stmt))
                invariant-end-stmts (mapv #(assertion-ir env-after-body :invariant %) (:invariant stmt))
                loop-body (vec (concat variant-prefix lowered-body invariant-end-stmts))]
            [(scoped-env env env-after-body)
             (ir/block-node
              (vec (concat lowered-init
                           invariant-start-stmts
                           variant-init-stmts
                           [(ir/loop-node [] test-ir loop-body)])))])

          (= :select (:type stmt))
          (lower-select env stmt)

          (= :raise (:type stmt))
          [env (ir/raise-node (lower-expression env (:value stmt)))]

          (= :retry (:type stmt))
          (if (:retry-allowed? env)
            [env (ir/retry-node)]
            (throw (ex-info "retry is only supported in compiled rescue blocks"
                            {:stmt stmt})))

          (contains? expression-node-types (:type stmt))
          [env (ir/pop-node (lower-expression env stmt))]

          :else
          (throw (ex-info "Unsupported statement node for lowering"
                          {:stmt stmt :node-type (:type stmt)})))]
    [env' (with-stmt-debug lowered stmt)]))

(defn lower-statements
  [env statements]
  (reduce (fn [[env' out] stmt]
            (let [[next-env lowered] (lower-statement env' stmt)]
              [next-env (conj out lowered)]))
          [env []]
          statements))

(defn- lower-repl-tail
  [env stmt]
  (cond
    (= :if (:type stmt))
    [env [] (lower-expression env stmt)]

    (contains? expression-node-types (:type stmt))
    [env [] (lower-expression env stmt)]

    (= :call (:type stmt))
    [env [] (lower-expression env stmt)]

    (= :let (:type stmt))
    (let [[env' lowered] (lower-statement env stmt)
          nex-type (or (:var-type stmt) (infer-type env (:value stmt)))
          jvm-type (resolve-jvm-type env' nex-type)]
      [env' [lowered] (if (:top-level? env')
                        (ir/top-get-node (:name stmt) nex-type jvm-type)
                        (ir/local-node (:name stmt)
                                       (:slot (get (:locals env') (:name stmt)))
                                       nex-type
                                       jvm-type))])

    (= :assign (:type stmt))
    (let [[env' lowered] (lower-statement env stmt)
          nex-type (or (get (:var-types env') (:target stmt))
                       (infer-type env' {:type :identifier :name (:target stmt)}))
          jvm-type (resolve-jvm-type env' nex-type)]
      [env' [lowered] (if-let [{:keys [slot]} (get (:locals env') (:target stmt))]
                        (ir/local-node (:target stmt) slot nex-type jvm-type)
                        (ir/top-get-node (:target stmt) nex-type jvm-type))])

    (= :convert (:type stmt))
    (let [[env' _] (ensure-convert-binding env stmt)
          [env'' convert-ir] (lower-convert-expression env' stmt)]
      [env'' [] convert-ir])

    :else
    [env [] nil]))

(defn- repl-tail-returns-value?
  [env stmt]
  (cond
    (= :if (:type stmt))
    (let [then-env (refine-condition-branch-env
                    (if (= :convert (get-in stmt [:condition :type]))
                      (assoc-in env [:var-types (get-in stmt [:condition :var-name])]
                                (get-in stmt [:condition :target-type]))
                      env)
                    (:condition stmt)
                    :then)
          else-env (refine-condition-branch-env env (:condition stmt) :else)]
      (and (some? (if-branch-expression then-env (:then stmt)))
           (some? (elseif->else-expr else-env (:elseif stmt) (:else stmt)))))

    (contains? expression-node-types (:type stmt))
    true

    (= :call (:type stmt))
    true

    (= :let (:type stmt))
    true

    (= :assign (:type stmt))
    true

    (= :convert (:type stmt))
    true

    :else
    false))

(defn lower-function
  [unit-name visible-functions visible-imports fn-def]
  (let [fn-def (normalized-function-def fn-def)
        return-type (function-return-type fn-def)
        visible-classes (vec (concat (:visible-classes fn-def)
                                     [(:class-def fn-def)]
                                     (keep :class-def visible-functions)))
        current-class (:class-name fn-def)
        generic-param-names (set (map :name (:generic-params (:class-def fn-def))))
        env0 (make-lowering-env {:classes visible-classes
                                 :functions visible-functions
                                 :imports visible-imports
                                 :var-types (field-type-map (:class-def fn-def))
                                 :compiled-classes (:compiled-classes fn-def)
                                 :current-class current-class
                                 :generic-param-names generic-param-names
                                 :fields (field-info-map {:compiled-classes (:compiled-classes fn-def)
                                                          :classes visible-classes
                                                          :generic-param-names generic-param-names}
                                                         (:class-def fn-def))
                                 :this-type current-class
                                 :top-level? false
                                 :repl? true
                                 :state-slot 1
                                 :next-slot 3})
        [env-with-params params]
        (reduce (fn [[env acc] {:keys [name type]}]
                  (let [[env' local] (env-add-local env name type)]
                    [env' (conj acc (assoc local :arg-index (count acc)))]))
                [env0 []]
                (:params fn-def))
        [env-with-result result-local]
        (if (:return-type fn-def)
          (let [[env' local] (env-add-local env-with-params "result" return-type)]
            [(env-add-local-alias env' "Result" local) local])
          [env-with-params nil])
        [env-with-old old-snapshot-stmts old-field-locals]
        (add-old-field-snapshots env-with-result (:ensure fn-def))
        env-with-old (assoc env-with-old :old-field-locals old-field-locals)
        body (vec (:body fn-def))]
    (if (or (:declaration-only? fn-def)
            (:deferred? fn-def))
      (ir/fn-node {:name (:name fn-def)
                   :owner unit-name
                   :emitted-name (if (:class-name fn-def)
                                   (lowered-instance-method-name fn-def)
                                   (lowered-function-method-name fn-def))
                   :params params
                   :return-type return-type
                   :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                   :locals (vec (vals (:locals env-with-params)))
                   :body []
                   :deferred? true
                   :override? (boolean (:override? fn-def))})
      (let [body-stmts
            (if (:return-type fn-def)
              (if (empty? body)
                [env-with-old []]
                (let [leading-statements (butlast body)
                      final-stmt (last body)]
                  (if (or (and (= :assign (:type final-stmt))
                               (= "result" (:target final-stmt)))
                          (and (contains? expression-node-types (:type final-stmt))
                               (or (not= :if (:type final-stmt))
                                   (implicit-if-expression? env-with-old final-stmt)))
                          (= :call (:type final-stmt))
                          (= :convert (:type final-stmt)))
                    (let [[env' lowered-leading] (lower-statements env-with-old leading-statements)
                          implicit-result-expr? (and (not (and (= :assign (:type final-stmt))
                                                               (= "result" (:target final-stmt))))
                                                     (not= "Void" (infer-type env' final-stmt)))
                          final-expr (when implicit-result-expr? final-stmt)
                          [env'' lowered-tail]
                          (if final-expr
                            [env' (with-stmt-debug
                                    (ir/set-local-node (:slot result-local)
                                                       (lower-expression env' final-expr)
                                                       (:nex-type result-local)
                                                       (:jvm-type result-local))
                                    final-stmt)]
                            (lower-statement env' final-stmt))]
                      [env'' (conj lowered-leading lowered-tail)])
                    ;; Statement-shaped tails are valid as long as they assign to `result`
                    ;; somewhere in the lowered body.
                    (lower-statements env-with-old body))))
              (lower-statements env-with-old body))
            [env-after-body raw-body-stmts] body-stmts
            [env-after-rescue lowered-body] (lower-body-with-rescue env-after-body raw-body-stmts (:rescue fn-def))
            require-stmts (mapv #(assertion-ir env-with-old :require %) (:require fn-def))
            ensure-env (assoc env-after-rescue :old-field-locals old-field-locals)
            ensure-stmts (mapv #(assertion-ir ensure-env :ensure %) (:ensure fn-def))
            class-validation-stmts (if (and (:class-def fn-def)
                                            (get (:compiled-classes fn-def) current-class))
                                     [(ir/pop-node
                                       (validate-object-state-ir ensure-env
                                                                 current-class
                                                                 (ir/this-node current-class
                                                                               (exact-class-jvm-type ensure-env current-class))
                                                                 current-class))]
                                     [])
            return-stmt (if (:return-type fn-def)
                          (with-stmt-debug
                            (ir/return-node (ir/local-node "result"
                                                           (:slot result-local)
                                                           (:nex-type result-local)
                                                           (:jvm-type result-local))
                                            return-type
                                            (ir/object-jvm-type "java/lang/Object"))
                            (last body))
                          nil)]
        (ir/fn-node {:name (:name fn-def)
                     :owner unit-name
                     :emitted-name (if (:class-name fn-def)
                                     (lowered-instance-method-name fn-def)
                                     (lowered-function-method-name fn-def))
                     :params params
                     :return-type return-type
                     :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                     :locals (vec (vals (:locals env-after-rescue)))
                     :body (cond-> (into []
                                         (concat old-snapshot-stmts
                                                 require-stmts
                                                 lowered-body
                                                 ensure-stmts
                                                 class-validation-stmts))
                             return-stmt
                             (conj return-stmt))
                     :deferred? (boolean (:deferred? fn-def))
                     :override? (boolean (:override? fn-def))})))))

(defn- lower-constructor
  [unit-name visible-functions visible-imports visible-classes class-def ctor-def compiled-classes]
  (let [class-name (:name class-def)
        generic-param-names (set (map :name (:generic-params class-def)))
        env0 (make-lowering-env {:classes visible-classes
                                 :functions visible-functions
                                 :imports visible-imports
                                 :var-types (field-type-map class-def)
                                 :compiled-classes compiled-classes
                                 :current-class class-name
                                 :generic-param-names generic-param-names
                                 :fields (field-info-map {:compiled-classes compiled-classes
                                                          :classes visible-classes
                                                          :generic-param-names generic-param-names}
                                                         class-def)
                                 :this-type class-name
                                 :top-level? false
                                 :repl? true
                                 :state-slot 1
                                 :next-slot 3})
        [env-with-params params]
        (reduce (fn [[env acc] {:keys [name type]}]
                  (let [[env' local] (env-add-local env name type)]
                    [env' (conj acc (assoc local :arg-index (count acc)))]))
                [env0 []]
                (:params ctor-def))
        [env-with-old old-snapshot-stmts old-field-locals]
        (add-old-field-snapshots env-with-params (:ensure ctor-def))
        env-with-old (assoc env-with-old :old-field-locals old-field-locals)]
    (if-let [shim-parent (:shim-parent ctor-def)]
      (let [parent-meta (class-jvm-meta {:compiled-classes compiled-classes} shim-parent)
            target-ir (ir/field-get-node (:internal-name (class-jvm-meta {:compiled-classes compiled-classes} class-name))
                                         (str "_parent_" shim-parent)
                                         (ir/this-node class-name
                                                       (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                                         shim-parent
                                         (exact-class-jvm-type {:compiled-classes compiled-classes} shim-parent))
            call-ir (ir/call-virtual-node (:internal-name parent-meta)
                                          (lowered-constructor-method-name ctor-def)
                                          (desc/repl-instance-method-descriptor)
                                          target-ir
                                          (mapv (fn [{:keys [name]}]
                                                  (let [{:keys [slot nex-type jvm-type]}
                                                        (get (:locals env-with-params) name)]
                                                    (ir/local-node name slot nex-type jvm-type)))
                                                (:params ctor-def))
                                          shim-parent
                                          (resolve-jvm-type {:compiled-classes compiled-classes} shim-parent))]
        (ir/fn-node {:name (:name ctor-def)
                     :owner unit-name
                     :emitted-name (lowered-constructor-method-name ctor-def)
                    :params params
                     :return-type class-name
                     :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                     :locals (vec (vals (:locals env-with-old)))
                     :body (vec (concat old-snapshot-stmts
                                        (map #(assertion-ir env-with-old :require %) (:require ctor-def))
                                        [(ir/pop-node call-ir)]
                                        (map #(assertion-ir env-with-old :ensure %) (:ensure ctor-def))
                                        [(ir/return-node
                                          (validate-object-state-ir {:compiled-classes compiled-classes}
                                                                    class-name
                                                                    (ir/this-node class-name
                                                                                  (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                                                                    class-name)
                                          class-name
                                          (ir/object-jvm-type "java/lang/Object"))]))}))
      (let [[env-after-body raw-body] (lower-statements env-with-old (vec (:body ctor-def)))
            [env-after-rescue lowered-body] (lower-body-with-rescue env-after-body raw-body (:rescue ctor-def))]
        (ir/fn-node {:name (:name ctor-def)
                     :owner unit-name
                     :emitted-name (lowered-constructor-method-name ctor-def)
                     :params params
                     :return-type class-name
                     :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                     :locals (vec (vals (:locals env-after-rescue)))
                     :body (vec (concat old-snapshot-stmts
                                        (map #(assertion-ir env-with-old :require %) (:require ctor-def))
                                        lowered-body
                                        (map #(assertion-ir (assoc env-after-rescue :old-field-locals old-field-locals)
                                                            :ensure %)
                                             (:ensure ctor-def))
                                        [(ir/return-node
                                          (validate-object-state-ir {:compiled-classes compiled-classes}
                                                                    class-name
                                                                    (ir/this-node class-name
                                                                                  (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                                                                    class-name)
                                          class-name
                                          (ir/object-jvm-type "java/lang/Object"))]))})))))

(defn- make-delegation-method-node
  [env class-meta class-name compiled-classes {:keys [source-class carrier-owner carrier-field owner-internal-name method-def carrier-jvm-type]}]
  (let [return-type (function-return-type method-def)
        params (map-indexed (fn [idx {:keys [name type]}]
                              {:name name
                               :slot (+ 2 idx)
                               :arg-index idx
                               :nex-type type
                               :jvm-type (resolve-jvm-type {:compiled-classes compiled-classes} type)})
                            (:params method-def))
        result-slot (+ 2 (reduce + (map (fn [{:keys [jvm-type]}]
                                          (if (#{:long :double} jvm-type) 2 1))
                                        params)))
        call-args (mapv (fn [{:keys [name slot nex-type jvm-type]}]
                          (ir/local-node name slot nex-type jvm-type))
                        params)
        target-ir (ir/field-get-node (:internal-name (class-jvm-meta env carrier-owner))
                                     carrier-field
                                     (ir/this-node class-name
                                                   (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                                     source-class
                                     carrier-jvm-type)
        call-ir (ir/call-virtual-node owner-internal-name
                                      (lowered-instance-method-name method-def)
                                      (desc/repl-instance-method-descriptor)
                                      target-ir
                                      call-args
                                      return-type
                                      (resolve-jvm-type {:compiled-classes compiled-classes} return-type))
        class-validation (ir/pop-node
                          (validate-object-state-ir {:compiled-classes compiled-classes}
                                                    class-name
                                                    (ir/this-node class-name
                                                                  (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                                                    class-name))]
    (ir/fn-node {:name (:name method-def)
                 :owner (:jvm-name class-meta)
                 :emitted-name (lowered-instance-method-name method-def)
                 :params params
                 :return-type return-type
                 :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                 :locals (vec params)
                 :body (if return-type
                         [(ir/set-local-node result-slot
                                             call-ir
                                             return-type
                                             (resolve-jvm-type {:compiled-classes compiled-classes} return-type))
                          class-validation
                          (ir/return-node (ir/local-node "__result"
                                                         result-slot
                                                         return-type
                                                         (resolve-jvm-type {:compiled-classes compiled-classes} return-type))
                                          return-type
                                          (ir/object-jvm-type "java/lang/Object"))]
                         [(ir/pop-node call-ir)
                          class-validation])
                 :override? false})))

(defn lower-class-def
  [class-def opts]
  (let [compiled-classes (:compiled-classes opts)
        class-name (:name class-def)
        class-meta (class-jvm-meta {:compiled-classes compiled-classes} class-name)
        env (make-lowering-env {:classes (:classes opts)
                                :functions (:functions opts)
                                :imports (:imports opts)
                                :compiled-classes compiled-classes
                                :generic-param-names (set (map :name (:generic-params class-def)))})
        visible-functions (vec (:functions opts))
        visible-imports (vec (:imports opts))
        own-ctor-names (set (map :name (class-constructors class-def)))
        inherited-shims (->> (:parents class-def)
                             (remove #(= "Any" (:parent %)))
                             (mapcat (fn [{:keys [parent]}]
                                       (let [parent-def (get (visible-class-map {:classes (:classes opts)}) parent)]
                                         (for [ctor-def (class-constructors parent-def)
                                               :when (not (contains? own-ctor-names (:name ctor-def)))]
                                           (assoc ctor-def :shim-parent parent)))))
                             vec)
        constructors (->> (concat (class-constructors class-def) inherited-shims)
                          (mapv (fn [ctor-def]
                                  (lower-constructor (:jvm-name class-meta)
                                                     visible-functions
                                                     visible-imports
                                                     (:classes opts)
                                                     class-def
                                                     ctor-def
                                                     compiled-classes))))
        own-methods (->> (class-methods class-def)
                         (mapv (fn [method-def]
                                 (lower-function (:jvm-name class-meta)
                                                 visible-functions
                                                 visible-imports
                                                 (assoc method-def
                                                        :class-name class-name
                                                        :class-def class-def
                                                        :visible-classes (:classes opts)
                                                        :deferred? (lowered-deferred-method? class-def method-def)
                                                        :override? (method-override? env class-def method-def)
                                                        :compiled-classes compiled-classes)))))
        own-method-names (set (map (fn [m] [(:name m) (count (:params m))]) (class-methods class-def)))
        delegation-methods (->> (direct-parent-method-map env class-def)
                                vals
                                (remove (fn [{:keys [method-def]}]
                                          (contains? own-method-names
                                                     [(:name method-def) (count (or (:params method-def) []))])))
                                (mapv #(make-delegation-method-node env
                                                                    class-meta
                                                                    class-name
                                                                    compiled-classes
                                                                    %)))
        methods (vec (concat own-methods delegation-methods))
        fields (mapv (fn [field]
                       {:name (:name field)
                        :nex-type (:field-type field)
                        :jvm-type (resolve-jvm-type {:compiled-classes compiled-classes
                                                     :generic-param-names (set (map :name (:generic-params class-def)))}
                                                    (:field-type field))})
                     (remove :constant? (class-fields class-def)))
        constants (mapv (fn [field]
                          (let [constant-env (make-lowering-env {:classes (:classes opts)
                                                                 :functions visible-functions
                                                                 :imports visible-imports
                                                                 :compiled-classes compiled-classes
                                                                 :generic-param-names (set (map :name (:generic-params class-def)))
                                                                 :current-class class-name
                                                                 :this-type class-name
                                                                 :top-level? false
                                                                 :repl? true})
                                nex-type (or (:field-type field)
                                             (infer-type constant-env (:value field)))]
                            {:name (:name field)
                             :nex-type nex-type
                             :jvm-type (resolve-jvm-type {:compiled-classes compiled-classes
                                                         :generic-param-names (set (map :name (:generic-params class-def)))}
                                                        nex-type)
                             :value (lower-expression constant-env (:value field))}))
                        (filter :constant? (class-fields class-def)))]
    {:name class-name
     :jvm-name (:jvm-name class-meta)
     :internal-name (:internal-name class-meta)
     :source-file (:source-file opts)
     :deferred? (boolean (:deferred? class-def))
     :parents (resolve-parent-metas env class-def)
     :composition-fields (mapv (fn [{:keys [nex-name internal-name composition-field deferred?]}]
                                 {:name composition-field
                                  :parent nex-name
                                  :deferred? deferred?
                                  :jvm-type (exact-class-jvm-type {:compiled-classes compiled-classes} nex-name)})
                               (resolve-parent-metas env class-def))
     :fields fields
     :constants constants
     :constructors constructors
     :methods methods}))

(defn lower-repl-cell
  "Lower a narrow REPL/program body to a first compiler unit."
  [program opts]
  (let [unit-name (or (:name opts) "nex/repl/Cell_0001")
        actual-classes (vec (user-class-defs program))
        anonymous-classes (vec (collect-anonymous-class-defs program))
        emitted-anonymous-classes (vec (remove :closure-runtime-object? anonymous-classes))
        visible-imports (vec (or (:imports opts) (:imports program)))
        imported-classes (->> visible-imports
                              (keep (fn [{:keys [qualified-name source]}]
                                      (when (and (nil? source) qualified-name)
                                        {:name (last (str/split qualified-name #"\."))
                                         :body []
                                         :import qualified-name})))
                              vec)
        visible-functions (vec (concat (:functions program) (:functions opts)))
        visible-classes (merge-visible-classes (builtin-class-defs)
                                               imported-classes
                                               actual-classes
                                               anonymous-classes
                                               (:classes opts)
                                               (keep :class-def visible-functions))
        env (make-lowering-env {:classes visible-classes
                                :functions visible-functions
                                :imports visible-imports
                                :compiled-classes (:compiled-classes opts)
                                :var-types (:var-types opts)
                                :top-level? true
                                :repl? true
                                :state-slot 0
                                :next-slot 1})
        statements (vec (:statements program))
        tail-stmt (last statements)
        return-tail? (repl-tail-returns-value? env tail-stmt)
        leading-statements (if return-tail? (pop statements) statements)
        [env' lowered-body] (lower-statements env leading-statements)
        [env'' tail-stmts final-expr-ir] (if return-tail?
                                          (lower-repl-tail env' tail-stmt)
                                          [env' [] nil])
        lowered-body' (if return-tail?
                        (into lowered-body tail-stmts)
                        lowered-body)
        lowered-body''
        (cond
          (and final-expr-ir (= "Void" (:nex-type final-expr-ir)))
          (conj lowered-body'
                (with-stmt-debug (ir/pop-node final-expr-ir) tail-stmt)
                (with-stmt-debug
                  (ir/return-node
                   (ir/const-node nil "Any"
                                  (ir/object-jvm-type "java/lang/Object"))
                   "Any"
                   (ir/object-jvm-type "java/lang/Object"))
                  tail-stmt))

          final-expr-ir
          (conj lowered-body'
                (with-stmt-debug
                  (ir/return-node
                   final-expr-ir
                   (:nex-type final-expr-ir)
                   (ir/object-jvm-type "java/lang/Object"))
                  tail-stmt))

          :else
          (conj lowered-body'
                (ir/return-node
                 (ir/const-node nil "Any"
                                (ir/object-jvm-type "java/lang/Object"))
                 "Any"
                 (ir/object-jvm-type "java/lang/Object"))))]
    {:env env''
     :unit (ir/unit {:name (or (:name opts) "nex/repl/Cell_0001")
                     :kind :repl-cell
                     :source-file (:source-file opts)
                     :locals (vec (vals (:locals env'')))
                     :classes (mapv #(lower-class-def % {:compiled-classes (:compiled-classes opts)
                                                         :classes visible-classes
                                                         :functions visible-functions
                                                         :imports visible-imports
                                                         :source-file (:source-file opts)})
                                    (concat actual-classes emitted-anonymous-classes))
                     :functions (mapv #(lower-function unit-name
                                                       visible-functions
                                                       visible-imports
                                                       (assoc %
                                                              :visible-classes visible-classes
                                                              :compiled-classes (:compiled-classes opts)))
                                      (remove :declaration-only? (:functions program)))
                     :body lowered-body''
                     :result-jvm-type (ir/object-jvm-type "java/lang/Object")})}))
