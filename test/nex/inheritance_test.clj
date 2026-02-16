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

class Dog
inherit
  Animal
  end
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

(deftest inheritance-with-rename-test
  (testing "Inheritance with rename clause"
    (let [code "class Base
  feature
    greet() do
      print(\"Hello from Base\")
    end
end

class Derived
inherit
  Base
    rename
      greet as base_greet
    end
feature
  greet() do
    print(\"Hello from Derived\")
  end
end"
          ast (p/ast code)
          derived-class (second (:classes ast))
          parent (first (:parents derived-class))]
      (is (= "Base" (:parent parent)))
      (is (some? (:renames parent)))
      (is (= 1 (count (:renames parent))))
      (is (= "greet" (:old-name (first (:renames parent)))))
      (is (= "base_greet" (:new-name (first (:renames parent))))))))

(deftest inheritance-with-redefine-test
  (testing "Inheritance with redefine clause"
    (let [code "class Shape
  feature
    area() do
      print(\"Generic area\")
    end
end

class Circle
inherit
  Shape
    redefine
      area
    end
feature
  area() do
    print(\"Circle area\")
  end
end"
          ast (p/ast code)
          circle-class (second (:classes ast))
          parent (first (:parents circle-class))]
      (is (= "Shape" (:parent parent)))
      (is (some? (:redefines parent)))
      (is (= ["area"] (:redefines parent))))))

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

class Duck
inherit
  Flyable
  end,
  Swimmable
  end
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

(deftest complex-inheritance-test
  (testing "Complex inheritance with both rename and redefine"
    (let [code "class Account
  feature
    deposit(amount: Integer) do
      print(\"Account deposit:\", amount)
    end

    balance() do
      print(\"Account balance\")
    end
end

class SavingsAccount
inherit
  Account
    rename
      deposit as account_deposit
    redefine
      deposit
    end
feature
  deposit(amount: Integer) do
    print(\"Savings deposit:\", amount)
  end

  interest() do
    print(\"Calculating interest\")
  end
end"
          ast (p/ast code)
          savings-class (second (:classes ast))
          parent (first (:parents savings-class))]
      (is (= "Account" (:parent parent)))
      (is (some? (:renames parent)))
      (is (= 1 (count (:renames parent))))
      (is (= "deposit" (:old-name (first (:renames parent)))))
      (is (= "account_deposit" (:new-name (first (:renames parent)))))
      (is (some? (:redefines parent)))
      (is (= ["deposit"] (:redefines parent))))))

