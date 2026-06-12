# Studio 5 — Reliability Notes (Levels 1–3)

**Reliability.** Studio 4 gave clean seams (ports/adapters/services). Studio 5
hardens them: explicit failure semantics, bounded retry, idempotency, degraded
fallback, invariant-preserving recovery, and an observability trail — all
enforced **at the service/port boundary**, with domain invariants intact on every
failure path.

## Files

| File | Role |
|---|---|
| `reliability_types.nex` | `Op_Result`, `Retry_Policy`, `Reliability_Log`. |
| `delivery_reliability.nex` | `Dispatch_Port` (+ healthy/flaky/broken) + retry/idempotency service. |
| `knowledge_reliability.nex` | `Knowledge_Query_Port` (+ available/unavailable) + degraded-fallback service. |
| `world_reliability.nex` | `World_Move_Port` (bounded + invariant) + recovery service. |
| `studio_5_main.nex` | **Levels 1 & 2** driver: failure-path logs + regression gate. |
| `level3_incident_drills.nex` | **Level 3** incident drills (repro/patch/regression). |
| `RELIABILITY_POLICY.md` | Policy sheet + release-gate checklist (deliverable). |
| `INCIDENT_PLAYBOOK.md` | Incident playbook: repro + fix + regression (deliverable). |

Run: `nex studio_5_main.nex`, `nex level3_incident_drills.nex`.

The collaborators are **ports** (Studio 4 seams), so failure modes are injected
through them (flaky / broken / unavailable) without touching the services —
polymorphism via Nex `inherit`. This is what makes failures *reproducible*.

## Explicit failure semantics (the vocabulary)

| Layer | Outcomes |
|---|---|
| `Dispatch_Port.send_dispatch` | `SENT` · `TRANSIENT_FAILURE` · `PERMANENT_FAILURE` |
| `Op_Result.status` | `OK` · `FAILED` · `IDEMPOTENT_OK` · `DEGRADED` · `RECOVERED` |
| `Knowledge` | `OK` · `DEGRADED` |
| `World` | `OK` · `RECOVERED` |

Failure is a contracted value, never an exception-as-control-flow or a sentinel
sharing a type with a real answer. `Retry_Policy.retryable(status)` is the single
source of truth for "may this be retried?" (transient: yes; permanent: no).

---

# Level 1 — Single Reliability Mechanism (Delivery)

`Delivery_Reliability_Service.dispatch_with_retry` adds **bounded retry +
idempotency** to the dispatch use case. Measured (from `studio_5_main.nex`):

```
dispatch T-7 (flaky, fails 1-2):  status=OK            attempts=3   (recovered within bound)
dispatch T-7 (repeat):            status=IDEMPOTENT_OK attempts=0   (deduped, no send)
dispatch T-8 (always transient):  status=FAILED        attempts=3   (bounded: stops at max)
dispatch T-9 (permanent):         status=FAILED        attempts=1   (NOT retried)
```

Regression checks assert each line. The two failure paths (bounded exhaustion;
permanent-not-retried) are the point: retry is *bounded* and *selective*.

---

# Level 2 — Three-Domain Reliability Pass

One mechanism per domain, each a contract-defined behavior:

| Domain | Mechanism | Failure path observed |
|---|---|---|
| Delivery | bounded retry + idempotency | transient recovers; permanent fails fast; repeat deduped |
| Knowledge | degraded fallback | index down → `DEGRADED` + labelled cache (`DOC:CACHED-001`), logged |
| World | invariant-preserving recovery | extreme delta → `RECOVERED`, clamped to `[0, max_x]`, invariant holds |

**Failure-path run log (observability):** every attempt/fallback/step is appended
to a `Reliability_Log` and dumped, e.g.

```
... | delivery task=T-8 attempt=3 -> TRANSIENT_FAILURE | delivery task=T-9 attempt=1 -> PERMANENT_FAILURE
   | knowledge fallback-used query=contracts | world step delta=5 x:9->10 | ...
```

Gate: `RELIABILITY_CHECK:PASS`.

---

# Level 3 — Incident Drills

Each incident: **REPRO** (failure observed) → **PATCH** (fixed at the seam) →
**REGRESSION** (assertion locking the fix). See `INCIDENT_PLAYBOOK.md`; results
from `level3_incident_drills.nex` (`3/3 pass`):

| Incident | Repro | Patch (at the seam) | Regression |
|---|---|---|---|
| **A · Delivery** retry storm on a dead carrier | naive retry calls the port **3×** for one permanently-failing task | `Retry_Policy.retryable` ⇒ permanent stops at 1 | port called **1×**, `attempts=1` |
| **B · Knowledge** outage leaks `UNAVAILABLE` | raw port returns the token `UNAVAILABLE` to callers | `safe_query` degrades to labelled cache + logs | `DEGRADED` / `DOC:CACHED-001`, never the raw token |
| **C · World** bad input drives out of bounds | an out-of-bounds object can't even be built (precondition fires) | `safe_step` clamps; invariant holds | `+1000`→`CLAMPED@10`, `-1000`→`CLAMPED@0`, always in bounds |

The drills measure the fix (call counts, status vocabulary, invariant) rather
than asserting it — that's what stops the incident from recurring silently.

---

# Postmortem (with evidence)

- **Largest stability gain?** The **permanent/transient split** in `Retry_Policy`.
  Incident A measured a 3×→1× drop in calls against a dead dependency: the naive
  retry amplified load on the exact thing that was already failing. Distinguishing
  retryable failures removed an entire class of self-inflicted overload, and
  **idempotency** removed duplicate side effects on the success path.
- **Which failures should not be retried?** `PERMANENT_FAILURE` (malformed/rejected
  work — retrying only duplicates load and side effects) and anything already
  succeeded (idempotency short-circuits it). Only `TRANSIENT_FAILURE` is retried,
  and only up to the bound.
- **Acceptable vs dangerous fallback?** Knowledge's degraded mode is **acceptable
  because it is labelled and bounded**: it returns `DOC:CACHED-001` (clearly a
  cache) and sets `status=DEGRADED` so callers can branch. It would be **dangerous**
  if it fabricated a *fresh-looking* result or silently returned the raw
  `UNAVAILABLE` token — Incident B exists precisely to forbid that
  (`degraded_is_cache` postcondition + regression).
- **Diagnostics that made root-cause fastest?** The append-only `Reliability_Log`.
  Because it keeps the *full* attempt sequence (not just the last event), the run
  log alone shows "T-8 failed transiently 3× then exhausted" vs "T-9 failed
  permanently once" — the difference between a flaky dependency and a dead one, at
  a glance.

---

# Tradeoffs / notes

- **Ports fail closed.** Each base port's default is the *safe* failure
  (`PERMANENT_FAILURE` / `UNAVAILABLE`), so an unwired collaborator degrades
  loudly instead of pretending success.
- **Idempotency is in-memory** (a `Map` of dispatched ids in the service). A real
  system needs a durable idempotency key store; the mechanism and its contract are
  the same.
- **`Op_Result.make` has no `recorded` postcondition** because its parameters
  share names with its fields (so a bare name in `ensure` would not refer to the
  parameter) — the invariants carry the guarantees instead. The `Reliability_Log`
  appends via local-then-reassign, since a collection-field read returns a copy.
- **Retry has no backoff/jitter.** `Retry_Policy` is attempts + retryability only;
  backoff is the obvious next parameter and would slot into the same object.
