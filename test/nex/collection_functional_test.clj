(ns nex.collection-functional-test
  "map, filter, fold, reduce, any, all, count and find on every built-in
   Iterable: Array, Set, String (over its Chars) and Map (over its
   Map_Entry values). map gives an Array of what the function returns; filter
   keeps the receiver's kind. The function's types come from the receiver
   and, for map and fold, its result binds the method's own type parameter.
   Asserted on both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "collection_functional" ".nex")]
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

(defn- type-error
  [& lines]
  (let [f (java.io.File/createTempFile "collection_functional" ".nex")]
    (try
      (spit f (str/join "\n" lines))
      (with-out-str (e/eval-file (.getPath f)))
      nil
      (catch clojure.lang.ExceptionInfo ex (ex-message ex))
      (finally (.delete f)))))

(defn- runtime-error
  [interpret? & lines]
  (let [f (java.io.File/createTempFile "collection_functional" ".nex")]
    (try
      (spit f (str/join "\n" lines))
      (with-out-str (e/eval-file (.getPath f) {:interpret? interpret?}))
      nil
      (catch Exception ex (ex-message ex))
      (finally (.delete f)))))

(deftest arrays
  (is (= ["[2, 4, 6, 8]" "3" "[\"1!\", \"2!\", \"3!\", \"4!\"]" "[2, 4]" "10" "\"1234\""
          "4" "true" "false" "3" "3" "nil" "90"]
         (both "let xs: Array[Integer] := [1, 2, 3, 4]"
               "let doubled := xs.map(fn (x) => x * 2)"
               "print(doubled)"
               ;; map's result is an Array[Integer]
               "print(doubled.get(0) + 1)"
               "print(xs.map(fn (x) => x.to_string + \"!\"))"
               "print(xs.filter(fn (x) => x % 2 = 0))"
               "print(xs.fold(0, fn (acc, x) => acc + x))"
               "print(xs.fold(\"\", fn (acc, x) => acc + x.to_string))"
               "print(xs.reduce(fn (a, b) => a.max(b)))"
               "print(xs.any(fn (x) => x > 3))"
               "print(xs.all(fn (x) => x > 3))"
               "print(xs.count(fn (x) => x > 1))"
               "print(xs.find(fn (x) => x > 2))"
               "print(xs.find(fn (x) => x > 20))"
               "print(xs.filter(fn (x) => x > 1).map(fn (x) => x * 10).fold(0, fn (a, b) => a + b))"))))

(deftest sets-strings-and-maps
  (is (= ["[1, 2, 3]" "#{\"bb\", \"ccc\"}" "1"
          "\"heo\"" "[\"H\", \"E\", \"L\", \"L\", \"O\"]" "2" "#h" "105"
          "{\"b\": 2, \"c\": 3}" "[\"a=1\", \"b=2\", \"c=3\"]" "6" "true"]
         (both "let s: Set[String] := #{\"a\", \"bb\", \"ccc\"}"
               "print(s.map(fn (w) => w.length))"
               ;; filter keeps the receiver's kind
               "print(s.filter(fn (w) => w.length > 1))"
               "print(s.count(fn (w) => w.length = 1))"
               "let word := \"hello\""
               "print(word.filter(fn (c) => c /= #l))"
               "print(word.map(fn (c) => c.to_upper))"
               "print(word.count(fn (c) => c = #l))"
               "print(word.find(fn (c) => c > #d))"
               "print(word.map(fn (c) => c.to_integer).get(1) + 4)"
               "let m: Map[String, Integer] := {\"a\": 1, \"b\": 2, \"c\": 3}"
               "print(m.filter(fn (e) => e.value > 1))"
               "print(m.map(fn (e) => e.key + \"=\" + e.value.to_string))"
               "print(m.fold(0, fn (acc, e) => acc + e.value))"
               "print(m.any(fn (e) => e.key = \"b\"))"))))

(deftest function-values-and-empty-collections
  (is (= ["[2, 3, 4]" "7" "[\"#1\", \"#2\", \"#3\"]" "5" "false" "true" "nil" "\"ANN\""]
         (both "function double(n: Integer): Integer do result := n * 2 end"
               "let xs: Array[Integer] := [1, 2, 3]"
               "let inc: Function(x: Integer): Integer := fn (x) => x + 1"
               "print(xs.map(inc))"
               "print(xs.map(double).get(2) + 1)"
               "print(xs.map(fn (x: Integer): String do result := \"#\" + x.to_string end))"
               "let empty: Array[Integer] := []"
               "print(empty.fold(5, fn (a, b) => a + b))"
               "print(empty.any(fn (x) => x > 0))"
               "print(empty.all(fn (x) => x > 0))"
               "print(empty.find(fn (x) => x > 0))"
               "let names: Array[String] := [\"bo\", \"ann\", \"cy\"]"
               "let three: ?String := names.find(fn (n) => n.length = 3)"
               "if ?three as t then print(t.to_upper) end"))))

(deftest errors
  (is (str/includes? (str (type-error "let xs: Array[Integer] := [1]"
                                      "print(xs.filter(fn (x) => x + 1))"))
                     "This function's result should be Boolean, but the expression after `=>` is Integer."))
  (is (str/includes? (str (type-error "let xs: Array[Integer] := [1]"
                                      "print(xs.map(fn (a, b) => a))"))
                     "Expected a Function with 1 parameter, got one with 2."))
  (testing "a do-form function cannot lend map its result type, and the message says how to fix it"
    (is (str/includes? (str (type-error "let xs: Array[Integer] := [1]"
                                        "print(xs.map(fn (x) do result := x * 100 end))"))
                       "write `fn (...) => <expression>`")))
  (testing "reduce needs at least one element"
    (doseq [interpret? [false true]]
      (is (str/includes? (str (runtime-error interpret?
                                             "let xs: Array[Integer] := []"
                                             "print(xs.reduce(fn (a, b) => a + b))"))
                         "reduce on an empty Array")))))
