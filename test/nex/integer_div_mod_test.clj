(ns nex.integer-div-mod-test
  "Integer `/` and `%` on both backends. Compiled code calls the runtime's
   div-long/mod-long unboxed when both operands are unboxed Integers
   (nex.compiler.jvm.emit/emit-prim-long-arith!); these check that keeps
   truncating semantics and Nex's errors, and that other operands still work."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "integer_div_mod" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})
            interpreted (run {:interpret? true})]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest truncating-division-and-remainder-test
  (testing "/ truncates toward zero and % takes the dividend's sign"
    (is (= ["\"3 1\"" "\"-3 -1\"" "\"-3 1\"" "\"3 -1\"" "\"0 0\"" "0"]
           (both "function show(a, b: Integer) do
  print((a / b).to_string + \" \" + (a % b).to_string)
end
show(7, 2)
show(-7, 2)
show(7, -2)
show(-7, -2)
show(0, 5)
let smallest := -9223372036854775807 - 1
print(smallest % -1)
")))))

(deftest division-errors-test
  (testing "a zero divisor and MIN / -1 raise Nex's errors"
    (is (= ["\"Division_by_Zero\"" "\"Division_by_Zero\"" "\"Arithmetic_Overflow\""]
           (both "function classify(op: String, a, b: Integer): String do
  if op = \"/\" then result := (a / b).to_string else result := (a % b).to_string end
rescue
  if convert exception to d: Division_by_Zero then
    result := \"Division_by_Zero\"
  elseif convert exception to o: Arithmetic_Overflow then
    result := \"Arithmetic_Overflow\"
  else
    result := \"other\"
  end
end
print(classify(\"/\", 5, 0))
print(classify(\"%\", 5, 0))
print(classify(\"/\", -9223372036854775807 - 1, -1))
")))))

(deftest boxed-operands-test
  (testing "operands that aren't unboxed Integers still divide"
    (is (= ["3" "2"]
           (both "let xs: Array[Integer] := [17, 5]
print(xs.get(0) / xs.get(1))
print(xs.get(0) % xs.get(1) % 3)
")))))
