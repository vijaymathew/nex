# Project 1 — A Text-Analysis CLI (`nexwc`)

Part I of *Contracts at Work*. A small word-frequency tool: given a text
file, report line and word counts and the most frequent words.

## Files

| File | Role |
|---|---|
| `word_stats.nex` | `Word_Stats` — pure, host-free library: line/word counts, top-N word frequency. `Count_Entry` is its result type. |
| `nexwc.nex` | CLI entry point: reads a file path and an optional `--top n` flag, prints the report. |
| `checks.nex` | Executable checks for `Word_Stats`, run directly, no file/CLI involved. |
| `sample.txt` | Sample input for manual runs. |

## Running

```bash
nex checks.nex               # library checks — 8/8, both backends
nex checks.nex --interpret

nex nexwc.nex sample.txt              # line/word counts, top 5 words
nex nexwc.nex sample.txt --top 3      # top 3 words instead
```

## Status: works, both backends, exit codes correct

`Process.command_line()` was fixed upstream after this project was first
built — it originally returned the JVM's own launch flags instead of the
program's real arguments, which meant no Nex CLI tool could read `argv` at
all. Confirmed fixed directly: a standalone compiled jar now reports the
real `argc`/`argv` it was invoked with, on the interpreter, the default JVM
backend, and a `nex compile jvm` standalone jar alike. `nexwc.nex` needed no
changes to its argument-parsing logic — it was written correct against the
language as documented and simply started working once the bug was fixed.

One thing it *did* need: a bare relative path (`nexwc.nex sample.txt`)
resolves against the `nex` CLI's own installation directory, not wherever
the program was actually run from (the launcher `cd`s into `NEX_HOME`
before starting the JVM). This is by design, not a bug — see the book's
top-level issues report. `resolve_path` in `nexwc.nex` reads `NEX_USER_DIR`
via `Process.getenv` and resolves any non-absolute path against it, the
same workaround used in Projects 2, 5, and 9.
