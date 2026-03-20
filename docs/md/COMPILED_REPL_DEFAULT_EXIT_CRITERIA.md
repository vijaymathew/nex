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
- [ ] Verify debugger behavior is safe under compiled-default routing
- [ ] Confirm common REPL workflows do not regress in latency or stability

## Current Progress

The following exit-criteria work is already in place:

- A dedicated compiled REPL soak suite exists in [compiled_repl_soak_test.clj](/home/vijay/Projects/nex/test/nex/compiler/jvm/compiled_repl_soak_test.clj).
- The soak suite includes long progressive sessions with mixed features, repeated deopt and reopt transitions, and state assertions.
- Explicit interpreter-versus-compiled parity assertions now exist for the same scripted sessions.
- There is a focused `:load` scenario that exercises repeated object-method calls across later deopts and compiled recovery.
- Tutorial and book example runners are green on both the interpreter backend and the compiled backend.

What remains open from this area:

- Deopt and reopt round-trip coverage is not yet exhaustive across every important surface, especially `intern`, imports, and debugger interaction in long sessions.
- The exact remaining deopt set still needs a tighter audit and documentation pass.
- Compiled-default readiness still depends on eliminating residual compiled-REPL-only correctness bugs as they are found.

## Flip Condition

Make compiled the default only when every box above is checked and there are no open correctness issues tagged against compiled REPL behavior.
