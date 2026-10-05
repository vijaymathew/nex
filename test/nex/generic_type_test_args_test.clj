(ns nex.generic-type-test-args-test
  "A runtime type test that names type arguments -- `convert x to s:
   Some[String]`, a `match` clause or field pattern `Some[String](...)` --
   tests them as well as the class (Definition 4.7: a Some[Integer] is not a
   Some[String]). Both backends used to test the class alone: the interpreter
   then bound an Integer as a String and carried on, the compiled backend
   failed later with \"a value was not of the expected type\". And a clause
   whose type arguments contradict the subject's static type, which can never
   match, is now a type error.

   An argument erased in generic code (an object made by `create Some[T]`
   inside a generic function) is not known, and matches anything; that
   leniency is not pinned here."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]
            [nex.parser :as p]
            [nex.typechecker :as tc]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "generic_type_test_args" ".nex")]
    (try
      (spit f code)
      (let [run #(str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) %))))
            compiled (run {})
            interpreted (run {:interpret? true})]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(def ^:private opt
  "union Opt[T]
  Some(value: T)
  None
end
")

(deftest convert-tests-type-arguments-test
  (testing "a Some[Integer] held in an Any is not a Some[String]"
    (is (= ["6"]
           (both (str opt "class Holder
create
  make(x: Any) do
    item := x
  end
feature
  item: Any
end
let h := create Holder.make(create Some[Integer].make(5))
if convert h.item to s: Some[String] then
  print(\"string some: \" + s.value)
elseif convert h.item to n: Some[Integer] then
  print(n.value + 1)
end"))))))

(deftest field-pattern-tests-type-arguments-test
  (testing "a nested pattern Some[String](...) skips a Some[Integer]"
    (is (= ["6" "\"string: hi\""]
           (both (str opt "union Box
  Holds(content: Any)
  Empty
end
function d(b: Box): String do
  result := \"\"
  match b of
    Holds(content: Some[String](value)) then result := \"string: \" + value
    Holds(content: Some[Integer](value)) then result := (value + 1).to_string
    Holds(content) then result := \"other\"
    Empty then result := \"empty\"
  end
end
print(d(create Holds.make(create Some[Integer].make(5))).to_integer)
print(d(create Holds.make(create Some[String].make(\"hi\"))))"))))))

(deftest type-arguments-through-inheritance-test
  (testing "the value's arguments are mapped through `inherit` before comparing,
            including a reordering heir"
    (is (= ["\"ok\"" "\"swapped\""]
           (both "deferred class Pair [A, B]
end
class Flip [X, Y]
inherit Pair[Y, X]
end
let p: Any := create Flip[Integer, String]
if convert p to q: Pair[Integer, String] then print(\"wrong\") end
if convert p to r: Pair[String, Integer] then print(\"ok\") end
if convert p to s: Flip[Integer, String] then print(\"swapped\") end")))))

(deftest generic-code-clause-decided-at-run-time-test
  (testing "in generic code a clause's arguments cannot be checked statically,
            and are tested against the value at run time"
    (is (= ["9" "0"]
           (both (str opt "function first_int[T](o: Opt[T]): Integer do
  result := -1
  match o of
    Some[Integer](value) then result := value
    Some(value) then result := 0
    None then result := -2
  end
end
print(first_int(create Some[Integer].make(9)))
print(first_int(create Some[String].make(\"x\")))"))))))

(deftest impossible-clause-is-a-type-error-test
  (testing "a clause whose type arguments contradict the subject's can never
            match, and is rejected"
    (let [result (tc/type-check (p/ast (str opt "let o: Opt[Integer] := create Some[Integer].make(5)
match o of
  Some[String](value) then print(value)
  Some(value) then print(\"int\")
  None then print(\"none\")
end")))]
      (is (not (:success result)))
      (is (re-find #"Some\[String\] does not conform to Opt\[Integer\]" (pr-str (:errors result))))))
  (testing "matching type arguments, or none, are accepted"
    (is (:success (tc/type-check (p/ast (str opt "let o: Opt[Integer] := create Some[Integer].make(5)
match o of
  Some[Integer](value) then print(value)
  Some(value) then print(value)
  None then print(\"none\")
end")))))))

(deftest ordinary-generic-matches-are-unchanged-test
  (testing "destructuring, nested patterns and guards on generic unions"
    (is (= ["42" "42" "\"big 3\""]
           (both (str opt "union Res[T, E]
  Ok(value: T)
  Err(error: E)
end
let o: Opt[Integer] := create Some[Integer].make(41)
match o of
  Some(value) then print(value + 1)
  None then print(\"none\")
end
let r: Res[Opt[Integer], String] := create Ok[Opt[Integer], String].make(create Some[Integer].make(7))
match r of
  Ok(value: Some[Integer](value as v)) then print(v * 6)
  Ok(value) then print(\"ok but none\")
  Err(error) then print(error)
end
let t: Opt[Integer] := create Some[Integer].make(3)
match t of
  Some[Integer](value) if value > 2 then print(\"big \" + value.to_string)
  Some(value) then print(\"small\")
  None then print(\"none\")
end"))))))
