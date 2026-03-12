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
  (testing "Integral division yields an Integer at runtime when both operands are integral"
    (let [code "class Test
  feature
    demo() do
      print(10 / 3)
      print(type_of(10 / 3))
    end
end"
          output (execute-method-output code)]
      (is (= "3" (first output)))
      (is (= "\"Integer\"" (second output))))))

(deftest power-runtime-format-and-type
  (testing "Integral exponentiation stays Integer and mixed exponentiation stays Real"
    (let [code "class Test
  feature
    demo() do
      print(2 ^ 8)
      print(type_of(2 ^ 8))
      print(2.0 ^ 8)
      print(type_of(2.0 ^ 8))
    end
end"
          output (execute-method-output code)]
      (is (= "256" (nth output 0)))
      (is (= "\"Integer\"" (nth output 1)))
      (is (= "256.0" (nth output 2)))
      (is (= "\"Real\"" (nth output 3))))))

(deftest string-integer-conversions-accept-base-prefixes
  (testing "String to_integer and to_integer64 accept binary, octal, hex, and separators"
    (let [code "class Test
  feature
    demo() do
      print(\"0b1010\".to_integer())
      print(\"0o10\".to_integer())
      print(\"0xFF\".to_integer())
      print(\"1_000\".to_integer64())
      print(\"-0x10\".to_integer())
    end
end"
          output (execute-method-output code)]
      (is (= ["10" "8" "255" "1000" "-16"] output)))))

(deftest string-concatenation-coerces-with-to-string
  (testing "String + value coerces the value using Nex to_string semantics"
    (let [code "class Box
  feature
    value: Integer

    to_string(): String do
      result := \"Box(\" + value.to_string() + \")\"
    end

  create
    make(v: Integer) do
      value := v
    end
end

class Test
  feature
    demo() do
      let b: Box := create Box.make(7)
      print(\"value=\" + 10)
      print(\"box=\" + b)
    end
end"
          output (execute-method-output code)]
      (is (= ["\"value=10\"" "\"box=Box(7)\""] output)))))

(deftest any-root-runtime-methods
  (testing "Explicit Any subclasses inherit to_string, equals, and clone at runtime"
    (let [code "class Box inherit Any
  feature
    x: Integer
  create
    make(v: Integer) do
      x := v
    end
end

class Test
  feature
    demo() do
      let a: Box := create Box.make(10)
      let b: Any := a.clone()
      print(a.to_string())
      print(type_of(b))
      print(a.equals(a))
      print(a.equals(b))
    end
end"
          output (execute-method-output code)]
      (is (= "\"#<Box object>\"" (nth output 0)))
      (is (= "\"Box\"" (nth output 1)))
      (is (= "true" (nth output 2)))
      (is (= "false" (nth output 3))))))

(deftest collection-deep-methods-runtime
  (testing "Array, Map, and Set implement deep to_string, equals, and clone"
    (let [code "class Test
  feature
    demo() do
      let a1 := [[1, 2], [3, 4]]
      let a2 := a1.clone()
      a2.get(0).put(0, 99)
      print(a1.to_string())
      print(a1.equals([[1, 2], [3, 4]]))
      print(a2.equals([[99, 2], [3, 4]]))

      let m1 := {\"nums\": [1, 2]}
      let m2 := m1.clone()
      m2.get(\"nums\").add(3)
      print(m1.to_string())
      print(m1.equals({\"nums\": [1, 2]}))
      print(m2.equals({\"nums\": [1, 2, 3]}))

      let s1: Set[Any] := #{[1, 2], [3, 4]}
      let s2: Set[Any] := s1.clone()
      print(s1.equals(#{[3, 4], [1, 2]}))
      print(s2.to_string())
    end
end"
          output (execute-method-output code)]
      (is (= "\"[[1, 2], [3, 4]]\"" (nth output 0)))
      (is (= "true" (nth output 1)))
      (is (= "true" (nth output 2)))
      (is (str/includes? (nth output 3) "\"nums\": [1, 2]"))
      (is (= "true" (nth output 4)))
      (is (= "true" (nth output 5)))
      (is (= "true" (nth output 6)))
      (is (= "\"#{[1, 2], [3, 4]}\"" (nth output 7))))))
