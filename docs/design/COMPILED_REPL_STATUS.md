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
- `old` in compiled method and constructor postconditions, matching the current interpreter-style field snapshot model
- loop invariant / variant checks
- top-level function declarations and definitions
- top-level function calls through compiled REPL state
- anonymous functions without captures
- user-defined classes
  - fields
  - methods
  - constructors
  - constants
  - deferred classes
  - single and multiple inheritance via composition/delegation
  - `super` calls on the composition model
  - erased generic/parameterized class support
  - class invariants
  - runtime object-model validation against stored class AST metadata
- `convert ... to`
  - standalone form
  - guard form inside control flow

### 2. Compiled runtime-bridge semantics

These are still compiled, but the emitted bytecode calls back into the shared Nex runtime through helper/runtime calls:

- unqualified builtins such as `print`, `println`, `type_of`, `sleep`
- compiled concurrency helpers such as:
  - `spawn`
  - `create Channel`
  - `create Channel.with_capacity(...)`
  - `await_all`
  - `await_any`
  - `select` timeout/probing helpers
- captured closures
  - closure allocation uses a runtime helper that builds a Nex closure object with a captured environment snapshot
  - later invocation still stays on the compiled path through function-object dispatch

This is intentional. It keeps semantics correct while avoiding duplicated builtin implementations in the compiler.

## Supported Now

The compiled REPL path currently supports:

- constants
  - `Integer`, `Real`, `String`, `Char`, `Boolean`, `nil`
  - Array literals like `[1, 2, 3]`
  - Map literals like `{"a": 1, "b": 2}`
  - Set literals like `#{1, 2, 3}`
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
- closures / higher-order
  - anonymous functions
  - captured closures
  - function-object invocation
  - passing function objects through `Function`-typed parameters
  - returning function objects from compiled functions
- module-aware top-level batches containing:
  - `import`
  - `intern`
  - function declarations
  - function definitions
  - `let`
  - assignment
  - final expressions and calls
- legacy `:calls`-only program AST normalization
- builtin lowering through runtime/helper calls
  - unqualified builtins
  - runtime-backed feature calls on non-collection builtin receiver types
- collections
  - direct compiled Array / Map / Set literals
  - direct compiled collection methods, including:
    - Array mutation/access like `add`, `remove`, `put`, `length`, `slice`, `reverse`
    - Map mutation/access like `put`, `remove`, `contains_key`, `keys`, `values`
    - Set operations like `contains`, `union`, `difference`, `intersection`
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
  - `old` for compiled method/constructor postconditions using the current field-based model
- note metadata
  - `note` annotations are parsed and preserved in class/function/member AST metadata
  - compiled lowering/emission intentionally ignores them semantically
- nil-safety
  - detachable types like `?Counter`
  - nil-aware branch refinement for guards such as `if x /= nil then ...` and `if x = nil then ... else ...`
  - `convert ... to` bindings on the compiled path
- concurrency
  - `spawn do...end`
  - `Task.await`, `Task.cancel`, `Task.is_done`
  - `Channel.send`, `Channel.receive`, `Channel.try_send`, `Channel.try_receive`, `Channel.close`
  - `select`
  - `await_all`, `await_any`
- user-defined classes
  - fields
  - methods
  - constructors
  - constants
  - deferred classes
  - single and multi-parent inheritance
  - `super` calls in overriding methods
  - generic/parameterized user classes with JVM erasure
  - class invariants
  - runtime object-model validation on compiled creation/method paths
  - `convert ... to`
- modules
  - Java `import` declarations are tracked in compiled-session state
  - imported Java classes can be instantiated on the compiled path
  - imported Java instance methods and field access use specialized runtime helpers instead of forcing interpreter fallback
  - `intern` declarations resolve local `.nex` files into compiled class metadata on the compiled path

## Runtime-Backed Receiver Types

Target feature calls currently stay on the compiled path only when the receiver type is one of these runtime-backed builtin families:

- `Integer`
- `Integer64`
- `Real`
- `Decimal`
- `Char`
- `Boolean`
- `String`
- `Cursor`
- `Console`
- `Process`

Array / Map / Set no longer belong in this bucket for their ordinary collection methods. Those now lower through dedicated collection IR and emit direct collection bytecode or collection-specific helpers.

`Task` and `Channel` receiver methods also no longer belong in this bucket. They now lower through dedicated concurrency IR and emit specialized runtime helper calls rather than the generic receiver-call bridge.

## What Is Not Supported Yet

These still fall outside the compiled subset and therefore deopt to the interpreter:

- file/module compilation for the JVM bytecode backend beyond the current REPL/helper path
- specialized direct codegen for each builtin beyond the current helper/receiver-call paths

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
- builtin-heavy or module-aware batches that do not rely on unsupported file-compilation machinery
- concurrency-heavy REPL batches such as:
  - `let t := spawn do ... end`
  - channel creation and send/receive work
  - top-level `select`
  - `await_all([..])` / `await_any([..])`
- parent-typed virtual calls through compiled deferred/concrete class hierarchies once the value is already present in compiled-session state
- compiled user-class batches using:
  - `super`
  - `Box[T]`-style parameterized classes
  - `convert ... to`

Likely deopt triggers today:

- file/module bytecode compilation beyond the current REPL/helper path
- JVM bytecode file/module compilation beyond the current compiled REPL/helper path
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
let numbers: Array[Integer] := [1, 2, 3]
let m: Map[String, Integer] := {"a": 1, "b": 2}
let s: Set[Integer] := #{1, 2, 3}

numbers.add(4)
print(numbers.length)
print(m.contains_key("a"))
print(s.contains(2))
type_of(numbers.slice(0, 2).reverse)
```

A concurrency batch:

```nex
let ch: Channel[Integer] := create Channel[Integer].with_capacity(1)
let t: Task[Integer] := spawn do
  result := 40 + 2
end

print(await_any([t]))
select
  when ch.try_send(7) then
    print(ch.receive)
  timeout 5 then
    print("timeout")
end
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
