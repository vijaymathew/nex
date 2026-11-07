(ns nex.function-call-protocol-test
  "Invoking a function value through its call protocol, `f.callN(...)`
   (Definition B.1), and through a call's own result, `adder(1)(...)`.

   The compiled backend refused `f.call1(x)` outright (it lowered only the
   nameless `a(1)(2)` form as a function call); it now lowers both the same
   way. Separately, the typechecker returned the declared result type for both
   forms without checking the arguments against the function's parameters, so
   a wrong argument type or count reached run time; it now checks them
   exactly as a direct `f(x)` call does."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "function_call_protocol" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})
            interpreted (run {:interpret? true})]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(defn- type-error [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (pr-str (:errors result)))))

(def ^:private adder
  "function adder(n: Integer): Function(y: Integer): Integer do
  result := fn(y: Integer): Integer do result := y + n end
end
")

(deftest call-protocol-invokes-function-values-test
  (testing "callN on a closure, a plain Function, a named function's value,
            and a positional-parameter signature"
    (is (= ["8" "8" "10" "\"hi\"" "4" "3"]
           (both (str adder "let f := fn(x: Integer): Integer do result := x * 2 end
print(f.call1(4))
let g: Function := f
print(g.call1(4))
function twice(x: Integer): Integer do result := x * 2 end
let h: Function(x: Integer): Integer := twice
print(h.call1(5))
let s := fn(): String do result := \"hi\" end
print(s.call0())
let k: Function(Integer, x: Array[String]): Integer := fn(n: Integer, x: Array[String]): Integer do result := n + x.length end
print(k.call2(1, [\"a\", \"b\", \"c\"]))
print(adder(1)(2))"))))))

(deftest call-protocol-result-is-typed-test
  (testing "the call's type is the function's declared result type"
    (is (= ["8"]
           (both "let f := fn(x: Integer): Integer do result := x * 2 end
let n: Integer := f.call1(4)
print(n)")))))

(deftest call-protocol-result-dispatches-on-its-type-test
  (testing "a feature called on a callN result dispatches on the declared
            result type, not the Any protocol (the compiled backend lowered
            `xs.tail.call0().first` as an Any call and failed with an
            unbound builtin-method-any-first)"
    (is (= ["1" "1" "3"]
           (both "class Cons[T]
feature
  head: T
  tail: Function(): Cons[T]
create
  make(h: T, t: Function(): Cons[T]) do head := h tail := t end
feature
  first: T do result := head end
  rest: Cons[T] do result := tail() end
end

function lseq(n: Integer): Cons[Integer]
do
  result := create Cons.make(n, fn(): Cons[Integer] do result := lseq(n + 1) end)
end

let xs := lseq(0)
print(xs.tail.call0().first)
print(xs.tail.call0().head)
print(xs.tail.call0().tail.call0().rest.first)")))))

(deftest call-protocol-arguments-are-checked-test
  (testing "argument types and count are held to the function's parameters,
            with the messages a direct call gets"
    (doseq [[code expected] [["let f := fn(x: Integer): Integer do result := x * 2 end\nprint(f.call1(\"s\"))"
                              #"should be Integer, but got String"]
                             ["let f := fn(x: Integer): Integer do result := x * 2 end\nprint(f.call2(4, 5))"
                              #"Method call2 expects 1 arguments, got 2"]
                             [(str adder "print(adder(1)(\"s\"))")
                              #"should be Integer, but got String"]
                             [(str adder "print(adder(1)(2, 3))")
                              #"Method call2 expects 1 arguments, got 2"]
                             ["let k: Function(Integer, x: Array[String]): Integer := fn(n: Integer, x: Array[String]): Integer do result := n end\nprint(k.call2(1, [2]))"
                              #"should be Array\[String\], but got Array\[Integer\]"]
                             ["let f: Function(Integer): Integer := fn(n: Integer): Integer do result := n end\nlet a: Any := 3\nprint(f.call1(a))"
                              #"parameter 'arg1' of call1"]]]
      (let [err (type-error code)]
        (is (some? err) (str "expected a type error for:\n" code))
        (is (re-find expected (or err "")) err)))))

(deftest direct-calls-are-unchanged-test
  (testing "the direct spelling keeps its own checks"
    (is (re-find #"should be Integer, but got String"
                 (or (type-error "let f := fn(x: Integer): Integer do result := x * 2 end\nprint(f(\"s\"))") "")))
    (is (re-find #"expects 1 arguments, got 2"
                 (or (type-error "let f := fn(x: Integer): Integer do result := x * 2 end\nprint(f(1, 2))") "")))))

(deftest only-function-objects-are-callable-test
  (testing "a class with callN that inherits Function can be called"
    (is (= ["100"]
           (both "class Times_Ten
inherit Function
feature
  call1(a: Integer): Integer do result := a * 10 end
end
let f := create Times_Ten
print(f(10))
"))))
  (testing "a class with callN that does not inherit Function cannot be called"
    (let [err (type-error "class Times_Ten
feature
  call1(a: Integer): Integer do result := a * 10 end
end
let f := create Times_Ten
print(f(10))
")]
      (is (some? err))
      (is (str/includes? err "not callable")))))

(deftest function-values-of-each-arity-test
  (testing "closures of 0 to 4 parameters and Function subclasses with call1
            and call3, called directly and through a parameter"
    (is (= ["7" "8" "9" "10" "14" "30" "6"]
           (both "class Times_Ten
inherit Function
feature
  call1(a: Integer): Integer do result := a * 10 end
end
class Sum3
inherit Function
feature
  call3(a, b, c: Integer): Integer do result := a + b + c end
end
function apply1(g: Function(x: Integer): Integer, x: Integer): Integer do result := g(x) end
let f0 := fn (): Integer do result := 7 end
let f1 := fn (x: Integer): Integer do result := x + 1 end
let f2 := fn (x, y: Integer): Integer do result := x + y end
let f3 := fn (x, y, z: Integer): Integer do result := x + y + z end
let f4 := fn (w, x, y, z: Integer): Integer do result := w + x + y + z end
print(f0())
print(f1(7))
print(f2(4, 5))
print(f3(2, 3, 5))
print(f4(2, 3, 4, 5))
print(apply1(create Times_Ten, 3))
let s := create Sum3
print(s(1, 2, 3))
")))))

(deftest typed-function-value-calls-test
  (testing "calls of function values whose static type gives a typed call:
            closures of each scalar type, a mixed signature, a procedure, an
            alias, a Function subclass, a function passed by name, a closure
            whose signature differs from its variable's type, a detachable
            result, a curried call, the call1 spelling, and a raise"
    (is (= ["2" "3.0" "false" "#q" "4" "\"s=3.5\"" "11" "12" "9" "42" "\"pos\"" "nil"
            "30" "6" "4" "3" "10" "\"raised\"" "33"]
           (both "class Times_Ten
inherit Function
feature
  call1(a: Integer): Integer do result := a * 10 end
end
declare type Transformer = Function(n: Integer): Integer
function double_it(n: Integer): Integer do result := n * 2 end
function apply1(g: Function(x: Integer): Integer, x: Integer): Integer do result := g(x) end
function adder(n: Integer): Function(y: Integer): Integer do
  result := fn (y: Integer): Integer do result := y + n end
end
let fi := fn (x: Integer): Integer do result := x + 1 end
let fr := fn (x: Real): Real do result := x * 2.0 end
let fb := fn (b: Boolean): Boolean do result := not b end
let fc := fn (c: Char): Char do result := c end
let fs := fn (s: String): Integer do result := s.length end
let mixed := fn (a: Integer, b: Real, c: String): String do result := c + (a.to_real + b).to_string end
let total := 0
let proc := fn (x: Integer) do total := total + x end
let t: Transformer := fn (n: Integer): Integer do result := n * 3 end
let loose: Function(x: Integer): Any := fn (x: Integer): Integer do result := x - 1 end
let wide: Function(x: Integer): Integer := fn (x: Any): Integer do result := 42 end
let maybe := fn (x: Integer): ?String do if x > 0 then result := \"pos\" else result := nil end end
let raiser := fn (x: Integer): Integer do raise \"boom\" end
function try_raise(): String do
  result := raiser(1).to_string
rescue
  result := \"raised\"
end
print(fi(1))
print(fr(1.5))
print(fb(true))
print(fc(#q))
print(fs(\"abcd\"))
print(mixed(1, 2.5, \"s=\"))
proc(5)
proc(6)
print(total)
print(t(4))
print(loose(10))
print(wide(10))
print(maybe(1))
print(maybe(-1))
print(apply1(create Times_Ten, 3))
print(apply1(double_it, 3))
print(apply1(fi, 3))
print(adder(1)(2))
print(fi.call1(9))
print(try_raise())
let fns: Array[Function(x: Integer): Integer] := [fi, double_it, create Times_Ten, t]
let acc := 0
across fns as g do acc := acc + g(2) end
print(acc)
")))))
