(ns nex.definition-type-rules-test
  "Three static rules of the Definition of Nex the typechecker used to break:

   1. Generic type arguments are invariant: Box[Dog] does not conform to
      Box[Animal] (nex.typechecker/types-compatible?). The compiler accepted
      it, and an Animal could then be put into the Box[Dog].
   2. After `a and b`, the narrowings of both a and b apply in the then
      branch (nex.typechecker/guarded-non-nil-vars). Only a bare `x /= nil`
      narrowed. The right operand of every `and`, not only one in an `if` or
      `when` condition, is checked with the left's narrowings in effect, so
      `require ok: x /= nil and x.v > 0` type-checks.
   3. `when c then a else b end` has the join of the branch types: the
      narrowest type both conform to, or Any when there is no single one
      (nex.typechecker/join-type). The compiler rejected unrelated branch
      types, and otherwise typed the expression as the then branch even
      when the else branch was wider."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "definition_type_rules" ".nex")]
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

(defn- type-error [code]
  (try (run code {}) nil
       (catch Exception ex (.getMessage ex))))

(def ^:private animals
  "class Animal
create
  make do end
feature
  name(): String do result := \"animal\" end
end
class Pet
create
  make do end
end
class Dog
inherit Animal, Pet
create
  make do end
feature
  name(): String do result := \"dog\" end
  bark(): String do result := \"woof\" end
end
class Cat
inherit Animal, Pet
create
  make do end
feature
  name(): String do result := \"cat\" end
end
class Fish
inherit Animal
create
  make do end
feature
  name(): String do result := \"fish\" end
end
class Box [T]
create
  make(v: T) do item := v end
feature
  item: T
  put(v: T) do item := v end
end
")

(deftest generic-arguments-are-invariant-test
  (testing "a Box[Dog] is not a Box[Animal]"
    (is (re-find #"Cannot assign Box\[Dog\] to variable 'animals' of type Box\[Animal\]"
                 (type-error (str animals "let dogs: Box[Dog] := create Box[Dog].make(create Dog.make)
let animals: Box[Animal] := dogs
animals.put(create Animal.make)
print(dogs.item.bark())")))))
  (testing "nor is an Array[Dog] an Array[Animal]"
    (is (re-find #"Cannot assign Array\[Dog\] to variable 'animals' of type Array\[Animal\]"
                 (type-error (str animals "let dogs: Array[Dog] := [create Dog.make]
let animals: Array[Animal] := dogs")))))
  (testing "the same arguments still conform, and an heir still conforms to its instantiated parent"
    (is (= ["\"dog\"" "\"dog\""]
           (both (str animals "class Labelled_Box [T]
inherit Box[T]
create
  make(v: T) do super.make(v) end
end
let b: Box[Dog] := create Box[Dog].make(create Dog.make)
let same: Box[Dog] := b
print(same.item.name())
let heir: Box[Dog] := create Labelled_Box[Dog].make(create Dog.make)
print(heir.item.name())"))))))

(def ^:private point
  "class P
create
  make(n: Integer) do v := n end
feature
  v: Integer
end
")

(deftest and-narrows-every-conjunct-test
  (testing "both variables are narrowed in the then branch"
    (is (= ["3"]
           (both (str point "let x: ?P := create P.make(1)
let y: ?P := create P.make(2)
if x /= nil and y /= nil then
  print(x.v + y.v)
end")))))
  (testing "in a longer chain, and in a when"
    (is (= ["6" "6"]
           (both (str point "let x: ?P := create P.make(1)
let y: ?P := create P.make(2)
let z: ?P := create P.make(3)
if x /= nil and y /= nil and z /= nil then
  print(x.v + y.v + z.v)
end
print(when x /= nil and y /= nil and z /= nil then x.v + y.v + z.v else 0 end)")))))
  (testing "an `or` narrows nothing"
    (is (re-find #"Cannot call feature 'v' on detachable \?P"
                 (type-error (str point "let x: ?P := create P.make(1)
let y: ?P := create P.make(2)
if x /= nil or y /= nil then
  print(x.v)
end"))))))

(deftest and-narrows-its-right-operand-everywhere-test
  (testing "in contracts, assignments, arguments and results, on both backends"
    (is (= ["1" "Precondition violation: big" "Precondition violation: big"
            "false" "true" "false" "true" "true" "false" "true" "false" "true"]
           (both (str point "class Q
feature
  f(p: ?P): Integer
  require
    big: p /= nil and p.v > 10
  do
    result := 1
  ensure
    still: p /= nil and p.v > 10
  end
end
class Dog
create
  make(a: Integer) do age := a end
feature
  age: Integer
end
function big(p: ?P): Boolean do
  result := p /= nil and p.v > 10
end
function over(x: ?Integer): Boolean do
  result := x /= nil and x > 5
end
function old_dog(a: Any): Boolean do
  result := convert a to d: Dog and d.age > 10
end
function attached_big(p: ?P): Boolean do
  result := ?p as q and q.v > 10
end
let q := create Q
print(q.f(create P.make(20)))
do q.f(create P.make(3)) rescue print(exception) end
do q.f(nil) rescue print(exception) end
print(big(nil))
print(big(create P.make(30)))
print(over(nil))
print(over(9))
print(old_dog(create Dog.make(12)))
print(old_dog(\"x\"))
print(attached_big(create P.make(11)))
let n: ?P := nil
print(n /= nil and n.v > 1)
let y: ?Integer := 7
let ok := y /= nil and y + 1 = 8
assert ok and y /= nil and y = 7
print(ok)")))))
  (testing "the narrowing ends with the `and`, and `or` still narrows nothing"
    (is (re-find #"Cannot call feature 'v' on detachable \?P"
                 (type-error (str point "let x: ?P := create P.make(1)
let ok := x /= nil and x.v > 0
print(x.v)"))))
    (is (re-find #"Cannot call feature 'v' on detachable \?P"
                 (type-error (str point "let x: ?P := create P.make(1)
print(x = nil or x.v > 0)"))))))

(deftest when-has-the-join-of-its-branches-test
  (testing "unrelated scalar branches join to Any"
    (is (= ["1" "2.5"]
           (both "let c := true
let a := when c then 1 else \"a\" end
print(a)
let b := when not c then 1 else 2.5 end
print(b)")))
    (is (re-find #"got Any and Integer"
                 (type-error "let c := false
let a := when c then 1 else 2.5 end
print(a + 1)"))))
  (testing "sibling classes join to their one common ancestor"
    (is (= ["\"fish\""]
           (both (str animals "let c := false
let a := when c then create Dog.make else create Fish.make end
print(a.name())")))))
  (testing "two common ancestors, neither narrower, join to Any"
    (is (re-find #"Cannot assign Any to variable 'a' of type Animal"
                 (type-error (str animals "let c := false
let a: Animal := when c then create Dog.make else create Cat.make end")))))
  (testing "the result has the wider branch's type, whichever branch that is"
    (is (re-find #"`bark` is not a feature of Animal"
                 (type-error (str animals "let c := false
let d := when c then create Dog.make else create Animal.make end
print(d.bark())")))))
  (testing "an optional or nil branch makes the join optional"
    (is (= ["true" "3"]
           (both (str animals "let c := false
let maybe: ?Dog := nil
let o := when c then create Cat.make else maybe end
print(o = nil)
let k := when c then nil else 3 end
print(k)")))))
  (testing "equal generic types join to themselves"
    (is (= ["3"]
           (both (str animals "let c := false
let b := when c then create Box[Integer].make(1) else create Box[Integer].make(2) end
print(b.item + 1)"))))))
