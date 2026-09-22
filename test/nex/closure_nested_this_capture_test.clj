(ns nex.closure-nested-this-capture-test
  "Regression coverage for a closure nested two-or-more levels deep, where
   the innermost one references the enclosing instance's own `this`/field/
   method, failing at runtime with \"Undefined variable: __closure_this__\".

   nex.lower rewrites a closure's own bare `this`/field/method reference
   into an explicit read/call through a synthetic captured identifier
   (rewrite-expression-for-closures/capture-closure-this!, name
   __closure_this__). When the closure containing that reference is itself
   nested inside ANOTHER closure — not directly inside the instance method
   — the OUTER closure must also capture __closure_this__, so it can hand
   the value down at the point it constructs the inner one. This mirrors
   the pre-existing \"a capture two closures deep must propagate outward\"
   mechanism nested-closure-with-unmutated-capture-is-unaffected-test (in
   closure_shared_capture_test.clj) already covers for an ordinary named
   variable — rewrite-anonymous-function-for-closures walks the nested
   closure's own :captures and registers each one on the enclosing scope
   too. But that propagation loop used a generic helper (capture-
   reference!) that only recognizes real declared variable names —
   __closure_this__ is synthetic and never a key in outer-var-types, so
   propagating it was a silent no-op: the outer closure compiled as if it
   never captured `this` at all. At runtime, when the (interpreted) outer
   closure constructs the inner one, __closure_this__ is unresolvable in
   its capture list — \"Undefined variable: __closure_this__\". Fixed by
   special-casing that name in the propagation loop to go through
   capture-closure-this! instead of capture-reference!.

   Every test here is compiled-backend-only (run-compiled, not a both-
   backends helper): the tree-walking interpreter has its own, separate,
   pre-existing gap resolving `this`/a field/a method two closures deep
   (\"Undefined method: bump\" / \"Method not found: count\" —
   nex.interpreter's own dynamic-scoping resolution of a nested closure's
   `this`, unrelated to nex.lower's __closure_this__ capture machinery this
   file is about) — out of scope here."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run-compiled
  "Printed output of CODE on the compiled backend only — see this file's
   own docstring for why (the interpreter has a separate, unrelated gap)."
  [code]
  (let [f (java.io.File/createTempFile "closure_nested_this_capture" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
      (finally (.delete f)))))

(deftest nested-closure-two-deep-calling-a-method-and-reading-a-field-test
  (testing "a closure passed as an argument to a higher-order function,
            itself built inside another closure (both nested inside an
            instance method), correctly resolves a bare method call and a
            bare field read against the enclosing instance two levels out"
    (is (= ["1"]
           (run-compiled "function apply1(f: Function(): Integer): Integer do
  result := f()
end

class Counter
feature
  count: Integer

  bump() do
    count := count + 1
  end

  nested_this(): Integer do
    let f: Function(): Integer := fn (): Integer do
      result := apply1(fn (): Integer do
        bump()
        result := count
      end)
    end
    result := f()
  end
end

let c: Counter := create Counter
print(c.nested_this())")))))

(deftest nested-closure-two-deep-reading-this-explicitly-test
  (testing "the same two-levels-deep nesting, but the innermost closure
            reads `this` explicitly (`let self_ref := this`) rather than
            through a bare field/method reference"
    (is (= ["0"]
           (run-compiled "function apply1(f: Function(): Integer): Integer do
  result := f()
end

class Counter
feature
  count: Integer

  nested_this(): Integer do
    let f: Function(): Integer := fn (): Integer do
      result := apply1(fn (): Integer do
        let self_ref: Counter := this
        result := self_ref.count
      end)
    end
    result := f()
  end
end

let c: Counter := create Counter
print(c.nested_this())")))))
