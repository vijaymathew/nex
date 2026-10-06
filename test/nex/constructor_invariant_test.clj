(ns nex.constructor-invariant-test
  "The invariant is checked once, by the outermost `create`, when its
   constructor returns (Definition §5.5), after every constructor it
   delegated to has run. The compiled backend checked it at the end of every
   constructor, so a delegation such as `super.make(b)` checked the object's
   invariant before the delegating constructor had finished building it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "constructor_invariant" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) opts))))
      (finally (.delete f)))))

(defn- both
  "Printed output of CODE, asserted identical on both backends."
  [code]
  (let [compiled (run code {})]
    (is (= (run code {:interpret? true}) compiled) "compiled and interpreted output must agree")
    compiled))

(def ^:private accounts
  "class Account
feature
  balance: Real
create
  make(b: Real) do balance := b end
invariant
  positive: balance > 0.0
end

class Savings_Account
inherit Account
feature
  number: String
create
  make(n: String, b: Real) do
    super.make(b)
    print(\"setting number: \" + n)
    number := n
  end
  by_name(n: String, b: Real) do
    Account.make(b)
    number := n
  end
invariant
  valid_number: number.length = 5
end
")

(deftest delegation-does-not-check-the-invariant
  (testing "super.make leaves a broken invariant for the rest of the constructor to see;
            the outermost create then reports it"
    (is (= ["Class invariant violation: positive"
            "\"setting number: 12345\""
            "Class invariant violation: positive"]
           (both (str accounts "do
  let a := create Account.make(-1.0)
rescue
  print(exception)
end
do
  let s := create Savings_Account.make(\"12345\", -1.0)
rescue
  print(exception)
end")))))
  (testing "the heir's own invariant, on a field set after the delegation, holds at the end"
    (is (= ["\"setting number: 12345\"" "\"12345 5.0\""
            "\"setting number: 123\"" "Class invariant violation: valid_number"]
           (both (str accounts "let s := create Savings_Account.make(\"12345\", 5.0)
print(s.number + \" \" + s.balance.to_string)
do
  let t := create Savings_Account.make(\"123\", 5.0)
rescue
  print(exception)
end"))))))

(deftest every-form-of-delegation-defers-the-check
  (testing "naming the ancestor"
    (is (= ["\"ok 1.0\"" "Class invariant violation: positive"]
           (both (str accounts "let a := create Savings_Account.by_name(\"abcde\", 1.0)
print(\"ok \" + a.balance.to_string)
do
  let c := create Savings_Account.by_name(\"abcde\", -2.0)
rescue
  print(exception)
end")))))
  (testing "an inherited constructor checks the heir's whole invariant"
    (is (= ["Class invariant violation: small"]
           (both "class Account
create
  make(b: Real) do balance := b end
feature
  balance: Real
invariant
  positive: balance > 0.0
end
class Capped
inherit Account
invariant
  small: balance < 100.0
end
do
  let c := create Capped.make(500.0)
rescue
  print(exception)
end"))))
  (testing "a chain of three constructors: A's invariant reads a query C overrides,
            over a field C sets only after its delegation returns"
    (is (= ["\"1 2 3\"" "Class invariant violation: below_limit"]
           (both "class A
create
  make(x: Integer) do a := x end
feature
  a: Integer
  limit(): Integer do result := 100 end
invariant
  below_limit: a < limit()
end
class B
inherit A
create
  make(x, y: Integer) do A.make(x) b := y end
feature
  b: Integer
end
class C
inherit B
create
  make(x, y, z: Integer) do B.make(x, y) c := z end
feature
  c: Integer
  limit(): Integer do result := c end
end
let c := create C.make(1, 2, 3)
print(c.a.to_string + \" \" + c.b.to_string + \" \" + c.c.to_string)
do
  let d := create C.make(5, 2, 3)
rescue
  print(exception)
end")))))
