# Compiled REPL Defaulting Record

Compiled REPL is now the default REPL backend. This note records the criteria
that were used to justify that switch, what evidence exists, and what still
needs continued observation after the flip.

## Exit Criteria

1. Semantic parity
- For supported interactive programs, compiled REPL results match interpreter REPL results:
  - returned value
  - printed output
  - raised errors
  - top-level state after execution

2. Fallback transparency
- When compiled mode deopts, the user cannot observe a semantic difference versus interpreter-only execution.
- That includes:
  - vars
  - var types
  - functions and redefinitions
  - classes
  - imports and `intern`
  - task and channel state

3. Session stability
- Long progressive REPL sessions remain correct across:
  - many evaluations
  - repeated deopt and reopt cycles
  - `:load`
  - class and function redefinition
  - mixed object, concurrency, and closure usage

4. No known user-facing compiled REPL correctness bugs
- There are no open bugs where:
  - compiled REPL errors but interpreter works
  - compiled REPL returns different values
  - compiled REPL corrupts session state

5. Deopt surface is explicit and small
- Remaining deopt cases are:
  - documented
  - intentional
  - covered by tests
- Normal tutorial and book workflows should not hit surprise deopts.

6. Product-path coverage
- Docs examples pass on both:
  - interpreter backend
  - compiled backend
- Default suite stays green.
- Integration suite stays green.

7. Operational safety
- Debugger interaction is either:
  - supported correctly with compiled default
  - or automatically routed to interpreter with no semantic drift

8. Performance is not worse in common REPL use
- Common interactive workloads should not regress noticeably from sync and deopt overhead.

## Release Checklist Status

- [x] Add a compiled-REPL soak suite for long progressive sessions
- [x] Add interpreter-vs-compiled parity tests for values, output, errors, and state
- [~] Add explicit deopt and reopt round-trip tests for `:load`, `intern`, imports, classes, closures, and concurrency
- [x] Audit and document the exact remaining deopt cases
- [~] Eliminate all known compiled-REPL-only correctness bugs
- [x] Verify docs runners stay green on both backends
- [~] Verify debugger behavior is safe under compiled-default routing
- [x] Confirm common REPL workflows do not regress in latency or stability

## Current Progress

The following exit-criteria work is already in place:

- A dedicated compiled REPL soak suite exists in [compiled_repl_soak_test.clj](/home/vijay/Projects/nex/test/nex/compiler/jvm/compiled_repl_soak_test.clj).
- The soak suite includes long progressive sessions with mixed features, repeated deopt and reopt transitions, and state assertions.
- Explicit interpreter-versus-compiled parity assertions now exist for the same scripted sessions.
- There is a focused `:load` scenario that exercises repeated object-method calls across later deopts and compiled recovery.
- There is a focused `import` plus `intern` scenario that exercises Java imports and local interned classes across later deopts and compiled recovery.
- There is an explicit debugger-routing regression showing the current safe behavior: when the debugger is enabled, compiled-mode REPL evaluation routes to the interpreter path instead of attempting compiled execution.
- Tutorial and book example runners are green on both the interpreter backend and the compiled backend.
- A dedicated performance harness exists in [run_compiled_repl_perf.clj](/home/vijay/Projects/nex/test/scripts/run_compiled_repl_perf.clj). It compares interpreter versus compiled REPL performance on representative interactive workloads and exits non-zero on threshold failure.
- A dedicated long-session performance harness exists in [run_compiled_repl_soak_perf.clj](/home/vijay/Projects/nex/test/scripts/run_compiled_repl_soak_perf.clj). It compares interpreter versus compiled REPL performance on longer progressive sessions, including module loading and deopt/reopt boundaries, and exits non-zero on threshold failure.

What remains open from this area:

- Deopt and reopt round-trip coverage is not yet exhaustive across every important surface, especially debugger interaction and other long-session operational edges.
- Debugger safety is only partially covered today: the current route-to-interpreter behavior is tested, but compiled-default readiness still needs confidence around broader debugger workflows and mixed long sessions.
- Compiled-default correctness still depends on eliminating residual compiled-REPL-only bugs as they are found in normal use.
- Performance gates are in place and currently passing, but the threshold values still need continued observation on real developer machines and future releases.

## Performance Gate

Run the harness with:

```bash
clojure -M:test test/scripts/run_compiled_repl_perf.clj --iterations 25 --warmup 5
```

The harness measures these workloads:

- startup: fresh top-level `let`
- steady arithmetic: repeated `x + y`
- steady function call: repeated top-level function invocation
- steady object method: repeated instance method invocation
- deopt/reopt cycle: one forced deopt followed by a compiled expression

Current acceptance thresholds:

- Startup overhead
  - `p50`: compiled must stay within `max(12x interpreter, +250 ms)`, capped at `750 ms`
  - `p95`: compiled must stay within `max(15x interpreter, +400 ms)`, capped at `1000 ms`
- Steady-state eval latency
  - `p50`: compiled must stay within `max(2x interpreter, +5 ms)`
  - `p95`: compiled must stay within `max(3x interpreter, +10 ms)`
- Deopt/reopt overhead
  - `p50`: compiled must stay within `max(3x interpreter, +15 ms)`
  - `p95`: compiled must stay within `max(4x interpreter, +30 ms)`

These thresholds are intentionally pragmatic rather than aggressive. They are meant to catch serious regressions and unacceptable overhead, not to require compiled REPL to beat the interpreter on every microbenchmark.

## Long-Session Performance Gate

Run the soak-style harness with:

```bash
clojure -M:test test/scripts/run_compiled_repl_soak_perf.clj --iterations 8 --warmup 2
```

The harness measures these longer scenarios:

- mixed progressive session: variables, functions, closures, classes, and one forced deopt
- `:load` plus repeated object-method calls across later deopt/reopt
- `import` plus `intern` across later deopt/reopt
- `:load` plus closures, concurrency, and later deopt/reopt

Current acceptance thresholds:

- Progressive mixed session
  - `p50`: compiled must stay within `max(3x interpreter, +40 ms)`
  - `p95`: compiled must stay within `max(4x interpreter, +80 ms)`
- Module-heavy sessions (`:load`, `intern`, `import`, closures, concurrency`)
  - `p50`: compiled must stay within `max(4x interpreter, +75 ms)`
  - `p95`: compiled must stay within `max(6x interpreter, +150 ms)`

These long-session thresholds are intentionally looser than the micro-workload thresholds. They are meant to catch pathological session-level overhead, especially around deopt/reopt and module state handling, without penalizing the compiled backend for doing more upfront work inside richer interactive sessions.

## Post-Flip Condition

Keep compiled as the default REPL backend only while:

- the default suite stays green
- the integration and performance gates stay green
- docs examples stay green on both interpreter and compiled backends
- no user-facing compiled-REPL-only correctness bug remains unresolved for long
