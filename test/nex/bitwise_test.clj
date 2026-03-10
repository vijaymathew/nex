(ns nex.bitwise-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- execute-method-output [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [c (:classes ast)]
            (interp/register-class ctx c))
        class-def (last (:classes ast))
        method-def (-> class-def :body first :members first)
        method-env (interp/make-env (:globals ctx))
        obj (interp/make-object (:name class-def) {})
        _ (interp/env-define method-env "obj" obj)
        ctx-with-env (assoc ctx :current-env method-env)]
    (interp/eval-node ctx-with-env {:type :call
                                    :target "obj"
                                    :method (:name method-def)
                                    :args []})
    @(:output ctx-with-env)))

(deftest integer-bitwise-typecheck
  (testing "Integer bitwise methods type-check"
    (let [ast (p/ast "class Test
  feature
    demo(): Integer do
      let x: Integer := (5).bitwise_left_shift(1)
      let y: Integer := (5).bitwise_right_shift(1)
      let z: Integer := (-1).bitwise_logical_right_shift(1)
      let a: Integer := (5).bitwise_rotate_left(2)
      let b: Integer := (5).bitwise_rotate_right(2)
      let c: Boolean := (5).bitwise_is_set(0)
      let d: Integer := (5).bitwise_set(3)
      let e: Integer := (5).bitwise_unset(0)
      let f: Integer := (6).bitwise_and(3)
      let g: Integer := (6).bitwise_or(3)
      let h: Integer := (6).bitwise_xor(3)
      result := (0).bitwise_not
    end
end")
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest integer-bitwise-runtime
  (testing "Integer bitwise methods follow 32-bit semantics"
    (let [code "class Test
  feature
    demo() do
      print((5).bitwise_left_shift(1))
      print((5).bitwise_right_shift(1))
      print((-1).bitwise_logical_right_shift(1))
      print((9).bitwise_rotate_left(2))
      print((9).bitwise_rotate_right(2))
      print((5).bitwise_is_set(0))
      print((5).bitwise_is_set(1))
      print((0).bitwise_set(3))
      print((7).bitwise_unset(1))
      print((6).bitwise_and(3))
      print((6).bitwise_or(3))
      print((6).bitwise_xor(3))
      print((0).bitwise_not)
    end
end"
          output (execute-method-output code)]
      (is (= ["10" "2" "2147483647" "36" "1073741826" "true" "false" "8" "5" "2" "7" "5" "-1"]
             output)))))
