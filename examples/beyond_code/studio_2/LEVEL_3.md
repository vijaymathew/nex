# Studio 2 — Level 3 Exploration

**Experiment chosen:** *State-centric vs. event-centric* transition handling for
delivery **reassignment** (moving an in-flight delivery from one robot to another).

Two runnable variants:

| File | Approach |
|---|---|
| `level3_state_centric.nex` | reassignment = drive the existing state machine (`fail` then `assign`) |
| `level3_event_centric.nex` | reassignment = a first-class `Reassign_Event` the task `apply`s |

## What each variant does

**State-centric.** There is no "reassignment." To switch robots the dispatcher
fails the current attempt and assigns a new one:

```
t.fail("reassigning")   -- IN_TRANSIT -> FAILED
t.assign("R9")          -- FAILED -> IN_TRANSIT
```

The task stores only the *current* `robot_id`. Output:

```
assigned to R7 (IN_TRANSIT)
now carried by R9 (IN_TRANSIT)
history available: none (only the current robot is stored)
```

**Event-centric.** A `Reassign_Event` carries `{task_id, from_robot, to_robot,
reason}` and is applied through one operation:

```
let ev1 := create Reassign_Event.make("T001", "R7", "R9", "battery_low")
t.apply_reassign(ev1)
```

Output:

```
assigned to R7 (IN_TRANSIT)
now carried by R9, reassigned 1 time(s), last reason: battery_low
event record: task T001 R7->R9
blocked stale reassignment: Precondition violation: from_matches (task is on R9, event assumed R7)
```

## Evaluation

### Complexity impact
- **State-centric is simpler.** Zero new types; reassignment reuses `fail` and
  `assign`. But "reassignment" is *implicit* — it exists only as a convention in
  the caller, and it briefly passes through a `FAILED` state that did not really
  happen (the delivery never failed; it was redirected).
- **Event-centric costs one entity and one operation** (`Reassign_Event` +
  `apply_reassign`). More moving parts, but the concept is now named and the
  intermediate fake-`FAILED` state disappears.

### Testability impact
- **Event-centric wins.** The event is an inspectable value you can build and
  assert on *without a task* (`ev1.from_robot`, `ev1.to_robot`), and the task
  exposes derived history (`reassign_count`, `last_reason`) that tests can check.
  Reassignments are effectively a replayable log.
- **State-centric** offers nothing to assert beyond the final `robot_id`. To test
  "who carried it before" or "why it changed," you would have to add those fields
  anyway — i.e. drift toward the event-centric data model.

### Failure-mode clarity
- **Event-centric centralizes legality in one contract.** `apply_reassign`'s
  `require` block (`same_task`, `moving`, `from_matches`) is the single, readable
  definition of a legal reassignment. Crucially `from_matches` is an *optimistic
  concurrency* check: the event must start from the robot the task is **actually**
  on. The demo shows a stale event (`from R7` after the task already moved to R9)
  being rejected — a lost update the state-centric variant cannot even detect,
  because `fail`+`assign` never checks the "from" robot.
- **State-centric scatters the rule** across two operations and a transient state,
  and silently allows the lost update.

## Conclusion / tradeoff accepted

For a single-dispatcher toy, **state-centric** is the right amount of machinery —
less code, fewer concepts. As soon as reassignment needs an **audit trail** or
must be **safe under concurrent dispatchers**, **event-centric** pays for itself:
it names the operation, makes it testable as data, and concentrates the legality
(including the stale-reassignment guard) in one contract. The cost is one extra
entity and the discipline of constructing events instead of calling two methods.

If carried further, the event-centric task would hold a `Array[Reassign_Event]`
log rather than just a count — full history and replay — at the cost of more state
to keep invariant.
