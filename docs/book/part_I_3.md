# Writing a Problem Statement

Once we have observed the real world and identified the actual problem, a new challenge emerges.

How do we describe that problem clearly enough that a computer — and a team of programmers — can actually solve it?

This is the job of a **problem statement**.

A good problem statement is not a wish list or a vague description of desired outcomes. It is a precise account of what a system must accomplish: the inputs it receives, the outputs it must produce, and the constraints it must respect. It sits between messy reality and precise algorithms, translating one into the other.

Get it wrong in either direction and the project suffers. Too vague, and every programmer builds something different. Too rigid, and the system becomes impossible to evolve. Writing a strong problem statement is therefore one of the most important skills in programming — and one of the least taught.


## Two Ways to Get It Wrong

### Being Too Vague

A vague problem statement might look like this:

> *"Build a robot that can deliver packages inside the building."*

This sounds reasonable. It is not. It leaves critical questions completely open:

- How does the robot know where it is?
- What happens when a path is blocked?
- How are delivery locations represented?
- What response time is acceptable?

Without answers, every programmer imagines something different. The result is confusion, incompatible code, and a system that satisfies no one.

### Being Overly Rigid

At the other extreme, some specifications attempt to nail down every detail:

> *"The robot must represent the building as an adjacency matrix and compute shortest paths using Dijkstra's algorithm."*

This feels precise. But it introduces a different kind of problem: it locks the solution in too early.

What if the building turns out to be very large? What if the map changes frequently, or real-time updates are required? The data structure chosen upfront may be entirely wrong for the actual task.

A good problem statement describes **what must be achieved**, not how it must be implemented. Implementation is the programmer's job; the problem statement is not the place for it.


## What a Strong Problem Statement Does

A well-written problem statement answers three questions clearly:

1. **What information enters the system?**
2. **What output must the system produce?**
3. **What constraints must always be respected?**

It avoids prescribing specific algorithms or data structures. Think of it as describing the shape of the problem, not the shape of the solution.

Here is a clearer version of the delivery robot problem:

> *The system receives a map of the building consisting of locations connected by traversable paths. Given a starting location and a destination, the robot must find a route that reaches the destination while avoiding any blocked paths.*

Notice what this does well. It defines the inputs (map, starting location, destination), the expected behavior (find a valid route), and leaves all implementation decisions open. Multiple algorithms could solve this problem correctly. That flexibility is intentional.


## Specifications in Practice

Seeing the contrast between weak and strong specifications makes the principle concrete.

**Weak:** *"The knowledge engine should provide relevant answers to user questions."*

The phrase "relevant answers" is ambiguous. Two programmers reading this independently will produce two incompatible systems.

**Stronger:** *Given a user query, the system retrieves documents containing information related to the query and ranks them by estimated relevance.*

Now we know the input (a query), the output (ranked documents), and the goal (relevance ranking). Plenty of design decisions remain open — which is exactly what we want.


**Weak:** *"Objects in the virtual world should interact realistically."*

What does "realistically" mean here? Physics simulation? Game-style rules? Simplified abstractions?

**Stronger:** *Objects in the world have positions and interaction rules. When two objects occupy overlapping space, the system applies the interaction rule associated with their types.*

Now the problem is algorithmically meaningful. We can start thinking about spatial data structures, collision detection, and interaction systems. The work can begin.


## The Power of Examples

Even a well-written specification can leave gaps. That is why engineers rely heavily on examples.

Examples do something that abstract description cannot: they make expectations concrete and surface hidden assumptions before they become bugs.

For the delivery robot system, a simple example looks like this:

```
Start:       Office A
Destination: Office D

Map: A → B → C → D

Expected route: A → B → C → D
```

This confirms the basic behavior. But examples become truly powerful when they explore what happens at the edges.


## Edge Cases and Counterexamples

A counterexample is an example designed to expose the limits of a naïve solution. Consider what happens when a path is blocked:

```
Map: A → B → C → D
             X
     (B → C is blocked)
```

Now the robot cannot follow the obvious route. Two questions immediately arise: can it find an alternative path? And if no alternative exists, what should it do?

These are not afterthoughts. They are requirements — and without counterexamples, they are easy to forget.

Every good specification should be stress-tested with three kinds of examples:

- **Normal cases** — the everyday behavior the system is built for
- **Edge cases** — unusual but valid inputs that might trip up a simple implementation
- **Failure cases** — situations where the system cannot succeed and must respond gracefully

By the time the specification is written, the system's behavior should already have been explored through examples. The writing merely captures what the examples revealed.


## A Note on AI-Assisted Development

Examples matter even more in the current era of AI coding assistants. A vague description gives an AI model almost nothing to work with. A precise specification with concrete examples — including edge cases — gives it a clear target.

The habits that make specifications useful to human teammates make them equally useful to machine ones.


## The Problem Statement as Contract

At its core, a problem statement is a contract. It creates an agreement between:

- the **problem** we want solved
- the **algorithms** we will design to solve it
- the **code** we will eventually write

Vague contracts produce unpredictable systems. Precise ones make every downstream step easier: algorithms are easier to choose, tests are easier to write, bugs are easier to find and fix.

When something goes wrong — and something always does — a well-written problem statement tells you whether the implementation violated the contract or the contract itself was wrong. Either way, you know where to look.


::: {.note-exercise}
**Quick Exercise**

Choose one of these three systems: the delivery robot, the knowledge engine, or the virtual world. Write a problem statement with exactly three parts:

1. **Inputs** — what information enters the system?
2. **Required behavior** — what must the system do with it?
3. **Constraints** — what conditions must always hold?

Then add one normal example, one edge case, and one failure case.

If your statement specifies an algorithm or data structure, rewrite it to focus on outcomes instead.
:::

::: {.note-takeaways}
**Takeaways**

- A strong problem statement is precise about outcomes and silent about implementation.
- Vague statements create misalignment; over-specified ones lock in decisions too early.
- Examples, edge cases, and counterexamples surface missing requirements before they become costly mistakes.
- A problem statement is a contract between intent, design, and code.
- Clear contracts make algorithm selection, testing, and debugging substantially easier.
:::



*In Chapter 4, we stress-test problem statements systematically — probing edge cases, failure conditions, and contradictions. If Chapter 3 defines the contract, Chapter 4 tries to break it.*
