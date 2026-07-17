(ns nex.refinement-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.interpreter :as interp]))

;; Refinement types (`declare type X = Base where n: <pred>`) register as an
;; alias to Base for type checking and inject a predicate check at every
;; narrowing site (let / parameter / return). These tests drive the injected
;; checks through the tree-walking interpreter.

(defn- run
  "Evaluate a whole program, returning its printed output."
  [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)]
    (interp/eval-node ctx ast)
    @(:output ctx)))

(defn- violates?
  "True if running the program raises a refinement violation."
  [code]
  (try
    (run code)
    false
    (catch clojure.lang.ExceptionInfo e
      (boolean (re-find #"Refinement" (str (ex-message e)))))))

(deftest refinement-valid-let-passes
  (testing "a value satisfying the predicate binds without error"
    (is (= ["6"]
           (run "declare type Quantity = Integer where n: n > 0
let q: Quantity := 5
print(q + 1)")))))

(deftest refinement-bad-let-raises
  (testing "a let that violates the predicate raises"
    (is (violates? "declare type Quantity = Integer where n: n > 0
let q: Quantity := -3
print(q)"))))

(deftest refinement-widening-is-free
  (testing "using a refinement where its base is wanted needs no check and no wrapper"
    (is (= ["13"]
           (run "declare type Quantity = Integer where n: n > 0
let q: Quantity := 3
let total: Integer := q + 10
print(total)")))))

(deftest refinement-parameter-checked-at-boundary
  (testing "passing a base value that violates the predicate raises at entry"
    (is (violates? "declare type Quantity = Integer where n: n > 0
function use(x: Quantity): Integer do result := x end
let bad: Integer := 0
print(use(bad))"))
    (is (= ["7"]
           (run "declare type Quantity = Integer where n: n > 0
function use(x: Quantity): Integer do result := x end
let ok: Integer := 7
print(use(ok))")))))

(deftest refinement-return-checked
  (testing "returning a value that violates the predicate raises"
    (is (violates? "declare type Quantity = Integer where n: n > 0
function make_bad(): Quantity do result := -1 end
print(make_bad())"))))

(deftest refinement-nested-let-checked
  (testing "a refinement let nested inside a body is still checked"
    (is (violates? "declare type Quantity = Integer where n: n > 0
function f(x: Integer): Integer do
  result := 0
  if x < 100 then
    let q: Quantity := x
    result := q
  end
end
print(f(-5))"))))

(deftest refinement-compound-predicate
  (testing "predicates may use connectives and the base type's methods"
    (is (= ["42.5"]
           (run "declare type Percentage = Real where p: p >= 0.0 and p <= 100.0
let pct: Percentage := 42.5
print(pct)")))
    (is (violates? "declare type Percentage = Real where p: p >= 0.0 and p <= 100.0
let pct: Percentage := 150.0
print(pct)"))
    (is (violates? "declare type NonEmpty = String where s: s.length() > 0
let e: NonEmpty := \"\"
print(e)"))))

(deftest refinement-plain-alias-still-works
  (testing "declare type without a where is unchanged (structural alias, no checks)"
    (is (= ["7"]
           (run "declare type Count = Integer
let c: Count := -7
print(c * -1)")))))

(deftest where-is-a-soft-keyword
  (testing "`where` stays usable as a member name alongside a refinement decl"
    (is (= ["3"]
           (run "declare type Quantity = Integer where n: n > 0
let s: Set[Integer] := #{1, 2}
let t: Set[Integer] := #{2, 3}
print(s.union(t).size())")))))

;; ─── Compiled backend: calling methods on a refinement-typed receiver ────────
;;
;; A refinement is an alias to its base plus checks at the narrowing sites; by
;; lowering time it is erased and the value simply *is* a String/Integer. The
;; compiled backend used to pick its dispatch strategy from the unresolved
;; declared type, and the refinement's name matches no class and no builtin —
;; so every method call on such a receiver died with "Unsupported target call
;; expression for lowering" and the program ran only under --interpret. Reading
;; the field without a call (`b.t`) always worked, which is what made this look
;; like a `to_string` problem rather than a dispatch one.
;;
;; Driven through `nex.eval/eval-file` — the same path `nex <file>` takes.

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "refinement" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned."
  [code]
  (let [compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(deftest compiled-refinement-local-receiver
  (testing "base-type methods dispatch on a refinement-typed local"
    (is (= ["5" "\"TRK-1\"" "true"]
           (both "declare type Tid = String where s: s.length > 0
let t: Tid := \"TRK-1\"
print(t.length)
print(t.to_upper)
print(t.starts_with(\"TRK\"))")))))

(deftest compiled-refinement-field-receiver
  (testing "base-type methods dispatch on a refinement-typed field"
    ;; `to_string` on a String yields its *quoted* form, which print then quotes
    ;; again — the interpreter does the same, which is the point of the test.
    (is (= ["5" "\"\"TRK-1\"\""]
           (both "declare type Tid = String where s: s.length > 0
class Shipment
  feature tracking: Tid
  create make(t: Tid) do tracking := t end
end
let s := create Shipment.make(\"TRK-1\")
print(s.tracking.length)
print(s.tracking.to_string)")))))

(deftest compiled-refinement-parameter-receiver
  (testing "base-type methods dispatch on a refinement-typed parameter"
    (is (= ["3"]
           (both "declare type NonEmpty = String where s: s.length > 0
function size_of(v: NonEmpty): Integer
do
  result := v.length
end
print(size_of(\"abc\"))")))))

(deftest compiled-refinement-numeric-receiver
  (testing "a refinement over a non-String base dispatches too"
    (is (= ["\"7\""]
           (both "declare type Quantity = Integer where n: n > 0
let q: Quantity := 7
print(q.to_string)")))))

(deftest compiled-refinement-return-type-is-the-base-type
  (testing "the call's result type resolves through the alias, so it binds to the base type"
    ;; A wrong (or "Any") inferred return type here fails to verify rather than
    ;; producing a wrong answer, so this pins the inference, not just dispatch.
    (is (= ["6"]
           (both "declare type Tid = String where s: s.length > 0
let t: Tid := \"TRK-1\"
let n: Integer := t.length
print(n + 1)")))))
