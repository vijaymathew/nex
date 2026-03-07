# Finding the Best Path

Chapter 21 drew the line between reachability and path quality. Traversal answers whether a destination can be reached at all. Best-path algorithms answer a harder question: among all paths that reach the destination, which one is optimal?

The word *optimal* has no meaning without a cost model. A path that minimizes the number of hops is not necessarily the path that minimizes travel time, and neither is necessarily the path that minimizes risk or energy consumption. Different objectives require different algorithms, and an algorithm that is correct under one cost model may produce wrong answers under another. This is the central discipline of best-path design: the cost model must be declared before the algorithm is chosen, because the algorithm's correctness guarantees are relative to the cost model, not absolute.


## The Cost Model

A cost model assigns a numeric value to each edge in the graph and defines how those values are combined along a path. The total cost of a path is a function of the costs of its constituent edges — most commonly their sum, but potentially their maximum, their product, or some other combination depending on what the costs represent.

The choice of combination function has consequences for which algorithms apply. The most important of these consequences concerns the assumption of non-negative edge costs. Dijkstra's algorithm, the canonical best-path algorithm for weighted graphs, requires that all edge costs be non-negative. Given this assumption, it guarantees that when it finalizes a node — when it removes it from the frontier and commits to its current cost as the minimum — no future path can improve on that cost. This guarantee depends entirely on non-negativity: if negative edges exist, a path that was suboptimal at one moment may become optimal when a negative edge is discovered later, and the finalization decision was premature.

If edge costs can be negative, a different algorithm is required. If edge costs are all equal — if the graph is unweighted — breadth-first search from Chapter 21 finds the minimum-hop path more efficiently than Dijkstra. The algorithm choice follows from the cost model, and the cost model must be determined first.


## Priority-Driven Frontier Management

Best-path search maintains a frontier of candidate nodes — nodes that have been discovered but not yet finalized — ordered by their current best-known cost from the source. At each step, the algorithm removes the node with the lowest current cost from the frontier, finalizes it, and expands its neighbors: for each unfinalized neighbor, if the path through the current node offers a lower cost than any previously known path to that neighbor, the neighbor's known cost is updated and it is added (or re-positioned) in the frontier.

The frontier is a priority queue: a structure that allows efficient extraction of the minimum-cost element. The cost of the algorithm is determined by how many times nodes are added to and removed from the frontier, and how efficiently each operation runs. For a graph with V nodes and E edges, the total number of frontier operations is at most O(E), and if the priority queue supports extraction in O(log V), the total cost of the algorithm is O(E log V).

Three design questions arise from this structure.

**What is the frontier ordering key?** For minimum-cost paths, the key is the current best-known total cost from the source to the node. For A* search — a variant of Dijkstra that uses a heuristic estimate of remaining cost to guide the frontier ordering — the key is the sum of the known cost so far and the estimated cost to the destination. The heuristic guides the search toward the destination, reducing the number of nodes explored when the estimate is accurate. The heuristic must be admissible — it must never overestimate the true remaining cost — for the algorithm to remain correct.

**How are ties handled?** When two nodes have equal frontier costs, the order in which they are processed may affect which path is returned in the case of multiple optimal paths. A consistent tie-break rule — by node identifier, by arrival order, by some domain-specific criterion — makes the output deterministic. An inconsistent one produces different results on different runs.

**When is a node finalized?** Under Dijkstra's algorithm with non-negative edge costs, a node is finalized the first time it is removed from the frontier. Its cost at that moment is the minimum possible cost from the source. This finalization rule is what allows the algorithm to terminate without exploring all possible paths; it relies on non-negativity to guarantee that no unprocessed path can improve on the finalized cost.


## Local Optimality Does Not Imply Global Optimality

A greedy algorithm makes locally optimal choices at each step without reconsidering earlier decisions. In some problems, local optimality implies global optimality — the greedy choice is always the right one. In best-path problems on general weighted graphs, it does not.

Consider a graph where the cheapest edge from the source leads to a node with only expensive outgoing edges, while a slightly more expensive initial edge leads to a node from which a cheap path to the destination exists. A greedy algorithm that always expands the cheapest available edge from the current node will commit to the cheap initial edge and arrive at an expensive total path. Dijkstra's algorithm avoids this error by maintaining a frontier over the entire set of discovered nodes — not just the neighbors of the most recently expanded node — and always expanding the node with the globally lowest known cost, wherever it is in the graph.

The lesson generalizes: local cheapness is not global optimality. An algorithm that makes irrevocable local choices, without maintaining the possibility of backtracking to less-promising-looking paths, will fail on inputs where the globally optimal path requires temporarily accepting a locally suboptimal edge. The priority-driven frontier is precisely what prevents this: every discovered node remains a candidate until it is finalized, and finalization is deferred until no remaining path could improve on the current cost.


## From Requirement to Best-Path Design

Consider the requirement:

> *"Route from A to D minimizing travel cost."*

**Step 1: Define edge costs.** Each edge has a non-negative integer cost. Negative costs are not permitted. This is a precondition on the graph structure, not just a performance hint — it is the assumption that makes Dijkstra's algorithm correct.

**Step 2: Define path validity.** A valid path is a connected sequence of edges from the source to the destination, using only edges that currently exist and are traversable. The path cost is the sum of the individual edge costs. The algorithm must not return a path that includes a missing or blocked edge, regardless of whether such a path would be optimal.

**Step 3: Define failure behavior.** If no path exists from source to destination — if the destination is not reachable from the source under the current edge configuration — the result is `UNREACHABLE`. This is a declared outcome, not a degenerate case to be handled by returning a path with zero edges or infinite cost.

**Step 4: Choose the algorithm.** Non-negative edge costs and a minimum-sum-cost objective: Dijkstra's algorithm applies and its correctness guarantees hold. If the costs were all equal, BFS would be more efficient. If the costs could be negative, a different algorithm would be required. The choice follows from the cost model.

**Step 5: Define a tie-break rule.** When two paths to the destination have equal total costs, the algorithm must return one of them consistently. The tie-break criterion — lexicographic order of node identifiers, the path that uses fewer edges, or any other stable criterion — must be declared so that the output is deterministic.


## A Best-Path Algorithm in Code

```nex
class Path_Result
feature
  status: String
  path: String
  total_cost: Integer
invariant
  status_present: status /= ""
  non_negative_cost: total_cost >= 0
end

class Best_Path_Example
feature
  a_b: Integer
  b_d: Integer
  a_c: Integer
  c_d: Integer
  a_d: Integer

  best_a_to_d(): Path_Result
    require
      non_negative_costs:
        a_b >= 0 and b_d >= 0 and a_c >= 0 and c_d >= 0 and a_d >= 0
    do
      let r: Path_Result := create Path_Result
      let abd: Integer := a_b + b_d
      let acd: Integer := a_c + c_d

      if a_d <= abd and a_d <= acd then
        r.status := "FOUND"
        r.path := "A->D"
        r.total_cost := a_d
      elseif abd <= acd then
        r.status := "FOUND"
        r.path := "A->B->D"
        r.total_cost := abd
      else
        r.status := "FOUND"
        r.path := "A->C->D"
        r.total_cost := acd
      end

      result := r
    ensure
      known_status: result.status = "FOUND" or result.status = "UNREACHABLE"
    end
end
```

The precondition on `best_a_to_d` asserts non-negativity for all five edge costs. This is not defensive programming — it is the statement of the assumption under which the algorithm is correct. A caller that provides a negative edge cost has violated the contract, and the algorithm's guarantees no longer hold.

The body computes the costs of the three candidate paths — `A→D` directly, `A→B→D`, and `A→C→D` — and selects the minimum. The comparisons encode the priority-driven selection logic in a form suitable for a fixed three-path graph: the path with the lowest total cost is selected, with ties broken in favor of the direct path and then the first intermediate path. In a full implementation over an arbitrary graph, these comparisons would be replaced by a priority queue that maintains the frontier and a relaxation step that updates known costs as new paths are discovered. The contract — non-negative preconditions, declared output status, non-negative total cost — would remain exactly the same.

`Path_Result`'s invariant `non_negative_cost` is a consequence of the precondition: if all edge costs are non-negative and path cost is the sum of edge costs, no valid path can have a negative total cost. The invariant makes this consequence explicit and checkable independently of the algorithm that produced the result.


## Best-Path in the Three Systems

In the delivery system, route selection minimizes travel cost over a weighted road graph. The edge weights may represent distance, expected travel time, or a combination that accounts for traffic and road conditions. The non-negativity assumption holds naturally — negative travel time is not a domain concept. Blocked paths are absent edges, not edges with high cost: an edge that is marked blocked should be removed from the graph before the algorithm runs, not left in with a prohibitive weight that the algorithm must remember to avoid.

In the knowledge engine, a best-path computation over the citation graph might find the lowest-cost explanation path between two concepts — the chain of citations and references that most efficiently connects one idea to another. The edge costs might represent the strength of the relationship, with lower cost indicating a stronger or more direct connection. The objective is minimum total distance in this conceptual space, and the algorithm is selected accordingly.

In the virtual world, entity navigation through a weighted interaction space finds the path of least resistance for an entity moving from one region to another, where edge costs encode the difficulty or energy of each transition. The algorithm must account for edges that are dynamically blocked — regions that have become inaccessible due to other entities or events — and must recompute routes when the graph changes.

In all three systems, the path algorithm is a direct consequence of the cost model. The cost model is a direct consequence of the domain's definition of "best."


## Four Ways Best-Path Design Fails

**Objective ambiguity.** An algorithm that optimizes for fewest hops will not produce the minimum-cost path on a weighted graph. An algorithm that minimizes cost without declaring that cost is the sum of edge weights may be reimplemented differently by different developers. The objective must be stated precisely — what is being minimized, how individual edge costs are combined, and what "optimal" means in this domain — before an algorithm is selected. When the objective is ambiguous, different parts of the system will produce different answers to the same routing question.

**Greedy local choice treated as global optimum.** A local greedy algorithm that always expands the cheapest edge from the current node will fail on graphs where the globally optimal path requires an initially expensive edge. The failure is invisible on graphs where the locally greedy choice happens to be globally optimal, and appears only on carefully constructed counterexamples. The remedy is to use an algorithm — Dijkstra, or a variant suited to the cost model — that maintains a global frontier and defers finalization until no remaining path can improve on the current cost.

**Illegal edges included in expansion.** An algorithm that expands edges without verifying that they are currently traversable may return paths that include blocked connections. The check for edge validity must occur during expansion, not before the algorithm runs, because edge states may change while the algorithm is running — in a dynamic environment where paths can be blocked in real time, an edge that was valid at the start of the search may be invalid when it is reached. The expansion step must re-verify traversability at the moment of expansion.

**Undefined unreachable semantics.** A path algorithm that returns an empty path, a zero-cost result, or a null when the destination is unreachable is indistinguishable, from the caller's perspective, from an algorithm that found an empty path that is the optimal path. The `UNREACHABLE` status must be a distinct, declared outcome. Every caller must handle it explicitly.


## Quick Exercise

Choose one routing problem in your system and define its best-path specification completely with five components: the objective function and how edge costs are combined, the edge validity rules and when they are checked, the tie-break rule when multiple paths have equal cost, the declared behavior when the destination is unreachable, and the algorithm and the assumption it requires to be correct.

Then construct one adversarial input — a graph where a locally greedy choice produces a suboptimal global result — and verify that your chosen algorithm handles it correctly.


## Takeaways

- Best-path optimality is defined relative to a cost model. The cost model must be stated before the algorithm is chosen, because the algorithm's correctness guarantees are relative to it.
- Dijkstra's algorithm is correct for non-negative edge costs. Modifying the cost model — allowing negative edges, changing the combination function — requires reconsidering the algorithm.
- Local optimality does not imply global optimality. An algorithm that makes irrevocable local choices will fail on graphs where the globally optimal path requires a locally suboptimal step.
- Edge validity must be checked during expansion, not just at the start. In dynamic environments, an edge that was valid when the search began may become invalid before it is reached.
- Unreachability is a distinct outcome that must be declared explicitly. An empty path is not an unreachable status.


*Part V has now covered the core algorithm families: search, sorting, traversal, and path optimization. Part VI applies these tools to the problems of system design at scale — where algorithms and data structures must be composed into architectures that are correct, efficient, and maintainable under real conditions.*
