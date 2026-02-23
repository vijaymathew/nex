(ns nex.inheritance-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(deftest simple-inheritance-test
  (testing "Simple inheritance parsing"
    (let [code "class Animal
  feature
    speak() do
      print(\"Animal speaks\")
    end
end

class Dog inherit Animal
feature
  bark() do
    print(\"Woof!\")
  end
end"
          ast (p/ast code)
          dog-class (second (:classes ast))]
      (is (some? (:parents dog-class)))
      (is (= 1 (count (:parents dog-class))))
      (is (= "Animal" (:parent (first (:parents dog-class))))))))

(deftest multiple-inheritance-test
  (testing "Multiple inheritance"
    (let [code "class Flyable
  feature
    fly() do
      print(\"Flying...\")
    end
end

class Swimmable
  feature
    swim() do
      print(\"Swimming...\")
    end
end

class Duck inherit Flyable, Swimmable
feature
  quack() do
    print(\"Quack!\")
  end
end"
          ast (p/ast code)
          duck-class (nth (:classes ast) 2)
          parents (:parents duck-class)]
      (is (= 2 (count parents)))
      (is (= "Flyable" (:parent (first parents))))
      (is (= "Swimmable" (:parent (second parents)))))))

(deftest inline-inherit-syntax-test
  (testing "Inherit clause is inline with class declaration"
    (let [code "class Base
  feature
    hello() do
      print(\"hello\")
    end
end

class Child inherit Base
feature
  world() do
    print(\"world\")
  end
end"
          ast (p/ast code)
          child-class (second (:classes ast))]
      (is (= "Child" (:name child-class)))
      (is (= 1 (count (:parents child-class))))
      (is (= "Base" (:parent (first (:parents child-class)))))
      ;; No :renames or :redefines keys
      (is (nil? (:renames (first (:parents child-class)))))
      (is (nil? (:redefines (first (:parents child-class))))))))
