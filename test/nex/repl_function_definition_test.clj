(ns nex.repl-function-definition-test
  "Two ways a valid function failed to be defined in the REPL.

   With type checking off (the default), a function whose body held a `fn`
   literal with its parameter types left out, like `scores.filter(fn (s) => s
   > threshold)`, could not be compiled: those types are inferred only by the
   checker, which did not run. The definition failed (\"Unable to infer
   expression type during lowering\"), so the function was undefined. The
   checker now runs quietly for such inputs, for the inferred types alone.

   With type checking on, a zero-argument function was checked as an override
   of Function's call0 (returning Any, no precondition), so `function pick():
   ?String` and a zero-argument function with a `require` were rejected."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.repl :as repl]))

(defn- session [typecheck? lines]
  (let [ctx (repl/init-repl-context)]
    (reset! repl/*type-checking-enabled* typecheck?)
    (try
      (mapv #(with-out-str (repl/eval-code ctx %)) lines)
      (finally
        (reset! repl/*type-checking-enabled* false)
        (reset! repl/*repl-var-types* {})))))

(deftest fn-literals-in-function-bodies-with-type-checking-off
  (let [[d1 r1 d2 r2 d3 r3 r4 r5]
        (session false
                 ["function scores_above(threshold: Integer, scores: Array[Integer]): Array[Integer]\ndo\n  result := scores.filter(fn (s) => s > threshold)\nend"
                  "scores_above(50, [40, 60, 80])"
                  "function all_a(text: String): Boolean\ndo\n  result := text.all(fn (c) => c = #a)\nend"
                  "all_a(\"aa\")"
                  "function sum(xs: Array[Integer]): Integer\ndo\n  result := xs.fold(0, fn (s, x) => s + x)\nend"
                  "sum([1, 2, 3])"
                  "[1, 2].map(fn (x) => x * 2)"
                  ;; still lenient: no type error with checking off
                  "let r: Map[String, Any] := {\"n\": 1}\nr.get(\"n\") + 1"])]
    (doseq [d [d1 d2 d3]]
      (is (not (str/includes? d "Error")) d))
    (is (str/includes? r1 "[60, 80]") r1)
    (is (str/includes? r2 "true") r2)
    (is (str/includes? r3 "6") r3)
    (is (str/includes? r4 "[2, 4]") r4)
    (is (str/includes? r5 "2") r5)
    (is (not (str/includes? r5 "Type error")) r5)))

(deftest zero-argument-functions-with-type-checking-on
  (let [[d1 r1 d2 r2 bad-assign user-class]
        (session true
                 ["function pick(): ?String\ndo\n  result := nil\nend"
                  "pick() = nil"
                  "function pick2(): ?String\nrequire\n  ok: true\ndo\n  result := \"x\"\nend"
                  "pick2()"
                  "let s: String := pick()"
                  "class Gen inherit Function\nfeature\n  call0(): ?String do result := nil end\nend"])]
    (is (not (str/includes? d1 "error")) d1)
    (is (str/includes? r1 "true") r1)
    (is (not (str/includes? d2 "error")) d2)
    (is (str/includes? r2 "\"x\"") r2)
    (testing "callers still see the detachable result"
      (is (str/includes? bad-assign "Cannot assign ?String to variable 's' of type String") bad-assign))
    (testing "an override someone wrote is still checked"
      (is (str/includes? user-class "changes the return type from Any to ?String") user-class))))
