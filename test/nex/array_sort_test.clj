(ns nex.array-sort-test
  "Array.sort on both backends. The compiled backend compares Integers,
   Reals, Strings and Chars directly and calls a comparator through a Method
   it looks up once (nex.compiler.jvm.runtime/array-sort); these check that
   gives the order each type's own `compare` does, and that the other
   element types still take the general path."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "array_sort" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})
            interpreted (run {:interpret? true})]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest natural-sort-of-builtin-scalars-test
  (testing "Integers, Reals, Strings and Chars sort by their own compare"
    (is (= ["[-9223372036854775808, -3, 0, 2, 7, 9223372036854775807]"
            "[-2.5, -0.0, 0.0, 1.25, 3.0]"
            "[\"\", \"A\", \"a\", \"ab\", \"b\", \"é\"]"
            "[#A, #Z, #a, #z]"]
           (both "print([7, -3, 9223372036854775807, 0, 2, -9223372036854775807 - 1].sort())
print([3.0, -0.0, 1.25, 0.0, -2.5].sort())
print([\"b\", \"ab\", \"\", \"é\", \"a\", \"A\"].sort())
print([#z, #A, #a, #Z].sort())
")))))

(deftest natural-sort-general-path-test
  (testing "a mix of Integers and Reals, and Bytes, still sort correctly"
    (is (= ["[1, 1.5, 2, 2.5]" "[1, 9, 200]"]
           (both "let xs: Array[Any] := [2.5, 1, 2, 1.5]
print(xs.sort())
let bs: Array[Byte] := [200.to_byte, 1.to_byte, 9.to_byte]
print(bs.sort())
")))))

(deftest comparator-sort-test
  (testing "a Nex comparator orders the elements, and an empty sort calls it never"
    (is (= ["[9, 5, 3, 1]" "[\"kiwi\", \"pear\", \"fig\"]" "[]"]
           (both "print([3, 9, 1, 5].sort(fn (a, b: Integer): Integer do result := b.compare(a) end))
print([\"kiwi\", \"pear\", \"fig\"].sort(fn (a, b: String): Integer do result := b.length.compare(a.length) end))
let empty: Array[Integer] := []
print(empty.sort(fn (a, b: Integer): Integer do result := a.compare(b) end))
")))))
