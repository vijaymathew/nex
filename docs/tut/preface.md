# Preface

This book teaches programming through a language designed to make good habits unavoidable.

Most programming books introduce correctness late — after syntax, after data structures, after you have already built several programs the wrong way. Contracts, specifications, and invariants appear in advanced chapters, if they appear at all, as techniques for experts working on serious systems. The implicit message is that beginners should first learn to make programs work, and worry about making them right later.

This book takes the opposite position. The question *what must be true before this code runs, and what will be true when it finishes?* is not an advanced question. It is the first question. Every function you write, every class you design, every loop you construct has an answer to it — whether you state the answer explicitly or leave it implicit for someone else to discover through failure. Nex is a language that asks you to state it.


## What Nex Is

Nex is a programming language in the tradition of Eiffel, designed around the principle of Design by Contract. Its `require`, `ensure`, and `invariant` keywords are not annotations or documentation conventions — they are executable parts of the program, checked at runtime, reported by name when violated. A precondition that fires tells you precisely which caller broke which obligation. A postcondition that fires tells you precisely which routine broke which promise. An invariant that fires tells you precisely which operation left an object in an inconsistent state.

Nex also has the practical properties a modern language needs. It compiles to the JVM and to JavaScript. It supports generic types, multiple inheritance with conflict resolution, structured exception handling, and a REPL for interactive development. It is not a toy language that simplifies away the concerns that arise in real systems. It is a language in which real systems can be built — with the additional property that the assumptions underlying those systems can be stated and checked.

For the purposes of this book, Nex is also a lens. The engineering habits it makes explicit — thinking carefully about what a function requires, what it guarantees, and what must always remain true — transfer directly to any language you work in after this one. Contracts may have different syntax in Java or Python or TypeScript, or no syntax at all, but the thinking they represent is universal.



## How This Book Is Organised

The book is divided into eight parts.

**Part I** covers the basics: values, variables, expressions, conditionals, and loops. By the end of Part I you will be writing small complete programs and running them in the REPL.

**Part II** introduces functions — how to define them, how to compose them, and how to think about what they do independent of how they do it. It ends with recursion, which is worth understanding early because it is one of the clearest demonstrations that thinking carefully about a problem is more useful than writing code quickly.

**Part III** covers the built-in data structures — arrays and maps — and the patterns that arise when data is nested and composite. Understanding what a data structure is good for, and where it falls short, is a skill that pays dividends throughout a programming career.

**Part IV** introduces classes and objects. The emphasis is not on syntax but on design: what a class should be responsible for, what it should expose, and what it should hide. Inheritance and generic types are introduced here as tools with specific uses rather than features to be applied by default.

**Part V** is the heart of the book. It covers Design by Contract in full: preconditions, postconditions, class invariants, and loop contracts. By the time you reach Part V you will have been writing programs for several chapters, and you will have accumulated intuitions about what can go wrong and where. Part V gives those intuitions a precise form.

**Part VI** covers errors and recovery — the distinction between a contract violation, which represents a programming error, and an exception, which represents a condition the program must handle gracefully.

**Part VII** addresses the concerns that arise when programs grow: splitting code across files, interoperating with Java and JavaScript libraries, and testing. These chapters are shorter than the earlier ones, because the habits established in Parts I through VI make the material here straightforward.

**Part VIII** brings everything together in a single worked example and a survey of common patterns. It closes with pointers to what comes next.



## How to Use This Book

Read it with a REPL open.

Every code example in this book can be typed at the Nex REPL and will produce the output shown. No example requires installation beyond accessing the browser-based IDE at [nex-lang.org/ide]. The exercises at the end of each chapter are meant to be done, not read. The ones marked with an asterisk extend the chapter's ideas into territory that is not fully covered — they are invitations to explore rather than tests of recall.

If you are new to programming, move through Parts I and II slowly. Implement every example yourself rather than reading past it. The understanding that comes from having run a piece of code is qualitatively different from the understanding that comes from having read it.

If you are an experienced developer coming to Nex from another language, you may move quickly through Parts I through IV and spend more time on Part V. The contract system will be new even if the surrounding language features are familiar, and it is worth working through the examples carefully rather than skimming.

If you are using this book as a companion to *Engineering Software That Lasts* — the design and engineering text that uses Nex throughout — the two books are complementary but independent. This book teaches the language; that one uses it to teach software engineering. You can read them in either order, though reading this one first will make the code in that one immediately accessible.



## A Note on Errors

When a contract is violated in Nex, the runtime reports the violation by name:

```
Error: Precondition violation: enough
```

This is not a crash. It is a diagnostic. The name tells you which condition failed, which tells you which assumption was wrong, which tells you where to look. Learning to read contract violations as information — as the program telling you precisely what went wrong and where — is one of the habits this book is designed to build.

When you encounter an error you do not understand, resist the impulse to change code until the error makes sense. The error is evidence. Reading it carefully is almost always faster than guessing at a fix.



## Acknowledgments

Nex stands on the shoulders of Bertrand Meyer's work on Eiffel and Design by Contract — one of the most consequential ideas in the history of programming language design. The intellectual debt is large and gladly acknowledged.

The structure of this book owes much to Kernighan and Ritchie's *The C Programming Language*, which remains the clearest demonstration that a programming book can be complete without being long, and precise without being inaccessible.



*The REPL is waiting. Type something.*
