(ns nex.param-syntax-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(deftest traditional-syntax-test
  (testing "Traditional parameter syntax (each param separate)"
    (let [code "class Test
  feature
    add(a: Integer, b: Integer) do
      print(a + b)
    end
end"
          ast (p/ast code)
          method (-> ast :classes first :body first :members first)
          params (:params method)]
      (is (= 2 (count params)))
      (is (= "a" (:name (first params))))
      (is (= "Integer" (:type (first params))))
      (is (= "b" (:name (second params))))
      (is (= "Integer" (:type (second params))))
      ;; Test execution
      (let [ctx (interp/make-context)
            _ (interp/register-class ctx (first (:classes ast)))
            method-def (-> ast :classes first :body first :members first)
            method-env (interp/make-env (:globals ctx))
            _ (do
                (interp/env-define method-env "a" 5)
                (interp/env-define method-env "b" 3))
            ctx-with-env (assoc ctx :current-env method-env)]
        (doseq [stmt (:body method-def)]
          (interp/eval-node ctx-with-env stmt))
        (is (= ["8"] @(:output ctx-with-env)))))))

(deftest grouped-syntax-test
  (testing "Grouped parameter syntax (same type together)"
    (let [code "class Test
  feature
    add(a, b: Integer) do
      print(a + b)
    end
end"
          ast (p/ast code)
          method (-> ast :classes first :body first :members first)
          params (:params method)]
      (is (= 2 (count params)))
      (is (= "a" (:name (first params))))
      (is (= "Integer" (:type (first params))))
      (is (= "b" (:name (second params))))
      (is (= "Integer" (:type (second params))))
      ;; Test execution
      (let [ctx (interp/make-context)
            _ (interp/register-class ctx (first (:classes ast)))
            method-def (-> ast :classes first :body first :members first)
            method-env (interp/make-env (:globals ctx))
            _ (do
                (interp/env-define method-env "a" 10)
                (interp/env-define method-env "b" 7))
            ctx-with-env (assoc ctx :current-env method-env)]
        (doseq [stmt (:body method-def)]
          (interp/eval-node ctx-with-env stmt))
        (is (= ["17"] @(:output ctx-with-env)))))))

(deftest mixed-syntax-test
  (testing "Mixed parameter syntax"
    (let [code "class Test
  feature
    calc(a, b: Integer, name: String) do
      print(name, a + b)
    end
end"
          ast (p/ast code)
          method (-> ast :classes first :body first :members first)
          params (:params method)]
      (is (= 3 (count params)))
      (is (= "a" (:name (first params))))
      (is (= "Integer" (:type (first params))))
      (is (= "b" (:name (second params))))
      (is (= "Integer" (:type (second params))))
      (is (= "name" (:name (nth params 2))))
      (is (= "String" (:type (nth params 2))))
      ;; Test execution
      (let [ctx (interp/make-context)
            _ (interp/register-class ctx (first (:classes ast)))
            method-def (-> ast :classes first :body first :members first)
            method-env (interp/make-env (:globals ctx))
            _ (do
                (interp/env-define method-env "a" 15)
                (interp/env-define method-env "b" 25)
                (interp/env-define method-env "name" "Sum"))
            ctx-with-env (assoc ctx :current-env method-env)]
        (doseq [stmt (:body method-def)]
          (interp/eval-node ctx-with-env stmt))
        (is (= ["\"Sum\" 40"] @(:output ctx-with-env)))))))

(deftest gcd-grouped-params-test
  (testing "GCD with grouped parameter syntax"
    (let [code "class Math
  feature
    gcd(a, b: Integer) do
      let x := a
      let y := b
      from
      until
        x = y
      do
        if x > y then
          x := x - y
        else
          y := y - x
        end
      end
      print(x)
    end
end"
          ast (p/ast code)
          method (-> ast :classes first :body first :members first)
          params (:params method)]
      (is (= 2 (count params)))
      (is (= "a" (:name (first params))))
      (is (= "b" (:name (second params))))
      ;; Test execution
      (let [ctx (interp/make-context)
            _ (interp/register-class ctx (first (:classes ast)))
            method-def (-> ast :classes first :body first :members first)
            method-env (interp/make-env (:globals ctx))
            _ (do
                (interp/env-define method-env "a" 48)
                (interp/env-define method-env "b" 18))
            ctx-with-env (assoc ctx :current-env method-env)]
        (doseq [stmt (:body method-def)]
          (interp/eval-node ctx-with-env stmt))
        (is (= ["6"] @(:output ctx-with-env)))))))

(deftest three-params-same-type-test
  (testing "Three parameters of same type"
    (let [code "class Test
  feature
    sum3(x, y, z: Integer) do
      print(x + y + z)
    end
end"
          ast (p/ast code)
          method (-> ast :classes first :body first :members first)
          params (:params method)]
      (is (= 3 (count params)))
      (is (= "x" (:name (first params))))
      (is (= "y" (:name (second params))))
      (is (= "z" (:name (nth params 2))))
      (is (every? #(= "Integer" (:type %)) params))
      ;; Test execution
      (let [ctx (interp/make-context)
            _ (interp/register-class ctx (first (:classes ast)))
            method-def (-> ast :classes first :body first :members first)
            method-env (interp/make-env (:globals ctx))
            _ (do
                (interp/env-define method-env "x" 10)
                (interp/env-define method-env "y" 20)
                (interp/env-define method-env "z" 30))
            ctx-with-env (assoc ctx :current-env method-env)]
        (doseq [stmt (:body method-def)]
          (interp/eval-node ctx-with-env stmt))
        (is (= ["60"] @(:output ctx-with-env)))))))
