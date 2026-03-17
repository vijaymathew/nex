(ns nex.compiler.jvm.repl
  "Experimental compiled REPL execution path for a narrow expression subset."
  (:require [nex.compiler.jvm.classloader :as loader]
            [nex.compiler.jvm.descriptor :as desc]
            [nex.compiler.jvm.emit :as emit]
            [nex.compiler.jvm.runtime :as rt]
            [nex.lower :as lower])
  (:import [java.util HashMap]))

(defn make-session
  []
  (let [ldr (loader/make-loader)]
    {:loader ldr
     :state (rt/make-repl-state ldr)
     :counter (atom 0)}))

(defn reset-session
  []
  (make-session))

(def ^:private relational-ops
  #{"=" "/=" "<" "<=" ">" ">="})

(declare supported-expr?)

(defn- supported-if-branches?
  [branch]
  (and (= 1 (count branch))
       (supported-expr? (first branch))))

(defn supported-expr?
  [expr]
  (case (:type expr)
    :integer true
    :real true
    :string true
    :char true
    :boolean true
    :nil true
    :identifier true
    :call (and (nil? (:target expr))
               (empty? (:args expr))
               (not (:has-parens expr)))
    :binary (and (contains? (into #{"+" "-" "*" "/"} relational-ops) (:operator expr))
                 (supported-expr? (:left expr))
                 (supported-expr? (:right expr)))
    :if (and (empty? (:elseif expr))
             (supported-expr? (:condition expr))
             (supported-if-branches? (:then expr))
             (supported-if-branches? (:else expr)))
    false))

(defn eligible-ast?
  [ast]
  (and (= :program (:type ast))
       (empty? (:imports ast))
       (empty? (:interns ast))
       (empty? (:classes ast))
       (empty? (:functions ast))
       (= 1 (count (:statements ast)))
       (supported-expr? (first (:statements ast)))))

(defn- next-class-name!
  [session]
  (format "nex/repl/Expr_%04d" (swap! (:counter session) inc)))

(defn- reset-runtime-state!
  [state]
  (reset! (:values state) (HashMap.))
  (reset! (:types state) (HashMap.))
  (reset! (:functions state) (HashMap.))
  state)

(defn- seed-state-from-context!
  [state ctx var-types]
  (reset-runtime-state! state)
  (doseq [[k v] @(:bindings (:globals ctx))]
    (rt/state-set-value! state (if (string? k) k (name k)) v))
  (doseq [[k t] var-types]
    (rt/state-set-type! state k t))
  state)

(defn compile-and-eval!
  "Attempt compiled evaluation for a narrow REPL-safe expression subset.
   Returns {:compiled? true :session .. :result ..} on success, nil when the
   input is outside the supported subset or lowering/emission declines it."
  [session ctx ast var-types]
  (when (eligible-ast? ast)
    (try
      (let [class-name (next-class-name! session)
            {:keys [unit]} (lower/lower-repl-cell ast {:name class-name
                                                       :var-types var-types})
            bytecode (emit/compile-unit->bytes unit)
            binary-name (desc/binary-class-name class-name)
            cls (loader/define-class! (:loader session) binary-name bytecode)
            state (seed-state-from-context! (:state session) ctx var-types)
            method (.getMethod cls "eval" (into-array Class [(class state)]))
            result (.invoke method nil (object-array [state]))]
        {:compiled? true
         :session session
         :result result})
      (catch clojure.lang.ExceptionInfo e
        (let [msg (.getMessage e)]
          (when-not (or (.contains msg "Unsupported")
                        (.contains msg "Unable to infer expression type during lowering"))
            (throw e))
          nil)))))
