(ns nex.operator-alias-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.interpreter :as interp]
            [nex.lower :as lower]))

;; A class feature may bind itself to one of the fixed arithmetic operators with
;; an `alias` clause: the operator is then exactly sugar for the call, contracts
;; included. These tests pin the three properties the feature promises:
;;
;;   1. the operator resolves to the feature, on both backends;
;;   2. the feature's contracts fire *through* the operator; and
;;   3. built-in Integer/Real arithmetic is untouched — an alias can never
;;      shadow it, and a program that declares none pays nothing.

(def money-class
  "class Money
  feature
    once amount: Integer
    once currency: String

    plus(other: Money): Money
      alias \"+\"
      require
        same_currency: currency = other.currency
      do
        result := create Money.make(amount + other.amount, currency)
      end

    minus(other: Money): Money
      alias \"-\"
      require
        same_currency: currency = other.currency
      do
        result := create Money.make(amount - other.amount, currency)
      end
  create
    make(a: Integer, c: String) do amount := a  currency := c end
end
")

(defn- run-output
  "Run a whole program on the interpreter, returning printed output."
  [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)]
    (interp/eval-node ctx ast)
    @(:output ctx)))

(defn- typecheck
  [code]
  (tc/type-check (p/ast code)))

(deftest alias-resolves-operator-to-feature
  (testing "an aliased feature backs the operator"
    (is (= ["15" "5"]
           (run-output (str money-class
                            "let a := create Money.make(10, \"USD\")
let b := create Money.make(5, \"USD\")
print((a + b).amount)
print((a - b).amount)"))))))

(deftest alias-carries-the-features-contracts
  (testing "a precondition on the aliased feature fires through the operator"
    (is (thrown-with-msg?
         Exception #"same_currency"
         (run-output (str money-class
                          "let usd := create Money.make(10, \"USD\")
let eur := create Money.make(5, \"EUR\")
print((usd + eur).amount)"))))))

(deftest alias-is-inherited
  (testing "an alias declared on a parent backs the operator on a subclass"
    (is (= ["7"]
           (run-output
            "deferred class Addable
  feature
    plus(other: Addable): Addable
      alias \"+\"
      deferred
end

class Counter
  inherit Addable
  feature
    once n: Integer
    plus(other: Addable): Addable
      do
        if convert other to c: Counter then
          result := create Counter.make(n + c.n)
        else
          raise \"not a Counter\"
        end
      end
  create
    make(v: Integer) do n := v end
end

let x: Addable := create Counter.make(3)
let y: Addable := create Counter.make(4)
let s := x + y
if convert s to c: Counter then
  print(c.n)
end")))))

(deftest builtin-arithmetic-is-unaffected
  (testing "Integer and Real arithmetic still typecheck and evaluate normally"
    (is (= ["7" "2.5"]
           (run-output "print(3 + 4)
print(5.0 / 2.0)"))))

  (testing "an alias on a user class cannot shadow built-in numeric arithmetic"
    (is (= ["7"]
           (run-output (str money-class "print(3 + 4)"))))))

(deftest arithmetic-on-a-class-without-an-alias-is-still-an-error
  (testing "the ordinary type error survives for classes that alias nothing"
    (let [result (typecheck "class Point
  feature
    once x: Integer
  create
    make(a: Integer) do x := a end
end
let p := create Point.make(1)
let q := create Point.make(2)
let r := p - q")]
      (is (not (:success result)))
      (is (re-find #"numeric" (pr-str (:errors result)))))))

(deftest alias-is-a-soft-keyword
  (testing "`alias` remains usable as an ordinary name — local, field, parameter,
            routine, and member access — so adding the clause reserved nothing"
    (is (= ["\"vj\"" "\"vijay\"" "6"]
           (run-output
            "class User
  feature
    alias: String
    set_alias(alias: String) do
      this.alias := alias
    end
  create
    make(n: String) do alias := n end
end

class Money
  feature
    once amount: Integer
    minus(other: Money): Money
      alias \"-\"
      do
        result := create Money.make(amount - other.amount)
      end
  create
    make(a: Integer) do amount := a end
end

let alias := \"vj\"
print(alias)
let u := create User.make(\"v\")
u.set_alias(\"vijay\")
print(u.alias)
print((create Money.make(10) - create Money.make(4)).amount)")))))

(deftest a-misspelled-alias-clause-is-reported-as-such
  (testing "since the grammar accepts any identifier there, the walker must name the typo"
    (is (thrown-with-msg?
         Exception #"Unexpected \"aliaz\""
         (p/ast "class Weird
  feature
    minus(other: Weird): Weird
      aliaz \"-\"
      do
        result := other
      end
  create
    make() do end
end")))))

(deftest only-the-fixed-operator-set-may-be-aliased
  (testing "a symbol outside the operator set is rejected at parse time"
    (is (thrown-with-msg?
         Exception #"Cannot alias"
         (p/ast "class Weird
  feature
    frobnicate(other: Weird): Weird
      alias \"<=>\"
      do
        result := other
      end
  create
    make() do end
end")))))

(deftest programs-without-aliases-declare-no-alias-operators
  (testing "the lowering env's alias set is empty, so arithmetic pays no lookup"
    (let [ast (p/ast "class Point
  feature
    once x: Integer
  create
    make(a: Integer) do x := a end
end")
          env (lower/make-lowering-env {:classes (:classes ast)})]
      (is (empty? (:aliased-operators env)))))

  (testing "and names exactly the aliased operators when they are used"
    (let [ast (p/ast money-class)
          env (lower/make-lowering-env {:classes (:classes ast)})]
      (is (= #{"+" "-"} (:aliased-operators env))))))
