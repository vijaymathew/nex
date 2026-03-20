# Compiler Feature Gap

This note tracks the gap between the tree-walking interpreter and the JVM bytecode compiler. It lists what is already compiled and what still deopts to the interpreter.

Last updated: 2026-03-20


## Already Compiled

| Feature | Notes |
|---|---|
| Literals | Integer, Real, String, Char, Boolean, nil, Array, Map, Set |
| Variables | Local load/store, top-level REPL get/set |
| Arithmetic / Operators | `+`, `-`, `*`, `/`, `%`, `^`, `and`, `or`, `not`, string `+`, Integer bitwise methods |
| Comparisons | `=`, `/=`, `<`, `<=`, `>`, `>=` |
| `if` / `elseif` / `when` | Expression and statement lowering on compiled path |
| Functions | Declare, define, redefine, mutual recursion |
| Classes | Fields, methods, constructors, constants |
| Deferred classes | Abstract methods, concrete children |
| Inheritance | Single and multi-parent, virtual dispatch, `super` on the composition model |
| Generic classes | Erased compiled support for parameterized user classes such as `Box[T]` |
| `convert ... to` | Guard and standalone-statement lowering on compiled path |
| Class invariants | Enforced on compiled creation, instance methods, and delegated inherited methods |
| Object-model validation | Compiled runtime validates user-object layout against stored class AST metadata |
| Loops | `from/until/do`, `repeat`, `across`, including invariant/variant checking |
| `case` | Statement-form lowering to compiled branches |
| Scoped blocks | `do...end` lexical scope, including `rescue` / `retry` |
| Exceptions | `raise`, `rescue`, `retry` |
| Contracts | `require`, `ensure`, and the current interpreter-style field-based `old` model in compiled method/constructor postconditions |
| Concurrency | `spawn`, channel creation, `select`, `await_all`, `await_any`, and compiled-path `Task` / `Channel` operations |
| Collections | Literal construction plus direct compiled lowering for Array / Map / Set methods |
| Closures / higher-order | Anonymous functions, captured closures, and passing/returning/invoking function objects |
| Modules | `import` metadata, imported Java class creation/calls on the compiled path, `intern` resolution for local `.nex` files, and JVM-side lowering of `with "java"` blocks |
| File compilation | `.nex` file compilation to a standalone shaded JVM jar, including launcher emission and source-relative `intern` handling |
| Note annotations | Parsed and preserved as documentation metadata; ignored by lowering/emission as intended |
| Nil-safety | Detachable types (`?Type`), nil checks, `convert` guards, and branch refinement on compiled path |
| Builtins | The supported builtin surface now lowers through direct helpers, specialized receiver lowering, collection IR, or concurrency IR rather than the older generic builtin trampoline |


## Still Needs Implementation

### Remaining Compiled REPL / Compiler Gaps

- debugger interaction is safe through interpreter routing, but not a native compiled-bytecode debugger experience
- the deopt surface is small and documented, but still intentionally mixed-mode rather than pure compiled execution
- some remaining host/runtime cases still rely on helper/runtime calls rather than bespoke bytecode sequences
- debug metadata is good enough for line/source navigation, but local-variable live ranges are still first-use/last-use rather than fully block-precise


## Suggested Next Candidates

Based on complexity and impact, these are natural next steps:

1. Further debug-info tightening such as more precise block-scoped local live ranges where the current first-use/last-use tables are still conservative
2. Continued reduction of runtime-helper boundaries where direct lowering would materially improve maintainability or observability
3. Ongoing hardening of compiled-default REPL behavior through soak, parity, and debugger workflow coverage
