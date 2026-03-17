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
  (:require [nex.compiler.jvm.descriptor :as desc]
            [nex.interpreter :as interp]
            [nex.ir :as ir]
            [nex.typechecker :as tc]))

(declare lower-expression)
(declare lower-statement)
(declare lower-class-def)
(declare if-branch-expression)
(declare current-class-def)
(declare class-method-def)
(declare class-field-def)
(declare visible-class-map)
(declare normalize-call-target)
(declare function-return-type)

(def ^:private expression-node-types
  #{:integer :real :string :char :boolean :nil :identifier :binary :call :if :this})

(def ^:private builtin-function-names
  (set (keys interp/builtins)))

(def ^:private builtin-runtime-receiver-types
  #{"Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Task" "Channel" "Console" "Process"})

(defn- base-type-name
  [t]
  (cond
    (string? t) t
    (map? t) (:base-type t)
    :else nil))

(defn- builtin-runtime-receiver-type?
  [t]
  (contains? builtin-runtime-receiver-types (base-type-name t)))

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
  - `:this-type` current receiver type when lowering instance methods"
  ([] (make-lowering-env {}))
  ([{:keys [locals top-level? repl? state-slot next-slot classes functions imports var-types
            compiled-classes current-class fields this-type] :as opts}]
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
    :this-type this-type}))

(defn- resolve-jvm-type
  [env nex-type]
  (let [base (base-type-name nex-type)]
    (if-let [compiled (get (:compiled-classes env) base)]
      (ir/object-jvm-type (:internal-name compiled))
      (desc/nex-type->jvm-type nex-type))))

(defn- env-visible-var-types
  [env]
  (merge (:var-types env)
         (into {}
               (map (fn [[name {:keys [nex-type]}]]
                      [name nex-type])
                    (:locals env)))))

(defn- infer-type
  [env expr]
  (let [direct-type
        (case (:type expr)
          :identifier
          (or (get-in (:locals env) [(:name expr) :nex-type])
              (get-in (:fields env) [(:name expr) :nex-type])
              (some-> (current-class-def env)
                      (class-field-def (:name expr))
                      :field-type)
              (get (:var-types env) (:name expr)))

          :create
          (:class-name expr)

          :this
          (:this-type env)

          :binary
          (let [op (:operator expr)]
            (cond
              (#{"+" "-" "*" "/"} op) (infer-type env (:left expr))
              (#{"and" "or" "=" "/=" "<" "<=" ">" ">="} op) "Boolean"
              :else nil))

          :if
          (or (some-> (:then expr) if-branch-expression (infer-type env))
              (some-> (:else expr) if-branch-expression (infer-type env)))

          :call
          (let [target-expr (normalize-call-target (:target expr))]
            (if (nil? target-expr)
              (when (:this-type env)
                (some-> (class-method-def (current-class-def env) (:method expr) (count (:args expr)))
                        function-return-type))
              (let [target-type (infer-type env target-expr)
                    base-type (base-type-name target-type)
                    class-def (get (visible-class-map env) base-type)]
                (when class-def
                  (if (false? (:has-parens expr))
                    (or (some-> (class-field-def class-def (:method expr))
                                :field-type)
                        (some-> (class-method-def class-def (:method expr) (count (:args expr)))
                                function-return-type))
                    (some-> (class-method-def class-def (:method expr) (count (:args expr)))
                            function-return-type))))))

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

(defn- env-add-local
  [env name nex-type]
  (let [jvm-type (resolve-jvm-type env nex-type)
        slot (:next-slot env)]
    [(assoc env
            :locals (assoc (:locals env)
                           name
                           {:name name
                            :slot slot
                            :nex-type nex-type
                            :jvm-type jvm-type})
            :var-types (assoc (:var-types env) name nex-type)
            :next-slot (inc slot))
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

(defn- if-branch-expression
  [branch]
  (when (= 1 (count branch))
    (let [stmt (first branch)]
      (cond
        (contains? expression-node-types (:type stmt))
        stmt

        (and (= :assign (:type stmt))
             (= "result" (:target stmt)))
        (:value stmt)

        :else
        nil))))

(defn- visible-class-map
  [env]
  (into {} (map (juxt :name identity) (:classes env))))

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

(defn- class-methods
  [class-def]
  (filter #(= :method (:type %)) (class-members class-def)))

(defn- class-constructors
  [class-def]
  (filter #(= :constructor (:type %)) (class-members class-def)))

(defn- class-field-def
  [class-def field-name]
  (some #(when (= (:name %) field-name) %) (class-fields class-def)))

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

(defn- field-info-map
  [env class-def]
  (into {}
        (map (fn [field]
               [(:name field)
                {:owner (:name class-def)
                 :field (:name field)
                 :nex-type (:field-type field)
                 :jvm-type (resolve-jvm-type env (:field-type field))}]))
        (class-fields class-def)))

(defn- field-type-map
  [class-def]
  (into {}
        (map (fn [field]
               [(:name field) (:field-type field)]))
        (class-fields class-def)))

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

(defn- lower-instance-dispatch
  [env target-expr method args has-parens]
  (let [target-type (infer-type env target-expr)
        base-type (base-type-name target-type)
        target-ir (lower-expression env target-expr)
        class-def (get (visible-class-map env) base-type)
        field-def (when (and class-def (false? has-parens))
                    (class-field-def class-def method))
        method-def (when class-def
                     (class-method-def class-def method (count args)))]
    (cond
      field-def
      (let [nex-type (:field-type field-def)
            jvm-type (resolve-jvm-type env nex-type)]
        (ir/field-get-node (:internal-name (class-jvm-meta env base-type))
                           method
                           target-ir
                           nex-type
                           jvm-type))

      method-def
      (let [nex-type (function-return-type method-def)
            jvm-type (resolve-jvm-type env nex-type)]
        (ir/call-virtual-node (:internal-name (class-jvm-meta env base-type))
                              (lowered-instance-method-name method-def)
                              (desc/repl-instance-method-descriptor)
                              target-ir
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

    :identifier
    (if-let [{:keys [slot nex-type jvm-type]} (get (:locals env) (:name expr))]
      (ir/local-node (:name expr) slot nex-type jvm-type)
      (if-let [{:keys [owner field nex-type jvm-type]} (get (:fields env) (:name expr))]
        (ir/field-get-node (:internal-name (class-jvm-meta env owner))
                           field
                           (ir/this-node (:this-type env)
                                         (resolve-jvm-type env (:this-type env)))
                           nex-type
                           jvm-type)
        (let [nex-type (or (get (:var-types env) (:name expr))
                           (infer-type env expr))
              jvm-type (resolve-jvm-type env nex-type)]
          (if (:top-level? env)
            (ir/top-get-node (:name expr) nex-type jvm-type)
            (throw (ex-info "Unknown local in non-top-level lowering"
                            {:name (:name expr)}))))))

    :this
    (if (:this-type env)
      (ir/this-node (:this-type env)
                    (resolve-jvm-type env (:this-type env)))
      (throw (ex-info "this is only valid in instance-method lowering"
                      {:expr expr})))

    :binary
    (let [left-ir (lower-expression env (:left expr))
          right-ir (lower-expression env (:right expr))
          nex-type (infer-type env expr)
          jvm-type (desc/nex-type->jvm-type nex-type)
          op (:operator expr)]
      (if (#{"+" "-" "*" "/" "and" "or"} op)
        (ir/binary-node (get {"+" :add
                              "-" :sub
                              "*" :mul
                              "/" :div
                              "and" :and
                              "or" :or}
                             op)
                        left-ir right-ir nex-type jvm-type)
        (ir/compare-node (get {">" :gt
                               ">=" :gte
                               "<" :lt
                               "<=" :lte
                               "=" :eq
                               "/=" :neq}
                              op)
                         left-ir right-ir nex-type jvm-type)))

    :if
    (let [elseif (:elseif expr)
          then-branch (:then expr)
          else-branch (:else expr)
          then-expr (if-branch-expression then-branch)
          else-expr (if-branch-expression else-branch)]
      (when (seq elseif)
        (throw (ex-info "Elseif is not yet supported in lowering"
                        {:expr expr})))
      (when (or (nil? then-expr)
                (nil? else-expr))
        (throw (ex-info "Only expression-shaped or result-assignment if branches are supported in lowering"
                        {:expr expr})))
      (let [test-ir (lower-expression env (:condition expr))
            then-ir (lower-expression env then-expr)
            else-ir (lower-expression env else-expr)
            nex-type (infer-type env expr)
            jvm-type (resolve-jvm-type env nex-type)]
        (ir/if-node test-ir [then-ir] [else-ir] nex-type jvm-type)))

    :create
    (let [class-name (:class-name expr)
          compiled (get (:compiled-classes env) class-name)
          class-def (get (visible-class-map env) class-name)]
      (when (seq (:generic-args expr))
        (throw (ex-info "Generic create is not yet supported in compiled lowering"
                        {:expr expr})))
      (when-not compiled
        (throw (ex-info "Create of non-compiled class is not supported in lowering"
                        {:expr expr :class-name class-name})))
      (if-let [constructor-name (:constructor expr)]
        (let [ctor-def (class-constructor-def class-def constructor-name (count (:args expr)))]
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
                                             class-name
                                             (ir/object-jvm-type (:internal-name compiled)))
                                (mapv #(lower-expression env %) (:args expr))
                                class-name
                                (ir/object-jvm-type (:internal-name compiled))))
        (do
          (when (seq (:args expr))
            (throw (ex-info "Only create ClassName or create ClassName.ctor(...) is supported in compiled lowering"
                            {:expr expr})))
          (ir/new-node (:internal-name compiled)
                       class-name
                       class-name
                       (ir/object-jvm-type (:internal-name compiled))))))

    :call
    (if (and (nil? (:target expr))
             (empty? (:args expr))
             (not (:has-parens expr)))
      (lower-expression env {:type :identifier
                             :name (:method expr)})
      (let [target-expr (normalize-call-target (:target expr))
            arg-irs (mapv #(lower-expression env %) (:args expr))]
        (if (nil? target-expr)
          (cond
            (and (:this-type env)
                 (class-method-def (current-class-def env) (:method expr) (count (:args expr))))
            (let [method-def (class-method-def (current-class-def env) (:method expr) (count (:args expr)))
                  nex-type (function-return-type method-def)
                  jvm-type (resolve-jvm-type env nex-type)]
              (ir/call-virtual-node (:internal-name (class-jvm-meta env (:this-type env)))
                                    (lowered-instance-method-name method-def)
                                    (desc/repl-instance-method-descriptor)
                                    (ir/this-node (:this-type env)
                                                  (resolve-jvm-type env (:this-type env)))
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
          (let [target-type (infer-type env target-expr)]
            (if (builtin-runtime-receiver-type? target-type)
              (let [target-ir (lower-expression env target-expr)
                    nex-type (infer-type env expr)
                    jvm-type (resolve-jvm-type env nex-type)]
                (ir/call-runtime-node (str "method:" (:method expr))
                                      (into [target-ir] arg-irs)
                                      nex-type
                                      jvm-type))
              (or (lower-instance-dispatch env target-expr (:method expr) (:args expr) (:has-parens expr))
                  (throw (ex-info "Unsupported target call expression for lowering"
                                  {:expr expr
                                   :target-type target-type}))))))))

    (throw (ex-info "Unsupported expression node for lowering"
                    {:expr expr :node-type (:type expr)}))))

(defn lower-statement
  [env stmt]
  (cond
    (= :let (:type stmt))
    (let [value-ir (lower-expression env (:value stmt))
          nex-type (or (:var-type stmt) (infer-type env (:value stmt)))]
      (if (:top-level? env)
        [(update env :var-types assoc (:name stmt) nex-type)
         (ir/top-set-node (:name stmt) value-ir nex-type (desc/nex-type->jvm-type nex-type))]
        (let [[env' local] (env-add-local env (:name stmt) nex-type)]
          [env' (ir/set-local-node (:slot local) value-ir (:nex-type local) (:jvm-type local))])))

    (= :assign (:type stmt))
    (let [value-ir (lower-expression env (:value stmt))
          target-name (:target stmt)]
      (if-let [{:keys [slot nex-type jvm-type]} (get (:locals env) target-name)]
        [env (ir/set-local-node slot value-ir nex-type jvm-type)]
        (if-let [{:keys [owner field nex-type jvm-type]} (get (:fields env) target-name)]
          [env (ir/field-set-node (:internal-name (class-jvm-meta env owner))
                                  field
                                  (ir/this-node (:this-type env)
                                                (resolve-jvm-type env (:this-type env)))
                                  value-ir
                                  nex-type
                                  jvm-type)]
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
          target-type (infer-type env target-expr)
          owner (base-type-name target-type)
          class-def (get (visible-class-map env) owner)
          field-def (when class-def (class-field-def class-def field-name))
          value-ir (lower-expression env (:value stmt))]
      (when-not field-def
        (throw (ex-info "Unknown field in member assignment during lowering"
                        {:field field-name
                         :target target-expr
                         :target-type target-type})))
      [env (ir/field-set-node (:internal-name (class-jvm-meta env owner))
                              field-name
                              (lower-expression env target-expr)
                              value-ir
                              (:field-type field-def)
                              (resolve-jvm-type env (:field-type field-def)))])

    (= :call (:type stmt))
    [env (ir/pop-node (lower-expression env stmt))]

    (contains? expression-node-types (:type stmt))
    [env (ir/pop-node (lower-expression env stmt))]

    :else
    (throw (ex-info "Unsupported statement node for lowering"
                    {:stmt stmt :node-type (:type stmt)}))))

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
    (contains? expression-node-types (:type stmt))
    [env [] (lower-expression env stmt)]

    (= :call (:type stmt))
    [env [] (lower-expression env stmt)]

    (= :let (:type stmt))
    (let [[env' lowered] (lower-statement env stmt)
          nex-type (or (:var-type stmt) (infer-type env (:value stmt)))
          jvm-type (desc/nex-type->jvm-type nex-type)]
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
          jvm-type (desc/nex-type->jvm-type nex-type)]
      [env' [lowered] (if-let [{:keys [slot]} (get (:locals env') (:target stmt))]
                        (ir/local-node (:target stmt) slot nex-type jvm-type)
                        (ir/top-get-node (:target stmt) nex-type jvm-type))])

    :else
    [env [] nil]))

(defn lower-function
  [unit-name visible-functions visible-imports fn-def]
  (let [return-type (function-return-type fn-def)
        visible-classes (vec (concat [(:class-def fn-def)]
                                     (keep :class-def visible-functions)))
        current-class (:class-name fn-def)
        env0 (make-lowering-env {:classes visible-classes
                                 :functions visible-functions
                                 :imports visible-imports
                                 :var-types (field-type-map (:class-def fn-def))
                                 :compiled-classes (:compiled-classes fn-def)
                                 :current-class current-class
                                 :fields (field-info-map {:compiled-classes (:compiled-classes fn-def)} (:class-def fn-def))
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
        body (vec (:body fn-def))
        leading-statements (butlast body)
        final-stmt (last body)
        [env' lowered-leading] (lower-statements env-with-params leading-statements)
        return-expr (cond
                      (and (= :assign (:type final-stmt))
                           (= "result" (:target final-stmt)))
                      (lower-expression env' (:value final-stmt))

                      (contains? expression-node-types (:type final-stmt))
                      (lower-expression env' final-stmt)

                      :else
                      (throw (ex-info "Unsupported function tail for lowering"
                                      {:function (:name fn-def)
                                       :stmt final-stmt})))]
    (ir/fn-node {:name (:name fn-def)
                 :owner unit-name
                 :emitted-name (if (:class-name fn-def)
                                 (lowered-instance-method-name fn-def)
                                 (lowered-function-method-name fn-def))
                 :params params
                 :return-type return-type
                 :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                 :locals (vec (vals (:locals env')))
                 :body (conj lowered-leading
                             (ir/return-node return-expr
                                             return-type
                                             (ir/object-jvm-type "java/lang/Object")))})))

(defn- lower-constructor
  [unit-name visible-functions visible-imports class-def ctor-def compiled-classes]
  (let [class-name (:name class-def)
        env0 (make-lowering-env {:classes [class-def]
                                 :functions visible-functions
                                 :imports visible-imports
                                 :var-types (field-type-map class-def)
                                 :compiled-classes compiled-classes
                                 :current-class class-name
                                 :fields (field-info-map {:compiled-classes compiled-classes} class-def)
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
        [env' lowered-body] (lower-statements env-with-params (vec (:body ctor-def)))]
    (ir/fn-node {:name (:name ctor-def)
                 :owner unit-name
                 :emitted-name (lowered-constructor-method-name ctor-def)
                 :params params
                 :return-type class-name
                 :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                 :locals (vec (vals (:locals env')))
                 :body (conj lowered-body
                             (ir/return-node
                              (ir/this-node class-name
                                            (resolve-jvm-type {:compiled-classes compiled-classes} class-name))
                              class-name
                              (ir/object-jvm-type "java/lang/Object")))})))

(defn lower-class-def
  [class-def opts]
  (let [compiled-classes (:compiled-classes opts)
        class-name (:name class-def)
        class-meta (class-jvm-meta {:compiled-classes compiled-classes} class-name)
        visible-functions (vec (:functions opts))
        visible-imports (vec (:imports opts))
        constructors (->> (class-constructors class-def)
                          (mapv (fn [ctor-def]
                                  (lower-constructor (:jvm-name class-meta)
                                                     visible-functions
                                                     visible-imports
                                                     class-def
                                                     ctor-def
                                                     compiled-classes))))
        methods (->> (class-methods class-def)
                     (mapv (fn [method-def]
                             (lower-function (:jvm-name class-meta)
                                             visible-functions
                                             visible-imports
                                             (assoc method-def
                                                    :class-name class-name
                                                    :class-def class-def
                                                    :compiled-classes compiled-classes)))))
        fields (mapv (fn [field]
                       {:name (:name field)
                        :nex-type (:field-type field)
                        :jvm-type (resolve-jvm-type {:compiled-classes compiled-classes} (:field-type field))})
                     (class-fields class-def))]
    {:name class-name
     :jvm-name (:jvm-name class-meta)
     :internal-name (:internal-name class-meta)
     :fields fields
     :constructors constructors
     :methods methods}))

(defn lower-repl-cell
  "Lower a narrow REPL/program body to a first compiler unit."
  [program opts]
  (let [unit-name (or (:name opts) "nex/repl/Cell_0001")
        actual-classes (vec (user-class-defs program))
        visible-functions (vec (concat (:functions program) (:functions opts)))
        visible-classes (vec (concat actual-classes
                                     (:classes opts)
                                     (keep :class-def visible-functions)))
        env (make-lowering-env {:classes visible-classes
                                :functions visible-functions
                                :imports (:imports program)
                                :compiled-classes (:compiled-classes opts)
                                :var-types (:var-types opts)
                                :top-level? true
                                :repl? true
                                :state-slot 0
                                :next-slot 1})
        statements (vec (:statements program))
        tail-stmt (last statements)
        return-tail? (or (contains? expression-node-types (:type tail-stmt))
                         (= :call (:type tail-stmt))
                         (= :let (:type tail-stmt))
                         (= :assign (:type tail-stmt)))
        leading-statements (if return-tail? (pop statements) statements)
        [env' lowered-body] (lower-statements env leading-statements)
        [env'' tail-stmts final-expr-ir] (if return-tail?
                                          (lower-repl-tail env' tail-stmt)
                                          [env' [] nil])
        lowered-body' (if return-tail?
                        (into lowered-body tail-stmts)
                        lowered-body)
        lowered-body'' (if final-expr-ir
                         (if (= "Void" (:nex-type final-expr-ir))
                           (conj lowered-body'
                                 (ir/pop-node final-expr-ir)
                                 (ir/return-node
                                  (ir/const-node nil "Any"
                                                 (ir/object-jvm-type "java/lang/Object"))
                                  "Any"
                                  (ir/object-jvm-type "java/lang/Object")))
                           (conj lowered-body'
                                 (ir/return-node
                                  final-expr-ir
                                  (:nex-type final-expr-ir)
                                  (ir/object-jvm-type "java/lang/Object"))))
                         (conj lowered-body'
                               (ir/return-node
                                (ir/const-node nil "Any"
                                               (ir/object-jvm-type "java/lang/Object"))
                                "Any"
                                (ir/object-jvm-type "java/lang/Object"))))]
    {:env env''
     :unit (ir/unit {:name (or (:name opts) "nex/repl/Cell_0001")
                     :kind :repl-cell
                     :classes (mapv #(lower-class-def % {:compiled-classes (:compiled-classes opts)
                                                         :functions visible-functions
                                                         :imports (:imports program)})
                                    actual-classes)
                     :functions (mapv #(lower-function unit-name visible-functions (:imports program) %)
                                      (remove :declaration-only? (:functions program)))
                     :body lowered-body''
                     :result-jvm-type (ir/object-jvm-type "java/lang/Object")})}))
