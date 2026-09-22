(ns nex.closure-self-recursion-test
  "Regression coverage for self-referential recursive closures.

   `let fact := fn(n) do ... fact(n-1) ... end` used to fail at
   type-check time (\"Undefined function or method: fact\"): nex.typechecker/
   check-let registered a `:let`'s own name only AFTER checking its value,
   matching ordinary sequential-let semantics (an outer `x` is what `let x
   := x + 1` sees, never the new binding) — correct for every other `:let`,
   but it meant a closure literal could never see its own name to call
   itself. Fixed by a narrow, deliberate exception: when a `:let`'s value
   is a closure literal, its name is registered (with a signature derived
   from its own params/return-type, or the `:let`'s own declared type)
   BEFORE the closure's body is checked — the same convention a JS named
   function expression's own name gets inside its own body.

   Getting past the typechecker only unblocked the interpreter (which
   already has true reference-shared lexical environments, so a closure
   literal capturing the same environment its own not-yet-assigned `let`
   slot lives in already worked once the slot resolved at all). The
   compiled backend still failed at RUNTIME (a NullPointerException
   reaching for a nonexistent reflective class lookup, since \"fact\"
   inside its own body isn't an actual field/local of the closure's own
   compiled class — it was never captured, deliberately, to avoid a
   `construct-then-patch-in-a-self-reference` tie-the-knot problem).
   Fixed in nex.lower (rewrite-self-recursive-calls, run before the
   ordinary closure-capture rewrite): a bare, self-recursive call is
   renamed from `fact(...)` to `call1(...)` — the exact shape an ordinary
   `self_method(...)` call already lowers through, since callN is a real
   member of the closure's own class-def. No new AST/IR node, no capture,
   no env threading: JVM `this` inside a closure's own callN method
   already refers to the closure instance itself. The rename is scoped by
   a plain recursive walk that stops at any nested `:anonymous-function`
   boundary, so a call argument or other closure reached inside the
   recursive one's own body — even one that happens to reuse the same
   name for something unrelated — is left alone."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [nex.eval :as e]))

(defn- both
  "Printed output of CODE, asserted identical on both backends, and returned
   as the compiled backend's output (a vector of lines)."
  [code]
  (let [f (java.io.File/createTempFile "closure_self_recursion" ".nex")]
    (try
      (spit f code)
      (let [compiled (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {}))))
            interpreted (str/split-lines (str/trim-newline (with-out-str (e/eval-file (.getPath f) {:interpret? true}))))]
        (is (= interpreted compiled) "compiled and interpreted output must agree")
        compiled)
      (finally (.delete f)))))

(deftest let-level-typed-recursive-closure-test
  (testing "a closure's own name, declared via the `let`'s own Function(...)
            annotation, resolves inside its own body for a recursive call"
    (is (= ["120"]
           (both "let fact: Function(Integer): Integer := fn (n: Integer): Integer do
  if n <= 1 then
    result := 1
  else
    result := n * fact(n - 1)
  end
end
print(fact(5))")))))

(deftest inline-typed-recursive-closure-test
  (testing "a closure's own name resolves for recursion even with no
            `let`-level Function(...) annotation at all — just the
            closure literal's own inline param/return types, and more
            than one self-call in the same expression (fib)"
    (is (= ["55"]
           (both "let fib := fn (n: Integer): Integer do
  if n <= 1 then
    result := n
  else
    result := fib(n - 1) + fib(n - 2)
  end
end
print(fib(10))")))))

(deftest recursive-closure-also-capturing-an-outer-variable-test
  (testing "self-recursion and an ordinary outer capture coexist in the
            same closure without interfering with each other"
    (is (= ["0"]
           (both "let step := 2
let count_down := fn (n: Integer): Integer do
  if n <= 0 then
    result := 0
  else
    result := count_down(n - step)
  end
end
print(count_down(10))")))))

(deftest unrelated-nested-closure-reusing-the-same-name-is-unaffected-test
  (testing "a DIFFERENT closure — passed as a call argument, not
            let-bound — whose own parameter happens to reuse the
            recursive closure's name must not have its own (unrelated)
            references rewritten into a self-call. Regression coverage
            for rewrite-self-recursive-calls' scope-stopping boundary at
            a nested :anonymous-function"
    (is (= ["24" "5"]
           (both "function apply_twice(x: Integer, f: Function(Integer): Integer): Integer do
  result := f(f(x))
end
let fact := fn (n: Integer): Integer do
  if n <= 1 then
    result := 1
  else
    result := n * fact(n - 1)
  end
end
print(fact(4))
print(apply_twice(3, fn (fact: Integer): Integer do result := fact + 1 end))")))))

(deftest self-recursive-closure-inside-a-same-arity-enclosing-function-test
  (testing "a self-recursive closure nested inside a top-level FUNCTION
            whose own arity happens to equal the closure's own arity must
            still resolve its self-call correctly, not collide with the
            enclosing function's own synthetic callN. Regression coverage:
            every top-level function is ALSO its own synthetic
            <name>_Function class (nex.lower/prepare-program-for-closures),
            and rewrite-class-for-closures sets :this-type to that
            wrapper's name for the whole function body — including any
            closure literal nested inside it, so a closure nested there can
            see the enclosing function's own fields/self the way one nested
            in a class method does. rewrite-self-recursive-calls renames a
            closure's bare self-call to the generic name `call<arity>`
            BEFORE the ordinary closure-capture rewrite runs; when the
            enclosing function has that SAME arity (both 1 here), its own
            synthetic call1 collides by name+arity with the just-renamed
            self-call, and rewrite-call-for-closures' this-type-method?
            check wrongly matched it, rerouting the self-call through the
            captured `this` identifier (__closure_this__) instead of
            leaving it as the already-correct bare self-call — the
            enclosing scope never captures that identifier, so it threw
            \"Undefined variable: __closure_this__\" the first time the
            recursion ran. Fixed by tagging the self-recursion rename
            (:self-recursive-call?) and excluding it from that check."
    (is (= ["3.0"]
           (both "function outer(x: Real): Real
do
  let helper := fn (n: Integer): Integer do
    if n <= 0 then
      result := 0
    else
      result := helper(n - 1) + 1
    end
  end
  result := x + helper(3).to_real
end
print(outer(0.0))")))))

(deftest newtons-method-sqrt-with-same-arity-self-recursive-closure-test
  (testing "the original failing program: a 1-param `sqrt` function whose
            own 1-param helper closure (sqr_iter) self-recurses, alongside
            two sibling closures (improve, good_enough) it calls by name —
            the exact shape that produced \"Undefined variable:
            __closure_this__\" before the fix above"
    (is (= ["2.0000000929222947"]
           (both "function sqrt(x: Real): Real
do
  let improve := fn (guess: Real): Real do
    result := (guess + x / guess) / 2.0
  end
  let good_enough := fn (guess: Real): Boolean do
    result := (x - guess * guess).abs / x < 0.0001
  end
  let sqr_iter := fn (guess: Real): Real do
    if good_enough(guess) then
      result := guess
    else
      result := sqr_iter(improve(guess))
    end
  end
  result := sqr_iter(1.0)
end
print(sqrt(4.0))")))))

(deftest non-recursive-closure-is-unaffected-test
  (testing "an ordinary closure that never calls itself is completely
            unaffected by this fix — regression coverage that the
            check-let pre-registration and the (no-op) self-call rewrite
            pass don't change behavior when there is no self-reference at
            all"
    (is (= ["105" "110"]
           (both "let base := 100
let add := fn (x: Integer): Integer do result := base + x end
print(add(5))
print(add(10))")))))
