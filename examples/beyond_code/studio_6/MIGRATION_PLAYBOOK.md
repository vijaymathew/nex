# Migration + Rollback Playbook — Studio 6

Migration is **code**, not informal ops notes: a batched, canary-gated,
**reversible** migration (`migration_runner.nex`). This playbook is the runbook
around it.

## Model

```
run_batch(size, canary_passed):
   migrated_count += size            # 1. optimistically APPLY the batch
   if canary_passed:                 # 2. CANARY decides
       checkpoint = migrated_count   #    COMMIT: advance the safe point
       status = IN_PROGRESS
       -> MIGRATION_CONTINUE
   else:
       migrated_count = checkpoint   #    ROLLBACK: revert exactly this batch
       status = ROLLED_BACK
       -> ROLLBACK
```

Contracts that make this trustworthy:
- `reverts_on_rollback`: after a ROLLBACK, `migrated_count = checkpoint` (exact).
- `committed_consistent`: after a COMMIT, `migrated_count = checkpoint`.
- invariants: counts non-negative, `status ∈ {IN_PROGRESS, ROLLED_BACK}`.

`rollback()` is also exposed as a manual cord-pull (operator-triggered), with the
same exact-revert guarantee.

## Gate conditions (advance only if ALL hold)

A batch's canary is "passed" only when every signal is green:

| Signal | Pass condition |
|---|---|
| Behavior parity / canary divergence | `canary_compare` divergences ⊆ the intended change set |
| Reliability invariants | world `bounded` and Studio 5 checks still pass on migrated data |
| Error/latency budget | within SLO for the batch window |
| Serving-mode mix | trending toward target (e.g. V2_NATIVE share rising) |

If any signal is red → `canary_passed = false` → automatic ROLLBACK of the batch.

## Run log (from `studio_6_main.nex`)

```
batch 1 (canary pass): MIGRATION_CONTINUE  count=100
batch 2 (canary pass): MIGRATION_CONTINUE  count=200
batch 3 (canary FAIL): ROLLBACK            count=200 (reverted to checkpoint)
batch 4 (canary pass): MIGRATION_CONTINUE  count=300
committed batches=3 final count=300
```

Batch 3's failed canary reverted the optimistic +100 exactly; batch 4 resumed
from the committed checkpoint (200 → 300). No partial/torn state.

## Rollback procedure

1. **Trigger:** any gate signal red, or an operator decision.
2. **Action:** the in-flight batch reverts to `checkpoint` automatically
   (`run_batch` with a failed canary), or call `rollback()` to revert to the last
   commit.
3. **Verify:** `migrated_count = checkpoint`, `status = ROLLED_BACK`.
4. **Diagnose:** pull the canary signals for the failed window; fix forward.
5. **Resume:** re-run the batch once green; a successful `run_batch` clears the
   rolled-back status and advances the checkpoint.

## Pre-flight checklist

- [ ] Additive path exists for every changed shape/outcome (no breaking-only step).
- [ ] Compatibility matrix (see `VERSIONING_PLAN.md`) green for all live clients.
- [ ] Canary metrics + thresholds defined for this migration.
- [ ] Rollback verified in a dry run (`run_batch(size, false)` reverts exactly).
- [ ] Reliability invariants asserted on a migrated sample.
- [ ] Owner sign-off recorded.

## Release gate (reliability + evolution sign-off)

Block release unless:
- `EVOLUTION_CHECK:PASS` (old behavior preserved, new behavior correct,
  world `bounded` invariant holds under both versions).
- Rollback is demonstrated reversible (exact checkpoint revert).
- Deprecation roadmap dates/owners are current (`VERSIONING_PLAN.md`).
