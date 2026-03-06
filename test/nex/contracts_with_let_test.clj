(ns nex.contracts-with-let-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(defn- invoke-first-method
  [code method arg-values]
  (let [ast (p/ast code)
        class-def (first (:classes ast))
        class-name (:name class-def)
        ctx (interp/make-context)
        _ (interp/register-class ctx class-def)
        obj (interp/make-object class-name {})
        env (interp/make-env (:globals ctx))
        _ (interp/env-define env "obj" obj)
        ctx' (assoc ctx :current-env env)
        arg-nodes (mapv (fn [v] {:type :integer :value v}) arg-values)]
    (interp/eval-node ctx' {:type :call
                            :target "obj"
                            :method method
                            :args arg-nodes})
    @(:output ctx')))

(deftest postcondition-can-reference-let-local-test
  (testing "Postconditions can reference locals introduced with let"
    (let [code "class Math
  feature
    compute_area(width: Integer, height: Integer)
      require
        positive_width: width > 0
        positive_height: height > 0
      do
        let area := width * height
        print(area)
      ensure
        area_positive: area > 0
        area_correct: area = width * height
      end
end"]
      (is (= ["200"] (invoke-first-method code "compute_area" [10 20]))))))

(deftest postcondition-detects-buggy-let-computation-test
  (testing "Postcondition catches wrong local computation"
    (let [code "class BuggyMath
  feature
    buggy_sum(a: Integer, b: Integer)
      require
        positive: a > 0 and b > 0
      do
        let sum := a + b + 1
      ensure
        sum_correct: sum = a + b
      end
end"]
      (is (thrown-with-msg?
            Exception
            #"Postcondition violation: sum_correct"
            (invoke-first-method code "buggy_sum" [5 10]))))))
