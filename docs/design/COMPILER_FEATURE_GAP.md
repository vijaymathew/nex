# Compiler Feature Gap

This note tracks the gap between the tree-walking interpreter and the experimental JVM bytecode compiler. It lists what is already compiled and what still deopts to the interpreter.

Last updated: 2026-03-19


## Already Compiled

| Feature | Notes |
|---|---|
| Literals | Integer, Real, String, Char, Boolean, nil |
| Variables | Local load/store, top-level REPL get/set |
| Arithmetic | `+`, `-`, `*`, `/` |
| Comparisons | `=`, `/=`, `<`, `<=`, `>`, `>=` |
| `if` / `elseif` / `when` | Expression and statement lowering on compiled path |
| Functions | Declare, define, redefine, mutual recursion |
| Classes | Fields, methods, constructors, constants |
| Deferred classes | Abstract methods, concrete children |
| Inheritance | Single and multi-parent, virtual dispatch |
| Loops | `from/until/do`, `repeat`, `across` (no invariant/variant) |
| `case` | Statement-form lowering to compiled branches |
| Scoped blocks | `do...end` lexical scope without `rescue` |
| Builtins via runtime bridge | `print`, `println`, `type_of`, `sleep`, collection methods, etc. |


## Still Needs Implementation

### Control Flow

- Loop invariants and variants (contract checking in loops)
- Scoped blocks with `rescue`

### Operators

- Logical operators as direct bytecode: `and`, `or`, `not`
- Unary negation (`-x`)
- Modulo (`%`), power (`^`)
- String concatenation (`+` on strings)
- Bitwise operators (shift, rotate, and/or/xor/not, set/unset)

### Object-Oriented

- `super` calls in overriding methods
- Generic/parameterized classes (`Box[T]`, `Array[String]`)
- Selective visibility (`private`, `-> Friend`)
- `convert ... to` (type casting)

### Design by Contract

- `require` (preconditions)
- `ensure` (postconditions)
- `old` expressions (capture pre-state for postconditions)
- Class invariants

### Exception Handling

- `raise` (throw)
- `rescue` (catch, within `do...rescue...end`)
- `retry` (restart enclosing block)

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
- User-facing REPL wrapping — statement-shaped inputs (`let`, `if`, `from`, `do`) still bypass the compiled path even when the internal helper supports them

### Meta / Infrastructure

- Source line numbers in emitted bytecode (debug metadata)
- File compilation (currently REPL-only; no `.nex` → `.class` pipeline)


## Suggested Next Candidates

Based on complexity and impact, these are natural next steps:

1. `elseif` branches
2. Logical operators (`and`, `or`, `not`)
3. String concatenation
4. `across` loops
5. Exception handling (`raise`/`rescue`/`retry`)
