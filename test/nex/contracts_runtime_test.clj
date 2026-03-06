(ns nex.contracts-runtime-test
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
        arg-nodes (mapv (fn [v]
                          (if (integer? v)
                            {:type :integer :value v}
                            {:type :real :value v}))
                        arg-values)]
    (interp/eval-node ctx' {:type :call
                            :target "obj"
                            :method method
                            :args arg-nodes})
    @(:output ctx')))

(deftest precondition-pass-and-fail-test
  (testing "Preconditions are enforced at runtime"
    (let [code "class Test
  feature
    set_day(a_day: Integer)
      require
        valid_day: a_day >= 1 and a_day <= 31
      do
        print(a_day)
      end
end"]
      (is (= ["15"] (invoke-first-method code "set_day" [15])))
      (is (thrown-with-msg?
            Exception
            #"Precondition violation: valid_day"
            (invoke-first-method code "set_day" [50]))))))

(deftest postcondition-pass-and-fail-test
  (testing "Postconditions are enforced at runtime"
    (let [ok-code "class Test
  feature
    set_hour(a_hour: Integer)
      require
        valid_hour: a_hour >= 0 and a_hour <= 23
      do
        let hour := a_hour
      ensure
        hour_set: hour = a_hour
      end
end"
          bad-code "class Test
  feature
    buggy_set(a_hour: Integer)
      require
        valid_hour: a_hour >= 0 and a_hour <= 23
      do
        let hour := 99
      ensure
        hour_set: hour = a_hour
      end
end"]
      ;; No output expected, only successful execution.
      (is (= [] (invoke-first-method ok-code "set_hour" [15])))
      (is (thrown-with-msg?
            Exception
            #"Postcondition violation: hour_set"
            (invoke-first-method bad-code "buggy_set" [15]))))))
