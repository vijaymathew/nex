# Part V — Algorithms That Power Systems — Searching for What Matters

## 19. Searching for What Matters

Part IV gave us data-structure options.

Part V focuses on algorithm families that use those structures effectively.

We start with searching because most systems spend significant time answering one question:

- "Where is the thing we need?"

---

## Search Is A Design Decision

Search strategy should match data organization and workload.

Common strategies:

- linear search for small or unsorted collections
- binary search for sorted sequences
- map/set lookup for keyed identity access
- graph/tree traversal for structural discovery

Choosing a strategy by habit instead of operation profile creates hidden latency costs.

---

## Correctness Before Speed

A search algorithm is useful only if it clearly defines:

- what counts as a match
- what happens when no match exists
- what assumptions input must satisfy

Without explicit miss semantics, search paths tend to leak null/empty ambiguity.

---

## Worked Design Path

Requirement:

> "Given a task id, return current status quickly and safely."

### Step 1: Define match semantics

- exact id equality

### Step 2: Define miss behavior

- return `NOT_FOUND`

### Step 3: Check data properties

- if unsorted sequence: linear search baseline
- if sorted key list: binary search option
- if direct index exists: keyed lookup preferred

### Step 4: Choose first implementation

Start with linear search if model is early and data is small.

### Step 5: Define upgrade trigger

When scans dominate hot path latency, move to indexed/keyed strategy.

---

## Nex Implementation Sketch

```nex
class Search_Result
feature
  status: String
  steps: Integer
invariant
  status_present: status /= ""
  steps_non_negative: steps >= 0
end

class Task_Search
feature
  id1: String
  st1: String
  id2: String
  st2: String
  id3: String
  st3: String
  id4: String
  st4: String

  linear_find(task_id: String): Search_Result
    require
      id_present: task_id /= ""
    do
      let r: Search_Result := create Search_Result
      r.status := "NOT_FOUND"
      r.steps := 0

      r.steps := r.steps + 1
      if task_id = id1 then
        r.status := st1
      elseif task_id = id2 then
        r.steps := r.steps + 1
        r.status := st2
      elseif task_id = id3 then
        r.steps := r.steps + 1
        r.status := st3
      elseif task_id = id4 then
        r.steps := r.steps + 1
        r.status := st4
      else
        r.steps := r.steps + 1
      end

      result := r
    ensure
      bounded_steps: result.steps >= 1 and result.steps <= 4
    end
end
```

This chapter example emphasizes explicit match/miss semantics and measurable behavior.

---

## Search Across The Three Systems

### Delivery

- locate task/robot/location by identity

### Knowledge

- locate candidate docs by token/id/tag

### Virtual World

- locate entity state by object id

In each system, search is often the first scaling bottleneck.

---

## Common Mistakes

### Mistake 1: One search strategy everywhere

Symptom:

- growing latency on high-frequency operations

Recovery:

- classify operation profile first
- use structure-appropriate search

### Mistake 2: Undefined miss semantics

Symptom:

- null/empty meaning depends on caller

Recovery:

- return explicit status values

### Mistake 3: Ignoring input assumptions

Symptom:

- binary search used on unsorted data

Recovery:

- enforce preconditions through contracts

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (10 Minutes)

Choose one search hotspot and document:

1. match rule
2. miss rule
3. current strategy
4. expected input size
5. redesign trigger

Then add one contract that prevents incorrect strategy use.

---

## Connection to Nex

Nex contracts make search assumptions executable, reducing silent mismatches between caller expectations and implementation behavior.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Search strategy should follow operation profile and data organization.
- Correctness semantics (match/miss) are part of algorithm design.
- Linear search is often a baseline, not an end state.
- Contracts help prevent invalid search usage.

---

In Chapter 20, we examine sorting, where ordering becomes a leverage point for downstream speed.
