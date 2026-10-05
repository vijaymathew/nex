(ns nex.function-call-protocol-test
  "Invoking a function value through its call protocol, `f.callN(...)`
   (Definition B.1), and through a call's own result, `adder(1)(...)`.

   The compiled backend refused `f.call1(x)` outright (it lowered only the
   nameless `a(1)(2)` form as a function call); it now lowers both the same
   way. Separately, the typechecker returned the declared result type for both
   forms without checking the arguments against the function's parameters, so
   a wrong argument type or count reached run time; it now checks them
   exactly as a direct `f(x)` call does."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "function_call_protocol" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})
            interpreted (run {:interpret? true})]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(defn- type-error [code]
  (let [result (tc/type-check (p/ast code))]
    (when-not (:success result)
      (pr-str (:errors result)))))

(def ^:private adder
  "function adder(n: Integer): Function(y: Integer): Integer do
  result := fn(y: Integer): Integer do result := y + n end
end
")

(deftest call-protocol-invokes-function-values-test
  (testing "callN on a closure, a plain Function, a named function's value,
            and a positional-parameter signature"
    (is (= ["8" "8" "10" "\"hi\"" "4" "3"]
           (both (str adder "let f := fn(x: Integer): Integer do result := x * 2 end
print(f.call1(4))
let g: Function := f
print(g.call1(4))
function twice(x: Integer): Integer do result := x * 2 end
let h: Function(x: Integer): Integer := twice
print(h.call1(5))
let s := fn(): String do result := \"hi\" end
print(s.call0())
let k: Function(Integer, x: Array[String]): Integer := fn(n: Integer, x: Array[String]): Integer do result := n + x.length end
print(k.call2(1, [\"a\", \"b\", \"c\"]))
print(adder(1)(2))"))))))

(deftest call-protocol-result-is-typed-test
  (testing "the call's type is the function's declared result type"
    (is (= ["8"]
           (both "let f := fn(x: Integer): Integer do result := x * 2 end
let n: Integer := f.call1(4)
print(n)")))))

(deftest call-protocol-arguments-are-checked-test
  (testing "argument types and count are held to the function's parameters,
            with the messages a direct call gets"
    (doseq [[code expected] [["let f := fn(x: Integer): Integer do result := x * 2 end\nprint(f.call1(\"s\"))"
                              #"Expected Integer, got String"]
                             ["let f := fn(x: Integer): Integer do result := x * 2 end\nprint(f.call2(4, 5))"
                              #"Method call2 expects 1 arguments, got 2"]
                             [(str adder "print(adder(1)(\"s\"))")
                              #"Expected Integer, got String"]
                             [(str adder "print(adder(1)(2, 3))")
                              #"Method call2 expects 1 arguments, got 2"]
                             ["let k: Function(Integer, x: Array[String]): Integer := fn(n: Integer, x: Array[String]): Integer do result := n end\nprint(k.call2(1, [2]))"
                              #"Expected Array\[String\], got Array\[Integer\]"]
                             ["let f: Function(Integer): Integer := fn(n: Integer): Integer do result := n end\nlet a: Any := 3\nprint(f.call1(a))"
                              #"parameter 'arg1' of call1"]]]
      (let [err (type-error code)]
        (is (some? err) (str "expected a type error for:\n" code))
        (is (re-find expected (or err "")) err)))))

(deftest direct-calls-are-unchanged-test
  (testing "the direct spelling keeps its own checks"
    (is (re-find #"Expected Integer, got String"
                 (or (type-error "let f := fn(x: Integer): Integer do result := x * 2 end\nprint(f(\"s\"))") "")))
    (is (re-find #"expects 1 arguments, got 2"
                 (or (type-error "let f := fn(x: Integer): Integer do result := x * 2 end\nprint(f(1, 2))") "")))))
