# Part IV — Organizing Data — Trees: Structured Data

## 17. Trees: Structured Data

Lists give order. Maps and sets give direct access.

Trees add hierarchy.

A tree is a structure where parent-child relationships represent layered organization and enable structured search.

Trees are useful when relationships are naturally nested and queries can exploit that shape.

---

## Why Trees Matter

Trees can make operations faster and clearer when:

- data has hierarchical meaning (category -> subcategory -> item)
- you need ordered search over keys
- you need bounded-depth traversal rather than full scans

Common tree use cases include indexes, configuration scopes, scene graphs, and expression structures.

---

## Tree Invariants

A tree is only useful if its structure remains valid.

Typical invariants include:

- no cycles
- every non-root node has exactly one parent
- ordering rule for search trees (left < node < right)

Without structural invariants, tree logic degrades into graph-like ambiguity.

---

## Worked Design Path

Requirement:

> "Organize notes by topic hierarchy and find the first matching topic quickly."

### Step 1: Identify hierarchy

- root topic
- subtopics
- leaf notes

### Step 2: Choose tree representation

A simple teaching shape:

- node with key
- optional left child
- optional right child

### Step 3: Define lookup behavior

- if key matches current node -> return found
- if target smaller -> traverse left
- otherwise -> traverse right

### Step 4: Define miss behavior

- return explicit `NOT_FOUND`

### Step 5: Preserve invariants on insert/update

- keep ordering rule valid
- reject duplicate policy violations where required

---

## Nex Implementation Sketch

```nex
class Topic_Node
feature
  key: Integer
  label: String
  left_key: Integer
  right_key: Integer
invariant
  label_present: label /= ""
  no_self_child: left_key /= key and right_key /= key
end

class Topic_Tree
feature
  root: Topic_Node
  left: Topic_Node
  right: Topic_Node

  find_label(target: Integer): String
    do
      if target = root.key then
        result := root.label
      elseif target < root.key and target = left.key then
        result := left.label
      elseif target > root.key and target = right.key then
        result := right.label
      else
        result := "NOT_FOUND"
      end
    ensure
      non_empty: result /= ""
    end
end
```

This minimal sketch demonstrates tree-style branching decisions and explicit miss semantics.

---

## Trees in the Three Systems

### Delivery

- region hierarchy for dispatch zones

### Knowledge

- taxonomy and topic classification trees

### Virtual World

- scene graph or containment hierarchy

In each case, hierarchy encodes meaning, not just storage.

---

## Common Mistakes

### Mistake 1: Using trees without hierarchy need

Symptom:

- unnecessary complexity over flat keyed access

Recovery:

- validate that operations truly benefit from parent-child structure

### Mistake 2: Ignoring balancing concerns

Symptom:

- deep skewed trees behave like linked lists

Recovery:

- monitor depth distribution
- consider balanced variants when needed

### Mistake 3: Violating ordering invariants

Symptom:

- search misses existing values

Recovery:

- enforce insertion/update rules
- add invariant-focused tests

### Mistake 4: Confusing tree with graph

Symptom:

- multiple parents/cycles appear silently

Recovery:

- enforce single-parent and acyclic constraints

---

## Quick Exercise (10 Minutes)

Model one small hierarchy in your domain as a tree.

Write:

1. node identity key
2. parent-child rule
3. one search operation
4. one structural invariant
5. one invalid state to reject

Then explain why a map-only model would lose useful structure.

---

## Connection to Nex

Nex contracts and invariants are a strong fit for tree structure because they keep parent-child and ordering assumptions explicit.

This is critical as tree code grows beyond toy depth.

---

## Chapter Takeaways

- Trees represent hierarchy with structured search paths.
- Tree invariants are mandatory for correctness.
- Unbalanced trees can erase expected performance gains.
- Use trees when structure carries semantic value.

---

In Chapter 18, we generalize from hierarchy to networks using graphs.
