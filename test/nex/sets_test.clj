(ns nex.sets-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.repl :as repl]
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

(deftest set-literal-parsing-and-typecheck
  (testing "Set literals parse and infer Set[T]"
    (let [ast (p/ast "class Test
  feature
    demo() do
      let s: Set[Integer] := #{0, 2, 4}
    end
end")
          set-expr (-> ast :classes first :body first :members first :body first :value)
          result (tc/type-check ast)]
      (is (= :set-literal (:type set-expr)))
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest empty-set-literal-parsing-and-typecheck
  (testing "Empty set literals parse with contextual type"
    (let [ast (p/ast "class Test
  feature
    demo() do
      let s: Set[Integer] := #{}
    end
end")
          set-expr (-> ast :classes first :body first :members first :body first :value)
          result (tc/type-check ast)]
      (is (= :set-literal (:type set-expr)))
      (is (empty? (:elements set-expr)))
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest set-runtime-operations
  (testing "Set literals, printing, and core operations work at runtime"
    (let [code "class Test
  feature
    demo() do
      let s: Set[Integer] := #{0, 2, 5}
      print(s)
      print(s.contains(2))
      print(s.union(#{3}))
      print(#{1, 2}.difference(#{2, 3}))
      print(#{1, 2}.intersection(#{2, 3}))
      print(#{1, 2}.symmetric_difference(#{2, 3}))
    end
end"
          output (execute-method-output code)]
      (is (= ["#{0, 2, 5}" "true" "#{0, 2, 5, 3}" "#{1}" "#{2}" "#{1, 3}"] output)))))

(deftest set-from-array-runtime
  (testing "create Set[T].from_array builds a set"
    (let [code "class Test
  feature
    demo() do
      let s: Set[Integer] := create Set[Integer].from_array([0, 2, 4, 2])
      print(s)
      print(s.contains(4))
    end
end"
          output (execute-method-output code)]
      (is (= ["#{0, 2, 4}" "true"] output)))))

(deftest set-from-array-top-level-expression
  (testing "bare constructor expressions work at the REPL"
      (let [ctx (repl/init-repl-context)
          output (binding [repl/*type-checking-enabled* (atom false)
                           repl/*repl-var-types* (atom {})]
                   (with-out-str
                     (repl/eval-code ctx "create Set[Integer].from_array([1,2])" "<repl>")))]
      (is (= "#{1, 2}\n" output)))))

(deftest empty-set-literal-runtime
  (testing "empty set literals work at runtime"
    (let [ctx (repl/init-repl-context)
          output (binding [repl/*type-checking-enabled* (atom false)
                           repl/*repl-var-types* (atom {})]
                   (with-out-str
                     (repl/eval-code ctx "let s: Set[Integer] := #{}\nprint(s.is_empty())" "<repl>")))]
      (is (= "true\n" output)))))

(deftest old-bare-brace-set-syntax-rejected
  (testing "old {1, 2} set literal syntax is rejected"
    (is (thrown? Exception (p/ast "{1, 2}")))))
