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
  (testing "compiled helper supports named constructors and explicit target field assignment"
    (let [session (compiled-repl/make-session)
          define-result (compiled-repl/compile-and-eval! session
                                                         (p/ast (str counter-with-constructor-program
                                                                     "\n\n"
                                                                     "let c: Counter := create Counter.with_value(5)\n"
                                                                     "c.value")))
          assign-result (compiled-repl/compile-and-eval! session
                                                         (p/ast "c.value := 9\nc.bump()"))
          field-result (compiled-repl/compile-and-eval! session
                                                        (p/ast "c.current()"))]
      (is (:compiled? define-result))
      (is (:compiled? assign-result))
      (is (:compiled? field-result))
      (is (= 5 (:result define-result)))
      (is (= 10 (:result assign-result)))
      (is (= 10 (:result field-result))))))

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
                              "when true 20 else 0 end")))]
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

(deftest compiled-scoped-block-rescue-rethrow-smoke-test
  (testing "compiled helper rethrows the original exception after rescue when no retry occurs"
    (let [session (compiled-repl/make-session)]
      (let [thrown (try
                     (compiled-repl/compile-and-eval!
                      session
                      (p/ast (str "do\n"
                                  "  raise \"boom\"\n"
                                  "rescue\n"
                                  "  print(exception)\n"
                                  "end")))
                     nil
                     (catch Throwable e
                       (if (instance? java.lang.reflect.InvocationTargetException e)
                         (.getCause ^java.lang.reflect.InvocationTargetException e)
                         e)))]
        (is (some? thrown))
        (is (instance? clojure.lang.ExceptionInfo thrown))
        (is (re-find #"boom" (.getMessage ^clojure.lang.ExceptionInfo thrown))))
      (is (= ["\"boom\""] (runtime/state-output (:state session)))))))

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
                              "when t x + m + p + q + r + s else 0 end")))
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
