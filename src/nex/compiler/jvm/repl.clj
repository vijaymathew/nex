(ns nex.compiler.jvm.repl
  "Experimental compiled REPL execution path for a narrow expression subset."
  (:require [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.descriptor :as desc]
            [nex.compiler.jvm.emit :as emit]
            [nex.compiler.jvm.runtime :as rt]
            [nex.interpreter :as interp]
            [nex.lower :as lower]
            [nex.typechecker :as tc])
  (:import [java.util HashMap]))

(defn- clone-hash-map
  [m]
  (let [copy (HashMap.)]
    (doseq [[k v] m]
      (.put copy k v))
    copy))

(defn make-session
  []
  (let [ldr (loader/make-loader)]
    {:loader ldr
     :state (rt/make-repl-state ldr)
     :counter (atom 0)
     :function-asts (atom {})
     :class-asts (atom {})
     :import-asts (atom [])
     :intern-asts (atom [])}))

(defn reset-session
  []
  (make-session))

(def ^:private relational-ops
  #{"=" "/=" "<" "<=" ">" ">="})

(declare supported-expr-in-ctx?)
(declare supported-stmt-in-ctx?)
(declare session-var-types)

(defn- supported-if-branches?
  [ctx branch]
  (and (= 1 (count branch))
       (supported-expr-in-ctx? ctx (first branch))))

(defn normalize-program-ast
  "Normalize legacy program shapes so the compiled REPL path can reason about
   one top-level statement stream. Older ASTs may carry top-level calls only in
   :calls; newer parser output mirrors them into :statements as well."
  [ast]
  (if (and (= :program (:type ast))
           (empty? (:statements ast))
           (seq (:calls ast)))
    (assoc ast :statements (vec (:calls ast)))
    ast))

(defn- initial-eligibility-ctx
  [session ast]
  {:known-vars (set (concat (keys (session-var-types session))
                            (keys @(:values (:state session)))))
   :known-fns (set (concat (keys @(:function-asts session))
                           (map :name (:functions ast))))})

(defn supported-expr-in-ctx?
  [ctx expr]
  (case (:type expr)
    :integer true
    :real true
    :string true
    :char true
    :boolean true
    :nil true
    :identifier (contains? (:known-vars ctx) (:name expr))
    :call (and (nil? (:target expr))
               (every? #(supported-expr-in-ctx? ctx %) (:args expr))
               (or (and (empty? (:args expr))
                        (not (:has-parens expr)))
                   (contains? (:known-fns ctx) (:method expr))))
    :binary (and (contains? (into #{"+" "-" "*" "/"} relational-ops) (:operator expr))
                 (supported-expr-in-ctx? ctx (:left expr))
                 (supported-expr-in-ctx? ctx (:right expr)))
    :if (and (empty? (:elseif expr))
             (supported-expr-in-ctx? ctx (:condition expr))
             (supported-if-branches? ctx (:then expr))
             (supported-if-branches? ctx (:else expr)))
    false))

(defn supported-stmt-in-ctx?
  [ctx stmt]
  (case (:type stmt)
    :let (supported-expr-in-ctx? ctx (:value stmt))
    :assign (and (string? (:target stmt))
                 (contains? (:known-vars ctx) (:target stmt))
                 (supported-expr-in-ctx? ctx (:value stmt)))
    :call (supported-expr-in-ctx? ctx stmt)
    (supported-expr-in-ctx? ctx stmt)))

(defn- advance-eligibility-ctx
  [ctx stmt]
  (case (:type stmt)
    :let (update ctx :known-vars conj (:name stmt))
    ctx))

(defn eligible-ast?
  [session ast]
  (let [ast' (normalize-program-ast ast)
        generated-class-names (set (keep (comp :name :class-def) (:functions ast')))
        actual-class-names (set (map :name (:classes ast')))
        initial-ctx (initial-eligibility-ctx session ast')]
    (and (= :program (:type ast'))
         (empty? (:imports ast))
         (empty? (:interns ast))
         (or (empty? actual-class-names)
             (= actual-class-names generated-class-names))
         (or (seq (:functions ast'))
             (seq (:statements ast')))
         (reduce (fn [ctx stmt]
                   (when (and ctx (supported-stmt-in-ctx? ctx stmt))
                     (advance-eligibility-ctx ctx stmt)))
                 initial-ctx
                 (:statements ast')))))

(defn- next-class-name!
  [session]
  (format "nex/repl/Expr_%04d" (swap! (:counter session) inc)))

(defn- reset-runtime-state!
  [state]
  (reset! (:values state) (HashMap.))
  (reset! (:types state) (HashMap.))
  (reset! (:functions state) (HashMap.))
  state)

(defn- session-function-name-set
  [session]
  (set (keys @(:function-asts session))))

(defn- replace-metadata-atoms!
  [session metadata]
  (reset! (:function-asts session) (:functions metadata))
  (reset! (:class-asts session) (:classes metadata))
  (reset! (:import-asts session) (:imports metadata))
  (reset! (:intern-asts session) (:interns metadata))
  session)

(defn session-metadata
  [session]
  {:functions @(:function-asts session)
   :classes @(:class-asts session)
   :imports @(:import-asts session)
   :interns @(:intern-asts session)})

(defn session-var-types
  [session]
  (into {} @(:types (:state session))))

(defn- sync-var-types-from-ast!
  [session ast]
  (doseq [stmt (:statements (normalize-program-ast ast))]
    (case (:type stmt)
      :let (let [nex-type (or (:var-type stmt)
                              (tc/infer-expression-type
                               (:value stmt)
                               {:classes (vals @(:class-asts session))
                                :imports @(:import-asts session)
                                :var-types (session-var-types session)}))]
             (when nex-type
               (rt/state-set-type! (:state session) (:name stmt) nex-type)))
      :assign (when-let [nex-type (or (get (session-var-types session) (:target stmt))
                                      (tc/infer-expression-type
                                       (:value stmt)
                                       {:classes (vals @(:class-asts session))
                                        :imports @(:import-asts session)
                                        :var-types (session-var-types session)}))]
                (rt/state-set-type! (:state session) (:target stmt) nex-type))
      nil))
  session)

(defn- merge-import-like-nodes
  [existing incoming]
  (let [seen (atom (set (map pr-str existing)))]
    (reduce (fn [acc node]
              (let [k (pr-str node)]
                (if (contains? @seen k)
                  acc
                  (do
                    (swap! seen conj k)
                    (conj acc node)))))
            (vec existing)
            incoming)))

(defn remember-top-level-ast!
  [session ast]
  (swap! (:function-asts session)
         (fn [m]
           (reduce (fn [acc fn-def]
                     (assoc acc (:name fn-def) fn-def))
                   m
                   (:functions ast))))
  (swap! (:class-asts session)
         (fn [m]
           (reduce (fn [acc class-def]
                     (assoc acc (:name class-def) class-def))
                   m
                   (:classes ast))))
  (swap! (:import-asts session) merge-import-like-nodes (:imports ast))
  (swap! (:intern-asts session) merge-import-like-nodes (:interns ast))
  session)

(defn- compile-and-register-functions!
  [session ast]
  (when (seq (remove :declaration-only? (:functions ast)))
    (let [current-functions (vals @(:function-asts session))
          current-classes (vals @(:class-asts session))
          current-imports @(:import-asts session)
          replaced-names (set (map :name (:functions ast)))
          other-functions (remove #(contains? replaced-names (:name %)) current-functions)
          class-name (next-class-name! session)
          compile-ast {:type :program
                       :imports current-imports
                       :interns []
                       :classes current-classes
                       :functions (:functions ast)
                       :statements []
                       :calls []}
          {:keys [unit]} (lower/lower-repl-cell compile-ast
                                                {:name class-name
                                                 :functions other-functions
                                                 :var-types (session-var-types session)})
          bytecode (emit/compile-unit->bytes unit)
          binary-name (desc/binary-class-name class-name)
          cls (loader/define-class! (:loader session) binary-name bytecode)
          state (:state session)
          method (.getMethod cls "eval" (into-array Class [(class state)]))]
      (.invoke method nil (object-array [state]))))
  session)

(defn sync-interpreter->session!
  "Copy top-level interpreter state into the compiled session and remember
   top-level AST metadata so later compiled cells can type/lower against it."
  [session ctx var-types ast]
  (remember-top-level-ast! session ast)
  (let [state (:state session)
        function-names (session-function-name-set session)]
    (reset-runtime-state! state)
    (doseq [[k v] @(:bindings (:globals ctx))
            :let [name (if (string? k) k (name k))]
            :when (not (contains? function-names name))]
      (rt/state-set-value! state name v))
    (doseq [[k t] var-types
            :when (not (contains? function-names k))]
      (rt/state-set-type! state k t))
    (compile-and-register-functions! session ast)
    session))

(defn sync-session->interpreter!
  "Materialize compiled-session top-level state into the interpreter context.
   Returns {:ctx ctx :var-types {..}} for the caller to update REPL globals."
  [session ctx]
  (reset! (:bindings (:globals ctx)) {})
  (reset! (:imports ctx) [])
  (reset! (:classes ctx) {})
  (doseq [import-node @(:import-asts session)]
    (interp/eval-node ctx import-node))
  (doseq [class-def (vals @(:class-asts session))]
    (interp/eval-node ctx class-def))
  (doseq [fn-def (vals @(:function-asts session))]
    (interp/eval-node ctx fn-def))
  (doseq [[k v] @(:values (:state session))]
    (interp/env-define (:globals ctx) k v))
  {:ctx ctx
   :var-types (merge
               (into {}
                     (map (fn [[name fn-def]]
                            [name (:class-name fn-def)]))
                     @(:function-asts session))
               (session-var-types session))})

(defn compile-and-eval!
  "Attempt compiled evaluation for a narrow REPL-safe top-level subset.
   Returns {:compiled? true :session .. :result ..} on success, nil when the
   input is outside the supported subset or lowering/emission declines it."
  [session ast]
  (let [ast' (normalize-program-ast ast)]
    (when (eligible-ast? session ast')
    (try
      (let [class-name (next-class-name! session)
            {:keys [unit]} (lower/lower-repl-cell ast' {:name class-name
                                                        :functions (vals @(:function-asts session))
                                                        :var-types (session-var-types session)})
            bytecode (emit/compile-unit->bytes unit)
            binary-name (desc/binary-class-name class-name)
            cls (loader/define-class! (:loader session) binary-name bytecode)
            state (:state session)
            method (.getMethod cls "eval" (into-array Class [(class state)]))
            result (.invoke method nil (object-array [state]))]
        (remember-top-level-ast! session ast')
        (sync-var-types-from-ast! session ast')
        {:compiled? true
         :session session
         :result result})
      (catch clojure.lang.ExceptionInfo e
        (let [msg (.getMessage e)]
          (when-not (or (.contains msg "Unsupported")
                        (.contains msg "Unable to infer expression type during lowering"))
            (throw e))
          nil))))))
