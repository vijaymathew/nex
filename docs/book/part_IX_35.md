# Part IX — Programming in the Age of AI — Reviewing AI-Generated Code

## 35. Reviewing AI-Generated Code

AI can generate code quickly.

Review determines whether that code is safe, correct, and maintainable.

In AI workflows, review quality is the difference between acceleration and debt.

---

## What To Review First

Prioritize review by risk, not by line count.

High-priority review targets:

- contract correctness
- invariant preservation
- behavior regressions
- security/reliability implications
- integration boundary violations

Cosmetic style issues come after behavioral safety.

---

## Review Checklist For AI Code

A practical checklist:

1. Does it satisfy stated requirement?
2. Does it preserve existing contracts?
3. Does it handle failure states explicitly?
4. Does it introduce hidden coupling?
5. Are tests added for changed behavior?

This checklist catches most high-impact problems early.

---

## Worked Design Path

Requirement:

> "AI added retry logic to delivery dispatch."

Review steps:

1. verify retry bound exists
2. verify non-retryable errors do not loop
3. verify idempotency guard remains correct
4. verify existing success path unchanged
5. add regression tests for retry and duplicate requests

---

## Nex Implementation Sketch

```nex
class Dispatch_Port
feature
  fail_code: String

  send(task_id: String): String
    require
      id_present: task_id /= ""
    do
      if fail_code = "NONE" then
        result := "SENT"
      elseif fail_code = "TRANSIENT" then
        result := "TRANSIENT_FAILURE"
      else
        result := "PERMANENT_FAILURE"
      end
    ensure
      known_result:
        result = "SENT" or
        result = "TRANSIENT_FAILURE" or
        result = "PERMANENT_FAILURE"
    end
end

class Dispatch_With_Retry
feature
  port: Dispatch_Port

  send(task_id: String; max_attempts: Integer): String
    require
      id_present: task_id /= ""
      attempts_valid: max_attempts >= 1
    do
      let attempt: Integer := 1
      let status: String := "TRANSIENT_FAILURE"

      from
      until attempt > max_attempts or status = "SENT" or status = "PERMANENT_FAILURE"
      loop
        status := port.send(task_id)
        attempt := attempt + 1
      end

      result := status
    ensure
      known_result:
        result = "SENT" or
        result = "TRANSIENT_FAILURE" or
        result = "PERMANENT_FAILURE"
    end
end
```

Review focus here is correctness under all failure paths, not syntax.

---

## AI Review Across The Three Systems

### Delivery

- transition legality and retry safety

### Knowledge

- ranking correctness and fallback semantics

### Virtual World

- deterministic update guarantees and bound safety

Review must be domain-aware, not generic.

---

## Common Mistakes

### Mistake 1: Style-first review

Symptom:

- severe behavioral bugs survive

Recovery:

- start with contracts, invariants, failure paths

### Mistake 2: No regression focus

Symptom:

- existing behavior broken by new code

Recovery:

- compare before/after observable behavior explicitly

### Mistake 3: Missing adversarial cases

Symptom:

- retry loops, edge crashes, or invalid outputs in production

Recovery:

- add failure-oriented tests before approval

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

Take one AI-generated patch and review it with this order:

1. contract alignment
2. invariant impact
3. failure path handling
4. regression test coverage
5. maintainability concerns

Document one blocker-level issue and one minor issue.

---

## Connection to Nex

Nex contracts make semantic review more objective by converting behavior expectations into explicit checks.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- AI-generated code requires risk-first review.
- Contract and invariant validation should lead review order.
- Regression safety is non-negotiable.
- Review quality defines AI workflow reliability.

---

In Chapter 36, we close with the role of human engineering judgment in AI-assisted software development.
