# Relationships — How Things Connect

Entities give us the vocabulary of a system. Relationships give it grammar.

A model composed only of entities is a list of nouns with no verbs, no dependencies, no structure. The interesting behavior of any real system — the behavior that is difficult to reason about, difficult to test, and difficult to change without breaking something — lives almost entirely in how entities connect to one another. Getting entities wrong makes a system confusing. Getting relationships wrong makes it dangerous: data diverges silently, cascading failures become invisible, and the system acquires behavior that nobody designed and nobody can fully explain.

Relationships deserve the same deliberate treatment we gave entities in Chapter 7. That means making them explicit, naming their properties, and encoding the rules that govern them before those rules get buried in application code.


## What a Relationship Is

A relationship encodes how two entities are linked and what that link means. Three examples from our running systems:

- A `Robot` is assigned to a `DeliveryTask`
- A `Document` carries a `Tag`
- A `WorldObject` interacts with another `WorldObject`

In each case, the link is not decorative. It defines which operations are permitted, which queries are meaningful, and what must remain consistent across the system. An assignment implies that the robot is unavailable for other tasks. A document carrying a tag implies that removing the tag changes the document's position in search results. An interaction between world objects implies that the rules governing both objects must be consulted when their states change.

When these implications are left implicit — encoded nowhere in the model, enforced nowhere in the code — they do not disappear. They simply become assumptions that every developer must independently discover and manually respect. That is the source of most subtle, long-lived bugs in large systems.


## Five Dimensions of a Relationship

To make a relationship fully explicit, five questions must be answered.

**Cardinality.** How many instances of each entity can participate? A robot may be assigned to at most one active delivery task at a time — this is a one-to-one constraint. A document may carry many tags, and a tag may apply to many documents — this is many-to-many. Getting cardinality wrong produces either artificial restrictions or data models that permit states the domain forbids.

**Direction.** Is the relationship symmetric or does it flow one way? A delivery task references an origin and a destination, but a location does not inherently reference the tasks that pass through it. A document link between A and B may or may not imply a link from B to A, depending on the link type. Direction determines which entity "owns" the relationship and which follows from it.

**Ownership.** Which entity is responsible for maintaining the integrity of the link? In some relationships, one entity is the parent and the other is a dependent — the parent's existence is a precondition for the child's. In others, the relationship is mediated by a shared index or junction structure, and neither entity fully owns it. Leaving ownership undefined means that two different parts of the system may each assume the other is maintaining the link, and neither does.

**Lifecycle coupling.** What happens to a relationship when one of its participants is removed? Three outcomes are possible: the related entity is deleted with it (cascade), the related entity is left without a valid reference (orphan), or the relationship is preserved with a historical marker that makes the absence explicit. Each choice has consequences. None of them is automatically correct. All of them need to be decided rather than discovered.

**Constraint rules.** What must always be true across the relationship? A link must reference entities that actually exist. Certain link types may forbid self-reference. Traversal graphs for certain relationship types may forbid cycles. These constraints are invariants of the relationship itself, and they belong in the model for the same reason that entity invariants do.


## Relationships in the Three Systems

Applying these dimensions to our three systems reveals both the specific relationships and the shared pattern they instantiate.

**Delivery network.** Locations are connected to other locations through paths; each path has a direction and a status. A robot is assigned to at most one active delivery task at a time — a one-to-one constraint with a well-defined lifecycle: when a task is completed or fails, the assignment is released. A delivery task references its origin and destination locations, and those locations must exist for the task to be valid.

**Knowledge engine.** Documents are linked to tags in a many-to-many relationship, mediated by an association that can carry additional information such as confidence or provenance. Documents are linked to other documents through typed edges, where the link type determines the semantics of traversal. Inferred links — connections generated by the system rather than asserted by a user — may carry a confidence score that distinguishes them from explicit ones.

**Virtual world.** World objects belong to spatial regions, a containment relationship that determines which interaction rules apply. Interaction rules reference pairs of object types rather than individual objects, making them a relationship between the type system and the entity system. Event relationships capture cause-and-effect chains, linking the event that triggered a transition to the transition it produced.

In all three cases, relationships are first-class elements of the model. They are not incidental fields on entities; they are structures with their own properties, constraints, and lifecycle rules.


## When to Use a Relationship Entity

Sometimes a relationship is simple enough to be represented as a reference field on one of the participating entities. A delivery task carries an identifier for its assigned robot. A document carries a list of tag identifiers. These representations work when the relationship has no properties of its own and when only one query direction matters.

When a relationship needs to carry metadata — a timestamp, a confidence score, a link type, a source — it becomes a thing in its own right and deserves to be modeled as an entity. Consider the requirement:

> *"The knowledge engine should connect related notes."*

The first candidate model is a direct many-to-many connection: each document holds a list of related document identifiers. This works until we need to know when a connection was made, who made it, or what kind of connection it is. At that point the flat field becomes inadequate, because the relationship has acquired properties that cannot live on either endpoint.

The stronger model names the relationship explicitly. Start with the entities: `Document`, `Tag`, `Link`. A `Link` connects two documents with a typed edge. Constraints follow: both endpoints must exist, self-links of certain types are forbidden, and the set of valid link types is controlled. Query patterns follow from there: finding all references for a given document, finding backlinks, traversing two hops to discover indirectly related material.

The discipline is to model for real query patterns, not for theoretical elegance. A model that supports forward lookup efficiently but makes reverse lookup expensive or impossible is a model that was designed without asking how the data would actually be used.


## A Relationship in Code

The following sketch expresses the `Doc_Link` relationship entity in Nex:

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

This is minimal by design. It captures the three invariants that any link must satisfy — both endpoints must be identified, neither endpoint may reference itself, and the link type must be specified — and exposes a validation operation whose contract is explicit. The underlying document objects are referenced by identifier rather than by direct containment, which means the link's validity depends on those documents existing in the broader model, not just in this class. That dependency belongs in the integration-level invariants we write when assembling the full model.

The same structure generalizes: `Path` in the delivery network is a relationship entity between locations; typed interaction edges in the virtual world are relationship entities between object types.


## Four Ways Relationship Modeling Goes Wrong

**Encoding relationships as free-text fields.** When entity references are stored as untyped strings — names, descriptions, human-readable identifiers — the model has no way to enforce that the referenced entity actually exists or that two references to "the same thing" are in fact consistent. Joins become unreliable and traversals become impossible. The recovery is to model relationships as typed links with constrained endpoints.

**Ignoring reverse queries.** A relationship that is easy to traverse in one direction and expensive or impossible to traverse in the other was designed for only half of its intended use. Forward lookup and reverse lookup are both first-class access patterns, and both should be considered when the relationship is modeled. If the reverse query is expensive under the current model, that is information about the model, not about the query.

**Semantic drift in link types.** When the same link type accumulates subtly different meanings across different parts of the system, the relationship becomes uninterpretable: a traversal that was valid under the original meaning may be invalid under the acquired one, and there is no record of when or why the meaning changed. The recovery is to define a controlled taxonomy of link types at the model level and to enforce membership in that taxonomy when links are created.

**Hidden lifecycle rules.** When an entity is removed and the links that referenced it are not updated, the system accumulates broken references that will produce failures at some unpredictable future point. The lifecycle policy — cascade, orphan, or preserve — must be defined explicitly at the model level and verified by tests that exercise entity removal, not just entity creation.


## Quick Exercise

Choose one of the three running systems and construct a relationship matrix. For each relationship you identify, record: the two entity types it connects, the relationship type and cardinality, and one constraint rule that must always hold.

Then identify one reverse query that your model must support — a query that traverses the relationship in the direction opposite to how you first defined it. If that reverse query is expensive or ambiguous under your current model, the model needs refinement before implementation begins.


## Takeaways

- Relationships are first-class elements of a model: they have cardinality, direction, ownership, lifecycle coupling, and constraint rules.
- Getting relationships wrong produces silent integrity failures and invisible cascading behavior — more dangerous, in practice, than getting entities wrong.
- A relationship that carries metadata or supports multiple semantic types is usually better modeled as an entity in its own right.
- Model for real access patterns. A relationship that supports only forward traversal is only half a relationship.
- Lifecycle rules must be explicit. The choice between cascade, orphan, and preserve has consequences that compound over the lifetime of a system.


*Chapter 9 brings entities and relationships together into a complete data model, and examines the tradeoffs that arise when a model must serve multiple competing concerns simultaneously.*
