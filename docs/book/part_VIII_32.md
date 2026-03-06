# Part VIII — Systems That Grow — Designing for Change

## 32. Designing for Change

Managing complexity keeps today stable.

Designing for change keeps tomorrow affordable.

The goal is not predicting every future requirement. The goal is building seams where likely changes can land without system-wide rewrite.

---

## Change Seams

A change seam is a boundary where behavior can vary while contracts remain stable.

Common seam locations:

- policy/strategy interfaces
- adapter boundaries
- versioned data contracts
- feature toggle points

Good seams isolate volatility.

---

## Versioning Strategy

Change-safe systems define explicit evolution policy:

- additive fields over breaking replacements
- deprecation windows
- compatibility adapters for old clients

Without versioning discipline, small improvements become migration crises.

---

## Worked Design Path

Requirement:

> "Add new ranking strategy without breaking existing query clients."

Design path:

1. keep stable query interface (`rank(query): result`)
2. extract ranking strategy port
3. implement V1 and V2 strategies behind same contract
4. choose strategy via configuration/feature flag

Clients remain unchanged while behavior evolves.

---

## Nex Implementation Sketch

```nex
class Rank_Strategy
feature
  rank(query: String): String
    require
      query_present: query /= ""
    do
      result := "NOT_IMPLEMENTED"
    ensure
      non_empty: result /= ""
    end
end

class Rank_V1
feature
  rank(query: String): String
    require
      query_present: query /= ""
    do
      result := "DOC:LEGACY-1"
    ensure
      non_empty: result /= ""
    end
end

class Rank_V2
feature
  rank(query: String): String
    require
      query_present: query /= ""
    do
      result := "DOC:MODERN-1"
    ensure
      non_empty: result /= ""
    end
end

class Rank_Service
feature
  strategy: Rank_V1

  run(query: String): String
    require
      query_present: query /= ""
    do
      result := strategy.rank(query)
    ensure
      non_empty: result /= ""
    end
end
```

The seam is clear: change ranking behavior without breaking service contract.

---

## Designing For Change Across The Three Systems

### Delivery

- swap route policies without changing dispatch API

### Knowledge

- evolve ranking/scoring strategies behind stable query contract

### Virtual World

- add new simulation rules behind existing step interface

Safe change depends on stable contracts and isolated variation points.

---

## Common Mistakes

### Mistake 1: Premature abstraction

Symptom:

- many empty seams with no real variation

Recovery:

- add seams where volatility is likely and evidenced

### Mistake 2: No compatibility story

Symptom:

- new version breaks existing consumers

Recovery:

- plan versioning and transitional adapters

### Mistake 3: Hidden feature flags

Symptom:

- behavior varies silently across environments

Recovery:

- make strategy selection explicit and observable

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

Pick one likely future change and define:

1. stable contract to preserve
2. seam to introduce
3. old/new implementation variants
4. rollout plan
5. rollback condition

Then identify one compatibility test you must automate.

---

## Connection to Nex

Nex contract checks keep seams honest: old and new variants can be validated against the same behavior guarantees.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Design for change means designing for controlled variation.
- Stable contracts are the backbone of safe evolution.
- Versioning and compatibility are architecture concerns, not release chores.
- Seams should be intentional, not speculative.

---

In Chapter 33, we close Part VIII with refactoring practices that preserve behavior while restructuring systems.
