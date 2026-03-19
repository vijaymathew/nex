# Compiler Feature Gap

This note tracks the gap between the tree-walking interpreter and the experimental JVM bytecode compiler. It lists what is already compiled and what still deopts to the interpreter.

Last updated: 2026-03-19


## Already Compiled

| Feature | Notes |
|---|---|
| Literals | Integer, Real, String, Char, Boolean, nil |
| Variables | Local load/store, top-level REPL get/set |
| Arithmetic / Operators | `+`, `-`, `*`, `/`, `%`, `^`, `and`, `or`, `not`, string `+`, Integer bitwise methods |
| Comparisons | `=`, `/=`, `<`, `<=`, `>`, `>=` |
| `if` / `elseif` / `when` | Expression and statement lowering on compiled path |
| Functions | Declare, define, redefine, mutual recursion |
| Classes | Fields, methods, constructors, constants |
| Deferred classes | Abstract methods, concrete children |
| Inheritance | Single and multi-parent, virtual dispatch |
| Loops | `from/until/do`, `repeat`, `across`, including invariant/variant checking |
| `case` | Statement-form lowering to compiled branches |
| Scoped blocks | `do...end` lexical scope, including `rescue` / `retry` |
| Exceptions | `raise`, `rescue`, `retry` |
| Contracts | `require`, `ensure`, `old` in compiled method/constructor postconditions |
| Builtins via runtime bridge | `print`, `println`, `type_of`, `sleep`, collection methods, etc. |


## Still Needs Implementation

### Control Flow

- `select`

### Object-Oriented

- `super` calls in overriding methods
- Generic/parameterized classes (`Box[T]`, `Array[String]`)
- `convert ... to` (type casting)

### Design by Contract

- Class invariants
- Broader `old` support beyond the current compiled field-snapshot model used for method/constructor postconditions

### Exception Handling

- File/module compilation path for exceptions and contracts beyond the REPL/compiler helper path

### Concurrency

- `spawn do...end` (create async tasks)
- `Task` lifecycle — `await`, `cancel`, `is_done`
- `Channel` — `send`, `receive`, `try_send`, `try_receive`, `close`
- `select` (multiplex over channels/tasks with timeout)
- `await_all`/`await_any` as direct codegen (currently runtime-bridged)

### Closures & Higher-Order

- Anonymous functions (`fn (x) do...end`)
- Closures capturing enclosing scope
- Higher-order functions (passing/returning function objects)

### Collections

- Array literals (`[1, 2, 3]`)
- Map literals (`{"a": 1, "b": 2}`)
- Set literals (`{1, 2, 3}`)
- Direct codegen for collection methods (currently all go through runtime bridge)

### Modules

- `import` (load external/Java classes)
- `intern` (load local `.nex` files)

### Misc

- `note` annotations (documentation metadata)
- Nil-safety (`?Type` detachable types, nil checks)
- Graphics/Turtle built-ins (JVM-only drawing API)
- HTTP/JSON built-ins as direct codegen
- User-facing REPL wrapping — some larger statement-shaped inputs still bypass the compiled path even when the internal helper supports them

### Meta / Infrastructure

- Source line numbers in emitted bytecode (debug metadata)
- File compilation (currently REPL-only; no `.nex` → `.class` pipeline)


## Suggested Next Candidates

Based on complexity and impact, these are natural next steps:

1. Class invariants
2. `super` on the multiple-inheritance composition model
3. `select` and direct concurrency lowering
4. Closures / anonymous functions
5. File compilation (`.nex` -> JVM classes/jar) beyond the REPL-only compiler path
