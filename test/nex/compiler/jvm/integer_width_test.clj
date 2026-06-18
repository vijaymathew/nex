(ns nex.compiler.jvm.integer-width-test
  "Phase 3 of the numeric-tower work (docs/md/NUMERIC_TOWER.md): the JVM bytecode
   compiler treats Nex Integer as 64-bit (Int64) with checked overflow, matching
   the interpreter. Previously Integer lowered to a 32-bit `int`, so the compiler
   silently truncated (e.g. 1000000 * 1000000 wrapped to 32 bits) and large
   literals (> 2^31) failed entirely. Arithmetic overflow now raises, via
   java.lang.Math.*Exact, exactly as the interpreter's checked arithmetic does."
  (:require [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.repl :as compiled-repl]
            [nex.parser :as p]))

(defn- root-cause [^Throwable t]
  (loop [x t] (if-let [c (.getCause x)] (recur c) x)))

(defn- compiled-result [src]
  (:result (compiled-repl/compile-and-eval! (compiled-repl/make-session) (p/ast src))))

(defn- compiled-raises? [src]
  (try (compiled-result src) false
       (catch Throwable t (instance? ArithmeticException (root-cause t)))))

(deftest integer-is-64-bit-no-truncation
  (testing "products that exceed 32 bits are not truncated"
    (is (= 1000000000000 (compiled-result "1000000 * 1000000")))
    (is (= 4611686018427387904 (compiled-result "let a: Integer := 4611686018427387904\na")))
    (is (= 9223372036854775807 (compiled-result "let a: Integer := 9223372036854775807\na")))))

(deftest large-integer-literals-work
  (testing "literals above 2^31 are usable as values, locals, and operands"
    (is (= 3000000000 (compiled-result "let a: Integer := 3000000000\na")))
    (is (= 1000000000001 (compiled-result "1000000000000 + 1")))
    (is (= 5000000000 (compiled-result "let a: Integer := 5000000000\nlet b: Integer := a\nb")))))

(deftest integer-overflow-raises
  (testing "arithmetic that overflows Int64 raises (checked, like the interpreter)"
    ;; Built from a local so the overflow happens in emitted bytecode, not folding.
    (is (compiled-raises? "let a: Integer := 1000000000\na * a * a"))
    (is (compiled-raises? "let a: Integer := 9223372036854775807\na + 1"))
    (is (compiled-raises? "let a: Integer := 9223372036854775807\na * 2"))))

(deftest non-overflowing-arithmetic-still-works
  (testing "ordinary arithmetic is unaffected"
    (is (= 5 (compiled-result "2 + 3")))
    (is (= 42 (compiled-result "7 * 6")))
    (is (= -5 (compiled-result "let a: Integer := 5\n0 - a")))
    (is (= 1 (compiled-result "10 % 3")))
    (is (= 256 (compiled-result "2 ^ 8")))))

(deftest bitwise-still-correct-on-64-bit-integer
  (testing "bitwise ops (a 32-bit island matching the interpreter) survive the width change"
    (is (= 2048 (compiled-result "(2).bitwise_left_shift(10)")))
    (is (= 2 (compiled-result "(6).bitwise_and(3)")))
    (is (= 7 (compiled-result "(6).bitwise_or(1)")))
    (is (= -1 (compiled-result "(0).bitwise_not")))
    (is (= true (compiled-result "(5).bitwise_is_set(0)")))))

(deftest collection-indices-and-sizes-cross-the-int-boundary
  (testing "Integer (long) indices narrow to int for Java collections; sizes widen back"
    (is (= 30 (compiled-result "let xs: Array[Integer] := [10, 20, 30]\nxs.get(2)")))
    (is (= 3 (compiled-result "let xs: Array[Integer] := [10, 20, 30]\nxs.length")))
    (is (= 1 (compiled-result "let xs: Array[Integer] := [10, 20, 30]\nxs.index_of(20)")))))
