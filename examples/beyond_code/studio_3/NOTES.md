# Studio 3 — Scaling Notes (Levels 1–3)

**The Scaling Crisis.** Studio 2 made the three models *correct* (identity,
relationships, invariants, contracted transitions). Studio 3 keeps that model
semantics **unchanged** and attacks the hot paths that went scan-heavy once the
collections grew to thousands of records. We *optimize access paths, not domain
meaning* — and we prove each change with an operation count, not intuition.

## Files

| File | Role |
|---|---|
| `Measure_Result.nex` | Shared value object: an answer + its operation count (`steps`). |
| `delivery_scale_from_studio2.nex` | Delivery: model + `Delivery_Store_V2` (scan) + `Delivery_Store_V3` (index). |
| `knowledge_scale_from_studio2.nex` | Knowledge: `Document`/`Doc_Link` + `Knowledge_Model_V2`/`V3`. |
| `world_scale_from_studio2.nex` | World: `World_Object` + `World_Model_V2`/`V3`. |
| `studio_3_main.nex` | **Level 1 + Level 2** driver: before/after on all three domains. |
| `level3_index_vs_partition.nex` | **Level 3**: index-heavy vs partition (delivery). |
| `DESIGN_NOTE.md` | **Level 3 deliverable**: the one-page design note. |

Run from this directory: `nex studio_3_main.nex`, `nex level3_index_vs_partition.nex`.

Each `*_scale_from_studio2.nex` exposes an entry class named so `intern` resolves
the file by its lowercased name (`Delivery_Scale_From_Studio2` →
`delivery_scale_from_studio2.nex`). Loading it registers *all* the module's
classes (model + both stores). The entry class doubles as a small façade
(`baseline(rows)` / `indexed(rows)`) so the driver seeds each before/after pair
once. The shared `Measure_Result` is pulled in with `intern Measure_Result`;
several modules intern it, which Nex handles without conflict.

> **V2/V3 naming.** Following the chapter, **V2** is the Studio-2-style *baseline*
> (a linear scan — the usage pattern that worked fine at small `n`), and **V3** is
> the *refactor* that adds an index layer. V3 reuses the V2 model objects verbatim.

---

# Level 1 — One Refactor (Delivery)

**Bottleneck:** `find_status(task_id)` — look up a task's status by id. At volume
this is the most-called read, and the Studio 2 usage pattern scans the task
collection.

| | Access path | Cost | On a miss |
|---|---|---|---|
| **V2** `Delivery_Store_V2` | linear scan of `Array [Delivery_Task]` | O(n) | full `n` (must check every row) |
| **V3** `Delivery_Store_V3` | `Map [task_id -> Delivery_Task]` derived from identity | O(1) | O(1) |

The index key **is the model's identity field** (`task_id`), so the index cannot
point at a task whose id disagrees with it. The model class `Delivery_Task` is
untouched — same fields, same `id_present` invariant.

### Measured (from `studio_3_main.nex`, 12 tasks, target last)

```
hit  T-12  V2(scan): IN_TRANSIT in 12 steps  |  V3(index): IN_TRANSIT in 1 step
miss T-99  V2(scan): NOT_FOUND  in 12 steps  |  V3(index): NOT_FOUND  in 1 step
```

Same answer both ways; the cost drops from 12 → 1. The **miss** is the scan's
true worst case — it pays full price to conclude "not here," which the index
answers in one step.

### Contracts that held during the refactor
- `Measure_Result.non_negative_steps` and `value_present` — a cost is never
  negative, absence is the explicit status `NOT_FOUND` (never `""`).
- V2 `bounded_steps: 1 ≤ steps ≤ rows.length` — the scan's honest range.
- V3 `constant_step: steps = 1` — the index's promise, checked every call.
- V3 `complete_index: index.size() = rows.length` — every task is reachable by id.

---

# Level 2 — Three-Domain Refactor

Same recipe in all three domains: keep the Studio 2 model class, add an index
**derived from the model's identity field**, return a `Measure_Result` so the
win is measured. Driver: `studio_3_main.nex`.

| Domain | Bottleneck operation | Index derived from | V2 cost | V3 cost |
|---|---|---|---|---|
| Delivery | `find_status(task_id)` | `task_id` | O(n) scan | O(1) |
| Knowledge | `validate_link(link)` (two endpoints) | `doc_id` | O(n) **per endpoint** | 2 accesses |
| World | `move_by_id(id, delta)` (targeted update) | `object_id` | O(n) to locate | O(1) to locate |

### Measured

```
DELIVERY  hit  T-12        V2: 12 steps  | V3: 1 step
KNOWLEDGE valid D-1->D-8   V2:  9 steps  | V3: 2 steps
KNOWLEDGE dangling D-1->?  V2:  9 steps  | V3: 2 steps
WORLD     move E-10 +5     V2: 10 steps  | V3: 1 step
WORLD     move E-99 (miss) V2: 10 steps  | V3: 1 step
```

Notes:
- **Knowledge** is the clearest "constants matter" case: a link does *two*
  membership tests, so the scan is up to `2n` while the index is a flat `2`. The
  `status_known` postcondition (`VALID | MISSING_FROM | MISSING_TO`) is asserted
  in **both** versions, so the refactor provably preserves the contract; failure
  is an explicit status, never a sentinel sharing a type with a real answer.
- **World** is a *write* path. The position-update **semantics are unchanged** —
  both versions call the model's own `shift(delta)`; only target *selection* got
  faster. Each model owns its objects, so a move is read back through the same
  model that performed it.

### Where the indexes come from (identity, not duplication)
Every V3 index is `Map [<identity field> -> <model object>]`, built in one pass
at construction. No domain rule is re-encoded in the index; it is purely an
**access structure** layered around unchanged model objects. Because the key is
the object's own id, the index and the objects cannot disagree.

---

# Level 3 — Competing Strategies (Delivery)

See **`DESIGN_NOTE.md`** for the one-page writeup. Experiment: two refactors of
the *same* Studio 2 task collection, each optimizing a different query shape
(`level3_index_vs_partition.nex`).

| Strategy | Structure | Point lookup by id | Group tally by status |
|---|---|---|---|
| **Index-heavy** | `Map [task_id -> task]` | **O(1)** | O(n) — must visit every task |
| **Partition** | `Map [status -> Array [task]]` | O(n) — scan buckets | **O(1)** — one bucket's size |

### Measured (12 tasks)

```
point find_status(T-12):     index 1 step   | partition 8 steps
group count_status(PENDING): index 12 steps | partition 1 step
```

Each structure wins the workload whose key matches its organization. The
id-index is keyed by identity, so it nails point lookups but is blind to
categories; the status-partition is keyed by category, so it nails group/bulk
queries but loses the direct line to a single id.

**When each wins**
- **Index-heavy** when the dominant query is *"give me task X by id"* (dashboards,
  status pages, update-by-id).
- **Partition** when the dominant query is *"operate on the whole PENDING set"*
  (dispatch sweeps, backlog counts, batch reassignment).
- **Both** only when both workloads are hot — and that means paying for two
  structures: extra memory and a write that must update both to stay consistent.

---

# Postmortem (with evidence)

- **Worst bottleneck, and why.** Knowledge `validate_link`. It is not one scan but
  *two* per link (from-endpoint, to-endpoint), so it is the steepest in the
  collection size — `2n` where the others are `n` — and link validation runs in
  bulk when ingesting documents. Measured: 9 steps at only 8 docs.
- **Asymptotic change vs. constants only.** All three index refactors change the
  **asymptotics**: O(n) → O(1) (Delivery, World) and O(n) per endpoint → O(1) per
  endpoint (Knowledge). What is *only* a constant: knowledge stays "2 steps per
  link" — the index removed the `n` factor but the *two* endpoint checks remain
  (you can't validate a link with fewer than two membership tests). Early-exit on
  hit in the V2 scans is likewise a constant-factor win, not an asymptotic one —
  the miss case still pays full `n`.
- **Contract that protected correctness while optimizing.** `Measure_Result`'s
  invariants plus each operation's postcondition. Knowledge's `status_known` is
  the sharpest: it pins the result set identically across V2 and V3, so the index
  could not silently change behavior while changing the data organization. The
  model invariants (`id_present`) guaranteed every object had a usable index key.
- **Optimization to postpone until higher load.** The **partition** structure (and
  any second index in general). At low/moderate `n` a single id-index is plenty;
  partitioning adds write-time bucket maintenance and a second structure to keep
  consistent. Build it only when a *group/bulk* query measurably dominates — the
  trigger is evidence (a hot `count_status`/sweep), not anticipation.

---

# Tradeoffs intentionally accepted

- **Indexes are rebuilt once at construction, not maintained incrementally.** The
  stores take a fixed seed array. A live system would update the index on every
  insert/update/delete; here that machinery is out of scope, so the demo seeds
  once. (This is exactly the cost called out for keeping *two* structures in
  Level 3.)
- **`steps` is a model-level operation count, not wall-clock time.** It counts
  record comparisons / key accesses — the thing that scales with `n` — which is
  what the brief asks us to compare. A real benchmark would also weigh constant
  per-op costs (hashing vs. string compare).
- **Map semantics:** a `Map.put` stores the value, and a typed map *field* is
  built in a local then assigned once (`this.index := m`) rather than mutated
  in place — Nex returns a copy when you read a collection field, so accumulating
  via `this.index.put(...)` would not stick. The build loops follow that pattern.
- **Partition keys are the four known lifecycle statuses.** A status outside that
  set would fall into no bucket; the model's Studio 2 `valid_status` rule (in the
  richer model) is what keeps statuses inside the set in the first place.
