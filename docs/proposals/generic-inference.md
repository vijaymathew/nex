# Proposal: generic-argument inference & propagation

> **Status. Both findings implemented.**
> - *Finding 2 (match propagation)*: `check-match` carries the subject's type
>   arguments onto the clause variable via the variant's `inherit` mapping
>   (`match-clause-binding-type`), so `o.field` resolves with real element types.
>   This unblocked nested patterns in `richer-patterns.md` (also required fixing
>   generic `convert` in the interpreter and lowering a `convert`-in-guard on the
>   JVM).
> - *Finding 1 (construction inference)*: `check-create` infers a type-map from
>   the constructor's argument types (`create Ok.make(5)` → `Ok[Integer, Any]`);
>   parameters the constructor does not mention stay `Any`. To make partial
>   inference usable, `types-equal?`/`types-compatible?` now compare type
>   arguments element-wise with `Any` as a per-argument wildcard, so
>   `Ok[Integer, Any]` assigns to `Result[Integer, String]`. Explicit `[…]`
>   remains authoritative; concrete mismatches (`Full[String]` vs `Box[Integer]`)
>   still error.


Investigation of the generics limitation that keeps surfacing:

- `create Ok.make(5)` is rejected; you must write `create Ok[Integer, String].make(5)`.
- `match r of when Ok as o` binds `o` as a *raw* `Ok`, so `o.value` is `Option[Any]`,
  not `Option[Integer]` — which blocks nested patterns (`Ok(Some(x))`) and loses
  the element type of destructured fields.

These are two facets of one gap: Nex does not **infer** a generic type's arguments
from context, nor **propagate** them across a `match` binding. Both are
type-checker-only concerns — the runtime erases generics (behaviour is by base
class name; the interpreter/JVM already run construction and dispatch without the
arguments), so nothing below touches a backend's value representation.

## Finding 1 — construction ignores its arguments

`check-create` (`typechecker.clj:2504`, the user-class branch) computes:

```clojure
target-type (if (seq generic-args)
              {:base-type class-name :type-args generic-args}   ; explicit only
              class-name)                                        ; bare name, no args
type-map    (build-generic-type-map env target-type)            ; empty when bare
```

So with no explicit `[…]`, the result type is the *bare* class name and the
type-map is empty. `create Ok.make(5)` therefore has type `Ok` (no args); the
later assignment to `Result[Integer, String]` then fails because a raw `Ok` is not
seen as `Result[Integer, String]`.

The fix already exists elsewhere. The **function/method call** path
(`typechecker.clj:2235`) infers a type-map from the argument types:

```clojure
generic-names     (set (map :name (:generic-params class-def)))
inferred-type-map (reduce … (infer-generic-type-map-from-arg
                              env generic-names (:type param) arg-type) …)
```

`infer-generic-type-map-from-arg` (`typechecker.clj:1326`) already unifies a
parameter type against an argument type structurally (e.g. param `Option[T]` vs
arg `Option[Integer]` yields `{T → Integer}`). Construction just never calls it.

**Proposed fix.** In `check-create`'s user-class branch, when `generic-args` is
empty and the class is generic, run the same inference over the constructor's
parameters and arguments, then set

```clojure
target-type {:base-type class-name
             :type-args (mapv #(get inferred (:name %) "Any") (:generic-params class-def))}
```

`create Ok.make(5)` then infers `{T → Integer}` from `make(v: T)` and yields
`Ok[Integer, Any]`.

**Inherent limit — partial inference.** A type parameter that does not appear in
the constructor's parameters cannot be inferred: `Ok[T, E].make(v: T)` fixes `T`
but leaves `E` unconstrained (→ `Any`), and `Err[T, E].make(e: E)` fixes `E` but
not `T`. So `create Ok.make(5) : Ok[Integer, Any]` and
`create Err.make("x") : Err[Any, String]`. Whether these then assign to
`Result[Integer, String]` depends on `Any` compatibility in `types-compatible?`
(today `Any` is broadly compatible, so it is likely to work, but this is the place
to decide the policy). Explicit `[…]` remains available and authoritative whenever
inference is partial or the caller wants a specific instantiation.

## Finding 2 — `match` discards the subject's type arguments

`check-match` (`typechecker.clj:3010`) binds the clause variable to the bare
clause class name:

```clojure
(env-add-var clause-env var-name class-name)   ; e.g. "Ok", losing [Integer, String]
```

So inside `when Ok as o`, `o : Ok` (raw), and a field access `o.value` resolves
`value : T` with an empty type-map → `T` unresolved → `Option[Any]`. Simple
scalar uses tolerate this because `Any` is assignment-compatible, but strict
positions do not: `convert o.value to s: Some[Integer]` reports *"convert requires
related types, got Option[Any] and Some[Integer]"* — which is exactly why nested
patterns were deferred in the pattern-matching work.

The information needed is present: the match subject's type
(`expr-type = check-expression env expr`) carries its arguments
(`Result[Integer, String]`), and each variant's `inherit` clause records how its
own parameters map onto the parent's (`class Ok[T, E] inherit Result[T, E]`).

**Proposed fix.** When binding the clause variable, reconstruct the clause type's
arguments from the subject:

1. Let the subject be `Parent[A₁ … Aₙ]` and the clause class `Ok`, with
   `Ok`'s parents recording `inherit Parent[P₁ … Pₙ]` where each `Pᵢ` is one of
   `Ok`'s generic-param names.
2. For each of `Ok`'s generic params `G`, find the position `i` where `Pᵢ = G` in
   the parent's argument list and take `Aᵢ`. (This handles reordering/renaming;
   for the common identity case `Ok[T,E] inherit Result[T,E]` it just copies the
   subject's args.)
3. Bind `var-name` to `{:base-type "Ok" :type-args [resolved …]}` instead of the
   bare name.

Then `o : Ok[Integer, String]`, `o.value : Option[Integer]`, and
`convert o.value to s: Some[Integer]` is well-typed — **unblocking nested
patterns** and giving destructured fields their real element types.

The same reconstruction should apply to the destructuring `:bindings` the
pattern work introduced (they read `o.field`), so `when Placed(items)` gives
`items` its parameterized type rather than `Array[Any]`.

## Impact

- **Ergonomics.** `create Ok.make(5)` / `some(5)`-style construction stops
  requiring `[T, E]` noise wherever the arguments determine the parameters — the
  headline complaint against the Result/Option library.
- **Pattern matching.** Propagation gives destructured fields their true types and
  removes the blocker on **nested patterns** (Phase 3 of `richer-patterns.md`),
  which desugar through `convert` on a field.
- **No backend work.** Both are type-checker changes; the runtime already erases
  generics and dispatches by base name.

## Suggested phasing

1. **Match propagation (Finding 2) first.** It is self-contained
   (`check-match` + a small `inherit`-mapping helper), it is the higher-value fix
   (typed fields + unblocks nested patterns), and it has no partial-inference
   policy questions. Add tests: `o.value` typed, `convert`/nested pattern now
   type-checks, destructured field types.
2. **Construction inference (Finding 1).** Wire `infer-generic-type-map-from-arg`
   into `check-create`; decide the unconstrained-parameter policy (`Any` vs a
   "cannot infer, write `[…]`" error — recommend `Any` to stay permissive, since
   explicit args remain available). Tests: `create Ok.make(5) : Ok[Integer, Any]`,
   partial inference, assignment into `Result[Integer, String]`.
3. **Then** revisit nested patterns in `richer-patterns.md` (Phase 3) — with
   Finding 2 in place the deferred desugaring should type-check.

## Risks and open questions

- **Variance / assignment policy.** With partial inference producing `Any` in some
  positions (`Ok[Integer, Any]` → `Result[Integer, String]`), the behaviour hinges
  on how `types-compatible?` treats `Any` in a type argument. This should be
  pinned down with tests and, if needed, an explicit rule (invariant args with
  `Any` as a wildcard).
- **Inference from nested/collection args.** `infer-generic-type-map-from-arg`
  already recurses (`Array[T]` vs `Array[Integer]`), so `create Box.make([1,2,3])`
  should infer `T = Integer`; worth covering in tests.
- **`inherit`-mapping edge cases.** A variant that fixes a parent argument
  (`class Int_Ok inherit Result[Integer, E]`) or reorders params needs the
  position-mapping in Finding 2 rather than a blind copy; the helper must read the
  actual `:generic-args` on the `inherit` entry.
- **Non-generic subjects / already-raw values.** When the subject has no type
  args (a raw `Result`), propagation falls back to today's behaviour (bare clause
  name) — no regression.

## Recommendation

Implement **Finding 2 (match propagation)** first as a contained, high-value
type-checker change that also unblocks nested patterns, then **Finding 1
(construction inference)** to remove the `[T, E]` ceremony. Both are backend-free
and build directly on inference machinery the type checker already has.
