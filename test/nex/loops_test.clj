(ns nex.loops-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.repl :as repl]))

;; Helper function to execute a method body
(defn execute-method [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (interp/register-class ctx (first (:classes ast)))
        method-body (-> ast :classes first :body first :members first :body)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt method-body]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

;; Helper function to execute a method with parameters
(defn execute-method-with-params [code param-bindings]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (interp/register-class ctx (first (:classes ast)))
        method-def (-> ast :classes first :body first :members first)
        method-env (interp/make-env (:globals ctx))
        ;; Bind parameters
        _ (doseq [[param-name value] param-bindings]
            (interp/env-define method-env param-name value))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt (:body method-def)]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

(deftest basic-loop-test
  (testing "Basic loop - count from 1 to 5"
    (let [code "class Test
  feature
    demo() do
      let i := 1
      from
        let i := 1
      until
        i > 5
      do
        print(i)
        i := i + 1
      end
    end
end"
          output (execute-method code)]
      (is (= ["1" "2" "3" "4" "5"] output)))))

(deftest gcd-algorithm-test
  (testing "GCD using Euclid's subtraction algorithm"
    (let [code "class Test
  feature
    gcd(a: Integer, b: Integer) do
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
          output (execute-method-with-params code {"a" 48 "b" 18})]
      (is (= ["6"] output) "gcd(48, 18) should be 6"))))

(deftest loop-with-invariant-test
  (testing "Loop with invariant checking"
    (let [code "class Test
  feature
    demo() do
      let x := 10
      from
      invariant
        positive: x > 0
      until
        x = 1
      do
        print(x)
        x := x - 1
      end
      print(x)
    end
end"
          output (execute-method code)]
      (is (= ["10" "9" "8" "7" "6" "5" "4" "3" "2" "1"] output)))))

(deftest loop-with-variant-test
  (testing "Loop with variant that decreases each iteration"
    (let [code "class Test
  feature
    demo() do
      let n := 5
      from
        let n := 5
      variant
        n
      until
        n = 0
      do
        print(n)
        n := n - 1
      end
    end
end"
          output (execute-method code)]
      (is (= ["5" "4" "3" "2" "1"] output)))))

(deftest gcd-with-contracts-test
  (testing "GCD with invariants and variant"
    (let [code "class Test
  feature
    gcd(a: Integer, b: Integer) do
      let x := a
      let y := b
      from
      invariant
        x_positive: x > 0
        y_positive: y > 0
      variant
        x + y
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
          output (execute-method-with-params code {"a" 24 "b" 18})]
      (is (= ["6"] output) "gcd(24, 18) with contracts should be 6"))))

(deftest nested-loops-test
  (testing "Nested loops"
    (let [code "class Test
  feature
    demo() do
      let i := 1
      from
        let i := 1
      until
        i > 3
      do
        let j := 1
        from
          let j := 1
        until
          j > 3
        do
          print(i, j)
          j := j + 1
        end
        i := i + 1
      end
    end
end"
          output (execute-method code)]
      (is (= ["1 1" "1 2" "1 3" "2 1" "2 2" "2 3" "3 1" "3 2" "3 3"] output)))))

(deftest loop-with-accumulator-test
  (testing "Loop with accumulator - sum of 1 to 10"
    (let [code "class Test
  feature
    demo() do
      let sum := 0
      let i := 1
      from
      until
        i > 10
      do
        sum := sum + i
        i := i + 1
      end
      print(sum)
    end
end"
          output (execute-method code)]
      (is (= ["55"] output) "Sum of 1 to 10 should be 55"))))

(deftest loop-init-locals-do-not-leak-from-repl
  (testing "variables introduced by loop init are scoped to the loop"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "from
  let total := 0
  let i := 1
until
  i > 4
do
  total := total + i
  i := i + 1
end")
                   (repl/eval-code ctx "total"))]
      (is (.contains output "Error: Undefined variable: total")))))
