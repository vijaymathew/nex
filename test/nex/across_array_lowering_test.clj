(ns nex.across-array-lowering-test
  "The compiled backend lowers `across` over a statically typed Array, String
   or Set to an indexed walk (nex.lower/indexed-across) instead of three
   dynamic cursor calls per element. Each case must print the same on both backends:
   the interpreter still runs the cursor, so it is the reference."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends."
  [code]
  (let [f (java.io.File/createTempFile "across_array_lowering" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})]
        (is (= (run {:interpret? true}) compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest top-level-local-array-test
  (is (= ["1" "2" "3" "6"]
         (both "let xs: Array[Integer] := [1, 2, 3]
let total: Integer := 0
across xs as x do
  print(x)
  total := total + x
end
print(total)"))))

(deftest empty-array-test
  (is (= ["\"done\""]
         (both "let xs: Array[String] := []
across xs as x do print(x) end
print(\"done\")"))))

(deftest field-array-in-routine-test
  (is (= ["\"a-b-c\""]
         (both "class Words
create
  make do items := [\"a\", \"b\", \"c\"] end
feature
  items: Array[String]
  joined: String do
    result := \"\"
    across items as w do
      if result.length > 0 then result := result + \"-\" end
      result := result + w
    end
  end
end
let w: Words := create Words.make
print(w.joined)"))))

(deftest generic-element-type-test
  (is (= ["2"]
         (both "class Box[T]
create
  make do items := [] end
feature
  items: Array[T]
  put(x: T) do items.add(x) end
  count_all: Integer do
    result := 0
    across items as x do result := result + 1 end
  end
end
let b: Box[String] := create Box[String].make
b.put(\"p\")
b.put(\"q\")
print(b.count_all)"))))

(deftest nested-across-test
  (is (= ["\"1a\"" "\"1b\"" "\"2a\"" "\"2b\""]
         (both "let ns: Array[Integer] := [1, 2]
let cs: Array[String] := [\"a\", \"b\"]
across ns as n do
  across cs as c do print(n.to_string + c) end
end"))))

(deftest target-expression-evaluated-once-test
  (is (= ["\"made\"" "10" "20" "1"]
         (both "class Source
create
  make do calls := 0 end
feature
  calls: Integer
  items: Array[Integer] do
    calls := calls + 1
    print(\"made\")
    result := [10, 20]
  end
end
let s: Source := create Source.make
across s.items as x do print(x) end
print(s.calls)"))))

(deftest array-shrinking-during-loop-test
  (testing "the length is read on every test, as the cursor does"
    (is (= ["1" "3" "[3, 4]"]
           (both "let xs: Array[Integer] := [1, 2, 3, 4]
across xs as x do
  print(x)
  xs.remove(0)
end
print(xs)")))))

(deftest closure-captures-loop-variable-test
  (is (= ["10" "20"]
         (both "let fs: Array[Function] := []
across [1, 2] as x do
  fs.add(fn (): Integer do result := x * 10 end)
end
across fs as f do print(f.call0) end"))))

(deftest non-array-targets-still-work-test
  (is (= ["\"a\"" "\"b\"" "3"]
         (both "across \"ab\" as ch do print(ch.to_string) end
let s: Set[Integer] := #{1, 2, 3}
let n: Integer := 0
across s as x do n := n + 1 end
print(n)"))))

(deftest string-test
  (is (= ["#a" "#b" "#c" "3"]
         (both "let s: String := \"abc\"
let n: Integer := 0
across s as ch do
  print(ch)
  n := n + 1
end
print(n)"))))

(deftest empty-string-test
  (is (= ["\"done\""]
         (both "across \"\" as ch do print(ch) end
print(\"done\")"))))

(deftest string-in-routine-test
  (is (= ["3"]
         (both "class Counter
feature
  vowels(s: String): Integer do
    result := 0
    across s as ch do
      if ch = #a or ch = #e or ch = #i or ch = #o or ch = #u then result := result + 1 end
    end
  end
end
let c: Counter := create Counter
print(c.vowels(\"nexus\" + \"a\"))"))))

(deftest set-in-insertion-order-test
  (is (= ["\"b\"" "\"a\"" "\"c\""]
         (both "let s: Set[String] := #{\"b\", \"a\", \"c\"}
across s as x do print(x) end"))))

(deftest set-changed-during-loop-test
  (testing "the loop walks a copy taken once, as the Set cursor does"
    (is (= ["1" "2" "4"]
           (both "let s: Set[Integer] := #{1, 2}
across s as x do
  print(x)
  s.add(x + 10)
end
print(s.size)")))))

(deftest set-field-in-generic-class-test
  (is (= ["3"]
         (both "class Bag[T]
create
  make do items := #{} end
feature
  items: Set[T]
  put(x: T) do items.add(x) end
  count_all: Integer do
    result := 0
    across items as x do result := result + 1 end
  end
end
let b: Bag[Integer] := create Bag[Integer].make
b.put(7)
b.put(8)
b.put(9)
print(b.count_all)"))))

(deftest map-still-uses-cursor-test
  (is (= ["\"a=1\"" "\"b=2\""]
         (both "let m: Map[String, Integer] := {\"a\": 1, \"b\": 2}
across m as e do print(e.key + \"=\" + e.value.to_string) end"))))
