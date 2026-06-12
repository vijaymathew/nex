# Algorithm Lab 2 — Decision Note (one page)

**Decision:** for "find a path from A to G in an unweighted graph," default to
**BFS**, with **depth-bounded DFS** as the fallback under memory stress. Backed
by measured runs across five graph variants (`algorithm_lab_2_main.nex`).

## Decision template

- **Problem objective:** *shortest path* (minimum hops), not mere reachability.
  The task names a specific quality bar — fewest hops from A to G.

- **Dominant workload shape:** small-to-moderate, **unweighted** directed graphs
  that are not extremely bushy. Paths are short (3–6 hops in the fixtures), and
  the graph fits comfortably in memory.

- **Correctness requirement:** **best path** (fewest hops), not "any path." A
  valid-but-longer route is a *wrong* answer for this objective — which is
  exactly what DFS returns on the default graph (`A->B->D->E->G`, 4 hops, vs
  BFS's `A->C->F->G`, 3 hops).

- **Preferred algorithm and why: BFS.** It *guarantees* the minimum-hop path on
  unweighted graphs — the one property the objective demands. The measured cost
  of that guarantee is acceptable for the target workload: a few extra node
  expansions (7 vs 5 on the default graph) and a frontier bounded by the layer
  width. The correctness check confirms BFS hops ≤ DFS hops on **every** variant;
  DFS is never shorter, and is often longer.

- **Fallback strategy under stress: depth-bounded DFS.** When the graph turns
  **wide/bushy**, BFS's frontier grows ~ branching^depth (measured: frontier 6 and
  11 expansions on the small tree, where DFS needed frontier 4 and 4 expansions).
  There, DFS's `O(branching·depth)` frontier is the cheaper traversal *if* a
  shortest path is not strictly required. The depth cap converts "ran out of
  memory exploring forever" into an explicit, contracted `DEPTH_CAPPED` result
  (measured: cap=3 on the 6-hop chain reports `DEPTH_CAPPED`, not a false
  `UNREACHABLE`), so the caller can raise the bound deliberately.

## Evidence (excerpt)

```
default     | DFS h4 s5  f2 | BFS h3 s7  f2     <- BFS shorter, DFS cheaper
distractor  | DFS h4 s7  f3 | BFS h3 s9  f3     <- dead-end inflates DFS steps
wide        | DFS h3 s4  f4 | BFS h3 s11 f6     <- BFS frontier/steps blow up
deep+cap3   | DFS DEPTH_CAPPED                  <- explicit bound signal
```

## Assumptions that must remain true

1. **Unweighted edges** — BFS's minimum-*hops* equals minimum-*cost* only here.
   Introduce edge weights and the decision changes (Dijkstra / A*).
2. **Graph fits in memory** — if not, BFS's frontier is the first thing to break;
   switch to the DFS fallback (or iterative deepening).
3. **Directed edges as specified**, unique string node ids, neighbor order =
   insertion order (DFS determinism depends on it).
4. **Simple paths wanted** (no node repeats) — the cycle guards encode this.

If assumption (1) or (2) stops holding, revisit this note: weighted graphs retire
both competitors, and out-of-memory graphs promote the DFS fallback to default.
