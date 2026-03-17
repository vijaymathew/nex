# JVM Bytecode Compiler Plan

This note turns the dynamic JVM compiler idea into a concrete first implementation plan.

The goal is not to replace the tree-walking interpreter immediately. The goal is to add a second execution path that compiles Nex dynamically to JVM bytecode, keeps raw JVM values where practical, and uses Nex runtime helpers only at semantic boundaries.


## Scope of the First Compiler Milestone

Compile a narrow but useful subset first:

- literals
- local and top-level `let`
- assignment
- arithmetic
- comparisons
- `if`
- top-level function definition
- top-level function call
- `print` and `println`
- `result` / returns

Everything else should continue to use the interpreter until the compiled path is stable.


## Design Constraints

- The tree-walking interpreter remains the reference semantics engine.
- The compiled backend should reuse the existing parser and typechecker.
- Compiled code should use raw JVM values where practical:
  - `int`
  - `long`
  - `double`
  - `boolean`
  - `char`
  - `String`
  - `ArrayList`
  - `HashMap`
  - `LinkedHashSet`
- Semantic boundaries should go through runtime helpers:
  - stringification
  - deep equality
  - collection helpers
  - contract checks
  - dynamic function lookup
  - REPL state access


## Namespaces and Files

Add these first:

- `src/nex/ir.cljc`
- `src/nex/lower.cljc`
- `src/nex/compiler/jvm/descriptor.clj`
- `src/nex/compiler/jvm/classloader.clj`
- `src/nex/compiler/jvm/runtime.clj`
- `src/nex/compiler/jvm/emit.clj`
- `src/nex/compiler/jvm/repl.clj`

Patch later:

- `src/nex/repl.clj`
- `src/nex/typechecker.cljc`

Add tests:

- `test/nex/compiler/jvm/descriptor_test.clj`
- `test/nex/compiler/jvm/lower_test.clj`
- `test/nex/compiler/jvm/emit_test.clj`
- `test/nex/compiler/jvm/repl_test.clj`


## IR Shape

The compiler should not emit bytecode directly from the AST. It should lower to a typed IR first.

IR properties:

- each node has a resolved Nex type
- each node has a resolved JVM type
- local variables are explicit
- top-level REPL bindings are explicit
- control flow is explicit
- call dispatch is already classified
- boxing and unboxing are explicit


## Exact IR Data Structures

Use plain maps with `:op`, `:nex-type`, and `:jvm-type`.

### Type Shapes

```clojure
{:tag :scalar :name "Integer"}
{:tag :class :name "Point"}
{:tag :generic-instance :base "Array" :args ["Integer"]}
{:tag :detachable :inner "String"}
```

### JVM Type Shapes

Use a small set of JVM type tags:

```clojure
:int
:long
:double
:boolean
:char
:void
[:object "java/lang/String"]
[:object "java/util/ArrayList"]
[:object "nex/repl/Cell_0001"]
```

### Program Unit

```clojure
{:op :unit
 :name "nex/repl/Cell_0001"
 :kind :repl-cell
 :functions [...]
 :body [...]
 :result-jvm-type [:object "java/lang/Object"]}
```

### Function IR

```clojure
{:op :fn
 :name "greet_user"
 :owner "nex/repl/Fns_0003"
 :params [{:name "name"
           :slot 0
           :nex-type "String"
           :jvm-type [:object "java/lang/String"]}]
 :return-type "String"
 :return-jvm-type [:object "java/lang/String"]
 :locals [{:name "tmp"
           :slot 1
           :nex-type "String"
           :jvm-type [:object "java/lang/String"]}]
 :body [...]}
```

### Core IR Ops

```clojure
{:op :const
 :value 42
 :nex-type "Integer"
 :jvm-type :int}

{:op :const
 :value "hello"
 :nex-type "String"
 :jvm-type [:object "java/lang/String"]}

{:op :local
 :name "x"
 :slot 2
 :nex-type "Integer"
 :jvm-type :int}

{:op :set-local
 :slot 2
 :expr {...}
 :nex-type "Integer"
 :jvm-type :int}

{:op :top-get
 :name "x"
 :nex-type "Integer"
 :jvm-type :int}

{:op :top-set
 :name "x"
 :expr {...}
 :nex-type "Integer"
 :jvm-type :int}

{:op :binary
 :operator :add
 :left {...}
 :right {...}
 :nex-type "Integer"
 :jvm-type :int}

{:op :compare
 :operator :lt
 :left {...}
 :right {...}
 :nex-type "Boolean"
 :jvm-type :boolean}

{:op :if
 :test {...}
 :then [{...} {...}]
 :else [{...}]
 :nex-type "Integer"
 :jvm-type :int}

{:op :call-static
 :class "nex/repl/Fns_0003"
 :method "greet_user"
 :descriptor "(Ljava/lang/String;)Ljava/lang/String;"
 :args [{...}]
 :nex-type "String"
 :jvm-type [:object "java/lang/String"]}

{:op :call-runtime
 :helper :print
 :args [{...}]
 :nex-type "Void"
 :jvm-type :void}

{:op :call-repl-fn
 :name "greet_user"
 :args [{...}]
 :nex-type "String"
 :jvm-type [:object "java/lang/Object"]}

{:op :box
 :from :int
 :to [:object "java/lang/Object"]
 :expr {...}
 :nex-type "Integer"
 :jvm-type [:object "java/lang/Object"]}

{:op :unbox
 :from [:object "java/lang/Object"]
 :to :int
 :expr {...}
 :nex-type "Integer"
 :jvm-type :int}

{:op :return
 :expr {...}
 :nex-type "Integer"
 :jvm-type :int}

{:op :pop
 :expr {...}}
```


## Runtime Representation

### Repl State

The REPL needs explicit mutable state. Do not try to synthesize JVM globals for every top-level binding.

Use a small runtime object:

```clojure
(defrecord NexReplState [values types functions counter])
```

Suggested content:

- `values`: `Atom<HashMap<String,Object>>`
- `types`: `Atom<HashMap<String,String>>`
- `functions`: `Atom<HashMap<String,Object>>`
- `counter`: `Atom<long>`

Required helpers:

- `make-repl-state`
- `state-get-value`
- `state-set-value!`
- `state-get-fn`
- `state-set-fn!`
- `next-class-name!`

### Function Wrapper

Use a tiny runtime interface for REPL-indirect calls:

```java
public interface NexFn {
  Object invoke(Object[] args);
}
```

For the first version, a reflection-backed wrapper is acceptable.


## Classloader Model

Use a dedicated dynamic classloader per REPL session.

Rules:

- never redefine the same JVM class name in the same loader
- always generate fresh internal names
- keep one loader for one REPL session

Example generated names:

- `nex/repl/Cell_0001`
- `nex/repl/Cell_0002`
- `nex/repl/Fns_0003`

State shape:

```clojure
{:loader ...
 :bindings {"x" {:type "Integer" :owner-class "nex/repl/Cell_0012" :field "x"}}
 :functions {"f" {:owner-class "nex/repl/Fns_0007" :method "f"}}
 :classes {"Point" "nex/gen/Point_v5"}
 :counter 12}
```

Even if the first version does not use all of this data, the model should anticipate versioned redefinition.


## Descriptor Layer

`src/nex/compiler/jvm/descriptor.clj` should be small and mechanical.

Responsibilities:

- map Nex types to JVM types
- map JVM types to JVM descriptors
- build method descriptors
- compute internal class names

Examples:

```clojure
(nex-type->jvm-type "Integer") ;=> :int
(nex-type->jvm-type "String")  ;=> [:object "java/lang/String"]

(method-descriptor [:int [:object "java/lang/String"]] :void)
;=> "(ILjava/lang/String;)V"
```


## Lowering Layer

`src/nex/lower.cljc` should transform typed AST into explicit IR.

Responsibilities:

1. resolve names
2. classify calls
3. insert boxing and unboxing
4. lower built-ins to runtime helpers where needed
5. assign local slots
6. turn REPL top-level names into explicit state reads and writes

Suggested lowering env:

```clojure
{:locals {"x" {:slot 2 :nex-type "Integer" :jvm-type :int}}
 :top-level? true
 :repl? true
 :current-fns {...}
 :state-slot 0}
```

Key entry points:

- `lower-program`
- `lower-repl-cell`
- `lower-function`
- `lower-expression`
- `lower-statement`


## ASM Emission Strategy

Use ASM directly.

Initial strategy:

- `ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS)`
- plain classes
- plain static methods
- one generated class per compilation unit

Emit these first:

- REPL cell class with `public static Object eval(NexReplState state)`
- function holder class with `public static` methods

For phase 1, do not try to encode every Nex feature.

Required emission helpers:

- `emit-class`
- `emit-method`
- `emit-stmt`
- `emit-expr`
- `emit-box`
- `emit-unbox`
- `emit-runtime-call`
- `compile-unit->bytes`

Recommended method shape for REPL:

```java
public static Object eval(NexReplState state)
```

This method:

- loads top-level values from `state`
- executes the compiled cell body
- writes updated top-level values back to `state`
- returns the REPL result as `Object`


## REPL Let State and Function Redefinition

### Top-Level `let`

Top-level `let` should update shared REPL state.

Compiled REPL cells should:

- read existing bindings from `state.values`
- cast or unbox as needed
- write updated values back with `state-set-value!`

This avoids per-variable static fields and keeps redefinition manageable.

### Function Definitions

Top-level functions should be compiled to fresh versioned classes or methods.

For file compilation:

- direct static invocation is fine

For REPL compilation:

- use state-based dynamic lookup for top-level function names
- new definitions update the binding in `state.functions`
- later REPL cells see the new definition

That means two call modes are needed:

- `:call-direct` for stable file compilation
- `:call-repl-fn` for redefinable REPL names


## First Compiler Milestone Checklist

### Step 1

Add descriptor layer.

- `descriptor.clj`
- tests for primitive and object descriptor mapping

### Step 2

Add REPL runtime state.

- `runtime.clj`
- `NexReplState`
- state get/set helpers

### Step 3

Add `ir.cljc`.

- define op shapes
- add comments documenting invariants

### Step 4

Lower these forms:

- literals
- binary arithmetic
- comparisons
- `let`
- assignment
- top-level var get/set

### Step 5

Emit one REPL cell class.

- static `eval(state)` only

### Step 6

Integrate compiled REPL for:

- integer and string expressions
- `let`
- `print`
- top-level variable reuse across cells

### Step 7

Lower and emit top-level functions.

- direct static methods for normal compilation
- REPL function state lookup for redefinition

### Step 8

Add `if`.

### Step 9

Add interpreter fallback.

If lowering sees an unsupported construct, route that input through the tree-walking interpreter instead of failing hard.

### Step 10

Add debug metadata.

- source line numbers
- stable naming tied to REPL cell counter


## Acceptance Tests for the First Milestone

### 1. Top-Level `let`

```nex
let x := 10
x + 2
```

Expected result:

```text
12
```

### 2. Top-Level Function

```nex
function inc(n: Integer): Integer
do
  result := n + 1
end

inc(4)
```

Expected result:

```text
5
```

### 3. Function Redefinition

```nex
function f(): Integer do result := 1 end
f()

function f(): Integer do result := 2 end
f()
```

Expected results:

```text
1
2
```

### 4. Top-Level Variable Persistence

```nex
let name := "Vijay"
print(name)
```

Expected output:

```text
"Vijay"
```


## What Not to Compile in Phase 1

Do not try to compile these yet:

- classes
- inheritance
- closures
- contracts
- exceptions
- tasks and channels
- full loops
- graphics
- host interop edge cases

The right first milestone is narrow and solid, not broad and brittle.


## Immediate Next Step

Start by scaffolding these files:

- `src/nex/ir.cljc`
- `src/nex/compiler/jvm/descriptor.clj`
- `src/nex/compiler/jvm/runtime.clj`
- `src/nex/compiler/jvm/classloader.clj`

Once those exist with tests, add `lower.cljc` and compile one trivial REPL cell end to end.
