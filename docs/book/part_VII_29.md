# Testing as Exploration

Contracts make assumptions explicit and promises verifiable. But a contract is a claim, not evidence. The precondition on `assign` asserts that the caller must provide a non-empty robot identifier; the postcondition asserts that the task will be in `IN_TRANSIT` afterward. These claims may be written correctly and violated by the implementation. A routine whose postcondition is stated correctly but whose body computes the wrong result does not fail because of a bad contract — it fails because the contract was never confronted with evidence.

Testing provides that evidence. A test is an experiment: choose inputs, call the routine, observe the outputs, and compare the observations against the expected behavior. When the expected and actual outputs agree, the test provides evidence — not proof, but evidence — that the routine behaves correctly for that input. When they disagree, the test has found a defect. The quality of a test portfolio depends on how well the chosen inputs cover the space of possible failures and how precisely the expected behavior is stated.

The mindset that produces good tests is not confirmation — verifying that the routine works for the inputs you expect to appear. It is exploration — discovering the inputs for which the routine fails to satisfy its contract. Confirmation produces evidence of correctness for the cases you already understood. Exploration discovers the cases you did not.


## Three Kinds of Tests

A test portfolio that covers only one kind of input is a portfolio with gaps. Three kinds of tests together provide substantially more coverage than any one kind alone.

**Example tests** are tests of concrete, known scenarios. Choose a specific input, state the expected output, call the routine, compare. For `plan_route("A", "C")`, the expected output is `"A->B->C"`. For `plan_route("C", "C")`, the expected output is `"C"`. These tests verify the nominal behavior that the routine was built to provide. They are the foundation of a test portfolio, but they are insufficient alone: they cover only the cases the developer thought of when writing them, which are typically the cases where the routine works correctly.

**Boundary tests** probe the edges of the input space — the cases that sit at or near the limits of what the routine accepts. The empty identifier is a boundary. The case where start equals destination is a boundary. The case where no path exists is a boundary. The single-element collection is a boundary. Boundary tests are the most reliable way to discover off-by-one errors, incorrect base case handling, and the implicit assumptions that example tests leave unexplored. The failure of a boundary test is evidence that the routine's behavior at the boundary was never fully defined.

**Property-based checks** assert truths that should hold across a range of inputs, rather than for a single specific input. If the route planner returns `FOUND`, the path must be non-empty. If the task's `assign` operation returns `"ASSIGNED"`, the task's status must be `IN_TRANSIT`. If the sort operation returns a sequence, no element should appear after any element with a strictly smaller priority. These are properties derivable from the contract, and they should hold for every input satisfying the precondition. Testing them across a range of inputs — generated programmatically, if the language permits, or across a set of representative cases otherwise — provides evidence that the contract holds generally, not just for the specific inputs of the example tests.


## Oracles: How a Test Decides

A test can only be as strong as its oracle — the criterion by which it decides whether the outcome is correct. A weak oracle fails to detect most meaningful defects. A strong oracle detects defects that the weak one would miss.

The weakest oracle is "did not crash." A routine that produces a wrong result without throwing an exception passes this oracle. Nearly every defect of consequence produces a wrong result rather than a crash.

A stronger oracle is a specific expected value: `result = "A->B->C"`. This oracle detects any deviation from the expected output, including wrong paths, extra nodes, and missing nodes. It is strong for the specific input it tests, and it requires that the developer knows what the correct output is before writing the test.

The strongest oracle for a component is its contract. If the postcondition of `plan_route` asserts that the returned status is always one of three declared values and that a `FOUND` result comes with a non-empty path, then a test that verifies these properties is a test that detects any implementation that violates the contract — including implementations that the developer has not specifically imagined. Contract-derived oracles extend naturally to inputs that specific expected-value oracles do not cover.

A test whose oracle is "the result equals what the implementation computed" is not a test at all. It is a transcript of execution. The oracle must be independent of the implementation: it must state what the correct output is, derived from the specification, before the implementation has run.


## From Requirement to Test Portfolio

Consider the requirement:

> *"The route planner should return a valid route or an explicit unreachable status."*

The word "valid" is not self-defining. A valid route is a connected sequence of traversable edges from the start to the destination. The contract from Chapter 27 made this precise: status is `FOUND`, `UNREACHABLE`, or `INVALID_INPUT`; a `FOUND` result's path uses only existing edges; the path's first node is the start and last node is the destination. The test portfolio explores whether the implementation satisfies this contract.

**Nominal case.** Start is `A`, destination is `C`, a path exists: `A→B→C`. Expected status: `FOUND`. Expected path: `"A->B->C"`. This is the case the routine was primarily designed for.

**Trivial boundary.** Start equals destination. Expected status: `FOUND`. Expected path: the start location alone. This is the smallest possible "found" case — a route of length zero.

**Unknown inputs.** Start is `"X"`, destination is `"Z"`, neither exists in the graph. Expected status: `INVALID_INPUT`. This tests the precondition boundary: what happens when the inputs do not satisfy the precondition.

**Disconnected graph.** Start and destination both exist but are not connected. Expected status: `UNREACHABLE`. This tests the second failure mode.

**Property check.** For any input where the returned status is `FOUND`, the path must be non-empty and the first node must be the start location. This property should hold for every `FOUND` result, not just for specific inputs.

Together, these five tests cover the three declared statuses, the boundaries between them, and a property that should hold globally. No single test could discover all the defects this portfolio can discover.


## A Test Suite in Code

```nex
class Route_Planner
feature
  plan(start_loc, end_loc: String): String
    require
      inputs_present: start_loc /= "" and end_loc /= ""
    do
      if start_loc = end_loc then
        result := start_loc
      elseif start_loc = "A" and end_loc = "C" then
        result := "A->B->C"
      else
        result := "UNREACHABLE"
      end
    ensure
      non_empty_result: result /= ""
    end
end

class Route_Planner_Tests
feature
  run_all(): String
    do
      let p: Route_Planner := create Route_Planner
      let t1: String := p.plan("A", "C")
      let t2: String := p.plan("C", "C")
      let t3: String := p.plan("X", "Z")
      if t1 = "A->B->C" and t2 = "C" 
	     and t3 = "UNREACHABLE" then
        result := "PASS"
      else
        result := "FAIL"
      end
    ensure
      known_result: result = "PASS" or result = "FAIL"
    end
end
```

`Route_Planner_Tests.run_all` is minimal but honest about what it does: it calls the routine with three inputs, compares each result to the specific expected value, and returns a single aggregate verdict. The oracle for each test is a specific expected string — `"A->B->C"`, `"C"`, `"UNREACHABLE"` — derived from the contract rather than from running the implementation.

What this sketch does not show — and what a full test suite for a production route planner would require — is coverage of the `INVALID_INPUT` case and the property-based check that `FOUND` results always have non-empty paths. The three tests here provide evidence for the nominal case and two boundary cases. They do not provide evidence that the contract holds generally. A portfolio that adds the property check and the invalid-input case approaches the coverage that the contract's three-outcome structure warrants.

The postcondition on `run_all` — `result = "PASS" or result = "FAIL"` — is the test framework's own contract: the test runner always returns a declared outcome, never an undefined one. This matters because a test runner that crashes or returns an undeclared value on a test failure provides weaker evidence than one whose own behavior is defined.


## Testing in the Three Systems

In the delivery system, the critical tests are transition legality tests: does `assign` fail correctly when called on a `DELIVERED` task? Does it succeed correctly when called on a `FAILED` task? Does the postcondition hold for every legal transition? These tests exercise the precondition boundaries of the transition operations and verify that illegal transitions are rejected at the boundary rather than silently corrupting state.

In the knowledge engine, the critical tests include determinism — does the same query on the same document collection return the same ranked result on consecutive calls? — and miss semantics — does a query that matches no documents return the declared empty result rather than null, an exception, or an empty list with no status? These are properties that example tests do not cover unless they are specifically designed to cover them.

In the virtual world, the critical tests are bounded update tests: does the state update for any tick leave every entity in a state that satisfies the world's invariants? Does the update operation behave deterministically — the same initial state always producing the same next state? These properties must hold for every tick, not just for ticks with typical inputs.

In all three systems, the test portfolio that matters is not the one that maximizes coverage of code paths. It is the one that maximizes coverage of failure modes — the portfolio designed from the contract outward, not from the implementation inward.


## Three Ways Testing Fails

**Happy-path-only testing.** A portfolio that tests only the inputs the developer expected to work provides evidence that the routine handles the cases the developer already understood. It provides no evidence about cases the developer did not consider — which is precisely the space where production failures occur. The test portfolio must be designed to find failures, not to demonstrate that expected inputs produce expected outputs.

**Fragile assertions.** A test that asserts the exact string representation of a complex result, including incidental whitespace and ordering that are not part of the contract, will fail when the formatting changes even if the behavior is correct. A test whose oracle is weaker than the contract — that asserts only that the result is non-empty rather than that it satisfies the contract's properties — will pass when the behavior is wrong. The oracle should assert exactly what the contract guarantees, no more and no less.

**Shared assumptions between test and implementation.** A test written by the same developer who wrote the implementation, immediately after writing the implementation, will often encode the same assumptions as the implementation. If the implementation misunderstands the requirement, the test will likely misunderstand it the same way. The oracle should be derived from the specification — the contract, the worked examples from Chapter 3, the edge cases from Chapter 4 — not from the implementation the developer just wrote. When possible, tests should be written before the implementation, so the oracle is derived from the requirement rather than from the code.


## Quick Exercise

Choose one routine in your system and design a test portfolio with five tests: two example tests of the nominal behavior, two boundary tests at the edges of the input space, and one property-based check derived from the postcondition that should hold across a range of inputs.

For each test, write the oracle explicitly — the specific property that the output must satisfy, stated without reference to the implementation. Then map each test to the specific failure mode it is designed to detect. If a test is not clearly targeted at a specific failure mode, revise it until it is.


## Takeaways

- Testing is exploration, not confirmation. The goal is to discover failures, not to demonstrate that the cases you already understood work correctly.
- Three kinds of tests cover different parts of the failure space: example tests for nominal behavior, boundary tests for edge cases, and property-based checks for contract-wide guarantees.
- An oracle is how a test decides whether the outcome is correct. The oracle must be independent of the implementation and derived from the specification. A weak oracle misses most meaningful defects.
- The strongest oracles are derived from contracts. A test that verifies the postcondition holds for a range of inputs provides more evidence than a test that verifies a single expected value for a single input.
- A test portfolio designed from the contract outward — asking what inputs would violate the contract — is more reliable than one designed from the implementation inward.


*Chapter 30 examines debugging as a hypothesis-driven engineering process. When tests discover a failure, debugging is the discipline of reasoning from the failure to its cause — treating the failed test as an observation that constrains the set of possible explanations, and designing experiments to narrow that set until the cause is identified.*
