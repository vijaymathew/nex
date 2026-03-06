# Part III — The Shape of Algorithms — What Is an Algorithm?

## 11. What Is an Algorithm?

Part II established models: entities, relationships, and constraints.

Part III turns those models into behavior.

An **algorithm** is a finite, explicit procedure that transforms valid input into output while preserving declared guarantees.

That definition matters because teams often blur three different things:

- a requirement (“find best route”)
- an algorithm (BFS, Dijkstra, A*)
- an implementation detail (language, library, runtime)

When those are mixed together, correctness is hard to reason about and changes become expensive.

---

## Algorithm vs Requirement vs Implementation

Keep these layers distinct:

- **Requirement**: what outcome the system must deliver.
- **Algorithm**: the step-by-step logic used to deliver it.
- **Implementation**: concrete code in Nex/JavaScript/Java and platform choices.

One requirement can have multiple valid algorithms. One algorithm can have multiple implementations.

Example:

- Requirement: “Return a valid path or report unreachable.”
- Algorithm A: BFS for minimum hops in unweighted graphs.
- Algorithm B: Dijkstra for weighted cost.
- Implementation: generated JS for browser simulation, generated Java for backend service.

This separation keeps design conversations precise.

---

## Correctness First

A procedure that “usually works” is not an algorithmic success.

An algorithm is acceptable only when its guarantees are explicit and checkable.

For our running systems:

### Delivery

- Returned route uses valid, traversable links.
- If no route exists, report explicit failure (`UNREACHABLE`).

### Knowledge

- Ranking follows declared scoring rules.
- Missing evidence is handled intentionally, not silently.

### Virtual World

- Transition steps preserve world invariants.
- Update order is deterministic when required by design.

The right question is not “Does it run?” but “What does it guarantee?”

---

## Worked Design Path

Requirement:

> “Find the best route quickly.”

### Step 1: Define valid input

- start and destination identifiers must exist
- graph snapshot must be internally consistent

### Step 2: Define output contract

- `FOUND(path)` where each edge is legal
- or `UNREACHABLE`
- or `INVALID_INPUT`

### Step 3: Define objective explicitly

For this version: “best” means minimum hops.

### Step 4: Pick algorithm to match objective

Minimum hops on unweighted graph suggests BFS.

### Step 5: Declare failure semantics

- unknown nodes -> `INVALID_INPUT`
- disconnected graph -> `UNREACHABLE`

### Step 6: Encode in contracts

- `require`: inputs are present
- `ensure`: output status is always valid

Now the team has algorithmic intent, not vague search behavior.

---

## Nex Implementation Sketch

```nex
class Route_Result
feature
  status: String
  path: String
invariant
  valid_status:
    status = "FOUND" or
    status = "UNREACHABLE" or
    status = "INVALID_INPUT"
end

class Route_Algorithm
feature
  compute(start_loc, dest_loc: String): Route_Result
    require
      inputs_present: start_loc /= "" and dest_loc /= ""
    do
      let r: Route_Result := create Route_Result

      if start_loc = dest_loc then
        r.status := "FOUND"
        r.path := start_loc
      elseif start_loc = "A" and dest_loc = "C" then
        r.status := "FOUND"
        r.path := "A->B->C"
      else
        r.status := "UNREACHABLE"
        r.path := ""
      end

      result := r
    ensure
      status_is_declared:
        result.status = "FOUND" or
        result.status = "UNREACHABLE" or
        result.status = "INVALID_INPUT"
    end
end
```

The sketch is intentionally small. The chapter point is the contract shape around the algorithm, not graph implementation details.

---

## Common Mistakes

### Mistake 1: Calling any script an algorithm

Symptom:

- behavior depends on accidental control flow

Recovery:

- write language-agnostic steps first
- state guarantees before coding

### Mistake 2: Undefined failure behavior

Symptom:

- empty/null outputs with no meaning

Recovery:

- declare explicit failure statuses
- test each status path

### Mistake 3: Ambiguous objective

Symptom:

- team argues later about what “best” meant

Recovery:

- encode one objective now
- document deferred objectives

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (8-10 Minutes)

Choose one operation in your project and write:

1. valid input definition
2. output contract (success + failure)
3. objective function
4. 5-step language-agnostic algorithm
5. one contract you will enforce in code

If two engineers would implement your sketch differently, the algorithm description is still too loose.

---

## Connection to Nex

Nex makes algorithm intent concrete because contracts and invariants live next to behavior.

This reinforces a reliable engineering sequence:

- specify
- implement
- verify

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Algorithms are procedures with explicit guarantees, not just executable code.
- Requirement, algorithm, and implementation should stay separate.
- Correctness and failure semantics must be declared up front.
- Contracts are a practical mechanism for preserving algorithm intent.

---

In Chapter 12, we study decomposition: how to break hard algorithmic work into manageable, testable pieces.
