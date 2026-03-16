(ns nex.char-repl-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.repl :as repl]))

(deftest repl-prints-special-char-literals-symbolically
  (testing "REPL value display uses #space/#tab style rendering for Char results"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "let space: Char := #space")
                   (repl/eval-code ctx "#tab"))]
      (is (str/includes? output "#space"))
      (is (str/includes? output "#tab")))))

(deftest print-and-concatenation-still-use-real-char-values
  (testing "print uses the actual character value rather than the symbolic literal text"
    (let [ctx (repl/init-repl-context)
          output (with-out-str
                   (repl/eval-code ctx "print(\"hello\" + #space)")
                   (repl/eval-code ctx "print(\"a\" + #tab + \"b\")"))]
      (is (str/includes? output "\"hello \""))
      (is (not (str/includes? output "hello#space")))
      (is (not (str/includes? output "a#tabb"))))))
