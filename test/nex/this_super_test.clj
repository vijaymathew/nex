(ns nex.this-super-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [nex.parser :as p]
            [nex.walker :as walker]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [nex.eval :as e]))

;; ─── Parser / Walker Tests ───

(deftest parse-this-field-assignment
  (testing "this.x := 5 parses to :member-assign"
    (let [code "class A
  create
    make(x: Integer) do
      this.x := x
    end
  feature
    x: Integer
end"
          ast (p/ast code)
          ctor (-> ast :classes first :body
                   (->> (filter #(= (:type %) :constructors)))
                   first :constructors first)
          stmt (first (:body ctor))]
      (is (= :member-assign (:type stmt)))
      (is (= :this (-> stmt :object :type)))
      (is (= "x" (:field stmt))))))

(deftest parse-super-field-assignment
  (testing "super.field assignment parses as a member assignment target"
    (let [code "class B
  inherit A
  create
    make(x: Integer) do
      super.x := x
    end
  feature
    y: Integer
end

class A
  feature
    x: Integer
end"
          ast (p/ast code)
          ctor (-> ast :classes first :body
                   (->> (filter #(= (:type %) :constructors)))
                   first :constructors first)
          stmt (first (:body ctor))]
      (is (= :member-assign (:type stmt)))
      (is (= :super (-> stmt :object :type)))
      (is (= "x" (:field stmt))))))

(deftest parse-super-call-target
  (testing "super.method() parses to its own node type, like this.method()"
    (let [ast (p/ast "class A
  feature
    show do
      super.greet
    end
end")
          stmt (-> ast :classes first :body first :members first :body first)]
      (is (= :call (:type stmt)))
      (is (= :super (-> stmt :target :type)))
      (is (= "greet" (:method stmt))))))

(deftest super-is-a-reserved-word
  (testing "super cannot be used as an identifier anywhere IDENTIFIER is expected"
    (doseq [code ["let super := 5"
                  "class super create make() do end end"
                  "class A feature super: Integer end"
                  "function f(super: Integer): Integer do result := super end"]]
      (is (thrown? clj_antlr.ParseError (p/ast code)) code))))

(deftest parse-this-in-expression
  (testing "this parses to {:type :this} in primary position"
    (let [code "class A
  feature
    x: Integer
    get_x: Integer do
      result := this.x
    end
end"
          ast (p/ast code)
          method (-> ast :classes first :body
                     (->> (filter #(= (:type %) :feature-section)))
                     first :members
                     (->> (filter #(= (:type %) :method)))
                     first)
          ;; The body should have an assignment where value is a call on this
          stmt (first (:body method))]
      ;; result := this.x  =>  {:type :assign :target "result" :value {:type :call :target {:type :this} ...}}
      (is (= :assign (:type stmt)))
      (is (= :call (:type (:value stmt))))
      (is (= :this (:type (:target (:value stmt))))))))

;; ─── Interpreter Tests ───

(deftest this-field-assign-in-constructor
  (testing "this.field := param sets the field in a constructor"
    (let [code "class Point
  create
    make(x: Integer, y: Integer) do
      this.x := x
      this.y := y
    end
  feature
    x: Integer
    y: Integer
end"
          ast (p/ast code)
          ctx (interp/make-context)
          _ (interp/eval-node ctx ast)
          env (interp/make-env (:globals ctx))
          obj (interp/eval-node (assoc ctx :current-env env)
                                {:type :create
                                 :class-name "Point"
                                 :generic-args nil
                                 :constructor "make"
                                 :args [{:type :integer :value 10}
                                        {:type :integer :value 20}]})]
      (is (= 10 (:x (:fields obj))))
      (is (= 20 (:y (:fields obj)))))))

(deftest super-method-call
  (testing "super.method() calls the parent's implementation of an overridden method"
    (let [code "class A
  create
    make(x: Integer) do
      this.x := x
    end
  feature
    x: Integer
    show do
      print(x)
    end
end

class B
  inherit A
  create
    make(x: Integer, y: Integer) do
      A.make(x)
      this.y := y
    end
  feature
    y: Integer
    show do
      super.show()
      print(y)
    end
end"
          ast (p/ast code)
          ctx (interp/make-context)
          _ (interp/eval-node ctx ast)
          env (interp/make-env (:globals ctx))
          obj (interp/eval-node (assoc ctx :current-env env)
                                {:type :create
                                 :class-name "B"
                                 :generic-args nil
                                 :constructor "make"
                                 :args [{:type :integer :value 10}
                                        {:type :integer :value 20}]})
          _ (interp/env-define env "b" obj)
          call-ctx (assoc ctx :current-env env)]
      (interp/eval-node call-ctx {:type :call :target "b" :method "show" :args []})
      (is (= ["10" "20"] @(:output call-ctx))))))

(deftest this-field-assign-with-same-param-name
  (testing "this.x := x disambiguates field from parameter"
    (let [code "class Box
  create
    make(value: Integer) do
      this.value := value
    end
  feature
    value: Integer
end"
          ast (p/ast code)
          ctx (interp/make-context)
          _ (interp/eval-node ctx ast)
          env (interp/make-env (:globals ctx))
          obj (interp/eval-node (assoc ctx :current-env env)
                                {:type :create
                                 :class-name "Box"
                                 :generic-args nil
                                 :constructor "make"
                                 :args [{:type :integer :value 42}]})]
      (is (= 42 (:value (:fields obj)))))))

;; ─── Typechecker Tests ───

(deftest typecheck-this-super-usage
  (testing "this and super usage passes type checking"
      (let [code "class A
  create
    make(x: Integer) do
      this.x := x
    end
  feature
    x: Integer
    show do
      print(x)
    end
end

class B
  inherit A
  create
    make(x: Integer, y: Integer) do
      A.make(x)
      this.y := y
    end
  feature
    y: Integer
    show do
      super.show()
      print(y)
    end
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

(deftest typecheck-this-field-assign
  (testing "this.field := value type-checks correctly"
    (let [code "class Simple
  create
    make(v: Integer) do
      this.val := v
    end
  feature
    val: Integer
end"
          ast (p/ast code)
          result (tc/type-check ast)]
      (is (:success result)))))

;; ─── `super` on the interpreter ──────────────────────────────────────────────
;;
;; `super.method(...)`, `super.make(...)`, and `super.field := v` were all
;; unimplemented on the tree-walking interpreter — `super` typechecked (via an
;; exemption from the undefined-variable check) but had no runtime meaning
;; there, so any of these threw "Undefined variable: super" the moment
;; execution reached them (see the now-updated `super-method-call` above,
;; and `nex.compiler.jvm.repl-test`/`nex.repl-test`'s REPL-session tests,
;; which document the same interpreter gap surfacing through the REPL when a
;; class silently deopted to it). These tests pin the fix: all three forms now
;; work on the interpreter, and every one is checked against the compiled
;; backend for agreement, since that's exactly what the bug broke — the two
;; backends disagreeing on a real feature.

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned."
  [code]
  (let [f (java.io.File/createTempFile "this_super" ".nex")]
    (try
      (spit f code)
      (let [compiled (clojure.string/split-lines
                       (clojure.string/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
            interpreted (clojure.string/split-lines
                         (clojure.string/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest super-constructor-delegation-test
  (testing "super.make(...) initialises fields declared on the parent"
    (is (= ["\"blue\"" "3.0"]
           (both "class Shape
  feature colour: String
  create make(c: String) do colour := c end
end
class Circle
  inherit Shape
  feature radius: Real
  create make(c: String, r: Real) do
    super.make(c)
    radius := r
  end
end
let ci := create Circle.make(\"blue\", 3.0)
print(ci.colour)
print(ci.radius)")))))

(deftest super-generic-method-delegation-test
  (testing "super.push(...) inside an overriding method calls the parent's implementation"
    (is (= ["2" "true"]
           (both "class Stack [G]
create make() do items := [] end
feature
  items: Array[G]
  push(value: G) do items.add(value) end
  size(): Integer do result := items.length end
end
class Bounded_Stack [G] inherit Stack[G]
create make(max: Integer) do
  super.make
  max_size := max
end
feature
  max_size: Integer
  is_full(): Boolean do result := size = max_size end
  push(value: G) do
    if not is_full then super.push(value) end
  end
end
let bs := create Bounded_Stack[Integer].make(2)
bs.push(1)
bs.push(2)
bs.push(3)
print(bs.size)
print(bs.is_full)")))))

(deftest super-field-assignment-test
  (testing "super.field := v assigns a field declared on the parent"
    (is (= ["10" "20"]
           (both "class A
  feature x: Integer
end
class B
  inherit A
  feature y: Integer
  create make(a: Integer, b: Integer) do
    super.x := a
    y := b
  end
end
let obj := create B.make(10, 20)
print(obj.x)
print(obj.y)")))))

(deftest super-with-no-parent-errors-on-interpreter-test
  (testing "super used in a class with no parent throws a clear error, not a crash"
    (let [code "class Solo
  create make() do end
  feature
    greet() do super.greet() end
end
let s := create Solo.make
s.greet"
          f (java.io.File/createTempFile "this_super" ".nex")]
      (try
        (spit f code)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"super requires a direct parent"
                              (e/eval-file (.getPath f) {:interpret? true})))
        (finally (.delete f))))))

(deftest super-ambiguous-parents-errors-on-interpreter-test
  (testing "super with multiple direct parents throws a clear ambiguity error, not a crash"
    (let [code "class Flyable
  feature fly() do print(\"flying\") end
end
class Swimmable
  feature fly() do print(\"swimming-fly?\") end
end
class Duck
  inherit Flyable, Swimmable
  create make() do end
  feature fly() do super.fly() end
end
let d := create Duck.make
d.fly"
          f (java.io.File/createTempFile "this_super" ".nex")]
      (try
        (spit f code)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"super is ambiguous with multiple direct parents"
                              (e/eval-file (.getPath f) {:interpret? true})))
        (finally (.delete f))))))
