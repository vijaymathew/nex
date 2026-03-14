# Libraries, Host Integration, and Concurrency

The previous chapters described Nex as a language core — syntax, semantics, typechecking, code generation. This chapter describes how that core reaches outward: into reusable libraries, host platforms, and concurrent execution.

Each of these involves a design tension that is worth naming before the implementation details. Libraries must grow the language without growing the grammar. Host integration must connect to real ecosystems without pretending those ecosystems are part of Nex. Concurrency must be expressible in terms that preserve reasoning rather than opening a trapdoor into unstructured shared state. How Nex resolves each tension is the actual subject of this chapter.


## 4.1 Extending Nex with Libraries

The preferred extension mechanism for Nex is not "add another core keyword." It is "write a Nex library and load it with `intern`." This is a crucial design choice. It keeps the language kernel small and pushes growth into ordinary source-level abstractions — the same kind the user writes.

Library loading is implemented in the interpreter through a straightforward pipeline. The `intern` form is parsed into an AST node, the file path is resolved, the library source is parsed, the classes defined there are registered, and an optional alias is bound. The interpreter searches for interned files in sensible locations: the current source directory, the current working directory, and user-level dependency locations.

The relevant interpreter functions are `intern-search-roots`, `find-intern-file`, and `process-intern`. Together they implement the concrete flow:

1. determine search roots relative to the current source file and process environment
2. derive candidate filenames from the requested class name
3. find the first matching `.nex` file
4. parse it
5. evaluate the resulting AST in the current context
6. bind the loaded class name or alias

This is a small mechanism, but it is the whole of Nex's current library model. Its smallness is a virtue: it is simple enough to understand completely and flexible enough to support the libraries that already exist.

### What Libraries Cover

The `lib/` tree shows the intended scope. Nex libraries handle file and directory operations, text processing, time, networking, and higher-level wrappers around runtime services. This is the right boundary: core semantics in the language and runtime, domain-specific convenience in libraries written at the surface level.

Useful examples live under [`lib/io`](https://github.com/vijaymathew/nex/tree/main/lib/io), [`lib/net`](https://github.com/vijaymathew/nex/tree/main/lib/net), [`lib/text`](https://github.com/vijaymathew/nex/tree/main/lib/text), and [`lib/time`](https://github.com/vijaymathew/nex/tree/main/lib/time). Reading these alongside the `intern` pipeline shows how much can be expressed in Nex itself once the underlying runtime hooks exist.



## 4.2 Java Integration

Nex supports host interoperation with Java through `import`. The design is intentionally modest: Nex is not implementing the full Java name-resolution model. It is giving the programmer a clear and explicit bridge to imported host classes.

In the AST, Java imports are represented as import nodes with a qualified name and no `source` field. The interpreter stores these nodes in the runtime context. When Nex needs to resolve a host class, it searches the stored imports and matches the simple class name against the imported qualified names. The Java generator uses the same information to emit Java `import` statements in generated code.

The implementation path is worth tracing. The walker records imports as AST nodes. The interpreter stores those nodes. Later resolution is a straightforward lookup against that stored information. There is no hidden class-path scanning or implicit resolution — the programmer's explicit `import` is the entire record of what host classes are available.

This is a modest but effective design. It gives Nex a clean bridge into the JVM ecosystem without requiring that all useful host functionality be wrapped as a Nex built-in from the start. New Java classes become available to Nex programs as soon as they are explicitly imported, and the cost of adding that bridge is a single line.



## 4.3 JavaScript Integration

JavaScript integration uses the same surface concept but a different host form. When a Nex `import` includes a `from` clause, the walker records both the imported identifier and its source path. The JavaScript generator turns that into an ES module import.

The key distinction is captured in the AST:

- `import X` — qualified name only, no `source` — is a Java import
- `import X from "module"` — qualified name plus `source` — is a JavaScript import

The Java generator ignores imports that have a `source`. The JavaScript generator emits only imports that do. This simple AST distinction keeps the front end shared while allowing the backends to behave differently with no ambiguity. It is a clean example of the general pattern: one representation, multiple interpretations at generation time.



## 4.4 Platform-Specific Code

Java and JavaScript APIs differ in ways that cannot always be abstracted away. Rather than hiding that fact, Nex allows explicit target-specific sections with `with "java"` and `with "javascript"` blocks. This is a pragmatic choice, and an honest one: the portability boundary is made visible rather than papered over.

The implementation is consistent across all three execution paths. The interpreter evaluates the branch that matches its host and ignores the others. The Java generator emits `with "java"` blocks and omits the rest. The JavaScript generator does the reverse. In each case the decision is made at the same point in the pipeline — statement evaluation — not as a preprocessing step.

This means `with` is not a textual macro trick. It is a real target-discrimination construct that survives the full implementation stack. A programmer who writes `with "javascript"` can reason about exactly where that code will and will not run.



## 4.5 Concurrency: Design Before Implementation

Concurrency in Nex is expressed through language-level constructs — `spawn`, `Task`, `Channel[T]`, `await`, `await_any`, `await_all`, and `select` — rather than through raw host threads or shared mutable objects. This is one of the most significant design decisions in the language, and it is worth understanding the motivation before the implementation.

Locks and shared mutable state can express any concurrent program, but they hide the structure of coordination in ways that make programs hard to reason about and hard to teach. When one task sends and another receives on a channel, the communication path is visible in the source. The typechecker can check that the types agree. A reader can follow the coordination without inferring it from lock acquisition patterns. Channels do not just provide synchronisation — they make synchronisation legible.

That conceptual choice propagates into the implementation. The typechecker has special rules for legal `select` clauses precisely because `select` is a controlled concurrency abstraction, not an arbitrary expression. The generators lower channel operations differently on JVM and JavaScript, but both preserve the same visible coordination structure at the surface level.



## 4.6 JVM Concurrency Implementation

In the JVM interpreter, concurrency is built on Java primitives: `CompletableFuture`, executors, timeouts, and cancellation support. This gives the interpreter a real concurrent runtime — tasks execute asynchronously, awaits can block or time out, channels can be unbuffered or buffered, and `select`-style coordination is implemented concretely.

The execution flow for `spawn` is straightforward:

1. create a new lexical environment for the spawned body
2. initialise a `result` binding
3. execute the body asynchronously
4. inspect whether `result` was assigned
5. wrap the underlying future in a Nex `Task`

Channels are implemented as built-in runtime objects rather than user-defined classes. Their constructors are recognised specially, and their methods are wired into both the interpreter and the typechecker. This is one of the places where Nex uses a built-in abstraction because the semantics genuinely belong in the runtime — a user-defined `Channel` class could not provide the synchronisation guarantees the language model requires.

To trace the concurrency path end to end, start at the interpreter's `:spawn` evaluator and work outward to the `Task` and `Channel` method support. The typechecker's registration of `Task` and `Channel` is the corresponding static half of the same story.



## 4.7 JavaScript Concurrency Implementation

JavaScript cannot offer the blocking-thread model the JVM provides, so the JavaScript backend uses async control flow and promise-based mechanisms to represent tasks and channel coordination. The surface language does not change. The implementation strategy does.

This becomes especially visible in `select`. Both generators lower `select` into explicit coordination loops, but in target-appropriate ways:

- the Java generator uses a loop with small sleeps and direct runtime calls
- the JavaScript generator uses async loops, immediate probes such as `try_receive`, and promise-friendly suspension

The structure is similar enough that comparing `generate-select` in both generators is one of the fastest ways to understand what `select` means in Nex independent of either host. Where the generators agree, the behaviour is language-level. Where they diverge, the divergence is a host accommodation, not a semantic difference.

For the async boundary specifically, `generate-spawn-expr`, `generate-select`, and `generate-select-clause-js` are the functions to read. They make explicit how Nex's synchronous-looking concurrency model is lowered into JavaScript's explicitly asynchronous one.



## 4.8 The Design Pressure

Libraries, host integration, and concurrency all test the same underlying question: can Nex remain conceptually clean while reaching into real systems?

The current implementation answers that question with a consistent strategy: keep the core syntax and semantics explicit; push practical growth into libraries; use host interop directly when that is the honest solution; and build concurrency out of visible coordination primitives rather than exposing the host's threading model raw.

Each part of this chapter is an instance of that strategy applied to a different kind of outward reach. The `intern` mechanism keeps library loading as ordinary as possible. The import distinction keeps host integration traceable. The `with` construct keeps platform specificity visible rather than implicit. And the channel model keeps concurrency in the language's reasoning model rather than below it.

For contributors, the practical reading order for this chapter's material is:

1. the `intern` path in the interpreter
2. the import path in the walker and both generators
3. the built-in `Task` and `Channel` support in the typechecker
4. the `spawn` and `select` implementations in the interpreter and generators

That sequence moves from the simplest form of outward reach to the most complex, and it is the shortest path to understanding how Nex extends beyond its local semantics without losing its character in the process.
