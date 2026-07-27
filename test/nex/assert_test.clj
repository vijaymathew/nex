(ns nex.assert-test
  "The `assert` statement: a mid-body contract check.

   `require` / `ensure` guard a routine's boundaries and `invariant` guards a
   loop, so these tests cover what only `assert` can say — that something holds
   at one point inside a body — on both the interpreter and the compiled path."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- interpret-output
  [code]
  (interp/interpret-and-get-output (p/ast code)))

(defn- compiled-output
  [code]
  ;; The compiled cell is invoked reflectively, so a contract violation arrives
  ;; wrapped in an InvocationTargetException. Unwrap it to compare the two
  ;; backends on the same footing.
  (let [result (try
                 (compiled-repl/compile-and-eval! (compiled-repl/make-session)
                                                  (p/ast code))
                 (catch java.lang.reflect.InvocationTargetException e
                   (throw (or (.getCause e) e))))]
    (is (:compiled? result) "expected the compiled backend to accept this program")
    (:output result)))

(defn- type-errors
  [code]
  (map :message (:errors (tc/type-check (p/ast code)))))

(deftest assert-parses-in-all-three-forms-test
  (testing "labelled, bare, and multi-assertion forms all reach the walker"
    (let [ast (p/ast (str "assert positive: 1 > 0\n"
                          "assert 2 > 1\n"
                          "assert\n"
                          "  a: 3 > 2\n"
                          "  b: 4 > 3\n"))
          stmts (filter #(= :assert (:type %)) (:statements ast))]
      (is (= 3 (count stmts)))
      (is (= [{:label "positive"}] (map #(select-keys % [:label])
                                        (:assertions (first stmts))))
          "a labelled assertion keeps its name")
      (is (= [nil] (map :label (:assertions (second stmts))))
          "a bare assertion has no label, so backends fall back to the line")
      (is (= ["a" "b"] (map :label (:assertions (nth stmts 2))))
          "one `assert` may carry several labelled assertions"))))

(deftest assert-passes-silently-test
  (let [code (str "let x: Integer := 5\n"
                  "assert positive: x > 0\n"
                  "assert x < 10\n"
                  "assert\n"
                  "  still_positive: x > 0\n"
                  "  small: x < 10\n"
                  "print(1)\n")]
    (testing "a satisfied assert produces no output and does not interrupt the body"
      (is (= ["1"] (interpret-output code)))
      (is (= ["1"] (compiled-output code))))))

(deftest labelled-assert-failure-reports-its-label-test
  (let [code (str "let x: Integer := 5\n"
                  "assert too_big: x > 100\n")]
    (testing "interpreter"
      (is (thrown-with-msg? Exception #"Assertion violation: too_big"
                            (interpret-output code))))
    (testing "compiled"
      (is (thrown-with-msg? Exception #"Assertion violation: too_big"
                            (compiled-output code))))))

(deftest bare-assert-failure-reports-its-line-test
  ;; The bare form has no name to report, so the line stands in for one. Both
  ;; backends must word this identically or the same program fails differently
  ;; depending on how it was run.
  (let [code (str "let x: Integer := 5\n"
                  "print(0)\n"
                  "assert x > 100\n")]
    (testing "interpreter"
      (is (thrown-with-msg? Exception #"Assertion violation \(line 3\)"
                            (interpret-output code))))
    (testing "compiled"
      (is (thrown-with-msg? Exception #"Assertion violation \(line 3\)"
                            (compiled-output code))))))

(deftest multi-assertion-assert-fails-on-the-first-false-one-test
  (let [code (str "let x: Integer := 5\n"
                  "assert\n"
                  "  fine: x > 0\n"
                  "  broken: x > 100\n"
                  "  also_broken: x > 1000\n")]
    (testing "interpreter"
      (is (thrown-with-msg? Exception #"Assertion violation: broken"
                            (interpret-output code))))
    (testing "compiled"
      (is (thrown-with-msg? Exception #"Assertion violation: broken"
                            (compiled-output code))))))

(deftest assert-inside-a-method-test
  (let [code (str "class Counter\n"
                  "  feature\n"
                  "    n: Integer\n"
                  "    bump(by: Integer) do\n"
                  "      assert positive_step: by > 0\n"
                  "      n := n + by\n"
                  "    end\n"
                  "end\n"
                  "let k: Counter := create Counter\n"
                  "k.bump(3)\n"
                  "print(k.n)\n")]
    (testing "a passing assert in a method body"
      (is (= ["3"] (interpret-output code)))
      (is (= ["3"] (compiled-output code))))
    (testing "a failing assert in a method body"
      (let [bad (str code "k.bump(-1)\n")]
        (is (thrown-with-msg? Exception #"Assertion violation: positive_step"
                              (interpret-output bad)))
        (is (thrown-with-msg? Exception #"Assertion violation: positive_step"
                              (compiled-output bad)))))))

(deftest assert-inside-a-loop-body-test
  (let [code (str "from\n"
                  "  let i: Integer := 0\n"
                  "until\n"
                  "  i = 3\n"
                  "do\n"
                  "  assert in_range: i >= 0 and i < 3\n"
                  "  print(i)\n"
                  "  i := i + 1\n"
                  "end\n")]
    (is (= ["0" "1" "2"] (interpret-output code)))
    (is (= ["0" "1" "2"] (compiled-output code)))))

(deftest assert-condition-must-be-boolean-test
  (testing "a non-Boolean condition is a compile-time error, labelled or not"
    (is (= ["assert condition must be Boolean, got Integer"]
           (type-errors "let x: Integer := 5\nassert bad: x + 1\n")))
    (is (= ["assert condition must be Boolean, got String"]
           (type-errors "assert \"nope\"\n")))
    (is (empty? (type-errors "let x: Integer := 5\nassert fine: x > 0\nassert x > 0\n")))))

(deftest assert-does-not-swallow-following-statements-test
  ;; `assert` takes exactly one bare expression; it must not absorb the
  ;; statement after it.
  (let [code (str "let x: Integer := 5\n"
                  "assert x > 0\n"
                  "print(2)\n")]
    (is (= ["2"] (interpret-output code)))
    (is (= ["2"] (compiled-output code)))))
