## 3. Writing a Problem Statement

Once we have observed the real world and identified the **actual problem**, we face a new challenge:

How do we describe that problem **clearly enough that a computer — and a team of programmers — can solve it?**

This is the role of the **problem statement**.

A good problem statement is not a vague description of what we want.
It is a **precise description of the task the system must accomplish**.

It sits between messy reality and precise algorithms.

Too vague, and no one knows what to build.
Too rigid, and the system becomes impossible to evolve.

Writing a good problem statement is therefore one of the most important skills in programming.

---

## Precision Without Overengineering

Beginners often make one of two mistakes.

### Mistake 1: Being Too Vague

A vague problem statement might look like this:

> “Build a robot that can deliver packages inside the building.”

This sounds reasonable, but it leaves critical questions unanswered:

* How does the robot know where it is?
* What happens if a path is blocked?
* How are delivery locations represented?
* How fast must the robot respond?

Without answers, every programmer imagines something different.

The result is confusion and incompatible code.

---

### Mistake 2: Being Overly Detailed

At the opposite extreme, some specifications attempt to describe **every tiny detail**:

* precise internal data structures
* exact algorithms
* specific implementation choices

For example:

> “The robot must represent the building using an adjacency matrix and compute shortest paths using Dijkstra’s algorithm.”

This sounds precise, but it introduces another problem.

It **locks the solution too early**.

Perhaps later we discover that:

* the building is very large
* the map changes frequently
* real-time updates are needed

Now the chosen data structure may be the wrong one.

A good problem statement should describe **what must be achieved**, not **how it must be implemented**.

---

### The Goal: Precise but Flexible

A strong problem statement answers questions like:

* What information enters the system?
* What output must the system produce?
* What constraints must be respected?

But it avoids committing to specific algorithms or internal structures.

Think of it as describing **the shape of the problem**, not the solution.

---

## Example: Delivery Robot Navigation

A clearer problem statement might look like this:

> The system receives a map of the building consisting of locations connected by traversable paths.
> Given a starting location and a destination, the robot must determine a route that allows it to reach the destination while avoiding blocked paths.

Notice what this statement does well:

* It defines **inputs** (map, start, destination).
* It defines **expected behavior** (find a route).
* It allows multiple algorithms to solve the problem.

This clarity makes algorithm design possible.

---

## Good Specifications vs Bad Specifications

Let us look at a few examples.

### Bad Specification

> “The knowledge engine should provide relevant answers to user questions.”

The phrase **“relevant answers”** is ambiguous.

Different programmers might interpret this differently.

---

### Better Specification

> Given a user query, the system retrieves documents that contain information related to the query and ranks them according to their estimated relevance.

This version clarifies:

* the input (query)
* the output (ranked documents)
* the goal (estimated relevance)

We still have many design choices left — which is exactly what we want.

---

### Another Bad Specification

> “Objects in the virtual world should interact realistically.”

What does “realistically” mean?

Physics simulation? Game-like interactions? Simplified rules?

---

### Better Specification

> Objects in the world have positions and interaction rules.
> When two objects occupy overlapping space, the system must apply the interaction rule associated with their types.

Now we can start thinking about:

* spatial data structures
* collision detection
* interaction systems

The problem has become **algorithmically meaningful**.

---

## The Power of Examples

Even the best written specification can leave gaps.

That is why engineers rely heavily on **examples**.

Examples help clarify expectations and reveal hidden assumptions.

For instance, in the delivery robot system:

**Example**

```
Start: Office A
Destination: Office D

Map:
A → B → C → D
```

Expected result:

```
Route: A → B → C → D
```

Simple examples establish the basic behavior.

But examples become even more powerful when they show **edge cases**.

---

## Counterexamples and Edge Cases

Counterexamples reveal situations where naïve solutions fail.

Consider a blocked path.

**Example**

```
A → B → C → D
      X
```

If B → C is blocked, the robot must find an alternative route.

If no alternative exists, the system must report failure.

Counterexamples force us to ask important questions:

* What happens when a destination is unreachable?
* What if the map contains loops?
* What if multiple routes exist?

These questions often expose missing parts of the specification.

---

## Example-Driven Thinking

Many experienced engineers design systems by collecting examples first.

They ask:

* What are the **normal cases**?
* What are the **edge cases**?
* What are the **failure cases**?

By the time the specification is written, the system’s behavior has already been explored through examples.

This approach has another advantage in the AI era.

Examples are often the **best way to communicate intent to AI coding assistants**.

Clear examples help both humans and machines understand what the system must do.

---

## Connection to Nex

Nex is a good fit for this stage because it encourages explicit problem expression.

As we move from statements to implementation, Nex lets us keep intent visible through:

- clear type annotations when we need precision
- optional flexibility when we are still exploring
- contracts and invariants when behavior must be guaranteed

The key habit is the same in any language: state the problem clearly before encoding a solution.

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (5 Minutes)

Choose one of the three systems (delivery robot, knowledge engine, virtual world) and write a mini problem statement with exactly three parts:

1. Inputs: what information enters the system?
2. Required behavior: what must the system do?
3. Constraints: what conditions must always be respected?

Then add:

- one normal example
- one edge case
- one failure case

If your statement prescribes a specific algorithm, rewrite it to focus on outcomes instead.

---

## A Problem Statement Is a Contract

At its core, a problem statement is a **contract**.

It establishes an agreement between:

* the **problem** we want solved
* the **algorithms** we will design
* the **code** we will eventually write

If the contract is vague, the system becomes unpredictable.

If the contract is precise, the entire development process becomes easier:

* algorithms are easier to design
* tests are easier to write
* bugs are easier to detect

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- A strong problem statement is precise about outcomes, not rigid about implementation.
- Vague statements create misalignment; over-detailed statements lock in bad decisions.
- Examples, edge cases, and counterexamples expose missing requirements early.
- Problem statements function as contracts between intent, design, and code.
- Clear statements make algorithm selection and testing substantially easier.

---

In Chapter 4, we stress-test problem statements with edge cases and failure conditions. If Chapter 3 defines the contract, Chapter 4 tries to break it.
