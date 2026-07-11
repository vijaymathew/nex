# Proposal: Lightweight refinement types (`declare type … where …`)

Addresses **Deficiency #2** in `docs/language-notes-from-book.md`:

> No lightweight refinement / newtype. "A positive integer" required a full
> `Quantity` class with a constructor precondition *and* an invariant. Every
> constrained scalar becomes a class. This is the biggest tax on "make illegal
> states unrepresentable." **Proposal:** refinement subtypes — an existing type
> narrowed by a predicate — without declaring a class.

> **Implementation status.** The refinement-subtype flavor is implemented:
> `declare type X = Base where n: <pred>`, with checks injected at `let`,
> parameter, and return narrowing sites, erased to the base representation, and
> working on the interpreter, JVM, and JS backends. Not yet implemented: field
> narrowing, `convert`-to-refinement, `?R` detachable checks, `distinct`
> newtypes, and `skip-contracts` gating of the injected checks. The design below
> is the full target; those items remain follow-ups.

## Summary

Extend the existing `declare type` form with an optional `where` predicate:

```nex
declare type Quantity   = Integer where n: n > 0
declare type Percentage = Real    where p: p >= 0.0 and p <= 100.0
declare type NonEmpty   = String  where s: s.length() > 0
```

A refinement type is its base type narrowed by a boolean predicate. It is **not**
a class: it carries no fields, no constructor, no boxing. At runtime a `Quantity`
*is* an `Integer` — the refinement is a *checked brand*, erased to the base
representation. The predicate is enforced only where a base value is **narrowed**
into the refinement (assignment to a refinement-typed binding, a refinement
parameter, a field, a return, or an explicit `convert`), reusing the contract
runtime Nex already has. Widening (`Quantity` → `Integer`) is always free.

Crucially, unlike the `union` proposal — which is pure walker sugar with zero
backend changes — refinement types are a genuine type-system feature. This
proposal keeps the footprint small by (a) **erasing to the base value** so the
codegen backends' value representation is untouched, and (b) **reusing the
existing contract-assertion machinery** (`check-assertions` /
`report-contract-violation`, gated by `skip-contracts`) for the injected checks,
rather than inventing a new runtime.

Two flavors are offered:

- **Refinement subtype** (primary, above): `Quantity <: Integer`. Interoperates
  freely with its base — a `Quantity` can be used anywhere an `Integer` is
  wanted.
- **Distinct newtype** (secondary): `declare distinct type UserId = Integer`. A
  nominal type that is *not* interchangeable with its base, to stop `UserId` and
  `ProductId` being mixed even though both are integers.

## The pain today

"A positive integer" currently costs a full class with a duplicated constraint —
a constructor precondition *and* an invariant — plus the interop tax that every
use site now handles a `Quantity` object instead of an `Integer`:

```nex
class Quantity
  feature value: Integer
  create make(v: Integer)
    require positive: v > 0
    do value := v end
  invariant still_positive: value > 0
end
```

With the proposal that entire declaration collapses to one line, and a `Quantity`
stays an ordinary integer at every use site:

```nex
declare type Quantity = Integer where n: n > 0

let q: Quantity := 5          -- checked narrowing: asserts 5 > 0
let total: Integer := q + 1   -- free widening: Quantity is an Integer
```

## Surface syntax

```
declareTypeDecl
    : DECLARE DISTINCT? TYPE_KW IDENTIFIER EQUAL type (WHERE IDENTIFIER ':' expression)?
```

- `where n: <expr>` binds the value under test to `n` (any identifier) and gives
  a boolean predicate over it, mirroring the `label: condition` shape of
  `require`/`ensure`/`invariant`. The binder is explicit — no magic pronoun.
- The predicate is an ordinary boolean expression in the same language as a
  contract assertion. It should be pure; a first cut may restrict it to the value,
  constants, operators, and calls to pure functions.
- `distinct` (optional) switches from refinement subtype to nominal newtype
  (see below).
- With no `where`, `declare type` keeps its current meaning — a structural alias.

`WHERE` and `DISTINCT` are new tokens (`DECLARE`, `TYPE_KW`, `EQUAL` already
exist). Neither currently appears as an identifier or stdlib method name in Nex
code — only in comments — so unlike `union` (which collided with `Set.union`),
reserving them breaks nothing today. Even so, both should be **soft keywords**,
recognized only in their declaration positions (`where` only after `declare type
X = <type>`, `distinct` only between `declare` and `type`), so a future
`.where(...)` query method or `distinct` variable stays legal. In clj-antlr a
matched keyword surfaces as its literal string, so admitting `WHERE`/`DISTINCT`
where an identifier is expected costs no walker change — the same technique the
`union` proposal used for member names.

## Semantic model

Let `R` be a refinement of base `B` with predicate `P`.

1. **Widening is free.** `R` is a subtype of `B`: an `R` value flows into any `B`
   (or `B`-compatible) target with no check. `class-subtype?`/`types-compatible?`
   learn that `R <: B`.
2. **Narrowing is checked.** A `B` value flowing into an `R` target inserts a
   runtime check `assert P(value)`; on failure it reports a contract violation at
   that site. Narrowing sites:
   - `let x: R := <B expr>`
   - passing a `B` argument to an `R` parameter
   - assigning to an `R` field
   - `result :=` in a function returning `R` (checked as a postcondition)
   - `convert v to x: R` (see below)
3. **Erased representation.** An `R` value is represented exactly as a `B` value —
   no wrapper object. So `q + 1`, `p * 2.0`, string ops, etc. all work as base
   operations. The backends never see a new value kind.
4. **Refinement is not propagated through operations.** `q1 + q2` has type
   `Integer`, not `Quantity` — the checker does not attempt to prove that `+`
   preserves `> 0`. To get a `Quantity` back, flow the result into a
   `Quantity`-typed binding, which re-checks. This keeps the feature sound
   without a theorem prover (and dovetails with Deficiency #5 if richer contract
   reasoning lands later).
5. **Detachable refinements.** `?Quantity` admits `nil`; the predicate is checked
   only on the non-nil path.

### `convert` is the explicit narrowing primitive

`convert` already returns a boolean and binds the name on success
(`docs/md/SYNTAX.md` §"Type Conversion"; `interpreter.clj:2182`). That is exactly
the safe, non-throwing narrowing operator a refinement type wants:

```nex
if convert user_input to q: Quantity then
  -- q : Quantity, predicate held
else
  -- user_input was not positive
end
```

So `convert x to n: R` succeeds iff `P(x)` holds. The implicit `let n: R := x`
form is then just the throwing counterpart of the same check (a violated
narrowing raises, like a failed `require`). Reusing `convert` means the "how do I
safely build a refinement from untrusted input" story needs no new syntax.

## Where the work lands

Three front-to-middle layers; **codegen value representation is untouched.**

1. **Grammar / walker** — add the optional `WHERE IDENTIFIER ':' expression` (and
   `DISTINCT`) to `declareTypeDecl` (`grammar/nexlang.g4`,
   `walker.clj:935`). The walker records `{:type :refinement-type :name … :base …
   :binder … :predicate …}` (or keeps `:type-alias` when there is no `where`).
2. **Type checker** — register refinement names in the type environment beside
   classes and aliases. Teach `class-subtype?`/`types-compatible?`
   (`typechecker.clj:549`/`577`) the widen-free / narrow-checked rules, and mark
   each narrowing site so lowering knows to inject a check. `expand-type-aliases`
   (`typechecker.clj:401`) must *not* erase a refinement to its base the way it
   erases a plain alias — the name has to survive checking so narrowing is
   detected.
3. **Lowering** — at each marked narrowing site, inject a predicate assertion that
   reuses the existing contract path (`check-assertions` /
   `report-contract-violation`, `interpreter.clj:763`) and is elided under
   `skip-contracts` exactly like `require`/`ensure`/`invariant`. No new runtime
   primitive; no change to how Integers, Reals, or Strings are represented on the
   JVM.

The contrast with the `union` proposal is deliberate: `union` needed no
typechecker changes because it desugared to constructs the checker already
understood. Refinements genuinely extend the type lattice, so the checker must
change — but by erasing to the base and reusing the contract runtime, the change
stops at lowering and never reaches value representation.

## Contract-stripping story

Refinement checks are contracts, and they honor `skip-contracts` like every other
contract: development builds enforce them, production builds strip them, the
predicate remains in the source as executable documentation. This is consistent
with the language's stated production model — a stripped `Quantity` is "an Integer
that was validated in dev," never a runtime wrapper whose removal changes value
representation.

## Distinct newtypes (`distinct`)

```nex
declare distinct type UserId    = Integer
declare distinct type ProductId = Integer
```

A `distinct` type is nominal: it is **not** a subtype of its base, so `UserId` and
`ProductId` cannot be mixed, and neither flows silently into `Integer`.
Interconversion is explicit via `convert`. A `distinct` type may still carry a
`where` predicate. Because there is no implicit widening, `distinct` is the tool
for "these are both integers but must never be confused," whereas the plain
refinement is for "this is an integer with a constraint but otherwise an integer."

`distinct` needs one extra bit of runtime identity only if `match`/`convert` must
distinguish it at runtime; if it too erases to the base, cross-assignment is
caught statically and there is still no boxing. First cut: erase `distinct` as
well and rely on static checking, documenting that runtime dispatch cannot tell a
`distinct` newtype from its base.

## Scope boundaries and limitations

- **Operations drop the refinement** (rule 4). Re-narrow by flowing through a
  refinement-typed binding.
- **Collection elements.** `Array[Quantity]` checks on direct typed assignment
  where the element type is statically visible; through generic erasure, per-
  element checks on library inserts (`.add`) are out of scope for a first cut.
  Document that `Array[Quantity]` guarantees are boundary-checked, not
  deep-checked.
- **No runtime dispatch on refinements.** Because the value is erased to its base,
  `match`/`convert` cannot recover "was this a Quantity" at runtime — it is an
  Integer. Refinements are a compile-time-plus-boundary-check device, not a
  runtime tag. (A class is still the tool when you need runtime identity or
  behavior.)
- **Predicate purity.** The predicate must be side-effect free; enforcing purity
  fully is its own problem. A first cut can restrict predicates to the value,
  literals, operators, and whitelisted pure calls.
- **Quantifiers.** "Every element positive" wants Deficiency #5 (`forall`) inside
  the predicate; independent of this proposal.

## Suggested rollout

1. Grammar + walker: parse `where`/`distinct`, emit the refinement node; plain
   `declare type` unchanged.
2. Type checker: register refinements, implement widen-free/narrow-checked
   subtyping, flag narrowing sites.
3. Lowering: inject boundary predicate checks through the existing contract path;
   verify `skip-contracts` elides them on both backends.
4. Tests: (a) narrowing failure raises at the offending line; (b) widening never
   checks; (c) `convert x to q: Quantity` returns the right boolean; (d)
   `skip-contracts` removes all refinement checks; (e) `distinct` rejects
   cross-assignment statically.
5. Docs: a "Refinement Types" section in `SYNTAX.md` next to "Type Aliases".
6. Migrate the book's `Quantity`/`Percentage`-style scalars from classes to
   refinements and delete the boilerplate.

## Alternatives considered

- **Refinement as sugar over a one-field class + invariant.** This is exactly the
  status quo the deficiency is about: it reintroduces boxing and the interop tax
  (`Err[Quantity]` cannot stand in for `Err[Line_Item]`, per Deficiency #3). A
  lightweight refinement must erase to the base, which a class cannot.
- **A dedicated keyword** (`refine`, `constrain`, `subtype`). Extending
  `declare type` reuses an existing declaration and keeps the "it's still that
  type, just narrower" intuition; a plain alias is the same construct minus the
  `where`.
- **Predicate binder spellings.** A fixed pronoun (`where it > 0`) is terser but
  adds a reserved word and reads poorly with `.method()` calls; an explicit
  `where n: …` binder matches Nex's `label: condition` contract idiom and scales
  to string/collection predicates.
- **Implicit vs. explicit narrowing.** Requiring an explicit `convert` at every
  narrowing would be safer but reintroduces ceremony the deficiency is trying to
  remove. Injecting a checked narrowing at typed boundaries (with `convert`
  available when you want the non-throwing form) is the ergonomic middle that
  matches the contract-at-the-boundary philosophy.
