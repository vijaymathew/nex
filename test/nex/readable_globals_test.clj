(ns nex.readable-globals-test
  "Top-level `let` globals are readable (not assignable) from the static world —
   the body of any free function or class routine (§7.4 of the Definition). The
   read is lexical: a body resolves a free name to the global, never to a caller's
   local. A def-before-use watermark rejects programs that could read a global
   before its `let` has run. Covers both backends plus the two static rejections.

   Also covers the analogous case for top-level `function`s (also readable
   everywhere, §7): a class's own method must take priority, by name+arity,
   over a same-named global function reachable through that same readable-
   globals mechanism, with a same-named-but-different-arity global still
   reachable as a fallback when no own method matches."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.interpreter :as interp]
            [nex.compiler.jvm.file :as file]
            [nex.compiler.jvm.classloader :as loader]))

(defn- run-interpreted
  [code]
  (let [ctx (interp/make-context)]
    (interp/eval-node ctx (p/ast code))
    (->> @(:output ctx) (map str) (remove str/blank?) vec)))

(defn- run-compiled
  [code]
  (let [{:keys [main-class classes]} (file/compile-ast "globals_test.nex" (p/ast code) {})
        ldr (loader/make-loader)]
    (doseq [[binary-name ^bytes bytecode] classes]
      (loader/define-class! ldr binary-name bytecode))
    (let [cls (loader/resolve-class ldr main-class)
          m (.getMethod cls "main" (into-array Class [(Class/forName "[Ljava.lang.String;")]))
          out (with-out-str
                (.invoke m nil (object-array [(into-array String [])])))]
      (->> (str/split-lines out) (remove str/blank?) vec))))

(defn- type-check
  [code]
  (tc/type-check (p/ast code) {:strict-undefined-targets? true}))

;; --- positive: readable on both backends -------------------------------------

(def free-function-reads-global
  "let base := 100
function bump(x: Integer): Integer
do
  result := x + base
end
print(bump(5))")

(deftest free-function-reads-a-global
  (testing "a free function reads a top-level global, same on both backends"
    (is (= ["105"] (run-compiled free-function-reads-global)))
    (is (= ["105"] (run-interpreted free-function-reads-global)))))

(def class-method-reads-global
  "let label := \"sum=\"
class Reporter
feature
  report(n: Integer)
  do
    print(label + n.to_string)
  end
end
let r := create Reporter
r.report(42)")

(deftest class-method-reads-a-global
  (testing "a class routine reads a top-level global, same on both backends"
    (is (= ["\"sum=42\""] (run-compiled class-method-reads-global)))
    (is (= ["\"sum=42\""] (run-interpreted class-method-reads-global)))))

;; --- lexical, not dynamic ----------------------------------------------------

(def lexical-not-dynamic
  "let g := 100
function inner(): Integer
do
  result := g
end
function outer(): Integer
do
  let g := 999
  result := inner()
end
print(outer())")

(deftest global-read-is-lexical-on-compiled-backend
  (testing "compiled: inner() reads the global g (100), not outer()'s local g"
    ;; The compiled backend reads globals by name from the single threaded session
    ;; state, so a like-named caller local never intercepts the read.
    (is (= ["100"] (run-compiled lexical-not-dynamic)))))

(deftest global-read-shadowing-is-dynamic-on-interpreter
  (testing "interpreter: a like-named caller local shadows the global (known gap)"
    ;; KNOWN DIVERGENCE: the tree-walking interpreter roots each call frame at the
    ;; dynamic caller (needed for its reference write-back machinery), so when a
    ;; caller local shares a global's name the callee sees the local (999), not the
    ;; global (100). The compiled backend — the one `nex file.nex` uses — is correct.
    ;; This only bites when a caller local happens to share a global's name.
    (is (= ["999"] (run-interpreted lexical-not-dynamic)))))

(def param-shadows-global
  "let g := 5
function f(g: Integer): Integer
do
  result := g + 1
end
print(f(40))")

(deftest a-parameter-shadows-the-global
  (testing "a parameter of the same name shadows the global throughout the body"
    (is (= ["41"] (run-compiled param-shadows-global)))
    (is (= ["41"] (run-interpreted param-shadows-global)))))

(def global-callable-called-by-bare-name
  "declare type Adder = Function(Integer): Integer

let add_one: Adder := fn(x: Integer): Integer do result := x + 1 end

function apply_it(): Integer
do
  result := add_one(41)
end

print(apply_it())")

(deftest free-function-calls-a-global-callable-by-bare-name
  (testing "a free function calls a top-level global holding a Function
            value, by bare name — `add_one(41)`, not `add_one.call1(41)`.
            Regression test: check-bare-name-call (typechecker) and
            function-object-binding-type (lowering) both only ever
            consulted lexical :vars, never the readable-globals table, so
            a bare call to a global callable failed type-checking with
            \"Undefined function or method\" (typechecker fix) and, once
            that was patched, still crashed the compiled backend with
            \"Used a value that is void (nil)\" (the JVM lowering silently
            resolving the callee to a null REPL-function-name lookup)."
    (is (= ["42"] (run-compiled global-callable-called-by-bare-name)))
    (is (= ["42"] (run-interpreted global-callable-called-by-bare-name)))))

;; --- a class's own method vs. a same-named global function -------------------

(def own-method-shadows-mismatched-arity-global
  "function greet(): String
do
  result := \"global\"
end

class Greeter
create
  make do end
feature
  hello(a, b: String): String
  do
    result := greet(a, b)
  end

  greet(a, b: String): String
  do
    result := \"own:\" + a + b
  end
end

let g := create Greeter.make
print(g.hello(\"x\", \"y\"))")

(deftest class-own-method-shadows-a-same-named-global-of-different-arity
  (testing "a bare self-call inside a class method resolves to the class's
            OWN method of that name+arity, not a same-named top-level
            `function` of a different arity, even though the global is
            readable from inside the class body (§7). Regression test: both
            backends resolved any env-bound global before ever checking
            whether the enclosing class had its own matching method —
            compiled: check-call tried env-lookup-var (which finds the
            0-arity global's synthesized Function class) before
            check-bare-name-call's self-method lookup, so
            check-function-object-call rejected the call with \"Function
            `greet` takes 0 arguments, 2 given\"; interpreter:
            eval-call-without-target's env-lookup found the same global
            Function object first and dispatched `call2` on it, which
            doesn't exist on a 0-arity Function (\"Method not found:
            call2\")."
    (is (= ["\"own:xy\""] (run-compiled own-method-shadows-mismatched-arity-global)))
    (is (= ["\"own:xy\""] (run-interpreted own-method-shadows-mismatched-arity-global)))))

(def falls-back-to-global-when-no-own-method-matches-arity
  "function greet(x: Integer): Integer
do
  result := x + 100
end

class Calc
create
  make do end
feature
  add_hundred(x: Integer): Integer
  do
    result := greet(x)
  end

  greet(a, b: Integer): Integer
  do
    result := a + b
  end
end

let c := create Calc.make
print(c.add_hundred(5))")

(deftest class-falls-back-to-global-function-when-no-own-method-matches-call-arity
  (testing "the class-own-method-shadows-global priority above is arity-scoped,
            not name-scoped: a bare call whose arg count matches the global
            (1) but not the class's own same-named method (2) still falls
            back to the global function, exactly as before this fix."
    (is (= ["105"] (run-compiled falls-back-to-global-when-no-own-method-matches-arity)))
    (is (= ["105"] (run-interpreted falls-back-to-global-when-no-own-method-matches-arity)))))

;; --- watermark self-reference -------------------------------------------------

(def global-whose-own-initializer-is-the-watermark
  "class Cell
feature x: Integer
create make(v: Integer) do x := v end
end

let root := create Cell.make(0)

function describe(): Integer
do
  result := root.x
end

print(describe())")

(deftest global-whose-own-let-is-the-watermark-is-not-self-rejected
  (testing "a global's own defining `let` can itself be the statement that
            first enters user code (`create Cell.make(...)`), making its
            position equal to the watermark — that must not be flagged as
            reading itself before initialization. Regression test: the
            watermark check used `>=` instead of `>` when comparing a
            global's def-position against the watermark, so `global-pos ==
            watermark` (only possible for the global's own statement, since
            positions are unique) was wrongly rejected."
    (let [{:keys [success errors]} (type-check global-whose-own-initializer-is-the-watermark)]
      (is (true? success) (pr-str errors)))
    (is (= ["0"] (run-compiled global-whose-own-initializer-is-the-watermark)))
    (is (= ["0"] (run-interpreted global-whose-own-initializer-is-the-watermark)))))

;; --- watermark is per-statement reachability, not a single whole-program cutoff

(def sequential-construction-then-later-read
  "class Box
create
  make(v: Integer) do this.v := v end
feature v: Integer
end

let a := create Box.make(1)
let b := create Box.make(2)

function sum(): Integer
do
  result := a.v + b.v
end

print(sum())")

(deftest later-global-built-via-create-is-not-rejected-by-an-earlier-unrelated-create
  (testing "two top-level globals each built by `create SomeClass.make(...)`,
            where the later one (b) is read inside a function body. Regression
            test: the old watermark was a single whole-program cutoff — the
            position of the FIRST top-level statement that entered user code
            at all, `create Box.make(1)` here — so any later-defined global
            used anywhere in the static world was rejected outright, even
            though Box's constructor body can't possibly read it. The
            reachability-based check instead asks whether the specific thing
            invoked (Box's constructor, which reads nothing) can transitively
            reach a read of `b` — it can't, so this is legal, matching
            delivery_main.nex's `let console := ...; let map := create
            Terrain_Map.make(...); let robot := create Robot.with_map(map)`
            shape from the bug report this fix addresses."
    (let [{:keys [success errors]} (type-check sequential-construction-then-later-read)]
      (is (true? success) (pr-str errors)))
    (is (= ["3"] (run-compiled sequential-construction-then-later-read)))
    (is (= ["3"] (run-interpreted sequential-construction-then-later-read)))))

;; --- static rejections -------------------------------------------------------

(def global-after-entry-point
  "function f()
do
  print(g)
end
f()
let g := 5")

(deftest watermark-rejects-global-defined-after-entry-point
  (testing "a global read by a body but bound after the first user call is rejected"
    (let [{:keys [success errors]} (type-check global-after-entry-point)]
      (is (false? success))
      (is (some #(str/includes? (tc/format-type-error %) "not initialized before this call")
                errors)))))

(def global-read-via-transitive-free-function-chain
  "function inner(): Integer
do
  result := g
end
function outer(): Integer
do
  result := inner()
end
outer()
let g := 5")

(deftest watermark-rejects-a-global-read-two-calls-deep
  (testing "the reachability closure is transitive: outer() itself never
            reads g, but it calls inner(), which does — the watermark check
            must still catch this, not just a body's own direct reads."
    (let [{:keys [success errors]} (type-check global-read-via-transitive-free-function-chain)]
      (is (false? success))
      (is (some #(str/includes? (tc/format-type-error %) "Global 'g'") errors)))))

(def global-read-via-a-method-call
  "class Reader
create
  make do end
feature
  read_it(): Integer
  do
    result := g
  end
end

let r := create Reader.make
print(r.read_it())
let g := 5")

(deftest watermark-rejects-a-global-read-through-a-method-call
  (testing "the call-graph reachability closure also follows `.method(...)`
            calls (resolved conservatively by name+arity, since the
            receiver's static type isn't tracked at this pre-typecheck
            pass), not just bare free-function calls — r.read_it() runs
            before g's own `let`, and read_it's body reads g."
    (let [{:keys [success errors]} (type-check global-read-via-a-method-call)]
      (is (false? success))
      (is (some #(str/includes? (tc/format-type-error %) "Global 'g'") errors)))))

(def assign-to-global
  "let g := 5
function f()
do
  g := 10
end
f()")

(deftest assigning-to-a-global-from-a-body-is-rejected
  (testing "globals are read-only in the static world"
    (let [{:keys [success errors]} (type-check assign-to-global)]
      (is (false? success))
      (is (some #(str/includes? (tc/format-type-error %) "read-only")
                errors)))))
