(ns nex.once-field-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.interpreter :as interp]
            [nex.typechecker :as tc]))

(defn- run [code]
  (interp/interpret-and-get-output (p/ast code)))

(defn- typecheck-ok? [code]
  (:success (tc/check-program (p/ast code))))

(defn- typecheck-error? [code]
  (not (typecheck-ok? code)))

(defn- parse-field [code]
  (-> (p/ast code) :classes first :body first :members first))

;; ---------------------------------------------------------------------------
;; Parser / AST

(deftest once-field-parsed-with-once-flag
  (let [field (parse-field "class Foo feature once id: Integer end")]
    (is (= "id" (:name field)))
    (is (true? (:once? field)))
    (is (false? (:constant? field)))))

(deftest non-once-field-has-false-flag
  (let [field (parse-field "class Foo feature id: Integer end")]
    (is (= "id" (:name field)))
    (is (false? (:once? field)))))

;; ---------------------------------------------------------------------------
;; Interpreter — valid use

(def ^:private point-class
  "class Point
  feature
    once x: Integer
    once y: Integer
  create
    make(px: Integer, py: Integer) do
      x := px
      y := py
    end
  feature
    show do
      print(x)
      print(y)
    end
end")

(deftest once-field-set-in-constructor
  (testing "once fields set during construction are readable afterward"
    (let [output (run (str point-class "
let p: Point := create Point.make(3, 7)
p.show"))]
      (is (= ["3" "7"] output)))))

;; ---------------------------------------------------------------------------
;; Interpreter — enforcement at runtime

(deftest once-field-reassignment-rejected-by-interpreter
  (testing "assigning a once field outside the constructor raises an error"
    (let [code "class Counter
  feature
    once count: Integer
  create
    make(n: Integer) do count := n end
  feature
    reset do count := 0 end
end
let c: Counter := create Counter.make(5)
c.reset"]
      (is (thrown? Exception (run code))))))

;; ---------------------------------------------------------------------------
;; Typechecker — enforcement at compile time

(deftest once-field-method-assignment-rejected-by-typechecker
  (testing "typechecker rejects once field write in a regular method"
    (is (typecheck-error? "class Box
  feature
    once value: Integer
  create
    make(v: Integer) do value := v end
  feature
    overwrite(v: Integer) do value := v end
end"))))

(deftest once-field-constructor-write-passes-typechecker
  (testing "typechecker accepts once field write inside a constructor"
    (is (typecheck-ok? "class Box
  feature
    once value: Integer
  create
    make(v: Integer) do value := v end
end"))))

(deftest once-field-multiple-constructors-all-pass
  (testing "once fields may be set in any constructor of the class"
    (is (typecheck-ok? "class Box
  feature
    once value: Integer
  create
    make(v: Integer) do value := v end
    empty do value := 0 end
end"))))

;; ---------------------------------------------------------------------------
;; Typechecker — inheritance

(deftest once-field-child-method-cannot-write-via-member-assign
  (testing "child class method cannot write parent's once field via this.field"
    (is (typecheck-error? "class Base
  feature
    once id: Integer
  create
    init(n: Integer) do id := n end
end
class Child
  inherit Base
  create
    make(n: Integer) do Base.init(n) end
  feature
    hack do this.id := 99 end
end"))))
