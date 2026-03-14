# Future Directions

A language implementation becomes interesting not only because of what it already does, but because of the pressures it reveals. Nex already shows several such pressures clearly. This chapter names them — not as a wishlist, but as a map of where the current architecture points and where it would have to change.

The organising question is not "what else could be added?" but "what additions would strengthen the original purpose of the language?" That question has a different shape for each area, and the answer is sometimes "not much."


## 5.1 Parsing on the JavaScript Side

The current parsing story is strongest on the JVM. The parser is built around ANTLR and `clj-antlr`, which work well in Clojure but have no natural path to ClojureScript. The current workaround — parse on the JVM, then ship ASTs to the JavaScript side — works, but it means the JavaScript target depends on a JVM process for its front end. That asymmetry is the most obvious architectural gap in the system.

A cleaner JavaScript story would require one of three paths: a pure ClojureScript parser, a more polished generated JavaScript parser pipeline, or a more principled approach to AST transport between server and client. None of these is trivial.

The current boundary is defined in [`src/nex/parser.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/parser.clj), [`src/nex/walker.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/walker.cljc), and [`docs/md/CLOJURESCRIPT.md`](https://github.com/vijaymathew/nex/blob/main/docs/md/CLOJURESCRIPT.md). A contributor working on this area should read all three before proposing a direction, because the constraints they reveal are easy to underestimate.



## 5.2 Libraries and Package Management

`intern` is conceptually clean and works well for the library sizes Nex currently needs. But it defers several questions that will eventually need answers: how libraries declare their supported targets, how versions are managed, how dependencies are resolved reproducibly, and how library documentation is discovered by tools rather than by reading source.

The challenge is to answer these questions without burying Nex under the weight of a conventional package manager. Many languages have damaged their teaching value by making the bootstrap experience — getting a working project — the hardest part of learning the language. Nex should avoid that.

The right approach is probably to let the `intern` mechanism stay as it is for as long as possible and add structure only where it is genuinely needed. When the time comes, the pressure points will be `intern` resolution in the interpreter, the shape of the `lib/` tree, and whatever metadata format is chosen for library declarations. Those changes should stay outside the grammar unless a compelling language-level reason emerges.



## 5.3 Richer Static Reasoning

The present typechecker is intentionally conservative. A type system that cannot be explained cannot be taught, and Nex has consistently chosen intelligibility over power where the two are in tension.

That choice has a cost. There is genuine room for more precise reasoning in several areas: flow-sensitive narrowing of detachable types, stronger generic constraints, better interaction between contract clauses and static analysis, and more rigorous checking of inheritance obligations. Each of these would catch real errors that the current checker misses.

The implementation pressure falls almost entirely on [`src/nex/typechecker.cljc`](https://github.com/vijaymathew/nex/blob/main/src/nex/typechecker.cljc) — specifically on environment refinement, generic substitution and method lookup, and the interaction between static analysis and the contract representation in the AST. These are not independent changes. A contributor should expect them to interact, and should test each addition against the principle that the resulting rules remain explainable to a reader without a background in type theory.

The danger is real: a type system can become substantially more powerful while becoming much less useful as a teaching tool. Expansion here should be slow and deliberate.



## 5.4 Tooling

A language designed to teach engineering discipline can benefit disproportionately from good tools, because good tools reinforce the habits the language is trying to instil. Weak tools push the programmer back into guesswork — exactly the mode the language is trying to displace.

Nex already has an interpreter, REPL, debugger, formatter, and documentation generator. The most valuable near-term improvements are probably: clearer and more local error messages (including contract violation messages that name not just which condition failed but what the violation implies about the caller's obligations), stronger IDE support for navigating contracts and inheritance hierarchies, and richer debugger workflows for concurrent programs where the current tooling is thinnest.

These improvements would live in [`src/nex/repl.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/repl.clj), [`src/nex/debugger.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/debugger.clj), [`src/nex/fmt.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/fmt.clj), and [`src/nex/docgen.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/docgen.clj). One advantage of the current codebase is that these tools are still close enough to the language core that they can evolve together without the usual friction between language teams and tooling teams.



## 5.5 Backends and Deployment

The Java and JavaScript generators already demonstrate that Nex is not bound to one execution strategy. That is a structural asset. The questions that any future backend would have to answer — how values are represented, how classes and functions are lowered, how contracts are enforced, how concurrency is expressed, how host integration is handled — are already answered twice in the current codebase. A third backend could follow the same pattern.

The most plausible near-term directions are a better browser-first generation story, improved Node-specific deployment support, and lighter-weight runtime packaging. A WebAssembly-oriented backend is conceivable further out. None of these should be pursued speculatively — the architecture supports them, but that is not by itself a reason to build them.

The pressure points for new backends are concentrated in the generator files and in the runtime helper sections they emit: Java runtime emission in [`src/nex/generator/java.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/generator/java.clj) and JavaScript runtime emission in [`src/nex/generator/javascript.clj`](https://github.com/vijaymathew/nex/blob/main/src/nex/generator/javascript.clj). Reading both before designing a new backend is not optional — the decisions they embody are easy to reinvent badly.



## 5.6 Language Growth and Restraint

The hardest future direction is deciding what not to add.

A language with educational ambitions can easily lose its clarity by accumulating convenience features one at a time, each individually reasonable, collectively blurring the model the language was meant to convey. A language with practical ambitions can just as easily become too small to solve real problems and get abandoned for something less principled but more capable. Nex must keep navigating between these.

The working criterion for additions is straightforward to state: a change is worth making if it makes reasoning about programs clearer, improves practical usefulness without distorting the language model, or strengthens the connection between language design and good engineering. A change that does none of these is noise, however much it is requested.

Implementation details matter here in a way that design discussions often ignore. A proposal that is elegant at the language level may require special cases in every pass — grammar, walker, typechecker, interpreter, and both generators — in ways that accumulate into an implementation that is no longer easy to read. Nex should continue to prefer features that fit the existing architecture cleanly, not because cleanliness is an end in itself, but because a messy implementation makes the semantics harder to verify and the codebase harder for contributors to enter.



## 5.7 What Maturity Looks Like

A mature Nex would not necessarily be a larger Nex. It would be a more settled one: clearer library conventions, stronger tooling, a firmer JavaScript story, more complete documentation, a typechecker and runtime that are broader in coverage but still readable in full.

That last property matters more than it might seem. The codebase is currently small enough that a determined reader can trace any feature from grammar to walker to typechecker to interpreter to generators. That traceability is one of Nex's most valuable properties. Growth should preserve it — not out of sentiment, but because a language designed to teach disciplined thinking about programs should itself be implemented in a way that rewards disciplined reading.

The pressures this chapter has described are real, and some of them will eventually force changes that make the system larger and more complex. The goal is to let that happen slowly enough that the system remains comprehensible at each stage, rather than crossing the threshold — familiar from many language histories — where the implementation becomes something that can only be worked on piecemeal, by specialists, without a view of the whole.

Nex has not crossed that threshold. Keeping it on the right side is the most important long-term design commitment the project can make.
