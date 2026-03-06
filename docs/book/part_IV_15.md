# Part IV — Organizing Data — Lists and Sequences

## 15. Lists and Sequences

Part III gave us algorithmic thinking.

Part IV focuses on the data structures those algorithms run on.

We begin with lists and sequences because they are the default structure most teams reach for first.

That default is sometimes right and often costly.

---

## What Lists Are Good At

A list is an ordered collection.

That order gives you clear strengths:

- preserve insertion or logical order
- iterate predictably from start to end
- represent timelines, queues, logs, and ranked output

Lists work well when operations are mostly sequential and the primary question is:

- "What comes next?"

---

## Where Lists Hurt

Lists become expensive when you repeatedly need:

- fast membership checks
- random lookups by key
- frequent insert/delete in the middle

If the dominant operation is "find by id" and your structure is a list, performance will degrade as data grows.

This is the first scaling failure many systems hit.

---

## Worked Design Path

Requirement:

> "Show active delivery tasks in creation order and fetch one task by id quickly."

### Step 1: Separate operations

- operation A: ordered display
- operation B: id-based lookup

### Step 2: Start with naive list-only design

- `tasks: [task]`
- display is easy
- lookup is linear scan

### Step 3: Identify cost risk

At small scale, linear scan is fine.
At larger scale, repeated lookup dominates latency.

### Step 4: Keep list for order, plan index for lookup

This chapter keeps list-first implementation to expose the tradeoff clearly.
Chapter 16 introduces sets/maps to solve lookup cost.

### Step 5: Add contracts for behavior

- `append` guarantees order is preserved
- `find` guarantees either valid task or explicit missing result

---

## Nex Implementation Sketch

```nex
class Task
feature
  id: String
  status: String
invariant
  id_present: id /= ""
  valid_status:
    status = "PENDING" or
    status = "IN_TRANSIT" or
    status = "DELIVERED" or
    status = "FAILED"
end

class Task_Sequence
feature
  t1: Task
  t2: Task
  t3: Task

  find_by_id(task_id: String): String
    require
      id_present: task_id /= ""
    do
      if t1.id = task_id then
        result := t1.status
      elseif t2.id = task_id then
        result := t2.status
      elseif t3.id = task_id then
        result := t3.status
      else
        result := "NOT_FOUND"
      end
    ensure
      declared_result:
        result = "PENDING" or
        result = "IN_TRANSIT" or
        result = "DELIVERED" or
        result = "FAILED" or
        result = "NOT_FOUND"
    end

  ordered_ids(): String
    do
      result := t1.id + " -> " + t2.id + " -> " + t3.id
    ensure
      non_empty: result /= ""
    end
end
```

This sketch is intentionally small and list-like. It makes sequence behavior visible while exposing lookup cost.

---

## Lists in the Three Running Systems

### Delivery

- delivery tasks by creation or dispatch order

### Knowledge

- ranked search results in score order

### Virtual World

- deterministic update order for entities per tick

In each case, lists capture order well but may not satisfy fast keyed access.

---

## Common Mistakes

### Mistake 1: Using lists for everything

Symptom:

- repeated linear scans dominate runtime

Recovery:

- profile dominant operations
- add indexes or alternate structures where needed

### Mistake 2: Confusing order with identity lookup

Symptom:

- "find by id" implemented as repeated sequence traversal

Recovery:

- keep list for order
- introduce map/set for identity operations

### Mistake 3: Silent duplicate identity

Symptom:

- same logical entity appears multiple times without policy

Recovery:

- define uniqueness rules
- enforce during append/ingest

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (10 Minutes)

For one feature in your project:

1. list the top three operations on its collection
2. mark each as order-sensitive or key-sensitive
3. estimate operation frequency
4. decide whether list-only is sufficient

Then write one contract that would prevent the most likely misuse.

---

## Connection to Nex

Nex contracts help define what sequence operations guarantee, so behavior remains clear when the underlying structure changes later.

This allows incremental evolution from list-only designs to hybrid designs without losing correctness.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Lists are strong for order-preserving workflows.
- Lists are weak for repeated key-based lookup at scale.
- Distinguish sequence operations from identity operations early.
- Contracts make later structure refactors safer.

---

In Chapter 16, we introduce sets and maps to solve membership and lookup efficiently.
