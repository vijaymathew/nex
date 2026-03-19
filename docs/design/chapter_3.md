# Typechecking and Code Generation

After parsing and AST construction, the implementation has two different but related questions to answer.

First: is the program statically coherent? Second: if it is, how should it run on a host platform?

The typechecker answers the first question. The two code generators answer the second. This chapter covers all three, in that order, because the generators depend on the typechecker and the typechecker depends on the AST — the pipeline runs left to right, and so does the explanation.


## 3.1 The Typechecker

The typechecker in [`src/nex/typechecker.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/typechecker.cljc) is a proper pass over the AST, not a set of ad hoc checks buried in the interpreter.

That separation matters on two levels. At the language level, it gives programmers earlier and clearer feedback — a type error reported before execution is more useful than a runtime failure with a confusing message. At the implementation level, it prevents the interpreter and generators from rediscovering static facts on every evaluation. Facts established once by the typechecker do not need to be re-established downstream.

### The Type Environment

Typechecking is fundamentally a process of carrying and refining assumptions. The typechecker makes this process visible by maintaining an explicit environment that records:

- variables and their types
- methods and their signatures
- classes and their definitions
- proven non-`nil` facts that hold in the current control-flow scope

The environment is created with `make-type-env` and manipulated through a small set of named operations: `env-add-var`, `env-lookup-var`, `env-add-method`, `env-lookup-method`, `env-add-class`, and `env-mark-non-nil`. Because nearly all of the checker's knowledge lives in this one explicit structure, the pass is straightforward to inspect and debug. There is no hidden compiler state to account for.

### The Main Typechecking Flow

The best entry point into the typechecker is `check-program`. Its control flow is clear and worth reading directly:

1. create a fresh type environment
2. register imported Java classes as placeholder class names
3. collect class information for all declared classes
4. register built-in methods and built-in classes
5. inject any pre-existing variable types, such as REPL bindings
6. register top-level functions as callable values
7. check class bodies
8. check top-level statements in source order

The ordering is not arbitrary. Class information is collected before class bodies are checked so that forward references resolve correctly. Built-ins are registered after user class collection so that the checker has access to both before it begins body-checking. Top-level statements are checked last, preserving the source-order feel of the language while ensuring all declarations are already in scope when they are needed.

This is a good example of Nex's general implementation style: a multi-pass pipeline with explicit, non-overlapping responsibilities.



## 3.2 What the Typechecker Checks

The typechecker is substantial. It handles scalar types, arrays, maps, sets, tasks, channels, and function values; generic classes and their arguments; constructor calls; inheritance-aware method lookup; detachability and `nil`; `convert`; `spawn` result typing; and the validity rules for channel operations and `select`. This is enough to protect real programs without being so complex that it becomes opaque.

Reading through the file, the implementation clusters into four areas:

- **expression checking** — computing the type of literals, calls, binary expressions, constructors, and so on
- **statement checking** — updating the environment across `let`, assignment, loops, conditionals, and blocks
- **class checking** — validating fields, constructors, methods, inheritance, and invariants
- **built-in registration** — giving core types such as `Array`, `Map`, `Set`, `Task`, and `Channel` their method signatures

Three features have richer semantics than ordinary method calls and deserve closer attention.

### Spawn Typing

`spawn` is handled by a dedicated path rather than by pretending it is a normal function call. The checker creates a nested environment, introduces a synthetic `result` binding, and tracks assignments to that binding to infer the task's element type. A spawn body that assigns no result produces `Task`; one that assigns a consistent type produces `Task[T]`; one with inconsistent assignments is rejected. This precision would not be achievable if `spawn` were treated as an ordinary expression.

### Channels and `select`

The same explicit treatment applies to channels and `select`. The checker registers `Channel[T]` methods as built-ins, but it also has dedicated rules for constructor forms such as `Channel.with_capacity`, the send and receive operations, timeout arguments, and the legality of `select` clauses. This is necessary because `select` is not a method call — it is a control-flow form whose clauses are restricted to a small set of communication patterns, and those restrictions must be enforced by the checker, not discovered at runtime.

### Nil and Detachability

The checker tracks proven non-`nil` facts in the type environment. This gives it limited but useful flow-sensitive behaviour: after certain checks, a variable may be treated as safely non-detachable within a particular branch. The implementation records locally justified facts in the environment and uses them where appropriate. It does not attempt global dataflow analysis or theorem proving — the goal is precision where it is achievable without obscurity.



## 3.3 Conservative by Design

The typechecker is not trying to be heroic. It does not perform deep global inference or sophisticated constraint solving. That restraint is deliberate.

Nex is intended to teach programmers how to state intent clearly. A type system that works primarily through invisible cleverness would undermine that goal — if the rules cannot be explained, they cannot be taught. The checker aims to be precise enough to catch real errors and simple enough that a contributor reading the file can understand how every rule is applied.

This is a recurring theme across the Nex implementation: precision matters, but intelligibility matters too. Where they are in tension, Nex usually chooses intelligibility.



## 3.4 Why Two Generators

Nex has two code generation targets: Java on the JVM and JavaScript for Node.js and browser environments. The relevant files are:

- [`src/nex/generator/java.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/generator/java.clj)
- [`src/nex/generator/javascript.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/generator/javascript.clj)

Both generators are source-to-source translators rather than optimising native compilers. Their task is to preserve Nex semantics in practical host languages. The main control flow is intentionally similar in both:

1. parse Nex source
2. type-check it unless explicitly disabled
3. walk the AST and emit target-language code
4. splice in runtime helpers needed by that target

Type-checking before generation is the right default. A generator should not silently produce host code for a program that Nex itself considers ill-typed.

Having two targets also imposes a useful discipline. Any feature added to Nex must make sense in three settings — interpreted on the JVM, translated to Java, and translated to JavaScript. That pressure discourages features that only work by accident in one execution model. If you add or change a language feature, you should expect to touch the grammar, the walker, the typechecker, the interpreter, and both generators. This is not duplication for its own sake. It is how Nex keeps semantics explicit across the whole system.



## 3.5 The Java Generator

The Java generator is the richer of the two backends, which is natural: the JVM is the primary deployment target, and many Nex runtime services map comfortably onto Java facilities.

The generator is large but organised around a small set of recurring tasks: mapping Nex types to Java types; generating expressions, statements, and class members from AST nodes; and emitting runtime helper code for language features that Java does not directly provide. To read it effectively, start with these functions and follow the control flow downward:

- `translate` and `translate-ast` — whole-program entry points
- `generate-class`, `generate-method`, `generate-constructor` — class-level emission
- `generate-expression`, `generate-statement` — local code generation

The generator emits translated Nex classes, helper base classes for function values, runtime support for contracts and invariants, helpers for arrays, maps, sets, cursors, tasks, channels, and I/O.

One design decision worth noting: the Java generator embeds a substantial runtime alongside the translated user code. A translated Nex program carries the parts of the runtime it needs rather than assuming a separately installed Nex VM. This keeps deployment simple at the cost of larger output, a tradeoff that is appropriate for Nex's current use cases.



## 3.6 The JavaScript Generator

The JavaScript generator serves a different purpose. It must preserve Nex's semantics in a host environment with a different object model, single-threaded event-loop execution, promises rather than JVM blocking primitives, and ES module import conventions. It does not simply mirror the Java emitter — it re-expresses Nex semantics in a JavaScript-native form.

The generator is organised around the same four concerns as the Java generator — type mapping, expression emission, statement emission, and class emission — but several target-specific areas are worth calling out.

First, the generator must decide when operations should produce `await`-bearing code. This matters for task and channel semantics, where the event-loop model requires explicit async boundaries that the JVM does not.

Second, JavaScript imports are generated from Nex `import ... from ...` forms rather than from JVM-style qualified names, reflecting the ES module convention.

Third, target-specific `with "javascript"` blocks are retained while `with "java"` blocks are omitted. This is a visible example of compile-time target selection — the same source file can contain platform-specific fragments, and each generator takes only what belongs to it.

For the concurrency side specifically, read `generate-spawn-expr`, `generate-select`, and `generate-select-clause-js`. These functions make explicit how Nex concurrency is lowered into async JavaScript without changing the surface language — the semantics are preserved, but the mechanism is entirely different from the JVM path.



## 3.7 Generators as Semantic Documents

A source generator is not only an output mechanism. It is also a semantic document.

Reading the Java and JavaScript generators side by side is instructive precisely where they diverge. Where both generators take the same path, the feature is straightforwardly language-level. Where they diverge — concurrency being the clearest example — the divergence reveals something real about Nex: the language semantics are more fundamental than any one execution model, and the generators are two independent proofs of that claim.

The larger architectural point is that parsing and typechecking are shared while code generation diverges only where the targets genuinely differ. One syntax, one AST, one static model, multiple execution strategies. That separation is what a language implementation should aim for, and Nex largely achieves it.
  * 
