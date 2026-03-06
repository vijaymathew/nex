# Part VII — Making Software Trustworthy — Preconditions and Postconditions

## 27. Preconditions and Postconditions

By Part VI, we can build modular software.

Part VII focuses on trust: making behavior explicit, checkable, and dependable.

Preconditions and postconditions are the first layer of that trust model.

---

## Why Contracts Matter

Every routine has assumptions and promises.

A precondition (`require`) states what must be true before execution.
A postcondition (`ensure`) states what must be true after execution.

Without contracts, assumptions are implicit and failures appear far from the source.

---

## Practical Contract Rules

Use preconditions for caller obligations:

- input presence
- valid ranges
- required state before transition

Use postconditions for routine guarantees:

- result shape/value constraints
- state transition outcomes
- explicit failure semantics

A useful rule:

- never use postconditions to check caller mistakes
- never use preconditions to state routine results

---

## Worked Design Path

Requirement:

> "Assign task to robot and return assignment status."

### Step 1: Define caller obligations

- task id present
- robot id present
- task is assignable

### Step 2: Define routine guarantees

- task has assigned robot on success
- status is updated to `IN_TRANSIT`

### Step 3: Define failure behavior

- violation should fail fast with contract signal

### Step 4: Encode in code

- put input/state rules in `require`
- put assignment guarantees in `ensure`

---

## Nex Implementation Sketch

```nex
class Delivery_Task
feature
  task_id: String
  status: String
  assigned_robot_id: String

  assign(robot_id: String): String
    require
      task_id_present: task_id /= ""
      robot_id_present: robot_id /= ""
      can_assign: status = "PENDING" or status = "FAILED"
    do
      assigned_robot_id := robot_id
      status := "IN_TRANSIT"
      result := "ASSIGNED"
    ensure
      assigned: assigned_robot_id = robot_id
      now_in_transit: status = "IN_TRANSIT"
      known_result: result = "ASSIGNED"
    end
invariant
  valid_status:
    status = "PENDING" or
    status = "IN_TRANSIT" or
    status = "DELIVERED" or
    status = "FAILED"
end
```

The routine is now self-documenting: obligations and guarantees are executable.

---

## Contracts Across The Three Systems

### Delivery

- route inputs valid; output path/status guaranteed

### Knowledge

- query non-empty; ranked result format guaranteed

### Virtual World

- step inputs valid; world bounds preserved after update

Contracts turn informal expectations into runtime-checked behavior.

---

## Common Mistakes

### Mistake 1: Missing preconditions for state transitions

Symptom:

- illegal transitions occur silently

Recovery:

- encode transition legality in `require`

### Mistake 2: Weak postconditions

Symptom:

- success path does not guarantee meaningful result

Recovery:

- ensure key outputs and state changes explicitly

### Mistake 3: Contract duplication drift

Symptom:

- similar routines have inconsistent assumptions

Recovery:

- centralize patterns and review contract consistency

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (10 Minutes)

Choose one routine and write:

1. three preconditions
2. three postconditions
3. one invalid call case
4. one valid call case

Then verify your postconditions still hold after a refactor.

---

## Connection to Nex

Nex supports contract-first design directly with `require` and `ensure`, making intent explicit in executable form.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Preconditions define caller obligations.
- Postconditions define routine guarantees.
- Contracts improve diagnostics and reduce hidden assumptions.
- Trustworthy code starts with explicit behavior boundaries.

---

In Chapter 28, we move from routine-level guarantees to class-level guarantees: invariants.
