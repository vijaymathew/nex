(ns nex.if-conditions-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.generator.java :as java-gen]
            [nex.generator.javascript :as js-gen]))

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

(deftest basic-if-then-else-test
  (testing "Basic if-then-else with true condition"
    (let [code "class Test
  feature
    demo() do
      let x := 10
      if x > 5 then
        print(\"x is greater than 5\")
      else
        print(\"x is less than or equal to 5\")
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"x is greater than 5\""] output)))))

(deftest false-condition-test
  (testing "False condition (else branch)"
    (let [code "class Test
  feature
    demo() do
      let x := 3
      if x > 5 then
        print(\"x is greater than 5\")
      else
        print(\"x is less than or equal to 5\")
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"x is less than or equal to 5\""] output)))))

(deftest calculations-in-branches-test
  (testing "Calculations in different branches"
    (let [code "class Test
  feature
    demo() do
      let x := 10
      let result := 0
      if x > 5 then
        let result := x * 2
        print(result)
      else
        let result := x + 10
        print(result)
      end
    end
end"
          output (execute-method code)]
      (is (= ["20"] output)))))

(deftest nested-if-test
  (testing "Nested if statements"
    (let [code "class Test
  feature
    demo() do
      let x := 15
      if x > 10 then
        if x > 20 then
          print(\"x is greater than 20\")
        else
          print(\"x is between 10 and 20\")
        end
      else
        print(\"x is 10 or less\")
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"x is between 10 and 20\""] output)))))

(deftest boolean-conditions-test
  (testing "Boolean conditions"
    (let [code "class Test
  feature
    demo() do
      let flag := true
      if flag then
        print(\"Flag is true\")
      else
        print(\"Flag is false\")
      end
      let flag := false
      if flag then
        print(\"Flag is true\")
      else
        print(\"Flag is false\")
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"Flag is true\"" "\"Flag is false\""] output)))))

(deftest complex-logical-conditions-test
  (testing "Complex logical conditions with 'and'"
    (let [code "class Test
  feature
    demo() do
      let x := 15
      let y := 20
      if x > 10 and y > 15 then
        print(\"Both conditions are true\")
      else
        print(\"At least one condition is false\")
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"Both conditions are true\""] output)))))

(deftest equality-check-test
  (testing "Equality checks"
    (let [code "class Test
  feature
    demo() do
      let x := 10
      let y := 10
      if x = y then
        print(\"x equals y\")
      else
        print(\"x does not equal y\")
      end
      let z := 5
      if x = z then
        print(\"x equals z\")
      else
        print(\"x does not equal z\")
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"x equals y\"" "\"x does not equal z\""] output)))))

(deftest multiple-statements-in-branches-test
  (testing "Multiple statements in branches"
    (let [code "class Test
  feature
    demo() do
      let score := 85
      if score >= 60 then
        print(\"Pass\")
        let grade := score / 10
        print(grade)
      else
        print(\"Fail\")
        print(0)
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"Pass\"" "17/2"] output)))))

;; ========== elseif tests ==========

(deftest elseif-first-branch-true-test
  (testing "elseif chain - first branch true"
    (let [code "class Test
  feature
    demo() do
      let x := -5
      if x < 0 then
        print(\"negative\")
      elseif x > 100 then
        print(\"big\")
      else
        print(\"normal\")
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"negative\""] output)))))

(deftest elseif-middle-branch-true-test
  (testing "elseif chain - middle branch true"
    (let [code "class Test
  feature
    demo() do
      let x := 200
      if x < 0 then
        print(\"negative\")
      elseif x > 100 then
        print(\"big\")
      else
        print(\"normal\")
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"big\""] output)))))

(deftest elseif-else-branch-test
  (testing "elseif chain - else branch"
    (let [code "class Test
  feature
    demo() do
      let x := 50
      if x < 0 then
        print(\"negative\")
      elseif x > 100 then
        print(\"big\")
      else
        print(\"normal\")
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"normal\""] output)))))

(deftest multiple-elseif-test
  (testing "Multiple elseif clauses"
    (let [code "class Test
  feature
    demo() do
      let score := 75
      if score >= 90 then
        print(\"A\")
      elseif score >= 80 then
        print(\"B\")
      elseif score >= 70 then
        print(\"C\")
      elseif score >= 60 then
        print(\"D\")
      else
        print(\"F\")
      end
    end
end"
          output (execute-method code)]
      (is (= ["\"C\""] output)))))

(deftest elseif-no-else-test
  (testing "if/elseif with no else"
    (let [code "class Test
  feature
    demo() do
      let x := 50
      if x < 0 then
        print(\"negative\")
      elseif x > 100 then
        print(\"big\")
      end
      print(\"done\")
    end
end"
          output (execute-method code)]
      (is (= ["\"done\""] output)))))

(deftest if-then-end-no-else-no-elseif-test
  (testing "if/then/end with no else and no elseif"
    (let [code "class Test
  feature
    demo() do
      let x := 10
      if x > 5 then
        print(\"big\")
      end
      print(\"done\")
    end
end"
          output (execute-method code)]
      (is (= ["\"big\"" "\"done\""] output)))))

(deftest if-then-end-false-no-else-test
  (testing "if/then/end with false condition and no else"
    (let [code "class Test
  feature
    demo() do
      let x := 3
      if x > 5 then
        print(\"big\")
      end
      print(\"done\")
    end
end"
          output (execute-method code)]
      (is (= ["\"done\""] output)))))

(deftest java-codegen-elseif-test
  (testing "Java codegen emits else if for elseif"
    (let [code "class Test
  feature
    demo() do
      let x: Integer := 0
      if x < 0 then
        print(\"negative\")
      elseif x > 100 then
        print(\"big\")
      else
        print(\"normal\")
      end
    end
end"
          ast (p/ast code)
          java-code (java-gen/translate-ast ast)]
      (is (str/includes? java-code "} else if ("))
      (is (str/includes? java-code "} else {")))))

(deftest java-codegen-no-else-test
  (testing "Java codegen with no else block"
    (let [code "class Test
  feature
    demo() do
      let x: Integer := 0
      if x < 0 then
        print(\"negative\")
      elseif x > 100 then
        print(\"big\")
      end
    end
end"
          ast (p/ast code)
          java-code (java-gen/translate-ast ast)]
      (is (str/includes? java-code "} else if ("))
      (is (not (str/includes? java-code "} else {"))))))

(deftest js-codegen-elseif-test
  (testing "JavaScript codegen emits else if for elseif"
    (let [code "class Test
  feature
    demo() do
      let x: Integer := 0
      if x < 0 then
        print(\"negative\")
      elseif x > 100 then
        print(\"big\")
      else
        print(\"normal\")
      end
    end
end"
          ast (p/ast code)
          js-code (js-gen/translate-ast ast)]
      (is (str/includes? js-code "} else if ("))
      (is (str/includes? js-code "} else {")))))

