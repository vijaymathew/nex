(ns nex.block-scope-test
  "A `let` inside a block (an `if` branch, a loop body, a `do` block) declares
   a new variable that shadows any outer one of the same name until the block
   ends. Asserted identically on both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [nex.eval :as e]))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "block_scope" ".nex")]
    (try
      (spit f code)
      (str/split-lines
       (str/trim-newline
        (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))))
      (finally (.delete f)))))

(defn- both
  [code]
  (let [compiled (run-backend code false)
        interpreted (run-backend code true)]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- lines [& ls] (str/join "\n" ls))

(deftest let-in-an-if-branch-shadows-the-outer-variable
  (is (= ["2" "1" "3" "1" "4" "1"]
         (both (lines "let x := 1"
                      "if true then"
                      "  let x := 2"
                      "  print(x)"
                      "end"
                      "print(x)"
                      "if false then"
                      "  print(0)"
                      "elseif true then"
                      "  let x := 3"
                      "  print(x)"
                      "end"
                      "print(x)"
                      "if false then"
                      "  print(0)"
                      "else"
                      "  let x := 4"
                      "  print(x)"
                      "end"
                      "print(x)")))))

(deftest let-in-blocks-inside-a-routine-shadows
  (is (= ["20" "10" "10" "10"]
         (both (lines "function f() do"
                      "  let y := 10"
                      "  if true then"
                      "    let y := 20"
                      "    print(y)"
                      "  end"
                      "  print(y)"
                      "  from let i := 0 until i = 1 do"
                      "    let y := 30"
                      "    i := i + 1"
                      "  end"
                      "  print(y)"
                      "  do"
                      "    let y := 40"
                      "  end"
                      "  print(y)"
                      "end"
                      "f()")))))

(deftest assignments-and-result-inside-a-branch-still-reach-out
  (is (= ["5" "6" "7" "-1"]
         (both (lines "let z := 1"
                      "if true then"
                      "  z := 5"
                      "end"
                      "print(z)"
                      "function g(n: Integer): Integer do"
                      "  if n > 0 then"
                      "    result := n * 2"
                      "  elseif n = 0 then"
                      "    let t := 7"
                      "    result := t"
                      "  else"
                      "    result := -1"
                      "  end"
                      "end"
                      "print(g(3))"
                      "print(g(0))"
                      "print(g(-5))")))))
