(ns nex.spawn-rescue-retry-test
  "Regression coverage for `rescue`/`retry` attached directly to a
   `spawn do ... end` block.

   Unlike a loop (iterative, needs a \"terminate the whole loop\" trick —
   see loop_rescue_retry_test.clj), spawn's body is a single, non-iterative
   unit of work, so its rescue needs no special semantics at all: it should
   behave exactly like a function/method's own rescue already does. Also
   implemented as pure walker-level desugaring (nex.walker/handle-spawn-
   expression): a rescue-bearing spawn's :body becomes a single nested
   {:type :scoped-block :body <original-body> :rescue <rescue>} — no new
   AST node, no typechecker/interpreter/JVM-backend changes for the
   desugaring itself.

   One genuine, independently-reproducible interpreter bug was found and
   fixed alongside this feature (not new :rescue-handling logic): spawn's
   result-value read-back (nex.interpreter/eval-node :spawn) looked up its
   \"__result_assigned__\" tracking marker directly on spawn-env, but that
   marker is always written via env-define (local to whatever env the
   assignment is CURRENTLY executing in), never env-set! (which walks the
   parent chain, the way \"result\" itself correctly propagates) — so a
   `result := ...` written inside any nested env-creating construct inside
   a spawn body (an `if`, an existing scoped-block, and now specifically a
   spawn's own rescue arm) silently failed to register, and .await() got
   nil even though \"result\" itself really had been updated. Fixed to
   mirror method-result-value's own, already-correct fallback (read
   \"result\" directly when the flag lookup misses). `spawn do ... rescue
   do result := fallback end end` is exactly the pattern that trips this,
   so test 1 below is a direct regression test for that fix."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "spawn_rescue_retry" ".nex")]
    (try
      (spit f code)
      (let [compiled (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
            interpreted (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest spawn-rescue-without-retry-completes-with-the-rescues-result-test
  (testing "an exception in a spawn body, rescued without retry, where the
            rescue clause assigns `result` — .await() must return the
            rescue's own value, not nil. Direct regression test for the
            __result_assigned__ propagation bug fixed alongside this
            feature (fails with nil on the interpreter backend without
            that fix)"
    (is (= ["99"]
           (both "function compute(): Integer
do
  let t: Task[Integer] := spawn do
    raise \"boom\"
    result := 1
  rescue
    result := 99
  end
  result := t.await
end
print(compute())")))))

(deftest spawn-rescue-with-retry-reruns-the-whole-body-test
  (testing "a spawn's rescue calling retry re-runs the ENTIRE spawn body
            from the top (matching function/method retry semantics — spawn
            is single-shot, there is no iteration to redo just part of);
            once the retried attempt succeeds, .await() returns its result"
    (is (= ["11"]
           (both "function compute(base: Integer): Integer
do
  let attempted := false
  let t: Task[Integer] := spawn do
    if attempted = false then
      attempted := true
      raise \"boom\"
    end
    result := base + 1
  rescue
    retry
  end
  result := t.await
end
print(compute(10))")))))

(deftest spawn-rescue-referencing-a-captured-outer-variable-test
  (testing "a spawn's rescue clause (and its retried body) correctly sees
            variables captured from the enclosing scope on BOTH backends —
            compiled-backend confidence test that the existing
            :scoped-block closure-capture rewriting (nex.lower's
            rewrite-statement-for-closures :scoped-block case) already
            covers a rescue arm nested inside a spawn, with no new
            capture-rewriting logic needed for this feature"
    (is (= ["7"]
           (both "function compute(): Integer
do
  let base := 5
  let t: Task[Integer] := spawn do
    raise \"boom\"
  rescue
    result := base + 2
  end
  result := t.await
end
print(compute())")))))

(deftest spawn-without-rescue-is-completely-unaffected-test
  (testing "a plain spawn with no rescue at all is byte-for-byte
            unaffected by this feature — regression coverage that the
            walker desugaring is a true no-op when there is no rescue
            clause"
    (is (= ["5"]
           (both "function compute(): Integer
do
  let t: Task[Integer] := spawn do
    result := 5
  end
  result := t.await
end
print(compute())")))))
