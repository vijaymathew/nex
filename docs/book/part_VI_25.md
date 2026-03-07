# Object-Oriented Thinking

Chapter 24 showed that functional thinking organizes a system as a pipeline of transformations: data flows in, computations happen, results flow out, effects are confined to the edges. This model is powerful for systems where the dominant question is *what should be computed?* But many systems also have to answer a different kind of question: *who is responsible for maintaining this constraint?* When a delivery task transitions to a delivered state, something must ensure that it cannot transition back to pending. When a robot is assigned to a task, something must ensure that the robot existed when the assignment was made. When two world objects interact, something must ensure that the interaction rules for their types are consulted.

These are questions about ownership and responsibility, and they are the domain where object-oriented thinking provides its clearest value. Not the value of classes and inheritance as syntactic features, but the value of a design discipline that assigns behavior to the objects that own the relevant state — that makes each object responsible for the invariants it is best positioned to enforce, rather than scattering that responsibility across the system.


## Responsibility as the Organizing Principle

Useful object-oriented design begins not with the question "what classes should I create?" but with the question "what is responsible for what?" Behavior belongs with the object that owns the state the behavior depends on. An object that stores delivery task status should be the object that validates status transitions. An object that stores a robot's readiness state should be the object that marks the robot ready. An object that stores a document's metadata should be the object that guards the integrity of that metadata.

This principle has a name in the design literature: the *information expert* — behavior belongs with the object that has the information needed to perform it. But the deeper reason for the principle is not organizational tidiness. It is that when behavior is separated from the state it depends on, the invariants that behavior is supposed to enforce become unenforced. A status transition that can be performed by any code anywhere in the system is a status transition that can be performed incorrectly anywhere in the system. A status transition that can only be performed through the owning object's defined operation is a transition that is checked every time.

The three questions that a good object-oriented boundary answers are: who owns this data, who is permitted to change this state, and who enforces the legality of transitions? If the same answer applies to all three, the boundary is in the right place.


## Collaboration Through Protocols

An object-oriented system is not a collection of objects that act independently. It is a collection of objects that collaborate — that ask each other for behavior and rely on each other's contracts. The discipline of collaboration is what separates a system of well-designed objects from a system where objects reach into each other's internal state and the invariants of each are at the mercy of all the others.

A well-designed collaboration protocol has two sides. The requesting object asks for a defined behavior through a method with a declared contract. The responding object performs the behavior and enforces its own invariants, returning a result through the same contract. Neither object inspects or modifies the other's internal fields. The boundary between them is the contract.

When collaborations become complex — when multiple objects must participate in a single workflow — a coordinator is the right structure. The coordinator calls each object's methods in the right order, handles the cases where any call fails, and passes results between objects. It does not own any domain state, enforce any domain invariants, or contain any domain logic. Its only responsibility is orchestration. This is the same coordinator design from Chapter 23, now applied to object collaboration rather than component assembly: the coordinator orchestrates, the objects enforce, and neither responsibility leaks into the other's domain.


## From Requirement to Object Design

Consider the requirement:

> *"Assign a task to a robot and start movement only when both are ready."*

The two entities involved — a delivery task and a robot — each have state that determines whether the workflow can proceed. The task must be in a state that permits assignment. The robot must be marked as ready. These are local conditions, owned by the respective objects. A coordinator must check both before proceeding, but the checking should be done through the objects' own interfaces, not by inspecting their internal fields directly.

**`Robot`** owns the readiness state. It exposes an operation to mark itself ready and an invariant that requires every robot to have a non-empty identifier. Whether a robot is ready is a fact about the robot, and the robot is the right place to record and expose it.

**`Delivery_Task`** owns the assignment operation and the status transitions. Assignment is only legal from the `PENDING` or `FAILED` states — attempting to assign a task that is already `IN_TRANSIT` or `DELIVERED` must be rejected. The task enforces this precondition. The invariant that the status is always one of the four declared values is also the task's responsibility to maintain.

**`Dispatch_Service`** is the coordinator. Before calling `task.assign`, it verifies that the robot is ready and the task is in an assignable state. Both verifications are expressed as preconditions on the `dispatch` operation — they are part of the coordinator's contract. The coordinator does not implement assignment logic; it calls the task's `assign` method, which implements its own.

This design enforces two distinct invariant layers. The task's invariants — valid status, legal transitions — are enforced by the task. The workflow preconditions — robot must be ready, task must be assignable — are enforced by the coordinator. Neither layer depends on the other's internals.


## An Object Collaboration in Code

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

`Robot` enforces one invariant and exposes one transition. `Delivery_Task` enforces four status values and one legal transition sequence. `Dispatch_Service` enforces the workflow-level preconditions and delegates the domain-level operation to the task itself.

The precondition on `Dispatch_Service.dispatch` — `robot.ready = true` and the task's status condition — duplicates part of the task's own precondition. This is not redundant. The coordinator's precondition is checked before `task.assign` is called; the task's precondition is checked when `task.assign` is called. The coordinator's check is the gate that prevents the call from being made when preconditions are not met; the task's check is the guarantee that its own invariants are always enforced, regardless of whether the coordinator checked first. Both are necessary, and they serve different purposes.

What the sketch does not show is equally important. No code outside `Robot` sets `robot.ready` directly. No code outside `Delivery_Task` sets `task.status` directly. The objects' internal fields are accessible to them through their own operations and to no one else. This constraint — that state changes flow through defined operations rather than through direct field assignment — is what makes the invariants enforceable. An invariant that can be bypassed by direct field mutation is an invariant in name only.


## Object-Oriented Thinking in the Three Systems

In the delivery system, `Robot` and `DeliveryTask` are the central domain objects. Each owns its own state transitions and enforces its own invariants. A dispatch coordinator orchestrates their interaction but does not duplicate their logic. The route computation from Chapter 22, which is a pure function, remains separate — it is not made into a method of either object because it does not depend on or modify either object's state.

In the knowledge engine, `Document` and `Tag` are the central domain objects. A document owns its metadata integrity and its link structure. A tag owns its label and its membership rules. A ranking coordinator calls scoring functions — which are pure — and then delivers results through an output component. The document does not know how it will be ranked; the ranker does not know how the document stores its metadata.

In the virtual world, each `WorldObject` owns its position, its current state, and the rules that govern its transitions. An interaction coordinator identifies pairs of objects that have entered each other's interaction range and calls the appropriate interaction operation on each. The objects enforce their own transition rules; the coordinator enforces the rule that interactions are triggered by proximity.

In all three systems, the design question is the same: who owns which state, and which operations are that owner's responsibility to expose and enforce? The answer to that question determines the object boundaries, and the object boundaries determine where invariants live and who is responsible for maintaining them.


## Three Ways Object-Oriented Thinking Goes Wrong

**Anemic models.** A model in which objects store data but contain no behavior — where all logic lives in external service classes that operate on the objects' fields — is an object-oriented design in syntax only. The objects have no invariants of their own, because the operations that would enforce those invariants are not methods on the objects. Status transitions are performed by external services that set fields directly. The result is a system where invariants must be re-enforced everywhere the fields are accessed, and where a single external service that sets a field incorrectly silently corrupts the model. The remedy is to move behavior into the objects that own the relevant state — to make the objects, not the services, responsible for what they are permitted to do.

**God objects.** The opposite of the anemic model is the object that has absorbed responsibilities that belong elsewhere: the task object that also handles notification delivery, or the robot object that also contains route computation logic. A god object accumulates behavior until it has multiple, unrelated reasons to change. The remedy is to ask, for each method on an object, whether that method genuinely depends on the object's state and enforces its invariants, or whether it belongs in a separate component or coordinator.

**Cross-object field mutation.** Code that modifies another object's fields directly — reaching past the object's interface to set internal state — bypasses the invariants those fields are meant to maintain. A status field set to an undeclared value by external code is a status field whose invariant has been violated. A readiness flag cleared by a coordinator without going through the robot's interface is a readiness flag whose semantics have been overridden. The discipline is to expose state changes only through defined operations with contracts, and to treat direct field mutation from outside an object as a design error.


## Quick Exercise

Choose one workflow in your system that involves two or more collaborating entities and redesign it using the three-part structure from this chapter: a state-owning object for each entity, each enforcing its own transition rules and invariants through defined operations; and a coordinator that orchestrates the workflow without owning any domain state.

For each object, identify one transition it must enforce and write the precondition and postcondition for the operation that performs it. For the coordinator, write the precondition that it must check before calling each object's operations. Then identify one place in your current implementation where domain state is being modified by code outside the owning object, and describe what would be required to route that modification through the object's interface instead.


## Takeaways

- Object-oriented design is responsibility design. The right question is not how many classes to create but who owns which state and who is responsible for which invariants.
- Behavior belongs with the object that owns the state the behavior depends on. An invariant that can only be enforced through the owning object's operations is an invariant that is always enforced.
- Collaboration happens through contracts, not field access. An object that reaches into another's internal fields has bypassed the other's invariants and introduced a coupling that will produce failures when the other changes.
- Coordinators orchestrate without owning. A coordinator that accumulates domain logic or domain state has violated the separation that makes both the coordinator and the domain objects independently understandable.
- Anemic models and god objects are opposite failures of the same principle. In both cases, responsibility and the state it governs are in different places.


*Chapter 26 formalizes interface design — the discipline of defining stable contracts between components that allows each side of a boundary to evolve independently. An interface is the promise a component makes to its callers, and the quality of that promise determines how much of the system must be understood before any part of it can be changed.*
