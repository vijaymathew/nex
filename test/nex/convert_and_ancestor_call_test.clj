(ns nex.convert-and-ancestor-call-test
  "Two groups of bug fixes, each checked on both backends.

   `convert e to x: S` is an expression of type Boolean (Definition, Type
   conversion): true when the value conforms to S, else false with x bound to
   nil. A failed convert evaluated to nil in the interpreter; on the compiled
   backend, a convert anywhere but an `if` condition, a `let` or a statement
   of its own had no slot for x (\"convert binding must exist before lowering
   expression\"), and one stored into a top-level variable left a stray value
   on the stack (a VerifyError, so the program fell back to the interpreter).

   `Ancestor.m(...)` runs the ancestor's own version of m on this object. The
   compiled backend handled only a direct parent, so a grandparent's routine
   crashed lowering, and so did `Ancestor.field`. A generic ancestor's types
   are the ones the inherit chain binds. Naming a class that is not an
   ancestor is now a type error: there is no object to run the routine on."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "convert_and_ancestor_call" ".nex")]
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

(def ^:private animals
  "class Animal
create
  make do end
end
class Dog
inherit Animal
create
  make do end
feature
  bark(): String do result := \"woof\" end
end
")

(deftest failed-convert-is-false-test
  (is (= ["false" "true"]
         (both (str animals "let c: Animal := create Animal.make
let ok := convert c to d: Dog
print(ok)
let a: Animal := create Dog.make
let ok2 := convert a to e: Dog
print(ok2)")))))

(deftest convert-anywhere-an-expression-goes-test
  (testing "as a call argument, at top level and in a routine, with its variable in scope afterwards"
    (is (= ["true" "false" "true" "\"woof\"" "false" "\"not a dog\"" "true"]
           (both (str animals "function check(a: Animal): String do
  print(convert a to d: Dog)
  if d /= nil then result := d.bark() else result := \"not a dog\" end
end
let a: Animal := create Dog.make
print(convert a to x: Dog)
let c: Animal := create Animal.make
print(convert c to y: Dog)
print(check(create Dog.make))
print(check(create Animal.make))
let maybe: ?Dog := create Dog.make
print(?maybe as m)")))))
  (testing "in a when, and returned from a method"
    (is (= ["\"woof\"" "false" "true"]
           (both (str animals "class Kennel
create
  make do end
feature
  test(a: Animal): Boolean do result := convert a to k: Dog end
end
let a: Animal := create Dog.make
print(when convert a to w: Dog then w.bark() else \"no\" end)
let kn: Kennel := create Kennel.make
print(kn.test(create Animal.make))
print(kn.test(create Dog.make))")))))
  (testing "nested inside an if condition"
    (is (= ["true" "\"no\"" "\"woof\""]
           (both (str animals "function yes(b: Boolean): Boolean do result := b end
function f(a: Animal) do
  if yes(convert a to d: Dog) then print(d /= nil) else print(\"no\") end
end
f(create Dog.make)
f(create Animal.make)
let a: Animal := create Dog.make
if yes(convert a to t: Dog) and t /= nil then print(t.bark()) end")))))
  (testing "two guards of one name with different targets"
    (is (= ["\"woof\"" "\"meow\""]
           (both (str animals "class Cat
inherit Animal
create
  make do end
feature
  meow(): String do result := \"meow\" end
end
function f(a: Animal, b: Animal) do
  if convert a to d: Dog then print(d.bark()) end
  if convert b to d: Cat then print(d.meow()) end
end
f(create Dog.make, create Cat.make)"))))))

(deftest ancestor-qualified-calls-test
  (testing "a grandparent's routine, one it only inherits, a second parent's, without parens, in an expression"
    (is (= ["\"puppy dog animal\"" "\"being\"" "\"being\"" "\"pet owner\"" "\"animal\"" "\"animal!\""]
           (both "class Being
create
  make do end
feature
  kind(): String do result := \"being\" end
end
class Animal
inherit Being
create
  make do end
feature
  name(): String do result := \"animal\" end
end
class Pet
create
  make do end
feature
  owner(): String do result := \"pet owner\" end
end
class Dog
inherit Animal, Pet
create
  make do end
feature
  name(): String do result := \"dog\" end
  owner(): String do result := \"dog owner\" end
end
class Puppy
inherit Dog
create
  make do end
feature
  name(): String do result := \"puppy\" end
  kind(): String do result := \"puppy kind\" end
  report() do
    print(name() + \" \" + Dog.name() + \" \" + Animal.name())
    print(Animal.kind())
    print(Being.kind())
    print(Pet.owner())
    print(Animal.name)
    let s := Animal.name() + \"!\"
    print(s)
  end
end
let p: Puppy := create Puppy.make
p.report"))))
  (testing "a generic ancestor's routine, typed through the inherit chain"
    (is (= ["10"]
           (both "class Box [T]
create
  make(v: T) do item := v end
feature
  item: T
  get(): T do result := item end
end
class Int_Box
inherit Box[Integer]
create
  make(v: Integer) do super.make(v) end
end
class Small_Int_Box
inherit Int_Box
create
  make(v: Integer) do super.make(v) end
feature
  get(): Integer do result := 0 end
  both(): Integer do result := Box.get() + Int_Box.get() + get() end
end
let sb := create Small_Int_Box.make(5)
print(sb.both())"))))
  (testing "an ancestor's field"
    (is (= ["\"anim\"" "\"anim!\""]
           (both "class Animal
create
  make do label := \"anim\" end
feature
  label: String
end
class Dog
inherit Animal
create
  make do super.make end
end
class Puppy
inherit Dog
create
  make do super.make end
feature
  show() do print(Animal.label) print(Dog.label + \"!\") end
end
let p: Puppy := create Puppy.make
p.show")))))

(deftest class-qualified-call-needs-an-ancestor-test
  (testing "from an unrelated class"
    (is (re-find #"Animal.name\(\.\.\.\) is not reachable here: Animal is not an ancestor of Rock"
                 (type-error "class Animal
create
  make do end
feature
  name(): String do result := \"animal\" end
end
class Rock
create
  make do end
feature
  show(): String do result := Animal.name() end
end"))))
  (testing "naming the current class itself"
    (is (re-find #"Rock.show\(\.\.\.\) names Rock itself"
                 (type-error "class Rock
create
  make do end
feature
  show(): String do result := \"rock\" end
  again(): String do result := Rock.show() end
end"))))
  (testing "from outside any class"
    (is (re-find #"can only be called from inside a class that inherits Animal"
                 (type-error "class Animal
create
  make do end
feature
  name(): String do result := \"animal\" end
end
print(Animal.name())")))))
