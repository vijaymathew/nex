(ns nex.inheritance-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]))

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
      (is (= "Base" (:parent (first (:parents child-class))))))))

(deftest any-as-explicit-base-class-test
  (testing "Any can appear as an explicit base class"
    (let [code "class Thing inherit Any
feature
  show(): String do
    result := to_string()
  end
end"
          ast (p/ast code)
          thing-class (first (:classes ast))]
      (is (= "Thing" (:name thing-class)))
      (is (= 1 (count (:parents thing-class))))
      (is (= "Any" (:parent (first (:parents thing-class)))))))

(deftest typecheck-child-assignable-to-parent-type
  (testing "a child instance is accepted where the parent type is declared"
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
end

class Test
  feature
    demo() do
      let a: Animal := create Dog
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest typecheck-override-incompatible-return-type-fails
  (testing "overriding a method with an incompatible return type is rejected by the typechecker"
    (let [code "class Base
  feature
    value(): Integer do
      result := 1
    end
end

class Child inherit Base
  feature
    value(): String do
      result := \"one\"
    end
end"
          result (tc/type-check (p/ast code))]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest typecheck-inherited-method-callable-on-child
  (testing "a method defined on the parent is callable on a child-typed variable"
    (let [code "class Animal
  feature
    speak(): String do
      result := \"Animal speaks\"
    end
end

class Dog inherit Animal
end

class Test
  feature
    demo() do
      let d: Dog := create Dog
      let s: String := d.speak
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest typecheck-super-call-resolves-parent-signature
  (testing "a super constructor call in a child constructor typechecks against the parent signature"
    (let [code "class Base
  feature
    x: Integer
  create
    make(v: Integer) do
      x := v
    end
end

class Child inherit Base
  create
    make(v: Integer) do
      Base.make(v)
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result)))))))
