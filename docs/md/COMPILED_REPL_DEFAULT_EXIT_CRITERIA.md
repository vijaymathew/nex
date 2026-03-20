# Compiled REPL Default Exit Criteria

` :backend compiled ` is safe to make the default only when all of these are true.

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

## Short Checklist

- [x] Add a compiled-REPL soak suite for long progressive sessions
- [x] Add interpreter-vs-compiled parity tests for values, output, errors, and state
- [~] Add explicit deopt and reopt round-trip tests for `:load`, `intern`, imports, classes, closures, and concurrency
- [ ] Audit and document the exact remaining deopt cases
- [ ] Eliminate all known compiled-REPL-only correctness bugs
- [x] Verify docs runners stay green on both backends
- [~] Verify debugger behavior is safe under compiled-default routing
- [~] Confirm common REPL workflows do not regress in latency or stability

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

What remains open from this area:

- Deopt and reopt round-trip coverage is not yet exhaustive across every important surface, especially debugger interaction and other long-session operational edges.
- Debugger safety is only partially covered today: the current route-to-interpreter behavior is tested, but compiled-default readiness still needs confidence around broader debugger workflows and mixed long sessions.
- The exact remaining deopt set still needs a tighter audit and documentation pass.
- Compiled-default readiness still depends on eliminating residual compiled-REPL-only correctness bugs as they are found.
- Performance has a first gate now, but the threshold values still need continued observation against real workloads and normal developer machines.

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

## Flip Condition

Make compiled the default only when every box above is checked and there are no open correctness issues tagged against compiled REPL behavior.
