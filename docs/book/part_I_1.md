# Part I — Seeing the Problem Clearly

## 1. What Problem Are We Actually Solving?

A team designing a delivery robot for a large office building faces what appears to be a well-defined problem: compute shortest paths through the building and execute them. The path-finding algorithms are well-understood; the implementation is straightforward. After several months the robot navigates reliably — and delivers packages to the wrong destinations.

The algorithm was correct. The problem was wrong.

Buildings change. Hallways become blocked, elevators fail, doors close. The engineers had modeled the building as a fixed graph and optimized movement through it. But the actual challenge was not path-finding in a known environment — it was decision-making in an uncertain one. These are structurally different problems, requiring different solutions. No improvement to the path-finding algorithm could have fixed the system, because the algorithm was not the source of the failure.

This distinction — between the problem as initially described and the problem as it actually exists — is one of the most consequential in software engineering.


## Symptoms and Problems

What the team described as their problem — "compute the shortest path from A to B" — was in fact a symptom: a visible manifestation of a deeper structural condition. The underlying problem was that the environment was dynamic and the system assumed it was not.

A symptom names the observable failure. A problem names the structural condition that makes the failure inevitable. Treating a symptom may eliminate the immediate manifestation while leaving the condition intact, producing either the same failure under different circumstances, or a different failure entirely.

The distinction appears throughout software:

| Symptom | Underlying problem |
|---|---|
| "The database is slow." | The data model does not match the query patterns. |
| "Search results are irrelevant." | The system does not model user intent. |
| "The robot misdelivers packages." | The environment is dynamic; the model is static. |
| "The system cannot scale." | The architecture assumes a fixed workload. |

In each case, the symptom suggests a local repair: tune a query, adjust a ranking, recalibrate a sensor, add a server. The problem demands a different kind of response: a change to the model, the architecture, or the representation. Conflating the two leads to systems that are locally optimized and globally broken.

::: {.note-exercise}
**Exercise 1.1.** Each description below names a symptom. Restate it as a structural problem, and identify one constraint that any valid solution must satisfy.
:::

1. "Our build times are too slow."
2. "Users keep entering invalid data."
3. "The recommendation engine suggests items users already own."


## Three Systems

Throughout this book we will return to three systems that appear unrelated but share a common difficulty: in each case, the problem as naively described obscures the problem as it actually exists.

**A delivery robot** must move through a large office building and deliver packages. The naive formulation — find shortest paths, execute them — collapses once the map changes, the elevator fails, or a hallway is blocked. The actual problem is not path optimization. It is planning under uncertainty: how does an agent act rationally when its model of the world is incomplete or out of date?

**A knowledge engine** answers questions using a large document collection. The naive formulation — index the documents, rank by relevance — breaks down immediately. Relevant to what? The system must model what the user is trying to accomplish, not merely what words they used. It must recognize when its sources disagree, and when its knowledge is insufficient to answer. The actual problem is not retrieval. It is reasoning about the reliability and scope of knowledge.

**A virtual world** contains thousands of objects interacting in real time — characters, physics, events, behaviors. The naive formulation frames this as a performance problem: make it fast enough. But the real difficulty is structural. How can large numbers of independently evolving components interact without producing chaos? The actual problem is not throughput. It is managing complexity through representation.

In each system, the naive formulation leads to a dead end. The engineers who succeed are those who see past the initial description to the structure underneath.


## The Shape of a Problem

Different problems have different structures, and the structure of a problem constrains the space of reasonable solutions.

Consider what distinguishes the three systems above. The delivery robot problem involves an agent acting over time in a changing environment — it belongs to a class of problems requiring state, perception, and recovery from failure. The knowledge engine problem involves reasoning under uncertainty — it belongs to a class where the system must represent not just what it knows, but the limits of what it knows. The virtual world problem involves the composition of many interacting components — it belongs to a class where the challenge is controlling emergent behavior.

These structural differences are not incidental. A solution suited to one class will not transfer to another. Applying retrieval techniques to the robot problem, or path-planning techniques to the knowledge problem, will not produce a working system. The mismatch between problem structure and solution structure is the primary source of software that works technically while failing practically.

Recognizing the structure of a problem is therefore not preliminary to engineering — it *is* the central act of engineering. The choice of algorithm, data structure, and architecture all follow from it. A team that misidentifies the structure will make systematically wrong choices throughout the project, and no amount of subsequent optimization will recover the loss.

::: {.note-exercise}
**Exercise 1.2.** For each of the three systems described above, identify one design decision that would be correct given the naive formulation but wrong given the actual problem structure.
:::

## Before the First Line of Code

Software projects fail for many reasons, but one cause appears with unusual regularity: the problem was never clearly defined. This failure rarely looks dramatic. It appears in small decisions made early, when the cost of changing direction seems low:

- requirements specify features rather than goals
- performance is optimized before correctness is established
- tools and frameworks are chosen before the problem is understood

Each of these is a form of the same error: beginning to construct a solution before the problem has been examined. The cost is not immediately visible, because early code still compiles, tests still pass, and progress still appears to be made. The failure emerges later, when the system meets the actual problem and the mismatch cannot be patched away.

The discipline this book develops is the habit of stopping before that first line of code and asking a harder set of questions:

- What is the system actually trying to accomplish?
- What assumptions are hidden in the problem description?
- What would constitute a failure, and what would cause it?
- What is the structure of this problem, and what class of solutions does that structure admit?

These questions slow the process at the beginning. They prevent a larger, costlier slowdown later.

Programming begins with code. Engineering begins with understanding.


## Looking Ahead

The next chapter introduces a concrete method for examining systems before designing them: how to observe real behavior, surface hidden assumptions, and state constraints precisely enough that they can be violated, tested, and refined. We will apply this method first to the delivery robot, where the gap between naive description and actual problem is clearest. By the end of Part I, the three systems will each have yielded a precise problem statement — and the algorithms we build in Part II will follow from those statements almost without choice.
