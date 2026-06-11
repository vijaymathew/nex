# Studio 2 — Modeling Notes (Levels 1–3)

Redesign of the three Studio 1 flat models (`Delivery_Tiny`, `Notes_Tiny`,
`World_Tiny`) into explicit entity models with identity, relationships,
invariants, and contracted transitions.

## Files

| File | Role |
|---|---|
| `Delivery_Task.nex` | Delivery V2: `Route` + `Delivery_Task` entities. |
| `Document.nex` | Knowledge V2: `Document` + `Doc_Link` entities. |
| `World_Object.nex` | World V2: `World_Object` entity. |
| `studio_2_main.nex` | **Level 1** driver (delivery): 1 nominal + 2 edge cases. |
| `studio_2_level2.nex` | **Level 2** driver: all three systems, before/after each. |
| `level3_state_centric.nex` / `level3_event_centric.nex` | **Level 3** reassignment variants. |
| `LEVEL_3.md` | **Level 3** exploration writeup. |

Run from this directory: `nex studio_2_main.nex`, `nex studio_2_level2.nex`, etc.

The drivers pull models in with `intern`. Nex's `intern` is class-per-file:
`intern Delivery_Task` resolves a sibling file `Delivery_Task.nex` (searched in
the driver's directory / `NEX_USER_DIR` / cwd) that must define a `class
Delivery_Task`; evaluating that file registers *all* its classes, so `Route`
comes along too. (For a subfolder, `intern` instead uses a hardcoded `lib/<sub>/`
convention, e.g. `intern delivery/Delivery_Task` → `lib/delivery/Delivery_Task.nex`.
`import` is unrelated — it is Java/JS interop.)

---

# Level 1 — Delivery

## Before → after (the flat model and its defects)

The Studio 1 model was a stateless function over a hardcoded map:

```nex
class Delivery_Tiny
feature
  next_stop(current, destination: String): String
    require current_non_empty, destination_non_empty
    do  -- hardcoded A->B->C in if/elseif, else result := "UNREACHABLE"
    ensure decision_returned: result /= ""
end
```

| Studio 2 defect | In the flat model | Fixed in V2 by |
|---|---|---|
| Identity unclear | no entity persists; it is a function bag | `Delivery_Task.task_id` (stable identity) |
| Relationships implicit | robot absent; map hardcoded in branches | `robot_id` (assignment) + `Route` entity |
| Transitions scattered | no `status`; no lifecycle to begin with | explicit `assign`/`advance`/`complete`/`fail` state machine |
| Invalid states easy | failure is the sentinel string `"UNREACHABLE"` | invalid states forbidden by invariants |

## Entities

- **`Delivery_Task`** — one delivery. Identity is `task_id` (immutable). State is
  `status` (lifecycle) plus `current` (position along the route).
- **`Route`** — the map a delivery follows: an ordered list of `stops`. Identity
  is `route_id`. This replaces the hardcoded `if/elseif` map of the flat model.

Identity vs. state: a `Delivery_Task` is *the same task* across its whole
lifecycle even as `status`, `current`, and `robot_id` change — `task_id` never
does. A `Route` is identified by `route_id`; its `stops` are fixed after creation.

## Relationships

- **`Delivery_Task` → `Route`** (each task follows exactly one route). Because the
  task holds its route, `advance()` derives the next stop from the route instead
  of trusting a caller-supplied hop — an off-route move is unrepresentable.
- **`Delivery_Task` → robot** (`robot_id`): a task is carried by at most one robot;
  `""` means unassigned. Set by `assign`, required while `IN_TRANSIT`.

## Invariants

`Delivery_Task`:

| Invariant | Guarantees |
|---|---|
| `id_present` | identity is never empty |
| `valid_status` | status is one of the four legal states (no typo states) |
| `transit_has_robot` | cannot be `IN_TRANSIT` without an assigned robot |
| `pending_at_origin` | an unstarted delivery sits at its origin |
| `delivered_at_destination` | cannot be `DELIVERED` unless `current = destination` |
| `on_route` | the package is always located at a real stop on its route |

`Route`: `id_present`, `has_path` (≥ 2 stops), `distinct_endpoints`.

The cross-field invariants (`transit_has_robot`, `delivered_at_destination`,
`on_route`) are the ones the flat model could not express — they tie two pieces
of state together so an inconsistent pair cannot exist.

## Transitions (contracts)

| Operation | From → To | Key `require` | Key `ensure` |
|---|---|---|---|
| `assign(rid)` | PENDING/FAILED → IN_TRANSIT | robot non-empty; legal source state | robot set, status IN_TRANSIT |
| `advance()` | (position move, IN_TRANSIT) | moving; not already arrived | `current` changed (progress) |
| `complete()` | IN_TRANSIT → DELIVERED | in transit **and at destination** | status DELIVERED |
| `fail(reason)` | ¬DELIVERED → FAILED | reason non-empty; not delivered | status FAILED |

## Edge cases and the bugs they encode (from the run)

```
== nominal ==                    PENDING@A → IN_TRANSIT@C → DELIVERED@C
== edge 1: complete en route ==  blocked: Precondition violation: at_destination  (stayed IN_TRANSIT@B)
== edge 2: advance unassigned == blocked: Precondition violation: moving          (stayed PENDING@A)
```

- **Edge 1** — "marked delivered while still en route." The flat model had no
  concept of *arrival*, so this state was not even expressible; here it is blocked
  by the `at_destination` precondition and the `delivered_at_destination` invariant.
- **Edge 2** — "package moves with no robot carrying it." The flat model had no
  robot at all, so this class of bug was unpreventable; here `advance` requires
  `IN_TRANSIT`, which requires a prior `assign`. In both cases the task's state is
  **unchanged** after the rejected call.

## Tradeoffs intentionally accepted

- **No multi-route / branching maps.** `Route` is a single linear path. Branching
  topologies (a graph) would need a richer `Route` and a reachability query.
- **No robot entity.** The carrier is a `String` id, not a modeled `Robot` with its
  own state. Adequate for the assignment relationship; a real fleet would model it.
- **Typechecker note:** array element reads use `stops.get(i)` rather than `stops[i]`
  because subscripting a typed array field currently infers the array type, not the
  element type, when assigned to a typed target.

## Postmortem answers

- **Entity boundary that removed the most confusion:** splitting *identity*
  (`task_id`) from *state* (`status`/`current`). The flat model conflated "the
  delivery" with "the next-stop computation"; separating them made the lifecycle
  describable at all.
- **Relationship constraint that prevented a real invalid state:** `transit_has_robot`
  — a delivery in transit with no carrier is now impossible.
- **Hardest transition to make explicit:** `complete()`. "Delivered" intuitively
  felt like a status flip, but the honest rule is *delivered ⇒ at destination*,
  which only became enforceable once `current` and `Route` existed.
- **Tradeoff accepted:** linear single `Route` (no branching maps) to keep Level 1
  focused on the lifecycle redesign.

---

# Level 2 — Cross-System Model Consistency

All three systems are redesigned with the **same four modeling concepts**. The
table reads the shared vocabulary across domains:

| Concept | Delivery | Knowledge | World |
|---|---|---|---|
| **Entity** (stable identity) | `Delivery_Task` (`task_id`), `Route` (`route_id`) | `Document` (`doc_id`) | `World_Object` (`object_id`) |
| **Relationship** (cardinality/constraints) | task → one `Route`; task → ≤1 robot | `Doc_Link`: document → document, many-to-many, typed | object owns its own `max_x` bound |
| **Invariant** (invalid state forbidden) | `transit_has_robot`, `delivered_at_destination`, `on_route` | `no_self_link`, `valid_link_type` | `bounded: 0 ≤ x ≤ max_x` (always) |
| **Transition** (contracted operation) | `assign` / `advance` / `complete` / `fail` | `publish` / `archive`; validated `Doc_Link.make` | `step` (deterministic, bounded) |

## Before → after (one bug per system the new model prevents)

Driver: `studio_2_level2.nex`. Each system runs the Studio 1 flat operation
(*before*) and the V2 model (*after*). Observed:

| System | Before (flat) bug | After (V2) prevents it |
|---|---|---|
| Delivery | `next_stop("X","C") = "UNREACHABLE"` — failure is an in-band String usable as a real stop | unassigned `advance()` blocked by `moving` precondition |
| Knowledge | `find_by_tag("unknown") = "NOT_FOUND"` — same type as a real note id | `Doc_Link.make("D1","D1",…)` blocked by `no_self_link`; validity is a Boolean, not a sentinel |
| World | `step(-5,0,10) = 0` — an out-of-bounds input is silently clamped, hiding the bug | `World_Object.make("o1",-5,…)` blocked by `start_in_bounds`; `bounded` invariant holds for the object's whole life |

The common thread: each flat model reported failure or absorbed bad input
**in-band** (a sentinel String, or silent clamping). Each V2 model converts that
into either an **impossible state** (blocked by a contract/invariant) or an
**explicit, typed answer** (a Boolean query, a modeled status).

## Knowledge V2 — notes

- **Entities:** `Document` (identity `doc_id`, lifecycle `status`); `Doc_Link`
  (a typed directed relationship between two documents).
- **Relationship:** `Doc_Link` is many-to-many and typed (`references` / `related`
  / `contradicts`); constraints: non-empty endpoints, no self-link, known type.
- **Invariants:** `valid_status`; on links `no_self_link`, `valid_link_type`.
- **Transitions:** `publish` (DRAFT→PUBLISHED), `archive` (PUBLISHED→ARCHIVED);
  `Doc_Link.make` is itself contracted so an invalid link cannot be constructed.

## World V2 — notes

- **Entity:** `World_Object` (identity `object_id`), owning `x`, `vx`, and its
  bound `max_x`.
- **Relationship:** the bound belongs to the object (was a per-call argument).
- **Invariant:** `bounded: 0 ≤ x ≤ max_x` holds *always*, not only after `step`.
- **Transition:** `step()` (deterministic, clamped); `make(...)` requires the
  starting position to already be in bounds.

---

# Level 3 — Exploration

See **`LEVEL_3.md`**. Experiment: *state-centric vs. event-centric* handling of
delivery **reassignment** (`level3_state_centric.nex` vs.
`level3_event_centric.nex`). Summary: state-centric is simpler (reuses
`fail`+`assign`, no new types) but loses the audit trail and cannot detect a
stale/concurrent reassignment; event-centric adds a `Reassign_Event` entity and
one `apply_reassign` contract that centralizes legality (including an
optimistic `from_matches` guard), making reassignment testable as data and safe
under concurrent dispatchers — at the cost of one extra entity.
