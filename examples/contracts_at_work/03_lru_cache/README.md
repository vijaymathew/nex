# Project 3 — A Generic LRU Cache

Part II of *Contracts at Work*. A fixed-capacity, least-recently-used cache:
a small reusable library, not an app. `Lru_Cache [K, V]` is the entire
public surface; a class invariant (never exceed capacity, the two internal
structures always agree on size) *is* the specification.

## Files

| File | Role |
|---|---|
| `lru_cache.nex` | `Lru_Cache [K, V]` — `get`/`put`/`contains`/`size`, capacity-eviction on `put`. |
| `checks.nex` | Executable checks: eviction order, touch-on-read, overwrite-in-place, a capacity-1 edge case. |
| `lru_demo.nex` | A short scripted walkthrough — not interactive, a fixed sequence with printed commentary on what evicts and why. |

## Running

```bash
nex checks.nex              # 13/13 checks
nex checks.nex --interpret  # also 13/13 — this project has no backend split
nex lru_demo.nex
```

## Status: clean

No issues. Both backends agree on every check, first attempt — the only
project in this book so far where that was true. Worth noting precisely
*because* the others weren't: this project's operations are all
scalar/collection manipulation on `Map`/`Array` with no file I/O, no JSON,
no `?T`-typed mutation, and no `old` on a mutable field in a postcondition —
exactly the combination of things that turned out to be where this pass of
Nex's bugs cluster. A library kept to core language + `Map`/`Array` is, for
now, the safest ground to build on.
