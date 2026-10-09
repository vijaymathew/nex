(ns nex.deferred-contracts-test
  "Contracts on deferred routines, and how an override's precondition combines
   with what it inherits.

   1. A deferred routine may carry a contract: `require` before `deferred`,
      `ensure` after it closed by `end`. The type checker checks it like any
      other, and every implementation inherits it on both backends.
   2. An empty `do end` body is an ordinary routine, in a deferred class as
      anywhere else. The JVM lowering used to treat it as deferred, so a heir
      that inherited it failed to link and fell back to the interpreter.
   3. A first declaration with no `require` admits every call, and an
      override's precondition is OR-ed with what it inherits, so a `require`
      added below such a declaration can never be checked: the type checker
      rejects it, and the runtimes treat the effective precondition as empty.
      An override with no `require` of its own keeps the inherited one.
   4. Inherited assertions are read under the override's own parameter names.
      Both backends used to evaluate them under the ancestor's names, which
      failed when the override renamed a parameter.
   5. A violation names the assertion that failed. An OR-ed precondition is
      broken only when every alternative is, so it names the failing assertion
      of each, `positive or is_neg`, rather than a synthetic label."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [nex.eval :as e]
            [nex.fmt :as fmt]
            [nex.parser :as p]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "deferred_contracts" ".nex")]
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

(defn- error-message [code]
  (try (run code {}) nil
       (catch Exception ex (.getMessage ex))))

(defn- run-unchecked
  "Printed output of CODE run on a backend without the type checker, to see
   what the runtime itself does with a program the checker would reject."
  [code opts]
  (str/split-lines
   (str/trim-newline
    (with-out-str (#'e/run-ast "deferred_contracts_unchecked" (p/ast code) opts)))))

(defn- members [code]
  (->> (p/ast code) :classes first :body (mapcat :members)))

(def ^:private try-call
  "Prints the call's result, or the contract it broke."
  "function try_scale(s: Shape, x: Integer)
do
  do
    print(s.scale(x))
  rescue
    print(exception.to_string)
  end
end
")

(def ^:private holds
  "Applies a predicate, so a contract can read a closure."
  "function holds(p: Function(Integer): Boolean, x: Integer): Boolean
do
  result := p(x)
end
")

;; ---------------------------------------------------------------------------
;; 1. Syntax

(deftest deferred-routine-contract-syntax
  (let [ms (members "deferred class Shape
feature
  a(x: Integer) require positive: x > 0 deferred
  b(x: Integer): Integer
    require
      positive: x > 0
    deferred
    ensure
      bigger: result > x
    end
  count: Integer
  c(): Integer deferred ensure nonneg: result >= 0 end
  d(x: Integer) deferred
  e(x: Integer)
end")
        by-name (into {} (map (juxt :name identity)) ms)]
    (testing "require only"
      (is (:declaration-only? (by-name "a")))
      (is (= ["positive"] (mapv :label (:require (by-name "a")))))
      (is (nil? (:ensure (by-name "a")))))
    (testing "require and ensure"
      (is (:declaration-only? (by-name "b")))
      (is (= ["positive"] (mapv :label (:require (by-name "b")))))
      (is (= ["bigger"] (mapv :label (:ensure (by-name "b"))))))
    (testing "the `end` closing an ensure leaves a following field a field"
      (is (= :field (:type (by-name "count")))))
    (testing "ensure only"
      (is (:declaration-only? (by-name "c")))
      (is (nil? (:require (by-name "c"))))
      (is (= ["nonneg"] (mapv :label (:ensure (by-name "c"))))))
    (testing "the contract-less forms are unchanged"
      (is (every? :declaration-only? [(by-name "d") (by-name "e")]))
      (is (every? (comp nil? :require) [(by-name "d") (by-name "e")])))))

(deftest deferred-ensure-needs-end
  (is (thrown? Exception
               (p/ast "deferred class Shape
feature
  c(): Integer deferred ensure nonneg: result >= 0
  count: Integer
end"))))

(deftest deferred-routine-may-not-have-a-body
  (is (thrown? Exception
               (p/ast "deferred class Shape
feature
  c(): Integer require ok: true deferred do result := 1 end
end"))))

;; ---------------------------------------------------------------------------
;; 1. Type checking a deferred routine's contract

(deftest deferred-contract-is-type-checked
  (testing "a precondition must be Boolean"
    (is (re-find #"Precondition must be Boolean"
                 (str (error-message "deferred class Shape
feature
  scale(x: Integer): Integer require bad: x + 1 deferred
end")))))
  (testing "a postcondition must be Boolean"
    (is (re-find #"Postcondition must be Boolean"
                 (str (error-message "deferred class Shape
feature
  scale(x: Integer): Integer deferred ensure bad: result + x end
end")))))
  (testing "an unknown name is an error"
    (is (re-find #"Undefined variable: y"
                 (str (error-message "deferred class Shape
feature
  scale(x: Integer): Integer require ok: y > 0 deferred
end")))))
  (testing "`result` needs a return type"
    (is (re-find #"uses `result` but declares no return type"
                 (str (error-message "deferred class Shape
feature
  touch(x: Integer) deferred ensure ok: result > 0 end
end")))))
  (testing "a deferred routine with an attached non-scalar result needs no body"
    (is (= ["\"ok\""]
           (both "deferred class Named
feature
  label(n: Integer): String require positive: n > 0 deferred ensure nonempty: result.length > 0 end
end
class Tag
inherit Named
create
  make do end
feature
  label(n: Integer): String do result := \"t\" + n.to_string end
end
let t: Named := create Tag.make
if t.label(2) = \"t2\" then print(\"ok\") end")))))

;; ---------------------------------------------------------------------------
;; 1. Implementations inherit a deferred routine's contract

(def ^:private shape
  "deferred class Shape
feature
  scale(x: Integer): Integer
    require
      positive: x > 0
    deferred
    ensure
      bigger: result > x
    end
end
")

(deftest implementation-inherits-deferred-contract
  (is (= ["6" "\"Precondition violation: positive\"" "\"Postcondition violation: bigger\""
          "\"Precondition violation: positive\""]
         (both (str shape try-call "
class Good
inherit Shape
create
  make do end
feature
  scale(x: Integer): Integer do result := x * 2 end
end
class Bad
inherit Shape
create
  make do end
feature
  scale(x: Integer): Integer do result := x end
end
try_scale(create Good.make, 3)
try_scale(create Good.make, -1)
try_scale(create Bad.make, 3)
let g: Good := create Good.make
do
  print(g.scale(0))
rescue
  print(exception.to_string)
end")))))

(deftest deferred-contract-through-an-intermediate-deferred-class
  (is (= ["8" "\"Precondition violation: positive\"" "\"Postcondition violation: bigger\""]
         (both (str shape try-call "
deferred class Polygon
inherit Shape
end
class Square
inherit Polygon
create
  make do end
feature
  scale(x: Integer): Integer do
    if x = 5 then result := 5 else result := x * 2 end
  end
end
try_scale(create Square.make, 4)
try_scale(create Square.make, -4)
try_scale(create Square.make, 5)")))))

(deftest override-may-widen-a-deferred-precondition
  (is (= ["-40" "\"Precondition violation: positive or very_negative\"" "6"]
         (both (str "deferred class Shape
feature
  scale(x: Integer): Integer require positive: x > 0 deferred
end
" try-call "
class Wide
inherit Shape
create
  make do end
feature
  scale(x: Integer): Integer
    require
      very_negative: x < -10
    do
      result := x * 2
    end
end
try_scale(create Wide.make, -20)
try_scale(create Wide.make, -5)
try_scale(create Wide.make, 3)")))))

(deftest diamond-implementation-inherits-the-shared-contract
  (is (= ["2" "\"Precondition violation: positive\""]
         (both (str shape try-call "
deferred class Left
inherit Shape
end
deferred class Right
inherit Shape
end
class Both
inherit Left, Right
create
  make do end
feature
  scale(x: Integer): Integer do result := x + 1 end
end
try_scale(create Both.make, 1)
try_scale(create Both.make, 0)")))))

;; ---------------------------------------------------------------------------
;; 2. An empty body is a routine, not a deferred one

(deftest empty-body-in-a-deferred-class-is-an-ordinary-routine
  (let [code "deferred class Hooks
feature
  on_start(n: Integer) require positive: n > 0 do end
  run(n: Integer) do on_start(n) print(\"ran \" + n.to_string) end
end
class Quiet
inherit Hooks
create
  make do end
end
let q: Hooks := create Quiet.make
q.on_start(1)
q.run(2)
do
  q.on_start(0)
rescue
  print(exception.to_string)
end"
        err (java.io.StringWriter.)]
    (binding [*err* err]
      (is (= ["\"ran 2\"" "\"Precondition violation: positive\""] (both code))))
    (is (not (str/includes? (str err) "falling back"))
        "the compiled program must link, not fall back to the interpreter")))

(deftest empty-body-is-not-required-to-be-overridden
  (is (= ["\"done\""]
         (both "deferred class Hooks
feature
  on_start do end
end
class Quiet
inherit Hooks
create
  make do end
end
let q := create Quiet.make
q.on_start
print(\"done\")"))))

;; ---------------------------------------------------------------------------
;; 3. A precondition added below a first declaration that has none

(deftest precondition-below-an-unconstrained-concrete-routine-is-rejected
  (let [msg (error-message "class Parent
create
  make do end
feature
  f(x: Integer) do print(x) end
end
class Child
inherit Parent
create
  make do end
feature
  f(x: Integer) require positive: x > 0 do print(x) end
end")]
    (is (re-find #"Precondition on 'f' in class 'Child' can never be checked" (str msg)))
    (is (re-find #"first declared in 'Parent' with no precondition" (str msg)))))

(deftest precondition-below-an-unconstrained-deferred-routine-is-rejected
  (is (re-find #"Precondition on 'scale' in class 'Square' can never be checked: 'scale' is first declared in 'Shape'"
               (str (error-message "deferred class Shape
feature
  scale(x: Integer): Integer deferred
end
class Square
inherit Shape
create
  make do end
feature
  scale(x: Integer): Integer require positive: x > 0 do result := x end
end")))))

(deftest rejection-looks-past-intermediate-overrides
  (is (re-find #"first declared in 'A' with no precondition"
               (str (error-message "class A
create
  make do end
feature
  f(x: Integer) do end
end
class B
inherit A
create
  make do end
feature
  f(x: Integer) do print(x) end
end
class C
inherit B
create
  make do end
feature
  f(x: Integer) require positive: x > 0 do end
end")))))

(deftest rejection-when-any-inherited-path-is-unconstrained
  (is (re-find #"first declared in 'Free' with no precondition"
               (str (error-message "deferred class Strict
feature
  f(x: Integer) require positive: x > 0 deferred
end
deferred class Free
feature
  f(x: Integer) deferred
end
class Impl
inherit Strict, Free
create
  make do end
feature
  f(x: Integer) require small: x < 10 do end
end")))))

(deftest precondition-is-allowed-where-every-path-is-constrained
  (testing "a precondition stated on the first declaration may be widened below it"
    (is (= ["\"ok\""]
           (both "class A
create
  make do end
feature
  f(x: Integer) require positive: x > 0 do end
end
class B
inherit A
create
  make do end
feature
  f(x: Integer) do end
end
class C
inherit B
create
  make do end
feature
  f(x: Integer) require negative: x < 0 do end
end
print(\"ok\")"))))
  (testing "a diamond over a constrained first declaration"
    (is (= ["\"ok\""]
           (both (str shape "
deferred class Left
inherit Shape
end
deferred class Right
inherit Shape
end
class Both
inherit Left, Right
create
  make do end
feature
  scale(x: Integer): Integer require zero: x = 0 do result := x + 1 end
end
print(\"ok\")")))))
  (testing "a routine that overrides nothing keeps its own precondition"
    (is (= ["\"Precondition violation: positive\""]
           (both "class Lone
create
  make do end
feature
  f(x: Integer) require positive: x > 0 do end
end
let lone := create Lone.make
do
  lone.f(0)
rescue
  print(exception.to_string)
end")))))

(deftest override-without-require-keeps-the-inherited-precondition
  (is (= ["\"Precondition violation: positive\"" "\"Precondition violation: positive\""]
         (both "class A
create
  make do end
feature
  f(x: Integer) require positive: x > 0 do end
end
class B
inherit A
create
  make do end
feature
  f(x: Integer) do end
end
class C
inherit B
create
  make do end
feature
  f(x: Integer) do end
end
let b: A := create B.make
let c: A := create C.make
do b.f(0) rescue print(exception.to_string) end
do c.f(0) rescue print(exception.to_string) end"))))

(deftest runtimes-treat-an-unconstrained-first-declaration-as-admitting-every-call
  ;; The type checker rejects this program; the runtimes, run without it,
  ;; must still not enforce the dead precondition.
  (let [code "class Parent
create
  make do end
feature
  f(x: Integer) do print(\"parent \" + x.to_string) end
end
class Child
inherit Parent
create
  make do end
feature
  f(x: Integer) require positive: x > 0 do print(\"child \" + x.to_string) end
end
let p: Parent := create Child.make
p.f(-1)
let c: Child := create Child.make
c.f(-2)"]
    (is (= ["\"child -1\"" "\"child -2\""] (run-unchecked code {})))
    (is (= ["\"child -1\"" "\"child -2\""] (run-unchecked code {:interpret? true})))))

;; ---------------------------------------------------------------------------
;; 4. Inherited assertions under the override's parameter names

(deftest inherited-contract-reads-renamed-parameters
  (testing "from a deferred routine"
    (is (= ["6" "\"Precondition violation: positive\"" "\"Postcondition violation: bigger\""]
           (both (str shape try-call "
class Renamed
inherit Shape
create
  make do end
feature
  scale(n: Integer): Integer do
    if n = 7 then result := 7 else result := n * 2 end
  end
end
try_scale(create Renamed.make, 3)
try_scale(create Renamed.make, -3)
try_scale(create Renamed.make, 7)")))))
  (testing "from a concrete routine"
    (is (= ["4" "\"Precondition violation: positive\""]
           (both "class Parent
create
  make do end
feature
  f(x: Integer): Integer require positive: x > 0 do result := x ensure big: result > x end
end
class Child
inherit Parent
create
  make do end
feature
  f(y: Integer): Integer do result := y + 1 end
end
let p: Parent := create Child.make
print(p.f(3))
do print(p.f(-1)) rescue print(exception.to_string) end"))))
  (testing "swapped names are renamed at once, not one after the other"
    (is (= ["\"ok\"" "\"Precondition violation: ordered\""]
           (both "deferred class Range
feature
  span(lo, hi: Integer): Integer require ordered: lo < hi deferred
end
class Swapped
inherit Range
create
  make do end
feature
  span(hi, lo: Integer): Integer do result := lo - hi end
end
let r: Range := create Swapped.make
if r.span(1, 5) = 4 then print(\"ok\") end
do print(r.span(5, 1)) rescue print(exception.to_string) end"))))
  (testing "a receiver and a closure parameter of the same name"
    (is (= ["3" "\"Precondition violation: short\"" "\"Precondition violation: big\""]
           (both (str holds "deferred class Measure
feature
  size(s: String, n: Integer): Integer
    require
      short: s.length < 5
      big: holds(fn (s: Integer): Boolean do result := s > 10 end, n)
    deferred
end
class Len
inherit Measure
create
  make do end
feature
  size(t: String, m: Integer): Integer do result := t.length end
end
let m: Measure := create Len.make
print(m.size(\"abc\", 20))
do print(m.size(\"abcdef\", 20)) rescue print(exception.to_string) end
do print(m.size(\"abc\", 1)) rescue print(exception.to_string) end"))))))

(deftest renamed-parameter-may-not-capture-a-name-the-contract-reads
  (testing "a field"
    (is (re-find #"Parameter 'limit' of 'f' in class 'Child' takes a name the contract inherited from 'Parent.f' already uses"
                 (str (error-message "class Parent
create
  make do limit := 10 end
feature
  limit: Integer
  f(x: Integer) require under: x < limit do end
end
class Child
inherit Parent
create
  make do end
feature
  f(limit: Integer) do end
end")))))
  (testing "a postcondition counts too"
    (is (re-find #"Parameter 'total'"
                 (str (error-message "deferred class Counter
feature
  total: Integer
  add(n: Integer) deferred ensure grew: total = old total + n end
end
class Impl
inherit Counter
create
  make do end
feature
  add(total: Integer) do end
end")))))
  (testing "a name read only inside a closure that rebinds it is not captured"
    (is (= ["\"ok\""]
           (both (str holds "deferred class Measure
feature
  size(n: Integer): Integer
    require
      big: holds(fn (s: Integer): Boolean do result := s > 10 end, n)
    deferred
end
class Len
inherit Measure
create
  make do end
feature
  size(s: Integer): Integer do result := s end
end
let m: Measure := create Len.make
if m.size(20) = 20 then print(\"ok\") end"))))))

;; ---------------------------------------------------------------------------
;; The formatter keeps a deferred routine deferred

(deftest formatter-round-trips-deferred-routines
  (let [src "deferred class Shape
feature
  a(x: Integer) require positive: x > 0 deferred
  b(x: Integer): Integer require positive: x > 0 deferred ensure bigger: result > x end
  count: Integer
  c(): Integer deferred ensure nonneg: result >= 0 end
  d(x: Integer) deferred
end"
        formatted (fmt/format-code src)
        strip (fn [ms] (mapv #(select-keys % [:name :type :declaration-only? :require :ensure]) ms))
        no-pos (fn [x] (walk/postwalk #(if (map? %) (dissoc % :dbg/line :dbg/col) %) x))]
    (is (str/starts-with? formatted "deferred class Shape"))
    (is (str/includes? formatted "d(x: Integer) deferred"))
    (is (= (no-pos (strip (members src))) (no-pos (strip (members formatted)))))
    (is (= formatted (fmt/format-code formatted)) "formatting is idempotent")))

(deftest violations-name-the-failing-assertions
  (testing "an OR-ed precondition names each alternative's first failing assertion,
            ancestor first, read under the override's parameter names; inherited
            postconditions and invariants name their own assertion"
    (is (= ["Precondition violation: positive or is_neg"
            "Precondition violation: small or is_neg"
            "Precondition violation: positive or not_tiny"
            "Postcondition violation: p_nonneg"
            "Postcondition violation: p_ge_x"
            "Precondition violation: positive or not_tiny or is_zero"
            "Precondition violation: small or is_neg or is_zero"
            "Postcondition violation: c_small"
            "Class invariant violation: base_nonneg"
            "Class invariant violation: derived_small"
            "Class invariant violation: m_pos"]
           (both "class P
feature
  f(x: Integer): Integer
  require
    positive: x > 0
    small: x < 100
  do
    result := x
  ensure
    p_nonneg: result >= 0
  end
  g(x: Integer): Integer
  do
    result := x
  ensure
    p_ge_x: result >= x
  end
end

class C
inherit P
feature
  f(y: Integer): Integer
  require
    is_neg: y < 0
    not_tiny: y > -50
  do
    result := y
  ensure
    c_small: result < 1000
  end
  g(y: Integer): Integer
  do
    result := y - 1
  ensure
    c_ok: result /= 99
  end
end

class D
inherit C
feature
  f(z: Integer): Integer
  require
    is_zero: z = 0
  do
    result := z + 5000
  end
end

let p: P := create C
-- group1: positive fails; group2: is_neg fails
do p.f(0) rescue print(exception) end
-- group1: small fails; group2: is_neg fails
do p.f(200) rescue print(exception) end
-- group1: positive fails; group2: not_tiny fails
do p.f(-60) rescue print(exception) end
-- passes via C, then inherited ensure p_nonneg fails
do p.f(-5) rescue print(exception) end
-- inherited postcondition p_ge_x fails
do p.g(3) rescue print(exception) end

let q: P := create D
do q.f(-60) rescue print(exception) end
do q.f(150) rescue print(exception) end
-- passes via D's is_zero; then C's c_small ensure fails (z + 5000)
do q.f(0) rescue print(exception) end

class Base
feature
  n: Integer
  set_n(v: Integer) do n := v end
invariant
  base_nonneg: n >= 0
end

class Derived
inherit Base
feature
  m: Integer
  set_m(v: Integer) do m := v end
invariant
  derived_small: n < 10
  m_pos: m >= 0
end

let d := create Derived
do d.set_n(-1) rescue print(exception) end
let d2 := create Derived
do d2.set_n(20) rescue print(exception) end
let d3 := create Derived
do d3.set_m(-1) rescue print(exception) end
")))))
