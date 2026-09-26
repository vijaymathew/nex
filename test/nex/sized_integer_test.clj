(ns nex.sized-integer-test
  "Integer16 and Integer32: signed fixed-width integers, modelled on Byte (see
   nex.byte-type-test). Neither converts to or from another integer type
   implicitly; arithmetic on them promotes to Integer; bitwise methods work on,
   and wrap to, the type's own width; `i16` / `i32` suffixed literals make them.

   They are wrapper objects at runtime (nex.types.runtime/nex-int16?), not a
   Number, so every behaviour is asserted on both backends: a numeric path that
   forgot to unwrap one would fail loudly on one of them.

   `Integer64` is only another spelling of `Integer`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.fmt :as fmt]
            [nex.interpreter :as interp]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.types.runtime :as rt]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- type-errors
  "The type errors CODE produces, or nil when it type-checks."
  [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (mapv :message (:errors result)))))

(defn- error-text [code]
  (str/join "\n" (map str (type-errors code))))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "byte_type" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  "Printed output lines of CODE, asserted identical on both backends."
  [code]
  (let [compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- raises-on-both?
  "Whether CODE fails at runtime on both backends with a message containing MSG."
  [code msg]
  (every? (fn [interpret?]
            (let [f (java.io.File/createTempFile "byte_type" ".nex")]
              (try
                (spit f code)
                (try (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))
                     false
                     (catch Throwable t
                       (loop [x t]
                         (cond
                           (str/includes? (str (ex-message x)) msg) true
                           (.getCause x) (recur (.getCause x))
                           :else false))))
                (finally (.delete f)))))
          [false true]))

;; ---------------------------------------------------------------------------
;; Typechecker
;; ---------------------------------------------------------------------------

(deftest sized-integers-do-not-convert-implicitly
  (testing "no implicit conversion between Integer, Integer16, Integer32 and Byte"
    (doseq [[from to] [["Integer16" "Integer32"] ["Integer32" "Integer16"]
                       ["Integer16" "Integer"] ["Integer32" "Integer"]
                       ["Integer" "Integer16"] ["Integer" "Integer32"]
                       ["Integer16" "Byte"] ["Byte" "Integer16"]
                       ["Integer32" "Byte"] ["Byte" "Integer32"]]]
      (let [seed (case from
                   "Integer16" "1i16" "Integer32" "1i32" "Integer" "1" "Byte" "1u8")]
        (is (str/includes? (error-text (str "let a: " from " := " seed "\nlet b: " to " := a"))
                           (str "Cannot assign " from " to variable 'b' of type " to))
            (str from " -> " to))))))

(deftest plain-integer-literal-is-not-sized
  (is (some? (type-errors "let a: Integer16 := 5")))
  (is (some? (type-errors "let a: Integer32 := 5")))
  (is (some? (type-errors "let xs: Array[Integer16] := [1, 2]")))
  (is (nil? (type-errors "let xs: Array[Integer16] := [1i16, -2i16]")))
  (is (nil? (type-errors "let a: Integer32 := (5).to_integer32()"))))

(deftest sized-arithmetic-promotes-to-integer
  (is (nil? (type-errors "let a: Integer16 := 5i16\nlet i: Integer := a + a")))
  (is (nil? (type-errors "let a: Integer32 := 5i32\nlet i: Integer := a * 2 - a / 2 + a % 2")))
  (is (nil? (type-errors "let a: Integer32 := 5i32\nlet i: Integer := -a")))
  (is (nil? (type-errors "let a: Integer16 := 5i16\nlet r: Real := a + 1.5")))
  (is (nil? (type-errors "let a: Integer16 := 5i16\nlet b: Integer32 := 6i32\nlet i: Integer := a + b")))
  (is (some? (type-errors "let a: Integer16 := 5i16\nlet c: Integer16 := a + a"))
      "the sum is an Integer; narrow it with to_integer16()")
  (is (nil? (type-errors "let a: Integer16 := 5i16\nlet c: Integer16 := (a + a).to_integer16()"))))

(deftest sized-comparison-is-same-type-only
  (is (nil? (type-errors "print(1i16 < 2i16)\nprint(1i32 = 1i32)")))
  (is (some? (type-errors "print(1i16 < 2i32)")))
  (is (some? (type-errors "print(1i16 = 1)")))
  (is (some? (type-errors "print(1i32 < 2)")))
  (is (nil? (type-errors "print(1i16.to_integer() < 2)"))))

(deftest convert-rejects-sized-numeric-changes
  (is (str/includes? (error-text "let n: Integer := 5\nif convert n to b: Integer16 then print(b) end")
                     "convert cannot change numeric representation")))

(deftest sized-literal-range-is-checked
  (testing "out of range at parse time"
    (doseq [src ["32769i16" "40000i16" "0x10000i16" "2147483649i32" "99999999999999999999i32"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"literal out of range"
                            (p/ast (str "print(" src ")")))
          src)))
  (testing "the positive limit + 1 parses (so -32768i16 can) but is rejected by the typechecker"
    (is (some? (p/ast "print(32768i16)")))
    (is (str/includes? (error-text "print(32768i16)") "outside -32768..32767"))
    (is (str/includes? (error-text "print(2147483648i32)") "outside -2147483648..2147483647"))
    (is (nil? (type-errors "print(-32768i16)\nprint(-2147483648i32)\nprint(32767i16)\nprint(2147483647i32)")))))

(deftest integer64-is-an-alias-of-integer
  (is (nil? (type-errors "let a: Integer64 := 5\nlet b: Integer := a\nlet c: Integer64 := b")))
  (is (nil? (type-errors "let xs: Array[Integer64] := [1, 2]\nlet ys: Array[Integer] := xs")))
  (is (nil? (type-errors "function f(x: Integer64): Integer64 do result := x end\nlet i: Integer := f(1)")))
  (is (some? (type-errors "let a: Integer64 := 1i32"))))

;; ---------------------------------------------------------------------------
;; Runtime representation
;; ---------------------------------------------------------------------------

(deftest wrapper-representation
  (testing "Integer16 / Integer32 are tagged wrappers, distinct from each other, Byte and Integer"
    (is (rt/nex-int16? (rt/->nex-int16 -32768)))
    (is (rt/nex-int32? (rt/->nex-int32 2147483647)))
    (is (not (rt/nex-int16? 5)))
    (is (not (rt/nex-int32? (int 5))) "a plain java.lang.Integer is not an Integer32")
    (is (not (rt/nex-int16? (rt/->nex-int32 5))))
    (is (not (number? (rt/->nex-int16 5))) "deliberately not a Number")
    (is (thrown? clojure.lang.ExceptionInfo (rt/->nex-int16 32768)))
    (is (thrown? clojure.lang.ExceptionInfo (rt/->nex-int16 -32769)))
    (is (thrown? clojure.lang.ExceptionInfo (rt/->nex-int32 2147483648)))
    (is (= (rt/->nex-int16 5) (rt/->nex-int16 5)))
    (is (not= (rt/->nex-int16 5) (rt/->nex-int32 5)))
    (is (= (hash (rt/->nex-int32 5)) (hash (rt/->nex-int32 5))))
    (is (= "-7" (str (rt/->nex-int16 -7))))
    (is (= 5 (rt/sized->long (rt/->nex-int32 5))))
    (is (= 200 (rt/sized->long (rt/->nex-byte 200))))
    (is (= 9 (rt/sized->long 9)))))

;; ---------------------------------------------------------------------------
;; Behaviour on both backends
;; ---------------------------------------------------------------------------

(deftest literals-arithmetic-and-conversion
  (is (= ["300" "-70000" "301" "-140000" "-32768" "true" "true" "40000" "-301" "2400" "70000" "\"101\"" "10"]
         (both "let a: Integer16 := 300i16
let b: Integer32 := -70000i32
print(a)
print(b)
print(a + 1)
print(b * 2)
print(-32768i16)
print(a > 5i16)
print(a = 300i16)
let n: Integer := 40000
print(n.to_integer32())
print(a.bitwise_not())
print(a.bitwise_left_shift(3))
print(b.abs())
print((5).to_integer16().to_string(2))
let i64: Integer64 := 9
print(i64 + 1)"))))

(deftest narrowing-conversions-are-checked
  (is (raises-on-both? "let n: Integer := 70000\nprint(n.to_integer16())" "-32768..32767"))
  (is (raises-on-both? "let n: Integer := -70000\nprint(n.to_integer16())" "-32768..32767"))
  (is (raises-on-both? "let n: Integer := 2147483648\nprint(n.to_integer32())" "-2147483648..2147483647"))
  (is (raises-on-both? "print(70000i32.to_integer16())" "-32768..32767"))
  (is (raises-on-both? "print(-1i16.to_byte())" "0..255"))
  (is (raises-on-both? "print(300i32.to_byte())" "0..255"))
  (is (= ["-32768" "32767" "255" "7" "-7" "200" "200"]
         (both "let n: Integer := -32768
print(n.to_integer16())
print(32767i32.to_integer16())
print(255i16.to_byte())
print(7u8.to_integer16())
print(-7i16.to_integer32())
print(200u8.to_integer32())
print(200u8.to_integer16())"))))

(deftest integer16-bitwise-is-sixteen-bit
  (is (= ["-1" "-32768" "-2" "-2" "false" "true" "-32768" "0" "4095" "-4" "-1" "0"]
         (both "let p: Integer16 := 32767i16
print(0i16.bitwise_not())
print(p.bitwise_left_shift(15))
print(p.bitwise_rotate_left(1))
print(p.bitwise_not().bitwise_right_shift(14))
print(p.bitwise_is_set(15))
print(p.bitwise_is_set(14))
print(p.bitwise_not())
print(p.bitwise_left_shift(16))
print((-1i16).bitwise_logical_right_shift(4))
print((-16i16).bitwise_right_shift(2))
print(p.bitwise_set(15))
print(p.bitwise_not().bitwise_unset(15).bitwise_rotate_right(15))"))))

(deftest integer32-bitwise-is-thirty-two-bit
  (is (= ["-1" "-2147483648" "0" "2147483647" "-2" "2147483647" "-2147483647" "2"]
         (both "let p: Integer32 := 2147483647i32
print(0i32.bitwise_not())
print(p.bitwise_not())
print(p.bitwise_left_shift(32))
print((-1i32).bitwise_logical_right_shift(1))
print(p.bitwise_left_shift(1))
print(p.bitwise_rotate_right(1).bitwise_rotate_left(1))
print(p.bitwise_not().bitwise_or(1i32).bitwise_xor(0i32).bitwise_and(-1i32).bitwise_or(0i32).bitwise_xor(0i32))
print(1i32.bitwise_rotate_right(31))"))))

(deftest bitwise-range-and-count-errors
  (is (raises-on-both? "print(1i16.bitwise_set(16))" "0..15"))
  (is (raises-on-both? "print(1i32.bitwise_is_set(32))" "0..31"))
  (is (raises-on-both? "print(1i16.bitwise_left_shift(-1))" "non-negative"))
  (is (raises-on-both? "print((-32768i16).abs())" "no positive counterpart"))
  (is (raises-on-both? "print(5i16.to_string(7))" "base must be")))

(deftest to-string-bases
  (is (= ["\"ff\"" "\"-ff\"" "\"11111111\"" "\"-80000000\""]
         (both "print(255i16.to_string(16))
print((-255i32).to_string(16))
print(255i32.to_string(2))
print((-2147483648i32).to_string(16))"))))

(deftest sized-equality-and-methods-are-type-strict
  (is (= ["true" "false" "false" "false" "true" "false"]
         (both "print(5i16.equals(5i16))
print(5i16.equals(5i32))
print(5i16.equals(5))
print((5).equals(5i16))
print(5i32.equals(5i32))
print(5i32.equals(5u8))"))))

(deftest sized-min-max-compare
  (is (= ["-3" "7" "true" "false"]
         (both "print((-3i16).min(7i16))
print((-3i32).max(7i32))
print(1i16 < 2i16)
print(3i32 <= 2i32)"))))

(deftest sized-fields-defaults-results-and-functions
  (is (= ["0" "0" "0" "0" "42" "5" "-2147483648"]
         (both "class Box
  feature
    a: Integer16
    b: Integer32
    total(): Integer do result := a + b end
end
function twice(x: Integer32): Integer32 do
  result := (x * 2).to_integer32()
end
function zero16(): Integer16 do
end
let bx := create Box
print(bx.a)
print(bx.b)
print(bx.total())
print(zero16())
print(twice(21i32))
print(-5i16.abs())
print(-2147483648i32)"))))

(deftest sized-in-collections
  (is (= ["[-1, 2, 3]" "\"seven\"" "1" "true" "-4"]
         (both "let xs: Array[Integer16] := [3i16, -1i16, 2i16]
print(xs.sort())
let m: Map[Integer32, String] := {}
m.put(7i32, \"seven\")
print(m.get(7i32))
let s: Set[Integer16] := #{4i16, 4i16}
print(s.size())
print(xs.contains(-1i16))
let total: Integer := 0
across xs as x do
  total := total - x
end
print(total)"))))

(deftest sized-through-any-and-convert
  (testing "a sized value keeps its identity through Any; an Integer is not one"
    (is (= ["\"Integer16\"" "\"Integer32\"" "\"5\"" "\"not i16\"" "true" "false" "true"]
           (both "let a: Any := 5i16
let n: Any := 5
print(type_of(a))
print(type_of(5i32))
if convert a to y: Integer16 then print(y.to_string(16)) end
if convert n to z: Integer16 then print(\"wrong\") else print(\"not i16\") end
print(type_is(\"Integer16\", a))
print(type_is(\"Integer32\", a))
print(type_is(\"Integer32\", 5i32))")))))

(deftest sized-string-concat-and-mixed-arithmetic
  (is (= ["\"v=12/13\"" "-9" "0.5" "-2"]
         (both "print(\"v=\" + 12i16 + \"/\" + 13i32)
print(-3i16 * 3i32)
print(5i16 / 2 - 1i32 + 1.0 * 0.5 - 1.0)
print(-2i32)"))))

(deftest negative-literals-fold-into-the-literal
  (testing "-5i16 is one Integer16 literal, so a method chain hangs off the negative value"
    (is (= ["5" "-32768" "\"Integer16\"" "-5" "-6"]
           (both "print(-5i16.abs())
print(-32768i16)
print(type_of(-1i16))
let a: Integer16 := 5i16
print(-a)
print(-a - 1)")))
    (is (nil? (type-errors "let a: Integer16 := -5i16")))
    (is (some? (type-errors "let a: Integer16 := 5i16\nlet b: Integer16 := -a")))))

(deftest sized-closures-detachable-generics-and-tasks
  (is (= ["700" "nil" "9" "\"07\"" "8"]
         (both "class Box[T]
  create
    make(v: T) do this.value := v end
  feature
    value: T
end
let f := fn(v: Integer16): Integer do result := v * 100 end
print(f(7i16))
let sb: ?Integer32 := nil
print(sb)
let t := spawn do result := 9i32 end
print(t.await())
let bx: Box[Byte] := create Box[Byte].make(7u8)
print(bx.value.to_string(10).pad_start(\"0\", 2))
let c: Box[Integer16] := create Box[Integer16].make(8i16)
print(c.value)"))))

(deftest sized-case-literals
  (is (= ["\"five\""]
         (both "case 5i16 of
  5i16 then print(\"five\")
  else print(\"other\")
end"))))

(deftest sized-values-in-contracts-and-loops
  (is (= ["10" "\"ok\""]
         (both "function count_to(n: Integer32): Integer32
  require
    non_negative: n >= 0i32
  do
    let i: Integer32 := 0i32
    from i := 0i32 until i >= n do
      i := (i + 1).to_integer32()
    end
    result := i
  ensure
    reached: result = n
  end
print(count_to(10i32))
print(\"ok\")"))))

;; ---------------------------------------------------------------------------
;; Java interop
;; ---------------------------------------------------------------------------

(deftest sized-values-cross-into-java-as-integers
  (is (= ["2" "5"]
         (both "import java.io.ByteArrayOutputStream
import java.lang.Math
let out: Any := nil
let r: Integer := 0
with \"java\" do
  let buffer := create ByteArrayOutputStream
  buffer.write(65i32)
  buffer.write(66i16)
  out := buffer.size()
  r := Math.abs(-5i32)
end
print(out)
print(r)"))))

;; ---------------------------------------------------------------------------
;; Formatter
;; ---------------------------------------------------------------------------

(deftest formatter-keeps-literal-suffixes
  (let [src "class T\n  feature\n    run() do\n      let a: Integer16 := -5i16\n      let b: Integer32 := 6i32\n      let c: Byte := 7u8\n      print(a)\n    end\nend\n"
        formatted (fmt/format-code src)]
    (is (str/includes? formatted "6i32"))
    (is (str/includes? formatted "7u8"))
    (is (str/includes? formatted "-5i16"))
    (is (some? (p/ast formatted)) "the formatted program still parses")))
