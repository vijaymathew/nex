(ns nex.refinement-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

;; Refinement types (`declare type X = Base where n: <pred>`) register as an
;; alias to Base for type checking and inject a predicate check at every
;; narrowing site (let / parameter / return). These tests drive the injected
;; checks through the tree-walking interpreter.

(defn- run
  "Evaluate a whole program, returning its printed output."
  [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)]
    (interp/eval-node ctx ast)
    @(:output ctx)))

(defn- violates?
  "True if running the program raises a refinement violation."
  [code]
  (try
    (run code)
    false
    (catch clojure.lang.ExceptionInfo e
      (boolean (re-find #"Refinement" (str (ex-message e)))))))

(deftest refinement-valid-let-passes
  (testing "a value satisfying the predicate binds without error"
    (is (= ["6"]
           (run "declare type Quantity = Integer where n: n > 0
let q: Quantity := 5
print(q + 1)")))))

(deftest refinement-bad-let-raises
  (testing "a let that violates the predicate raises"
    (is (violates? "declare type Quantity = Integer where n: n > 0
let q: Quantity := -3
print(q)"))))

(deftest refinement-widening-is-free
  (testing "using a refinement where its base is wanted needs no check and no wrapper"
    (is (= ["13"]
           (run "declare type Quantity = Integer where n: n > 0
let q: Quantity := 3
let total: Integer := q + 10
print(total)")))))

(deftest refinement-parameter-checked-at-boundary
  (testing "passing a base value that violates the predicate raises at entry"
    (is (violates? "declare type Quantity = Integer where n: n > 0
function use(x: Quantity): Integer do result := x end
let bad: Integer := 0
print(use(bad))"))
    (is (= ["7"]
           (run "declare type Quantity = Integer where n: n > 0
function use(x: Quantity): Integer do result := x end
let ok: Integer := 7
print(use(ok))")))))

(deftest refinement-return-checked
  (testing "returning a value that violates the predicate raises"
    (is (violates? "declare type Quantity = Integer where n: n > 0
function make_bad(): Quantity do result := -1 end
print(make_bad())"))))

(deftest refinement-nested-let-checked
  (testing "a refinement let nested inside a body is still checked"
    (is (violates? "declare type Quantity = Integer where n: n > 0
function f(x: Integer): Integer do
  result := 0
  if x < 100 then
    let q: Quantity := x
    result := q
  end
end
print(f(-5))"))))

(deftest refinement-compound-predicate
  (testing "predicates may use connectives and the base type's methods"
    (is (= ["42.5"]
           (run "declare type Percentage = Real where p: p >= 0.0 and p <= 100.0
let pct: Percentage := 42.5
print(pct)")))
    (is (violates? "declare type Percentage = Real where p: p >= 0.0 and p <= 100.0
let pct: Percentage := 150.0
print(pct)"))
    (is (violates? "declare type NonEmpty = String where s: s.length() > 0
let e: NonEmpty := \"\"
print(e)"))))

(deftest refinement-plain-alias-still-works
  (testing "declare type without a where is unchanged (structural alias, no checks)"
    (is (= ["7"]
           (run "declare type Count = Integer
let c: Count := -7
print(c * -1)")))))

(deftest where-is-a-soft-keyword
  (testing "`where` stays usable as a member name alongside a refinement decl"
    (is (= ["3"]
           (run "declare type Quantity = Integer where n: n > 0
let s: Set[Integer] := #{1, 2}
let t: Set[Integer] := #{2, 3}
print(s.union(t).size())")))))
