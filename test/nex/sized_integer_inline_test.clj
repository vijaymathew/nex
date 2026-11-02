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
      (let [err (java.io.StringWriter.)
            out (binding [*err* err]
                  (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?})))]
        ;; A compiled program that fails to compile or link is run by the
        ;; interpreter instead, which would make the comparison vacuous.
        (is (not (str/includes? (str err) "falling back")) (str err))
        (str/split-lines (str/trim-newline out)))
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

(deftest stored-as-jvm-ints-test
  (testing "fields, constants, Any, generics, collections, closures and contracts"
    (let [lines (both "class Cell
  create
    make(b: Byte, s: Integer16, w: Integer32) do
      this.b := b
      this.s := s
      this.w := w
    end
  feature
    b: Byte
    s: Integer16
    w: Integer32
    Top: Byte = 250u8
    Low: Integer16 = -32768i16
    copy_from(o: Cell) do
      -- Another object's fields: read by reflection.
      b := o.b
      s := o.s
      w := o.w
    end
    bump(n: Byte)
      do
        b := b.bitwise_xor(n)
      ensure
        changed: b /= old b or n = 0u8
      end
  invariant
    small: w.to_integer() < 3000000000
end
class Box[T]
  create
    make(v: T) do this.item := v end
  feature
    item: T
end
function twice(w: Integer32): Integer32 do
  result := (w.to_integer() * 2).to_integer32()
end
function maybe(flag: Boolean): ?Integer16 do
  if flag then result := 7i16 end
end
let c := create Cell.make(200u8, -300i16, 2000000000i32)
print(c.b)
print(c.s)
print(c.w)
print(Cell.Top)
print(Cell.Low)
c.bump(255u8)
print(c.b)
let other := create Cell.make(1u8, 2i16, 3i32)
other.copy_from(c)
print(other.b)
print(other.s)
print(other.w)
print(type_of(other.b))
if convert c.clone to d: Cell then
  d.bump(1u8)
  print(c.b)
  print(d.b)
end
print(c = create Cell.make(55u8, -300i16, 2000000000i32))
let cells: Set[Cell] := #{c, create Cell.make(55u8, -300i16, 2000000000i32)}
print(cells.size)
let a: Any := c.w
print(type_of(a))
if convert a to back: Integer32 then print(back.to_integer() + 1) end
let bx := create Box[Integer16].make(-5i16)
print(bx.item)
print(type_of(bx.item))
let ws: Array[Integer32] := [1i32, -2i32, 2147483647i32]
ws.add(c.w)
print(ws)
print(ws.get(2).to_integer() + 1)
let m: Map[Byte, String] := {200u8: \"x\"}
print(m.get(c.b.bitwise_xor(255u8)))
let total: Integer32 := 0i32
[1i32, 2i32, 3i32].map(fn (x: Integer32): Integer32 do
  total := (total + x).to_integer32()
  result := x
end)
print(total)
print(twice(-7i32))
let o := maybe(true)
if o /= nil then print(o.to_integer() + 1) end
print(maybe(false))
across [9u8, 255u8] as v do print(v.to_integer()) end")]
      (is (= "200" (first lines)))
      (is (some #{"\"Byte\""} lines))
      (is (some #{"\"Integer32\""} lines))
      (is (some #{"\"Integer16\""} lines)))))

(deftest typed-routine-calls-test
  (testing "Integer16/Integer32 pass unboxed through routine calls, overrides and closures"
    (is (= ["-32768" "30" "-6" "200" "\"Integer16\"" "2147483647" "10"]
           (both "deferred class Shape
  feature
    scale(k: Integer16): Integer32 deferred
end
class Square inherit Shape
  create
    make(s: Integer32) do side := s end
  feature
    side: Integer32
    scale(k: Integer16): Integer32 do
      result := (side.to_integer() * k.to_integer()).to_integer32()
    end
end
class Twice inherit Shape
  create
    make(s: Integer32) do side := s end
  feature
    side: Integer32
    scale(k: Integer16): Integer32 do
      result := (2 * side.to_integer() * k.to_integer()).to_integer32()
    end
end
function low(): Integer16 do result := -32768i16 end
function echo(b: Byte): Byte do result := b end
print(low())
let shapes: Array[Shape] := [create Square.make(10i32), create Twice.make(-1i32)]
print(shapes.get(0).scale(3i16))
print(shapes.get(1).scale(3i16))
print(echo(200u8))
let f := fn (x: Integer16): Integer16 do result := x end
print(type_of(f(4i16)))
print(create Square.make(2147483647i32).scale(1i16))
print(shapes.get(0).scale(1i16).bitwise_and(15i32))")))))

(defn- method-interface-calls
  "The interfaces method MNAME of the program class compiled from CODE calls
   through (invokeinterface owners)."
  [code mname]
  (let [{:keys [program-class classes]} (file/compile-ast "sized_inline.nex" (p/ast code) {})
        owners (atom [])]
    (.accept (org.objectweb.asm.ClassReader. ^bytes (get classes program-class))
             (proxy [org.objectweb.asm.ClassVisitor] [org.objectweb.asm.Opcodes/ASM9]
               (visitMethod [_access name _desc _sig _exceptions]
                 (when (= name mname)
                   (proxy [org.objectweb.asm.MethodVisitor] [org.objectweb.asm.Opcodes/ASM9]
                     (visitMethodInsn [_opcode owner _name _desc _itf]
                       (swap! owners conj owner))))))
             0)
    @owners))

(deftest sized-locals-are-ints-test
  (let [calls (method-interface-calls "function hash(n: Integer): Integer32 do
  let h: Integer32 := 17i32
  from let i := 0 until i = n do
    h := h.bitwise_xor(i.bitwise_and(255).to_integer32()).bitwise_rotate_left(5)
    i := i + 1
  end
  result := h
end
print(hash(10))" "__method_hash$arity1$t")]
    (testing "a loop on an Integer32 local neither wraps nor unwraps one"
      (is (seq calls))
      (is (not (some #{"clojure/lang/IFn$LO" "clojure/lang/IFn$OL"} calls)))
      (testing "only the range check of to_integer32"
        (is (some #{"clojure/lang/IFn$LL"} calls))))))

(deftest byte-array-access-test
  (testing "Byte_Array get/set/length are array instructions, errors unchanged"
    (is (= ["256" "0" "200" "255" "32640" "\"raised: Byte_Array index out of range: 256 (length 256)\""
            "\"raised: Byte_Array index out of range: -1 (length 256)\"" "7"]
           (both "intern data/Byte_Array
function try_get(a: Byte_Array, i: Integer) do
  do
    print(a.get(i))
  rescue
    print(\"raised: \" + exception.to_string)
  end
end
function try_set(a: Byte_Array, i: Integer) do
  do
    a.set(i, 1u8)
  rescue
    print(\"raised: \" + exception.to_string)
  end
end
let a := create Byte_Array.make(256)
from let i := 0 until i = a.length do
  a.set(i, i.to_byte())
  i := i + 1
end
print(a.length)
print(a.get(0))
print(a.get(200))
print(a.get(255))
let total := 0
from let i := 0 until i = a.length do
  total := total + a.get(i).to_integer()
  i := i + 1
end
print(total)
try_get(a, 256)
try_set(a, -1)
let b := create Byte_Array.from_array([7u8])
print(b.get(0))"))))
  (testing "get, set and length compile to baload, bastore and arraylength"
    (let [{:keys [classes]} (file/compile-ast "sized_inline.nex"
                                              (p/ast "intern data/Byte_Array
let a := create Byte_Array.make(4)
a.set(1, 9u8)
print(a.get(1) = 9u8 and a.length = 4)")
                                              {})
          [_ bytes] (first (filter #(str/ends-with? (key %) ".Byte_Array") classes))
          ops (atom {})]
      (.accept (org.objectweb.asm.ClassReader. ^bytes bytes)
               (proxy [org.objectweb.asm.ClassVisitor] [org.objectweb.asm.Opcodes/ASM9]
                 (visitMethod [_access name _desc _sig _exceptions]
                   (proxy [org.objectweb.asm.MethodVisitor] [org.objectweb.asm.Opcodes/ASM9]
                     (visitInsn [opcode]
                       (swap! ops update name (fnil conj #{}) opcode)))))
               0)
      (is (contains? (get @ops "__method_get$arity1$t") org.objectweb.asm.Opcodes/BALOAD))
      (is (contains? (get @ops "__method_set$arity2$t") org.objectweb.asm.Opcodes/BASTORE))
      (is (contains? (get @ops "__method_length$arity0$t") org.objectweb.asm.Opcodes/ARRAYLENGTH)))))
