(ns nex.arrow-lambda-test
  "`fn (params) => expression`: an anonymous function whose result is the
   expression. Its parameter types come from context or are written; its
   result type is written, comes from context, or is the expression's type;
   a Void result makes the expression a plain statement. A generic routine's
   type parameter that only the result mentions is bound from it. Asserted on
   both backends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]))

(defn- run-backend
  [code interpret?]
  (let [f (java.io.File/createTempFile "arrow_lambda" ".nex")]
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
  (let [f (java.io.File/createTempFile "arrow_lambda" ".nex")]
    (try
      (spit f (str/join "\n" lines))
      (with-out-str (e/eval-file (.getPath f)))
      nil
      (catch clojure.lang.ExceptionInfo ex (ex-message ex))
      (finally (.delete f)))))

(deftest result-types-from-the-expression-the-context-or-the-declaration
  (is (= ["42" "\"hi ann\"" "18" "\"ab!\"" "[3, 2, 1]" "7" "15" "5" "11" "1.5"]
         (both "class Box [T]"
               "create"
               "  make(v: T) do value := v end"
               "feature"
               "  value: T"
               "  apply(f: Function(x: T): T) do value := f(value) end"
               "end"
               "function twice(f: Function(n: Integer): Integer, x: Integer): Integer do"
               "  result := f(f(x))"
               "end"
               "let double := fn (n: Integer) => n * 2"
               "print(double(21))"
               "let greet := fn (name: String) => \"hi \" + name"
               "print(greet(\"ann\"))"
               "print(twice(fn (n) => n * 3, 2))"
               "let b: Box[String] := create Box[String].make(\"ab\")"
               "b.apply(fn (s) => s + \"!\")"
               "print(b.value)"
               "let nums: Array[Integer] := [3, 1, 2]"
               "print(nums.sort(fn (a, b) => b - a))"
               "let add: Function(a: Integer, b: Integer): Integer := fn (a, b) => a + b"
               "print(add(3, 4))"
               "let k := 10"
               "print((fn (x: Integer) => x + k)(5))"
               "let adder := fn (x: Integer) => fn (y: Integer) => x + y"
               "print(adder(2)(3))"
               "print(twice(fn (n) => when n > 5 then n else n + 10 end, 1))"
               "let half := fn (n: Integer): Real => n.to_real / 2"
               "print(half(3))"))))

(deftest void-results-are-statements
  (is (= ["\"say x\"" "3" "3"]
         (both "class Box [T]"
               "create"
               "  make(v: T) do value := v end"
               "feature"
               "  value: T"
               "  each_twice(f: Function(x: T): Void) do f(value)  f(value) end"
               "end"
               "let say := fn (m: String) => print(\"say \" + m)"
               "say(\"x\")"
               "let b: Box[String] := create Box[String].make(\"abc\")"
               "b.each_twice(fn (s) => print(s.length))"))))

(deftest generic-result-types-are-bound-from-the-expression
  (is (= ["[1, 2, 3]" "4" "[\"A!\", \"B!\"]" "[2, 4]" "6" "\"ab\"" "[\"10!\", \"20!\"]"]
         (both "function map_all[T, U](xs: Array[T], f: Function(x: T): U): Array[U] do"
               "  across xs as x do result.add(f(x)) end"
               "end"
               "function keep[T](xs: Array[T], ok: Function(x: T): Boolean): Array[T] do"
               "  across xs as x do if ok(x) then result.add(x) end end"
               "end"
               "function fold[T, A](xs: Array[T], init: A, f: Function(acc: A, x: T): A): A do"
               "  result := init"
               "  across xs as x do result := f(result, x) end"
               "end"
               "let lengths := map_all([\"a\", \"bb\", \"ccc\"], fn (s) => s.length)"
               "print(lengths)"
               ;; U is Integer, so the elements are Integers
               "print(lengths.get(2) + 1)"
               "print(map_all([\"a\", \"b\"], fn (s) => s.to_upper + \"!\"))"
               "print(keep([1, 2, 3, 4], fn (n) => n % 2 = 0))"
               "print(fold([1, 2, 3], 0, fn (acc, x) => acc + x))"
               "print(fold([\"a\", \"b\"], \"\", fn (acc, x) => acc + x))"
               "print(map_all(map_all([1, 2], fn (n) => n * 10), fn (n) => n.to_string + \"!\"))"))))

(deftest parsing
  (testing "the expression runs as far right as an expression can, and stops at , ) and else"
    (is (= ["12" "6"]
           (both "function both(f: Function(n: Integer): Integer, g: Function(n: Integer): Integer): Integer do"
                 "  result := f(1) + g(1)"
                 "end"
                 "print(both(fn (n) => n + 1, fn (n) => n * 10))"
                 "let pick := when true then fn (n: Integer) => n + 1 else fn (n: Integer) => n - 1 end"
                 "print(pick(5))"))))
  (testing "it is the do-form with `result := e`, and keeps the expression"
    (let [f (:value (first (:statements (p/ast "let f := fn (x: Integer) => x * 2"))))]
      (is (= :anonymous-function (:type f)))
      (is (= :binary (:type (:expression-body f))))
      (is (= [:assign "result"] ((juxt :type :target) (first (:body f))))))))

(deftest errors
  (let [twice "function twice(f: Function(n: Integer): Integer, x: Integer): Integer do result := f(f(x)) end"]
    (is (str/includes? (str (type-error twice "print(twice(fn (n) => \"s\", 2))"))
                       "This function's result should be Integer, but the expression after `=>` is String."))
    (is (str/includes? (str (type-error "let f: Function(x: Integer): String := fn (x) => x + 1"))
                       "To make text from a number, use `.to_string`"))
    (is (str/includes? (str (type-error "let f := fn (x) => x + 1"))
                       "Cannot infer the type of parameter 'x'"))
    (is (str/includes? (str (type-error twice "print(twice(fn (n) => n.to_upper, 2))"))
                       "`to_upper` is not a feature of Integer"))))
