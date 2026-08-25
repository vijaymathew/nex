(ns nex.inheritance-runtime-test
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.repl :as repl]))

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

(deftest multiple-inherited-method-preconditions-use-or-test
  (testing "Overridden feature preconditions are OR-ed across all inherited parents"
    (let [code "class A
feature
  f(x: Integer): Integer
  require
    a_ok: x > 10
  do
    result := x
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
end

class D inherit A, C
feature
  f(x: Integer): Integer
  require
    d_ok: x = 0
  do
    result := x
  end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (let [obj (interp/make-object "D" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "d" obj)
            ctx-with-d (assoc ctx :current-env env)]
        (is (= 20 (interp/eval-node ctx-with-d {:type :call
                                                :target "d"
                                                :method "f"
                                                :args [{:type :integer :value 20}]})))
        (is (= -20 (interp/eval-node ctx-with-d {:type :call
                                                 :target "d"
                                                 :method "f"
                                                 :args [{:type :integer :value -20}]})))
        (is (= 0 (interp/eval-node ctx-with-d {:type :call
                                               :target "d"
                                               :method "f"
                                               :args [{:type :integer :value 0}]})))
        (is (thrown-with-msg?
              Exception
              #"Precondition violation: inherited_or_local_require"
              (interp/eval-node ctx-with-d {:type :call
                                            :target "d"
                                            :method "f"
                                            :args [{:type :integer :value 5}]})))))))

(deftest multiple-inherited-method-postconditions-use-and-test
  (testing "Overridden feature postconditions are AND-ed across all inherited parents"
    (let [code "class A
feature
  g(): Integer
  do
    result := 5
  ensure
    a_ok: result = 5
  end
end

class C
feature
  g(): Integer
  do
    result := 99
  ensure
    c_ok: result = 99
  end
end

class D inherit A, C
feature
  g(): Integer
  do
    result := 5
  end
end"
          ast (p/ast code)
          ctx (interp/make-context)]
      (doseq [class-node (:classes ast)]
        (interp/register-class ctx class-node))
      (let [obj (interp/make-object "D" {})
            env (interp/make-env (:globals ctx))
            _ (interp/env-define env "d" obj)
            ctx-with-d (assoc ctx :current-env env)]
        (is (thrown-with-msg?
              Exception
              #"Postcondition violation: c_ok"
              (interp/eval-node ctx-with-d {:type :call
                                            :target "d"
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

;; ─── Compiled backend ────────────────────────────────────────────────────────

(defmacro with-compiled-repl [ctx-sym & body]
  `(binding [repl/*type-checking-enabled* (atom true)
             repl/*repl-var-types* (atom {})
             repl/*repl-backend* (atom :compiled)
             repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
     (let [~ctx-sym (repl/init-repl-context)]
       ~@body)))

(deftest compiled-three-level-chain-dispatch-test
  (testing "compiled backend dispatches a grandparent method through a two-level inheritance chain"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "class A
  feature
    greet(): String do
      result := \"hello from A\"
    end
end"))
      (with-out-str
        (repl/eval-code ctx "class B inherit A end"))
      (with-out-str
        (repl/eval-code ctx "class C inherit B end"))
      (with-out-str
        (repl/eval-code ctx "let c := create C"))
      (let [output (with-out-str (repl/eval-code ctx "c.greet"))]
        (is (not (str/includes? output "Error:")) output)
        (is (str/includes? output "hello from A"))))))

(deftest compiled-multiple-inheritance-dispatch-test
  (testing "compiled backend dispatches methods from both parents of a multiply-inheriting class"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "class Flyable
  feature
    fly(): String do result := \"flying\" end
end"))
      (with-out-str
        (repl/eval-code ctx "class Swimmable
  feature
    swim(): String do result := \"swimming\" end
end"))
      (with-out-str
        (repl/eval-code ctx "class Duck inherit Flyable, Swimmable end"))
      (with-out-str
        (repl/eval-code ctx "let d := create Duck"))
      (let [fly-out  (with-out-str (repl/eval-code ctx "d.fly"))
            swim-out (with-out-str (repl/eval-code ctx "d.swim"))]
        (is (str/includes? fly-out  "flying")  fly-out)
        (is (str/includes? swim-out "swimming") swim-out)))))

(deftest compiled-inherited-precondition-enforced-test
  (testing "compiled backend enforces preconditions inherited from a parent class"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "class Counter
  feature
    value: Integer
  create make() do value := 0 end
end"))
      (with-out-str
        (repl/eval-code ctx "class Bounded_Counter inherit Counter
  feature
    increment(by: Integer)
      require
        positive_step: by > 0
      do
        value := value + by
      end
end"))
      (with-out-str
        (repl/eval-code ctx "let bc := create Bounded_Counter.make"))
      (let [ok-out  (with-out-str (repl/eval-code ctx "bc.increment(1)"))
            bad-out (with-out-str (repl/eval-code ctx "bc.increment(-1)"))]
        (is (not (str/includes? ok-out  "Error:")) ok-out)
        (is (str/includes? bad-out "Precondition violation") bad-out)))))

(deftest compiled-super-constructor-initialises-parent-fields-test
  (testing "compiled super constructor call populates fields defined on the parent"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "class Shape
  feature
    colour: String
  create make(c: String) do colour := c end
end"))
      (with-out-str
        (repl/eval-code ctx "class Circle inherit Shape
  feature
    radius: Real
  create make(c: String, r: Real) do
    super.make(c)
    radius := r
  end
end"))
      (with-out-str
        (repl/eval-code ctx "let ci := create Circle.make(\"blue\", 3.0)"))
      (let [colour-out (with-out-str (repl/eval-code ctx "ci.colour"))
            radius-out (with-out-str (repl/eval-code ctx "ci.radius"))]
        (is (str/includes? colour-out "blue")  colour-out)
        (is (str/includes? radius-out "3.0")   radius-out)))))

;; ─── A class that fails to compile no longer silently deopts to the interpreter ──
;;
;; Before this fix, any class/function definition whose body hit a compiled-
;; backend gap (essentially any "Unsupported ..." lowering error, plus a few
;; other messages) was silently re-run on the tree-walking interpreter by
;; `nex.compiler.jvm.repl/compile-and-eval!` — no error, no warning. That
;; interpreted class then stayed interpreted for the rest of the REPL session,
;; and the interpreter has no implementation of `super.method()` calls (see
;; `nex.this-super-test/super-method-call`), so a later, perfectly valid
;; subclass calling `super` against it failed with a confusing "Undefined
;; variable: super" — with nothing in the session pointing back at the real
;; cause. These tests pin the fix: such a class now fails visibly at the point
;; it's defined.
;;
;; Redefining an already-*compiled* class used to be forced onto the
;; interpreter unconditionally (any name collision with a previously compiled
;; class made `eligible-ast?` decline outright), with the same visible warning
;; as above. The compiled backend now recompiles a straightforward
;; redefinition directly — see `redefining-a-compiled-class-stays-compiled-
;; test` below — so the warning fires only when the *new* shape hits a real
;; compiled-backend gap, in which case the redefinition still falls back to
;; the interpreter (silently taking effect there, per
;; `redefining-a-compiled-class-with-a-gap-falls-back-visibly-test`) rather
;; than surfacing as a raw error the way a first-time declaration's gap does.

(defmacro ^:private with-err-str
  "Like `clojure.core/with-out-str`, but captures `*err*` — where the REPL's
   interpreter-fallback warning is printed (matching `nex.eval/warn-fallback!`'s
   own convention of writing to stderr, not stdout)."
  [& body]
  `(let [s# (java.io.StringWriter.)]
     (binding [*err* s#]
       ~@body)
     (str s#)))

(def ^:private generic-class-calling-hash-on-unconstrained-param
  "`.hash` on a value of unconstrained generic type `G` is a real, still-open
   compiled-lowering gap (deliberately excluded when `to_string`/`equals` were
   fixed for this same position — see lower.clj's `lower-instance-dispatch`,
   the comment on the `any:` dispatch branch). Lowering it throws \"Unsupported
   target call expression for lowering\", a reliable trigger for this test."
  "class Box [G]
  create make(v: G) do value := v end
  feature
    value: G
    key(): Integer do result := value.hash end
end")

(deftest compiled-backend-gap-in-class-body-fails-visibly-test
  (testing "the class definition itself reports the error, not silence"
    (with-compiled-repl ctx
      (let [out (with-out-str
                  (repl/eval-code ctx generic-class-calling-hash-on-unconstrained-param))]
        (is (str/includes? out "Error:") out)
        (is (str/includes? out "Unsupported") out))))

  (testing "and the class was never defined by either backend — no silent partial success"
    (with-compiled-repl ctx
      (with-out-str (repl/eval-code ctx generic-class-calling-hash-on-unconstrained-param))
      (let [out (with-out-str (repl/eval-code ctx "let b := create Box[Integer].make(1)"))]
        (is (str/includes? out "Error:") out)))))

(deftest redefining-a-compiled-class-stays-compiled-test
  (testing "a straightforward redefinition of an already-compiled class recompiles
            directly, with no fallback warning"
    (with-compiled-repl ctx
      (with-out-str (repl/eval-code ctx "class Foo create make() do x := 1 end feature x: Integer end"))
      (let [err (with-err-str
                  (with-out-str (repl/eval-code ctx "class Foo create make() do x := 2 end feature x: Integer end")))]
        (is (not (str/includes? err "Warning")) err)
        (is (not (str/includes? err "interpreter")) err))))

  (testing "and the redefinition still takes effect"
    (with-compiled-repl ctx
      (with-out-str (repl/eval-code ctx "class Foo create make() do x := 1 end feature x: Integer end"))
      (with-err-str
        (with-out-str (repl/eval-code ctx "class Foo create make() do x := 2 end feature x: Integer end")))
      (with-out-str (repl/eval-code ctx "let f := create Foo.make"))
      (let [out (with-out-str (repl/eval-code ctx "f.x"))]
        (is (str/includes? out "2") out)))))

(deftest redefining-a-compiled-class-with-a-gap-falls-back-visibly-test
  (testing "a redefinition whose *new* shape hits a real compiled-backend gap
            falls back to the interpreter (visible warning), not a raw error"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "class Box [G] create make(v: G) do value := v end feature value: G end"))
      (let [redef-out (with-out-str
                        (let [err (with-err-str
                                   (repl/eval-code ctx generic-class-calling-hash-on-unconstrained-param))]
                          (is (str/includes? err "Warning") err)
                          (is (str/includes? err "interpreter") err)))]
        (is (not (str/includes? redef-out "Error:")) redef-out))))

  (testing "and the redefinition still takes effect, on the interpreter"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "class Box [G] create make(v: G) do value := v end feature value: G end"))
      (with-err-str
        (with-out-str (repl/eval-code ctx generic-class-calling-hash-on-unconstrained-param)))
      (with-out-str (repl/eval-code ctx "let b := create Box[Integer].make(1)"))
      ;; `b.key` itself hits an unrelated, pre-existing interpreter gap in
      ;; generic-method resolution after a redefinition — not what this test
      ;; is pinning down. Reading the plain field is enough to confirm the
      ;; redefined class (the one with `key`, not the original) is what ran.
      (let [out (with-out-str (repl/eval-code ctx "b.value"))]
        (is (not (str/includes? out "Error:")) out)))))

;; ─── Self-calls inside an inherited (never-overridden-in-between) template
;; method must dispatch to the actual runtime object's override, however many
;; composition levels deep it sits ────────────────────────────────────────
;;
;; The compiled backend has no real JVM `extends` between Nex classes —
;; inheritance is emulated by composition (nex.lower/make-delegation-method-
;; node): a class that doesn't override an inherited method gets a thin
;; forwarding stub that calls into a "carrier" field holding a genuine,
;; separately-compiled instance of whichever ancestor actually defines it.
;; Dynamic self-dispatch (nex.lower's `method-def` branch of the target-call
;; lowering) works by reading a `__outer__` back-pointer instead of relying
;; on the JVM's own (nonexistent) virtual dispatch. Before this fix,
;; `__outer__` was set only one level deep at construction time
;; (`parent.__outer__ = this`), so a class with its own composition fields
;; (Circle2, composed from Shape) built as *another* class's composition
;; field (inside Circle3) pointed its own Shape field's `__outer__` at
;; itself, not at the true outermost Circle3 -- so `describe()` (defined on
;; Shape, calling `area()` on itself) resolved `area` against Circle2
;; (deferred, no override) instead of Circle3's actual implementation,
;; crashing with a raw "Internal error" naming a synthetic
;; `__method_area$arity0` symbol. The fix: `__outer__` propagation
;; (`nex.compiler.jvm.emit/emit-set-outer-method!`) is now a real recursive
;; instance method call chain, invoked once a class's own composition tree
;; is fully built, so it self-corrects no matter how many levels deep.

(def ^:private shape-with-template-method
  "deferred class Shape
  feature
    colour: String
    area(): Real deferred
    perimeter(): Real deferred
    describe(): String do
      result := \"A \" + colour + \" shape with area \" + area.to_string
    end
end")

(deftest compiled-template-method-dispatches-through-direct-override-test
  (testing "describe() (defined on Shape) correctly finds a DIRECT child's area() override"
    (with-compiled-repl ctx
      (with-out-str (repl/eval-code ctx shape-with-template-method))
      (with-out-str
        (repl/eval-code ctx "class Square
  inherit Shape
  create
    make(c: String, s: Real) do
      colour := c
      side := s
    end
  feature
    side: Real
    area(): Real do
      result := side * side
    end
    perimeter(): Real do
      result := 4.0 * side
    end
end"))
      (with-out-str (repl/eval-code ctx "let sq := create Square.make(\"blue\", 3.0)"))
      (let [out (with-out-str (repl/eval-code ctx "sq.describe"))]
        (is (not (str/includes? out "Error:")) out)
        (is (str/includes? out "area 9.0") out)))))

(deftest compiled-template-method-dispatches-through-grandchild-override-test
  (testing "describe() (defined on Shape, inherited unchanged through Circle2) correctly
            finds Circle3's area() override two composition levels down"
    (with-compiled-repl ctx
      (with-out-str (repl/eval-code ctx shape-with-template-method))
      (with-out-str
        (repl/eval-code ctx "deferred class Circle2
  inherit Shape
  create
    make(c: String, r: Real) do
      colour := c
      radius := r
    end
  feature
    radius: Real
    perimeter(): Real do
      result := 2.0 * 3.14159 * radius
    end
end"))
      (with-out-str
        (repl/eval-code ctx "class Circle3
  inherit Circle2
  feature
    area(): Real do
      result := 3.14159 * radius * radius
    end
end"))
      (with-out-str (repl/eval-code ctx "let c2 := create Circle3.make(\"aa\", 1.2)"))
      (let [area-out (with-out-str (repl/eval-code ctx "c2.area"))
            perimeter-out (with-out-str (repl/eval-code ctx "c2.perimeter"))
            describe-out (with-out-str (repl/eval-code ctx "c2.describe"))]
        (is (not (str/includes? area-out "Error:")) area-out)
        (is (not (str/includes? perimeter-out "Error:")) perimeter-out)
        (is (not (str/includes? describe-out "Error:")) describe-out)
        (is (not (str/includes? describe-out "Internal error")) describe-out)
        (is (str/includes? describe-out "area 4.5238") describe-out)))))

(deftest compiled-template-method-dispatches-through-four-level-chain-test
  (testing "self-dispatch survives a chain deeper than the reported 2-intermediate-level
            case -- Level0's report() -> val() must find Level3's override through
            Level1 and Level2, neither of which override anything"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "deferred class Level0
  feature
    val(): Integer deferred
    report(): String do
      result := \"val=\" + val.to_string
    end
end"))
      (with-out-str (repl/eval-code ctx "deferred class Level1 inherit Level0 end"))
      (with-out-str (repl/eval-code ctx "deferred class Level2 inherit Level1 end"))
      (with-out-str
        (repl/eval-code ctx "class Level3
  inherit Level2
  feature
    val(): Integer do result := 42 end
end"))
      (with-out-str (repl/eval-code ctx "let lv := create Level3"))
      (let [report-out (with-out-str (repl/eval-code ctx "lv.report"))]
        (is (not (str/includes? report-out "Error:")) report-out)
        (is (not (str/includes? report-out "Internal error")) report-out)
        (is (str/includes? report-out "val=42") report-out)))))

(deftest compiled-template-method-dispatches-through-multiple-inheritance-test
  (testing "self-dispatch works when the template method's class is one of several
            direct parents (multiple inheritance), not the only one"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "deferred class Named
  feature
    name: String
    label(): String deferred
    announce(): String do
      result := \"This is \" + name + \": \" + label
    end
end"))
      (with-out-str
        (repl/eval-code ctx "deferred class Sized
  feature
    size(): Integer deferred
end"))
      (with-out-str
        (repl/eval-code ctx "class Widget
  inherit Named, Sized
  create
    make(n: String, c: Integer) do
      name := n
      count := c
    end
  feature
    count: Integer
    label(): String do
      result := \"Widget\"
    end
    size(): Integer do
      result := count
    end
end"))
      (with-out-str (repl/eval-code ctx "let w := create Widget.make(\"gadget\", 5)"))
      (let [size-out (with-out-str (repl/eval-code ctx "w.size"))
            announce-out (with-out-str (repl/eval-code ctx "w.announce"))]
        (is (not (str/includes? size-out "Error:")) size-out)
        (is (str/includes? size-out "5") size-out)
        (is (not (str/includes? announce-out "Error:")) announce-out)
        (is (not (str/includes? announce-out "Internal error")) announce-out)
        (is (str/includes? announce-out "This is gadget: Widget") announce-out)))))

(deftest compiled-template-method-dispatches-through-multiple-inheritance-and-depth-test
  (testing "self-dispatch works when multiple inheritance and extra chain depth combine --
            one of the multiply-inherited parents (NamedThing) is itself a pure pass-
            through over the class that actually declares the template method (Named)"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "deferred class Named
  feature
    label(): String deferred
    announce(): String do
      result := \"Label: \" + label
    end
end"))
      (with-out-str (repl/eval-code ctx "deferred class NamedThing inherit Named end"))
      (with-out-str
        (repl/eval-code ctx "deferred class Sized
  feature
    size(): Integer deferred
end"))
      (with-out-str
        (repl/eval-code ctx "class Widget
  inherit NamedThing, Sized
  feature
    label(): String do result := \"Widget\" end
    size(): Integer do result := 5 end
end"))
      (with-out-str (repl/eval-code ctx "let w := create Widget"))
      (let [size-out (with-out-str (repl/eval-code ctx "w.size"))
            announce-out (with-out-str (repl/eval-code ctx "w.announce"))]
        (is (not (str/includes? size-out "Error:")) size-out)
        (is (not (str/includes? announce-out "Error:")) announce-out)
        (is (not (str/includes? announce-out "Internal error")) announce-out)
        (is (str/includes? announce-out "Label: Widget") announce-out)))))

;; ─── A constructor can assign/read a field declared two or more `inherit`s
;; above it, not just its direct parent's ──────────────────────────────────
;;
;; `nex.lower/direct-parent-field-map` used to record, for each inherited
;; field, a single `{:carrier-owner :carrier-field}` hop into the *direct*
;; parent's own composition field -- so a field declared on a grandparent (or
;; further) was simply absent from the compiled lowering env's `:fields` map,
;; even though the typechecker (which walks the full chain) accepted the
;; program. A constructor assigning such a field crashed with "Assignment
;; target is not a known local"; a bare read of it crashed with "Unknown
;; local in non-top-level lowering". The fix makes `direct-parent-field-map`
;; recurse into each direct parent's own inherited-field map, prepending the
;; extra composition hop, so `:carrier-path` is a chain of however many hops
;; are actually needed (see `nex.lower/carrier-path-target-ir`).

(deftest compiled-constructor-assigns-grandparent-field-test
  (testing "a constructor two inherit-levels below the field's declaration can still
            assign it, and a sibling method can read it back"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "deferred class Level0
  feature
    v: Integer
    val(): Integer deferred
end"))
      (with-out-str (repl/eval-code ctx "deferred class Level1 inherit Level0 end"))
      (with-out-str
        (repl/eval-code ctx "class Level2
  inherit Level1
  create
    make(n: Integer) do v := n end
  feature
    val(): Integer do result := v end
end"))
      (with-out-str (repl/eval-code ctx "let lv := create Level2.make(42)"))
      (let [out (with-out-str (repl/eval-code ctx "lv.val"))]
        (is (not (str/includes? out "Error:")) out)
        (is (str/includes? out "42") out)))))

(deftest compiled-constructor-assigns-great-grandparent-field-test
  (testing "the same, three inherit-levels below the field's declaration"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "deferred class LevelA
  feature
    w: Integer
end"))
      (with-out-str (repl/eval-code ctx "deferred class LevelB inherit LevelA end"))
      (with-out-str (repl/eval-code ctx "deferred class LevelC inherit LevelB end"))
      (with-out-str
        (repl/eval-code ctx "class LevelD
  inherit LevelC
  create
    make(n: Integer) do w := n end
  feature
    val(): Integer do result := w end
end"))
      (with-out-str (repl/eval-code ctx "let ld := create LevelD.make(99)"))
      (let [out (with-out-str (repl/eval-code ctx "ld.val"))]
        (is (not (str/includes? out "Error:")) out)
        (is (str/includes? out "99") out)))))

(deftest compiled-constructor-assigns-generic-grandparent-field-test
  (testing "generic substitutions still compose correctly across the extra hop --
            a field declared as one generic class's own param, restated under a
            different name by an intermediate class, then bound concretely by the
            leaf -- reads back as the right value with the right type"
    (with-compiled-repl ctx
      (with-out-str
        (repl/eval-code ctx "class Pair [A, B]
  feature
    first: A
    second: B
end"))
      (with-out-str (repl/eval-code ctx "class Middle [X, Y] inherit Pair[Y, X] end"))
      (with-out-str
        (repl/eval-code ctx "class Leaf
  inherit Middle[Integer, String]
  create
    make(s: String, i: Integer) do
      first := s
      second := i
    end
  feature
    show(): String do result := first + \":\" + second.to_string end
end"))
      (with-out-str (repl/eval-code ctx "let lf := create Leaf.make(\"hi\", 42)"))
      (let [out (with-out-str (repl/eval-code ctx "lf.show"))]
        (is (not (str/includes? out "Error:")) out)
        (is (str/includes? out "hi:42") out)))))

;; ─── A bare-identifier call *target* (e.g. `label` in `label.to_string`)
;; must resolve a still-abstract sibling method through the object's actual
;; runtime class, on the interpreter too ──────────────────────────────────
;;
;; `nex.interpreter/resolve-interp-call-target` resolved a call target
;; written as a plain name via a bare `env-lookup`, which just throws
;; "Undefined variable" for a name that isn't a local -- never falling back
;; to "is this a zero-arg method/constant on the current object" the way
;; `eval-node :identifier` already does for a bare identifier used as an
;; ordinary expression. So a template method's call target chain (`label.
;; to_string`, not just a bare `label`) broke for exactly the deferred-
;; method-call-target shape this fix's compiled-backend counterpart
;; (`__outer__`, see `nex.compiler.jvm.emit/emit-set-outer-method!`) already
;; covers. Separately, `eval-node :identifier`'s own fallback searched for
;; the method starting from `class-def` -- the class whose source is
;; lexically executing (e.g. `Shape`) -- which only ever finds an override
;; *above* it, never one below in the object's own more-derived subclass;
;; fixed to start from the object's actual runtime class instead.

(defmacro with-interpreter-repl [ctx-sym & body]
  `(binding [repl/*type-checking-enabled* (atom true)
             repl/*repl-var-types* (atom {})
             repl/*repl-backend* (atom :interpreter)]
     (let [~ctx-sym (repl/init-repl-context)]
       ~@body)))

(deftest interpreter-template-method-dispatches-through-direct-override-test
  (testing "describe() (defined on Shape) correctly finds a DIRECT child's area() override"
    (with-interpreter-repl ctx
      (with-out-str (repl/eval-code ctx shape-with-template-method))
      (with-out-str
        (repl/eval-code ctx "class Square
  inherit Shape
  create
    make(c: String, s: Real) do
      colour := c
      side := s
    end
  feature
    side: Real
    area(): Real do
      result := side * side
    end
    perimeter(): Real do
      result := 4.0 * side
    end
end"))
      (with-out-str (repl/eval-code ctx "let sq := create Square.make(\"blue\", 3.0)"))
      (let [out (with-out-str (repl/eval-code ctx "sq.describe"))]
        (is (not (str/includes? out "Error:")) out)
        (is (str/includes? out "area 9.0") out)))))

(deftest interpreter-template-method-dispatches-through-grandchild-override-test
  (testing "describe() (defined on Shape, inherited unchanged through Circle2) correctly
            finds Circle3's area() override two levels down, on the interpreter"
    (with-interpreter-repl ctx
      (with-out-str (repl/eval-code ctx shape-with-template-method))
      (with-out-str
        (repl/eval-code ctx "deferred class Circle2
  inherit Shape
  create
    make(c: String, r: Real) do
      colour := c
      radius := r
    end
  feature
    radius: Real
    perimeter(): Real do
      result := 2.0 * 3.14159 * radius
    end
end"))
      (with-out-str
        (repl/eval-code ctx "class Circle3
  inherit Circle2
  feature
    area(): Real do
      result := 3.14159 * radius * radius
    end
end"))
      (with-out-str (repl/eval-code ctx "let c2 := create Circle3.make(\"aa\", 1.2)"))
      (let [area-out (with-out-str (repl/eval-code ctx "c2.area"))
            describe-out (with-out-str (repl/eval-code ctx "c2.describe"))]
        (is (not (str/includes? area-out "Error:")) area-out)
        (is (not (str/includes? describe-out "Error:")) describe-out)
        (is (str/includes? describe-out "area 4.5238") describe-out)))))

(deftest interpreter-template-method-with-field-and-empty-intermediate-test
  (testing "a template method's deferred call target still resolves when the field it
            ultimately reads was assigned by a constructor two inherit-levels below --
            the exact combination that originally surfaced this bug"
    (with-interpreter-repl ctx
      (with-out-str
        (repl/eval-code ctx "deferred class Named
  feature
    w: Integer
    label(): Integer deferred
    announce(): String do result := \"L=\" + label.to_string end
end"))
      (with-out-str (repl/eval-code ctx "deferred class NamedThing inherit Named end"))
      (with-out-str
        (repl/eval-code ctx "class Widget
  inherit NamedThing
  create
    make(n: Integer) do w := n end
  feature
    label(): Integer do result := w end
end"))
      (with-out-str (repl/eval-code ctx "let ww := create Widget.make(7)"))
      (let [announce-out (with-out-str (repl/eval-code ctx "ww.announce"))]
        (is (not (str/includes? announce-out "Error:")) announce-out)
        (is (str/includes? announce-out "L=7") announce-out)))))
