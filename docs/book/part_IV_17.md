# Chapter 17: Trees — Structured Data

Lists preserve order. Maps and sets provide direct access by key. Both are flat: their elements have no structural relationship to one another beyond position or association. Trees introduce a third kind of organization, one that neither flat structure offers — hierarchy.

A tree is a collection in which elements stand in parent-child relationships, forming a layered structure from a single root down through branches to leaves. This hierarchy is not merely a way of storing data. It encodes meaning about the data — that one thing contains or governs another, that a search can be focused by branching in one direction rather than the other, that the depth at which something lives reflects something real about the domain. When that meaning is genuine, a tree makes it explicit and exploits it. When it is not, a tree is unnecessary complexity layered over a problem that a map would have solved more simply.

---

## When Hierarchy Is Real

The first question to ask before introducing a tree is whether the data is genuinely hierarchical — whether the parent-child relationship corresponds to something meaningful in the domain rather than being an artifact of the storage choice.

In the knowledge engine, topics and subtopics form a natural hierarchy: a broad topic contains narrower ones, and the narrower ones may contain still more specific ones. A search for notes about a topic can be confined to the relevant subtree rather than scanning the entire collection. The hierarchy is real because it reflects how the domain organizes knowledge.

In the delivery system, dispatch zones may be organized hierarchically: a city contains districts, a district contains blocks, a block contains specific stops. Routing decisions that apply to a district apply to every block within it. The hierarchy is real because it reflects the structure of geographical containment.

In the virtual world, objects exist within regions, regions within zones, zones within the world. Collision detection, rendering, and rule application can all be scoped to a subtree rather than applied globally. The hierarchy is real because containment is a genuine relationship between objects in the world model.

In each case, the hierarchy is not a storage convenience — it is a fact about the domain that the data structure makes computationally exploitable.

---

## Tree Invariants

A tree's usefulness depends entirely on its structural invariants. A collection of nodes with parent-child links that does not maintain these invariants is not a tree — it is a graph with aspirations. Once the invariants are violated, the algorithms that rely on them produce incorrect results, and the failures are often silent: a search that should find an element returns `NOT_FOUND` because the path the algorithm would have taken no longer leads where it should.

Three invariants define the basic tree structure. First, there are no cycles: following parent links from any node eventually reaches the root and stops. Second, every non-root node has exactly one parent: a node that has two parents is not a tree node, it is a graph node, and the single-path property that makes tree traversal efficient and predictable no longer holds. Third, for a binary search tree, there is an ordering rule: every key in a node's left subtree is smaller than the node's key, and every key in its right subtree is larger. It is this ordering invariant that makes logarithmic search possible — at each node, the comparison eliminates half the remaining search space.

Maintaining these invariants requires that every insertion and update operation enforces them, not just checks them. An insertion that places a new node without verifying the ordering rule produces a tree that looks correct and searches incorrectly.

---

## From Requirement to Tree Design

Consider the requirement:

> *"Organize notes by topic hierarchy and find the first matching topic quickly."*

Two things are embedded here. The first is a structural question: what is the shape of the hierarchy? The second is an operational question: how does a search navigate that shape efficiently?

**Step 1: Identify the hierarchy.** The domain has root topics, subtopics nested within them, and leaf notes attached to the most specific topics. The parent-child relationship is: each subtopic belongs to exactly one parent topic, and each note belongs to exactly one topic. This satisfies the single-parent invariant.

**Step 2: Choose a representation.** For the search operation, a binary search tree organized by a numeric topic key is a natural teaching representation: each node has a key, a label, and references to a left child (with smaller keys) and a right child (with larger keys).

**Step 3: Define the search operation.** At each node, compare the target key with the current node's key. If they match, return the label. If the target is smaller, continue left. If larger, continue right. If neither child exists in the direction of travel, return `NOT_FOUND`. Each comparison eliminates one branch of the remaining tree from consideration, producing a search whose cost grows with the depth of the tree rather than its total size.

**Step 4: Define miss behavior.** A search that reaches a node with no child in the required direction has exhausted the relevant portion of the tree. The result is `NOT_FOUND` — an explicit, declared outcome, not a null or an exception.

**Step 5: Preserve invariants on modification.** Every insertion must place the new node in the position that preserves the ordering invariant. If the tree is allowed to receive insertions that violate the ordering rule — a new node placed on the wrong side of its parent — subsequent searches will fail to find elements that are structurally present.

---

## A Tree in Code

```nex
class Topic_Node
feature
  key: Integer
  label: String
  left_key: Integer
  right_key: Integer
invariant
  label_present: label /= ""
  no_self_child: left_key /= key and right_key /= key
end

class Topic_Tree
feature
  root: Topic_Node
  left: Topic_Node
  right: Topic_Node

  find_label(target: Integer): String
    do
      if target = root.key then
        result := root.label
      elseif target < root.key and target = left.key then
        result := left.label
      elseif target > root.key and target = right.key then
        result := right.label
      else
        result := "NOT_FOUND"
      end
    ensure
      non_empty: result /= ""
    end
end
```

`Topic_Node` carries two invariants worth examining. `label_present` ensures that every node in the tree has a non-empty label — a node with no label is a structural placeholder masquerading as a content-bearing element, and the invariant rejects it. `no_self_child` ensures that a node does not list itself as a child — the minimal check against a trivially cyclic structure that would cause any traversal to loop.

`find_label` demonstrates the branching search that trees make possible. The comparison at each level directs the search toward the relevant subtree. In this three-node sketch, the depth is fixed at two and the cost is constant. In a full implementation over a balanced tree with a million nodes, the same logic would require at most twenty comparisons. A linear scan over a million elements would require up to a million. The invariant is what makes that difference real: without the ordering rule, the branching decision at each node is meaningless, and the search degenerates to a scan.

---

## Balance and Its Consequences

A binary search tree's logarithmic search cost depends on one assumption that the structure itself does not automatically guarantee: that the tree is approximately balanced — that no path from root to leaf is dramatically longer than any other.

A tree built by inserting elements in sorted order — first the smallest key, then the next smallest, and so on — is not balanced. Each new insertion adds a node to the rightmost branch, and the result is a structure that looks like a tree and behaves like a linked list: every search must traverse every node from the root to the target, and the cost is linear in the number of nodes, not logarithmic.

This is the most common tree performance failure, and it occurs silently. The structure is correct — the invariants hold, the searches return the right results — but the performance guarantee that motivated the use of a tree has evaporated. The fix is either to use a self-balancing tree variant — one that restructures itself after insertions to maintain approximate balance — or to monitor the depth distribution of the tree in production and restructure it when the distribution degrades.

---

## Four Ways Tree Design Goes Wrong

**Using a tree without genuine hierarchy.** A tree is more complex to build, maintain, and reason about than a map. If the data does not have genuine hierarchical structure — if there is no meaningful parent-child relationship, no search path that exploits the structure — the complexity is not justified. A map with the same keys will answer the same queries more simply. The check is to ask whether the parent-child relationship corresponds to something real in the domain, or whether it is an artifact of the storage decision.

**Ignoring balance.** An unbalanced tree loses its performance advantage and retains its structural complexity. The worst case is a fully degenerate tree — effectively a linked list — which produces O(N) search on a structure that was introduced for O(log N) search. If the input distribution is not controlled, balance must be actively maintained.

**Violating the ordering invariant.** A search tree whose ordering invariant has been violated by a bad insertion will fail to find elements that are structurally present. The failure presents as incorrect search results, not as a structural error, and the element that cannot be found may be anywhere in the tree. Preventing this requires enforcing the ordering rule at every insertion and every modification that affects a node's key.

**Confusing trees with graphs.** A node that acquires two parents, or a sequence of nodes that forms a cycle, is no longer a tree node. The tree algorithms — depth-first search, binary search, hierarchical aggregation — do not handle multiple parents or cycles correctly, and the failures they produce are often difficult to reproduce because they depend on the order in which the problematic structure was created. Enforcing the single-parent and acyclic invariants at insertion time is what prevents the tree from silently becoming a graph.

---

## Quick Exercise

Choose one place in your system where data has genuine hierarchical structure — a containment relationship, a classification taxonomy, a configuration scope — and model it as a tree with five components: the node identity key and a justification for its stability and uniqueness within the hierarchy, the parent-child rule, one search operation and its contract, one structural invariant beyond what is already implied by the tree shape, and one invalid state the structure must reject.

Then answer this question: why would a map from key to value lose information that the tree preserves? If you cannot give a specific answer — if the map would serve just as well — reconsider whether the hierarchy is genuine.

---

## Takeaways

- Trees represent genuine hierarchy and make structured search efficient. They are not a substitute for maps when the data is flat.
- The ordering invariant of a binary search tree is what makes logarithmic search possible. Maintaining it at every modification is mandatory, not optional.
- Balance is not guaranteed by tree structure alone. An unbalanced tree provides the complexity of a tree with the performance of a list.
- Tree algorithms depend on three structural invariants: no cycles, single parent for every non-root node, and (for search trees) the ordering rule. Violations produce silent correctness failures.
- Use a tree when the parent-child relationship corresponds to something real in the domain and when search can exploit that structure. When neither is true, a map is simpler and sufficient.

---

*Chapter 18 generalizes from trees to graphs — structures in which the restriction to a single parent is lifted and cycles are permitted. Graphs model the most general form of connected data, and they are the natural representation for routing, dependency, and reachability problems that trees cannot express.*
