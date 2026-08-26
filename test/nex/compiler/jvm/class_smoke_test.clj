(ns nex.compiler.jvm.class-smoke-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.compiler.jvm.runtime :as runtime]
            [nex.parser :as p]
            [nex.repl :as repl]))

(def ^:private counter-program
  "class Counter
feature
  value: Integer

  set_to(v: Integer): Integer
  do
    this.value := v
    result := this.value
  end

  bump(): Integer
  do
    this.value := this.value + 1
    result := this.value
  end

  bump_twice(): Integer
  do
    result := bump() + bump()
  end

  current(): Integer
  do
    result := this.value
  end
end")

(def ^:private counter-with-constructor-program
  "class Counter
create
  with_value(v: Integer) do
    this.value := v
  end
feature
  value: Integer

  bump(): Integer
  do
    this.value := this.value + 1
    result := this.value
  end

  current(): Integer
  do
    result := this.value
  end
end")

(def ^:private noted-counter-program
  "class Counter
note \"counter docs\"
feature
  value: Integer note \"value field\"

  current(): Integer note \"current value\"
  do
    result := this.value
  end
end")

(def ^:private frame-constants-program
  "class Frame
feature
  HELLO: String = \"hello\"
  MAX_WIDTH = 450

  demo(): Integer
  do
    print(HELLO)
    print(Frame.MAX_WIDTH)
    result := MAX_WIDTH + 10
  end
end")

(def ^:private deferred-shape-program
  "deferred class Shape
feature
  area(): Real do end
end

class Square inherit Shape
create
  with_side(v: Real) do
    this.side := v
  end
feature
  side: Real

  area(): Real
  do
    result := side * side
  end
end")

(def ^:private super-program
  "class A
create
  make(v: Integer) do
    this.x := v
  end
feature
  x: Integer

  show(): String
  do
    result := \"A=\" + x
  end
end

class B inherit A
create
  make(v: Integer, extra: Integer) do
    super.make(v)
    this.y := extra
  end
feature
  y: Integer

  show(): String
  do
    result := super.show + \",B=\" + y
  end
end")

(def ^:private box-program
  "class Box[T]
create
  with_value(v: T) do
    this.value := v
  end
feature
  value: T

  get: T
  do
    result := value
  end
end")

(def ^:private convert-program
  "class Vehicle
feature
  label(): String
  do
    result := \"vehicle\"
  end
end

class Car inherit Vehicle
feature
  label(): String
  do
    result := \"car\"
  end
end")

(def ^:private multi-parent-program
  "class A
create
  make_a(v: Integer) do
    this.x := v
  end
feature
  x: Integer

  show_a(): Integer
  do
    result := x
  end
end

class B
create
  make_b(v: Integer) do
    this.y := v
  end
feature
  y: Integer

  show_b(): Integer
  do
    result := y
  end
end

class C inherit A, B
create
  make(vx, vy: Integer) do
    A.make_a(vx)
    B.make_b(vy)
  end
feature
  sum(): Integer
  do
    result := show_a() + show_b()
  end

  parent_sum(): Integer
  do
    result := A.show_a() + B.show_b()
  end
end")

(def ^:private contract-counter-program
  "class Counter
feature
  value: Integer

  bump(): Integer
    require
      non_negative: value >= 0
    do
      this.value := this.value + 1
      result := this.value
    ensure
      advanced: value = old value + 1
      result_matches: result = value
    end

  break_bump(): Integer
    do
      this.value := this.value + 2
      result := this.value
    ensure
      advanced: value = old value + 1
    end
end")

(def ^:private inherited-contract-program
  "class A
feature
  f(x: Integer): Integer
    require
      base_positive: x > 0
    do
      result := 5
    ensure
      base_result: result = 5
    end
end

class B inherit A
feature
  f(x: Integer): Integer
    require
      local_negative: x < 0
    do
      result := 5
    end
end

class C inherit A
feature
  f(x: Integer): Integer
    do
      result := 4
    end
end")

(def ^:private multi-parent-contract-program
  "class A
feature
  f(x: Integer): Integer
    require
      a_ok: x > 10
    do
      result := x
    end

  g(): Integer
    do
      result := 1
    ensure
      a_positive: result > 0
    end
end

class C
feature
  f(x: Integer): Integer
    require
      c_ok: x < -10
    do
      result := x
    end

  g(): Integer
    do
      result := -1
    ensure
      c_negative: result < 0
    end
end

class D inherit A, C
feature
  f(x: Integer): Integer
    require
      d_ok: x = 0
    do
      result := x
    end

  g(): Integer
    do
      result := 1
    end
end")

(def ^:private old-pair-program
  "class Pair
create
  make(x0, y0: Integer) do
    this.x := x0
    this.y := y0
  end
feature
  x: Integer
  y: Integer

  move_x(dx: Integer): Integer
  do
    this.x := this.x + dx
    result := this.x
  ensure
    x_moved: x = old x + dx
    y_unchanged: y = old y
    sum_consistent: x + y = old x + old y + dx
  end
end")

(def ^:private invariant-account-program
  "class Account
create
  with_balance(v: Integer) do
    this.balance := v
  end
feature
  balance: Integer

  set_balance(v: Integer): Integer
  do
    this.balance := v
    result := this.balance
  end
invariant
  non_negative: balance >= 0
end

class PositiveOnly
feature
  value: Integer
invariant
  positive: value > 0
end")

(def ^:private inherited-invariant-program
  "class A
feature
  x: Integer

  set_x(v: Integer): Integer
  do
    this.x := v
    result := this.x
  end
invariant
  parent_positive: x > 0
end

class B inherit A
create
  make(x0, y0: Integer) do
    set_x(x0)
    this.y := y0
  end
feature
  y: Integer

  break_parent(): Integer
  do
    set_x(0)
    result := y
  end
invariant
  child_positive: y > 0
end")

(defn- root-cause
  [t]
  (loop [x t]
    (if-let [cause (.getCause ^Throwable x)]
      (recur cause)
      x)))

(deftest compiled-class-batch-smoke-test
  (testing "compiled helper can define a simple class, create an instance, mutate it through methods, and read a field"
    (let [session (compiled-repl/make-session)
          ast (p/ast (str counter-program
                          "\n\n"
                          "let c: Counter := create Counter\n"
                          "c.set_to(4)\n"
                          "c.bump()\n"
                          "c.value"))
          result (compiled-repl/compile-and-eval! session ast)]
      (is (:compiled? result))
      (is (= 5 (:result result)))
      (is (contains? @(:class-asts session) "Counter"))
      (is (= "Counter" (runtime/state-get-type (:state session) "c")))
      (is (some? (runtime/state-get-value (:state session) "c"))))))

(deftest compiled-class-cross-cell-smoke-test
  (testing "compiled helper keeps class and instance state coherent across cells"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str counter-program
                                                                     "\n\n"
                                                                     "let c: Counter := create Counter\n"
                                                                     "c.set_to(10)")))
          call-result (compiled-repl/compile-and-eval! session
                                                       (p/ast "c.bump_twice()"))
          field-result (compiled-repl/compile-and-eval! session
                                                        (p/ast "c.current()"))]
      (is (:compiled? define-result))
      (is (:compiled? call-result))
      (is (:compiled? field-result))
      (is (= 23 (:result call-result)))
      (is (= 12 (:result field-result))))))

(deftest compiled-class-constructor-and-field-assign-test
  (testing "compiled helper rejects top-level explicit target field assignment"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str counter-with-constructor-program
                                                                     "\n\n"
                                                                     "let c: Counter := create Counter.with_value(5)\n"
                                                                     "c.value")))
          assign-ex (try
                      (compiled-repl/compile-and-eval! session
                                                       (p/ast "c.value := 9\nc.bump()"))
                      nil
                      (catch Throwable t
                        (root-cause t)))
          field-result (compiled-repl/compile-and-eval! session
                                                        (p/ast "c.current()"))]
      (is (:compiled? define-result))
      (is (:compiled? field-result))
      (is (= 5 (:result define-result)))
      (is (some? assign-ex))
      (is (re-find #"Cannot assign to field value outside of class Counter" (str assign-ex)))
      (is (= 5 (:result field-result))))))

(deftest compiled-repl-class-definition-test
  (testing "compiled REPL backend can define and use a simple class without deopting"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx (repl/init-repl-context)
            define-output (with-out-str
                            (repl/eval-code ctx (str counter-program
                                                     "\n\n"
                                                     "let c: Counter := create Counter\n"
                                                     "c.set_to(7)")))
            call-output (with-out-str
                          (repl/eval-code ctx "c.bump()"))
            field-output (with-out-str
                           (repl/eval-code ctx "c.value"))
            session @repl/*compiled-repl-session*]
        (is (str/includes? define-output "7"))
        (is (contains? @(:class-asts session) "Counter"))
        (is (= "Counter" (runtime/state-get-type (:state session) "c")))
        (is (str/includes? call-output "8"))
        (is (str/includes? field-output "8"))))))

(deftest compiled-class-constants-test
  (testing "compiled helper supports class constants as static fields"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str frame-constants-program
                                                                     "\n\n"
                                                                     "let f: Frame := create Frame\n"
                                                                     "f.demo()")))
          const-result (compiled-repl/compile-and-eval! session
                                                        (p/ast "Frame.MAX_WIDTH"))]
      (is (:compiled? define-result))
      (is (:compiled? const-result))
      (is (= ["\"hello\"" "450"] (:output define-result)))
      (is (= 460 (:result define-result)))
      (is (= 450 (:result const-result))))))

(deftest compiled-super-call-smoke-test
  (testing "compiled helper supports super constructor and method calls on the composition model"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str super-program
                                                                     "\n\n"
                                                                     "let b: B := create B.make(10, 3)\n"
                                                                     "b.show()")))
          cross-cell-result (compiled-repl/compile-and-eval! session
                                                             (p/ast "b.show()"))]
      (is (:compiled? define-result))
      (is (:compiled? cross-cell-result))
      (is (= "A=10,B=3" (:result define-result)))
      (is (= "A=10,B=3" (:result cross-cell-result))))))

(deftest compiled-generic-class-create-smoke-test
  (testing "compiled helper supports erased generic class creation and method calls"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str box-program
                                                                     "\n\n"
                                                                     "let b: Box[String] := create Box[String].with_value(\"hello\")\n"
                                                                     "b.get")))
          field-result (compiled-repl/compile-and-eval! session
                                                        (p/ast "b.value"))]
      (is (:compiled? define-result))
      (is (:compiled? field-result))
      (is (= "hello" (:result define-result)))
      (is (= "hello" (:result field-result))))))

(deftest compiled-convert-smoke-test
  (testing "compiled helper supports convert in guard and standalone statement forms"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str convert-program
                                                                     "\n\n"
                                                                     "let v: Vehicle := create Car\n"
                                                                     "if convert v to my_car:Car then\n"
                                                                     "  my_car.label()\n"
                                                                     "else\n"
                                                                     "  \"fail\"\n"
                                                                     "end")))
          stmt-result (compiled-repl/compile-and-eval! session
                                                       (p/ast "convert v to again:Car\nagain.label()"))]
      (is (:compiled? define-result))
      (is (:compiled? stmt-result))
      (is (= "car" (:result define-result)))
      (is (= "car" (:result stmt-result))))))

(deftest compiled-note-annotation-smoke-test
  (testing "compiled helper ignores note annotations as metadata and still compiles classes"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str noted-counter-program
                              "\nlet c := create Counter\n"
                              "c.current()")))]
      (is (:compiled? result))
      (is (= 0 (:result result))))))

(deftest compiled-detachable-when-smoke-test
  (testing "compiled helper supports detachable references with nil-checked when branches"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str counter-with-constructor-program
                              "\nlet c: ?Counter := create Counter.with_value(7)\n"
                              "when c = nil then 0 else c.current() end")))]
      (is (:compiled? result))
      (is (= 7 (:result result))))))

(deftest compiled-method-contracts-and-old-smoke-test
  (testing "compiled helper enforces require/ensure and supports old in method postconditions"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str contract-counter-program
                                                                     "\n\n"
                                                                     "let c: Counter := create Counter\n"
                                                                     "c.bump()")))
          fail-ex (try
                    (compiled-repl/compile-and-eval! session (p/ast "c.break_bump()"))
                    nil
                    (catch Throwable t
                      (root-cause t)))]
      (is (:compiled? define-result))
      (is (= 1 (:result define-result)))
      (is (some? fail-ex))
      (is (re-find #"Postcondition violation: advanced" (str fail-ex))))))

(deftest compiled-inherited-method-contracts-smoke-test
  (testing "compiled helper composes inherited method preconditions and postconditions"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str inherited-contract-program
                                                                     "\n\n"
                                                                     "let b: B := create B\n"
                                                                     "let c: C := create C\n"
                                                                     "0")))
          base-ok (compiled-repl/compile-and-eval! session (p/ast "b.f(1)"))
          local-ok (compiled-repl/compile-and-eval! session (p/ast "b.f(-1)"))
          bad-pre-ex (try
                       (compiled-repl/compile-and-eval! session (p/ast "b.f(0)"))
                       nil
                       (catch Throwable t
                         (root-cause t)))
          bad-post-ex (try
                        (compiled-repl/compile-and-eval! session (p/ast "c.f(1)"))
                        nil
                        (catch Throwable t
                          (root-cause t)))]
      (is (:compiled? define-result))
      (is (= 0 (:result define-result)))
      (is (= 5 (:result base-ok)))
      (is (= 5 (:result local-ok)))
      (is (some? bad-pre-ex))
      (is (re-find #"Precondition violation: inherited_or_local_require" (str bad-pre-ex)))
      (is (some? bad-post-ex))
      (is (re-find #"Postcondition violation: base_result" (str bad-post-ex))))))

(deftest compiled-multiple-inherited-method-contracts-smoke-test
  (testing "compiled helper composes overridden method contracts across all inherited parents"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str multi-parent-contract-program
                                                                     "\n\n"
                                                                     "let d: D := create D\n"
                                                                     "0")))
          from-a (compiled-repl/compile-and-eval! session (p/ast "d.f(20)"))
          from-c (compiled-repl/compile-and-eval! session (p/ast "d.f(-20)"))
          from-local (compiled-repl/compile-and-eval! session (p/ast "d.f(0)"))
          bad-pre-ex (try
                       (compiled-repl/compile-and-eval! session (p/ast "d.f(5)"))
                       nil
                       (catch Throwable t
                         (root-cause t)))
          bad-post-ex (try
                        (compiled-repl/compile-and-eval! session (p/ast "d.g()"))
                        nil
                        (catch Throwable t
                          (root-cause t)))]
      (is (:compiled? define-result))
      (is (= 0 (:result define-result)))
      (is (= 20 (:result from-a)))
      (is (= -20 (:result from-c)))
      (is (= 0 (:result from-local)))
      (is (some? bad-pre-ex))
      (is (re-find #"Precondition violation: inherited_or_local_require" (str bad-pre-ex)))
      (is (some? bad-post-ex))
      (is (re-find #"Postcondition violation: c_negative" (str bad-post-ex))))))

(deftest compiled-old-field-expression-smoke-test
  (testing "compiled helper supports the full interpreter-style old model for field-based postconditions"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str old-pair-program
                                                                     "\n\n"
                                                                     "let p: Pair := create Pair.make(2, 5)\n"
                                                                     "p.move_x(3)")))
          y-result (compiled-repl/compile-and-eval! session
                                                    (p/ast "p.y"))]
      (is (:compiled? define-result))
      (is (:compiled? y-result))
      (is (= 5 (:result define-result)))
      (is (= 5 (:result y-result))))))

(def ^:private old-array-field-program
  "class Basket
create
  make() do
    items := []
  end
feature
  items: Array[Integer]

  add_item(x: Integer)
  do
    items.add(x)
  ensure
    grew: items.length = old items.length + 1
  end
end")

(deftest compiled-old-array-field-length-snapshots-value-not-reference-test
  (testing "`old items.length` snapshots the Array field's membership at method entry, not a live reference to the same mutable ArrayList the method body then mutates in place"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str old-array-field-program
                                                                     "\n\n"
                                                                     "let b: Basket := create Basket.make()\n"
                                                                     "b.add_item(1)\n"
                                                                     "b.add_item(2)\n"
                                                                     "b.items.length")))]
      (is (:compiled? define-result))
      (is (= 2 (:result define-result))))))

(deftest compiled-class-invariants-smoke-test
  (testing "compiled helper enforces class invariants on creation and method exit"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str invariant-account-program
                                                                     "\n\n"
                                                                     "let a: Account := create Account.with_balance(10)\n"
                                                                     "a.balance")))
          bad-method-ex (try
                          (compiled-repl/compile-and-eval! session (p/ast "a.set_balance(-1)"))
                          nil
                          (catch Throwable t
                            (root-cause t)))
          bad-default-create-ex (try
                                  (compiled-repl/compile-and-eval! session
                                                                   (p/ast "let p: PositiveOnly := create PositiveOnly"))
                                  nil
                                  (catch Throwable t
                                    (root-cause t)))]
      (is (:compiled? define-result))
      (is (= 10 (:result define-result)))
      (is (some? bad-method-ex))
      (is (re-find #"Class invariant violation: non_negative" (str bad-method-ex)))
      (is (some? bad-default-create-ex))
      (is (re-find #"Class invariant violation: positive" (str bad-default-create-ex))))))

(def ^:private reentrant-invariant-program
  ;; The invariant calls a public method on `this`. That method's exit would
  ;; itself trigger invariant validation, so without a re-entrancy guard the
  ;; native __invariant would recurse forever. The guard (matching the historical
  ;; *validating-object-state* semantics) must let the outer check run and the
  ;; inner call proceed without re-validating.
  "class Acct
feature
  bal: Integer
  doubled(): Integer do result := bal + bal end
  set(n: Integer): Integer do this.bal := n  result := this.bal end
create make(n: Integer) do this.bal := n end
invariant
  consistent: doubled() = bal + bal
end")

(deftest compiled-invariant-reentrancy-test
  (testing "an invariant that calls a public method on this does not recurse"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval!
                         session
                         (p/ast (str reentrant-invariant-program
                                     "\n\n"
                                     "let a: Acct := create Acct.make(5)\n"
                                     "a.set(10)")))]
      (is (:compiled? define-result))
      (is (= 10 (:result define-result))))))

(deftest compiled-inherited-class-invariants-smoke-test
  (testing "compiled helper validates inherited invariants through the composition model"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str inherited-invariant-program
                                                                     "\n\n"
                                                                     "let b: B := create B.make(5, 2)\n"
                                                                     "b.y")))
          bad-local-ex (try
                         (compiled-repl/compile-and-eval! session (p/ast "b.break_parent()"))
                         nil
                         (catch Throwable t
                           (root-cause t)))
          bad-delegated-ex (try
                             (compiled-repl/compile-and-eval! session (p/ast "b.set_x(0)"))
                             nil
                             (catch Throwable t
                               (root-cause t)))]
      (is (:compiled? define-result))
      (is (= 2 (:result define-result)))
      (is (some? bad-local-ex))
      (is (re-find #"Class invariant violation: parent_positive" (str bad-local-ex)))
      (is (some? bad-delegated-ex))
      (is (re-find #"Class invariant violation: parent_positive" (str bad-delegated-ex))))))

(def ^:private object-equality-invariant-program
  ;; An invariant comparing two object-typed fields with `=` must use structural
  ;; equality, like `=` does everywhere else on the compiled backend. Class
  ;; invariants are evaluated through the interpreter, which did not recognise
  ;; the compiled field instances as objects and so compared them by identity —
  ;; a violation for distinct-but-equal values. Kind is an enum field so the
  ;; comparison also spans a nested (enum) object.
  "enum union Kind
  A
  B
end

class Money
feature
  amount: Real
  kind: Kind
create make(a: Real, k: Kind) do amount := a  kind := k end
end

class Holder
feature
  x: Money
  y: Money
create make(a: Money, b: Money) do x := a  y := b end
invariant
  consistent: x = y
end")

(deftest compiled-class-invariant-object-equality-test
  (testing "a class invariant comparing object fields with `=` uses structural equality"
    (let [session (compiled-repl/make-session)
          ;; Distinct Money instances with equal value + kind: the invariant
          ;; `x = y` must hold (previously failed as an identity comparison).
          ok-result (compiled-repl/compile-and-eval!
                     session
                     (p/ast (str object-equality-invariant-program
                                 "\n\n"
                                 "let m1: Money := create Money.make(22.2, Kind.A)\n"
                                 "let m2: Money := create Money.make(22.2, Kind.A)\n"
                                 "let h: Holder := create Holder.make(m1, m2)\n"
                                 "h.x.amount")))
          ;; Genuinely unequal object fields must still trip the invariant.
          bad-ex (try
                   (compiled-repl/compile-and-eval!
                    session
                    (p/ast "let n1: Money := create Money.make(1.0, Kind.A)
                            let n2: Money := create Money.make(2.0, Kind.A)
                            let bad: Holder := create Holder.make(n1, n2)"))
                   nil
                   (catch Throwable t (root-cause t)))]
      (is (:compiled? ok-result))
      (is (= 22.2 (:result ok-result)))
      (is (some? bad-ex))
      (is (re-find #"Class invariant violation: consistent" (str bad-ex))))))

(def ^:private inherited-only-invariant-program
  ;; Derived declares no invariant of its own but inherits Base's. Validation is
  ;; gated on whether the hierarchy declares *any* invariant, so the gate must
  ;; still fire for Derived — otherwise the inherited invariant would silently
  ;; stop being enforced. Plain has no invariant anywhere and must construct
  ;; freely (the gate skips the interpreter-context rebuild for it).
  "class Base
feature
  v: Integer
  set_v(n: Integer): Integer
  do
    this.v := n
    result := this.v
  end
create make(n: Integer) do this.v := n end
invariant
  positive: v > 0
end

class Derived inherit Base
feature
  tag: Integer
create make2(n: Integer) do Base.make(n) end
end

class Plain
feature
  w: Integer
create make(n: Integer) do this.w := n end
end")

(deftest compiled-invariant-gating-inherited-still-enforced-test
  (testing "gating validation on invariant presence still enforces inherited invariants"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval!
                         session
                         (p/ast (str inherited-only-invariant-program
                                     "\n\n"
                                     "let d: Derived := create Derived.make2(5)\n"
                                     "d.v")))
          ;; Invariant-free class: constructs with any value, no false violation.
          plain-result (compiled-repl/compile-and-eval!
                        session (p/ast "let p: Plain := create Plain.make(0)\np.w"))
          ;; Derived declares no invariant, but constructing it in violation of
          ;; Base's inherited invariant must still raise.
          bad-ex (try
                   (compiled-repl/compile-and-eval!
                    session (p/ast "let bad: Derived := create Derived.make2(0)"))
                   nil
                   (catch Throwable t (root-cause t)))]
      (is (:compiled? define-result))
      (is (= 5 (:result define-result)))
      (is (= 0 (:result plain-result)))
      (is (some? bad-ex))
      (is (re-find #"Class invariant violation: positive" (str bad-ex))))))

(deftest compiled-loop-contracts-smoke-test
  (testing "compiled helper enforces loop invariants and variants"
    (let [session (compiled-repl/make-session)
          ok-result (compiled-repl/compile-and-eval! session
                                                     (p/ast "let total: Integer := 0
from
  let i := 3
invariant
  non_negative: i >= 0
variant
  i
until
  i = 0
do
  total := total + i
  i := i - 1
end
total"))
          invariant-ex (try
                         (compiled-repl/compile-and-eval! session
                                                          (p/ast "from
  let i := 2
invariant
  large: i > 5
until
  i = 0
do
  i := i - 1
end"))
                         nil
                         (catch Throwable t
                           (root-cause t)))
          variant-ex (try
                       (compiled-repl/compile-and-eval! session
                                                        (p/ast "from
  let i := 0
variant
  5
until
  i > 2
do
  i := i + 1
end"))
                       nil
                       (catch Throwable t
                         (root-cause t)))]
      (is (:compiled? ok-result))
      (is (= 6 (:result ok-result)))
      (is (re-find #"Loop invariant violation: non_negative|Loop invariant violation: large" (str invariant-ex)))
      (is (re-find #"Loop variant must decrease" (str variant-ex))))))

(deftest compiled-deferred-parent-virtual-dispatch-test
  (testing "compiled helper dispatches virtually through a deferred parent-typed reference"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str deferred-shape-program
                                                                     "\n\n"
                                                                     "let s: Shape := create Square.with_side(4.0)\n"
                                                                     "s.area()")))
          cross-cell-result (compiled-repl/compile-and-eval! session
                                                             (p/ast "s.area()"))]
      (is (:compiled? define-result))
      (is (:compiled? cross-cell-result))
      (is (= 16.0 (:result define-result)))
      (is (= 16.0 (:result cross-cell-result)))
      (is (= "Shape" (runtime/state-get-type (:state session) "s"))))))

(deftest compiled-multiple-inheritance-composition-smoke-test
  (testing "compiled helper supports multiple direct parents through composition and delegation"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str multi-parent-program
                                                                     "\n\n"
                                                                     "let c: C := create C.make(10, 20)\n"
                                                                     "c.sum()")))
          inherited-a (compiled-repl/compile-and-eval! session
                                                       (p/ast "c.show_a()"))
          inherited-b (compiled-repl/compile-and-eval! session
                                                       (p/ast "c.show_b()"))
          parent-sum (compiled-repl/compile-and-eval! session
                                                      (p/ast "c.parent_sum()"))]
      (is (:compiled? define-result))
      (is (:compiled? inherited-a))
      (is (:compiled? inherited-b))
      (is (:compiled? parent-sum))
      (is (= 30 (:result define-result)))
      (is (= 10 (:result inherited-a)))
      (is (= 20 (:result inherited-b)))
      (is (= 30 (:result parent-sum))))))

(deftest compiled-elseif-and-when-smoke-test
  (testing "compiled helper supports elseif expressions and when expressions"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str "let score: Integer := 85\n"
                              "if score >= 90 then 1 elseif score >= 80 then 2 else 3 end\n"
                              "when true then 20 else 0 end")))]
      (is (:compiled? result))
      (is (= 20 (:result result))))))

(deftest compiled-scoped-block-smoke-test
  (testing "compiled helper supports scoped do/end blocks without leaking local lets"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str "let x: Integer := 1\n"
                              "do\n"
                              "  let x: Integer := 2\n"
                              "  print(x)\n"
                              "end\n"
                              "x")))]
      (is (:compiled? result))
      (is (= ["2"] (:output result)))
      (is (= 1 (:result result))))))

(deftest compiled-scoped-block-rescue-retry-smoke-test
  (testing "compiled helper supports scoped rescue blocks with retry"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str "let count: Integer := 0\n"
                              "do\n"
                              "  count := count + 1\n"
                              "  if count < 3 then\n"
                              "    raise \"retry me\"\n"
                              "  end\n"
                              "rescue\n"
                              "  retry\n"
                              "end\n"
                              "count")))]
      (is (:compiled? result))
      (is (= 3 (:result result))))))

(deftest compiled-scoped-block-rescue-recovers-smoke-test
  (testing "compiled helper treats a rescue block without retry as handled recovery"
    (let [session (compiled-repl/make-session)]
      (let [result (compiled-repl/compile-and-eval!
                    session
                    (p/ast (str "do\n"
                                "  raise \"boom\"\n"
                                "rescue\n"
                                "  print(exception)\n"
                                "end")))]
        (is (:compiled? result))
        (is (nil? (:result result))))
      (is (= ["\"boom\""] (runtime/state-output (:state session)))))))

;; ─── `result` auto-initializes to an empty Array/Map/Set ────────────────────
;;
;; Integer/Real/Boolean/Char return types already got an implicit zero-value
;; default for `result` (result-init-stmt in lower.clj), so a method could
;; mutate `result` in place (`result := result + 1`) without an explicit
;; `result := ...` on every path. Array/Map/Set return types didn't: the
;; typechecker required an explicit assignment on every returning path
;; (attached-non-scalar-type?), and even if it hadn't, the compiled `result`
;; local was always initialized to null rather than an empty collection —
;; mutating it in place (`result.add(...)`) would NPE. Both gaps are now
;; closed for non-detachable Array/Map/Set return types, matching what
;; get-default-field-value already does for the interpreter.

(deftest compiled-array-result-auto-inits-to-empty-smoke-test
  (testing "an Array[T] result can be built by mutation alone, with no explicit result :="
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "function make_list(n: Integer): Array[Integer]\n"
                   "do\n"
                   "  from\n"
                   "    let i := 0\n"
                   "  until\n"
                   "    i >= n\n"
                   "  do\n"
                   "    result.add(i)\n"
                   "    i := i + 1\n"
                   "  end\n"
                   "end\n"
                   "print(make_list(4))")))
      (is (= ["[0, 1, 2, 3]"] (runtime/state-output (:state session)))))))

(deftest compiled-map-result-auto-inits-to-empty-smoke-test
  (testing "a Map[K, V] result can be built by mutation alone, with no explicit result :="
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "function squares(n: Integer): Map[Integer, Integer]\n"
                   "do\n"
                   "  from\n"
                   "    let i := 0\n"
                   "  until\n"
                   "    i >= n\n"
                   "  do\n"
                   "    result.put(i, i * i)\n"
                   "    i := i + 1\n"
                   "  end\n"
                   "end\n"
                   "print(squares(3))")))
      (is (= ["{0: 0, 1: 1, 2: 4}"] (runtime/state-output (:state session)))))))

(deftest compiled-set-result-auto-inits-to-empty-smoke-test
  (testing "a Set[T] result can be built by mutation alone, with no explicit result :="
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "function distinct_upto(n: Integer): Set[Integer]\n"
                   "do\n"
                   "  from\n"
                   "    let i := 0\n"
                   "  until\n"
                   "    i >= n\n"
                   "  do\n"
                   "    result.add(i)\n"
                   "    i := i + 1\n"
                   "  end\n"
                   "end\n"
                   "print(distinct_upto(3))")))
      (is (= ["#{0, 1, 2}"] (runtime/state-output (:state session)))))))

(deftest compiled-class-method-array-result-auto-inits-to-empty-smoke-test
  (testing "the same auto-init applies to a class method's Array[T] result, not just free functions"
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "class Builder\n"
                   "feature\n"
                   "  build(n: Integer): Array[Integer]\n"
                   "  do\n"
                   "    from\n"
                   "      let i := 0\n"
                   "    until\n"
                   "      i >= n\n"
                   "    do\n"
                   "      result.add(i * 2)\n"
                   "      i := i + 1\n"
                   "    end\n"
                   "  end\n"
                   "end\n"
                   "print((create Builder).build(3))")))
      (is (= ["[0, 2, 4]"] (runtime/state-output (:state session)))))))

(deftest compiled-detachable-array-result-still-defaults-to-nil-smoke-test
  (testing "a detachable ?Array[T] result keeps the null default, unlike its attached counterpart"
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "function maybe_list(x: Integer): ?Array[Integer]\n"
                   "do\n"
                   "  if x = 0 then\n"
                   "    result := [1, 2, 3]\n"
                   "  end\n"
                   "end\n"
                   "print(maybe_list(1))")))
      (is (= ["nil"] (runtime/state-output (:state session)))))))

;; Note: whether a non-collection attached reference return type still
;; requires an explicit result assignment is a typechecker concern, not a
;; lowering/emission one — compile-and-eval! here skips the typechecker
;; entirely, so that invariant is covered instead by
;; test-attached-non-scalar-return-requires-result-assignment in
;; typechecker_test.clj.

;; ─── Nested `do/rescue` blocks ──────────────────────────────────────────────
;;
;; `emit-try!` (emit.clj) used to register its own try/catch entries via
;; `visitTryCatchBlock` *before* emitting its body/rescue statements. Any
;; nested `do/rescue` inside those statements would then register its entries
;; *after* the enclosing one. The JVM dispatches to the first matching handler
;; in exception-table order, so the enclosing (less specific) handler won the
;; race for any range it shared with the nested block — the nested `rescue`
;; never ran and the exception surfaced as an unhandled crash instead. Fixed
;; by deferring the enclosing try/catch registration until after body/rescue
;; (and any nested try/catch registrations within them) are emitted.

(deftest compiled-nested-rescue-in-rescue-clause-smoke-test
  (testing "a do/rescue nested inside another rescue clause catches its own exception"
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "do\n"
                   "  raise \"OUTER\"\n"
                   "rescue\n"
                   "  do\n"
                   "    raise \"BYE\"\n"
                   "  rescue\n"
                   "    print(\"2: \" + exception)\n"
                   "  end\n"
                   "  print(\"1: \" + exception)\n"
                   "end")))
      (is (= ["\"2: BYE\"" "\"1: OUTER\""] (runtime/state-output (:state session)))))))

(deftest compiled-nested-rescue-in-body-smoke-test
  (testing "a do/rescue nested inside another try's body catches its own exception"
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "do\n"
                   "  do\n"
                   "    raise \"BYE\"\n"
                   "  rescue\n"
                   "    print(\"2: \" + exception)\n"
                   "  end\n"
                   "  raise \"OUTER\"\n"
                   "rescue\n"
                   "  print(\"1: \" + exception)\n"
                   "end")))
      (is (= ["\"2: BYE\"" "\"1: OUTER\""] (runtime/state-output (:state session)))))))

(deftest compiled-triple-nested-rescue-smoke-test
  (testing "three levels of nested do/rescue each catch their own exception and
            `exception` re-scopes to the enclosing level once a nested rescue completes"
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "do\n"
                   "  raise \"L1\"\n"
                   "rescue\n"
                   "  do\n"
                   "    raise \"L2\"\n"
                   "  rescue\n"
                   "    do\n"
                   "      raise \"L3\"\n"
                   "    rescue\n"
                   "      print(\"inner: \" + exception)\n"
                   "    end\n"
                   "    print(\"mid: \" + exception)\n"
                   "  end\n"
                   "  print(\"outer: \" + exception)\n"
                   "end")))
      (is (= ["\"inner: L3\"" "\"mid: L2\"" "\"outer: L1\""]
             (runtime/state-output (:state session)))))))

;; `retry` shares the same exception-table dispatch that the nested-rescue
;; fix above touched (a retry-signal throwable, caught by the nearest
;; enclosing try/catch), so it needed the identical fix and gets the same
;; nested-do/rescue coverage here.

(deftest compiled-retry-in-rescue-nested-inside-outer-rescue-clause-smoke-test
  (testing "retry in a do/rescue nested inside another rescue clause only re-loops the inner block"
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "let outer_count := 0\n"
                   "let inner_count := 0\n"
                   "do\n"
                   "  outer_count := outer_count + 1\n"
                   "  raise \"OUTER\"\n"
                   "rescue\n"
                   "  do\n"
                   "    inner_count := inner_count + 1\n"
                   "    if inner_count < 3 then\n"
                   "      raise \"INNER\"\n"
                   "    end\n"
                   "  rescue\n"
                   "    retry\n"
                   "  end\n"
                   "  print(\"inner_count: \" + inner_count)\n"
                   "end\n"
                   "print(\"outer_count: \" + outer_count)")))
      (is (= ["\"inner_count: 3\"" "\"outer_count: 1\""] (runtime/state-output (:state session)))))))

(deftest compiled-retry-at-both-nesting-levels-smoke-test
  (testing "retry in a do/rescue nested inside another try's body, plus a separate
            retry at the outer level, each loop only their own enclosing block"
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "let inner_count := 0\n"
                   "let outer_count := 0\n"
                   "do\n"
                   "  do\n"
                   "    inner_count := inner_count + 1\n"
                   "    if inner_count < 3 then\n"
                   "      raise \"INNER\"\n"
                   "    end\n"
                   "  rescue\n"
                   "    retry\n"
                   "  end\n"
                   "  outer_count := outer_count + 1\n"
                   "  if outer_count < 2 then\n"
                   "    raise \"OUTER\"\n"
                   "  end\n"
                   "rescue\n"
                   "  retry\n"
                   "end\n"
                   "print(\"inner_count: \" + inner_count)\n"
                   "print(\"outer_count: \" + outer_count)")))
      (is (= ["\"inner_count: 4\"" "\"outer_count: 2\""] (runtime/state-output (:state session)))))))

(deftest compiled-retry-in-triple-nested-rescue-smoke-test
  (testing "retry at the innermost of three nested do/rescue levels only re-loops that level"
    (let [session (compiled-repl/make-session)]
      (compiled-repl/compile-and-eval!
       session
       (p/ast (str "let c1 := 0\n"
                   "let c2 := 0\n"
                   "let c3 := 0\n"
                   "do\n"
                   "  c1 := c1 + 1\n"
                   "  raise \"L1\"\n"
                   "rescue\n"
                   "  do\n"
                   "    c2 := c2 + 1\n"
                   "    raise \"L2\"\n"
                   "  rescue\n"
                   "    do\n"
                   "      c3 := c3 + 1\n"
                   "      if c3 < 3 then\n"
                   "        raise \"L3\"\n"
                   "      end\n"
                   "    rescue\n"
                   "      retry\n"
                   "    end\n"
                   "    print(\"c3: \" + c3)\n"
                   "  end\n"
                   "  print(\"c2: \" + c2)\n"
                   "end\n"
                   "print(\"c1: \" + c1)")))
      (is (= ["\"c3: 3\"" "\"c2: 1\"" "\"c1: 1\""] (runtime/state-output (:state session)))))))

;; ─── Exceptions thrown by a *free function* call, seen through `rescue` ─────
;;
;; A free-function call lowers to `:call-repl-fn` (emit.clj), which invokes it
;; via `java.lang.reflect.Method/invoke` rather than a direct `invokevirtual`
;; the way a method call does. Reflection wraps whatever the callee throws in
;; an `InvocationTargetException` with no message of its own; `exception-value`
;; and `retry-signal?` (runtime.clj) used to inspect that wrapper directly, so
;; `exception` printed `nil` for a precondition violation and `retry` could
;; never recognise a retry signal — both fixed by unwrapping down to the real
;; cause first (`unwrap-reflective-exception`). A method call was never
;; affected, since it never goes through reflection.

(deftest compiled-free-function-precondition-violation-message-test
  (testing "rescue sees the real message, not nil, for a failed function precondition"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str "function max_of(items: Array[Integer]): Integer\n"
                              "require\n"
                              "  not_empty: items.length > 0\n"
                              "do\n"
                              "  result := items.get(0)\n"
                              "end\n"
                              "do\n"
                              "  max_of([])\n"
                              "rescue\n"
                              "  print(exception)\n"
                              "end")))]
      (is (:compiled? result))
      (is (= ["\"Precondition violation: not_empty\""] (runtime/state-output (:state session)))))))

(deftest compiled-free-function-raise-message-test
  (testing "rescue sees the raised value, not nil, for a raise inside a free function"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str "function risky(): Integer do\n"
                              "  raise \"custom failure\"\n"
                              "end\n"
                              "do\n"
                              "  risky()\n"
                              "rescue\n"
                              "  print(exception)\n"
                              "end")))]
      (is (:compiled? result))
      (is (= ["\"custom failure\""] (runtime/state-output (:state session)))))))

(deftest compiled-free-function-retry-test
  (testing "retry recognises a retry signal raised inside a free function"
    (let [session (compiled-repl/make-session)
          _ (compiled-repl/compile-and-eval!
             session
             (p/ast (str "class Counter\n"
                         "create make() do n := 0 end\n"
                         "feature\n"
                         "  n: Integer\n"
                         "  bump() do n := n + 1 end\n"
                         "end")))
          _ (compiled-repl/compile-and-eval!
             session
             (p/ast (str "function flaky(c: Counter): Integer do\n"
                         "  c.bump()\n"
                         "  if c.n < 3 then\n"
                         "    raise \"not yet\"\n"
                         "  end\n"
                         "  result := c.n\n"
                         "end")))
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str "let c := create Counter.make\n"
                              "let final: Integer := 0\n"
                              "do\n"
                              "  final := flaky(c)\n"
                              "rescue\n"
                              "  retry\n"
                              "end\n"
                              "final")))]
      (is (:compiled? result))
      (is (= 3 (:result result))))))

(deftest compiled-case-smoke-test
  (testing "compiled helper supports case statements with multiple literals per clause"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str "let score: Integer := 2\n"
                              "let tag: Integer := 0\n"
                              "case score of\n"
                              "  1, 2 then tag := 20\n"
                              "  3 then tag := 30\n"
                              "  else tag := 99\n"
                              "end\n"
                              "tag")))]
      (is (:compiled? result))
      (is (= 20 (:result result))))))

(deftest compiled-across-smoke-test
  (testing "compiled helper supports across loops via loop desugaring"
    (let [session (compiled-repl/make-session)
          _ (runtime/state-set-value! (:state session) "numbers" (java.util.ArrayList. [1 2 3]))
          _ (runtime/state-set-type! (:state session) "numbers" {:base-type "Array" :type-params ["Integer"]})
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str "let total: Integer := 0\n"
                              "across numbers as item do\n"
                              "  print(item)\n"
                              "  total := total + 1\n"
                              "end\n"
                              "total")))]
      (is (:compiled? result))
      (is (= ["1" "2" "3"] (:output result)))
      (is (= 3 (:result result))))))

(deftest compiled-logical-operator-short-circuit-test
  (testing "compiled helper short-circuits and/or without evaluating the rhs"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval!
                         session
                         (p/ast "function boom(): Boolean
do
  print(\"boom\")
  result := true
end"))
          and-result (compiled-repl/compile-and-eval! session (p/ast "false and boom()"))
          or-result (compiled-repl/compile-and-eval! session (p/ast "true or boom()"))
          not-result (compiled-repl/compile-and-eval! session (p/ast "not false"))]
      (is (:compiled? define-result))
      (is (:compiled? and-result))
      (is (:compiled? or-result))
      (is (:compiled? not-result))
      (is (= [] (:output and-result)))
      (is (= [] (:output or-result)))
      (is (= false (:result and-result)))
      (is (= true (:result or-result)))
      (is (= true (:result not-result))))))

(deftest compiled-operator-smoke-test
  (testing "compiled helper supports unary, modulo, power, string concat, and integer bitwise operators"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval!
                  session
                  (p/ast (str "print(\"n=\" + 10)\n"
                              "let x: Integer := -5\n"
                              "let m: Integer := 10 % 3\n"
                              "let p: Integer := 2 ^ 8\n"
                              "let q: Integer := (5).bitwise_left_shift(1)\n"
                              "let r: Integer := (6).bitwise_and(3)\n"
                              "let s: Integer := (0).bitwise_not\n"
                              "let t: Boolean := not false\n"
                              "when t then x + m + p + q + r + s else 0 end")))
          real-power (compiled-repl/compile-and-eval! session (p/ast "2.0 ^ 3"))]
      (is (:compiled? result))
      (is (:compiled? real-power))
      (is (= ["\"n=10\""] (:output result)))
      (is (= 263 (:result result)))
      (is (= 8.0 (:result real-power))))))

;; ---- Loop support ----

(deftest compiled-loop-basic-sum-test
  (testing "compiled helper can execute a simple from/until/do loop that sums integers"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval! session
                   (p/ast "let sum := 0
from
  let i := 0
until
  i = 10
do
  sum := sum + i
  i := i + 1
end
sum"))]
      (is (:compiled? result))
      (is (= 45 (:result result))))))

(deftest compiled-loop-repeat-style-test
  (testing "compiled helper can execute a repeat-style loop (desugared to from/until)"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval! session
                   (p/ast "let count := 0
repeat 5 do
  count := count + 1
end
count"))]
      (is (:compiled? result))
      (is (= 5 (:result result))))))

(deftest compiled-loop-with-print-test
  (testing "compiled helper can execute a loop with print calls"
    (let [session (compiled-repl/make-session)
          result (compiled-repl/compile-and-eval! session
                   (p/ast "from
  let i := 0
until
  i = 3
do
  println(i)
  i := i + 1
end"))]
      (is (:compiled? result))
      (is (= ["0" "1" "2"] (:output result))))))

(deftest compiled-loop-cross-cell-test
  (testing "compiled loop can modify top-level variables across cells"
    (let [session (compiled-repl/make-session)
          _ (compiled-repl/compile-and-eval! session
              (p/ast "let total := 0"))
          result (compiled-repl/compile-and-eval! session
                   (p/ast "from
  let i := 1
until
  i > 5
do
  total := total + i
  i := i + 1
end
total"))]
      (is (:compiled? result))
      (is (= 15 (:result result))))))
