(ns nex.nil-literal-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(defn find-class [ast name]
  (first (filter #(= name (:name %)) (:classes ast))))

(defn find-method [class-def name]
  (->> (:body class-def)
       (mapcat :members)
       (filter #(= :method (:type %)))
       (filter #(= name (:name %)))
       first))

(defn execute-method [code class-name method-name]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (doseq [class-node (:classes ast)]
            (interp/register-class ctx class-node))
        class-def (find-class ast class-name)
        method-def (find-method class-def method-name)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt (:body method-def)]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

(deftest nil-literal-parsing-test
  (testing "Parse nil literal"
    (let [code "class Test
  feature
    demo() do
      let x := nil
    end
end"
          ast (p/ast code)
          class-def (find-class ast "Test")
          method-def (find-method class-def "demo")
          let-stmt (-> method-def :body first)]
      (is (= :let (:type let-stmt)))
      (is (= :nil (-> let-stmt :value :type))))))

(deftest nil-default-object-field-test
  (testing "Uninitialized object fields default to nil and compare correctly"
    (let [code "class B
end

class A
  feature
    b: B
    demo() do
      let a: A := create A
      print(a.b = nil)
      print(a.b /= nil)
    end
end"
          output (execute-method code "A" "demo")]
      (is (= ["true" "false"] output)))))

(deftest boolean-field-update-test
  (testing "Boolean field updates persist when set to false"
    (let [code "class Flag
  feature
    enabled: Boolean

    disable() do
      this.enabled := false
    end

    demo() do
      let f: Flag := create Flag
      f.disable()
      print(f.enabled)
    end
end"
          output (execute-method code "Flag" "demo")]
      (is (= ["false"] output)))))
