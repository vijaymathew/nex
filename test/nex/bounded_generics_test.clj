(ns nex.bounded-generics-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.file :as file]))

;; Eiffel-style constrained genericity: `function f[T -> Bound](…)` lets the body
;; call the routines of `Bound` on a value of type `T`. The typechecker has always
;; accepted this, but the JVM backend could not lower a call whose receiver was a
;; bound-constrained type parameter ("Unsupported target call expression for
;; lowering") unless the bound was a *builtin* like Comparable — a user-class bound
;; compiled only on the interpreter. These tests pin the compiled path.

(def shapes-program
  "deferred class Shape
  feature
    area(): Integer deferred
    name: String
    describe(): String do result := name end
end

class Square
  inherit Shape
  feature
    once side: Integer
    area(): Integer do result := side * side end
  create
    make(s: Integer) do side := s  name := \"square\" end
end

class Circle
  inherit Shape
  feature
    once r: Integer
    area(): Integer do result := 3 * r * r end
  create
    make(v: Integer) do r := v  name := \"circle\" end
end

function total_area[T -> Shape](xs: Array[T]): Integer do
  result := 0
  across xs as s do
    result := result + s.area()
  end
end

function first_described[T -> Shape](xs: Array[T]): String do
  result := xs.get(0).describe
end

function first_named[T -> Shape](xs: Array[T]): String do
  result := xs.get(0).name
end

let shapes: Array[Shape] := [create Square.make(3), create Circle.make(2)]
")

(defn- run-output
  [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)]
    (interp/eval-node ctx ast)
    @(:output ctx)))

(defn- compiles?
  "True when the compiled backend accepts the program. Before the fix these threw
   'Unsupported target call expression for lowering' during lowering."
  [code]
  (file/compile-ast "bounded.nex" (p/ast code) {})
  true)

(deftest bounded-generic-calls-a-routine-of-its-bound
  (testing "a deferred routine of the bound dispatches to the runtime subclass"
    (is (= ["21"] (run-output (str shapes-program "print(total_area(shapes))")))))

  (testing "and the compiled backend can lower it"
    (is (compiles? (str shapes-program "print(total_area(shapes))")))))

(deftest bounded-generic-supports-paren-less-calls-and-fields
  (testing "a no-arg routine of the bound written without parentheses is a call,
            not a field read"
    (is (= ["\"square\""]
           (run-output (str shapes-program "print(first_described(shapes))"))))
    (is (compiles? (str shapes-program "print(first_described(shapes))"))))

  (testing "a field of the bound still reads as a field"
    (is (= ["\"square\""]
           (run-output (str shapes-program "print(first_named(shapes))"))))
    (is (compiles? (str shapes-program "print(first_named(shapes))")))))

(deftest bounded-generic-with-a-builtin-bound-still-works
  (testing "Comparable, the bound that always compiled, is unaffected"
    (is (compiles? "function largest[T -> Comparable](xs: Array[T], seed: T): T do
  result := seed
  across xs as x do
    if x > result then
      result := x
    end
  end
end
let ns: Array[Integer] := [3, 9, 4]
print(largest(ns, 0))"))))
