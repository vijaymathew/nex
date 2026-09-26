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
