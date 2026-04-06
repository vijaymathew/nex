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

(deftest array-filled-runtime-test
  (testing "create Array.filled builds a mutable array with repeated values"
    (let [ctx (interp/make-context)
          value (interp/eval-node ctx {:type :create
                                       :class-name "Array"
                                       :generic-args ["Integer"]
                                       :constructor "filled"
                                       :args [{:type :literal :value 3}
                                              {:type :literal :value 0}]})]
      (is (= 3 (count value)))
      (is (= [0 0 0] (vec value))))))

(deftest string-chars-runtime-test
  (testing "String.chars returns a fresh Array[Char] in the same iteration order as char_at"
    (let [ctx (interp/make-context)
          chars (interp/call-builtin-method ctx "cat" "cat" "chars" [])]
      (is (= 3 (.size chars)))
      (is (= [\c \a \t] (vec chars)))
      (is (= \a (interp/call-builtin-method ctx chars chars "get" [1])))
      (interp/call-builtin-method ctx chars chars "add" [\!])
      (is (= 4 (.size chars)))
      (is (= 3 (interp/call-builtin-method ctx "cat" "cat" "length" [])))
      (is (= \a (interp/call-builtin-method ctx "cat" "cat" "char_at" [1]))))))

(deftest min-heap-runtime-test
  (testing "Min_Heap supports natural ordering, comparator ordering, and safe empty reads"
    (let [ctx (interp/make-context)
          natural (interp/eval-node ctx {:type :create
                                         :class-name "Min_Heap"
                                         :generic-args ["Integer"]
                                         :constructor "empty"
                                         :args []})
          reverse-compare (fn [a b]
                            (cond
                              (> a b) -1
                              (< a b) 1
                              :else 0))
          custom (interp/eval-node ctx {:type :create
                                        :class-name "Min_Heap"
                                        :generic-args ["Integer"]
                                        :constructor "from_comparator"
                                        :args [{:type :literal :value reverse-compare}]})]
      (interp/call-builtin-method ctx natural natural "insert" [5])
      (interp/call-builtin-method ctx natural natural "insert" [1])
      (interp/call-builtin-method ctx natural natural "insert" [3])
      (is (= 1 (interp/call-builtin-method ctx natural natural "peek" [])))
      (is (= 1 (interp/call-builtin-method ctx natural natural "extract_min" [])))
      (is (= 3 (interp/call-builtin-method ctx natural natural "extract_min" [])))
      (is (= 5 (interp/call-builtin-method ctx natural natural "extract_min" [])))
      (is (nil? (interp/call-builtin-method ctx natural natural "try_peek" [])))
      (is (nil? (interp/call-builtin-method ctx natural natural "try_extract_min" [])))
      (interp/call-builtin-method ctx custom custom "insert" [5])
      (interp/call-builtin-method ctx custom custom "insert" [1])
      (interp/call-builtin-method ctx custom custom "insert" [3])
      (is (= 5 (interp/call-builtin-method ctx custom custom "extract_min" []))))))

(deftest atomic-builtins-runtime-test
  (testing "atomic built-ins support load/store/update and reference CAS"
    (let [ctx (interp/make-context)
          ai (interp/eval-node ctx {:type :create
                                    :class-name "Atomic_Integer"
                                    :generic-args nil
                                    :constructor "make"
                                    :args [{:type :literal :value 10}]})
          ar (interp/eval-node ctx {:type :create
                                    :class-name "Atomic_Reference"
                                    :generic-args ["String"]
                                    :constructor "make"
                                    :args [{:type :literal :value "a"}]})]
      (is (= 10 (interp/call-builtin-method ctx ai ai "load" [])))
      (is (= 10 (interp/call-builtin-method ctx ai ai "get_and_add" [5])))
      (is (= 15 (interp/call-builtin-method ctx ai ai "load" [])))
      (is (= 16 (interp/call-builtin-method ctx ai ai "increment" [])))
      (is (= 15 (interp/call-builtin-method ctx ai ai "decrement" [])))
      (is (true? (interp/call-builtin-method ctx ai ai "compare_and_set" [15 7])))
      (is (= 7 (interp/call-builtin-method ctx ai ai "load" [])))
      (is (= "a" (interp/call-builtin-method ctx ar ar "load" [])))
      (is (true? (interp/call-builtin-method ctx ar ar "compare_and_set" ["a" "b"])))
      (is (= "b" (interp/call-builtin-method ctx ar ar "load" [])))
      (interp/call-builtin-method ctx ar ar "store" [nil])
      (is (nil? (interp/call-builtin-method ctx ar ar "load" []))))))
