(ns nex.walker
  (:require [clj-antlr.core :as antlr]))

;;
;; Utility
;;

(defn token-text [node]
  (when (string? node)
    node))

(defn numeric? [s]
  (re-matches #"-?\d+(\.\d+)?([eE][+-]?\d+)?" s))

;;
;; AST Builders
;;

(defmulti walk-node first)

;;
;; Program
;;

(defmethod walk-node :program [[_ & classes]]
  {:type :program
   :classes (map walk-node classes)})

;;
;; Class
;;

(defmethod walk-node :classDecl [[_ name body _end]]
  {:type :class
   :name (token-text name)
   :body (map walk-node (rest body))})

;;
;; Feature Section
;;

(defmethod walk-node :featureSection [[_ & members]]
  {:type :feature-section
   :members (map walk-node members)})

;;
;; Constructor Section
;;

(defmethod walk-node :constructorSection [[_ & ctors]]
  {:type :constructors
   :constructors (map walk-node ctors)})

;;
;; Field
;;

(defmethod walk-node :fieldDecl [[_ name _colon type]]
  {:type :field
   :name (token-text name)
   :field-type (token-text type)})

;;
;; Constructor
;;

(defmethod walk-node :constructorDecl
  [[_ name params _do block _end]]
  {:type :constructor
   :name (token-text name)
   :params (when params (walk-node params))
   :body (walk-node block)})

;;
;; Method
;;

(defmethod walk-node :methodDecl
  [[_ name params _do block _end]]
  {:type :method
   :name (token-text name)
   :params (when params (walk-node params))
   :body (walk-node block)})

;;
;; Parameters
;;

(defmethod walk-node :paramList [[_ & params]]
  (map walk-node params))

(defmethod walk-node :param [[_ name _colon type]]
  {:name (token-text name)
   :type (token-text type)})

;;
;; Block
;;

(defmethod walk-node :block [[_ & statements]]
  (map walk-node statements))

;;
;; Assignment
;;

(defmethod walk-node :assignment [[_ name _assign expr]]
  {:type :assign
   :target (token-text name)
   :value (walk-node expr)})

;;
;; Method Call
;;

(defmethod walk-node :methodCall [[_ & parts]]
  (let [parts (remove #(= "." %) parts)
        [target method _ & args] parts]
    {:type :call
     :target (when (and target (not (vector? target)))
               (token-text target))
     :method (token-text method)
     :args (map walk-node args)}))

(defmethod walk-node :argumentList [[_ & parts]]
  (let [parts (remove #(= "," %) parts)]
    (map walk-node parts)))

(defmethod walk-node :expression [[_ & parts]]
  (map walk-node parts))

;;
;; Expressions (binary ops already structured by grammar)
;;

(defmethod walk-node :addition [[_ left & rest]]
  (reduce
   (fn [acc [op rhs]]
     {:type :binary
      :operator op
      :left acc
      :right (walk-node rhs)})
   (walk-node left)
   (partition 2 rest)))

(defmethod walk-node :multiplication [[_ left & rest]]
  (reduce
   (fn [acc [op rhs]]
     {:type :binary
      :operator op
      :left acc
      :right (walk-node rhs)})
   (walk-node left)
   (partition 2 rest)))

(defmethod walk-node :comparison [[_ left & rest]]
  (reduce
   (fn [acc [op rhs]]
     {:type :binary
      :operator op
      :left acc
      :right (walk-node rhs)})
   (walk-node left)
   (partition 2 rest)))

(defmethod walk-node :equality [[_ left & rest]]
  (reduce
   (fn [acc [op rhs]]
     {:type :binary
      :operator op
      :left acc
      :right (walk-node rhs)})
   (walk-node left)
   (partition 2 rest)))

(defmethod walk-node :logicalAnd [[_ left & rest]]
  (reduce
   (fn [acc rhs]
     {:type :binary
      :operator "and"
      :left acc
      :right (walk-node rhs)})
   (walk-node left)
   rest))

(defmethod walk-node :logicalOr [[_ left & rest]]
  (reduce
   (fn [acc rhs]
     {:type :binary
      :operator "or"
      :left acc
      :right (walk-node rhs)})
   (walk-node left)
   rest))

;;
;; Unary minus
;;

(defmethod walk-node :unaryMinus [[_ _ expr]]
  {:type :unary
   :operator "-"
   :expr (walk-node expr)})

;;
;; Literals
;;

(defmethod walk-node :integerLiteral [[_ value]]
  {:type :integer
   :value (Long/parseLong value)})

(defmethod walk-node :realLiteral [[_ value]]
  {:type :real
   :value (Double/parseDouble value)})

(defmethod walk-node :booleanLiteral [[_ value]]
  {:type :boolean
   :value (= value "true")})

(defmethod walk-node :charLiteral [[_ value]]
  (let [v (subs value 1)]
    {:type :char
     :value (if (re-matches #"\d+" v)
              (char (Integer/parseInt v))
              (first v))}))

;;
;; Default
;;

(defmethod walk-node :default [node]
  (if (vector? node)
    (map walk-node (rest node))
    node))
