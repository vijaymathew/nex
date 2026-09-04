(ns nex.anonymous-function-type-inference-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned."
  [code]
  (let [f (java.io.File/createTempFile "anon_fn_infer" ".nex")]
    (try
      (spit f code)
      (let [compiled (clojure.string/split-lines
                      (clojure.string/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
            interpreted (clojure.string/split-lines
                         (clojure.string/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(defn- typecheck-error-message
  [code]
  (let [ast (p/ast code)
        result (tc/type-check ast)]
    (is (not (:success result)))
    (first (map tc/format-type-error (:errors result)))))

(deftest fn-param-types-inferred-from-typed-let-test
  (testing "fn(item) do ... end infers param and return types from a typed let's Function(...) annotation"
    (is (= ["false" "true"]
           (both "class Box
feature
  v: Integer
create
  make(x: Integer) do v := x end
end
let is_big: Function(Box): Boolean := fn(b) do result := b.v > 10 end
print(is_big(create Box.make(5)))
print(is_big(create Box.make(20)))")))))

(deftest fn-partial-types-inferred-test
  (testing "some params annotated, others inferred, is allowed"
    (is (= ["true"]
           (both "class Box
feature
  v: Integer
create
  make(x: Integer) do v := x end
end
let f: Function(Box, Integer): Boolean := fn(b: Box, threshold) do result := b.v > threshold end
print(f(create Box.make(20), 10))")))))

(deftest fn-bare-type-function-annotation-test
  (testing "Function(Box): Boolean (no parameter name) already works as a type annotation"
    (is (= ["true"]
           (both "class Box
feature
  v: Integer
create
  make(x: Integer) do v := x end
end
let is_big: Function(Box): Boolean := fn(b: Box): Boolean do result := b.v > 10 end
print(is_big(create Box.make(20)))")))))

(deftest fn-no-context-is-a-clear-error-test
  (testing "an untyped fn param with no target Function(...) type to infer from is a clear compile error, not a silent Any"
    (let [msg (typecheck-error-message "let f := fn(item) do result := item end
print(f)")]
      (is (re-find #"Cannot infer the type of parameter 'item'" msg)))))

(deftest fn-arity-mismatch-against-expected-type-is-an-error-test
  (testing "an anonymous function whose param count does not match the expected Function(...) type is a clear error"
    (let [msg (typecheck-error-message "class Box
feature
  v: Integer
create
  make(x: Integer) do v := x end
end
let f: Function(Box): Boolean := fn(a, b) do result := true end
print(f)")]
      (is (re-find #"Expected a Function with 1 parameter, got one with 2" msg)))))

(deftest method-param-without-type-is-now-a-clear-error-test
  (testing "an untyped method parameter (previously silently defaulted to Any) is now rejected"
    (let [msg (typecheck-error-message "class Box
feature
  foo(x) do print(x) end
end")]
      (is (re-find #"Parameter 'x' of method 'foo' must declare a type" msg)))))

(deftest fn-param-types-inferred-on-plain-reassignment-test
  (testing "a plain `t := fn(x) do ... end` reassignment (not just the original `let`) infers
            param/return types from t's own declared Function(...) type"
    (is (= ["20" "100"]
           (both "declare type Transformer = Function(Integer): Integer
let t: Transformer := fn(x): Integer do result := x * 2 end
print(t(10))
t := fn(x): Integer do result := x * x end
print(t(10))")))))

(deftest fn-param-types-inferred-on-reassignment-inside-nested-block-test
  (testing "the same reassignment inference works inside a nested if block"
    (is (= ["11"]
           (both "declare type Transformer = Function(Integer): Integer
let t: Transformer := fn(x): Integer do result := x * 2 end
if true then
  t := fn(x): Integer do result := x + 1 end
end
print(t(10))")))))

(deftest untyped-let-bound-lambda-used-as-an-if-test-lowers-to-boolean-test
  (testing "a `let f := fn(...): Boolean do ... end` local (no Function(...) annotation
            on the let itself) still lowers its call as a real :boolean when used
            directly as an if-test — the lambda's own signature must survive
            lower.clj's own (typechecker-independent) type inference for the let,
            not just the typechecker's"
    (is (= ["true"]
           (both "function f(g: Real): Boolean
do
  let good_enough := fn(x: Real): Boolean
  do
    result := x < 0.001
  end
  if good_enough(g) then
    result := true
  else
    result := false
  end
end
print(f(0.0001))")))))

(deftest fully-typed-fn-still-works-test
  (testing "a fully-annotated fn(...) literal is unaffected by inference support"
    (is (= ["true"]
           (both "class Box
feature
  v: Integer
create
  make(x: Integer) do v := x end
end
let is_big: Function(Box): Boolean := fn(b: Box): Boolean do result := b.v > 10 end
print(is_big(create Box.make(20)))")))))
