# Part IV — Organizing Data — Graphs: Networks of Everything

## 18. Graphs: Networks of Everything

Trees model hierarchy.

Graphs model networks.

A graph represents entities as nodes and relationships as edges, allowing cycles, cross-links, and many-to-many structure.

Most real systems eventually require graph thinking.

---

## Why Graphs Are Essential

Graphs appear whenever relationships are not strictly hierarchical:

- roads between locations
- citations between documents
- interactions between world entities

If you force these domains into lists or trees only, you lose expressive power and introduce fragile workarounds.

---

## Core Graph Questions

Graph-driven systems frequently ask:

- reachability: can we get from A to B?
- path quality: what is the best route by some objective?
- neighborhood: what is connected to this node?
- connectivity changes: what broke when an edge disappeared?

Data structure choice should follow these queries.

---

## Worked Design Path

Requirement:

> "Given two locations, return a valid route or report unreachable."

### Step 1: Define graph elements

- node: location id
- edge: traversable connection between two locations

### Step 2: Define correctness contract

- returned path uses only valid edges
- first node equals source
- last node equals destination
- failure is explicit (`UNREACHABLE`)

### Step 3: Pick traversal for first version

- BFS for minimum hops in unweighted graph

### Step 4: Add safety rules

- visited tracking to avoid cycles
- guard unknown nodes as invalid input

### Step 5: Keep representation explicit

- adjacency structure as first-class model element

---

## Nex Implementation Sketch

```nex
class Route_Graph
feature
  -- Teaching-sized adjacency for four nodes.
  a_to_b: Boolean
  b_to_c: Boolean
  c_to_d: Boolean
  a_to_d: Boolean

  has_edge(from_node, to_node: String): Boolean
    require
      non_empty_nodes: from_node /= "" and to_node /= ""
    do
      if from_node = "A" and to_node = "B" then
        result := a_to_b
      elseif from_node = "B" and to_node = "C" then
        result := b_to_c
      elseif from_node = "C" and to_node = "D" then
        result := c_to_d
      elseif from_node = "A" and to_node = "D" then
        result := a_to_d
      else
        result := false
      end
    ensure
      bool_result: result = true or result = false
    end

  route_a_to_d(): String
    do
      if has_edge("A", "D") then
        result := "A->D"
      elseif has_edge("A", "B") and has_edge("B", "C") and has_edge("C", "D") then
        result := "A->B->C->D"
      else
        result := "UNREACHABLE"
      end
    ensure
      declared_result:
        result = "A->D" or
        result = "A->B->C->D" or
        result = "UNREACHABLE"
    end
end
```

This sketch keeps graph intent explicit: edges define legal movement; route logic must respect them.

---

## Graphs in the Three Systems

### Delivery

- location network and route computation

### Knowledge

- citation/relevance links among documents

### Virtual World

- interaction network between entities and zones

Graphs are often the hidden structure behind system complexity.

---

## Common Mistakes

### Mistake 1: Implicit graph representation

Symptom:

- connectivity logic scattered across code paths

Recovery:

- centralize adjacency representation
- expose graph operations explicitly

### Mistake 2: Missing cycle protection in traversal

Symptom:

- infinite loops or repeated work

Recovery:

- add visited tracking
- bound traversal depth when appropriate

### Mistake 3: Conflating reachability with best path

Symptom:

- any found route treated as optimal

Recovery:

- define objective separately (hops, cost, risk)
- choose traversal/search accordingly

### Mistake 4: Weak failure semantics

Symptom:

- partial/empty path with no meaning

Recovery:

- return explicit unreachable/invalid status

---

## Quick Exercise (12 Minutes)

For one networked feature in your project:

1. define nodes and edges
2. define one reachability query
3. define one path-quality objective
4. define failure status
5. list one cycle-related risk and mitigation

Then decide whether current code treats the graph explicitly or accidentally.

---

## Connection to Nex

Nex contracts make graph assumptions explicit at operation boundaries, especially around valid inputs, declared failure modes, and route correctness guarantees.

That is essential for systems where graph mistakes become production incidents.

---

## Chapter Takeaways

- Graphs model real-world networks more faithfully than tree-only designs.
- Reachability and optimality are different questions.
- Traversal safety requires cycle-aware logic.
- Explicit graph models improve correctness and maintainability.

---

Part IV established the core data-structure families that underpin scalable software.

In Part V, we apply them to core algorithm families: searching, sorting, traversals, and path finding.
