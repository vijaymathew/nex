# Sorting the World

Chapter 19 showed that search strategy depends on how data is organized. Sorting is often what creates that organization. A sorted collection is not just a collection in a particular display order — it is a collection with a structure that algorithms can exploit. Binary search requires sorted input. Efficient merging of two result sets requires sorted input. Detecting duplicates in a large collection becomes a linear scan through sorted data rather than a quadratic comparison of every pair. In each case, sorting is not the end of the computation but the preparation that makes efficient computation possible downstream.

The distinction matters because it changes how we think about sorting's cost. The upfront cost of sorting a collection is not paid for the sort itself — it is paid for every subsequent operation that benefits from the ordered structure. When that structure is used many times, the upfront cost is amortized across all those uses. When it is used once or not at all, the sort was unnecessary work.


## What Sorting Guarantees

A sort operation, like any algorithm, is defined by its contract rather than by its implementation. The contract for a sorting operation has three parts.

**The comparison rule.** How are elements ordered relative to each other? For a collection of delivery tasks, the ordering might be by priority score — lower scores first, or higher scores first — or by creation time, or by some combination. The comparison rule must be total: for any two elements, it must be possible to determine which comes first, or that they are equivalent. A comparison rule that is undefined for some pairs of elements produces an algorithm that is correct on some inputs and undefined on others.

**The tie-break rule.** What happens when two elements are equivalent under the comparison rule? A sort that assigns the same priority to two tasks must decide which comes first in the output. One answer is that it does not matter — either order is acceptable, and the algorithm may choose either. Another answer is that the original relative order must be preserved — elements that were equal under the comparison rule must appear in the output in the same order they appeared in the input. This second property is called stability, and whether it is required depends on the semantics of the output, not on the implementation's convenience.

**The output invariant.** The output of a sort is a permutation of the input in which no element appears out of order under the comparison rule. This sounds obvious, but stating it explicitly as an invariant has practical value: it is a property that tests can verify mechanically, and it is the property that callers depend on when they use the sorted output to perform binary search, merge, or any other operation that requires sorted input.


## Stability as a Correctness Requirement

Stability is the property most often treated as optional and most often quietly required.

Consider the delivery task queue. Tasks are sorted by priority so that the robot dispatches the highest-priority tasks first. Two tasks with equal priority arrive in a defined sequence. The question of which one the robot dispatches first is not just a presentation preference — if the system has a fairness guarantee that equal-priority tasks are served in arrival order, stability is a correctness requirement. An unstable sort that reorders equal-priority tasks violates the fairness guarantee every time it runs.

The same situation arises in the knowledge engine. Search results with equal relevance scores may have a defined secondary ordering — by publication date, by author, by document identifier — that preserves a meaningful relationship the user depends on. An unstable sort that randomizes the order of equally-scored results is not producing equivalent output. It is producing different output on every run, and a system that produces different output from the same input for no declared reason is a system whose behavior cannot be understood or tested reliably.

The principle is this: when the order of equivalent elements matters to any caller, stability is part of the sort's contract. When no caller cares about the order of equivalent elements, stability is not required. Both conclusions must come from examining what callers actually need, not from an assumption that equal elements are interchangeable.


## Sort Strategy and Its Constraints

Sorting algorithms make different tradeoffs between time cost, memory cost, and stability, and the right choice depends on the constraints of the specific sorting problem.

A **comparison-based sort** derives element order entirely from pairwise comparisons. The theoretical minimum cost for any comparison-based sort is O(N log N) comparisons in the worst case — a result that follows from an information-theoretic argument about how many comparisons are needed to distinguish among all possible orderings of N elements. Any algorithm that does fewer comparisons in the worst case cannot be a correct general-purpose comparison sort. This lower bound is achieved in practice by algorithms like merge sort and heapsort.

**Simple pass-based sorts** — bubble sort, insertion sort, selection sort — are O(N²) in the worst case and are appropriate only when the collection is small or nearly sorted. Insertion sort in particular performs well on collections that are already almost in order, making it a useful component of hybrid strategies that use it for small subproblems. As a general-purpose sort for large collections, these algorithms are inappropriate — not because they are incorrect, but because their cost at scale is avoidable.

**Stability** eliminates some otherwise-correct algorithms from consideration. Heapsort is not stable. Merge sort is stable. Quicksort as typically implemented is not stable. When stability is required and memory is available, merge sort is the natural choice. When stability is required and memory is constrained, achieving it requires additional work. The constraint and its implications should be visible in the design, not buried in the implementation.

The practical advice for most systems is to use the standard library's sort — which is typically an optimized hybrid that handles the common cases well — unless domain constraints require custom behavior. The cases that require custom sorting are those where the comparison rule is non-standard, where stability matters in a way the library does not guarantee, or where the data has known structure (such as near-sortedness) that a specialized algorithm can exploit.


## From Requirement to Sort Design

Consider the requirement:

> *"Return delivery tasks ordered by priority, preserving insertion order for ties."*

**Step 1: Define the comparison key.** The primary ordering criterion is priority score. What "ordered by priority" means — whether a high priority score means a task should come first or last — must be stated explicitly. For a delivery system where lower numbers represent higher urgency, "ordered by priority" means ascending order.

**Step 2: Define tie-break behavior.** Equal-priority tasks must preserve insertion order. This is a stability requirement: the sort must be stable so that tasks inserted earlier appear before tasks inserted later when their priority scores are equal. An unstable sort would satisfy the primary ordering criterion and violate the tie-break requirement.

**Step 3: Choose a baseline sort.** For an early implementation with a small number of tasks, a simple stable sort is appropriate. Simple does not mean incorrect — it means the implementation prioritizes clarity over optimization at a scale where the performance difference does not yet matter.

**Step 4: State the correctness checks.** The output is non-decreasing by priority score: no task in the output appears after any task with a strictly lower priority score. Equal-priority tasks appear in the output in the same relative order as they appeared in the input. Both of these are mechanical properties that a test can verify on any input.

**Step 5: Define the scale transition.** When the task collection grows large enough that the sort's cost appears in latency measurements, the baseline sort should be replaced by an optimized stable sort — most likely the standard library's stable sort. The output contract does not change; only the implementation does.


## A Sort Operation in Code

```nex
class Task_View
feature
  id: String
  priority: Integer
  arrival: Integer
invariant
  id_present: id /= ""
  arrival_non_negative: arrival >= 0
end

class Sort_Example
feature
  t1: Task_View
  t2: Task_View
  t3: Task_View

  sorted_ids_by_priority(): String
    do
      -- Teaching-sized fixed comparison network for three items.
      let a: Task_View := t1
      let b: Task_View := t2
      let c: Task_View := t3

      if a.priority > b.priority then
        let tmp: Task_View := a
        a := b
        b := tmp
      end

      if b.priority > c.priority then
        let tmp2: Task_View := b
        b := c
        c := tmp2
      end

      if a.priority > b.priority then
        let tmp3: Task_View := a
        a := b
        b := tmp3
      end

      result := a.id + " -> " + b.id + " -> " + c.id
    ensure
      non_empty: result /= ""
    end
end
```

`Task_View` carries an `arrival` field alongside the priority score. In the sketch, `arrival` is present in the invariant but not yet used in the sorting logic — a deliberate choice that makes the gap between the current implementation and the full requirement visible. The sort orders tasks by priority but does not yet break ties by arrival order. Adding stability means modifying the comparisons to include `arrival` as a secondary key when `priority` values are equal. The postcondition `non_empty` is weak — a stronger postcondition would assert the ordering invariant directly. For a production implementation, that stronger assertion is worth writing and testing explicitly.

The three-swap comparison network is a sorting network for exactly three elements: it correctly sorts any combination of three values using exactly three comparisons. It is not a general sorting algorithm — it does not scale beyond three elements — but for a teaching sketch of fixed size it has the virtue of making every comparison explicit. A reader can trace the execution for any input and verify that the output is correctly ordered. That traceability is what makes it useful here.


## Sorting in the Three Systems

In the delivery system, the task queue must be sorted by priority before dispatch. The stability requirement comes from the fairness guarantee: equal-priority tasks should be dispatched in the order they arrived, so that no task waits indefinitely while equal-priority tasks that arrived later are repeatedly dispatched ahead of it. This is a correctness requirement on the sort, not a presentation preference.

In the knowledge engine, search results must be sorted by relevance score before being returned to the caller. Results with equal scores may need a secondary ordering — by recency, by document identifier, by some other stable criterion — so that the same query reliably produces the same output. An output that varies non-deterministically across runs on the same input cannot be tested or debugged. Stability with a deterministic secondary key is what makes the output reproducible.

In the virtual world, entities must be processed in a deterministic order each tick. The sort that establishes this order must be stable across runs: given the same set of entities with the same states, the processing order must be the same. If the sort is not stable, the simulation is not reproducible — two runs of the same scenario may produce different outcomes, which makes the system impossible to test and impossible to debug.

In all three systems, sorting sits between raw data and high-value operations. The sort's cost is paid once; the benefits of structured output are collected by every operation that follows.


## Three Ways Sorting Goes Wrong

**An undefined comparison rule.** A sort that relies on implicit or inconsistent comparisons produces output that varies across runs or implementations. The same collection sorted twice may produce different results if the comparison rule is not total, if it depends on mutable state, or if it is implemented differently in two places. The comparison rule must be defined explicitly — as a function with a documented contract — and it must be the same everywhere the sort is applied.

**Unexamined stability requirements.** A sort that is not stable will reorder equal elements arbitrarily. If any caller depends on the order of equal elements — for fairness, for reproducibility, for secondary sorting — the instability is a correctness failure. Stability requirements are easy to overlook because they are invisible when all elements are distinct. They become visible only when two elements are equal, which may not happen in early testing. Asking the stability question explicitly — for each sorted collection, does the order of equal elements matter to any caller? — is the discipline that prevents the failure.

**Re-sorting too frequently.** A full sort of a large collection is an O(N log N) operation. Performing it in a hot loop — once per incoming event, once per request, once per tick — when the collection changes only occasionally is work that compounds quickly. The alternatives are to maintain a sorted order incrementally, inserting new elements in sorted position rather than re-sorting the full collection; to batch updates and sort once per batch; or to use a data structure that maintains order intrinsically. The choice depends on the relative frequency of insertions and reads.


## Quick Exercise

Choose one ordered output in your system and define its sort contract completely: the primary comparison key and the direction of ordering, the tie-break rule and whether it constitutes a stability requirement, the output invariant that a test could verify mechanically, and the current sort frequency relative to how often the underlying collection changes.

Then write one test case using two elements with equal primary keys that verifies the tie-break behavior. If the test cannot be written without knowing the implementation, the tie-break rule is not yet explicit enough to be part of the contract.


## Takeaways

- Sorting creates structure that downstream operations exploit. Its cost is an investment in the efficiency of everything that follows.
- A sort's contract has three parts: the comparison rule, the tie-break rule, and the output invariant. All three must be explicit.
- Stability is a correctness requirement when the order of equal elements matters to any caller. It is not an implementation detail.
- The right sort strategy is determined by collection size, stability requirements, memory constraints, and whether the input has known structure. Defaulting to the standard library's sort is appropriate for most cases.
- Re-sorting a large collection more often than it changes is avoidable cost. Define the sort frequency alongside the sort strategy.


*Chapter 21 turns from sorting data to traversing structures — the algorithms that systematically visit every node in a tree or graph. Traversal is the basis for search, analysis, and transformation of structured data, and the order in which nodes are visited determines what the traversal can compute.*
