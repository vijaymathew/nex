(ns nex.self-call-dispatch-test
  "An unqualified call (`f`, `f()`) in inherited code runs the heir's override,
   as `this.f` does.

   The compiled backend runs inherited code on a composition carrier, and used
   to link `f()` straight to the carrier's own routine, so it ran the parent's
   version (only paren-less `f` went through `__outer__`). Fixed in
   nex.lower/overridable-self-call-ir: the call is direct when `this` is the
   object itself (`__outer__` = `this`), and otherwise dispatches on
   `__outer__` (nex.compiler.jvm.runtime/dispatch-self-call)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends."
  [code]
  (let [f (java.io.File/createTempFile "self_call_dispatch" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})]
        (is (= (run {:interpret? true}) compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest inherited-code-runs-the-override-test
  (testing "with and without parens, with an argument, and with no result"
    (is (= ["\"base\"" "\"base\"" "\"base\"" "\"base1\"" "\"base shout\""
            "\"child\"" "\"child\"" "\"child\"" "\"child1\"" "\"child shout\""]
           (both "class Base
create
  make do end
feature
  label(): String do result := \"base\" end
  tag(x: Integer): String do result := \"base\" + x.to_string end
  shout(): Void do print(\"base shout\") end
  show do
    print(label())
    print(label)
    print(this.label)
    print(tag(1))
    shout()
  end
end
class Child
inherit Base
create
  make do end
feature
  label(): String do result := \"child\" end
  tag(x: Integer): String do result := \"child\" + x.to_string end
  shout(): Void do print(\"child shout\") end
end
let b: Base := create Base.make
b.show
let c: Child := create Child.make
c.show")))))

(deftest override-two-levels-down-test
  (testing "a grandparent's routine, called from the parent's code, overridden by the grandchild"
    (is (= ["\"hello A\"" "\"B says A\"" "\"hello C\"" "\"B says C\"" "\"hello C\""]
           (both "class A
create
  make do end
feature
  name(): String do result := \"A\" end
  greet do print(\"hello \" + name()) end
end
class B
inherit A
create
  make do end
feature
  intro do greet() print(\"B says \" + name()) end
end
class C
inherit B
create
  make do end
feature
  name(): String do result := \"C\" end
end
let b: B := create B.make
b.intro
let c: C := create C.make
c.intro
c.greet")))))

(deftest override-of-scalar-and-generic-results-test
  (is (= ["110" "5.0" "\"x\"" "true" "100" "11" "1.0" "\"y\"" "false" "1"]
         (both "class Box [T]
create
  make(v: T) do value := v end
feature
  value: T
  weight(): Integer do result := 1 end
  ratio(): Real do result := 0.5 end
  pick(): T do result := value end
  ok(): Boolean do result := false end
  report do
    print(weight() + 10)
    print(ratio() * 2.0)
    print(pick())
    print(ok())
    print(weight)
  end
end
class Heavy [T]
inherit Box [T]
create
  make(v: T) do super.make(v) end
feature
  weight(): Integer do result := 100 end
  ratio(): Real do result := 2.5 end
  pick(): T do result := value end
  ok(): Boolean do result := true end
end
let h: Heavy[String] := create Heavy[String].make(\"x\")
h.report
let b: Box[String] := create Box[String].make(\"y\")
b.report"))))

(deftest recursion-through-an-override-test
  (is (= ["7" "3"]
         (both "class Counter
create
  make do end
feature
  limit(): Integer do result := 3 end
  count(n: Integer): Integer do
    if n >= limit() then result := n else result := count(n + 1) end
  end
end
class Long_Counter
inherit Counter
create
  make do end
feature
  limit(): Integer do result := 7 end
end
let c: Long_Counter := create Long_Counter.make
print(c.count(0))
let d: Counter := create Counter.make
print(d.count(0))"))))

(deftest overridden-call-mid-update-is-not-checked-test
  (testing "dispatching to the heir's override is still an unqualified call"
    (is (= ["\"child step 1 0\"" "1"]
           (both "class Base
create
  make do a := 0 b := 0 end
feature
  a: Integer
  b: Integer
  step(): String do result := \"base step\" end
  run do
    a := a + 1
    print(step())
    b := b + 1
  end
invariant
  same: a = b
end
class Child
inherit Base
create
  make do end
feature
  step(): String do result := \"child step \" + a.to_string + \" \" + b.to_string end
end
let c: Child := create Child.make
c.run
print(c.b)")))))

(deftest super-and-parent-qualified-calls-stay-static-test
  (is (= ["\"child+base\"" "\"base\""]
         (both "class Base
create
  make do end
feature
  label(): String do result := \"base\" end
end
class Child
inherit Base
create
  make do end
feature
  label(): String do result := \"child+\" + super.label() end
  both do print(label()) print(Base.label()) end
end
let c: Child := create Child.make
c.both"))))
