(ns nex.parameterless-call-test
  "Tests for parameterless method calls without parentheses"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.generator.java :as java]))

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

  constructors
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

  constructors
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
      (is (str/includes? java-code "public Point(int newx, int newy)"))
      (is (str/includes? java-code "public void show()"))
      (is (str/includes? java-code "Point p = new Point(10, 20);"))
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
