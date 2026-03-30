# JVM Bytecode Translation Reference

The JVM backend is a mature execution target for Nex. It covers most of the language surface, supports both compiled REPL execution and file-to-jar compilation, and uses a mixed strategy of direct bytecode emission plus explicit runtime helper calls where that is the better engineering trade.

This chapter is a translation guide for the current implementation. It is not a wishlist, and it is not a plan. The primary implementation lives in:

- [`src/nex/lower.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/lower.cljc)
- [`src/nex/ir.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/ir.cljc)
- [`src/nex/compiler/jvm/emit.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/emit.clj)
- [`src/nex/compiler/jvm/runtime.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/runtime.clj)
- [`src/nex/compiler/jvm/repl.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/repl.clj)
- [`src/nex/compiler/jvm/file.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/file.clj)

The validation surface is substantial too. The most useful tests to read alongside the implementation are:

- [`test/nex/compiler/jvm/repl_test.clj`](https://github.com/vijaymathew/nex/blob/main/test/nex/compiler/jvm/repl_test.clj)
- [`test/nex/compiler/jvm/compiled_repl_soak_test.clj`](https://github.com/vijaymathew/nex/blob/main/test/nex/compiler/jvm/compiled_repl_soak_test.clj)
- [`test/nex/compiler/jvm/file_test.clj`](https://github.com/vijaymathew/nex/blob/main/test/nex/compiler/jvm/file_test.clj)
- [`test/nex/compiler/jvm/file_smoke_test.clj`](https://github.com/vijaymathew/nex/blob/main/test/nex/compiler/jvm/file_smoke_test.clj)
- [`test/nex/compiler/jvm/cli_integration.clj`](https://github.com/vijaymathew/nex/blob/main/test/nex/compiler/jvm/cli_integration.clj)

This chapter explains the design choices that are no longer obvious from reading those files one by one.


## 5.1 Purpose and Scope

This reference answers a practical question: when a Nex construct stays on the compiled JVM path, what does the compiler actually do with it?

In this chapter, **compiled path** means:

- the compiled REPL backend, where a REPL input is parsed, lowered, emitted to JVM bytecode, loaded through a dynamic classloader, and executed immediately, or
- the file compiler, where a `.nex` file is compiled into `.class` files and then packaged into a runnable jar.

This chapter does **not** try to document every helper function or every opcode sequence. It documents the stable translation strategy:

- which constructs lower directly to IR and bytecode,
- which constructs stay compiled but route through runtime helpers,
- where the compiler deliberately deopts to the interpreter,
- and what invariants the mixed-mode design depends on.

The remaining deopt surface is real and intentional. That does not make the backend experimental. It means the implementation prefers semantic fidelity over forced compilation when a boundary is not yet worth specializing. The compiled backend is the default REPL path and the file compiler produces runnable jars; mixed-mode execution is part of the mature design, not evidence that the target is provisional.


## 5.2 Compilation Pipeline

The JVM backend follows the same broad pipeline in both REPL and file compilation, but the surrounding packaging differs.

### Parse

Source first becomes the ordinary Nex AST through the ANTLR-based parser and walker. The backend does not use a separate parser or a parallel AST shape.

### Typecheck

Typechecking still belongs to the shared typechecker, not to the JVM backend. This matters because the compiler is not the owner of the language semantics; it is a consumer of the typed AST.

### Lowering

The lowering pass in [`src/nex/lower.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/lower.cljc) converts AST nodes into:

- expression and statement IR in [`src/nex/ir.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/ir.cljc),
- function specs,
- class specs,
- launcher/program units for file compilation.

Lowering is where most of the backend strategy lives. It decides whether a construct becomes:

- direct IR for primitive JVM emission,
- a user-class or function class spec,
- or a runtime-helper call.

### Emission

The emitter in [`src/nex/compiler/jvm/emit.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/emit.clj) turns lowered units and class specs into byte arrays using ASM. It emits:

- compiled REPL cell classes,
- user-defined classes,
- anonymous function classes,
- program classes for file compilation,
- launcher classes with `main`.

### Runtime Helpers

The JVM backend does not try to inline the entire Nex runtime into bytecode. It relies on [`src/nex/compiler/jvm/runtime.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/runtime.clj) for:

- REPL state access,
- host interop,
- some builtins,
- some collection and concurrency operations,
- closure invocation,
- deopt/reopt reconstruction support.

The important point is that **runtime helper use is still compiled execution**. A helper call is not a deopt. It is a compiled call into a runtime boundary chosen deliberately.

### REPL Compilation vs File Compilation

The REPL compiler and the file compiler share the same lowering and emission core.

The main differences are:

- the REPL compiler emits short-lived cell classes and keeps canonical session state in memory,
- the file compiler emits named program classes, launcher classes, and shaded jars.

This shared core is what keeps the JVM backend coherent: REPL compilation is not a toy path and file compilation is not a separate compiler.


## 5.3 Runtime Model

### `NexReplState`

The compiled REPL runtime is organized around `NexReplState` in [`src/nex/compiler/jvm/runtime.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/runtime.clj). It stores:

- top-level values,
- top-level types,
- compiled function wrappers,
- REPL output,
- compiled class metadata,
- imports,
- a class-name counter,
- the dynamic classloader.

This is the canonical runtime state for compiled REPL execution.

### Compiled Session vs Interpreter Sync

The compiled REPL session in [`src/nex/compiler/jvm/repl.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/repl.clj) stores more than runtime values. It also remembers:

- function ASTs,
- class ASTs,
- import ASTs,
- intern ASTs,
- compiled class metadata,
- the dynamic loader and the runtime state.

The interpreter context and the compiled session are kept coherent by explicit sync in both directions:

- compiled session -> interpreter, when the system deopts,
- interpreter -> compiled session, after interpreter evaluation succeeds.

This is why the compiled REPL can be the default without pretending the interpreter no longer exists.

### Deopt/Reopt Model

The compiler does not attempt to compile every AST shape. If an input is outside the supported compiled subset, or hits a deliberately conservative fallback boundary, the REPL:

1. materializes interpreter state from the compiled session,
2. runs the input through the interpreter,
3. syncs the resulting state back into the compiled session,
4. resumes compiled execution on later supported inputs.

The correct way to think about the REPL now is:

- compiled by default and used in ordinary work,
- interpreter as a correctness-preserving fallback,
- not “compiled unless we forgot to implement it.”


## 5.4 Expression Translation

### Literals

Primitive literals lower directly:

- integers,
- integer64,
- reals,
- decimals,
- booleans,
- chars,
- strings,
- `nil`.

These either become primitive JVM values or object constants depending on type.

### Arithmetic and Comparisons

Arithmetic lowers directly for the normal numeric operators:

- `+`, `-`, `*`, `/`, `%`
- comparisons such as `=`, `/=`, `<`, `<=`, `>`, `>=`

Mixed numeric operands are widened before emission. The compiler now follows Nex numeric widening rules rather than requiring identical lowered JVM types.

### Logical Operators

`and`, `or`, and `not` are direct compiled operators. `and` and `or` are emitted with short-circuit branching rather than as runtime calls.

### String Concatenation

String `+` stays on the compiled path. It is compiled through a dedicated helper strategy rather than pretending it is ordinary primitive addition.

### Builtins

The builtin surface is split into two categories:

- builtins with direct lowering/emission or dedicated helper mapping,
- builtins that still use a generic runtime bridge as a defensive fallback.

The important design change is that the supported builtin surface no longer relies on pretending builtins are user-defined REPL functions.

### Collection Literals

Array, map, and set literals lower directly. They no longer require interpreter fallback just because they are collection constructors.

### `if` and `when`

Expression-shaped `if` and `when` lower directly. Branch results are coerced to the declared IR result type during emission when necessary.

### `convert`

`convert ... to` lowers on the compiled path and uses the runtime’s compatibility model for the dynamic success/failure result.


## 5.5 Statement and Control-Flow Translation

### `let`

Top-level `let` in the compiled REPL is not lowered as a local-only binding. It becomes mutation of canonical top-level REPL state.

That design choice is what made compiled REPL state coherent enough to become the default.

### Assignment and Member Assignment

Top-level assignment lowers directly when the target is known and type-valid. Member assignment lowers to field-set IR on compiled object paths that are currently supported.

### Loops

Loop constructs now lower on the compiled path, including loop contracts where supported. The backend does not treat ordinary looping as an interpreter-only feature anymore.

### `case`

`case` lowers to statement-level branching rather than forcing REPL wrapper fallback.

### `across`

`across` is not compiled as a magical special loop. It is translated through the cursor protocol. One important refinement was necessary here: if the iterated value is already a `Cursor`, the compiled path now uses it directly instead of forcing an unnecessary `.cursor` call.

### `select`

`select` has explicit lowering and runtime helper support. It is not just a generic builtin call. Clause scheduling and timeout handling live partly in lowering strategy and partly in runtime helpers.


## 5.6 Functions and Closures

### Top-Level Functions

Top-level functions lower to callable compiled units and are registered into `NexReplState.functions`.

In the REPL, this means later compiled cells can call them directly through compiled session state.

### Anonymous Functions

Anonymous functions without captures lower to synthetic compiled classes.

### Captured Closures

Captured closures are supported too, but not with the same representation. They use runtime-backed closure objects with captured environment snapshots. This is a deliberate split:

- no-capture anonymous functions can be emitted cleanly as compiled classes,
- captured closures are more stable when represented as runtime-backed Nex closure objects.

### Function-Object Invocation

Indirect calls through function values lower to explicit function-object invocation, not to the top-level function registry. This is what makes higher-order compiled code possible without pretending all functions are top-level named routines.


## 5.7 Object Model

### Classes

User-defined classes are compiled. The JVM backend now treats ordinary class definitions as part of the compiled surface, not as a special REPL-only exception.

### Constants

Class constants lower separately from instance fields and emit as real JVM `static final` fields.

### Constructors

Default creation and simple named constructors are compiled. Constructor logic is lowered into the class model rather than forced back into the interpreter.

### Multiple Inheritance

Nex multiple inheritance is **not** implemented through JVM class inheritance.

Instead, the backend mirrors the Java generator strategy:

- user classes extend `Object`,
- direct parents are represented as composition fields,
- inherited methods are exposed through generated delegation methods,
- inherited constructor behavior is exposed through generated shims.

This is one of the most important design decisions in the backend. It keeps Nex semantics aligned with the language rather than with the JVM’s single-inheritance object model.

### `super`

`super` is compiled on top of the composition/delegation inheritance strategy, not through JVM `super` dispatch between user-defined Nex classes.

### Deferred Classes

Deferred classes are compiled as abstract JVM classes where appropriate, but the important semantic layer remains the Nex class model and parent metadata, not raw JVM inheritance.

### Invariants

Class invariants are enforced on the compiled path through runtime validation hooks. The backend does not pretend invariants are optional because code is compiled.


## 5.8 Contracts and Exceptions

### `require` and `ensure`

Preconditions and postconditions lower to assertion-like checks on the compiled path. They use the same runtime exception/contract-violation mechanism as the rest of the backend rather than inventing a second error model.

### `old`

`old` is implemented through entry snapshots for the supported method/constructor postcondition cases. This is broader than the earliest field-snapshot prototype but still intentionally tied to the actual runtime object model.

### `raise`

`raise` becomes a real compiled throw path.

### `rescue`

`rescue` lowers to try/catch-style control flow using explicit runtime exception values where needed.

### `retry`

`retry` is represented as a retry signal in the compiled runtime model. It is not a parser-only feature.


## 5.9 Concurrency

### `spawn`

`spawn` lowers on the compiled path and allocates tasks through the runtime model rather than forcing interpreter fallback.

### `Task`

Task lifecycle operations are supported on the compiled path:

- `await`
- `cancel`
- `is_done`
- related task state queries

Some of these use specialized helper calls rather than bespoke primitive opcodes, but they are no longer generic builtin trampolines.

### `Channel`

Channel creation and core operations stay on the compiled path:

- `send`
- `receive`
- `try_send`
- `try_receive`
- `close`
- state queries where supported

### `select`

`select` is part of the concurrency lowering story, not a separate ad hoc runtime trick.

### `await_any` and `await_all`

These now have dedicated compiled helper paths. They are not routed through the old generic builtin dispatch layer.


## 5.10 Modules and Interop

### `import`

Imports are part of compiled session state and are visible to the compiler. The backend does not treat them as interpreter-only metadata anymore.

### `intern`

Interned local `.nex` files are resolved before lowering in both REPL and file compilation. The compiled session keeps the resulting metadata so later compiled inputs can reuse it across deopt/reopt cycles.

### `with "java"`

`with "java"` is now on the JVM compiled path. Static and instance Java interop is lowered into host interop support rather than being excluded from compilation just because it is foreign code.


## 5.11 Debugging and Metadata

The emitter now includes more than raw executable bytecode. It also emits:

- source file names,
- line tables,
- local-variable tables.

The current metadata is sufficient for meaningful stack traces and debugger-oriented inspection. It is still pragmatic rather than perfect; the local-variable live ranges are tighter than the earliest whole-method implementation, but they are not yet a full optimizer-grade liveness model.

Debugger behavior under compiled mode is currently safe because the REPL routes debugger-enabled evaluation through the interpreter path rather than attempting native compiled debugging for every step.


## 5.12 Fallback Boundaries

The compiled backend is the default REPL path, but the fallback surface still matters.

The remaining deopt cases are not “all advanced features,” and they are not a sign that the backend is still a side path. They are mostly:

- AST shapes outside the compiled eligibility gate,
- debugger-enabled REPL evaluation,
- known conservative mixed-mode boundaries,
- unresolved or unsupported receiver/call shapes,
- a small number of deliberately interpreter-routed cases where semantic fidelity is better preserved that way.

Two design rules are important here:

1. A runtime helper call is **not** a deopt.
2. A deopt must preserve user-visible semantics after sync, not just finish execution somehow.


## 5.13 Validation Surface

This chapter documents the current JVM compiler as implemented, tested, and used.

Three kinds of validation matter in practice:

- compiled REPL tests, which check ordinary interactive use and regression behavior across a wide language surface,
- soak tests, which stress long mixed-mode sessions with deopt/reopt cycles and cross-cell state,
- file and CLI tests, which verify standalone compilation, jar packaging, launcher generation, and end-to-end execution under `java -jar`.

This matters when reading the source. Many design choices that would otherwise look conservative or overly explicit are there because the backend is expected to survive real REPL sessions and standalone compilation workflows, not only unit-sized translation examples.


## 5.14 Worked Examples

### Example 1: Top-Level `let`

Source:

```nex
let x: Integer := 40
```

Lowered strategy:

- lower to top-level REPL state mutation, not a transient local

Emitted strategy:

- compiled REPL cell stores into `NexReplState.values` and `NexReplState.types`

Runtime involvement:

- state update helpers
- session->interpreter sync only if later deopt requires it

### Example 2: Captured Closure

Source:

```nex
let add_base := fn (n: Integer): Integer do
  result := n + x
end
```

Lowered strategy:

- captured closure does not become a plain no-capture function class
- captured environment is represented through a runtime-backed closure object

Emitted strategy:

- compiled code allocates the closure through a runtime helper

Runtime involvement:

- closure capture storage
- function-object invocation path

### Example 3: Multiple Inheritance Method Call

Source:

```nex
class C inherit A, B
feature
  run() do
    A.show()
    B.show()
  end
end
```

Lowered strategy:

- parent-qualified calls lower against composition fields and delegation metadata

Emitted strategy:

- child class extends `Object`
- parent state lives in parent composition fields
- delegation methods and parent-qualified dispatch are emitted explicitly

Runtime involvement:

- object-state validation where required

### Example 4: `select`

Source:

```nex
select
  when ch.receive() as msg then
    print(msg)
  timeout 100 then
    print(0)
end
```

Lowered strategy:

- explicit select lowering
- clause structure preserved rather than reduced to one builtin call

Emitted strategy:

- compiled control flow plus runtime helper support for probe/send/receive behavior

Runtime involvement:

- channel/task coordination helpers

### Example 5: File Compilation

Source:

```nex
print("hello")
```

Lowered strategy:

- ordinary program AST lowered into a program unit

Emitted strategy:

- emit a program class
- emit a launcher class with `main`
- package emitted classes and runtime support into a jar

Runtime involvement:

- launcher bootstrap
- shaded runtime helpers in the output jar


## 5.14 Why This Reference Exists

The JVM backend has reached the point where “read the source” is no longer an adequate replacement for a design reference. There are now too many important translation choices whose correctness depends on understanding the whole:

- direct bytecode vs runtime-helper boundaries,
- mixed-mode REPL state coherence,
- composition-based multiple inheritance,
- closure representation,
- contract and exception lowering,
- concurrency lowering,
- module and interop handling.

This chapter is meant to keep those choices legible. It is a maintenance tool as much as a book chapter. Future backend work should update this reference whenever the translation strategy changes in a way that affects reasoning, not just implementation detail.
