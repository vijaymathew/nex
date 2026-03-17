(ns nex.ir
  "Typed lowered IR data shapes for future compiler backends.

  The IR is intentionally plain-data-based. Nodes are ordinary maps with:

  - `:op`       keyword identifying the IR operation
  - `:nex-type` resolved Nex type
  - `:jvm-type` lowered JVM type shape

  This namespace currently provides constructors and predicates only. Lowering
  and code generation will live elsewhere."
  (:require [clojure.string :as str]))

(def primitive-jvm-types
  #{:int :long :double :boolean :char :void})

(defn object-jvm-type
  "Construct an object JVM type descriptor shape."
  [internal-name]
  [:object internal-name])

(defn object-jvm-type?
  [x]
  (and (vector? x)
       (= 2 (count x))
       (= :object (first x))
       (string? (second x))))

(defn valid-jvm-type?
  [x]
  (or (contains? primitive-jvm-types x)
      (object-jvm-type? x)))

(defn scalar-type [name]
  {:tag :scalar :name name})

(defn class-type [name]
  {:tag :class :name name})

(defn generic-instance-type [base args]
  {:tag :generic-instance :base base :args (vec args)})

(defn detachable-type [inner]
  {:tag :detachable :inner inner})

(defn ir-node?
  [x]
  (and (map? x) (keyword? (:op x))))

(defn unit
  [{:keys [name kind functions body result-jvm-type] :as m}]
  (assoc m :op :unit
           :functions (vec (or functions []))
           :body (vec (or body []))
           :result-jvm-type result-jvm-type
           :name name
           :kind kind))

(defn fn-node
  [{:keys [name owner params return-type return-jvm-type locals body] :as m}]
  (assoc m :op :fn
           :name name
           :owner owner
           :params (vec (or params []))
           :return-type return-type
           :return-jvm-type return-jvm-type
           :locals (vec (or locals []))
           :body (vec (or body []))))

(defn const-node [value nex-type jvm-type]
  {:op :const
   :value value
   :nex-type nex-type
   :jvm-type jvm-type})

(defn local-node [name slot nex-type jvm-type]
  {:op :local
   :name name
   :slot slot
   :nex-type nex-type
   :jvm-type jvm-type})

(defn set-local-node [slot expr nex-type jvm-type]
  {:op :set-local
   :slot slot
   :expr expr
   :nex-type nex-type
   :jvm-type jvm-type})

(defn top-get-node [name nex-type jvm-type]
  {:op :top-get
   :name name
   :nex-type nex-type
   :jvm-type jvm-type})

(defn top-set-node [name expr nex-type jvm-type]
  {:op :top-set
   :name name
   :expr expr
   :nex-type nex-type
   :jvm-type jvm-type})

(defn binary-node [operator left right nex-type jvm-type]
  {:op :binary
   :operator operator
   :left left
   :right right
   :nex-type nex-type
   :jvm-type jvm-type})

(defn compare-node [operator left right nex-type jvm-type]
  {:op :compare
   :operator operator
   :left left
   :right right
   :nex-type nex-type
   :jvm-type jvm-type})

(defn if-node [test then else nex-type jvm-type]
  {:op :if
   :test test
   :then (vec then)
   :else (vec else)
   :nex-type nex-type
   :jvm-type jvm-type})

(defn call-static-node
  [class method descriptor args nex-type jvm-type]
  {:op :call-static
   :class class
   :method method
   :descriptor descriptor
   :args (vec args)
   :nex-type nex-type
   :jvm-type jvm-type})

(defn call-runtime-node [helper args nex-type jvm-type]
  {:op :call-runtime
   :helper helper
   :args (vec args)
   :nex-type nex-type
   :jvm-type jvm-type})

(defn call-repl-fn-node [name args nex-type jvm-type]
  {:op :call-repl-fn
   :name name
   :args (vec args)
   :nex-type nex-type
   :jvm-type jvm-type})

(defn this-node [nex-type jvm-type]
  {:op :this
   :nex-type nex-type
   :jvm-type jvm-type})

(defn new-node [class class-name nex-type jvm-type]
  {:op :new
   :class class
   :class-name class-name
   :args []
   :nex-type nex-type
   :jvm-type jvm-type})

(defn field-get-node [owner field target nex-type jvm-type]
  {:op :field-get
   :owner owner
   :field field
   :target target
   :nex-type nex-type
   :jvm-type jvm-type})

(defn static-field-get-node [owner field nex-type jvm-type]
  {:op :static-field-get
   :owner owner
   :field field
   :nex-type nex-type
   :jvm-type jvm-type})

(defn field-set-node [owner field target expr nex-type jvm-type]
  {:op :field-set
   :owner owner
   :field field
   :target target
   :expr expr
   :nex-type nex-type
   :jvm-type jvm-type})

(defn call-virtual-node [owner method descriptor target args nex-type jvm-type]
  {:op :call-virtual
   :owner owner
   :method method
   :descriptor descriptor
   :target target
   :args (vec args)
   :nex-type nex-type
   :jvm-type jvm-type})

(defn box-node [from to expr nex-type]
  {:op :box
   :from from
   :to to
   :expr expr
   :nex-type nex-type
   :jvm-type to})

(defn unbox-node [from to expr nex-type]
  {:op :unbox
   :from from
   :to to
   :expr expr
   :nex-type nex-type
   :jvm-type to})

(defn return-node [expr nex-type jvm-type]
  {:op :return
   :expr expr
   :nex-type nex-type
   :jvm-type jvm-type})

(defn pop-node [expr]
  {:op :pop
   :expr expr})

(defn pretty-op
  "Return a short human-readable label for an IR node."
  [node]
  (when (ir-node? node)
    (-> (:op node) name (str/replace "-" " "))))
