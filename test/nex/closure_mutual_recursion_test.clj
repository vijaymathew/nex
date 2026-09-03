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

(deftest mutual-recursion-name-shadowed-elsewhere-is-cleanly-rejected-test
  (testing "one of the two mutually-recursive names ALSO reused as an
            unrelated closure's own parameter, elsewhere in the same
            block, must be cleanly rejected at type-check time — not
            accepted and then misbehave at runtime. Regression test for a
            two-sided bug found while documenting this feature: nex.lower/
            forward-boxed-closure-names already excluded a shadowed name
            from boxing (mirroring box-candidate-lets' own shadow guard
            for the mutation case), but nex.typechecker/
            register-closure-let-signatures! had no matching exclusion —
            so the program type-checked (the shadowed name resolved as a
            Function value on the strength of that pre-registration
            alone) while lowering correctly declined to back it with a
            real box, producing a runtime crash reaching for an
            uninitialized value instead of the clean rejection ordinary
            sequential-let semantics should give a genuine forward
            reference. Both sides now share the same shadow exclusion
            (shadowed-closure-let-names / shadowed-anywhere-names), so
            the shadowed name simply falls back to check-let's ordinary,
            unmodified handling, which correctly rejects it."
    (let [f (java.io.File/createTempFile "closure_mutual_shadow" ".nex")]
      (try
        (spit f "let is_even := fn (n: Integer): Boolean do
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
let check_is_odd := fn (is_odd: Integer) do print(is_odd) end
print(is_even(10))
check_is_odd(99)")
        (doseq [opts [{} {:interpret? true}]]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Undefined function or method: is_odd"
                                 (e/eval-file (.getPath f) opts))))
        (finally (.delete f))))))

(deftest untyped-mutually-recursive-closure-lets-called-from-top-level-test
  (testing "mutually recursive closures with NO `let`-level Function(...)
            annotation — just the closure literals' own inline
            param/return types — called from top-level statements after
            both are declared. Regression test for a real bug this exact
            program hit outside the test suite: nex.lower/box-let-type's
            fallback for an untyped boxed :let went through
            infer-prepass-type (tc/infer-expression-type), which
            type-checks a WHOLE :anonymous-function body in an isolated,
            standalone env to infer its type — not just reads its
            signature. That is fine for a closure whose body only touches
            its own parameters, but the forward-referenced closure here
            (`is_odd`, boxed because `is_even` — declared first — calls
            it) itself calls BACK into `is_even`, a name that isolated,
            standalone check knows nothing about; it threw, infer-
            prepass-type's own try/catch swallowed the failure, and
            box-let-type silently fell back to \"Any\" for the box's
            element type — erasing the real Function(...) signature and
            leaving nex.lower/infer-type unable to determine what
            `is_odd(10)`, called from a bare top-level statement, actually
            returns: \"Unable to infer expression type during lowering\".
            Every earlier mutual-recursion test in this file used an
            EXPLICIT `let is_even: Function(Integer): Boolean := ...`
            annotation, which bypasses infer-prepass-type entirely (via
            box-let-type's own first branch) and so never exercised this
            path at all — this test's whole point is the untyped case."
    (is (= ["true" "false"]
           (both "let is_even := fn(n: Integer): Boolean do
  if n = 0 then result := true else result := is_odd(n - 1) end
end
let is_odd := fn(n: Integer): Boolean do
  if n = 0 then result := false else result := is_even(n - 1) end
end
print(is_even(10))
print(is_odd(10))")))))
