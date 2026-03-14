# Parsing, ASTs, and Interpretation

The front half of the Nex implementation is conventional in broad outline and deliberately simple in detail. Source text becomes a parse tree, the parse tree becomes a cleaner AST, and that AST is handed to an interpreter that evaluates it directly. There is no intermediate representation, no optimisation pass, no compilation to bytecode before meaning is assigned. The interpreter is the semantics.

That simplicity is a design choice, not a limitation. A tree-walking interpreter is slower than a compiler and unsuitable for production JVM deployment, but it has one property that matters enormously for a language under active development: the connection between the AST and the executed behaviour is immediate and inspectable. If you want to know what a piece of Nex means, you read the interpreter. What it does is what the language does.

The relevant files are:

- [`grammar/nexlang.g4`](https://github.com/vijaymathew/nex/blob/main/grammar/nexlang.g4) — the ANTLR grammar
- [`src/nex/parser.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/parser.clj) — the thin parser layer
- [`src/nex/walker.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/walker.cljc) — grammar tree to semantic AST
- [`src/nex/interpreter.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/interpreter.cljc) — evaluation

If you are reading the code for the first time, treat this chapter as a guide to reading order. The shortest useful path through the implementation is:

1. `parser/ast`
2. the walker's `:program` handler
3. `make-context`
4. `eval-node :program`
5. selected `eval-node` methods for expressions and statements

That path takes you from source text to executable meaning with very little indirection.


## 2.1 The Grammar

The ANTLR grammar defines the concrete syntax of Nex — what strings of characters constitute valid programs. It covers class declarations, functions and anonymous functions, generic parameters, contracts, array and map and set literals, `intern` and `import`, `spawn` and `select`.

The grammar's job is narrow on purpose: answer whether a given string is syntactically well-formed. It should not know whether a call is type-correct, whether an inheritance relation is legal, or whether a contract is meaningful. Those are later questions, and forcing them into the grammar would make it harder to evolve and harder to reason about. A grammar that tries to solve semantic problems is not a grammar any more — it is a grammar with hidden semantics scattered through it.

For a contributor, the practical consequence is simple: when a feature change is really a syntax change, start in the grammar. If it is not a syntax change, do not force it there.



## 2.2 The Parser Layer

[`src/nex/parser.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/parser.clj) is intentionally thin:

```clojure
(def parser
  (antlr/parser "grammar/nexlang.g4"))

(defn parse [input]
  (antlr/parse parser input))

(defn ast [input]
  (-> input
      parse
      walker/walk-node))
```

It creates an ANTLR parser via `clj-antlr`, invokes it on input, and hands the result to the walker. That is all. The thinness is a good sign — it means the parser layer is not secretly acting as a second semantics engine.

It also means the first real implementation boundary is easy to locate. Everything before `walker/walk-node` is parsing infrastructure. Everything after it is language processing.



## 2.3 The Walker: From Grammar Trees to Semantic ASTs

The parse tree that ANTLR produces is faithful to the grammar, but grammar-faithful is not the same as easy to evaluate. Grammar trees carry syntactic noise — token boundaries, optional punctuation, production-rule artifacts — that the rest of the implementation should not have to navigate. The role of [`src/nex/walker.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/walker.cljc) is to convert grammar-shaped trees into semantic AST nodes that the interpreter and type-checker can work with directly.

This conversion produces regular Clojure maps with a `:type` key and a set of named fields:

- `{:type :class ...}`
- `{:type :function ...}`
- `{:type :binary ...}`
- `{:type :call ...}`
- `{:type :spawn ...}`

The walker also normalises syntax that would otherwise require downstream phases to reconstruct:

- `intern` becomes path, class name, and alias
- `import` becomes qualified name and optional source
- generic parameters become structured type data
- source position becomes explicit `:line` and `:col` fields on every AST node

Each of these normalisations is a small thing on its own. Together they mean the interpreter can be written against a clean, explicit representation rather than against whatever the grammar happened to produce.

The walker is large but highly regular. It is best read as a dispatch table from grammar node names to AST constructors. Several parts deserve particular attention:

- the `:program` handler, because it determines the top-level shape of the AST and the main contract with downstream phases
- `:internStmt` and `:importStmt`, because they show how module-related syntax is normalised
- class and function handlers, because they define the structures the typechecker and interpreter consume
- expression handlers for `:binary`, `:call`, `:create`, and `:spawn`, because these become the most frequently evaluated nodes

A useful habit when reading the walker is to keep the corresponding grammar production open alongside it. The grammar shows how tokens are recognised; the walker shows which information survives into the semantic representation. The difference between the two is the walker's entire purpose.



## 2.4 The AST as Plain Data

The AST is represented entirely in plain Clojure data: maps, vectors, strings, keywords, and numbers. This is not incidental — it is one of the strongest architectural decisions in the implementation.

Because the AST is plain data, it can be inspected at the REPL without a special pretty-printer. Tests can construct or compare pieces of it without building a parser. Transformations stay explicit because there is no object graph to traverse imperatively. Later phases remain decoupled from parser internals because they receive ordinary data structures, not opaque parse-tree objects.

It also means feature debugging is unusually direct. When something is wrong, you can inspect the AST produced for a piece of source and ask three separate questions: did the grammar accept the right structure? Did the walker preserve the right meaning? Is the bug actually in the interpreter or generator? Plain data makes those questions answerable without a debugger.



## 2.5 The `:program` Node

The `:program` node is the output of the entire front end and the input to everything that follows. Its structure is worth understanding in detail, because it is the main contract between parsing and execution.

The walker collects top-level material into named buckets:

- imports
- interns
- classes
- functions
- statements
- calls

This structure encodes an important distinction: declarations and executable statements are separated, not interleaved. That separation drives both typechecking order and interpretation order. Classes can reference each other regardless of source position; top-level statements execute after the static world is fully loaded. The `:program` shape is what makes both of those properties straightforward to implement.



## 2.6 The Runtime Context

The interpreter in [`src/nex/interpreter.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/interpreter.cljc) keeps execution state inside a `Context` record. The context holds everything evaluation needs:

- known classes
- global bindings
- the current lexical environment
- captured output
- import metadata
- specialised generic classes

Making this state explicit — as a data structure passed through evaluation rather than as mutable globals — is what keeps the interpreter readable and testable. The evaluator is not a set of mutually recursive functions sharing hidden state. It is an evaluator with a visible runtime world that can be inspected at any point.

### `make-context`

`make-context` is the runtime root: the function that produces the initial context before any user code runs. It does three things:

1. creates the global environment
2. creates the mutable registries used during evaluation
3. registers the built-in base classes — `Any`, `Function`, `Cursor`, `Comparable`, `Hashable`, and the scalar types

A fresh context is therefore not empty in the ordinary sense. It already knows the built-in world of Nex. This is a useful point to keep in mind when debugging: many things that look like user-visible classes are bootstrapped in `make-context` before any user program loads.



## 2.7 The Environment Model

Lexical scope is implemented through nested environments. Variables are introduced with `env-define`, looked up with `env-lookup`, and updated with `env-set!`. Each scope — method body, constructor, spawned task, nested block — gets its own environment that chains to its enclosing scope.

This model is simple, but its simplicity is the point. Because every scope is an explicit structure in the chain, the reason a name resolves to a particular value is always traceable. When a value resolves unexpectedly, the environment chain is the first place to look — and it is always inspectable.



## 2.8 How Evaluation Proceeds

The core evaluator dispatches on node type. `eval-node :program` does the work described in Section 2.5: it processes imports, interns, class definitions, and function definitions before executing top-level statements in source order. The control flow is worth reading directly:

1. store import nodes in the context
2. process all `intern` nodes
3. register class definitions
4. register and instantiate function values
5. execute top-level statements in source order
6. return the updated context

This is not "evaluate everything in order." It is "load the static world first, then execute the dynamic world." That distinction is one of the reasons the implementation stays predictable: by the time any statement executes, all class and function definitions are already in scope.

Within expressions and statements, the evaluator handles:

- variable references through lexical environment lookup
- binary and unary expressions through operator helpers
- calls and method invocations through method lookup and dispatch
- object construction through built-in and user-defined creation paths
- control flow through direct recursive evaluation

### Two Paths Worth Special Attention

Two evaluator paths connect more subsystems than the others and deserve to be read carefully.

`eval-node :call` sits at the centre of routine and method execution. It handles free functions, method calls, built-in method dispatch, function values, and inherited behaviour. If something goes wrong during ordinary execution, the call path is usually involved.

`eval-node :create` sits at the centre of object construction. It handles built-in constructors (arrays, maps, sets, tasks, channels), user-defined class constructors, generic specialisation, contract checking at construction time, class invariant verification, and Java interop fallback on the JVM. Understanding this path means understanding most of the runtime contract machinery.



## 2.9 Built-In Types and the `types/` Subtree

The interpreter hosts Nex's built-in types and methods — arrays, maps, sets, strings, tasks, channels, cursors, and platform utilities — but it delegates the low-level mechanics of primitive operations to [`src/nex/types/`](https://github.com/vijaymathew/nex/blob/main/src/nex/types/). This split keeps the interpreter file from becoming a bag of primitive helpers while still keeping the meaning of the language — how methods are exposed, how built-in constructors behave, how runtime objects are represented — in the interpreter where it belongs.

The practical consequence: if you are debugging the behaviour of an array method, look in `types/`. If you are debugging how array methods are dispatched or what contract checks apply, look in the interpreter.



## 2.10 Source Tracking and Error Localisation

The front end attaches source position metadata — line and column — to every AST node. The interpreter carries debug stack information and source references through method and constructor calls. This infrastructure supports accurate error reporting, debugger integration, and contract failure localisation.

It is the kind of machinery that is easy to overlook when discussing a language in the abstract, but it is what makes a contract violation message say *which* condition failed on *which* line rather than simply crashing. Nex's contract system only delivers its diagnostic value if the runtime can locate failures precisely. The source-tracking infrastructure is what makes that possible.



## 2.11 The Interpreter as Semantic Baseline

The interpreter is the semantic baseline for Nex. Even when Nex is compiled to JVM bytecode or JavaScript, the interpreter remains the authoritative statement of what the language means. The generators are correct when they produce behaviour that matches the interpreter. When they diverge, the interpreter wins.

This has a practical consequence for contributors: if you are unsure what a feature should do, make it work correctly in the interpreter first. The generators should follow the interpreter, not define their own semantics independently. A generator that is easier to write by taking a shortcut from the intended semantics is a generator that is wrong.

The interpreter's clarity is therefore not just an aesthetic property. It is the guarantee that the rest of the implementation has something reliable to follow.
