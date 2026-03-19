(ns nex.create-test
  "Tests for create keyword and object instantiation"
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

(deftest simple-create-parsing-test
  (testing "Parse simple create expression"
    (let [code "class Test
  feature
    x: Integer
end

class Main
  feature
    demo() do
      let obj: Test := create Test
      print(obj)
    end
end"
          ast (p/ast code)
          class-def (second (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body first)
          create-expr (:value let-stmt)]
      (is (= :create (:type create-expr)))
      (is (= "Test" (:class-name create-expr)))
      (is (nil? (:constructor create-expr)))
      (is (empty? (:args create-expr))))))

(deftest named-constructor-parsing-test
  (testing "Parse create with named constructor"
    (let [code "class Account
  create
    with_balance(bal: Integer) do
      let balance: Integer := bal
    end
  feature
    balance: Integer
end

class Main
  feature
    demo() do
      let acc: Account := create Account.with_balance(1000)
      print(acc)
    end
end"
          ast (p/ast code)
          class-def (second (:classes ast))
          method (-> class-def :body first :members first)
          let-stmt (-> method :body first)
          create-expr (:value let-stmt)]
      (is (= :create (:type create-expr)))
      (is (= "Account" (:class-name create-expr)))
      (is (= "with_balance" (:constructor create-expr)))
      (is (= 1 (count (:args create-expr)))))))

(deftest deferred-class-parsing-test
  (testing "Parse deferred class declaration"
    (let [code "deferred class A
  feature
    f(i: Integer): Boolean do end
end

class B inherit A
  feature
    f(i: Integer): Boolean do
      result := i > 0
    end
end"
          ast (p/ast code)
          a-class (first (:classes ast))
          b-class (second (:classes ast))]
      (is (= "A" (:name a-class)))
      (is (true? (:deferred? a-class)))
      (is (= "B" (:name b-class)))
      (is (not (true? (:deferred? b-class)))))))

(deftest deferred-class-runtime-instantiation-guard-test
  (testing "Interpreter rejects direct creation of deferred class"
    (let [code "deferred class A
  feature
    f(i: Integer): Boolean do end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot instantiate deferred class: A"
           (interp/eval-node ctx {:type :create
                                  :class-name "A"
                                  :generic-args nil
                                  :constructor nil
                                  :args []}))))))

(deftest default-initialization-ast-test
  (testing "Create expression AST structure"
    (let [code "class Point
  feature
    x: Integer
    y: Integer
end

class Main
  feature
    test() do
      let p: Point := create Point
      print(p)
    end
end"
          ast (p/ast code)]
      ;; Just verify AST was created correctly
      (is (= 2 (count (:classes ast)))))))

(deftest constructor-execution-ast-test
  (testing "Create with constructor AST structure"
    (let [code "class Counter
  create
    with_value(val: Integer) do
      let count: Integer := val
    end
  feature
    count: Integer
end

class Main
  feature
    test() do
      let c: Counter := create Counter.with_value(10)
    end
end"
          ast (p/ast code)]
      ;; Verify we have both classes
      (is (= 2 (count (:classes ast)))))))

(deftest create-contract-violation-parsing-test
  (testing "Parse create with potentially violating contract"
    (let [code "class Account
  create
    with_balance(initial: Integer)
      require
        positive: initial >= 0
      do
        let balance: Integer := initial
      end
  feature
    balance: Integer
end

class Main
  feature
    test() do
      let acc: Account := create Account.with_balance(-100)
    end
end"
          ast (p/ast code)]
      ;; Just verify it parses correctly
      (is (= 2 (count (:classes ast)))))))

