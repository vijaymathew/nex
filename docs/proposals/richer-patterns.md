# Proposal: richer pattern matching (destructuring, guards, literals, nesting)

Addresses **Deficiency #4** in `docs/language-notes-from-book.md`:

> Pattern matching is type-only. `match` dispatches on runtime class only — no
> destructuring, nested patterns, literal patterns, or guards. The DSL
> interpreter and the unifier (Ch 10–13) wanted structural destructuring; instead
> they nest `match` and use `convert`. **Proposal:** richer patterns
> (destructuring, nested, literals, `when`-guards).

> **Implementation status.** Phase 1 (field destructuring, rename/skip, optional
> `as`, and the `when _` catch-all) is pure walker sugar. Phase 2 (`if` guards)
> is implemented on the JVM and interpreter backends: a clause carries `:bindings`
> and `:guard`; the interpreter and JVM lowering bind → run bindings → test guard
> → body-or-fall-through; the type checker requires a Boolean guard and excludes
> guarded clauses from exhaustiveness. Guards are rejected by the JS backend
> (out of scope). Phase 3 **literal** field patterns (`field: 0`) are implemented
> as equality guards (walker sugar over Phase 2). Phase 3 **nested** patterns
> (`field: Some[Integer](value: x)`) are now **implemented** too, after landing
> Finding 2 of `generic-inference.md` (match binding carries the subject's type
> args) plus fixing generic `convert` in the interpreter and lowering a
> `convert`-in-guard on the JVM. A nested field pattern desugars to a
> `convert … to __nest: T` guard with the sub-pattern's binds moved into the body;
> sub-patterns are by-name and nest arbitrarily. Give the nested type its
> arguments for typed sub-fields (construction inference / Finding 1 would remove
> that need).

## Where things stand today

Two separate constructs exist:

- `match <e> of when <Type> as <v> then <block> … [else <block>] end` — dispatches
  on the runtime class and binds the *whole* value to `v`
  (`grammar/nexlang.g4` `matchClause`; `interpreter.cljc` `eval-node :match`;
  lowered to a chain of `convert`-based instanceof checks in
  `lower.cljc` `lower-match-clauses`).
- `case <e> of <lit>, … then <stmt> … [else] end` — dispatches on literal
  *values* (`caseClause`).

So literal dispatch already exists (via `case`), but `match` cannot reach *into*
a value: to read a variant's payload you bind the whole object and then write
`v.field`, and to branch on a nested value you nest another `match` and `convert`
— exactly the friction Ch 10–13 hit.

## Proposed surface

Extend a match clause to `WHEN pattern (AS id)? (IF guard)? THEN block`, where a
`pattern` can destructure a variant, match a literal, nest, or wildcard:

```nex
match order of
  when Draft                     then note_draft()
  when Placed(id, total)         then charge(id, total)      -- destructure by field name
  when Placed(id, total) if total > 1000 then flag(id)       -- guard
  when Shipped(tracking: t)      then track(t)               -- rename a field
  when Cancelled(_)              then noop()                 -- ignore a field
  when _                         then reject()               -- wildcard (catch-all)
end
```

- **Destructuring** `Placed(id, total)` binds the payload fields named `id` and
  `total` to locals of the same name (struct-shorthand style). `Placed(id: x)`
  renames a field into `x`; `_` in a field position ignores it. Field access is
  by name, matching how variant payloads are already named fields — so order does
  not matter and the pattern is self-documenting.
- **`as`** still binds the whole matched value and composes with destructuring
  (`when Placed(id, total) as p then …`), keeping every existing `match` valid.
- **Guards** `if <bool>` run after a structural match; a false guard falls through
  to the next clause.
- **Literal patterns** in a field position: `when Line(qty, 0)` matches only when
  the second field equals `0`. (Whole-value literal dispatch stays the job of
  `case`; literals in `match` are for field and nested positions.)
- **Nested patterns** `when Ok(Some(x))` apply a pattern to a field's value.
- **Wildcard** `_` as a top-level pattern is a catch-all (a spelling of `else`).

Grammar sketch:

```antlr
matchClause : WHEN pattern (AS IDENTIFIER)? (IF expression)? THEN block ;

pattern
    : '_'
    | literal
    | typeName typeArgs? ('(' fieldPattern (',' fieldPattern)* ')')?
    ;

fieldPattern
    : '_'                                   // ignore this field
    | IDENTIFIER                            // bind field <name> to local <name>
    | IDENTIFIER ':' IDENTIFIER             // bind field <name> to local <renamed>
    | literal                               // field must equal this literal
    | typeName typeArgs? ('(' fieldPattern (',' fieldPattern)* ')')?  // nested
    ;
```

## Implementation — phased by cost

The three sub-features have very different costs, so ship them in order. The
first is a pure front-end desugaring (like `union`); the later ones touch
dispatch and exhaustiveness.

### Phase 1 — destructuring + wildcard (walker-only, no backend changes)

Simple destructuring never fails: if the type matches, the bindings always
succeed. So `when Placed(id, total) then <body>` desugars in the walker
(`:matchClause`, `walker.cljc:1102`) to the existing whole-value form plus
leading `let`s:

```nex
when Placed as __m0 then
  let id: <inferred> := __m0.id
  let total: <inferred> := __m0.total
  <body>
```

- Because bindings are by field *name*, the walker needs no class information —
  it emits `__m0.<field>` and the type checker validates the field exists
  (`__m0.id`), so no typechecker/interpreter/lowering change is required.
- `_` at top level lowers to the existing `else` block.
- This alone removes the bind-then-`v.field` boilerplate the deficiency names,
  and it is the change the DSL/unifier chapters most wanted.

### Phase 2 — guards (`if`)

A guard can fail after a type match, so control must fall through to the *next*
clause — which plain nesting cannot express. Guards need real dispatch support:

- **Interpreter** (`eval-node :match`, `interpreter.cljc`): after a clause's type
  matches and its bindings are made, evaluate the guard in that scope; if false,
  continue to the next clause instead of committing.
- **Lowering** (`lower-match-clauses`, `lower.cljc:1121`): fold the guard into the
  per-clause condition — `if (convert … to v:T) and <guard> then body else
  <next>` — reusing the existing convert-chain shape.
- **Exhaustiveness** (`check-match`, `typechecker.cljc:2994`): a guarded clause no
  longer guarantees its variant is covered, so it must *not* count toward
  exhaustiveness. A sealed match whose only clause for a variant is guarded now
  requires an unguarded clause, a wildcard, or `else`.

### Phase 3 — literal and nested field patterns

These share the same fall-through machinery as guards, applied to sub-values:

- A literal field pattern desugars to a guard: `Line(qty, 0)` ≡
  `Line(qty, __f1) if __f1 == 0`. Once Phase 2 exists, literals are mostly a
  walker rewrite into a binding + an equality guard.
- A nested type pattern `Ok(Some(x))` desugars to a bind plus an inner structural
  test that can fail: bind the field, then require it to match `Some(x)` — a
  nested match that, on failure, falls through to the outer clause's successor.
  This reuses the Phase-2 fall-through path; the walker expands one level of
  nesting into an inner guarded test.
- **Exhaustiveness**: as with guards, clauses carrying literal/nested constraints
  do not count as covering their variant.

## Interactions and edge cases

- **Definite assignment** already handles `match` (an exhaustive `match` assigns
  `result` on all paths, per the recent fix). Guards/literals/nested make a
  clause conditional, so — consistent with exhaustiveness — such clauses should
  not be assumed to run; the analysis already treats a non-exhaustive match (no
  `else`, missing coverage) as not definitely assigning.
- **Binding scope and types.** Destructured names are ordinary locals scoped to
  the clause body; their types are the payload field types (inferred), so no new
  type machinery is needed for Phase 1.
- **`case` stays.** Pure literal-value dispatch remains `case`'s job; `match`
  gains literals only where they compose with structure. The two need not merge.
- **Redundancy/reachability.** Optional polish: warn when an unguarded clause is
  shadowed by an earlier one, or when a guard chain is provably total. Not
  required for a first cut.
- **JavaScript backend.** Out of scope by request; Phase 1 is backend-agnostic
  (pure walker sugar) so it works wherever `match` already does, and Phases 2–3
  target the interpreter and JVM lowering only.

## Suggested rollout

1. **Phase 1**: grammar `pattern`/`fieldPattern` for the destructure/`_` subset;
   walker desugaring to `as` + leading `let`s; tests that a destructuring match
   equals the hand-written bind-and-access form (AST and runtime). No backend
   change.
2. **Phase 2**: guard syntax; interpreter + lowering dispatch; exhaustiveness
   treats guarded clauses as non-covering; tests for fall-through and for the
   "guarded-only ⇒ needs else" error.
3. **Phase 3**: literal field patterns (desugar to equality guards) and one level,
   then arbitrary nesting, of nested type patterns; exhaustiveness and
   reachability tests.
4. Docs: extend `SYNTAX.md` "Match Statement" with the pattern grammar and the
   `case`-vs-`match` guidance.

## Alternatives considered

- **A single unified `match` that subsumes `case`.** Cleaner in theory, but `case`
  already ships and is value-oriented; folding it in is churn without new power.
  Keep `case` for literal dispatch, grow `match` for structure.
- **Positional destructuring** (`Placed(x, y)` binds by field order). Rejected as
  the primary form: it needs field-order resolution (class info the walker lacks)
  and is position-fragile. By-field-name shorthand keeps Phase 1 a pure walker
  rewrite and reads better; a positional form could be added later in the
  typechecker if desired.
- **`where` for guards.** `where` is now the refinement-type soft keyword, and
  `if` reads naturally as a guard; using `if` avoids overloading `where`.
