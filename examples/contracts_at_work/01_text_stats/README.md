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

`nexwc.nex` reads its arguments from the predeclared `program.arguments`
— exactly the words after the script name, on the interpreter, the default
JVM backend, and a `nex compile jvm` standalone jar alike.

One thing to know: a bare relative path (`nexwc.nex sample.txt`) would
resolve against the `nex` CLI's own installation directory, not wherever
the program was actually run from (the launcher `cd`s into `NEX_HOME`
before starting the JVM). This is by design, not a bug.
`resolve_path` in `nexwc.nex` resolves any non-absolute path against
`program.working_directory` — the folder the program was started from —
the same approach used in Projects 2, 5, and 9.
