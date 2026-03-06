# Part III — The Shape of Algorithms — Breaking Problems Apart

## 12. Breaking Problems Apart

Complex algorithms rarely fail because a single line is wrong.

They fail because too many responsibilities are packed into one block, making behavior hard to reason about and harder to change safely.

Decomposition is the discipline of splitting a problem into subproblems with clear interfaces and guarantees.

A strong decomposition gives you:

- local reasoning (understand one piece at a time)
- independent testing (verify each stage in isolation)
- replaceability (swap strategy without rewriting everything)

---

## A Practical Decomposition Rule

For each part, be able to answer three questions:

1. What input does it require?
2. What output does it guarantee?
3. What is the one responsibility it owns?

If a part has multiple reasons to change, decomposition is incomplete.

---

## Common Decomposition Patterns

### Pipeline

A sequence of stages where output from stage `n` feeds stage `n+1`.

Good for search, ranking, transformation, and simulation loops.

### Strategy Boundary

Stable interface, swappable algorithm.

Good when objective or scale may evolve.

### Guard -> Core -> Commit

- validate assumptions
- perform core computation
- apply state changes

Good for state transitions with side effects.

### Domain vs Infrastructure Split

Keep algorithm logic separate from storage/transport/UI concerns.

Good for testability and maintainability.

---

## Worked Design Path

Requirement:

> “Return top relevant notes for a query.”

Naive implementation often mixes all concerns in one method:

- parsing
- candidate generation
- scoring
- ranking
- filtering
- formatting

A decomposed design:

1. `tokenize_query(query)`
2. `collect_candidates(tokens, index)`
3. `score_candidate(candidate, tokens)`
4. `rank_candidates(scored)`
5. `filter_by_threshold(ranked, min_score)`
6. `render_results(filtered)`

Now each stage has explicit contracts and can be tested independently.

---

## Nex Implementation Sketch

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
      inputs_present: doc_text /= "" and tokens /= ""
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
      docs_present: doc1 /= "" and doc2 /= "" and query /= ""
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

Even this small sketch shows decomposition: tokenization and scoring are separate, then composed.

---

## Common Mistakes

### Mistake 1: Decomposing by syntax, not responsibility

Symptom:

- helper names like `process`, `handle`, `do_stuff`

Recovery:

- rename functions by responsibility
- enforce one reason to change per stage

### Mistake 2: Over-fragmentation

Symptom:

- many tiny wrappers with no semantic value

Recovery:

- merge layers that do not improve reasoning

### Mistake 3: Hidden coupling between stages

Symptom:

- stage B depends on internals of stage A

Recovery:

- pass explicit values only
- eliminate implicit shared state where possible

### Mistake 4: Missing stage contracts

Symptom:

- malformed intermediate data causes late failures

Recovery:

- add pre/post conditions at key boundaries

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (10 Minutes)

Take one large function in your codebase and decompose it into 3-6 stages.

For each stage, write:

1. input contract
2. output contract
3. failure behavior
4. one test case

Then identify one stage you could replace without touching others.

If nothing is replaceable, coupling is still too strong.

---

## Connection to Nex

Nex contracts are especially useful at decomposition boundaries because they make handoff assumptions explicit and executable.

This is also where AI-assisted coding improves: clear stage contracts reduce incorrect glue code.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Decomposition is an engineering necessity, not style preference.
- Each stage should have one responsibility and explicit contracts.
- Replaceable components require stable interfaces.
- Good decomposition lowers change risk and improves test quality.

---

In Chapter 13, we apply decomposition to self-similar problems through recursion.
