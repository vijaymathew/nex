(ns nex.routine-result-test
  "A routine returns whatever `result` holds when it exits. `result` starts at
   its return type's zero value; only an explicit `result := ...` changes it.
   A trailing expression is NOT returned (there once was an implicit
   tail-expression rule on the compiled backend only — the interpreter never
   had it), and since its value would be silently thrown away, the typechecker
   rejects a routine body that ends in one. A return type with no zero value
   (String, an attached user class) must definitely assign `result`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.repl :as repl]
            [nex.typechecker :as tc]))

(defn- run-lines [code]
  (let [f (java.io.File/createTempFile "routine_result" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline
                        (with-out-str (e/eval-file (.getPath f) {:interpret? false}))))
      (finally (.delete f)))))

(defn- type-errors [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (mapv tc/format-type-error (:errors result)))))

(defn- discard-error [code]
  (some #(when (str/includes? % "Discarded value") %) (type-errors code)))

(deftest trailing-value-expression-is-rejected-test
  (testing "a routine body ending in a value-only expression is a type error
            naming the expression and the `result :=` it probably meant"
    (doseq [[src shown] [["function f(n: Integer): Integer do n * 2 end" "n * 2"]
                         ["function f(k: Integer): Integer do k end" "k"]
                         ["function f(): Integer do 11 end" "11"]
                         ["function f(n: Integer): Integer do -n end" "-n"]
                         ["function f(): Boolean do not true end" "not true"]
                         ["function f(): Array[Integer] do [1, 2] end" "[1, 2]"]
                         ["function f(n: Integer): Integer do
  let y := n + 1
  y
end" "y"]
                         ["function g(): Integer do result := 1 end
function f(): Integer do g end" "g"]]]
      (let [err (discard-error src)]
        (is (some? err) src)
        (when (and err shown)
          (is (str/includes? err (str "`" shown "`")) err)
          (is (str/includes? err (str "did you mean `result := " shown "`?")) err))))))

(deftest every-kind-of-routine-body-is-checked-test
  (testing "methods, fields' getters, anonymous functions, constructors and
            spawn bodies"
    (doseq [src ["class P
  feature
    x: Integer
    get_x: Integer do x end
end"
                 "let f := fn (k: Integer): Integer do k + 1 end"
                 "class P
  create make(v: Integer) do v end
end"
                 "let t := spawn do 1 + 2 end"]]
      (is (some? (discard-error src)) src)))
  (testing "a routine with no return type is told to remove it instead"
    (let [err (discard-error "function f(n: Integer) do n * 2 end")]
      (is (str/includes? err "Remove it, or use its value.") err)
      (is (not (str/includes? err "result :=")) err))))

(deftest tail-of-each-branch-is-checked-test
  (testing "the last statement of each branch of a trailing if/case/match/do"
    (doseq [src ["function f(n: Integer): Integer do
  if n > 0 then result := 1 elseif n < 0 then n else result := 0 end
end"
                 "function f(n: Integer): Integer do
  case n of 1 then result := 1 else n end
end"
                 "union R
  A(v: Integer)
  B
end
function f(r: R): Integer do
  match r of
    A as a then a.v + 1
    B then result := 0
  end
end"
                 "function f(n: Integer): Integer do
  do
    n + 1
  end
end"]]
      (is (some? (discard-error src)) src))))

(deftest statements-that-are-fine-test
  (testing "a trailing call (it may be made for its effect), a paren-less own
            routine, create, spawn, a mid-body bare name, and an if whose
            branches all assign `result` — none is rejected"
    (doseq [src ["function f(a: Array[Integer]): Integer do
  result := a.length
  a.add(1)
end"
                 "function g(): Integer do result := 1 end
function f(): Integer do
  result := 2
  g()
end"
                 "class Q
  feature
    five: Integer do result := 5 end
    touch do five end
end"
                 "class Pt feature x: Integer end
function f(): Integer do
  result := 1
  create Pt
end"
                 "function f(): Integer do
  result := 1
  spawn do result := 2 end
end"
                 "function f(k: Integer): Integer do
  k
  result := k
end"
                 "function f(n: Integer): Integer do
  if n > 0 then result := 1 else result := 2 end
end"]]
      (is (nil? (type-errors src)) src)))
  (testing "top-level statements are not a routine body"
    (is (= ["3"] (run-lines "let n := 21
n * 2
n
print(3)")))))

(deftest repl-expression-input-is-unaffected-test
  (testing "the REPL still evaluates and shows a bare expression, while a
            routine defined there is checked like any other"
    (binding [repl/*type-checking-enabled* (atom true)
              repl/*repl-var-types* (atom {})
              repl/*repl-backend* (atom :compiled)
              repl/*compiled-repl-session* (atom (compiled-repl/make-session))]
      (let [ctx (repl/init-repl-context)]
        (with-out-str (repl/eval-code ctx "let n := 21"))
        (is (= "Integer 42" (str/trim (with-out-str (repl/eval-code ctx "n * 2")))))
        (is (= "Integer 21" (str/trim (with-out-str (repl/eval-code ctx "n")))))
        (is (str/includes? (with-out-str
                             (repl/eval-code ctx "function g(k: Integer): Integer do k * 3 end"))
                           "Discarded value: `k * 3`"))
        (with-out-str
          (repl/eval-code ctx "function h(k: Integer): Integer do result := k * 3 end"))
        (is (= "Integer 6" (str/trim (with-out-str (repl/eval-code ctx "h(2)")))))))))

(deftest zero-values-test
  (testing "each return type with a zero value starts `result` at it"
    (is (= ["0" "0.0" "false" "[]" "{}" "#{}" "nil" "nil"]
           (run-lines "function i(): Integer do end
function r(): Real do end
function b(): Boolean do end
function a(): Array[Integer] do end
function m(): Map[String, Integer] do end
function s(): Set[Integer] do end
function ds(): ?String do end
function da(): ?Array[Integer] do end
print(i())
print(r())
print(b())
print(a())
print(m())
print(s())
print(ds())
print(da())")))))

(deftest no-zero-value-must-assign-result-test
  (testing "a return type with no zero value must assign `result`"
    (doseq [src ["function s(): String do print(1) end"
                 "class Pt feature x: Integer end
function p(): Pt do let q := create Pt end"
                 "class C
  feature
    name: String do end
end"]]
      (is (some #(str/includes? % "does not definitely assign result")
                (type-errors src))
          src)))
  (testing "an explicit assignment on every returning path does"
    (is (nil? (type-errors "function s(n: Integer): String do
  if n > 0 then result := \"pos\" else result := \"other\" end
end")))))

(deftest explicit-assignment-is-the-result-test
  (testing "the value `result` holds on exit: the last assignment that ran,
            or a mutation of the auto-created collection"
    (is (= ["42" "1" "2" "0" "9" "3" "[1, 2]"]
           (run-lines "function doubled(n: Integer): Integer do result := n * 2 end
function e(n: Integer): Integer do
  if n > 0 then result := 1 else result := 2 end
end
function last_wins(): Integer do
  result := 5
  result := 9
  print(0)
end
function looped(n: Integer): Integer do
  from let i := 0 until i = n do
    result := result + 1
    i := i + 1
  end
end
function built(): Array[Integer] do
  result.add(1)
  result.add(2)
end
print(doubled(21))
print(e(1))
print(e(-1))
print(last_wins())
print(looped(3))
print(built())")))))

(deftest nested-routine-result-is-its-own-test
  (testing "`result :=` inside a nested fn or spawn sets that body's result,
            never the enclosing routine's"
    (is (= ["7" "0" "5" "0"]
           (run-lines "function outer_fn(n: Integer): Integer do
  let f := fn (k: Integer): Integer do result := k end
  print(f(7))
end
function outer_spawn(n: Integer): Integer do
  let t := spawn do result := n end
  print(t.await)
end
print(outer_fn(1))
print(outer_spawn(5))")))))

(deftest bare-name-is-typed-as-the-value-test
  (testing "the typechecker types a paren-less bare name as the variable —
            a Function value included, which is not called — while `v()`
            calls it"
    (let [bare {:type :call :target nil :method "v" :args [] :has-parens false}
          fn-type {:base-type "Function" :param-types [] :return-type "Integer"}]
      (is (= "Integer" (tc/infer-expression-type bare {:var-types {"v" "Integer"}})))
      (is (= fn-type (tc/infer-expression-type bare {:var-types {"v" fn-type}})))
      (is (= "Integer"
             (tc/infer-expression-type (assoc bare :has-parens true)
                                       {:var-types {"v" fn-type}}))))))
