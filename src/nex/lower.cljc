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
  - `:imports`    visible imports for type inference
  - `:var-types`  visible variable types"
  ([] (make-lowering-env {}))
  ([{:keys [locals top-level? repl? state-slot next-slot classes imports var-types] :as opts}]
   {:locals (or locals {})
    :top-level? (if (contains? opts :top-level?) top-level? true)
    :repl? (if (contains? opts :repl?) repl? true)
    :state-slot (or state-slot 0)
    :next-slot (or next-slot 1)
    :classes (vec (or classes []))
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
          else-branch (:else expr)]
      (when (seq elseif)
        (throw (ex-info "Elseif is not yet supported in lowering"
                        {:expr expr})))
      (when (or (not= 1 (count then-branch))
                (not= 1 (count else-branch)))
        (throw (ex-info "Only expression-shaped if branches are supported in lowering"
                        {:expr expr})))
      (let [test-ir (lower-expression env (:condition expr))
            then-ir (lower-expression env (first then-branch))
            else-ir (lower-expression env (first else-branch))
            nex-type (infer-type env expr)
            jvm-type (desc/nex-type->jvm-type nex-type)]
        (ir/if-node test-ir [then-ir] [else-ir] nex-type jvm-type)))

    :call
    (if (and (nil? (:target expr))
             (empty? (:args expr))
             (not (:has-parens expr)))
      (lower-expression env {:type :identifier
                             :name (:method expr)})
      (throw (ex-info "Unsupported call expression for lowering"
                      {:expr expr})))

    (throw (ex-info "Unsupported expression node for lowering"
                    {:expr expr :node-type (:type expr)}))))

(defn lower-statement
  [env stmt]
  (cond
    (= :let (:type stmt))
    (let [value-ir (lower-expression env (:value stmt))
          nex-type (or (:var-type stmt) (infer-type env (:value stmt)))
          [env' local] (env-add-local env (:name stmt) nex-type)]
      [env' (ir/set-local-node (:slot local) value-ir (:nex-type local) (:jvm-type local))])

    (= :assign (:type stmt))
    (let [value-ir (lower-expression env (:value stmt))
          target-name (:target stmt)]
      (if-let [{:keys [slot nex-type jvm-type]} (get (:locals env) target-name)]
        [env (ir/set-local-node slot value-ir nex-type jvm-type)]
        (let [nex-type (or (get (:var-types env) target-name)
                           (infer-type env {:type :identifier :name target-name}))
              jvm-type (desc/nex-type->jvm-type nex-type)]
          (if (:top-level? env)
            [env (ir/top-set-node target-name value-ir nex-type jvm-type)]
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

(defn lower-repl-cell
  "Lower a narrow REPL/program body to a first compiler unit."
  [program opts]
  (let [env (make-lowering-env {:classes (:classes program)
                                :imports (:imports program)
                                :var-types (:var-types opts)
                                :top-level? true
                                :repl? true
                                :state-slot 0
                                :next-slot 1})
        statements (vec (:statements program))
        expr-tail? (contains? expression-node-types (:type (last statements)))
        leading-statements (if expr-tail? (pop statements) statements)
        [env' lowered-body] (lower-statements env leading-statements)
        final-expr-ir (when expr-tail?
                        (lower-expression env' (last statements)))
        lowered-body' (if expr-tail?
                        (conj lowered-body
                              (ir/return-node
                               final-expr-ir
                               (:nex-type final-expr-ir)
                               (ir/object-jvm-type "java/lang/Object")))
                        (conj lowered-body
                              (ir/return-node
                               (ir/const-node nil "Any"
                                              (ir/object-jvm-type "java/lang/Object"))
                               "Any"
                               (ir/object-jvm-type "java/lang/Object"))))]
    {:env env'
     :unit (ir/unit {:name (or (:name opts) "nex/repl/Cell_0001")
                     :kind :repl-cell
                     :functions []
                     :body lowered-body'
                     :result-jvm-type (ir/object-jvm-type "java/lang/Object")})}))
