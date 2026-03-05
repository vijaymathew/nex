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

# Prologue — The Day the System Broke

* Three small teams. Three ambitious systems.
* A delivery robot that can’t find its way
* A knowledge engine drowning in information
* A virtual world collapsing under its own complexity
* Why programming begins *long before code*

---

# Part I — Seeing the Problem Clearly

## 1. What Problem Are We Actually Solving?

* The difference between *symptoms* and *problems*
* The three systems we will build
* Why most software fails before the first line of code

## 2. Looking at the Real World

* Observing how systems behave
* Users, constraints, and hidden assumptions
* Turning messy reality into questions we can answer

## 3. Writing a Problem Statement

* Precision without overengineering
* Good and bad specifications
* Capturing examples and counterexamples

## 4. Edge Cases: Where Systems Break

* Strange inputs
* Boundary conditions
* Learning to think like a tester

## 5. From Stories to Specifications

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

## 6. Why Software Needs Models

* Reality is too messy
* Abstraction as a survival skill
* What every system has in common

## 7. Entities: The Things That Exist

* Robots, notes, players
* Identity and attributes
* Representing real objects in software

## 8. Relationships: How Things Connect

* Deliveries belong to routes
* Notes link to ideas
* Players interact with worlds

## 9. Designing a Good Data Model

* Simplicity vs flexibility
* Avoiding accidental complexity
* Modeling tradeoffs

## 10. Modeling Change

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

## 11. What Is an Algorithm?

* Recipes, procedures, and guarantees
* Algorithms as precise solutions

## 12. Breaking Problems Apart

* Decomposition
* Stepwise refinement
* Solving smaller problems first

## 13. Thinking Recursively

* Problems that refer to themselves
* Recursive thinking in everyday life
* Elegant solutions to complex problems

## 14. Measuring Algorithm Behavior

* Why some programs slow to a crawl
* Growth as inputs scale
* The intuition behind complexity

---

### Algorithm Lab — The First Experiments

We test different approaches and observe:

* some scale beautifully
* some collapse spectacularly

Only then do we introduce **Big-O notation**.

---

# Part IV — Organizing Data

## 15. Lists and Sequences

* Ordered collections
* Iteration and indexing
* Where lists work well (and where they don’t)

## 16. Sets and Maps

* Membership and lookup
* Hashing as a magical trick
* Fast retrieval

## 17. Trees: Structured Data

* Hierarchies everywhere
* Search trees and their invariants
* When trees beat lists

## 18. Graphs: Networks of Everything

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

## 19. Searching for What Matters

* Linear search
* Binary search
* Choosing the right strategy

## 20. Sorting the World

* Why order makes problems easier
* Simple sorting
* Divide-and-conquer methods

## 21. Exploring Trees and Graphs

* Depth-first exploration
* Breadth-first exploration
* Discovering hidden structure

## 22. Finding the Best Path

* Shortest routes in the delivery network
* Priority queues
* Greedy algorithms

---

### Algorithm Lab — When Algorithms Compete

We pit algorithms against each other and watch the results.

* surprising performance differences
* growth curves revealed

---

# Part VI — Building Real Software

## 23. From Algorithms to Components

* Encapsulation
* Clear boundaries
* Designing modules

## 24. Functional Thinking

* Pure functions
* Immutability
* Composition

## 25. Object-Oriented Thinking

* Modeling behavior
* Responsibilities and collaboration
* Objects in the virtual world

## 26. Designing Interfaces

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

## 27. Preconditions and Postconditions

* Contracts in software
* Explicit assumptions

## 28. Invariants: Rules That Must Never Break

* Structural guarantees
* Maintaining system consistency

## 29. Testing as Exploration

* Unit tests
* property-based testing
* discovering hidden bugs

## 30. Debugging Like an Engineer

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

## 31. Managing Complexity

* layers and architecture
* taming large systems

## 32. Designing for Change

* extensibility
* evolving requirements

## 33. Refactoring Without Fear

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

## 34. Working With AI Coding Assistants

* prompting effectively
* guiding AI toward good solutions

## 35. Reviewing AI-Generated Code

* detecting subtle mistakes
* enforcing contracts and invariants

## 36. Human Judgment in an AI World

* why understanding still matters
* AI as amplifier, not replacement

---

# Epilogue — The Systems Behind Everything

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
