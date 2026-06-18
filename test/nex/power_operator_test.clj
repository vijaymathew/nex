(ns nex.power-operator-test
  "Regression tests for the integral `^` (exponentiation) operator.

   Phase 1 of the numeric-tower work (docs/md/NUMERIC_TOWER.md): `nex-int-pow`
   used to square its base on every iteration, including the final one where the
   square is discarded. On the JVM that premature square overflowed `long` and
   raised even when the answer fit comfortably in `Int64` — e.g. 2^40 raised
   although 2^40 is far below `Long/MAX_VALUE`. The fix squares only when another
   iteration will consume the value, leaving the running product as the sole
   source of genuine overflow."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.types.runtime :as rt]))

(defn- execute-method-output [code]
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

(deftest int-pow-does-not-overflow-when-result-fits
  (testing "powers whose result fits in Int64 no longer raise spuriously"
    ;; The exact cases the bug regressed on: results that fit, but whose
    ;; next-power-of-base square would overflow long.
    (is (= 1099511627776 (rt/nex-int-pow 2 40)) "2^40 (the headline case)")
    (is (= 4611686018427387904 (rt/nex-int-pow 2 62)) "2^62, just under 2^63")
    (is (= 1000000000000000000 (rt/nex-int-pow 10 18)) "largest power of ten in Int64")
    (is (= 4294967296 (rt/nex-int-pow 2 32)))
    (is (= 1024 (rt/nex-int-pow 2 10)))))

(deftest int-pow-edge-cases
  (testing "identities and small exponents"
    (is (= 1 (rt/nex-int-pow 2 0)))
    (is (= 1 (rt/nex-int-pow 0 0)))
    (is (= 0 (rt/nex-int-pow 0 5)))
    (is (= 1 (rt/nex-int-pow 1 1000)))
    (is (= 5 (rt/nex-int-pow 5 1)))
    (is (= -8 (rt/nex-int-pow -2 3)) "negative base, odd exponent")
    (is (= 16 (rt/nex-int-pow -2 4)) "negative base, even exponent")))

(deftest int-pow-genuine-overflow-still-raises
  (testing "a result that truly exceeds Int64 still raises (overflow policy unified with *)"
    (is (thrown? Exception (rt/nex-int-pow 2 63)) "2^63 overflows signed Int64")
    (is (thrown? Exception (rt/nex-int-pow 2 64)))
    (is (thrown? Exception (rt/nex-int-pow 10 19)))))

(deftest int-pow-rejects-negative-exponent
  (testing "integral exponentiation requires a non-negative exponent"
    (is (thrown-with-msg? Exception #"non-negative exponent"
                          (rt/nex-int-pow 2 -1)))))

(deftest power-operator-end-to-end
  (testing "the `^` operator evaluates through the interpreter without spurious overflow"
    (is (= ["1099511627776"]
           (execute-method-output "class Test
  feature
    demo() do
      print(2 ^ 40)
    end
end")))
    (is (= ["1000000000000000000"]
           (execute-method-output "class Test
  feature
    demo() do
      let a: Integer := 10 ^ 18
      print(a)
    end
end")))))
