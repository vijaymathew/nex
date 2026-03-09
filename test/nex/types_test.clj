(ns nex.types-test
  "Tests for Nex basic types and default initialization"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.generator.java :as java]))

(deftest all-basic-types-parsing-test
  (testing "Parse class with all basic types"
    (let [code "class TypeDemo
  feature
    i: Integer
    i64: Integer64
    r: Real
    d: Decimal
    c: Char
    b: Boolean
    s: String
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          feature-section (first (:body class-def))
          fields (:members feature-section)]
      (is (= 7 (count fields)))
      (is (= "Integer" (:field-type (nth fields 0))))
      (is (= "Integer64" (:field-type (nth fields 1))))
      (is (= "Real" (:field-type (nth fields 2))))
      (is (= "Decimal" (:field-type (nth fields 3))))
      (is (= "Char" (:field-type (nth fields 4))))
      (is (= "Boolean" (:field-type (nth fields 5))))
      (is (= "String" (:field-type (nth fields 6)))))))

(deftest integer-type-java-generation-test
  (testing "Generate Java code for Integer type with default value"
    (let [code "class Test
  feature
    count: Integer
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public int count = 0;")))))

(deftest integer64-type-java-generation-test
  (testing "Generate Java code for Integer64 type with default value"
    (let [code "class Test
  feature
    big_count: Integer64
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public long big_count = 0L;")))))

(deftest real-type-java-generation-test
  (testing "Generate Java code for Real type with default value"
    (let [code "class Test
  feature
    temperature: Real
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public double temperature = 0.0;")))))

(deftest decimal-type-java-generation-test
  (testing "Generate Java code for Decimal type with default value"
    (let [code "class Test
  feature
    price: Decimal
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public java.math.BigDecimal price = java.math.BigDecimal.ZERO;")))))

(deftest char-type-java-generation-test
  (testing "Generate Java code for Char type with default value"
    (let [code "class Test
  feature
    initial: Char
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public char initial = '\\0';")))))

(deftest boolean-type-java-generation-test
  (testing "Generate Java code for Boolean type with default value"
    (let [code "class Test
  feature
    active: Boolean
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public boolean active = false;")))))

(deftest string-type-java-generation-test
  (testing "Generate Java code for String type with default value"
    (let [code "class Test
  feature
    name: String
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public String name = \"\";")))))

(deftest detachable-type-parsing-test
  (testing "Parse detachable type annotation '?A'"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end
class B
  feature
    a: ?A
end"
          ast (p/ast code)
          class-def (second (:classes ast))
          field (-> class-def :body first :members first)]
      (is (= {:base-type "A" :detachable true}
             (:field-type field))))))

(deftest all-types-default-values-test
  (testing "Generate Java with all types and their default values"
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

(deftest method-with-typed-parameters-test
  (testing "Methods with all basic types as parameters"
    (let [code "class Calculator
  feature
    process(i: Integer, i64: Integer64, r: Real, d: Decimal, c: Char, b: Boolean, s: String) do
      print(i)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public void process(int i, long i64, double r, java.math.BigDecimal d, char c, boolean b, String s)")))))

(deftest typed-let-with-new-types-test
  (testing "Typed let with new type annotations"
    (let [code "class Test
  feature
    demo() do
      let x: Integer64 := 100
      let y: Decimal := 3.14
      print(x)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "long x = 100;"))
      (is (str/includes? java-code "java.math.BigDecimal y = 3.14;")))))

(deftest mixed-types-with-methods-test
  (testing "Class with mixed field types and methods"
    (let [code "class Account
  feature
    balance: Decimal
    transactions: Integer64
    active: Boolean
    owner: String

    deposit(amount: Decimal) do
      balance := balance + amount
      let transactions: Integer64 := transactions + 1
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public java.math.BigDecimal balance = java.math.BigDecimal.ZERO;"))
      (is (str/includes? java-code "public long transactions = 0L;"))
      (is (str/includes? java-code "public boolean active = false;"))
      (is (str/includes? java-code "public String owner = \"\";"))
      (is (str/includes? java-code "public void deposit(java.math.BigDecimal amount)")))))

(deftest char-type-parsing-test
  (testing "Char type is recognized as a keyword"
    (let [code "class Test
  feature
    letter: Char
end"
          ast (p/ast code)]
      (is (some? ast))
      (let [class-def (first (:classes ast))
            feature-section (first (:body class-def))
            field (first (:members feature-section))]
        (is (= "Char" (:field-type field)))))))

(deftest type-keywords-not-identifiers-test
  (testing "Type keywords should be recognized as types, not identifiers"
    (let [types ["Integer" "Integer64" "Real" "Decimal" "Char" "Boolean" "String"]]
      (doseq [t types]
        (let [code (str "class Test feature x: " t " end")
              ast (p/ast code)]
          (is (some? ast) (str "Failed to parse type: " t)))))))

(deftest convert-and-to-not-identifiers-test
  (testing "'convert' and 'to' are reserved keywords, not identifiers"
    (is (thrown? Exception (p/ast "let convert := 1")))
    (is (thrown? Exception (p/ast "let to := 1")))
    (is (thrown? Exception (p/ast "class convert end")))
    (is (thrown? Exception (p/ast "class to end")))))

(deftest real-vs-decimal-distinction-test
  (testing "Real (double) vs Decimal (BigDecimal) are distinct Java types"
    (let [code "class Test
  feature
    f: Real
    d: Decimal
end"
          java-code (java/translate code)]
      ;; Real maps to double, Decimal maps to BigDecimal
      (is (str/includes? java-code "public double f = 0.0;"))
      (is (str/includes? java-code "public java.math.BigDecimal d = java.math.BigDecimal.ZERO;")))))

(deftest integer-vs-integer64-distinction-test
  (testing "Integer (32-bit) vs Integer64 (64-bit) are distinct types"
    (let [code "class Test
  feature
    i: Integer
    big: Integer64
end"
          java-code (java/translate code)]
      ;; Integer maps to int, Integer64 maps to long
      (is (str/includes? java-code "public int i = 0;"))
      (is (str/includes? java-code "public long big = 0L;")))))

(deftest default-initialization-with-visibility-test
  (testing "Default values work with visibility modifiers"
    (let [code "class Test
  private feature
    secret: Integer
  -> Friend feature
    shared: Decimal
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "private int secret = 0;"))
      (is (str/includes? java-code "public java.math.BigDecimal shared = java.math.BigDecimal.ZERO;")))))
