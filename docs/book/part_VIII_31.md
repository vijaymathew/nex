# Part VIII — Systems That Grow — Managing Complexity

## 31. Managing Complexity

By Studio 5, the systems are more reliable.

Now the next challenge is growth: keeping the code understandable and changeable as teams, features, and integrations expand.

Complexity is not just size. It is the cost of reasoning about behavior.

---

## Complexity As A Budget

Every system has a complexity budget.

When that budget is exceeded, symptoms appear:

- fragile changes
- long onboarding time
- unclear dependency impact
- rising incident rate after routine edits

Managing complexity means making structure intentional.

---

## Layered Reasoning

A practical layering model:

- domain layer: business rules and invariants
- application layer: orchestration/use cases
- interface/infrastructure layer: adapters, IO, transport

Key rule:

- dependencies should flow inward toward domain intent

This supports local reasoning and safer change.

---

## Worked Design Path

Requirement:

> "Add priority reroute for premium tasks without breaking normal dispatch."

Without layering, reroute logic leaks into adapters and transport code.

With layering:

1. domain service decides reroute eligibility
2. app service orchestrates reroute flow
3. adapter layer executes storage and notification

Result: policy change stays mostly in domain/application layers.

---

## Nex Implementation Sketch

```nex
class Task
feature
  task_id: String
  tier: String
  status: String
invariant
  id_present: task_id /= ""
  valid_tier: tier = "STANDARD" or tier = "PREMIUM"
end

class Reroute_Policy
feature
  should_reroute(t: Task): Boolean
    require
      task_present: t.task_id /= ""
    do
      result := t.tier = "PREMIUM" and t.status = "IN_TRANSIT"
    ensure
      bool_result: result = true or result = false
    end
end

class Dispatch_App_Service
feature
  policy: Reroute_Policy

  process_reroute(t: Task): String
    require
      task_present: t.task_id /= ""
    do
      if policy.should_reroute(t) then
        result := "REROUTE_TRIGGERED"
      else
        result := "NO_REROUTE"
      end
    ensure
      known_result: result = "REROUTE_TRIGGERED" or result = "NO_REROUTE"
    end
end
```

This design keeps rule decisions separate from infrastructure actions.

---

## Complexity Signals Across The Three Systems

### Delivery

- dispatch logic split across too many modules

### Knowledge

- ranking, retrieval, and caching tangled

### Virtual World

- update, collision, and render logic interwoven

Complexity management starts with explicit boundaries and dependency direction.

---

## Common Mistakes

### Mistake 1: Layer bypasses

Symptom:

- UI or adapter code modifies domain state directly

Recovery:

- route state changes through domain/application services

### Mistake 2: Shared utility dumping ground

Symptom:

- one module accumulates unrelated logic

Recovery:

- split by domain responsibility, not convenience

### Mistake 3: Cyclic dependencies

Symptom:

- modules require each other to compile/change

Recovery:

- extract ports/interfaces and invert dependency

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

For one subsystem:

1. map current modules
2. mark domain/application/interface boundaries
3. identify one dependency pointing the wrong direction
4. define one seam to fix it
5. estimate complexity impact

---

## Connection to Nex

Nex contracts at layer boundaries make complexity-reduction refactors safer by preserving behavior intent while structure changes.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Complexity must be managed as an explicit engineering budget.
- Layered boundaries improve local reasoning.
- Dependency direction determines long-term maintainability.
- Reliability degrades when complexity structure is ignored.

---

In Chapter 32, we focus on designing those boundaries to absorb future change safely.
