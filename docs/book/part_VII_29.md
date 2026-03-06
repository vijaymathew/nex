# Part VII — Making Software Trustworthy — Testing as Exploration

## 29. Testing as Exploration

Contracts define intended behavior.

Tests provide evidence that behavior holds across real scenarios, edge cases, and adversarial inputs.

Treating tests as exploration changes mindset from "confirm happy path" to "discover failure boundaries."

---

## What To Test

A practical test portfolio includes:

- example tests: concrete known scenarios
- boundary tests: limits and edge values
- property-oriented checks: truths that should always hold

Strong testing strategy targets risk, not just code coverage percentages.

---

## Oracles Matter

A test is only as good as its oracle (how it decides pass/fail).

Good oracles:

- explicit expected outputs
- invariants/properties that must hold
- status/contract validation

Weak oracles ("did not crash") miss most meaningful defects.

---

## Worked Design Path

Requirement:

> "Route planner should return valid route or explicit unreachable status."

Test plan:

1. nominal route exists
2. start equals destination
3. unknown nodes
4. disconnected graph
5. property check: if status is `FOUND`, path must be non-empty

This blends scenario tests with contract-inspired property checks.

---

## Nex Implementation Sketch

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

      if t1 = "A->B->C" and t2 = "C" and t3 = "UNREACHABLE" then
        result := "PASS"
      else
        result := "FAIL"
      end
    ensure
      known_result: result = "PASS" or result = "FAIL"
    end
end
```

Even simple tests become useful when expected behavior is precise.

---

## Testing Across The Three Systems

### Delivery

- transition legality and route validity

### Knowledge

- ranking determinism and miss semantics

### Virtual World

- bounded updates and deterministic step behavior

Testing should target system risks unique to each domain.

---

## Common Mistakes

### Mistake 1: Happy-path-only testing

Symptom:

- production failures from edge inputs

Recovery:

- include boundary and adversarial cases

### Mistake 2: Fragile assertions

Symptom:

- tests fail on irrelevant formatting/order noise

Recovery:

- assert meaningful properties and contracts

### Mistake 3: Test logic duplicates bugs

Symptom:

- test and implementation share same wrong assumption

Recovery:

- use independent oracle logic where possible

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

For one module, write:

1. two example tests
2. two boundary tests
3. one property-based check
4. one intentionally adversarial test

Then map each test to a specific risk it addresses.

---

## Connection to Nex

Nex contracts provide strong test oracles by turning assumptions and guarantees into executable checks.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Tests are exploration, not just confirmation.
- Good oracles are explicit and behavior-focused.
- Balanced test portfolios combine examples, boundaries, and properties.
- Contract-driven systems enable stronger, clearer tests.

---

In Chapter 30, we examine debugging as a hypothesis-driven engineering process.
