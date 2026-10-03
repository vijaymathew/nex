(ns nex.this-live-object-test
  "`this` in the interpreter is the object as the running routine has left it
   so far, and `this.m(...)` both sees and keeps the fields it changes.

   A routine's fields live as env bindings while it runs and are read back
   into the object when it returns, so the interpreter's `:current-object` is
   the object as it was on entry. `this` evaluated to that snapshot: `this.n`
   and `this.show()` missed the routine's own assignments, `peer(this)` passed
   a stale copy, and a field `this.bump()` changed was lost. Fixed in
   nex.interpreter/live-current-object and invoke-on-this!. The compiled
   backend already had reference semantics here."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends."
  [code]
  (let [f (java.io.File/createTempFile "this_live_object" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})]
        (is (= (run {:interpret? true}) compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest this-sees-the-routines-own-assignments-test
  (is (= ["\"show n=5\"" "\"this.n=5\"" "\"after this.bump n=6\"" "\"after this.n := 20, n=20\""
          "\"peer=20\"" "\"me.n=20\"" "\"final n=20\""]
         (both "class Counter
create
  make do n := 0 end
feature
  n: Integer
  show() do print(\"show n=\" + n.to_string) end
  bump() do n := n + 1 end
  peer(other: Counter): Integer do result := other.n end
  run() do
    n := 5
    this.show()
    print(\"this.n=\" + this.n.to_string)
    this.bump()
    print(\"after this.bump n=\" + n.to_string)
    this.n := 20
    print(\"after this.n := 20, n=\" + n.to_string)
    print(\"peer=\" + peer(this).to_string)
    let me: Counter := this
    print(\"me.n=\" + me.n.to_string)
  end
end
let c: Counter := create Counter.make
c.run()
print(\"final n=\" + c.n.to_string)"))))

(deftest this-calls-nested-recursive-private-and-shadowed-test
  (is (= ["\"note total=15\"" "\"note total=20\"" "\"total=20\"" "\"depth=23\""
          "\"param n=7 this.n=0\"" "\"after: this.n=7\"" "\"n=7\"" "\"20 7\""]
         (both "class Acc
create
  make do n := 0 total := 0 end
feature
  n: Integer
  total: Integer
  set(n: Integer) do
    print(\"param n=\" + n.to_string + \" this.n=\" + this.n.to_string)
    this.n := n
    print(\"after: this.n=\" + this.n.to_string)
  end
  add_twice(x: Integer) do this.add(x) this.add(x) end
  add(x: Integer) do total := total + x this.log_total() end
  depth(k: Integer): Integer do
    if k = 0 then result := total else result := this.depth(k - 1) + 1 end
  end
  run() do
    total := 10
    this.add_twice(5)
    print(\"total=\" + total.to_string)
    print(\"depth=\" + this.depth(3).to_string)
    this.set(7)
    print(\"n=\" + n.to_string)
  end
private feature
  log_total() do print(\"note total=\" + total.to_string) end
end
let a: Acc := create Acc.make
a.run()
print(a.total.to_string + \" \" + a.n.to_string)"))))

(deftest this-in-constructor-and-through-an-override-test
  (is (= ["\"ctor n=11\"" "\"child 2\"" "\"child 12\"" "12"]
         (both "class Base
create
  make do n := 1 this.bump() print(\"ctor n=\" + this.n.to_string) end
feature
  n: Integer
  bump() do n := n + 10 end
  label(): String do result := \"base\" end
  run() do
    n := 2
    print(this.label() + \" \" + this.n.to_string)
    this.bump()
    print(this.label() + \" \" + this.n.to_string)
  end
end
class Child
inherit Base
create
  make do super.make end
feature
  label(): String do result := \"child\" end
end
let c: Child := create Child.make
c.run()
print(c.n)"))))
