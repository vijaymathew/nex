# Part VI — Building Real Software — Functional Thinking

## 24. Functional Thinking

Component boundaries define where code lives.

Functional thinking defines how behavior flows through those boundaries.

The core idea is simple: represent logic as explicit transformations with minimal hidden state.

---

## What Functional Thinking Adds

Functional style emphasizes:

- pure functions (same input -> same output)
- explicit data flow
- composition of small transformations

Benefits:

- easier testability
- easier reasoning about correctness
- fewer side-effect bugs

This does not require eliminating all objects. It means using pure transformations where they give leverage.

---

## Pure vs Effectful Layers

A practical pattern:

- pure core: compute decisions, scores, transitions
- effectful shell: persistence, network calls, logging, UI

This split reduces debugging complexity and keeps behavior reproducible.

---

## Worked Design Path

Requirement:

> "Rank documents for a query and emit top 3 IDs."

Naive approach mixes ranking and I/O.

Functional approach:

1. `tokenize(query)`
2. `score(doc, tokens)`
3. `sort_by_score(scored)`
4. `take_top(sorted, 3)`
5. effect layer publishes result

Now ranking logic is deterministic and testable without infrastructure.

---

## Nex Implementation Sketch

```nex
class Rank_Functions
feature
  score(doc, query: String): Integer
    require
      inputs_present: doc /= "" and query /= ""
    do
      if doc = query then
        result := 100
      else
        result := 20
      end
    ensure
      non_negative: result >= 0
    end

  pick_top(doc1, doc2, doc3, query: String): String
    require
      docs_present: doc1 /= "" and doc2 /= "" and doc3 /= "" and query /= ""
    do
      let s1: Integer := score(doc1, query)
      let s2: Integer := score(doc2, query)
      let s3: Integer := score(doc3, query)

      if s1 >= s2 and s1 >= s3 then
        result := doc1
      elseif s2 >= s1 and s2 >= s3 then
        result := doc2
      else
        result := doc3
      end
    ensure
      from_inputs: result = doc1 or result = doc2 or result = doc3
    end
end

class Rank_Publisher
feature
  publish(top_doc: String): String
    require
      doc_present: top_doc /= ""
    do
      result := "PUBLISHED"
    ensure
      known_status: result = "PUBLISHED" or result = "FAILED"
    end
end
```

This keeps ranking logic pure while isolating effects in a separate component.

---

## Functional Thinking Across The Three Systems

### Delivery

- pure route-scoring function + effectful dispatch update

### Knowledge

- pure ranking pipeline + effectful storage/telemetry

### Virtual World

- pure next-state calculation + effectful render/output

Functional decomposition improves reliability regardless of domain.

---

## Common Mistakes

### Mistake 1: Hidden side effects in "pure" routines

Symptom:

- same input produces different output over time

Recovery:

- isolate mutable/environmental dependencies

### Mistake 2: Over-fragmentation

Symptom:

- too many tiny functions with no semantic gain

Recovery:

- compose around meaningful transformations

### Mistake 3: Avoiding all effects inside domain code

Symptom:

- awkward abstractions that reduce clarity

Recovery:

- keep pure/effectful separation pragmatic, not dogmatic

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (10 Minutes)

Pick one feature and split it into:

1. pure decision function
2. pure transformation function
3. effectful output function
4. one contract per function

Then run tests for pure functions without using any external systems.

---

## Connection to Nex

Nex supports functional thinking naturally through contract-checked routines and clear data-flow style, even in mixed OOP systems.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Functional thinking improves predictability and testability.
- Pure core + effectful shell is a practical architecture pattern.
- Composition works best with explicit contracts and data flow.
- Pragmatic hybrid design beats ideological purity.

---

In Chapter 25, we turn to object-oriented thinking for behavior-centered collaboration.
