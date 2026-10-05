(ns nex.closure-narrowed-capture-test
  "Regression coverage for a closure capturing a name that a condition binds:
   `convert e to x: T`, `?e as x`, or a match clause's destructured field.

   The compiled backend decides what a closure captures in a pre-pass
   (nex.lower's rewrite-*-for-closures) that tracks the local names in scope.
   Names a condition binds were never added, so a closure in the code that
   runs once the condition held -- the rest of its `and` chain, an `if`'s
   then branch, a `when`'s consequent, a match clause's guard or body --
   did not capture them, and lowering failed with \"Unable to infer
   expression type during lowering\". The interpreter was unaffected.

   That code can only run with the name bound, so it is captured at the
   narrowed type the typechecker gives it there (`Circle`, not `?Circle`);
   the scalar cases pin that a boxed convert slot feeds a primitive capture."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "closure_narrowed_capture" ".nex")]
    (try
      (spit f code)
      (let [compiled (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
            interpreted (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(def ^:private circle-decl
  "class Circle
create
  make(r: Real) do
    radius := r
  end
feature
  radius: Real
end
")

(deftest closure-in-then-branch-captures-convert-binding-test
  (testing "inside a function and at top level"
    (is (= ["2.5" "true"]
           (both (str circle-decl
                      "function g(a: Any): Real do
  result := 0.0
  if convert a to c: Circle then
    let f := fn(k: Real): Real do result := k + c.radius end
    result := f(0.5)
  end
end
print(g(create Circle.make(2.0)))
let a: Any := create Circle.make(2.0)
if convert a to c: Circle then
  let f := fn(k: Real): Boolean do result := k < c.radius end
  print(f(1.0))
end"))))))

(deftest closure-later-in-and-chain-captures-convert-binding-test
  (testing "the right of an `and` runs only once the left's convert held"
    (is (= ["\"big\"" "\"small\""]
           (both (str circle-decl
                      "function g(a: Any): String do
  result := \"small\"
  if convert a to c: Circle and (fn(k: Real): Boolean do result := k < c.radius end)(1.0) then
    result := \"big\"
  end
end
print(g(create Circle.make(2.0)))
print(g(create Circle.make(0.5)))"))))))

(deftest closure-captures-attached-test-binding-test
  (testing "`?e as x` binds x at e's attached type for the closure"
    (is (= ["true" "false"]
           (both (str circle-decl
                      "function g(s: ?Circle): Boolean do
  result := false
  if ?s as c and (fn(k: Real): Boolean do result := k < c.radius end)(1.0) then
    result := true
  end
end
print(g(create Circle.make(2.0)))
print(g(nil))"))))))

(deftest closure-in-when-and-elseif-captures-convert-binding-test
  (testing "a `when` consequent and an `elseif` branch see their own condition's binding"
    (is (= ["true" "6.0"]
           (both (str circle-decl
                      "function w(a: Any): Boolean do
  result := when convert a to c: Circle and (fn(k: Real): Boolean do result := k < c.radius end)(1.0) then true else false end
end
function e(a: Any): Real do
  result := 0.0
  if convert a to n: Integer then
    result := 1.0
  elseif convert a to c: Circle then
    let f := fn(): Real do result := c.radius end
    result := f()
  end
end
print(w(create Circle.make(2.0)))
print(e(create Circle.make(6.0)))"))))))

(deftest closure-captures-narrowed-scalar-convert-bindings-test
  (testing "a scalar convert target is stored boxed but captured as the scalar"
    (is (= ["3.5" "50" "0" "\"hi!\""]
           (both "function r(a: Any): Real do
  result := 0.0
  if convert a to x: Real then
    let f := fn(k: Real): Real do result := k + x end
    result := f(1.0)
  end
end
function i(a: Any): Integer do
  result := 0
  if convert a to n: Integer and (fn(): Boolean do result := n > 3 end)() then
    let f := fn(k: Integer): Integer do result := k * n end
    result := f(10)
  end
end
function s(a: Any, b: Any): String do
  result := \"no\"
  if convert a to t: String and convert b to flag: Boolean then
    let f := fn(): String do result := when flag then t + \"!\" else t end end
    result := f()
  end
end
print(r(2.5))
print(i(5))
print(i(2))
print(s(\"hi\", true))")))))

(deftest closure-capturing-convert-binding-outlives-the-if-test
  (testing "a nested closure, and a closure stored and called after the `if`"
    (is (= ["6.0" "5.0"]
           (both (str circle-decl
                      "function g(a: Any): Real do
  result := 0.0
  if convert a to c: Circle then
    let outer := fn(): Function(k: Real): Real do
      result := fn(k: Real): Real do result := k * c.radius end
    end
    result := outer()(3.0)
  end
end
print(g(create Circle.make(2.0)))
let saved: ?Function(k: Real): Real := nil
let a: Any := create Circle.make(4.0)
if convert a to c: Circle then
  saved := fn(k: Real): Real do result := k + c.radius end
end
if ?saved as f then print(f(1.0)) end"))))))

(deftest closure-in-match-captures-pattern-bindings-test
  (testing "a match body sees destructured fields, and a guard sees a nested
            pattern's binding"
    (is (= ["3.0" "\"wheel\""]
           (both "union Shape
  Circle(radius: Real)
  Labelled(inner: Any, label: String)
end
function body(s: Shape): Real do
  result := 0.0
  match s of
    Circle(radius) then do
      let f := fn(k: Real): Real do result := k + radius end
      result := f(1.0)
    end
    Labelled(inner, label) then result := 0.0
  end
end
function guard(s: Shape): String do
  result := \"?\"
  match s of
    Labelled(inner: Circle(radius as r), label) if (fn(k: Real): Boolean do result := k < r end)(1.0) then result := label
    Labelled(inner, label) then result := \"small\"
    Circle(radius)         then result := \"circle\"
  end
end
print(body(create Circle.make(2.0)))
print(guard(create Labelled.make(create Circle.make(2.0), \"wheel\")))")))))
