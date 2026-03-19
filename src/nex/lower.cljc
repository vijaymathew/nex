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
(declare lower-statements)
(declare lower-class-def)
(declare if-branch-expression)
(declare current-class-def)
(declare class-method-def)
(declare class-field-def)
(declare visible-class-map)
(declare normalize-call-target)
(declare function-return-type)
(declare lookup-class-constant)
(declare constant-nex-type)
(declare resolve-parent-metas)
(declare method-override?)
(declare class-jvm-meta)
(declare inherited-method-def)

(def ^:private expression-node-types
  #{:integer :real :string :char :boolean :nil :identifier :binary :unary :call :if :when :this})

(def ^:private builtin-function-names
  (set (keys interp/builtins)))

(def ^:private builtin-runtime-receiver-types
  #{"Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"
    "Array" "Map" "Set" "Cursor" "Task" "Channel" "Console" "Process"})

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
  - `:this-type` current receiver type when lowering instance methods
  - `:scoped-locals?` force `let` bindings to lower to JVM locals even in top-level code"
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
    :this-type this-type
    :scoped-locals? false}))

(defn- resolve-jvm-type
  [env nex-type]
  (let [base (base-type-name nex-type)]
    (if-let [compiled (get (:compiled-classes env) base)]
      (ir/object-jvm-type "java/lang/Object")
      (desc/nex-type->jvm-type nex-type))))

(defn- exact-class-jvm-type
  [env class-name]
  (ir/object-jvm-type (:internal-name (class-jvm-meta env class-name))))

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
              (some-> (and (:current-class env)
                           (lookup-class-constant env (:current-class env) (:name expr)))
                      (#(constant-nex-type env %)))
              (get (:var-types env) (:name expr)))

          :create
          (:class-name expr)

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

          :if
          (or (some-> (:then expr) if-branch-expression (infer-type env))
              (some-> (:else expr) if-branch-expression (infer-type env)))

          :call
          (let [raw-target (:target expr)
                class-target-name (when (string? raw-target)
                                    (some #(when (= (:name %) raw-target)
                                             (:name %))
                                          (:classes env)))
                target-expr (normalize-call-target raw-target)]
            (if (nil? target-expr)
              (when (:this-type env)
                (some-> (or (class-method-def (current-class-def env) (:method expr) (count (:args expr)))
                            (inherited-method-def env (current-class-def env) (:method expr) (count (:args expr))))
                        function-return-type))
              (let [target-type (when-not class-target-name
                                  (infer-type env target-expr))
                    base-type (base-type-name target-type)
                    class-def (or (when class-target-name
                                    (get (visible-class-map env) class-target-name))
                                  (get (visible-class-map env) base-type))]
                (when class-def
                  (if (and class-target-name (false? (:has-parens expr)))
                    (some-> (lookup-class-constant env class-target-name (:method expr))
                            (#(constant-nex-type env %)))
                    (if (false? (:has-parens expr))
                    (or (some-> (class-field-def class-def (:method expr))
                                :field-type)
                        (some-> (class-method-def class-def (:method expr) (count (:args expr)))
                                function-return-type))
                    (some-> (class-method-def class-def (:method expr) (count (:args expr)))
                            function-return-type)))))))

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

(defn- scoped-env
  [env child-env]
  (assoc env :next-slot (:next-slot child-env)))

(defn- scoped-child-env
  [env]
  (assoc env :scoped-locals? true))

(defn- lower-scoped-statements
  [env statements]
  (let [[child-env lowered] (lower-statements (scoped-child-env env) statements)]
    [(scoped-env env child-env) lowered]))

(defn- elseif->else-expr
  [elseif else-branch]
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
      (if-branch-expression else-body))))

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
       (remove #(= "Any" (:parent %)))
       (mapv (fn [{:keys [parent]}]
               (let [compiled (class-jvm-meta env parent)]
                 {:nex-name parent
                  :jvm-name (:jvm-name compiled)
                  :internal-name (:internal-name compiled)
                  :binary-name (:binary-name compiled)
                  :composition-field (str "_parent_" parent)
                  :deferred? (boolean (:deferred? (get (visible-class-map env) parent)))})))))

(defn- direct-parent-field-map
  [env class-def]
  (reduce (fn [m {:keys [parent]}]
            (let [parent-def (get (visible-class-map env) parent)
                  composition-field (str "_parent_" parent)]
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
                      (class-fields parent-def))))
          {}
          (remove #(= "Any" (:parent %)) (:parents class-def))))

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
            (let [parent-def (get (visible-class-map env) parent)
                  parent-meta (class-jvm-meta env parent)
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
                      (class-methods parent-def))))
          {}
          (remove #(= "Any" (:parent %)) (:parents class-def))))

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
      (let [nex-type (:field-type field-def)
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
      (let [nex-type (function-return-type method-def)
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
        (if-let [constant (and (:current-class env)
                               (lookup-class-constant env (:current-class env) (:name expr)))]
          (let [owner (:declaring-class constant)
                nex-type (constant-nex-type env constant)
                jvm-type (resolve-jvm-type env nex-type)]
            (ir/static-field-get-node (:internal-name (class-jvm-meta env owner))
                                      (:name constant)
                                      nex-type
                                      jvm-type))
          (let [nex-type (or (get (:var-types env) (:name expr))
                             (infer-type env expr))
                jvm-type (resolve-jvm-type env nex-type)]
            (if (:top-level? env)
              (ir/top-get-node (:name expr) nex-type jvm-type)
              (throw (ex-info "Unknown local in non-top-level lowering"
                              {:name (:name expr)})))))))

    :this
    (if (:this-type env)
      (ir/this-node (:this-type env)
                    (exact-class-jvm-type env (:this-type env)))
      (throw (ex-info "this is only valid in instance-method lowering"
                      {:expr expr})))

    :binary
    (let [left-ir (lower-expression env (:left expr))
          right-ir (lower-expression env (:right expr))
          nex-type (infer-type env expr)
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
          else-branch (:else expr)
          then-expr (if-branch-expression then-branch)
          else-expr (elseif->else-expr elseif else-branch)]
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

    :when
    (let [test-ir (lower-expression env (:condition expr))
          then-ir (lower-expression env (:consequent expr))
          else-ir (lower-expression env (:alternative expr))
          nex-type (infer-type env expr)
          jvm-type (resolve-jvm-type env nex-type)]
      (ir/if-node test-ir [then-ir] [else-ir] nex-type jvm-type))

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
                                             class-name
                                             (exact-class-jvm-type env class-name))
                                (mapv #(lower-expression env %) (:args expr))
                                class-name
                                (resolve-jvm-type env class-name)))
        (do
          (when (seq (:args expr))
            (throw (ex-info "Only create ClassName or create ClassName.ctor(...) is supported in compiled lowering"
                            {:expr expr})))
          (ir/new-node (:internal-name compiled)
                       class-name
                       class-name
                       (resolve-jvm-type env class-name)))))

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

            (contains? builtin-function-names (:method expr))
            (let [nex-type (infer-type env expr)
                  jvm-type (resolve-jvm-type env nex-type)]
              (ir/call-runtime-node (:method expr) arg-irs nex-type jvm-type))

            :else
            (let [nex-type (infer-type env expr)
                  jvm-type (resolve-jvm-type env nex-type)]
              (ir/call-repl-fn-node (:method expr) arg-irs nex-type jvm-type)))
          (cond
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
            (let [target-type (infer-type env target-expr)]
              (if-let [direct-op (and (= "Integer" (base-type-name target-type))
                                      (get direct-integer-bitwise-method->op (:method expr)))]
                (let [target-ir (lower-expression env target-expr)
                      nex-type (infer-type env expr)
                      jvm-type (resolve-jvm-type env nex-type)]
                  (if (= :bit-not direct-op)
                    (ir/unary-node direct-op target-ir nex-type jvm-type)
                    (ir/binary-node direct-op
                                    target-ir
                                    (first arg-irs)
                                    nex-type
                                    jvm-type)))
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
                                     :target-type target-type}))))))))))

    (throw (ex-info "Unsupported expression node for lowering"
                    {:expr expr :node-type (:type expr)}))))

(defn lower-statement
  [env stmt]
  (cond
    (= :let (:type stmt))
    (let [value-ir (lower-expression env (:value stmt))
          nex-type (or (:var-type stmt) (infer-type env (:value stmt)))]
      (if (and (:top-level? env) (not (:scoped-locals? env)))
        [(update env :var-types assoc (:name stmt) nex-type)
         (ir/top-set-node (:name stmt) value-ir nex-type (resolve-jvm-type env nex-type))]
        (let [[env' local] (env-add-local env (:name stmt) nex-type)]
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
          target-type (infer-type env target-expr)
          owner (base-type-name target-type)
          class-def (get (visible-class-map env) owner)
          field-def (when class-def (class-field-def class-def field-name))
          value-ir (lower-expression env (:value stmt))]
      (cond
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

        :else
        (throw (ex-info "Unknown field in member assignment during lowering"
                        {:field field-name
                         :target target-expr
                         :target-type target-type}))))

    (= :call (:type stmt))
    (if (and (:this-type env)
             (string? (:target stmt))
             (some #(= (:target stmt) (:parent %))
                   (:parents (current-class-def env)))
             (class-constructor-def (get (visible-class-map env) (:target stmt))
                                    (:method stmt)
                                    (count (:args stmt))))
      (let [parent-name (:target stmt)
            ctor-def (class-constructor-def (get (visible-class-map env) parent-name)
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

    (= :if (:type stmt))
    (let [[then-env then-body] (lower-scoped-statements env (:then stmt))
          [else-env else-body]
          (if-let [clause (first (:elseif stmt))]
            (lower-scoped-statements
             (scoped-env env then-env)
             [{:type :if
               :condition (:condition clause)
               :then (:then clause)
               :elseif (vec (rest (:elseif stmt)))
               :else (:else stmt)}])
            (lower-scoped-statements (scoped-env env then-env) (or (:else stmt) [])))]
      [(scoped-env env else-env)
       (ir/if-stmt-node (lower-expression env (:condition stmt)) then-body else-body)])

    (= :scoped-block (:type stmt))
    (do
      (when (:rescue stmt)
        (throw (ex-info "Scoped block rescue is not yet supported in compiled lowering"
                        {:stmt stmt})))
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
          test-ir (lower-expression env-after-init (:until stmt))
          [_ lowered-body] (lower-statements env-after-init (:body stmt))]
      [(scoped-env env env-after-init) (ir/loop-node lowered-init test-ir lowered-body)])

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

    :else
    [env [] nil]))

(defn lower-function
  [unit-name visible-functions visible-imports fn-def]
  (let [return-type (function-return-type fn-def)
        visible-classes (vec (concat (:visible-classes fn-def)
                                     [(:class-def fn-def)]
                                     (keep :class-def visible-functions)))
        current-class (:class-name fn-def)
        env0 (make-lowering-env {:classes visible-classes
                                 :functions visible-functions
                                 :imports visible-imports
                                 :var-types (field-type-map (:class-def fn-def))
                                 :compiled-classes (:compiled-classes fn-def)
                                 :current-class current-class
                                 :fields (field-info-map {:compiled-classes (:compiled-classes fn-def)
                                                          :classes visible-classes}
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
      (let [leading-statements (butlast body)
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
                                                 (ir/object-jvm-type "java/lang/Object")))
                     :deferred? (boolean (:deferred? fn-def))
                     :override? (boolean (:override? fn-def))})))))

(defn- lower-constructor
  [unit-name visible-functions visible-imports visible-classes class-def ctor-def compiled-classes]
  (let [class-name (:name class-def)
        env0 (make-lowering-env {:classes visible-classes
                                 :functions visible-functions
                                 :imports visible-imports
                                 :var-types (field-type-map class-def)
                                 :compiled-classes compiled-classes
                                 :current-class class-name
                                 :fields (field-info-map {:compiled-classes compiled-classes
                                                          :classes visible-classes}
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
                (:params ctor-def))]
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
                     :locals (vec (vals (:locals env-with-params)))
                     :body [(ir/pop-node call-ir)
                            (ir/return-node
                             (ir/this-node class-name
                                           (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                             class-name
                             (ir/object-jvm-type "java/lang/Object"))]}))
      (let [[env' lowered-body] (lower-statements env-with-params (vec (:body ctor-def)))]
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
                                                (exact-class-jvm-type {:compiled-classes compiled-classes} class-name))
                                  class-name
                                  (ir/object-jvm-type "java/lang/Object")))})))))

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
                                      (resolve-jvm-type {:compiled-classes compiled-classes} return-type))]
    (ir/fn-node {:name (:name method-def)
                 :owner (:jvm-name class-meta)
                 :emitted-name (lowered-instance-method-name method-def)
                 :params params
                 :return-type return-type
                 :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                 :locals (vec params)
                 :body [(ir/return-node call-ir
                                        return-type
                                        (ir/object-jvm-type "java/lang/Object"))]
                 :override? false})))

(defn lower-class-def
  [class-def opts]
  (let [compiled-classes (:compiled-classes opts)
        class-name (:name class-def)
        class-meta (class-jvm-meta {:compiled-classes compiled-classes} class-name)
        env (make-lowering-env {:classes (:classes opts)
                                :functions (:functions opts)
                                :imports (:imports opts)
                                :compiled-classes compiled-classes})
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
                        :jvm-type (resolve-jvm-type {:compiled-classes compiled-classes} (:field-type field))})
                     (remove :constant? (class-fields class-def)))
        constants (mapv (fn [field]
                          (let [constant-env (make-lowering-env {:classes (:classes opts)
                                                                 :functions visible-functions
                                                                 :imports visible-imports
                                                                 :compiled-classes compiled-classes
                                                                 :current-class class-name
                                                                 :this-type class-name
                                                                 :top-level? false
                                                                 :repl? true})
                                nex-type (or (:field-type field)
                                             (infer-type constant-env (:value field)))]
                            {:name (:name field)
                             :nex-type nex-type
                             :jvm-type (resolve-jvm-type {:compiled-classes compiled-classes} nex-type)
                             :value (lower-expression constant-env (:value field))}))
                        (filter :constant? (class-fields class-def)))]
    {:name class-name
     :jvm-name (:jvm-name class-meta)
     :internal-name (:internal-name class-meta)
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
                                                         :classes visible-classes
                                                         :functions visible-functions
                                                         :imports (:imports program)})
                                    actual-classes)
                     :functions (mapv #(lower-function unit-name visible-functions (:imports program) %)
                                      (remove :declaration-only? (:functions program)))
                     :body lowered-body''
                     :result-jvm-type (ir/object-jvm-type "java/lang/Object")})}))
