# Incident Playbook — Studio 5

Three simulated production incidents, each with reproduction, fix (at the Studio 4
seam), and the regression that prevents recurrence. Runnable:
`nex level3_incident_drills.nex` (`3/3 pass`).

---

## INC-A · Delivery — Retry storm on a dead carrier

- **Severity:** high (self-inflicted load amplification).
- **Symptom:** a task the carrier permanently rejects is retried to the bound on
  every request; load against a dead dependency multiplies.

**Reproduction**
1. Use a dispatch port that always returns `PERMANENT_FAILURE`
   (`Counting_Broken_Port`).
2. Drive it with the *naive* retry (loops to `max_attempts` regardless of failure
   kind).
3. Observe: port called **3×** for one task (`REPRO (naive): port called 3 times`).

**Root cause:** the retry loop did not distinguish transient from permanent
failures — it retried everything.

**Fix (at the service boundary):** the service consults
`Retry_Policy.retryable(status)`; `PERMANENT_FAILURE` is not retryable, so the
loop stops after attempt 1. No port/domain code changed.

**Regression:** `res.status = FAILED and res.attempts = 1 and port.calls = 1`
(and the naive path still demonstrably calls 3×). → PASS.

---

## INC-B · Knowledge — Outage leaks `UNAVAILABLE` to callers

- **Severity:** medium (downstream corruption).
- **Symptom:** during an index outage, the raw token `UNAVAILABLE` was returned as
  if it were a search result and rendered downstream.

**Reproduction**
1. Use the unavailable query port.
2. Call `fetch_top("contracts")` directly → returns `"UNAVAILABLE"`
   (`REPRO (raw port): fetch_top = UNAVAILABLE`).

**Root cause:** callers used the port directly, with no degraded-mode policy, so
the outage token leaked through.

**Fix (at the service boundary):** `Knowledge_Reliability_Service.safe_query`
intercepts `UNAVAILABLE`, returns `status=DEGRADED` with the labelled cache
`DOC:CACHED-001`, and logs `fallback-used`. The `degraded_is_cache` postcondition
makes a fabricated fresh result impossible.

**Regression:** `kres.status = DEGRADED and kres.value = "DOC:CACHED-001" and
kres.value ≠ "UNAVAILABLE" and log.contains_event("fallback-used")`. → PASS.

---

## INC-C · World — Bad input drives an object out of bounds

- **Severity:** high (state corruption that later reads trust).
- **Symptom:** an oversized delta from a buggy client pushed an object's position
  past its bound, corrupting world state.

**Reproduction**
1. Attempt to construct an already-out-of-bounds object: `world.port("E-X", -5, 10)`.
2. Observe it is **blocked** at the seam: `Precondition violation: start_in_bounds`
   — the corrupt state is unrepresentable.

**Root cause (pre-invariant):** updates wrote `x := x + delta` with no bound, so
extreme/invalid input could leave the valid range.

**Fix (at the service/port boundary):** `World_Move_Port.move` clamps to
`[0, max_x]` and the class `invariant bounded` holds for the object's whole life;
`World_Reliability_Service.safe_step` reports an out-of-range request as
`RECOVERED` rather than silently or erroneously.

**Regression:** `safe_step(1000) → RECOVERED (CLAMPED@10)`,
`safe_step(-1000) → RECOVERED (CLAMPED@0)`, and `0 ≤ x ≤ max_x` throughout. → PASS.

---

## Triage quick-reference

| You see… | Likely incident | First check |
|---|---|---|
| Spiking calls to a failing dependency | INC-A | Is the failure permanent? Was `retryable` consulted? |
| Garbled/blank results downstream | INC-B | Did a raw outage token leak past `safe_query`? |
| Impossible state values in reads | INC-C | Did an update bypass the bounded port / invariant? |
| Duplicate side effects | (idempotency) | Is the op keyed and recorded in the dispatched store? |

The `Reliability_Log` dump is the first artifact to pull for any of these — it
carries the full attempt/fallback/step sequence for the run.
