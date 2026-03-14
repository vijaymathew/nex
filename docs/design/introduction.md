# Introduction {-}

This short book is about Nex as a language system rather than Nex as a teaching surface.

The tutorial and syntax documents explain how to write Nex programs. This manual explains why the language looks the way it does and how the implementation is organised underneath that surface. It is intended for language implementers, contributors, advanced users, and readers who want to understand Nex as a piece of software engineering.

The discussion throughout is tied to the actual source tree. This is not a description of a hypothetical implementation that happens to resemble Nex — it is a description of Nex, and the code is the evidence.


## Design Commitments

Nex is built around a small set of linked commitments, and the tension between them is what gives the design its character.

The first is that correctness should be part of ordinary programming. Not a static analysis pass, not a type system annotation, not a proof obligation that lives in a separate file — but executable conditions woven into the program itself, checked at runtime, reported by name when violated. This is Bertrand Meyer's Design by Contract, carried forward from Eiffel into a language designed for a broader audience.

The second is that readability should survive as systems grow. A language that is clean at ten lines and illegible at ten thousand has solved the wrong problem. Nex borrows from Scheme the conviction that a small, consistent core scales further than a large feature set, and from Eiffel the structural discipline of explicit class interfaces.

The third is that object-oriented structure and functional abstraction should cooperate. Classes, inheritance, and contracts handle the stateful parts of a system; pure functions, higher-order operations, and immutable values handle the rest. Nex does not treat these as competing paradigms — it treats them as complementary tools.

The fourth is that a language can be theoretically informed without becoming hostile to beginners. The contract system has roots in Hoare logic; the type system has roots in ML; the concurrency model — goroutines and channels, borrowed from Go — has roots in CSP. None of this requires the user to know any of it. The theory shows up as design clarity, not as notation.

These commitments are in tension. Contracts add syntax. A clean core resists the features users ask for. Cooperation between paradigms requires the implementation to understand both. The chapters that follow explain how those tensions were resolved — and where they have not been resolved yet.



## Organisation

**Chapter 1** explains the design goals in full: what problem Nex is trying to solve, what it deliberately does not do, and how the four commitments above translate into concrete language decisions.

**Chapter 2** covers the front half of the implementation: the grammar and parser, AST construction, and the tree-walking interpreter. The interpreter is where the semantics of the language actually live, and understanding it is the prerequisite for everything that follows.

**Chapter 3** covers static checking and the two code generators — JVM bytecode and JavaScript. The static checker enforces the type system and contract structure before execution; the code generators translate the verified AST into runnable form.

**Chapter 4** covers extension and integration: how Nex programs call Java and JavaScript libraries, how the module system is organised, and how goroutine and channel concurrency is implemented on top of the JVM and JS runtimes.

**Chapter 5** turns to what is not yet done. Every implementation has a gap between its aspirations and its current state. This chapter names that gap honestly — what is missing, what is intentionally conservative, and where the implementation is likely to grow.



The reader this manual assumes has some familiarity with language implementation concepts — parsing, ASTs, type checking — but does not need expertise. Where implementation techniques require background, the relevant ideas are introduced before they are used.
