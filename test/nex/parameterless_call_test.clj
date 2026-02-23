(ns nex.parameterless-call-test
  "Tests for parameterless method calls without parentheses"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.generator.java :as java]
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

(deftest parameterless-call-java-generation-test
  (testing "Generate Java code for parameterless call without parentheses"
    (let [code "class Test
  feature
    show() do
      print(42)
    end

    demo() do
      show
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public void show()"))
      (is (str/includes? java-code "public void demo()"))
      (is (str/includes? java-code "show();")))))

(deftest object-parameterless-call-java-generation-test
  (testing "Generate Java code for object method call without parentheses"
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
          java-code (java/translate code)]
      (is (str/includes? java-code "p.show();")))))

(deftest mixed-calls-java-generation-test
  (testing "Generate Java code for mixed method calls"
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
          java-code (java/translate code)]
      (is (str/includes? java-code "show();"))
      (is (str/includes? java-code "greet(\"Alice\");")))))

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

(deftest user-example-java-generation-test
  (testing "Generate Java for user's example"
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
          java-code (java/translate code)]
      (is (str/includes? java-code "public static Point make(int newx, int newy)"))
      (is (str/includes? java-code "public void show()"))
      (is (str/includes? java-code "Point p = Point.make(10, 20);"))
      (is (str/includes? java-code "p.show();")))))

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

(deftest parameterless-with-contracts-test
  (testing "Parameterless method with contracts"
    (let [code "class Account
  feature
    balance: Integer

    validate()
      require
        positive: balance >= 0
      do
        print(balance)
      ensure
        still_positive: balance >= 0
      end

    demo() do
      validate
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public void validate()"))
      (is (str/includes? java-code "validate();"))
      (is (str/includes? java-code "assert (balance >= 0)")))))

(deftest io-print-example-test
  (testing "io.print style call (object.method without parens)"
    (let [code "class IO
  feature
    print(msg: String) do
      print(msg)
    end
end

class Main
  feature
    demo() do
      let io: IO := create IO
      io.print(\"Hello\")
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "io.print(\"Hello\");")))))

(deftest bare-method-in-expression-test
  (testing "Bare method name in expression context resolves to method call"
    (let [code "class C1
  feature
    a: Integer do result := 10 end
    b(x: Integer): Integer do result := a + x end
end"
          java-code (java/translate code)]
      ;; 'a' in expression context should generate 'a()' since it's a method
      (is (str/includes? java-code "a() + x")))))

(deftest function-field-access-test
  (testing "Function field in expression returns Function object (no call)"
    (let [code "class C1
  feature
    f: Function
    a: Integer do result := 10 end
    b(x: Integer): Integer do result := a + x end
end"
          java-code (java/translate code)]
      ;; 'a' is a method -> a(), 'f' is a Function field -> bare f
      (is (str/includes? java-code "a() + x")))))

(deftest parameter-shadows-method-test
  (testing "Parameter shadows method of same name"
    (let [code "class C1
  feature
    a: Integer do result := 10 end
    b(a: Integer): Integer do result := a + 1 end
end"
          java-code (java/translate code)]
      ;; 'a' is a parameter, so it should be bare 'a' not 'a()'
      (is (str/includes? java-code "a + 1")))))

(deftest inherited-method-in-expression-test
  (testing "Inherited method in expression context"
    (let [code "class A
  feature
    x: Integer
    get_x: Integer do result := x end
end

class B
  inherit A
  feature
    compute: Integer do result := get_x + 1 end
end"
          java-code (java/translate code)]
      ;; get_x is inherited via delegation, should generate get_x() in expression
      (is (str/includes? java-code "get_x() + 1")))))

(deftest inherited-field-in-expression-test
  (testing "Inherited field in expression context uses parent delegation"
    (let [code "class A
  feature
    x: Integer
end

class B
  inherit A
  feature
    compute: Integer do result := x + 1 end
end"
          java-code (java/translate code)]
      ;; x is a parent field, should use _parent_A.x
      (is (str/includes? java-code "_parent_A.x + 1")))))

;; ===== Interpreter tests =====

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
