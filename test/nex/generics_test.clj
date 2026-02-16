(ns nex.generics-test
  "Tests for generic types (parameterized classes)"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.generator.java :as java]))

(deftest simple-generic-class-parsing-test
  (testing "Parse simple generic class"
    (let [code "class List [G]
  feature
    item: G
end"
          ast (p/ast code)
          class-def (first (:classes ast))]
      (is (= "List" (:name class-def)))
      (is (= 1 (count (:generic-params class-def))))
      (is (= "G" (-> class-def :generic-params first :name)))
      (is (nil? (-> class-def :generic-params first :constraint))))))

(deftest constrained-generic-parsing-test
  (testing "Parse generic class with constraint"
    (let [code "class Sorted_List [G -> Comparable]
  feature
    item: G
end"
          ast (p/ast code)
          class-def (first (:classes ast))]
      (is (= "Sorted_List" (:name class-def)))
      (is (= 1 (count (:generic-params class-def))))
      (is (= "G" (-> class-def :generic-params first :name)))
      (is (= "Comparable" (-> class-def :generic-params first :constraint))))))

(deftest multiple-generic-params-parsing-test
  (testing "Parse generic class with multiple parameters"
    (let [code "class Hash_Table [G, KEY -> Hashable]
  feature
    value: G
    key: KEY
end"
          ast (p/ast code)
          class-def (first (:classes ast))]
      (is (= "Hash_Table" (:name class-def)))
      (is (= 2 (count (:generic-params class-def))))
      (is (= "G" (-> class-def :generic-params first :name)))
      (is (nil? (-> class-def :generic-params first :constraint)))
      (is (= "KEY" (-> class-def :generic-params second :name)))
      (is (= "Hashable" (-> class-def :generic-params second :constraint))))))

(deftest parameterized-type-usage-parsing-test
  (testing "Parse parameterized type usage"
    (let [code "class Container
  feature
    cats: List [Cat]
    numbers: List [Integer]
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          fields (-> class-def :body first :members)]
      (is (= 2 (count fields)))
      ;; Check cats field
      (let [cats-field (first fields)]
        (is (= "cats" (:name cats-field)))
        (is (map? (:field-type cats-field)))
        (is (= "List" (-> cats-field :field-type :base-type)))
        (is (= ["Cat"] (-> cats-field :field-type :type-args))))
      ;; Check numbers field
      (let [numbers-field (second fields)]
        (is (= "numbers" (:name numbers-field)))
        (is (map? (:field-type numbers-field)))
        (is (= "List" (-> numbers-field :field-type :base-type)))
        (is (= ["Integer"] (-> numbers-field :field-type :type-args)))))))

(deftest simple-generic-java-generation-test
  (testing "Generate Java code for simple generic class"
    (let [code "class List [G]
  feature
    item: G
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public class List<G>"))
      (is (str/includes? java-code "private G item = null;")))))

(deftest constrained-generic-java-generation-test
  (testing "Generate Java code for constrained generic"
    (let [code "class Sorted_List [G -> Comparable]
  feature
    item: G
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public class Sorted_List<G extends Comparable>"))
      (is (str/includes? java-code "private G item = null;")))))

(deftest multiple-generic-params-java-generation-test
  (testing "Generate Java code for multiple generic parameters"
    (let [code "class Hash_Table [G, KEY -> Hashable]
  feature
    value: G
    key: KEY
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public class Hash_Table<G, KEY extends Hashable>"))
      (is (str/includes? java-code "private G value = null;"))
      (is (str/includes? java-code "private KEY key = null;")))))

(deftest parameterized-type-usage-java-generation-test
  (testing "Generate Java code with parameterized type usage"
    (let [code "class Container
  feature
    cats: List [Cat]
    numbers: List [Integer]
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "private List<Cat> cats = null;"))
      (is (str/includes? java-code "private List<Integer> numbers = null;")))))

(deftest generic-method-parameter-test
  (testing "Generic type in method parameter"
    (let [code "class List [G]
  feature
    item: G
    put(new_item: G) do
      let item := new_item
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public class List<G>"))
      (is (str/includes? java-code "public void put(G new_item)")))))

(deftest generic-constructor-test
  (testing "Generic type in constructor"
    (let [code "class Box [T]
  constructors
    make(initial: T) do
      let value := initial
    end
  feature
    value: T
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public class Box<T>"))
      (is (str/includes? java-code "public Box(T initial)")))))

(deftest nested-generic-types-test
  (testing "Nested generic types"
    (let [code "class Container
  feature
    lists: List [List [Integer]]
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "private List<List<Integer>> lists = null;")))))

(deftest generic-with-basic-types-test
  (testing "Generic type instantiated with basic types"
    (let [code "class Wrapper
  feature
    int_list: List [Integer]
    real_list: List [Real]
    string_list: List [String]
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "private List<Integer> int_list = null;"))
      (is (str/includes? java-code "private List<Float> real_list = null;"))
      (is (str/includes? java-code "private List<String> string_list = null;")))))

(deftest multiple-constraints-test
  (testing "Multiple generic parameters with different constraints"
    (let [code "class Dictionary [K -> Comparable, V]
  feature
    key: K
    value: V
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public class Dictionary<K extends Comparable, V>"))
      (is (str/includes? java-code "private K key = null;"))
      (is (str/includes? java-code "private V value = null;")))))

(deftest generic-create-expression-test
  (testing "Create expression with parameterized type"
    (let [code "class Box [T]
  constructors
    make(val: T) do
      let value := val
    end
  feature
    value: T
end

class Main
  feature
    demo() do
      let box: Box [Integer] := create Box.make(42)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public class Box<T>"))
      (is (str/includes? java-code "Box<Integer> box = new Box(42);")))))

(deftest generic-class-with-methods-test
  (testing "Generic class with multiple methods"
    (let [code "class Stack [G]
  feature
    top: G

    push(item: G) do
      let top := item
    end

    pop() do
      print(top)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public class Stack<G>"))
      (is (str/includes? java-code "private G top = null;"))
      (is (str/includes? java-code "public void push(G item)")))))

(deftest generic-with-contracts-test
  (testing "Generic class with contracts"
    (let [code "class BoundedList [G]
  feature
    item: G

    put(new_item: G)
      require
        not_null: new_item /= 0
      do
        let item := new_item
      ensure
        stored: item = new_item
      end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public class BoundedList<G>"))
      (is (str/includes? java-code "public void put(G new_item)"))
      (is (str/includes? java-code "assert")))))

(deftest type-parameter-in-local-var-test
  (testing "Generic type parameter in local variable"
    (let [code "class Container [T]
  feature
    process(input: T) do
      let temp: T := input
      print(temp)
    end
end"
          java-code (java/translate code)]
      (is (str/includes? java-code "public class Container<T>"))
      (is (str/includes? java-code "T temp = input;")))))
