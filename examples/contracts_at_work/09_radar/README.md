# Project 9 — "Nex Radar": Composing What You've Built

Part VI of *Contracts at Work*, the capstone. Composes Project 5's
`Dup_Finder`, an HTTP status API in Project 7's style, and a background
`Task` in Project 3/6's style into one monitoring service — plus a Project
8-style Swing dashboard meant to watch it live. **The dashboard cannot
currently reach the service, and the reason why is the actual point of this
chapter**, not a footnote: it's a real, load-bearing conflict between two
already-documented bugs, discovered only at integration time, which is
exactly what a capstone is for.

## Files

| File | Role | Status |
|---|---|---|
| `dup_finder.nex` | Copy of Project 5's finder, unmodified. | — |
| `radar_service.nex` | `Radar_Metrics`, `Radar_Service` — GET `/status`, POST `/rescan`, a background tick `Task`. | **Works**, `--interpret` |
| `radar_service_main.nex` | Runs the service on port 4323. | **Works**, `--interpret` |
| `checks.nex` | Builds a duplicate-file tree, drives the service with a real `Http_Client`, checks scan counts and rescan behavior. | **6/6**, `--interpret` |
| `radar_dashboard_state.nex`, `radar_dashboard_listeners.nex` | Plain-Nex-state + real-`ActionListener` split, Project 8's pattern. | Correct code, blocked — see below |
| `radar_dashboard_main.nex` | Swing client meant to poll the service. | **Blocked** — see below |

## Running

```bash
nex checks.nex --interpret         # 6/6 — the composition that actually works

nex radar_service_main.nex --interpret   # terminal 1 — the service, works fully
nex radar_dashboard_main.nex             # terminal 2 — builds and runs, but see below
```

## What held: the service half

`radar_service.nex` is a genuine composition, not a facade: `Dup_Finder` is
`intern`ed unmodified from Project 5, the route/response shape follows
Project 7's `json_response` pattern, and the tick counter is a background
`Task` running a free function (Project 6's fix, applied from the start
this time rather than discovered the hard way again). `checks.nex` verifies
the whole thing together — initial scan on startup, `GET /status` reporting
real counts, `POST /rescan` picking up newly-added duplicate files and
incrementing `scan_count`. This part of "composing what you've built" held.

## What didn't: the dashboard half, and why it's structural, not a bug to route around

The plan, following Project 8: buttons set flags on plain `Radar_Dashboard_
State`, a poll loop performs the actual HTTP fetch and renders the result —
same split, same discipline, same reason (a listener touching only Nex
state stays clear of the Java-typed-field bug). Building and smoke-testing
it surfaced a **new** bug, not yet seen in this book:

**`net/Http_Client` returns `status: 0` and an empty body for every
request on the JVM (compiled) backend, while working correctly on the
interpreter.** Confirmed directly against the real, running service (not a
guess from a missing connection): `curl` against the same URL at the same
moment returns a correct `200` with the real JSON body, and the identical
Nex `client.get(...)` call, run with `--interpret`, also returns the
correct `200` and body. Only the compiled backend gets `status: 0`, an
empty body, and (calling `.headers()` on the response) a further `Type
error: a value was not of the expected type`. Reproduced for both `GET` and
`POST`, and with `127.0.0.1` in place of `localhost` to rule out name
resolution. The compiled backend's underlying HTTP request primitive
appears not to populate the response object at all, while the interpreter's
does.

**This is what makes the dashboard's design impossible today, on *either*
backend, not just inconvenient on one:**

- The JVM backend is what Project 8 established real `ActionListener`
  state needs — the interpreter's `Proxy`-based dispatch loses mutations.
- The interpreter is what this project's own service needs for
  `data/Json`, and now also what `net/Http_Client` needs to return a real
  response at all.

A dashboard that both holds correct button state *and* successfully
fetches over HTTP has no backend to run on right now — each backend
supplies exactly one of the two things it needs. `radar_dashboard_main.nex`
is kept as written: it's correct against the language as documented, the
architecture is the one this book has validated everywhere else it applies,
and it should run unmodified once either bug is fixed. It just doesn't run
today, and that's recorded here rather than concealed by quietly not
building it.

## What this chapter actually teaches

Every project through Project 8 was validated in isolation and held up.
Composing two of them — a JSON-backed service and a Swing dashboard, each
individually solid — surfaced a conflict neither project's own tests could
have caught, because each was tested against the one backend it needed and
never against the other. That's not a special property of this book's
example code; it's what integration finds that unit-level work structurally
cannot. The honest version of "composing what you've built" includes the
composition that doesn't compose yet, with the reason nailed down precisely
enough to hand to whoever fixes the underlying bug.
