# Chapter 7: Entities — The Things That Exist

Chapter 6 established that a model is the necessary layer between specification and implementation. Now we build the first element of that model.

An entity is something the system must track as a distinct thing over time.

That definition sounds simple. It is one of the most consequential distinctions in software design. When a team misidentifies its entities — treating derived values as persistent things, or collapsing distinct identities into one — everything downstream suffers. Interfaces leak implementation details. Algorithms operate on the wrong units. Tests pass in isolation and fail across the system.

The question that entity modeling forces us to answer is: **what are the actual things in this system that deserve an identity of their own?**

---

## Identity and State

Before naming entities, we need a sharper vocabulary. Two concepts do most of the work.

**Identity** answers: *which thing is this?* It is what remains stable across the entire lifetime of an entity — the thread of continuity that lets the system say "this is the same robot, even though it has moved."

**State** answers: *what is true about this thing right now?* It is everything that can change while identity stays fixed — position, status, assignment.

The separation is not cosmetic. If your model treats a state change as the creation of a new entity, you lose continuity: there is no way to say that the robot before the reroute and the robot after it are the same robot. If your model merges entities that are genuinely distinct, you corrupt behavior: two delivery tasks that happen to share a destination are not the same task.

Applied to our three systems, the separation looks like this:

**Delivery network.** The entities are `Robot`, `Location`, and `DeliveryTask`. A robot's identity persists across reroutes and retries; its current location is state. A delivery task's identity persists across status changes; whether it is pending or in transit is state.

**Knowledge engine.** The entities are `Document`, `Tag`, and `QuerySession`. A document can be edited, annotated, or re-indexed, but it remains the same document. Its content is state; its identity is not.

**Virtual world.** The entities are `WorldObject`, `Player`, and `InteractionRule`. An object's position changes every tick. Its identity does not.

---

## Responsibilities Belong to Entities

Once entities are named, the next question is what they are responsible for.

The temptation — especially in codebases that grew without an explicit model — is to collect entity-related logic in global helper functions or service layers that reach into shared data structures and mutate them directly. This pattern works at small scale and degrades badly at large scale: ownership becomes unclear, invariants are enforced inconsistently, and changing one entity's representation requires finding and updating code in many places.

The better pattern is to assign each entity responsibility for the invariants that govern its own state, and to expose state changes only through explicit operations. An entity that controls its own transitions is an entity whose behavior can be reasoned about locally.

In concrete terms: `DeliveryTask` should validate its own status transitions — it should not be possible for external code to move a delivered task back to pending. `Document` should guard the integrity of its own metadata. `WorldObject` should enforce whatever bounds constrain legal movement or state change.

This is not about strict encapsulation as a stylistic preference. It is about making invariants enforceable rather than merely conventional.

---

## From Requirement to Entity Model

The path from an ambiguous requirement to a set of well-defined entities has a shape. Consider this requirement:

> *"Track package progress and show live status."*

It mentions things — packages, status, presumably robots and locations — but gives them no structure. The following steps convert it into a model.

**Step 1: Extract candidate entities.** Read the requirement and list the nouns that might deserve persistent identity: package, robot, route, user notification. Do not commit yet.

**Step 2: Keep only stable identity carriers.** A route is often the output of a computation — a derived value, not a persistent thing. User notifications may be events rather than entities. After filtering: `DeliveryTask`, `Robot`, `Location` are likely stable identity carriers.

**Step 3: Define identity keys.** Assign each entity a field that uniquely identifies it across time: `DeliveryTask.task_id`, `Robot.robot_id`, `Location.location_id`. Choosing these fields early forces clarity about what makes two instances the same thing.

**Step 4: Define minimal state.** For `DeliveryTask`: origin, destination, status (`PENDING`, `IN_TRANSIT`, `DELIVERED`, `FAILED`), and optionally an assigned robot identifier. Start small. State can be extended; a bloated initial model is harder to correct.

**Step 5: Add invariants.** The task identifier must be non-empty. Origin and destination must be non-empty. A delivered task cannot return to pending. These are not validation rules to be added later — they are properties of the entity itself, and they belong in the model now.

**Step 6: Add transition operations.** Assign robot, mark in transit, mark delivered, mark failed. Each transition is an explicit operation, not a field assignment scattered across the codebase.

At the end of this process, we have entity structure rather than feature prose. Implementation can begin with a clear target.

---

## An Entity in Code

The following sketch shows how the `DeliveryTask` entity might be expressed in Nex:

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

Read this sketch against the six steps above. `task_id` is the identity key from Step 3. The four status values are the minimal state from Step 4. The three invariants correspond directly to Step 5. `start` and `complete` are the transition operations from Step 6, each with a postcondition that rules out the illegal outcomes.

The pathfinding logic, the ranking algorithm, the notification system — none of that appears here, because none of it belongs to this entity. The entity model and the algorithm are separate concerns. Keeping them separate is what makes both easier to change.

---

## Four Ways Entity Modeling Goes Wrong

**Treating everything as an entity.** If every value in the system gets an identity and a lifecycle, the model bloats and the overhead of managing persistence overwhelms the work the model is supposed to simplify. The recovery is to reserve entity status for things that genuinely need to be tracked across time, and to treat derived or computed values as results — outputs of queries over entity state, not entities themselves.

**No identity, only fields.** A model that represents entities as plain records without stable identifiers makes it impossible to reason about continuity. Updates overwrite the wrong records; history cannot be reconstructed. The recovery is to define explicit, stable identity fields and to specify what equality means for each entity.

**Mixed responsibilities.** When transition logic and validation are scattered across services, controllers, and helper functions, the entity's invariants are enforced only by convention — which means they are not reliably enforced at all. The recovery is to move entity-specific rules close to entity operations and to centralize transition checks.

**Unchecked state transitions.** A delivered task that can become pending again through a misapplied patch is a symptom of transitions that exist only as informal agreements between developers. The recovery is to model transitions explicitly and to enforce them with contracts and tests — not because developers cannot be trusted, but because explicit enforcement scales and informal agreement does not.

---

## Quick Exercise

Choose one of the three running systems and produce an entity sheet with five parts:

1. **Entity name**
2. **Identity field or fields**
3. **Core state fields**
4. **Allowed transitions** — which status or state changes are legal, and from which starting states?
5. **Two invariants** — what must always be true?

Then ask: which fields in your current implementation are being used as identity but were never explicitly designated as such? And which transitions are currently unchecked — possible to execute in any order, with no enforcement of preconditions?

Those two gaps are where entity modeling work should begin.

---

## Takeaways

- Entities are identity-bearing things that the system must track over time. Not every value is an entity.
- Identity and state are distinct: identity answers *which thing is this?*, state answers *what is true about it now?*
- Responsibilities should be assigned to entities, not diffused across global helpers and service layers.
- Transitions and invariants are part of the entity model itself, not implementation details to be added later.
- Strong entity models reduce friction at every downstream stage: algorithm design, testing, and refactoring.

---

*Chapter 8 moves from things to connections. Entities alone are not enough — behavior emerges from the relationships between them.*
