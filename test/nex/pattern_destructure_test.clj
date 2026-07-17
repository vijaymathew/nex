(ns nex.pattern-destructure-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.typechecker :as tc]
            [nex.interpreter :as interp]))

;; Phase 1 of richer patterns: field destructuring and `_` wildcard in `match`.
;; Both desugar in the walker to the plain type-dispatch form (a synthetic `as`
;; binding plus leading `let`s), so the backends are unchanged. Tests run whole
;; programs through the interpreter.

(defn- run [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)]
    (interp/eval-node ctx ast)
    @(:output ctx)))

(defn- type-error? [code]
  (let [result (tc/type-check (p/ast code))]
    (not (:success result))))

(def ^:private order-decl
  "union Order
  Draft
  Placed(id: String, total: Real)
  Shipped(tracking: String, at: Integer)
end
")

(deftest destructure-binds-fields-by-name
  (testing "when Variant(field, ...) binds the payload fields as locals"
    (is (= ["\"placed A-100\"" "\"draft\"" "\"shipped Z9\""]
           (run (str order-decl
                     "function d(o: Order): String do
  match o of
    when Draft             then result := \"draft\"
    when Placed(id, total) then result := \"placed \" + id
    when Shipped(tracking) then result := \"shipped \" + tracking
  end
end
print(d(create Placed.make(\"A-100\", 42.0)))
print(d(create Draft.make()))
print(d(create Shipped.make(\"Z9\", 3)))"))))))

(deftest destructure-rename-and-skip
  (testing "`field as local` renames; an unnamed field is ignored"
    (is (= ["\"Z9\""]
           (run (str order-decl
                     "function d(o: Order): String do
  result := \"?\"
  match o of
    when Shipped(tracking as t) then result := t
    when _                           then result := \"?\"
  end
end
print(d(create Shipped.make(\"Z9\", 3)))"))))))

(deftest destructure-composes-with-as
  (testing "a destructuring clause can also bind the whole value with `as`"
    (is (= ["\"A-100 / A-100\""]
           (run (str order-decl
                     "function d(o: Order): String do
  result := \"?\"
  match o of
    when Placed(id, total) as p then result := id + \" / \" + p.id
    when _                      then result := \"?\"
  end
end
print(d(create Placed.make(\"A-100\", 42.0)))"))))))

(deftest wildcard-is-catch-all
  (testing "when _ is a catch-all that suppresses the exhaustiveness requirement"
    (is (= ["\"other\"" "\"P\""]
           (run (str order-decl
                     "function d(o: Order): String do
  match o of
    when Placed(id) then result := \"P\"
    when _          then result := \"other\"
  end
end
print(d(create Draft.make()))
print(d(create Placed.make(\"x\", 1.0)))"))))))

(deftest destructure-keeps-exhaustiveness-check
  (testing "a destructuring match still requires all variants (or a wildcard/else)"
    (is (type-error?
         (str order-decl
              "function d(o: Order): String do
  match o of
    when Draft      then result := \"d\"
    when Placed(id) then result := id
  end
end
print(d(create Draft.make()))")))))

(deftest destructure-bad-field-is-rejected
  (testing "destructuring a field the variant does not have is a type error"
    (is (type-error?
         "union Box
  Full(v: Integer)
end
function f(b: Box): Integer do
  match b of
    when Full(nope) then result := nope
  end
end
print(f(create Full.make(1)))"))))

;; --- Phase 2: guards ---

(deftest guard-selects-first-satisfied-clause
  (testing "an `if` guard falls through to the next clause when false"
    (is (= ["\"big A\"" "\"small B\"" "\"zero C\"" "\"draft\""]
           (run (str order-decl
                     "function c(o: Order): String do
  match o of
    when Placed(id, total) if total > 1000.0 then result := \"big \" + id
    when Placed(id, total) if total > 0.0    then result := \"small \" + id
    when Placed(id, total)                   then result := \"zero \" + id
    when Draft                               then result := \"draft\"
  end
end
print(c(create Placed.make(\"A\", 5000.0)))
print(c(create Placed.make(\"B\", 50.0)))
print(c(create Placed.make(\"C\", 0.0)))
print(c(create Draft.make()))"))))))

(deftest guard-may-reference-destructured-fields
  (testing "the guard sees the destructured bindings"
    (is (= ["\"hi\"" "\"lo\""]
           (run (str order-decl
                     "function c(o: Order): String do
  result := \"?\"
  match o of
    when Placed(id, total) if total > 10.0 then result := \"hi\"
    when _                                 then result := \"lo\"
  end
end
print(c(create Placed.make(\"x\", 99.0)))
print(c(create Placed.make(\"y\", 1.0)))"))))))

(deftest guarded-clause-does-not-cover-its-variant
  (testing "a variant handled only by a guarded clause is not exhaustive"
    (is (type-error?
         (str order-decl
              "function c(o: Order): String do
  match o of
    when Draft                             then result := \"d\"
    when Placed(id, total) if total > 0.0  then result := id
  end
end
print(c(create Draft.make()))")))))

(deftest guard-with-else-or-unguarded-is-exhaustive
  (testing "adding an else (or unguarded clause) restores exhaustiveness"
    (is (= ["\"x\"" "\"other\""]
           (run (str order-decl
                     "function c(o: Order): String do
  match o of
    when Placed(id, total) if total > 0.0 then result := id
    else result := \"other\"
  end
end
print(c(create Placed.make(\"x\", 5.0)))
print(c(create Draft.make()))"))))))

(deftest non-boolean-guard-is-rejected
  (testing "a guard whose type is not Boolean is a type error"
    (is (type-error?
         (str order-decl
              "function c(o: Order): String do
  match o of
    when Placed(id, total) if total then result := id
    when _                          then result := \"x\"
  end
end
print(c(create Placed.make(\"a\", 1.0)))")))))

;; --- Phase 3: literal field patterns ---

(def ^:private cmd-decl
  "union Cmd
  Move(dx: Integer, dy: Integer)
  Say(text: String)
end
")

(deftest literal-field-pattern-matches-value
  (testing "a `field: literal` pattern matches only when the field equals the literal"
    (is (= ["\"stay\"" "\"move\"" "\"bye\"" "\"say hello\""]
           (run (str cmd-decl
                     "function r(c: Cmd): String do
  match c of
    when Move(dx: 0, dy: 0) then result := \"stay\"
    when Move(dx, dy)       then result := \"move\"
    when Say(text: \"quit\")  then result := \"bye\"
    when Say(text)          then result := \"say \" + text
  end
end
print(r(create Move.make(0, 0)))
print(r(create Move.make(1, 2)))
print(r(create Say.make(\"quit\")))
print(r(create Say.make(\"hello\")))"))))))

(deftest literal-and-explicit-guard-combine
  (testing "literal field patterns AND with an explicit if-guard"
    (is (= ["\"up-far\"" "\"other\"" "\"other\""]
           (run (str cmd-decl
                     "function r(c: Cmd): String do
  match c of
    when Move(dx: 0, dy) if dy > 5 then result := \"up-far\"
    when Move(dx, dy)              then result := \"other\"
    when Say(text)                then result := text
  end
end
print(r(create Move.make(0, 10)))
print(r(create Move.make(0, 2)))
print(r(create Move.make(3, 10)))"))))))

(deftest literal-clause-does-not-cover-its-variant
  (testing "a variant handled only by a literal-constrained clause is not exhaustive"
    (is (type-error?
         (str cmd-decl
              "function r(c: Cmd): String do
  match c of
    when Move(dx: 0, dy: 0) then result := \"stay\"
    when Say(text)          then result := text
  end
end
print(r(create Say.make(\"x\")))")))))

;; --- match generic-argument propagation + nested patterns ---

(def ^:private opt-result-decl
  "union Option[T]
  Some(value: T)
  None
end
union Result[T]
  Ok(inner: Option[T])
  Bad
end
")

(deftest match-propagates-generic-args-to-binding
  (testing "a matched variant's field keeps its real element type (not Any)"
    ;; Passing o.value to a function that strictly wants Integer only type-checks
    ;; if the match binding carried the subject's [Integer] argument.
    (is (= ["43"]
           (run "union Box[T]
  Full(value: T)
  Empty
end
function inc_it(n: Integer): Integer do result := n + 1 end
function f(b: Box[Integer]): Integer do
  result := 0
  match b of
    when Full(value) then result := inc_it(value)
    when Empty       then result := -1
  end
end
print(f(create Full[Integer].make(42)))")))))

(deftest nested-pattern-matches-and-binds
  (testing "a nested variant pattern narrows a field and binds through it"
    (is (= ["42" "-1" "-1"]
           (run (str opt-result-decl
                     "function f(r: Result[Integer]): Integer do
  result := -1
  match r of
    when Ok(inner: Some[Integer](value as x)) then result := x
    when _                                  then result := -1
  end
end
let s: Option[Integer] := create Some[Integer].make(42)
let n: Option[Integer] := create None[Integer].make()
print(f(create Ok[Integer].make(s)))
print(f(create Ok[Integer].make(n)))
print(f(create Bad[Integer].make()))"))))))

(deftest nested-pattern-does-not-cover-its-variant
  (testing "a nested pattern is conditional, so it does not make the match exhaustive"
    (is (type-error?
         (str opt-result-decl
              "function f(r: Result[Integer]): Integer do
  match r of
    when Ok(inner: Some[Integer](value as x)) then result := x
    when Bad                                then result := 0
  end
end
print(f(create Bad[Integer].make()))")))))

(deftest plain-type-clause-without-as-is-allowed
  (testing "a clause may bind neither fields nor the whole value"
    (is (= ["\"yes\""]
           (run (str order-decl
                     "function d(o: Order): String do
  result := \"no\"
  match o of
    when Draft then result := \"yes\"
    when _     then result := \"no\"
  end
end
print(d(create Draft.make()))"))))))

;; ─── `:` constrains, `as` renames ────────────────────────────────────────────
;;
;; A field pattern's colon originally did three unrelated jobs: pin a field to a
;; literal (`total: 0`), narrow it to a type (`inner: Some(...)`), and — with a
;; bare identifier — *rename* it (`tracking: t`). The last one reads exactly like
;; the type annotation `x: T` is everywhere else in Nex while meaning the
;; opposite, and it collided with the type form: dropping the parens from a
;; working nested pattern silently flipped "test the type" into "rename to a
;; local". Worse, a builtin type name there was a *parse* error (`String` is a
;; keyword token, not an IDENTIFIER), so `when Err(s: String)` — the annotation a
;; reader would most expect to work — could not even be spelled.
;;
;; Now `:` always constrains the named field and `as` always renames it.

(defn- type-error-messages [code]
  (let [result (tc/type-check (p/ast code))]
    (map #(if (map? %) (:message %) (str %)) (:errors result))))

(def ^:private box-decl
  "class Circle
  feature radius: Integer
  create make(r: Integer) do radius := r end
end
union Shape
  Box(content: Any)
  Empty
end
")

(deftest type-pattern-narrows-and-binds-the-field
  (testing "`field: T` requires the field to be a T and binds it, narrowed"
    ;; `content` is declared Any; reading `.radius` off it only type-checks if
    ;; the pattern narrowed the binding to Circle, so this pins the type too.
    (is (= ["\"circle 7\"" "\"other\""]
           (run (str box-decl
                     "function d(s: Shape): String do
  result := \"?\"
  match s of
    when Box(content: Circle) then result := \"circle \" + content.radius.to_string
    when Box(content)         then result := \"other\"
    when Empty                then result := \"empty\"
  end
end
print(d(create Box.make(create Circle.make(7))))
print(d(create Box.make(\"hello\")))")))))) 

(deftest type-pattern-accepts-a-builtin-type-name
  (testing "a builtin type keyword is spellable in a pattern"
    ;; `String`/`Integer` are keyword tokens (STRING_TYPE, INTEGER_TYPE), not
    ;; IDENTIFIERs; the rename-only colon made this a syntax error.
    (is (= ["\"str hi\"" "\"int 3\""]
           (run (str box-decl
                     "function d(s: Shape): String do
  result := \"?\"
  match s of
    when Box(content: String)  then result := \"str \" + content
    when Box(content: Integer) then result := \"int \" + content.to_string
    when Box(content)          then result := \"other\"
    when Empty                 then result := \"empty\"
  end
end
print(d(create Box.make(\"hi\")))
print(d(create Box.make(3)))"))))))

(deftest type-pattern-does-not-cover-its-variant
  (testing "a type pattern is a test, so it does not make the match exhaustive"
    (is (type-error?
         (str box-decl
              "function d(s: Shape): String do
  match s of
    when Box(content: Circle) then result := \"circle\"
    when Empty                then result := \"empty\"
  end
end
print(d(create Empty.make()))")))))

(deftest as-renames-a-field
  (testing "`field as local` binds the field under a different name"
    (is (= ["\"Z9\""]
           (run (str order-decl
                     "function d(o: Order): String do
  result := \"?\"
  match o of
    when Shipped(tracking as t) then result := t
    when _                      then result := \"?\"
  end
end
print(d(create Shipped.make(\"Z9\", 3)))"))))))

(deftest unnamed-fields-are-ignored
  (testing "a field the pattern does not name is simply not bound"
    (is (= ["\"A-100\""]
           (run (str order-decl
                     "function d(o: Order): String do
  result := \"?\"
  match o of
    when Placed(id) then result := id
    when _          then result := \"?\"
  end
end
print(d(create Placed.make(\"A-100\", 42.0)))"))))))

(deftest old-rename-spelling-names-the-fix
  (testing "`field: local` is rejected, and the error gives the `as` spelling"
    ;; The migration case. It must not be silently reinterpreted as a type test
    ;; against a type that happens not to exist.
    (let [msgs (type-error-messages
                (str order-decl
                     "function d(o: Order): String do
  result := \"?\"
  match o of
    when Shipped(tracking: t) then result := t
    when _                    then result := \"?\"
  end
end
print(d(create Shipped.make(\"Z9\", 3)))"))]
      (is (some #(re-find #"`t` is not a type" %) msgs)
          (str "expected a pattern-type error, got: " (pr-str msgs)))
      (is (some #(re-find #"write `tracking as t`" %) msgs)
          (str "error should name the `as` spelling, got: " (pr-str msgs))))))

(deftest old-skip-spelling-names-the-fix
  (testing "`field: _` is rejected, and the error says to omit the field"
    (let [msgs (type-error-messages
                (str order-decl
                     "function d(o: Order): String do
  result := \"?\"
  match o of
    when Shipped(tracking, at: _) then result := tracking
    when _                        then result := \"?\"
  end
end
print(d(create Shipped.make(\"Z9\", 3)))"))]
      (is (some #(re-find #"omit `at` to ignore it" %) msgs)
          (str "error should say to omit the field, got: " (pr-str msgs))))))

(deftest literal-field-pattern-still-uses-the-colon
  (testing "`field: <literal>` is unchanged by the colon's narrowing"
    (is (= ["\"none\"" "\"some\""]
           (run (str order-decl
                     "function d(o: Order): String do
  result := \"?\"
  match o of
    when Shipped(at: 0) then result := \"none\"
    when Shipped(at)    then result := \"some\"
    when _              then result := \"?\"
  end
end
print(d(create Shipped.make(\"Z9\", 0)))
print(d(create Shipped.make(\"Z9\", 3)))"))))))

(deftest undefined-field-in-pattern-names-the-variants-fields
  (testing "a field the variant does not have is reported with the real fields"
    ;; "Undefined field: q" alone is unreadable when the writer believes `q` is
    ;; a binding rather than a field — the mistake this syntax invites.
    (let [msgs (type-error-messages
                (str order-decl
                     "function d(o: Order): String do
  result := \"?\"
  match o of
    when Shipped(q: Integer) then result := \"?\"
    when _                   then result := \"?\"
  end
end
print(d(create Draft.make()))"))]
      (is (some #(re-find #"Undefined field: q on Shipped" %) msgs)
          (str "error should name the variant, got: " (pr-str msgs)))
      (is (some #(re-find #"Accessible fields: at, tracking" %) msgs)
          (str "error should list the real fields, got: " (pr-str msgs)))
      (is (some #(re-find #"write `<field> as q`" %) msgs)
          (str "error should give the binding form, got: " (pr-str msgs))))))

(deftest undefined-field-outside-a-pattern-omits-the-pattern-hint
  (testing "a plain field typo names the class and its fields, with no pattern advice"
    (let [msgs (type-error-messages
                "class P
  feature x: Integer
  private feature secret: Integer
  create make(v: Integer) do
    x := v
    secret := 0
  end
end
print(create P.make(1).z)")]
      (is (some #(re-find #"Undefined field: z on P\. Accessible fields: x\." %) msgs)
          (str "got: " (pr-str msgs)))
      ;; The listing follows the same visibility rules as the lookup, so a
      ;; private field is not disclosed to a caller that could not read it.
      (is (not-any? #(re-find #"secret" %) msgs)
          (str "must not leak private fields, got: " (pr-str msgs)))
      (is (not-any? #(re-find #"In a pattern" %) msgs)
          (str "no pattern hint outside a pattern, got: " (pr-str msgs))))))
