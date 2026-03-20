# Changelog

## 0.1.1 - 2026-03-20

- Made the JVM-compiled backend the default REPL backend.
- Kept automatic interpreter fallback for unsupported inputs.
- Kept `:backend interpreter` as the explicit escape hatch.
- Added `.nex -> .class` and standalone shaded JAR compilation via `compile jvm`.
- Completed broad JVM compiler coverage across:
  - classes, inheritance, `super`, generics, `convert`
  - contracts and exceptions
  - closures and higher-order functions
  - concurrency, channels, tasks, `select`
  - `import`, `intern`, and `with "java"`
- Added compiled REPL soak and parity coverage for long progressive sessions.
- Added interpreter and compiled validation for tutorial and book examples.
- Added micro and soak performance gates for the compiled REPL.
- Added a JVM bytecode translation reference to the design book.
