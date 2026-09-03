(ns nex.closure-mutual-recursion-test
  "Regression coverage for mutual recursion between let-bound closures.

   `let is_even := fn(n) do ... is_odd(n - 1) ... end / let is_odd :=
   fn(n) do ... is_even(n - 1) ... end` — is_even's body references
   is_odd, whose own `:let` has not even been reached yet. Ordinary named
   top-level functions already resolve each other regardless of
   declaration order (check-program registers every function's signature
   before checking any body), but a `:let`-bound closure literal did not:
   check-let only ever registered its OWN name, and only after checking
   its own value.

   Fixed on the typechecker side by nex.typechecker/check-statements
   (used at the scopes this matters in practice — top-level statements, a
   function body, a method/constructor body — not at every single
   statement-list site in the checker), which pre-registers every direct
   closure-literal `:let` in a block before checking any of them, the
   multi-name generalization of check-let's own single-name self-
   recursion pre-registration.

   Getting past the typechecker only unblocked the interpreter — it
   already has true reference-shared lexical environments, so this
   \"just worked\" once it could resolve at all, the same way self-
   recursion did. The compiled backend needed nex.lower/
   box-forward-referenced-closures: unlike self-recursion (where `this`
   inside a closure's own callN method already IS the closure, so no
   capture was needed at all), is_even capturing is_odd needs an actual
   reference to something that does not exist yet at is_even's own
   construction time. Solved by hoisting a Closure_Mut_Box (the same
   synthetic box type nex.lower's shared-mutable-capture fix already
   established) to the very top of the block for every forward-
   referenced closure name, holding a placeholder nil; is_even captures
   that box (not is_odd itself); is_odd's own original `:let` position is
   replaced with a plain fill-in of the same box. Every bare reference to
   a forward-referenced name — call, read, or the fill-in write — is
   rewritten to go through the box."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "closure_mutual_recursion" ".nex")]
    (try
      (spit f code)
      (let [compiled (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
            interpreted (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest top-level-mutually-recursive-closures-test
  (testing "two top-level closures, each calling the other, resolve
            correctly regardless of which is declared first"
    (is (= ["true" "false" "false"]
           (both "let is_even: Function(Integer): Boolean := fn (n: Integer): Boolean do
  if n = 0 then
    result := true
  else
    result := is_odd(n - 1)
  end
end
let is_odd: Function(Integer): Boolean := fn (n: Integer): Boolean do
  if n = 0 then
    result := false
  else
    result := is_even(n - 1)
  end
end
print(is_even(10))
print(is_odd(10))
print(is_even(7))")))))

(deftest mutually-recursive-closures-inside-a-function-body-test
  (testing "the same mutual recursion works for closures declared inside
            a function body, not just at the top level"
    (is (= ["true" "false"]
           (both "function check(n: Integer): Boolean
do
  let is_even := fn (n: Integer): Boolean do
    if n = 0 then
      result := true
    else
      result := is_odd(n - 1)
    end
  end
  let is_odd := fn (n: Integer): Boolean do
    if n = 0 then
      result := false
    else
      result := is_even(n - 1)
    end
  end
  result := is_even(n)
end
print(check(10))
print(check(7))")))))

(deftest three-way-mutually-recursive-closures-test
  (testing "a cycle of three closures (not just two) resolves correctly —
            regression coverage that the fix generalizes past the
            pairwise case"
    (is (= ["true" "false"]
           (both "let is_zero_mod3 := fn (n: Integer): Boolean do
  if n = 0 then
    result := true
  else
    result := is_one_mod3(n - 1)
  end
end
let is_one_mod3 := fn (n: Integer): Boolean do
  if n = 0 then
    result := false
  else
    result := is_two_mod3(n - 1)
  end
end
let is_two_mod3 := fn (n: Integer): Boolean do
  if n = 0 then
    result := false
  else
    result := is_zero_mod3(n - 1)
  end
end
print(is_zero_mod3(9))
print(is_zero_mod3(10))")))))

(deftest backward-reference-between-closures-is-unaffected-test
  (testing "a closure calling an EARLIER-declared sibling (an ordinary
            backward reference, already a real object by the time the
            later closure is constructed) needs no boxing at all and must
            keep working exactly as it did before this fix — regression
            coverage that forward-boxed-closure-names' asymmetric
            (forward-only) detection doesn't over-box the common case"
    (is (= ["5"]
           (both "let double := fn (x: Integer): Integer do result := x * 2 end
let double_then_inc := fn (x: Integer): Integer do result := double(x) + 1 end
print(double_then_inc(2))")))))

(deftest non-recursive-sibling-closures-are-unaffected-test
  (testing "two ordinary closures in the same block that never reference
            each other at all are completely unaffected — regression
            coverage that forward-boxed-closure-names finds nothing to
            box when there is no cross-reference"
    (is (= ["7" "3"]
           (both "let add_one := fn (x: Integer): Integer do result := x + 1 end
let sub_one := fn (x: Integer): Integer do result := x - 1 end
print(add_one(6))
print(sub_one(4))")))))
