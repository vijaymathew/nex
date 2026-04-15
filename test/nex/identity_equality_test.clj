(ns nex.identity-equality-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- execute-method
  [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [c (:classes ast)]
            (interp/register-class ctx c))
        method-body (-> ast :classes first :body first :members first :body)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt method-body]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

(deftest identity-operators-parse-and-typecheck-test
  (testing "identity operators parse and typecheck"
    (let [ast (p/ast "class Test
  feature
    demo(a: Array[Integer], b: Array[Integer]) do
      let same: Boolean := a == b
      let diff: Boolean := a != b
    end
end")]
      (is (= "==" (-> ast :classes first :body first :members first :body first :value :operator)))
      (is (= "!=" (-> ast :classes first :body first :members first :body second :value :operator)))
      (is (nil? (:error (tc/type-check ast)))))))

(deftest interpreter-identity-operators-test
  (testing "interpreter distinguishes reference identity from value equality"
    (let [output (execute-method "class Test
  feature
    demo() do
      let a := [1]
      let b := a
      let c := [1]
      print(a == b)
      print(a == c)
      print(a != c)
      print(1 == 1)
      print(\"x\" != \"y\")
    end
end")]
      (is (= ["true" "false" "true" "true" "true"] output)))))
