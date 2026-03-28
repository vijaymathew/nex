# Preconditions and Postconditions

Parts I through VI gave us the tools to build software that is correct by design: clear problem statements, explicit models, well-chosen algorithms, organized data structures, and components with stable interfaces. Part VII asks a harder question. Given software that was built with care, how do we make its behavior dependable as it changes — as requirements evolve, as new developers join the team, as algorithms are replaced and data models are extended? The answer is to make the software's assumptions explicit and verifiable at every boundary where those assumptions matter.

Every routine in a system makes two kinds of implicit commitments. It assumes certain things about the world when it is called — that its inputs satisfy certain constraints, that the system is in a certain state. And it promises certain things about the world when it returns — that its output has a certain shape, that the system has been left in a certain state. When these assumptions and promises are implicit, they are invisible to callers, unenforceable by the system, and discoverable only through failure. When they are explicit, they are readable, checkable, and — when violated — diagnostic: the failure occurs at the violated boundary, not at some distant point where the corrupted state finally produces a visible symptom.

A precondition is the formal statement of an assumption: what must be true of the inputs and the current state before this routine may be called. A postcondition is the formal statement of a promise: what will be true of the outputs and the resulting state when this routine returns, given that the precondition was satisfied.


## Preconditions: Caller Obligations

A precondition is a statement about the caller's responsibility. It says: *I, this routine, am only obligated to deliver my postcondition if you, the caller, have satisfied this condition before calling me.* If the caller violates a precondition, the routine's behavior is unspecified — it may signal an error, it may produce a wrong result, or it may do something else entirely. The caller has not held up their end of the contract.

This has a practical consequence: preconditions should not be written to handle the caller's mistakes gracefully. A precondition is not a validation layer that converts bad inputs into error results. It is a boundary that says: inputs satisfying these conditions are valid inputs for this routine; inputs that do not satisfy them are not. A routine that promises `ASSIGNED` on valid inputs and `BAD_INPUT` on invalid ones has not written a precondition — it has written a conditional that handles both cases and made both cases part of the postcondition. That is a legitimate design, but it is different from a contract that rejects invalid inputs with a contract violation.

What belongs in a precondition? Three categories cover most cases.

**Input presence and validity.** Empty identifiers, null references, and values outside a defined range are inputs that no routine can process meaningfully. Stating the required form explicitly — `task_id /= ""`, `priority >= 0` — tells the caller what they must guarantee before the call.

**Required state.** Many operations are only legal from certain states. A task can only be assigned if its status is `PENDING` or `FAILED`. A robot can only be dispatched if it is marked ready. These conditions belong in the precondition, not in a conditional inside the routine body, because they are the caller's responsibility to ensure before calling.

**Relationship constraints.** Some operations require that two or more inputs stand in a certain relationship — that a start location and a destination both exist in the same graph, that an update refers to an entity that has been previously registered. When these relationships are required, stating them as preconditions makes the requirement visible and checkable.


## Postconditions: Routine Guarantees

A postcondition is a statement about the routine's responsibility. It says: *if you, the caller, have satisfied the precondition, then I, this routine, guarantee the following when I return.* The postcondition is an unconditional promise, relative to the precondition — no matter what path through the routine body execution takes, if the precondition was satisfied on entry, the postcondition will be satisfied on exit.

This unconditional quality is what makes postconditions valuable for reasoning. A caller that knows the precondition and postcondition of a routine can reason about what comes after the call without reading the routine's implementation. The postcondition is the contract the implementation must fulfill, and the implementation can be changed freely as long as it continues to fulfill it.

Three categories cover most postconditions.

**Result constraints.** The shape, value, and meaning of the returned value. A route operation's result is always one of three declared status values. A score is always non-negative. A retrieved document is always from the collection that was queried. Stating these constraints ensures that callers can depend on them rather than defensively checking them.

**State transition outcomes.** When a routine modifies state, the postcondition records what changed and what the new state satisfies. An assignment operation leaves the task in `IN_TRANSIT` with the specified robot identifier. A blocking operation leaves the path with status `BLOCKED`. These assertions close the loop between the operation's intent and its result.

**Derivable consequences.** Some postconditions follow from the combination of the precondition and the routine's action. If a precondition requires non-negative edge costs and the routine computes a sum of edge costs, the postcondition can assert that the result is non-negative — not because the routine checks it explicitly, but because it follows from the combination of the precondition and the computation. Stating derivable consequences explicitly makes them checkable and makes the chain of reasoning visible.


## From Requirement to Contract

Consider the requirement:

> *"Assign a task to a robot and return the assignment status."*

**Step 1: Define caller obligations.** The task identifier must be present and non-empty — a call with an empty identifier cannot refer to any task. The robot identifier must be present — an assignment requires a robot to assign to. The task's current status must be `PENDING` or `FAILED` — assigning a task that is already `IN_TRANSIT` or `DELIVERED` is an illegal transition. These three conditions are the precondition.

**Step 2: Define routine guarantees.** If the precondition is satisfied, the routine will: set `assigned_robot_id` to the provided robot identifier, set `status` to `IN_TRANSIT`, and return `"ASSIGNED"`. The postcondition asserts all three: `assigned_robot_id = robot_id`, `status = "IN_TRANSIT"`, and `result = "ASSIGNED"`.

**Step 3: Define fail-fast behavior.** A call that violates the precondition should fail immediately with a clear signal — a contract violation — rather than silently producing a wrong result that propagates through the system. The earlier a violation is detected, the closer it is to the code that caused it, and the easier it is to diagnose.

**Step 4: Verify the contract is complete.** A contract is complete when the precondition and postcondition together fully specify the routine's behavior for all valid inputs. A contract with a precondition that permits inputs the postcondition cannot deliver is incomplete. A contract with a postcondition that leaves the new state underspecified is incomplete. Completeness is the test.


## A Contract in Code

```nex
class Delivery_Task
feature
  task_id: String
  status: String
  assigned_robot_id: String

  assign(robot_id: String): String
    require
      task_id_present: task_id /= ""
      robot_id_present: robot_id /= ""
      can_assign: status = "PENDING" or status = "FAILED"
    do
      assigned_robot_id := robot_id
      status := "IN_TRANSIT"
      result := "ASSIGNED"
    ensure
      assigned: assigned_robot_id = robot_id
      now_in_transit: status = "IN_TRANSIT"
      known_result: result = "ASSIGNED"
    end
invariant
  valid_status:
    status = "PENDING" or
    status = "IN_TRANSIT" or
    status = "DELIVERED" or
    status = "FAILED"
end
```

Read the structure in layers. The invariant asserts a property that must hold at all times — after construction, after every operation, and before every operation call. The precondition on `assign` adds requirements specific to this operation: the task identifier and robot identifier must be non-empty, and the status must be one that permits assignment. The postcondition asserts what the operation has accomplished: the assignment is recorded, the status has been updated, and the return value carries the expected token.

The three preconditions address different responsibilities. `task_id_present` is an integrity check on the task itself — a task without an identifier is malformed and should never have been constructed, which is why the invariant also requires `task_id /= ""`. Its presence in the precondition is a belt-and-suspenders assertion that guards against callers operating on malformed instances. `robot_id_present` is a caller obligation — the caller must provide a real robot. `can_assign` is a state precondition — the caller must ensure the task is in a state that permits assignment before calling.

The postcondition closes the loop: every claim made in the `do` block has a corresponding assertion in the `ensure` block. The assignment is checked, the status is checked, the result is checked. A future refactor that modifies the `do` block must satisfy all three assertions, which means the postcondition is both documentation and test specification.


## Contracts in the Three Systems

In the delivery system, every state transition on `DeliveryTask` — assign, start, complete, fail — has a contract. The preconditions encode the legal transition graph from `Modeling Change`: which states permit which operations. The postconditions encode the resulting state and return value. A developer modifying the transition logic can see immediately what the contract requires and whether their change satisfies it.

In the knowledge engine, the route computation operation has a precondition that requires non-empty inputs and a postcondition that guarantees one of three declared statuses. The scoring operation has a precondition that requires non-empty document and query strings and a postcondition that guarantees a non-negative score. These contracts make the pipeline from `Breaking Problems Apart` independently verifiable at each stage.

In the virtual world, the state update operation for each tick has a precondition that requires the entity to exist in the current frame and a postcondition that guarantees the entity's state satisfies the world's invariants after the update. Violations of the postcondition are caught at the boundary of the update, before the corrupted state propagates to collision detection or event output.

In all three systems, contracts turn the informal expectations that developers carry in their heads — "this must be non-empty," "this transition is only legal from PENDING" — into executable statements that are checked automatically and fail close to their source.


## Three Ways Contract Design Fails

**Missing preconditions for state transitions.** A transition operation without a precondition on the current state permits callers to invoke it from any state. A delivered task can be reassigned. A robot can be dispatched before it is ready. The illegal transition is not detected at the call site — it is detected later, when downstream code encounters a state it was not designed to handle. The remedy is to encode transition legality as a precondition on every transition operation. The precondition fires at the call, not at the downstream consequence.

**Weak postconditions.** A postcondition that asserts only that the result is non-empty, or that the return value is a string, is a postcondition that provides almost no guarantee. A caller that depends only on these weak properties cannot reason about what the operation actually accomplished. The postcondition should assert the specific state changes that matter — the new status, the recorded assignment, the returned path — so that any implementation that fails to accomplish them will violate the postcondition and fail immediately.

**Contract drift between similar operations.** A codebase with many similar operations — multiple transition operations, multiple search operations, multiple update operations — will accumulate contracts that began as consistent and gradually diverged through independent modifications. One transition requires non-empty task id; another was written later and omits it. One search guarantees `NOT_FOUND` on miss; another returns an empty string. The inconsistency is invisible until a caller that works correctly with one operation fails with another. The remedy is to review contracts across similar operations periodically and to establish conventions — all transitions require non-empty identifiers, all searches declare an explicit miss status — that future contracts are written to match.


::: {.note-exercise}
**Quick Exercise**

Choose one routine in your system — one that is called from multiple places and whose behavior callers depend on — and write its complete contract with four parts: three preconditions that state the caller's obligations, three postconditions that state the routine's guarantees, one input that violates a precondition and the behavior that should result, and one valid input with the specific postconditions it should satisfy.

Then verify that the postconditions still hold after one plausible refactor of the routine body. If a refactor that improves the implementation without changing the observable behavior would break a postcondition, the postcondition is specifying implementation rather than guarantees.
:::

::: {.note-takeaways}
**Takeaways**

- A precondition is the caller's obligation. If the caller does not satisfy it, the routine's behavior is unspecified. Preconditions should fail fast, not handle invalid inputs gracefully.
- A postcondition is the routine's guarantee. If the precondition is satisfied, the postcondition holds — unconditionally, regardless of which path through the routine body execution takes.
- Three categories belong in preconditions: input presence and validity, required state, and relationship constraints. Three categories belong in postconditions: result constraints, state transition outcomes, and derivable consequences.
- A contract is complete when the precondition and postcondition together fully specify the routine's behavior for all valid inputs.
- Contracts turn implicit assumptions into explicit, executable statements that fail close to their source when violated.
:::



*The next chapter, `Invariants — Rules That Must Never Break`, extends the contract discipline from individual routines to entire objects. Where postconditions specify what is true after a single operation, invariants specify what is true at all times — before every call and after every return. Invariants are the class-level counterpart of postconditions, and they are what make it possible to reason about an object's state without reading every operation that might have modified it.*
