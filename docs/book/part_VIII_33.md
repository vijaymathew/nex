# Part VIII — Systems That Grow — Refactoring Without Fear

## 33. Refactoring Without Fear

Growth requires change.

Refactoring is how we improve structure without changing observable behavior.

Fear appears when teams lack safety nets and clear process.

This chapter makes refactoring an engineering routine, not a risky event.

---

## Behavior Preservation First

Refactoring is valid only when behavior remains equivalent for intended contracts.

Core safety tools:

- contract checks
- regression tests
- incremental scope
- before/after comparators

Without these, "refactor" becomes disguised rewrite.

---

## Incremental Refactor Pattern

A reliable sequence:

1. capture current behavior with tests/contracts
2. extract seam (interface/adapter/function boundary)
3. move one responsibility at a time
4. run parity checks after each step
5. remove dead path only after confidence

This pattern minimizes risk and improves reversibility.

---

## Worked Design Path

Requirement:

> "Split one large knowledge service into retrieval and ranking services."

Plan:

1. add tests for existing outputs
2. extract `retrieve_candidates` method
3. extract `rank_candidates` method
4. wire through coordinator
5. run before/after result comparison

Outcome: improved structure with preserved external behavior.

---

## Nex Implementation Sketch

```nex
class Legacy_Knowledge_Service
feature
  query(q: String): String
    require
      query_present: q /= ""
    do
      if q = "graphs" then
        result := "DOC:G-1"
      else
        result := "DOC:GENERIC"
      end
    ensure
      non_empty: result /= ""
    end
end

class Retrieval_Service
feature
  retrieve(q: String): String
    require
      query_present: q /= ""
    do
      if q = "graphs" then
        result := "CAND:G-1"
      else
        result := "CAND:GENERIC"
      end
    ensure
      non_empty: result /= ""
    end
end

class Ranking_Service
feature
  rank(candidate: String): String
    require
      candidate_present: candidate /= ""
    do
      if candidate = "CAND:G-1" then
        result := "DOC:G-1"
      else
        result := "DOC:GENERIC"
      end
    ensure
      non_empty: result /= ""
    end
end

class Refactored_Knowledge_Service
feature
  retrieval: Retrieval_Service
  ranking: Ranking_Service

  query(q: String): String
    require
      query_present: q /= ""
    do
      let c: String := retrieval.retrieve(q)
      result := ranking.rank(c)
    ensure
      non_empty: result /= ""
    end
end
```

Both services expose the same outward query contract while internal structure improves.

---

## Refactoring Risks Across The Three Systems

### Delivery

- transition logic moved incorrectly across components

### Knowledge

- retrieval/ranking split changes subtle ordering behavior

### Virtual World

- update-loop extraction breaks deterministic order

Refactoring safety depends on explicit behavior contracts and parity checks.

---

## Common Mistakes

### Mistake 1: Big-bang refactor

Symptom:

- many failures with unclear root cause

Recovery:

- refactor in small verified slices

### Mistake 2: Missing parity tests

Symptom:

- "cleaner" design changes business behavior unintentionally

Recovery:

- compare before/after outputs on representative cases

### Mistake 3: Early deletion of old path

Symptom:

- no rollback option when regressions appear

Recovery:

- keep transitional path until confidence threshold

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

Choose one module and plan a refactor:

1. current behavior contract
2. seam to extract
3. first small structural change
4. parity check set
5. rollback criterion

Then execute only step 1 and step 2 before making deeper edits.

---

## Connection to Nex

Nex contracts and invariants provide a built-in behavior guardrail for incremental refactoring, reducing fear and guesswork.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Refactoring is behavior-preserving structural improvement.
- Incremental, contract-backed steps reduce risk.
- Parity testing is the key confidence mechanism.
- Fear decreases when rollback and verification are explicit.

---

Part VIII established growth discipline: complexity control, change-oriented design, and safe refactoring.

Next is **System Milestone 6 — Evolution**, where these practices are applied to long-term system adaptation in Studio 6.
