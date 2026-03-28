# Algorithm Lab — When Algorithms Compete {-}

## Subtitle

Benchmarking competing algorithm choices under realistic workload shapes.

::: {.note-lab}
**Lab Focus**
Run controlled experiments, collect evidence, and compare algorithm choices under explicit assumptions.
:::

## Why This Lab Exists

The first algorithm lab built intuition that different strategies behave differently.

This lab goes one step further: direct competition under controlled scenarios.

The goal is to answer engineering questions with evidence:

- Which algorithm wins for our workload profile?
- Where does each algorithm break down?
- Which assumptions must hold for the chosen strategy?


## Lab Objectives

By the end of this lab, you should be able to:

- compare competing algorithms for the same requirement
- evaluate normal-case vs adverse-case behavior
- separate asymptotic reasoning from measured behavior
- justify a strategy choice with explicit tradeoffs


## Competition Setup

Use one concrete problem:

> "Find the shortest path from A to G in an unweighted graph."

Competing strategies:

- DFS-style search (finds a path, not guaranteed shortest by hops)
- BFS-style search (guarantees minimum hops in unweighted graphs)

Why this setup works:

- same input graph
- same success/failure contract
- different quality/cost behavior


## Nex Implementation

Suggested files:

- `algorithm_lab_2.nex`
- `algorithm_lab_2_main.nex`


### Shared Result Type

```nex
class Path_Run_Result
create
  make(strategy, status, path: String, hops: Integer, steps: Integer) do
    this.strategy := strategy
    this.status := status
    this.path := path
    this.hops := hops
    this.steps := steps
  end
feature
  strategy: String
  status: String
  path: String
  hops: Integer
  steps: Integer
invariant
  strategy_present: strategy /= ""
  status_present: status /= ""
  hops_non_negative: hops >= 0
  steps_non_negative: steps >= 0
end
```

### Graph Fixture

```nex
class Graph_Fixture
create
  make_default() do
    a_b := true
    a_c := true
    b_d := true
    d_e := true
    e_g := true
    c_f := true
    f_g := true
  ensure
    initialized:
      a_b and a_c and b_d and d_e and e_g and c_f and f_g
  end
feature
  -- Teaching-sized directed edges.
  a_b: Boolean
  a_c: Boolean
  b_d: Boolean
  d_e: Boolean
  e_g: Boolean
  c_f: Boolean
  f_g: Boolean

  setup_default() do
    a_b := true
    a_c := true
    b_d := true
    d_e := true
    e_g := true
    c_f := true
    f_g := true
  ensure
    initialized:
      a_b and a_c and b_d and d_e and e_g and c_f and f_g
  end

  disable_short_branch() do
    c_f := false
  ensure
    disabled: not c_f
  end
end
```

### DFS-Style Competitor

```nex
class DFS_Competitor
feature
  run(g: Graph_Fixture): Path_Run_Result
    do
      -- Deterministic branch preference: A->B before A->C.
      let steps: Integer := 1
      if g.a_b and g.b_d and g.d_e and g.e_g then
        result := create Path_Run_Result.make(
          "DFS",
          "FOUND",
          "A->B->D->E->G",
          4,
          steps
        )
      elseif g.a_c and g.c_f and g.f_g then
        result := create Path_Run_Result.make(
          "DFS",
          "FOUND",
          "A->C->F->G",
          3,
          steps
        )
      else
        result := create Path_Run_Result.make(
          "DFS",
          "UNREACHABLE",
          "",
          0,
          steps
        )
      end
    ensure
      known_status:
        result.status = "FOUND" or
        result.status = "UNREACHABLE"
    end
end
```

### BFS-Style Competitor

```nex
class BFS_Competitor
feature
  run(g: Graph_Fixture): Path_Run_Result
    do
      -- Layer-aware logic returns minimum-hop route if available.
      let steps: Integer := 1
      if g.a_c and g.c_f and g.f_g then
        result := create Path_Run_Result.make(
          "BFS",
          "FOUND",
          "A->C->F->G",
          3,
          steps
        )
      elseif g.a_b and g.b_d and g.d_e and g.e_g then
        result := create Path_Run_Result.make(
          "BFS",
          "FOUND",
          "A->B->D->E->G",
          4,
          steps
        )
      else
        result := create Path_Run_Result.make(
          "BFS",
          "UNREACHABLE",
          "",
          0,
          steps
        )
      end
    ensure
      known_status:
        result.status = "FOUND" or
        result.status = "UNREACHABLE"
    end
end
```

### Driver: Run Competitions

```nex
class App
feature
  run() do
    let g: Graph_Fixture := create Graph_Fixture.make_default

    let dfs: DFS_Competitor := create DFS_Competitor
    let bfs: BFS_Competitor := create BFS_Competitor

    let r_dfs: Path_Run_Result := dfs.run(g)
    let r_bfs: Path_Run_Result := bfs.run(g)

    print(
      "DFS: " + r_dfs.status +
      " " + r_dfs.path +
      " hops=" + r_dfs.hops
    )
    print(
      "BFS: " + r_bfs.status +
      " " + r_bfs.path +
      " hops=" + r_bfs.hops
    )

    -- Adverse scenario: disable shorter branch.
    g.disable_short_branch
    let r_dfs_2: Path_Run_Result := dfs.run(g)
    let r_bfs_2: Path_Run_Result := bfs.run(g)

    print(
      "DFS adverse: " + r_dfs_2.status +
      " " + r_dfs_2.path +
      " hops=" + r_dfs_2.hops
    )
    print(
      "BFS adverse: " + r_bfs_2.status +
      " " + r_bfs_2.path +
      " hops=" + r_bfs_2.hops
    )
  end
end
```

Expected observations:

- both may find paths in nominal case
- BFS-style competitor returns shorter route in default fixture
- DFS-style competitor can return longer-but-valid route depending on branch order


## Lab Tasks

### Task A — Nominal Competition

Run both competitors on the default graph and record:

- found/unreachable status
- returned path
- hop count
- step count

### Task B — Workload Variation

Modify graph shape and rerun:

- remove one shortcut edge
- add one distracting dead-end edge

Document which strategy is more sensitive to each change.

### Task C — Constraint Stress

Introduce an explicit depth cap and evaluate:

- when DFS fails due to depth bound
- when BFS frontier growth becomes expensive


## Decision Template

Use this template for final strategy choice:

1. problem objective (reachability or shortest path)
2. dominant workload shape
3. correctness requirement (any path vs best path)
4. preferred algorithm and why
5. fallback strategy under stress


## Deliverables

- runnable Nex code for both competitors
- experiment table across at least 3 graph variants
- one-page decision note with selected strategy
- explicit list of assumptions that must remain true


## Forward Link

This lab closes Part V by turning algorithm choice into measurable engineering practice.

In Part VI, we move from algorithm decisions to software architecture decisions: components, boundaries, and interfaces.
