(ns nex.real-math-test
  "Real's math methods (floor/ceiling/truncate, sqrt, log, trigonometry,
   clamp, ...) and the Real.PI / Real.E constants, on both backends. Real
   results follow IEEE-754 (NaN or an infinity outside the domain); the
   Integer-returning rounders raise where there is no Integer."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "real_math" ".nex")]
    (try
      (spit f code)
      (let [out (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))]
        (is (not (str/includes? out "falling back to the tree-walking interpreter"))
            (str "compiled backend declined this program:\n" out))
        (str/split-lines (str/trim-newline out)))
      (finally (.delete f)))))

(defn- both
  [& lines]
  (let [code (str/join "\n" lines)
        compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- raises-on-both?
  [code msg]
  (every? (fn [interpret?]
            (let [f (java.io.File/createTempFile "real_math" ".nex")]
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

(deftest constants
  (is (= ["3.141592653589793" "2.718281828459045" "6.283185307179586"]
         (both "print(Real.PI)"
               "print(Real.E)"
               "print(2.0 * Real.PI)")))
  (testing "an unknown constant is a clear error"
    (is (thrown-with-msg? Exception #"Real has no constant TAU"
                          (run-backend "print(Real.TAU)" true)))))

(deftest rounding-to-integer
  (is (= ["2" "-3" "3" "-2" "-2" "2" "5"]
         (both "print(2.7.floor)"
               "print((-2.7).floor)"
               "print(2.2.ceiling)"
               "print((-2.7).ceiling)"
               "print((-2.7).truncate)"
               "print(2.7.truncate)"
               "print(5.0.floor)")))
  (is (raises-on-both? "print((0.0 / 0.0).floor)" "Real.floor: NaN has no Integer value"))
  (is (raises-on-both? "print((1.0 / 0.0).ceiling)" "Real.ceiling: Infinity has no Integer value"))
  (is (raises-on-both? "print(1.0e300.truncate)" "Real.truncate: 1.0E300 has no Integer value")))

(deftest round-raises-where-there-is-no-integer
  ;; `round` was Java's bare Math.round: NaN became 0 and out-of-range values
  ;; saturated. It now raises like floor/ceiling/truncate; ordinary rounding
  ;; (halves up) is unchanged.
  (is (= ["3" "-2" "0" "4" "-4"]
         (both "print(2.5.round)"
               "print((-2.5).round)"
               "print(0.49999999999999994.round)"
               "print(3.6.round)"
               "print((-3.6).round)")))
  (is (raises-on-both? "print((0.0 / 0.0).round)" "Real.round: NaN has no Integer value"))
  (is (raises-on-both? "print((-1.0 / 0.0).round)" "Real.round: -Infinity has no Integer value"))
  (is (raises-on-both? "print(1.0e300.round)" "Real.round: 1.0E300 has no Integer value")))

(deftest powers-and-logs
  (is (= ["4.0" "3.0" "true" "0.0" "3.0" "10.0" "1024.0" "1.4142135623730951"]
         (both "print(16.0.sqrt)"
               "print(27.0.cbrt)"
               "print(1.0.exp = Real.E)"
               "print(1.0.log)"
               "print(1000.0.log10)"
               "print(1024.0.log2)"
               "print(2.0.power(10.0))"
               "print(2.0.power(0.5))"))))

(deftest outside-the-domain-is-ieee
  (is (= ["true" "-Infinity" "true" "true" "Infinity"]
         (both "print((-1.0).sqrt.is_nan)"
               "print(0.0.log)"
               "print((-1.0).log.is_nan)"
               "print(2.0.asin.is_nan)"
               "print(1000.0.exp)"))))

(deftest trigonometry
  (is (= ["1.0" "1.0" "0.0" "true" "true" "true" "true" "180.0" "true"]
         (both "print((Real.PI / 2.0).sin)"
               "print(0.0.cos)"
               "print(0.0.tan)"
               "print(1.0.atan2(1.0) = Real.PI / 4.0)"
               "print((-1.0).atan2(-1.0) = -3.0 * Real.PI / 4.0)"
               "print(1.0.asin = Real.PI / 2.0)"
               "print(180.0.to_radians = Real.PI)"
               "print(Real.PI.to_degrees)"
               "print((1.0.acos - 0.0).abs < 1.0e-12 and (1.0.atan - Real.PI / 4.0).abs < 1.0e-12)"))))

(deftest utilities
  (is (= ["-1.0" "0.0" "1.0" "10.0" "0.0" "5.5" "5.0" "true"]
         (both "print((-3.5).sign)"
               "print(0.0.sign)"
               "print(2.0.sign)"
               "print(15.0.clamp(0.0, 10.0))"
               "print((-1.0).clamp(0.0, 10.0))"
               "print(5.5.clamp(0.0, 10.0))"
               "print(3.0.hypot(4.0))"
               "print((0.0 / 0.0).sign.is_nan)")))
  (is (raises-on-both? "print(1.0.clamp(10.0, 0.0))" "Real.clamp: low 10.0 is not <= high 0.0")))

(deftest methods-are-typed
  (testing "Integer results can be used as Integers, Real results as Reals"
    (is (= ["5" "true"]
           (both "let n: Integer := 2.5.floor + 3"
                 "print(n)"
                 "let r: Real := 2.0.sqrt * 2.0.sqrt"
                 "print((r - 2.0).abs < 1.0e-12)")))))
