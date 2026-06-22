(ns nex.forward-declaration-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]
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

(deftest duplicate-free-function-definitions-rejected
  ;; Definition §4.8: free-function names must be unique within a program. The
  ;; walker collapses duplicates last-wins, so without an explicit check the
  ;; earlier definition would silently vanish. Both front-ends must diagnose it.
  (testing "the type checker rejects a name defined twice"
    (let [result (tc/type-check
                  (p/ast "function f(): Integer do result := 1 end
function f(): Integer do result := 2 end
print(f())"))]
      (is (not (:success result)))
      (is (some #(str/includes? (tc/format-type-error %) "defined more than once")
                (:errors result)))))
  (testing "the interpreter (authoritative account) rejects it directly, not just last-wins"
    (let [ast (p/ast "function f(): Integer do result := 1 end
function f(): Integer do result := 2 end
print(f())")
          ex (try (interp/eval-node (interp/make-context) ast) nil
                  (catch Exception e e))]
      (is (some? ex) "duplicate definition must raise rather than silently succeed")
      (is (str/includes? (interp/nex-error-message ex) "defined more than once"))))
  (testing "a forward declaration paired with one definition is not a duplicate"
    (let [ast (p/ast "declare function f(): Integer
function f(): Integer do result := 5 end
print(f())")
          ctx (interp/make-context)]
      (interp/eval-node ctx ast)
      (is (= ["5"] @(:output ctx)))))
  (testing "distinct function names are accepted"
    (let [ast (p/ast "function f(): Integer do result := 1 end
function g(): Integer do result := 2 end
print(f() + g())")
          ctx (interp/make-context)]
      (interp/eval-node ctx ast)
      (is (= ["3"] @(:output ctx))))))

(deftest definition-must-match-forward-declaration
  ;; SYNTAX: "The later definition must match the earlier declaration exactly."
  ;; The declaration is collapsed away before evaluation, so both the type
  ;; checker and the authoritative interpreter must reject a signature mismatch.
  (let [reject (fn [code]
                 (let [ast (p/ast code)
                       tcr (tc/type-check ast)
                       ie (try (interp/eval-node (interp/make-context) ast) nil
                               (catch Exception e e))]
                   [tcr ie]))]
    (testing "an arity mismatch between declaration and definition is rejected"
      (let [[tcr ie] (reject "declare function f(x: Integer): Real
function f(): Real do result := 3.14 end
print(f())")]
        (is (not (:success tcr)))
        (is (some #(str/includes? (tc/format-type-error %) "must match the earlier declaration")
                  (:errors tcr)))
        (is (some? ie) "interpreter must raise rather than silently run the definition")
        (is (str/includes? (interp/nex-error-message ie) "must match the earlier declaration"))))
    (testing "a parameter-type mismatch is rejected"
      (let [[tcr ie] (reject "declare function f(x: Integer): Real
function f(x: String): Real do result := 3.14 end
print(f(\"a\"))")]
        (is (not (:success tcr)))
        (is (some? ie))))
    (testing "a return-type mismatch is rejected"
      (let [[tcr ie] (reject "declare function f(x: Integer): Real
function f(x: Integer): Integer do result := 3 end
print(f(1))")]
        (is (not (:success tcr)))
        (is (some? ie)))))
  (testing "a definition whose signature matches the declaration is accepted"
    (let [ast (p/ast "declare function f(x: Integer): Real
function f(x: Integer): Real do result := 3.14 end
print(f(2))")
          ctx (interp/make-context)]
      (is (:success (tc/type-check ast)))
      (interp/eval-node ctx ast)
      (is (= ["3.14"] @(:output ctx)))))
  (testing "parameter names need not match — only types, return, and generics form the signature"
    (let [ast (p/ast "declare function f(x: Integer): Real
function f(n: Integer): Real do result := 1.0 end
print(f(2))")]
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

(deftest repl-input-completeness-continues-on-dangling-operator
  (testing "a line ending on a binary operator keeps reading for its right-hand side"
    (is (true? (repl/continue-reading? ["let double: Function(n: Integer): Integer :="])))
    (is (true? (repl/continue-reading? ["let x := 1 +"])))
    (is (true? (repl/continue-reading? ["let y := a and"])))
    (is (true? (repl/continue-reading? ["foo,"]))))
  (testing "the binding completes once the right-hand side arrives"
    (is (false? (repl/continue-reading?
                 ["let double: Function(n: Integer): Integer :="
                  "  fn (n: Integer): Integer do result := n * 2 end"]))))
  (testing "operators inside string and char literals are not mistaken for dangling ones"
    (is (false? (repl/continue-reading? ["let s := \"ends with :=\""])))
    (is (false? (repl/continue-reading? ["let msg := \"hi\""])))
    (is (false? (repl/continue-reading? ["let c := #newline"])))
    (is (false? (repl/continue-reading? ["let z := 5"])))
    (is (false? (repl/continue-reading? ["a - b"])))))

(deftest repl-input-completeness-balances-when-expressions
  (testing "multi-line when expressions keep reading until else/end complete the expression"
    (let [lines ["when convert expenses.get(\"children\") to children: Array[Map[String, Any]] then"
                 "  total_amount(children.get(0))"
                 "else"
                 "  0"
                 "end"]]
      (is (true? (repl/continue-reading? (subvec lines 0 1))))
      (is (true? (repl/continue-reading? (subvec lines 0 2))))
      (is (true? (repl/continue-reading? (subvec lines 0 3))))
      (is (true? (repl/continue-reading? (subvec lines 0 4))))
      (is (false? (repl/continue-reading? lines)))))
  (testing "select clauses using when ... then do not add an extra unclosed when"
    (is (true? (repl/continue-reading? ["select"
                                        "  when ch.receive as value then"
                                        "    print(value)"])))
    (is (false? (repl/continue-reading? ["select"
                                         "  when ch.receive as value then"
                                         "    print(value)"
                                         "end"])))))

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
