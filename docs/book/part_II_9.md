# Designing a Good Data Model

Chapters 7 and 8 gave us the two building blocks of a model: entities, which carry identity and state, and relationships, which encode the structure between them. A data model is what these pieces become when assembled into a coherent design — one that must hold together not just on paper, but under the pressure of real queries, real load, and real change.

The goal is not the most abstract model or the most general one. It is the model that keeps the system correct, understandable, and evolvable as usage evolves. Those three properties are in genuine tension, and resolving that tension requires making explicit choices rather than deferring them.


## Five Properties of a Good Model

A data model that serves a system well over time satisfies five properties.

**It represents reality adequately for the problem's scope.** Not perfectly — a model that tries to represent every nuance of the domain it describes becomes as complex as the domain itself. The right model captures the distinctions that matter for the operations the system must perform, and deliberately ignores the rest.

**It supports critical operations efficiently.** A model that is logically correct but operationally unusable at scale is not a good model; it is a design that deferred a hard problem. The access patterns the system depends on most heavily should be visible in the model's structure.

**It protects invariants against invalid states.** The value of an invariant is precisely that it holds everywhere, not just where the developer remembered to check. A model that encodes invariants as conventions — things programmers agree to uphold rather than things the system enforces — will eventually produce the impossible states that conventions cannot prevent.

**It allows change without destructive rewrites.** Requirements change. The model must be able to absorb that change incrementally. A model that is correct today but can only be extended by dismantling its existing structure will be replaced, or worked around, rather than evolved.

**It remains explainable to new engineers.** A model that can only be understood by the person who built it is a liability. Explainability is not a soft requirement; it is what determines whether the model continues to be used correctly as the team changes.


## Model from Operations, Not from Nouns Alone

The most common failure in data modeling is to begin with a list of entities — the nouns in the domain — and build structure around them without asking how the data will actually be used. This produces models that are logically tidy and operationally expensive, because the access patterns the system needs were never consulted during design.

The corrective practice is to ask three questions before the model is finalized:

*What are the most frequent read paths?* For a knowledge engine where the dominant operation is retrieving notes by tag and recency, the model needs a tag index path, a recency field, and a defined ranking input. Without these, code accumulates ad hoc caches and special cases to compensate for a model that was never shaped for this query.

*What are the most frequent write and update paths?* For a delivery system where reassignment is a routine operation, the model needs to make the assigned-robot relationship easy to update and the validity of an update easy to check. A model that makes reassignment expensive is a model that will be bypassed.

*What invariants must hold through both reads and writes?* The invariants that protect the model during reads — a task's status must be one of a defined set — also constrain writes. A write that violates an invariant must be rejected before it completes, not discovered afterward.

Designing from operations does not mean ignoring the domain. It means ensuring that the domain model and the operational reality stay aligned from the beginning, rather than diverging and requiring reconciliation later.


## Tradeoffs That Must Be Made Explicit

No model of a real system is free of tradeoffs. The failure is not in making tradeoffs — it is in making them implicitly, so that their consequences are discovered rather than anticipated.

**Normalization versus duplication.** A fully normalized model stores each fact in exactly one place, which makes updates clean and consistency easy to guarantee. It also means that queries requiring data from multiple entities must traverse relationships, which increases read complexity. A model that duplicates data for read performance trades update complexity for query simplicity. Neither choice is universally correct; both choices need to be documented so that future developers understand why the model has the shape it does.

**Generality versus simplicity.** A highly generic schema — one that represents all possible relationships as typed edges, or all possible attributes as key-value pairs — can accommodate future requirements that the current model does not anticipate. It also makes the current requirements harder to express, harder to validate, and harder to query efficiently. The right level of generality is the minimum needed to handle the requirements that are actually known, plus a small margin for the changes that are already foreseeable.

**Strictness versus flexibility.** Strict constraints prevent invalid states. They also mean that workflows requiring temporarily incomplete data — a delivery task created before a robot is assigned, a document link created before its target exists — must be designed explicitly. Relaxing constraints to accommodate these workflows trades correctness guarantees for operational convenience. This tradeoff is real and sometimes the right one; what matters is that it is chosen deliberately and that the consequences are understood.


## From Requirement to Data Model

The following requirement will serve as a worked example:

> *"Show live delivery status and allow reassignment when robots fail."*

Two distinct operations are embedded in this sentence: a read operation (show live status) and a write operation (reassign tasks when a robot fails). A good model must support both.

**Step 1: Identify core entities.** Robot, DeliveryTask, Location. These are the identity-bearing things the system must track.

**Step 2: Define relationships.** A delivery task references its origin and destination locations. A delivery task optionally references an assigned robot — optionally, because a task exists before it is assigned and may exist again after reassignment. The cardinality of the assignment relationship is one-to-one for active tasks: a robot may hold at most one active task at a time.

**Step 3: Define operational queries.** Find the active task for a given robot. Find the highest-priority pending task available for assignment. Find all active tasks belonging to a failed robot and mark them available for reassignment. These three queries determine which fields the model needs and how they need to be indexed.

**Step 4: Shape the model for those queries.** The task needs a status field, a priority field, and an assigned robot identifier. The status field drives the first and third queries; the priority and status fields together drive the second.

**Step 5: Protect invariants.** A delivered task cannot be reassigned. A task in transit must have an assigned robot. Origin and destination must reference valid locations. These are the constraints that the model must enforce, not merely assume.

**Step 6: Plan for evolution.** Future requirements already visible on the horizon include multi-robot cooperative delivery, regional routing constraints, and service-level classes. The current model should not implement these, but it should not foreclose them either. The task's assignment relationship should accommodate multiple assignees when that requirement arrives. The priority field should be defined broadly enough that SLA classes can be expressed through it. The goal is not to build for the future now — it is to avoid building something today that prevents the future from being built at all.


## A Data Model in Code

```nex
class Delivery_Model
feature
  task_id: String
  assigned_robot_id: String
  status: String
  priority: Integer

  can_reassign: Boolean do
    result := status = "PENDING" 
	          or status = "FAILED"
  end

  mark_in_transit(robot_id: String)
    require
      has_robot: robot_id /= ""
      legal_transition: can_reassign
    do
      assigned_robot_id := robot_id
      status := "IN_TRANSIT"
    ensure
      transit_set: status = "IN_TRANSIT" 
	               and assigned_robot_id = robot_id
    end

create
  pending(task_id: String) do
    this.task_id := task_id
	status := "PENDING"
  end
invariant
  task_id_present: task_id /= ""
  priority_non_negative: priority >= 0
  valid_status:
    status = "PENDING" or status = "IN_TRANSIT" 
	or status = "DELIVERED" or status = "FAILED"
end
```

This sketch is worth reading against the six steps above. The status and priority fields answer Step 4. The three invariants answer Step 5. The `can_reassign` query answers the first part of Step 3 — it codifies the rule that only `PENDING` and `FAILED` tasks are available for reassignment, making that rule checkable before `mark_in_transit` is called. The precondition on `mark_in_transit` answers the second part: a transition into `IN_TRANSIT` requires both a valid robot identifier and a legal starting status.

What the sketch does not address is the storage backend, the query execution strategy, or the index layout. Those are implementation concerns. The model's job is to define what is true; the implementation's job is to enforce and compute it efficiently.


## Four Ways Data Model Design Fails

**Mirroring the UI rather than the domain.** When the data model is shaped to match the structure of a particular screen or interface, it becomes fragile in exactly the way interfaces are fragile: a UI redesign requires a model change, which requires a migration, which propagates through every system that depends on the model. The recovery is to model domain semantics first and treat every interface as a projection of the model, not a template for it.

**Missing transition rules.** When the operations that change entity state are not part of the model — when status updates are just field assignments with no enforced preconditions — illegal state combinations appear in production. A delivered task becomes pending; an in-transit task loses its assigned robot. The recovery is to encode transitions explicitly, as operations with preconditions and postconditions, and to verify them with tests that attempt the illegal transitions.

**Performance as an afterthought.** A model designed without consulting its dominant access patterns will be correct and slow. At the point where performance becomes unacceptable, the model has usually hardened into the system's foundation, and adjusting it is expensive. The recovery — which is really a prevention — is to identify the critical query paths during modeling, not after deployment.

**No versioning strategy.** Models change. Fields are added, relationships are restructured, invariants are tightened or relaxed. A model that was not designed with change in mind will require migrations that break compatibility with existing data or existing clients. The recovery is to make model changes additive where possible — adding fields rather than modifying them, extending enumerations rather than replacing them — and to document the compatibility assumptions that each version of the model relies on.


## Quick Exercise

For one of the three running systems, write a one-page data model brief with six sections: core entities, core relationships, the three most important read operations, the three most important write operations, three invariants that must hold across all of them, and one foreseeable extension that the current model should not implement but should not prevent.

Then name one tradeoff your model makes — between normalization and duplication, generality and simplicity, or strictness and flexibility — and explain why you made it.

If you cannot name a tradeoff, look harder. A model with no visible tradeoffs is a model whose tradeoffs have not yet been examined.


## Takeaways

- A good data model is operational, not merely conceptual: it is shaped by the queries and transitions the system must support, not just the entities the domain contains.
- Critical access patterns must inform model structure from the beginning. Performance problems that originate in model design are the most expensive to fix.
- Every non-trivial model embodies tradeoffs between normalization and duplication, generality and simplicity, strictness and flexibility. Make these tradeoffs explicit and document them.
- Transition rules are part of the data model. A model that defines states without defining legal transitions between them is incomplete.
- The test of a model's quality is not how it looks at design time but how well it absorbs change over the life of the system.


*Chapter 10 examines the dimension of data modeling that most designs underestimate: change over time. A model that handles its initial requirements correctly may still fail as the system evolves. Chapter 10 makes the patterns of temporal change explicit and shows how to design for them.*
