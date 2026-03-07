# Chapter 18: Graphs — Networks of Everything

Trees impose a discipline on connected data: one parent per node, no cycles, a single root from which all other nodes descend. This discipline is what makes tree algorithms efficient and predictable. It is also a constraint that many real domains do not satisfy.

A road network has no root and no notion of parent and child — every location is connected to some set of others, and the connections form cycles wherever roads loop back on themselves. A citation network among documents is not a tree: a paper may cite many others, and two papers may cite each other. The interaction structure of a virtual world has entities in multiple overlapping relationships, none of which is naturally hierarchical. When the domain is genuinely networked rather than hierarchical, forcing it into a tree produces a structure that is either incomplete — omitting the edges that violate the tree constraint — or distorted — introducing an artificial root and parent assignments that carry no domain meaning.

A graph is the structure for connected data in its most general form: nodes represent entities, edges represent relationships between them, and neither cycles nor multiple connections between a node and its neighbors are forbidden. Most real systems eventually require graph thinking. The question is whether that thinking happens deliberately, in the model, or accidentally, scattered across code paths that independently reconstruct the connectivity the model never made explicit.

---

## Four Questions Graphs Answer

Graph-driven systems return repeatedly to four questions. The data structure choices and algorithm choices that serve a system well depend on which of these questions it asks most often and what answers it requires.

**Reachability.** Can we get from node A to node B at all? This is the foundational question for any routing or connectivity problem. For the delivery system, it determines whether a destination is reachable from the robot's current location. For the knowledge engine, it determines whether one document is connected to another through a chain of citations or links. For the virtual world, it determines whether two entities can interact — whether any path of interactions connects them through the world's rule structure.

**Path quality.** Among all paths from A to B, which is best by some defined objective? This is a different question from reachability, and it requires a different algorithm. Reachability can be determined by any traversal that visits all reachable nodes; path quality requires a traversal that compares alternatives by a defined criterion — fewest hops, minimum total cost, shortest travel time. Conflating these two questions — treating any found path as an optimal path — produces a system that works and produces wrong answers.

**Neighborhood.** What is directly connected to this node? For a delivery robot, the neighborhood of a location is the set of locations reachable in one step. For a document, it is the set of documents it directly cites or is cited by. Neighborhood queries are the building blocks of traversal algorithms and are often the most frequent graph operation a system performs.

**Connectivity changes.** What happens to reachability when an edge is removed? When a path between two locations is blocked, which destinations become unreachable? When a document is removed from the knowledge base, which search paths are disrupted? Systems that must respond to connectivity changes need not just a way to compute reachability, but a way to detect when reachability has changed and respond accordingly.

The data structures chosen to represent the graph, and the algorithms written to traverse it, should follow from which of these questions the system must answer and how frequently.

---

## Representing a Graph

A graph can be represented in several ways, and the choice matters for performance. The two most common representations make different tradeoffs between the cost of edge lookup and the cost of enumerating a node's neighbors.

An **adjacency matrix** represents the graph as a two-dimensional grid where the entry at row i, column j indicates whether an edge exists from node i to node j. Edge lookup is constant time: check one cell. Enumerating all neighbors of a node requires scanning one row — linear in the total number of nodes, regardless of how many edges that node actually has. An adjacency matrix is a natural choice when the graph is dense (most possible edges exist) and when edge lookup is the dominant operation.

An **adjacency list** represents the graph as a collection where each node maps to the list of nodes it connects to. Enumerating a node's neighbors is linear in the number of edges that node has — which for a sparse graph is much smaller than the total number of nodes. Checking whether a specific edge exists requires scanning the neighbor list, which is linear in degree rather than constant. An adjacency list is a natural choice when the graph is sparse (most possible edges do not exist) and when neighbor enumeration is the dominant operation.

Real delivery networks, citation graphs, and interaction networks are all sparse. The adjacency list is the natural representation for all three. Making that choice explicit in the model — rather than leaving it implicit in however the code happens to store connections — is what makes the representation a design decision rather than an accident.

---

## From Requirement to Graph Algorithm

Consider the requirement:

> *"Given two locations, return a valid route or report that no route exists."*

**Step 1: Define the graph elements.** Nodes are location identifiers. Edges are traversable connections between locations. An edge is directed (A connects to B does not imply B connects to A) or undirected depending on whether the connections are one-way or bidirectional. Blocked paths are absent edges — an edge that is no longer traversable is removed from the adjacency structure, not marked with a flag that the algorithm must remember to check.

**Step 2: Define the correctness contract.** A returned path must use only edges that exist and are traversable in the direction of travel. The first node in the path must be the source. The last must be the destination. Every intermediate step must be connected to the next by a valid edge. When no such path exists, the result is `UNREACHABLE` — an explicit, declared outcome, not an empty list that the caller must interpret.

**Step 3: Choose a traversal algorithm.** For the first version of the system, with unweighted edges and the objective of fewest hops, breadth-first search is the right choice. BFS explores nodes in order of their distance from the source, measured in hops, and guarantees that the first path it finds to any node is the shortest one. If the objective were minimum total cost on a weighted graph, Dijkstra's algorithm would be the right choice. The algorithm is chosen to match the objective, not selected by habit.

**Step 4: Add traversal safety.** Graphs may contain cycles. A traversal that does not track which nodes have been visited will follow cycles indefinitely, consuming memory and time without making progress. The visited set, introduced in Chapter 13 for recursive traversals, is equally necessary for iterative ones. Unknown nodes — identifiers not present in the graph — should produce `INVALID_INPUT` rather than being treated as disconnected nodes.

**Step 5: Keep the representation explicit.** The adjacency structure is a first-class element of the model, not a detail hidden inside the traversal algorithm. Exposing it explicitly makes the graph's content inspectable, testable, and modifiable independently of the algorithms that operate on it.

---

## A Graph in Code

```nex
class Route_Graph
feature
  -- Teaching-sized adjacency for four nodes.
  a_to_b: Boolean
  b_to_c: Boolean
  c_to_d: Boolean
  a_to_d: Boolean

  has_edge(from_node, to_node: String): Boolean
    require
      non_empty_nodes: from_node /= "" and to_node /= ""
    do
      if from_node = "A" and to_node = "B" then
        result := a_to_b
      elseif from_node = "B" and to_node = "C" then
        result := b_to_c
      elseif from_node = "C" and to_node = "D" then
        result := c_to_d
      elseif from_node = "A" and to_node = "D" then
        result := a_to_d
      else
        result := false
      end
    ensure
      bool_result: result = true or result = false
    end

  route_a_to_d(): String
    do
      if has_edge("A", "D") then
        result := "A->D"
      elseif has_edge("A", "B") and has_edge("B", "C") and has_edge("C", "D") then
        result := "A->B->C->D"
      else
        result := "UNREACHABLE"
      end
    ensure
      declared_result:
        result = "A->D" or
        result = "A->B->C->D" or
        result = "UNREACHABLE"
    end
end
```

`Route_Graph` is minimal — four nodes, four possible edges, represented as boolean fields. What it demonstrates is the separation of concerns that a graph model requires. `has_edge` is the primitive operation: given two node identifiers, does the edge between them exist? It knows nothing about routes. `route_a_to_d` composes `has_edge` calls to determine which paths are available and returns the first valid one it finds, or `UNREACHABLE` if none exists.

The postcondition on `route_a_to_d` — three explicitly declared possible return values — is the contract that `route_a_to_d` makes with its callers. Every path it might return is valid according to `has_edge`: the direct route is guarded by `has_edge("A", "D")`, the long route by three consecutive edge checks. The algorithm cannot return a route that includes an edge which `has_edge` would reject. The contract is enforced by the structure of the code rather than by a runtime check that might be forgotten.

In a full implementation, the boolean fields would be replaced by an adjacency list, and `route_a_to_d` would be replaced by a BFS traversal over that list. The contracts would remain the same. This is the value of specifying the contract at the boundary rather than inside the implementation: when the implementation changes, the contract does not need to change with it.

---

## Graphs in the Three Systems

The delivery network is a graph in which locations are nodes and traversable paths are edges. When a path is blocked, the corresponding edge is removed, and any route that used it must be recomputed. The graph changes dynamically as the robot moves and paths become available or unavailable, and the route-finding algorithm must operate on the current state of the graph rather than on a snapshot taken at initialization.

The knowledge engine's document collection forms a graph in which documents are nodes and citations or relevance links are edges. A query that asks for documents related to a topic may want not just directly tagged documents but documents reachable within some number of hops through the citation graph. The depth bound determines the breadth of the search; the edge direction determines whether the search follows citations forward, backward, or both.

The virtual world's interaction structure forms a graph in which entities are nodes and potential interactions are edges. Two entities that are in the same region are potentially interacting; the interaction rules determine what happens when they do. The graph changes every tick as entities move, appear, and disappear. Efficiently computing which entity pairs are close enough to interact — the neighborhood query — is one of the dominant computational costs of any physics or interaction simulation.

In all three systems, the graph is the natural representation of the domain's connectivity. In all three, treating it as implicit — as connectivity logic embedded in algorithm code rather than expressed as a data structure — produces systems that are harder to inspect, test, and modify.

---

## Four Ways Graph Design Goes Wrong

**Implicit graph representation.** A system whose connectivity is expressed only in algorithm logic — in the conditionals and loops of traversal code, rather than in an explicit adjacency structure — has a hidden graph that cannot be inspected or modified independently of the algorithms over it. Adding a new edge requires finding and modifying traversal code. Removing one requires the same. Testing the graph's structure independently of the traversal is impossible. The remedy is to represent the adjacency structure explicitly and to expose operations for querying and modifying it that the traversal algorithms call.

**Missing cycle protection.** A traversal that does not track visited nodes will follow cycles without terminating. This is not an edge case — any graph that models a real network may contain cycles, and the absence of a visited set is a bug waiting for a graph that happens to contain one. The visited set is mandatory, not optional, for any traversal over a graph that may have cycles.

**Conflating reachability with path quality.** BFS finds the path with fewest hops. Dijkstra finds the path with minimum weighted cost. Depth-first search finds a path, but not necessarily the shortest one. These are different algorithms for different objectives, and using one when the problem requires another produces a system that computes the wrong answer correctly. The objective must be defined before the algorithm is chosen, not after.

**Weak failure semantics.** A traversal that returns an empty path when no route exists, or returns a partial path when traversal is interrupted, leaves the caller to infer what happened from an output that carries no explicit information about it. Two different failure conditions — unreachable destination, invalid input — look identical to a caller who receives an empty list in both cases. The remedy is to declare the full set of possible outcomes in the operation's contract and to return a distinct value for each.

---

## Quick Exercise

Choose one networked feature in your system — one where entities are connected by relationships that are not strictly hierarchical — and define its graph model with five components: the node type and identity key, the edge type and what it represents in the domain, one reachability query and its contract, one path-quality objective and the algorithm it requires, and one cycle-related risk and how the traversal will handle it.

Then examine how the current implementation represents connectivity. Is there an explicit adjacency structure, or is the connectivity implied by the traversal logic? If it is implied, identify what would need to change to make it explicit.

---

## Takeaways

- Graphs model real-world networks that trees and lists cannot express without distortion or loss. When the domain is genuinely networked, the graph is the right representation.
- Reachability and path quality are distinct questions that require distinct algorithms. Choosing an algorithm without first defining the objective produces a system that computes the wrong answer.
- Traversal over any graph that may contain cycles requires a visited set. There are no exceptions to this rule.
- The adjacency structure should be explicit and first-class in the model, not implied by traversal code. An implicit graph cannot be tested or modified independently of the algorithms over it.
- Every graph operation needs declared failure semantics. An empty return is not a failure status.

---

*Part IV has now built the complete data structure foundation: lists for ordered sequences, sets and maps for membership and keyed access, trees for hierarchical search, and graphs for general networks. Part V applies these structures to the core algorithm families — searching, sorting, traversal, and path-finding — that turn organized data into system behavior.*
