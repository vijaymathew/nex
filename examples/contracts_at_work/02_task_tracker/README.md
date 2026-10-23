# Project 2 — A Persistent Task-Tracker CLI

Part I of *Contracts at Work*. A small task tracker: add, list, complete, and
remove tasks, persisted to disk between runs, with a schema that can evolve
without breaking old data files.

## Files

| File | Role |
|---|---|
| `task.nex` | `Task` — id, text, a `Priority` refinement type, and a `Task_Status` union (`Open`/`Done`). |
| `task_store.nex` | `Task_Store` — the collection: add/find/mark_done/remove, plus JSON (de)serialization and file I/O, kept as separate concerns. |
| `tasks_cli.nex` | Interactive command loop over a `Task_Store`, persisted next to wherever it's run. |
| `checks.nex` | Executable checks for `Task_Store`, including a schema-migration check. |

## Running

```bash
nex checks.nex      # 18/18 checks, default JVM backend
nex tasks_cli.nex    # interactive; type `add 3 buy milk`, `list`, `done 1`, `remove 1`, `quit`
```

## Status: works cleanly on the default JVM backend

This project originally needed `--interpret` for several reasons, all since
fixed upstream — `Task_Store` was rewritten to use the idiomatic form once
each fix was confirmed, rather than leaving defensive workarounds for bugs
that no longer exist:

- **`data/Json`-parsed `Map` method calls on the JVM backend** — fixed;
  this project no longer needs `--interpret` at all.
- **Refinement types (`declare type ... where`) declared in an interned
  file** — fixed; `Priority` is a real refinement type again
  (`task.nex`), checked at `Task.make`'s parameter boundary, instead of a
  `require` clause standing in for one.
- **`old <mutable-array-field>.length` snapshotting the reference instead
  of the value** — fixed; `Task_Store.add`'s postcondition reads `old
  tasks.length` directly again.
- **Mutating through a `?T`-typed binding not persisting** — fixed;
  `mark_done` goes back through `find` and mutates the returned optional
  directly, rather than indexing into the array to avoid it.

`Task_Store.parse_json` reads the file through `data/Json`'s typed
reader: `json.read(line)` gives a `Json_Value`, and each field is asked
for as the type the schema says it is (`row.get("id").as_integer`,
`row.get("text").as_string`, ...). A row that doesn't match the schema
raises an error naming the field, such as `Json: id is a string, expected
an integer`, instead of being patched up with defaults or skipped: the CLI
saves after every change, so a file read only partly would be overwritten
and the rest of it lost.

The one-JSON-object-per-line file format (rather than a single
`{"tasks":[...]}` document) started as a workaround for a now-fixed
nested-array bug; it's kept regardless, since a flat, append-friendly,
diffable format is arguably the better choice for a save-on-every-change
task store either way.
