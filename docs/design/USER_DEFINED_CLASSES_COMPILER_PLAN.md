# User-Defined Classes Compiler Plan

This note defines the next implementation milestone for the experimental JVM bytecode compiler: user-defined classes.

The scope here is deliberately narrow. The goal is to compile ordinary Nex classes and object interactions before attempting inheritance, contracts, exceptions, or concurrency-heavy object behavior.

## Goal

Add compiled support for user-defined classes in the JVM bytecode backend and compiled REPL path.

The first milestone should support:

- top-level class definitions
- object creation for simple classes
- field reads and writes
- instance method calls on user-defined classes
- method bodies using the already-supported compiled subset

This should make the compiled REPL capable of handling ordinary object-oriented Nex code, not just expressions and top-level functions.

## Explicit Non-Goals For This Milestone

Do not include these in the first class milestone:

- inheritance
- `super`
- contracts in compiled methods
- exceptions in compiled methods
- visibility enforcement in the compiled path
- concurrency-specific lowering inside class methods
- specialized generic code generation
- closures or higher-order method objects

These can be added later, but they should not complicate the initial object model.

## Phase 1 Scope

The first class milestone should support all of the following together:

- `class` definitions in compiled REPL mode
- fields with ordinary JVM storage
- creation of instances
- instance field access
- instance field assignment
- instance method definition and invocation
- methods calling other methods on the same object
- methods calling top-level compiled functions
- methods calling builtin/runtime-bridge operations where needed

## IR Additions

Add these IR nodes first.

### `:this`

Represents the current receiver inside an instance method.

```clojure
{:op :this
 :nex-type "Point"
 :jvm-type [:object "nex/gen/Point"]}
```

### `:new`

Represents object allocation.

```clojure
{:op :new
 :class "nex/gen/Point"
 :args []
 :nex-type "Point"
 :jvm-type [:object "nex/gen/Point"]}
```

### `:field-get`

```clojure
{:op :field-get
 :owner "Point"
 :field "x"
 :target {...}
 :nex-type "Integer"
 :jvm-type :int}
```

### `:field-set`

```clojure
{:op :field-set
 :owner "Point"
 :field "x"
 :target {...}
 :expr {...}
 :nex-type "Integer"
 :jvm-type :int}
```

### `:call-virtual`

For instance method dispatch on known user-defined class receivers.

```clojure
{:op :call-virtual
 :owner "nex/gen/Point"
 :method "move_by"
 :descriptor "(II)V"
 :target {...}
 :args [{...} {...}]
 :nex-type "Void"
 :jvm-type :void}
```

## Lowering Work

Add a class-lowering layer on top of the existing function/REPL lowering.

### 1. Lower class definitions into class specs

For each Nex class, lower:

- class name
- fields
- methods
- creation routines
- emitted JVM name

Suggested lowered shape:

```clojure
{:name "Point"
 :jvm-name "nex/repl/Point_0001"
 :fields [...]
 :methods [...]
 :creation-routines [...]} 
```

### 2. Lower field access and assignment

In expression/method lowering:

- `x` inside an instance method should resolve to field access when not found in locals
- `x := ...` inside an instance method should resolve to field assignment when `x` is a field
- explicit target access like `p.x` should lower to `:field-get`
- explicit target assignment like `p.x := ...` should lower to `:field-set`

### 3. Lower method calls on user-defined receivers

For known user-defined class types:

- `p.move_by(1, 2)` should lower to `:call-virtual`
- `move_by(1, 2)` inside a method should lower as a call on `:this`

### 4. Lower object creation

For the first pass, support the simplest viable creation subset.

Recommended order:

1. `create ClassName`
2. `create ClassName.make(...)`

If named creation routines are already central to Nex semantics, lower them as ordinary instance methods plus a small creation wrapper, rather than inventing a new object model in the compiler.

## Emitter Work

### 1. Emit JVM classes for Nex classes

For each lowered Nex class:

- emit one JVM class
- emit ordinary JVM fields
- emit instance methods
- emit default constructor

### 2. Emit field operations

Support:

- `GETFIELD`
- `PUTFIELD`

using lowered field metadata.

### 3. Emit instance method bodies

Each compiled method should initially support the existing compiled subset plus:

- `:this`
- `:field-get`
- `:field-set`
- `:call-virtual`

### 4. Emit object creation

For the first pass:

- `NEW`
- `DUP`
- `INVOKESPECIAL <init>`

If named creation routines are supported immediately:

- emit creation routine wrappers after allocation
- return the initialized object

## Runtime / REPL Session Work

### 1. Compiled session metadata must store class ASTs as canonical state

This already exists partially. Expand it so compiled class definitions become authoritative.

The compiled session should track:

- class ASTs
- emitted class JVM names
- class redefinition versions if needed

### 2. Compiled REPL mode should stop deopting for simple class definitions

A top-level class definition should stay on the compiled path when it uses only the supported subset.

### 3. Interpreter deopt/sync must preserve compiled class state

When deopting:

- materialize compiled classes into the interpreter context
- after interpreter execution, sync any resulting class metadata back into compiled session state

## Suggested Implementation Order

### Step 1

Add IR constructors in `src/nex/ir.cljc`:

- `this-node`
- `new-node`
- `field-get-node`
- `field-set-node`
- `call-virtual-node`

### Step 2

Add descriptor helpers in `src/nex/compiler/jvm/descriptor.clj` for:

- emitted user class names
- instance method descriptors
- field JVM type lookup

### Step 3

Extend lowering environment in `src/nex/lower.cljc` with:

- current class
- current method
- field table
- `this` binding metadata

Suggested env additions:

```clojure
{:current-class ...
 :current-method ...
 :fields {"x" {:owner "Point" :nex-type "Integer" :jvm-type :int}}
 :this-type "Point"
 ...}
```

### Step 4

Lower field reads/writes inside methods.

Acceptance target:

- a method can read and write its own fields without falling back to the interpreter

### Step 5

Emit one JVM class for one simple Nex class with:

- fields
- default constructor
- one instance method

### Step 6

Lower and emit object creation for the simplest supported creation form.

Acceptance target:

- compile `create Point`
- read/write fields on the resulting object

### Step 7

Lower and emit instance method calls on user-defined receivers.

Acceptance target:

- `p.move_by(1, 2)` works end-to-end in compiled REPL mode

### Step 8

Allow compiled REPL class-definition cells.

Acceptance target:

- define class in one REPL cell
- instantiate and use it in later compiled REPL cells

## First Acceptance Tests

### 1. Simple field read/write

```nex
class Point
feature
  x: Integer
end

let p := create Point
p.x := 10
p.x
```

Expected result: `10`

### 2. Method reading a field

```nex
class Counter
feature
  value: Integer

  current: Integer
  do
    result := value
  end
end
```

### 3. Method mutating a field

```nex
class Counter
feature
  value: Integer

  inc()
  do
    value := value + 1
  end
end
```

### 4. Method calling another method on the same object

```nex
class Counter
feature
  value: Integer

  inc()
  do
    value := value + 1
  end

  inc_twice()
  do
    inc()
    inc()
  end
end
```

### 5. Cross-cell compiled REPL use

Cell 1:

```nex
class Counter
feature
  value: Integer

  inc()
  do
    value := value + 1
  end
end
```

Cell 2:

```nex
let c := create Counter
c.inc()
c.value
```

## Testing Plan

Add focused tests under:

- `test/nex/compiler/jvm/lower_test.clj`
- `test/nex/compiler/jvm/emit_test.clj`
- `test/nex/compiler/jvm/repl_test.clj`

Add a dedicated new smoke namespace for user-defined classes:

- `test/nex/compiler/jvm/class_smoke_test.clj`

That smoke file should cover:

- class definition
- object creation
- field read/write
- method call
- cross-cell reuse in compiled mode

## Design Constraints

Keep these constraints explicit while implementing:

- no inheritance in this milestone
- no contracts in compiled methods in this milestone
- no exceptions in compiled methods in this milestone
- compiled session remains canonical REPL state
- unsupported object features still deopt explicitly
- do not bypass the existing typechecker; lowering should use typed information, not guess

## Exit Criteria

This milestone is complete when all of the following are true:

- simple user-defined classes no longer deopt by default in compiled REPL mode
- object creation works on the compiled path
- field reads and writes work on the compiled path
- instance methods execute on the compiled path
- compiled REPL can define a class in one cell and use it in later compiled cells
- full test suite remains green

## Related Design Notes

- [Compiled REPL Status](COMPILED_REPL_STATUS.md)
- [JVM Bytecode Compiler Plan](JVM_BYTECODE_COMPILER_PLAN.md)
