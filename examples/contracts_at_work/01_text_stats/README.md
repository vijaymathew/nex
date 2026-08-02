# Project 1 — A Text-Analysis CLI (`nexwc`)

Part I of *Contracts at Work*. A small word-frequency tool: given a text
file, report line and word counts and the most frequent words.

## Files

| File | Role |
|---|---|
| `word_stats.nex` | `Word_Stats` — pure, host-free library: line/word counts, top-N word frequency. `Count_Entry` is its result type. |
| `nexwc.nex` | CLI entry point: reads a file path and an optional `--top n` flag, prints the report. **Currently blocked — see below.** |
| `checks.nex` | Executable checks for `Word_Stats`, run directly, no file/CLI involved. |
| `sample.txt` | Sample input for manual runs. |

## Running

```bash
nex checks.nex               # library checks — works today, both backends
nex checks.nex --interpret

nex nexwc.nex sample.txt              # blocked, see below
nex nexwc.nex sample.txt --top 3      # blocked, see below
```

## Status: library done and tested; CLI entry point blocked upstream

`word_stats.nex` is complete and passing (8/8 checks, both backends —
`nex checks.nex` / `nex checks.nex --interpret`). It requires no CLI
argument handling at all, so it isn't affected by the issue below.

`nexwc.nex` is written and reads as intended, but **cannot run**, because
`Process.command_line()` does not return the program's actual arguments on
either backend. Confirmed with a minimal repro (not specific to this
project): a one-function `.nex` file that prints `proc.command_line()`
reports `argc=0` even when compiled to a standalone jar and run as
`java -jar app.jar foo.txt --top 3` — the real argv is dropped entirely, on
every execution path (`--interpret`, default JVM-backend run, and a
standalone compiled jar). The root cause: `nex-process-command-line`
(`src/nex/types/runtime.clj:726-727`) is wired to
`ManagementFactory/getRuntimeMXBean().getInputArguments()`, which is the
JVM's own launch flags (`-Xmx512m`, `-XX:...`), not `main(String[] args)`.
The real argv is already sitting right there in `nex.eval/-main`'s `args`
binding (`src/nex/eval.clj:195-197`) — it's parsed, filtered for
`--interpret`, and then never threaded through to `command_line()`. The
compiled-jar backend needs the equivalent fix on its own generated
`main(String[] args)`.

Until that's fixed, no Nex CLI tool can read its own arguments, on either
backend. `nexwc.nex` is kept as-is (correct against the language as
documented) rather than worked around with something like reading arguments
from stdin — that would defeat the point of this project, which is
specifically about parsing `argv` at a program's boundary. It should run
unmodified once `command_line()` is fixed.
