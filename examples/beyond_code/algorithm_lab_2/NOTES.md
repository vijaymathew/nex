# Algorithm Lab 2 — When Algorithms Compete

One problem — *find a path from A to G in an unweighted, directed graph* — with
two competitors judged by evidence: **DFS** (finds *a* path) vs **BFS**
(guarantees *minimum hops*). Every run reports `hops` (path quality), `steps`
(node expansions), and `frontier` (peak frontier size — a memory proxy).

## Files

| File | Role |
|---|---|
| `algorithm_lab_2.nex` | `Graph`, `Path_Run_Result`, `Path_Tools`, `DFS_Competitor`, `BFS_Competitor`, and the `Algorithm_Lab_2` façade (fixtures + factory). |
| `algorithm_lab_2_main.nex` | Driver: Tasks A, B, C + experiment table + correctness check. |
| `NOTES.md` | This write-up. |
| `DECISION_NOTE.md` | One-page decision note (the deliverable). |

Run from this directory: `nex algorithm_lab_2_main.nex`. The driver pulls the
module in with `intern Algorithm_Lab_2`.

## Honest implementation

The chapter sketches the graph as boolean edge flags (`a_b`, `a_c`, …) with the
two candidate paths hand-written into `if/elseif` and `steps := 1`. This lab
uses a **real adjacency-map graph** (`Map [String, Array [String]]`) and **real
iterative traversals**, so paths, hops, steps, and frontier are *computed*. On
the chapter's default graph it reproduces the book's outcome exactly
(DFS → `A->B->D->E->G`, BFS → `A->C->F->G`) and additionally measures the
tradeoff the book only asserts.

- **step** = one node expansion (a node popped/dequeued and its neighbors
  examined). Comparable across both strategies.
- **frontier** = peak size of the frontier (DFS stack / BFS live queue).
- DFS explores neighbors in edge-insertion order (deterministic branch
  preference: `A->B` before `A->C`), with a visited-on-path guard against cycles.
- BFS marks nodes visited on enqueue, so each node is expanded once; the first
  time `G` is dequeued, its path is minimum-hop.

---

## Task A — Nominal competition (default graph)

```
DFS  FOUND  A->B->D->E->G   hops=4  steps=5  frontier=2
BFS  FOUND  A->C->F->G      hops=3  steps=7  frontier=2
```

Both find a valid path. **BFS wins quality** (3 hops vs 4) — it is shortest by
hops by construction. **DFS wins cost here** (5 expansions vs 7): it commits to
`A->B` and drives straight to `G` without exploring the `C` branch, while BFS
expands the whole graph until `G` surfaces at the shortest layer. This is the
central lesson: *shorter path, more work to find it.*

---

## Task B — Workload variation

| variant | DFS | BFS |
|---|---|---|
| default | FOUND `A->B->D->E->G` h4 s5 f2 | FOUND `A->C->F->G` h3 s7 f2 |
| remove shortcut `C->F` | FOUND `A->B->D->E->G` h4 s5 f2 | FOUND `A->B->D->E->G` **h4** s6 f2 |
| add dead-end `B->X->Y` | FOUND `A->B->D->E->G` h4 **s7** f3 | FOUND `A->C->F->G` h3 s9 f3 |

**Which strategy is more sensitive to each change?**
- **Remove the shortcut (`C->F`)** hurts **BFS's result**: its hop count rises
  `3 -> 4` because the short route no longer exists. DFS is unaffected — it never
  used that route.
- **Add a dead-end (`B->X->Y`)** hurts **DFS's cost**: it prefers `B` and wanders
  into `X -> Y` before backtracking, so steps jump `5 -> 7`. BFS shrugs it off
  for quality (still 3 hops) — it never descends — though its steps rise too as
  it expands the extra nodes.

Summary: **DFS is sensitive to topology that lengthens or traps a branch**
(dead-ends, branch order); **BFS is sensitive to topology that removes
shortcuts** (its guarantee is about hops, so hop-changing edits move its result).

---

## Task C — Constraint stress

**(1) DFS depth cap** on the deep chain `A->N1->...->N5->G` (the only path is 6
hops):

```
cap=3  ->  DEPTH_CAPPED  (none)                  steps=4  frontier=1
cap=6  ->  FOUND         A->N1->N2->N3->N4->N5->G steps=7  frontier=1
```

A depth bound smaller than the only path's length makes DFS **fail by the
cap**. The result is the explicit status `DEPTH_CAPPED` (we refused to extend a
branch at the bound) — *not* a silent `UNREACHABLE`. That distinction matters: it
tells the caller "a path may exist beyond the bound; raise the cap," instead of
wrongly implying no path exists.

**(2) BFS frontier growth** on a bushy tree (`A->B1..B3`, each `Bi->2 C's->G`):

```
DFS  FOUND  A->B1->C1->G  hops=3  steps=4   frontier=4
BFS  FOUND  A->B1->C1->G  hops=3  steps=11  frontier=6
```

BFS holds a **whole layer of distinct nodes at once**, so its frontier peaks at
the layer width (6) and its step count is high (it expands the entire breadth
before `G` surfaces). DFS descends one branch and keeps only per-level siblings,
so its frontier stays smaller (4). The gap widens with bushiness:
**BFS frontier ~ branching^depth, DFS ~ branching·depth** — BFS's minimum-hop
guarantee is paid for in memory on wide graphs.

---

## Experiment table (5 variants)

```
default     | DFS FOUND h4 s5  f2 | BFS FOUND h3 s7  f2
no-shortcut | DFS FOUND h4 s5  f2 | BFS FOUND h4 s6  f2
distractor  | DFS FOUND h4 s7  f3 | BFS FOUND h3 s9  f3
wide        | DFS FOUND h3 s4  f4 | BFS FOUND h3 s11 f6
deep        | DFS FOUND h6 s7  f1 | BFS FOUND h6 s7  f1
```

**Correctness check** (driver): on every reachable variant both find a path, and
**BFS hops ≤ DFS hops** everywhere — BFS's shortest-by-hops guarantee holds, and
DFS is never shorter. `PASS`.

---

## Reflection

- **Which wins for which workload?** If you need the *shortest* route, BFS — it
  guarantees it. If you only need *a* route and the graph is deep/sparse with
  cheap-to-follow branches, DFS finds one with less work and a tiny frontier.
- **Where does each break down?** BFS breaks down on **memory** for wide/bushy
  graphs (frontier ~ branching^depth). DFS breaks down on **quality** (can return
  a needlessly long path) and on **wasted work** in dead-end-heavy graphs; under a
  depth cap it can miss a path that exists beyond the bound.
- **Asymptotic vs measured.** Both are O(V+E) to traverse, so asymptotics call it
  a tie — but the *measured* behavior separates them: hop quality, expansion
  count, and especially frontier size differ sharply by graph shape. That is the
  whole point of measuring rather than reasoning from big-O alone.

## Assumptions that must remain true

- The graph is **unweighted** (BFS's minimum-*hops* guarantee equals
  minimum-*cost* only when every edge costs the same). With weights, neither
  competitor is correct for shortest cost — you would move to Dijkstra/A*.
- Edges are **directed** as given; neighbor order is edge-insertion order, which
  fixes DFS's deterministic branch preference.
- Node identity is by string name; ids are unique.
- For DFS, the **visited-on-path** guard assumes we want simple paths (no repeated
  node); for BFS, **visited-on-enqueue** assumes each node need be expanded once.

## How contracts shaped the design

`Path_Run_Result`'s invariants keep every run self-describing (non-empty
strategy/status, non-negative hops/steps/frontier). Each competitor's
postcondition pins the result to a known status set (`known_status`) and ties
`FOUND` to a real path (`found_has_path: status ≠ FOUND or hops ≥ 1`) — both
guards are safe under Nex's eager `or` because neither side touches a collection.
The DFS depth cap is surfaced as a *distinct contracted status* (`DEPTH_CAPPED`)
rather than folded into `UNREACHABLE`, so "bound too small" can never be mistaken
for "no path." BFS's global guarantee (shortest by hops) can't be expressed as a
local postcondition, so it is verified as **evidence** in the driver's
correctness check (BFS hops ≤ DFS hops on every variant) — the lab's way of
turning a claim into a measurement.

## Tradeoffs / notes

- Graph **variants are separate immutable graphs** built from edge lists, not one
  mutated graph — each experiment is reproducible in isolation, and it sidesteps
  Nex's copy-on-read semantics for collection fields (a map field is built in a
  local and assigned once).
- The iterative DFS uses an explicit **path stack** (so it reconstructs the path
  and counts honestly); its frontier is therefore `O(branching·depth)`, not the
  `O(depth)` of a recursive DFS. The asymptotic comparison above reflects this
  implementation.
- BFS uses a **head pointer** instead of removing from the front of the queue
  (O(1) dequeue, no array shifting).
