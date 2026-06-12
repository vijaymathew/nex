# Reliability Policy Sheet — Studio 5

Policies enforced at the Studio 4 service/port boundaries. Each row is backed by
code and a regression check.

## Retry policy

| Aspect | Policy | Where |
|---|---|---|
| Bound | At most `Retry_Policy.max_attempts` tries (≥ 1). | `Retry_Policy`, loop guard |
| Retryable | **Transient only.** `retryable(status) = (status = "TRANSIENT_FAILURE")`. | `Retry_Policy.retryable` |
| Non-retryable | `PERMANENT_FAILURE` (stop at attempt 1); `SENT` (done). | service loop |
| On exhaustion | Return `FAILED` with the last status and the attempt count. | `dispatch_with_retry` |
| Side effects | Guarded by idempotency (below) so retries never duplicate a success. | dispatched-id store |

## Idempotency policy

| Aspect | Policy |
|---|---|
| Scope | Any operation with an external side effect (delivery dispatch). |
| Key | The operation's natural id (`task_id`). |
| Guarantee | A key dispatched once returns `IDEMPOTENT_OK` / `ALREADY_SENT` (`attempts=0`) on every repeat — the port is **not** called again. |
| Storage | In-memory `Map` in the service (production: durable key store). |

## Fallback (degraded mode) policy

| Aspect | Policy |
|---|---|
| Trigger | Upstream returns `UNAVAILABLE` (outage). |
| Behavior | Return `status=DEGRADED` with a **labelled cache** value (`DOC:CACHED-001`). |
| Forbidden | Returning the raw `UNAVAILABLE` token, or a fabricated fresh-looking result. |
| Contract | `known_status ∈ {OK, DEGRADED}` and `degraded_is_cache` (postconditions). |
| Observability | Every fallback logs `knowledge fallback-used …`. |

## Invariant-recovery policy (world)

| Aspect | Policy |
|---|---|
| Invariant | `0 ≤ x ≤ max_x`, held for the object's whole life (class invariant). |
| Out-of-range input | Clamped; reported as `status=RECOVERED` (not silently corrected, not an error). |
| Unconstructable | An out-of-bounds object cannot be created (`start_in_bounds` precondition). |

## Non-retryable failure catalogue

| Failure | Retry? | Rationale |
|---|---|---|
| `PERMANENT_FAILURE` | No | Won't recover; retry duplicates load/side effects (Incident A). |
| Already succeeded | No | Idempotency: side effect already happened. |
| `UNAVAILABLE` (knowledge) | No (fallback instead) | Degrade to cache; retrying a known outage adds latency, not success. |
| Out-of-range world input | No (clamp instead) | Recover deterministically; the value is bad, not transient. |
| `TRANSIENT_FAILURE` | **Yes**, up to the bound | The only retryable class. |

---

# Release Gate Checklist — Reliability Sign-off

Block release unless **all** are checked:

- [x] **Explicit failure semantics** — every guarded op returns a contracted
      status (`Op_Result` / port result), no raw tokens leak to callers.
- [x] **Retry is bounded** — every retry loop is governed by `Retry_Policy.max_attempts`.
- [x] **Retry is selective** — permanent failures are not retried
      (`retryable` consulted); regression: Incident A (3×→1×).
- [x] **Idempotency on side-effecting ops** — repeat requests dedupe
      (`IDEMPOTENT_OK`, `attempts=0`); regression: delivery repeat check.
- [x] **Degraded mode is intentional & labelled** — fallback returns the cache,
      never raw outage tokens; regression: Incident B.
- [x] **Invariants hold on failure paths** — extreme/invalid input cannot corrupt
      state; regression: Incident C (`bounded` invariant, `start_in_bounds`).
- [x] **Observability present** — a `Reliability_Log` trail exists and is
      assertable; the run log shows the full attempt/fallback/step sequence.
- [x] **Regression checks green** — `RELIABILITY_CHECK:PASS` and incident drills
      `3/3 pass`.

Sign-off = the two drivers exit 0 with the gates above green.
