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
            [nex.ir :as ir]
            [nex.typechecker :as tc]))

(declare lower-expression)
(declare lower-statement)

(def ^:private expression-node-types
  #{:integer :real :string :char :boolean :nil :identifier :binary :call :if})

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
  - `:var-types`  visible variable types"
  ([] (make-lowering-env {}))
  ([{:keys [locals top-level? repl? state-slot next-slot classes functions imports var-types] :as opts}]
   {:locals (or locals {})
    :top-level? (if (contains? opts :top-level?) top-level? true)
    :repl? (if (contains? opts :repl?) repl? true)
    :state-slot (or state-slot 0)
    :next-slot (or next-slot 1)
    :classes (vec (or classes []))
    :functions (vec (or functions []))
    :imports (vec (or imports []))
    :var-types (or var-types {})}))

(defn- env-visible-var-types
  [env]
  (merge (:var-types env)
         (into {}
               (map (fn [[name {:keys [nex-type]}]]
                      [name nex-type])
                    (:locals env)))))

(defn- infer-type
  [env expr]
  (or (tc/infer-expression-type expr {:classes (:classes env)
                                      :functions (:functions env)
                                      :imports (:imports env)
                                      :var-types (env-visible-var-types env)})
      (throw (ex-info "Unable to infer expression type during lowering"
                      {:expr expr}))))

(defn- expr-jvm-type
  [env expr]
  (desc/nex-type->jvm-type (infer-type env expr)))

(defn- env-add-local
  [env name nex-type]
  (let [jvm-type (desc/nex-type->jvm-type nex-type)
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
      (let [nex-type (or (get (:var-types env) (:name expr))
                         (infer-type env expr))
            jvm-type (desc/nex-type->jvm-type nex-type)]
        (if (:top-level? env)
          (ir/top-get-node (:name expr) nex-type jvm-type)
          (throw (ex-info "Unknown local in non-top-level lowering"
                          {:name (:name expr)})))))

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
            jvm-type (desc/nex-type->jvm-type nex-type)]
        (ir/if-node test-ir [then-ir] [else-ir] nex-type jvm-type)))

    :call
    (if (and (nil? (:target expr))
             (empty? (:args expr))
             (not (:has-parens expr)))
      (lower-expression env {:type :identifier
                             :name (:method expr)})
      (if (nil? (:target expr))
        (let [arg-irs (mapv #(lower-expression env %) (:args expr))
              nex-type (infer-type env expr)
              jvm-type (desc/nex-type->jvm-type nex-type)]
          (ir/call-repl-fn-node (:method expr) arg-irs nex-type jvm-type))
        (throw (ex-info "Unsupported call expression for lowering"
                        {:expr expr}))))

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
        (let [nex-type (or (get (:var-types env) target-name)
                           (infer-type env {:type :identifier :name target-name}))
              jvm-type (desc/nex-type->jvm-type nex-type)]
          (if (:top-level? env)
            [(update env :var-types assoc target-name nex-type)
             (ir/top-set-node target-name value-ir nex-type jvm-type)]
            (throw (ex-info "Assignment target is not a known local"
                            {:target target-name}))))))

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
        env0 (make-lowering-env {:classes visible-classes
                                 :functions visible-functions
                                 :imports visible-imports
                                 :var-types {}
                                 :top-level? true
                                 :repl? true
                                 :state-slot 0
                                 :next-slot 2})
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
                 :emitted-name (lowered-function-method-name fn-def)
                 :params params
                 :return-type return-type
                 :return-jvm-type (ir/object-jvm-type "java/lang/Object")
                 :locals (vec (vals (:locals env')))
                 :body (conj lowered-leading
                             (ir/return-node return-expr
                                             return-type
                                             (ir/object-jvm-type "java/lang/Object")))})))

(defn lower-repl-cell
  "Lower a narrow REPL/program body to a first compiler unit."
  [program opts]
  (let [unit-name (or (:name opts) "nex/repl/Cell_0001")
        visible-functions (vec (concat (:functions program) (:functions opts)))
        visible-classes (vec (concat (:classes program)
                                     (keep :class-def visible-functions)))
        env (make-lowering-env {:classes visible-classes
                                :functions visible-functions
                                :imports (:imports program)
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
                         (conj lowered-body'
                               (ir/return-node
                                final-expr-ir
                                (:nex-type final-expr-ir)
                                (ir/object-jvm-type "java/lang/Object")))
                         (conj lowered-body'
                               (ir/return-node
                                (ir/const-node nil "Any"
                                               (ir/object-jvm-type "java/lang/Object"))
                                "Any"
                                (ir/object-jvm-type "java/lang/Object"))))]
    {:env env''
     :unit (ir/unit {:name (or (:name opts) "nex/repl/Cell_0001")
                     :kind :repl-cell
                     :functions (mapv #(lower-function unit-name visible-functions (:imports program) %)
                                      (remove :declaration-only? (:functions program)))
                     :body lowered-body''
                     :result-jvm-type (ir/object-jvm-type "java/lang/Object")})}))
