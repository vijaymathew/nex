(ns nex.parameterless-call-test
  "Tests for parameterless method calls without parentheses"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(deftest simple-parameterless-call-parsing-test
  (testing "Parse parameterless method call without parentheses"
    (let [code "class Test
  feature
    show() do
      print(42)
    end

    demo() do
      show
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          demo-method (-> class-def :body first :members second)
          call-stmt (-> demo-method :body first)]
      (is (= :call (:type call-stmt)))
      (is (= "show" (:method call-stmt)))
      (is (nil? (:target call-stmt)))
      (is (empty? (:args call-stmt))))))

(deftest object-parameterless-call-parsing-test
  (testing "Parse parameterless method call on object without parentheses"
    (let [code "class Point
  feature
    x: Integer
    show() do
      print(x)
    end
end

class Main
  feature
    demo() do
      let p: Point := create Point
      p.show
    end
end"
          ast (p/ast code)
          main-class (second (:classes ast))
          demo-method (-> main-class :body first :members first)
          call-stmt (-> demo-method :body second)]
      (is (= :call (:type call-stmt)))
      (is (= "show" (:method call-stmt)))
      (is (= "p" (:target call-stmt)))
      (is (empty? (:args call-stmt))))))

(deftest parameterless-call-with-parentheses-test
  (testing "Parse parameterless method call with parentheses (backward compatibility)"
    (let [code "class Test
  feature
    show() do
      print(42)
    end

    demo() do
      show()
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          demo-method (-> class-def :body first :members second)
          call-stmt (-> demo-method :body first)]
      (is (= :call (:type call-stmt)))
      (is (= "show" (:method call-stmt)))
      (is (empty? (:args call-stmt))))))

(deftest mixed-calls-test
  (testing "Parse method calls with and without parentheses"
    (let [code "class Test
  feature
    show() do
      print(42)
    end

    greet(name: String) do
      print(name)
    end

    demo() do
      show
      greet(\"Alice\")
      show()
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          demo-method (-> class-def :body first :members (nth 2))
          stmts (:body demo-method)]
      (is (= 3 (count stmts)))
      ;; First call: show (no parens)
      (is (= :call (:type (first stmts))))
      (is (= "show" (:method (first stmts))))
      (is (empty? (:args (first stmts))))
      ;; Second call: greet("Alice") (with parens and args)
      (is (= :call (:type (second stmts))))
      (is (= "greet" (:method (second stmts))))
      (is (= 1 (count (:args (second stmts)))))
      ;; Third call: show() (with parens but no args)
      (is (= :call (:type (nth stmts 2))))
      (is (= "show" (:method (nth stmts 2))))
      (is (empty? (:args (nth stmts 2)))))))

(deftest chained-parameterless-calls-test
  (testing "Parse chained parameterless method calls"
    (let [code "class Test
  feature
    step1() do
      print(1)
    end

    step2() do
      print(2)
    end

    demo() do
      step1
      step2
    end
end"
          ast (p/ast code)]
      (is (= 1 (count (:classes ast)))))))

(deftest user-example-test
  (testing "Parse user's exact example"
    (let [code "class Point
  private feature
    x: Integer
    y: Integer

  create
    make(newx, newy: Integer) do
      x := newx
      y := newy
    end

  feature
    show() do
      print(x)
    end
end

class Main
  feature
    demo() do
      let p: Point := create Point.make(10, 20)
      p.show
    end
end"
          ast (p/ast code)]
      (is (= 2 (count (:classes ast)))))))

(deftest parameterless-in-expression-test
  (testing "Parameterless call in expression context"
    (let [code "class Test
  feature
    get_value() do
      print(42)
    end

    demo() do
      let x := get_value
      print(x)
    end
end"
          ast (p/ast code)]
      (is (= 1 (count (:classes ast)))))))

(deftest interpreter-bare-method-in-expression-test
  (testing "Interpreter: bare method name in expression resolves to method call"
    (let [code "class C1
  feature
    a: Integer do result := 10 end
    b(x: Integer): Integer do result := a + x end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (let [c1-obj (interp/make-object "C1" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "c1" c1-obj)
            test-ctx (assoc ctx :current-env env)
            result (interp/eval-node test-ctx {:type :call
                                                :target "c1"
                                                :method "b"
                                                :args [{:type :integer :value 10}]})]
        (is (= 20 result))))))

(deftest interpreter-function-field-no-parens-test
  (testing "Interpreter: c1.f (no parens) returns Function object"
    (let [code "class MyFunc inherit Function
  feature
    call0: Integer do result := 42 end
end

class C1
  feature
    f: Function
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (let [fn-obj (interp/make-object "MyFunc" {})
            c1-obj (interp/make-object "C1" {:f fn-obj})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "c1" c1-obj)
            test-ctx (assoc ctx :current-env env)
            result (interp/eval-node test-ctx {:type :call
                                                :target "c1"
                                                :method "f"
                                                :args []
                                                :has-parens false})]
        (is (interp/nex-object? result))
        (is (= "MyFunc" (:class-name result)))))))

(deftest interpreter-function-field-with-parens-test
  (testing "Interpreter: c1.f() (with parens) invokes Function"
    (let [code "class MyFunc inherit Function
  feature
    call0: Integer do result := 42 end
end

class C1
  feature
    f: Function
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (let [fn-obj (interp/make-object "MyFunc" {})
            c1-obj (interp/make-object "C1" {:f fn-obj})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "c1" c1-obj)
            test-ctx (assoc ctx :current-env env)
            result (interp/eval-node test-ctx {:type :call
                                                :target "c1"
                                                :method "f"
                                                :args []
                                                :has-parens true})]
        (is (= 42 result))))))

(deftest interpreter-zero-arg-method-invoke-test
  (testing "Interpreter: c1.a (zero-arg method) invokes and returns value"
    (let [code "class C1
  feature
    a: Integer do result := 10 end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (let [c1-obj (interp/make-object "C1" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "c1" c1-obj)
            test-ctx (assoc ctx :current-env env)
            result (interp/eval-node test-ctx {:type :call
                                                :target "c1"
                                                :method "a"
                                                :args []})]
        (is (= 10 result))))))
