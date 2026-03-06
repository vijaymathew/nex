# Part III — The Shape of Algorithms — Thinking Recursively

## 13. Thinking Recursively

Recursion is not an advanced trick.

It is a decomposition approach for problems where the structure of the whole resembles the structure of its parts.

A recursive algorithm has two essential components:

1. a base case solved directly
2. a reduction step that transforms the problem into smaller instances

If either component is weak, recursion becomes fragile.

---

## When Recursion Is Appropriate

Recursion is often a good fit when data is hierarchical or graph-like:

- nested structures
- trees
- bounded graph traversals
- divide-and-conquer problems

Recursion is often a poor fit for simple linear workflows where iteration is clearer and cheaper.

Choose recursion based on problem shape, not style preference.

---

## The Base Case Is the Safety Boundary

Most recursion bugs come from incorrect base cases or missing progress.

A robust recursive design must prove:

- base case is reachable
- each recursive step moves toward base case
- output contract holds for both base and recursive branches

For graph-like domains, you also need cycle guards (`visited` set, depth limit, or both).

---

## Worked Design Path

Requirement:

> “Count reachable locations from a starting node.”

Recursive DFS concept:

1. if node already visited -> return 0
2. mark node visited
3. total := 1
4. for each neighbor -> total := total + count(neighbor)
5. return total

This design is concise, but only safe with explicit visited tracking.

---

## Nex Implementation Sketch

```nex
class Reachability
feature
  visited: String

  count_from(node: String): Integer
    require
      node_present: node /= ""
    do
      -- Teaching sketch: visited is encoded as a simple string marker set.
      -- A full implementation would use a dedicated collection type.
      if visited = node then
        result := 0
      elseif node = "Z" then
        visited := node
        result := 1
      else
        visited := node
        result := 1 + count_from("Z")
      end
    ensure
      non_negative_count: result >= 0
    end
end
```

This snippet emphasizes recursive structure, not production-grade graph storage.

---

## Recursion Across the Three Systems

### Delivery

- explore alternate path branches
- must guard against route cycles

### Knowledge

- traverse related-document networks with depth bounds
- must avoid revisiting nodes

### Virtual World

- process hierarchical containment (zone -> region -> object)
- must maintain deterministic traversal policy

In all three, recursion without bounds is unsafe.

---

## Common Mistakes

### Mistake 1: Missing base case

Symptom:

- infinite recursion

Recovery:

- write and test base case first

### Mistake 2: No progress metric

Symptom:

- recursive call does not reduce problem size

Recovery:

- define a decreasing metric (depth, remaining nodes, input size)

### Mistake 3: Ignoring cycles

Symptom:

- repeated traversal of same nodes

Recovery:

- add visited tracking
- optionally add max-depth guard

### Mistake 4: Recomputing identical subproblems

Symptom:

- exponential runtime

Recovery:

- memoize repeated subproblems
- consider dynamic programming if needed

---

## Quick Exercise (10 Minutes)

Design one recursive routine for your project and write:

1. base case
2. progress metric
3. recursive step
4. cycle guard
5. worst-case depth assumption

Then test with:

- trivial input
- normal input
- adversarial cyclic input

If adversarial input is unbounded, redesign before implementation.

---

## Connection to Nex

Nex contracts make recursive assumptions explicit and checkable, especially around base-case guarantees and non-negative/valid outputs.

That tightens correctness early and reduces latent recursion bugs.

---

## Chapter Takeaways

- Recursion is decomposition over self-similar structure.
- Base case and progress metric are non-negotiable.
- Graph recursion requires cycle and depth controls.
- Elegant recursion is not enough; bounded recursion is the goal.

---

In Chapter 14, we shift from correctness to cost: how algorithm behavior changes as inputs grow.
