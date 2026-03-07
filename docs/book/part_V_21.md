# Exploring Trees and Graphs

Sorting organizes data for efficient access. Traversal navigates structure to answer questions about it. Once data is represented as a tree or graph, nearly every meaningful computation over that data — finding connected components, determining reachability, discovering paths, computing aggregates over hierarchies — is some form of traversal: a systematic visit to the nodes of the structure according to a defined order.

Two fundamental orders exist, and they are not interchangeable. The right choice between them depends on what the traversal is trying to compute. A team that uses depth-first search where breadth-first is required will produce correct code that answers the wrong question.


## Depth-First Search

Depth-first search commits fully to one direction before exploring any other. Starting from a node, it follows one outgoing edge to a neighbor, then one outgoing edge from that neighbor, then one from that neighbor's neighbor, continuing in this way until it reaches a node with no unvisited neighbors. Only then does it backtrack and try a different direction.

The consequence of this commitment is that DFS reaches distant nodes quickly but makes no guarantee about the distance at which it finds them. A DFS from node A may reach node Z before it has visited all of A's immediate neighbors. If the goal is to find *any* path to Z, DFS will find one efficiently. If the goal is to find the *shortest* path to Z, DFS provides no such guarantee — it will find a path, but not necessarily the shortest one.

DFS is naturally expressed as a recursive algorithm. The recursive structure mirrors the traversal structure: visiting a node means visiting the node itself and then recursively visiting each of its unvisited neighbors. This correspondence between the algorithm's structure and the data's structure is why DFS is the natural choice for operations over trees and hierarchies — structures that are themselves recursively defined. Computing the depth of a tree, evaluating an expression tree, finding all nodes in a subtree that satisfy some property: these are DFS computations, and they read clearly as recursive code.

DFS is also the natural choice for exhaustive exploration — when the goal is to visit every reachable node, not to find a specific one quickly. The connected-components problem, the cycle-detection problem, and topological sorting of a directed acyclic graph are all DFS computations. They require visiting all reachable nodes, and DFS's commitment to depth makes it efficient for this class of problem.


## Breadth-First Search

Breadth-first search visits nodes in order of their distance from the starting node, measured in the number of edges traversed. It visits all of the start node's neighbors before visiting any of their neighbors, and all of those before visiting any of their neighbors' neighbors. It expands outward in concentric layers, one hop at a time.

The consequence is that BFS finds the nearest node matching any criterion before it finds more distant ones. If the goal is to find the shortest path — in terms of hops — from A to any node satisfying a condition, BFS is guaranteed to find the shortest such path. DFS makes no such guarantee.

BFS is naturally implemented iteratively, using a queue. The queue holds the frontier — the set of nodes that have been discovered but not yet processed. At each step, BFS removes a node from the front of the queue, processes it, and adds its unvisited neighbors to the back. The ordering of the queue is what enforces the layer-by-layer expansion: neighbors added at one step are processed before the neighbors of neighbors added at the next step.

For the delivery system's route-finding problem, BFS over an unweighted graph finds the route with fewest hops. For the knowledge engine's document discovery problem, BFS finds documents within a defined hop count in the order they would be reached — first documents directly linked to the query result, then documents one hop further. For the virtual world's region traversal, BFS starting from a region visits nearby regions before distant ones, which is the natural processing order when distance from a source matters.


## Traversal Safety: Three Required Controls

A traversal that visits the right nodes in the right order is correct. A traversal without safety controls is correct on graphs that cooperate and incorrect on graphs that do not.

**Visited tracking** is the first required control, and it is not optional for any graph that may contain cycles. A traversal that does not record which nodes have been visited will follow a cycle indefinitely, revisiting the same nodes without making progress. The visited set — introduced in Chapter 13 for recursive DFS — is equally necessary for iterative BFS. Every node is added to the visited set at the moment it is first discovered, before it is processed, so that subsequent encounters with the same node are recognized and skipped.

**Depth and expansion bounds** are the second required control for traversals that must be bounded. A BFS over a document graph with no depth limit will explore the entire connected component of the starting node, which may be the entire document collection. A depth limit of two means: visit the start node and its direct neighbors (depth one) and their neighbors (depth two), then stop. The bound must be enforced explicitly — it does not emerge automatically from the traversal structure — and it must be chosen based on what the system needs to compute, not on what happens to work in testing.

**Deterministic ordering** is the third required control for traversals whose output order matters. The order in which a node's neighbors are processed during traversal may depend on the order in which they appear in the adjacency structure, which may depend on insertion order, which may depend on when entities were created. A traversal that produces different output on different runs, or different output on different machines, is not a correct implementation of a deterministic algorithm — it is an algorithm whose correctness depends on an assumption it never stated. When the traversal's output order is meaningful, the ordering must be declared explicitly and enforced.


## From Requirement to Traversal Design

Consider the requirement:

> *"From a starting document, discover connected documents up to depth two."*

**Step 1: Define the reachability scope.** Depth two means: the start document, the documents directly linked to it (depth one), and the documents linked from those (depth two). Documents at depth three or beyond are outside scope. The depth limit is a correctness constraint, not a performance optimization — the result at depth two is not a subset of the result at depth three; it is a different, defined output.

**Step 2: Choose the traversal.** BFS is the right choice because the requirement is organized by depth layer. BFS naturally produces depth-one results before depth-two results, which matches the structure of the output. DFS would find documents at varying depths in an order that depends on the graph's shape, not on the depth criterion. The choice of BFS is a correctness decision.

**Step 3: Define the duplicate policy.** Each document is visited at most once. If document D is reachable from the start through two different paths — one of length one and one of length two — it appears in the output once, at the depth it was first reached. This policy is enforced by the visited set.

**Step 4: Define the output semantics.** The output is the sequence of discovered document identifiers in the order they were first reached: the start document, then its neighbors in the order they appear in the adjacency structure, then their unvisited neighbors in order. The visit order must be deterministic.

**Step 5: Define failure behavior.** An unknown start identifier produces `INVALID_START`. A start document with no outgoing links produces a result containing only the start document itself with status `ISOLATED`. Both cases are declared outcomes.


## A Traversal in Code

```nex
class Explore_Result
feature
  status: String
  discovered: String
invariant
  status_present: status /= ""
end

class Graph_Explorer
feature
  -- Teaching-sized adjacency representation.
  a_to_b: Boolean
  a_to_c: Boolean
  b_to_d: Boolean
  c_to_d: Boolean

  bfs_from_a_depth2(): Explore_Result
    do
      let r: Explore_Result := create Explore_Result

      if not a_to_b and not a_to_c then
        r.status := "ISOLATED"
        r.discovered := "A"
      elseif a_to_b and b_to_d then
        r.status := "OK"
        r.discovered := "A,B,D"
      elseif a_to_c and c_to_d then
        r.status := "OK"
        r.discovered := "A,C,D"
      elseif a_to_b then
        r.status := "OK"
        r.discovered := "A,B"
      elseif a_to_c then
        r.status := "OK"
        r.discovered := "A,C"
      else
        r.status := "ISOLATED"
        r.discovered := "A"
      end

      result := r
    ensure
      declared_status: result.status = "OK" or result.status = "ISOLATED"
    end
end
```

The structure of `bfs_from_a_depth2` encodes the five design decisions from the worked path. The cases are organized by what edges exist, reflecting the depth-layer structure of BFS: direct neighbors of A first, then their neighbors. The `ISOLATED` case handles the failure mode of a start node with no outgoing edges. The postcondition guarantees that the result status is always one of the two declared values — the traversal never returns an undeclared state.

The adjacency fields — `a_to_b`, `a_to_c`, `b_to_d`, `c_to_d` — are the explicit representation of the graph's structure. The traversal consults them directly rather than inferring connectivity from the traversal's own control flow. This is the same separation established in Chapter 18: the graph structure and the traversal algorithm are distinct, and the traversal's correctness depends only on what the adjacency structure reports, not on how it is implemented.

What the sketch does not show — and cannot, at this size — is the visited set and the queue that BFS requires in a general implementation. In a full implementation over an arbitrary graph, both are essential. The sketch's fixed size and hardcoded structure mean the cases are enumerable; in production, the structure must be traversed programmatically, and the safety controls are what prevent the traversal from becoming incorrect when the graph grows.


## Traversal in the Three Systems

In the delivery system, DFS over the road graph explores all locations reachable from a starting point — useful for determining whether a destination is reachable at all, and for finding all alternate routes to enumerate when the primary route is blocked. BFS over the same graph finds the route with fewest hops. The two traversals answer different questions over the same data.

In the knowledge engine, BFS from a query result discovers related documents in layers: documents directly cited by the result, then documents cited by those, up to a defined depth. The depth limit prevents the traversal from expanding to the entire document collection when connectivity is dense. DFS over the same graph would find documents in a less structured order that does not correspond naturally to relevance-by-distance.

In the virtual world, DFS over the containment hierarchy processes nested structures — zones containing regions containing objects — in an order that visits all objects within a zone before moving to the next zone. This is the natural processing order for operations that must aggregate or transform all objects within a scope. BFS over the interaction graph finds entities within a defined number of interaction hops, which may be relevant for propagating effects that travel through chains of interactions.

In all three systems, the traversal algorithm is chosen to match the structure of the computation, not the structure of the graph. The same graph may be traversed with DFS for one purpose and BFS for another.


## Three Ways Traversal Design Fails

**No visited policy.** A traversal without a visited set will follow cycles indefinitely. This is a latent bug in any codebase that uses traversal over graphs that may contain cycles — which includes every real-world graph. The visited set is mandatory. Omitting it because the current test graphs happen to be acyclic is not a defense; the first cyclic graph the traversal encounters will expose the missing control.

**Wrong traversal for the objective.** DFS used to find a shortest path will find *a* path, not necessarily the shortest one. BFS used for exhaustive structure exploration in a deep graph will require a large frontier queue before reaching deep nodes. The traversal algorithm must match the objective, and the objective must be stated before the algorithm is chosen. A team that always uses DFS because it is easy to write recursively, or always uses BFS because it feels safer, is making the choice before asking the question.

**Implicit or absent bounds.** A traversal with no depth or expansion limit will process the entire reachable component of the starting node. For a knowledge engine document graph where any document may be linked to any other, this can mean processing the entire collection. For a delivery network where the graph is fully connected, this means processing every location. Bounds must be chosen deliberately based on what the computation needs, not omitted because the test graphs are small. When a bound is required, its value must be visible in the design — a configurable parameter or a named constant — not buried in a loop condition.


## Quick Exercise

Choose one graph or tree operation in your system and specify its traversal design with five components: the traversal type (DFS or BFS) and a one-sentence justification based on the objective, the visited policy and where it is enforced, the depth or expansion bound and how it is chosen, the output order rule and whether it must be deterministic, and the declared behavior for invalid input.

Then consider: would swapping DFS for BFS, or BFS for DFS, produce incorrect results, slower results, or the same results? If the answer is "the same results," the traversal type may not have been chosen for a principled reason.


## Takeaways

- DFS and BFS are not interchangeable. DFS is suited to exhaustive exploration and recursive decomposition; BFS is suited to minimum-hop reachability and layer-by-layer discovery. The objective determines the choice.
- A visited set is mandatory for any traversal over a graph that may contain cycles. It is not optional and not a performance concern — it is a correctness requirement.
- Depth and expansion bounds must be chosen deliberately and stated explicitly. A traversal without explicit bounds will process arbitrarily large subgraphs on sufficiently connected inputs.
- When traversal output order matters, the ordering must be declared and enforced. An ordering that depends on insertion order or machine state is not a deterministic algorithm.
- The graph structure and the traversal algorithm are separate concerns. The traversal's correctness depends on what the adjacency structure reports, not on how it is implemented.


*Chapter 22 builds on traversal to address a more demanding question: not just which nodes are reachable, but which path to a destination is best by some defined criterion. Path optimization requires both traversal and a cost model, and the interplay between the two determines which algorithms are applicable and what guarantees they can provide.*
