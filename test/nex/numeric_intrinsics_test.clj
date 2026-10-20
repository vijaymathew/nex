(ns nex.numeric-intrinsics-test
  "Built-in Integer and Real methods the compiled backend emits as JVM
   instructions (nex.compiler.jvm.emit/numeric-intrinsics) must compute
   exactly what the runtime's methods compute, errors included: the
   interpreter, which runs the runtime's, is the reference."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  [code]
  (let [f (java.io.File/createTempFile "numeric_intrinsics" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})]
        (is (= (run {:interpret? true}) compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest real-math-test
  (is (= ["2.25" "0.0" "NaN" "4.0" "1024.0" "-1.0" "true" "false" "0.7853981633974483" "5.0"]
         (both "let x: Real := -2.25
print(x.abs)
print((0.0 - 0.0).abs)
print(x.sqrt)
print(16.0.sqrt)
print(2.0.power(10.0))
print(x.sign)
print((0.0 / 0.0).is_nan)
print(1.0.is_infinite)
print(1.0.atan2(1.0))
print(3.0.hypot(4.0))"))))

(deftest integer-math-test
  (is (= ["7.0" "3" "30" "7" "\"7\"" "\"ff\""]
         (both "let n: Integer := 7
print(n.to_real)
print(n.min(3))
print(n.max(30))
print(n.to_integer)
print(n.to_string)
print(255.to_string(16))"))))

(deftest rounding-test
  (is (= ["3" "-2" "2" "-3" "3" "-2" "2" "-2" "0" "9000000000000000000" "\"2.5\"" "\"1.0E30\""]
         (both "let a: Real := 2.5
let b: Real := -2.5
let z: Real := 0.0 - 0.0
print(a.round)
print(b.round)
print(a.floor)
print(b.floor)
print(a.ceiling)
print(b.ceiling)
print(a.truncate)
print(b.truncate)
print(z.truncate)
print(9.0e18.floor)
print(a.to_string)
print(1.0e30.to_string)"))))

(deftest rounding-without-an-integer-raises-test
  (is (= ["\"Real.floor: 1.0E30 has no Integer value\""
          "\"Real.round: NaN has no Integer value\""
          "\"Real.round: 1.0E30 has no Integer value\""
          "\"Real.truncate: Infinity has no Integer value\""
          "\"Real.ceiling: NaN has no Integer value\""]
         (both "let big: Real := 1.0e30
let nan: Real := 0.0 / 0.0
do print(big.floor) rescue print(exception.to_string) end
do print(nan.round) rescue print(exception.to_string) end
do print(big.round) rescue print(exception.to_string) end
do print((1.0 / 0.0).truncate) rescue print(exception.to_string) end
do print(nan.ceiling) rescue print(exception.to_string) end"))))
