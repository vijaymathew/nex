(ns nex.random-test
  "math/Random (lib/math/random.nex): seedable pseudo-random numbers over
   java.util.SplittableRandom, plus the generic `shuffle` function.
   Behaviour is asserted on both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "random" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  "Printed output lines of CODE, asserted identical on both backends."
  [& lines]
  (let [code (str/join "\n" (cons "intern math/Random" lines))
        compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- raises-on-both?
  [code msg]
  (every? (fn [interpret?]
            (let [f (java.io.File/createTempFile "random" ".nex")]
              (try
                (spit f (str "intern math/Random\n" code))
                (try (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))
                     false
                     (catch Throwable t
                       (loop [x t]
                         (cond
                           (str/includes? (str (ex-message x)) msg) true
                           (ex-cause x) (recur (ex-cause x))
                           :else false))))
                (finally (.delete f)))))
          [false true]))

(deftest a-seed-repeats-the-sequence
  (let [out (both "let a: Random := create Random.with_seed(42)"
                  "let b: Random := create Random.with_seed(42)"
                  "let c: Random := create Random.with_seed(43)"
                  "let same: Boolean := true"
                  "let differs: Boolean := false"
                  "repeat 100 do"
                  "  let x: Real := a.uniform()"
                  "  if x /= b.uniform() then same := false end"
                  "  if x /= c.uniform() then differs := true end"
                  "end"
                  "print(same)"
                  "print(differs)"
                  ;; Pinned: java.util.SplittableRandom's sequence for seed 42.
                  "print(create Random.with_seed(42).uniform())")]
    (is (= ["true" "true" "0.7415648787718233"] out))))

(deftest numbers-stay-in-range
  (is (= ["true" "true" "true" "true"]
         (both "let r: Random := create Random.with_seed(7)"
               "let ok_u: Boolean := true"
               "let ok_b: Boolean := true"
               "let ok_between: Boolean := true"
               "let seen: Set[Integer] := #{}"
               "repeat 2000 do"
               "  let u: Real := r.uniform()"
               "  if u < 0.0 or u >= 1.0 then ok_u := false end"
               "  let b: Integer := r.below(5)"
               "  if b < 0 or b >= 5 then ok_b := false end"
               "  let d: Integer := r.between(1, 6)"
               "  if d < 1 or d > 6 then ok_between := false end"
               "  seen.add(d)"
               "end"
               "print(ok_u)"
               "print(ok_b)"
               "print(ok_between)"
               "print(seen.size() = 6)"))))

(deftest gaussian-has-the-bell-curve-shape
  (is (= ["true" "true"]
         (both "let r: Random := create Random.with_seed(1)"
               "let n: Integer := 20000"
               "let total: Real := 0.0"
               "let squares: Real := 0.0"
               "repeat n do"
               "  let g: Real := r.gaussian()"
               "  total := total + g"
               "  squares := squares + g * g"
               "end"
               "let mean: Real := total / n.to_real"
               "print(mean.abs < 0.05)"
               "print((squares / n.to_real - mean * mean - 1.0).abs < 0.05)"))))

(deftest shuffle-permutes-in-place
  (is (= ["[1, 2, 3, 4, 5, 6, 7, 8]" "true" "[\"only\"]" "[]"]
         (both "let r: Random := create Random.with_seed(3)"
               "let xs: Array[Integer] := [1, 2, 3, 4, 5, 6, 7, 8]"
               "let before: Array[Integer] := xs.clone()"
               "shuffle(xs, r)"
               "print(xs.clone().sort())"
               "print(xs /= before)"
               "let one: Array[String] := [\"only\"]"
               "shuffle(one, r)"
               "print(one)"
               "let none: Array[Real] := []"
               "shuffle(none, r)"
               "print(none)")))
  (testing "an unseeded generator works too"
    (is (= ["true"]
           (both "let r: Random := create Random.make"
                 "print(r.below(10) < 10)")))))

(deftest contracts
  (is (raises-on-both? "print(create Random.with_seed(1).below(0))" "positive"))
  (is (raises-on-both? "print(create Random.with_seed(1).between(3, 2))" "ordered")))
