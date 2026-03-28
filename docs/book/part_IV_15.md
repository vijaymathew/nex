# Lists and Sequences

Part III developed the tools for algorithmic thinking: what an algorithm is, how to decompose one, how to design recursive algorithms safely, and how to reason about cost. Part IV asks a different question. Algorithms do not operate in the abstract — they operate on data that has been organized in some particular way, and the organization chosen determines which operations are cheap and which are expensive. The same algorithm can be fast or slow depending entirely on how its data is arranged.

We begin with lists and sequences, because they are the structure most teams reach for first. This is not always wrong — lists are genuinely the right choice for certain problems — but it is often unreflective. A team that uses lists because they are familiar, rather than because they fit the operations the system requires, will eventually discover the mismatch through degraded performance. The goal of this chapter is to make that choice conscious: to understand what lists are good at, where they become expensive, and how to recognize early which situation applies.


## What a List Is

A list is an ordered collection: a sequence of elements in which position is meaningful and preserved. The order may reflect the sequence in which elements were added, a logical ranking, a timeline of events, or a processing priority. Whatever its source, the order is part of the list's content — it is not an artifact of the implementation but a fact about the data that the structure is responsible for maintaining.

Order gives lists their characteristic strengths. Iterating from start to finish is natural and efficient. The first element, the last element, and the next element after any given one are all immediately accessible. Lists represent timelines well, ranked outputs well, queues and logs well — any domain where the primary question is *what comes next?* or *in what order did these things occur?*

Order also defines the limits of a list's usefulness. A list organizes elements by position. It has no mechanism for organizing them by content. Finding an element by a property — by its identifier, by a key — requires examining elements one at a time until the matching one is found. For a list of ten elements, this is inconsequential. For a list of ten million, it is the dominant cost of every lookup operation, and it grows linearly with the size of the collection.


## The Cost of What Lists Cannot Do

Three operations become expensive on lists as collections grow:

**Membership testing.** Determining whether a particular element is present in a list requires, in the worst case, examining every element. There is no shortcut available from the list's structure alone. If membership testing is a frequent operation, a list is the wrong structure.

**Lookup by key.** Finding the element with a particular identifier requires a linear scan. If the element is near the front, the scan is short; if it is near the back or absent, the scan is long. The expected cost of a lookup grows proportionally with the size of the list.

**Insertion and deletion in the middle.** Adding or removing an element at an arbitrary position requires shifting every element after it. For a list where insertions and deletions are frequent and positions are arbitrary, this cost accumulates quickly.

These are not obscure operations. They are the dominant operations in a large class of systems. A system that stores delivery tasks in a list and repeatedly looks up tasks by identifier will degrade as the number of tasks grows. The degradation is predictable — it follows directly from the structure chosen — but it will arrive as a surprise if the structure was chosen without asking what operations the system actually performs.

This is the first scaling failure many systems encounter. It is also one of the most avoidable.


## Choosing a Structure by Its Operations

The right question to ask before choosing a data structure is not *what kind of thing am I storing?* but *what operations will I perform on it most often?* The answer to the second question determines which structure will serve the system well.

For the delivery system, two operations on task collections matter. The first is displaying active tasks in the order they were created or dispatched — an order-sensitive operation that a list handles naturally. The second is fetching a specific task by its identifier when a robot requests its assignment or when a status update arrives — a key-sensitive operation that a list handles poorly.

The mismatch between these two operations and a single list structure is the central tension of this chapter. A list satisfies the first operation directly. It satisfies the second only at a cost that grows with the number of tasks. At small scale, this cost is invisible. As the system grows, it becomes the bottleneck.

The resolution — keeping a list for ordered display and introducing a separate index for keyed lookup — is the subject of Chapter 17. This chapter holds the tension open deliberately, because understanding why the list alone is insufficient is a prerequisite for understanding what the index adds.


## A Sequence in Code

```nex
class Task
create
  make(id: String, status: String) do
    this.id := id
    this.status := status
  end
feature
  id: String
  status: String
invariant
  id_present: id /= ""
  valid_status:
    status = "PENDING" or
    status = "IN_TRANSIT" or
    status = "DELIVERED" or
    status = "FAILED"
end

class Task_Sequence
create
  make(t1: Task, t2: Task, t3: Task) do
    this.t1 := t1
    this.t2 := t2
    this.t3 := t3
  end
feature
  t1: Task
  t2: Task
  t3: Task

  find_by_id(task_id: String): String
    require
      id_present: task_id /= ""
    do
      if t1.id = task_id then
        result := t1.status
      elseif t2.id = task_id then
        result := t2.status
      elseif t3.id = task_id then
        result := t3.status
      else
        result := "NOT_FOUND"
      end
    ensure
      declared_result:
        result = "PENDING" or
        result = "IN_TRANSIT" or
        result = "DELIVERED" or
        result = "FAILED" or
        result = "NOT_FOUND"
    end

  ordered_ids(): String
    do
      result := t1.id + " -> " + t2.id 
	            + " -> " + t3.id
    ensure
      non_empty: result /= ""
    end
end
```

`Task_Sequence` is a fixed-size sequence of three tasks — intentionally minimal, but structurally honest about what a list provides. `ordered_ids` reflects the list's natural strength: it returns the elements in their defined order, and the cost of doing so is simply the cost of visiting each element once. `find_by_id` reflects the list's natural weakness: it examines each task in turn until it finds a match or exhausts the sequence. For three tasks, this is trivial. For three thousand, the same structure and the same operation would examine up to three thousand tasks per lookup.

The postcondition on `find_by_id` matters. It guarantees that the result is always one of five declared values — the four valid status strings or `NOT_FOUND` — and never an undefined or ambiguous return. This is the contract that makes `find_by_id` composable: callers can depend on the declared output without inspecting the implementation. When the implementation is later replaced by one that uses an index, the contract remains the same, and callers need not change.


## Lists in the Three Systems

In the delivery system, active tasks in creation or dispatch order are a natural list. The order is meaningful — it reflects when tasks were created and may determine which task a robot picks up next — and sequential iteration over the list is a real operation.

In the knowledge engine, search results ranked by relevance score are a natural list. The ranking is the result's most important property for the user: the first result is the most relevant, and the user reads down from there. The list preserves and expresses that ranking directly.

In the virtual world, the order in which entities are updated each tick is a natural list. As established in Chapter 10, deterministic update order is a correctness requirement, not a performance detail: a simulation that processes entities in different orders on different runs is not reproducible. A list makes the update order explicit and stable.

In all three cases, the list earns its place by providing order. In all three cases, the question of whether another structure is also needed — for keyed lookup, for membership testing, for efficient modification — depends on what other operations the system must support. The list is the right answer to the ordering question. It may not be the complete answer to all questions.


## Three Ways List Usage Goes Wrong

**Using lists for everything.** A list is the simplest data structure to reach for, and simplicity has real value. But a system that uses lists for all its collections, regardless of the operations those collections must support, will eventually produce a profile dominated by linear scans. The remedy is to begin by identifying the dominant operations on each collection, not by choosing a structure. A collection whose dominant operations are order-sensitive deserves a list. One whose dominant operations are key-sensitive deserves something else.

**Confusing order with identity lookup.** Order and identity are different properties of a collection, and they are often handled by different structures. A system that uses a list to answer the question "what is the status of task T42?" is using an order-preserving structure to answer a question that does not involve order. The list preserves something the operation does not need and cannot efficiently provide what the operation does need. The remedy is to keep the list for operations where order matters and to introduce a separate structure — a map, an index — for operations where identity matters.

**Silent duplicate identity.** A list that accepts any element at append time, without checking whether an element with the same identifier already exists, will accumulate duplicates if the calling code ever appends the same logical entity twice. Depending on how `find_by_id` is implemented, it will find the first match, the last match, or all matches — and different callers may have different assumptions about which they will receive. The remedy is to define a uniqueness policy for each collection and to enforce it at the point of insertion.


::: {.note-exercise}
**Quick Exercise**

Choose one collection in your system — a collection of tasks, documents, objects, or any other entity type — and answer four questions about it: what are the three most frequent operations performed on it, is each operation primarily order-sensitive or key-sensitive, how often does each operation occur relative to the others, and is a list alone sufficient for the dominant operations?

Then write one contract for the most frequent operation — a precondition that rejects invalid inputs and a postcondition that declares what valid output looks like. If the contract cannot be stated without reference to the implementation, the operation's interface is not yet well-defined.
:::

::: {.note-takeaways}
**Takeaways**

- A list is an order-preserving structure. Its strength is sequential access; its weakness is keyed lookup.
- The right data structure for a collection is determined by the operations that collection must support, not by the type of data it contains.
- Order-sensitive operations and key-sensitive operations are different in kind. A single list often cannot satisfy both efficiently at scale.
- The cost of a linear scan is invisible at small scale and dominant at large scale. Recognizing which operations will become bottlenecks before they do is a design skill.
- Contracts on collection operations make later structure changes safe: when the implementation changes, the contract remains, and callers do not need to change.
:::



*Chapter 17 introduces sets and maps — the structures that make membership testing and keyed lookup efficient. Where this chapter identified the cost of what lists cannot do, Chapter 17 introduces the structures designed to do exactly those things.*
