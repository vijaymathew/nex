(ns nex.visibility-test
  "Tests for feature visibility modifiers"
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]))

(deftest public-feature-parsing-test
  (testing "Parse public feature (default)"
    (let [code "class Test
  feature
    x: Integer
    demo() do
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          feature-section (first (:body class-def))]
      (is (= :feature-section (:type feature-section)))
      (is (= :public (-> feature-section :visibility :type))))))

(deftest private-feature-parsing-test
  (testing "Parse private feature"
    (let [code "class Test
  private feature
    x: Integer
    helper() do
      print(x)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          feature-section (first (:body class-def))]
      (is (= :feature-section (:type feature-section)))
      (is (= :private (-> feature-section :visibility :type))))))

(deftest selective-feature-syntax-rejected-test
  (testing "Selective visibility syntax is no longer part of the grammar"
    (is (thrown? Exception
                 (p/ast "class Test
  -> Friend, Helper feature
    x: Integer
end")))))

(deftest mixed-visibility-parsing-test
  (testing "Parse class with mixed public/private sections"
    (let [code "class Account
  feature
    balance: Integer

  private feature
    internal_balance: Integer
    calculate_fee() do
      print(internal_balance)
    end
end"
          ast (p/ast code)
          class-def (first (:classes ast))
          sections (:body class-def)]
      (is (= 2 (count sections)))
      (is (= :public (-> sections (nth 0) :visibility :type)))
      (is (= :private (-> sections (nth 1) :visibility :type))))))

(deftest empty-feature-section-test
  (testing "Feature section visibility without members should parse"
    (let [code "class Test
  feature
    x: Integer
  private feature
    y: Integer
end"
          ast (p/ast code)]
      (is (some? ast)))))
