# Changelog

## Unreleased

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
