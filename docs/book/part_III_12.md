# Breaking Problems Apart

Complex algorithms rarely fail because a single line is wrong. They fail because too many responsibilities have been packed into one place, until the code can no longer be read as an account of what it does. The logic is present, but it is not separable — understanding any one part requires understanding all the others simultaneously, and changing any one part risks breaking something that was never intended to change at all.

Decomposition is the discipline of dividing a problem into subproblems, each with a clear responsibility and an explicit contract governing what it receives and what it returns. A well-decomposed algorithm can be understood one piece at a time, tested in isolation, and extended by adding or replacing pieces rather than rewriting the whole. These are not stylistic virtues. They are the properties that determine whether an algorithm can be maintained as requirements change.


## The Test of a Decomposition

A decomposition is not just a list of functions. Any working code can be broken into functions. The question is whether the pieces reflect the actual structure of the problem — whether each piece has a single, nameable responsibility, a contract that can be stated without reference to the implementation, and an interface that allows it to be understood and tested independently.

Three questions test whether a decomposition is genuine:

*What input does this piece require?* Not the inputs it happens to receive, but the inputs it actually depends on to do its job. A piece that reaches outside its inputs to consult shared state is not a piece with a clear interface — it is a piece with hidden dependencies that will produce surprises when the shared state changes.

*What output does it guarantee?* Not what it usually returns, but what it is obligated to return for any valid input. A piece whose output format varies depending on internal conditions it does not expose is not a piece with a stable contract.

*What is the one responsibility it owns?* If a piece has two reasons to change — if updating the scoring logic and updating the output format both require touching the same code — the decomposition is incomplete. A piece with multiple responsibilities is multiple pieces that have not yet been separated.


## Four Decomposition Patterns

Certain problem structures recur often enough that their decompositions have names. Recognizing these patterns reduces the work of decomposition from an act of invention to an act of identification.

**Pipeline.** A sequence of stages where each stage takes the output of the previous one as its input. The stages are ordered, each transforms its input in one defined way, and the final stage produces the result the caller needs. Pipelines are the natural decomposition for search, ranking, data transformation, and simulation loops — any problem where the solution is a chain of refinements applied to an initial input.

**Strategy boundary.** A stable interface around a swappable algorithm. The caller knows what the interface promises; it does not know or care which algorithm sits behind it. This pattern is appropriate when the objective the algorithm optimizes may change — when the current requirement is fewest hops but a future requirement may be minimum cost — or when the algorithm must vary by deployment context. The strategy boundary keeps the interface stable while allowing the implementation to evolve.

**Guard, core, commit.** Validate inputs and preconditions before any computation begins. Perform the core computation on verified data. Apply state changes only after the computation has succeeded. This separation prevents the most common class of partial-failure bugs: the operation that updates one piece of state and then fails before updating the rest. When the guard, core, and commit stages are distinct, it becomes possible to reason about each transition step — and to add compensation behavior when a commit cannot be completed.

**Domain and infrastructure split.** Algorithm logic and storage, transport, or rendering concerns belong in separate pieces. An algorithm that directly queries a database or formats its output for a specific UI is an algorithm whose behavior cannot be tested without the database or the UI. Separating domain logic from infrastructure concerns is not just a testing convenience — it is what makes the algorithm's correctness independent of the platform it runs on.


## A Pipeline in Practice

The following requirement will serve as a worked example:

> *"Return the top relevant notes for a query."*

A naive implementation of this feature tends to mix all its concerns into a single method: parse the query, find candidate documents, score each one, sort by score, filter out weak matches, and format the results. This implementation may be correct, but it cannot be tested in pieces, cannot have its scoring logic replaced without touching the pipeline structure, and cannot be understood without reading the entire method.

A decomposed design names each stage and gives it an explicit contract:

1. `tokenize_query(query)` — convert the raw query string into a normalized set of search terms.
2. `collect_candidates(tokens, index)` — retrieve the documents that contain at least one query term.
3. `score_candidate(candidate, tokens)` — compute a relevance score for a single candidate document.
4. `rank_candidates(scored)` — order the scored candidates from most to least relevant.
5. `filter_by_threshold(ranked, min_score)` — remove candidates whose scores fall below an acceptable floor.
6. `render_results(filtered)` — convert the filtered list into the format the caller expects.

Each stage now has a single responsibility. The scoring logic lives entirely in stage 3 and can be replaced — substituting a weighted term-frequency model for a simple match counter — without touching any other stage. The filtering threshold in stage 5 can be adjusted without affecting scoring. The output format in stage 6 can change without affecting the ranking. The decomposition has made each axis of change independent of the others.


## A Decomposition in Code

```nex
class Search_Algorithm
feature
  tokenize(query: String): String
    require
      query_present: query /= ""
    do
      result := query
    ensure
      non_empty_tokens: result /= ""
    end

  score(doc_text, tokens: String): Integer
    require
      inputs_present: doc_text /= "" 
	                  and tokens /= ""
    do
      if doc_text = tokens then
        result := 100
      else
        result := 10
      end
    ensure
      non_negative: result >= 0
    end

  choose_top(doc1, doc2, query: String): String
    require
      docs_present: doc1 /= "" and doc2 /= "" 
	                and query /= ""
    do
      let t: String := tokenize(query)
      let s1: Integer := score(doc1, t)
      let s2: Integer := score(doc2, t)

      if s1 >= s2 then
        result := doc1
      else
        result := doc2
      end
    ensure
      from_inputs: result = doc1 or result = doc2
    end
end
```

`tokenize` and `score` are independent operations, each with its own contract. `choose_top` composes them: it calls `tokenize` to normalize the query, calls `score` twice to evaluate each candidate, and selects based on the scores. The postcondition on `choose_top` — `result = doc1 or result = doc2` — guarantees that the result comes from the inputs and not from some other source, which is a meaningful guarantee even in this simplified sketch.

Notice that `choose_top` does not know how tokenization works or how scoring is computed. It knows only what each operation guarantees. This is what makes composition safe: each piece can be reasoned about through its contract, and a piece that satisfies its contract can be replaced by any other piece that satisfies the same contract.


## Four Ways Decomposition Goes Wrong

**Decomposing by syntax rather than responsibility.** A function named `process`, `handle`, or `do_stuff` is not a decomposition — it is code divided at arbitrary boundaries without regard to the structure of the problem. The sign of genuine decomposition is that each piece's name states its responsibility, and its contract can be written without reading its implementation. If naming a piece requires describing what it does rather than what it is for, the boundary was drawn in the wrong place.

**Over-fragmentation.** The opposite error is to divide a problem into so many small pieces that the decomposition itself becomes an obstacle to understanding. When a function does nothing but delegate to another function of the same name with a slightly different signature, it adds a layer of indirection without adding a layer of meaning. The test for whether a layer is genuine is whether it improves the ability to reason about the problem. Layers that exist only to satisfy a structural preference should be merged back.

**Hidden coupling between stages.** A stage that depends on the internal state of the stage before it is not an independent piece with a clear interface — it is a piece that has been separated syntactically but remains coupled semantically. The only input a stage should depend on is what is passed to it explicitly. Implicit dependencies on shared mutable state, on call order, or on properties of the implementation above or below will produce failures that are difficult to localize because they cannot be reproduced by testing the stage in isolation.

**Missing contracts at stage boundaries.** A decomposed algorithm whose stages do not have explicit contracts has most of the costs of decomposition and few of its benefits. When an intermediate result is malformed — when `tokenize` returns an empty string for a non-empty query, or when `score` returns a negative value — the failure will propagate through subsequent stages and surface far from its origin. Contracts at stage boundaries catch these failures at the point where they occur, before they have been compounded by later processing.


::: {.note-exercise}
**Quick Exercise**

Take one function in your current codebase that mixes more than one concern — a function that validates, computes, and formats, or that queries, transforms, and updates — and decompose it into three to six stages.

For each stage, write: the input contract, the output contract, and the single responsibility it owns. Then identify which stage you could replace with a different implementation without changing any other stage. If no stage is independently replaceable, the decomposition still has hidden coupling that the exercise has not yet surfaced.
:::

::: {.note-takeaways}
**Takeaways**

- Decomposition is a structural discipline, not a stylistic one. Its purpose is to make algorithms locally understandable, independently testable, and safely modifiable.
- A genuine decomposition passes three tests: each piece has a nameable input, a guaranteed output, and a single responsibility.
- Four patterns cover most cases: pipeline, strategy boundary, guard-core-commit, and domain-infrastructure split. Recognizing the pattern makes the decomposition easier to find.
- Composition is safe when it is based on contracts, not on knowledge of implementation details. A piece that knows only what its dependencies guarantee can be reasoned about independently of how those dependencies are implemented.
- A stage boundary without a contract is a seam that looks like decomposition and behaves like coupling.
:::

*The next chapter, `Thinking Recursively`, applies the decomposition principle to a specific and important class of problems: those whose structure is self-similar. Recursive algorithms are decompositions in which a problem is divided into a smaller instance of the same problem. Understanding recursion as a special case of decomposition — rather than as a separate technique — is what makes it a reliable tool rather than an occasional trick.*
