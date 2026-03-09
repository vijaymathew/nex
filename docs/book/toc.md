# Before You Code

## Understanding Problems, Designing Algorithms, and Engineering Software That Lasts

### A Story-Driven Guide to Programming in the AI Era

## Studio Chapters

* [Studio 1 — Our First Tiny System](studio_1.md)
* [Studio 2 — The Model Redesign](studio_2.md)
* [Studio 3 — The Scaling Crisis](studio_3.md)
* [Studio 4 — The Architecture Refactor](studio_4.md)
* [Studio 5 — Reliability](studio_5.md)
* [Studio 6 — Evolution](studio_6.md)

---

# [Forward](forward.md)

* Why this book exists now
* How to use it as a working guide

---

# [Prologue — The Day the System Broke](prologue.md)

* Three small teams. Three ambitious systems.
* A delivery robot that can’t find its way
* A knowledge engine drowning in information
* A virtual world collapsing under its own complexity
* Why programming begins *long before code*

---

# Part I — Seeing the Problem Clearly

## 1. [What Problem Are We Actually Solving?](part_I_1.md)

* The difference between *symptoms* and *problems*
* The three systems we will build
* Why most software fails before the first line of code

## 2. [Looking at the Real World](part_I_2.md)

* Observing how systems behave
* Users, constraints, and hidden assumptions
* Turning messy reality into questions we can answer

## 3. [Writing a Problem Statement](part_I_3.md)

* Precision without overengineering
* Good and bad specifications
* Capturing examples and counterexamples

## 4. [Edge Cases: Where Systems Break](part_I_4.md)

* Strange inputs
* Boundary conditions
* Learning to think like a tester

## 5. [From Stories to Specifications](part_I_5.md)

* Turning narratives into rules
* Inputs, outputs, and guarantees
* First glimpse of contracts

---

### SYSTEM MILESTONE 1 — Our First Tiny System

We build the simplest working version of all three systems.

* Studio outline: [Studio 1 — Our First Tiny System](studio_1.md)

* A tiny delivery scheduler
* A tiny note organizer
* A tiny virtual world

Nothing fancy. Just enough to work.

And already… problems appear.

---

# Part II — Modeling the World

## 6. [Why Software Needs Models](part_II_6.md)

* Reality is too messy
* Abstraction as a survival skill
* What every system has in common

## 7. [Entities: The Things That Exist](part_II_7.md)

* Robots, notes, players
* Identity and attributes
* Representing real objects in software

## 8. [Relationships: How Things Connect](part_II_8.md)

* Deliveries belong to routes
* Notes link to ideas
* Players interact with worlds

## 9. [Designing a Good Data Model](part_II_9.md)

* Simplicity vs flexibility
* Avoiding accidental complexity
* Modeling tradeoffs

## 10. [Modeling Change](part_II_10.md)

* State vs events
* Time in software systems
* Designing systems that evolve

---

### SYSTEM MILESTONE 2 — The Model Redesign

Our first data models fail.

* Studio outline: [Studio 2 — The Model Redesign](studio_2.md)

We redesign the systems so they can grow.

Lessons learned:

* bad models create bad software
* good models simplify algorithms

---

# Part III — The Shape of Algorithms

## 11. [What Is an Algorithm?](part_III_11.md)

* Recipes, procedures, and guarantees
* Algorithms as precise solutions

## 12. [Breaking Problems Apart](part_III_12.md)

* Decomposition
* Stepwise refinement
* Solving smaller problems first

## 13. [Thinking Recursively](part_III_13.md)

* Problems that refer to themselves
* Recursive thinking in everyday life
* Elegant solutions to complex problems

## 14. [Measuring Algorithm Behavior](part_III_14.md)

* Why some programs slow to a crawl
* Growth as inputs scale
* The intuition behind complexity

---

### [Algorithm Lab — The First Experiments](algorithm_lab_1.md)

We test different approaches and observe:

* some scale beautifully
* some collapse spectacularly

Only then do we introduce **Big-O notation**.

---

# Part IV — Organizing Data

## 15. [Lists and Sequences](part_IV_15.md)

* Ordered collections
* Iteration and indexing
* Where lists work well (and where they don’t)

## 16. [Sets and Maps](part_IV_16.md)

* Membership and lookup
* Hashing as a magical trick
* Fast retrieval

## 17. [Trees: Structured Data](part_IV_17.md)

* Hierarchies everywhere
* Search trees and their invariants
* When trees beat lists

## 18. [Graphs: Networks of Everything](part_IV_18.md)

* Cities, ideas, and worlds
* Modeling complex relationships
* Traversing networks

---

### SYSTEM MILESTONE 3 — The Scaling Crisis

Our systems now contain **thousands of objects**.

* Studio outline: [Studio 3 — The Scaling Crisis](studio_3.md)

Everything slows down.

We must redesign using better data structures.

---

# Part V — Algorithms That Power Systems

## 19. [Searching for What Matters](part_V_19.md)

* Linear search
* Binary search
* Choosing the right strategy

## 20. [Sorting the World](part_V_20.md)

* Why order makes problems easier
* Simple sorting
* Divide-and-conquer methods

## 21. [Exploring Trees and Graphs](part_V_21.md)

* Depth-first exploration
* Breadth-first exploration
* Discovering hidden structure

## 22. [Finding the Best Path](part_V_22.md)

* Shortest routes in the delivery network
* Priority queues
* Greedy algorithms

---

### [Algorithm Lab — When Algorithms Compete](algorithm_lab_2.md)

We pit algorithms against each other and watch the results.

* surprising performance differences
* growth curves revealed

---

# Part VI — Building Real Software

## 23. [From Algorithms to Components](part_VI_23.md)

* Encapsulation
* Clear boundaries
* Designing modules

## 24. [Functional Thinking](part_VI_24.md)

* Pure functions
* Immutability
* Composition

## 25. [Object-Oriented Thinking](part_VI_25.md)

* Modeling behavior
* Responsibilities and collaboration
* Objects in the virtual world

## 26. [Designing Interfaces](part_VI_26.md)

* Making systems understandable
* Contracts between modules
* Avoiding fragile designs

---

### SYSTEM MILESTONE 4 — The Architecture Refactor

Our systems work…

* Studio outline: [Studio 4 — The Architecture Refactor](studio_4.md)

…but the code is a mess.

We redesign:

* modules
* interfaces
* responsibilities

---

# Part VII — Making Software Trustworthy

## 27. [Preconditions and Postconditions](part_VII_27.md)

* Contracts in software
* Explicit assumptions

## 28. [Invariants: Rules That Must Never Break](part_VII_28.md)

* Structural guarantees
* Maintaining system consistency

## 29. [Testing as Exploration](part_VII_29.md)

* Unit tests
* property-based testing
* discovering hidden bugs

## 30. [Debugging Like an Engineer](part_VII_30.md)

* Symptoms vs causes
* systematic investigation

---

### SYSTEM MILESTONE 5 — Reliability

We harden our systems:

* Studio outline: [Studio 5 — Reliability](studio_5.md)

* invariant enforcement
* testing frameworks
* debugging tools

Now the software becomes **reliable infrastructure**.

---

# Part VIII — Systems That Grow

## 31. [Managing Complexity](part_VIII_31.md)

* layers and architecture
* taming large systems

## 32. [Designing for Change](part_VIII_32.md)

* extensibility
* evolving requirements

## 33. [Refactoring Without Fear](part_VIII_33.md)

* improving designs safely
* maintaining correctness

---

### SYSTEM MILESTONE 6 — Evolution

New requirements appear.

* Studio outline: [Studio 6 — Evolution](studio_6.md)

We extend the systems without breaking them.

The real test of good design.

---

# Part IX — Programming in the Age of AI

## 34. [Working With AI Coding Assistants](part_IX_34.md)

* prompting effectively
* guiding AI toward good solutions

## 35. [Reviewing AI-Generated Code](part_IX_35.md)

* detecting subtle mistakes
* enforcing contracts and invariants

## 36. [Human Judgment in an AI World](part_IX_36.md)

* why understanding still matters
* AI as amplifier, not replacement

---

# [Epilogue — The Systems Behind Everything](epilogue.md)

We step back and realize something remarkable.

The delivery network.
The knowledge engine.
The virtual world.

They are all variations of the **same underlying system**:

* entities
* relationships
* events
* algorithms

And now you know how to build them.

---

# Back Matter

## [Glossary](glossary.md)

Major terms used across the book:

* algorithms
* invariants
* contracts
* inheritance rules
* complexity

## [Index of Terms](index_terms.md)

A practical topic index that points to the chapters where each concept is introduced and applied.

## [References](references.md)

A placeholder bibliography chapter and citation workflow.
