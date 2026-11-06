(ns nex.string-concat-map-test
  "String `+` and Map storage/lookup on both backends. Compiled code calls
   the runtime's string-concat-2 directly, skips the portable-value
   conversion for values statically typed as built-in scalars, and does one
   lookup in Map.get when the value isn't nil; these check none of that
   changes what a program does."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "string_concat_map" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})
            interpreted (run {:interpret? true})]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest string-concat-test
  (testing "String + any operand gives that operand's text"
    (is (= ["\"int 5\"" "\"real 2.5\"" "\"char z\"" "\"bool true\"" "\"obj P(3)\""
            "\"array [1, 2]\"" "\"map {\"a\": 1}\"" "\"5 first\"" "\"abc52.5\""]
           (both "class Point
  create
    make(v: Integer) do
      x := v
    end
feature
  x: Integer
  to_string: String do result := \"P(\" + x.to_string + \")\" end
end
let p := create Point.make(3)
let n := 5
print(\"int \" + n)
print(\"real \" + 2.5)
print(\"char \" + #z)
print(\"bool \" + true)
print(\"obj \" + p)
print(\"array \" + [1, 2])
print(\"map \" + {\"a\": 1})
print(n.to_string + \" first\")
print(\"a\" + \"b\" + \"c\" + n + 2.5)
")))))

(deftest map-storage-and-lookup-test
  (testing "keys built by +, a nested map stored through Any, a stored nil, a missing key"
    (is (= ["5" "{\"nested\": {\"q\": 1}}" "nil"
            "\"raised: Precondition violation: the map has no key \"absent\"; check with has_key first, or use try_get with a default\""]
           (both "let n := 5
let keys: Map[String, Integer] := {}
keys.put(\"k\" + n.to_string, n)
print(keys.get(\"k5\"))
let any: Any := {\"q\": 1}
let holder: Map[String, Any] := {}
holder.put(\"nested\", any)
print(holder)
let nils: Map[String, ?Integer] := {}
nils.put(\"none\", nil)
print(nils.get(\"none\"))
function missing(mm: Map[String, Integer]): String do
  result := mm.get(\"absent\").to_string
rescue
  result := \"raised: \" + exception.to_string
end
print(missing(keys))
")))))
