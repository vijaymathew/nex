(ns nex.generator.javascript_test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nex.generator.javascript :as js]
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
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "class Person"))
      (is (str/includes? js-code "this.name = \"\""))
      (is (str/includes? js-code "this.age = 0")))))

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
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "static make(x, y)"))
      (is (str/includes? js-code "this.x")))))

(deftest inheritance-test
  (testing "Class with single inheritance"
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
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "class Animal"))
      (is (str/includes? js-code "class Dog extends Animal")))))

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
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "throw new Error"))
      (is (str/includes? js-code "Precondition"))
      (is (str/includes? js-code "Postcondition")))))

(deftest nil-literal-test
  (testing "Nil literal translation"
    (let [nex-code "class Test
  feature
    demo() do
      print(nil)
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "console.log(null)")))))

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
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "if ((a > b))"))
      (is (str/includes? js-code "} else {")))))

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
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "let i = 1"))
      (is (str/includes? js-code "while"))
      (is (str/includes? js-code "!((i > n))")))))

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
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "let x = 10"))
      (is (str/includes? js-code "{"))
      (is (str/includes? js-code "let x = 20")))))

(deftest type-mapping-test
  (testing "Type mapping from Nex to JavaScript"
    (is (= "number" (js/nex-type-to-js "Integer")))
    (is (= "number" (js/nex-type-to-js "Integer64")))
    (is (= "number" (js/nex-type-to-js "Real")))
    (is (= "number" (js/nex-type-to-js "Decimal")))
    (is (= "string" (js/nex-type-to-js "Char")))
    (is (= "boolean" (js/nex-type-to-js "Boolean")))
    (is (= "string" (js/nex-type-to-js "String")))))

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
      let g: Boolean := true and false
      let h: Boolean := true or false
      let g: Integer := 2 ^ 3
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "(1 + 2)"))
      (is (str/includes? js-code "(3 - 4)"))
      (is (str/includes? js-code "(5 * 6)"))
      (is (str/includes? js-code "(7 / 8)"))
      (is (str/includes? js-code "(9 > 10)"))
      (is (str/includes? js-code "(11 < 12)"))
      (is (str/includes? js-code "&&"))
      (is (str/includes? js-code "||"))
      (is (str/includes? js-code "(2 ** 3)")))))

(deftest equality-operators-test
  (testing "Equality operator translation to ==="
    (let [nex-code "class Test
  feature
    test(a, b: Integer) do
      let x: Boolean := a = b
      let y: Boolean := a /= b
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "==="))
      (is (str/includes? js-code "!==")))))

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
          js-with-contracts (js/translate nex-code)
          js-without-contracts (js/translate nex-code {:skip-contracts true})]
      ;; With contracts should include error throws
      (is (str/includes? js-with-contracts "throw new Error"))
      (is (str/includes? js-with-contracts "Precondition"))
      (is (str/includes? js-with-contracts "Postcondition"))
      (is (str/includes? js-with-contracts "// Class invariant:"))
      ;; Without contracts should not include error throws
      (is (not (str/includes? js-without-contracts "throw new Error")))
      (is (not (str/includes? js-without-contracts "Precondition")))
      (is (not (str/includes? js-without-contracts "Postcondition")))
      (is (not (str/includes? js-without-contracts "// Class invariant:"))))))

(deftest generic-class-test
  (testing "Generic class with type parameter"
    (let [nex-code "class List [G]
  feature
    items: Array [G]
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "@template G"))
      (is (str/includes? js-code "class List")))))

(deftest constrained-generic-test
  (testing "Generic class with constrained type parameter"
    (let [nex-code "class Sorted_List [G -> Comparable]
  feature
    items: Array [G]
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "@template G extends Comparable"))
      (is (str/includes? js-code "class Sorted_List")))))

(deftest multiple-generics-test
  (testing "Generic class with multiple type parameters"
    (let [nex-code "class Pair [K, V]
  feature
    key: K
    value: V
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "@template K, V"))
      (is (str/includes? js-code "class Pair")))))

(deftest array-type-test
  (testing "Array type declaration"
    (let [nex-code "class Container
  feature
    items: Array [String]
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "this.items = []")))))

(deftest map-type-test
  (testing "Map type declaration"
    (let [nex-code "class Store
  feature
    data: Map [String, Integer]
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "this.data = new Map()")))))

(deftest array-literal-test
  (testing "Array literal translation"
    (let [nex-code "class Demo
  feature
    demo() do
      let nums: Array[Integer] := [1, 2, 3]
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "[1, 2, 3]")))))

(deftest map-literal-test
  (testing "Map literal translation"
    (let [nex-code "class Demo
  feature
    demo() do
      let m: Map[String, Integer] := {\"a\": 1, \"b\": 2}
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "new Map("))
      (is (str/includes? js-code "[\"a\", 1]"))
      (is (str/includes? js-code "[\"b\", 2]")))))

(deftest subscript-access-test
  (testing "Subscript access for arrays and maps"
    (let [nex-code "class Test
  feature
    items: Array [Integer]

    demo() do
      let x: Integer := items[0]
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "items.get")))))

(deftest create-expression-test
  (testing "Create expression translation"
    (let [nex-code "class Point
  create
    make(x, y: Integer) do
      print(x)
    end
  feature
    x: Integer

  feature
    demo() do
      let p: Point := create Point.make(10, 20)
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "Point.make(10, 20)")))))

(deftest graphics-create-expression-test
  (testing "Window/Turtle create expressions are supported in JS generator"
    (let [nex-code "class Draw
  feature
    demo() do
      let win: Window := create Window.with_title(\"Demo\", 640, 480)
      let t: Turtle := create Turtle.on_window(win)
      t.forward(10.0)
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "class NexWindow"))
      (is (str/includes? js-code "class NexTurtle"))
      (is (str/includes? js-code "new NexWindow(\"Demo\", 640, 480)"))
      (is (str/includes? js-code "new NexTurtle(win)")))))

(deftest parameterless-call-test
  (testing "Parameterless method call"
    (let [nex-code "class Point
  feature
    show() do
      print(\"point\")
    end

    demo() do
      show
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "show()")))))

(deftest private-visibility-test
  (testing "Private feature members use underscore prefix"
    (let [nex-code "class Account
  private feature
    balance: Integer

    get_balance() do
      print(balance)
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "this._balance"))
      (is (str/includes? js-code "_get_balance()")))))

(deftest jsdoc-test
  (testing "JSDoc comments for methods with parameters"
    (let [nex-code "class Calculator
  feature
    add(a, b: Integer) do
      print(a + b)
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "@param {number} a"))
      (is (str/includes? js-code "@param {number} b")))))

(deftest default-values-test
  (testing "Default values for different types"
    (is (= "0" (js/default-value "Integer")))
    (is (= "0" (js/default-value "Integer64")))
    (is (= "0.0" (js/default-value "Real")))
    (is (= "0.0" (js/default-value "Decimal")))
    (is (= "false" (js/default-value "Boolean")))
    (is (= "\"\"" (js/default-value "String")))
    (is (= "[]" (js/default-value "Array")))
    (is (= "new Map()" (js/default-value "Map")))))

(deftest nested-arrays-test
  (testing "Nested array types (matrix)"
    (let [nex-code "class Matrix
  feature
    data: Array [Array [Integer]]

  create
    make() do
      data := [[1, 2], [3, 4]]
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "[[1, 2], [3, 4]]")))))

(deftest map-of-arrays-test
  (testing "Map with array values"
    (let [nex-code "class Categories
  feature
    items: Map [String, Array [String]]

  create
    make() do
      items := {\"fruits\": [\"apple\", \"banana\"]}
    end
end"
          js-code (js/translate nex-code)]
      (is (str/includes? js-code "new Map("))
      (is (str/includes? js-code "[\"apple\", \"banana\"]")))))

(deftest generate-main-default-constructor-test
  (testing "Main with no constructors uses new ClassName()"
    (let [nex-code "class App
  feature
    run() do
      print(\"hello\")
    end
end"
          ast (p/ast nex-code)
          main-code (js/generate-main ast)]
      (is (str/includes? main-code "require('./App')"))
      (is (str/includes? main-code "new App()")))))

(deftest generate-main-named-constructor-test
  (testing "Main with no-arg constructor uses ClassName.ctorName()"
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
          main-code (js/generate-main ast)]
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
          main-code (js/generate-main ast)]
      (is (str/includes? main-code "App.default()"))
      (is (not (str/includes? main-code "App.other()"))))))

(deftest translate-file-creates-separate-files-test
  (testing "translate-file writes separate files to output directory"
    (let [tmp-dir (io/file (System/getProperty "java.io.tmpdir") "nex-js-test")
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
              files (js/translate-file (.getPath nex-file) (.getPath out-dir) {})]
          (is (contains? files "Function.js"))
          (is (contains? files "NexWindow.js"))
          (is (contains? files "Greeter.js"))
          (is (contains? files "main.js"))
          (is (.exists (io/file out-dir "Function.js")))
          (is (.exists (io/file out-dir "NexWindow.js")))
          (is (.exists (io/file out-dir "Greeter.js")))
          (is (.exists (io/file out-dir "main.js")))
          (is (str/includes? (get files "main.js") "Greeter.make()")))
        (finally
          (doseq [f (reverse (file-seq tmp-dir))]
            (.delete f)))))))
