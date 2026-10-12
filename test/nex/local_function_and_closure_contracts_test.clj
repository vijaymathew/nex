(ns nex.local-function-and-closure-contracts-test
  "A `function` written inside a block, and `require` / `ensure` / `rescue` on
   an `fn` literal.

   A local `function f(...) ... end` is the closure `let f := fn (...) ... end`
   (see `handle-local-function-decl` in nex.walker), so it captures the
   enclosing locals, may call itself, and may be mutually recursive with its
   neighbours. Both forms carry contracts, checked on both backends."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- run [code opts]
  (let [f (java.io.File/createTempFile "local_function" ".nex")]
    (try
      (spit f code)
      (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) opts))))
      (finally (.delete f)))))

(defn- both
  "Printed output of CODE, asserted identical on both backends."
  [code]
  (let [compiled (run code {})
        interpreted (run code {:interpret? true})]
    (is (= interpreted compiled) "compiled and interpreted output must agree")
    compiled))

(defn- failure
  "The message CODE fails with, on the backend OPTS selects."
  [code opts]
  (try
    (run code opts)
    nil
    (catch Throwable t (str (ex-message t) " " (some-> (ex-cause t) ex-message)))))

(defn- fails-on-both? [code fragment]
  (every? #(some-> (failure code %) (str/includes? fragment))
          [{} {:interpret? true}]))

(def ^:private cons-pair
  "declare type Pair = Function(Integer): Any

function cons(x, y: Any): Pair
do
  function dispatch(m: Integer): Any
  require
    valid_selector: m = 0 or m = 1
  do
    if m = 0 then result := x else result := y end
  end
  result := dispatch
end
let p := cons(1, \"two\")
")

(deftest local-function-captures-enclosing-locals-test
  (is (= ["1" "\"two\""]
         (both (str cons-pair "print(p(0))\nprint(p(1))")))))

(deftest local-function-precondition-test
  (is (fails-on-both? (str cons-pair "print(p(2))") "valid_selector")))

(deftest local-function-recursion-test
  (testing "a local function may call itself"
    (is (= ["120"]
           (both "function outer(n: Integer): Integer
do
  function fact(k: Integer): Integer
  require
    non_negative: k >= 0
  do
    if k = 0 then result := 1 else result := k * fact(k - 1) end
  ensure
    positive: result > 0
  end
  result := fact(n)
end
print(outer(5))"))))
  (testing "consecutive local functions may call each other"
    (is (= ["\"odd\""]
           (both "function parity(n: Integer): String
do
  function is_even(k: Integer): Boolean
  do
    if k = 0 then result := true else result := is_odd(k - 1) end
  end
  function is_odd(k: Integer): Boolean
  do
    if k = 0 then result := false else result := is_even(k - 1) end
  end
  if is_even(n) then result := \"even\" else result := \"odd\" end
end
print(parity(7))")))))

(deftest local-function-in-method-test
  (testing "a local function in a method reads and assigns the object's fields"
    (is (= ["6"]
           (both "class Counter
create
  make() do count := 0 end
feature
  count: Integer
  bump(by: Integer): Integer
  do
    function add(n: Integer): Integer
    require
      positive: n > 0
    do
      count := count + n
      result := count
    end
    result := add(by) + add(by)
  end
end
let c := create Counter.make()
print(c.bump(2))")))))

(deftest local-function-in-nested-block-test
  (is (= ["\"hi!\""]
         (both "if true then
  function shout(s: String): String do result := s + \"!\" end
  print(shout(\"hi\"))
end"))))

(deftest anonymous-function-contracts-test
  (let [half "let half := fn (n: Integer): Integer
  require
    even: n % 2 = 0
  do
    result := n / 2
  ensure
    halved: result * 2 = n
  end
"]
    (is (= ["5"] (both (str half "print(half(10))"))))
    (is (fails-on-both? (str half "print(half(3))") "even")))
  (testing "a postcondition that does not hold"
    (is (fails-on-both? "let f := fn (n: Integer): Integer do result := n ensure bigger: result > n end
print(f(1))" "bigger"))))

(deftest anonymous-function-rescue-test
  (is (= ["9" "3"]
         (both "let attempts := 0
let flaky := fn (n: Integer): Integer
  do
    attempts := attempts + 1
    if attempts < 3 then raise \"boom\" end
    result := n
  rescue
    retry
  end
print(flaky(9))
print(attempts)"))))

(deftest old-in-closure-postcondition-test
  (testing "`old` in a closure's ensure is rejected, and not blamed on the
            enclosing method"
    (let [msg (failure "class Counter
create
  make() do count := 0 end
feature
  count: Integer
  bump(by: Integer): Integer
  do
    function add(n: Integer): Integer
    do
      count := count + n
      result := count
    ensure
      grew: result > old count
    end
    result := add(by)
  end
end" {})]
      (is (str/includes? msg "local function 'add'")))))

(deftest closure-contract-captures-test
  (testing "a name only a contract clause mentions is captured"
    (is (= ["1"]
           (both "function bounded(lo, hi: Integer): Function(Integer): Integer
do
  function clamp(n: Integer): Integer
  require
    ordered: lo <= hi
  do
    result := n
    if n < lo then result := lo end
  ensure
    capped: result <= hi or n > hi
  end
  result := clamp
end
print(bounded(1, 10)(0))")))
    (is (fails-on-both? "function pick(x, y: Any): Function(Integer): Any
do
  function dispatch(m: Integer): Any
  do
    if m = 0 then result := x else result := 100 end
  ensure
    x_or_y: result = x or result = y
  end
  result := dispatch
end
print(pick(1, 2)(1))" "x_or_y")))
  (testing "a rescue arm assigns a captured local"
    (is (= ["10"]
           (both "function tries(limit: Integer): Integer
do
  let failures := 0
  function go(n: Integer): Integer
  do
    if failures < limit then raise \"boom\" end
    result := n
  rescue
    failures := failures + 1
    retry
  end
  result := go(7) + failures
end
print(tries(3))"))))
  (testing "a postcondition calls the local function it belongs to"
    (is (= ["4"]
           (both "function down(n: Integer): Integer
do
  function count(k: Integer): Integer
  do
    if k = 0 then result := 0 else result := 1 + count(k - 1) end
  ensure
    same: k = 0 or result = 1 + count(k - 1)
  end
  result := count(n)
end
print(down(4))"))))
  (testing "a closure's contracts read the enclosing object's fields"
    (let [box "class Box
create
  make(l: Integer) do limit := l end
feature
  limit: Integer
  checker(): Function(Integer): Integer
  do
    result := fn (n: Integer): Integer
      require
        within: n <= limit
      do
        result := n
      ensure
        still_within: result <= limit
      end
  end
end
let b := create Box.make(5)
"]
      (is (= ["3"] (both (str box "print(b.checker()(3))"))))
      (is (fails-on-both? (str box "print(b.checker()(9))") "within")))))

(deftest assert-in-closure-captures-test
  (testing "a name only an `assert` in a closure's body mentions is captured"
    (is (= ["2"]
           (both "function guard(x: Integer): Function(Integer): Integer
do
  result := fn (n: Integer): Integer do
    assert x > 0
    result := n
  end
end
print(guard(1)(2))")))))
