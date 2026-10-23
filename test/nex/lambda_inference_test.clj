(ns nex.lambda-inference-test
  "A `fn` literal passed as an argument takes its omitted parameter (and
   result) types from the parameter it is passed to: of a method, a free or
   generic function, a constructor, a Function value, or Array.sort. The
   typechecker records what it inferred and writes it into the AST before
   either backend runs. Asserted on both backends."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]))

(defn- run-file
  [path interpret?]
  (let [out (with-out-str (e/eval-file path {:interpret? interpret?}))]
    (is (not (str/includes? out "falling back to the tree-walking interpreter"))
        (str "compiled backend declined this program:\n" out))
    (str/split-lines (str/trim-newline out))))

(defn- both
  [& lines]
  (let [f (java.io.File/createTempFile "lambda_inference" ".nex")]
    (try
      (spit f (str/join "\n" lines))
      (let [compiled (run-file (.getPath f) false)
            interpreted (run-file (.getPath f) true)]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(defn- type-error
  [& lines]
  (let [f (java.io.File/createTempFile "lambda_inference" ".nex")]
    (try
      (spit f (str/join "\n" lines))
      (with-out-str (e/eval-file (.getPath f)))
      nil
      (catch clojure.lang.ExceptionInfo ex (ex-message ex))
      (finally (.delete f)))))

(deftest methods-functions-and-generics
  (is (= ["\"abab\"" "4" "4" "18" "11" "\"X\"" "\"abab5\"" "\"abab5\""]
         (both "class Box [T]"
               "create"
               "  make(v: T) do value := v end"
               "feature"
               "  value: T"
               "  apply(f: Function(x: T): T) do value := f(value) end"
               "  each_twice(f: Function(x: T): Void) do f(value)  f(value) end"
               "end"
               "function twice(f: Function(n: Integer): Integer, x: Integer): Integer do"
               "  result := f(f(x))"
               "end"
               "function apply_to[T](x: T, f: Function(v: T): T): T do"
               "  result := f(x)"
               "end"
               "let b: Box[String] := create Box[String].make(\"ab\")"
               "b.apply(fn (s) do result := s + s end)"
               "print(b.value)"
               "b.each_twice(fn (s) do print(s.length) end)"
               "print(twice(fn (n) do result := n * 3 end, 2))"
               ;; T is bound by the other argument before the fn is checked
               "print(apply_to(10, fn (v) do result := v + 1 end))"
               "print(apply_to(\"x\", fn (v) do result := v.to_upper end))"
               "let k := 5"
               "b.each_twice(fn (s) do print(s + k.to_string) end)"))))

(deftest constructors-fields-nesting-and-sort
  (is (= ["5" "3" "10" "23" "[3, 2, 1]" "49"]
         (both "class Handler"
               "create"
               "  make(f: Function(msg: String): Integer) do action := f end"
               "feature"
               "  action: Function(msg: String): Integer"
               "  run(m: String): Integer do result := action(m) end"
               "end"
               "class Counter"
               "create"
               "  make() do count := 0 end"
               "feature"
               "  count: Integer"
               "  bump_by(f: Function(c: Integer): Integer) do count := f(count) end"
               "  go() do bump_by(fn (c) do result := c + count + 1 end) end"
               "end"
               "function compose(f: Function(x: Integer): Integer, g: Function(x: Integer): Integer): Function(x: Integer): Integer do"
               "  result := fn (x) do result := g(f(x)) end"
               "end"
               "function apply_outer(f: Function(x: Integer): Function(y: Integer): Integer): Integer do"
               "  result := f(2)(3)"
               "end"
               "let h: Handler := create Handler.make(fn (msg) do result := msg.length end)"
               "print(h.run(\"hello\"))"
               "let c: Counter := create Counter.make()"
               "c.go()"
               "c.go()"
               "print(c.count)"
               "let inc_then_double := compose(fn (x) do result := x + 1 end, fn (x) do result := x * 2 end)"
               "print(inc_then_double(4))"
               "print(apply_outer(fn (x) do result := fn (y) do result := x * 10 + y end end))"
               "let nums: Array[Integer] := [3, 1, 2]"
               "print(nums.sort(fn (a, b) do result := b - a end))"
               "let caller: Function(f: Function(n: Integer): Integer): Integer := fn (f) do result := f(7) end"
               "print(caller(fn (n) do result := n * n end))"))))

(deftest interned-files-keep-their-inferred-types
  (let [dir (doto (java.io.File/createTempFile "lambda_inference" "") (.delete) (.mkdirs))
        tools (io/file dir "tools.nex")
        main (io/file dir "main.nex")]
    (try
      (spit tools (str/join "\n" ["class Tools"
                                  "feature"
                                  "  map_ints(xs: Array[Integer], f: Function(x: Integer): Integer): Array[Integer] do"
                                  "    across xs as x do result.add(f(x)) end"
                                  "  end"
                                  "  doubled(xs: Array[Integer]): Array[Integer] do"
                                  "    result := map_ints(xs, fn (x) do result := x * 2 end)"
                                  "  end"
                                  "end"]))
      (spit main (str/join "\n" ["intern Tools"
                                 "let t: Tools := create Tools"
                                 "print(t.doubled([1, 2, 3]))"
                                 "print(t.map_ints([1, 2], fn (x) do result := x + 100 end))"]))
      (is (= ["[2, 4, 6]" "[101, 102]"] (run-file (.getPath main) false)))
      (is (= ["[2, 4, 6]" "[101, 102]"] (run-file (.getPath main) true)))
      (finally (.delete tools) (.delete main) (.delete dir)))))

(deftest errors-use-the-inferred-types
  (let [twice "function twice(f: Function(n: Integer): Integer, x: Integer): Integer do result := f(f(x)) end"]
    (is (str/includes? (str (type-error twice "print(twice(fn (n) do result := n.to_upper end, 2))"))
                       "`to_upper` is not a feature of Integer"))
    (is (str/includes? (str (type-error twice "print(twice(fn (a, b) do result := a end, 2))"))
                       "Expected a Function with 1 parameter, got one with 2"))
    (is (str/includes? (str (type-error twice "print(twice(fn (n: String) do result := 1 end, 2))"))
                       "Expected Function(n: Integer): Integer, got Function(n: String): Integer"))
    (is (str/includes? (str (type-error twice "print(twice(fn (n) do result := \"s\" end, 2))"))
                       "Cannot assign String to variable 'result' of type Integer"))
    (testing "with no context, the parameter type must be written"
      (is (str/includes? (str (type-error "let f := fn (x) do print(x) end"))
                         "Cannot infer the type of parameter 'x'")))))
