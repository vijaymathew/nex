# 2. Looking at the Real World

A specification describes how a system is supposed to behave. An observation describes how it actually behaves. These are rarely the same thing, and the gap between them is where most software failures live.

The previous chapter established that problems have structure, and that misidentifying the structure leads to systematically wrong solutions. This chapter asks a prior question: how do we discover the actual structure of a problem before we have committed to a design? The answer is observation — disciplined, skeptical attention to the real environment the system must inhabit.


## Models and Their Limits

Every software system embeds a model of the world it operates in. The delivery robot embeds a model of the building. The knowledge engine embeds a model of what users want. The virtual world embeds a model of physical interaction. These models are not incidental — they are load-bearing. Every algorithm in the system depends on them.

A model is an abstraction: it retains some features of the world and discards others. A graph of hallways retains connectivity and discards the fact that hallways are occupied by people, blocked by deliveries, and occasionally closed for maintenance. This is not a flaw — every useful model discards something. The question is whether what was discarded matters.

It often does. Watch the building the robot must navigate for an hour. What the graph does not contain: people moving unpredictably through corridors, a door propped open on some days and locked on others, an elevator out of service without notice, a hallway narrowed by construction equipment. The algorithm operating on the graph is correct. The graph itself is an inadequate model of the environment. No improvement to the algorithm can fix this — the problem is upstream.

This is the first purpose of observation: to find what the model discards, and to ask whether that matters.


## Users Are Inside the System

A second source of modeling failure is the treatment of users as inputs rather than participants. A system that accepts queries treats the user as a source of well-formed requests. Real users are not this.

Consider a user who asks a knowledge engine: *"What causes memory leaks?"* Taken literally, this is a retrieval problem — find documents about memory leaks. But the question is underdetermined. The user might mean memory leaks in C, where the cause is manual allocation without corresponding deallocation; in Java, where the garbage collector cannot reclaim objects with live references; in machine learning pipelines, where tensors accumulate across training steps. The same string of words points at structurally different problems in different contexts.

Users also ask questions that do not name what they mean at all: *"Why does my program keep growing?"* This is not a malformed query. It is a reasonable description of an experience, offered by someone who does not yet know the technical vocabulary for the phenomenon they are observing.

A system designed for well-formed inputs will fail both of these users. The design failure is not in the retrieval mechanism — it is in the assumption that input arrives already interpreted. Real users bring incomplete information, wrong vocabulary, and implicit context. A model that excludes this is a model of a system that does not need to exist, because its users do not exist either.

::: {.note-exercise}
**Exercise 2.1.** A calendar application allows users to schedule meetings. Describe three ways a real user's behavior might violate the assumptions embedded in a naive design. For each, state the hidden assumption explicitly.
:::

## Constraints Are Not Obstacles

A common pattern in early design is to solve the unconstrained version of the problem first, then "add constraints later." This produces systems that are elegant in principle and unworkable in practice, because the constraints are not obstacles laid on top of the solution — they are part of the problem's structure.

The virtual world illustrates this clearly. In an unconstrained simulation, every object interacts with every other: forces propagate, collisions are detected exactly, state is updated at every timestep. The computational cost is quadratic in the number of objects. At a thousand objects, this is infeasible in real time. The constraint — that the simulation must run at interactive speeds — is not a performance requirement to be met after the design is complete. It is a structural feature of the problem that determines which solutions are admissible.

A designer who ignores this constraint will produce an architecture that cannot be repaired by optimization. The data structures, the update strategy, the partitioning of the world — all of these must be chosen with the constraint in mind from the beginning. A spatial index that makes nearby-object queries fast, a fixed timestep that allows approximate but bounded computation, a distinction between objects that matter to the current frame and objects that do not — these design choices follow from the constraint. They are not refinements to a design; they are consequences of understanding the problem.

The question is not *"What is the ideal solution, and how close can we get?"* It is *"What does a solution look like when the constraints are treated as given?"*


## Hidden Assumptions

The most dangerous element of any model is what it assumes without stating. A hidden assumption is a condition the system requires in order to function correctly, which is neither documented nor tested, and which therefore fails silently when violated.

The delivery robot assumes its map is current. The knowledge engine assumes that documents in its collection are authoritative. The virtual world assumes that no two objects occupy the same position at the start of a frame. Each of these assumptions is reasonable under normal conditions. Each produces a failure mode that is difficult to diagnose, because the failure does not point to the assumption — it points to the code that depended on it.

When the robot reaches a hallway that no longer exists, it does not report "map out of date." It reports, if it reports anything coherent at all, that it cannot find a valid path. The assumption has been violated, but the violation is invisible to the system, which has no representation of map currency and therefore no way to detect its absence.

Observation surfaces hidden assumptions because observation confronts the model with the world. Watching the robot navigate exposes the assumption about the map. Watching users query the knowledge engine exposes the assumption about vocabulary. Running the simulation with two objects at the same position exposes the assumption about initial state. The assumptions do not reveal themselves in code review or specification analysis; they reveal themselves when the system meets conditions it was not built to handle.

::: {.note-exercise}
**Exercise 2.2.** Identify two hidden assumptions in the following specification: *"The payment system processes transactions in the order they are received."* For each assumption, describe the failure mode that results when it is violated.
:::

## From Observation to Question

Observation produces raw material — a collection of behaviors, failures, and anomalies. Before this can inform a design, it must be transformed into questions precise enough to analyze.

This transformation is not automatic. The observations produced by watching a real system are messy and particular: the robot got stuck in corridor B3 on Tuesday afternoon; a user searching for "memory leak" clicked none of the top five results; two objects in the simulation passed through each other during a high-velocity collision. These are facts, but they are not yet questions. A question names a structural condition, not an instance of it.

| Observation | Question |
|---|---|
| The robot got stuck in corridor B3. | What information does the robot need to detect that a path has become impassable? |
| Users ignore the top search results. | How should the system represent the difference between topical relevance and user intent? |
| Objects pass through each other at high velocity. | What invariant must the collision detection system maintain regardless of object velocity? |

The question is not a restatement of the observation. It identifies the structural condition the observation is evidence of, and names what a solution must address. A team that asks *"How do we fix the B3 incident?"* will produce a patch. A team that asks *"What information does the robot need to detect impassable paths?"* will produce a design.

A well-formed engineering question has three properties. First, it is general enough to cover the class of cases the observation belongs to, not just the instance observed. Second, it is specific enough to admit a definite answer — or at least to make clear what evidence would constitute progress toward one. Third, it is stated in terms of the system's behavior and structure, not in terms of the symptom that motivated it.

::: {.note-exercise}
**Exercise 2.3.** The following are observations from a real system. For each, write a well-formed engineering question that names the structural condition the observation is evidence of.

1. *"The app crashes when two users edit the same document simultaneously."*
2. *"The recommendation engine stops suggesting new items after a user has been active for several months."*
3. *"The search index returns stale results for several minutes after content is updated."*
:::

## What Observation Cannot Do

Observation is not sufficient on its own. A system can be observed indefinitely without producing a design, because observation produces evidence but not explanations. The gap between them requires a model — a proposed account of the structure that produces the observed behavior.

This is why observation and modeling must proceed together. An observation without a model is an anomaly without a cause. A model without observation is a hypothesis without evidence. The engineering process alternates between them: observe behavior, form a hypothesis about structure, derive predictions, test the predictions against further observation, revise the model.

The three systems in this book will each undergo this process. In each case, careful observation will reveal that the naive model is inadequate, and the revised model will suggest a design that the naive model could not have produced.

The next chapter formalizes what a model must contain: a precise statement of the problem, the constraints that bound it, and the assumptions it makes explicit. That formalization is what converts the output of observation into something an algorithm can be built against.
