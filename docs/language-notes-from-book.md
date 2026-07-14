# Nex language notes, derived from writing *Tight Core, Open Edge*

Internal notes for the Nex language developers. **Not reader-facing** — the book
itself no longer contains these observations. Collected while writing the book's
~15 chapters of Nex code (a constrained order-and-fulfillment core with an open
pricing/rules engine). Two sources below: (A) deficiencies noticed while
expressing the ideas, and (B) the "gap" sidebars that were removed from the
chapters.

---

## A. Deficiencies that made the book's ideas harder to express

Ranked by how much they impeded the writing. The top four genuinely shaped the
code.

1. **Sum types are very verbose.** Every sum type is a `sealed deferred class`
   plus N full subclasses (`inherit` / `feature` / `create make`). What is one
   line in F#/Rust/Haskell (`type Order = Draft of … | Placed of …`) is ~20 lines
   in Nex. Domain modeling is the whole of Part II, so this friction is
   pervasive. **Proposal:** a concise ADT syntax with inline variant payloads
   that desugars to the sealed-class form. **Implemented** as the `union`
   declaration (`docs/proposals/concise-sum-types.md`); it desugars in the walker
   to the sealed-class AST, so the backends are untouched.

2. **No lightweight refinement / newtype.** "A positive integer" required a full
   `Quantity` class with a constructor precondition *and* an invariant. Every
   constrained scalar becomes a class. This is the biggest tax on "make illegal
   states unrepresentable." **Proposal:** refinement subtypes — an existing type
   narrowed by a predicate — without declaring a class. **Implemented** as
   `declare type X = Base where n: <pred>` (`docs/proposals/refinement-types.md`);
   the predicate is checked at narrowing sites (let / parameter / return), the
   value erases to its base (no boxing). Fields, `convert`, `?R`, and `distinct`
   newtypes are not yet covered.

3. **No stdlib `Result`/`Option`, and no error-propagation sugar.** `Result[T]`
   had to be hand-defined; threading failures meant manual `match`-and-rewrap at
   every level (no `?` / `and_then` / bind / do-notation). Typed errors also do
   not compose: `Err[Quantity]` cannot stand in for `Err[Line_Item]`.
   **Proposal:** standard `Result`/`Option`, a bind/`?` operator, and a shared
   error-channel story. **Partly implemented:** stdlib `data/result` and
   `data/option` modules (`intern data/Result` / `data/Option`) with
   `map`/`and_then`/`map_err`/`filter` combinators (`docs/proposals/result-option.md`).
   Enabled three underlying fixes: `intern` now exports free functions,
   the interpreter dispatches `match` on generic sealed classes, and an
   exhaustive `match` satisfies definite-assignment. Still TODO: the `?`
   propagation operator, and typed-error composition still needs an explicit
   `result_map_err` (no automatic widening).

4. **Pattern matching is type-only.** `match` dispatches on runtime class only —
   no destructuring, nested patterns, literal patterns, or guards. The DSL
   interpreter and the unifier (Ch 10–13) wanted structural destructuring;
   instead they nest `match` and use `convert`. **Proposal:** richer patterns
   (destructuring, nested, literals, `when`-guards). See
   `docs/proposals/richer-patterns.md`. **Phases 1–2 implemented:** field
   destructuring (`when Placed(id, total)`), rename (`id: x`)/skip (`_`), optional
   `as`, `when _` catch-all, `if` guards (fall-through, Boolean check, excluded
   from exhaustiveness), literal field patterns (`field: 0`), and **nested
   patterns** (`field: Some[Integer](value: x)`) — on the JVM and interpreter
   backends. Nested patterns were unblocked by implementing **Finding 2 of
   `docs/proposals/generic-inference.md`** (match binding now carries the
   subject's generic args, so `o.inner : Option[Integer]`) plus fixing generic
   `convert` in the interpreter and `convert`-in-guard lowering on the JVM.
   **Finding 1** (construction inference) is now also done: `create Ok.make(5)`
   infers `Ok[Integer, Any]`, with `Any` a per-argument wildcard in
   `types-compatible?` so it assigns to `Result[Integer, String]`. (Nested
   sub-fields still want the nested type's args written for a precise element
   type.)

5. **Contracts lack quantifiers and connectives.** Could not write "every line
   has positive quantity" directly (needed a `sum_of` helper); the capstone
   reached for `implies`, which may not exist. **Proposal:** `forall` / `exists`
   over collections and `implies` inside `require`/`ensure`/`invariant`.
   *(Also a book-code correctness item: verify whether `implies` is valid Nex; if
   not, rewrite Ch 15's `admit_quote` postcondition.)*

6. **Combinators want ergonomic first-class functions.** The Specification and
   Handler algebras were built as a class-per-combinator (deferred classes),
   partly because Nex's closure story looked young (recent "fix class closure"
   commit). Higher-order combinator code would be far terser with good lambda /
   closure support. **Action:** verify current lambda/closure ergonomics; if
   limited, prioritize.

7. **Heterogeneous collection literals don't infer a common supertype.**
   `[chargeEffect, emailEffect]` would not cleanly type as `Array[Effect]`, so
   effect lists were built with `.add`. **Proposal:** infer the least common
   supertype for mixed literals.

8. **Value semantics / functional update.** "Pure transformation returning new
   state" fought reference-object mutation; a `with`-style immutable update or
   record types would make the functional-core / imperative-shell split (Ch 6)
   more natural. (See also the interpreter object value-semantics divergence
   already tracked in the spec-conformance notes.)

9. **User classes cannot participate in arithmetic operators.** The book's
   `Money` is assumed to support `+ - = < <= > >=`. Comparisons are achievable
   today (implement `Comparable`'s `compare`), and `=` dispatches to a user
   `equals` — but `check-binary-op` restricts `+`/`-`/`*`/`/` to numeric (or
   String for `+`) operands, so `balance - amount` on a real `Money` class is a
   compile-time type error. This makes Ch 2's `Account`/`sum_totals`, Ch 12's
   `Price_Range` construction sites, and Appendix B's `sum_of` unimplementable
   as written against any actual `Money` class. **Proposal:** an `Addable`-style
   protocol mirroring `Comparable` (e.g. `plus`/`minus` backing `+`/`-`), or
   restrict the book's `Money` to method calls (much noisier). Found 2026-07-13
   while verifying the book's examples against the current implementation.
   **FIXED IN THE LANGUAGE 2026-07-14** via **operator aliases**: a feature binds
   itself to an operator with `alias "-"`, Eiffel-style, and the operator is sugar
   for the call — so `balance - amount` now carries `minus`'s `require
   same_currency` with it. Chosen over an `Addable` protocol because a nominal
   protocol's payoff is generic algorithms (one `sum` over anything addable), and
   Nex's generics cannot express the self-bound `[T -> Addable[T]]` that would
   require (see #11). The operator set is closed (`+ - * / % ^`); comparisons
   still go through `Comparable`. Integer/Real arithmetic is unaffected — emitted
   bytecode for alias-free programs is byte-for-byte identical.

10. **No class-level (static) members.** There is no way to declare a constant or
    routine on a *class* rather than an instance. `once` is an immutable
    *instance* field, not a shared one. So the book's `Money.zero` — used in
    contracts throughout (Ch 2, 9, 12, 15, App A, App B) — does not exist: it
    parses, but lowering fails with "Unsupported class-target access during
    lowering", and the typechecker cannot resolve fields on it. The workaround in
    the book is a *constructor*, `create Money.zero(currency)`, plus sign
    predicates `is_negative()`/`is_positive()` so that contracts like
    `quote >= Money.zero` become `not quote.is_negative()` and never need to
    conjure a zero value at all. That is arguably better domain modelling (a zero
    amount is still denominated in a currency), but it is a workaround, not a
    design: named constants on a value type are an ordinary thing to want.
    **Proposal:** class-level `once` features (shared, initialized on first use),
    which would also give the stdlib somewhere to put things like `Integer.max`.
    Found 2026-07-14 while rewriting the book's `Money` off operator overloading.

11. **Generic bounds cannot be self-referential (no F-bounds).** Nex *does* have
    Eiffel-style constrained genericity — `function total[T -> Addable](…)` — and
    the typechecker resolves methods through the bound. But the grammar
    (`genericParam` in `nexlang.g4`) allows only a bare identifier as the bound,
    so `[T -> Addable[T]]` is a syntax error. Without it, a protocol whose
    operations return the implementing type (`plus(other: T): T`) cannot be
    expressed: you degrade to `plus(other: Any): Any` plus a `convert`, which
    discards the type safety that motivated the protocol. This is what killed the
    `Addable` design and made operator aliases (#9) the right answer instead. It
    will block any future `Numeric`/`Monoid`-style abstraction too.
    **Proposal:** allow a parameterized type as a generic bound.
    Found 2026-07-14 while weighing the `Addable` protocol against aliases.

12. **BUG: the JVM backend cannot lower a call on a bound-constrained type
    parameter.** With `function total[T -> Addable](xs: Array[T], seed: T): T`,
    the body `result := result.plus_any(x)` type-checks and *runs correctly on the
    interpreter*, but compiling it fails with "Unsupported target call expression
    for lowering". So constrained genericity is currently half-implemented:
    accepted by the typechecker, rejected by the default backend. Independent of
    the operator question, but it will bite anyone who uses a generic bound.
    Found 2026-07-14; minimal repro is a bounded generic calling a method of its
    bound.

---

## B. Gap sidebars removed from the chapters (relocated here)

These were the reader-facing "Nex Note" boxes; removed so the book does not
advertise language gaps. Each is a candidate library or language feature.

- **Ch 3 — refinement subtypes.** Wrapping every scalar in a constrained class is
  heavy when the only rule is a simple bound. A refinement of an existing type by
  a predicate, without a full class, would let the constraint stream reach
  further with less ceremony. *(= deficiency #2.)*

- **Ch 4 — `Result` ergonomics.** Threading a typed error upward means
  re-wrapping (`Err[Quantity]` cannot stand in for `Err[Line_Item]`). The manual
  `match`-and-rewrap is boilerplate a bind/`and_then` combinator (or do-notation)
  would collapse to one line. Wants a shared error channel and a standard
  `Result` combinator library. Nex's detachable `?T` is a lighter alternative
  today but loses the sum-type exhaustiveness guarantee. *(= deficiency #3.)*

- **Ch 10 — DSL surface syntax.** An embedded DSL from constructor calls is noisy
  (`create Floored.make(create When.make(...))`). Builder functions help;
  infix operators and a terser literal syntax for object trees would let a
  Nex-embedded DSL read closer to its domain.

- **Ch 11 — positive: mirrors Nex's own `intern` philosophy.** An extensible
  evaluator (closed syntax, open operators) is the same small-core-plus-growth
  discipline Nex applies to itself via `intern`/libraries. Not a gap — a possible
  positive callout to place elsewhere (docs/marketing), not in the book.

- **Ch 12 — propagator library.** The cell + monotone merge are expressible
  today, but a production network needs a scheduler (to quiescence), provenance
  tags, and a truth-maintenance layer for retraction. A reusable propagator
  library, generic over the partial-information type, would be worth shipping.

- **Ch 13 — logic-and-search library.** A production unifier needs occurs-check,
  an efficient substitution representation, backtracking search over trees with
  lazy solution streams, and possibly a relational (miniKanren-style) surface —
  generic over the term type and integrated with contracts (a variant proves
  search termination; a postcondition validates each solution).

- **Ch 1 — device removed.** The book originally framed these as a recurring "Nex
  Note" sidebar recording what the book stretched. That framing is gone from the
  reader-facing text; this file is its replacement.

---

## C. Implementation bugs exposed by the book's code (2026-07-13 audit)

- **FIXED (2026-07-13, verified against the installed `nex`): a non-generic
  class inheriting an instantiated generic was not assignable to the parent
  type.** `class Over_Amount inherit Spec[Draft]` typechecked as a declaration,
  but `let s: Spec[Draft] := create Over_Amount.make(m)` — and equivalently
  passing it to a `Spec[Draft]` parameter — failed with
  `Cannot assign Over_Amount to variable 's' of type Spec[Draft]` (the
  generic-subclass shape `class Over[T] inherit Spec[T]` always worked). Both
  shapes now typecheck and run. With this fix the book's Ch 7 primitives
  (`Over_Amount`/`Domestic` : `Spec[Placed]`), Ch 10's `When` condition
  argument, and Appendix B's rule assembly are valid as written.
