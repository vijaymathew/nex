(ns nex.identity-equality-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- execute-method
  [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [c (:classes ast)]
            (interp/register-class ctx c))
        method-body (-> ast :classes first :body first :members first :body)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt method-body]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

(deftest identity-operators-parse-and-typecheck-test
  (testing "identity operators parse and typecheck"
    (let [ast (p/ast "class Test
  feature
    demo(a: Array[Integer], b: Array[Integer]) do
      let same: Boolean := a == b
      let diff: Boolean := a != b
    end
end")]
      (is (= "==" (-> ast :classes first :body first :members first :body first :value :operator)))
      (is (= "!=" (-> ast :classes first :body first :members first :body second :value :operator)))
      (is (nil? (:error (tc/type-check ast)))))))

(deftest interpreter-identity-operators-test
  (testing "interpreter distinguishes reference identity from value equality"
    (let [output (execute-method "class Test
  feature
    demo() do
      let a := [1]
      let b := a
      let c := [1]
      print(a == b)
      print(a == c)
      print(a != c)
      print(1 == 1)
      print(\"x\" != \"y\")
    end
end")]
      (is (= ["true" "false" "true" "true" "true"] output)))))

(deftest map-value-equality-test
  ;; `=` fell through to Clojure's `=`, which compares the portable map's
  ;; internal atom by identity, so two structurally equal maps never compared
  ;; equal — and neither did an array or map containing one. The compiled
  ;; backend said `true` throughout, so the same program gave different answers
  ;; depending on how it was run.
  (testing "two maps with the same entries are equal"
    (is (= ["true"] (mapv str (interp/interpret-and-get-output
                               (p/ast "print({\"a\": 1, \"b\": 2} = {\"a\": 1, \"b\": 2})")))))
    (is (= ["true"] (mapv str (interp/interpret-and-get-output
                               (p/ast "print({\"a\": 1} = {\"a\": 1})"))))))
  (testing "a map built by `set` equals the matching literal"
    (is (= ["true"] (mapv str (interp/interpret-and-get-output
                               (p/ast (str "let m: Map[String, Integer] := {}\n"
                                           "m.set(\"a\", 1)\n"
                                           "m.set(\"b\", 2)\n"
                                           "print(m = {\"a\": 1, \"b\": 2})")))))))
  (testing "maps that differ are not equal"
    (is (= ["false"] (mapv str (interp/interpret-and-get-output
                                (p/ast "print({\"a\": 1} = {\"a\": 2})")))))
    (is (= ["true"] (mapv str (interp/interpret-and-get-output
                               (p/ast "print({\"a\": 1} /= {\"a\": 2})"))))))
  (testing "a map nested inside another value is compared structurally too"
    (is (= ["true"] (mapv str (interp/interpret-and-get-output
                               (p/ast "print([{\"k\": 1}] = [{\"k\": 1}])")))))
    (is (= ["true"] (mapv str (interp/interpret-and-get-output
                               (p/ast "print({\"a\": {\"b\": 1}} = {\"a\": {\"b\": 1}})"))))))
  (testing "the operators that already worked are unchanged"
    (is (= ["true"] (mapv str (interp/interpret-and-get-output
                               (p/ast "print([1, 2] = [1, 2])")))))
    (is (= ["true"] (mapv str (interp/interpret-and-get-output
                               (p/ast "print(#{1, 2} = #{1, 2})")))))
    (is (= ["true"] (mapv str (interp/interpret-and-get-output
                               (p/ast "print(\"ab\" = \"ab\")")))))
    (is (= ["false"] (mapv str (interp/interpret-and-get-output
                                (p/ast "print({\"a\": 1} == {\"a\": 1})")))))))
