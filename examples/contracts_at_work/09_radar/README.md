# Project 9 — "Nex Radar": Composing What You've Built

Part VI of *Contracts at Work*, the capstone. Composes Project 5's
`Dup_Finder`, an HTTP status API in Project 7's style, and a background
`Task` in Project 3/6's style into one monitoring service, watched live by
a Project 8-style Swing dashboard — as **two separate processes talking
over HTTP**, not one program. That split was forced by real backend
incompatibilities discovered while building this project, most of which
have since been fixed upstream; the retrospective below is kept accurate to
what actually happened rather than quietly rewritten to look like the
two-process design was the plan from the start.

## Files

| File | Role | Status |
|---|---|---|
| `dup_finder.nex` | Copy of Project 5's finder, unmodified. | — |
| `radar_service.nex` | `Radar_Metrics`, `Radar_Service` — GET `/status`, POST `/rescan`, a background tick `Task`. | **Works**, JVM backend |
| `radar_service_main.nex` | Runs the service on port 4323. | **Works**, JVM backend |
| `checks.nex` | Builds a duplicate-file tree, drives the service with a real `Http_Client`, checks scan counts and rescan behavior. | **6/6**, both backends |
| `radar_dashboard_state.nex`, `radar_dashboard_listeners.nex` | Plain-Nex-state + real-`ActionListener` split, Project 8's pattern. | **Works**, JVM backend |
| `radar_dashboard_main.nex` | Swing client polling the service live. | **Works**, JVM backend |

## Running

```bash
nex checks.nex                           # 6/6, either backend now

nex radar_service_main.nex               # terminal 1 — JVM backend
nex radar_dashboard_main.nex             # terminal 2 — JVM backend, connects to terminal 1 live
```

Both processes now run on the default JVM backend. Smoke-tested end to
end: the dashboard's background tick counter visibly increments as it
polls the live service, `refresh` and `rescan` both round-trip real HTTP
requests, and state renders correctly through the poll loop.

## What held, and what it took to get there

This project went through three real integration attempts, and the first
two failed for reasons no individual project's own tests could have
caught — which is exactly what a capstone chapter is for.

**Attempt 1 — one process.** Blocked because Project 8's `ActionListener`
state needed the JVM backend, while this service's `data/Json` usage
needed the interpreter. No backend satisfied both.

**Attempt 2 — two processes over HTTP, first try.** Also blocked: while
building the dashboard as an HTTP client, `net/Http_Client` turned out to
return `status: 0` and an empty body for every request on the JVM
backend — confirmed against `curl` hitting the same live server at the
same moment, which got a correct response while the identical Nex call
didn't. That made even the two-process design impossible on either
backend, for a *different* reason than attempt 1's.

**What actually fixed it:** both root causes — `data/Json`'s method calls
on the JVM backend, and `Http_Client`'s response handling on the JVM
backend — were fixed upstream in the language itself. Once both were
fixed, **attempt 1's original one-process design became
possible again**, but by then the two-process, HTTP-boundary design was
already built, tested, and arguably the better architecture regardless —
watching a service from a separate process over a real network boundary is
closer to how monitoring actually works than a monitor sharing a process
with the thing it watches. So the split was kept on its own merits, not
reverted just because the constraint that originally forced it went away.

**One thing the fix didn't reach:** interning `net/Http_Client` and
`data/Json` together still breaks `.get()` on a parsed Map — but only
*inside a `function ... do ... end` body*. The exact same code as bare
top-level script statements does not trigger it (confirmed directly: moving
identical code out of `function main() do ... end` into top-level
statements made the failure disappear). `radar_dashboard_main.nex`'s
`fetch_status`/`fetch_rescan` are functions, so they still read the
response with `text/Regex` rather than `data/Json`, exactly as Project 7's
`calc_client.nex` does — that workaround is still load-bearing, just
narrower than originally understood.

## What this chapter actually teaches

The lesson isn't "it all works now, moving on." It's that **composing two
independently-correct pieces surfaced two real, separate bugs that neither
project's own isolated tests exercised** — because each was tested against
the one backend it needed, never against the other, and never in
combination with a second library making outbound calls from inside the
same function. That gap is what integration finds and unit-level work
structurally cannot, whether or not upstream fixes eventually arrive. This
project's history — blocked, blocked differently, fixed, kept the better
design anyway — is the honest version of "composing what you've built."
