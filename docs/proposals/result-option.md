# Proposal / implementation: standard Result & Option + error-propagation

Addresses **Deficiency #3** in `docs/language-notes-from-book.md`:

> No stdlib `Result`/`Option`, and no error-propagation sugar. `Result[T]` had to
> be hand-defined; threading failures meant manual `match`-and-rewrap at every
> level (no `?` / `and_then` / bind / do-notation). Typed errors also do not
> compose: `Err[Quantity]` cannot stand in for `Err[Line_Item]`. **Proposal:**
> standard `Result`/`Option`, a bind/`?` operator, and a shared error-channel
> story.

## What shipped

A stdlib for `Result` and `Option`, plus three enabling fixes that were the real
reason a combinator library could not be written before.

### The library (`lib/data/result.nex`, `lib/data/option.nex`)

`intern data/Result` / `intern data/Option` bring in the sealed sum types, their
variants, and the combinator functions.

- `Result[T, E]` = `Ok(value: T)` | `Err(error: E)`
- `Option[T]` = `Some(value: T)` | `None`
- Methods (type-preserving): `is_ok`/`is_err`/`unwrap_or` on `Result`;
  `is_some`/`is_none`/`get_or` on `Option`.
- Free-function combinators (type-changing): `result_map`, `result_and_then`,
  `result_map_err`; `option_map`, `option_and_then`, `option_filter`.

`result_and_then` is the bind that collapses the manual `match`-and-rewrap the
deficiency complains about to a single call, short-circuiting on the first `Err`:

```nex
let out: Result[Integer, String] :=
  result_and_then(r, fn (x: Integer): Result[Integer, String] do result := step(x) end)
```

`result_map_err` is the shared-error-channel answer: it converts the error type
(`Err[E1]` → `Err[E2]`), so a lower-level error composes into a caller's error
type explicitly.

### Three enabling fixes

These are the substance — the library is a thin layer over them, and each is a
general correctness/capability improvement independent of Result/Option.

1. **`intern` exports free functions** (`interpreter.clj`, `eval.clj`,
   `compiler/jvm/file.clj`). Previously `intern` brought in only classes, so a
   library could not ship helper/combinator *functions*. `resolve-interned*` now
   also collects `:functions`, merged into the program for the typechecker and
   the compiled backend. (The interpreter already registered them at runtime when
   it evaluated the module, so only static analysis and compilation needed it.)

2. **`match` dispatches on generic sealed classes at runtime**
   (`interpreter.clj`, `eval-node :match`). A generic instance carries its
   *specialized* class name (`Ok[Integer,String]`), but a `when` clause names the
   base (`Ok`), so the interpreter matched nothing and raised "No matching clause
   in match". The dispatch now also compares the base name. Without this, no
   generic sum type — including a hand-written `Result` — could be matched under
   the interpreter.

3. **An exhaustive `match` satisfies definite-assignment** (`typechecker.clj`,
   `result-definitely-assigned-after-stmt`). A `match` with no `else` that
   type-checked is exhaustive over a sealed type, so it has no fall-through path;
   the analysis now treats it as assigning `result` on all paths when every clause
   does. Before, a combinator whose whole body was an exhaustive match was
   rejected ("does not definitely assign result on all returning paths") and
   needed a dummy default — exactly the boilerplate the deficiency names.

## Design choices and why

- **Free functions for `map`/`and_then`/`map_err`, methods for the rest.** Nex
  has no method-level generics (`methodDecl` takes no `genericParams`), so a
  combinator introducing a fresh type variable (`map[U]`) cannot be a method. The
  type-preserving ones (`unwrap_or`, `is_ok`, …) *can* be methods, and read better
  as `r.unwrap_or(0)`, so those stay methods. Adding method-level generics would
  let the whole API be methods (`r.map(f).and_then(g)`) — a worthwhile follow-up.
- **Sealed classes, not the `union` shorthand.** `union` is data-only; the types
  need the deferred `is_ok`/`unwrap_or` methods, so they are written as explicit
  sealed classes.
- **Explicit type args at construction.** Nex does not infer generic arguments at
  `create` (`create Ok.make(5)` fails; `create Ok[Integer,String].make(5)`
  works). The library and its docs use explicit args; generic-argument inference
  at construction is a separate follow-up.

## Not yet done

- **The `?` propagation operator.** Rust-style `expr?` (return the `Err`/`None`
  early, else bind the inner value) is the remaining sugar. It needs an
  early-exit desugaring, and Nex has no early `return` — a function sets `result`
  and falls through — so `?` requires either an early-return mechanism or a
  monadic-bind rewrite of the enclosing function body. Deferred as its own piece.
- **Automatic typed-error composition.** `Err[E1]` still does not *implicitly*
  stand in for `Err[E2]`; `result_map_err` makes the conversion explicit. Implicit
  widening would need variance rules in the type system.
- **Method-level generics** (would make `map`/`and_then` methods) and
  **generic-argument inference at construction** (would drop the `[T, E]` noise) —
  both would improve ergonomics and are tracked as follow-ups.
- **JavaScript backend.** The library runs on the JVM (compiled) and interpreter
  backends. The JS backend has pre-existing gaps in its *free-function* output
  (generated class modules are not exported, and the `Function` base class is
  emitted after the classes that extend it), so a program using the `result_*` /
  `option_*` functions does not yet run under the JS backend. Fixing the JS
  free-function module wiring is an independent backend task; the JS generator is
  intentionally left untouched here.
