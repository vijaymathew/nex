(ns nex.arrays-maps-test
  "Tests for arrays and maps with parameterized types"
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]))

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

(deftest array-access-parsing-test
  (testing "Parse array get access"
    (let [code "class Test
  feature
    demo() do
      let arr: Array [Integer] := [1, 2, 3]
      let x: Integer := arr.get(0)
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body second)
          call-expr (:value let-stmt)]
      (is (= :call (:type call-expr)))
      (is (= "arr" (:target call-expr)))
      (is (= "get" (:method call-expr))))))

(deftest nested-array-access-test
  (testing "Nested array access matrix.get(i).get(j)"
    (let [code "class Test
  feature
    demo() do
      let matrix: Array [Array [Integer]] := create Array
      let x: Integer := matrix.get(0).get(1)
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
  (testing "Parse map get access"
    (let [code "class Test
  feature
    demo() do
      let m: Map [String, Integer] := {\"a\": 1, \"b\": 2}
      let x: Integer := m.get(\"a\")
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body second)
          call-expr (:value let-stmt)]
      (is (= :call (:type call-expr)))
      (is (= "m" (:target call-expr)))
      (is (= "get" (:method call-expr))))))

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

