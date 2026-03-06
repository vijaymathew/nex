# Part V — Algorithms That Power Systems — Exploring Trees and Graphs

## 21. Exploring Trees and Graphs

Once data is structured as trees or graphs, traversal becomes the core algorithmic operation.

Traversal answers questions like:

- what is reachable?
- what is connected?
- in what order should we process structure?

This chapter focuses on depth-first and breadth-first exploration patterns.

---

## DFS vs BFS

Two foundational traversal styles:

- **Depth-first search (DFS)**: go deep along a branch before backtracking.
- **Breadth-first search (BFS)**: visit level by level from a frontier.

They solve different problems efficiently:

- DFS is natural for exhaustive structure exploration and recursive decomposition.
- BFS is natural for minimum-hop reachability in unweighted graphs.

---

## Traversal Safety Rules

Traversal correctness requires explicit controls:

- visited tracking (avoid repeated loops)
- boundary conditions (depth/time/cost limits)
- deterministic policy when order matters

Without these, traversal may be correct on toy cases and unstable in real workloads.

---

## Worked Design Path

Requirement:

> "From a starting document, discover connected documents up to depth 2."

### Step 1: Define reachability scope

- max depth = 2

### Step 2: Choose traversal

- BFS for depth-layer behavior

### Step 3: Define duplicate policy

- each document visited once

### Step 4: Define output semantics

- return discovered ids in visit order

### Step 5: Define failure behavior

- unknown start id -> `INVALID_START`

---

## Nex Implementation Sketch

```nex
class Explore_Result
feature
  status: String
  discovered: String
invariant
  status_present: status /= ""
end

class Graph_Explorer
feature
  -- Teaching-sized adjacency representation.
  a_to_b: Boolean
  a_to_c: Boolean
  b_to_d: Boolean
  c_to_d: Boolean

  bfs_from_a_depth2(): Explore_Result
    do
      let r: Explore_Result := create Explore_Result

      if not a_to_b and not a_to_c then
        r.status := "ISOLATED"
        r.discovered := "A"
      elseif a_to_b and b_to_d then
        r.status := "OK"
        r.discovered := "A,B,D"
      elseif a_to_c and c_to_d then
        r.status := "OK"
        r.discovered := "A,C,D"
      elseif a_to_b then
        r.status := "OK"
        r.discovered := "A,B"
      elseif a_to_c then
        r.status := "OK"
        r.discovered := "A,C"
      else
        r.status := "ISOLATED"
        r.discovered := "A"
      end

      result := r
    ensure
      declared_status: result.status = "OK" or result.status = "ISOLATED"
    end
end
```

This simplified sketch emphasizes traversal outcomes and explicit status.

---

## Traversal Across The Three Systems

### Delivery

- explore route alternatives and reachable depots

### Knowledge

- walk linked documents/concepts

### Virtual World

- traverse scene/interactions for update and visibility

Traversal design determines both correctness and cost behavior.

---

## Common Mistakes

### Mistake 1: No visited policy

Symptom:

- repeated processing or infinite loops

Recovery:

- track visited nodes explicitly

### Mistake 2: Wrong traversal for goal

Symptom:

- DFS used where minimum-hop answer required

Recovery:

- choose traversal to match objective

### Mistake 3: Implicit depth/cost bounds

Symptom:

- traversal growth surprises under dense connectivity

Recovery:

- define max depth or expansion budget

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

Pick one graph/tree operation and specify:

1. traversal type (DFS/BFS)
2. visited policy
3. bound policy (depth/time)
4. output order rule
5. invalid-input behavior

Then justify the choice in one paragraph.

---

## Connection to Nex

Nex contracts make traversal assumptions explicit, especially around entry validity and result guarantees under bounded exploration.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Traversal is the primary algorithm over structured relationships.
- DFS and BFS solve different classes of questions.
- Visited and boundary policies are correctness requirements.
- Traversal order should be explicit when behavior depends on it.

---

In Chapter 22, we build on traversal to solve best-path optimization problems.
