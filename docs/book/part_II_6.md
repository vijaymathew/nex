# Part II — Modeling the World — Why Software Needs Models

## 6. Why Software Needs Models

By the end of Part I, we learned to write better problem statements.

That was necessary.

Now we hit the next wall.

Even with a clear specification, teams still fail when they move too quickly from requirements to code. The code may compile, tests may pass, and demos may look good. Then scale, ambiguity, and change arrive, and the system becomes fragile.

What is missing?

A **model**.

A model is not the same as code. It is the structured representation of what exists in the system, how those things relate, and what rules govern change.

Without a model, implementation becomes guesswork.
With a model, implementation becomes engineering.

---

## Why Code Alone Is Not Enough

New engineers often assume this sequence is enough:

1. Understand the feature request.
2. Start implementing functions and classes.
3. Refactor when problems appear.

In small tasks, this works.

In real systems, it creates hidden inconsistencies:

- two teams represent the same concept differently
- edge behavior depends on call order instead of rules
- performance collapses because access patterns were never modeled
- every new feature adds local patches and global confusion

A model solves this by forcing explicit structure before implementation details harden.

Think of it this way:

- specification answers: “What must happen?”
- model answers: “What exists, and how can it change?”
- algorithms answer: “How do we compute efficiently within that model?”

If the middle layer is weak, the rest of the system drifts.

---

## Models Across Our Three Systems

The delivery network, knowledge engine, and virtual world still look different. Modeling reveals shared structure.

### Delivery Network

If we model only “route from A to B,” we miss reality.

A stronger model includes:

- locations
- traversable connections
- dynamic path state (open/blocked)
- robot position and task

Now route choice is a query over model state, not a hard-coded behavior.

### Knowledge Engine

If we model only “query string -> results,” quality degrades quickly.

A stronger model includes:

- documents
- terms/tags
- relevance signals
- uncertainty/confidence

Now ranking can evolve because the model captures what ranking depends on.

### Virtual World

If we model only “update objects every frame,” interactions become chaotic.

A stronger model includes:

- entity identity
- entity state
- interaction rules by type
- update boundaries per tick

Now correctness can be reasoned about per transition, not only by runtime observation.

The domains differ, but the modeling move is the same: name stable structure first.

---

## Worked Design Path

Let us walk from ambiguous requirement to model.

Ambiguous requirement:

> “The robot should choose good routes and avoid getting stuck.”

That sentence is useful for product discussion and nearly useless for implementation.

### Step 1: Separate Nouns and Verbs

Nouns suggest modeled entities. Verbs suggest operations.

Nouns:

- robot
- route
- location
- path
- blockage

Verbs:

- choose
- avoid
- move
- re-plan

### Step 2: Define State, Not Just Behavior

A minimal state model:

- `Location`: identifier
- `Path`: `from`, `to`, `status`
- `Robot`: `current_location`, `destination`
- `Map`: set of locations and paths

### Step 3: Add Invariants Early

Before algorithms, define truths that must always hold.

Examples:

- every path endpoint must reference a known location
- robot current location must exist in map
- returned route (if present) must use only open paths

These invariants become your reliability backbone.

### Step 4: Make Change Explicit

Most failures happen during change, not static state.

Transitions:

- path becomes blocked
- robot receives new destination
- route recomputation succeeds or fails

A model that ignores transitions will pass demos and fail production.

### Step 5: Delay Algorithm Commitment

At this stage, do not lock to BFS, Dijkstra, A*, or custom heuristics.

Pick algorithm after model and access patterns are clear.

This preserves flexibility and prevents early overfitting.

---

## Nex Implementation Sketch

Nex is a useful teaching language here because it lets us express contracts close to behavior.

```nex
class Path
feature
  from_loc: String
  to_loc: String
  open: Boolean
invariant
  endpoints_present: from_loc /= "" and to_loc /= ""
end

class Robot_State
feature
  current: String
  destination: String

  request_replan(has_open_route: Boolean): String
    require
      current_known: current /= ""
      destination_known: destination /= ""
    do
      if current = destination then
        result := "ARRIVED"
      elseif has_open_route then
        result := "ROUTE_FOUND"
      else
        result := "UNREACHABLE"
      end
    ensure
      status_defined: result = "ARRIVED" or result = "ROUTE_FOUND" or result = "UNREACHABLE"
    end
invariant
  state_valid: current /= "" and destination /= ""
end
```

This is intentionally small. It does three important things:

- defines state explicitly
- expresses behavior in terms of state
- encodes guarantees as contracts/invariants

The algorithm can be inserted later without changing the conceptual model.

The same pattern applies to the knowledge engine and world simulation: model first, optimize second.

---

## Common Modeling Mistakes

### Mistake 1: Modeling Operations Before Entities

Symptom:

- lots of utility functions
- unclear ownership of data

Recovery:

- list stable entities first
- attach operations to explicit state

### Mistake 2: Ignoring Boundaries

Symptom:

- unclear where one subsystem ends and another begins
- duplicated representations across modules

Recovery:

- define model boundaries in writing
- make conversion points explicit

### Mistake 3: Missing Invariants

Symptom:

- bugs appear as “impossible states”
- fixes add conditional checks everywhere

Recovery:

- write invariants at class/operation level
- fail fast when invariants are violated

### Mistake 4: Premature Algorithm Lock-In

Symptom:

- architecture constrained by early performance guess
- hard to adapt when constraints change

Recovery:

- keep interface and model stable
- treat algorithm as replaceable strategy

### Mistake 5: Treating Models As Documentation Only

Symptom:

- diagrams exist, code disagrees

Recovery:

- encode model assumptions in types, contracts, and tests
- keep model and implementation synchronized

---

## Quick Exercise (8 Minutes)

Pick one of the three systems and produce a mini model draft with four parts:

1. Entities (3-6 items)
2. Relationships (how entities connect)
3. Invariants (2-3 rules that must always hold)
4. Transitions (2-3 important state changes)

Then answer:

- Which part of your current implementation is not represented in this model?
- Which part of the model is not currently enforced in code?

That gap is where reliability work should start.

---

## Reflection Checkpoint

Before moving on, you should be able to explain:

- Why your chosen entities are stable over time.
- Which assumptions are encoded as invariants.
- Which design decisions are still provisional.
- What evidence shows your model handles nominal and edge behavior.

If you cannot answer these clearly, do not add more features yet.

Strengthen the model first.

---

## Connection to Nex

Nex was chosen for this book because it supports this modeling discipline directly:

- object-style structures for entities and responsibilities
- functional-style decomposition for pure transformations
- contracts and invariants for correctness guarantees
- optional typing flexibility during exploration

That makes Nex a practical bridge between concept and implementation.

The transferable lesson is broader: in any language, explicit models reduce accidental complexity.

---

## Chapter Takeaways

- Models are the missing layer between specification and implementation.
- Stable entities, boundaries, invariants, and transitions are core modeling elements.
- Algorithm choice should follow model clarity, not precede it.
- Most scaling failures are model failures before they are code failures.
- Encoding model assumptions in code prevents drift.

---

In Chapter 7, we go deeper into the first modeling primitive: **entities**.

We will separate identity from state, assign responsibilities, and choose representations that remain stable as the system grows.
