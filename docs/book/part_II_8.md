# Part II — Modeling the World — Relationships: How Things Connect

## 8. Relationships: How Things Connect

Entities give us the nouns of a system.

Relationships give us the structure.

Most interesting system behavior does not come from isolated entities. It comes from how entities connect, depend, and constrain each other.

If entities are wrong, systems are confusing.
If relationships are wrong, systems are dangerous.

---

## Why Relationships Matter

A relationship encodes how two entities are linked.

Examples:

- `Robot` **is assigned to** `DeliveryTask`
- `Document` **has tag** `Tag`
- `WorldObject` **interacts with** `WorldObject`

These links are not decoration. They define allowable operations.

If links are underspecified:

- data duplicates and diverges
- cascading failures become invisible
- queries become expensive or ambiguous

---

## Relationship Dimensions

Use these dimensions to make relationships explicit.

### 1) Cardinality

How many items can connect?

- one-to-one
- one-to-many
- many-to-many

### 2) Direction

Is the relationship directional?

- `A -> B` can mean dependency, containment, or flow.

### 3) Ownership

Who is responsible for maintaining integrity?

- parent owns child?
- shared relationship table/index?

### 4) Lifecycle Coupling

If one entity is removed, what happens to related entities?

- delete
- orphan
- preserve with historical reference

### 5) Constraint Rules

What must always be true across the relationship?

- assignment must reference existing entities
- link types must be valid
- no illegal cycles for specific relation types

---

## Worked Design Path

Ambiguous requirement:

> “The knowledge engine should connect related notes.”

### Step 1: Name Entities

- `Document`
- `Tag`
- `Link`

### Step 2: Choose Relationship Shape

Potential options:

- direct many-to-many `Document <-> Document`
- relationship entity `Link(from_doc, to_doc, link_type)`

Use relationship entity when:

- you need metadata (strength, timestamp, source)
- you need multiple link types
- you need auditability

### Step 3: Define Constraints

- `from_doc` and `to_doc` must exist
- disallow self-link for selected link types
- link type must be in controlled set

### Step 4: Define Query Paths

Design depends on usage:

- “show all references for this document”
- “find backlinks”
- “traverse two hops for discovery”

Model for expected queries, not abstract purity.

---

## Relationships In The Three Systems

### Delivery

- `Location` connected to `Location` via `Path`
- `Robot` assigned to at most one active `DeliveryTask`
- `DeliveryTask` references origin and destination locations

### Knowledge

- `Document` linked to `Tag`
- `Document` linked to `Document` with typed edges
- optional confidence score for inferred links

### Virtual World

- `WorldObject` belongs to region/zone
- interaction rules reference object type pairs
- event relationships capture cause/effect chains

Shared pattern: relationships are first-class model elements, not incidental fields.

---

## Nex Implementation Sketch

```nex
class Doc_Link
feature
  from_id: String
  to_id: String
  link_type: String

  is_structurally_valid(): Boolean do
    result := from_id /= "" and to_id /= "" and link_type /= ""
  ensure
    result_is_boolean: result = true or result = false
  end
invariant
  endpoints_present: from_id /= "" and to_id /= ""
  non_self_reference: from_id /= to_id
  link_type_present: link_type /= ""
end
```

Minimal, but expressive:

- relationship as explicit entity
- endpoint constraints
- structural validation operation

This style generalizes to `Path` in delivery and interaction edges in world simulation.

---

## Common Mistakes

### Mistake 1: Encoding Relationships As Free-Text Fields

Symptom:

- inconsistent IDs/names
- impossible joins/traversals

Recovery:

- model relationships as typed links
- constrain endpoints and link type

### Mistake 2: Ignoring Reverse Queries

Symptom:

- forward lookup fast, reverse lookup slow/unavailable

Recovery:

- design indexes for both directions when needed
- document expected query patterns

### Mistake 3: Relationship Semantics Drift

Symptom:

- same link type used for incompatible meanings

Recovery:

- define allowed link taxonomy
- enforce in constructors/validators

### Mistake 4: Hidden Lifecycle Rules

Symptom:

- deleting an entity leaves broken links

Recovery:

- define delete/update policy explicitly
- test relationship integrity after lifecycle events

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (8 Minutes)

Pick one system and write a relationship matrix:

1. Entity A
2. Relationship type
3. Entity B
4. Cardinality
5. Constraint rule

Then add one reverse query your model must support.

If reverse query is expensive or unclear, your relationship model needs refinement.

---

## Connection to Nex

Nex makes relationship rules easier to keep visible through invariants and explicit validation methods.

The teaching value is not syntax. It is disciplined modeling: connections are modeled, constrained, and tested.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Relationships determine system structure and query behavior.
- Cardinality, direction, lifecycle, and constraints must be explicit.
- Relationship entities are often better than ad hoc fields.
- Model for real access patterns, not theoretical elegance.
- Good relationship models prevent silent integrity drift.

---

In Chapter 9, we combine entities and relationships into a full data model and evaluate tradeoffs.
