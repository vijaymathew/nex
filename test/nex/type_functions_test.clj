(ns nex.type-functions-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]))

(defn- execute-method-output [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [c (:classes ast)]
            (interp/register-class ctx c))
        method-body (-> ast :classes last :body first :members first :body)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt method-body]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

(deftest type-of-runtime-types
  (testing "type_of returns exact runtime type names"
    (let [code "class Vehicle end

class Car inherit Vehicle end

class Test
  feature
    demo() do
      let v: Vehicle := create Car
      print(type_of(\"hello\"))
      print(type_of(123))
      print(type_of(123.0))
      print(type_of(v))
    end
end"
          output (execute-method-output code)]
      (is (= ["\"String\"" "\"Integer\"" "\"Real\"" "\"Car\""] output)))))

(deftest type-is-runtime-subtype
  (testing "type_is checks type/subtype relation at runtime"
    (let [code "class Vehicle end

class Car inherit Vehicle end

class Test
  feature
    demo() do
      let v: Vehicle := create Car
      print(type_is(\"String\", \"hello\"))
      print(type_is(\"String\", 123))
      print(type_is(\"Real\", 123))
      print(type_is(\"Vehicle\", v))
      print(type_is(\"Car\", v))
    end
end"
          output (execute-method-output code)]
      (is (= ["true" "false" "true" "true" "true"] output)))))

(deftest type-functions-typecheck
  (testing "type_of and type_is have correct static types"
    (let [code "class Test
  feature
    demo(v: Any) do
      let a: String := type_of(v)
      let b: Boolean := type_is(\"String\", v)
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest type-is-first-arg-must-be-string
  (testing "type_is requires first argument to be String"
    (let [code "class Test
  feature
    demo(v: Any) do
      let b: Boolean := type_is(v, v)
    end
end"
          result (tc/type-check (p/ast code))]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest division-runtime-format-and-type
  (testing "Non-integral division is reported as a Real in JVM runtime output"
    (let [code "class Test
  feature
    demo() do
      print(10 / 3)
      print(type_of(10 / 3))
    end
end"
          output (execute-method-output code)]
      (is (str/starts-with? (first output) "3.333333333333333"))
      (is (= "\"Real\"" (second output))))))
