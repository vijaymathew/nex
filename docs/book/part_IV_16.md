# Part IV — Organizing Data — Sets and Maps

## 16. Sets and Maps

Chapter 15 showed why list-only designs eventually slow down.

This chapter introduces two structures built for direct access:

- **set**: fast membership (is this present?)
- **map**: fast retrieval by key (get value by identity)

These structures are foundational for scalable software.

---

## Sets vs Maps

Think in terms of the question you ask most often.

Use a set when the question is:

- "Have we seen this already?"

Use a map when the question is:

- "Give me the object for this key."

If your dominant query is keyed retrieval and you are scanning a list, you are paying avoidable cost.

---

## Key Design Matters

Maps and sets are only as good as their keys.

A good key is:

- stable over time
- unique for the intended scope
- cheap to compare

Weak key design causes collisions, accidental overwrites, and stale lookups.

---

## Worked Design Path

Requirement:

> "For each query, avoid re-scoring the same document and fetch metadata by id quickly."

### Step 1: Identify operations

- dedupe candidate ids -> membership
- fetch document by id -> keyed lookup

### Step 2: Choose structures

- `seen_ids` as set
- `documents_by_id` as map

### Step 3: Define contracts

- adding id to set preserves uniqueness semantics
- map lookup returns document or explicit `NOT_FOUND`

### Step 4: Preserve display behavior

If result order still matters, keep an ordered list for output and use set/map for control/lookups.

This hybrid pattern is common in real systems.

---

## Nex Implementation Sketch

```nex
class Doc_Record
feature
  doc_id: String
  title: String
invariant
  id_present: doc_id /= ""
  title_present: title /= ""
end

class Doc_Index
feature
  k1: String
  v1: Doc_Record
  k2: String
  v2: Doc_Record
  k3: String
  v3: Doc_Record

  contains(doc_id: String): Boolean
    require
      id_present: doc_id /= ""
    do
      result := doc_id = k1 or doc_id = k2 or doc_id = k3
    ensure
      bool_result: result = true or result = false
    end

  fetch_title(doc_id: String): String
    require
      id_present: doc_id /= ""
    do
      if doc_id = k1 then
        result := v1.title
      elseif doc_id = k2 then
        result := v2.title
      elseif doc_id = k3 then
        result := v3.title
      else
        result := "NOT_FOUND"
      end
    ensure
      non_empty: result /= ""
    end
end
```

This chapter sketch emulates map/set behavior in a minimal teaching form.

---

## Sets and Maps in the Three Systems

### Delivery

- set: blocked locations visited this cycle
- map: `task_id -> task`

### Knowledge

- set: dedupe candidate document ids
- map: `doc_id -> document metadata`

### Virtual World

- set: active entities in current frame
- map: `entity_id -> entity state`

The pattern is consistent: membership control + keyed state access.

---

## Common Mistakes

### Mistake 1: Bad key choice

Symptom:

- updates overwrite wrong record

Recovery:

- define key policy explicitly
- use stable identity keys only

### Mistake 2: Assuming order from map/set

Symptom:

- inconsistent user-facing output order

Recovery:

- keep ordered sequence separately when needed

### Mistake 3: Duplicate source of truth

Symptom:

- list and map diverge silently

Recovery:

- define one authoritative write path
- verify sync invariants in tests

### Mistake 4: Treating missing as impossible

Symptom:

- null or crash on lookup miss

Recovery:

- define explicit miss behavior (`NOT_FOUND`/option type)

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (10-12 Minutes)

Pick one list-scan hotspot and redesign it using set/map semantics.

Write:

1. chosen key
2. membership operation
3. keyed lookup operation
4. miss behavior
5. one invariant that protects index consistency

Then compare before/after on at least one medium-size input.

---

## Connection to Nex

Nex contracts make key and lookup assumptions explicit, which is critical when data structures become central to correctness and speed.

This is where many scaling bugs are prevented early.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Sets answer membership questions efficiently.
- Maps answer keyed retrieval questions efficiently.
- Key design is a correctness decision, not just a performance detail.
- Ordered output often requires sequence + map/set hybrid designs.

---

In Chapter 17, we move to trees, where hierarchy and ordered search behavior become first-class concerns.
