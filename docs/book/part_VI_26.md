# Part VI — Building Real Software — Designing Interfaces

## 26. Designing Interfaces

By this point we have:

- algorithms
- data structures
- components
- functional and object-oriented design tools

Interfaces are what keep these pieces composable over time.

A weak interface leaks internals, couples modules, and blocks safe evolution.

---

## Interface Design Goals

A good interface should be:

- minimal: only expose necessary operations
- explicit: make assumptions and failure modes clear
- stable: avoid forcing callers to track internals
- testable: behavior can be validated through contract

Interfaces are not just signatures; they are behavioral agreements.

---

## Contract-First APIs

Interface contracts should define:

- input preconditions
- output guarantees
- error/failure semantics
- invariants that remain true across calls

When these are missing, integration bugs move from compile-time to production-time.

---

## Worked Design Path

Requirement:

> "Expose route planning to multiple clients (web UI, scheduler, simulator)."

Bad approach:

- expose internal graph structures and mutable route buffers

Better approach:

- expose one operation: `plan_route(start, goal)`
- return explicit result status and path
- hide internal algorithm/data structure choices

Now algorithm upgrades do not break clients.

---

## Nex Implementation Sketch

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

Clients depend on stable response contracts, not internal planner details.

---

## Interface Design Across The Three Systems

### Delivery

- route/task APIs with explicit statuses

### Knowledge

- retrieval/ranking APIs with confidence and miss semantics

### Virtual World

- update/query APIs with deterministic boundary guarantees

Strong interfaces preserve flexibility behind the boundary.

---

## Common Mistakes

### Mistake 1: Leaking internals

Symptom:

- callers manipulate internal structures directly

Recovery:

- return value objects and status codes, not internal state handles

### Mistake 2: Ambiguous failure modes

Symptom:

- same return shape used for success and failure

Recovery:

- define explicit statuses and per-status expectations

### Mistake 3: Over-wide interfaces

Symptom:

- many methods with overlapping behavior

Recovery:

- narrow interface to essential use cases

### Mistake 4: Versioning by breakage

Symptom:

- small internal change breaks many consumers

Recovery:

- preserve stable contracts, introduce additive evolution

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

Pick one module interface and document:

1. operation signature
2. preconditions
3. success guarantees
4. failure statuses
5. one internal detail that must remain hidden

Then write one integration test that validates only contract behavior.

---

## Connection to Nex

Nex contracts make interface agreements explicit and executable, reducing integration ambiguity across modules and teams.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Interfaces are behavioral contracts, not just method names.
- Contract-first design prevents integration ambiguity.
- Stable interfaces enable internal algorithm/data evolution.
- Narrow, explicit interfaces improve long-term system adaptability.

---

Part VI prepared the architecture layer: components, functional decomposition, object collaboration, and interfaces.

Next is **Studio 4 — The Architecture Refactor**, where these ideas are applied to a system-wide redesign.
