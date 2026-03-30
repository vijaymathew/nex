(ns nex.inheritance-runtime-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(deftest self-inheritance-registration-fails-test
  (testing "register-class rejects self-inheritance instead of recursing later"
    (let [ast (p/ast "class C inherit C end")
          ctx (interp/make-context)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"cannot inherit from itself"
                            (interp/register-class ctx (first (:classes ast))))))))

(deftest cyclic-inheritance-registration-fails-test
  (testing "register-class rejects cycles when the closing class is registered"
    (let [ast (p/ast "class A inherit B end

class B inherit A end")
          ctx (interp/make-context)
          [a b] (:classes ast)]
      (interp/register-class ctx a)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cyclic inheritance detected"
                            (interp/register-class ctx b))))))

(deftest calling-inherited-method-test
  (testing "Calling inherited method from parent class"
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

class Dog inherit Animal
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
  (testing "Method overriding (implicit - same name as parent method)"
    (let [code "class Shape
  feature
    draw() do
      print(\"Drawing generic shape\")
    end
end

class Circle inherit Shape
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
      ;; Create object and call overridden method
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

class Duck inherit Flyable, Swimmable
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

class Mammal inherit Animal
feature
  nurture() do
    print(\"Nurturing young\")
  end
end

class Dog inherit Mammal
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

(deftest parent-method-call-test
  (testing "Calling parent method via A.show() syntax"
    (let [code "class A
  feature
    x: Integer

    show() do
      print(x)
    end
end

class B inherit A
feature
  y: Integer

  show() do
    A.show
    print(y)
  end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      ;; Register all classes
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      ;; Create B object with fields and call show
      (let [b-obj (interp/make-object "B" {:x 10 :y 20})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "b" b-obj)
            ctx-with-b (assoc ctx :current-env env)]
        (interp/eval-node ctx-with-b {:type :call
                                       :target "b"
                                       :method "show"
                                       :args []})
        ;; A.show prints x (10), then show prints y (20)
        (is (= ["10" "20"] @(:output ctx-with-b)))))))

(deftest parent-constructor-call-test
  (testing "Calling parent constructor via A.make_A(x) syntax"
    (let [code "class A
  feature
    x: Integer
  create
    make_A(x: Integer) do
      this.x := x
    end
end

class B inherit A
feature
  y: Integer
create
  make_B(x, y: Integer) do
    A.make_A(x)
    this.y := y
  end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      ;; Register all classes
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      ;; Create B using constructor
      (let [b-obj (interp/eval-node ctx {:type :create
                                          :class-name "B"
                                          :generic-args nil
                                          :constructor "make_B"
                                          :args [{:type :integer :value 10}
                                                 {:type :integer :value 20}]})]
        (is (= 10 (get (:fields b-obj) :x)))
        (is (= 20 (get (:fields b-obj) :y)))))))

(deftest parent-field-access-test
  (testing "Inherited fields are accessible"
    (let [code "class Vehicle
  feature
    speed: Integer
end

class Car inherit Vehicle
feature
  brand: String

  info() do
    print(speed)
    print(brand)
  end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      ;; Register all classes
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      ;; Create car with inherited and own fields
      (let [car-obj (interp/make-object "Car" {:speed 100 :brand "Tesla"})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "mycar" car-obj)
            ctx-with-car (assoc ctx :current-env env)]
        (interp/eval-node ctx-with-car {:type :call
                                         :target "mycar"
                                         :method "info"
                                         :args []})
        (is (= ["100" "\"Tesla\""] @(:output ctx-with-car)))))))

(deftest inherited-invariants-checked-on-create-test
  (testing "Inherited class invariants are enforced during object creation"
    (let [code "class A
  feature
    x: Integer
  invariant
    parent_positive: x > 0
end

class B inherit A
end

class C inherit B
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (is (thrown-with-msg?
            Exception
            #"Class invariant violation: parent_positive"
            (interp/eval-node ctx {:type :create
                                   :class-name "C"
                                   :generic-args nil
                                   :constructor nil
                                   :args []}))))))

(deftest inherited-and-local-invariants-conjoined-test
  (testing "Class invariants are inherited as base-invariants and conjoined with local invariants"
    (let [code "class A
  feature
    x: Integer
    set_x(v: Integer) do
      this.x := v
    end
  invariant
    parent_positive: x > 0
end

class B inherit A
create
  make(x0: Integer) do
    A.set_x(x0)
  end
invariant
  local_lt_ten: x < 10
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (let [obj (interp/eval-node ctx {:type :create
                                       :class-name "B"
                                       :generic-args nil
                                       :constructor "make"
                                       :args [{:type :integer :value 5}]})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "b" obj)
            ctx-with-b (assoc ctx :current-env env)]
        (is (thrown-with-msg?
              Exception
              #"Class invariant violation: parent_positive"
              (interp/eval-node ctx-with-b {:type :call
                                            :target "b"
                                            :method "set_x"
                                            :args [{:type :integer :value 0}]})))
        (is (thrown-with-msg?
              Exception
              #"Class invariant violation: local_lt_ten"
              (interp/eval-node ctx-with-b {:type :call
                                            :target "b"
                                            :method "set_x"
                                            :args [{:type :integer :value 11}]})))))))

(deftest diamond-inheritance-invariants-deduplicated-test
  (testing "Diamond inheritance deduplicates shared ancestor class invariants"
    (let [code "class A
  invariant
    a_ok: true
end

class B inherit A
  invariant
    b_ok: true
end

class C inherit A
  invariant
    c_ok: true
end

class D inherit B, C
  invariant
    d_ok: true
end"
          ast (p/ast code)
          ctx (interp/make-context)
          d-class (last (:classes ast))
          labels-seen (atom nil)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (with-redefs [interp/check-assertions
                    (fn [_ assertions _]
                      (reset! labels-seen (mapv :label assertions)))]
        (interp/check-class-invariant ctx d-class))
      (is (= ["a_ok" "b_ok" "c_ok" "d_ok"] @labels-seen)))))

(deftest inherited-method-preconditions-use-or-test
  (testing "Overridden feature preconditions are base OR local"
    (let [code "class A
feature
  f(x: Integer)
  require
    base_positive: x > 0
  do
    print(\"A\")
  end
end

class B inherit A
feature
  f(x: Integer)
  require
    local_negative: x < 0
  do
    print(\"B\")
  end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (let [obj (interp/make-object "B" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "b" obj)
            ctx-with-b (assoc ctx :current-env env)]
        ;; base require true
        (is (nil? (interp/eval-node ctx-with-b {:type :call
                                                :target "b"
                                                :method "f"
                                                :args [{:type :integer :value 1}]})))
        ;; local require true
        (is (nil? (interp/eval-node ctx-with-b {:type :call
                                                :target "b"
                                                :method "f"
                                                :args [{:type :integer :value -1}]})))
        ;; both false -> precondition violation
        (is (thrown-with-msg?
              Exception
              #"Precondition violation"
              (interp/eval-node ctx-with-b {:type :call
                                            :target "b"
                                            :method "f"
                                            :args [{:type :integer :value 0}]})))))))

(deftest inherited-method-postconditions-use-and-test
  (testing "Overridden feature postconditions are base AND local"
    (let [code "class A
feature
  g(): Integer
  do
    result := 5
  ensure
    base_non_negative: result >= 0
  end
end

class B inherit A
feature
  g(): Integer
  do
    result := 11
  ensure
    local_lt_ten: result < 10
  end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (let [obj (interp/make-object "B" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "b" obj)
            ctx-with-b (assoc ctx :current-env env)]
        (is (thrown-with-msg?
              Exception
              #"Postcondition violation: local_lt_ten"
              (interp/eval-node ctx-with-b {:type :call
                                            :target "b"
                                            :method "g"
                                            :args []})))))))

(deftest inherited-constructor-create-test
  (testing "Child class can use constructor inherited from parent"
    (let [code "class A
  feature
    x: Integer
  create
    make(x: Integer) do
      this.x := x
    end
end

class B inherit A
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (let [b-obj (interp/eval-node ctx {:type :create
                                         :class-name "B"
                                         :constructor "make"
                                         :args [{:type :integer :value 20}]})]
        (is (= "B" (:class-name b-obj)))
        (is (= 20 (get-in b-obj [:fields :x])))))))
