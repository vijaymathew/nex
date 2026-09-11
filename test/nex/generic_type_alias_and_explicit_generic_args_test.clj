(ns nex.generic-type-alias-and-explicit-generic-args-test
  "Two additive language features implemented together:

   (A) Generic `declare type` aliases (`declare type Pair[T] = Function(T, T):
   T`) -- the grammar previously had no `[T]` slot for a type alias at all,
   so a free-looking name inside one (e.g. `T` in `Function(T, ...)`) could
   never be bound to anything; every use of such an alias with `[...]`
   arguments failed with a bogus, locationless 'Could not infer generic type
   parameter T'. See expand-type-aliases/validate-generic-args in
   nex.typechecker and env-add-type-alias-generic-params.

   (B) Explicit generic type arguments on an ordinary call or a bare,
   uncalled function reference (`f[Integer]`, `f[Integer](args)`), mirroring
   the `create Foo[Integer]` syntax classes already had. See
   resolve-explicit-generic-args/check-identifier/check-call in
   nex.typechecker, and the new `genericArgs` alternative on `postfixPart`
   in the grammar.

   Getting (B) to run on the *compiled* backend surfaced a real, independent
   lowering bug (nex.lower/infer-call-type): a still-generic function's own
   callN method-def declares its return type in the function's OWN
   unresolved generic-param name (e.g. literally \"T\"), and nothing
   substituted BINDING-TYPE's explicit `:type-args` through it before
   codegen used it -- the raw \"T\" then reached the runtime as if it were a
   real class name, crashing with `ClassNotFoundException: T` when the
   value was later called. compiled-generic-function-reference-... below
   pins that fix directly against the JVM backend, the same way sibling
   type_alias_generic_inference_test.clj pins its own compiled-backend-only
   lowering bug."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.classloader :as loader]))

(defn- run [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)]
    (interp/eval-node ctx ast)
    @(:output ctx)))

(defn- type-check [code]
  (tc/type-check (p/ast code)))

(defn- run-compiled
  "Actually define and invoke the compiled classes in a fresh loader (the
   same JVM linking path a real `nex compile jvm` jar goes through), not
   nex.eval -- a lowering-time exception is not swallowed by nex.eval's
   fallback-to-interpreter safety net, but a *runtime* LinkageError like the
   one this file's own bug produced would be, silently hiding a regression."
  [code]
  (let [{:keys [main-class classes]} (file/compile-ast
                                       "generic_type_alias_and_explicit_generic_args_test.nex"
                                       (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

;; --- (A) generic type aliases -------------------------------------------

(deftest generic-alias-resolves-through-a-concrete-instantiation
  (testing "a Function-shaped alias declared with its own [T] substitutes correctly
            when applied with an explicit concrete argument, for both backends"
    (let [code "declare type Pair_Fn[T] = Function(T, T): T

function combine(op: Pair_Fn[Integer], a: Integer, b: Integer): Integer
do
  result := op(a, b)
end

function add(a: Integer, b: Integer): Integer
do
  result := a + b
end

print(combine(add, 3, 4))"]
      (is (:success (type-check code)))
      (is (= ["7"] (run code)))
      (is (= ["7"] (run-compiled code))))))

(deftest generic-alias-arity-mismatch-is-reported-with-a-location
  (testing "Pair[Integer] against a 2-param alias is a clear, located error -- not the
            bogus, locationless 'Could not infer generic type parameter' this alias
            shape used to produce before generic aliases existed at all"
    (let [{:keys [success errors]}
          (type-check "declare type Pair[T, U] = Function(T, U): Boolean

function f(op: Pair[Integer], a: Integer): Boolean
do
  result := true
end")]
      (is (false? success))
      (is (= 1 (count errors)))
      (is (= "Expected 2 type arguments, got 1" (:message (first errors))))
      (is (some? (:line (first errors)))))))

(deftest non-generic-alias-and-refinement-alias-still-work
  (testing "plain (non-generic) declare type aliases, including refinements, are
            unaffected by generic-alias support -- both still typecheck and run"
    (is (:success (type-check "declare type Count = Integer
let c: Count := 5
print(c)")))
    (is (= ["5"] (run "declare type Count = Integer
let c: Count := 5
print(c)")))
    (is (:success (type-check "declare type Positive = Integer where n: n > 0
let p: Positive := 3
print(p)")))
    (is (= ["3"] (run "declare type Positive = Integer where n: n > 0
let p: Positive := 3
print(p)")))))

;; --- (B) explicit generic arguments --------------------------------------

(deftest explicit-generic-args-on-reference-and-call
  (testing "a generic free function can be pinned to a concrete instantiation
            explicitly, as a bare reference, as a direct call, or left to ordinary
            inference -- all three in the same program, on both backends"
    (let [code "function add[T](a: T, b: T): T
do
  result := a
end

let f := add[Integer]
print(f(2, 3))
print(add[Integer](4, 5))
print(add(6, 7))"]
      (is (:success (type-check code)))
      (is (= ["2" "4" "6"] (run code)))
      (is (= ["2" "4" "6"] (run-compiled code))))))

(deftest explicit-generic-args-on-non-generic-identifier-is-rejected
  (testing "[...] on a value that isn't generic is a clear, located error rather than
            silently producing a bogus parameterized type"
    (let [{:keys [success errors]}
          (type-check "let x := 5
print(x[Integer])")]
      (is (false? success))
      (is (= 1 (count errors)))
      (is (str/includes? (:message (first errors)) "is not generic"))
      (is (some? (:line (first errors)))))))

(deftest explicitly-pinned-generic-function-conforms-to-a-function-shaped-parameter
  (testing "a generic function explicitly pinned via [Integer] structurally conforms
            to a plain `Function(...)` parameter type -- types-compatible? previously
            only recognized a class implementing Function when it was a bare,
            non-generic class-name string; a parameterized reference
            ({:base-type ... :type-args [...]}, exactly what an explicit [Integer]
            produces) fell through every branch and was always rejected, even once
            fully concrete"
    (let [code "function add[T](a: T, b: T): T
do
  result := a
end

function apply2(op: Function(Integer, Integer): Integer, a: Integer, b: Integer): Integer
do
  result := op(a, b)
end

print(apply2(add[Integer], 3, 4))"]
      (is (:success (type-check code)))
      (is (= ["3"] (run code)))
      (is (= ["3"] (run-compiled code))))))

(deftest explicitly-pinned-generic-function-conforms-through-a-generic-alias
  (testing "the same conformance fix composed with (A): a generic function pinned via
            [Integer] also conforms to a generic Function-shaped alias applied to
            Integer -- exercising expand-type-aliases' substitution together with
            types-compatible?'s generic-class-implements-Function branch"
    (let [code "declare type Pair_Fn[T] = Function(T, T): T

function add[T](a: T, b: T): T
do
  result := a
end

function combine(op: Pair_Fn[Integer], a: Integer, b: Integer): Integer
do
  result := op(a, b)
end

print(combine(add[Integer], 3, 4))"]
      (is (:success (type-check code)))
      (is (= ["3"] (run code)))
      (is (= ["3"] (run-compiled code))))))

(deftest compiled-generic-function-reference-return-type-is-substituted
  (testing "storing an explicitly-instantiated generic function reference in a
            variable and calling it later resolves the callee's return type through
            the pinned type argument during JVM lowering, instead of leaking the
            function's own unresolved generic-param name (\"T\") into codegen as if
            it were a real class -- which used to crash at link time with
            `ClassNotFoundException: T`"
    (is (= ["2"]
           (run-compiled "function add[T](a: T, b: T): T
do
  result := a
end

let f := add[Integer]
print(f(2, 3))")))))
