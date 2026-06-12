# Studio 3 — Design Note (one page)

**Scope:** scaling the Studio 2 delivery model. The model semantics are frozen;
only the access path changes. Evidence is operation counts from
`studio_3_main.nex` and `level3_index_vs_partition.nex`.

## Bottleneck operation

`find_status(task_id)` on the delivery task collection — the most-called read.
The Studio 2 usage pattern is a **linear scan**: O(n), and a *miss* pays the full
`n` to conclude "not present." At 12 tasks the worst-case hit and the miss both
cost **12 steps**.

Secondary (Level 2) bottleneck: knowledge `validate_link`, which is *two* scans
per link (`2n`) — the steepest path in the suite, measured at 9 steps for 8 docs.

## Chosen refactor

Add a **hash index derived from the model's identity field**, leaving the model
class untouched:

- Delivery: `Map [task_id -> Delivery_Task]` → `find_status` is **1 step** (hit or
  miss).
- Knowledge: `Map [doc_id -> Document]` → `validate_link` is **2 steps** (one per
  endpoint), independent of document count.
- World: `Map [object_id -> World_Object]` → `move_by_id` locates its target in
  **1 step**, then runs the *unchanged* `shift(delta)`.

```
find_status   V2(scan) 12  ->  V3(index) 1
validate_link V2(scan)  9  ->  V3(index) 2
move_by_id    V2(scan) 10  ->  V3(index) 1
```

These are **asymptotic** wins (O(n) → O(1)); knowledge's residual "2" is the
irreducible two-endpoint check, a constant the index cannot remove.

## Correctness safeguards

- `Measure_Result` invariants: `value_present` (absence is the explicit status
  `NOT_FOUND`/`MISSING_*`, never `""`), `non_negative_steps`.
- Postconditions assert the contract is preserved across the refactor:
  `constant_step` (index), `bounded_steps` (scan), and especially knowledge's
  `status_known` (`VALID | MISSING_FROM | MISSING_TO`) — identical in V2 and V3,
  so the index cannot change behavior while changing organization.
- `complete_index: index.size() = rows.length` — every model object is reachable
  by its id; the index key being the identity field means index and objects
  cannot disagree.
- Model invariants (`id_present`) guarantee every object has a usable key.

## Competing strategies (Level 3)

For the delivery domain, an **id-index** and a **status-partition**
(`Map [status -> Array [task]]`) optimize opposite workloads:

```
point  find_status(T-12):     index 1   | partition 8
group  count_status(PENDING): index 12  | partition 1
```

- **Index wins** point lookups by identity.
- **Partition wins** group/bulk queries by category (it reads one bucket and
  ignores the rest).
- Serving both cheaply means keeping **both** structures — extra memory plus a
  write that must update both to stay consistent.

## Limitations & next trigger point

- Indexes are built once from a seed; incremental maintenance on insert/update/
  delete is out of scope. **Trigger:** moving to a live, mutating store.
- `steps` counts comparisons/key accesses, not wall-clock; constant per-op costs
  (hashing vs. string compare) are not modeled.
- Only the id-index ships in V3. **Trigger for the partition (or any second
  index):** a *measured* hot group/bulk query (e.g., a dispatch sweep over all
  `PENDING`) — build it on evidence, not anticipation.
