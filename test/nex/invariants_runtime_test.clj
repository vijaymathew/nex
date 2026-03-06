(ns nex.invariants-runtime-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(defn- check-invariants
  [code field-values]
  (let [ast (p/ast code)
        class-def (first (:classes ast))
        ctx (interp/make-context)
        _ (interp/register-class ctx class-def)
        env (interp/make-env (:globals ctx))
        _ (doseq [[k v] field-values]
            (interp/env-define env k v))
        ctx' (assoc ctx :current-env env)]
    (interp/check-class-invariant ctx' class-def)))

(deftest simple-invariant-pass-fail-test
  (testing "Simple class invariant accepts valid values and rejects invalid values"
    (let [code "class Counter
  feature
    value: Integer
  invariant
    non_negative: value >= 0
end"]
      (is (nil? (check-invariants code {"value" 10})))
      (is (nil? (check-invariants code {"value" 0})))
      (is (thrown-with-msg?
            Exception
            #"Class invariant violation: non_negative"
            (check-invariants code {"value" -5}))))))

(deftest multi-invariant-boundary-test
  (testing "Multiple invariants enforce field boundaries"
    (let [code "class Date
  feature
    day: Integer
    hour: Integer
  invariant
    valid_day: day >= 1 and day <= 31
    valid_hour: hour >= 0 and hour <= 23
end"]
      (is (nil? (check-invariants code {"day" 1 "hour" 0})))
      (is (nil? (check-invariants code {"day" 31 "hour" 23})))
      (is (thrown-with-msg?
            Exception
            #"Class invariant violation: valid_day"
            (check-invariants code {"day" 0 "hour" 12})))
      (is (thrown-with-msg?
            Exception
            #"Class invariant violation: valid_hour"
            (check-invariants code {"day" 15 "hour" 25}))))))
