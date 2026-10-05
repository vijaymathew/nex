(ns nex.native-closures-test
  "Closures that capture, compiled as ordinary classes (lower/*native-closures*,
   docs/md/COMPILED_CLOSURES.md) rather than run on the tree-walking
   interpreter. Each program must print the same as the interpreter-backed
   path does, and must never enter the interpreter."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.interpreter :as interp]
            [nex.lower :as lower]))

(defn- run-native
  "Printed lines of CODE run compiled with native closures, asserting that no
   interpreter evaluation happened along the way."
  [code]
  (let [f (java.io.File/createTempFile "native_closures" ".nex")
        calls (atom 0)
        eval-node interp/eval-node]
    (try
      (spit f code)
      (let [err (java.io.StringWriter.)
            out (with-redefs [interp/eval-node (fn [& args] (swap! calls inc) (apply eval-node args))]
                  (binding [lower/*native-closures* true
                            *err* err]
                    (with-out-str (e/eval-file (.getPath f) {}))))]
        (is (zero? @calls) "a compiled closure must not run on the interpreter")
        (is (not (str/includes? (str err) "falling back")) (str err))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(deftest captures-by-value-boxed-lets-and-params-test
  (testing "by-value captures, shared mutable lets, and a mutated captured parameter"
    (is (= ["11" "6" "7" "7" "7" "6"]
           (run-native "let k: Integer := 10
let f := fn (x: Integer): Integer do result := x + k end
print(f(1))
function make_counter(start: Integer): Function(): Integer do
  result := fn (): Integer do
    start := start + 1
    result := start
  end
end
let c := make_counter(5)
print(c())
print(c())
let total := 0
let add := fn (x: Integer) do total := total + x end
let peek := fn (): Integer do result := total end
add(3)
add(4)
print(peek())
print(total)
class Acc
create
  make() do count := 0 end
feature
  count: Integer
  bump_all(xs: Array[Integer]) do
    let g := fn (x: Integer) do count := count + x end
    across xs as x do g(x) end
  end
end
let a := create Acc.make()
a.bump_all([1, 2, 3])
print(a.count)")))))

(deftest private-features-through-captured-this-test
  (testing "a closure reaches its enclosing class's private fields and routines"
    ;; The interpreter-backed path fails this program with
    ;; \"Undefined field: secret\": the interpreted closure body cannot read a
    ;; private field of a compiled object.
    (is (= ["\"secret=6 bumps=3\""]
           (run-native "class Counter
create
  make() do secret := 0 end
feature
  add_all(xs: Array[Integer]) do
    let f := fn (x: Integer) do secret := secret + x  bump() end
    across xs as x do f(x) end
  end
  report(): String do result := \"secret=\" + secret.to_string + \" bumps=\" + bumps.to_string end
private feature
  secret: Integer
  bumps: Integer
  bump() do bumps := bumps + 1 end
end
let c := create Counter.make()
c.add_all([1, 2, 3])
print(c.report())")))))

(deftest generic-nested-spawned-and-loop-captures-test
  (testing "generic captures, nested closures, spawn, across-loop variables"
    (is (= ["\"boxed\"" "\"boxed\"" "123" "42" "5" "10" "20" "30" "true"]
           (run-native "class Box [T]
create
  make(v: T) do value := v end
feature
  value: T
  getter(): Function(): T do
    let v: T := value
    result := fn (): T do result := v end
  end
  self_getter(): Function(): T do
    result := fn (): T do result := value end
  end
end
let b := create Box[String].make(\"boxed\")
print(b.getter()())
print(b.self_getter()())
function adder(n: Integer): Function(x: Integer): Function(y: Integer): Integer do
  result := fn (x: Integer): Function(y: Integer): Integer do
    result := fn (y: Integer): Integer do result := n + x + y end
  end
end
print(adder(100)(20)(3))
let base := 7
let t: Task[Integer] := spawn do
  result := base * 6
end
print(t.await)
let shared := 0
let t2 := spawn do
  shared := shared + 5
end
t2.await
print(shared)
let fs: Array[Function(): Integer] := []
across [1, 2, 3] as i do
  fs.add(fn (): Integer do result := i * 10 end)
end
across fs as g do print(g()) end
let h := fn (): Integer do result := base end
print(h = h)")))))

(deftest constrained-generic-function-capture-test
  (testing "a closure capturing a value typed by a constrained generic function's parameter"
    (is (= ["[1, 3, 0, 2]"]
           (run-native "function grade_up[T -> Comparable](a: Array[T]): Array[Integer] do
  result := []
  from let i := 0
  until i >= a.length do
    result.add(i)
    i := i + 1
  end
  result := result.sort(fn(i, j: Integer): Integer do result := a.get(i).compare(a.get(j)) end)
end
print(grade_up([3, 1, 4, 1]))")))))

(deftest nested-this-and-alias-typed-result-test
  (testing "a closure built inside another passes on the enclosing instance; result := fn under a Function alias"
    (is (= ["1" "9.0"]
           (run-native "function apply1(f: Function(): Integer): Integer do
  result := f()
end
class Counter
feature
  count: Integer
  bump() do
    count := count + 1
  end
  nested_this(): Integer do
    let f: Function(): Integer := fn (): Integer do
      result := apply1(fn (): Integer do
        bump()
        result := count
      end)
    end
    result := f()
  end
end
let c: Counter := create Counter
print(c.nested_this())
declare type RR = Function(Real): Real
function average(a, b: Real): Real do
  result := (a + b) / 2.0
end
function average_damp(f: RR): RR do
  result := fn(x: Real) do result := average(x, f(x)) end
end
let double: RR := fn(x: Real): Real do result := x * 2.0 end
print(average_damp(double)(6.0))")))))
