# Backend Alignment: stages & progress

Tracking doc for aligning the tree-walking interpreter and the JVM compiler with
each other and with the Definition of Nex
(`vijaymathew.github.io/nex/docs/definition-of-nex`). Origin: a differential
audit (2026-07-03) that ran the same programs through `interp/eval-node` and
`jvm-file/compile-ast` + `Main.main` and diffed stdout.

(Historical note: the project also once maintained a JavaScript generator and a
ClojureScript interpreter runtime. Both were removed to leave a single JVM
implementation; earlier stage entries below that mention `cljs`, platform-diff,
or the JS generator are kept as a record of that period.)

Update the Status column as work lands; add a commit hash when a stage merges.

## Decisions (adopted)

| # | Decision | Rationale |
|---|----------|-----------|
| D1 | `%` is **truncated** (C/Java-style), on Integer and Real, in every backend | JVM opcodes and JS `%` are truncated natively; floored existed only in the shared helper. Spec §B.3 must be updated to say so. |
| D2 | NaN ordering follows **IEEE**: every `<` `<=` `>` `>=` against NaN is false | Spec §B.3 already requires it. |
| D3 | Integer division overflow (`MIN_LONG / -1`, and `MIN_LONG % -1` is fine) **raises**, like all checked integer ops | Spec §B.3 checked-arithmetic rule. |
| D4 | Built-in exceptions carry the **interpreter's canonical messages** ("Division by zero", "Used a value that is void (nil)", "long overflow") in compiled code too. Real named exception *values* (`Division_by_Zero`, …) are a separate, later project. | Cheap now; value classes need spec + typechecker work. |
| D5 | `convert` **never coerces numerics**: `convert i to r: Real` with Integer runtime value is `false` (use `to_real()`); statically-decidable cases should become a typecheck error | Integer ⋠ Real in the spec's conformance relation (§4.3); interpreter already behaves this way. |
| D6 | `==` on scalars coincides with `=` (so `5 == 5.0` is `true`), per spec §5.1 | Interpreter already behaves this way. |

## Stage A — numeric tower & small divergences (landed 2026-07-03)

| Item | Change | Status |
|------|--------|--------|
| A1 | `%` truncated + zero-check with canonical message: `nex-int-mod`/`nex-int-div` in `types/runtime.clj` become the checked entry points; interpreter `apply-binary-op` uses them; lowering routes int `/` `%` through `op:div-*`/`op:mod-*` runtime helpers (like `op:pow-*`) instead of raw LDIV/LREM; real `%` truncated via `nex-real-rem` | done |
| A2 | NaN ordering: `emit-long-or-double-compare!` uses DCMPG for `<`/`<=` (DCMPL for `>`/`>=`); interpreter comparisons get a numeric fast path (`nex-numeric-lt` etc.) instead of the 3-way compare | done |
| A3 | `MIN_LONG / -1` raises (part of the new div helper) | done |
| A4 | `5 == 5.0` → true compiled: `identity-equals` in `compiler/jvm/runtime.clj` uses `nex-numeric-equals?` for numeric pairs | done |
| A5 | Bitwise 32-bit island: interpreter no longer raises on overflowing values — `int32`/`bit-index` truncate with `unchecked-int` | done |
| A6 | Nil-deref in compiled dynamic dispatch raises "Used a value that is void (nil)" instead of a raw Java NPE (`invoke-user-method` nil check) | done |
| A7 | Known gap: compiled comparisons on *boxed* (Any-typed) reals still go through the 3-way `runtime-compare-values`, which cannot express NaN-unordered | open |

## Stage B — convert semantics (landed 2026-07-03)

| Item | Change | Status |
|------|--------|--------|
| B1 | Compiled convert with a failing runtime check now yields `false` + nil binding instead of crashing. Root cause was not `convert-value` (its predicate was already correct) but the *binding slot*: `ensure-convert-binding` allocated the raw target type (Real → primitive `:double`), and the emitter unboxes the bound value unconditionally — nil → NPE. Convert bindings are now detachable (`{:base-type T :detachable true}` → reference slot), matching the typechecker's `?T` binding. `refine-var-non-nil` / `refine-condition-branch-env` no longer re-resolve a local's JVM type when narrowing (a slot's JVM type is fixed at allocation; re-resolving emitted DLOAD from a reference slot → VerifyError). | done |
| B2 | Typechecker rejects statically-decidable numeric converts (D5): `check-convert` errors on Integer↔Real with a hint (Integer widens implicitly where a Real is expected; `Real.round()` for the other direction — note there is no `to_real()` builtin on Integer). Any-sourced converts stay runtime-checked and yield `false` for numeric mismatches in both backends. | done |

## Stage C — loud fallback, then compiled-only CLI (landed 2026-07-03)

| Item | Change | Status |
|------|--------|--------|
| C1 | `eval.clj`: compiled *runtime* failure is the program's outcome (spec §7.3) — partial output is printed, the exception is reported (nested `InvocationTargetException` chains unwrapped), and the interpreter is **not** re-run, so side effects execute exactly once (verified with a file-append-then-raise program). Exception: a `LinkageError` (VerifyError & kin) is a backend defect, not program behaviour — it falls back with a warning. | done |
| C2 | `eval.clj`: "not compilable" fallback warns on stderr: `Warning: falling back to the tree-walking interpreter: program is outside the compiled subset (<reason>)` | done |
| C1a | Compiled-backend bug exposed by C1, fixed: **inherited generic methods** — `make-delegation-method-node` resolved the parent method's types without any `generic-param-names`, so a `T` return became `CHECKCAST T` → `ClassNotFoundException: T` at run time (`class Logged_Box [T] inherit Box[T]`). Delegation methods now resolve types with the subclass's *and* the declaring parent's generic params in scope. | done |
| C1b | Compiled-backend bug exposed by C1, fixed: **`with "java"` swallowed Nex dispatch** — the `:with-java?` lowering branch outranked the builtin/user-class branches, so `console.print_line(...)` inside a with-"java" block was lowered as host reflection (`No matching method print_line … PersistentArrayMap`). The branch now applies only to targets that are not statically known Nex receivers, matching its own docstring ("unresolved target calls"). | done |
| C3 | Close the lowering gaps so `lib/` + examples compile — **done**; repo corpus went from 86 to 99 compiling, and every remaining "not compilable" in the survey is an intern-path artifact of the survey itself. The six gaps and their actual fixes: (1) *named constructors on imported Java classes* — the interpreter ignores the name and reflectively invokes the host constructor, lowering now does the same; (2) *`create Map` / `create Set`* lower to the empty map/set literal IR; (3) *builtin free-function return types* — lowering's ad-hoc 9-entry list replaced with a full `builtin-free-function-return-types` table mirroring the typechecker's `builtin-call-checkers` (fixes `datetime_*` in `lib/time/date_time.nex`); (4) *`Integer64`* was an **undeclared type** used by `lib/time` and `lib/io/path` (erased by the interpreter, un-lowerable by the compiler) — replaced with `Integer` in the lib sources; (5) *`apl.nex` compiler NPE* — lowering calls `tc/types-compatible?` with a lowering env, and `env-lookup-type-alias` deref'd a missing `:type-aliases` atom; now nil-safe; (6) *paren-less builtin-method chains* (`this.area.to_string`) — inference falls back to the Any/Comparable/Hashable protocol types (`to_string`→String, `equals`→Boolean, `hash`/`compare`→Integer). Also fixed `examples/sample.nex`, which used `Account.balance` (class-qualified parent *field* access) — invalid in both engines. | done |
| C3a | Typechecker gap noticed while fixing sample.nex: `Parent.field` (class-qualified parent-field access) passes the typechecker but is rejected by both engines at different stages (interpreter at runtime, compiler at lowering). Should be a typecheck error; `Parent.routine(...)` is the supported form. | open |
| C4 | `eval.clj` is compiled-only: a program outside the compiled subset is now a hard error naming the construct and suggesting `--interpret`; the flag (CLI: `nex app.nex --interpret`, API: `(eval-file path {:interpret? true})`) runs the tree-walking interpreter explicitly. `bin/nex` forwards trailing args and documents the flag. The one automatic fallback kept is a `LinkageError` (backend defect, not program behaviour) — loud warning + interpreted run rather than failing a valid program. | done |

## Stage D — extract the builtin runtime

| Item | Change | Status |
|------|--------|--------|
| D1 | **Builtin library extracted** (landed 2026-07-03). Two new engine-neutral namespaces: `nex.types.concurrency` (tasks, channels, the queue/promise plumbing — clj + cljs variants, ~536 lines) and `nex.types.builtins` (`builtin-type-methods`, `call-builtin-method`, `builtin-type-method-return-type`, plus the value-level helpers they're built from: ordering, structural hashing, membership, sorting, heaps, atomics, `concat-string-value`, `report-contract-violation`; ~995 lines). Engine-specific behaviour is injected via `set-engine-hooks!` (`:nex-object?`, `:make-object`, `:object-equals-override`, `:call-object-method`, `:user-to-string`), following the `*value-equals*`/`*value-hash*` precedent in `nex.types.runtime`; the interpreter registers its eval-node-backed hooks at load. `interpreter.clj` shrank 4757 → 3494 lines and re-exports the moved names as aliases, so no other interpreter code changed. `compiler/jvm/runtime.clj` and `lower.clj` now take `call-builtin-method`/`nex-format-value`/`concat-string-value` and the builtin-method metadata from `nex.types.builtins` directly (interp/ call sites in runtime.clj: 59 → 36). Verified: full suite, cljs platform-diff 21/21, differential sweep unchanged. | done |
| D2 | **Free-function table + Java interop extracted** (landed 2026-07-03). The `builtins` table (print/println, type_of/type_is, regex/datetime/path/file/json/http families, await_*, http-server routes), `print-output-value`, `runtime-type-name`/`runtime-type-is?`, the http-server plumbing, and the Java-interop helpers (`resolve-imported-java-class`, `java-create-object`, `java-call-method`) all moved to `nex.types.builtins`. Two new engine hooks: `:add-output` (interpreter accumulates on the ctx; default writes to console) and `:is-parent?` (class-hierarchy query); http-server handler dispatch reuses `:call-object-method`. **`lower.clj` no longer requires `nex.interpreter` at all** — builtin names/metadata come from `nex.types.builtins` and the base-class defs from `nex.types.bootstrap` directly. `runtime.clj` interp/ sites: 36 → 31; `interpreter.clj` 3494 → 2801 lines. | done |
| D3 | Retire the deopt path — decision: **option (a)**, drop the interpreted REPL backend. First increment landed 2026-07-03: the user-facing `:backend interpreter`/`compiled`/`status` commands are **removed** (compiled is the only REPL backend; `*repl-backend*` is pinned `:compiled`, and the `--interpreter` flag of `check_docs_examples.clj` is a developer diagnostic that pokes the atom directly). Three bridge pieces retired from `runtime.clj`: channel creation now calls `conc/make-channel` directly (was: a `:create` node through a fresh interpreter context), imported-class resolution passes a plain `{:imports …}` map instead of rebuilding an interpreter ctx, and `runtime-compatible-with?`/`compiled-is-parent?` walk the program classes plus `nex.types.bootstrap` base-class defs with no interpreter involved. | in progress |
| D3b | What remains of the bridge (30 `interp/` sites, 12 `rebuild-interpreter-ctx`): the REPL's *internal* narrow fallback still evaluates some cells with the interpreter and syncs both ways — wrapped expression inputs, cells referencing REPL `declare type` aliases, three construct predicates (`ast-needs-interpreter-fallback?`), two `fallback-eligible-compiled-error?` runtime errors, and debugger sessions (`:debug on` evaluates interpreted). Retiring those means teaching `compiled-repl` to execute wrapped/expression cells and alias-typed vars, then deleting `sync-session->interpreter!`/`sync-interpreter-back-into-compiled-session!` and the `nex-object?` deopt branches. Separately: http request/response objects are built through the `:make-object` hook (interpreter's `NexObject` when loaded), so compiled method calls on them still route through `invoke-interpreter-object-method`. | open |

## Big-ticket items (parked, not forgotten)

1. Interpreter object aliasing (value semantics + write-back) — objects in
   field slots / array elements go stale. Fix or retire the interpreter as an
   execution engine (Stage C/D make the latter viable).
2. Closure capture semantics — compiled closures mutate a *copy* of captured
   locals, interpreter writes back. Needs a decision: write-back boxes in
   compiled closures, or typechecker rejects assignment to captured locals.

## Spec updates owed (definition-of-nex repo)

All DONE (2026-07-04), spec updated to match the implementation:

- §B.3: truncated `/` and `%` (integer and real, with the `a = (a/b)*b + a%b`
  identity), `MIN/-1` raises overflow, real `% 0.0` is NaN, NaN ordering
  comparisons false.
- §2.6 + Appendix A.5: `^` documented left-associative (`2^3^2 = 64`).
- Appendix C.2: `repeat` translation drops the `_n` binding; bound documented
  as re-evaluated per iteration.
- §2.8/§5.8: `with` renamed "host block"; new §5.8 subsection defines it
  (recognised facility → host name resolution; unrecognised → block skipped;
  does not open a scope — `let` bindings escape). JVM facility: `"java"`.
- §3.3 + Appendix A: field initialisers removed from the grammar; new
  `constant` member production (`id ⟨: ty⟩ = exp`), documented as class-level,
  immutable, accessed bare in-class / `C.x` outside; §5.5 field defaults
  updated (zero / nil, no initialiser clause).

Remaining spec/impl gap (spec leads impl, left in spec deliberately): `once`
fields (§3.3, rule 4.17, §4.4, §5.5) are specified but the parser does not
accept `once` — decide: implement or remove from the Definition.

## Verification

- Differential harness (session scratchpad, easy to recreate ~50 lines: parse
  once, run `interp/eval-node` vs `jvm-file/compile-ast`+`Main.main`, diff
  stdout). Cases: mod signs, real mod, NaN compares, mixed `==`, overflow,
  `MIN/-1`, bitwise shifts, div-by-zero messages, nil-deref message.
- Full suite: `clojure -M:test test/scripts/run_tests.clj`
