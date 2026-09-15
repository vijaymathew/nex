(ns nex.paren-continuation-glue-test
  "Regression tests for the postfixPart* continuation gotcha: nex's grammar
   has no newline-based statement separation, so a bare `(` immediately
   after a call statement -- even across a line break -- used to be
   swallowed as a continuation of that statement (`print(a)` then
   `(create Foo).bar(a)` on the next line parsed as one phantom-application
   expression instead of two statements). Since clj-antlr's interpreted
   parser mode never evaluates grammar-level semantic predicates (verified:
   ParserInterpreter falls back to Recognizer's default `sempred`, which
   always returns true), the fix lives in the walker, not the grammar --
   see phantom-call-glue-point?/split-glued-statement in walker.clj."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(defn- run
  "Parse and interpret SRC, returning its printed output as strings."
  [src]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast src))
    (mapv str @(:output ctx))))

(deftest glued-statement-splits-across-newline-test
  (testing "a call statement followed by `(...)` on the next line is two statements"
    (let [stmts (:statements (p/ast "print(a)\n(b).c(d)"))]
      (is (= 2 (count stmts)) "must split into print(a) and (b).c(d)")
      (is (= :call (:type (first stmts))))
      (is (= "print" (:method (first stmts))))
      (let [second-stmt (second stmts)]
        (is (= :call (:type second-stmt)))
        (is (= "c" (:method second-stmt)))
        (is (= "b" (:target second-stmt)) "the swallowed (b) becomes .c's receiver")
        (is (= 1 (count (:args second-stmt))))
        ;; No handle-postfix bookkeeping should leak into the final AST.
        (is (not (contains? second-stmt :glue-target-line)))
        (is (not (contains? second-stmt :glue-paren-line)))))))

(deftest glued-statement-not-split-same-line-test
  (testing "the same shape all on one line is a genuine curried application, not a glue point"
    (let [stmts (:statements (p/ast "print(a)(b).c(d)"))]
      (is (= 1 (count stmts)) "must stay one expression")
      (is (= :call (:type (first stmts))))
      (is (= "c" (:method (first stmts))))
      (let [phantom (:target (first stmts))]
        (is (= :call (:type phantom)))
        (is (nil? (:method phantom)) "phantom application: calling print(a)'s result")))))

(deftest paren-attached-argument-wraps-not-split-test
  (testing "`(` right after the call, only its argument wrapping to the next line, is not a glue point"
    ;; Regression: an earlier version of this fix approximated the paren's
    ;; position from the argument's own line instead of the call-suffix's
    ;; own line, and wrongly split this case.
    (let [stmts (:statements (p/ast "print(a)(\nb).c(d)"))]
      (is (= 1 (count stmts)) "must stay one expression: the '(' itself never moved to a new line")
      (is (= :call (:type (first stmts))))
      (is (= "c" (:method (first stmts))))
      (let [phantom (:target (first stmts))]
        (is (= :call (:type phantom)))
        (is (nil? (:method phantom)))))))

(deftest three-way-glued-statements-split-test
  (testing "a run of 3 glued lines splits into 3 statements"
    (let [stmts (:statements (p/ast "print(a)\n(b)\n(c)"))]
      (is (= 3 (count stmts)))
      (is (= "print" (:method (first stmts))))
      ;; A split-off fragment with nothing further chained onto it must get
      ;; the same paren-less-call restoration an ordinary bare-identifier
      ;; statement gets (handle-statement's statement-position-node) --
      ;; regression: this used to leak out as a bare :identifier node that
      ;; nothing downstream knows how to execute as a statement.
      (is (= :call (:type (second stmts))))
      (is (= "b" (:method (second stmts))))
      (is (false? (:has-parens (second stmts))))
      (is (= :call (:type (nth stmts 2))))
      (is (= "c" (:method (nth stmts 2))))
      (is (false? (:has-parens (nth stmts 2)))))))

(deftest same-line-curried-call-not-split-test
  (testing "a genuine curried call `f(a)(b)` on one line stays one expression"
    (let [stmts (:statements (p/ast "f(a)(b)"))]
      (is (= 1 (count stmts)))
      (is (= :call (:type (first stmts))))
      (is (nil? (:method (first stmts))))
      (is (= "f" (:method (:target (first stmts))))))))

(deftest member-chain-dot-then-newline-test
  (testing "`.` at the end of a line, method name on the next, is one statement"
    (let [stmts (:statements (p/ast "a.m(1).\nn(2)"))]
      (is (= 1 (count stmts)) "member access is unambiguous regardless of the newline")
      (is (= :call (:type (first stmts))))
      (is (= "n" (:method (first stmts))))
      (let [inner (:target (first stmts))]
        (is (= :call (:type inner)))
        (is (= "m" (:method inner)))
        (is (= "a" (:target inner)))))))

(deftest member-chain-newline-then-dot-test
  (testing "a newline before a leading `.` is one statement"
    (let [stmts (:statements (p/ast "a.m(1)\n.n(2)"))]
      (is (= 1 (count stmts)))
      (is (= :call (:type (first stmts))))
      (is (= "n" (:method (first stmts))))
      (let [inner (:target (first stmts))]
        (is (= :call (:type inner)))
        (is (= "m" (:method inner)))
        (is (= "a" (:target inner)))))))

(deftest glued-statement-splits-inside-nested-block-test
  (testing "the same split happens inside an `if` body, not just at the top level"
    (let [stmts (:statements (p/ast "if true then\nprint(a)\n(b).c(d)\nend"))
          then-body (:then (first stmts))]
      (is (= 2 (count then-body))))))

(deftest glued-statement-executes-both-halves-test
  (testing "both split statements actually run, in order, with the right receiver"
    (is (= ["1" "2" "3"]
           (run "class Foo
feature
  bar(n: Integer)
  do
    print(n)
  end
end

print(1)
(create Foo).bar(2)
print(3)")))))

(deftest same-line-curried-call-evaluates-test
  (testing "a genuine curried call still evaluates as one application, not split"
    (is (= ["8"]
           (run "declare function make_adder(n: Integer): Function
function make_adder(n: Integer): Function
do
  result := fn(x: Integer): Integer do result := x + n end
end
print(make_adder(5)(3))")))))

(deftest glued-statement-splits-and-executes-inside-nested-block-test
  (testing "a glued statement inside a function's if-body splits and runs correctly"
    (is (= ["1" "2" "3"]
           (run "class Foo
feature
  bar(n: Integer)
  do
    print(n)
  end
end

function run()
do
  if true then
    print(1)
    (create Foo).bar(2)
    print(3)
  end
end

run()")))))
