# Sets and Maps

Chapter 15 arrived at a tension: lists preserve order well but answer keyed questions poorly, and the dominant operations in many real systems are keyed. This chapter introduces the two structures designed to resolve that tension.

A **set** is a collection with no duplicates that answers one question efficiently: is this element present? A **map** is a collection of key-value pairs that answers one question efficiently: given this key, what value is associated with it? Neither preserves insertion order. Neither is organized for sequential traversal. Both are organized for direct access — the operation that lists cannot perform without scanning.

Understanding when to use each, and how to design the keys they depend on, is what separates systems that scale from systems that degrade.


## The Question Each Structure Answers

The choice between a set and a map follows directly from the question the operation asks.

A set answers: *is this element present?* Checking whether a document has already been scored in the current query, whether a location has been visited in the current traversal, whether an entity is already active in the current frame — these are membership questions, and a set answers them in constant time regardless of how many elements it contains. The same question answered by scanning a list takes time proportional to the list's size.

A map answers: *given this key, what is the associated value?* Fetching a delivery task by its identifier, retrieving document metadata by document ID, looking up an entity's current state by its entity ID — these are keyed retrieval questions, and a map answers them in constant time. The same question answered by scanning a list for a matching identifier takes linear time.

If the dominant operation on a collection is a membership question and the structure is a list, the cost is avoidable. If the dominant operation is keyed retrieval and the structure is a list, the cost is equally avoidable. The structures that avoid it exist precisely because these two questions are asked frequently enough in real systems to justify structures organized around answering them efficiently.


## Key Design Is a Correctness Decision

Sets and maps are only as useful as the keys they are built on. A poorly chosen key produces errors that are more insidious than a slow linear scan: the wrong record is updated, a membership check reports false when the element is actually present, a lookup returns a stale result. These failures are not obviously performance problems — they are correctness failures that trace back to a structural decision made before the code was written.

A good key has three properties.

**Stability.** A key that changes over the lifetime of the entity it identifies cannot be used as a map key. If the key changes, the entity can no longer be found at its old location in the map, and the new location is wherever the changed key happens to point — which may be an entry that belongs to a different entity entirely. Keys must be stable across the full lifetime of the entity they identify. For delivery tasks, the task identifier assigned at creation is stable; the task's current status is not. For documents, the document's unique ID is stable; its title is not.

**Uniqueness.** A key that does not uniquely identify its entity within the intended scope produces collisions: two different entities map to the same key, and one overwrites the other. Uniqueness is a property of the domain, not of the implementation, and it must be verified rather than assumed. If two delivery tasks can share an identifier — because the identifier is generated from the destination location rather than from a globally unique counter — then the identifier is not a valid key for a map whose entries must be distinct.

**Cheapness.** A key that is expensive to compute or compare is a key that makes every lookup expensive. A short, fixed-length identifier is cheaper to compare than a long variable-length string; a numeric identifier is cheaper than either. This is a performance consideration rather than a correctness one, but it compounds: in a system that performs millions of lookups per second, the cost of key comparison accumulates.


## Combining Structures for Different Operations

Real systems rarely face a choice between order and keyed access — they need both, for different operations. The correct response is not to choose the structure that satisfies the more important operation and accept degraded performance on the other. It is to use both structures, each for the operation it handles well.

Consider the requirement:

> *"For each query, avoid re-scoring the same document, and fetch metadata by id quickly."*

Two operations are embedded here. The first is a membership question: has this document already been scored in the current query? A set of seen document identifiers answers this in constant time. The second is a keyed retrieval question: given a document identifier, what is the associated metadata? A map from document identifier to metadata record answers this in constant time.

If result order matters for the output — if the ranked list of results must be returned in score order — a third structure is also needed: an ordered sequence that preserves the ranking. The set handles deduplication. The map handles metadata retrieval. The sequence handles ordered output. Each structure is used for exactly the operation it is good at.

This hybrid pattern — an ordered sequence for presentation, a map for identity-based access, optionally a set for membership control — is one of the most common structural patterns in real systems. It appears in the delivery system (a sequence of tasks for dispatch order, a map from task identifier to task for status lookup), in the knowledge engine (a ranked sequence of results for display, a map from document identifier to metadata for enrichment), and in the virtual world (an ordered sequence of entities for deterministic update, a map from entity identifier to state for targeted access).

The hybrid works only if there is one authoritative write path that keeps the structures synchronized. A system that updates the map without updating the sequence, or updates the sequence without updating the map, will produce inconsistencies that are difficult to diagnose because the failure appears at read time, far from where the write error occurred.


## A Map in Code

```nex
class Doc_Record
create
  make(doc_id: String, title: String) do
    this.doc_id := doc_id
    this.title := title
  end
feature
  doc_id: String
  title: String
invariant
  id_present: doc_id /= ""
  title_present: title /= ""
end

class Doc_Index
create
  make(
    k1: String,
    v1: Doc_Record,
    k2: String,
    v2: Doc_Record,
    k3: String,
    v3: Doc_Record
  ) do
    this.k1 := k1
    this.v1 := v1
    this.k2 := k2
    this.v2 := v2
    this.k3 := k3
    this.v3 := v3
  end
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
      result := doc_id = k1 or doc_id = k2 
	            or doc_id = k3
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

`Doc_Index` is a fixed-size map with three entries — minimal, but honest about the structure's properties. `contains` answers a membership question: is this identifier present among the known keys? It returns a boolean, not the associated value. `fetch_title` answers a retrieval question: given this identifier, what title is associated with it? It returns the title if the key is present and `"NOT_FOUND"` if it is not.

The explicit `"NOT_FOUND"` return is not defensive programming — it is part of the contract. A caller of `fetch_title` must handle the case where the key is absent. If the operation returned an empty string for both "this document has an empty title" and "this document does not exist," the caller would have no way to distinguish the two. Declaring a distinct miss behavior as part of the output contract is what makes the operation composable without forcing the caller to inspect the implementation.

The precondition on both operations — `doc_id /= ""` — rejects the empty string as a key. An empty string is not a stable, unique identifier; it is the absence of a value masquerading as one. Rejecting it at the boundary prevents a class of errors that would otherwise produce confusing behavior deep in the lookup logic.


## Sets and Maps in the Three Systems

The pattern across all three systems is consistent: membership control and keyed state access appear together, and each is handled by the structure suited to it.

In the delivery system, a set of locations visited during the current traversal prevents the route-finding algorithm from revisiting nodes it has already explored. A map from task identifier to task provides O(1) access when a robot requests its current assignment or when a status update must be applied to a specific task.

In the knowledge engine, a set of candidate document identifiers eliminates duplicates before scoring begins — a document that is reachable through multiple paths in the document graph should be scored once, not once per path. A map from document identifier to metadata provides the title, timestamp, and other fields needed to enrich the ranked results without re-fetching the full document.

In the virtual world, a set of active entity identifiers tracks which entities are present in the current frame, providing a fast answer to the question of whether a newly spawned entity identifier is already in use. A map from entity identifier to entity state provides direct access when a collision rule must be applied to two specific entities.

In every case, the set and the map are not replacements for the list that holds the ordered collection — they are complements to it, each handling the operation the list cannot.


## Four Ways Set and Map Usage Goes Wrong

**Poor key choice.** An unstable key — one derived from a property that changes — produces a map that loses entries as the domain evolves. A non-unique key — one derived from a property that two entities can share — produces a map where one entry silently overwrites another. Neither failure announces itself as a key design error. Both present as mysterious correctness failures that are difficult to reproduce and trace. The remedy is to define the key policy explicitly before building the map, verify that the chosen key is stable and unique within the intended scope, and record that decision in the model.

**Assuming order from a map or set.** Neither a map nor a set guarantees any particular traversal order. A system that relies on iterating a map and receiving entries in insertion order, or alphabetical order, or any other order, is depending on an implementation detail that is not part of the structure's contract. When the implementation changes — and map implementations vary across languages and platforms — the order changes with it. If ordered output is required, it must be maintained separately, in a structure whose purpose is to preserve order.

**Divergence between parallel structures.** A system that maintains both a list and a map for the same collection has two representations of the same data. If both are updated atomically through a single write path, they remain consistent. If different parts of the system update one but not the other — if a task is added to the map but not the sequence, or removed from the sequence but not the map — the representations diverge, and the system will produce results that are inconsistent in ways that are hard to diagnose. The remedy is to define a single authoritative write path and to verify consistency between structures in tests that exercise that path.

**Treating a missing key as impossible.** An operation that assumes a key will always be present, and crashes or returns a meaningless default when it is not, has made an assumption that the domain does not guarantee. Keys are absent for legitimate reasons: a task has been completed and removed, a document has not yet been indexed, an entity has been destroyed. The correct response to a missing key is a declared behavior — an explicit miss result, an error status, an optional return type — that the caller can depend on. A crash is not a declared behavior.


::: {.note-exercise}
**Quick Exercise**

Identify one place in your system where a collection is currently searched by a key-like property — a scan for an element with a particular identifier, a check for whether a particular item is already present. Redesign that operation using map or set semantics by specifying: the chosen key and a justification for its stability and uniqueness, the membership or retrieval operation and its contract, the miss behavior, and one invariant that must hold between this structure and any parallel structure that covers the same data.

Then compare the asymptotic cost of the original scan with the cost of the direct access operation. If the collection is currently small, estimate the input size at which the difference would first become visible in latency measurements.
:::

::: {.note-takeaways}
**Takeaways**

- A set answers membership questions in constant time. A map answers keyed retrieval questions in constant time. Each is organized for the operation it performs well and provides no advantage for operations it was not designed for.
- The choice between a list, a set, and a map is determined by the dominant operations on the collection, not by the type of data it contains.
- Key design is a correctness decision. An unstable or non-unique key produces correctness failures, not just performance ones.
- Order and direct access are different properties that often require different structures. The hybrid of an ordered sequence with a map for keyed access is the normal pattern for collections that must support both.
- A missing key is a legitimate outcome. Declaring a distinct miss behavior in the operation's contract is what makes the operation usable without forcing the caller to inspect its implementation.
:::



*Chapter 17 introduces trees — structures that organize data hierarchically and make ordered search efficient. Where sets and maps provide direct access by key, trees provide something different: efficient navigation through data that has natural structure, and efficient answers to range queries that neither lists nor maps can serve well.*
