(ns nex.collection-membership-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]))

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

(deftest collection-membership-uses-deep-equality-for-objects
  (testing "array.contains/index_of, map.contains_key, and set.contains respect Nex object equality"
    (let [code "class Box
feature
  value: Integer
create
  make(value: Integer) do
    this.value := value
  end
end

class Test
feature
  demo() do
    let a: Box := create Box.make(7)
    let b: Box := create Box.make(7)
    let arr: Array[Box] := [a]
    let map: Map[Box, String] := {a: \"seen\"}
    let set: Set[Box] := #{a}
    print(arr.contains(b))
    print(arr.index_of(b))
    print(map.contains_key(b))
    print(set.contains(b))
  end
end"
          output (execute-method-output code)]
      (is (= ["true" "0" "true" "true"] output)))))
