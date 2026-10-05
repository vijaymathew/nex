(ns nex.string-to-string-test
  "`to_string` on a String is the text itself (Definition B.1: the user-facing
   rendering), and on a Char the character. String had no `to_string` of its
   own and fell back to Any's, which formats a value as print shows it inside a
   collection -- quoted -- so `\"x: \" + s.to_string` read `x: \"abc\"`. The
   compiled backend's Any-typed path (any-to-string, e.g. on a rescued
   `exception`) did the same for a String or Char held in an `Any`."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "string_to_string" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})
            interpreted (run {:interpret? true})]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest string-to-string-is-the-text-test
  (testing "statically a String, held in an Any, and a raised string"
    (is (= ["\"w: abc\"" "\"v: abc\"" "\"r: oops\"" "3"]
           (both "let s: String := \"abc\"
print(\"w: \" + s.to_string)
let a: Any := \"abc\"
print(\"v: \" + a.to_string)
do
  raise \"oops\"
rescue
  print(\"r: \" + exception.to_string)
end
print(s.to_string.length)")))))

(deftest char-to-string-is-the-character-test
  (testing "a Char, statically and held in an Any"
    (is (= ["\"c: q\"" "\"d: q\""]
           (both "let c := #q
print(\"c: \" + c.to_string)
let d: Any := #q
print(\"d: \" + d.to_string)")))))

(deftest other-to_string-renderings-are-unchanged-test
  (testing "numbers and collections render as before, nested strings still quoted"
    (is (= ["\"n: 5\"" "\"xs: [\"a\", \"b\"]\""]
           (both "let n: Any := 5
print(\"n: \" + n.to_string)
let xs: Any := [\"a\", \"b\"]
print(\"xs: \" + xs.to_string)")))))
