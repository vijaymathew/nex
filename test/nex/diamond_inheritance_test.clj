(ns nex.diamond-inheritance-test
  "Repeated inheritance (a diamond): a class that reaches a common ancestor
   along more than one path holds one copy of that ancestor's fields per
   path (Definition §4.9, §5.1).

   1. Each path's copy is attached by its own constructor delegation, and the
      code of a class on that path reads and writes that copy.
   2. A client, and an unqualified reference in the heir, reach the shared
      ancestor's members along the first path in `inherit` order.
   3. Naming an ancestor (`Right.count`, `Right.bump()`) picks its path.
   4. A call on the current object, bare or `this.f`, made by code running
      along a path: an override between the object's class and that code
      wins, the outermost first; with none, the call stays on the code's own
      path (Definition §5.4). An override on one path does not leak onto the
      other.
   5. The invariant holds on every copy, and `=` compares every copy.

   The compiled backend used to run an inherited routine called from one
   path on the first path's copy; the interpreter kept a single copy."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "diamond_inheritance" ".nex")]
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

(def ^:private diamond
  "class Counter
  create make(start: Integer) do count := start end
feature
  count: Integer
  bump() do count := count + 1 end
  bump_twice() do bump() bump() end
  drop() do count := count - 1 end
invariant
  non_negative: count >= 0
end

class Left
  inherit Counter
  create make() do Counter.make(10) end
feature
  bump_left() do bump() end
  left_count(): Integer do result := count end
end

class Right
  inherit Counter
  create make() do Counter.make(20) end
feature
  bump_right() do bump() end
  bump_right_qualified() do this.bump() end
  bump_right_twice() do bump_twice() end
  drop_right() do drop() end
  right_count(): Integer do result := count end
  right_this(): Integer do result := this.count end
  right_closure(): Integer do
    let f := fn (): Integer do result := count end
    result := f()
  end
end

class Both
  inherit Left, Right
  create make() do Left.make() Right.make() end
feature
  left_copy(): Integer do result := Left.count end
  right_copy(): Integer do result := Right.count end
  bump_via_right() do Right.bump() end
end

function counts(b: Both): String do
  result := b.left_count.to_string + \" \" + b.right_count.to_string
end
")

(deftest each-path-has-its-own-copy
  (testing "the Definition's example: bumping through Left leaves Right's copy alone"
    (is (= ["2" "0"]
           (both "class Counter
  create make() do count := 0 end
feature
  count: Integer
  bump() do count := count + 1 end
end
class Left
  inherit Counter
  create make() do Counter.make() end
feature
  bump_left() do bump() end
  left_count(): Integer do result := count end
end
class Right
  inherit Counter
  create make() do Counter.make() end
feature
  right_count(): Integer do result := count end
end
class Both
  inherit Left, Right
  create make() do Left.make() Right.make() end
end
let b := create Both.make()
b.bump_left()
b.bump_left()
print(b.left_count())
print(b.right_count())"))))
  (testing "each copy is attached by its own path's constructor, and an inherited routine
            called from Right's code acts on Right's copy"
    (is (= ["\"10 20\"" "\"12 21\""]
           (both (str diamond "let b := create Both.make()
print(counts(b))
b.bump_left
b.bump_left
b.bump_right
print(counts(b))"))))))

(deftest clients-reach-the-first-path
  (is (= ["\"11 11 20\""]
         (both (str diamond "let b := create Both.make()
b.bump
print(b.count.to_string + \" \" + counts(b))")))))

(deftest naming-an-ancestor-picks-its-path
  (is (= ["\"10 21\"" "\"10 21\""]
         (both (str diamond "let b := create Both.make()
b.bump_via_right
print(b.left_copy.to_string + \" \" + b.right_copy.to_string)
print(counts(b))")))))

(deftest calls-stay-on-the-running-path
  (testing "`this.f`, a call made inside the shared ancestor's own text, `this.field`,
            and a closure all act on the path the code runs on"
    (is (= ["\"10 23\"" "\"23 23\""]
           (both (str diamond "let b := create Both.make()
b.bump_right_qualified
b.bump_right_twice
print(counts(b))
print(b.right_this.to_string + \" \" + b.right_closure.to_string)"))))))

(deftest an-override-in-the-heir-wins-on-every-path
  (is (= ["\"10 23\""]
         (both (str diamond "class Routed
  inherit Left, Right
  create make() do Left.make() Right.make() end
feature
  bump() do Right.bump() end
end
let r := create Routed.make()
r.bump_left
r.bump_right
r.bump
print(r.left_count.to_string + \" \" + r.right_count.to_string)")))))

(deftest an-override-on-one-path-does-not-leak
  (testing "Doubling_Left's override serves Left's code and clients, never Right's code"
    (is (= ["\"12 21\"" "\"14 21\""]
           (both (str diamond "class Doubling_Left
  inherit Counter
  create make() do Counter.make(10) end
feature
  bump() do Counter.bump() Counter.bump() end
  bump_left() do bump() end
  left_count(): Integer do result := count end
end
class Mixed
  inherit Doubling_Left, Right
  create make() do Doubling_Left.make() Right.make() end
end
let m := create Mixed.make()
m.bump_left
m.bump_right
print(m.left_count.to_string + \" \" + m.right_count.to_string)
m.bump
print(m.left_count.to_string + \" \" + m.right_count.to_string)")))))
  (testing "super in an override on one path"
    (is (= ["\"10 22\""]
           (both (str diamond "class Logging_Right
  inherit Counter
  create make() do Counter.make(20) end
feature
  bump() do super.bump() super.bump() end
  bump_logging() do bump() end
  logging_count(): Integer do result := count end
end
class Both2
  inherit Left, Logging_Right
  create make() do Left.make() Logging_Right.make() end
end
let b := create Both2.make()
b.bump_logging
print(b.left_count.to_string + \" \" + b.logging_count.to_string)"))))))

(deftest a-diamond-inside-a-further-heir
  (is (= ["\"11 23\""]
         (both (str diamond "class Top
  inherit Both
  create make() do Both.make() end
end
let t := create Top.make()
t.bump_left
t.bump_right
t.bump_right_twice
print(counts(t))")))))

(deftest construction-and-old-on-the-second-path
  (testing "a routine called by a constructor running on Right's path, and `old` in a
            postcondition there, use Right's copy"
    (is (= ["\"10 102\""]
           (both "class Counter
  create make(start: Integer) do count := start clamp() end
feature
  count: Integer
  clamp() do if count > 100 then count := 100 end end
  step()
    do
      count := count + 1
    ensure
      one_more: count = old count + 1
    end
end
class Left
  inherit Counter
  create make() do Counter.make(10) end
feature
  left_count(): Integer do result := count end
end
class Right
  inherit Counter
  create make() do Counter.make(500) end
feature
  step_right() do step() step() end
  right_count(): Integer do result := count end
end
class Both
  inherit Left, Right
  create make() do Left.make() Right.make() end
end
let b := create Both.make()
b.step_right
print(b.left_count.to_string + \" \" + b.right_count.to_string)")))))

(deftest the-invariant-holds-on-every-copy
  (is (= ["0" "Class invariant violation: non_negative"]
         (both (str diamond "let b := create Both.make()
from let i := 0 until i = 20 do b.drop_right i := i + 1 end
print(b.right_count)
do b.drop_right rescue print(exception) end")))))

(deftest equality-compares-every-copy
  (is (= ["true" "false" "true"]
         (both (str diamond "let a := create Both.make()
let b := create Both.make()
print(a = b)
a.bump_right
b.bump_left
print(a = b)
a.bump_left
b.bump_right
print(a = b)")))))

(deftest unrelated-parents-keep-same-named-fields-apart
  (is (= ["\"7 2 7\""]
         (both "class Has_X1
  create make() do x := 1 end
feature
  x: Integer
  x1(): Integer do result := x end
  set_x1(v: Integer) do x := v end
end
class Has_X2
  create make() do x := 2 end
feature
  x: Integer
  x2(): Integer do result := x end
end
class Both_X
  inherit Has_X1, Has_X2
  create make() do Has_X1.make() Has_X2.make() end
end
let b := create Both_X.make()
b.set_x1(7)
print(b.x1.to_string + \" \" + b.x2.to_string + \" \" + b.x.to_string)"))))

(deftest a-deferred-shared-ancestor-implemented-on-one-path
  (testing "a routine still deferred along the running path is the object's own"
    (is (= ["\"named area 4\""]
           (both "deferred class Shape
feature
  area(): Integer deferred
  describe(): String do result := \"area \" + area().to_string end
end
class Square
  inherit Shape
  create make() do end
feature
  area(): Integer do result := 4 end
end
deferred class Named
  inherit Shape
feature
  name(): String do result := \"named \" + describe() end
end
class Named_Square
  inherit Square, Named
  create make() do Square.make() end
end
print((create Named_Square.make()).name())")))))
