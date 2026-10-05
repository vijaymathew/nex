(ns nex.repeat-bound-test
  "Regression coverage for `repeat`'s stopping condition. Definition C.2
   translates `repeat e do b end` to a loop that stops when the counter
   reaches `e` with `>=`, re-reading `e` before every pass. The walker used
   `=` instead, so a negative bound, or one the body lowered below the
   counter, was never hit exactly and the loop never ended.

   A regression would hang rather than fail, so every program here carries
   its own guard: it raises once the body has run more passes than the
   correct answer allows, turning a runaway loop into an ordinary failure."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]
            [nex.parser :as p]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "repeat_bound" ".nex")]
    (try
      (spit f code)
      (let [compiled (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
            interpreted (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest repeat-lowers-to-a-greater-or-equal-stop-test
  (testing "the walker's desugared loop stops on counter >= bound"
    (let [loop-node (->> (p/ast "repeat 3 do\n  print(1)\nend")
                         :statements
                         (filter #(= :loop (:type %)))
                         first)]
      (is (= ">=" (get-in loop-node [:until :operator]))))))

(deftest repeat-with-a-negative-bound-runs-zero-times-test
  (testing "a negative bound means no passes, not a counter that never
            lands on it"
    (is (= ["0" "\"done\""]
           (both "let c := 0
repeat 0 - 2 do
  c := c + 1
  if c > 100 then raise \"runaway repeat\" end
end
print(c)
print(\"done\")")))))

(deftest repeat-with-a-shrinking-bound-stops-when-passed-test
  (testing "the bound is re-read each pass; once the counter has passed
            it the loop ends (counter 2 vs bound 1), instead of running on"
    (is (= ["3" "2" "\"done\""]
           (both "let n := 3
let passes := 0
repeat n do
  print(n)
  n := n - 1
  passes := passes + 1
  if passes > 100 then raise \"runaway repeat\" end
end
print(\"done\")")))))

(deftest repeat-with-a-computed-negative-count-test
  (testing "padding to a width shorter than the text adds nothing"
    (is (= ["\"hello\"" "\"hi   \""]
           (both "function pad(text: String, width: Integer): String
do
  result := text
  repeat width - text.length do
    result := result + \" \"
    if result.length > 100 then raise \"runaway repeat\" end
  end
end
print(pad(\"hello\", 3))
print(pad(\"hi\", 5))")))))

(deftest repeat-with-a-fixed-bound-is-unchanged-test
  (testing "with a non-negative bound the body leaves alone, `>=` and `=`
            agree: exactly that many passes, zero for a zero bound"
    (is (= ["3" "0"]
           (both "let a := 0
repeat 3 do
  a := a + 1
end
print(a)
let b := 0
repeat 0 do
  b := b + 1
end
print(b)")))))
