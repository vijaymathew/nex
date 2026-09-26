(ns nex.byte-type-test
  "The Byte scalar type: an unsigned 8-bit value (0..255), the element type of
   String.to_bytes() and of binary file IO.

   A Byte is a boxed java.lang.Short at runtime (see nex.types.runtime/nex-byte?)
   — the box type is the tag that tells it from an Integer (a Long) so builtin
   methods dispatch correctly on the interpreter, and on the compiled backend
   where it is an object type that arithmetic unwraps to a long.

   Runtime tests assert the two backends agree, since a Byte crosses a
   different boundary on each; they are driven through nex.eval/eval-file, the
   path `nex <file>` takes, so a construct the compiled backend declines would
   surface as a fallback banner rather than passing on interpreter output."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
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

(deftest to-bytes-returns-array-of-byte
  (testing "String.to_bytes() is an Array[Byte], not an Array[Integer]"
    (is (nil? (type-errors "let xs: Array[Byte] := \"cat\".to_bytes()\nlet b: Byte := xs.get(1)")))
    (is (some? (type-errors "let xs: Array[Integer] := \"cat\".to_bytes()"))
        "Array has no variance: Array[Byte] is not an Array[Integer]")))

(deftest byte-does-not-convert-implicitly
  (testing "no implicit Byte <-> Integer conversion in either direction"
    (is (str/includes? (error-text "let b: Byte := (65).to_byte()\nlet i: Integer := b")
                       "Cannot assign Byte to variable 'i' of type Integer"))
    (is (str/includes? (error-text "let i: Integer := 65\nlet b: Byte := i")
                       "Cannot assign Integer to variable 'b' of type Byte"))
    (is (some? (type-errors "function f(n: Integer): Integer do result := n end\nlet b: Byte := (1).to_byte()\nprint(f(b))")))
    (is (some? (type-errors "function f(b: Byte): Byte do result := b end\nprint(f(65))")))))

(deftest plain-integer-literal-is-not-a-byte
  (testing "an unsuffixed integer literal is an Integer even where a Byte is expected"
    (is (some? (type-errors "let b: Byte := 65")))
    (is (some? (type-errors "let xs: Array[Byte] := [65, 66]")))
    (is (nil? (type-errors "let b: Byte := (65).to_byte()")))))

(deftest byte-literal-typechecks
  (testing "a u8-suffixed literal is a Byte"
    (is (nil? (type-errors "let b: Byte := 65u8")))
    (is (nil? (type-errors "let xs: Array[Byte] := [65u8, 0xFFu8, 0b11u8, 0o7u8, 1_0u8]")))
    (is (some? (type-errors "let i: Integer := 65u8")))
    (is (some? (type-errors "let b: Byte := 65u8\nlet c: Byte := b + 1u8"))
        "arithmetic still promotes to Integer")))

(deftest byte-literal-range-is-checked-at-parse-time
  (doseq [src ["256u8" "0x100u8" "1000u8" "99999999999999999999u8"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Byte literal out of range 0\.\.255"
                          (p/ast (str "print(" src ")")))
        src))
  (is (some? (p/ast "print(255u8)")))
  (is (some? (p/ast "print(0u8)"))))

(deftest byte-literal-behaviour
  (is (= ["12" "255" "250" "\"ABC\"" "13" "true" "256" "10" "-5" "#A" "\"twelve\""]
         (both "let b: Byte := 12u8
print(b)
print(0xFFu8)
print(0b101u8.bitwise_not())
let bs: Array[Byte] := [65u8, 66u8, 67u8]
print(create String.from_bytes(bs))
print(b + 1)
print(200u8 > b)
print(255u8.to_integer() + 1)
print(1_0u8)
print(-5u8)
print(0x41u8.to_char())
case b of
  12u8 then print(\"twelve\")
  else print(\"other\")
end"))))

(deftest byte-literal-is-tagged-as-byte
  (testing "a literal Byte dispatches as a Byte, not an Integer"
    (is (= ["\"Byte\"" "60"]
           (both "let a: Any := 195u8
print(type_of(a))
print(195u8.bitwise_not())")))))

(deftest byte-arithmetic-promotes-to-integer
  (testing "arithmetic on a Byte yields an Integer, like Integer/Real mixing yields Real"
    (is (nil? (type-errors "let b: Byte := (65).to_byte()\nlet i: Integer := b + b")))
    (is (nil? (type-errors "let b: Byte := (65).to_byte()\nlet i: Integer := b * 2")))
    (is (nil? (type-errors "let b: Byte := (65).to_byte()\nlet i: Integer := b / b")))
    (is (nil? (type-errors "let b: Byte := (65).to_byte()\nlet i: Integer := -b")))
    (is (nil? (type-errors "let b: Byte := (65).to_byte()\nlet r: Real := b + 1.5")))
    (is (some? (type-errors "let b: Byte := (65).to_byte()\nlet c: Byte := b + b"))
        "the sum is an Integer; narrow it with to_byte()")
    (is (nil? (type-errors "let b: Byte := (65).to_byte()\nlet c: Byte := (b + b).to_byte()")))))

(deftest byte-comparison
  (testing "Byte compares with Byte; mixing with Integer needs an explicit conversion"
    (is (nil? (type-errors "let a: Byte := (1).to_byte()\nlet b: Byte := (2).to_byte()\nprint(a < b)\nprint(a = b)")))
    (is (some? (type-errors "let a: Byte := (1).to_byte()\nprint(a < 2)")))
    (is (some? (type-errors "let a: Byte := (1).to_byte()\nprint(a = 1)")))
    (is (nil? (type-errors "let a: Byte := (1).to_byte()\nprint(a.to_integer() < 2)")))))

(deftest convert-does-not-change-numeric-representation
  (testing "convert Integer to Byte is rejected like Integer to Real"
    (is (str/includes? (error-text "let n: Integer := 5\nif convert n to b: Byte then print(b) end")
                       "convert cannot change numeric representation"))))

(deftest binary-file-io-is-byte-typed
  (testing "binary_file_read/read_all return Array[Byte]; binary_file_write takes one"
    (is (nil? (type-errors "let h := binary_file_open_read(\"x\")\nlet xs: Array[Byte] := binary_file_read_all(h)\nlet ys: Array[Byte] := binary_file_read(h, 4)")))
    (is (nil? (type-errors "let h := binary_file_open_write(\"x\")\nbinary_file_write(h, \"cat\".to_bytes())")))
    (is (some? (type-errors "let h := binary_file_open_write(\"x\")\nlet xs: Array[Integer] := [1, 2]\nbinary_file_write(h, xs)")))))

;; ---------------------------------------------------------------------------
;; Runtime representation
;; ---------------------------------------------------------------------------

(deftest to-bytes-runtime-values
  (testing "to_bytes yields UTF-8 bytes as tagged Byte values"
    (let [ctx (interp/make-context)
          ascii (interp/call-builtin-method ctx "cat" "cat" "to_bytes" [])
          unicode (interp/call-builtin-method ctx "é" "é" "to_bytes" [])]
      (is (= [99 97 116] (vec ascii)))
      (is (= [195 169] (vec unicode)))
      (is (every? rt/nex-byte? ascii))
      (is (every? rt/nex-byte? unicode) "bytes above 127 stay unsigned"))))

(deftest byte-tag-is-distinct-from-integer
  (testing "a Byte is told apart from an Integer at runtime, but is not a Java short"
    (is (rt/nex-byte? (rt/->nex-byte 200)))
    (is (not (rt/nex-byte? 200)))
    (is (not (rt/nex-byte? (byte 5))) "a java.lang.Byte is not a Nex Byte")
    (is (= 200 (rt/->nex-byte 200)))
    (is (thrown? clojure.lang.ExceptionInfo (rt/->nex-byte 256)))
    (is (thrown? clojure.lang.ExceptionInfo (rt/->nex-byte -1)))
    (is (= 7 (rt/java->nex (short 7))))
    (is (not (rt/nex-byte? (rt/java->nex (short 7)))) "a Java short comes back as an Integer")))

;; ---------------------------------------------------------------------------
;; Behaviour on both backends
;; ---------------------------------------------------------------------------

(deftest to-bytes-and-elementwise-use
  (is (= ["[99, 97, 116]" "99" "100"]
         (both "let bytes: Array[Byte] := \"cat\".to_bytes()
print(bytes)
let b: Byte := bytes.get(0)
print(b)
print(b + 1)"))))

(deftest utf8-bytes-are-unsigned
  (is (= ["[195, 169]" "195" "\"c3\""]
         (both "let e: Array[Byte] := \"é\".to_bytes()
print(e)
let x: Byte := e.get(0)
print(x)
print(x.to_hex())"))))

(deftest byte-bitwise-is-eight-bit
  (testing "bitwise methods work on 8 bits and return a Byte"
    (is (= ["60" "0" "134" "30" "true" "195" "3" "192"]
           (both "let x: Byte := (195).to_byte()
print(x.bitwise_not())
print(x.bitwise_and(x.bitwise_not()))
print(x.bitwise_left_shift(1))
print(x.bitwise_rotate_left(3))
print(x.bitwise_is_set(7))
print(x.bitwise_or(x))
print(x.bitwise_right_shift(6))
print(x.bitwise_unset(0).bitwise_unset(1).bitwise_unset(2).bitwise_unset(3).bitwise_unset(4).bitwise_unset(5))")))
    (is (= ["0" "255" "1"]
           (both "let x: Byte := (255).to_byte()
print(x.bitwise_left_shift(8))
print(x.bitwise_right_shift(0))
print(x.bitwise_right_shift(7))")))
    (is (= ["129" "129"]
           (both "let x: Byte := (3).to_byte()
print(x.bitwise_rotate_right(1))
print(x.bitwise_rotate_left(7))")))))

(deftest byte-conversions
  (is (= ["200" "\"c8\"" "\"11001000\"" "\"A\"" "200"]
         (both "let n: Integer := 200
let m: Byte := n.to_byte()
print(m)
print(m.to_string(16))
print(m.to_string(2))
print(\"\" + (65).to_byte().to_char())
print(m.to_integer())")))
  (testing "to_string with an unsupported base raises"
    (is (raises-on-both? "print((5).to_byte().to_string(7))" "base must be"))))

(deftest byte-range-is-enforced
  (is (raises-on-both? "print((256).to_byte())" "0..255"))
  (is (raises-on-both? "print((-1).to_byte())" "0..255"))
  (is (raises-on-both? "let b: Byte := (255).to_byte()\nprint(b.bitwise_set(8))" "0..7"))
  (is (raises-on-both? "let b: Byte := (1).to_byte()\nprint(b.bitwise_left_shift(-1))" "non-negative"))
  (is (= ["0" "255"] (both "print((0).to_byte())\nprint((255).to_byte())"))))

(deftest byte-arithmetic-and-comparison
  (is (= ["400" "-200" "40000" "1" "true" "false" "true" "200"]
         (both "let m: Byte := (200).to_byte()
let two: Byte := (2).to_byte()
print(m * two)
print(-m)
print(m * m)
print(m / m)
print(m > two)
print(m = two)
print(m = m)
print(m.max(two))"))))

(deftest byte-mixes-with-integer-and-real-in-arithmetic
  (is (= ["201" "201.5" "\"v=200\""]
         (both "let m: Byte := (200).to_byte()
print(m + 1)
print(m + 1.5)
print(\"v=\" + m)"))))

(deftest byte-fields-defaults-and-results
  (testing "an unset Byte field or result is 0, like Integer"
    (is (= ["0" "0" "0" "7"]
           (both "class Box
  feature
    v: Byte
    get(): Byte do result := v end
    put(b: Byte) do v := b end
end
function z(): Byte do
end
let bx := create Box
print(bx.get())
print(bx.v)
print(z())
bx.put((7).to_byte())
print(bx.get())")))))

(deftest byte-in-collections
  (is (= ["\"seven\"" "1" "true" "[97, 98, 101, 114, 122]"]
         (both "let m: Map[Byte, String] := {}
let k: Byte := (7).to_byte()
m.put(k, \"seven\")
print(m.get(k))
let s: Set[Byte] := #{k, k}
print(s.size())
let a: Array[Byte] := \"zebra\".to_bytes()
print(a.contains((122).to_byte()))
print(a.sort())"))))

(deftest byte-across-and-sum
  (is (= ["#c" "#a" "#t" "312"]
         (both "let bytes: Array[Byte] := \"cat\".to_bytes()
let total: Integer := 0
across bytes as c do
  print(c.to_char())
  total := total + c
end
print(total)"))))

(deftest byte-through-any-and-convert
  (testing "a Byte keeps its identity through Any; an Integer is not a Byte"
    (is (= ["\"Byte\"" "\"41\"" "\"Integer is not Byte\""]
           (both "let b: Byte := (65).to_byte()
let a: Any := b
print(type_of(a))
if convert a to y: Byte then print(y.to_hex()) end
let n: Any := 65
if convert n to z: Byte then print(\"wrong\") else print(\"Integer is not Byte\") end")))))

(deftest byte-type-is
  (is (= ["true" "false" "false" "true"]
         (both "let a: Any := (65).to_byte()
let n: Any := 65
print(type_is(\"Byte\", a))
print(type_is(\"Integer\", a))
print(type_is(\"Byte\", n))
print(type_is(\"Integer\", n))"))))

(deftest byte-functions-and-generics
  (is (= ["66" "\"Byte\""]
         (both "function next(b: Byte): Byte do
  result := (b + 1).to_byte()
end
function id[T](x: T): T do result := x end
print(next((65).to_byte()))
print(type_of(id((1).to_byte())))"))))

(deftest byte-equals-is-not-integer-equals
  (is (= ["true" "false" "false"]
         (both "let b: Byte := (65).to_byte()
print(b.equals((65).to_byte()))
print(b.equals(65))
print((65).equals(b))"))))

(deftest byte-detachable-closures-generics-and-tasks
  (is (= ["98" "\"09\"" "nil" "#z" "255" "5" "[1, 7]" "true"]
         (both "class Box[T]
  create
    make(v: T) do this.value := v end
  feature
    value: T
end

function first_byte(s: String): ?Byte do
  let bs: Array[Byte] := s.to_bytes()
  if bs.length() > 0 then
    result := bs.get(0)
  end
end

let f := fn(b: Byte): Integer do
  result := b + 1
end
print(f(\"a\".to_bytes().get(0)))

let bx: Box[Byte] := create Box[Byte].make((9).to_byte())
print(bx.value.to_hex())
let nb: ?Byte := first_byte(\"\")
print(nb)
let sb: ?Byte := first_byte(\"z\")
if ?sb as z then print(z.to_char()) end

let g: Function(Byte): Byte := fn(b: Byte): Byte do result := b.bitwise_not() end
print(g((0).to_byte()))

let tsk := spawn do result := (5).to_byte() end
print(tsk.await())
let r: Array[Byte] := []
r.add((1).to_byte())
r.add(r.get(0).bitwise_or((6).to_byte()))
print(r)
print(r.get(1) = (7).to_byte())"))))

;; ---------------------------------------------------------------------------
;; String.from_bytes
;; ---------------------------------------------------------------------------

(deftest string-from-bytes-round-trips
  (is (= ["\"héllo, wörld ✓\"" "true" "0" "\"A\""]
         (both "let text: String := \"héllo, wörld ✓\"
let back: String := create String.from_bytes(text.to_bytes())
print(back)
print(back = text)
print((create String.from_bytes([])).length())
let one: Array[Byte] := []
one.add((65).to_byte())
print(create String.from_bytes(one))"))))

(deftest string-from-bytes-rejects-invalid-utf8
  (testing "malformed input raises instead of being replaced"
    (is (raises-on-both? "let b: Array[Byte] := \"é\".to_bytes()
b.remove(1)
print(create String.from_bytes(b))" "not valid UTF-8"))
    (is (raises-on-both? "let b: Array[Byte] := []
b.add((255).to_byte())
print(create String.from_bytes(b))" "not valid UTF-8"))))

(deftest string-from-bytes-typechecks
  (is (nil? (type-errors "let s: String := create String.from_bytes(\"a\".to_bytes())")))
  (is (str/includes? (error-text "let xs: Array[Integer] := [65]\nprint(create String.from_bytes(xs))")
                     "String.from_bytes expects Array[Byte], got Array[Integer]"))
  (is (str/includes? (error-text "print(create String)") "String.from_bytes"))
  (is (str/includes? (error-text "print(create String.make(\"a\"))") "Constructor not found: String.make"))
  (is (some? (type-errors "print(create String.from_bytes(\"a\".to_bytes(), 1))"))))

;; ---------------------------------------------------------------------------
;; Java interop
;; ---------------------------------------------------------------------------

(deftest byte-crosses-into-java-as-an-integer
  (testing "a Byte passed to a Java int parameter behaves like an Integer"
    (is (= ["3" "[99, 97, 116]"]
           (both "import java.io.ByteArrayOutputStream

let bytes: Array[Byte] := \"cat\".to_bytes()
let out: Any := nil
with \"java\" do
  let buffer := create ByteArrayOutputStream
  let i := 0
  from i := 0 until i >= bytes.length() do
    buffer.write(bytes.get(i))
    i := i + 1
  end
  out := buffer.size()
end
print(out)
print(bytes)")))))

(deftest java-short-return-is-an-integer
  (testing "a Java short is normalized to an Integer rather than read as a Byte"
    (is (= ["-2" "-2"]
           (both "import java.lang.Short

let s: Integer := 0
with \"java\" do
  s := Short.parseShort(\"-2\")
end
print(s)
print(s.abs() * -1)")))))

;; ---------------------------------------------------------------------------
;; Binary file IO
;; ---------------------------------------------------------------------------

(deftest binary-file-roundtrips-bytes-above-127
  (testing "bytes above 127 survive a write/read round trip (they used to raise on write)"
    (let [f (java.io.File/createTempFile "byte_type_io" ".bin")
          path (.replace (.getPath f) "\\" "/")]
      (try
        (is (= ["[195, 169, 65]" "[195, 169]" "[65]" "true"]
               (both (str "let w := binary_file_open_write(\"" path "\")
binary_file_write(w, \"éA\".to_bytes())
binary_file_close(w)
let r := binary_file_open_read(\"" path "\")
let all: Array[Byte] := binary_file_read_all(r)
print(all)
print(binary_file_read(r, 2))
print(binary_file_read(r, 5))
print(all.get(0).equals((195).to_byte()))
binary_file_close(r)"))))
        (is (= [195 169 65] (mapv #(bit-and % 0xFF) (java.nio.file.Files/readAllBytes (.toPath f)))))
        (finally (.delete f))))))
