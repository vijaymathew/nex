# Bytecode Patterns by Construct

This appendix is a code-reading companion to the JVM backend. It does not try to list every opcode the emitter can produce. It shows the stable bytecode shapes used for the major Nex constructs, so that a reader can move between a Nex example and the corresponding emitter path in [`src/nex/compiler/jvm/emit.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/emit.clj) without reverse-engineering the whole file each time.

The sequences below are schematic. They omit some boxing, unboxing, casts, and local-variable-table details when those are not the point of the example. The important thing is the control-flow and stack shape the backend relies on.


## Reading This Appendix

The compiled JVM path has three layers:

- lowering in [`src/nex/lower.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/lower.cljc)
- IR definitions in [`src/nex/ir.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/ir.cljc)
- bytecode emission in [`src/nex/compiler/jvm/emit.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/emit.clj)

This appendix starts at the last of these. Each section answers the same question: once lowering has decided that a construct stays on the compiled path, what bytecode pattern does the emitter actually write?


## Constants and Local State

The simplest compiled constructs become direct stack operations.

### Constants

Integer, boolean, character, string, and `nil` literals become ordinary JVM constants through the emitter's constant path. In practice the sequence is a short `LDC`-style push followed, when needed, by boxing or coercion at the use site.

```text
Nex: 42

Bytecode shape:
  LDC 42
```

```text
Nex: true

Bytecode shape:
  ICONST_1
```

```text
Nex: "hello"

Bytecode shape:
  LDC "hello"
```

### Local Loads and Stores

Lowered locals become typed JVM local slots. The emitter uses a typed load opcode on read and a typed store opcode on assignment.

```text
Nex:
  let x: Integer := 10
  x + 1

Bytecode shape:
  LDC 10
  ISTORE <slot-x>
  ILOAD <slot-x>
  ICONST_1
  IADD
```

The actual load and store opcodes vary with the lowered JVM type:

- `ILOAD` / `ISTORE` for `Integer`, `Boolean`, and `Char`
- `LLOAD` / `LSTORE` for `Integer64`
- `DLOAD` / `DSTORE` for `Real`
- `ALOAD` / `ASTORE` for object-typed values

The relevant emitter paths are the `:local` and `:set-local` branches in [`emit.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/emit.clj).


## Top-Level REPL Bindings

The compiled REPL does not treat top-level `let` as a normal JVM local. Top-level values live in the `values` atom inside `NexReplState`, and compiled cells mutate that canonical state.

```text
Nex:
  let count := 10

Bytecode shape:
  ALOAD state
  GETFIELD NexReplState.values
  CHECKCAST clojure/lang/Atom
  INVOKEVIRTUAL Atom.deref
  CHECKCAST java/util/HashMap
  LDC "count"
  LDC 10
  INVOKEVIRTUAL HashMap.put
  POP
```

A top-level read mirrors this:

```text
Bytecode shape:
  ALOAD state
  GETFIELD NexReplState.values
  ...
  LDC "count"
  INVOKEVIRTUAL HashMap.get
  <unbox-or-cast>
```

This is why the compiled REPL can execute multiple cells coherently without pretending every top-level name is a local variable in one giant synthetic method. The read and write paths live under `:top-get`, `:top-set`, and the `emit-load-values-map!` helper in [`emit.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/emit.clj).


## Arithmetic, Comparison, and Boolean Operators

### Arithmetic

Primitive arithmetic is emitted directly after any required numeric promotion.

```text
Nex:
  x + y

Bytecode shape:
  <emit x>
  <coerce to operand type>
  <emit y>
  <coerce to operand type>
  IADD | LADD | DADD
```

The same pattern applies to `-`, `*`, `/`, and `%`, with the opcode chosen from the lowered JVM type.

### Comparisons

Comparisons compile to branch-and-materialize sequences. The emitter does not leave a raw JVM comparison result on the stack. It branches to produce a Nex boolean value explicitly.

```text
Nex:
  x < y

Bytecode shape for int-like operands:
  <emit x>
  <emit y>
  IF_ICMPLT true
  ICONST_0
  GOTO end
true:
  ICONST_1
end:
```

For `long` and `double`, the JVM requires a compare instruction first:

```text
Bytecode shape:
  <emit x>
  <emit y>
  LCMP | DCMPL
  IFLT | IFGT | IFEQ ...
  ICONST_0
  GOTO end
true:
  ICONST_1
end:
```

Object comparison is deliberately narrower. The compiled path currently supports only `=` and `/=` on object operands, which become `IF_ACMPEQ` and `IF_ACMPNE` style branches over references.

### Short-Circuit Boolean Operators

`and` and `or` are emitted as real short-circuit control flow.

```text
Nex:
  a and b

Bytecode shape:
  <emit a>
  IFEQ false
  <emit b>
  IFEQ false
  ICONST_1
  GOTO end
false:
  ICONST_0
end:
```

```text
Nex:
  a or b

Bytecode shape:
  <emit a>
  IFNE true
  <emit b>
  IFNE true
  ICONST_0
  GOTO end
true:
  ICONST_1
end:
```

This is handled in `emit-boolean-short-circuit!` in [`emit.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/emit.clj).


## Branching and Looping

### Expression `if`

Expression-shaped `if` lowers to one-expression branches with an explicit merge label.

```text
Nex:
  if test then a else b end

Bytecode shape:
  <emit test>
  IFEQ else
  <emit a>
  <coerce to result type>
  GOTO end
else:
  <emit b>
  <coerce to result type>
end:
```

The critical detail is the result-type coercion before the merge point. Lowering already decided the result type; emission enforces it on both branches.

### Statement `if`

Statement-form `if` uses the same branch skeleton without preserving a result on the stack.

```text
Bytecode shape:
  <emit test>
  IFEQ else
  <emit then statements>
  GOTO end
else:
  <emit else statements>
end:
```

### Loops

The lowered loop form is a guard-at-top loop with a conventional back edge.

```text
Bytecode shape:
  <emit init statements>
loop:
  <emit test>
  IFNE end
  <emit body>
  GOTO loop
end:
```

This looks inverted only if one expects the JVM code to mirror the Nex source text exactly. Lowering has already normalized the loop condition into the internal guard form the emitter uses.


## Functions, Methods, and Calls

### Function Argument Prologue

Compiled REPL functions and compiled instance methods receive arguments in an `Object[]`. The first step in the emitted method body is to unpack that array into typed locals.

```text
Bytecode shape for one argument:
  ALOAD __args
  LDC 0
  AALOAD
  <unbox-or-cast to declared type>
  <typed store into local slot>
```

The emitter repeats this for each parameter in `emit-function-arg-prologue!`.

### Top-Level REPL Function Calls

Top-level functions in the compiled REPL are registered in the session state's `functions` map as reflective `Method` objects. Calling one therefore goes through that map and then through `Method.invoke`.

```text
Bytecode shape:
  <load functions map from state>
  LDC "fn-name"
  INVOKEVIRTUAL HashMap.get
  CHECKCAST java/lang/reflect/Method
  ACONST_NULL
  ICONST_2
  ANEWARRAY java/lang/Object
  ...
  INVOKEVIRTUAL Method.invoke
  <unbox-or-cast result>
```

This is not how file-compiled static calls work. It is specifically the compiled REPL strategy, where the current session state is part of the call protocol.

### Compiled Instance and Virtual Calls

Ordinary method calls on compiled objects use a direct virtual invocation after the target is emitted and cast to the expected owner type.

```text
Bytecode shape:
  <emit target>
  CHECKCAST owner
  ALOAD state
  <emit boxed arg array>
  INVOKEVIRTUAL owner.method
  <unbox-or-cast result>
```

Higher-order function-object calls take a different path: they invoke runtime helper machinery rather than inlining the closure protocol into raw bytecode. That choice keeps the backend readable while still staying on the compiled path.


## Object Construction, Fields, and Class Initialization

### Plain Object Construction

A lowered `create C` eventually becomes the ordinary JVM object-construction triplet:

```text
Bytecode shape:
  NEW C
  DUP
  INVOKESPECIAL C.<init>
```

That is the `:new` branch in [`emit.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/emit.clj).

### Field Reads and Writes

Compiled field access is direct.

```text
Nex:
  obj.x

Bytecode shape:
  <emit obj>
  CHECKCAST owner
  GETFIELD owner/x <descriptor>
```

```text
Nex:
  obj.x := value

Bytecode shape:
  <emit obj>
  CHECKCAST owner
  <emit value>
  <coerce to field type>
  PUTFIELD owner/x <descriptor>
```

### User Default Constructors

User-defined classes get a default constructor that does more than call `Object.<init>`. It also initializes composition fields, assigns default values, and sets the `__outer__` pointer used for dynamic dispatch through composed parent objects.

```text
Bytecode shape:
  ALOAD 0
  INVOKESPECIAL super.<init>

  ALOAD 0
  ALOAD 0
  PUTFIELD owner.__outer__

  ALOAD 0
  NEW ParentPart
  DUP
  INVOKESPECIAL ParentPart.<init>
  PUTFIELD owner.parent_part

  ALOAD 0
  GETFIELD owner.parent_part
  ALOAD 0
  PUTFIELD ParentPart.__outer__

  ALOAD 0
  <default value>
  PUTFIELD owner.field

  RETURN
```

The back-pointer setup is specific to Nex's compiled treatment of composed inheritance and is worth understanding before changing constructor emission.

### Class Initializers

Static constants become a straightforward class initializer:

```text
Bytecode shape:
  <emit constant value>
  PUTSTATIC owner/CONST
  ...
  RETURN
```


## Collections

Collection literals compile directly to Java collection objects.

### Arrays

Nex arrays become `java.util.ArrayList`.

```text
Nex:
  [1, 2, 3]

Bytecode shape:
  NEW java/util/ArrayList
  DUP
  INVOKESPECIAL ArrayList.<init>
  DUP
  LDC 1
  <box if needed>
  INVOKEVIRTUAL ArrayList.add
  POP
  DUP
  LDC 2
  ...
```

### Maps

Maps become `java.util.HashMap`.

```text
Bytecode shape:
  NEW java/util/HashMap
  DUP
  INVOKESPECIAL HashMap.<init>
  DUP
  <emit key>
  <emit value>
  INVOKEVIRTUAL HashMap.put
  POP
```

### Sets

Sets become `java.util.LinkedHashSet`, which preserves insertion order.

```text
Bytecode shape:
  NEW java/util/LinkedHashSet
  DUP
  INVOKESPECIAL LinkedHashSet.<init>
  DUP
  <emit element>
  INVOKEVIRTUAL LinkedHashSet.add
  POP
```

### Collection Operations

Several core collection operations are emitted as direct host-library calls:

- array `get` -> `ArrayList.get`
- array `put` -> `ArrayList.set`
- array `add` -> `ArrayList.add`
- array `length` -> `ArrayList.size`
- map `put` -> `HashMap.put`
- map `size` -> `HashMap.size`
- set `size` -> `LinkedHashSet.size`

The emitter uses runtime helpers only where a direct Java collection call would hide Nex semantics rather than clarify them. String rendering, cloning, equality, and some cursor-related operations still go through helper calls for exactly that reason.


## Exceptions, Contracts, and Retry

### `raise`

`raise expr` first evaluates the value, boxes it if necessary, converts it to a runtime exception object, then throws it.

```text
Bytecode shape:
  <emit expr>
  <box if primitive>
  <runtime call make-raised-exception>
  CHECKCAST java/lang/Throwable
  ATHROW
```

### Contract Assertions

Compiled preconditions, postconditions, invariants, and variants are ordinary guard checks that construct and throw a contract violation when false.

```text
Bytecode shape:
  <emit condition>
  IFNE ok
  LDC "Postcondition"
  LDC "label"
  <runtime call make-contract-violation>
  CHECKCAST java/lang/Throwable
  ATHROW
ok:
```

This directness is one of the strengths of the compiled backend. Contract checks are not magic metadata. They are visible control-flow.

### `rescue` and `retry`

The compiled try/rescue path uses explicit JVM try/catch regions plus a retry signal recognized by the runtime.

```text
Bytecode shape:
loop-start:
body-start:
  <emit body>
body-end:
  GOTO end

body-handler:
  ASTORE throwable
  ALOAD throwable
  <runtime call retry-signal?>
  ...
  IFEQ not-retry
  ALOAD throwable
  ATHROW
not-retry:
  ALOAD throwable
  <runtime call exception-value>
  ASTORE exception
rescue-start:
  <emit rescue body>
rescue-end:
  GOTO end

rescue-handler:
  ASTORE rescue-throwable
  ALOAD rescue-throwable
  <runtime call retry-signal?>
  ...
  IFEQ rescue-not-retry
  GOTO loop-start
rescue-not-retry:
  ALOAD rescue-throwable
  ATHROW
end:
```

This is more substantial than a helper call wrapped around the interpreter. The control flow is genuinely compiled; the runtime only supplies the retry-signal protocol and exception-value extraction.


## Concurrency and Helper Calls

Concurrency is compiled, but not by inlining channel and task mechanics into raw JVM instructions. The emitter uses dedicated runtime helper calls for the concurrency surface.

```text
Nex:
  t.await()

Bytecode shape:
  <load runtime var task-await-method>
  <emit boxed task object>
  INVOKEVIRTUAL Var.invoke
  <unbox-or-cast result>
```

```text
Nex:
  ch.send(value)

Bytecode shape:
  <load runtime var channel-send-method>
  <emit boxed channel>
  <emit boxed value>
  INVOKEVIRTUAL Var.invoke
  <unbox-or-cast or POP>
```

The important design point is that these are still compiled calls. They are not deopts. Lowering produces `:concurrency-method` IR, and emission maps each supported method to a named runtime helper such as:

- `task-await-method`
- `task-cancel-method`
- `channel-send-method`
- `channel-receive-method`
- `channel-close-method`

This keeps the bytecode layer small while still preserving a compiled execution path for concurrency-heavy code.


## File Compilation and Program Launch

File compilation adds one more layer: a launcher class with `main`.

The launcher bytecode shape is:

```text
  <load clojure.core/require>
  <require nex.compiler.jvm.runtime>

  <runtime call make-repl-state>
  CHECKCAST NexReplState
  ASTORE state

  <runtime call bootstrap-compiled-state! state classes-edn imports-edn>
  POP

  ALOAD state
  INVOKESTATIC Program.eval
  POP

  <runtime call print-state-output! state>
  POP
  RETURN
```

This structure is why the file compiler can share so much code with the compiled REPL. The program class still exposes an `eval` entry point that consumes `NexReplState`; the launcher is just the small wrapper that creates and bootstraps that state in a standalone jar.


## How to Use This Appendix While Reading the Code

For actual code reading, the most efficient order is:

1. Read the lowering rule in [`src/nex/lower.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/lower.cljc) for the construct you care about.
2. Find the corresponding IR shape in [`src/nex/ir.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/ir.cljc).
3. Jump to the matching `:op` branch or helper in [`src/nex/compiler/jvm/emit.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/emit.clj).
4. Check [`src/nex/compiler/jvm/runtime.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/compiler/jvm/runtime.clj) only if emission routes through a runtime helper.

The main practical distinction to keep in mind is this:

- direct JVM emission is used when the construct has a stable and readable stack-level meaning
- runtime helper calls are used when they preserve Nex semantics more clearly than expanding everything inline

That design is visible throughout the backend, and the bytecode patterns in this appendix are the quickest way to see it.
