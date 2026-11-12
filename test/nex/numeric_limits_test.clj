(ns nex.numeric-limits-test
  "The limits of the built-in numeric types (`Integer.MAX`, `Byte.MIN`,
   `Real.MAX`, ...) and the `can_add` / `can_subtract` / `can_multiply`
   queries, which tell a precondition whether an operation would overflow
   without it having to write the bound out. Asserted on both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.fmt :as fmt]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- type-errors
  "The type errors CODE produces, or nil when it type-checks."
  [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (mapv (comp str :message) (:errors result)))))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "numeric_limits" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  "Printed output lines of LINES (joined), asserted identical on both backends."
  [& lines]
  (let [code (str/join "\n" lines)
        compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(deftest integer-limits
  (is (= ["9223372036854775807" "-9223372036854775808" "9223372036854775807" "-9223372036854775808"]
         (both "print(Integer.MAX)"
               "print(Integer.MIN)"
               "print(Integer64.MAX)"
               "print(Integer64.MIN)")))
  (testing "they are Integers, usable anywhere one is"
    (is (= ["9223372036854775806" "\"9223372036854775807\""]
           (both "let n: Integer := Integer.MAX - 1"
                 "print(n)"
                 "print(Integer.MAX.to_string)"))))
  (testing "going past them still raises"
    (is (= ["Arithmetic overflow"]
           (both "do print(Integer.MAX + 1) rescue print(exception) end"))))
  (testing "subtracting Integer.MIN is fine when the result fits (the interpreter used to raise)"
    (is (= ["9223372036854775807" "0" "Arithmetic overflow"]
           (both "let m := Integer.MIN"
                 "print(-1 - m)"
                 "print(m - m)"
                 "do print(0 - m) rescue print(exception) end")))))

(deftest sized-limits
  (is (= ["-2147483648" "2147483647" "-32768" "32767" "0" "255"]
         (both "print(Integer32.MIN)"
               "print(Integer32.MAX)"
               "print(Integer16.MIN)"
               "print(Integer16.MAX)"
               "print(Byte.MIN)"
               "print(Byte.MAX)")))
  (testing "each has its own type"
    (is (nil? (type-errors (str "let a: Integer32 := Integer32.MIN\n"
                                "let b: Integer16 := Integer16.MAX\n"
                                "let c: Byte := Byte.MAX\n"
                                "let d: Integer := Integer64.MIN"))))
    (is (seq (type-errors "let c: Integer := Byte.MAX")))
    (is (seq (type-errors "let c: Byte := Integer32.MAX"))))
  (testing "a method call on one works"
    (is (= ["-2147483648" "\"ff\""]
           (both "print(Integer32.MAX.bitwise_not)"
                 "print(Byte.MAX.to_hex)")))))

(deftest real-limits
  (is (= ["1.7976931348623157E308" "-1.7976931348623157E308" "true"]
         (both "print(Real.MAX)"
               "print(-Real.MAX)"
               "print((Real.MAX * 2.0).is_infinite)")))
  (testing "Real has no MIN: the least finite Real is -Real.MAX"
    (is (thrown-with-msg? Exception #"Real has no constant MIN \(it has E, MAX, PI\)"
                          (p/ast "print(Real.MIN)")))))

(deftest unknown-constants
  (is (thrown-with-msg? Exception #"Integer has no constant TOP \(it has MAX, MIN\)"
                        (p/ast "print(Integer.TOP)")))
  (is (thrown-with-msg? Exception #"Byte has no constant TOP \(it has MAX, MIN\)"
                        (p/ast "print(Byte.TOP)")))
  (is (thrown-with-msg? Exception #"Integer64 has no constant TOP"
                        (p/ast "print(Integer64.TOP)"))))

(deftest integer-can-queries
  (testing "can_add"
    (is (= ["true" "false" "true" "false" "true" "true"]
           (both "print(Integer.MAX.can_add(0))"
                 "print(Integer.MAX.can_add(1))"
                 "print(Integer.MIN.can_add(Integer.MAX))"
                 "print(Integer.MIN.can_add(-1))"
                 "print((Integer.MAX - 5).can_add(5))"
                 "print((-3).can_add(7))"))))
  (testing "can_subtract"
    (is (= ["false" "true" "false" "true" "true" "false"]
           (both "print(Integer.MIN.can_subtract(1))"
                 "print(Integer.MIN.can_subtract(0))"
                 "print((0).can_subtract(Integer.MIN))"
                 "print((-1).can_subtract(Integer.MIN))"
                 "print(Integer.MAX.can_subtract(Integer.MAX))"
                 "print(Integer.MAX.can_subtract(-1))"))))
  (testing "can_multiply"
    (is (= ["true" "false" "true" "false" "false" "false" "true" "true" "true"]
           (both "print((3037000499).can_multiply(3037000499))"
                 "print((3037000500).can_multiply(3037000500))"
                 "print((-4611686018427387904).can_multiply(2))"
                 "print((-4611686018427387905).can_multiply(2))"
                 "print((-1).can_multiply(Integer.MIN))"
                 "print(Integer.MIN.can_multiply(-1))"
                 "print((-1).can_multiply(Integer.MAX))"
                 "print(Integer.MIN.can_multiply(0))"
                 "print(Integer.MIN.can_multiply(1))"))))
  (testing "each agrees with whether the operator raises"
    (is (= ["\"ok\""]
           (both "let samples := [Integer.MIN, Integer.MIN + 1, -3037000500, -3037000499, -2, -1, 0, 1, 2, 3037000499, 3037000500, Integer.MAX - 1, Integer.MAX]"
                 "let bad := 0"
                 "across samples as a do"
                 "  across samples as b do"
                 "    let added := true"
                 "    do let r := a + b rescue added := false end"
                 "    if added /= a.can_add(b) then bad := bad + 1 end"
                 "    let subtracted := true"
                 "    do let r := a - b rescue subtracted := false end"
                 "    if subtracted /= a.can_subtract(b) then bad := bad + 1 end"
                 "    let multiplied := true"
                 "    do let r := a * b rescue multiplied := false end"
                 "    if multiplied /= a.can_multiply(b) then bad := bad + 1 end"
                 "  end"
                 "end"
                 "if bad = 0 then print(\"ok\") else print(bad) end")))))

(deftest sized-can-queries
  (testing "Byte: whether the Integer result fits back in 0..255"
    (is (= ["false" "true" "false" "true" "false" "true"]
           (both "print(Byte.MAX.can_add(1u8))"
                 "print(200u8.can_add(55u8))"
                 "print(3u8.can_subtract(4u8))"
                 "print(4u8.can_subtract(4u8))"
                 "print(16u8.can_multiply(16u8))"
                 "print(15u8.can_multiply(17u8))"))))
  (testing "Integer16 / Integer32: whether the Integer result fits back in the type"
    (is (= ["false" "true" "false" "false" "false" "true" "false" "true"]
           (both "print(Integer32.MAX.can_add(1i32))"
                 "print(Integer32.MAX.can_add(Integer32.MIN))"
                 "print(Integer32.MIN.can_multiply(-1i32))"
                 "print(Integer16.MIN.can_subtract(1i16))"
                 "print(Integer16.MAX.can_subtract(-1i16))"
                 "print(100i16.can_multiply(327i16))"
                 "print(100i16.can_multiply(328i16))"
                 "print(Integer16.MIN.can_add(Integer16.MAX))"))))
  (testing "the argument has the receiver's type"
    (is (seq (type-errors "print(3u8.can_add(4))")))
    (is (seq (type-errors "print(3i32.can_add(4i16))")))
    (is (nil? (type-errors "let b: Boolean := 3i16.can_multiply(4i16)")))))

(deftest in-a-precondition
  (is (= ["3" "Precondition violation: no_overflow" "-2147483648"
          "Precondition violation: fits"]
         (both "function safe_add(a, b: Integer): Integer"
               "  require"
               "    no_overflow: a.can_add(b)"
               "  do"
               "    result := a + b"
               "  end"
               "function sub32(a, b: Integer32): Integer32"
               "  require"
               "    fits: a.can_subtract(b)"
               "  do"
               "    result := (a - b).to_integer32"
               "  end"
               "print(safe_add(1, 2))"
               "do print(safe_add(Integer.MAX, 1)) rescue print(exception) end"
               "print(sub32(-2147483647i32, 1i32))"
               "do print(sub32(Integer32.MIN, 1i32)) rescue print(exception) end"))))

(deftest formatting-keeps-the-spelling
  (doseq [src ["let a := Integer.MAX" "let a := Integer64.MIN" "let a := Byte.MAX"
               "let a := Integer16.MIN" "let a := Integer32.MAX" "let a := Real.MAX"]]
    (is (str/includes? (fmt/format-code (str "class C\n  feature\n    f do\n      " src "\n    end\nend"))
                       src)
        src)))
