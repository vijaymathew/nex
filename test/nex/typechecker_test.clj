(ns nex.typechecker-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(deftest test-simple-arithmetic
  (testing "Type checking simple arithmetic"
    (let [code "class Test
                  feature
                    add(x, y: Integer): Integer
                    do
                      Result := x + y
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-type-mismatch-assignment
  (testing "Type mismatch in assignment should fail"
    (let [code "class Test
                  private feature
                    x: Integer
                  feature
                    wrong()
                    do
                      x := \"not an integer\"
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest test-return-type-mismatch
  (testing "Return type mismatch should fail"
    (let [code "class Test
                  feature
                    get_number(): Integer
                    do
                      Result := \"not a number\"
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest test-undefined-variable
  (testing "Using undefined variable should fail"
    (let [code "class Test
                  feature
                    bad()
                    do
                      print(undefined_var)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest test-comparison-operators
  (testing "Comparison operators should work on compatible types"
    (let [code "class Test
                  feature
                    compare(x, y: Integer): Boolean
                    do
                      Result := x < y
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest test-comparison-type-mismatch
  (testing "Comparing incompatible types should fail"
    (let [code "class Test
                  feature
                    bad_compare()
                    do
                      let x: Integer := 5
                      let y: String := \"hello\"
                      print(x < y)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest test-nil-equality
  (testing "Equality comparison with nil should type check"
    (let [code "class Test
                  feature
                    is_nil(x: String): Boolean
                    do
                      Result := x = nil
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-java-import-typecheck
  (testing "Imported Java classes should be recognized"
    (let [code "import java.net.Socket

class Client
  feature
    connect(host: String, port: Integer) do
      let s: Socket := create Socket.make(host, port)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result)))))) 

(deftest test-generic-constraint-enforced
  (testing "Generic constraints should be enforced"
    (let [code "class A feature p do end end
class B inherit A feature p do end end
class X feature p do end end
class C[T -> A]
  feature
    t: T
  create
    make(tt: T) do
      t := tt
    end
end
class Test
  feature
    demo() do
      let b: B := create B
      let x: X := create X
      let ok: C[B] := create C[B].make(b)
      let bad: C[X] := create C[X].make(x)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result)))))) 

(deftest test-generic-constructor-arg-mismatch
  (testing "Constructor args must respect resolved generic types"
    (let [code "class A feature p do end end
class B inherit A feature p do end end
class Y inherit B feature p do end end
class X feature p do end end
class C[T -> A]
  feature
    t: T
  create
    make(tt: T) do
      t := tt
    end
end
class Test
  feature
    demo() do
      let x: X := create X
      let ok: C[Y] := create C[Y].make(create Y)
      let bad: C[Y] := create C[Y].make(x)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result)))))) 

(deftest test-boolean-operators
  (testing "Boolean operators should require Boolean operands"
    (let [code "class Test
                  feature
                    bool_op(x, y: Boolean): Boolean
                    do
                      Result := x and y
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest test-boolean-operator-type-mismatch
  (testing "Boolean operators with non-Boolean operands should fail"
    (let [code "class Test
                  feature
                    bad_bool()
                    do
                      let x: Integer := 5
                      let y: Integer := 10
                      print(x and y)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest test-if-condition-type
  (testing "If condition must be Boolean"
    (let [code "class Test
                  feature
                    check(x: Integer)
                    do
                      if x then
                        print(x)
                      else
                        print(0)
                      end
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest test-contracts
  (testing "Contracts must be Boolean"
    (let [code "class Test
                  feature
                    safe_divide(x, y: Integer): Integer
                    require
                      non_zero: y /= 0
                    do
                      Result := x / y
                    ensure
                      positive: Result >= 0
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest test-bad-contract
  (testing "Non-Boolean contract should fail"
    (let [code "class Test
                  feature
                    bad_contract(x: Integer)
                    require
                      bad: x + 5
                    do
                      print(x)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest test-let-with-type
  (testing "Let with explicit type should check value type"
    (let [code "class Test
                  feature
                    test()
                    do
                      let x: Integer := 42
                      print(x)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest test-let-type-mismatch
  (testing "Let with mismatched type should fail"
    (let [code "class Test
                  feature
                    bad_let()
                    do
                      let x: Integer := \"not a number\"
                      print(x)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest test-undefined-parent-class
  (testing "Inheriting from undefined parent class should fail"
    (let [code "class Savings_Account
                inherit
                  Account
                    rename
                      deposit as account_deposit
                    redefine
                      deposit
                    end
                feature
                  balance: Integer
                end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result)))
      (is (some #(re-find #"Undefined parent class" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-valid-inheritance
  (testing "Inheriting from defined parent class should succeed"
    (let [code "class Account
                  feature
                    balance: Integer

                    deposit(amount: Integer)
                    do
                      let balance: Integer := balance + amount
                    end
                  end

                class Savings_Account
                inherit
                  Account
                    rename
                      deposit as account_deposit
                    redefine
                      deposit
                    end
                feature
                  interest_rate: Real

                  deposit(amount: Integer)
                  do
                    let balance: Integer := balance + amount
                  end
                end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

;; Mandatory type annotation tests

(deftest test-let-without-type-annotation-fails
  (testing "Let without type annotation should fail in typechecking mode"
    (let [code "class Test
                  feature
                    test()
                    do
                      let a := 1
                      print(a)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result)))
      (is (some #(re-find #"Type annotation required" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-let-with-type-annotation-succeeds
  (testing "Let with type annotation should pass in typechecking mode"
    (let [code "class Test
                  feature
                    test()
                    do
                      let a: Integer := 1
                      print(a)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest test-let-without-type-create-expression-fails
  (testing "Let without type annotation on create expression should fail"
    (let [code "class Box
                  feature
                    value: Integer
                  end

                class Test
                  feature
                    test()
                    do
                      let b := create Box
                      print(b)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"Type annotation required" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-let-with-type-create-expression-succeeds
  (testing "Let with type annotation on create expression should pass"
    (let [code "class Box
                  feature
                    value: Integer
                  end

                class Test
                  feature
                    test()
                    do
                      let b: Box := create Box
                      print(b)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

;; Mandatory return type tests

(deftest test-method-using-result-without-return-type-fails
  (testing "Method using Result without return type should fail"
    (let [code "class Test
                  feature
                    compute(x: Integer)
                    do
                      Result := x + 1
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result)))
      (is (some #(re-find #"does not declare a return type" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-method-using-result-with-return-type-succeeds
  (testing "Method using Result with return type should pass"
    (let [code "class Test
                  feature
                    compute(x: Integer): Integer
                    do
                      Result := x + 1
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest test-method-without-result-no-return-type-succeeds
  (testing "Method not using Result without return type should pass"
    (let [code "class Test
                  private feature
                    x: Integer
                  feature
                    set_x(val: Integer)
                    do
                      x := val
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest test-method-result-in-postcondition-requires-return-type
  (testing "Method referencing Result in postcondition must declare return type"
    (let [code "class Test
                  feature
                    compute(x: Integer)
                    do
                      Result := x * 2
                    ensure
                      positive: Result > 0
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"does not declare a return type" %)
                (map tc/format-type-error (:errors result)))))))

;; Generic type safety tests

(deftest test-generic-method-type-mismatch
  (testing "Calling generic method with wrong type should fail"
    (let [code "class Box [T]
                  feature
                    value: T

                    set(new_value: T)
                    do
                      value := new_value
                    end
                  end

                class Main
                  feature
                    demo()
                    do
                      let b: Box[Integer] := create Box[Integer]
                      b.set(\"hello\")
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"Expected Integer, got String" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-generic-method-correct-type-succeeds
  (testing "Calling generic method with correct type should pass"
    (let [code "class Box [T]
                  feature
                    value: T

                    set(new_value: T)
                    do
                      value := new_value
                    end
                  end

                class Main
                  feature
                    demo()
                    do
                      let b: Box[Integer] := create Box[Integer]
                      b.set(42)
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest test-generic-method-with-var-types
  (testing "Type checking with pre-existing var-types catches generic mismatches"
    (let [code "class Box [T]
                  feature
                    value: T

                    set(new_value: T)
                    do
                      value := new_value
                    end
                  end"
          ast (p/ast code)
          ;; Simulate REPL: b was previously defined as Box[Integer]
          var-types {"b" {:base-type "Box" :type-args ["Integer"]}}
          ;; Now check b.set("hello") as a top-level call
          call-ast {:type :program
                    :classes (:classes ast)
                    :calls [{:type :call
                             :target "b"
                             :method "set"
                             :args [{:type :string :value "hello"}]}]}
          result (tc/type-check call-ast {:var-types var-types})]
      (is (not (:success result)))
      (is (some #(re-find #"Expected Integer, got String" %)
                (map tc/format-type-error (:errors result)))))))
