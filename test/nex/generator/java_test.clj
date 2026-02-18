(ns nex.generator.java-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nex.generator.java :as java]
            [clojure.string :as str]))

(deftest simple-class-test
  (testing "Simple class with fields and methods"
    (let [nex-code "class Person
  feature
    name: String
    age: Integer
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "public class Person"))
      (is (str/includes? java-code "private String name = \"\""))
      (is (str/includes? java-code "private int age = 0")))))

(deftest constructor-test
  (testing "Class with constructor"
    (let [nex-code "class Point
  create
    make(x, y: Integer) do
      let x := x
    end
  feature
    x: Integer
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "public Point(int x, int y)"))
      (is (str/includes? java-code "private int x")))))

(deftest inheritance-test
  (testing "Class with single inheritance"
    (let [nex-code "class Animal
  feature
    speak() do
      print(\"Hello\")
    end
end

class Dog
inherit
  Animal
  end
feature
  bark() do
    print(\"Woof\")
  end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "public class Animal"))
      (is (str/includes? java-code "public class Dog extends Animal")))))

(deftest multiple-inheritance-test
  (testing "Class with multiple inheritance"
    (let [nex-code "class A
  feature
    a() do
      print(\"A\")
    end
end

class B
  feature
    b() do
      print(\"B\")
    end
end

class C
inherit
  A
  end,
  B
  end
feature
  c() do
    print(\"C\")
  end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "public class C extends A implements B")))))

(deftest contracts-test
  (testing "Methods with contracts"
    (let [nex-code "class Account
  feature
    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        print(amount)
      ensure
        done: amount > 0
      end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "assert"))
      (is (str/includes? java-code "Precondition"))
      (is (str/includes? java-code "Postcondition")))))

(deftest if-then-else-test
  (testing "If-then-else statement"
    (let [nex-code "class Test
  feature
    max(a, b: Integer) do
      if a > b then
        print(a)
      else
        print(b)
      end
    end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "if ((a > b))"))
      (is (str/includes? java-code "} else {")))))

(deftest loop-test
  (testing "Loop with from-until-do"
    (let [nex-code "class Test
  feature
    count(n: Integer) do
      from
        let i := 1
      until
        i > n
      do
        print(i)
        i := i + 1
      end
    end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "i = 1"))
      (is (str/includes? java-code "while"))
      (is (str/includes? java-code "!((i > n))")))))

(deftest scoped-block-test
  (testing "Scoped blocks"
    (let [nex-code "class Test
  feature
    demo() do
      let x := 10
      do
        let x := 20
      end
    end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "x = 10"))
      (is (str/includes? java-code "{"))
      (is (str/includes? java-code "x = 20")))))

(deftest type-mapping-test
  (testing "Type mapping from Nex to Java"
    (is (= "int" (java/nex-type-to-java "Integer")))
    (is (= "long" (java/nex-type-to-java "Integer64")))
    (is (= "float" (java/nex-type-to-java "Real")))
    (is (= "double" (java/nex-type-to-java "Decimal")))
    (is (= "char" (java/nex-type-to-java "Char")))
    (is (= "boolean" (java/nex-type-to-java "Boolean")))
    (is (= "String" (java/nex-type-to-java "String")))))

(deftest binary-operators-test
  (testing "Binary operator translation"
    (let [nex-code "class Test
  feature
    test() do
      let a := 1 + 2
      let b := 3 - 4
      let c := 5 * 6
      let d := 7 / 8
      let e := 9 > 10
      let f := 11 < 12
    end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "(1 + 2)"))
      (is (str/includes? java-code "(3 - 4)"))
      (is (str/includes? java-code "(5 * 6)"))
      (is (str/includes? java-code "(7 / 8)"))
      (is (str/includes? java-code "(9 > 10)"))
      (is (str/includes? java-code "(11 < 12)")))))

(deftest skip-contracts-option-test
  (testing "Skip contracts option for production builds"
    (let [nex-code "class Account
  feature
    balance: Integer

    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        let balance := balance + amount
      ensure
        increased: balance >= 0
      end

  invariant
    non_negative: balance >= 0
end"
          java-with-contracts (java/translate nex-code)
          java-without-contracts (java/translate nex-code {:skip-contracts true})]
      ;; With contracts should include assertions
      (is (str/includes? java-with-contracts "assert"))
      (is (str/includes? java-with-contracts "Precondition"))
      (is (str/includes? java-with-contracts "Postcondition"))
      (is (str/includes? java-with-contracts "// Class invariant:"))
      ;; Without contracts should not include assertions
      (is (not (str/includes? java-without-contracts "assert")))
      (is (not (str/includes? java-without-contracts "Precondition")))
      (is (not (str/includes? java-without-contracts "Postcondition")))
      (is (not (str/includes? java-without-contracts "// Class invariant:"))))))

