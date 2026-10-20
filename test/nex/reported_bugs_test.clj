(ns nex.reported-bugs-test
  "Regressions for three user-reported bugs:
   - a class constant defined from another (`SIZE = SYMBOLS.length`) made the
     compiled backend fail to link and quietly fall back to the interpreter;
   - a `declare type` alias (`Vector = Array[Real]`) was not seen through: a
     routine returning one had no zero result, and `across` over one lost its
     element type;
   - the interpreter required a file loaded with `intern` to declare a class
     of the file's name, which the compiled backend never did.
   Every case must print the same on both backends, and the compiled one must
   not fall back to the interpreter."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run-file
  "[printed lines, stderr text] of running FILE with OPTS."
  [^java.io.File file opts]
  (let [err (java.io.StringWriter.)
        out (binding [*err* err]
              (with-out-str (e/eval-file (.getPath file) opts)))]
    [(str/split-lines (str/trim-newline out)) (str err)]))

(defn- both-file
  [^java.io.File file]
  (let [[compiled err] (run-file file {})
        [interpreted _] (run-file file {:interpret? true})]
    (is (not (str/includes? err "falling back")) (str "compiled backend fell back: " err))
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- both
  [code]
  (let [f (java.io.File/createTempFile "reported_bugs" ".nex")]
    (try
      (spit f code)
      (both-file f)
      (finally (.delete f)))))

(deftest constant-defined-from-another-constant-test
  (is (= ["6" "3" "12"]
         (both "class Codes
feature
  SYMBOLS = \"ABCDEF\"
  SIZE = SYMBOLS.length
  DIGITS = [1, 2, 3]
  COUNT = DIGITS.length
  DOUBLE = SIZE * 2
end
print(Codes.SIZE)
print(Codes.COUNT)
print(Codes.DOUBLE)"))))

(deftest constant-read-inside-another-class-test
  (is (= ["6"]
         (both "class Codes
feature
  SYMBOLS = \"ABCDEF\"
  SIZE = SYMBOLS.length
end
class Reader
feature
  size: Integer do result := Codes.SIZE end
end
print((create Reader).size)"))))

(deftest alias-return-type-has-a-zero-result-test
  (is (= ["0" "0"]
         (both "declare type Vector = Array[Real]
function empty_vec(): Vector do
end
function plain(): Array[Real] do
end
print(plain().length)
print(empty_vec().length)"))))

(deftest across-over-an-alias-keeps-its-element-type-test
  (is (= ["1.0" "2.0" "3.0"]
         (both "declare type Vector = Array[Real]
let v: Vector := [1.0, 4.0, 9.0]
across v as x do
  print(x.sqrt)
end"))))

(deftest alias-typed-field-and-chained-alias-test
  (is (= ["0" "4.0" "[3.0, -5.0]"]
         (both "declare type Vector = Array[Real]
declare type Points = Vector
class Path
feature
  pts: Points
  add(x: Real) do pts.add(x) end
  total: Real do
    across pts as p do result := result + p.abs end
  end
  copy_out: Vector do
    across pts as p do result.add(p * 2.0) end
  end
end
let path: Path := create Path
print(path.pts.length)
path.add(1.5)
path.add(-2.5)
print(path.total)
print(path.copy_out)"))))

(defn- with-files
  "Run F with a fresh directory holding FILES (name -> source)."
  [files f]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "reported_bugs" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[name src] files] (spit (io/file dir name) src))
      (f dir)
      (finally
        (doseq [file (reverse (file-seq dir))] (.delete ^java.io.File file))))))

(deftest intern-a-file-of-functions-only-test
  (with-files {"mathutil.nex" "function double_it(n: Integer): Integer do result := n * 2 end
function greet(name: String): String do result := \"hi \" + name end"
               "main.nex" "intern mathutil
print(double_it(21))
print(greet(\"nex\"))"}
    (fn [dir]
      (is (= ["42" "\"hi nex\""] (both-file (io/file dir "main.nex")))))))

(deftest intern-a-file-whose-class-has-another-name-test
  (with-files {"shapes.nex" "class Circle
create make(r: Real) do radius := r end
feature
  radius: Real
end"
               "main.nex" "intern shapes
let c: Circle := create Circle.make(2.0)
print(c.radius)"}
    (fn [dir]
      (is (= ["2.0"] (both-file (io/file dir "main.nex")))))))
