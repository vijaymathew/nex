# Searching for What Matters

Part IV organized data into structures suited to different operations. Part V asks what algorithms bring those structures to life. We begin with search, because nearly every system spends significant time answering a single question in one form or another: where is the thing we need?

That question sounds simple. The answer is not. The right search strategy depends on how the data is organized, how frequently the search occurs, and what the system must do when the thing being sought is not there. A search algorithm chosen by habit rather than by these factors will work correctly for small inputs and become a performance problem as the system grows. More subtly, a search algorithm whose failure behavior is undefined will work correctly when things are found and produce confusion when they are not.


## Four Search Strategies

Different data organizations admit different search strategies, and the choice between them is determined by the properties of the data and the cost the system can afford.

**Linear search** examines elements one at a time from start to finish until a match is found or the collection is exhausted. It requires no assumptions about the organization of the data — the elements may be in any order, and the search will find a match if one exists. Its cost grows linearly with the size of the collection: a collection twice as large requires twice as many comparisons in the worst case. Linear search is appropriate when the collection is small, when it is unsorted and sorting it would cost more than the search saves, or when search is rare relative to modification — maintaining a sorted order for a collection that is searched infrequently and modified constantly is work that does not pay off.

**Binary search** repeatedly halves the remaining search space by comparing the target against the middle element of the current range. If the target is smaller, the search continues in the lower half; if larger, in the upper half; if equal, the search terminates with a match. Each comparison eliminates half of the remaining candidates, producing a cost that grows logarithmically: a collection of a million sorted elements requires at most twenty comparisons. Binary search requires that the collection be sorted — this is a precondition, not a performance hint. Applied to an unsorted collection, binary search produces incorrect results, not slow ones. The precondition must be enforced, not assumed.

**Map and set lookup** provides constant-time access when the data has been organized around a key. Given a task identifier, a map from identifier to task returns the result in constant time regardless of the total number of tasks. This is not faster binary search — it is a fundamentally different operation that does not depend on sorting or comparison at all. It requires that the data have been organized into the map structure in advance, and it requires a stable, unique key. When these conditions hold, keyed lookup is the preferred strategy for any collection where the dominant operation is identity-based retrieval.

**Graph and tree traversal** finds elements by navigating the connections between them. A depth-first or breadth-first traversal discovers nodes reachable from a starting point; a binary search tree traversal finds an element whose key satisfies an ordering criterion; a shortest-path algorithm finds the element (or path) that optimizes some quality measure. These are the right strategies when the data has genuine structure — hierarchy, network connectivity — and when the answer to the search question depends on that structure rather than on a single key comparison.


## Correctness Before Speed

Search strategy discussions tend to focus on performance — linear versus logarithmic versus constant. Performance matters, but it is secondary to a prior requirement: the search algorithm must define what it promises and what it does when the promise cannot be kept.

A search operation has a correctness contract with two parts. The match semantics specify what counts as a successful result: exact identifier equality, a value within a range, a document that satisfies a relevance criterion. Without explicit match semantics, different implementations will answer the same question differently, and the difference will not be visible until a caller is surprised by a result it did not expect.

The miss semantics specify what the algorithm returns when no match exists. This is not an edge case — it is half the search space. A system that searches for a task by identifier will sometimes find it and sometimes not. Both outcomes must be declared. An explicit miss result — `NOT_FOUND`, an option type with a defined absent case, a status code — tells the caller unambiguously what happened. An empty return, a null, or a zero tells the caller that something was returned but provides no information about whether it means "not found," "found but empty," or "error."

The input assumptions specify what the algorithm requires to operate correctly. Binary search requires a sorted collection. Keyed lookup requires a valid, non-empty key. Graph traversal with cycle detection requires a visited set. These are preconditions, and they belong in the algorithm's contract. A caller that violates a precondition is not guaranteed any particular behavior; a caller that satisfies it is guaranteed the declared output. Writing the preconditions down — in the code, not just in documentation — is what makes the contract enforceable.


## From Requirement to Search Design

Consider the requirement:

> *"Given a task identifier, return the current status quickly and safely."*

The word "safely" is doing more work than it appears. It implies that missing tasks must be handled gracefully, not that the algorithm should avoid crashing on valid inputs.

**Step 1: Define match semantics.** A match is exact identifier equality. A task whose identifier is equal to the query identifier is the result; any other task is not. There is no partial match, no prefix search, no relevance ranking. The match criterion is simple and must be stated explicitly because it determines which search strategy is appropriate.

**Step 2: Define miss behavior.** If no task with the given identifier exists in the collection, the result is `NOT_FOUND` — a declared status value, not null, not an empty status string, not an exception. The caller of this operation must handle both the found case and the not-found case; the contract makes both cases explicit.

**Step 3: Assess the data's organization.** If tasks are stored in an unordered sequence, linear search is the only option without additional structure. If tasks are stored in a map from identifier to task, keyed lookup is available. If the collection is small and search is infrequent, linear search on the sequence may be acceptable even as a long-term design. If the collection is large or growing, or if identifier lookup is a hot path, the map is the right structure and the question of search strategy is already answered by the structure choice.

**Step 4: Choose the initial implementation.** For an early version of the system with a small number of tasks, linear search over an ordered sequence is a defensible starting point. It is simple, correct, and easy to test. The cost at this scale is negligible.

**Step 5: Define the upgrade trigger.** The linear search should be replaced when identifier lookup appears as a significant contributor to latency on the hot path. This trigger should be stated explicitly — a collection size, a latency measurement, a throughput target — so that the replacement is a scheduled decision rather than an emergency response to a performance incident.


## A Search Operation in Code

```nex
class Search_Result
create
  make(status: String, steps: Integer) do
    this.status := status
    this.steps := steps
  end
feature
  status: String
  steps: Integer
invariant
  status_present: status /= ""
  steps_non_negative: steps >= 0
end

class Task_Search
feature
  id1: String
  st1: String
  id2: String
  st2: String
  id3: String
  st3: String
  id4: String
  st4: String

  linear_find(task_id: String): Search_Result
    require
      id_present: task_id /= ""
    do
      let final_status: String := "NOT_FOUND"
      let steps: Integer := 1
      if task_id = id1 then
        final_status := st1
      elseif task_id = id2 then
        steps := 2
        final_status := st2
      elseif task_id = id3 then
        steps := 3
        final_status := st3
      elseif task_id = id4 then
        steps := 4
        final_status := st4
      else
        steps := 4
      end
      result := create Search_Result.make(
        final_status,
        steps
      )
    ensure
      bounded_steps: result.steps >= 1 
	                 and result.steps <= 4
    end
end
```

`Search_Result` carries two fields and two invariants. The `status` field holds either the found task's status or `NOT_FOUND`. The `steps` field counts how many comparisons the search required. The invariants enforce that both fields are always in a valid state: `status` is never empty, and `steps` is never negative.

`linear_find` initializes the result to `NOT_FOUND` before beginning the search. This initialization is not an optimization — it is the guarantee that the miss case is handled correctly regardless of which branch the control flow takes. If none of the four comparisons produce a match, the result is already correct without any additional code in the else branch. The postcondition confirms that the number of steps is bounded between one and four — a measurable statement about the algorithm's behavior on this particular collection size that a test can verify directly.

The `steps` field deserves particular attention. A search algorithm that returns only a result status produces a black box: the caller knows what was found, but has no information about how hard the search was. An algorithm that also returns a step count makes its cost observable and testable. For the worked design path above, this observability is what makes it possible to detect when linear search is becoming a bottleneck: the steps count grows with the collection size, and when it grows large enough to affect latency, the upgrade trigger fires.


## Search in the Three Systems

In the delivery system, searching for a task by identifier is a frequent operation: robots request their assignments, status updates target specific tasks, and dispatch logic queries task states. The collection of active tasks grows as the system scales. Linear search over a sequence of tasks will degrade at scale; a map from task identifier to task, introduced in Chapter 16, is the appropriate structure when identifier lookup is the dominant operation.

In the knowledge engine, search operates at two levels. The first level locates candidate documents by token, tag, or identifier — a structured search over index entries. The second level ranks the candidates by relevance — a scoring and comparison over a much smaller set. These are distinct operations with distinct performance requirements, and they warrant distinct strategies. The first benefits from indexed structure; the second operates on a small enough candidate set that a linear scan over ranked scores is appropriate.

In the virtual world, locating entity state by identifier is a per-tick operation: every frame may require fetching the current state of specific entities to apply interaction rules or check collision. The frequency of this operation — once per entity per tick, potentially thousands of times per frame — makes it the most demanding search workload of the three systems. Keyed lookup in a map organized by entity identifier is the only strategy that scales.

In all three systems, the search operation that looks trivial at small scale is the one most likely to become the first bottleneck at large scale.


## Three Ways Search Design Goes Wrong

**One strategy everywhere.** A codebase that uses linear search for all collection queries has made a default choice that is correct at small scale and incorrect at large scale. The problem is not linear search itself — it is the absence of a decision about strategy. When a team has never asked which operations are frequent, which collections are large, and which access patterns are key-based, they cannot know which searches will degrade as the system grows. The remedy is to classify the operation profile of each collection before choosing a strategy.

**Undefined miss semantics.** A search that returns an empty string for both "task found with empty status" and "task not found" forces every caller to add defensive logic that guesses which situation applies — or to skip that logic, accept the ambiguity, and produce incorrect behavior. The ambiguity compounds across callers: different parts of the system will interpret the same return value differently, and the inconsistency will not be visible until a real miss produces an unexpected result in a caller that assumed all returns were valid. The remedy is to declare distinct return values for all outcomes before writing the implementation.

**Violated preconditions.** Binary search applied to an unsorted collection does not return slow results — it returns wrong ones. A keyed lookup called with an empty string does not degrade gracefully — it returns a result that was never intended. Preconditions are not optional documentation. They are the boundary conditions under which the algorithm's guarantees hold, and violating them voids the guarantee. Enforcing preconditions in the code — with require clauses, assertions, or type constraints — is what makes the contract reliable rather than advisory.


::: {.note-exercise}
**Quick Exercise**

Choose one search operation in your system and document it completely with five components: the match rule that defines what counts as a successful result, the miss rule that defines the explicit return value when no match exists, the current strategy and its asymptotic cost, the expected input size range in production, and the trigger condition at which the current strategy should be reconsidered.

Then write the precondition and postcondition for that operation. If the postcondition cannot distinguish the found case from the not-found case without reading the implementation, the miss semantics are not yet explicit.
:::

::: {.note-takeaways}
**Takeaways**

- Search strategy is a design decision determined by data organization and operation frequency. Choosing by habit produces systems that work at small scale and degrade at large scale.
- Match semantics, miss semantics, and input preconditions are part of every search algorithm's contract. An algorithm without explicit miss semantics is not complete.
- Linear search, binary search, keyed lookup, and structural traversal are not interchangeable techniques for the same problem. Each is the right choice for a specific combination of data organization and operation profile.
- The search operation that looks trivial at small scale is often the first bottleneck at large scale. Define the upgrade trigger before the bottleneck arrives.
:::



*Chapter 20 examines sorting — the operation that turns unordered data into a form where faster search, efficient merge, and reliable comparison become possible. Understanding sorting as a prerequisite for other algorithms, rather than as a standalone operation, is what connects it to the system design concerns of this part.*
