# Part II — Modeling the World — Modeling Change

## 10. Modeling Change

Static models are necessary.

Real systems are dynamic.

Most severe bugs do not happen because stored state is wrong at rest. They happen during transitions:

- two updates race
- one step succeeds, next step fails
- event order changes outcomes
- partial writes leave inconsistent state

If your model does not include change semantics, correctness is accidental.

---

## State + Transition = Real Behavior

A robust model includes:

- state definitions
- legal transitions
- transition preconditions
- transition postconditions

Think in terms of state machines, even for simple systems.

Example delivery task transitions:

- `PENDING -> IN_TRANSIT`
- `IN_TRANSIT -> DELIVERED`
- `IN_TRANSIT -> FAILED`

Illegal transition example:

- `DELIVERED -> PENDING`

Explicit transition modeling prevents this class of bugs from becoming runtime surprises.

---

## Time And Ordering

Change introduces time.

Time introduces ordering problems.

### Knowledge Engine Example

If relevance scores update after indexing events arrive out-of-order, ranking may oscillate unexpectedly.

### Virtual World Example

If collision resolution happens before movement for some objects and after movement for others, simulation becomes nondeterministic.

### Delivery Example

If reassignment happens before failure status commits, two robots may both claim the same task.

Modeling change means defining deterministic order and conflict policy.

---

## Worked Design Path

Requirement:

> “When a robot fails mid-delivery, tasks should be reassigned automatically.”

### Step 1: Identify Transition Events

- robot heartbeat missed
- robot status set to failed
- active tasks discovered
- reassignment attempted

### Step 2: Define Transition Preconditions

For task reassignment:

- task status is `IN_TRANSIT` or `FAILED`
- old robot is failed/unavailable
- candidate replacement exists

### Step 3: Define Transition Postconditions

- task assigned to exactly one robot
- task status remains valid
- reassignment decision is auditable

### Step 4: Define Failure Behavior

If no replacement exists:

- mark task as `FAILED`
- surface actionable reason

Do not leave ambiguous half-state.

### Step 5: Add Idempotency Where Needed

If the same failure event is processed twice, outcome should remain consistent.

Idempotent transitions are a practical reliability multiplier.

---

## Nex Implementation Sketch

```nex
class Task_Transition
feature
  status: String
  assigned_robot_id: String

  reassign(new_robot_id: String)
    require
      robot_present: new_robot_id /= ""
      transition_allowed: status = "IN_TRANSIT" or status = "FAILED"
    do
      assigned_robot_id := new_robot_id
      status := "IN_TRANSIT"
    ensure
      reassigned: assigned_robot_id = new_robot_id and status = "IN_TRANSIT"
    end

  mark_delivered()
    require
      must_be_in_transit: status = "IN_TRANSIT"
    do
      status := "DELIVERED"
    ensure
      delivered: status = "DELIVERED"
    end
invariant
  valid_status:
    status = "PENDING" or status = "IN_TRANSIT" or status = "DELIVERED" or status = "FAILED"
end
```

This keeps change rules explicit and enforceable.

---

## Common Mistakes In Modeling Change

### Mistake 1: Transition Logic Scattered Everywhere

Symptom:

- no single source of truth for legal changes

Recovery:

- centralize transitions in model operations/services
- codify pre/post contracts

### Mistake 2: Silent Partial Failure

Symptom:

- operation updates one field, then exits on error

Recovery:

- define atomic boundaries where possible
- add compensation/fallback states where not possible

### Mistake 3: Ignoring Event Reprocessing

Symptom:

- duplicate events create duplicate effects

Recovery:

- design idempotent transition handling
- track event identity when applicable

### Mistake 4: Undefined Conflict Policy

Symptom:

- concurrent updates produce nondeterministic outcomes

Recovery:

- define winner rule (timestamp/version/priority)
- enforce ordering at model boundary

### Mistake 5: No Change Audit Trail

Symptom:

- impossible to explain how state became invalid

Recovery:

- log transition intent and result
- retain failure reasons for diagnosis

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (10 Minutes)

For one entity in your system, define:

1. All legal states
2. Allowed transitions
3. One illegal transition
4. Preconditions for one transition
5. Postconditions for one transition
6. Failure behavior when transition cannot complete

Then ask: can this transition be safely retried?

If not, what must be added for idempotency?

---

## Connection to Nex

Nex encourages explicit transition modeling with contracts and invariants close to operations.

That helps teams reason about change correctness before scaling complexity.

The broader lesson: model transitions as first-class design objects, not incidental code paths.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Most real failures occur during state transitions.
- Legal transitions must be explicit and enforced.
- Ordering, conflict policy, and idempotency are model concerns.
- Partial failure handling must be designed, not improvised.
- Reliable systems treat change as structured behavior.

---

Part II has now established a modeling foundation.

In Part III, we shift from representation to computation: what an algorithm is, how to decompose problems, and how to reason about algorithm behavior under load.
