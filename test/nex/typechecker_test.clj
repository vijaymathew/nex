(ns nex.typechecker-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(deftest test-simple-arithmetic
  (testing "Type checking simple arithmetic"
    (let [code "class Test
                  feature
                    add(x, y: Integer): Integer
                    do
                      result := x + y
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

(deftest test-any-root-methods
  (testing "Any exposes to_string, equals, and clone on explicit Any subclasses"
    (let [code "class Box inherit Any
                  feature
                    x: Integer
                  create
                    make(v: Integer) do
                      x := v
                    end
                    demo(other: Box) do
                      let s: String := this.to_string()
                      let eq: Boolean := this.equals(other)
                      let copy: Any := this.clone()
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-return-type-mismatch
  (testing "Return type mismatch should fail"
    (let [code "class Test
                  feature
                    get_number(): Integer
                    do
                      result := \"not a number\"
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

(deftest test-private-field-not-accessible-from-outside
  (testing "Private fields cannot be accessed from outside the defining class"
    (let [code "class Counter
                  create
                    make(start: Integer) do
                      count := start
                    end
                  feature
                    current(): Integer
                    do
                      result := count
                    end
                  private feature
                    count: Integer
                  end

                let c := create Counter.make(10)
                c.count"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(= "Undefined field: count" (:message %)) (:errors result))))))

(deftest test-map-put-typechecks-inside-function-body
  (testing "Map.put type-checks for typed map values inside top-level functions"
    (let [code "function word_frequencies(text: String): Map[String, Integer]
do
  result := {}
  let words := text.to_lower.split(\" \")
  across words as w do
    let count := result.try_get(w, 0)
    result.put(w, count + 1)
  end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-min-heap-constructors-typecheck
  (testing "Min_Heap.empty requires Comparable elements while from_comparator supports custom ordering"
    (let [ok-code "class Box
  feature
    value: Integer
  create
    make(v: Integer) do
      value := v
    end
end

function cmp(a: Box, b: Box): Integer
do
  result := a.value - b.value
end

let numbers: Min_Heap[Integer] := create Min_Heap.empty
let boxes: Min_Heap[Box] := create Min_Heap.from_comparator(cmp)"
          ok-result (tc/type-check (p/ast ok-code))
          bad-code "class Box
  feature
    value: Integer
end

let boxes: Min_Heap[Box] := create Min_Heap[Box].empty"
          bad-result (tc/type-check (p/ast bad-code))]
      (is (:success ok-result))
      (is (not (:success bad-result)))
      (is (some #(str/includes? (:message %) "Min_Heap.empty requires")
                (:errors bad-result))))))

(deftest test-atomic-constructors-and-methods-typecheck
  (testing "atomic built-ins typecheck with their declared value types"
    (let [code "let ai: Atomic_Integer := create Atomic_Integer.make(1)
let ai64: Atomic_Integer64 := create Atomic_Integer64.make(1)
let ab: Atomic_Boolean := create Atomic_Boolean.make(true)
let ar: Atomic_Reference[String] := create Atomic_Reference.make(\"x\")
let n: Integer := ai.increment
let ok: Boolean := ai.compare_and_set(n, 10)
let s: ?String := ar.load
ar.store(nil)
let swapped: Boolean := ar.compare_and_set(nil, \"done\")"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-comparison-operators
  (testing "Comparison operators should work on compatible types"
    (let [code "class Test
                  feature
                    compare(x, y: Integer): Boolean
                    do
                      result := x < y
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

(deftest test-unary-minus-literal-typechecks
  (testing "Unary minus on numeric literal should typecheck"
    (let [code "class Test
                  feature
                    neg(): Integer
                    do
                      result := -1
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-special-char-names-remain-valid-identifiers
  (testing "newline/tab/space style names can be used as identifiers while #newline still parses as a char literal"
    (let [code "class Test
                  feature
                    demo(): Char
                    do
                      let newline: Char := #newline
                      let tab: Char := #tab
                      result := newline
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-member-field-access-types-correctly
  (testing "Field access via obj.field should resolve declared field type"
    (let [code "class A
                  inherit Comparable
                  feature
                    x: Integer
                    compare(a: A): Integer
                    do
                      if x < a.x then
                        result := -1
                      elseif x > a.x then
                        result := 1
                      else
                        result := 0
                      end
                    end
                  create
                    make(x: Integer)
                    do
                      this.x := x
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-member-field-access-inherited-field
  (testing "Field access via obj.field should resolve inherited field type"
    (let [code "class A
                  feature
                    x: Integer
                  end

                class B
                  inherit A
                  feature
                    gt(other: B): Boolean
                    do
                      result := other.x > 0
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-member-field-assignment-rejected-outside-declaring-class
  (testing "Top-level object.field assignment is rejected even for public fields"
    (let [code "class Task
                  feature
                    status: String
                  end

                let t: Task := create Task
                t.status := \"PENDING\""
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(str/includes? (tc/format-type-error %) "Cannot assign to field status outside of class Task")
                (:errors result))))))

(deftest test-member-field-assignment-rejected-in-subclass
  (testing "A subclass cannot directly assign a parent field"
    (let [code "class Account
                  feature
                    balance: Real
                  end

                class Savings_Account
                  inherit Account
                  feature
                    reset_balance() do
                      this.balance := 0.0
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(str/includes? (tc/format-type-error %) "Cannot assign to field balance outside of class Account")
                (:errors result))))))

(deftest test-member-field-assignment-rejected-in-multiple-inheritance-first-parent
  (testing "A multiply-inheriting child cannot directly assign a field from its first parent"
    (let [code "class A
                  feature
                    x: Integer
                  end

                class B
                  feature
                    y: Integer
                  end

                class C
                  inherit A, B
                  feature
                    break_x() do
                      this.x := 1
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(str/includes? (tc/format-type-error %) "Cannot assign to field x outside of class A")
                (:errors result))))))

(deftest test-member-field-assignment-rejected-in-multiple-inheritance-second-parent
  (testing "A multiply-inheriting child cannot directly assign a field from its second parent"
    (let [code "class A
                  feature
                    x: Integer
                  end

                class B
                  feature
                    y: Integer
                  end

                class C
                  inherit A, B
                  feature
                    break_y() do
                      this.y := 2
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(str/includes? (tc/format-type-error %) "Cannot assign to field y outside of class B")
                (:errors result))))))

(deftest test-member-field-assignment-allowed-on-other-instance-within-declaring-class
  (testing "A class may directly assign one of its own fields on another instance of the same class"
    (let [code "class Counter
                  feature
                    value: Integer
                    sync_to(other: Counter, v: Integer) do
                      other.value := v
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-user-class-can-shadow-builtin-task-type
  (testing "A user-defined Task class should not be clobbered by the builtin Task placeholder"
    (let [code "class Task
                  create
                    make(id: String, status: String) do
                      this.id := id
                      this.status := status
                    end
                  feature
                    id: String
                    status: String
                  invariant
                    id_present: id /= \"\"
                    valid_status:
                      status = \"PENDING\" or
                      status = \"IN_TRANSIT\" or
                      status = \"DELIVERED\" or
                      status = \"FAILED\"
                  end

                class Task_Sequence
                  create
                    make(t1: Task, t2: Task, t3: Task) do
                      this.t1 := t1
                      this.t2 := t2
                      this.t3 := t3
                    end
                  feature
                    t1: Task
                    t2: Task
                    t3: Task

                    find_by_id(task_id: String): String
                      require
                        id_present: task_id /= \"\"
                      do
                        if t1.id = task_id then
                          result := t1.status
                        elseif t2.id = task_id then
                          result := t2.status
                        elseif t3.id = task_id then
                          result := t3.status
                        else
                          result := \"NOT_FOUND\"
                        end
                      ensure
                        declared_result:
                          result = \"PENDING\" or
                          result = \"IN_TRANSIT\" or
                          result = \"DELIVERED\" or
                          result = \"FAILED\" or
                          result = \"NOT_FOUND\"
                      end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-later-class-field-name-does-not-poison-earlier-method-call-resolution
  (testing "A later class field named v2 should not change the type of an earlier class field named v2"
    (let [code "class Evolution_Result
                  create
                    make(status: String, value: String, mode: String) do
                      this.status := status
                      this.value := value
                      this.mode := mode
                    end
                  feature
                    status: String
                    value: String
                    mode: String
                  end

                class Rollout_Config
                  feature
                    use_v2: Boolean
                  end

                class Delivery_Policy_V1
                  feature
                    decide_priority(tier: String): String
                      do
                        result := \"FAST_TRACK\"
                      end
                  end

                class Delivery_Policy_V2
                  feature
                    decide_priority(tier: String, risk_score: Integer): String
                      do
                        result := \"SAFE_TRACK\"
                      end
                  end

                class Delivery_Evolution_Service
                  create
                    make(v1: Delivery_Policy_V1, v2: Delivery_Policy_V2, config: Rollout_Config) do
                      this.v1 := v1
                      this.v2 := v2
                      this.config := config
                    end
                  feature
                    v1: Delivery_Policy_V1
                    v2: Delivery_Policy_V2
                    config: Rollout_Config
                    route_mode(tier: String, risk_score: Integer): Evolution_Result
                      do
                        if config.use_v2 then
                          result := create Evolution_Result.make(\"OK\", v2.decide_priority(tier, risk_score), \"V2\")
                        else
                          result := create Evolution_Result.make(\"OK\", v1.decide_priority(tier), \"V1\")
                        end
                      end
                  end

                class Knowledge_Query_V2
                  feature
                    run(query: String, intent: String): String
                      do
                        result := \"DOC:K-FAST\"
                      end
                  end

                class Knowledge_Compatibility_Adapter
                  create
                    make(v2: Knowledge_Query_V2) do
                      this.v2 := v2
                    end
                  feature
                    v2: Knowledge_Query_V2
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-nil-equality
  (testing "Equality comparison with nil should type check"
    (let [code "class Test
                  feature
                    is_nil(x: String): Boolean
                    do
                      result := x = nil
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

(deftest test-function-typecheck
  (testing "Function definitions and calls should typecheck"
    (let [code "function increment(x: Integer): Integer
do
  result := x + 1
end
class Test
  feature
    demo() do
      let y: Integer := increment(10)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest test-function-arg-mismatch
  (testing "Function call with wrong argument type should fail"
    (let [code "function increment(x: Integer): Integer
do
  result := x + 1
end
class Test
  feature
    demo() do
      let y: Integer := increment(\"oops\")
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result)))))) 

(deftest test-explicit-generic-function-call-infers-type-params-from-arguments
  (testing "Explicit generic free-function calls infer type parameters from argument types"
    (let [code "function zip[T](a: Array[T], b: Array[T], f: Function): Array[T]
do
  result := a
end
function add(a: Integer, b: Integer): Integer
do
  result := a + b
end
class Test
  feature
    demo() do
      let values: Array[Integer] := zip([1, 2, 3], [4, 5, 6], add)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result) (pr-str result)))))

(deftest test-explicit-generic-anonymous-function-typechecks
  (testing "Explicit generic anonymous functions typecheck"
    (let [code "class Test
  feature
    demo() do
      let id := fn[T](x: T): T do
        result := x
      end
      let y: Integer := id(10)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result) (pr-str result)))))

(deftest test-anonymous-function-let-infers-function-type
  (testing "Unannotated let bindings infer Function for anonymous functions"
    (let [code "class Test
  feature
    demo() do
      let transform := fn(x: Integer): Integer do
        result := x + x
      end
      transform := fn(x: Integer): Integer do
        result := x * x
      end
      let y: Integer := transform(5)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result) (pr-str result)))))

(deftest test-console-read-line-allows-prompt
  (testing "Console.read_line accepts an optional String prompt"
    (let [code "class Test
  feature
    demo() do
      let con: Console := create Console
      let name: String := con.read_line(\"Name: \")
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result) (pr-str result)))))

(deftest test-attached-non-scalar-return-requires-result-assignment
  (testing "Attached non-scalar return types must definitely assign result"
    (let [code "class Test
  feature
    bad(): Array[Integer] do
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"does not definitely assign result on all returning paths" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-detachable-non-scalar-return-may-omit-result-assignment
  (testing "Detachable non-scalar return types may omit result assignment"
    (let [code "class Test
  feature
    ok(): ?Array[Integer] do
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result) (pr-str result)))))

(deftest test-string-concatenation-typecheck
  (testing "String concatenation with + should typecheck"
    (let [code "class Test
                  feature
                    greet(name: String): String
                    do
                      result := \"hello \" + name
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-division-result-types
  (testing "Division is integral for integral operands and Real otherwise"
    (let [code "class Test
                  feature
                    demo() do
                      let i: Integer := 10 / 3
                      let r: Real := 10 / 3.0
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-power-result-types
  (testing "Exponentiation is integral for integral operands and Real otherwise"
    (let [code "class Test
                  feature
                    demo() do
                      let i: Integer := 2 ^ 8
                      let r: Real := 2.0 ^ 8
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-mixed-numeric-arithmetic-widens
  (testing "Mixed numeric arithmetic widens to a common numeric type"
    (let [code "class Test
                  feature
                    demo() do
                      let r1: Real := 10 + 3.5
                      let r2: Real := 10 * 3.5
                      let j: Integer64 := \"3\".to_integer64()
                      let i64: Integer64 := 10 + j
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-boolean-operators
  (testing "Boolean operators should require Boolean operands"
    (let [code "class Test
                  feature
                    bool_op(x, y: Boolean): Boolean
                    do
                      result := x and y
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
                      result := x / y
                    ensure
                      positive: result >= 0
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

(deftest test-self-inheritance-fails
  (testing "Inheriting from self should fail with a real type error"
    (let [code "class C inherit C end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"cannot inherit from itself" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-cyclic-inheritance-fails
  (testing "Inheritance cycles should fail with a real type error"
    (let [code "class A inherit B end

class B inherit A end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"Cyclic inheritance detected" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-array-filled-constructor-types
  (testing "Array.filled typechecks with inferred or explicit element type"
    (let [ok-code "class Test
  feature
    demo() do
      let xs: Array[Integer] := create Array.filled(3, 0)
    end
end"
          bad-code "class Test
  feature
    demo() do
      let xs: Array[Integer] := create Array.filled(3, \"oops\")
    end
end"]
      (is (:success (tc/type-check (p/ast ok-code))))
      (is (not (:success (tc/type-check (p/ast bad-code))))))))

(deftest test-string-chars-types
  (testing "String.chars typechecks as Array[Char]"
    (let [code "class Test
  feature
    demo() do
      let xs: Array[Char] := \"cat\".chars()
      let ch: Char := xs.get(1)
    end
end"]
      (is (:success (tc/type-check (p/ast code)))))))

(deftest test-string-to-bytes-types
  (testing "String.to_bytes typechecks as Array[Integer]"
    (let [code "class Test
  feature
    demo() do
      let xs: Array[Integer] := \"cat\".to_bytes()
      let b: Integer := xs.get(1)
    end
end"]
      (is (:success (tc/type-check (p/ast code)))))))

;; Let type inference tests

(deftest test-let-without-type-annotation-succeeds
  (testing "Let without type annotation should infer the variable type"
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
      (is (:success result))
      (is (empty? (:errors result))))))

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

(deftest test-let-without-type-create-expression-succeeds
  (testing "Let without type annotation on create expression should infer the variable type"
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
      (is (:success result))
      (is (empty? (:errors result))))))

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
  (testing "Method using result without return type should fail"
    (let [code "class Test
                  feature
                    compute(x: Integer)
                    do
                      result := x + 1
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result)))
      (is (some #(re-find #"does not declare a return type" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-method-using-result-with-return-type-succeeds
  (testing "Method using result with return type should pass"
    (let [code "class Test
                  feature
                    compute(x: Integer): Integer
                    do
                      result := x + 1
                    end
                  end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest test-method-without-result-no-return-type-succeeds
  (testing "Method not using result without return type should pass"
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
  (testing "Method referencing result in postcondition must declare return type"
    (let [code "class Test
                  feature
                    compute(x: Integer)
                    do
                      result := x * 2
                    ensure
                      positive: result > 0
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

(deftest test-string-conversion-methods-typecheck
  (testing "String conversion methods resolve to concrete numeric types"
    (let [code "class Test
  feature
    parse_values() do
      let i: Integer := \"123\".to_integer()
      let i64: Integer64 := \"123\".to_integer64()
      let r: Real := \"3.14\".to_real()
      let d: Decimal := \"42.5\".to_decimal()
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-string-to-real-not-integer
  (testing "String.to_real should not type-check as Integer"
    (let [code "class Test
  feature
    bad() do
      let i: Integer := \"3.14\".to_real()
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (seq (:errors result))))))

(deftest test-attachable-field-without-constructor-fails
  (testing "Attachable class field must be initialized by constructors"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end

class B
  feature
    a: A
    show() do
      a.show()
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"Attachable fields must be initialized by constructors" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-detachable-field-without-constructor-succeeds
  (testing "Detachable class field is allowed to remain uninitialized"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end

class B
  feature
    a: ?A
    show() do
      print(\"ok\")
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-attachable-field-must-be-initialized-by-all-constructors
  (testing "Every constructor must initialize attachable fields"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end

class B
  feature
    a: A
  create
    make_ok() do
      a := create A
    end
    make_bad() do
      print(\"skip\")
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"must initialize attachable fields: a" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-detachable-cannot-assign-to-attachable
  (testing "A detachable value cannot be assigned to attachable variable"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end

class B
  feature
    src: ?A
  create
    make() do
      src := create A
    end
    demo() do
      let dst: A := src
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"Cannot assign \?A to variable 'dst' of type A" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-detachable-feature-access-requires-nil-guard
  (testing "Calling a feature on detachable object without nil-guard should fail"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end

class B
  feature
    a: ?A
    demo() do
      a.show()
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"Cannot call feature 'show' on detachable" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-type-errors-include-source-location
  (testing "Type errors are reported with AST source line and column when available"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end

class B
  feature
    a: ?A
    demo() do
      a.show()
    end
end"
          result (tc/type-check (p/ast code))
          formatted (map tc/format-type-error (:errors result))]
      (is (not (:success result)))
      (is (some #(re-find #"Type error at line 12, column 7: Cannot call feature 'show' on detachable" %)
                formatted)))))

(deftest test-convert-in-and-condition-refines-then-branch
  (testing "Convert bindings nested under `and` are attached in the then branch"
    (let [code "class Node
  feature
    left: ?Node
end

function is_red(node: ?Node): Boolean do
  result := node /= nil
end

function fix(node: Node)
do
  if is_red(node.left) and convert node.left to left_child: Node then
    if is_red(left_child.left) then
      print(\"ok\")
    end
  end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-detachable-feature-access-with-nil-guard-succeeds
  (testing "Calling a feature on detachable object inside `if a /= nil` should pass"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end

class B
  feature
    a: ?A
    demo() do
      if a /= nil then
        a.show()
      end
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-detachable-feature-access-with-else-nil-guard-succeeds
  (testing "Calling a feature on detachable object inside the else branch of `if a = nil` should pass"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end

class B
  feature
    a: ?A
    demo() do
      if a = nil then
        print(\"nil\")
      else
        a.show()
      end
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-detachable-feature-access-in-elseif-after-nil-guard-succeeds
  (testing "Elseif conditions inherit non-nil refinement after `if a = nil`"
    (let [code "class Node [K -> Comparable, V]
  create
    make(key: K, value: V) do
      this.key := key
      this.value := value
      this.left := nil
      this.right := nil
    end
  feature
    key: K
    value: ?V
    left: ?Node[K, V]
    right: ?Node[K, V]
end

function search(node: ?Node[K, V], key: K): ?V
do
  if node = nil then
    result := nil
  elseif key < node.key then
    result := search(node.left, key)
  elseif key > node.key then
    result := search(node.right, key)
  else
    result := node.value
  end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-detachable-value-refines-in-elseif-after-nil-guard-succeeds
  (testing "Elseif branches can pass a nil-guarded detachable variable as an attachable value"
    (let [code "class Node [K -> Comparable, V]
  create
    make(key: K, value: V) do
      this.key := key
      this.value := value
      this.left := nil
      this.right := nil
    end
  feature
    key: K
    value: ?V
    left: ?Node[K, V]
    right: ?Node[K, V]

    set_left(n: ?Node[K, V]) do
      this.left := n
    end

    set_right(n: ?Node[K, V]) do
      this.right := n
    end

    set_value(v: V) do
      this.value := v
    end
end

function rebalance(node: Node[K, V]): Node[K, V]
do
  result := node
end

function insert(node: ?Node[K, V], key: K, value: V): Node[K, V]
do
  if node = nil then
    result := create Node[K, V].make(key, value)
  elseif key < node.key then
    node.set_left(insert(node.left, key, value))
    result := rebalance(node)
  elseif key > node.key then
    node.set_right(insert(node.right, key, value))
    result := rebalance(node)
  else
    node.set_value(value)
    result := node
  end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result) (pr-str (:errors result)))
      (is (empty? (:errors result))))))

(deftest test-default-create-disallowed-when-constructors-exist
  (testing "create B without constructor should fail if class B defines constructors"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end

class B
  feature
    x: Integer
    a: A
  create
    m1(a: A) do
      x := 10
      this.a := a
    end
    m2(a: A) do
      this.a := a
      x := 20
    end
  feature
    show() do
      print(x)
      a.show()
    end
end

class Main
  feature
    run() do
      let b: B := create B
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"defines constructors; use an explicit constructor call" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-explicit-create-allowed-when-constructors-exist
  (testing "create B.m1(...) should pass when class B defines constructors"
    (let [code "class A
  feature
    show() do
      print(\"A\")
    end
end

class B
  feature
    x: Integer
    a: A
  create
    m1(a: A) do
      x := 10
      this.a := a
    end
  feature
    show() do
      print(x)
      a.show()
    end
end

class Main
  feature
    run() do
      let a: A := create A
      let b: B := create B.m1(a)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-inherited-constructor-create
  (testing "Constructor lookup in create() supports inheritance"
    (let [code "class A
  feature
    x: Integer
  create
    make(x: Integer) do
      this.x := x
    end
end

class B inherit A
end

class Test
  feature
    demo() do
      let b: A := create B.make(20)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-deferred-class-direct-instantiation-disallowed
  (testing "create A should fail when A is deferred"
    (let [code "deferred class A
  feature
    f(i: Integer): Boolean do end
end

class Main
  feature
    demo() do
      let a: A := create A
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (not (:success result)))
      (is (some #(re-find #"Cannot instantiate deferred class A" %)
                (map tc/format-type-error (:errors result)))))))

(deftest test-deferred-parent-child-instantiation-allowed
  (testing "create B is valid when B inherits deferred A and assigned to A"
    (let [code "deferred class A
  feature
    f(i: Integer): Boolean do end
end

class B inherit A
  feature
    f(i: Integer): Boolean do
      result := i > 0
    end
end

class Main
  feature
    demo() do
      let a: A := create B
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-builtin-comparable-hashable-constraints
  (testing "Built-in scalar types satisfy Comparable and Hashable generic constraints"
    (let [code "class Sorted_Box [T -> Comparable]
  feature
    value: T
  create
    make(v: T) do
      value := v
    end
end

class Hash_Box [T -> Hashable]
  feature
    value: T
  create
    make(v: T) do
      value := v
    end
end

class Main
  feature
    demo() do
      let s1: Sorted_Box[String] := create Sorted_Box[String].make(\"x\")
      let s2: Sorted_Box[Integer] := create Sorted_Box[Integer].make(10)
      let h1: Hash_Box[Boolean] := create Hash_Box[Boolean].make(true)
      let h2: Hash_Box[Char] := create Hash_Box[Char].make(#A)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-array-sort-requires-sortable-elements
  (testing "Array.sort accepts built-in sortable types and Comparable classes"
    (let [ok-code "class Box inherit Comparable
  feature
    value: Integer
  create
    make(value: Integer) do
      this.value := value
    end
  feature
    compare(other: Box): Integer do
      result := value.compare(other.value)
    end
end

class Main
  feature
    demo() do
      let xs: Array[Integer] := [10, 2, 3]
      let ys: Array[Box] := [create Box.make(2), create Box.make(1)]
      let a: Array[Integer] := xs.sort
      let b: Array[Box] := ys.sort
    end
end"
          ok-result (tc/type-check (p/ast ok-code))
          bad-code "class Box
  feature
    value: Integer
  create
    make(value: Integer) do
      this.value := value
    end
end

class Main
  feature
    demo() do
      let ys: Array[Box] := [create Box.make(2), create Box.make(1)]
      let b := ys.sort
    end
end"
          bad-result (tc/type-check (p/ast bad-code))]
      (is (:success ok-result))
      (is (empty? (:errors ok-result)))
      (is (false? (:success bad-result)))
      (is (some #(str/includes? (tc/format-type-error %) "Array.sort requires elements of a built-in sortable type or Comparable")
                (:errors bad-result))))))

(deftest test-array-sort-with-comparator-function
  (testing "Array.sort(compareFn) accepts a Function comparator and returns Array[T]"
    (let [ok-code "class Main
  feature
    demo() do
      let xs: Array[Integer] := [10, 2, 3]
      let sorted: Array[Integer] := xs.sort(fn(a: Integer, b: Integer): Integer do
        result := b - a
      end)
    end
end"
          bad-code "class Main
  feature
    demo() do
      let xs: Array[Integer] := [10, 2, 3]
      let sorted := xs.sort(123)
    end
end"
          ok-result (tc/type-check (p/ast ok-code))
          bad-result (tc/type-check (p/ast bad-code))]
      (is (:success ok-result))
      (is (empty? (:errors ok-result)))
      (is (false? (:success bad-result)))
      (is (some #(str/includes? (tc/format-type-error %) "Expected Function, got Integer")
                (:errors bad-result))))))

(deftest test-array-reverse-types-as-array-expression
  (testing "Array.reverse remains an Array[T] expression so it composes with type_of"
    (let [code "class Main
  feature
    demo(): String
    do
      let numbers: Array[Integer] := [1, 2, 3]
      result := type_of(numbers.reverse)
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-regex-validate-types-as-boolean-expression
  (testing "regex_validate remains a Boolean expression so it composes with print and control flow"
    (let [code "class Main
  feature
    demo()
    do
      if regex_validate(\"a+\", \"\") then
        print(\"ok\")
      end
    end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-contextual-map-any-literal-typing
  (testing "typed collection literals can widen nested heterogeneous map values to Any"
    (let [code "let books: Array[Map[String, Any]] := [
  {\"title\": \"Dune\", \"author\": \"Frank Herbert\", \"year\": 1965},
  {\"title\": \"Neuromancer\", \"author\": \"William Gibson\", \"year\": 1984},
  {\"title\": \"Foundation\", \"author\": \"Isaac Asimov\", \"year\": 1951}
]"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))

(deftest test-user-defined-methods-can-overload-by-arity
  (testing "user-defined methods with the same name dispatch by arity"
    (let [code "class Greeter
feature
  greet(): String
  do
    result := \"hello\"
  end

  greet(name: String): String
  do
    result := \"hello, \" + name
  end
end

class Main
feature
  demo() do
    let g: Greeter := create Greeter
    print(g.greet())
    print(g.greet(\"Ada\"))
  end
end"
          result (tc/type-check (p/ast code))]
      (is (:success result))
      (is (empty? (:errors result))))))
