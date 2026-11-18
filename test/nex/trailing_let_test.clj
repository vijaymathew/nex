(ns nex.trailing-let-test
  "A compiled program whose last statement is a top-level `let` of an
   Integer, Real or Boolean failed with a void value: the `let` was kept in a
   JVM local, and the program's result was read from the map of top-level
   values, where it never was."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run-lines [code interpret?]
  (let [f (java.io.File/createTempFile "trailing_let" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline
                        (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))))
      (finally (.delete f)))))

(deftest a-program-may-end-with-a-let
  (doseq [interpret? [false true]
          code ["let x: Integer := 3"
                "let x: Real := 1.5"
                "let x: Boolean := true"
                "let x := 3"
                "print(1)\nlet x: Integer := 3\nlet y: Integer := x + 1"]]
    (testing (str (if interpret? "interpreter: " "compiled: ") code)
      (is (some? (run-lines code interpret?)))))
  (testing "a top-level let that a function reads stays in the map"
    (is (= ["3"] (run-lines "let x := 3\nfunction f(): Integer do result := x end\nprint(f())\nlet z := f()"
                            false)))))
