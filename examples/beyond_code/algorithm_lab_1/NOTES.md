# Algorithm Lab 1 — The First Experiments

One task — *find a target in a sorted sequence* — implemented with four
strategies that all return the same `Search_Result` (so logs stay comparable),
each carrying an explicit **step counter** (one step = one comparison of an array
element against the target). The point is engineering intuition from evidence,
not micro-optimization.

## Files

| File | Role |
|---|---|
| `algorithm_lab_1.nex` | `Search_Result` + the four strategy classes + the `Algorithm_Lab_1` façade. |
| `algorithm_lab_1_main.nex` | Driver: Tasks A, B, C + an automated correctness sweep. |
| `NOTES.md` | This write-up (tradeoffs, reflection answers, contracts paragraph). |

Run from this directory: `nex algorithm_lab_1_main.nex`.

The driver pulls the strategies in with `intern Algorithm_Lab_1` (the entry
class, named so its lowercased form matches the filename; loading it registers
every class in the module). Unlike the chapter's hand-unrolled eight-argument
(`a1..a8`) sketch, the strategies run over a real `Array [Integer]` with real
loops, so the experiments scale to any size and every step count is genuine —
binary search of the sample for `30` costs 4 probes here, exactly as in the book.

## Strategies

| Strategy | Input assumption | Idea | Order |
|---|---|---|---|
| `Linear_Search` | none | scan left → right | O(n) |
| `Binary_Search` | sorted | halve the range each probe | O(log n) |
| `Jump_Search` | sorted | hop in √n blocks, then scan one block | O(√n) |
| `Interpolation_Search` | sorted | probe where the value *should* be | ~O(log log n) uniform → O(n) skewed |

---

# Task A — Correctness Check

Run on `[3, 7, 10, 14, 19, 21, 25, 30]` at four positions. All four strategies
return the **same found/index**; only the **steps** differ.

| target | linear | binary | jump | interpolation |
|---|---|---|---|---|
| `3` first (idx 0) | found, 1 | found, 3 | found, 2 | found, **1** |
| `14` middle (idx 3) | found, 4 | found, **1** | found, 4 | found, 2 |
| `30` last (idx 7) | found, 8 | found, 4 | found, 6 | found, **1** |
| `11` absent (idx -1) | not found, 8 | not found, 3 | not found, 4 | not found, **1** |

Reading the evidence:
- **Linear** cost = position (or full `n` on a miss): cheapest only when the
  target is near the front.
- **Binary** is steady (≤ 4 = ⌈log₂8⌉+1) and shines for the *middle* (1 step).
- **Jump** sits between linear and binary (≈ √n + block scan).
- **Interpolation** is dramatic on this near-uniform data — it predicts the
  position and often lands in 1 probe — but that depends on uniformity (see
  tradeoffs).

The automated **correctness sweep** in the driver cross-checks all four against
linear-search truth for every element and a spread of misses (below / between /
above the range): `PASS`. So "all strategies are correct" is measured, not
asserted.

---

# Task B — Constraint Violation

Calling the order-dependent strategies on **unsorted** input
`[3, 30, 10, 14, 7, 21, 25, 19]`:

```
binary:        blocked -> Precondition violation: sorted_input
interpolation: blocked -> Precondition violation: sorted_input
linear:        runs (no order assumption) -> found=true
```

- **What failure signal do you get?** A *precondition violation* raised at the
  call boundary, naming the broken assumption (`sorted_input`). It is an
  exception you can `rescue`, not a return value to inspect.
- **Why is this better than silent wrong results?** Without the contract, binary
  search on unsorted data would happily return a *wrong* index (or a false
  "not found") that looks exactly like a correct answer — a bug discovered far
  downstream, if ever. The precondition converts "wrong answer" into "loud
  failure at the exact place the assumption was broken," which is cheap to
  diagnose. Linear search has no such assumption, so it is allowed to run.

---

# Task C — Extension (jump + interpolation)

Two extra strategies were added in the same `Search_Result` format. Step counts:

```
target 30 (hit):   linear 8 | binary 4 | jump 6 | interpolation 1
target 11 (miss):  linear 8 | binary 3 | jump 4 | interpolation 1
```

`Jump_Search` uses block size √n (= 2 for n = 8): it hops a[1], a[3], a[5], a[7]
until a block's end reaches the target, then scans that block — a middle ground
that needs only sorted order (no division). `Interpolation_Search` interpolates
the probe index from the values at the window ends; on uniform data it is the
clear winner, but it carries an extra precondition's worth of risk on skewed
data (it can degrade toward O(n)).

---

# Reflection

- **Which strategy has stricter input assumptions?** Binary, jump, and
  interpolation all require `sorted`; **interpolation is effectively the
  strictest** — beyond sorted order it *assumes a roughly uniform distribution*
  to deliver its speed. Break that (clustered values) and its step count climbs
  while binary's does not. Linear assumes nothing.
- **Which is easiest to reason about for correctness?** **Linear search.** Its
  invariant is trivial ("everything before `i` has been checked and rejected"),
  it has no precondition, and it cannot mis-probe. Binary is next; interpolation
  is hardest (the index arithmetic plus the divide-by-zero guard on a flat
  window).
- **At what scale would you stop using linear search?** As soon as `n` is more
  than a few hundred *and* the data is sorted and searched repeatedly: linear's
  cost grows with `n` (8 steps at n = 8; ~1000 at n = 1000), while binary stays
  ≈ log₂n (~10 at n = 1000). The crossover in *steps* is immediate for a miss;
  the practical crossover accounts for the one-time cost of keeping data sorted.
- **Which contract prevented the most dangerous misuse?** `sorted_input` on
  binary/jump/interpolation. A silent wrong answer from an order-dependent search
  on unordered data is the worst failure here — correct-looking and undetected.
  The precondition makes that misuse impossible to perform quietly.

---

# How contracts influenced the design

Contracts were not decoration; they shaped the code. The `sorted_input`
precondition is the honest statement of each fast strategy's assumption, and it
turned Task B from "observe a subtle wrong result" into "observe a named failure
at the call." The `Search_Result.confirms` postcondition (`verified`) makes every
strategy *check its own answer against the array* — if a refactor ever returned a
hit whose element did not equal the target, the run would stop at that method,
not three layers away. `steps_positive`/`steps_bounded` keep the cost metric
meaningful (and caught a real issue: interpolation could report 0 probes on an
out-of-range target, so we now count the endpoint comparison that justified the
rejection). Because Nex's `or` does not short-circuit, the correctness guard
lives inside `confirms`'s `if` rather than a `found = false or a.get(index)=…`
expression — a small reminder that the contract language has its own semantics.
All of these checks can be stripped from a production build, leaving the same
source as executable specification.

## Tradeoffs / notes

- **Step metric** = element-vs-target comparisons (probes). `is_sorted` (an O(n)
  check) runs only as a contract; with contracts stripped it costs nothing at
  runtime, so it does not distort the comparison of search work.
- **Integer probe arithmetic:** `(lo + hi) / 2` and the interpolation formula use
  Nex integer division, which floors — correct for indexing. The interpolation
  search guards `a[hi] = a[lo]` (a flat window) to avoid divide-by-zero and uses
  a value-window guard so the probe stays in range.
- **Jump block size** is ⌊√n⌋ via an integer `isqrt` (no floating point), at
  least 1.
