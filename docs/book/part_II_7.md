# Part II — Modeling the World — Entities: The Things That Exist

## 7. Entities: The Things That Exist

In Chapter 6, we argued that software needs models.

Now we build the first modeling primitive: **entities**.

An entity is something the system must track as a distinct thing over time.

That definition sounds simple. It is one of the most important distinctions in software design.

When teams get entities wrong, everything downstream becomes unstable:

- interfaces leak implementation details
- algorithms operate on the wrong units
- tests pass locally and fail system-wide

The core question is:

**What are the actual things in this system that deserve identity?**

---

## Identity vs State

A useful mental model:

- **Identity** answers: “Which thing is this?”
- **State** answers: “What is true about it now?”

Example: delivery task

- identity: `task_id`
- state: destination, priority, status

If state changes, identity stays.

This is critical. If your model treats state changes as new entities, you lose continuity. If your model merges distinct identities by accident, you corrupt behavior.

The same pattern appears in all three systems.

### Delivery

- Entity: `Robot`, `Location`, `DeliveryTask`
- Identity survives reroutes and retries.

### Knowledge

- Entity: `Document`, `Tag`, `QuerySession`
- A document can be edited, but remains the same document.

### Virtual World

- Entity: `WorldObject`, `Player`, `InteractionRule`
- Position changes every tick; object identity should not.

---

## Responsibilities Belong To Entities

Once entities are named, assign responsibilities around them.

Bad pattern:

- global helper functions mutate shared maps with weak ownership

Better pattern:

- each entity controls invariants related to its state
- transitions happen through explicit operations

Entity-centered responsibilities improve:

- correctness
- readability
- refactoring safety

Example:

- `DeliveryTask` should validate status transitions.
- `Document` should guard metadata integrity.
- `WorldObject` should enforce legal bounds for movement/state.

---

## Worked Design Path

Ambiguous requirement:

> “Track package progress and show live status.”

### Step 1: Extract Candidate Entities

Candidates:

- package
- robot
- route
- user notification

### Step 2: Keep Only Stable Identity Carriers

`Route` is often a computation result, not always a persistent entity.

Likely entities:

- `DeliveryTask`
- `Robot`
- `Location`

### Step 3: Define Identity Keys

- `DeliveryTask.task_id`
- `Robot.robot_id`
- `Location.location_id`

### Step 4: Define Minimal State

For `DeliveryTask`:

- origin
- destination
- status (`PENDING`, `IN_TRANSIT`, `DELIVERED`, `FAILED`)
- assigned_robot_id (optional)

### Step 5: Add Invariants

- task id non-empty
- origin and destination non-empty
- delivered task cannot go back to pending

### Step 6: Add Transition Operations

- assign robot
- mark in transit
- mark delivered
- mark failed

Now we have entity structure, not just feature text.

---

## Nex Implementation Sketch

```nex
class Delivery_Task
feature
  task_id: String
  origin: String
  destination: String
  status: String

  start() do
    if status = "PENDING" then
      status := "IN_TRANSIT"
    end
  ensure
    started_or_unchanged: status = "IN_TRANSIT" or status = "PENDING"
  end

  complete() do
    if status = "IN_TRANSIT" then
      status := "DELIVERED"
    end
  ensure
    delivered_or_unchanged: status = "DELIVERED" or status = "IN_TRANSIT"
  end
invariant
  id_present: task_id /= ""
  endpoints_present: origin /= "" and destination /= ""
  valid_status:
    status = "PENDING" or
    status = "IN_TRANSIT" or
    status = "DELIVERED" or
    status = "FAILED"
end
```

This sketch demonstrates:

- explicit identity (`task_id`)
- mutable state (`status`)
- behavior tied to entity transitions
- invariants protecting impossible states

The same approach scales to `Document` and `WorldObject` entities.

---

## Common Mistakes

### Mistake 1: Everything Is An Entity

Symptom:

- model bloats quickly
- every helper value gets persistence and lifecycle burden

Recovery:

- reserve entities for identity-bearing things
- treat derived values as views/results

### Mistake 2: No Identity, Only Fields

Symptom:

- updates overwrite wrong records
- hard to reason about history

Recovery:

- add explicit stable identifiers
- define equality/uniqueness rules

### Mistake 3: Mixed Responsibilities

Symptom:

- validation scattered in services/controllers/helpers

Recovery:

- move entity-specific rules near entity operations
- centralize transition checks

### Mistake 4: Illegal State Transitions

Symptom:

- “delivered” tasks become “pending” due to patch logic

Recovery:

- model transitions explicitly
- enforce with contracts and tests

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (8 Minutes)

For one system, produce an entity sheet with:

1. Entity name
2. Identity field(s)
3. Core state fields
4. Allowed transitions
5. Two invariants

Then answer:

- Which fields are identity vs state?
- Which transitions are currently unchecked in your code?

---

## Connection to Nex

Nex helps here because entities can carry both data and behavior, while contracts/invariants keep assumptions explicit.

You can begin with lightweight models, then strengthen guarantees as complexity grows.

That mirrors real project evolution.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Entities are identity-bearing things tracked over time.
- Identity and state must be separated clearly.
- Responsibilities should align to entities, not global utility sprawl.
- Transitions and invariants are part of the entity model, not afterthoughts.
- Strong entity modeling reduces downstream architectural friction.

---

In Chapter 8, we move from “things” to “connections.”

Entities alone are not enough; behavior emerges from **relationships**.
