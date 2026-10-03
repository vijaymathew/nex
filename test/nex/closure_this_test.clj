(ns nex.closure-this-test
  "`this` inside a closure is the object of the routine that made the closure,
   and `this` as a value in inherited code is the heir's object.

   Compiled backend: inherited code runs on a composition carrier, and `this`
   as a value (passed, stored, printed, captured by a closure) was that
   carrier, so a call on it ran the parent's version instead of the heir's
   override (nex.lower/lower-expr-this now reads `__outer__`). A closure using
   a field as a receiver (`n.to_string`) failed to lower.

   Interpreter: a closure's body ran with the closure itself as the current
   object, so `label()` and `this.label()` failed (\"Undefined method\"). A
   closure now carries its enclosing routine's object (eval-node
   :anonymous-function). Its calls count as qualified, as on the compiled
   backend: a closure may run after the routine that made it has returned."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "closure_this" ".nex")]
    (try
      (spit f code)
      (let [err (java.io.StringWriter.)
            out (binding [*err* err]
                  (with-out-str (e/eval-file (.getPath f) opts)))]
        (is (not (str/includes? (str err) "falling back")) (str err))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  "Printed output of CODE, asserted identical on both backends."
  [code]
  (let [compiled (run code {})]
    (is (= (run code {:interpret? true}) compiled) "compiled and interpreted output must agree")
    compiled))

(def ^:private child
  "class Child
inherit Base
create
  make do super.make end
feature
  label(): String do result := \"child\" end
end
let c: Child := create Child.make
c.run()
print(c.n)
let b: Base := create Base.make
b.run()
")

(deftest this-as-a-value-in-inherited-code-test
  (is (= ["\"child\"" "\"child\"" "#<Child object>" "1" "\"base\"" "\"base\"" "#<Base object>"]
         (both (str "class Base
create
  make do n := 1 end
feature
  n: Integer
  label(): String do result := \"base\" end
  describe(other: Base): String do result := other.label() end
  run() do
    let me: Base := this
    print(me.label())
    print(describe(this))
    print(this)
  end
end
" child)))))

(deftest closure-uses-its-routines-object-test
  (testing "unqualified and this-qualified calls, field reads, writes through a call"
    (is (= ["\"child 1\"" "\"child 1\"" "\"1\"" "11" "11" "\"base 1\"" "\"base 1\"" "\"1\"" "11"]
           (both (str "class Base
create
  make do n := 1 end
feature
  n: Integer
  label(): String do result := \"base\" end
  bump() do n := n + 10 end
  run() do
    let f := fn (): String do result := label() + \" \" + n.to_string end
    let g := fn (): String do result := this.label() + \" \" + this.n.to_string end
    let s := fn (): String do result := n.to_string end
    print(f())
    print(g())
    print(s())
    let h := fn () do bump() end
    h()
    print(n)
  end
end
" child))))))

(deftest nested-passed-spawned-and-private-test
  (is (= ["\"child/secret\"" "3" "\"child\"" "8" "9"]
         (both "class Base
create
  make do n := 1 end
feature
  n: Integer
  label(): String do result := \"base\" end
  apply_twice(f: Function(x: Integer): Integer, v: Integer): Integer do result := f(f(v)) end
  nested() do
    let outer := fn (): String do
      let inner := fn (): String do result := this.label() + \"/\" + secret() end
      result := inner()
    end
    print(outer())
  end
  passing() do
    print(apply_twice(fn (x: Integer): Integer do result := x + n end, 1))
  end
  spawned() do
    let t: Task[String] := spawn do result := label() end
    print(t.await)
  end
private feature
  secret(): String do result := \"secret\" end
end
class Child
inherit Base
create
  make do super.make end
feature
  label(): String do result := \"child\" end
end
let c: Child := create Child.make
c.nested()
c.passing()
c.spawned()
let k := fn (x: Integer): Integer do result := x * 2 end
print(k(4))
function top(): Integer do
  let g := fn (): Integer do result := 9 end
  result := g()
end
print(top())"))))

(deftest closure-calls-are-qualified-test
  (testing "a call from a closure checks the invariant, on both backends"
    (doseq [opts [{} {:interpret? true}]]
      (is (thrown-with-msg? Exception #"Class invariant violation: same"
                            (run "class Pair
create
  make do a := 0 b := 0 end
feature
  a: Integer
  b: Integer
  trace() do print(\"trace\") end
  bump do
    a := a + 1
    let f := fn () do trace() end
    f()
    b := b + 1
  end
invariant
  same: a = b
end
let p: Pair := create Pair.make
p.bump" opts))))))
