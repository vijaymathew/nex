# Compiler Feature Gap

This note tracks the gap between the tree-walking interpreter and the experimental JVM bytecode compiler. It lists what is already compiled and what still deopts to the interpreter.

Last updated: 2026-03-19


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
| Modules | `import` metadata, imported Java class creation/calls on the compiled path, and `intern` resolution for local `.nex` files |
| File compilation | `.nex` file compilation to JVM `.class` files, including launcher emission and source-relative `intern` handling |
| Note annotations | Parsed and preserved as documentation metadata; ignored by lowering/emission as intended |
| Nil-safety | Detachable types (`?Type`), nil checks, `convert` guards, and branch refinement on compiled path |
| Builtins via runtime bridge | `print`, `println`, `type_of`, `sleep`, and other remaining runtime-backed builtins |


## Still Needs Implementation

### Object-Oriented

### Design by Contract

### Exception Handling

### Misc

- HTTP/JSON built-ins as direct codegen
- User-facing REPL wrapping — some larger statement-shaped inputs still bypass the compiled path even when the internal helper supports them

### Meta / Infrastructure

- Source line numbers in emitted bytecode (debug metadata)


## Suggested Next Candidates

Based on complexity and impact, these are natural next steps:

1. Thin packaging on top of the new `.class` pipeline (`.jar`/classpath ergonomics) rather than REPL-only execution
2. Broader direct lowering for remaining runtime-backed builtins that still use the generic runtime bridge
3. Source line numbers and better debug metadata in emitted bytecode
