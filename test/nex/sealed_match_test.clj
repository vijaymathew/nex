(ns nex.sealed-match-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]))

(def ^:private result-hierarchy "
sealed deferred class Result
end

class Ok
  inherit Result
  feature value: Integer
  create make(v: Integer) do value := v end
end

class Err
  inherit Result
  feature msg: String
  create make(m: String) do msg := m end
end
")

(defn- parse-and-run [src]
  (interp/interpret-and-get-output (p/ast src)))

(defn- typecheck [src]
  (tc/type-check (p/ast src)))

(deftest sealed-class-parsed
  (testing "sealed deferred class produces :sealed? true in AST"
    (let [ast (p/ast result-hierarchy)
          result-class (first (:classes ast))]
      (is (= "Result" (:name result-class)))
      (is (:sealed? result-class))
      (is (:deferred? result-class)))))

(deftest match-ok-branch
  (testing "match dispatches to Ok clause when value is Ok"
    (let [src (str result-hierarchy "
let r: Result := create Ok.make(42)
match r of
  when Ok as ok then
    print(ok.value)
  when Err as err then
    print(err.msg)
end
")]
      (is (= ["42"] (parse-and-run src))))))

(deftest match-err-branch
  (testing "match dispatches to Err clause when value is Err"
    (let [src (str result-hierarchy "
let r: Result := create Err.make(\"oops\")
match r of
  when Ok as ok then
    print(ok.value)
  when Err as err then
    print(err.msg)
end
")]
      (is (= ["\"oops\""] (parse-and-run src))))))

(deftest match-else-branch
  (testing "else branch runs when no when clause matches"
    (let [src (str result-hierarchy "
let r: Result := create Err.make(\"nope\")
match r of
  when Ok as ok then
    print(ok.value)
  else
    print(\"fallback\")
end
")]
      (is (= ["\"fallback\""] (parse-and-run src))))))

(deftest match-clause-var-scoped
  (testing "clause variable is accessible in clause body"
    (let [src (str result-hierarchy "
let r: Result := create Ok.make(7)
match r of
  when Ok as ok then
    let doubled: Integer := ok.value + ok.value
    print(doubled)
  when Err as err then
    print(err.msg)
end
")]
      (is (= ["14"] (parse-and-run src))))))

(deftest match-nil-return-from-clause
  (testing "match works when clause body returns nil (e.g. print returns nil)"
    (let [src (str result-hierarchy "
let r: Result := create Ok.make(1)
match r of
  when Ok as ok then
    print(ok.value)
  when Err as err then
    print(err.msg)
end
")]
      (is (= ["1"] (parse-and-run src))))))

(deftest typecheck-match-success
  (testing "typecheck passes when all sealed variants are covered"
    (let [src (str result-hierarchy "
let r: Result := create Ok.make(1)
match r of
  when Ok as ok then
    print(ok.value)
  when Err as err then
    print(err.msg)
end
")
          result (typecheck src)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest typecheck-match-exhaustiveness-failure
  (testing "typecheck rejects match on sealed type missing a variant"
    (let [src (str result-hierarchy "
let r: Result := create Ok.make(1)
match r of
  when Ok as ok then
    print(ok.value)
end
")
          result (typecheck src)]
      (is (not (:success result)))
      (is (some #(re-find #"Err" (:message %)) (:errors result))))))

(deftest typecheck-match-else-skips-exhaustiveness
  (testing "typecheck passes when else branch is present even without full coverage"
    (let [src (str result-hierarchy "
let r: Result := create Ok.make(1)
match r of
  when Ok as ok then
    print(ok.value)
  else
    print(\"other\")
end
")
          result (typecheck src)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest typecheck-match-wrong-variant-type
  (testing "typecheck rejects clause with type unrelated to matched expression"
    (let [src (str result-hierarchy "
class Unrelated
end

let r: Result := create Ok.make(1)
match r of
  when Unrelated as u then
    print(\"bad\")
  when Ok as ok then
    print(ok.value)
  when Err as err then
    print(err.msg)
end
")
          result (typecheck src)]
      (is (not (:success result))))))

(deftest sealed-class-must-be-deferred
  (testing "a sealed class declared without 'deferred' is rejected"
    (let [src "
sealed class Shape
  feature area(): Integer do result := 0 end
end

class Circle
  inherit Shape
  feature r: Integer
  create make(v: Integer) do r := v end
end
"
          result (typecheck src)]
      (is (not (:success result)))
      (is (some #(re-find #"must be declared 'sealed deferred'"
                          (:message %))
                (:errors result)))))
  (testing "a sealed deferred class is accepted"
    (let [result (typecheck result-hierarchy)]
      (is (:success result)))))
