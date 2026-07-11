# Changelog

## Unreleased

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
