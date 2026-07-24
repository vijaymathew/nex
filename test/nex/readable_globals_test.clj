(ns nex.readable-globals-test
  "Top-level `let` globals are readable (not assignable) from the static world —
   the body of any free function or class routine (§7.4 of the Definition). The
   read is lexical: a body resolves a free name to the global, never to a caller's
   local. A def-before-use watermark rejects programs that could read a global
   before its `let` has run. Covers both backends plus the two static rejections."
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
print(outer())"
  )

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
      (is (some #(str/includes? (tc/format-type-error %) "not initialized before the first call")
                errors)))))

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
