(ns nex.routine-result-test
  "A routine returns whatever `result` holds when it exits. `result` starts at
   its return type's zero value; only an explicit `result := ...` changes it.
   A trailing expression is NOT returned (there once was an implicit
   tail-expression rule on the compiled backend only — the interpreter never
   had it). A return type with no zero value (String, an attached user class)
   must definitely assign `result`, which the typechecker enforces."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- run-lines [code]
  (let [f (java.io.File/createTempFile "routine_result" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline
                        (with-out-str (e/eval-file (.getPath f) {:interpret? false}))))
      (finally (.delete f)))))

(defn- type-errors [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (mapv tc/format-type-error (:errors result)))))

(deftest trailing-expression-is-not-the-result-test
  (testing "with no `result :=`, a routine returns the zero value whatever its
            last statement is"
    (is (= ["0" "0" "0" "0" "0" "0" "0"]
           (run-lines "function doubled(n: Integer): Integer do n * 2 end
function id(k: Integer): Integer do k end
function lit(): Integer do 11 end
function via_call(n: Integer): Integer do doubled(n) + 1 end
function classify(n: Integer): Integer do
  if n > 0 then 1 elseif n < 0 then -1 else 7 end
end
class P
  create make(v: Integer) do x := v end
  feature
    x: Integer
    get_x: Integer do x end
end
let f := fn (k: Integer): Integer do k + 1 end
print(doubled(21))
print(id(7))
print(lit())
print(via_call(3))
print(classify(5))
print((create P.make(4)).get_x)
print(f(1))")))))

(deftest zero-values-test
  (testing "each return type with a zero value starts `result` at it"
    (is (= ["0" "0.0" "false" "[]" "{}" "#{}" "nil" "nil"]
           (run-lines "function i(n: Integer): Integer do n + 1 end
function r(): Real do 1.5 end
function b(): Boolean do true end
function a(): Array[Integer] do [1, 2] end
function m(): Map[String, Integer] do {\"k\": 1} end
function s(): Set[Integer] do #{1} end
function ds(): ?String do \"x\" end
function da(): ?Array[Integer] do [1] end
print(i(1))
print(r())
print(b())
print(a())
print(m())
print(s())
print(ds())
print(da())")))))

(deftest no-zero-value-must-assign-result-test
  (testing "a trailing expression does not count as assigning `result`"
    (doseq [src ["function s(): String do \"hi\" end"
                 "class Pt feature x: Integer end
function p(): Pt do create Pt end"
                 "class C
  feature
    name: String do \"c\" end
end"]]
      (is (some #(str/includes? % "does not definitely assign result")
                (type-errors src))
          src)))
  (testing "an explicit assignment on every returning path does"
    (is (nil? (type-errors "function s(n: Integer): String do
  if n > 0 then result := \"pos\" else result := \"other\" end
end")))))

(deftest explicit-assignment-is-the-result-test
  (testing "the value `result` holds on exit: the last assignment that ran,
            or a mutation of the auto-created collection"
    (is (= ["42" "1" "2" "0" "9" "3" "[1, 2]"]
           (run-lines "function doubled(n: Integer): Integer do result := n * 2 end
function e(n: Integer): Integer do
  if n > 0 then result := 1 else result := 2 end
end
function last_wins(): Integer do
  result := 5
  result := 9
  print(0)
end
function looped(n: Integer): Integer do
  from let i := 0 until i = n do
    result := result + 1
    i := i + 1
  end
end
function built(): Array[Integer] do
  result.add(1)
  result.add(2)
end
print(doubled(21))
print(e(1))
print(e(-1))
print(last_wins())
print(looped(3))
print(built())")))))

(deftest nested-routine-result-is-its-own-test
  (testing "`result :=` inside a nested fn or spawn sets that body's result,
            never the enclosing routine's"
    (is (= ["7" "0" "5" "0"]
           (run-lines "function outer_fn(n: Integer): Integer do
  let f := fn (k: Integer): Integer do result := k end
  print(f(7))
end
function outer_spawn(n: Integer): Integer do
  let t := spawn do result := n end
  print(t.await)
end
print(outer_fn(1))
print(outer_spawn(5))")))))

(deftest bare-name-statement-test
  (testing "a bare variable name as a statement typechecks as that variable's
            value (both backends evaluate it so) rather than as a call of it,
            and like any trailing expression is not returned"
    (is (nil? (type-errors "function id(k: Integer): Integer do k end")))
    (is (= ["0"] (run-lines "function id(k: Integer): Integer do k end
print(id(7))"))))
  (testing "the typechecker types a paren-less bare name as the variable —
            a Function value included, which is not called — while `v()`
            calls it"
    (let [bare {:type :call :target nil :method "v" :args [] :has-parens false}
          fn-type {:base-type "Function" :param-types [] :return-type "Integer"}]
      (is (= "Integer" (tc/infer-expression-type bare {:var-types {"v" "Integer"}})))
      (is (= fn-type (tc/infer-expression-type bare {:var-types {"v" fn-type}})))
      (is (= "Integer"
             (tc/infer-expression-type (assoc bare :has-parens true)
                                       {:var-types {"v" fn-type}})))))
  (testing "a paren-less class routine is still called"
    (is (= ["5"]
           (run-lines "class Q
  feature
    five: Integer do result := 5 end
    again: Integer do result := five end
end
print((create Q).again)")))))
