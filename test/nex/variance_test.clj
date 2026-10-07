(ns nex.variance-test
  "Type-checker tests for parameter/return-type variance: function-value
   conformance and class-method override conformance both use CONTRAVARIANT
   parameters and a COVARIANT return type, with generic type arguments resolved
   through inheritance before an override is checked."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
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

;; ---------------------------------------------------------------------------
;; Function values: Any and `?` inside a signature.
;; ---------------------------------------------------------------------------

(deftest fn-value-param-any-narrowing-rejected
  (testing "fn (x: Integer) is not a Function(x: Any): it would be passed non-Integers"
    (is (rejects? "let f: Function(x: Any): String := fn (x: Integer): String do result := \"\" end"))))

(deftest fn-value-return-any-widening-rejected
  (testing "fn (): Any is not a Function(): Integer"
    (is (rejects? "let g: Function(x: Integer): Integer := fn (x: Integer): Any do result := 1 end"))))

(deftest fn-value-param-widened-to-any-accepts
  (testing "fn (x: Any) is a Function(x: Integer)"
    (is (accepts? "let f: Function(x: Integer): String := fn (x: Any): String do result := \"\" end"))))

(deftest fn-value-detachable-param-narrowing-rejected
  (testing "fn (x: Dog) is not a Function(x: ?Dog): it would be passed nil"
    (is (rejects? (str animals "let f: Function(x: ?Dog): String := fn (x: Dog): String do result := x.fetch end")))))

(deftest fn-value-detachable-param-widening-accepts
  (testing "fn (x: ?Dog) is a Function(x: Dog)"
    (is (accepts? (str animals "let f: Function(x: Dog): String := fn (x: ?Dog): String do result := \"\" end")))))

(deftest fn-value-detachable-return-widening-rejected
  (testing "fn (): ?Dog is not a Function(): Dog"
    (is (rejects? (str animals "let f: Function(): Dog := fn (): ?Dog do result := nil end")))))

(deftest fn-value-detachable-return-narrowing-accepts
  (testing "fn (): Dog is a Function(): ?Dog"
    (is (accepts? (str animals "let f: Function(): ?Dog := fn (): Dog do result := create Dog.make end")))))

;; ---------------------------------------------------------------------------
;; Function values: every place one is conformed, and nested signatures.
;; ---------------------------------------------------------------------------

(deftest fn-value-as-argument-narrowing-rejected
  (testing "a Function(Dog) argument to a Function(Animal) parameter is rejected"
    (is (rejects? (str animals
                       "function app(f: Function(a: Animal): String): String do result := f(create Cat.make) end\n"
                       "print(app(fn (d: Dog): String do result := d.fetch end))")))))

(deftest fn-value-as-argument-widening-accepts
  (testing "a Function(Animal) argument to a Function(Dog) parameter is accepted"
    (is (accepts? (str animals
                       "function app(f: Function(d: Dog): String): String do result := f(create Dog.make) end\n"
                       "print(app(fn (a: Animal): String do result := a.name end))")))))

(deftest fn-value-as-result-narrowing-rejected
  (testing "returning a Function(Dog) where a Function(Animal) is declared is rejected"
    (is (rejects? (str animals
                       "function mk(): Function(a: Animal): String do result := fn (d: Dog): String do result := d.fetch end end")))))

(deftest fn-value-into-field-narrowing-rejected
  (testing "storing a Function(Dog) in a Function(Animal) field is rejected"
    (is (rejects? (str animals
                       "class H feature cb: ?Function(a: Animal): String
                          set do cb := fn (x: Dog): String do result := x.fetch end end
                        create make do end end")))))

(deftest fn-value-higher-order-param-rejected
  (testing "variance flips twice: a parameter taking Function(Animal) cannot stand for one taking Function(Dog)"
    (is (rejects? (str animals
                       "let h: Function(k: Function(d: Dog): String): Integer := "
                       "fn (k: Function(a: Animal): String): Integer do result := 1 end")))))

(deftest fn-value-higher-order-param-accepts
  (testing "variance flips twice: a parameter taking Function(Dog) can stand for one taking Function(Animal)"
    (is (accepts? (str animals
                       "let h: Function(k: Function(a: Animal): String): Integer := "
                       "fn (k: Function(d: Dog): String): Integer do result := 1 end")))))

(deftest fn-value-second-param-narrowing-rejected
  (testing "each parameter is checked, not just the first"
    (is (rejects? (str animals
                       "let f: Function(a: Dog, b: Animal): String := fn (a: Animal, b: Cat): String do result := \"\" end")))))

(deftest fn-value-generic-param-invariant
  (testing "Array[...] inside a Function parameter is invariant in both directions"
    (is (rejects? (str animals "let f: Function(xs: Array[Animal]): Integer := fn (xs: Array[Dog]): Integer do result := 0 end")))
    (is (rejects? (str animals "let f: Function(xs: Array[Dog]): Integer := fn (xs: Array[Animal]): Integer do result := 0 end")))))

;; ---------------------------------------------------------------------------
;; Overrides: detachable, Any returns, function-typed and generic signatures.
;; ---------------------------------------------------------------------------

(deftest override-return-any-widening-rejected
  (testing "an override cannot return Any where the inherited routine returns Integer"
    (is (rejects? "class B feature f(): Integer do result := 1 end create make do end end
                   class S inherit B feature f(): Any do result := 1 end create make do end end"))))

(deftest override-detachable-param
  (testing "Dog widens to ?Dog; ?Dog does not narrow to Dog"
    (is (accepts? (str animals "class B feature f(x: Dog) do end create make do end end
                                class S inherit B feature f(x: ?Dog) do end create make do end end")))
    (is (rejects? (str animals "class B feature f(x: ?Dog) do end create make do end end
                                class S inherit B feature f(x: Dog) do end create make do end end")))))

(deftest override-detachable-return
  (testing "?Dog narrows to Dog; Dog does not widen to ?Dog"
    (is (accepts? (str animals "class B feature f(): ?Dog do result := nil end create make do end end
                                class S inherit B feature f(): Dog do result := create Dog.make end create make do end end")))
    (is (rejects? (str animals "class B feature f(): Dog do result := create Dog.make end create make do end end
                                class S inherit B feature f(): ?Dog do result := nil end create make do end end")))))

(deftest override-function-typed-param
  (testing "a Function-typed parameter is contravariant like any other"
    (is (rejects? (str animals "class B feature f(cb: Function(d: Dog): String) do end create make do end end
                                class S inherit B feature f(cb: Function(a: Animal): String) do end create make do end end")))
    (is (accepts? (str animals "class B feature f(cb: Function(a: Animal): String) do end create make do end end
                                class S inherit B feature f(cb: Function(d: Dog): String) do end create make do end end")))))

(deftest override-function-typed-return
  (testing "a Function-typed return is covariant like any other"
    (is (accepts? (str animals "class B feature f(): Function(d: Dog): String do result := fn (d: Dog): String do result := \"\" end end create make do end end
                                class S inherit B feature f(): Function(a: Animal): String do result := fn (a: Animal): String do result := \"\" end end create make do end end")))
    (is (rejects? (str animals "class B feature f(): Function(a: Animal): String do result := fn (a: Animal): String do result := \"\" end end create make do end end
                                class S inherit B feature f(): Function(d: Dog): String do result := fn (d: Dog): String do result := \"\" end end create make do end end")))))

(deftest override-generic-param-invariant
  (testing "Array[...] parameters of an override must match exactly"
    (is (rejects? (str animals "class B feature f(xs: Array[Animal]) do end create make do end end
                                class S inherit B feature f(xs: Array[Dog]) do end create make do end end")))
    (is (rejects? (str animals "class B feature f(xs: Array[Dog]) do end create make do end end
                                class S inherit B feature f(xs: Array[Animal]) do end create make do end end")))
    (is (rejects? (str animals "class B feature f(xs: Array[Animal]) do end create make do end end
                                class S inherit B feature f(xs: Array[Any]) do end create make do end end")))))

(deftest deferred-implementation-param-narrowing-rejected
  (testing "implementing a deferred routine is an override"
    (is (rejects? (str animals "deferred class Sh feature f(x: Animal): Integer deferred end
                                class Sq inherit Sh feature f(x: Dog): Integer do result := 1 end create make do end end")))))

(deftest override-second-param-narrowing-rejected
  (testing "each parameter of an override is checked"
    (is (rejects? (str animals "class B feature f(a: Animal, b: Animal) do end create make do end end
                                class S inherit B feature f(a: Animal, b: Dog) do end create make do end end")))))

(deftest override-renarrowing-in-grand-heir-rejected
  (testing "a grand-heir conforms to the widened routine it redefines, not the original"
    (is (rejects? (str animals "class B feature f(x: Dog) do end create make do end end
                                class M inherit B feature f(x: Animal) do end create make do end end
                                class S inherit M feature f(x: Dog) do end create make do end end")))))

;; ---------------------------------------------------------------------------
;; Overrides under multiple inheritance: every parent's routine counts.
;; ---------------------------------------------------------------------------

(deftest multi-inherit-param-narrowing-second-parent-rejected
  (testing "narrowing the second parent's parameter is caught, not just the first's"
    (is (rejects? (str animals "class L feature f(x: Dog) do end create make do end end
                                class R feature f(x: Animal) do end create make do end end
                                class Both inherit L, R feature f(x: Dog) do end create make do end end")))
    (is (rejects? (str animals "class L feature f(x: Animal) do end create make do end end
                                class R feature f(x: Dog) do end create make do end end
                                class Both inherit L, R feature f(x: Dog) do end create make do end end")))))

(deftest multi-inherit-return-must-conform-to-every-parent
  (testing "a return conforming to the first parent's but not the second's is rejected"
    (is (rejects? (str animals "class L feature f(): Animal do result := create Dog.make end create make do end end
                                class R feature f(): Dog do result := create Dog.make end create make do end end
                                class Both inherit L, R feature f(): Animal do result := create Dog.make end create make do end end")))))

(deftest multi-inherit-conforming-to-both-accepts
  (testing "an override conforming to both parents is accepted"
    (is (accepts? (str animals "class L feature f(x: Dog): Animal do result := x end create make do end end
                                class R feature f(x: Cat): Animal do result := x end create make do end end
                                class Both inherit L, R feature f(x: Animal): Dog do result := create Dog.make end create make do end end")))))

(deftest diamond-shared-routine-accepts
  (testing "a diamond reaches one declaration twice; a matching override is fine"
    (is (accepts? "class Counter feature bump(x: Integer) do end create make do end end
                   class L inherit Counter create make do end end
                   class R inherit Counter create make do end end
                   class Both inherit L, R feature bump(x: Integer) do end create make do end end"))))

;; ---------------------------------------------------------------------------
;; Overrides naming the heir's own generic parameters.
;; ---------------------------------------------------------------------------

(deftest generic-heir-narrowing-own-param-rejected
  (testing "Stack[E] inherit Container[E] cannot take store(x: Integer): E may be String"
    (is (rejects? (str container
                       "class Stack[E] inherit Container[E] feature store(x: Integer) do end create make do end end")))))

(deftest generic-heir-widening-own-param-to-any-accepts
  (testing "store(x: Any) accepts every E"
    (is (accepts? (str container
                       "class Stack[E] inherit Container[E] feature store(x: Any) do end create make do end end")))))

(deftest generic-heir-concrete-return-for-own-param-rejected
  (testing "get(): ?Integer cannot redefine get(): ?E"
    (is (rejects? "class Container[T] feature get(): ?T do result := nil end create make do end end
                   class Stack[E] inherit Container[E] feature get(): ?Integer do result := nil end create make do end end"))))

(deftest generic-heir-swapped-params-rejected
  (testing "A and B are distinct type parameters"
    (is (rejects? "class P[A, B] feature f(x: A) do end create make do end end
                   class C[A, B] inherit P[A, B] feature f(x: B) do end create make do end end"))))

(deftest generic-heir-widening-to-constraint-accepts
  (testing "a parameter typed by E's constraint accepts every E"
    (is (accepts? (str animals
                       "class Container[T -> Animal] feature store(x: T) do end create make do end end
                        class Stack[E -> Animal] inherit Container[E] feature store(x: Animal) do end create make do end end")))))

(deftest generic-heir-narrowing-any-to-own-param-rejected
  (testing "take(x: E) cannot redefine take(x: Any)"
    (is (rejects? "class Base feature take(x: Any) do end create make do end end
                   class Sub[E] inherit Base feature take(x: E) do end create make do end end"))))

(deftest generic-heir-returning-own-param-for-any-accepts
  (testing "get(): ?E narrows get(): ?Any, which is covariant"
    (is (accepts? "class Base feature get(): ?Any do result := nil end create make do end end
                   class Sub[E] inherit Base feature get(): ?E do result := nil end create make do end end"))))

;; ---------------------------------------------------------------------------
;; Runtime: a conforming override is the one that runs, through a reference
;; typed as the parent, on both backends.
;; ---------------------------------------------------------------------------

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "variance" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(deftest variant-override-dispatches-on-both-backends
  (testing "a widened parameter and a narrowed return run through a parent-typed reference"
    (let [code "class Animal feature name: String set_name(n: String) do name := n end create make do name := \"a\" end end
class Dog inherit Animal create make do set_name(\"d\") end end
class B feature
  f(x: Dog): String do result := \"B\" end
  g(): Animal do result := create Animal.make end
create make do end end
class S inherit B feature
  f(x: Animal): String do result := \"S:\" + x.name end
  g(): Dog do result := create Dog.make end
create make do end end
let b: B := create S.make
print(b.f(create Dog.make))
print(b.g().name)
let s: S := create S.make
print(s.f(create Animal.make))
print(s.g().name)"
          compiled (run-backend code false)
          interpreted (run-backend code true)]
      (is (= ["\"S:d\"" "\"d\"" "\"S:a\"" "\"d\""] compiled))
      (is (= interpreted compiled)))))
