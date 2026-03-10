# Thinking Recursively

Chapter 12 established that decomposition means dividing a problem into subproblems with clear interfaces and distinct responsibilities. Recursion is a special and important case of this principle: it applies when the subproblem has the same shape as the original problem, only smaller.

This is not a trick or an advanced technique reserved for difficult cases. It is a natural way to express algorithms over data whose structure is self-referential — trees, graphs, nested containment hierarchies, any domain where the whole is made of parts that resemble the whole. In these domains, a recursive algorithm often expresses the solution more directly and more transparently than an iterative one, because the structure of the algorithm matches the structure of the data.

The two are not always interchangeable in practice. A recursive algorithm that operates on deeply nested data may exhaust the call stack before it reaches a base case. A recursive algorithm that revisits already-explored nodes in a graph may run forever. Understanding recursion well means understanding not just how to write recursive algorithms, but when they are safe, when they are not, and what must be in place to make them reliable.


## The Shape of a Recursive Algorithm

Every correct recursive algorithm has exactly two components, and both must be present for the algorithm to be correct.

The **base case** is the simplest possible instance of the problem — the version that can be solved directly, without recursion. For counting reachable nodes in a graph, the base case is a node that has already been visited: the answer is zero, because the node has already been counted. For computing the depth of a tree, the base case is a leaf: the depth is one. The base case is not optional. An algorithm without a reachable base case does not terminate.

The **recursive step** reduces the original problem to one or more smaller instances of the same problem, solves those instances by calling itself, and combines their results into the answer for the original. The word *smaller* is critical. Each recursive call must bring the computation measurably closer to the base case. If the recursive step does not make progress — if a call with input of size *n* produces a recursive call with input also of size *n* — the algorithm will not terminate either.

The relationship between these two components mirrors the structure of a proof by induction: the base case establishes correctness for the smallest input; the recursive step establishes that correctness for any input of size *n* follows from correctness for inputs smaller than *n*. An algorithm that satisfies both components is correct for all valid inputs. An algorithm that satisfies only one is not an algorithm — it is a procedure that works on some inputs by accident.


## When Recursion Fits

The choice between recursion and iteration is a choice about how to express the algorithm's logic, and it should be made based on which expression more clearly reflects the structure of the problem.

Recursion tends to be the more natural expression when the data is hierarchical or graph-like: nested structures where each node contains other nodes of the same type, trees where each subtree is itself a tree, graph traversals where each neighbor is itself a node to be explored. In these cases, the recursive structure of the algorithm corresponds directly to the recursive structure of the data, and the algorithm reads as a statement about the data rather than as a sequence of bookkeeping steps.

Iteration tends to be the more natural expression for linear workflows: processing a sequence from start to finish, accumulating a result as you go. A loop that sums the elements of a list is not hiding a self-similar structure; it is doing the same thing repeatedly, and saying so directly is clearer than encoding it as recursion.

Choosing recursion for a linear workflow, or iteration for a hierarchical one, produces code that works but does not communicate. The algorithm's structure and the problem's structure are misaligned, and a reader must work to find the correspondence that the code does not make visible.


## Graph Recursion and the Necessity of Bounds

The three systems developed throughout this book are all graph-like in some respect, and all three require recursive reasoning that is careful about bounds.

In the delivery network, exploring alternate route branches requires recursion over the graph of locations and paths. But a delivery network may contain cycles — two locations connected to each other through multiple paths — and a recursive traversal that does not track which nodes have been visited will follow those cycles indefinitely.

In the knowledge engine, traversing the network of related documents to find indirectly relevant material requires recursion over the document graph. The same concern applies: documents may be mutually related, cycles are possible, and depth must be bounded both to prevent infinite traversal and to prevent the search from expanding to the entire document collection.

In the virtual world, processing the containment hierarchy — zones contain regions, regions contain objects — is naturally recursive because the hierarchy is a tree. Trees have no cycles, but depth bounds still matter for performance, and deterministic traversal order matters for correctness: if the same hierarchy is processed in different orders on different runs, the simulation is not reproducible.

In all three domains, recursion without bounds is unsafe. The pattern that makes graph recursion safe is always the same: maintain a set of visited nodes, check it before recursing, and update it before proceeding. Optionally, enforce a depth limit as a second line of defense against graphs that are technically acyclic but are too deep for the call stack to handle.


## From Requirement to Recursive Algorithm

Consider the requirement:

> *"Count the number of locations reachable from a starting node."*

The problem has a self-similar structure. A node is reachable from the start if it is the start itself, or if it is reachable from any neighbor of the start. The count of reachable nodes from a given node is one (for the node itself) plus the sum of reachable nodes from each unvisited neighbor. This is a recursive definition, and it maps directly to a recursive algorithm.

**Base case.** If the current node has already been visited, return zero. The node has already been counted, and counting it again would produce an incorrect total.

**Recursive step.** Mark the current node as visited. Count it (contributing one to the total). For each neighbor, add the count returned by a recursive call on that neighbor. Return the sum.

**Progress metric.** The recursion terminates because the visited set grows with each call, and the graph is finite. Every recursive call either finds a visited node and returns immediately, or visits a new node and reduces the number of unvisited nodes by one. The total number of recursive calls is bounded by the number of nodes in the graph.

**Cycle guard.** The visited set is the cycle guard. A node that has been visited will not be visited again, which breaks any cycle the graph might contain.

These four elements — base case, recursive step, progress metric, cycle guard — are the complete specification of the algorithm. The implementation follows directly from them.


## A Recursive Algorithm in Code

```nex
class Reachability
feature
  visited: String

  count_from(node: String): Integer
    require
      node_present: node /= ""
    do
      -- Teaching sketch: visited is encoded as a 
	  -- simple string marker set. 
	  -- A full implementation would use a 
	  -- dedicated collection type.
      if visited = node then
        result := 0
      elseif node = "Z" then
        visited := node
        result := 1
      else
        visited := node
        result := 1 + count_from("Z")
      end
    ensure
      non_negative_count: result >= 0
    end
end
```

Read this sketch against the four elements above. The first branch of the conditional is the base case: the node has been visited, return zero. The remaining branches are the recursive step: mark the node visited, count it, and add the result of the recursive call. The `visited` field serves as the cycle guard. The progress metric is implicit — each call either terminates immediately (the base case) or adds a new node to `visited` before recursing — but in a full implementation it would be worth making the progress argument explicit, either in a comment or in an assertion.

The postcondition `result >= 0` is minimal but meaningful. A negative count is nonsensical, and guaranteeing non-negativity allows the caller to depend on this property rather than defensively check for it. As with the decomposed stages in Chapter 12, the contract at the boundary is what allows the piece to be composed with other pieces safely.


## Four Ways Recursive Algorithms Fail

**Missing base case.** Without a reachable base case, the recursion has no stopping condition. The call stack grows without bound until the runtime exhausts its stack space and the program terminates with an error. The remedy is to write and test the base case before writing the recursive step. An algorithm whose base case is correct for the smallest input is an algorithm that has been verified to stop.

**No progress metric.** A recursive call that does not reduce the problem — that calls itself with the same input, or with an input of the same size — will not reach the base case. This is a subtler failure than a missing base case, because the code may look correct: there is a base case, and there are recursive calls, but the calls never get closer to the base. Defining a progress metric explicitly — the number of unvisited nodes, the remaining depth, the size of the input — and verifying that each recursive call strictly reduces it is the discipline that prevents this failure.

**Ignoring cycles.** In any graph that may contain cycles, a recursive traversal without a visited set will follow cycles indefinitely. The algorithm may look correct on acyclic inputs and fail silently on cyclic ones. Since the presence or absence of cycles is a property of the data, not of the algorithm, the safe assumption is that cycles may exist and the visited set is always required.

**Recomputing identical subproblems.** Some recursive algorithms naturally decompose a problem into overlapping subproblems — the same subproblem arises from multiple different paths through the recursion. Without memoization, each instance is recomputed independently, and the total work grows exponentially in the number of overlapping subproblems. The signature of this failure is an algorithm that is correct on small inputs and runs for an unacceptable amount of time on larger ones. The remedy is to record the result of each subproblem the first time it is computed and return the recorded result on subsequent calls.


## Quick Exercise

Design one recursive routine for a problem in your system and specify it completely with five components: the base case, the recursive step, the progress metric, the cycle guard (or an explicit argument that none is needed), and a worst-case bound on the depth of recursion.

Then test it against three inputs: a trivial input that exercises only the base case, a normal input that exercises the recursive step, and an adversarial input that contains a cycle or approaches the depth bound. If the adversarial input produces unbounded behavior, the specification is incomplete. Revise it before writing the implementation.


## Takeaways

- Recursion is decomposition applied to self-similar structure. It is not a general-purpose technique but a natural fit for hierarchical and graph-like problems.
- Every correct recursive algorithm has a base case and a progress metric. Both are mandatory. An algorithm missing either one is not correct.
- Graph recursion requires cycle guards. The visited set is not an optimization — it is the mechanism that prevents the algorithm from following cycles indefinitely.
- Overlapping subproblems require memoization. An algorithm that recomputes the same subproblem repeatedly is correct but not acceptable; recognizing the overlap is the first step toward fixing it.
- The four elements of a safe recursive design — base case, recursive step, progress metric, cycle guard — are a complete specification. The implementation follows from them directly.


*Chapter 14 shifts from the correctness of algorithms to their cost. A correct algorithm that is too slow for its inputs is not a usable algorithm. Understanding how algorithmic cost grows with input size is what makes it possible to choose between correct alternatives — and to predict, before deployment, whether an algorithm will hold up under real conditions.*
