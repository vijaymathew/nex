(ns nex.multiple-errors-test
  "A type check reports up to :max-errors errors (default 10), not just the
   first. It recovers at routine and top-level-statement boundaries: each
   method, constructor, function and top-level statement is checked even when
   an earlier one failed, but only a routine's first error is reported, since
   what follows it may hinge on what failed. A top-level `let` that fails still
   defines its name, so its later uses are not reported as undefined."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- check
  ([code] (check code {}))
  ([code opts] (tc/type-check (p/ast code) opts)))

(defn- messages [result]
  (mapv tc/format-type-error (:errors result)))

(def ^:private three-routines
  "class A
feature
  f(): Integer do result := \"x\" end
  g(): Integer do result := undefined_one end
end

function h(): Integer do result := true end
")

(deftest errors-in-separate-routines-are-all-reported
  (let [msgs (messages (check three-routines))]
    (is (= 3 (count msgs)) (pr-str msgs))
    (is (str/includes? (nth msgs 0) "line 3"))
    (is (str/includes? (nth msgs 0) "Cannot assign String to variable 'result'"))
    (is (str/includes? (nth msgs 1) "line 4"))
    (is (str/includes? (nth msgs 1) "Undefined variable: undefined_one"))
    (is (str/includes? (nth msgs 2) "line 7"))
    (is (str/includes? (nth msgs 2) "Cannot assign Boolean to variable 'result'"))))

(deftest only-the-first-error-of-a-routine-is-reported
  (let [msgs (messages (check "function f(): Integer do
  result := \"x\"
  result := true
end
"))]
    (is (= 1 (count msgs)) (pr-str msgs))
    (is (str/includes? (first msgs) "line 2"))))

(deftest top-level-statements-recover-without-cascading
  (testing "each failing top-level statement is reported"
    (let [msgs (messages (check "let y: Integer := \"s\"
print(nope)
let z: Boolean := 3
"))]
      (is (= 3 (count msgs)) (pr-str msgs))))
  (testing "a failed let still defines its name"
    (let [msgs (messages (check "let x: Integer := \"s\"
print(x)
"))]
      (is (= 1 (count msgs)) (pr-str msgs))
      (is (not-any? #(str/includes? % "Undefined variable: x") msgs)))))

(deftest errors-are-capped
  (let [code (str/join "\n" (for [i (range 15)]
                              (str "function f" i "(): Integer do result := \"x\" end")))]
    (testing "at 10 by default"
      (let [result (check code)]
        (is (= 10 (count (:errors result))))
        (is (= 5 (:omitted-errors result)))
        (is (= "... and 5 more errors (use --max-errors to see more)"
               (last (tc/format-type-errors result))))))
    (testing "at :max-errors when given"
      (let [result (check code {:max-errors 3})]
        (is (= 3 (count (:errors result))))
        (is (= 12 (:omitted-errors result)))))
    (testing "and nothing is said about omitted errors when there are none"
      (let [result (check code {:max-errors 20})]
        (is (= 15 (count (:errors result))))
        (is (= 15 (count (tc/format-type-errors result))))))))

(deftest an-error-inside-an-anonymous-function-belongs-to-its-statement
  (let [msgs (messages (check "let f := fn(n: Integer): Integer do result := \"x\" end
let g: Integer := \"y\"
"))]
    (is (= 2 (count msgs)) (pr-str msgs))
    (is (str/includes? (first msgs) "line 1"))
    (is (str/includes? (second msgs) "line 2"))))

(deftest every-shadowing-let-is-reported
  (let [f (java.io.File/createTempFile "multiple_errors" ".nex")]
    (try
      (spit f "class Counter
feature
  count: Integer
  total: Integer

  bump do
    let count := 1
    print(count)
  end

  reset do
    let total := 0
    print(total)
  end
end
")
      (let [out (with-out-str (e/run-file (.getPath f) {}))]
        (is (str/includes? out "Line 7: Local variable 'count'"))
        (is (str/includes? out "Line 12: Local variable 'total'")))
      (finally (.delete f)))))

(deftest run-file-reports-several-errors-and-honours-max-errors
  (let [f (java.io.File/createTempFile "multiple_errors" ".nex")]
    (try
      (spit f three-routines)
      (let [out (with-out-str (is (= 1 (e/run-file (.getPath f) {}))))]
        (is (str/includes? out "line 3"))
        (is (str/includes? out "line 4"))
        (is (str/includes? out "line 7")))
      (let [out (with-out-str (e/run-file (.getPath f) {:max-errors 1}))]
        (is (str/includes? out "line 3"))
        (is (not (str/includes? out "line 4")))
        (is (str/includes? out "... and 2 more errors")))
      (finally (.delete f)))))
