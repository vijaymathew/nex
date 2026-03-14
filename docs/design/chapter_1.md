# Design Goals

Nex was not designed as a minimal teaching toy and not as a maximal industrial platform. It was designed to occupy the space between them: a language small enough to understand, disciplined enough to teach sound habits, and practical enough to support real programs.

That combination is harder to achieve than it looks. Teaching languages typically simplify by removing the problems that make software engineering difficult — and so teach a version of programming that does not survive contact with real systems. Production languages solve practical problems while leaving correctness, clarity, and teachability to convention — and so scale in power while failing to scale in comprehensibility. Nex tries to keep all three concerns in view at once. This chapter explains what that means in concrete terms.


## 3.1 A Language That Teaches More Than Syntax

Most beginners first experience programming as a collection of constructs: variables, conditionals, loops, functions, classes. Those constructs matter, but they do not by themselves produce engineers. A student can learn syntax quickly and still have no clear idea how to divide responsibility between classes, how to state what a function assumes, or how to protect an object's internal consistency. Those are judgment calls — and judgment is not taught by syntax alone.

Nex therefore treats software engineering concerns as language-level concerns rather than as conventions to be absorbed later. Contracts are built into the ordinary routine form, not added as an assertion library or a documentation convention. Generic types are ordinary types, not a library trick layered over an untyped core. Concurrency is exposed through explicit tasks and channels rather than through low-level thread APIs that require expert handling to use safely. Modularity and host integration are part of the normal programming story from the beginning.

The hope is not that a language feature can replace judgment — it cannot. The hope is that the language can make good judgment *visible* sooner. A student who writes a precondition has named an assumption. A student who writes an invariant has named a commitment. The act of writing it in executable form is qualitatively different from leaving it in a comment or leaving it unstated.



## 3.2 Why Object-Oriented and Functional Together

Nex is object-oriented because long-lived programs need stable models of things. Classes, constructors, fields, methods, invariants, and inheritance all exist to support that modelling work. When the problem is to represent a bank account, an HTTP request, or a graphics window, a class is the right unit of thought: it names the concept, bundles the data, and specifies the rules under which the data may change.

But classes are not the only useful unit of thought. Sometimes the important thing is not a long-lived object but a transformation — a local computation, a callback, an operation that takes values in and produces a value out without touching any persistent state. For that kind of problem, function values and expression-oriented forms are more natural and more honest. Wrapping a pure computation in a class to satisfy a stylistic rule produces noise without adding clarity.

Nex therefore supports both, not as competing paradigms but as complementary tools. A class may use functions internally when local abstraction serves it better than a new method. A function may manipulate objects when that is what the computation requires. The language makes no ideological commitment to either style. It makes a practical commitment to letting each be used where it fits, without friction between them.



## 3.3 Declarative Structure in an Imperative World

Nex is mostly imperative in execution model. Statements run in sequence. Objects hold state. Loops update variables. This is not an accident — it is a deliberate choice to keep the execution model legible, especially for learners who are still building a mental model of what a running program does.

But many of the most important things about a program are not naturally imperative. Preconditions, postconditions, invariants, and types are all *declarative* — they are claims about what must hold, not instructions for what to do. In most languages, the body of a routine is asked to do all the explanatory work. In Nex, the body is only part of the story. The surrounding claims matter just as much: what callers must provide, what the routine guarantees on exit, what the class maintains across all its operations, what kinds of values are permitted to flow through a given point.

This gives Nex a partly declarative character without turning it into a theorem prover or a logic language. The imperative body handles the *how*; the contract handles the *what* and the *why*. That separation is what allows the language to be simultaneously concrete enough for beginners and rigorous enough for serious use.



## 3.4 Rigor Without Ceremony

Nex wants to be theoretically informed. The contract system has roots in Hoare logic. The type system draws on decades of work in static analysis. The concurrency model has roots in CSP. These are not cosmetic choices — they are the reason the language behaves the way it does and provides the guarantees it provides.

But none of this should require the reader to absorb a heavy formal notation before writing useful code. That affects the surface design throughout. The syntax is intentionally direct. Control flow is explicit. Assertions are named in plain language rather than expressed as logical formulae. Generic parameters use the square-bracket notation that programmers in many languages already recognise. Anonymous functions are present but written in a form that does not obscure what they do.

The same principle governs the implementation. Nex tends to prefer explicit data structures and clearly separated passes over hidden inference engines or elaborate metaprogramming. The goal is a system that remains inspectable — one where a reader who wants to understand why the compiler makes a particular decision can trace it through the source without needing a background in type theory to follow the argument.



## 3.5 Real-World Practicality

If Nex existed only as a vehicle for chapters and exercises, it would not need files, processes, graphics, host imports, or concurrency. A clean teaching language can be entirely self-contained. But then the line between "the language students learn" and "the tools they use in practice" would be too wide, and the habits formed in one would not transfer to the other.

Nex includes practical facilities because the educational story is stronger when it reaches real systems. Collections handle ordinary data work. Files and processes support scripting and system interaction. Graphics libraries support visual programs. HTTP and networking libraries support service-style programs. Tasks and channels support modern concurrent programming. Host interop connects Nex programs to the JVM and JavaScript ecosystems, where the majority of practical libraries live.

These are not concessions to practicality at the expense of the teaching mission. They are part of it. A student who builds a program that reads files, calls a web API, and processes the result concurrently has learned something that a student who builds only in-memory examples has not.



## 3.6 Why the Core Stays Small

There is a temptation in young languages to add every useful abstraction directly into the core grammar. The temptation is understandable — each addition solves a real problem. The cost is that the core grows until it can no longer be held in a single coherent view, and the language becomes something that must be learned piecemeal rather than understood as a whole.

Nex resists that temptation. The grammar should remain small enough that its semantics can be explained without hand-waving. New vocabulary — new operations, new conveniences, new host integrations — belongs in libraries rather than in the language itself. This is why the `intern` mechanism matters: it creates room for growth outside the grammar, so that the language can remain useful to practitioners without becoming unintelligible by accumulation.

A language intended for study should not become, over time, a language that can only be learned by reading a long list of special cases.



## 3.7 The Central Tradeoff

Every design decision in Nex sits inside the same tradeoff: more power often means more complexity; more convenience often means less clarity; more abstraction often means less immediate visibility into what the program actually does.

Nex usually resolves that tradeoff in favour of the side that preserves reasoning, even at some cost in convenience. This is not a refusal to be practical — the previous section established that practicality matters. It is a judgment that a language whose behaviour can be understood is ultimately more useful than a language whose behaviour can only be observed.

That judgment is the design philosophy in one sentence. The chapters that follow explain how it plays out in the implementation.
