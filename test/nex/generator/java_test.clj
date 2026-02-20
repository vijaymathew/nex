(ns nex.generator.java-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nex.generator.java :as java]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [nex.parser :as p]))

(deftest simple-class-test
  (testing "Simple class with fields and methods"
    (let [nex-code "class Person
  feature
    name: String
    age: Integer
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "public class Person"))
      (is (str/includes? java-code "public String name = \"\""))
      (is (str/includes? java-code "public int age = 0")))))

(deftest constructor-test
  (testing "Class with constructor"
    (let [nex-code "class Point
  create
    make(x, y: Integer) do
      let x: Integer := x
    end
  feature
    x: Integer
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "public static Point make(int x, int y)"))
      (is (str/includes? java-code "public int x")))))

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

(deftest nil-literal-test
  (testing "Nil literal translation"
    (let [nex-code "class Test
  feature
    demo() do
      print(nil)
    end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "System.out.print(null")))))

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
        let i: Integer := 1
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
      let x: Integer := 10
      do
        let x: Integer := 20
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
      let a: Integer := 1 + 2
      let b: Integer := 3 - 4
      let c: Integer := 5 * 6
      let d: Integer := 7 / 8
      let e: Boolean := 9 > 10
      let f: Boolean := 11 < 12
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
        let balance: Integer := balance + amount
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

(deftest generate-main-default-constructor-test
  (testing "Main class with no constructors uses new ClassName()"
    (let [nex-code "class App
  feature
    run() do
      print(\"hello\")
    end
end"
          ast (p/ast nex-code)
          main-code (java/generate-main ast)]
      (is (str/includes? main-code "public class Main"))
      (is (str/includes? main-code "public static void main(String[] args)"))
      (is (str/includes? main-code "new App()")))))

(deftest generate-main-named-constructor-test
  (testing "Main class with no-arg constructor uses ClassName.ctorName()"
    (let [nex-code "class App
  create
    make() do
      print(\"init\")
    end
  feature
    run() do
      print(\"hello\")
    end
end"
          ast (p/ast nex-code)
          main-code (java/generate-main ast)]
      (is (str/includes? main-code "App.make()"))
      (is (not (str/includes? main-code "new App()"))))))

(deftest generate-main-picks-first-noarg-test
  (testing "Main picks the first no-arg constructor when multiple exist"
    (let [nex-code "class App
  create
    from_config(path: String) do
      print(path)
    end
    default() do
      print(\"default\")
    end
    other() do
      print(\"other\")
    end
  feature
    run() do
      print(\"hello\")
    end
end"
          ast (p/ast nex-code)
          main-code (java/generate-main ast)]
      (is (str/includes? main-code "App.default()"))
      (is (not (str/includes? main-code "App.other()"))))))

(deftest translate-file-compiles-jar-test
  (testing "translate-file compiles .java files into a runnable JAR and cleans up"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-java-test")
          nex-file (io/file tmp-dir "test.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class Greeter
  create
    make() do
      print(\"hi\")
    end
  feature
    greet() do
      print(\"hello\")
    end
end")
        (let [out-dir (io/file tmp-dir "out")
              result (java/translate-file (.getPath nex-file) (.getPath out-dir) {})]
          ;; Returns :files map and :jar path
          (is (contains? (:files result) "Function.java"))
          (is (contains? (:files result) "Greeter.java"))
          (is (contains? (:files result) "Main.java"))
          (is (str/includes? (get (:files result) "Main.java") "Greeter.make()"))
          ;; JAR exists
          (is (.exists (io/file (:jar result))))
          (is (str/ends-with? (:jar result) ".jar"))
          ;; .java and .class files are cleaned up
          (is (empty? (filter #(str/ends-with? (.getName %) ".java")
                              (.listFiles out-dir))))
          (is (empty? (filter #(str/ends-with? (.getName %) ".class")
                              (.listFiles out-dir)))))
        (finally
          (doseq [f (reverse (file-seq tmp-dir))]
            (.delete f)))))))

(deftest translate-file-jar-is-runnable-test
  (testing "The produced JAR can be executed with java -jar"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-java-run-test")
          nex-file (io/file tmp-dir "app.nex")]
      (try
        (.mkdirs tmp-dir)
        (spit nex-file "class App
  create
    make() do
      print(\"hello from nex\")
    end
  feature
    greet() do
      print(\"hi\")
    end
end")
        (let [out-dir (io/file tmp-dir "out")
              result (java/translate-file (.getPath nex-file) (.getPath out-dir) {})
              proc (.exec (Runtime/getRuntime)
                          (into-array String ["java" "-jar" (:jar result)]))]
          (.waitFor proc)
          (let [output (slurp (.getInputStream proc))]
            (is (= 0 (.exitValue proc)))
            (is (str/includes? output "hello from nex"))))
        (finally
          (doseq [f (reverse (file-seq tmp-dir))]
            (.delete f)))))))
