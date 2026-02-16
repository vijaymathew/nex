(ns nex.scoped-blocks-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

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

(deftest basic-scoping-test
  (testing "Basic variable shadowing in scoped blocks"
    (let [code "class Test
  feature
    demo() do
      let x := 10
      do
        let x := 20
        print(x)
      end
      print(x)
    end
end"
          output (execute-method code)]
      (is (= ["20" "10"] output) "Inner x should be 20, outer x should be 10"))))

(deftest multiple-nesting-levels-test
  (testing "Multiple levels of nesting with shadowing"
    (let [code "class Test
  feature
    demo() do
      let x := 1
      print(x)
      do
        let x := 2
        print(x)
        do
          let x := 3
          print(x)
        end
        print(x)
      end
      print(x)
    end
end"
          output (execute-method code)]
      (is (= ["1" "2" "3" "2" "1"] output) "Should print 1, 2, 3, 2, 1"))))

(deftest different-variables-test
  (testing "Different variables in different scopes"
    (let [code "class Test
  feature
    demo() do
      let x := 10
      do
        let y := 20
        print(x, y)
      end
      print(x)
    end
end"
          output (execute-method code)]
      (is (= ["10 20" "10"] output)))))

(deftest scope-isolation-test
  (testing "Scope isolation - inner variables don't leak out"
    (let [code "class Test
  feature
    demo() do
      let temp := 100
      do
        let temp := 200
        print(temp)
      end
      print(temp)
    end
end"
          output (execute-method code)]
      (is (= ["200" "100"] output)))))

(deftest nested-calculations-test
  (testing "Nested blocks with calculations"
    (let [code "class Test
  feature
    demo() do
      let x := 10
      let result := x * 2
      do
        let x := 5
        let result := x * 3
        print(result)
      end
      print(result)
    end
end"
          output (execute-method code)]
      (is (= ["15" "20"] output)))))

(deftest empty-block-test
  (testing "Empty scoped block"
    (let [code "class Test
  feature
    demo() do
      let x := 42
      do
      end
      print(x)
    end
end"
          output (execute-method code)]
      (is (= ["42"] output)))))

(deftest sequential-blocks-test
  (testing "Multiple sequential scoped blocks"
    (let [code "class Test
  feature
    demo() do
      let x := 1
      do
        let x := 2
        print(x)
      end
      do
        let x := 3
        print(x)
      end
      print(x)
    end
end"
          output (execute-method code)]
      (is (= ["2" "3" "1"] output)))))

