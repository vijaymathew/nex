(ns nex.bounded-generics-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.file :as file]
            [nex.eval :as e]))

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

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines). Unlike compiles?
   above (which only checks that lowering does not throw), this actually
   RUNS the compiled backend — needed for the test below, whose bug was a
   runtime failure (a reflective method lookup landing on the wrong
   dispatch table), not a lowering-time one; compiles? alone would not
   have caught it."
  [code]
  (let [f (java.io.File/createTempFile "bounded_generics" ".nex")]
    (try
      (spit f code)
      (let [compiled (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
            interpreted (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest bounded-generic-bound-shadowing-a-builtin-name-dispatches-to-the-user-class-test
  (testing "a user-defined class named the SAME as a builtin-runtime-
            receiver-type (`Comparable`, `Task`, `Channel`, ...) still
            works as a bound — `[T -> Comparable]` must call the USER's
            own `Comparable.less_than`, not silently dispatch through the
            fixed, reflection-free builtin-Comparable runtime table (which
            has no idea the call even exists). Regression test: nex.lower/
            lower-general-receiver-call checked the builtin-constrained
            branch before the user-constrained one, so ANY bound whose
            name happened to collide with a builtin-runtime-receiver-type
            matched there unconditionally on the name alone — the user's
            own class, and its own real `less_than` method, was never
            reached at all. Renaming the bound to anything that didn't
            collide (e.g. `Rankable`) always worked; only the SPELLING
            collision broke it, which is exactly why this needs its own
            class-per-name coverage rather than relying on the pre-
            existing `bounded-generic-with-a-builtin-bound-still-works`
            test above, which uses the untouched, real builtin Comparable
            protocol via `>`, not a same-named user override."
    (is (= ["7"]
           (both "deferred class Comparable
feature
  less_than(other: Comparable): Boolean deferred
end

class Score inherit Comparable
feature
  v: Integer
create
  make(x: Integer) do v := x end
feature
  less_than(other: Comparable): Boolean
  do
    if convert other to o: Score then
      result := v < o.v
    else
      result := false
    end
  end
end

function best[T -> Comparable](a: T, b: T): T
do
  if a.less_than(b) then
    result := b
  else
    result := a
  end
end

let w := best(create Score.make(3), create Score.make(7))
if convert w to ws: Score then print(ws.v) end")))))
