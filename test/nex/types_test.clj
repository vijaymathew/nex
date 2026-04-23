(ns nex.types-test
  "Tests for Nex basic types and default initialization"
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]))

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

(deftest reserved-keywords-not-identifiers-test
  (testing "'convert', 'to', and 'declare' are reserved keywords, not identifiers"
    (is (thrown? Exception (p/ast "let convert := 1")))
    (is (thrown? Exception (p/ast "let to := 1")))
    (is (thrown? Exception (p/ast "let declare := 1")))
    (is (thrown? Exception (p/ast "class convert end")))
    (is (thrown? Exception (p/ast "class to end")))
    (is (thrown? Exception (p/ast "class declare end")))))
