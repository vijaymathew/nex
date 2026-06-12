(ns nex.short-circuit-test
  "The logical operators `and` and `or` must short-circuit in the interpreter,
   matching the JVM compiler's `emit-boolean-short-circuit!` and the design docs
   (docs/design/appendix_a.md)."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(defn- run-demo
  "Parse CODE (a single class with a `demo()` method), evaluate the method body,
   and return the collected output lines."
  [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (interp/register-class ctx (first (:classes ast)))
        method-body (-> ast :classes first :body first :members first :body)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt method-body]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

(defn- demo [body]
  (str "class Test\n  feature\n    demo() do\n" body "\n    end\nend"))

(deftest and-short-circuits-false-left
  (testing "`false and <rhs>` does not evaluate the right operand"
    ;; `10 / z` would throw Division by zero if it were evaluated.
    (is (= ["\"else\""]
           (run-demo (demo "      let z := 0
      if false and (10 / z > 0) then
        print(\"then\")
      else
        print(\"else\")
      end"))))))

(deftest or-short-circuits-true-left
  (testing "`true or <rhs>` does not evaluate the right operand"
    (is (= ["\"then\""]
           (run-demo (demo "      let z := 0
      if true or (10 / z > 0) then
        print(\"then\")
      else
        print(\"else\")
      end"))))))

(deftest and-evaluates-rhs-when-left-true
  (testing "`true and <rhs>` still evaluates the right operand for the result"
    (is (= ["\"else\""]
           (run-demo (demo "      if true and (5 > 10) then
        print(\"then\")
      else
        print(\"else\")
      end"))))))

(deftest or-evaluates-rhs-when-left-false
  (testing "`false or <rhs>` still evaluates the right operand for the result"
    (is (= ["\"then\""]
           (run-demo (demo "      if false or (5 < 10) then
        print(\"then\")
      else
        print(\"else\")
      end"))))))
