# Studio 1 — Design Notes (Levels 1–3)

**Our First Tiny System.** The smallest end-to-end slice of each of the three
systems, built to test whether the problem framing is usable. The guiding rule is
*prefer clarity over generality, keep architecture deliberately small* — these
models are meant to be flat. Their limits are the agenda for Studios 2–6.

## Files

| File | Role |
|---|---|
| `delivery_tiny.nex` | `Delivery_Tiny.next_stop` — single robot, hardcoded A→B→C map. |
| `notes_tiny.nex` | `Notes_Tiny.find_by_tag` — fixed tag→note lookup. |
| `world_tiny.nex` | `World_Tiny.step` — one entity, clamped position update. |
| `studio_1_main.nex` | **Levels 1 & 2** driver: executable expected-vs-actual checks. |
| `level3_exploration.nex` | **Level 3**: hardcoded map vs data-driven path. |
| `NOTES.md` | This file (design notes, edge-case checklist, postmortem). |

Run: `nex studio_1_main.nex` (→ `STUDIO_1_CHECK:PASS 13/13`), `nex level3_exploration.nex`.

The class names match the filenames (`Delivery_Tiny` ↔ `delivery_tiny.nex`), so
the driver pulls each in with `intern Delivery_Tiny` etc. — no façade needed.

## Design notes — inputs, outputs, guarantees

| System | Operation | Inputs | Output | Precondition | Postcondition (guarantee) |
|---|---|---|---|---|---|
| Delivery | `next_stop(current, destination)` | two stop names | next hop, or `"UNREACHABLE"` | both non-empty | `result /= ""` (always a decision) |
| Notes | `find_by_tag(tag)` | a tag | note id, or `"NOT_FOUND"` | tag non-empty | `result /= ""` (always a response) |
| World | `step(position, velocity, max_x)` | three integers | next position | `max_x > 0` | `0 ≤ result ≤ max_x` (always in bounds) |

All three are **pure, total, deterministic functions**: same input → same output,
defined for every input the precondition admits, with no state retained between
calls.

---

# Level 1 — Core Implementation

One core operation per domain, each with an explicit contract, exercised by
**executable examples** (nominal + edge), every result checked against its
expected value. From `studio_1_main.nex`:

```
DELIVERY  next_stop(A,C)=B  next_stop(C,C)=C  next_stop(X,C)=UNREACHABLE
NOTES     find_by_tag(algorithms)=note_001    find_by_tag(unknown)=NOT_FOUND
WORLD     step(3,2,10)=5  step(9,5,10)=10  step(1,-5,10)=0
=> STUDIO_1_CHECK:PASS (13/13)
```

---

# Level 2 — Cross-System Generalization

All three tiny systems run, and the driver checks them through **one harness**.
That is possible because they share an abstraction:

> **A contracted total function: `query → guaranteed-well-formed answer`.**

Each has the same three-part shape — a `require` that guards inputs, a
deterministic body, an `ensure` that guarantees the result is *usable* — and each
turns an **out-of-domain input into an explicit answer** rather than failing:
delivery/notes use an in-band sentinel String (`UNREACHABLE` / `NOT_FOUND`), world
uses a clamp. The `Studio1_Checker.check(label, expected, actual)` harness verifies
all three identically (world's `Integer` is stringified at the boundary).

This shared shape is exactly what makes the systems *comparable* — and the shared
**weakness** (failure encoded in-band, in the same type as a real answer) is what
Studio 2 attacks with entities, invariants, and contracted transitions.

---

# Level 3 — Exploration

Replaced the delivery map's **hardcoded if/elseif** with a **data-driven ordered
path** (`Delivery_Map`, stops `["A","B","C"]`). Same queries, compared:

```
A->C agree(B)   B->C agree(C)   C->C agree(C)   X->C agree(UNREACHABLE)
A->B DIVERGE: hardcoded=UNREACHABLE  data-driven=B
B->A agree(UNREACHABLE)
```

**Complexity:** baseline = N branches, one per enumerated goal; adding a stop/goal
edits code. Data-driven = one array + two index lookups; adding a stop changes
*data*, no new branches.

**Failure behavior:** the baseline only answers the goals it enumerated, so `A->B`
returns a **false `UNREACHABLE`**. The data-driven model routes any forward pair on
the path, and `UNREACHABLE` now means a *real* condition (off-map or backward). But
**both still signal failure with an in-band sentinel String** — swapping the map
representation does not fix the deeper defect; that needs Studio 2's `Route` entity
and real contracts. (This exploration is the seed of that redesign.)

---

# Edge-case checklist (observed behavior)

| # | System | Edge input | Expected | Observed | Note |
|---|---|---|---|---|---|
| 1 | Delivery | `next_stop("C","C")` (already at dest) | `C` | `C` | at-destination is a valid no-move |
| 2 | Delivery | `next_stop("X","C")` (unknown current) | `UNREACHABLE` | `UNREACHABLE` | failure is a String, usable as a stop ⚠ |
| 3 | Delivery | `next_stop("A","B")` (un-enumerated goal) | — | `UNREACHABLE` (baseline) | **false** failure; data-driven returns `B` |
| 4 | Notes | `find_by_tag("unknown")` | `NOT_FOUND` | `NOT_FOUND` | same type as a real note id ⚠ |
| 5 | World | `step(9,5,10)` (over-shoots high) | `10` | `10` | clamped to bound |
| 6 | World | `step(1,-5,10)` (under-shoots low) | `0` | `0` | clamped to floor |
| 7 | World | `step(-5,0,10)` (already out of bounds) | `0` | `0` | input `-5` silently clamped — bug hidden ⚠ |
| 8 | World | `step(p,v,0)` (`max_x` not positive) | — | precondition blocks | `max_positive` guards it |

⚠ = a flat-model weakness, not a code error: failure shares a type with a real
answer (2, 4), false unreachability from a hardcoded map (3), and invalid input
silently absorbed (7).

---

# Postmortem

- **Assumptions validated.** The three problems *are* expressible as small,
  contracted, total functions; a vertical slice runs input→output for each; the
  postconditions (`result /= ""`, `0 ≤ x ≤ max_x`) hold on every nominal and edge
  case (13/13).
- **Assumptions that proved false / fragile.** (a) "A hardcoded map is enough" —
  it gives **false `UNREACHABLE`** for un-enumerated goals (Level 3, `A->B`).
  (b) "A sentinel String is a fine way to report failure" — it is **type-identical
  to a real answer**, so a caller can use `UNREACHABLE`/`NOT_FOUND` as if it were a
  real stop or note id. (c) "Clamping makes the world safe" — it **hides invalid
  input** (`step(-5,…)` silently becomes `0`); the bug is masked, not caught.
- **Missing from the original problem statement.** No notion of *identity* or
  *state* (a delivery is the same task across a lifecycle; an entity owns its
  bound); no map *topology* beyond the single path; no distinction between
  "no answer" and "a real answer." Failure modes were under-specified.
- **What to tighten before redesign.** Move failure out of the return *type*
  (don't overload Strings/clamps to mean "no answer"); give the map and the entity
  an explicit model with owned state; make "in bounds" / "on a real stop" an
  always-true *invariant*, not merely a per-call postcondition. → That is exactly
  the Studio 2 redesign (`Delivery_Task` + `Route`, `Document` + `Doc_Link`,
  `World_Object`).
