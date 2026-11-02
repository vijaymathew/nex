(ns nex.sized-integer-inline-test
  "In compiled code, operations on Byte, Integer16 and Integer32 values are
   JVM instructions on the value as a long (nex.compiler.jvm.emit/
   emit-sized-intrinsic!): unwrapping one for arithmetic, literals (which are
   constants), conversions, and the bitwise, min and max methods, when a shift
   count or bit index is a constant. They used to be a call into the runtime
   each, through a Var, with every long boxed; a loop on Integer32 values was
   about 30x slower than the same loop on Integer.

   Each case here runs on both backends and the outputs must agree, the
   interpreter's methods (nex.types.builtins) being the reference: edge
   values of each type, every shift count from 0 to past the width, and the
   cases the compiled code leaves to the runtime (a count that is not a
   constant, a negative or out-of-range one, an out-of-range conversion),
   which must raise the same errors."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.compiler.jvm.file :as file]
            [nex.eval :as e]
            [nex.parser :as p]))

(defn- run
  [code interpret?]
  (let [f (java.io.File/createTempFile "sized_inline" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))))
      (finally (.delete f)))))

(defn- both
  "CODE's printed lines, asserted the same on both backends, and returned."
  [code]
  (let [compiled (run code false)]
    (is (= (run code true) compiled) "compiled and interpreted output must agree")
    compiled))

(def ^:private types
  [{:name "Byte" :suffix "u8" :width 8
    :values ["0u8" "1u8" "127u8" "128u8" "200u8" "255u8"]}
   {:name "Integer16" :suffix "i16" :width 16
    :values ["-32768i16" "-1i16" "0i16" "1i16" "12345i16" "32767i16"]}
   {:name "Integer32" :suffix "i32" :width 32
    :values ["-2147483648i32" "-1i32" "0i32" "1i32" "123456789i32" "2147483647i32"]}])

(defn- try-fn
  "A function printing what EXPR (over x and y, of type T) gives, or the
   message of what it raises."
  [fname t expr]
  (str "function " fname "(x, y: " t ") do\n"
       "  do\n"
       "    print(" expr ")\n"
       "  rescue\n"
       "    print(\"raised: \" + exception.to_string)\n"
       "  end\n"
       "end\n"))

(defn- method-exprs
  [{:keys [name width]}]
  (let [counts (distinct [0 1 3 (dec width) width (+ width 5)])]
    (concat
     (when-not (= "Byte" name) ["x.to_byte()"])
     ["x.bitwise_not()" "x.bitwise_and(y)" "x.bitwise_or(y)" "x.bitwise_xor(y)"
      "x.min(y)" "x.max(y)" "x.to_integer()" "x + y" "x * y" "x < y" "x = y"
      "x.to_integer16()" "x.to_integer32()"
      ;; Chained: the inner result is unwrapped without being wrapped.
      "x.bitwise_xor(y).bitwise_rotate_left(1).to_integer()"
      "x.bitwise_and(y).bitwise_or(x).bitwise_not().bitwise_left_shift(2)"
      ;; Counts the compiled code leaves to the runtime.
      "x.bitwise_left_shift(-1)" "x.bitwise_is_set(-1)"
      (str "x.bitwise_is_set(" width ")") (str "x.bitwise_set(" width ")")]
     (for [m ["bitwise_left_shift" "bitwise_right_shift" "bitwise_logical_right_shift"
              "bitwise_rotate_left" "bitwise_rotate_right"]
           k counts]
       (str "x." m "(" k ")"))
     ["x.bitwise_rotate_left(-3)" "x.bitwise_rotate_right(-3)"]
     (for [m ["bitwise_is_set" "bitwise_set" "bitwise_unset"]
           k [0 1 (dec width)]]
       (str "x." m "(" k ")")))))

(defn- type-program
  [{:keys [name values] :as t}]
  (let [exprs (method-exprs t)
        fns (map-indexed (fn [i ex] [(str "f" i) ex]) exprs)]
    (str (str/join (map (fn [[fname ex]] (try-fn fname name ex)) fns))
         ;; Values come in through parameters and a local, not as literals,
         ;; so both the literal and the unwrapped-variable paths run.
         "let vs: Array[" name "] := [" (str/join ", " values) "]\n"
         "from let i := 0 until i = vs.length do\n"
         "  from let j := 0 until j = vs.length do\n"
         (str/join (map (fn [[fname _]] (str "    " fname "(vs.get(i), vs.get(j))\n")) fns))
         "    j := j + 1\n"
         "  end\n"
         "  i := i + 1\n"
         "end\n")))

(deftest sized-methods-agree-test
  (doseq [t types]
    (testing (:name t)
      (let [lines (both (type-program t))]
        (is (< 1000 (count lines)))))))

(deftest literals-and-conversions-test
  (testing "literals, defaults, and Integer conversions in and out of range"
    (is (= ["200" "-5" "70000" "0" "0" "0" "255" "-32768" "2147483647"
            "\"raised: Byte value must be in range 0..255, got 256\""
            "\"raised: Integer16 value must be in range -32768..32767, got 32768\""
            "\"raised: Integer32 value must be in range -2147483648..2147483647, got -2147483649\""
            "7" "\"raised: Byte shift count must be non-negative, got -2\""]
           (both "class Holder
  feature
    b: Byte
    s: Integer16
    w: Integer32
end
function conv(n: Integer, kind: Integer) do
  do
    if kind = 8 then print(n.to_byte())
    elseif kind = 16 then print(n.to_integer16())
    else print(n.to_integer32()) end
  rescue
    print(\"raised: \" + exception.to_string)
  end
end
function shift(b: Byte, k: Integer) do
  do
    print(b.bitwise_left_shift(k))
  rescue
    print(\"raised: \" + exception.to_string)
  end
end
print(200u8)
print(-5i16)
print(70000i32)
let h := create Holder
print(h.b)
print(h.s)
print(h.w)
conv(255, 8)
conv(-32768, 16)
conv(2147483647, 32)
conv(256, 8)
conv(32768, 16)
conv(-2147483649, 32)
let k := 1
print(230u8.bitwise_rotate_left(k + 1).bitwise_xor(156u8))
shift(1u8, -2)")))))

(defn- class-strings
  "The constant-pool text of the program class compiled from CODE."
  [code]
  (let [{:keys [program-class classes]} (file/compile-ast "sized_inline.nex" (p/ast code) {})]
    (String. ^bytes (get classes program-class) java.nio.charset.StandardCharsets/ISO_8859_1)))

(deftest operations-are-inline-test
  (let [text (class-strings "function mix(a: Byte, b: Byte, n: Integer): Integer32 do
  let c := a.bitwise_xor(b).bitwise_rotate_left(3).bitwise_and(15u8)
  let w: Integer32 := 31i32
  result := (c.to_integer() + w * 7i32 + n).to_integer32().bitwise_left_shift(1)
end
print(mix(200u8, 7u8, 5))")]
    (testing "no call of the runtime's methods, literal constructor or unwrapper"
      (is (not (str/includes? text "builtin-method-")))
      (is (not (str/includes? text "make-int32")))
      (is (not (str/includes? text "sized->integer"))))
    (testing "wrapping goes through the primitive-typed conversions"
      (is (str/includes? text "long->int32"))
      (is (str/includes? text "clojure/lang/IFn$LO")))))
