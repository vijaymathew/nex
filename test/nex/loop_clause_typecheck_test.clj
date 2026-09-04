(ns nex.loop-clause-typecheck-test
  "nex.typechecker/check-loop destructured a loop statement's exit test as
   `:condition` — but every loop-node producer in nex.walker
   (handle-loop-statement/handle-repeat-statement/handle-across-statement)
   has always named that field `:until`. The mismatch meant `(when condition
   ...)` never ran: a loop's `until` clause (and its `invariant`/`variant`
   clauses, which this function never checked at all) went completely
   unchecked by the static typechecker — not for well-typedness (a
   non-Boolean `until` test, a non-Integer `variant`), and not even for
   basic soundness (an undefined variable referenced only inside one of
   them). Such a program typechecked successfully and only failed much
   later and far less clearly, during lowering, as an opaque \"Unable to
   infer expression type during lowering\" internal-compiler-error report —
   on a program that should have been rejected outright at type-check
   time with a plain 'Undefined variable' error."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- type-check-errors
  "Formatted type errors, with the leading 'Type error at line X, column Y: '
   location prefix stripped, so tests can assert on the message alone."
  [code]
  (let [result (tc/type-check (p/ast code))]
    (map #(str/replace % #"^Type error at line \d+, column \d+: " "")
         (map tc/format-type-error (:errors result)))))

(defn- type-checks? [code]
  (:success (tc/type-check (p/ast code))))

(deftest undefined-variable-referenced-only-in-a-loop-until-clause-is-rejected-test
  (testing "an identifier that is not in scope anywhere, referenced only inside a
            loop's `until` condition, is a clear 'Undefined variable' type error
            instead of silently type-checking and crashing later during lowering"
    (is (= ["Undefined variable: tolerance"]
           (type-check-errors "function close_enough(tolerance, x, y: Real): Boolean
do
  result := (x - y) < tolerance
end
function fixed_point(first_guess: Real): Real
do
  from
    let guess := first_guess
    result := guess
  until
    close_enough(tolerance, guess, result) = true
  do
    result := guess
  end
end
print(fixed_point(1.0))")))))

(deftest non-boolean-until-condition-is-rejected-test
  (testing "a loop's `until` clause must be Boolean"
    (is (= ["Loop until condition must be Boolean, got Integer"]
           (type-check-errors "function f(): Integer
do
  from
    let i := 0
    result := 0
  until
    5
  do
    result := result + 1
    i := i + 1
  end
end
print(f())")))))

(deftest non-integer-variant-is-rejected-test
  (testing "a loop's `variant` clause must be Integer"
    (is (= ["Loop variant must be Integer, got Boolean"]
           (type-check-errors "function f(): Integer
do
  from
    let i := 0
    result := 0
  variant
    true
  until
    i = 10
  do
    result := result + 1
    i := i + 1
  end
end
print(f())")))))

(deftest non-boolean-invariant-is-rejected-test
  (testing "a loop's `invariant` clause(s) must be Boolean"
    (is (= ["Loop invariant must be Boolean, got Integer"]
           (type-check-errors "function f(): Integer
do
  from
    let i := 0
    result := 0
  invariant
    ok: 5
  until
    i = 10
  do
    result := result + 1
    i := i + 1
  end
end
print(f())")))))

(deftest well-typed-until-invariant-and-variant-still-type-check-test
  (testing "a loop with correctly-typed until/invariant/variant clauses is unaffected"
    (is (type-checks? "function f(): Integer
do
  from
    let i := 0
    result := 0
  invariant
    ok: i >= 0
  variant
    10 - i
  until
    i = 10
  do
    result := result + 1
    i := i + 1
  end
end
print(f())"))))
