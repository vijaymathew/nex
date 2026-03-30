(ns nex.member-assign-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.generator.javascript :as js]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]
            [nex.parser :as p]))

(deftest member-assign-parsing-test
  (testing "Parse field assignment on an explicit target object"
    (let [ast (p/ast "class Point
feature
  x: Integer
end

let p: Point := create Point
p.x := 10")
          assign-stmt (last (:statements ast))]
      (is (= :member-assign (:type assign-stmt)))
      (is (= "x" (:field assign-stmt)))
      (is (= :identifier (-> assign-stmt :object :type)))
      (is (= "p" (-> assign-stmt :object :name)))
      (is (= :integer (-> assign-stmt :value :type))))))

(deftest member-assign-interpreter-test
  (testing "Interpreter rejects top-level writes to object fields"
    (let [ctx (interp/make-context)
          ast (p/ast "class Point
feature
  x: Integer
end

let p: Point := create Point
p.x := 10")]
      (doseq [class-def (:classes ast)]
        (interp/register-class ctx class-def))
      (interp/eval-node ctx (first (:statements ast)))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot assign to field x outside of class Point"
           (interp/eval-node ctx (second (:statements ast))))))))

(deftest member-assign-typechecker-rejects-outside-write-test
  (testing "Typechecker rejects writes to a field from outside its declaring class"
    (let [code "class Point
feature
  x: Integer
end

let p: Point := create Point
p.x := 10"
          result (tc/type-check (p/ast code))]
      (is (not (:success result)))
      (is (some #(str/includes? (tc/format-type-error %) "Cannot assign to field x outside of class Point")
                (:errors result))))))

(deftest member-assign-interpreter-allows-same-class-write-to-other-instance-test
  (testing "Interpreter allows a class to assign its own field on another instance of the same class"
    (let [ctx (interp/make-context)
          ast (p/ast "class Point
feature
  x: Integer
  copy_to(other: Point, v: Integer) do
    other.x := v
  end
end

let p: Point := create Point
let q: Point := create Point")]
      (doseq [class-def (:classes ast)]
        (interp/register-class ctx class-def))
      (doseq [stmt (:statements ast)]
        (interp/eval-node ctx stmt))
      (interp/eval-node ctx {:type :call
                             :target "p"
                             :method "copy_to"
                             :args [{:type :identifier :name "q"}
                                    {:type :integer :value 10}]})
      (let [q (interp/env-lookup (:globals ctx) "q")]
        (is (= 10 (get (:fields q) :x)))))))

(deftest member-assign-interpreter-rejects-multiple-inheritance-parent-writes-test
  (testing "Interpreter rejects direct writes to either parent field from a multiply-inheriting child"
    (let [ctx (interp/make-context)
          ast (p/ast "class A
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
  break_y() do
    this.y := 2
  end
end

let c: C := create C")]
      (doseq [class-def (:classes ast)]
        (interp/register-class ctx class-def))
      (doseq [stmt (:statements ast)]
        (interp/eval-node ctx stmt))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot assign to field x outside of class A"
           (interp/eval-node ctx {:type :call
                                  :target "c"
                                  :method "break_x"
                                  :args []})))
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cannot assign to field y outside of class B"
           (interp/eval-node ctx {:type :call
                                  :target "c"
                                  :method "break_y"
                                  :args []}))))))

(deftest member-assign-generator-test
  (testing "JavaScript generator emits same-class explicit target field assignment"
    (let [code "class Point
feature
  x: Integer
  copy_to(other: Point) do
    other.x := 10
  end
end

class Main
feature
  run() do
    let p: Point := create Point
    let q: Point := create Point
    p.copy_to(q)
  end
end"]
      (is (str/includes? (js/translate code) "other.x = 10;")))))
