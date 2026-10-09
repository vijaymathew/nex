(ns nex.any-type-argument-test
  "Two ways a value could get past generic invariance through `Any`.

   1. `Any` as a type argument matched any type, so a Box[Dog] passed as a
      Box[Any], and through it took a Cat. That wildcard was really for
      arguments inference leaves open — `[]`, `{}`, `create Ok.make(5)` —
      which now get their own placeholder (nex.typechecker/unknown-type-arg,
      compared by type-args-match?). A written `Any` argument is only Any.
   2. An `Any` value could be assigned to a parameterized type without
      `convert` (`let m: Map[String, Any] := json.parse(text)`), although the
      value need not be a Map at all. It now needs one, as for any other type
      (nex.typechecker/any-into-concrete-without-convert?)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "any_type_argument" ".nex")]
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

(def ^:private classes
  "class Animal
create
  make do end
end
class Dog
inherit Animal
create
  make do end
end
class Box [T]
create
  make(v: T) do item := v end
feature
  item: T
  put(v: T) do item := v end
end
")

(deftest any-type-argument-is-only-any-test
  (testing "a Box[Dog] is not a Box[Any], nor the reverse"
    (is (re-find #"Cannot assign Box\[Dog\] to variable 'anything' of type Box\[Any\]"
                 (type-error (str classes "let dogs: Box[Dog] := create Box[Dog].make(create Dog.make)
let anything: Box[Any] := dogs"))))
    (is (re-find #"Cannot assign Box\[Any\] to variable 'dogs' of type Box\[Dog\]"
                 (type-error (str classes "let anything: Box[Any] := create Box[Any].make(1)
let dogs: Box[Dog] := anything")))))
  (testing "nor is an Array[T] an Array[Any] inside a generic routine"
    (is (re-find #"should be Array\[Any\], but got Array\[T\]"
                 (type-error "function size_of(a: Array[Any]): Integer do result := a.length end
function count[T](a: Array[T]): Integer do result := size_of(a) end")))))

(deftest inferred-type-arguments-still-fit-test
  (testing "empty literals, inference from constructor arguments, and the declared type"
    (is (= ["[2]" "{\"a\": 1}" "0" "5" "7" "\"Array[Any]\""]
           (both (str classes "let ys: Array[Integer] := []
ys.add(2)
print(ys)
let m: Map[String, Integer] := {}
m.set(\"a\", 1)
print(m)
let e := []
print(e.length)
let b: Box[Any] := create Box.make(5)
print(b.item)
let c: Box[Integer] := create Box.make(6)
print(c.item + 1)
function describe[T](a: Array[T]): String do result := \"Array[Any]\" end
print(describe([]))")))))
  (testing "a type parameter is inferred through an heir's inherit chain"
    (is (= ["42"]
           (both "class Base[X, Y]
  feature
    x: X
    y: Y
  create make(a: X, b: Y) do x := a  y := b end
end
class Mid[P, Q]
  inherit
    Base[Q, P]
  create make(p: P, q: Q) do super.make(q, p) end
end
class Leaf
  inherit
    Mid[String, Integer]
  create make(s: String, i: Integer) do super.make(s, i) end
end
function first_field[T, U](b: Base[T, U]): T do
  result := b.x
end
let l: Leaf := create Leaf.make(\"hello\", 42)
print(first_field(l))")))))

(deftest any-into-parameterized-type-needs-convert-test
  (testing "a plain Any value"
    (is (re-find #"Cannot assign Any to variable 'd' of type Box\[Integer\] without narrowing it first"
                 (type-error (str classes "let x: Any := 3
let d: Box[Integer] := x"))))
    (is (re-find #"Cannot assign Any to variable 'm' of type Map\[String, Any\] without narrowing it first"
                 (type-error "let x: Any := 3
let m: Map[String, Any] := x"))))
  (testing "convert narrows it, and fails cleanly on the wrong shape"
    (is (= ["2" "false"]
           (both "let parsed: Any := {\"a\": 1, \"b\": 2}
if convert parsed to m: Map[String, Any] then print(m.size) end
let other: Any := 3
print(convert other to n: Map[String, Any])")))))
