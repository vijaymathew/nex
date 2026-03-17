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
