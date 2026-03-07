# Chapter 10: Modeling Change

The models built in Chapters 7 through 9 describe a system at rest: what entities exist, how they relate, what invariants must hold. This is necessary. It is not sufficient.

Real systems do not rest. Robots move, tasks are reassigned, documents are updated, objects collide. The system is in constant transition between states, and the most severe bugs in any real codebase are not errors in stored data — they are errors in how data moves from one state to another. Two updates race and both succeed. One step in a multi-step operation fails after the first step has already committed. Events arrive out of order and state reflects the order of arrival rather than the order of occurrence. A partial write leaves the system in a configuration no design ever intended.

If a model does not describe change — not just what states are valid, but how the system may move between them — then correctness during transitions is accidental. The system works until it encounters a transition the model never considered, at which point it fails in a way no invariant will catch.

---

## States and Transitions

The solution is to model transitions as first-class design objects, with the same discipline we brought to entities and relationships.

A complete model of change for any entity requires four elements. First, the set of legal states the entity can occupy — for a delivery task, `PENDING`, `IN_TRANSIT`, `DELIVERED`, and `FAILED`. Second, the legal transitions between those states: which moves are permitted and which are not. Third, the preconditions each transition requires: what must be true of the world before the transition may occur. Fourth, the postconditions each transition guarantees: what will be true of the world once it completes.

For the delivery task, the legal transitions form a directed graph: `PENDING → IN_TRANSIT`, `IN_TRANSIT → DELIVERED`, `IN_TRANSIT → FAILED`, and `FAILED → IN_TRANSIT` (for reassignment). The transition `DELIVERED → PENDING` is not in this graph. That absence is a design decision. Making it explicit — not just absent from the code, but documented as forbidden — is what prevents a future developer from adding it under the mistaken belief that it was merely overlooked.

Think in terms of state machines, even for the simplest entities. The exercise of drawing the graph forces questions that informal descriptions leave unasked: can a failed task be retried? Can a pending task be cancelled before assignment? Each answer is a design decision, and each decision should appear in the model.

---

## The Problem of Ordering

Change introduces time, and time introduces ordering. The correctness of a transition often depends not just on the current state of one entity, but on the sequence in which a series of events occurred. When that sequence is not controlled, the system's behavior becomes a function of timing rather than design.

Three examples from our running systems make this concrete.

In the knowledge engine, relevance scores are updated in response to indexing events. If those events arrive out of order — if a document update is processed before the initial indexing that created the document's baseline — the resulting scores may be inconsistent, and ranking may oscillate between queries until the system converges or a developer investigates.

In the virtual world, collision resolution must happen in a defined relationship to movement. If some objects resolve collisions before their positions are updated for the current tick and others resolve collisions after, the simulation's outcome depends on which set a given object belongs to. Two runs of the same scenario produce different results. The simulation is nondeterministic not because the physics are random but because the model never specified an ordering.

In the delivery system, reassignment of a failed robot's tasks must be strictly ordered with respect to the failure event itself. If reassignment begins before the robot's status is committed as `FAILED`, two robots may independently be assigned the same task — each transition individually valid, the combined outcome impossible according to the invariants.

Modeling change means specifying deterministic ordering for sequences that must be deterministic, and specifying conflict policy for the cases where ordering cannot be guaranteed.

---

## From Requirement to Transition Model

Consider the requirement:

> *"When a robot fails mid-delivery, tasks should be reassigned automatically."*

This sentence describes an outcome. It conceals a sequence of events, each of which can fail independently, and several of which can interact badly if they are not carefully ordered.

**Step 1: Identify the transition events.** A robot's heartbeat is missed. The robot's status is set to `FAILED`. Its active tasks are discovered. Reassignment is attempted for each. This is not one event — it is a chain, and the chain has gaps.

**Step 2: Define preconditions for the critical transition.** Before a task can be reassigned, three things must be true: the task's current status is `IN_TRANSIT` or `FAILED`, the robot currently assigned to it is unavailable, and a candidate replacement exists. Each precondition is checkable before the transition begins. If any fails, the transition does not proceed — it is rejected cleanly rather than failing partway through.

**Step 3: Define postconditions.** After reassignment completes, the task is assigned to exactly one robot, the task's status is valid, and the reassignment event is auditable — recorded in enough detail that the reason for the change can be reconstructed later. Postconditions are not just documentation; they are the specification that tests will verify.

**Step 4: Define failure behavior.** If no replacement robot is available, the task is marked `FAILED` with an actionable reason attached. The system does not leave the task in a half-reassigned state — no assigned robot identifier pointing to a failed robot, status still reading `IN_TRANSIT`. That half-state is worse than acknowledged failure because it is invisible: it will not trigger an alert, but it will also never be resolved.

**Step 5: Design for idempotency.** In any distributed system, events may be delivered more than once. A failure event that is processed twice should produce the same outcome as a failure event processed once. An idempotent reassignment operation — one that checks whether the task is already in the target state before acting — provides this property. Idempotency is not an optimization; it is a reliability multiplier that makes the entire event processing pipeline safer.

---

## A Transition Model in Code

```nex
class Task_Transition
feature
  status: String
  assigned_robot_id: String

  reassign(new_robot_id: String)
    require
      robot_present: new_robot_id /= ""
      transition_allowed: status = "IN_TRANSIT" or status = "FAILED"
    do
      assigned_robot_id := new_robot_id
      status := "IN_TRANSIT"
    ensure
      reassigned: assigned_robot_id = new_robot_id and status = "IN_TRANSIT"
    end

  mark_delivered()
    require
      must_be_in_transit: status = "IN_TRANSIT"
    do
      status := "DELIVERED"
    ensure
      delivered: status = "DELIVERED"
    end
invariant
  valid_status:
    status = "PENDING" or status = "IN_TRANSIT" or status = "DELIVERED" or status = "FAILED"
end
```

The structure of this sketch mirrors the four elements of a complete transition model. The invariant defines the legal states. The two operations define legal transitions. Each operation's `require` clause defines preconditions; each `ensure` clause defines postconditions. A call to `reassign` that violates its precondition does not execute — the contract is enforced before the operation begins, not discovered after it completes.

What is absent is also significant. There is no operation `mark_pending` that moves a delivered task back to the start. The absence is not an oversight; it is a design decision made explicit by the model. A developer reading this code understands not just what is permitted but, from what is missing, what is forbidden.

---

## Five Ways Change Modeling Fails

**Transition logic scattered across the codebase.** When the rules governing state changes are spread across services, controllers, and event handlers, there is no single place where the complete set of legal transitions is visible. Different parts of the system enforce different subsets of the rules, and the gaps between them are where illegal states enter. The recovery is to centralize transition rules in model-level operations with explicit contracts, and to route all state changes through them.

**Silent partial failure.** A multi-step operation that updates one field successfully and then exits on an error leaves the system in a state that is neither the state before the operation nor the intended state after it. This half-state may be invisible to monitoring and will not be resolved by retry. The recovery is to define atomic boundaries where the underlying platform supports them, and to define explicit compensation states — states that make the failure visible and actionable — where it does not.

**Unhandled event reprocessing.** In any system that processes events asynchronously, the same event may be delivered more than once. An operation that is not idempotent will produce different results on the second delivery than on the first, creating state that nobody designed. The recovery is to design transition operations to be idempotent where possible, and to track event identity — processing each distinct event at most once — where idempotency cannot be achieved.

**Undefined conflict policy.** When two concurrent updates target the same entity, the system must have a rule for which one wins. Without such a rule, the outcome is determined by timing, and the same inputs may produce different outputs on different runs. The conflict policy — last write wins, highest priority wins, earlier timestamp wins — is a model-level decision, and it belongs in the model, not in the implementation of whichever operation happened to be written last.

**No audit trail for transitions.** When a system reaches an invalid state and nobody can explain how it got there, the investigation begins from nothing. An audit trail that records the intent and outcome of each transition — which operation was attempted, what the preconditions were, whether the postconditions were verified — makes invalid states diagnosable. More importantly, it reveals which part of the transition model failed to prevent them.

---

## Quick Exercise

Choose one entity from your system and construct a complete transition model for it with six parts: all legal states, all allowed transitions between them, one explicitly forbidden transition and the reason it is forbidden, preconditions for one transition, postconditions for the same transition, and the behavior when that transition cannot complete because its preconditions are not met.

Then ask: if the event that triggers this transition were delivered twice, would the second delivery produce a different outcome than the first? If yes, what must be added to make the operation idempotent?

---

## Takeaways

- Most real failures occur during state transitions, not in stored state. A model that describes states without describing transitions is incomplete.
- Legal transitions must be explicit and enforced. Absent transitions are design decisions, not omissions, and should be recognizable as such.
- Ordering, conflict policy, and idempotency are model-level concerns. They cannot be reliably addressed in implementation if they were never addressed in design.
- Partial failure must be designed for, not improvised around. Half-states that are invisible to monitoring are more dangerous than acknowledged failures.
- Change is structured behavior. Treat it as such from the beginning.

---

*Part II has now built a complete modeling foundation: entities, relationships, data models, and the semantics of change. Part III shifts from representation to computation — what algorithms are, how to decompose problems into them, and how to reason about their behavior under real conditions.*
