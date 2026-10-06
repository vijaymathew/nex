(ns nex.repl-continuation-test
  "How the REPL decides an input is incomplete (repl/continue-reading?): it counts
   block openers against `end`s. A keyword right after a `.` is a member name, not
   a block, and must not make the REPL show the `...` prompt and wait."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.repl :as repl]))

(deftest keyword-after-a-dot-is-not-a-block
  (testing "`from` as a member name (a misspelled constructor) no longer waits for more input"
    (is (false? (repl/continue-reading? ["let bs := create Byte_Array.from([0u8, 10u8])"])))
    (is (false? (repl/continue-reading? ["x.from(1)"])))
    (is (false? (repl/continue-reading? ["x . from(1)"])))
    (is (false? (repl/continue-reading? ["a.if"])))
    (is (false? (repl/continue-reading? ["a.do"])))))

(deftest ordinary-names-containing-keywords-still-work
  (is (false? (repl/continue-reading? ["let bs := create Byte_Array.from_array([0u8, 10u8])"])))
  (is (false? (repl/continue-reading? ["let x := from_here"]))))

(deftest real-blocks-still-continue
  (testing "a genuine loop or conditional still waits for its end"
    (is (true? (repl/continue-reading? ["from i := 0 until i > 3 do"])))
    (is (true? (repl/continue-reading? ["from i := 0 until i > 3 do" "  print(i)"])))
    (is (false? (repl/continue-reading? ["from i := 0 until i > 3 do" "  i := i + 1" "end"])))
    (is (true? (repl/continue-reading? ["if x then"])))
    (is (false? (repl/continue-reading? ["if x then print(1) end"])))
    (is (true? (repl/continue-reading? ["class Box"])))))

(deftest a-dotted-keyword-does-not-hide-a-real-block
  (testing "the dotted `from` is ignored, the real `if` still counts"
    (is (true? (repl/continue-reading? ["if a.from(1) then"])))
    (is (false? (repl/continue-reading? ["if a.from(1) then print(1) end"])))
    (is (true? (repl/continue-reading? ["from i := 0 until a.from(i) do"])))))

(defn- complete-only-at-the-end?
  "The REPL keeps reading through every proper prefix of LINES and stops at
   the last line."
  [lines]
  (and (every? #(true? (repl/continue-reading? (subvec lines 0 %)))
               (range 1 (count lines)))
       (false? (repl/continue-reading? lines))))

(deftest a-deferred-routine-with-an-ensure-clause
  (testing "`deferred ensure ... end` closes with an end of its own"
    (is (complete-only-at-the-end?
         ["deferred class Shape"
          "feature"
          "  scale(x: Integer): Integer"
          "  require"
          "    positive: x > 0"
          "  deferred"
          "  ensure"
          "    bigger: result > x"
          "  end"
          "end"]))
    (is (complete-only-at-the-end?
         ["deferred class Shape"
          "feature"
          "  area(): Real deferred ensure non_negative: result >= 0.0 end"
          "end"])))
  (testing "a when inside the ensure clause still pairs with its own end"
    (is (complete-only-at-the-end?
         ["deferred class Shape"
          "feature"
          "  f(x: Integer): Integer deferred"
          "  ensure"
          "    ok: result = when x > 0 then 1 else 0 end"
          "  end"
          "end"])))
  (testing "a bare deferred routine has no end"
    (is (complete-only-at-the-end?
         ["deferred class Shape"
          "feature"
          "  area(): Real deferred"
          "end"]))))

(deftest a-union-declaration
  (is (complete-only-at-the-end?
       ["union Shape"
        "  Circle(r: Real)"
        "  Square(s: Real)"
        "end"]))
  (is (complete-only-at-the-end?
       ["enum union Color"
        "  Red"
        "  Green"
        "end"]))
  (testing "union as a member name is not a declaration"
    (is (false? (repl/continue-reading? ["print(s.union(t))"])))
    (is (false? (repl/continue-reading? ["let u := a.union(b)"])))))
