# Part II — Modeling the World — Designing a Good Data Model

## 9. Designing a Good Data Model

By now we have two building blocks:

- entities
- relationships

A data model is where these pieces become a coherent system design.

A good model is not the most abstract one. It is the one that keeps the system correct, understandable, and evolvable under real usage.

---

## What Makes A Data Model Good?

A good model should do five things well.

1. **Represent reality adequately** for the problem scope.
2. **Support critical operations efficiently**.
3. **Protect invariants** against invalid states.
4. **Allow change** without destructive rewrites.
5. **Remain explainable** to new engineers.

If a model fails any of these, teams eventually compensate with brittle glue logic.

---

## Query-First Modeling

One practical rule:

Model from expected operations, not from nouns alone.

Ask:

- What are our top read paths?
- What are top write/update paths?
- What invariants must hold through both?

Example: knowledge engine

If the top operation is “fetch relevant notes by tag and recency,” model needs:

- tag index path
- recency field
- deterministic ranking inputs

Without this, code adds ad hoc caches and special cases later.

---

## Tradeoffs You Must Make Explicit

No non-trivial model is free of tradeoffs.

### Normalization vs Duplication

- normalized: cleaner consistency, heavier joins/traversals
- duplicated: faster reads, harder updates

### Generality vs Simplicity

- highly generic schemas support many future cases
- over-generality can slow current work and increase ambiguity

### Strictness vs Flexibility

- strict constraints prevent bad states
- some workflows need temporary incomplete data

A good model chooses intentionally and documents why.

---

## Worked Design Path

Requirement:

> “Show live delivery status and allow reassignment when robots fail.”

### Step 1: Identify Core Entities

- `Robot`
- `DeliveryTask`
- `Location`

### Step 2: Define Relationships

- `DeliveryTask` references origin/destination locations
- `DeliveryTask` optionally references assigned robot

### Step 3: Define Operational Queries

- find active task by robot
- find next pending task by priority
- reassign failed robot’s active tasks

### Step 4: Shape Model For Queries

Add fields/index intent:

- task status
- priority
- assigned_robot_id

### Step 5: Protect Invariants

- delivered task cannot be reassigned
- assigned robot must exist for `IN_TRANSIT`
- origin/destination must be valid locations

### Step 6: Plan For Evolution

Potential future changes:

- multi-robot cooperative delivery
- regional routing constraints
- SLA classes

Design extension points now, but keep current model minimal.

---

## Nex Implementation Sketch

```nex
class Delivery_Model
feature
  task_id: String
  assigned_robot_id: String
  status: String
  priority: Integer

  can_reassign(): Boolean do
    result := status = "PENDING" or status = "FAILED"
  ensure
    result_boolean: result = true or result = false
  end

  mark_in_transit(robot_id: String)
    require
      has_robot: robot_id /= ""
      legal_transition: status = "PENDING" or status = "FAILED"
    do
      assigned_robot_id := robot_id
      status := "IN_TRANSIT"
    ensure
      transit_set: status = "IN_TRANSIT" and assigned_robot_id = robot_id
    end
invariant
  task_id_present: task_id /= ""
  priority_non_negative: priority >= 0
  valid_status:
    status = "PENDING" or status = "IN_TRANSIT" or status = "DELIVERED" or status = "FAILED"
end
```

This sketch emphasizes model behavior consistency, not storage backend details.

---

## Common Modeling Failures

### Failure 1: Model Mirrors UI, Not Domain

Symptom:

- backend structure breaks when UI changes

Recovery:

- model domain semantics first
- treat UI as a projection/view

### Failure 2: Missing Transition Rules

Symptom:

- illegal status combinations appear in production

Recovery:

- encode transitions explicitly
- enforce via contracts and tests

### Failure 3: Performance Afterthought

Symptom:

- model is clean but operationally unusable at scale

Recovery:

- incorporate critical query paths early
- benchmark representative workloads

### Failure 4: No Versioning Strategy

Symptom:

- migrations break old assumptions

Recovery:

- make model changes additive where possible
- document compatibility assumptions

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (10 Minutes)

For one system, write a one-page data model brief:

1. Core entities
2. Core relationships
3. Top 3 reads
4. Top 3 writes
5. 3 invariants
6. One expected future extension

Then identify one tradeoff you are accepting now and why.

---

## Connection to Nex

Nex helps keep model decisions executable:

- fields define state clearly
- contracts define legal transitions
- invariants preserve correctness over time

This is the core teaching objective: align design decisions with enforceable behavior.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Good models are operational, not merely conceptual.
- Query paths should shape model design early.
- Tradeoffs must be explicit and documented.
- Transition rules are part of the data model.
- Model quality determines system changeability.

---

In Chapter 10, we focus on the most failure-prone area: **modeling change over time**.
