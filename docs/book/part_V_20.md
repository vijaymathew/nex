# Part V — Algorithms That Power Systems — Sorting the World

## 20. Sorting the World

Searching finds items.

Sorting organizes them so later operations become simpler and faster.

In production systems, sorting is rarely cosmetic. It is usually a prerequisite for ranking, batching, deterministic processing, and efficient retrieval.

---

## Why Sorting Matters

Sorting creates structure from raw collections.

Benefits include:

- deterministic output order
- easier duplicate detection
- compatibility with binary search and merge-like workflows
- clearer user-facing ranking behavior

The main tradeoff is upfront cost versus downstream savings.

---

## Choosing A Sort Strategy

Strategy depends on constraints:

- collection size
- stability requirements
- memory budget
- partially ordered inputs

For teaching, a simple comparison sort is enough to reason about behavior.

In real systems, use library sorts unless domain constraints demand custom behavior.

---

## Worked Design Path

Requirement:

> "Return delivery tasks ordered by priority, preserving insertion order for ties."

### Step 1: Define comparison key

- primary: priority score

### Step 2: Define tie behavior

- stable order by arrival sequence

### Step 3: Choose baseline

- simple stable pass-based approach for clarity

### Step 4: Define correctness checks

- output is non-decreasing by priority
- equal-priority items keep original relative order

### Step 5: Define scale transition

- when list size grows, use optimized stable sort implementation

---

## Nex Implementation Sketch

```nex
class Task_View
feature
  id: String
  priority: Integer
  arrival: Integer
invariant
  id_present: id /= ""
  arrival_non_negative: arrival >= 0
end

class Sort_Example
feature
  t1: Task_View
  t2: Task_View
  t3: Task_View

  sorted_ids_by_priority(): String
    do
      -- Teaching-sized fixed comparison network for three items.
      let a: Task_View := t1
      let b: Task_View := t2
      let c: Task_View := t3

      if a.priority > b.priority then
        let tmp: Task_View := a
        a := b
        b := tmp
      end

      if b.priority > c.priority then
        let tmp2: Task_View := b
        b := c
        c := tmp2
      end

      if a.priority > b.priority then
        let tmp3: Task_View := a
        a := b
        b := tmp3
      end

      result := a.id + " -> " + b.id + " -> " + c.id
    ensure
      non_empty: result /= ""
    end
end
```

This sketch focuses on order semantics, not production-grade sorting APIs.

---

## Sorting Across The Three Systems

### Delivery

- prioritize task queues

### Knowledge

- order ranked search results

### Virtual World

- deterministic processing order for updates/events

Sorting often sits between raw data and high-value operations.

---

## Common Mistakes

### Mistake 1: Unclear comparison rule

Symptom:

- inconsistent order across runs

Recovery:

- define comparator explicitly
- document tie-break policy

### Mistake 2: Ignoring stability needs

Symptom:

- equal-priority records reorder unexpectedly

Recovery:

- use stable strategy when required by semantics

### Mistake 3: Re-sorting too often

Symptom:

- repeated full sort in hot loops

Recovery:

- batch updates or maintain partially ordered structures

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (10 Minutes)

Pick one ordered output in your system and define:

1. primary sort key
2. tie-break key
3. stability requirement
4. current sort frequency
5. optimization opportunity

Then write one test case with equal keys to validate tie behavior.

---

## Connection to Nex

Nex contracts help encode ordering guarantees so changes to sort implementation do not silently change system behavior.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Sorting is a structural tool, not just presentation logic.
- Comparator and tie-break rules must be explicit.
- Stability can be a correctness requirement.
- Upfront sort cost can unlock faster downstream operations.

---

In Chapter 21, we explore traversal algorithms for trees and graphs.
