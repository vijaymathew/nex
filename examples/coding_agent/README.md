# A Coding Agent in Nex

Give it a task in plain words, and it works on a project by itself: it
looks at the files, runs the checks, reads the code that matters, changes
it, runs the checks again, and tells you what it did. The thinking is done
by Claude; this program is everything around it.

```
$ nex code.nex --replay sessions/fix_late_fines.json --yes
(workspace/ is a fresh copy of sample_project/)

> Some of the checks fail. Find out why and fix it.

I'll start by looking at the project and running its checks.
  list_files .
    README.md ...  (4 lines)
  run_command nex checks.nex
    exit code 1 ...  (12 lines)

Four checks fail, all about the due day and the day after. ...
  search days_late
    checks.nex:37: checker.check("returned early: not late", loan.days_...  (5 lines)
  read_file loans.nex
    1  -- loans.nex: a book lent to a borrower, and when it is due back...  (38 lines)
  edit_file loans.nex
    Edited loans.nex: replaced 2 lines with 2 lines. It now reads: ...  (7 lines)
  run_command nex checks.nex
    exit code 0 ...  (9 lines)

All 7 checks pass now. ...
```

No agent framework or SDK is used. Everything is written out in Nex: the
conversation as data, the agent loop, the tools, the sandbox, permissions,
the HTTP client for Claude's Messages API with retries, and recording and
replaying sessions. Contracts check the rules as it runs: paths stay in the
workspace, every tool call is answered, an edit matches exactly one place.

The tutorial `tutorial/coding_agent_tutorial.pdf` builds the whole project
step by step, explaining each decision along the way.

## Quick start

```bash
cd examples/coding_agent
nex checks.nex                                         # 62 checks, no API key needed
nex code.nex --replay sessions/fix_late_fines.json --yes   # watch a recorded session
```

To have Claude do the work, set your API key and give the agent a task:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
nex code.nex "Some of the checks fail. Find out why and fix it."
nex code.nex                           # type tasks one after another
```

The agent works in `workspace/`, a copy of `sample_project/` (a small
lending library whose fines come out wrong), so the original is never
changed. It asks before it edits a file or runs a command; `--yes` lets it
go ahead.

`run_command` runs commands with `bash`, and the sample project's checks
need `nex` on your `PATH`.

## Options

```
nex code.nex [options] [task]
```

| Option | Meaning |
|---|---|
| `--replay FILE` | play back a recorded session (no API key needed) |
| `--record FILE` | record the session as it runs, for `--replay` |
| `--from FOLDER` | work in `workspace/`, a fresh copy of FOLDER (default: `sample_project`) |
| `--dir FOLDER` | work in FOLDER itself; its files will change, so use version control |
| `--fresh` | start `workspace/` again from a fresh copy |
| `--yes` | let the agent edit files and run commands without asking |
| `--read-only` | never let it edit files or run commands |
| `--model ID` | which Claude (default `claude-opus-5-5`) |
| `--effort LEVEL` | `low`, `medium`, `high`, `xhigh` or `max` (default `high`) |
| `--max-turns N` | the most replies one task may take (default 40) |
| `--timeout SECS` | how long a command may run (default 120) |
| `--full-output` | print every tool's output in full |

When it asks before a change, answer `y` (yes), `n` (no) or `a` (always
allow that tool for the rest of the session).

## The tools

| Tool | What it does | Needs permission |
|---|---|---|
| `list_files` | lists the workspace, or a folder of it | no |
| `read_file` | reads a file, with numbered lines, 500 at a time | no |
| `search` | finds lines matching a regular expression, as `file:line: text` | no |
| `edit_file` | replaces one piece of text with another; the old text must occur exactly once | yes |
| `write_file` | creates or replaces a whole file | yes |
| `run_command` | runs a bash command in the workspace, with a time limit | yes |

## Files

| File | What it does |
|---|---|
| `helpers.nex` | Text helpers: counting occurrences, lines, numbering, shortening long output |
| `messages.nex` | The conversation as data: tool calls, tool results, replies (kept exactly as the API sent them) |
| `model.nex` | The `Model` class; models played by a script, by a recording, and a recorder |
| `workspace.nex` | The folder the agent works in, and the rule that it never reaches outside it |
| `tools.nex` | The six tools, and checking the model's input to them |
| `permissions.nex` | Who decides whether a tool may run: everything, read-only, ask first |
| `context.nex` | Counting tokens; summing up and starting afresh when the conversation gets long |
| `agent.nex` | The agent loop, the system prompt, and what is printed as it works |
| `claude_model.nex` | Claude's Messages API over HTTP: the request, retries, errors |
| `code.nex` | The program |
| `checks.nex` | Executable checks of all of the above, with scripted models and a pretend API server |
| `sample_project/` | The project the agent works on |
| `sessions/` | Recorded sessions, for `--replay` |

## Recorded sessions

`--record FILE` saves every request's new message and every reply to FILE
as the agent works. `--replay FILE` plays the replies back, in a fresh copy
of the same folder, while the agent really runs the tools. It compares
what it sends with the recording, and says if the tool results came out
differently (with another version of `nex`, say).

A session file holds commands, so a replay asks before running them unless
you give `--yes`. The `model` field of a session says who played the
model's part.

## The tutorial

The tutorial's LaTeX source, `tutorial/tutorial.tex` and the chapters it
inputs, is also the source of the code: every line of every `.nex` file
appears in it, in the order a reader adds it.

```bash
cd tutorial
python3 build.py            # rebuild the code from the tutorial, run every
                            # program, compare with ../*.nex, typeset the PDF
python3 build.py --no-pdf   # everything except typesetting
python3 build.py --pdf-only # only typeset, using the last build's outputs
python3 build.py --sync     # also copy the tutorial's code into the project
```

`NEX=<path to bin/nex>` builds with another `nex` (a checkout's, say). The
build never talks to Claude: it removes any API key from the environment,
and the model is played by scripts and the recorded session. Building the
PDF needs `pdflatex` (TeX Live).
