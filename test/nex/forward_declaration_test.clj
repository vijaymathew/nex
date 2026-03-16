(ns nex.forward-declaration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.repl :as repl]
            [nex.typechecker :as tc]))

(deftest declaration-only-functions-parse-and-typecheck
  (testing "body-less function declarations support mutually recursive definitions in the same input"
    (let [code "function is_even(n: Integer): Boolean
function is_odd(n: Integer): Boolean

function is_even(n: Integer): Boolean
do
  if n = 0 then
    result := true
  else
    result := is_odd(n - 1)
  end
end

function is_odd(n: Integer): Boolean
do
  if n = 0 then
    result := false
  else
    result := is_even(n - 1)
  end
end"
          ast (p/ast code)
          functions (:functions ast)]
      (is (= 2 (count functions)))
      (is (= #{"is_even" "is_odd"} (set (map :name functions))))
      (is (:success (tc/type-check ast))))))

(deftest repl-forward-declarations-support-mutual-recursion
  (testing "REPL can typecheck and execute mutually recursive functions once signatures are declared"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx "function is_even(n: Integer): Boolean")
                     (repl/eval-code ctx "function is_odd(n: Integer): Boolean")
                     (repl/eval-code ctx "function is_even(n: Integer): Boolean
do
  if n = 0 then
    result := true
  else
    result := is_odd(n - 1)
  end
end")
                     (repl/eval-code ctx "function is_odd(n: Integer): Boolean
do
  if n = 0 then
    result := false
  else
    result := is_even(n - 1)
  end
end")
                     (repl/eval-code ctx "print(is_even(4))")
                     (repl/eval-code ctx "print(is_odd(5))"))]
        (is (not (str/includes? output "Type checking failed")))
        (is (not (str/includes? output "Cannot assign Void to variable of type Boolean")))
        (is (str/includes? output "true"))
        (is (= 2 (count (re-seq #"true" output))))))))

(deftest calling-unresolved-declaration-fails-cleanly
  (testing "calling a declaration without a later definition raises a clear runtime error"
    (let [ctx (repl/init-repl-context)]
      (repl/eval-code ctx "function missing(n: Integer): Boolean")
      (let [output (with-out-str
                     (repl/eval-code ctx "print(missing(1))"))]
        (is (str/includes? output "declared but not defined"))))))
