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
  (testing "Class with single inheritance uses composition"
    (let [nex-code "class Animal
  feature
    speak() do
      print(\"Hello\")
    end
end

class Dog inherit Animal
feature
  bark() do
    print(\"Woof\")
  end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "public class Animal"))
      (is (str/includes? java-code "public class Dog {"))
      (is (not (str/includes? java-code "extends")))
      (is (str/includes? java-code "private Animal _parent_Animal"))
      ;; Delegation for speak()
      (is (str/includes? java-code "_parent_Animal.speak()")))))

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
  (testing "Class with multiple inheritance uses composition"
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

class C inherit A, B
feature
  c() do
    print(\"C\")
  end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "public class C {"))
      (is (not (str/includes? java-code "extends")))
      (is (not (str/includes? java-code "implements")))
      (is (str/includes? java-code "private A _parent_A"))
      (is (str/includes? java-code "private B _parent_B"))
      ;; Delegation methods
      (is (str/includes? java-code "_parent_A.a()"))
      (is (str/includes? java-code "_parent_B.b()")))))

(deftest parent-qualified-call-test
  (testing "Parent-qualified method call via A.show generates _parent_A.show()"
    (let [nex-code "class A
  feature
    x: Integer
    show() do
      print(x)
    end
end

class B inherit A
feature
  y: Integer
  show() do
    A.show
    print(y)
  end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "_parent_A.show()")))))

(deftest parent-constructor-call-test
  (testing "Parent constructor call A.make_A(x) generates _parent_A = A.make_A(x)"
    (let [nex-code "class A
  feature
    x: Integer
  create
    make_A(x: Integer) do
      this.x := x
    end
end

class B inherit A
feature
  y: Integer
create
  make_B(x, y: Integer) do
    A.make_A(x)
    this.y := y
  end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "_parent_A = A.make_A(x)")))))

(deftest inherited-field-in-expression-test
  (testing "Inherited field x from parent A is routed through _parent_A in method body"
    (let [nex-code "class A
  feature
    x: Integer
end

class B inherit A
feature
  y: Integer
  add(): Integer do
    result := x + y
  end
end"
          java-code (java/translate nex-code)]
      ;; x should be resolved as _parent_A.x
      (is (str/includes? java-code "_parent_A.x"))
      ;; y should remain bare (own field)
      (is (re-find #"\(_parent_A\.x \+ y\)" java-code)))))

(deftest inherited-field-member-assign-test
  (testing "this.x := val in constructor routes through _parent_A when x is inherited"
    (let [nex-code "class A
  feature
    x: Integer
end

class B inherit A
feature
  y: Integer
create
  make_B(x, y: Integer) do
    this.x := x
    this.y := y
  end
end"
          java-code (java/translate nex-code)]
      ;; In B's constructor, this.x should route through _parent_A
      (is (str/includes? java-code "._parent_A.x = x"))
      ;; y is own field, should stay as-is
      (is (str/includes? java-code ".y = y")))))

(deftest parameter-shadows-parent-field-test
  (testing "Method parameter x shadows parent field x"
    (let [nex-code "class A
  feature
    x: Integer
end

class B inherit A
feature
  y: Integer
  set_both(x, y: Integer) do
    this.x := x
    this.y := y
  end
end"
          java-code (java/translate nex-code)]
      ;; In B.set_both, param x shadows parent field x
      ;; The RHS x should be the param (bare), not _parent_A.x
      ;; The LHS this.x should route through _parent_A
      (is (str/includes? java-code "._parent_A.x = x"))
      (is (str/includes? java-code ".y = y")))))

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
    (is (= "double" (java/nex-type-to-java "Real")))
    (is (= "java.math.BigDecimal" (java/nex-type-to-java "Decimal")))
    (is (= "char" (java/nex-type-to-java "Char")))
    (is (= "boolean" (java/nex-type-to-java "Boolean")))
    (is (= "String" (java/nex-type-to-java "String")))
    (is (= "NexImage" (java/nex-type-to-java "Image")))))

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
      let g: Integer := 2 ^ 3
    end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "(1 + 2)"))
      (is (str/includes? java-code "(3 - 4)"))
      (is (str/includes? java-code "(5 * 6)"))
      (is (str/includes? java-code "(7 / 8)"))
      (is (str/includes? java-code "(9 > 10)"))
      (is (str/includes? java-code "(11 < 12)"))
      (is (str/includes? java-code "(Math.pow(2, 3))")))))

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
          (is (contains? (:files result) "NexImage.java"))
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

(deftest string-conversion-methods-java-generation-test
  (testing "String conversion methods map to Java numeric parsing APIs"
    (let [nex-code "class Test
  feature
    convert() do
      let i: Integer := \"123\".to_integer()
      let i64: Integer64 := \"123\".to_integer64()
      let r: Real := \"3.14\".to_real()
      let d: Decimal := \"42.5\".to_decimal()
    end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "Integer.parseInt(\"123\".trim())"))
      (is (str/includes? java-code "Long.parseLong(\"123\".trim())"))
      (is (str/includes? java-code "Double.parseDouble(\"3.14\".trim())"))
          (is (str/includes? java-code "new java.math.BigDecimal(\"42.5\".trim())")))))

(deftest image-create-and-methods-java-generation-test
  (testing "Image create/from_file and width/height methods are supported in Java generator"
    (let [nex-code "class ImgDemo
  feature
    demo() do
      let img: Image := create Image.from_file(\"sprite.png\")
      let w: Integer := img.width()
      let h: Integer := img.height()
    end
end"
          java-code (java/translate nex-code)]
      (is (str/includes? java-code "NexImage.from_file(\"sprite.png\")"))
      (is (str/includes? java-code "img.width()"))
      (is (str/includes? java-code "img.height()")))))

(deftest void-safety-enforced-in-java-generator-test
  (testing "Java generator should fail type-checking for uninitialized attachable fields"
    (let [nex-code "class A
  feature
    show() do
      print(\"A\")
    end
end
class B
  feature
    a: A
    show() do
      a.show()
    end
end"]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Type checking failed"
            (java/translate nex-code))))))
