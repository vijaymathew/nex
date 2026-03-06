# Part VII — Making Software Trustworthy — Debugging Like an Engineer

## 30. Debugging Like an Engineer

Testing tells us something is wrong.

Debugging determines why it is wrong and how to fix it without creating new faults.

Engineering-grade debugging is a structured process, not trial-and-error edits.

---

## Debugging As Hypothesis Work

Effective debugging loop:

1. reproduce reliably
2. localize failure boundary
3. form hypothesis
4. run targeted check
5. confirm cause
6. patch and verify regression safety

Skipping steps causes "fixes" that mask symptoms and move bugs elsewhere.

---

## Reproducibility First

A bug you cannot reproduce cannot be fixed confidently.

Before patching:

- capture exact inputs/state
- minimize to a smallest failing case
- note expected vs observed behavior

This transforms debugging from guesswork into evidence-driven analysis.

---

## Worked Design Path

Bug report:

> "Some delivery tasks jump directly from PENDING to DELIVERED."

Debug steps:

1. reproduce with minimal task flow
2. inspect transition entry points
3. hypothesize missing precondition on `complete()`
4. confirm by calling `complete()` without `IN_TRANSIT`
5. patch contract and rerun regression checks

Result: illegal transition blocked at boundary.

---

## Nex Implementation Sketch

```nex
class Delivery_Task
feature
  status: String

  start()
    require
      can_start: status = "PENDING" or status = "FAILED"
    do
      status := "IN_TRANSIT"
    ensure
      now_in_transit: status = "IN_TRANSIT"
    end

  complete()
    require
      in_transit: status = "IN_TRANSIT"
    do
      status := "DELIVERED"
    ensure
      delivered: status = "DELIVERED"
    end
invariant
  valid_status:
    status = "PENDING" or
    status = "IN_TRANSIT" or
    status = "DELIVERED" or
    status = "FAILED"
end

class Debug_Smoke_Test
feature
  run(): String
    do
      let t: Delivery_Task := create Delivery_Task
      t.status := "PENDING"
      t.start
      t.complete

      if t.status = "DELIVERED" then
        result := "PASS"
      else
        result := "FAIL"
      end
    ensure
      known_result: result = "PASS" or result = "FAIL"
    end
end
```

This patch shape demonstrates contract-based bug prevention and regression confirmation.

---

## Debugging Across The Three Systems

### Delivery

- transition violations and route mismatches

### Knowledge

- ranking inconsistencies and stale index effects

### Virtual World

- non-deterministic update ordering and bound violations

Debugging strategy is shared: isolate, hypothesize, verify, patch, regress.

---

## Common Mistakes

### Mistake 1: Patching before reproducing

Symptom:

- bug returns in slightly different form

Recovery:

- establish minimal reproducible case first

### Mistake 2: Multiple simultaneous changes

Symptom:

- cannot tell which edit fixed or broke behavior

Recovery:

- apply one hypothesis-driven change at a time

### Mistake 3: No regression safety net

Symptom:

- fix introduces new failures elsewhere

Recovery:

- add targeted regression tests before merging

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

Take one recent bug and write:

1. reproducible case
2. observed vs expected behavior
3. root-cause hypothesis
4. confirming check
5. patch summary
6. regression tests added

If any step is missing, the fix quality is uncertain.

---

## Connection to Nex

Nex contracts and invariants accelerate debugging by making assumption violations explicit at runtime boundaries.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Debugging is hypothesis-driven engineering work.
- Reproducibility is prerequisite to reliable fixes.
- Contracts and invariants help localize root causes quickly.
- Every fix needs regression evidence.

---

Part VII established trust mechanisms: contracts, invariants, testing, and debugging.

Next is **System Milestone 5 — Reliability**, where these practices are applied end-to-end in Studio 5.
