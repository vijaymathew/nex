# Part VI — Building Real Software — From Algorithms to Components

## 23. From Algorithms to Components

Part V helped us choose good algorithms.

Part VI is about turning those algorithms into maintainable software systems.

An algorithm can be correct and fast, but still fail in production if component boundaries are unclear.

---

## Why Components Matter

Components give structure to change.

A useful component boundary:

- groups related responsibilities
- hides internal data/steps
- exposes stable behavior contracts

Without boundaries, systems drift into tightly coupled code where every change risks unrelated behavior.

---

## Boundary Design Rules

A practical rule:

- high cohesion inside a component
- low coupling across components

Questions to ask:

- Does this component have one clear reason to change?
- Are dependencies flowing in a consistent direction?
- Can we test this component in isolation?

---

## Worked Design Path

Requirement:

> "Compute best route and notify clients when route changes."

Naive design mixes concerns in one service:

- route computation
- persistence updates
- notification delivery

Componentized design:

1. `Route_Component` (algorithm + route rules)
2. `Task_Component` (task state transitions)
3. `Notify_Component` (message delivery)
4. `Coordinator` (workflow orchestration)

Benefits:

- route algorithm can evolve without rewriting notification logic
- failures are localized by component contract

---

## Nex Implementation Sketch

```nex
class Route_Component
feature
  compute(start_loc, end_loc: String): String
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
      result_present: result /= ""
    end
end

class Notify_Component
feature
  send(task_id, message: String): String
    require
      inputs_present: task_id /= "" and message /= ""
    do
      result := "SENT"
    ensure
      status_known: result = "SENT" or result = "FAILED"
    end
end

class Delivery_Coordinator
feature
  route: Route_Component
  notify: Notify_Component

  reroute_and_notify(task_id, start_loc, end_loc: String): String
    require
      inputs_present: task_id /= "" and start_loc /= "" and end_loc /= ""
    do
      let p: String := route.compute(start_loc, end_loc)
      if p = "UNREACHABLE" then
        result := "NO_ROUTE"
      else
        result := notify.send(task_id, "route=" + p)
      end
    ensure
      known_result: result = "NO_ROUTE" or result = "SENT" or result = "FAILED"
    end
end
```

This sketch separates algorithm logic from side effects while preserving end-to-end behavior.

---

## Components Across The Three Systems

### Delivery

- routing, task state, notification as separate components

### Knowledge

- retrieval, ranking, rendering as separate components

### Virtual World

- simulation, collision, event output as separate components

Componentization is where algorithm quality becomes software quality.

---

## Common Mistakes

### Mistake 1: Utility blob component

Symptom:

- one module owns unrelated responsibilities

Recovery:

- split by reason-to-change

### Mistake 2: Leaky boundaries

Symptom:

- callers depend on internal fields/steps

Recovery:

- expose narrow interface contracts only

### Mistake 3: Wrong dependency direction

Symptom:

- core domain depends on transport/UI details

Recovery:

- invert dependencies through interfaces/adapters

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

Take one workflow and map:

1. core algorithm component
2. state component
3. side-effect component
4. coordinator/orchestrator
5. contract for each boundary

Then identify one dependency you should reverse.

---

## Connection to Nex

Nex contracts make component boundaries explicit and executable, improving refactor safety as systems grow.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Good algorithms need good component boundaries.
- Cohesion and coupling are operational concerns, not style preferences.
- Coordinators should orchestrate, not absorb all logic.
- Contracts stabilize component interactions.

---

In Chapter 24, we examine functional thinking as a strategy for composable and testable components.
