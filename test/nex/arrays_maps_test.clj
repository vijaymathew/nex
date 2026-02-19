(ns nex.arrays-maps-test
  "Tests for arrays and maps with parameterized types"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.generator.java :as java]))

;; ============================================================================
;; ARRAY TESTS
;; ============================================================================

(deftest array-type-declaration-test
  (testing "Parse array type declaration"
    (let [code "class Container
  feature
    items: Array [String]
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          field (-> class-def :body first :members first)]
      (is (= "items" (:name field)))
      (is (map? (:field-type field)))
      (is (= "Array" (-> field :field-type :base-type)))
      (is (= ["String"] (-> field :field-type :type-args))))))

(deftest array-default-value-test
  (testing "Array field gets default value []"
    (let [code "class Container
  feature
    items: Array [String]
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "private ArrayList<String> items = new ArrayList<>();")))))

(deftest array-literal-parsing-test
  (testing "Parse array literal"
    (let [code "class Test
  feature
    demo() do
      let arr: Array [Integer] := [1, 2, 3]
      print(arr)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body first)
          array-expr (:value let-stmt)]
      (is (= :array-literal (:type array-expr)))
      (is (= 3 (count (:elements array-expr)))))))

(deftest array-literal-java-generation-test
  (testing "Generate Java code for array literal"
    (let [code "class Test
  feature
    demo() do
      let arr: Array [Integer] := [1, 2, 3]
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "new ArrayList<>(Arrays.asList(1, 2, 3))")))))

(deftest array-access-parsing-test
  (testing "Parse array subscript access"
    (let [code "class Test
  feature
    demo() do
      let arr: Array [Integer] := [1, 2, 3]
      let x: Integer := arr[0]
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body second)
          subscript-expr (:value let-stmt)]
      (is (= :subscript (:type subscript-expr)))
      (is (= "arr" (-> subscript-expr :target :name)))
      (is (= 0 (-> subscript-expr :index :value))))))

(deftest array-access-java-generation-test
  (testing "Generate Java code for array access"
    (let [code "class Test
  feature
    items: Array [Integer]

    demo() do
      let x: Integer := items[0]
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "items.get(0)")))))

(deftest array-of-different-types-test
  (testing "Arrays with different element types"
    (let [code "class Container
  feature
    strings: Array [String]
    integers: Array [Integer]
    reals: Array [Real]
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "ArrayList<String> strings = new ArrayList<>();"))
      (is (str/includes? java-code "ArrayList<Integer> integers = new ArrayList<>();"))
      (is (str/includes? java-code "ArrayList<Float> reals = new ArrayList<>();")))))

(deftest nested-array-access-test
  (testing "Nested array access arr[i][j]"
    (let [code "class Test
  feature
    demo() do
      let matrix: Array [Array [Integer]] := create Array
      let x: Integer := matrix[0][1]
    end
end"
          ast (p/ast code)]
      (is (= 1 (count (:classes ast)))))))

;; ============================================================================
;; MAP TESTS
;; ============================================================================

(deftest map-type-declaration-test
  (testing "Parse map type declaration"
    (let [code "class Container
  feature
    data: Map [String, Integer]
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          field (-> class-def :body first :members first)]
      (is (= "data" (:name field)))
      (is (map? (:field-type field)))
      (is (= "Map" (-> field :field-type :base-type)))
      (is (= ["String" "Integer"] (-> field :field-type :type-args))))))

(deftest map-default-value-test
  (testing "Map field gets default value {}"
    (let [code "class Container
  feature
    data: Map [String, Integer]
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "private HashMap<String, Integer> data = new HashMap<>();")))))

(deftest map-literal-parsing-test
  (testing "Parse map literal"
    (let [code "class Test
  feature
    demo() do
      let m: Map [String, Integer] := {\"a\": 1, \"b\": 2}
      print(m)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body first)
          map-expr (:value let-stmt)]
      (is (= :map-literal (:type map-expr)))
      (is (= 2 (count (:entries map-expr)))))))

(deftest map-access-parsing-test
  (testing "Parse map subscript access"
    (let [code "class Test
  feature
    demo() do
      let m: Map [String, Integer] := {\"a\": 1, \"b\": 2}
      let x: Integer := m[\"a\"]
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body second)
          subscript-expr (:value let-stmt)]
      (is (= :subscript (:type subscript-expr)))
      (is (= "m" (-> subscript-expr :target :name))))))

(deftest map-access-java-generation-test
  (testing "Generate Java code for map access"
    (let [code "class Test
  feature
    data: Map [String, Integer]

    demo() do
      let x: Integer := data[\"key\"]
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "data.get(\"key\")")))))

(deftest map-with-different-types-test
  (testing "Maps with different key/value types"
    (let [code "class Container
  feature
    string_to_int: Map [String, Integer]
    int_to_string: Map [Integer, String]
    string_to_real: Map [String, Real]
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "HashMap<String, Integer> string_to_int"))
      (is (str/includes? java-code "HashMap<Integer, String> int_to_string"))
      (is (str/includes? java-code "HashMap<String, Float> string_to_real")))))

;; ============================================================================
;; COMBINED TESTS
;; ============================================================================

(deftest array-and-map-together-test
  (testing "Class with both arrays and maps"
    (let [code "class DataStore
  feature
    items: Array [String]
    lookup: Map [String, Integer]

    demo() do
      let x: String := items[0]
      let y: Integer := lookup[\"key\"]
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "ArrayList<String> items"))
      (is (str/includes? java-code "HashMap<String, Integer> lookup"))
      (is (str/includes? java-code "items.get(0)"))
      (is (str/includes? java-code "lookup.get(\"key\")")))))

(deftest empty-array-literal-test
  (testing "Empty array literal"
    (let [code "class Test
  feature
    demo() do
      let arr: Array [Integer] := []
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body first)
          array-expr (:value let-stmt)]
      (is (= :array-literal (:type array-expr)))
      (is (empty? (:elements array-expr))))))

(deftest empty-map-literal-test
  (testing "Empty map literal"
    (let [code "class Test
  feature
    demo() do
      let m: Map [String, Integer] := {}
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body first)
          map-expr (:value let-stmt)]
      (is (= :map-literal (:type map-expr)))
      (is (empty? (:entries map-expr))))))

(deftest array-of-arrays-test
  (testing "Array of arrays (nested arrays)"
    (let [code "class Matrix
  feature
    data: Array [Array [Integer]]
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "ArrayList<ArrayList<Integer>> data")))))

(deftest map-of-arrays-test
  (testing "Map with array values"
    (let [code "class Store
  feature
    categories: Map [String, Array [String]]
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "HashMap<String, ArrayList<String>> categories")))))

(deftest array-in-method-parameter-test
  (testing "Array type in method parameter"
    (let [code "class Test
  feature
    process(items: Array [Integer]) do
      let x: Integer := items[0]
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "void process(ArrayList<Integer> items)"))
      (is (str/includes? java-code "items.get(0)")))))

(deftest map-in-method-parameter-test
  (testing "Map type in method parameter"
    (let [code "class Test
  feature
    process(data: Map [String, Integer]) do
      let x: Integer := data[\"key\"]
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "void process(HashMap<String, Integer> data)"))
      (is (str/includes? java-code "data.get(\"key\")")))))

(deftest array-literal-with-strings-test
  (testing "Array literal with string elements"
    (let [code "class Test
  feature
    demo() do
      let names: Array [String] := [\"Alice\", \"Bob\", \"Charlie\"]
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "Arrays.asList(\"Alice\", \"Bob\", \"Charlie\")")))))

(deftest map-literal-with-identifiers-test
  (testing "Map literal with identifier keys"
    (let [code "class Test
  feature
    demo() do
      let m: Map [String, Any] := {name: \"Alice\", age: 30}
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body first)
          map-expr (:value let-stmt)]
      (is (= :map-literal (:type map-expr)))
      (is (= 2 (count (:entries map-expr)))))))

(deftest array-with-variable-index-test
  (testing "Array access with variable index"
    (let [code "class Test
  feature
    demo() do
      let arr: Array [Integer] := [1, 2, 3]
      let i: Integer := 1
      let x: Integer := arr[i]
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "arr.get(i)")))))

(deftest map-with-variable-key-test
  (testing "Map access with variable key"
    (let [code "class Test
  feature
    demo() do
      let m: Map [String, Integer] := {\"a\": 1, \"b\": 2}
      let key: String := \"a\"
      let x: Integer := m[key]
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "m.get(key)")))))
