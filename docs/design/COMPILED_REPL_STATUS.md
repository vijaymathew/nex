# Compiled REPL Status

This note defines the current boundary of the experimental JVM bytecode compiler and the compiled REPL backend.

It answers two questions:

- what is supported on the compiled path today
- what still deoptimizes to the tree-walking interpreter

The goal is to keep this boundary explicit while compiler coverage expands.

## Current Architecture

The experimental compiled backend is wired into the REPL only.

- The compiled session is the source of truth.
- Top-level values, types, functions, classes, and imports are tracked in compiled-session state.
- Supported inputs compile to JVM bytecode and execute against `NexReplState`.
- Unsupported inputs explicitly deopt:
  - compiled session syncs into interpreter state
  - the interpreter executes the input
  - interpreter state syncs back into the compiled session

This avoids split-brain REPL state while the compiler subset is still incomplete.

## Important REPL Boundary

There are currently two distinct layers:

- the internal compiled helper in `nex.compiler.jvm.repl`
- the user-facing REPL entry path in `nex.repl`

That distinction matters.

The internal compiled helper can already compile top-level program batches that contain:

- `let`
- assignment
- expressions
- function declarations and definitions
- supported class work in the current compiler subset
- supported contracts and exception constructs in the current compiler subset

The user-facing REPL still has an older wrapping rule for inputs that look like statements.

In particular, inputs beginning with forms such as:

- `if`
- `from`
- `repeat`
- `across`
- `do`

are still wrapped into a temporary `__ReplTemp__.__eval__()` method before evaluation.

That means:

- the user-facing REPL does not currently route those top-level statement-shaped inputs through the compiled fast path, even when the internal compiled helper could support them
- those wrapped inputs execute through the interpreter path and then sync back into compiled-session state

So the practical rule today is:

- expression-shaped top-level inputs can use the compiled REPL path
- many statement-shaped top-level inputs in the user-facing REPL still use the wrapped/interpreter path

Top-level `let` and simple assignment now go through compiled eligibility first in the interactive REPL. The remaining mismatch is mostly larger statement/control-flow forms.

## What "Supported" Means

There are two kinds of support in the compiled path.

### 1. Direct bytecode-emitted semantics

These are lowered into IR and emitted as JVM bytecode directly:

- constants
- local variable load/store
- top-level REPL variable load/store
- arithmetic
- logical operators
- comparisons
- unary operators
- `if` expressions and statements
  - `elseif` chains included
- `when` expressions
- `case` statements
- scoped `do...end` blocks
- scoped `do...rescue...end` blocks
- `raise`
- `retry`
- `require` / `ensure`
- `old` in compiled method and constructor postconditions
- loop invariant / variant checks
- top-level function declarations and definitions
- top-level function calls through compiled REPL state
- user-defined classes
  - fields
  - methods
  - constructors
  - constants
  - deferred classes
  - single and multiple inheritance via composition/delegation
  - `super` calls on the composition model
  - erased generic/parameterized class support
- `convert ... to`
  - standalone form
  - guard form inside control flow

### 2. Compiled runtime-bridge semantics

These are still compiled, but the emitted bytecode calls back into the shared Nex runtime through `:call-runtime`:

- unqualified builtins such as `print`, `println`, `type_of`, `sleep`, `await_any`, `await_all`
- builtin-style feature calls on runtime-backed receiver types such as:
  - `numbers.length`
  - `numbers.slice(0, 2)`
  - `numbers.reverse`
  - `m.contains_key("a")`
  - `s.contains(2)`
  - `task.await`
  - `ch.receive`

This is intentional. It keeps semantics correct while avoiding duplicated builtin implementations in the compiler.

## Supported Now

The compiled REPL path currently supports:

- constants
  - `Integer`, `Real`, `String`, `Char`, `Boolean`, `nil`
- variable access
  - locals
  - top-level REPL variables via `NexReplState`
- top-level mutation
  - `let`
  - assignment to known bindings
- arithmetic and comparisons
  - `+`, `-`, `*`, `/`, `%`, `^`
  - `=`, `/=`, `<`, `<=`, `>`, `>=`
- logical operators
  - `and`, `or`, `not`
- string concatenation
  - `+` when either side is a `String`
- Integer bitwise operators
  - shifts
  - rotates
  - `and` / `or` / `xor` / `not`
  - `set` / `unset` / `is_set`
- `if` control flow
  - expression and statement forms
  - `elseif` chains included
- `when` expressions
- `case` statements
- scoped `do...end` blocks
- scoped `do...rescue...end` blocks
- top-level function support
  - declarations
  - definitions
  - redefinitions
  - calls through compiled REPL state
  - functions calling other top-level functions
  - mutual recursion across REPL cells
- imports/intern-free top-level batches containing:
  - function declarations
  - function definitions
  - `let`
  - assignment
  - final expressions and calls
- legacy `:calls`-only program AST normalization
- builtin lowering through `:call-runtime`
  - unqualified builtins
  - builtin-style feature calls on runtime-backed builtin receiver types
- loops
  - `from/until/do`
  - `repeat` (desugared to `from/until/do`)
  - `across` (desugared to `from/until/do`)
  - loop invariant checking
  - loop variant checking
- exceptions
  - `raise`
  - `rescue`
  - `retry`
- contracts
  - `require`
  - `ensure`
  - `old` for compiled method/constructor postconditions
- user-defined classes
  - fields
  - methods
  - constructors
  - constants
  - deferred classes
  - single and multi-parent inheritance
  - `super` calls in overriding methods
  - generic/parameterized user classes with JVM erasure
  - `convert ... to`

## Runtime-Backed Receiver Types

Target feature calls currently stay on the compiled path only when the receiver type is one of these runtime-backed builtin families:

- `Integer`
- `Integer64`
- `Real`
- `Decimal`
- `Char`
- `Boolean`
- `String`
- `Array`
- `Map`
- `Set`
- `Cursor`
- `Task`
- `Channel`
- `Console`
- `Process`

These calls are compiled, but their semantics are provided by the runtime bridge.

## What Is Not Supported Yet

These still fall outside the compiled subset and therefore deopt to the interpreter:

- `select`
- contracts
  - class invariants
  - broader `old` support beyond the current compiled field-snapshot model
- file/module compilation for the newer object-oriented compiled subset
- imports and `intern` on the compiled path
- concurrency constructs as direct compiled semantics
  - `spawn`
  - `Task`
  - `Channel`
  - timeouts/cancellation/select lowering
- closures / lambdas / higher-order compiled function objects
- specialized direct codegen for each builtin

In addition, some inputs still deopt in the user-facing REPL because of the wrapping rule above, even though the internal compiled helper supports them.

The main examples today are:

- top-level `if` entered directly at the REPL prompt
- `from` / `repeat` / `across`
- scoped `do...end` and `do...rescue...end` blocks entered directly at the prompt

## Practical Boundary Today

Good candidates for the compiled path today:

- arithmetic-heavy REPL work over top-level values
- top-level function definition and redefinition
- mutually recursive top-level functions with forward declarations
- builtin-heavy expression batches that do not introduce classes, imports, or interns
- parent-typed virtual calls through compiled deferred/concrete class hierarchies once the value is already present in compiled-session state
- compiled user-class batches using:
  - `super`
  - `Box[T]`-style parameterized classes
  - `convert ... to`

Likely deopt triggers today:

- imports and interns
- class invariants
- broader `old` use outside the current compiled postcondition model
- object-oriented file/module compilation beyond the current compiled REPL/helper path
- concurrency features beyond runtime-bridged builtin methods
- statement-shaped REPL inputs that are still pre-wrapped in `nex.repl`

## Examples That Stay Compiled

A simple top-level batch:

```nex
function inc(n: Integer): Integer
  do
    result := n + 1
  end

let x: Integer := inc(40)
x := x + 1
x
```

A builtin-heavy batch:

```nex
print(numbers.length)
print(m.contains_key("a"))
print(s.contains(2))
type_of(numbers.reverse)
```

A legacy `:calls`-only shape is normalized into the same path when the calls are otherwise supported.

A compiled helper batch with class/object work:

```nex
deferred class Shape
feature
  area(): Real do end
end

class Square inherit Shape
create
  with_side(v: Real) do
    this.side := v
  end
feature
  side: Real

  area(): Real
  do
    result := side * side
  end
end

let s: Shape := create Square.with_side(4.0)
s.area()
```

This is supported by the internal compiled helper and is covered by compiler/runtime smoke tests.

## Examples That Still Deopt

These are not yet compiled end-to-end:

```nex
class Point
feature
  x: Integer
end
```

In the user-facing REPL, this still includes wrapped statement inputs such as:

```nex
let x: Integer := 40
```

The internal compiled helper supports top-level `let`, but the interactive REPL still wraps it and sends it through the interpreter path.

```nex
select
  when ch.receive as x then
    print(x)
end
```

```nex
spawn do
  result := 1 + 2
end
```

## Design Intent

The current compiler boundary is deliberately narrow.

The next expansions should preserve these rules:

- compiled session remains the canonical REPL state
- unsupported inputs deopt explicitly and resync cleanly
- runtime semantics are reused where correctness matters more than specialization
- direct bytecode lowering should grow only when its behavior is well-specified and test-covered

## Related Design Notes

- [JVM Bytecode Compiler Plan](JVM_BYTECODE_COMPILER_PLAN.md)
