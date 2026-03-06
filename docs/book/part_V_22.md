# Part V — Algorithms That Power Systems — Finding the Best Path

## 22. Finding the Best Path

Reachability asks whether a path exists.

Best-path algorithms ask which path is optimal under an objective.

That objective must be explicit: minimum hops, minimum time, minimum risk, or weighted combination.

---

## Path Optimality Requires A Cost Model

A path algorithm is only meaningful relative to a declared cost model.

Examples:

- unweighted hops -> BFS-style shortest path
- non-negative weighted edges -> Dijkstra-style approach
- heuristic-guided search -> A*-style tradeoff

If cost model is unclear, "best" becomes team interpretation, not system behavior.

---

## Priority Matters

Best-path search usually depends on managing a frontier by priority.

Key design questions:

- what is frontier ordering key?
- how are ties handled?
- when is a node considered finalized?

These choices affect both correctness and performance.

---

## Worked Design Path

Requirement:

> "Route from A to D minimizing travel cost."

### Step 1: Define edge costs

- each edge has non-negative integer cost

### Step 2: Define validity

- path cost = sum of edge costs
- result must be valid connected sequence

### Step 3: Define failure behavior

- unreachable destination -> `UNREACHABLE`

### Step 4: Choose algorithm

- Dijkstra-style priority expansion for non-negative costs

### Step 5: Define determinism

- stable tie-break rule for equal-cost candidates

---

## Nex Implementation Sketch

```nex
class Path_Result
feature
  status: String
  path: String
  total_cost: Integer
invariant
  status_present: status /= ""
  non_negative_cost: total_cost >= 0
end

class Best_Path_Example
feature
  a_b: Integer
  b_d: Integer
  a_c: Integer
  c_d: Integer
  a_d: Integer

  best_a_to_d(): Path_Result
    require
      non_negative_costs:
        a_b >= 0 and b_d >= 0 and a_c >= 0 and c_d >= 0 and a_d >= 0
    do
      let r: Path_Result := create Path_Result
      let abd: Integer := a_b + b_d
      let acd: Integer := a_c + c_d

      if a_d <= abd and a_d <= acd then
        r.status := "FOUND"
        r.path := "A->D"
        r.total_cost := a_d
      elseif abd <= acd then
        r.status := "FOUND"
        r.path := "A->B->D"
        r.total_cost := abd
      else
        r.status := "FOUND"
        r.path := "A->C->D"
        r.total_cost := acd
      end

      result := r
    ensure
      known_status: result.status = "FOUND" or result.status = "UNREACHABLE"
    end
end
```

This teaching sketch shows objective-driven path selection with explicit costs.

---

## Best-Path In The Three Systems

### Delivery

- route tasks by travel cost and constraints

### Knowledge

- find lowest-cost explanation/link path between concepts

### Virtual World

- navigate entities through weighted interaction spaces

Best-path design turns network models into operational decisions.

---

## Common Mistakes

### Mistake 1: Objective ambiguity

Symptom:

- different runs optimize different implicit goals

Recovery:

- document cost model explicitly

### Mistake 2: Using greedy local choice as global optimum

Symptom:

- locally cheap edge leads to expensive total path

Recovery:

- use algorithm with global optimality guarantees for chosen assumptions

### Mistake 3: Ignoring edge constraints

Symptom:

- algorithm treats blocked/illegal edges as available

Recovery:

- validate edge legality in expansion step

### Mistake 4: Unclear unreachable semantics

Symptom:

- empty path interpreted inconsistently

Recovery:

- return explicit unreachable status

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

For one routing problem in your system, define:

1. objective function
2. edge validity rules
3. tie-break rule
4. unreachable behavior
5. algorithm choice and assumption set

Then add one adversarial case where greedy local choice fails.

---

## Connection to Nex

Nex contracts keep path assumptions explicit and testable, especially around non-negative costs, legal edges, and declared failure semantics.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Best path is objective-dependent.
- Priority-driven frontier management is central to weighted search.
- Local greedy choice is not always globally optimal.
- Explicit cost and failure semantics are required for correctness.

---

Next: **Algorithm Lab — When Algorithms Compete**, where we benchmark competing strategies under different workload shapes before moving into Part VI.
