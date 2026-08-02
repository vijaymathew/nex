# Project 8 — A Live Desktop Dashboard

Part V of *Contracts at Work*. A real, event-driven Swing window: four
buttons (`+`, `-`, `reset`, `quit`), each wired to a real `ActionListener`
implemented in Nex. Validated by a feasibility spike
(`examples/contracts_at_work/spike_swing/`) before this project was
written — that spike found real interface-implemented Swing listeners work
today, provided the listener holds no field typed as an imported Java
class, and recommended the split this project follows.

## Files

| File | Role |
|---|---|
| `dashboard_state.nex` | `Dashboard_State` — plain Nex fields only (`count`, `should_quit`), no Java types anywhere. |
| `dashboard_listeners.nex` | `Increment_Listener`/`Decrement_Listener`/`Reset_Listener`/`Quit_Listener`, each a real `ActionListener` implementation that only ever touches `Dashboard_State`. |
| `dashboard_main.nex` | Builds the real window, wires the listeners, then a plain polling loop (`Thread.sleep` + `setText`, no `Task`) renders `state.count` into the label. **The only code that ever touches the `JLabel`.** |
| `checks.nex` | Drives the listeners through real `JButton.doClick()` calls — the same style of check the spike used — no visible window required. |

## Running

```bash
nex checks.nex             # 6/6 — must run on the JVM backend, not --interpret, see below
nex dashboard_main.nex     # a real window; needs a display
```

## Status: works, on the JVM backend only — confirms and extends the spike's finding

`checks.nex` is 6/6 on the JVM backend. Run under `--interpret`, 4 of the 6
fail — the same general fault the spike already diagnosed (mutable state
mutated through the interpreter's `Proxy`-based interface dispatch is
unreliable), though the exact shape here is worth recording precisely
rather than assumed identical to the spike's repro:

```
PASS three increments        (0 -> 1 -> 2 -> 3, via inc_button alone — accumulates correctly)
FAIL one decrement expected=2 actual=3    (dec_button's mutation never shows up)
FAIL reset to zero expected=0 actual=3    (reset_button's mutation never shows up)
FAIL quit after click expected=true actual=false   (quit_button's mutation never shows up)
```

Repeated clicks on the *same* button/listener accumulate correctly
(`inc_button` alone: 0→1→2→3); it's a *different* listener object — a
separate `Increment_Listener`/`Decrement_Listener`/etc. instance, each
constructed with the same `Dashboard_State` — whose mutation never becomes
visible through the original `state` variable afterward. That's a more
specific symptom than the spike's original repro (which saw every call
reset to a fresh value), so this is recorded as the same *category* of bug
— unreliable state through `Proxy`-based dispatch — rather than claimed to
be mechanically identical.

This is why the project's own build order matters: `checks.nex` was written
and run on the JVM backend *first*, immediately caught this when tried under
`--interpret` out of habit (every other project in this book defaults to
`--interpret`), and traced it back to the same fault family the spike had
already named rather than chasing it as an unrelated new bug. **This project
is the one place in the book where the JVM backend is required and
`--interpret` is wrong** — the opposite default from Projects 2 and 7.

No other issues. `dashboard_main.nex` itself was smoke-tested end-to-end
(real window construction, real `doClick()` calls both before and inside
the polling loop, correct final state, clean `dispose()`) before being
trusted as the interactive entry point.
