(ns nex.create-test
  "Tests for create keyword and object instantiation"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.generator.java :as java]
            [nex.interpreter :as interp]))

(deftest simple-create-parsing-test
  (testing "Parse simple create expression"
    (let [code "class Test
  feature
    x: Integer
end

class Main
  feature
    demo() do
      let obj: Test := create Test
      print(obj)
    end
end"
          ast (p/ast code)
          class-def (second (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body first)
          create-expr (:value let-stmt)]
      (is (= :create (:type create-expr)))
      (is (= "Test" (:class-name create-expr)))
      (is (nil? (:constructor create-expr)))
      (is (empty? (:args create-expr))))))

(deftest named-constructor-parsing-test
  (testing "Parse create with named constructor"
    (let [code "class Account
  create
    with_balance(bal: Integer) do
      let balance: Integer := bal
    end
  feature
    balance: Integer
end

class Main
  feature
    demo() do
      let acc: Account := create Account.with_balance(1000)
      print(acc)
    end
end"
          ast (p/ast code)
          class-def (second (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body first)
          create-expr (:value let-stmt)]
      (is (= :create (:type create-expr)))
      (is (= "Account" (:class-name create-expr)))
      (is (= "with_balance" (:constructor create-expr)))
      (is (= 1 (count (:args create-expr)))))))

(deftest simple-create-java-generation-test
  (testing "Generate Java code for simple create"
    (let [code "class Test
  feature
    x: Integer
end

class Main
  feature
    demo() do
      let obj: Test := create Test
      print(obj)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "Test obj = new Test();")))))

(deftest named-constructor-java-generation-test
  (testing "Generate Java code for create with named constructor"
    (let [code "class Account
  create
    with_balance(bal: Integer) do
      let balance: Integer := bal
    end
  feature
    balance: Integer
end

class Main
  feature
    demo() do
      let acc: Account := create Account.with_balance(1000)
      print(acc)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "Account acc = Account.with_balance(1000);")))))

(deftest default-initialization-ast-test
  (testing "Create expression AST structure"
    (let [code "class Point
  feature
    x: Integer
    y: Integer
end

class Main
  feature
    test() do
      let p: Point := create Point
      print(p)
    end
end"
          ast (p/ast code)]
      ;; Just verify AST was created correctly
      (is (= 2 (count (:classes ast)))))))

(deftest constructor-execution-ast-test
  (testing "Create with constructor AST structure"
    (let [code "class Counter
  create
    with_value(val: Integer) do
      let count: Integer := val
    end
  feature
    count: Integer
end

class Main
  feature
    test() do
      let c: Counter := create Counter.with_value(10)
    end
end"
          ast (p/ast code)]
      ;; Verify we have both classes
      (is (= 2 (count (:classes ast)))))))

(deftest multiple-parameters-constructor-java-test
  (testing "Java generation for multi-param constructor"
    (let [code "class Rectangle
  create
    make(w, h: Integer) do
      let width: Integer := w
      let height: Integer := h
    end
  feature
    width: Integer
    height: Integer
end

class Main
  feature
    test() do
      let r: Rectangle := create Rectangle.make(5, 10)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "Rectangle r = Rectangle.make(5, 10);")))))

(deftest create-with-all-types-test
  (testing "Create with all basic types"
    (let [code "class AllTypes
  feature
    i: Integer
    i64: Integer64
    r: Real
    d: Decimal
    c: Char
    b: Boolean
    s: String
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public int i = 0;"))
      (is (str/includes? java-code "public long i64 = 0L;"))
      (is (str/includes? java-code "public double r = 0.0;"))
      (is (str/includes? java-code "public java.math.BigDecimal d = java.math.BigDecimal.ZERO;"))
      (is (str/includes? java-code "public char c = '\\0';"))
      (is (str/includes? java-code "public boolean b = false;"))
      (is (str/includes? java-code "public String s = \"\";")))))

(deftest create-without-type-annotation-java-test
  (testing "Java generation without type annotation"
    (let [code "class Test
  feature
    x: Integer
end

class Main
  feature
    demo() do
      let obj := create Test
    end
end"
          java-code (java/translate code {:skip-type-check true})]
      ;; Without type annotation, uses simple assignment
      (is (str/includes? java-code "obj = new Test();")))))

(deftest create-with-contracts-java-test
  (testing "Java generation with constructor contracts"
    (let [code "class Account
  create
    with_balance(initial: Integer)
      require
        positive: initial >= 0
      do
        let balance: Integer := initial
      ensure
        set: balance = initial
      end
  feature
    balance: Integer
end

class Main
  feature
    test() do
      let acc: Account := create Account.with_balance(1000)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "Account acc = Account.with_balance(1000);"))
      (is (str/includes? java-code "assert (initial >= 0)")))))

(deftest create-contract-violation-parsing-test
  (testing "Parse create with potentially violating contract"
    (let [code "class Account
  create
    with_balance(initial: Integer)
      require
        positive: initial >= 0
      do
        let balance: Integer := initial
      end
  feature
    balance: Integer
end

class Main
  feature
    test() do
      let acc: Account := create Account.with_balance(-100)
    end
end"
          ast (p/ast code)]
      ;; Just verify it parses correctly
      (is (= 2 (count (:classes ast)))))))

(deftest create-in-assignment-java-test
  (testing "Java generation for create in assignment"
    (let [code "class Test
  feature
    value: Integer
end

class Main
  feature
    demo() do
      let x: Test := create Test
      let y: Test := x
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "Test x = new Test();"))
      (is (str/includes? java-code "Test y = x;")))))

(deftest create-java-with-default-constructor-test
  (testing "Java generation without user-defined constructor"
    (let [code "class Simple
  feature
    x: Integer
    y: Decimal
end

class Main
  feature
    demo() do
      let obj: Simple := create Simple
    end
end"
          java-code (java/translate code)]
      ;; Should generate new Simple() call
      (is (str/includes? java-code "Simple obj = new Simple();")))))

(deftest create-multiple-objects-java-test
  (testing "Java generation for multiple create statements"
    (let [code "class Counter
  create
    with_value(val: Integer) do
      let count: Integer := val
    end
  feature
    count: Integer
end

class Main
  feature
    test() do
      let c1: Counter := create Counter.with_value(10)
      let c2: Counter := create Counter.with_value(20)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "Counter c1 = Counter.with_value(10);"))
      (is (str/includes? java-code "Counter c2 = Counter.with_value(20);")))))
