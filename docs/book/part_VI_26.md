# Chapter 26: Designing Interfaces

The preceding chapters of Part VI established the tools for assembling algorithms into software: component boundaries that separate responsibilities, functional decomposition that isolates pure computation from effects, object-oriented design that assigns behavior to the objects that own the relevant state. Each of these tools produces pieces that work correctly in isolation. Interfaces are what determine whether those pieces work correctly together — and whether they continue to work correctly together as each piece evolves independently.

An interface is a promise. It is the commitment a component makes to every piece of code that calls it: *these are the inputs I require, this is what I guarantee in return, and this is what I will tell you when I cannot deliver.* A component that fulfills this promise can be changed internally without affecting its callers. A component that violates it — by changing what it returns, by introducing new preconditions it previously did not require, by altering the meaning of its failure codes — forces every caller to change with it. The quality of an interface is the quality of the promise it makes, and the stability of a system is the stability of its interfaces.

---

## Four Properties of a Good Interface

A good interface has four properties, and a weakness in any one of them will eventually produce integration failures.

**Minimal.** The interface exposes only the operations callers actually need. Every additional operation is a commitment: it must be maintained, documented, tested, and kept consistent with the rest of the interface as the component evolves. An interface that exposes more than necessary couples callers to behavior they did not ask for and creates obligations the component may not be able to fulfill. The right question when designing an interface is not *what can this component do?* but *what do callers need from it?* These are often smaller sets.

**Explicit.** The interface makes its assumptions and failure modes visible. A caller should be able to understand the full contract of an operation — what it requires, what it guarantees, and what it reports when it cannot deliver — without reading the implementation. When failure modes are implicit, different callers develop different assumptions about what a given return value means, and the system accumulates inconsistent handling that is only discovered when a failure occurs in production.

**Stable.** The interface does not change its contract without strong reason. Callers design their code around the promises an interface makes; a change that breaks those promises breaks every caller. Stability does not mean an interface can never change — requirements evolve, and interfaces must too. It means that changes are additive where possible, that breaking changes are treated as major decisions rather than implementation details, and that the direction of evolution is toward more capability, not toward redefining existing behavior.

**Testable.** The interface's behavior can be fully validated through its contract, without knowledge of the implementation. An integration test that verifies a `plan_route` call returns `FOUND` with a valid path for a connected graph, `UNREACHABLE` for a disconnected one, and `INVALID_INPUT` for an empty identifier is a test that will continue to hold across any number of internal implementation changes. An integration test that validates the structure of the internal route buffer is a test that will break the next time the buffer is refactored.

---

## What an Interface Contract Contains

The contract of an interface operation has four parts. All four must be present for the contract to be complete.

**Input preconditions** specify what the caller must provide for the operation to behave as specified. A precondition that is not stated is a precondition that is violated without warning. If `plan_route` requires non-empty location identifiers and this is not documented, callers will sometimes pass empty identifiers and discover the resulting behavior by accident rather than by design.

**Output guarantees** specify what the operation returns for any input satisfying the preconditions. This includes the structure of the return value, the meaning of its fields, and the constraints those fields satisfy. For `plan_route`, the output guarantee is that the returned path uses only traversable edges, the first node is the start location, and the last node is the goal location. These are promises to every caller.

**Failure semantics** specify the distinct outcomes when the operation cannot succeed. Two failure modes with different causes must have different representations. A missing route and an invalid input are not the same failure; a caller that treats them the same will produce incorrect behavior in at least one of the two cases. Each failure code must carry enough information for the caller to decide what to do next.

**Cross-call invariants** specify properties that remain true across multiple calls to the interface. A route planner that returns different paths for the same inputs on consecutive calls — without any change to the underlying graph — is not a stable interface. If determinism is not guaranteed, it must be documented as not guaranteed, and callers must not depend on it. If it is guaranteed, the guarantee must be stated.

---

## From Requirement to Interface Design

Consider the requirement:

> *"Expose route planning to multiple clients — a web interface, a scheduler, and a simulator."*

Three callers with different purposes make different demands on the interface. The web interface needs a human-readable path and a clear status code. The scheduler needs to know whether a route was found and what its cost is. The simulator needs deterministic behavior for reproducible runs. A well-designed interface must serve all three without forcing any of them to know about the others' concerns.

**The wrong approach** is to expose the internal graph structure — the adjacency list, the distance matrix, the mutable route buffer — and let each caller extract what it needs. This approach makes every internal data structure choice visible to every caller. When the graph representation changes from an adjacency list to a different structure, every caller must change with it. The internal implementation has no freedom to evolve.

**The right approach** exposes one operation: `plan_route(start, goal)`, returning a result object with a declared status and a path. The result object is a value — it contains information, not references to internal state. Its fields are defined by the interface contract, not by the implementation. Callers receive a `FOUND` result with a path, an `UNREACHABLE` result with no path, or an `INVALID_INPUT` result when the inputs do not satisfy the preconditions.

Under this design, the route algorithm can change from BFS to Dijkstra, the graph can change from an adjacency list to a different representation, the internal cost model can be revised — none of these affect any caller. What callers depend on is the contract, and the contract has not changed.

---

## An Interface in Code

```nex
class Route_Response
feature
  status: String
  path: String
invariant
  valid_status:
    status = "FOUND" or
    status = "UNREACHABLE" or
    status = "INVALID_INPUT"
end

class Route_Interface
feature
  plan_route(start_loc, goal_loc: String): Route_Response
    require
      inputs_present: start_loc /= "" and goal_loc /= ""
    do
      let r: Route_Response := create Route_Response

      if start_loc = goal_loc then
        r.status := "FOUND"
        r.path := start_loc
      elseif start_loc = "A" and goal_loc = "C" then
        r.status := "FOUND"
        r.path := "A->B->C"
      else
        r.status := "UNREACHABLE"
        r.path := ""
      end

      result := r
    ensure
      declared_status:
        result.status = "FOUND" or
        result.status = "UNREACHABLE" or
        result.status = "INVALID_INPUT"
    end
end

class Route_Client
feature
  route_api: Route_Interface

  request(start_loc, goal_loc: String): String
    require
      inputs_present: start_loc /= "" and goal_loc /= ""
    do
      let resp: Route_Response := route_api.plan_route(start_loc, goal_loc)
      if resp.status = "FOUND" then
        result := "OK:" + resp.path
      elseif resp.status = "UNREACHABLE" then
        result := "NO_ROUTE"
      else
        result := "BAD_INPUT"
      end
    ensure
      known_result: result /= ""
    end
end
```

`Route_Response` carries an invariant that constrains the status to one of three declared values. No code can construct a `Route_Response` with an undeclared status — the constraint is structural, not advisory. `Route_Interface.plan_route` has a precondition that rejects empty identifiers and a postcondition that asserts the output status is always one of the three declared values. Together, these define the complete contract.

`Route_Client` depends on nothing about `Route_Interface` except its contract. It does not know whether the route is computed by BFS or Dijkstra, whether the graph is stored as an adjacency list or a matrix, or whether routes are cached. It knows the three possible statuses and what to do with each. This is the definition of a stable caller: one whose behavior is determined by the interface contract and nothing else.

The postcondition on `Route_Client.request` — `result /= ""` — is weaker than the postconditions on the interface itself. This is correct: `request` is a consumer of the interface contract, and its own contract is a consequence of what the interface guarantees. A stronger postcondition that asserted specific result strings would couple `request` to the exact status values of the interface, reducing the interface's ability to change its status vocabulary in the future.

---

## Interfaces in the Three Systems

In the delivery system, the route planning interface is consumed by the dispatch coordinator, the monitoring dashboard, and the route simulation tool. All three callers need a path and a status; none of them need to know how routes are computed. The interface's contract — `FOUND`, `UNREACHABLE`, `INVALID_INPUT` — is stable across all consumers. When the route algorithm is upgraded, no consumer changes.

In the knowledge engine, the retrieval interface is consumed by the ranking component, the debug inspector, and the A/B testing harness. The retrieval interface returns a list of candidate documents with their scores and confidence levels. The contract includes miss semantics — a query that matches no documents returns an explicit empty result, not null — and a bound on the candidate set size. All three consumers can depend on these properties without knowing how retrieval is implemented.

In the virtual world, the state query interface is consumed by the collision detector, the event logger, and the external visualization client. The interface exposes entity state through a defined result type with declared fields. Internal state representation — how positions are stored, how interaction rules are indexed — is hidden behind the interface. The visualization client receives what it needs; the collision detector receives what it needs; neither knows about the other's requirements or the implementation's structure.

In all three systems, the interface is the line at which internal freedom is traded for external stability. Behind the interface, the implementation can change. In front of it, callers can depend on a promise that holds.

---

## Four Ways Interface Design Fails

**Leaking internals.** An interface that returns references to internal data structures, exposes mutable state, or requires callers to manipulate internal representations couples every caller to the implementation. The implementation cannot change without breaking every caller, because every caller depends not just on the contract but on the structure it was built to navigate. The remedy is to return value objects — data structures defined by the interface contract, not by the implementation — and to ensure that callers receive information, not handles to internal state.

**Ambiguous failure modes.** An interface that uses the same return value for success with empty results and for failure requires every caller to infer which situation applies from context. Different callers will infer differently, and one of them will be wrong. Every distinct failure condition deserves a distinct status code. The cost of defining one more status value is minimal; the cost of callers misinterpreting a shared one is unbounded.

**Over-wide interfaces.** An interface with many operations accumulates commitments. Each operation must be maintained, documented, and kept consistent with all the others. As the interface grows, the set of behaviors it exposes becomes harder to understand, harder to test, and harder to evolve without introducing inconsistencies. The minimum viable interface — the smallest set of operations that serves all legitimate callers — is almost always the right starting point. Operations can be added; removing them is a breaking change.

**Versioning by breakage.** An interface that changes its existing contract — redefining what a status code means, removing a field from a response object, tightening a precondition — breaks every caller simultaneously. Breakage is sometimes necessary, but it must be a deliberate decision with a migration plan, not a consequence of an implementation change that happened to affect the contract. The discipline of additive evolution — adding new operations and new status values rather than modifying existing ones — preserves the contract for existing callers while extending it for new ones.

---

## Quick Exercise

Choose one interface in your system that is consumed by more than one caller and document its complete contract with five parts: the input preconditions for its primary operation, the output guarantee for the success case, the failure semantics for each distinct failure mode, one cross-call invariant that callers depend on, and one internal detail that the interface must not expose.

Then write one integration test that validates the contract without inspecting the implementation. The test should remain valid across any internal change that does not alter the contract. If the test would break when the internal algorithm changes, it is testing the implementation rather than the interface.

---

## Takeaways

- An interface is a promise: what the component requires, what it guarantees, and what it reports when it cannot deliver. The quality of the promise determines the stability of every caller.
- A good interface is minimal, explicit, stable, and testable. Weakness in any one of these properties will eventually produce integration failures.
- The complete contract of an interface operation has four parts: input preconditions, output guarantees, failure semantics, and cross-call invariants. An interface with any of these missing is an interface with implicit assumptions that callers will discover in production.
- Leaking internals, ambiguous failure modes, over-wide interfaces, and versioning by breakage are the four failure modes of interface design. All four are preventable by contract-first thinking.
- The line between what is behind the interface and what is in front of it is the line between internal freedom and external stability. Stable interfaces are what make internal evolution possible.

---

*Part VI has now assembled the complete toolkit for building real software: components, functional thinking, object-oriented responsibility design, and stable interfaces. These are the tools that connect the algorithmic correctness of Parts III and V to the architectural quality of the systems that algorithms ultimately inhabit.*
