# Edge Cases — Where Systems Break

In the previous chapter, we wrote a contract: a precise statement of what a system must do. In this chapter, we try to break it.

This is not destructiveness for its own sake. Breaking a specification is the fastest way to find out whether it is complete. Every gap that an edge case exposes is a requirement that would otherwise be discovered in production — which is to say, discovered expensively.


## The Structure of a Failure

Most programs fail not because their central logic is wrong, but because they encounter situations nobody imagined. The algorithm works. The reasoning is sound. But somewhere, silently, the code assumed something it was never entitled to assume.

These unexamined assumptions have a name: **edge cases**.

Consider the delivery robot. Its pathfinding works correctly for any well-formed map with a reachable destination. Now ask: what is a "well-formed map"? What does "reachable" mean if the map changes while the robot is moving? The moment we push on the specification, we discover that it was never as complete as it appeared.

This pattern repeats across every system:

- The knowledge engine ranks documents correctly — unless the query is empty, in which case it divides by zero.
- The virtual world applies interaction rules correctly — unless two rules conflict, in which case it applies both, or neither, or crashes.

The failure is not in the algorithm. It is in the **boundary of the algorithm's applicability** — the unstated preconditions that the algorithm relied upon but nobody wrote down.


## Boundaries

Edge cases cluster around boundaries. A boundary is any point where the nature of the input changes qualitatively — where "more of the same" becomes "something different."

Some boundaries are numerical: the empty collection, the single-element collection, the collection large enough to exhaust memory. Some are structural: the graph with no edges, the graph with a cycle, the graph where source and destination are the same node. Some are temporal: the event that arrives before the system is ready, the two events that arrive simultaneously.

Return to the delivery robot. Typical operation involves a map with several rooms and a clear path between them. But consider the boundary cases:

| Situation | What the algorithm must decide |
|---|---|
| Start equals destination | Return the trivial route, or report an error? |
| Destination is unreachable | Report failure — but what failure? |
| Multiple shortest paths exist | Return one, return all, or declare a tie? |
| Map contains a cycle | Does the algorithm terminate? |
| Map is empty | Fail immediately, or search and fail gracefully? |

None of these are exotic. Every one of them will occur in a real building with a real robot. The question is whether they are handled intentionally or accidentally.

A famous class of boundary errors is the **off-by-one error**. A loop written as

```
for i from 0 to n
```

may process element `n`, which lies one step beyond the end of a zero-indexed array of length `n`. The loop is almost correct. It works for every index except the last one. That word — *almost* — is doing a great deal of damage.

Almost correct is a category of wrong.


## Thinking Like a Tester

The most reliable way to find edge cases is to change perspective. Stop thinking like someone building the system. Start thinking like someone trying to make it fail.

A tester does not ask: *does this work for the normal case?* A tester asks:

- What happens if the input is empty?
- What happens if the input is larger than expected?
- What happens if the input is malformed?
- What happens if two things occur at the same time?
- What happens if something fails halfway through?

Applied to our three systems, this mindset produces specific, answerable questions.

**Delivery robot.** What if a path becomes blocked after the robot has begun moving? What if the robot receives a map update mid-route? What if two robots are routed through the same corridor simultaneously?

**Knowledge engine.** What if the query contains a term that appears in no document? What if two highly-ranked documents directly contradict each other? What if the same query, submitted twice in rapid succession, produces different rankings?

**Virtual world.** What if an interaction rule specifies a result that violates a physical invariant? What if an object interacts with itself? What if a thousand objects converge on the same point in a single step?

These questions may seem adversarial. They are. That is the point. The adversary is reality, and reality will ask every one of these questions eventually. Better to ask them now, in a design document, than later, in a crash report.


## Edge Cases Refine the Specification

There is a deeper reason to take edge cases seriously. They do not merely reveal holes in a design — they reveal that the original problem statement was about a simpler problem than the one we actually need to solve.

Return to the specification from Chapter 3:

> *Given a starting location and a destination, the robot must find a route that reaches the destination while avoiding any blocked paths.*

This is clear and precise — for the case where such a route exists. But edge analysis forces three new questions:

1. What must the system do when no route exists?
2. What must the system do when multiple equally valid routes exist?
3. What must the system do when the map changes between queries?

Each question extends the specification. After answering them, we have a richer, more realistic contract — one that a programmer can actually implement fully, and a tester can actually verify completely.

This is the double value of edge analysis: it improves the design *and* makes the design testable.


## The Three-Category Inventory

When examining any feature or system, edge cases can be organized into three categories. Working through all three systematically is more reliable than relying on intuition alone.

**Input edges.** What are the degenerate inputs — empty, null, malformed, out of range? What is the smallest valid input? The largest? What inputs sit exactly at declared boundaries?

**Size edges.** What happens at scale? An algorithm that works for ten items may behave differently for ten million. Data structures have capacity limits. Networks have latency. Time constraints that hold for small inputs may not hold for large ones.

**State edges.** What if the system is in an unexpected state when a request arrives? What if two operations occur simultaneously? What if a previous operation left the system in a partially modified state?

For the delivery robot: the empty map is an input edge; a building with ten thousand rooms is a size edge; a map update that arrives while a route is being computed is a state edge. Each category exposes a different class of assumption.


## Edge Cases as Design Instruments

It is tempting to treat edge cases as nuisances — special cases that complicate otherwise clean algorithms. This is the wrong way to see them.

Edge cases are **design instruments**. Carefully analyzed, they often point toward better abstractions.

Consider what happens when we ask: *what should the robot do if the start and destination are the same location?* One answer is to return the trivial route `[A]`. Another is to return the empty route `[]`. These are different answers with different implications for every piece of code that processes the result.

Choosing between them forces a decision about the meaning of a route. That decision, once made, clarifies the algorithm, simplifies the code that calls it, and makes the system's behavior predictable in cases that the original specification left undefined.

The edge case did not complicate the design. It completed it.


::: {.note-exercise}
**Quick Exercise**

Take the problem statement you wrote at the end of Chapter 3 and apply the three-category inventory to it.

For each category — input edges, size edges, state edges — identify at least one case your current specification does not address. Then answer:

1. What should the system do in each unaddressed case?
2. Does your answer require revising the specification, or adding to it?
3. Which cases would you test first, and why?

If your specification handles every case you can identify without modification, push harder. In our experience, a specification that survives its first edge analysis without revision is one whose edges were not examined closely enough.
:::

::: {.note-takeaways}
**Takeaways**

- Programs fail at the boundaries of their assumptions, not at the center of their logic.
- Edge cases cluster around boundaries: degenerate inputs, extreme sizes, conflicting states.
- Thinking like a tester — adversarially, systematically — is a learnable and transferable skill.
- Edge analysis refines the specification: every gap it exposes is a requirement that was always there, waiting to be written down.
- Almost correct is a category of wrong.
:::



*Chapter 5 takes the next step: turning problem statements, examples, and edge analyses into formal specifications — requirements precise enough that two programmers, working independently, build the same system.*
