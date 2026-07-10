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
   narrowed by a predicate — without declaring a class.

3. **No stdlib `Result`/`Option`, and no error-propagation sugar.** `Result[T]`
   had to be hand-defined; threading failures meant manual `match`-and-rewrap at
   every level (no `?` / `and_then` / bind / do-notation). Typed errors also do
   not compose: `Err[Quantity]` cannot stand in for `Err[Line_Item]`.
   **Proposal:** standard `Result`/`Option`, a bind/`?` operator, and a shared
   error-channel story.

4. **Pattern matching is type-only.** `match` dispatches on runtime class only —
   no destructuring, nested patterns, literal patterns, or guards. The DSL
   interpreter and the unifier (Ch 10–13) wanted structural destructuring;
   instead they nest `match` and use `convert`. **Proposal:** richer patterns
   (destructuring, nested, literals, `when`-guards).

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
