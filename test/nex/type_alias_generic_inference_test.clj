(ns nex.type-alias-generic-inference-test
  "A compiled-backend-only lowering bug found stress-testing a top-level
   function whose param/return types are spelled through a `declare type`
   alias (e.g. `declare type RR = Function(Real): Real` /
   `function average_damp(f: RR): RR`).

   nex.lower/free-function-generic-param-names decides which of a free
   function's param/return type names are *unbound generic placeholders*
   (as opposed to real classes or builtins) purely by name: not a known
   class, not a builtin type, doesn't start with \"__\". A `declare type`
   alias name matches none of those exclusions either, so it was wrongly
   treated as a free generic parameter. infer-free-function-return-type then
   \"inferred\" a binding for that bogus generic from whatever type the call
   site's argument happened to carry — for a bare top-level-function-
   reference argument (`average_damp(square)`), the synthetic
   `<name>_Function` wrapper-class type nex.lower itself generates for
   passing a function by name — and substituted that nonsense type in place
   of the alias in the function's own declared return type. The leaked type
   name (`square_Function`, not a real dispatchable receiver type) then
   crashed lowering of the call *using* that return value with \"Unsupported
   user-defined target access during lowering\", reported to the user as
   'this program uses a construct the compiled backend does not support
   yet' — a real compiler defect, not a genuinely unsupported construct.

   Reproduced via a real standalone class-load-and-run (loader/define-class!
   + reflective invoke — the same JVM linking/access-control path a real
   `nex compile jvm` jar goes through), not nex.eval: a lowering-time
   exception is not swallowed by nex.eval's fallback-to-interpreter safety
   net either, but this harness matches the sibling
   generic_inheritance_lowering_test.clj convention for compiled-backend-
   only lowering bugs."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.classloader :as loader]))

(defn- run-compiled [code]
  (let [{:keys [main-class classes]} (file/compile-ast "type_alias_generic_inference_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(deftest function-typed-alias-param-and-return-not-mistaken-for-a-generic-test
  (testing "a free function declared through a Function(...) type alias (`f: RR): RR`),
            called with a bare top-level-function reference, still lowers and runs
            correctly instead of crashing on a leaked synthetic wrapper-class type"
    (is (= ["55.0"]
           (run-compiled "declare type RR = Function(Real): Real

function square(x: Real): Real
do
  result := x * x
end

function average(a, b: Real): Real
do
  result := (a + b) / 2.0
end

function average_damp(f: RR): RR
do
  result := fn(x: Real) do result := average(x, f(x)) end
end

print(average_damp(square)(10.0))")))))

(deftest function-typed-alias-param-and-return-works-with-an-inline-lambda-argument-test
  (testing "the same alias-typed call also works when the RR argument is an inline
            lambda instead of a bare top-level-function reference"
    (is (= ["9.0"]
           (run-compiled "declare type RR = Function(Real): Real

function average(a, b: Real): Real
do
  result := (a + b) / 2.0
end

function average_damp(f: RR): RR
do
  result := fn(x: Real) do result := average(x, f(x)) end
end

let double: RR := fn(x: Real): Real do result := x * 2.0 end
print(average_damp(double)(6.0))")))))
