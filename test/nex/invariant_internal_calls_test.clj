(ns nex.invariant-internal-calls-test
  "The class invariant is checked on exit from qualified calls only (`x.f`,
   `this.f`), as in Eiffel. An unqualified call (`f`, `f()`), `super.f` and
   `Parent.f` are part of the routine making them, so a routine may break the
   invariant for a moment and call a helper.

   The compiled backend emits each routine of a class whose hierarchy
   declares an invariant twice — a checked `__method_` stub and an unchecked
   `__imethod_` twin that internal calls link to (nex.lower/self-call-method-
   name). The interpreter flags its re-dispatch of an unqualified call with
   :unchecked-call?."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "invariant_internal_calls" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) opts))))
      (finally (.delete f)))))

(defn- both
  "Printed output of CODE, asserted identical on both backends."
  [code]
  (let [compiled (run code {})]
    (is (= (run code {:interpret? true}) compiled) "compiled and interpreted output must agree")
    compiled))

(defn- violation-on-both [code]
  (doseq [opts [{} {:interpret? true}]]
    (is (thrown-with-msg? Exception #"Class invariant violation: same" (run code opts))
        (str "backend " (if (:interpret? opts) "interpreter" "compiled")))))

(def ^:private pair
  "class Pair
create
  make do a := 0 b := 0 end
feature
  a: Integer
  b: Integer
  half do a := a + 1 end
  trace() do print(\"trace \" + a.to_string + \" \" + b.to_string) end
  %s
invariant
  same: a = b
end
")

(def ^:private base
  "class Base
create
  make do a := 0 b := 0 end
feature
  a: Integer
  b: Integer
  inc_a do a := a + 1 end
  inc_b do b := b + 1 end
  trace() do print(\"base trace \" + a.to_string + \" \" + b.to_string) end
invariant
  same: a = b
end
")

(deftest unqualified-helper-call-is-not-checked-test
  (testing "bare, parenthesised and private helpers called mid-update"
    (doseq [call ["trace" "trace()"]]
      (is (= ["\"trace 1 0\"" "1"]
             (both (str (format pair (str "bump do a := a + 1 " call " b := b + 1 end"))
                        "let p: Pair := create Pair.make\np.bump\nprint(p.b)")))))
    (is (= ["\"log 1 0\"" "1"]
           (both "class Pair
create
  make do a := 0 b := 0 end
feature
  a: Integer
  b: Integer
  bump do
    a := a + 1
    log
    b := b + 1
  end
private feature
  log do print(\"log \" + a.to_string + \" \" + b.to_string) end
invariant
  same: a = b
end
let p: Pair := create Pair.make
p.bump
print(p.b)")))))

(deftest unqualified-query-in-expression-is-not-checked-test
  (testing "a bare zero-argument query read mid-update"
    (is (= ["1"]
           (both (str (format pair "gap: Integer do result := a - b - 1 end
  bump do
    a := a + 1
    b := gap + b + 1
  end")
                      "let p: Pair := create Pair.make\np.bump\nprint(p.b)"))))))

(deftest unqualified-call-from-constructor-is-not-checked-test
  (is (= ["\"log 5 0\"" "5"]
         (both "class Pair
create
  make do
    a := 5
    log
    b := 5
  end
feature
  a: Integer
  b: Integer
  log do print(\"log \" + a.to_string + \" \" + b.to_string) end
invariant
  same: a = b
end
let p: Pair := create Pair.make
print(p.b)"))))

(deftest unqualified-calls-to-inherited-routines-are-not-checked-test
  (testing "a subclass calls inherited setters and a query, bare and with parens"
    (doseq [[inc-a call] [["inc_a" "trace"] ["inc_a()" "trace()"]]]
      (is (= ["\"base trace 1 0\"" "1"]
             (both (str base "class Child
inherit Base
create
  make do c := 0 end
feature
  c: Integer
  bump do
    " inc-a "
    c := c + 1
    " call "
    inc_b
  end
invariant
  nonneg: c >= 0
end
let k: Child := create Child.make
k.bump
print(k.b)")))))))

(deftest template-routine-calling-deferred-step-is-not-checked-test
  (testing "a deferred class's routine calls a deferred feature its heir implements"
    (is (= ["\"step\"" "1"]
           (both "deferred class Base
feature
  a: Integer
  b: Integer
  run do
    a := a + 1
    step
    b := b + 1
  end
  step() deferred
invariant
  same: a = b
end
class Child
inherit Base
feature
  step() do print(\"step\") end
end
let k: Child := create Child
k.run
print(k.b)")))))

(deftest super-and-parent-qualified-calls-are-not-checked-test
  (doseq [call ["super.trace" "Base.trace"]]
    (testing call
      (is (= ["\"base trace 1 0\"" "1"]
             (both (str base "class Child
inherit Base
create
  make do end
feature
  trace() do print(\"child trace\") end
  bump do
    inc_a
    " call "
    inc_b
  end
end
let k: Child := create Child.make
k.bump
print(k.b)")))))))

(deftest unchecked-twin-forwards-arguments-and-result-test
  (testing "a generic class's routines with arguments, a result and an explicit Void"
    (is (= ["33" "\"t\"" "\"ping 4 3\"" "4"]
           (both "class Acc [T]
create
  make(seed: T) do
    last := seed
    a := 0
    b := 0
  end
feature
  last: T
  a: Integer
  b: Integer
  push(x: T, w: Integer): Integer do
    a := a + w
    result := weigh(x, w) + a
    b := b + w
  end
  weigh(x: T, w: Integer): Integer do
    last := x
    result := w * 10
  end
  ping(): Void do print(\"ping \" + a.to_string + \" \" + b.to_string) end
  both do
    a := a + 1
    ping()
    b := b + 1
  end
invariant
  same: a = b
end
let acc: Acc[String] := create Acc[String].make(\"s\")
print(acc.push(\"t\", 3))
print(acc.last)
acc.both
print(acc.b)")))))

(deftest self-call-arguments-are-evaluated-once-test
  (testing "the interpreter used to evaluate an unqualified call's arguments twice"
    (is (= ["\"tick 1\"" "\"show 1 n=1\"" "\"tick 2\"" "\"show 2 n=2\"" "2"]
           (both "class Pair
create
  make do n := 0 end
feature
  n: Integer
  tick: Integer do n := n + 1 result := n print(\"tick \" + n.to_string) end
  show(x: Integer) do print(\"show \" + x.to_string + \" n=\" + n.to_string) end
  go do show(tick) show(tick()) end
end
let p: Pair := create Pair.make
p.go
print(p.n)")))))

(deftest qualified-calls-are-still-checked-test
  (testing "an outside call that leaves the invariant broken"
    (violation-on-both (str (format pair "") "let p: Pair := create Pair.make\np.half")))
  (testing "an unqualified helper may break it, but the routine calling it must restore it"
    (violation-on-both (str (format pair "outer do half end") "let p: Pair := create Pair.make\np.outer")))
  (testing "a call on another object of the same class"
    (violation-on-both (str (format pair "poke(other: Pair) do other.half end")
                            "let p: Pair := create Pair.make\nlet q: Pair := create Pair.make\np.poke(q)")))
  (testing "an outside call to an inherited routine"
    (violation-on-both (str base "class Child
inherit Base
create
  make do end
end
let k: Child := create Child.make
k.inc_a"))))

(deftest this-qualified-call-is-checked-test
  ;; Compiled backend only: the interpreter runs `this.f` against the object
  ;; as it was on entry to the calling routine, so it sees no broken invariant.
  (testing "this.f is a qualified call"
    (is (thrown-with-msg? Exception #"Class invariant violation: same"
                          (run (str (format pair "bump do a := a + 1 this.trace b := b + 1 end")
                                    "let p: Pair := create Pair.make\np.bump")
                               {})))
    (is (thrown-with-msg? Exception #"Class invariant violation: same"
                          (run (str base "class Child
inherit Base
create
  make do end
feature
  bump do
    inc_a
    this.trace
    inc_b
  end
end
let k: Child := create Child.make
k.bump")
                               {})))))
