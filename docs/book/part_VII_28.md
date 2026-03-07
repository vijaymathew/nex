# Chapter 28: Invariants — Rules That Must Never Break

Chapter 27 introduced contracts at the level of individual operations: preconditions that state what must be true before a routine is called, and postconditions that state what will be true when it returns. These are powerful tools for reasoning about individual calls. They do not, by themselves, answer a broader question: what is always true about an object, regardless of which sequence of operations has been applied to it, and regardless of what state it was in before any particular call?

This is the question invariants answer. An invariant is a property that holds for every observable state of an object — after construction, before every operation call, and after every operation return. It is not a claim about one call or one transition; it is a claim about the object's entire lifetime. Where a postcondition says "after this operation, the status is `IN_TRANSIT`," an invariant says "at all times, the status is one of four declared values." The postcondition is a consequence of a specific operation; the invariant is a constraint on the entire state space.

---

## What an Invariant Is

The simplest way to understand the force of an invariant is to consider what it rules out. A `Delivery_Task` with status `"PENDING"` and a non-empty `assigned_robot_id` is a state that the model never intends to permit: a pending task is one that has not yet been assigned. A `WorldObject` at position `x = -5` when the world begins at zero is a state that the physics never intends to permit. A `Document` with an empty identifier in a collection keyed by identifiers is a state that the index never intends to permit. Each of these is an invalid state, and each will eventually cause a failure — not at the point where the invalid state was created, but later, when downstream code encounters it and discovers that its own assumptions have been violated.

An invariant is what prevents invalid states from being created. It is a constraint that every operation on the object must preserve. An operation that establishes an invalid state — that leaves the object in a configuration the invariant forbids — violates the invariant, and the violation can be detected immediately rather than at the distant point where the invalid state produces a symptom.

The relationship between invariants and contracts is additive: invariants operate at the class level, contracts operate at the operation level, and together they provide complementary coverage. A contract without an invariant can ensure that each operation leaves the object in a correct state given the state it was in on entry, but cannot prevent the object from being constructed in an invalid state. An invariant without contracts can ensure the object's state is always valid but cannot specify the relationship between inputs and outputs for any particular operation. Both are necessary.

---

## Three Categories of Invariant

Invariants fall into three categories, corresponding to different levels of the object's structure.

**Field-level invariants** constrain individual fields to valid domains. A task identifier must be non-empty. A position coordinate must be non-negative. A priority score must be within a defined range. These are the simplest invariants — they constrain one field in isolation — and they are also the most common source of errors when omitted. A system that permits empty identifiers will eventually call an operation that assumes identifiers are present, and the failure will be attributed to the operation rather than to the missing constraint on construction.

**Relationship invariants** constrain the relationship between two or more fields. A task in `IN_TRANSIT` status must have a non-empty `assigned_robot_id` — the transition to `IN_TRANSIT` is only meaningful when there is a robot to perform it, and the system should never be in a state where a task is claimed to be in transit without an assigned robot. A world object at `max_x` must have the appropriate boundary condition applied. These invariants are more powerful than field-level ones because they capture the dependencies between fields that individual field constraints cannot express.

**Range and bound invariants** constrain fields to stay within operational limits. A position must remain between zero and the world's maximum extent. A score must remain non-negative. A collection must not exceed its defined capacity. These invariants are particularly important for systems where values are computed dynamically — where positions are updated by velocity, scores are computed by summing term weights, priorities are modified by rules — because the computation may produce values outside the valid range without the error being visible at the computation site.

---

## Invariants and Transitions

A common confusion in invariant design is between invariants and transition preconditions, and the confusion produces both invariants that are too weak and invariants that are too strong.

An invariant that is too weak fails to prevent invalid states. Consider a `DeliveryTask` whose invariant only constrains the status to one of four values but does not constrain the relationship between status and `assigned_robot_id`. The invariant permits a task in `IN_TRANSIT` with an empty `assigned_robot_id` — an invalid state that no operation should produce but that the invariant does not forbid. Strengthening the invariant to require `assigned_robot_id /= ""` whenever `status = "IN_TRANSIT"` closes this gap.

An invariant that is too strict forbids states that are legitimately intermediate during a transition. If the invariant required `assigned_robot_id /= ""` at all times, no object could be constructed before an assignment has been made. The invariant must permit the initial `PENDING` state without an assigned robot while forbidding the `IN_TRANSIT` state without one. The relationship invariant — `status = "IN_TRANSIT" implies assigned_robot_id /= ""` — achieves this precision.

The principle is: invariants should constrain the outcomes of transitions, not the inputs to them. Inputs to transitions belong in preconditions. The states that are permanently forbidden belong in invariants.

---

## From Requirement to Invariant Design

Consider the requirement:

> *"World objects move each tick but must always stay within legal bounds."*

**Step 1: Identify the always-true rules.** Every world object must have a non-empty identifier — the identity constraint. Every world object must have a non-negative `max_x` — a malformed bound is worse than no bound. Every world object's position must satisfy `x >= 0 and x <= max_x` — the spatial constraint that the requirement directly states. These are invariants: they hold before every tick and must hold after every tick.

**Step 2: Keep transition behavior flexible.** The `step` operation updates position by adding velocity. The invariant must constrain the resulting position without forbidding the operation. The correct design is to clamp the position within bounds in the operation body and to assert the spatial invariant in both the postcondition and the class invariant. The invariant does not say "velocity must be zero" — that would block movement. It says "after movement, position is within bounds" — which the operation must guarantee by clamping.

**Step 3: Confirm that every operation preserves the invariant.** For `World_Object`, the only operation that modifies position is `step`. The postcondition on `step` asserts `x >= 0 and x <= max_x`. Since this assertion matches the invariant's spatial constraint, any execution of `step` that satisfies its postcondition will leave the invariant intact. The two assertions reinforce each other: the postcondition ensures the specific operation preserves the invariant, and the invariant ensures no other operation — including direct field assignment — can violate the constraint.

---

## An Invariant in Code

```nex
class World_Object
feature
  object_id: String
  x: Integer
  vx: Integer
  max_x: Integer
  step()
    require
      max_valid: max_x >= 0
    do
      let next: Integer := x + vx
      if next < 0 then
        x := 0
      elseif next > max_x then
        x := max_x
      else
        x := next
      end
    ensure
      bounded_after_step: x >= 0 and x <= max_x
    end
invariant
  id_present: object_id /= ""
  max_non_negative: max_x >= 0
  x_bounded: x >= 0 and x <= max_x
end
```

The three invariants cover all three categories. `id_present` is a field-level invariant — an object without an identifier is a malformed object. `max_non_negative` is a range invariant — a negative bound is not a legal bound. `x_bounded` is the relationship invariant between position and bound — the spatial constraint that the requirement states must always hold.

The interaction between the invariant and the postcondition on `step` is the central design relationship in this sketch. The invariant asserts `x_bounded` as a permanent truth. The postcondition on `step` asserts `bounded_after_step`, which is the same condition. This is intentional: the postcondition is the per-call evidence that the invariant is preserved across the `step` operation. If a future modification to `step` removed the clamping logic and the postcondition, the invariant would immediately catch any execution that left `x` out of bounds.

The precondition on `step` — `max_valid: max_x >= 0` — might look redundant given the class invariant `max_non_negative`. It is not redundant for the same reason the `task_id_present` precondition in Chapter 27 was not redundant: the invariant guarantees the condition for any correctly constructed object; the precondition is a defensive assertion that makes the assumption visible in the operation's own contract. As the class evolves and `step` is read in isolation, the precondition communicates the operation's requirements without requiring the reader to consult the class invariant.

---

## Invariants in the Three Systems

In the delivery system, the `DeliveryTask` invariant asserts that the status is always one of four declared values and that any task in `IN_TRANSIT` has a non-empty `assigned_robot_id`. The first invariant is a field-level constraint; the second is a relationship constraint. Together they ensure that no operation can leave the task in an ambiguous or unrepresentable state.

In the knowledge engine, the `Document` invariant asserts that the document identifier is non-empty and unique within its scope. The uniqueness invariant is a relationship constraint — it relates one document instance to the collection that contains it — and it must be enforced at both construction and update. A document whose identifier changes to match another document's identifier has violated the invariant, and the violation must be caught at the point of the change.

In the virtual world, every `WorldObject` carries a spatial invariant: position coordinates are within bounds. Every `InteractionRule` carries a validity invariant: the object types it references must exist in the type registry. These invariants ensure that collision detection and interaction application can trust the data they receive, rather than defensively validating every piece of state they encounter.

In all three systems, invariants are not defensive checks added to make buggy code safer. They are assertions about the system's design: states that the model never intends to permit, properties that every correct operation must preserve, constraints that exist because the system's reasoning depends on them.

---

## Three Ways Invariant Design Fails

**Invariants too weak.** An invariant that constrains individual fields but not their relationships permits states that the model never intends but cannot prevent. A `DeliveryTask` invariant that validates the status values but not the `assigned_robot_id` constraint permits in-transit tasks with no assigned robot — an invalid state that no invariant will catch and that subsequent operations will discover in the form of empty identifiers where non-empty ones were expected. The remedy is to strengthen the invariant to capture all of the relationships between fields that must hold for the object to be in a valid state.

**Invariants too strict.** An invariant that forbids states that are legitimate at certain points in an object's lifecycle will make normal operations impossible. If the spatial invariant required `vx = 0` — forbidding any non-zero velocity — the `step` operation could not exist. Invariants that are too strict are not corrected by weakening the invariant; they are corrected by identifying which conditions are permanent constraints (invariants) and which are conditions required only before or after specific operations (preconditions and postconditions). Permanent constraints belong in the invariant. Transition-specific conditions belong in operation contracts.

**Duplicate rule definitions that drift apart.** When the same constraint appears in both a class invariant and the postconditions of multiple operations, the two definitions may diverge over time. One operation's postcondition is updated to reflect a new understanding of the constraint; the invariant is not. Another operation is added with a postcondition that was written to match the updated understanding; the old operations are not updated. The invariant and the operations now describe different constraints, and the system enforces whichever one the checker happens to evaluate. The remedy is to treat the class invariant as the single authoritative statement of each constraint and to write postconditions that reference or derive from the invariant rather than restating it independently.

---

## Quick Exercise

Choose one class in your system and define its complete invariant with three parts: two field-level invariants that constrain individual fields to their valid domains, one relationship invariant that constrains the relationship between two or more fields, and one operation that could violate one of the invariants if written carelessly.

Then write one test that calls the potentially violating operation with inputs designed to produce a boundary case and verifies that the invariants hold afterward. If the test requires reading the implementation to write, the invariants are not yet strong enough to specify the boundary independently.

---

## Takeaways

- An invariant is a property that holds for every observable state of an object — not after one operation, but across the entire object's lifetime. It is a constraint on the state space, not a claim about a single transition.
- Invariants and contracts are complementary. Invariants protect against invalid states across all operations; contracts specify the input-output relationship of individual operations. Both are necessary.
- Three categories cover most invariants: field-level constraints on individual fields, relationship invariants on the dependencies between fields, and range or bound invariants on computed values.
- Invariants should constrain the outcomes of transitions, not their inputs. Transition inputs belong in preconditions; permanently forbidden states belong in invariants.
- Duplicate rule definitions drift apart. The class invariant is the authoritative statement of each constraint; operation postconditions should derive from it, not independently restate it.

---

*Chapter 29 examines how testing extends the guarantees established by contracts and invariants. Where contracts specify what must be true, tests provide evidence that the specifications have been met — and they do so by exploring behavior across the full range of inputs, not just the ones the developer thought of first.*
