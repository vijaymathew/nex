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
                      let x := 5
                      let y := \"hello\"
                      print(x < y)
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
                      let x := 5
                      let y := 10
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
                      let balance := balance + amount
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
                    let balance := balance + amount
                  end
                end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))
