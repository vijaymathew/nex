(ns nex.inheritance-runtime-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(deftest calling-inherited-method-test
  (testing "Calling inherited method from parent class"
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
          ctx (interp/make-context)]
      ;; Register all classes
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      ;; Create object and call inherited method
      (let [dog-obj (interp/make-object "Dog" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "mydog" dog-obj)
            ctx-with-dog (assoc ctx :current-env env)]
        (interp/eval-node ctx-with-dog {:type :call
                                         :target "mydog"
                                         :method "speak"
                                         :args []})
        (is (= ["\"Animal speaks\""] @(:output ctx-with-dog)))))))

(deftest calling-own-method-test
  (testing "Calling own method (not inherited)"
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
          ctx (interp/make-context)]
      ;; Register all classes
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      ;; Create object and call own method
      (let [dog-obj (interp/make-object "Dog" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "mydog" dog-obj)
            ctx-with-dog (assoc ctx :current-env env)]
        (interp/eval-node ctx-with-dog {:type :call
                                         :target "mydog"
                                         :method "bark"
                                         :args []})
        (is (= ["\"Woof!\""] @(:output ctx-with-dog)))))))

(deftest method-overriding-test
  (testing "Method overriding (redefine)"
    (let [code "class Shape
  feature
    draw() do
      print(\"Drawing generic shape\")
    end
end

class Circle
inherit
  Shape
    redefine
      draw
    end
feature
  draw() do
    print(\"Drawing circle\")
  end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      ;; Register all classes
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      ;; Create object and call redefined method
      (let [circle-obj (interp/make-object "Circle" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "mycircle" circle-obj)
            ctx-with-circle (assoc ctx :current-env env)]
        (interp/eval-node ctx-with-circle {:type :call
                                            :target "mycircle"
                                            :method "draw"
                                            :args []})
        (is (= ["\"Drawing circle\""] @(:output ctx-with-circle)))))))

(deftest multiple-inheritance-methods-test
  (testing "Multiple inheritance - calling methods from both parents"
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
          ctx (interp/make-context)]
      ;; Register all classes
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      ;; Create duck and test all methods
      (let [duck-obj (interp/make-object "Duck" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "myduck" duck-obj)
            ctx-with-duck (assoc ctx :current-env env)]
        ;; Test fly from Flyable
        (interp/eval-node ctx-with-duck {:type :call
                                          :target "myduck"
                                          :method "fly"
                                          :args []})
        (is (= ["\"Flying...\""] @(:output ctx-with-duck)))
        ;; Test swim from Swimmable
        (reset! (:output ctx-with-duck) [])
        (interp/eval-node ctx-with-duck {:type :call
                                          :target "myduck"
                                          :method "swim"
                                          :args []})
        (is (= ["\"Swimming...\""] @(:output ctx-with-duck)))
        ;; Test quack from Duck
        (reset! (:output ctx-with-duck) [])
        (interp/eval-node ctx-with-duck {:type :call
                                          :target "myduck"
                                          :method "quack"
                                          :args []})
        (is (= ["\"Quack!\""] @(:output ctx-with-duck)))))))

(deftest inheritance-chain-test
  (testing "Inheritance chain - grandparent methods accessible"
    (let [code "class Animal
  feature
    breathe() do
      print(\"Breathing...\")
    end
end

class Mammal
inherit
  Animal
  end
feature
  nurture() do
    print(\"Nurturing young\")
  end
end

class Dog
inherit
  Mammal
  end
feature
  bark() do
    print(\"Woof!\")
  end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      ;; Register all classes
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      ;; Create dog and test all methods
      (let [dog-obj (interp/make-object "Dog" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "mydog" dog-obj)
            ctx-with-dog (assoc ctx :current-env env)]
        ;; Test breathe from Animal (grandparent)
        (interp/eval-node ctx-with-dog {:type :call
                                         :target "mydog"
                                         :method "breathe"
                                         :args []})
        (is (= ["\"Breathing...\""] @(:output ctx-with-dog)))
        ;; Test nurture from Mammal (parent)
        (reset! (:output ctx-with-dog) [])
        (interp/eval-node ctx-with-dog {:type :call
                                         :target "mydog"
                                         :method "nurture"
                                         :args []})
        (is (= ["\"Nurturing young\""] @(:output ctx-with-dog)))
        ;; Test bark from Dog (own)
        (reset! (:output ctx-with-dog) [])
        (interp/eval-node ctx-with-dog {:type :call
                                         :target "mydog"
                                         :method "bark"
                                         :args []})
        (is (= ["\"Woof!\""] @(:output ctx-with-dog)))))))

