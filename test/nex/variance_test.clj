(ns nex.variance-test
  "Type-checker tests for parameter/return-type variance: function-value
   conformance and class-method override conformance both use CONTRAVARIANT
   parameters and a COVARIANT return type, with generic type arguments resolved
   through inheritance before an override is checked."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- accepts?
  "True when the program type-checks with no errors."
  [code]
  (let [r (tc/type-check (p/ast code))]
    (and (:success r) (empty? (:errors r)))))

(defn- rejects?
  "True when the program is rejected with at least one error."
  [code]
  (let [r (tc/type-check (p/ast code))]
    (and (not (:success r)) (seq (:errors r)))))

(def ^:private animals
  "class Animal feature name: String create make do name := \"a\" end end
   class Dog inherit Animal feature fetch: String do result := \"f\" end create make do name := \"d\" end end
   class Cat inherit Animal create make do name := \"c\" end end\n")

;; ---------------------------------------------------------------------------
;; Function-value conformance: contravariant parameters, covariant return.
;; ---------------------------------------------------------------------------

(deftest fn-value-param-contravariant-accepts
  (testing "a Function(Animal) value satisfies a Function(Dog) slot (params contravariant)"
    (is (accepts? (str animals
                       "let af: Function(a: Animal): String := fn (a: Animal): String do result := a.name end\n"
                       "let df: Function(d: Dog): String := af\n")))))

(deftest fn-value-param-narrowing-rejected
  (testing "a Function(Dog) value does NOT satisfy a Function(Animal) slot"
    (is (rejects? (str animals
                       "let df: Function(d: Dog): String := fn (d: Dog): String do result := d.fetch end\n"
                       "let af: Function(a: Animal): String := df\n")))))

(deftest fn-value-return-covariant-accepts
  (testing "a Function returning Dog satisfies a slot expecting Function returning Animal"
    (is (accepts? (str animals
                       "let g: Function(x: Integer): Dog := fn (x: Integer): Dog do result := create Dog.make end\n"
                       "let h: Function(x: Integer): Animal := g\n")))))

(deftest fn-value-return-widening-rejected
  (testing "a Function returning Animal does NOT satisfy a slot expecting Function returning Dog"
    (is (rejects? (str animals
                       "let g: Function(x: Integer): Animal := fn (x: Integer): Animal do result := create Animal.make end\n"
                       "let h: Function(x: Integer): Dog := g\n")))))

(deftest fn-value-identical-signature-accepts
  (testing "identical function signatures conform"
    (is (accepts? "let a: Function(x: Integer): Integer := fn (x: Integer): Integer do result := x end
                   let b: Function(x: Integer): Integer := a"))))

;; ---------------------------------------------------------------------------
;; Class-method override conformance: widen parameter, narrow return.
;; ---------------------------------------------------------------------------

(deftest override-param-widening-accepts
  (testing "an override may widen a parameter (contravariant)"
    (is (accepts? "class Animal feature interact(other: Animal) do end create make do end end
                   class Dog inherit Animal feature interact(other: Any) do end create make do end end"))))

(deftest override-param-narrowing-rejected
  (testing "an override may NOT narrow a parameter (the catcall)"
    (is (rejects? "class Animal feature interact(other: Animal) do end create make do end end
                   class Dog inherit Animal
                     feature
                       fetch: String do result := \"f\" end
                       interact(other: Dog) do print(other.fetch) end
                     create make do end
                   end"))))

(deftest override-return-narrowing-accepts
  (testing "an override may narrow the return type (covariant)"
    (is (accepts? "class Animal feature name: String create make do name := \"a\" end end
                   class Dog inherit Animal create make do name := \"d\" end end
                   class Base feature make_it(): Animal do result := create Animal.make end create make do end end
                   class Sub inherit Base feature make_it(): Dog do result := create Dog.make end create make do end end"))))

(deftest override-return-nonconforming-rejected
  (testing "an override may NOT change the return to a non-conforming type"
    (is (rejects? "class Animal feature name: String create make do name := \"a\" end end
                   class Base feature thing(): Animal do result := create Animal.make end create make do end end
                   class Sub inherit Base feature thing(): Integer do result := 5 end create make do end end"))))

(deftest override-same-signature-accepts
  (testing "an override with the identical signature conforms"
    (is (accepts? "class Animal feature speak(): String do result := \"...\" end create make do end end
                   class Dog inherit Animal feature speak(): String do result := \"woof\" end create make do end end"))))

;; ---------------------------------------------------------------------------
;; Generic substitution through inheritance.
;; ---------------------------------------------------------------------------

(def ^:private container
  "class Container[T]\n feature\n  store(x: T) do end\n create make do end\nend\n")

(deftest generic-override-matching-substitution-accepts
  (testing "override of a method inherited from Container[Integer] using the substituted type"
    (is (accepts? (str container
                       "class IntBox inherit Container[Integer] feature store(x: Integer) do end create make do end end")))))

(deftest generic-override-narrowing-substitution-rejected
  (testing "override narrows the substituted parameter (Integer -> String) and is rejected"
    (is (rejects? (str container
                       "class BadBox inherit Container[Integer] feature store(x: String) do end create make do end end")))))

(deftest generic-override-preserving-accepts
  (testing "a generic-preserving override (Stack[E] over Container[E]) is not falsely rejected"
    (is (accepts? (str container
                       "class Stack[E] inherit Container[E] feature store(x: E) do end create make do end end")))))
