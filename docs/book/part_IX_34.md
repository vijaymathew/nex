# Part IX — Programming in the Age of AI — Working With AI Coding Assistants

## 34. Working With AI Coding Assistants

AI coding assistants can accelerate implementation, but speed without structure creates fragile systems.

The key lesson is not "use AI" or "avoid AI." It is: use AI within engineering constraints.

---

## AI As A Collaborator, Not An Authority

An assistant is strongest when given:

- clear problem framing
- explicit contracts and invariants
- architecture boundaries
- acceptance criteria

If prompts are vague, outputs are usually plausible but misaligned.

---

## Constraint-Driven Prompting

Good prompts define:

- required behavior
- non-negotiable constraints
- integration boundaries
- validation steps

Example prompt pattern:

1. task goal
2. existing interfaces/contracts
3. forbidden changes
4. required tests/checks

This keeps AI output aligned with system intent.

---

## Worked Design Path

Requirement:

> "Add fallback behavior to knowledge query service without changing current interface."

AI workflow:

1. provide current interface contract
2. specify allowed internal changes only
3. request fallback logic with explicit statuses
4. require regression checks for existing behavior
5. review output for contract adherence

This produces usable drafts and reduces integration rework.

---

## Nex Implementation Sketch

```nex
class Knowledge_Query_Service
feature
  query(q: String): String
    require
      query_present: q /= ""
    do
      if q = "contracts" then
        result := "DOC:K-101"
      else
        result := "UNAVAILABLE"
      end
    ensure
      non_empty: result /= ""
    end
end

class Knowledge_Fallback_Wrapper
feature
  core: Knowledge_Query_Service

  query_safe(q: String): String
    require
      query_present: q /= ""
    do
      let raw: String := core.query(q)
      if raw = "UNAVAILABLE" then
        result := "DOC:CACHED-001"
      else
        result := raw
      end
    ensure
      non_empty: result /= ""
    end
end
```

This pattern is ideal for AI assistance: bounded scope, stable interface, explicit behavior.

---

## AI Workflow Across The Three Systems

### Delivery

- propose route policy variants under fixed dispatch contract

### Knowledge

- propose ranking/fallback variants under stable query API

### Virtual World

- propose update-rule variants under bounded state invariants

In each case, constraints define safe collaboration.

---

## Common Mistakes

### Mistake 1: Prompting without system context

Symptom:

- output conflicts with architecture or contracts

Recovery:

- include interfaces, invariants, and exclusions in prompt

### Mistake 2: Accepting first draft blindly

Symptom:

- subtle regressions discovered later

Recovery:

- require parity checks and targeted tests

### Mistake 3: Asking AI to redesign everything at once

Symptom:

- high-risk broad changes with weak validation

Recovery:

- scope to one bounded change per iteration

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (10 Minutes)

Choose one small change and write an AI prompt containing:

1. objective
2. current contract
3. constraints/forbidden changes
4. expected tests
5. acceptance criteria

Then compare AI output quality with and without constraints.

---

## Connection to Nex

Nex contracts and invariants are excellent prompt anchors because they encode behavior intent in machine-checkable form.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- AI assistants are most effective under explicit engineering constraints.
- Prompt quality directly affects code quality.
- Contracts and interfaces are the control surface for safe AI collaboration.
- Human review remains mandatory.

---

In Chapter 35, we focus on systematic review of AI-generated code.
