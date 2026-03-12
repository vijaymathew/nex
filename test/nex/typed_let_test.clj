(ns nex.typed-let-test
  "Tests for typed let syntax: let x: Integer := 10"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.generator.java :as java]
            [nex.repl :as repl]))

(deftest typed-let-parsing-test
  (testing "Parse let with type annotation"
    (let [code "class Test
  feature
    demo() do
      let x: Integer := 42
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method-def (-> class-def :body first :members first)
          let-stmt (first (:body method-def))]
      (is (= :let (:type let-stmt)))
      (is (= "x" (:name let-stmt)))
      (is (= "Integer" (:var-type let-stmt)))
      (is (= 42 (-> let-stmt :value :value))))))

(deftest untyped-let-parsing-test
  (testing "Parse let without type annotation (original syntax)"
    (let [code "class Test
  feature
    demo() do
      let x := 42
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method-def (-> class-def :body first :members first)
          let-stmt (first (:body method-def))]
      (is (= :let (:type let-stmt)))
      (is (= "x" (:name let-stmt)))
      (is (nil? (:var-type let-stmt)))
      (is (= 42 (-> let-stmt :value :value))))))

(deftest typed-let-interpreter-test
  (testing "Interpret code with typed let"
    (let [code "class Test
  feature
    demo() do
      let x: Integer := 10
      let y: Integer := 20
      print(x + y)
    end
end"
          ast (p/ast code)
          ctx (interp/make-context)
          _ (interp/register-class ctx (first (:classes ast)))
          method-def (-> ast :classes first :body first :members first)
          method-env (interp/make-env (:globals ctx))
          ctx-with-env (assoc ctx :current-env method-env)]
      (doseq [stmt (:body method-def)]
        (interp/eval-node ctx-with-env stmt))
      (is (= ["30"] @(:output ctx-with-env))))))

(deftest mixed-let-interpreter-test
  (testing "Interpret code with mixed typed and untyped let"
    (let [code "class Test
  feature
    demo() do
      let x: Integer := 10
      let y := 20
      let z: Integer := x + y
      print(z)
    end
end"
          ast (p/ast code)
          ctx (interp/make-context)
          _ (interp/register-class ctx (first (:classes ast)))
          method-def (-> ast :classes first :body first :members first)
          method-env (interp/make-env (:globals ctx))
          ctx-with-env (assoc ctx :current-env method-env)]
      (doseq [stmt (:body method-def)]
        (interp/eval-node ctx-with-env stmt))
      (is (= ["30"] @(:output ctx-with-env))))))

(deftest typed-let-java-generation-test
  (testing "Generate Java code with typed let"
    (let [code "class Calculator
  feature
    add(a, b: Integer): Integer do
      let result: Integer := a + b
      print(result)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "int result = (a + b);"))
      ;; Verify it's a typed declaration, not just an assignment
      (is (str/includes? java-code "int result")))))

(deftest untyped-let-java-generation-test
  (testing "Generate Java code with untyped let (backward compatibility)"
    (let [code "class Calculator
  feature
    add(a, b: Integer) do
      let result := a + b
      print(result)
    end
end"
          java-code (java/translate code {:skip-type-check true})]
      (is (str/includes? java-code "result = (a + b);"))
      (is (not (str/includes? java-code "int result"))))))

(deftest all-types-let-test
  (testing "Test let with all supported types"
    (let [code "class TypeDemo
  feature
    demo() do
      let i: Integer := 42
      let s: String := \"hello\"
      let b: Boolean := true
      let r: Real := 3.14
      print(i)
      print(s)
      print(b)
      print(r)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method-def (-> class-def :body first :members first)
          let-stmts (take 4 (:body method-def))]
      (is (= "Integer" (:var-type (nth let-stmts 0))))
      (is (= "String" (:var-type (nth let-stmts 1))))
      (is (= "Boolean" (:var-type (nth let-stmts 2))))
      (is (= "Real" (:var-type (nth let-stmts 3)))))))

(deftest typed-let-in-loops-test
  (testing "Typed let in loop constructs"
    (let [code "class LoopDemo
  feature
    count_to_five() do
      from
        let i: Integer := 1
      until
        i > 5
      do
        print(i)
        i := i + 1
      end
    end
end"
          ast (p/ast code)
          ctx (interp/make-context)
          _ (interp/register-class ctx (first (:classes ast)))
          method-def (-> ast :classes first :body first :members first)
          method-env (interp/make-env (:globals ctx))
          ctx-with-env (assoc ctx :current-env method-env)]
      (doseq [stmt (:body method-def)]
        (interp/eval-node ctx-with-env stmt))
      (is (= ["1" "2" "3" "4" "5"] @(:output ctx-with-env))))))

(deftest typed-let-repl-test
  (testing "Typed let works in REPL context"
    (let [code "class __ReplTemp__
  feature
    __eval__() do
      let x: Integer := 100
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method-def (-> class-def :body first :members first)
          let-stmt (first (:body method-def))]
      (is (= :let (:type let-stmt)))
      (is (= "x" (:name let-stmt)))
      (is (= "Integer" (:var-type let-stmt)))
      (is (= 100 (-> let-stmt :value :value))))))

(deftest repl-typecheck-allows-string-concatenation
  (testing "REPL typechecking accepts string concatenation with +"
    (let [ctx (repl/init-repl-context)
          output (try
                   (reset! repl/*type-checking-enabled* true)
                   (with-out-str
                     (repl/eval-code ctx "\"hello\" + \" world\""))
                   (finally
                     (reset! repl/*type-checking-enabled* false)
                     (reset! repl/*repl-var-types* {})))]
      (is (str/includes? output "hello world"))
      (is (not (str/includes? output "Type error:"))))))

(deftest repl-typecheck-allows-string-concatenation-with-non-string
  (testing "REPL typechecking accepts String + non-String"
    (let [ctx (repl/init-repl-context)
          output (try
                   (reset! repl/*type-checking-enabled* true)
                   (with-out-str
                     (repl/eval-code ctx "\"n=\" + 10"))
                   (finally
                     (reset! repl/*type-checking-enabled* false)
                     (reset! repl/*repl-var-types* {})))]
      (is (str/includes? output "n=10"))
      (is (not (str/includes? output "Type error:"))))))

(deftest repl-typecheck-rejects-nil-for-attachable-bindings
  (testing "REPL typechecking rejects nil assigned to attachable references"
    (let [ctx (repl/init-repl-context)
          output (try
                   (reset! repl/*type-checking-enabled* true)
                   (with-out-str
                     (repl/eval-code ctx "class A feature x: Integer end")
                     (repl/eval-code ctx "let s: String := nil")
                     (repl/eval-code ctx "let a: A := nil"))
                   (finally
                     (reset! repl/*type-checking-enabled* false)
                     (reset! repl/*repl-var-types* {})))]
      (is (str/includes? output "Cannot assign Nil to variable 's' of type String"))
      (is (str/includes? output "Cannot assign Nil to variable 'a' of type A")))))

(deftest repl-typecheck-rejects-attachable-field-without-constructor
  (testing "REPL typechecking rejects class definitions with uninitialized attachable fields"
    (let [ctx (repl/init-repl-context)
          output (try
                   (reset! repl/*type-checking-enabled* true)
                   (with-out-str
                     (repl/eval-code ctx "class A feature x: Integer end")
                     (repl/eval-code ctx "class B feature a: A end"))
                   (finally
                     (reset! repl/*type-checking-enabled* false)
                     (reset! repl/*repl-var-types* {})))]
      (is (str/includes? output "Attachable fields must be initialized by constructors in class B: a")))))
