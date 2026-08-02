# Project 5 — A Concurrent Duplicate-File Finder

Part III of *Contracts at Work*. Walks a directory tree and reports groups
of files with identical content, fanning the I/O-bound work (reading every
file) across one `Task` per file and collecting with `await_all`.

## Files

| File | Role |
|---|---|
| `dup_finder.nex` | `Dup_Finder` — `collect_files` (sequential recursion), `read_all_concurrently` (fan-out/fan-in over `Task`), `find_duplicates`. `File_Content` is the per-file result type. |
| `checks.nex` | Builds a small directory tree under a temp folder, verifies duplicate detection, cleans up. |
| `scan_demo.nex` | Runs `Dup_Finder` against this project's own directory — always present, nothing to set up. |
| `cancellation_demo.nex` | A small, separate illustration of `Task.await(ms)` timeout and `Task.cancel()` — see note below. |

## Running

```bash
nex checks.nex               # 4/4 checks, both backends
nex checks.nex --interpret
nex scan_demo.nex
nex cancellation_demo.nex
```

## Status: clean

No language bugs hit. `Task`, `Channel`/`await_all`, and recursive
`Directory`/`Path` walking all worked as documented, both backends, on the
first fully-corrected attempt.

One real design note, not a bug: `cancellation_demo.nex` is deliberately
**separate** from `dup_finder.nex`'s own code path. `find_duplicates`'s
`await_all` waits for every read unconditionally — nothing in it takes a
timeout or exposes a cancel token, so there's no natural place inside the
actual duplicate-finder to demonstrate "give up and cancel in-flight work."
`cancellation_demo.nex` is a small, worked illustration of the mechanism
(`await(ms)` returning `nil` on timeout, then `cancel()`) standing in for
what a cancel-aware version of the scan would need, rather than a feature
exercised by `find_duplicates` today.

Also worth naming, since it shaped the file's structure: `intern <Name>`
requires a top-level declaration literally named `<Name>` in the
snake_case-matched file. `dup_finder.nex` originally held only a
`File_Content` class and loose top-level functions — `intern Dup_Finder`
failed with `Class Dup_Finder not found`. Wrapping the functions as methods
on a `Dup_Finder` class fixed it, and reads better besides.
