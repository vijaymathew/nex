(ns nex.top-level-locals-test
  "A compiled file keeps a top-level `let` that only its top-level code uses
   in a JVM local rather than the session's map of top-level values
   (nex.lower/promotable-top-level-names). One that a function, a class, a
   closure or a `spawn` body uses stays in the map. Every case must print
   the same on both backends."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.compiler.jvm.file :as file]))

(defn- both
  [code]
  (let [f (java.io.File/createTempFile "top_level_locals" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})]
        (is (= (run {:interpret? true}) compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(defn- program-bytecode-text
  "The compiled program class's bytecode, as text, for CODE."
  [code]
  (let [{:keys [program-class classes]} (file/compile-ast "t.nex" (p/ast code) {:skip-type-check true})]
    (String. ^bytes (get classes program-class) java.nio.charset.StandardCharsets/ISO_8859_1)))

(deftest top-level-only-variables-are-locals-test
  (is (= ["15" "7.5"]
         (both "let total: Integer := 0
let x: Real := 0.0
from let i: Integer := 0 until i = 6 do
  total := total + i
  x := x + 1.5
  i := i + 1
end
x := x - 1.5
print(total)
print(x)")))
  (is (not (str/includes? (program-bytecode-text "let total: Integer := 1\ntotal := total + 1\nprint(total)")
                          "\"total\""))
      "the variable's name is not looked up at run time"))

(deftest variable-read-by-a-function-stays-global-test
  (is (= ["11"]
         (both "let base: Integer := 10
function bump(n: Integer): Integer do result := base + n end
print(bump(1))"))))

(deftest variable-captured-by-a-closure-stays-global-test
  (is (= ["6" "8"]
         (both "let k: Integer := 5
let f: Function := fn (x: Integer): Integer do result := x + k end
print(f.call1(1))
k := 7
print(f.call1(1))"))))

(deftest variable-read-by-a-class-stays-global-test
  (is (= ["42"]
         (both "let answer: Integer := 42
class Oracle
feature
  ask: Integer do result := answer end
end
print((create Oracle).ask)"))))

(deftest function-valued-variable-called-by-name-test
  (is (= ["9"]
         (both "let sq: Function := fn (x: Integer): Integer do result := x * x end
print(sq(3))"))))

(deftest variable-used-by-spawn-stays-global-test
  (is (= ["3"]
         (both "let n: Integer := 3
let t: Task[Integer] := spawn do result := n end
print(t.await)"))))

(deftest locals-and-calls-mix-test
  (is (= ["\"area 9\"" "1"]
         (both "class Sq
create make(s: Integer) do side := s end
feature
  side: Integer
  describe: String do result := \"area \" + (side * side).to_string end
end
let s: Sq := create Sq.make(3)
let count: Integer := 0
print(s.describe)
count := count + 1
print(count)"))))
