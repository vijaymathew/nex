# What Is an Algorithm?

Part II gave us a model: a precise account of what exists in a system, how the pieces relate, and what must remain true as they change. Part III turns that model into behavior. The question is no longer *what is the system about?* but *how does it compute?*

We begin with a definition. An algorithm is a finite, explicit procedure that transforms valid input into output while preserving declared guarantees. Every word in that definition carries weight. *Finite* rules out procedures that might run forever. *Explicit* rules out procedures whose steps depend on unstated assumptions. *Valid input* means the procedure is not required to behave sensibly on inputs it was never designed for. *Declared guarantees* means that what the algorithm promises is written down and checkable — not inferred from behavior and hoped to hold.

Teams that skip this definition do not avoid it. They implement it informally, inconsistently, and invisibly, and then discover its consequences when the system fails in a way nobody can explain.


## Three Things That Are Not the Same

Software development regularly conflates three distinct concepts. Keeping them separate is one of the habits that most reliably distinguishes careful engineering from productive guessing.

A **requirement** describes an outcome the system must deliver. It says nothing about how. "Return a valid route between two locations, or report that none exists" is a requirement.

An **algorithm** is the step-by-step procedure used to deliver that outcome. It is independent of any programming language and independent of any platform. Breadth-first search is an algorithm. So is Dijkstra's. Both can satisfy the same requirement; they make different tradeoffs in doing so.

An **implementation** is the concrete realization of an algorithm in a specific language, on a specific platform, using specific libraries. The same algorithm may have many implementations, differing in performance, readability, or the constraints of the runtime they target.

One requirement can be satisfied by multiple algorithms. One algorithm can be implemented in multiple ways. Mixing these levels produces conversations that cannot be resolved because the participants are arguing about different things. A debate about whether to "use Dijkstra" that is really a debate about whether weighted path cost is the right objective is a debate that will not end until someone names the level at which the disagreement lives.

For our running systems, the separation looks like this:

- **Requirement:** return a valid path, or report that no path exists.
- **Algorithm A:** breadth-first search, which finds the path with fewest hops on an unweighted graph.
- **Algorithm B:** Dijkstra's algorithm, which finds the path with minimum total cost on a weighted graph.
- **Implementation:** the same algorithm compiled to JavaScript for a browser simulation and to Java for a backend service.

The requirement is the same across all four. The algorithms answer different versions of "best." The implementations are interchangeable as far as the model is concerned.


## Correctness Is Not Approximate

A procedure that usually works is not an algorithm. It is a heuristic with a good track record on the inputs that have been tested. This distinction matters because "usually works" provides no basis for reasoning about inputs that have not been tested — which is precisely the class of inputs that will be encountered in production.

An algorithm is acceptable only when its guarantees are explicit and checkable. For each of our systems, the first question to ask of any proposed algorithm is not *does it run?* but *what does it guarantee?*

For the delivery network: does the returned route use only traversable edges? If no route exists, is failure reported explicitly, or does the algorithm return an empty path that the caller must interpret? These are not implementation details — they are the algorithm's contract with the rest of the system.

For the knowledge engine: does the ranking follow a defined scoring rule, or does it reflect whatever the implementation happened to produce? When evidence is missing or contradictory, is that handled intentionally — with a defined fallback behavior — or silently, with whatever the code happens to do by default?

For the virtual world: does each update step preserve the world's invariants? When multiple objects must be updated in the same tick, is the order deterministic? An algorithm whose output depends on update order is not an algorithm with a correctness guarantee. It is a procedure whose behavior is a function of timing.


## From Requirement to Algorithm

The following requirement will serve as a worked example:

> *"Find the best route quickly."*

This sentence is a starting point, not a specification. "Best" is undefined. "Quickly" is unmeasured. Before any algorithm can be chosen, several prior questions must be answered.

**Step 1: Define valid input.** The start and destination identifiers must refer to locations that exist in the map. The map itself must be internally consistent — no edges referencing locations absent from the node set. An algorithm given malformed input is not obligated to produce meaningful output, but the boundary between valid and invalid input must be stated explicitly, before the algorithm is written.

**Step 2: Define the output contract.** Three outcomes are possible: a path was found, no path exists, or the input was invalid. Each must be represented distinctly. An algorithm that returns an empty list for both "no path found" and "input was invalid" forces the caller to guess which situation applies. The output contract should be: `FOUND(path)` where every edge in the path is traversable, `UNREACHABLE` when the destination cannot be reached from the start, and `INVALID_INPUT` when the inputs do not satisfy the preconditions.

**Step 3: Define the objective explicitly.** For this version of the system, "best" means fewest hops. This is a decision, and it should be recorded as one. A later version might define "best" as minimum travel time, minimum energy use, or maximum reliability. Those are different objectives requiring different algorithms. The current objective is fewest hops, and the algorithm will be chosen to match it — not the other way around.

**Step 4: Choose an algorithm that matches the objective.** Fewest hops on an unweighted graph is exactly what breadth-first search computes. The match between objective and algorithm is not incidental — it is the reason BFS is the right choice here and would be the wrong choice if the objective were minimum weighted cost.

**Step 5: Declare failure semantics.** Unknown nodes produce `INVALID_INPUT`. A graph in which no path connects start to destination produces `UNREACHABLE`. These cases are handled intentionally. The algorithm does not fall through to undefined behavior when they occur.

**Step 6: Encode the contract.** The algorithm's preconditions become `require` clauses. Its output guarantee becomes an `ensure` clause. The set of valid status values becomes an invariant on the result type. The contract is now part of the code, not a comment in a design document that may or may not reflect what the code actually does.


## An Algorithm in Code

```nex
class Route_Result
create
  make(status, path: String) do
    this.status := status
    this.path := path
  end
feature
  status: String
  path: String
invariant
  valid_status:
    status = "FOUND" or
    status = "UNREACHABLE" or
    status = "INVALID_INPUT"
end

class Route_Algorithm
feature
  compute(start_loc, dest_loc: String): Route_Result
    require
      inputs_present: start_loc /= "" 
	                  and dest_loc /= ""
    do
      if start_loc = dest_loc then
        result := create Route_Result.make("FOUND", start_loc)
      elseif start_loc = "A" and dest_loc = "C" then
        result := create Route_Result.make("FOUND", "A->B->C")
      else
        result := create Route_Result.make("UNREACHABLE", "")
      end
    ensure
      status_is_declared:
        result.status = "FOUND" or
        result.status = "UNREACHABLE" or
        result.status = "INVALID_INPUT"
    end
end
```

This sketch is deliberately minimal — the hardcoded paths are a placeholder for a graph traversal that is not yet the point. What deserves attention is the structure surrounding the algorithm rather than its interior.

`Route_Result` carries an invariant that makes it impossible to construct a result with an undeclared status. The `compute` operation has a precondition that rejects empty inputs before any traversal begins. Its postcondition guarantees that whatever the interior of the algorithm does, the returned status will always be one of the three declared values. The contract is enforced at the boundary, independently of the implementation — which means it will continue to hold when the hardcoded cases are replaced by a real graph traversal, and will continue to hold again when that traversal is later replaced by a faster one.

This is the pattern: specify the contract first, implement the interior second, verify that the contract holds regardless of what the interior does.


## Three Ways Algorithm Design Goes Wrong

**Calling any working script an algorithm.** A script that produces correct output for the inputs that have been tried is not an algorithm with a correctness guarantee — it is a program with a favorable history. The difference becomes visible when the inputs change. An algorithm whose steps are stated explicitly and whose guarantees are declared can be analyzed for correctness. A script whose behavior emerges from accumulated control flow can only be tested, and testing covers only the cases already imagined. The recovery is to write language-agnostic steps before writing code, and to treat the code as an implementation of an algorithm rather than as the algorithm itself.

**Undefined failure behavior.** An algorithm that returns null, an empty list, or a sentinel value for both "success with empty result" and "operation failed" forces every caller to guess which situation applies — and different callers will guess differently. The system accumulates implicit conventions about what empty results mean, conventions that are never fully consistent and that break silently when a new caller is added. The recovery is to declare distinct output statuses for each possible outcome and to test each one explicitly.

**Ambiguous objectives.** An algorithm chosen to optimize for one criterion will perform incorrectly by the standard of a different one. When "best route" is never defined to mean fewest hops, minimum cost, or fastest traversal, the team cannot agree on which algorithm is correct because they are not yet asking the same question. The recovery is to encode one objective explicitly in the current specification, document the alternatives that were considered and deferred, and treat a change in objective as a design decision that requires revisiting the algorithm — not a bug fix in the existing one.


::: {.note-exercise}
**Quick Exercise**

Choose one operation in your system and write a complete algorithm specification in five parts: the valid input definition, the output contract (naming each possible outcome distinctly), the objective function, a language-agnostic description of the steps, and one contract you will encode in the implementation.

Then apply this test: give your specification to another engineer and ask them to implement it independently. If their implementation makes different choices about failure behavior, output representation, or what the objective optimizes for, the specification is not yet precise enough to be called an algorithm description. Find the ambiguity and resolve it before writing code.
:::

::: {.note-takeaways}
**Takeaways**

- An algorithm is a finite, explicit procedure with declared guarantees — not any procedure that produces plausible output on familiar inputs.
- Requirement, algorithm, and implementation are three distinct levels. Conflating them makes design conversations unresolvable and makes changes expensive.
- Correctness means guarantees that are explicit and checkable. A procedure that usually works under normal conditions is not correct in any useful sense of the word.
- Failure semantics are part of the algorithm's contract. Each distinguishable failure mode deserves a distinct, named output.
- The objective an algorithm optimizes must be stated before an algorithm is chosen. Different objectives require different algorithms; changing the objective silently is a design error, not an implementation detail.
:::



*The next chapter, `Breaking Problems Apart`, examines decomposition: how to take a complex algorithmic problem and divide it into pieces that can be designed, implemented, and tested independently. The discipline of decomposition is what separates algorithms that can be reasoned about from algorithms that can only be run.*
