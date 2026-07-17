# Changelog

## Unreleased

- **Breaking: in a `match` field pattern, `:` constrains and `as` renames.**
  A field pattern's colon used to do three unrelated jobs — pin a field to a
  literal (`Move(dx: 0)`), narrow it to a type (`Ok(inner: Some(value))`), and,
  with a bare identifier, *rename* it (`Shipped(tracking: t)`). The rename reads
  exactly like the type annotation `x: T` is everywhere else in Nex while
  meaning the opposite, and it collided with the type form: dropping the parens
  from a working nested pattern silently flipped "test the type" into "rename to
  a local". A builtin type name in that position was not even spellable —
  `String` is a keyword token, not an identifier, so `when Err(s: String)` was a
  *syntax* error.

  Now the name left of the colon is always a field, `:` always constrains it
  (to a literal or a type), and renaming moves to `as`, which already means
  "bind under this name" at clause level:

  ```nex
  when Shipped(tracking as t)   then track(t)         -- was: tracking: t
  when Box(content: Circle)     then use(content)     -- narrows and binds
  when Box(content: String)     then say(content)     -- builtins now spellable
  when Ok(inner: Some[Integer](value as x)) then use(x)
  ```

  A bare `field: Type` narrows the field and binds it under its own name; with
  sub-patterns you reach the value through them, so the field itself is not
  bound. Like guards and literal patterns, a type pattern is a test and does not
  count toward exhaustiveness. To ignore a field, simply do not name it.

  **Migration.** The old spellings are rejected, not reinterpreted, and the error
  names the fix: `` `t` is not a type. To bind the field to a local named `t`,
  write `tracking as t` ``. For `field: _`, it says to omit the field instead.

- **New: operator aliases** — a class feature can bind itself to an arithmetic
  operator with an `alias` clause (`minus(other: Money): Money alias "-"`). The
  operator becomes exactly sugar for the call, so the feature's `require` and
  `ensure` clauses hold at the operator too: `a - b` checks `same_currency` just
  as `a.minus(b)` does. Aliases are inherited, so an operator declared on a
  deferred parent dispatches to a descendant's override.

  The mechanism is deliberately narrow. Only `+ - * / % ^` may be aliased — no
  new symbols can be invented — and only arithmetic: ordering already dispatches
  through `Comparable`'s `compare` and `=` through `equals`, so a class earns the
  comparison operators by inheriting `Comparable`, not by aliasing. An alias is
  consulted only after the numeric (and, for `+`, String) paths decline, so it
  can never shadow built-in arithmetic.

  Built-in `Integer`/`Real` arithmetic is unaffected at runtime: for a program
  that declares no alias, the emitted bytecode is byte-for-byte identical to
  before, and lowering costs one set-membership test per binary node.

  `alias` is a soft keyword (like `union` and `where`): it means this only in a
  routine signature, and remains usable as the name of a variable, field,
  parameter, or routine. Nothing that parsed before this change stops parsing.

  Also fixed: `nex format` now preserves an `alias` clause instead of dropping it.

- **Fixed: constrained generics now compile.** `function f[T -> Bound](…)` could
  call the routines of `Bound` on a `T` only when `Bound` was a builtin such as
  `Comparable`; a user-class bound type-checked and ran on the interpreter but
  failed to compile with "Unsupported target call expression for lowering". The
  receiver is an ordinary Nex object at runtime, so it now lowers to the same
  dynamic dispatch any user-class call uses. Reading a *field* through a bound
  works as well, and a no-arg routine written without parentheses (`x.describe`)
  dispatches as a routine rather than being misread as a field access.

## 0.2.0 - 2026-07-11

- **New: `union` declarations** — a concise syntax for sum types. `union Name`
  followed by a list of named variants (`Placed(id: String, total: Real)`, or a
  bare name for a payload-free variant) declares a closed family of data
  variants. It desugars to the existing `sealed deferred class` parent plus one
  inheriting class per variant with an auto-generated `make` constructor, so
  construction, generics, `match`, and exhaustiveness checking are unchanged. A
  `union` declares data only; a variant needing methods, invariants, or a
  constructor contract stays in the explicit sealed-class form. `union` is a
  soft keyword (it remains usable as a member name such as `Set.union`).
- **New: refinement types** — `declare type Quantity = Integer where n: n > 0`
  narrows a base type by a predicate. A refinement is not a class: it is erased
  to the base representation (a `Quantity` *is* an `Integer`, usable in any
  arithmetic), and the predicate is checked only where a base value is narrowed
  in — a typed `let`, a parameter, a return, or a `convert`. Widening is free,
  operations on refinements yield the base type, and the checks are elided under
  `skip-contracts` like every other contract. `where` is a soft keyword.
- **New: richer pattern matching** — a `match` clause can now destructure a
  variant's fields by name (`when Placed(id, total)`), rename a field
  (`when Shipped(tracking: t)`), ignore one (`_`), require a literal field
  value, match nested patterns, and carry an `if` guard evaluated after the
  structural match. Guarded, literal, and nested clauses do not count toward
  exhaustiveness, so a variant covered only by such a clause still needs an
  unguarded clause, a wildcard, or `else`. Destructuring is pure walker sugar;
  guards, literals, and nesting run on the JVM and interpreter backends.
- **New: standard `Result` and `Option`** — `intern data/Result` and
  `intern data/Option` ship `Result[T, E]` (`Ok` / `Err`, with independent
  value and error types so errors thread up without rewrapping) and `Option[T]`
  (`Some` / `None`). Query and unwrap are methods (`is_ok`/`is_err`/`unwrap_or`,
  `is_some`/`is_none`/`get_or`); the transforming combinators are free functions
  (`result_map`/`result_and_then`/`result_map_err`,
  `option_map`/`option_and_then`/`option_filter`). Three enabling changes made
  this possible: `intern` now exports a library's free functions (not only its
  classes) to the typechecker and compiled backend; `match` dispatches on a
  generic sealed class's base name at runtime; and an exhaustive `match` with no
  `else` now satisfies definite assignment (a combinator whose whole body is
  such a match no longer needs a dummy default).
- Fixed a compiled-backend lowering failure ("Unable to infer expression type
  during lowering") on a nested `match` whose inner subject is a method call on
  a value of a generic type parameter (e.g. `ok.value.resolve(…)`); the program
  type-checked but could not be lowered, though it ran under `--interpret`.
- Fixed a `StackOverflowError` in the interpreter (`nex.types.builtins/nex-object?`)
  triggered by the same nested-match-over-a-recursive-method construct.
- **Breaking:** the **JavaScript backend has been removed**, leaving a single
  JVM implementation. The Nex→JavaScript generator (`nex.generator.javascript`),
  the ClojureScript/Node interpreter runtime (shadow-cljs, `package.json`,
  `nex-wrapper.js`, `bin/nex-node.js`, the platform-diff harness), and the
  `nex compile js` / `./install.sh nodejs` targets are all gone. The interpreter,
  typechecker, lowering, and runtime source moved from `.cljc` to `.clj` and the
  `#?(:cljs …)` reader conditionals were dropped. `nex compile` now accepts only
  the `jvm` target. The `import X from "…"` and `with "javascript"` surface
  syntax still parses but has no backend that consumes it.

- **Breaking:** function and method parameters are now **contravariant** and
  return types **covariant** (previously parameters were covariant). A function
  value or overriding routine may *widen* a parameter and *narrow* a return, but
  not the reverse — making conformance a sound, local check. Override conformance
  is now enforced at the definition site (an override that narrows a parameter or
  returns a non-conforming type is rejected there, naming the routine and
  position), which also closes a case where a non-conforming return override was
  previously accepted. Function-value assignment now enforces the same rule
  (it was previously checked too leniently). Generic signatures are resolved
  through inheritance before the check, so an override of a method inherited from
  e.g. `Container[Integer]` is verified with the type parameter substituted.
  To keep a covariant-style override, retain the wider parameter type and narrow
  inside the body with `convert`/`match`, or use generics. See
  `docs/md/VARIANCE.md`.
- **Breaking:** the `when` expression now requires `then` before the consequent,
  matching the `when ... then` shape used by `match`/`select` clauses.
  Write `when cond then a else b end` instead of `when cond a else b end`.
- **Breaking:** the remainder operator `%` is **truncated** (sign of the
  dividend, like C/Java) on every backend, for Integer and Real alike:
  `-7 % 3` is now `-1` everywhere. Previously the interpreter used floored
  (Python-style) semantics while compiled code truncated, so programs could
  observe different results per backend.
- **Breaking:** `convert` never changes numeric representation: a statically
  numeric-to-numeric conversion (`convert i to r: Real` with `i: Integer`, or
  the reverse) is now a compile-time error. An Integer already widens
  implicitly where a Real is expected, and `Real.round()` yields an Integer.
  At runtime, an `Any`-sourced convert to a different numeric class yields
  `false` on both backends (previously the compiled backend crashed).
- **Breaking:** `nex <file>.nex` runs on the compiled JVM backend **only**.
  A program outside the compiled subset is an error naming the unsupported
  construct; pass `--interpret` to run on the tree-walking interpreter
  explicitly. A runtime failure of the compiled program is reported as the
  program's outcome — the program is no longer silently re-executed under the
  interpreter (side effects now run exactly once).
- **Breaking:** the REPL's `:backend interpreter|compiled|status` commands are
  removed; the compiled backend is the only REPL backend. The debugger
  (`:debug on`, breakpoints, watchpoints) is unaffected.
- **Breaking:** `lib/time` and `lib/io/path` used the undeclared type name
  `Integer64`; those signatures now say `Integer` (which is 64-bit).
- Numeric conformance fixes, identical on both backends per the Definition's
  §B.3: NaN ordering follows IEEE (every `<` `<=` `>` `>=` against NaN is
  false); `MIN_LONG / -1` raises on overflow; integer division by zero reports
  "Division by zero" (compiled code previously leaked the host's "/ by zero");
  `5 == 5.0` is `true` (`==` coincides with `=` on scalars); the interpreter's
  32-bit bitwise operations no longer raise on values that overflow an int
  (`(1).bitwise_left_shift(31)` is `-2147483648` everywhere).
- Fixed `convert` bindings: the bound variable now lives in a reference slot
  (it is detachable), so a failed convert binds `nil` and yields `false`
  instead of crashing (NPE, or a VerifyError when the guarded branch narrowed
  the type).
- Fixed compiled-backend dispatch bugs: methods inherited from a generic
  parent no longer fail with `ClassNotFoundException` for the type parameter;
  calls on ordinary Nex values inside a `with "java"` block dispatch normally
  instead of being routed to host reflection; a method call on a nil receiver
  reports "Used a value that is void (nil)" instead of a raw JVM message.
- Closed the remaining compiled-subset gaps, so the whole standard library and
  every bundled example compile: named constructors on imported Java classes,
  `create Map` / `create Set`, the full builtin free-function return-type
  table (datetime/regex/path/file/json/http), paren-less builtin-method
  chains (`x.to_string`), and a compiler crash on `examples/apl.nex`.
- Internal: the builtin runtime — the per-type method table, free-function
  builtins, tasks/channels, heaps/atomics, Java interop — moved out of the
  interpreter into the engine-neutral `nex.types.builtins` and
  `nex.types.concurrency` namespaces (engine specifics injected via
  `set-engine-hooks!`). `nex.lower` no longer depends on `nex.interpreter`.
  The backend-alignment plan and remaining work are tracked in
  `docs/md/BACKEND_ALIGNMENT.md`.

## 0.1.1-beta - 2026-03-23

- Made the JVM-compiled backend the default REPL backend.
- Kept automatic interpreter fallback for unsupported inputs.
- Kept `:backend interpreter` as the explicit escape hatch.
- Added `.nex -> .class` and standalone shaded JAR compilation via `compile jvm`.
- Completed broad JVM compiler coverage across:
  - classes, inheritance, `super-calls`, generics, `convert`
  - contracts and exceptions
  - closures and higher-order functions
  - concurrency, channels, tasks, `select`
  - `import`, `intern`, and `with "java"`
- Added compiled REPL soak and parity coverage for long progressive sessions.
- Added interpreter and compiled validation for tutorial and book examples.
- Added micro and soak performance gates for the compiled REPL.
- Added a JVM bytecode translation reference to the design book.
