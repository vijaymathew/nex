(ns nex.loop-rescue-retry-test
  "Regression coverage for `rescue`/`retry` attached directly to a loop's
   own `do` block (`from...until...do...rescue...end`, and `repeat`/
   `across`, which both desugar to the same {:type :loop} AST node before
   the typechecker ever sees them).

   Requested by the user as syntactic sugar for the pre-existing (and
   still fully supported) manual workaround of nesting a `do...rescue...end`
   scoped block inside the loop's own body — with one explicit semantic
   requirement: unlike a scoped-block's own rescue (which just \"handles\"
   the exception and falls through to whatever comes next), a LOOP-level
   rescue that completes WITHOUT calling `retry` must terminate the WHOLE
   loop immediately, the same way `until` becoming true already does —
   not just swallow the exception and continue to the next iteration.

   Implemented entirely as walker-level desugaring (nex.walker/rescue-wrap-
   loop, called from handle-loop-statement/handle-repeat-statement/handle-
   across-statement) — no new AST node type, no typechecker/interpreter/
   JVM-backend changes at all: a synthetic boolean \"stop flag\" is injected
   into the loop's `:init`, `:until` becomes `<original-until> or <flag>`,
   and the loop's own raw body is wrapped in a nested {:type :scoped-block}
   carrying the original `:rescue` plus one appended `<flag> := true`. A
   rescue that completes without retry falls through to the flag-set, then
   to whatever sits after the wrapped block in :body (for repeat/across,
   their own counter/cursor bookkeeping — advancing it one final time on
   the terminating iteration is harmless), then the loop's own `until` is
   re-evaluated and is now true, exiting through the exact same path an
   ordinary until-true exit already takes. `retry` is handled entirely by
   the pre-existing, unmodified :scoped-block retry machinery, redoing only
   the wrapped body — never :init/:until/:invariant/:variant or repeat/
   across's own bookkeeping, since those sit outside the wrap."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "loop_rescue_retry" ".nex")]
    (try
      (spit f code)
      (let [compiled (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
            interpreted (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest from-until-rescue-terminates-the-whole-loop-without-retry-test
  (testing "an exception in a from/until loop's body, rescued without
            calling retry, terminates the WHOLE loop immediately — not
            just the current iteration — and code after the loop still
            runs (proves \"terminate the loop\", not \"terminate the
            enclosing routine\")"
    (is (= ["\"1\"" "\"4\"" "\"9\"" "\"done\"" "\"after loop\""]
           (both "from
  let i := 0
until
  i = 5
do
  i := i + 1
  if i = 4 then
    raise \"stop\"
  end
  print((i * i).to_string)
rescue
  print(\"done\")
end
print(\"after loop\")")))))

(deftest from-until-rescue-with-retry-continues-the-loop-test
  (testing "a rescue that calls retry re-runs only the current iteration;
            once it succeeds the loop continues normally past it"
    (is (= ["\"caught: transient\"" "\"attempts=4\"" "\"successes=3\""]
           (both "let attempts := 0
let successes := 0
from
  let i := 0
until
  i = 3
do
  attempts := attempts + 1
  if attempts = 2 then
    raise \"transient\"
  end
  successes := successes + 1
  i := i + 1
rescue
  print(\"caught: \" + exception)
  retry
end
print(\"attempts=\" + attempts.to_string)
print(\"successes=\" + successes.to_string)")))))

(deftest repeat-rescue-with-retry-does-not-double-advance-the-counter-test
  (testing "repeat's own synthetic counter sits OUTSIDE the rescue-wrapped
            body (appended after it, per the desugaring), so a retry —
            which only re-runs the wrapped body — redoes exactly the
            current iteration without the counter having advanced yet.
            A failed attempt at counter value 1 is retried and its own
            log entry (\"1\") appears twice in a row before the loop moves
            on to 2 — proving the counter did not skip or double-advance"
    (is (= ["\"0112\""]
           (both "let log := \"\"
let raised_once := false
repeat 3 do
  log := log + __repeat_i__.to_string
  if __repeat_i__ = 1 and raised_once = false then
    raised_once := true
    raise \"boom\"
  end
rescue
  retry
end
print(log)")))))

(deftest across-rescue-with-retry-reprocesses-the-failing-item-once-test
  (testing "across's own cursor.next() call sits OUTSIDE the rescue-wrapped
            body (appended after it), so a retry redoes exactly the
            current item without the cursor having advanced — the failing
            item is reprocessed exactly once, never skipped or duplicated"
    (is (= ["\"seen=123\""]
           (both "let seen := \"\"
let raised_once := false
across [1, 2, 3] as x do
  if x = 2 and raised_once = false then
    raised_once := true
    raise \"boom\"
  end
  seen := seen + x.to_string
rescue
  retry
end
print(\"seen=\" + seen)")))))

(deftest rescue-less-loop-nested-in-an-outer-rescue-is-unaffected-test
  (testing "a loop with NO rescue of its own, nested inside an outer
            scoped-block's rescue, is completely untouched by this
            feature: a bare retry inside it legally propagates out to the
            OUTER rescue (pre-existing :scoped-block retry semantics,
            unmodified), restarting the whole outer block — including
            re-running the loop's own :init — not just the loop"
    (is (= ["4"]
           (both "let raised_once := false
let tries := 0
do
  from
    let i := 0
  until
    i = 3
  do
    tries := tries + 1
    if raised_once = false then
      raised_once := true
      raise \"boom\"
    end
    i := i + 1
  end
rescue
  retry
end
print(tries)")))))

(deftest loop-invariant-still-checked-on-rescue-terminated-iteration-test
  (testing "this design does not special-case skipping the loop's own
            post-body invariant check when a rescue terminates the loop —
            the terminating iteration's scoped-block \"returns\" normally
            from the outer loop body's own perspective, so the invariant
            is checked exactly as it already is for any ordinary
            statement list ending in a handled-without-incident nested
            block. Documented, deliberate behavior: a loop invariant
            written for this feature must hold even on the iteration a
            rescue terminates from."
    (is (= ["\"rescued\"" "\"count=2\""]
           (both "let count := 0
from
  let i := 0
invariant
  i_nonneg: i >= 0
until
  i = 3
do
  count := count + 1
  i := i + 1
  if i = 2 then
    raise \"boom\"
  end
rescue
  print(\"rescued\")
end
print(\"count=\" + count.to_string)")))))

(deftest non-rescue-loops-are-completely-unaffected-test
  (testing "plain from/until, repeat, and across loops with no rescue at
            all are byte-for-byte unaffected by this feature — regression
            coverage that rescue-wrap-loop is a true no-op when RESCUE is
            nil"
    (is (= ["\"0\"" "\"1\"" "\"2\""]
           (both "from
  let i := 0
until
  i = 3
do
  print(i.to_string)
  i := i + 1
end")))
    (is (= ["\"0\"" "\"1\"" "\"2\""]
           (both "repeat 3 do
  print(__repeat_i__.to_string)
end")))
    (is (= ["\"a\"" "\"b\"" "\"c\""]
           (both "across [\"a\", \"b\", \"c\"] as x do
  print(x)
end")))))
