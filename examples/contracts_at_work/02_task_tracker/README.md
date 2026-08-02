# Project 2 — A Persistent Task-Tracker CLI

Part I of *Contracts at Work*. A small task tracker: add, list, complete, and
remove tasks, persisted to disk between runs, with a schema that can evolve
without breaking old data files.

## Files

| File | Role |
|---|---|
| `task.nex` | `Task` — id, text, priority, and a `Task_Status` union (`Open`/`Done`). |
| `task_store.nex` | `Task_Store` — the collection: add/find/mark_done/remove, plus JSON (de)serialization and file I/O, kept as separate concerns. |
| `tasks_cli.nex` | Interactive command loop over a `Task_Store`, persisted next to wherever it's run. |
| `checks.nex` | Executable checks for `Task_Store`, including a schema-migration check. |

## Running

```bash
nex checks.nex --interpret     # 18/18 checks — must run under --interpret, see below
nex tasks_cli.nex --interpret  # interactive; type `add 3 buy milk`, `list`, `done 1`, `remove 1`, `quit`
```

Both **must** run under `--interpret`. This project is not runnable on the
JVM (compiled) backend at all right now — see the first issue below.

## Status: works, under three confirmed language bugs and one CLI-launcher quirk

Everything is implemented and passing (18/18 checks, `--interpret`); getting
there surfaced four separate issues, each with a minimal repro kept out of
this project's own files. Full detail in the book's top-level issues report;
summarized here because they directly shaped this project's code:

1. **`data/Json`-parsed `Map` values are unusable on the JVM backend.** Not
   just nested arrays — *any* method call (`get`, `keys`, `contains_key`) on
   a `Map` returned by `Json.parse` fails with `Type error: a value was not
   of the expected type`, even a bare `m.get("a")` on a one-key object. The
   interpreter handles every case correctly. This is why the file format is
   one flat JSON object per line rather than a single
   `{"tasks":[...]}` document (originally suspected to be about nesting
   specifically; turned out to be broader), and why this project runs under
   `--interpret` rather than the default JVM backend.
2. **Mutating through a `?T`-typed binding doesn't persist.** `let t: ?Task
   := array.get(i); t.mutate()` mutates the local's own copy, invisible to
   any other reference to "the same" object — reads through a `?T` binding
   are fine, only mutation is affected. `Task_Store.mark_done` mutates
   `tasks.get(i)` directly instead of going through `find`.
3. **On the interpreter, an object both stored in a collection and returned
   from the same method stops sharing identity after either is mutated** —
   the JVM backend does not have this problem, but is unusable here anyway
   (issue 1). `Task_Store.add` returns the new task's `id`, not the `Task`
   object, so nothing ever holds a long-lived reference that could go
   stale — arguably the better API regardless.
4. **`old <mutable-array-field>.<method>()` snapshots the reference, not the
   value** — `old tasks.length` in an `ensure` clause sees the *post*-mutation
   length, because `old` doesn't deep-copy the array. Worked around by
   capturing the count in a local before mutating.

One more, not a language bug: **`tasks_cli.nex` builds its file path from
`NEX_USER_DIR`, not a bare relative path.** The `nex` CLI's dev-mode launcher
`cd`s into its own installation directory before starting the JVM, so a
relative path resolves there instead of wherever the program was actually
run from; `NEX_USER_DIR` (which the launcher sets beforehand) is read via
`Process.getenv` and used to build an absolute path instead.
