(ns nex.java-interop-results-test
  "Two gaps in using Java objects outside `with \"java\"`, on both backends.

   1. `convert v to x: T` where T is an imported Java class was never true: both
      backends compared Nex type names, and a Java object has none. It is now
      an instanceof test (an interface or superclass matches too, and a Nex
      String is a java.lang.CharSequence).
   2. A Java method's result typed as Any during lowering, so arithmetic on it
      (`xs.size() * 2`) had no JVM type to emit and failed with \"Unsupported
      binary opcode emission\" on the compiled backend. A method whose
      overloads of that arity all return the same primitive (or String) now
      types as the matching Nex type."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "java_interop_results" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) opts))))
      (finally (.delete f)))))

(defn- both
  "Printed output of CODE, asserted identical on both backends."
  [code]
  (let [compiled (run code {})]
    (is (= (run code {:interpret? true}) compiled) "compiled and interpreted output must agree")
    compiled))

(deftest convert-to-java-type-test
  (testing "a class, an interface it implements, one it does not, and nil"
    (is (= ["\"builder 2\"" "\"chars 2\"" "\"not a list\"" "\"list of 1\""
            "\"a String is a CharSequence\"" "\"nil is not\""]
           (both "import java.lang.StringBuilder
import java.lang.CharSequence
import java.util.LinkedList
import java.util.List
let sb: StringBuilder := create StringBuilder
sb.append(\"ab\")
let a: Any := sb
if convert a to b: StringBuilder then print(\"builder \" + b.length().to_string) else print(\"no\") end
if convert a to c: CharSequence then print(\"chars \" + c.length().to_string) else print(\"no\") end
if convert a to d: List then print(\"list?\") else print(\"not a list\") end
let ll: LinkedList := create LinkedList
ll.add(1)
let x: Any := ll
if convert x to e: List then print(\"list of \" + e.size().to_string) end
let s: Any := \"text\"
if convert s to f: CharSequence then print(\"a String is a CharSequence\") end
let n: Any := nil
if convert n to g: StringBuilder then print(\"nil?\") else print(\"nil is not\") end
")))))

(deftest java-method-result-types-test
  (testing "Integer, Boolean, Char, String and Real results, outside and inside `with \"java\"`"
    (is (= ["5" "\"not empty\"" "#h" "\"hi!\"" "1" "0.0" "6"]
           (both "import java.util.LinkedList
import java.lang.StringBuilder
import java.util.Random
let xs: LinkedList := create LinkedList
xs.add(1)
xs.add(2)
print(xs.size() * 2 + 1)
if xs.isEmpty() then print(\"empty\") else print(\"not empty\") end
let sb: StringBuilder := create StringBuilder
sb.append(\"hi\")
print(sb.charAt(0))
print(sb.toString() + \"!\")
print(sb.length() - 1)
let r: Random := create Random.make(42)
print(r.nextDouble() * 0.0)
with \"java\" do
  let u := xs.size() * 3
  print(u)
end
"))))
  (testing "in a function, the original report"
    (is (= ["2"]
           (both "import java.util.LinkedList
function twice(xs: LinkedList): Integer do result := xs.size() * 2 end
let ll: LinkedList := create LinkedList
ll.add(1)
print(twice(ll))
")))))
