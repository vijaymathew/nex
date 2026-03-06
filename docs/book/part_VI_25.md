# Part VI — Building Real Software — Object-Oriented Thinking

## 25. Object-Oriented Thinking

Functional design emphasizes transformations.

Object-oriented design emphasizes responsibility and collaboration.

When a system has rich state, lifecycle rules, and interacting actors, OOP can make behavior easier to organize.

---

## OOP As Responsibility Design

Useful OOP is not about classes for everything.

It is about assigning behavior to the objects that own the relevant state and invariants.

Good OOP boundaries answer:

- who owns this data?
- who is allowed to change this state?
- who enforces transition legality?

---

## Collaboration Protocols

Objects should collaborate through explicit protocols, not by reaching into each other's internal fields.

A robust collaboration model:

- object A asks object B for behavior via method contracts
- object B enforces its own invariants
- cross-object workflows happen in coordinators/services

This keeps state integrity local and interactions predictable.

---

## Worked Design Path

Requirement:

> "Assign a task to a robot and start movement only when both are ready."

Design:

1. `Delivery_Task` owns assignment and status transitions
2. `Robot` owns readiness state
3. `Dispatch_Service` coordinates collaboration

Contracts:

- task can be assigned only from legal states
- robot must be ready before task starts

---

## Nex Implementation Sketch

```nex
class Robot
feature
  robot_id: String
  ready: Boolean

  mark_ready() do
    ready := true
  ensure
    now_ready: ready = true
  end
invariant
  id_present: robot_id /= ""
end

class Delivery_Task
feature
  task_id: String
  status: String
  assigned_robot: String

  assign(robot_id: String)
    require
      robot_present: robot_id /= ""
      can_assign: status = "PENDING" or status = "FAILED"
    do
      assigned_robot := robot_id
      status := "IN_TRANSIT"
    ensure
      assigned: assigned_robot = robot_id and status = "IN_TRANSIT"
    end
invariant
  id_present: task_id /= ""
  valid_status:
    status = "PENDING" or
    status = "IN_TRANSIT" or
    status = "DELIVERED" or
    status = "FAILED"
end

class Dispatch_Service
feature
  dispatch(task: Delivery_Task; robot: Robot): String
    require
      robot_ready: robot.ready = true
      task_pending_or_failed: task.status = "PENDING" or task.status = "FAILED"
    do
      task.assign(robot.robot_id)
      result := "DISPATCHED"
    ensure
      known_result: result = "DISPATCHED"
    end
end
```

This pattern keeps domain invariants inside domain objects while coordinating workflows externally.

---

## OOP Across The Three Systems

### Delivery

- task and robot behavior with explicit transition methods

### Knowledge

- document and link entities with relationship management methods

### Virtual World

- world objects owning update/collision state transitions

Behavior ownership is the key strength of OOP in stateful domains.

---

## Common Mistakes

### Mistake 1: Anemic models

Symptom:

- objects store data while logic lives in services only

Recovery:

- move invariant-related behavior to owning object

### Mistake 2: God objects

Symptom:

- one class controls too much workflow and state

Recovery:

- split by responsibility and collaboration protocol

### Mistake 3: Cross-object field mutation

Symptom:

- callers directly mutate internal state of other objects

Recovery:

- expose methods with contracts instead of raw mutation

---

::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (12 Minutes)

Choose one workflow and identify:

1. state-owning objects
2. transitions each object should enforce
3. coordinator/service role
4. one illegal transition to block
5. one collaboration contract to add

Then refactor one anemic object to own one key behavior.

---

## Connection to Nex

Nex supports OOP well through class invariants and contract-checked feature routines, making behavior ownership explicit and enforceable.

---

::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- OOP is responsibility design, not class count.
- Objects should own behavior tied to their invariants.
- Coordinators should orchestrate collaboration, not own all state logic.
- Contracts make collaboration protocols explicit.

---

In Chapter 26, we formalize interface design so component collaboration stays stable as systems evolve.
