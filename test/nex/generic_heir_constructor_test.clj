(ns nex.generic-heir-constructor-test
  "Constructors of a class that inherits an instantiated generic parent
   (`class Dog_Box inherit Box[Dog]`), and constructors inherited across more
   than one level.

   The parent's constructor is written in its own generic parameters
   (`make(v: T)`), which the inherit clause binds (T = Dog). The typechecker
   left T unresolved — `create Dog_Box.make(dog)` and `super.make(dog)` both
   failed with \"Expected T, got Dog\" — and the compiled backend's inherited-
   constructor shim declared a parameter of the nonexistent class T. And a
   constructor inherited through a parent that itself only inherits it got no
   shim at all, so the compiled program failed to link and fell back to the
   interpreter."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "generic_heir_constructor" ".nex")]
    (try
      (spit f code)
      (let [err (java.io.StringWriter.)
            out (binding [*err* err]
                  (with-out-str (e/eval-file (.getPath f) opts)))]
        (is (not (str/includes? (str err) "falling back")) (str err))
        (str/split-lines (str/trim-newline out)))
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
  "class Dog
create
  make do end
feature
  name(): String do result := \"dog\" end
end
class Cat
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
class Pair [A, B]
create
  make(a: A, b: B) do first := a second := b end
feature
  first: A
  second: B
end
")

(deftest heir-of-instantiated-generic-test
  (testing "the constructor called through super"
    (is (= ["\"dog\""]
           (both (str classes "class Dog_Box
inherit Box[Dog]
create
  make(v: Dog) do super.make(v) end
end
let b: Box[Dog] := create Dog_Box.make(create Dog.make)
print(b.item.name())")))))
  (testing "the constructor inherited"
    (is (= ["\"dog\"" "\"dog\""]
           (both (str classes "class Dog_Box
inherit Box[Dog]
end
let b: Box[Dog] := create Dog_Box.make(create Dog.make)
print(b.item.name())
let d: Dog_Box := create Dog_Box.make(create Dog.make)
print(d.item.name())")))))
  (testing "an argument of the wrong type is reported against the instantiated type"
    (is (re-find #"should be Dog, but got Cat"
                 (type-error (str classes "class Dog_Box
inherit Box[Dog]
end
let b := create Dog_Box.make(create Cat.make)"))))
    (is (re-find #"should be Dog, but got Cat"
                 (type-error (str classes "class Dog_Box
inherit Box[Dog]
create
  make(v: Cat) do super.make(v) end
end"))))))

(deftest generic-heirs-test
  (testing "arguments threaded into a nested type, reordered, and inferred"
    (is (= ["2" "\"s1\"" "6"]
           (both (str classes "class List_Box [T]
inherit Box[Array[T]]
end
class Swapped [X, Y]
inherit Pair[Y, X]
end
class Labelled_Box [T]
inherit Box[T]
end
let lb: Box[Array[Integer]] := create List_Box[Integer].make([1, 2])
print(lb.item.length)
let sw: Pair[String, Integer] := create Swapped[Integer, String].make(\"s\", 1)
print(sw.first + sw.second.to_string)
let inferred := create Labelled_Box.make(5)
let as_box: Box[Integer] := inferred
print(as_box.item + 1)"))))))

(deftest super-routine-of-instantiated-generic-test
  (is (= ["\"3 2\""]
         (both (str classes "class Counting_Box
inherit Box[Integer]
create
  make(v: Integer) do super.make(v) puts := 0 end
feature
  puts: Integer
  put(v: Integer) do super.put(v) puts := puts + 1 end
end
let cb := create Counting_Box.make(1)
cb.put(2)
cb.put(3)
print(cb.item.to_string + \" \" + cb.puts.to_string)")))))

(deftest constructor-inherited-across-two-levels-test
  (testing "through an instantiated generic"
    (is (= ["\"dog\""]
           (both (str classes "class Dog_Box
inherit Box[Dog]
end
class Small_Dog_Box
inherit Dog_Box
end
let sd: Box[Dog] := create Small_Dog_Box.make(create Dog.make)
print(sd.item.name())")))))
  (testing "with no generics at all"
    (is (= ["\"dog\""]
           (both (str classes "class Kennel
create
  make(v: Dog) do item := v end
feature
  item: Dog
end
class Big_Kennel
inherit Kennel
end
class Huge_Kennel
inherit Big_Kennel
end
let k: Kennel := create Huge_Kennel.make(create Dog.make)
print(k.item.name())"))))))
