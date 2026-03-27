---
numbered: false
---

# Foreword: What This Book Is For {-}

There is no shortage of programming books.

There are books that teach Python with clarity and patience. Books that explain React, or Rails, or the internals of Postgres. Books devoted to functional programming, to object-oriented design, to the architecture of distributed systems. For almost any technology a working programmer might encounter, a competent guide exists.

And the serious books exist too. *Code Complete* has guided developers through the full arc of software construction for thirty years. *A Philosophy of Software Design* makes a careful argument about what separates good design from bad. *The Pragmatic Programmer* addresses the craft of software development with breadth and honesty. SICP remains, decades after its publication, one of the finest demonstrations of how to think computationally. These are real books that have made real differences to the engineers who read them.

So the claim this book makes is not that the field has been neglected. It has not.

The claim is more specific: there is a synthesis that is difficult to find in a single place.


## What Is Hard to Find

Algorithms are taught in one course. Data structures in another. Design patterns in a third. System design in a fourth. Testing in a fifth. Each subject has its own textbook, its own examples, its own vocabulary. A diligent student can work through all of them and still emerge without a clear picture of how they fit together — because the connections between them are rarely the subject of explicit instruction.

Those connections are where the most important engineering decisions live.

The choice of algorithm depends on the cost model. The cost model depends on the data representation. The data representation depends on the queries the system must answer. The queries depend on the problem statement. The problem statement depends on understanding the real situation the software must address. These dependencies run in both directions: a good algorithm choice can reveal that the data model needs revision, and a good data model can make a complex algorithm unnecessary.

Teaching these as separate subjects leaves the synthesis to the student — to be assembled, if at all, through years of practice and a fair amount of failure. Most books address one part of the chain with depth and leave the rest implicit. The books that address the whole tend to stay at altitude, treating process and methodology rather than the technical substance of engineering decisions.

What is genuinely rare is a book that:

- treats algorithms, data structures, contracts, design, testing, and debugging as a single connected argument rather than adjacent topics
- follows that argument through the same running systems, chapter after chapter, so the reader sees how each tool applies to the same problem at different levels of abstraction
- takes the specification-to-implementation pipeline seriously as the central discipline — the idea that the hardest engineering work happens before the first line of code, in the precise articulation of what the system must do and what must always remain true

This book is an attempt to provide that synthesis.



## What This Book Does Differently

It does not start with a language. It starts with a problem: how do you look at a messy situation and identify what the software must actually do? How do you write a specification that is precise enough to build from, but honest enough to acknowledge what is not yet known? These are the questions of Part I, and they precede any code.

It does not start with a paradigm. Functional thinking and object-oriented thinking are both tools, and this book treats them as tools — each suited to certain problems, each with characteristic failure modes, neither universally correct. Part VI introduces both in the same breath, as complementary strategies for organising software, and lets the problem determine which applies.

It does not restrict itself to a domain. The three systems that run through this book — a delivery network, a knowledge engine, a virtual world — are chosen precisely because they are different. Different domains, different data structures, different algorithmic requirements, different failure modes. Following the same engineering questions through all three reveals what transfers and what does not. That transfer is the skill. It is what distinguishes a developer who has learned one framework from an engineer who can reason about systems they have never seen before.



## Who This Book Is For

This book is for developers who have learned to write code and want to learn to engineer software.

If you can write a working program but would not confidently recognise a fragile design before it became a production incident, this book is for you. If you have studied algorithms but are not sure how to choose between them when building a real system, this book is for you. If the pieces of software engineering — the design thinking, the data structures, the testing discipline, the component boundaries — exist separately in your mind and have not yet assembled into a coherent whole, this book is for you.

It is also — unapologetically — for students. The engineering habits this book teaches are not advanced topics to be introduced after the fundamentals are mastered. They are the fundamentals. A student who learns from the beginning to write precise problem statements, to model systems with explicit assumptions, and to verify behavior through contracts will write better code earlier and understand why it is better — not just that it passes the tests.

And it is for developers navigating the new reality of AI-assisted programming. When code becomes easy to generate, the ability to evaluate what has been generated becomes more important, not less. A developer who cannot identify when a suggested algorithm is wrong for the problem, who cannot recognise a fragile abstraction before it is built upon, who cannot read generated code against a precise specification — that developer is not made more capable by AI tools. They are made faster at producing problems they cannot diagnose. This book is for the developer who wants to remain the engineer, not become the reviewer of an AI's guesses.



## A Note on the Language

The code examples in this book use **Nex**, a language designed to make good engineering habits the path of least resistance. Contracts, invariants, and explicit behavioral guarantees are not features you add to Nex programs — they are the natural way Nex programs are written. A precondition is not a comment that might drift out of date. It is an executable part of the program, checked at the call boundary, reported with its label when violated.

This is a deliberate choice. Learning to think about preconditions, postconditions, and invariants is easier when the language makes those concepts visible and enforceable rather than optional and advisory. Nex draws on Eiffel's Design by Contract philosophy and generates production-quality output for the JVM and for JavaScript, so the discipline it encourages is not confined to exercises — it carries forward into real systems.

Nex is the medium. The engineering principles are the message. Both transfer directly to any serious language or environment you work in after this book.

You do not need a complex toolchain to begin. A local Nex REPL is enough for all the examples that follow.



## Note on the Code Examples

The code examples in this book are provided for illustrative purposes, designed to clarify the engineering principles and patterns being discussed. They are not intended to be "production-ready" out of the box.

When working on the challenges provided in the Studio chapters, you are encouraged to implement complete, robust versions of the code yourself. This process of implementation is where much of the real learning happens. To support this work, you should make use of the additional sources provided in the References section of this book. Furthermore, for a deeper dive into the language itself, you should refer to the companion book, *Programming in Nex*, which is available from [schemer.in](https://schemer.in).


## Before the Prologue

The chapter that follows introduces three systems and three failures. Those failures are not cautionary tales included for dramatic effect. They are the opening of an argument that runs through the entire book: that the most consequential engineering decisions are made before a line of code is written, that the hardest problems in software are problems of clarity rather than cleverness, and that the skills required to get those decisions right are learnable, transferable, and more important now than they have ever been.

The prologue begins with a robot that will not move.

That is where the engineering starts.
