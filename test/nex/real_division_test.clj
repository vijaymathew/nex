(ns nex.real-division-test
  "Phase 2 of the numeric-tower work (docs/md/NUMERIC_TOWER.md): Real division is
   honestly IEEE-754. `x / 0.0` yields +/-Infinity, `0.0 / 0.0` and `x % 0.0`
   yield NaN, and none of these raise. Integer division by zero still raises —
   the documented, well-precedented asymmetry. The IEEE inspection methods
   (is_nan / is_infinite / is_finite) let callers detect the special values.

   Background: clojure.core// on the *boxed* doubles that flow through the
   interpreter routes via Numbers.divide, which raises on a zero divisor; only
   primitive doubles yield IEEE Inf/NaN. The interpreter therefore coerces to
   primitive before dividing, which these tests lock in."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- run-output [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        class-def (first (:classes ast))
        method-def (-> class-def :body first :members first)
        _ (interp/register-class ctx class-def)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt (:body method-def)]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

(defn- demo [body]
  (str "class T\n  feature\n    d() do\n      " body "\n    end\nend"))

(deftest real-division-by-zero-is-ieee
  (testing "Real / 0.0 follows IEEE-754 and does not raise"
    (is (= ["Infinity"]  (run-output (demo "print(1.0 / 0.0)"))))
    (is (= ["-Infinity"] (run-output (demo "print(-1.0 / 0.0)"))))
    (is (= ["NaN"]       (run-output (demo "print(0.0 / 0.0)"))))))

(deftest real-remainder-by-zero-is-nan
  (testing "Real % 0.0 is NaN (not a raise); non-zero remainder is unaffected"
    (is (= ["NaN"] (run-output (demo "print(5.0 % 0.0)"))))
    (is (= ["1.0"] (run-output (demo "print(5.0 % 2.0)"))))))

(deftest divided-by-method-matches-operator
  (testing "the divided_by method is IEEE too (it is typed to return Real)"
    (is (= ["Infinity"] (run-output (demo "let x: Real := 5.0\n      print(x.divided_by(0.0))"))))))

(deftest integer-division-by-zero-still-raises
  (testing "the documented asymmetry: integral / 0 and % 0 raise"
    (is (thrown? Exception (run-output (demo "print(5 / 0)"))))
    (is (thrown? Exception (run-output (demo "print(5 % 0)"))))))

(deftest ieee-inspection-methods
  (testing "is_nan / is_infinite / is_finite report the special values"
    (is (= ["true"]  (run-output (demo "let x: Real := 0.0 / 0.0\n      print(x.is_nan)"))))
    (is (= ["false"] (run-output (demo "let x: Real := 2.5\n      print(x.is_nan)"))))
    (is (= ["true"]  (run-output (demo "let x: Real := 1.0 / 0.0\n      print(x.is_infinite)"))))
    (is (= ["false"] (run-output (demo "let x: Real := 0.0 / 0.0\n      print(x.is_infinite)")))
        "NaN is not infinite")
    (is (= ["true"]  (run-output (demo "let x: Real := 2.5\n      print(x.is_finite)"))))
    (is (= ["false"] (run-output (demo "let x: Real := 1.0 / 0.0\n      print(x.is_finite)"))))
    (is (= ["false"] (run-output (demo "let x: Real := 0.0 / 0.0\n      print(x.is_finite)"))))))

(deftest ieee-inspection-methods-typecheck
  (testing "the inspection methods type-check as Real -> Boolean"
    (let [result (tc/type-check (p/ast (demo (str "let x: Real := 1.0\n"
                                                  "      let a: Boolean := x.is_nan\n"
                                                  "      let b: Boolean := x.is_infinite\n"
                                                  "      let c: Boolean := x.is_finite"))))]
      (is (:success result))
      (is (empty? (:errors result))))))
