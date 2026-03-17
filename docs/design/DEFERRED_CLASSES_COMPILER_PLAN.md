# Deferred Classes Compiler Plan

This note defines the next implementation milestone for the experimental JVM bytecode compiler: deferred classes.

The goal is to extend the compiled class path from ordinary concrete classes to abstract class hierarchies, without taking on full inheritance complexity all at once.

## Goal

Add compiled support for deferred classes in the JVM bytecode backend and compiled REPL path.

The first milestone should support:

- compiled definition of deferred classes
- rejection of direct instantiation of deferred classes
- compiled concrete child classes of deferred parents
- compiled virtual calls through references typed as a deferred parent

This should make the compiled class model capable of handling basic abstraction and subtype polymorphism before moving on to `super`, contracts, and more complex inheritance behavior.

## Explicit Non-Goals For This Milestone

Do not include these in the first deferred-class milestone:

- `super`
- full override validation beyond the existing type rules
- merged inherited contracts in compiled methods
- deferred invariants in compiled code
- exceptions or rescue/retry inside compiled class methods
- multiple inheritance edge cases beyond the existing language model
- generic-specialized inheritance code generation
- mixed-mode parent/child dispatch where one side is compiled-only and the other is interpreter-only unless required for deopt correctness

These can be added later. They should not complicate the first abstract-class implementation.

## Phase 1 Scope

The first deferred-class milestone should support all of the following together:

- deferred class definitions in compiled REPL mode
- deferred feature declarations lowered into abstract JVM methods
- concrete child classes inheriting from compiled deferred parents
- concrete overrides emitted as normal JVM instance methods
- prevention of `create DeferredClass` in compiled execution
- method dispatch through a variable or field typed as the deferred parent
- top-level compiled REPL flows that define a deferred parent, define a concrete child, instantiate the child, and call through the parent type

## IR Additions

The existing user-defined-class IR is close to sufficient. The next step is to add explicit abstract/inheritance metadata rather than inventing many new expression ops.

### Class Spec Additions

Extend lowered class specs with:

```clojure
{:name "Shape"
 :jvm-name "nex/repl/Shape_0001"
 :deferred? true
 :parent nil
 :fields [...]
 :constants [...]
 :methods [...]
 :creation-routines [...]}
```

For a concrete child:

```clojure
{:name "Circle"
 :jvm-name "nex/repl/Circle_0002"
 :deferred? false
 :parent {:nex-name "Shape"
          :jvm-name "nex/repl/Shape_0001"}
 :fields [...]
 :constants [...]
 :methods [...]
 :creation-routines [...]}
```

### Method Spec Additions

Extend lowered method specs with:

```clojure
{:name "area"
 :owner "nex/repl/Shape_0001"
 :params [...]
 :return-type "Real"
 :return-jvm-type :double
 :deferred? true
 :override? false
 :body nil}
```

For a concrete override:

```clojure
{:name "area"
 :owner "nex/repl/Circle_0002"
 :params [...]
 :return-type "Real"
 :return-jvm-type :double
 :deferred? false
 :override? true
 :body [...]}
```

### Creation Lowering Guard

No new expression op is required. The lowering phase should reject or refuse compilation for:

```clojure
{:type :create
 :class "Shape"
 ...}
```

when the resolved target class is deferred.

### Virtual Dispatch

The existing `:call-virtual` op remains the right abstraction. The change is in resolution metadata:

```clojure
{:op :call-virtual
 :owner "nex/repl/Shape_0001"
 :method "area"
 :descriptor "()D"
 :target {...}
 :args []
 :nex-type "Real"
 :jvm-type :double}
```

This must work when the runtime receiver is a compiled concrete child like `Circle`.

## Lowering Work

### 1. Preserve deferred-class metadata

Class lowering should carry through:

- `:deferred?`
- parent class metadata
- deferred feature declarations
- concrete overrides

This metadata should become part of the canonical compiled session class state.

### 2. Separate deferred features from concrete method bodies

When lowering a deferred class:

- feature declarations with no body lower to methods with `:deferred? true`
- ordinary method bodies lower as before with `:deferred? false`

A deferred method should never lower to a normal body stub in this phase.

### 3. Resolve parent metadata in child lowering

When lowering a concrete child class:

- resolve the parent Nex class name
- resolve the parent JVM class name
- keep inherited method signatures available while lowering the child body
- mark overrides explicitly where possible

This is enough for correct JVM class emission and dispatch, even before adding deeper override diagnostics.

### 4. Prevent compiled instantiation of deferred classes

Creation lowering should refuse compiled lowering for deferred targets.

Recommended behavior:

- if the input is otherwise on the compiled path, deopt cleanly to the interpreter
- if compile-only lowering is being used in a test/helper path, raise a precise compiler error

### 5. Lower calls through deferred-typed references normally

If a variable, field, or parameter is statically typed as a deferred parent and the target feature is defined there:

- lower the call to `:call-virtual` against the parent owner/descriptor
- let JVM dispatch pick the concrete child override at runtime

This keeps the IR simple and matches JVM semantics directly.

## Emitter Work

### 1. Emit deferred classes as abstract JVM classes

For a deferred Nex class:

- emit `ACC_ABSTRACT`
- emit the class with its resolved parent if any
- emit fields and constants normally where allowed by language semantics

### 2. Emit deferred features as abstract JVM methods

For a lowered method with `:deferred? true`:

- emit `ACC_PUBLIC | ACC_ABSTRACT`
- do not emit bytecode body
- do not emit `Code` attribute

This is the correct JVM representation and avoids fake runtime stubs.

### 3. Emit concrete child classes with a real superclass

For a concrete child of a deferred parent:

- emit `superName` as the parent JVM class
- emit normal concrete overrides
- keep the existing default-constructor strategy unless parent-constructor constraints require a small adjustment

### 4. Keep virtual dispatch on `INVOKEVIRTUAL`

No special runtime bridge is needed for user-defined abstract dispatch.

If a call lowers to:

- owner = deferred parent JVM class
- method = deferred feature name
- descriptor = inherited signature

then plain `INVOKEVIRTUAL` is the correct bytecode.

### 5. Keep direct instantiation illegal

The emitter should never produce `NEW DeferredClass` from compiled lowering.

That rule should be enforced before bytecode emission.

## Runtime / REPL Session Work

### 1. Compiled session metadata must store deferred-class shape

Extend canonical compiled session class metadata to keep:

- whether a class is deferred
- parent class identity
- lowered/emitted JVM class name
- deferred feature signatures
- concrete overrides

This must survive deopt and sync, just like top-level functions and concrete classes.

### 2. Compiled REPL eligibility should allow deferred-class definitions

A top-level deferred class definition should stay on the compiled path when it uses only the supported subset.

Concrete children of deferred parents should also stay compiled when they do not use unsupported features.

### 3. Interpreter deopt/sync must preserve deferred-class metadata

When deopting from compiled session to interpreter:

- materialize deferred classes into interpreter-visible class metadata
- preserve parent/child relationships
- preserve deferred feature signatures

When syncing back:

- update compiled session class metadata from interpreter results
- keep class naming/versioning coherent

### 4. Compiled class lookup must resolve parent JVM names

The compiled class registry in the session should support:

- child -> parent lookup
- parent -> current JVM class name lookup
- inherited constant/method visibility where already supported by the language

## Suggested Implementation Order

### Step 1

Extend lowered class/method specs in `src/nex/lower.cljc` to carry:

- `:deferred?`
- `:parent`
- method-level `:deferred?`
- method-level `:override?`

### Step 2

Extend compiled session class metadata in:

- `src/nex/compiler/jvm/repl.clj`

so deferred/parent metadata becomes canonical compiled REPL state.

### Step 3

Update the emitter in:

- `src/nex/compiler/jvm/emit.clj`

so deferred classes emit as abstract JVM classes and deferred features emit as abstract JVM methods.

### Step 4

Update class creation lowering so `create DeferredClass` does not compile.

### Step 5

Update compiled class emission so concrete children use the deferred parent JVM class as `superName`.

### Step 6

Compile and validate virtual calls through deferred-typed references.

### Step 7

Add deopt/sync coverage so compiled-session deferred class state round-trips cleanly through the interpreter path.

## Acceptance Tests

### 1. Deferred class definition compiles

```nex
class Shape deferred
feature
  area(): Real
end
```

Expected:

- compiled class emitted
- JVM class is abstract
- `area` emitted as abstract

### 2. Deferred class instantiation is rejected

```nex
class Shape deferred
feature
  area(): Real
end

let s := create Shape
```

Expected:

- compiled path rejects or deopts before bytecode emission
- behavior matches language rule that deferred classes are not instantiable

### 3. Concrete child of deferred parent compiles and instantiates

```nex
class Shape deferred
feature
  area(): Real
end

class Square extends Shape
feature
  side: Real

  area(): Real
  do
    result := side * side
  end
end

let s: Shape := create Square
```

Expected:

- child compiles with deferred parent as JVM superclass
- child instantiates successfully

### 4. Virtual dispatch through deferred parent type works

```nex
class Shape deferred
feature
  area(): Real
end

class Square extends Shape
feature
  side: Real

  area(): Real
  do
    result := side * side
  end
end

let s: Shape := create Square
s.side := 4.0
print(s.area)
```

Expected:

- compiled call through `Shape` reference dispatches to `Square.area`
- printed result is `16.0`

### 5. Cross-cell compiled REPL flow stays coherent

Cell 1:

```nex
class Shape deferred
feature
  area(): Real
end
```

Cell 2:

```nex
class Square extends Shape
feature
  side: Real

  area(): Real
  do
    result := side * side
  end
end
```

Cell 3:

```nex
let s: Shape := create Square
s.side := 5.0
s.area
```

Expected:

- all three cells can remain on the compiled path
- class metadata remains canonical in compiled session state
- final result is `25.0`

## Exit Criteria

This deferred-class milestone is complete when all of the following are true:

- deferred classes compile to abstract JVM classes
- deferred features compile to abstract JVM methods
- `create DeferredClass` is rejected on the compiled path
- concrete children of deferred parents compile correctly
- virtual dispatch through deferred parent references works in compiled REPL execution
- compiled session metadata preserves deferred-parent relationships across deopt/sync
- the full test suite remains green

## What Should Come After This

Once deferred classes are stable in the compiled path, the next sensible steps are:

1. `super`
2. stronger override diagnostics in the compiled class path
3. contract-aware inheritance in compiled methods
4. broader inheritance-heavy REPL/class test coverage

Do not move to those until deferred classes and parent-typed virtual dispatch are stable and tested.
