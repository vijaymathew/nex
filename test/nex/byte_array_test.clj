(ns nex.byte-array-test
  "data/Byte_Array: a fixed-size mutable byte sequence stored in a real Java
   byte[] (lib/data/byte_array.nex). The storage is Java's signed byte; the
   interface is unsigned, so the tests pin the values above 127.

   Behaviour is asserted on both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.types.runtime :as rt]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- type-errors
  "The type errors CODE produces, or nil when it type-checks. Goes through
   nex.eval/eval-file (as `nex <file>` does) so `intern` is resolved; the
   snippets passed here are harmless to run."
  [code]
  (let [f (java.io.File/createTempFile "byte_array_tc" ".nex")]
    (try
      (spit f code)
      (try (with-out-str (e/eval-file (.getPath f) {:interpret? true}))
           nil
           (catch clojure.lang.ExceptionInfo ex
             (when-let [errors (:errors (ex-data ex))]
               (mapv str errors))))
      (finally (.delete f)))))

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
;; Runtime functions
;; ---------------------------------------------------------------------------

(deftest storage-is-a-real-java-byte-array
  (let [a (rt/byte-array-make 3)]
    (is (bytes? a))
    (is (= 3 (alength ^bytes a)))
    (rt/byte-array-set! a 0 (rt/->nex-byte 200))
    (is (= -56 (aget ^bytes a 0)) "the storage holds Java's signed byte")
    (is (= 200 (rt/byte-array-get a 0)) "the interface reads it back unsigned")
    (is (rt/nex-byte? (rt/byte-array-get a 0)))
    (is (= [0 0] (mapv #(bit-and % 0xFF) (rt/byte-array-slice a 1 3))))
    (is (not (identical? a (rt/byte-array-from-java a))) "from_java copies")
    (is (thrown? clojure.lang.ExceptionInfo (rt/byte-array-from-java [1 2])))
    (is (thrown? clojure.lang.ExceptionInfo (rt/byte-array-make -1)))
    (is (thrown? clojure.lang.ExceptionInfo (rt/byte-array-get a 3)))
    (is (thrown? clojure.lang.ExceptionInfo (rt/byte-array-get a -1)))
    (is (thrown? clojure.lang.ExceptionInfo (rt/byte-array-slice a 2 1)))
    (is (thrown? clojure.lang.ExceptionInfo (rt/byte-array-slice a 0 4)))))

;; ---------------------------------------------------------------------------
;; Typechecker
;; ---------------------------------------------------------------------------

(deftest byte-array-is-byte-typed
  (is (nil? (type-errors "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.make(2)
let x: Byte := b.get(0)
b.set(1, 7u8)
let n: Integer := b.length()
let a: Array[Byte] := b.to_array()
let s: Byte_Array := b.slice(0, 1)")))
  (is (some? (type-errors "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.make(2)
b.set(0, 7)")) "set takes a Byte, not an Integer")
  (is (some? (type-errors "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.make(2)
let x: Integer := b.get(0)")) "get returns a Byte")
  (is (some? (type-errors "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.from_array([1, 2])")) "from_array takes an Array[Byte]"))

;; ---------------------------------------------------------------------------
;; Behaviour on both backends
;; ---------------------------------------------------------------------------

(deftest make-get-set-length
  (is (= ["4" "0" "200" "255" "65" "Byte_Array([200, 255, 0, 65])"]
         (both "intern data/Byte_Array
let buf: Byte_Array := create Byte_Array.make(4)
print(buf.length())
print(buf.get(0))
buf.set(0, 200u8)
buf.set(1, 255u8)
buf.set(3, 65u8)
print(buf.get(0))
print(buf.get(1))
print(buf.get(3))
print(buf)"))))

(deftest values-above-127-round-trip
  (is (= ["[0, 1, 127, 128, 200, 255]" "true"]
         (both "intern data/Byte_Array
let src: Array[Byte] := [0u8, 1u8, 127u8, 128u8, 200u8, 255u8]
let buf: Byte_Array := create Byte_Array.from_array(src)
print(buf.to_array())
print(buf.to_array() = src)"))))

(deftest bounds-and-size-errors-raise
  (is (raises-on-both? "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.make(2)
print(b.get(2))" "index out of range"))
  (is (raises-on-both? "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.make(2)
b.set(-1, 1u8)" "index out of range"))
  (is (raises-on-both? "intern data/Byte_Array
print(create Byte_Array.make(-1))" "non-negative"))
  (is (raises-on-both? "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.make(2)
print(b.slice(1, 3))" "outside"))
  (is (raises-on-both? "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.make(2)
print(b.slice(2, 1))" "outside"))
  (is (raises-on-both? "intern data/Byte_Array
print(create Byte_Array.from_java(\"not an array\"))" "byte[]")))

(deftest slice-copies
  (is (= ["Byte_Array([0, 65])" "0" "9" "Byte_Array([])" "Byte_Array([200, 0, 0, 65])"]
         (both "intern data/Byte_Array
let buf: Byte_Array := create Byte_Array.make(4)
buf.set(0, 200u8)
buf.set(3, 65u8)
let s: Byte_Array := buf.slice(2, 4)
print(s)
s.set(0, 9u8)
print(buf.get(2))
print(s.get(0))
print(buf.slice(1, 1))
print(buf)"))))

(deftest constructors-copy-their-input
  (is (= ["[1, 2, 3]" "[9, 2, 3]" "[9, 2, 3]"]
         (both "intern data/Byte_Array
let src: Array[Byte] := [1u8, 2u8, 3u8]
let buf: Byte_Array := create Byte_Array.from_array(src)
src.set(0, 9u8)
print(buf.to_array())
buf.set(0, 9u8)
print(buf.to_array())
print(src)"))))

(deftest equality-and-hash-are-by-contents
  (is (= ["true" "false" "true" "false"]
         (both "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.from_array(\"héy\".to_bytes())
let b: Byte_Array := create Byte_Array.from_array(\"héy\".to_bytes())
let c: Byte_Array := create Byte_Array.make(4)
print(a = b)
print(a = c)
print(a.hash() = b.hash())
print(a.equals(\"héy\"))"))))

(deftest works-with-strings
  (is (= ["\"héy\""]
         (both "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.from_array(\"héy\".to_bytes())
print(create String.from_bytes(a.to_array()))"))))

(deftest byte-array-in-classes-and-collections
  (is (= ["3" "\"x\""]
         (both "intern data/Byte_Array
class Packet
  create
    make(data: Byte_Array) do payload := data end
  feature
    payload: Byte_Array
    size(): Integer do result := payload.length() end
end
let p := create Packet.make(create Byte_Array.make(3))
print(p.size())
let m: Map[String, Byte_Array] := {}
m.put(\"x\", p.payload)
print(\"x\")"))))

;; ---------------------------------------------------------------------------
;; fill / copy / concat / copy_into
;; ---------------------------------------------------------------------------

(deftest fill-copy-concat-copy-into
  (is (= ["\"ffffffff\"" "\"68c3a979\"" "\"68c3a9790102\"" "\"68c3a979\""
          "Byte_Array([0, 0, 0, 1, 2, 0])" "Byte_Array([9, 9, 3, 4, 9, 9])"]
         (both "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.from_array(\"héy\".to_bytes())
let b: Byte_Array := create Byte_Array.from_array([1u8, 2u8])
let d: Byte_Array := a.copy()
d.fill(255u8)
print(d.to_hex())
print(a.to_hex())
print(a.concat(b).to_hex())
print(a.to_hex())
let t: Byte_Array := create Byte_Array.make(6)
b.copy_into(t, 3)
print(t)
let u: Byte_Array := create Byte_Array.from_array([9u8, 9u8, 3u8, 4u8, 9u8, 9u8])
print(u)"))))

(deftest copy-and-concat-do-not-alias
  (is (= ["0" "0" "200" "Byte_Array([200, 0, 0, 0])"]
         (both "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.make(2)
let c: Byte_Array := a.copy()
c.set(0, 200u8)
print(a.get(0))
let cc: Byte_Array := a.concat(c)
cc.set(0, 7u8)
print(a.get(0))
print(c.get(0))
print(c.concat(a))"))))

(deftest concat-with-empty-arrays
  (is (= ["Byte_Array([])" "Byte_Array([5])" "Byte_Array([5])" "0"]
         (both "intern data/Byte_Array
let e: Byte_Array := create Byte_Array.make(0)
let f: Byte_Array := create Byte_Array.from_array([5u8])
print(e.concat(e))
print(e.concat(f))
print(f.concat(e))
print(e.copy().length())"))))

(deftest copy-into-checks-the-range
  (is (= ["Byte_Array([1, 2])" "Byte_Array([0, 0, 1, 2])"]
         (both "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.from_array([1u8, 2u8])
let t: Byte_Array := create Byte_Array.make(2)
b.copy_into(t, 0)
print(t)
let w: Byte_Array := create Byte_Array.make(4)
b.copy_into(w, 2)
print(w)")))
  (is (raises-on-both? "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.from_array([1u8, 2u8])
b.copy_into(create Byte_Array.make(3), 2)" "do not fit"))
  (is (raises-on-both? "intern data/Byte_Array
let b: Byte_Array := create Byte_Array.from_array([1u8, 2u8])
b.copy_into(create Byte_Array.make(3), -1)" "do not fit")))

(deftest fill-uses-the-low-eight-bits
  (is (= ["\"00000000\"" "\"80808080\"" "128"]
         (both "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.make(4)
print(a.to_hex())
a.fill(128u8)
print(a.to_hex())
print(a.get(3))"))))

;; ---------------------------------------------------------------------------
;; index_of / contains
;; ---------------------------------------------------------------------------

(deftest index-of-and-contains
  (is (= ["1" "-1" "0" "2" "true" "false" "-1"]
         (both "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.from_array([9u8, 200u8, 200u8, 0u8])
print(a.index_of(200u8))
print(a.index_of(7u8))
print(a.index_of(9u8))
print(a.index_of(0u8) - 1)
print(a.contains(200u8))
print(a.contains(199u8))
print(create Byte_Array.make(0).index_of(0u8))"))))

;; ---------------------------------------------------------------------------
;; compare
;; ---------------------------------------------------------------------------

(deftest compare-is-lexicographic-and-unsigned
  (is (= ["-1" "1" "0" "-1" "1" "1" "-1"]
         (both "intern data/Byte_Array
let lo: Byte_Array := create Byte_Array.from_array([1u8, 2u8])
let hi: Byte_Array := create Byte_Array.from_array([1u8, 200u8])
let big: Byte_Array := create Byte_Array.from_array([255u8])
let small: Byte_Array := create Byte_Array.from_array([1u8, 2u8, 3u8])
print(lo.compare(hi))
print(hi.compare(lo))
print(lo.compare(lo.copy()))
print(lo.compare(small))
print(small.compare(lo))
print(big.compare(small))
print(create Byte_Array.make(0).compare(lo))"))))

(deftest byte-array-is-comparable
  (testing "the ordering operators and sort work on Byte_Arrays"
    (is (= ["true" "false" "true" "true" "[Byte_Array([0]), Byte_Array([1, 2]), Byte_Array([1, 200]), Byte_Array([255])]"]
           (both "intern data/Byte_Array
let lo: Byte_Array := create Byte_Array.from_array([1u8, 2u8])
let hi: Byte_Array := create Byte_Array.from_array([1u8, 200u8])
print(lo < hi)
print(lo > hi)
print(lo <= lo.copy())
print(hi >= lo)
let xs: Array[Byte_Array] := [create Byte_Array.from_array([255u8]), hi, lo, create Byte_Array.make(1)]
print(xs.sort())")))))

(deftest compare-rejects-other-types
  (is (raises-on-both? "intern data/Byte_Array
print(create Byte_Array.make(1).compare(5))" "requires a Byte_Array")))

;; ---------------------------------------------------------------------------
;; to_hex / to_utf8_string
;; ---------------------------------------------------------------------------

(deftest to-hex
  (is (= ["\"\"" "\"00017f80ff\"" "\"c3a9\""]
         (both "intern data/Byte_Array
print(create Byte_Array.make(0).to_hex())
print((create Byte_Array.from_array([0u8, 1u8, 127u8, 128u8, 255u8])).to_hex())
print((create Byte_Array.from_array(\"é\".to_bytes())).to_hex())"))))

(deftest to-utf8-string
  (is (= ["\"héllo wörld ✓\"" "\"\"" "true"]
         (both "intern data/Byte_Array
let text: String := \"héllo wörld ✓\"
let b: Byte_Array := create Byte_Array.from_array(text.to_bytes())
print(b.to_utf8_string())
print(create Byte_Array.make(0).to_utf8_string())
print(b.to_utf8_string() = text)")))
  (is (raises-on-both? "intern data/Byte_Array
print(create Byte_Array.from_array([255u8]).to_utf8_string())" "not valid UTF-8"))
  (is (raises-on-both? "intern data/Byte_Array
print(create Byte_Array.from_array([195u8]).to_utf8_string())" "not valid UTF-8")))

(deftest to-string-shows-the-bytes
  (is (= ["\"Byte_Array([1, 200])\"" "\"x=Byte_Array([])\""]
         (both "intern data/Byte_Array
print(create Byte_Array.from_array([1u8, 200u8]).to_string())
print(\"x=\" + create Byte_Array.make(0))"))))

;; ---------------------------------------------------------------------------
;; cursor / across
;; ---------------------------------------------------------------------------

(deftest across-yields-bytes
  (testing "across over a Byte_Array yields Byte items, so no convert is needed"
    (is (= ["#h" "#i" "209" "2"]
           (both "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.from_array(\"hi\".to_bytes())
let total: Integer := 0
let count: Integer := 0
across a as b do
  print(b.to_char())
  total := total + b
  count := count + 1
end
print(total)
print(count)")))
    (is (nil? (type-errors "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.make(1)
across a as b do
  let x: Byte := b
end")))
    (is (some? (type-errors "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.make(1)
across a as b do
  let x: Integer := b
end")) "the item is a Byte, not an Integer")))

(deftest across-edge-cases
  (is (= ["0" "[65, 65, 65]"]
         (both "intern data/Byte_Array
let n: Integer := 0
across create Byte_Array.make(0) as b do
  n := n + 1
end
print(n)
let a: Byte_Array := create Byte_Array.make(3)
a.fill(65u8)
let seen: Array[Byte] := []
across a as b do
  seen.add(b)
end
print(seen)"))))

(deftest explicit-cursor-protocol
  (is (= ["65" "66" "true"]
         (both "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.from_array(\"AB\".to_bytes())
let c := a.cursor()
from c.start() until c.at_end() do
  print(c.item())
  c.next()
end
print(c.at_end())"))))

(deftest across-sees-later-writes-and-nested-loops
  (is (= ["3" "9"]
         (both "intern data/Byte_Array
let a: Byte_Array := create Byte_Array.from_array([1u8, 1u8, 1u8])
let sum: Integer := 0
across a as x do
  sum := sum + x
end
print(sum)
let pairs: Integer := 0
across a as x do
  across a as y do
    pairs := pairs + 1
  end
end
print(pairs)"))))

(deftest byte-array-cursor-inside-a-class
  (is (= ["6"]
         (both "intern data/Byte_Array
class Summer
  feature
    total(data: Byte_Array): Integer do
      result := 0
      across data as b do
        result := result + b
      end
    end
end
let s := create Summer
print(s.total(create Byte_Array.from_array([1u8, 2u8, 3u8])))"))))

;; ---------------------------------------------------------------------------
;; Java interop
;; ---------------------------------------------------------------------------

(deftest to-java-hands-a-real-byte-array-to-java
  (testing "a Byte_Array goes to MessageDigest without the ByteArrayOutputStream bridge"
    (is (= ["[186, 120, 22, 191, 143, 1, 207, 234]" "32"]
           (both "intern data/Byte_Array
import java.security.MessageDigest

let input: Byte_Array := create Byte_Array.from_array(\"abc\".to_bytes())
let digest: Any := nil
with \"java\" do
  let md := MessageDigest.getInstance(\"SHA-256\")
  digest := md.digest(input.to_java())
end
let out: Byte_Array := create Byte_Array.from_java(digest)
print(out.slice(0, 8).to_array())
print(out.length())")))))

(deftest from-java-copies-a-byte-array
  (is (= ["[104, 105]" "[104, 105]"]
         (both "intern data/Byte_Array
let raw: Any := nil
with \"java\" do
  let s := \"hi\"
  raw := s.getBytes()
end
let a: Byte_Array := create Byte_Array.from_java(raw)
print(a.to_array())
a.set(0, 0u8)
let b: Byte_Array := create Byte_Array.from_java(raw)
print(b.to_array())"))))
