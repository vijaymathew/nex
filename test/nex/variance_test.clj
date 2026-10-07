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
  "class Animal feature name: String set_name(n: String) do name := n end create make do name := \"a\" end end
   class Dog inherit Animal feature fetch: String do result := \"f\" end create make do set_name(\"d\") end end
   class Cat inherit Animal create make do set_name(\"c\") end end\n")

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
;; Calling through a Function(...)-typed variable: check-call must check
;; call-site arguments against the variable's own declared signature, not
;; against the generic builtin call<N> signature (every param typed Any) it
;; resolves to find *some* callN method for an arbitrary arity. Distinct from
;; the value-conformance checks above: this is about invoking, not assigning,
;; a Function(...)-typed value.
;; ---------------------------------------------------------------------------

(deftest fn-var-call-argument-checked-against-declared-param-type-rejected
  (testing "calling a Function(Dog)-typed variable with an unrelated sibling class (Cat) is rejected"
    (is (rejects? (str animals
                       "let dog_handler: Function(d: Dog): String := fn (d: Dog): String do result := d.fetch end\n"
                       "print(dog_handler(create Cat.make))\n")))))

(deftest fn-var-call-argument-matching-declared-param-type-accepted
  (testing "calling a Function(Dog)-typed variable with a Dog is accepted"
    (is (accepts? (str animals
                       "let dog_handler: Function(d: Dog): String := fn (d: Dog): String do result := d.fetch end\n"
                       "print(dog_handler(create Dog.make))\n")))))

(deftest fn-var-call-arity-checked-against-declared-signature-rejected
  (testing "calling a Function(Dog)-typed (single-parameter) variable with the wrong number of arguments is rejected"
    (is (rejects? (str animals
                       "let dog_handler: Function(d: Dog): String := fn (d: Dog): String do result := d.fetch end\n"
                       "print(dog_handler(create Dog.make, create Dog.make))\n")))))

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
    (is (accepts? "class Animal feature name: String set_name(n: String) do name := n end create make do name := \"a\" end end
                   class Dog inherit Animal create make do set_name(\"d\") end end
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

;; ---------------------------------------------------------------------------
;; Builtin protocol routines. Any's `equals`/`clone`, Comparable's `compare`
;; and Hashable's `hash` live only in the method table, never in a class body,
;; so the ancestor walk used to miss them, and every class inherits Any
;; without naming it. An `Any` parameter also passed for any narrowing,
;; because types-compatible? treats Any as a wildcard.
;; ---------------------------------------------------------------------------

(deftest any-equals-param-narrowing-rejected
  (testing "equals(other: Rational) narrows Any's equals(other: Any)"
    (is (rejects? "class Rational
                     feature n: Integer
                     create make(x: Integer) do n := x end
                     feature
                       equals(other: Rational): Boolean do result := n = other.n end
                   end"))))

(deftest any-equals-same-signature-accepts
  (testing "equals(other: Any) narrowing inside the body with convert conforms"
    (is (accepts? "class Rational
                     feature n: Integer
                     create make(x: Integer) do n := x end
                     feature
                       equals(other: Any): Boolean do
                         result := false
                         if convert other to r: Rational then result := n = r.n end
                       end
                   end"))))

(deftest any-clone-covariant-return-accepts
  (testing "clone: M narrows Any's clone: Any return, which is covariant"
    (is (accepts? "class M
                     feature v: Integer
                     create make(x: Integer) do v := x end
                     feature clone: M do result := create M.make(v) end
                   end"))))

(deftest any-to-string-nonconforming-return-rejected
  (testing "to_string: Integer does not conform to Any's to_string: String"
    (is (rejects? "class M feature to_string: Integer do result := 1 end end"))))

(deftest comparable-compare-param-narrowing-rejected
  (testing "compare(other: Box) narrows Comparable's compare(a: Any)"
    (is (rejects? "class Box inherit Comparable
                     feature v: Integer
                     create make(x: Integer) do v := x end
                     feature compare(other: Box): Integer do result := v.compare(other.v) end
                   end"))))

(deftest comparable-compare-any-param-accepts
  (testing "compare(other: Any) with a convert inside conforms"
    (is (accepts? "class Box inherit Comparable
                     feature v: Integer
                     create make(x: Integer) do v := x end
                     feature
                       compare(other: Any): Integer do
                         if convert other to b: Box then result := v.compare(b.v) end
                       end
                   end"))))

(deftest user-any-param-narrowing-rejected
  (testing "narrowing a user ancestor's Any parameter is rejected like any other narrowing"
    (is (rejects? "class Base feature take(x: Any) do end create make do end end
                   class Sub inherit Base feature take(x: Integer) do end create make do end end"))))

(deftest protocol-name-at-other-arity-is-not-an-override
  (testing "a routine sharing a protocol name but not its arity is a new routine"
    (is (accepts? "class Tools
                     feature
                       clone(p: Array[String]): Array[String] do result := p end
                       equals(a, b: Integer): Boolean do result := a = b end
                   end"))))

(deftest user-any-param-narrowing-through-intermediate-rejected
  (testing "a grandparent's Any parameter is still Any to the override"
    (is (rejects? "class Base feature take(x: Any) do end create make do end end
                   class Mid inherit Base create make do end end
                   class Sub inherit Mid feature take(x: Integer) do end create make do end end"))))

(deftest user-detachable-any-param-narrowing-rejected
  (testing "?Any narrowed to ?Integer is rejected like Any to Integer"
    (is (rejects? "class Base feature take(x: ?Any) do end create make do end end
                   class Sub inherit Base feature take(x: ?Integer) do end create make do end end"))))

(deftest user-any-param-widening-to-detachable-accepts
  (testing "Any widened to ?Any conforms"
    (is (accepts? "class Base feature take(x: Any) do end create make do end end
                   class Sub inherit Base feature take(x: ?Any) do end create make do end end"))))
