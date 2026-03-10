(ns nex.constants-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]))

(defn- execute-method-output [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [c (:classes ast)]
            (interp/register-class ctx c))
        class-def (last (:classes ast))
        method-def (-> ast :classes last :body first :members first)
        method-env (interp/make-env (:globals ctx))
        obj (interp/make-object (:name class-def) {})
        _ (interp/env-define method-env "obj" obj)
        ctx-with-env (assoc ctx :current-env method-env)]
    (interp/eval-node ctx-with-env {:type :call
                                    :target "obj"
                                    :method (:name method-def)
                                    :args []})
    @(:output ctx-with-env)))

(deftest constants-typecheck-and-runtime
  (testing "constants infer types, are inherited, and are accessible by class name"
    (let [code "class Frame
  feature
    HELLO: String = \"hello\"
    MAX_WIDTH = 450
end

class Special_Frame
  inherit Frame
  feature
    demo() do
      print(HELLO)
      print(Frame.MAX_WIDTH)
      print(MAX_WIDTH)
      print(MAX_WIDTH + 10)
    end
end"
          result (tc/type-check (p/ast code))
          output (execute-method-output code)]
      (is (:success result))
      (is (empty? (:errors result)))
      (is (= ["\"hello\"" "450" "450" "460"] output)))))

(deftest constants-do-not-require-constructor-init
  (testing "class constants are excluded from constructor initialization requirements"
    (let [code "class Frame
  feature
    MAX_WIDTH = 450
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest constants-are-not-assignable
  (testing "assigning to constants is rejected"
    (let [code "class Frame
  feature
    MAX_WIDTH = 450
    bad() do
      MAX_WIDTH := 10
    end
end"
          result (tc/type-check (p/ast code))]
      (is (not (:success result)))
      (is (seq (:errors result))))))
