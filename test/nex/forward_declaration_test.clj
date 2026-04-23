(ns nex.forward-declaration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.repl :as repl]
            [nex.typechecker :as tc]))

(deftest declaration-only-functions-parse-and-typecheck
  (testing "declare function supports mutually recursive definitions in the same input"
    (let [code "declare function is_even(n: Integer): Boolean
declare function is_odd(n: Integer): Boolean

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

(deftest plain-function-signature-without-body-is-not-a-declaration
  (testing "function definitions require a body; use declare function for forward declarations"
    (is (thrown? Exception
                 (p/ast "function is_even(n: Integer): Boolean")))))

(deftest repl-input-completeness-distinguishes-declare-from-function-definition
  (testing "a plain function header keeps reading, while declare function is complete"
    (is (true? (repl/continue-reading? ["function f(n: Integer): Integer"])))
    (is (false? (repl/continue-reading? ["declare function f(n: Integer): Integer"])))
    (is (false? (repl/continue-reading? ["function f(n: Integer): Integer do result := n end"])))))

(deftest repl-input-completeness-balances-delimited-literals
  (testing "multi-line array and map literals keep the REPL in continuation mode until delimiters close"
    (let [lines ["let books: Array[Map[String, Any]] := ["
                 "  {\"title\": \"Dune\", \"author\": \"Frank Herbert\", \"year\": 1965},"
                 "  {\"title\": \"Neuromancer\", \"author\": \"William Gibson\", \"year\": 1984},"
                 "  {\"title\": \"Foundation\", \"author\": \"Isaac Asimov\", \"year\": 1951}]"]]
      (is (true? (repl/continue-reading? (subvec lines 0 1))))
      (is (true? (repl/continue-reading? (subvec lines 0 2))))
      (is (true? (repl/continue-reading? (subvec lines 0 3))))
      (is (false? (repl/continue-reading? lines)))))
  (testing "delimiters inside strings and character literals do not force continuation"
    (is (false? (repl/continue-reading? ["let s := \"[not open\""])))
    (is (false? (repl/continue-reading? ["let ch: Char := #]"]))))
  (testing "multi-line calls also continue until the closing parenthesis"
    (is (true? (repl/continue-reading? ["print("])))
    (is (false? (repl/continue-reading? ["print(" "  42)"])))))

(deftest repl-read-input-collects-multi-line-array-literal
  (testing "read-input returns a complete multi-line literal as one REPL cell"
    (let [inputs (atom ["let books: Array[Map[String, Any]] := ["
                        "  {\"title\": \"Dune\", \"author\": \"Frank Herbert\", \"year\": 1965},"
                        "  {\"title\": \"Neuromancer\", \"author\": \"William Gibson\", \"year\": 1984},"
                        "  {\"title\": \"Foundation\", \"author\": \"Isaac Asimov\", \"year\": 1951}]"])]
      (with-redefs [repl/read-line-safe (fn [_prompt]
                                          (let [line (first @inputs)]
                                            (swap! inputs subvec 1)
                                            line))]
        (is (= (str/join "\n" ["let books: Array[Map[String, Any]] := ["
                               "  {\"title\": \"Dune\", \"author\": \"Frank Herbert\", \"year\": 1965},"
                               "  {\"title\": \"Neuromancer\", \"author\": \"William Gibson\", \"year\": 1984},"
                               "  {\"title\": \"Foundation\", \"author\": \"Isaac Asimov\", \"year\": 1951}]"])
               (repl/read-input)))
        (is (empty? @inputs))))))

(deftest repl-forward-declarations-support-mutual-recursion
  (testing "REPL can typecheck and execute mutually recursive functions once signatures are declared"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx "declare function is_even(n: Integer): Boolean")
                     (repl/eval-code ctx "declare function is_odd(n: Integer): Boolean")
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
                     (repl/eval-code ctx "is_even(4)")
                     (repl/eval-code ctx "is_odd(5)"))]
        (is (not (str/includes? output "Type checking failed")))
        (is (not (str/includes? output "Cannot assign Void to variable of type Boolean")))
        (is (str/includes? output "true"))
        (is (= 2 (count (re-seq #"true" output))))))))

(deftest repl-rejects-forward-reference-without-declaration
  (testing "REPL typechecker rejects a function body that calls an undeclared later function"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})]
      (let [ctx (repl/init-repl-context)
            output (with-out-str
                     (repl/eval-code ctx "function greet_user(name: String): String
do
  result := \"Hello, \" + normalize_name(name)
end"))]
        (is (str/includes? output "Undefined function or method: normalize_name"))
        (is (str/includes? output "Type checking failed"))))))

(deftest calling-unresolved-declaration-fails-cleanly
  (testing "calling a declaration without a later definition raises a clear runtime error"
    (let [ctx (repl/init-repl-context)]
      (repl/eval-code ctx "declare function missing(n: Integer): Boolean")
      (let [output (with-out-str
                     (repl/eval-code ctx "print(missing(1))"))]
        (is (str/includes? output "declared but not defined"))))))
