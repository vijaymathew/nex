(ns nex.stdlib-result-option-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

;; Exercises the stdlib Result/Option modules (lib/data/result.nex,
;; lib/data/option.nex) through `intern`, which relies on:
;;   - intern exporting free functions (the result_*/option_* combinators)
;;   - match dispatch on generic sealed classes at runtime
;;   - definite-assignment recognising an exhaustive match
;; Tests run from the repo root so `intern data/Result` resolves under ./lib.

(defn- run
  "Evaluate a whole program, returning its printed output."
  [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)]
    (interp/eval-node ctx ast)
    @(:output ctx)))

(deftest result-map-and-query
  (testing "result_map transforms Ok, leaves Err; query methods work"
    (is (= ["20" "true"]
           (run "intern data/Result
let r: Result[Integer, String] := create Ok[Integer, String].make(10)
let d: Result[Integer, String] := result_map(r, fn (x: Integer): Integer do result := x * 2 end)
print(d.unwrap_or(0))
print(d.is_ok())")))))

(deftest result-err-unwrap-or
  (testing "unwrap_or on an Err returns the fallback"
    (is (= ["-1" "false"]
           (run "intern data/Result
let e: Result[Integer, String] := create Err[Integer, String].make(\"boom\")
print(e.unwrap_or(-1))
print(e.is_ok())")))))

(deftest result-and-then-short-circuits
  (testing "result_and_then chains Ok and short-circuits on Err"
    (is (= ["6"]
           (run "intern data/Result
function half(x: Integer): Result[Integer, String] do
  if x % 2 == 0 then result := create Ok[Integer, String].make(x / 2)
  else result := create Err[Integer, String].make(\"odd\") end
end
let r: Result[Integer, String] := create Ok[Integer, String].make(12)
let out: Result[Integer, String] := result_and_then(r, fn (x: Integer): Result[Integer, String] do result := half(x) end)
print(out.unwrap_or(-1))")))
    (is (= ["-1"]
           (run "intern data/Result
function half(x: Integer): Result[Integer, String] do
  if x % 2 == 0 then result := create Ok[Integer, String].make(x / 2)
  else result := create Err[Integer, String].make(\"odd\") end
end
let r: Result[Integer, String] := create Ok[Integer, String].make(7)
let out: Result[Integer, String] := result_and_then(r, fn (x: Integer): Result[Integer, String] do result := half(x) end)
print(out.unwrap_or(-1))")))))

(deftest result-map-err-composes-error-type
  (testing "result_map_err converts the error channel (Err[E1] -> Err[E2])"
    (is (= ["4"]
           (run "intern data/Result
let e: Result[Integer, String] := create Err[Integer, String].make(\"oops\")
let e2: Result[Integer, Integer] := result_map_err(e, fn (m: String): Integer do result := m.length() end)
print(e2.unwrap_or(0) + e2.unwrap_or(4))")))))

(deftest option-map-and-filter
  (testing "option_map transforms Some; option_filter can drop it to None"
    (is (= ["true" "22"]
           (run "intern data/Option
let o: Option[Integer] := create Some[Integer].make(11)
let d: Option[Integer] := option_map(o, fn (x: Integer): Integer do result := x * 2 end)
print(d.is_some())
print(d.get_or(0))")))
    (is (= ["true" "-1"]
           (run "intern data/Option
let o: Option[Integer] := create Some[Integer].make(7)
let big: Option[Integer] := option_filter(o, fn (x: Integer): Boolean do result := x > 100 end)
print(big.is_none())
print(big.get_or(-1))")))))

(deftest option-and-then
  (testing "option_and_then chains and short-circuits on None"
    (is (= ["49"]
           (run "intern data/Option
function nonzero(x: Integer): Option[Integer] do
  if x == 0 then result := create None[Integer].make()
  else result := create Some[Integer].make(x * x) end
end
let o: Option[Integer] := create Some[Integer].make(7)
let r: Option[Integer] := option_and_then(o, fn (x: Integer): Option[Integer] do result := nonzero(x) end)
print(r.get_or(-1))")))))
