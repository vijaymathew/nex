(ns nex.convert-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest convert-parser-shape
  (testing "Parser/walker produce :convert AST node"
    (let [ast (p/ast "class T
  feature
    f(v: Any)
    do
      if convert v to c:Car then
        c.sound_horn
      end
    end
end")
          cond-node (-> ast :classes first :body first :members first :body first :condition)]
      (is (= :convert (:type cond-node)))
      (is (= "c" (:var-name cond-node)))
      (is (= "Car" (:target-type cond-node))))))

(deftest convert-runtime-success
  (testing "convert succeeds for compatible runtime type and binds converted value"
    (let [code "class Vehicle
  feature
    sound_horn do
      print(\"vehicle\")
    end
end

class Car
  inherit Vehicle
  feature
    sound_horn do
      print(\"car\")
    end
end

class Test
  feature
    demo() do
      let vehicle_1: Vehicle := create Car
      if convert vehicle_1 to my_car:Car then
        my_car.sound_horn
      else
        print(\"fail\")
      end
    end
end"
          output (execute-method-output code)]
      (is (= ["\"car\""] output)))))

(deftest convert-runtime-failure-binds-nil
  (testing "convert failure returns false and binds variable to nil"
    (let [code "class Vehicle end
class Car inherit Vehicle end

class Test
  feature
    demo() do
      let vehicle_1: Vehicle := create Vehicle
      let ok: Boolean := convert vehicle_1 to my_car:Car
      if ok then
        print(\"ok\")
      else
        print(my_car)
      end
    end
end"
          output (execute-method-output code)]
      (is (= ["nil"] output)))))

(deftest convert-typecheck-success-in-if
  (testing "convert in if condition typechecks and makes converted var usable in then"
    (let [code "class Vehicle
  feature
    sound_horn do
    end
end

class Car
  inherit Vehicle
  feature
    sound_horn do
    end
end

class Test
  feature
    demo(v: Vehicle) do
      if convert v to my_car:Car then
        my_car.sound_horn
      end
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest convert-typecheck-unrelated-types-fail
  (testing "convert between unrelated static types is rejected"
    (let [code "class A end
class B end
class Test
  feature
    demo(a: A) do
      let ok: Boolean := convert a to b:B
    end
end"
          result (tc/type-check (p/ast code))]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest convert-do-scope-does-not-leak
  (testing "convert-bound variable in do-block does not leak outside"
    (let [code "class Vehicle end
class Car inherit Vehicle end
class Test
  feature
    demo(v: Vehicle) do
      do
        let ok: Boolean := convert v to my_car:Car
      end
      print(my_car)
    end
end"
          result (tc/type-check (p/ast code))]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest convert-standalone-statement-binds-in-scope
  (testing "standalone convert statement binds its variable in the enclosing scope"
    (let [code "class Pair [F, S]
  create
    make(first_val: F, second_val: S) do
      first := first_val
      second := second_val
    end

  feature
    first: F
    second: S

    swap() do
      convert second to f:F
      let maybe_f: ?F := f
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))
