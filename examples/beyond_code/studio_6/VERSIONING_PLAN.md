# Versioning Plan — Studio 6

## v1 / v2 behavior map

| Domain | Operation (stable contract) | V1 behavior | V2 behavior | Kind of change |
|---|---|---|---|---|
| Delivery | `route_mode(tier, risk)` | premium → `FAST_TRACK`; else `STANDARD_TRACK` (risk ignored) | premium & risk<50 → `FAST_TRACK`; premium → `SAFE_TRACK`; else `STANDARD_TRACK` | additive input use (new rule, same vocabulary) |
| Knowledge | `query(q)` | `run(q)` → `DOC:K-LEGACY` | `run(q, intent)`: deep→`DOC:K-DEEP`, fast→`DOC:K-FAST` | **request-shape** change (new `intent` input) |
| World | `step(x, delta, max_x)` | clamp position to `[0, max_x]` | damp `delta` to `[-3,3]`, then clamp | additive safety rule (same invariant) |

**Invariants preserved across versions:** world `bounded: 0 ≤ result ≤ max_x`
holds for V1 *and* V2 (Studio 5 reliability guarantee is not weakened by
evolution). Every versioned op returns a contracted `Evolution_Result`.

**Outcome vocabularies (the real contract boundary):**
- Delivery contract v1 (`Delivery_Policy`): `{FAST_TRACK, SAFE_TRACK, STANDARD_TRACK}`.
- Delivery contract v2 (`Delivery_Policy_C2`): the above **+ `PRIORITY_TRACK`**
  (additive; introduced only via a new contract version — a subclass cannot add it).

## Compatibility matrix (client version × service mode)

`serving_mode` is derived from `Rollout_Config(use_v2, compatibility_mode)`:

| Client expects ↓ / Service mode → | `V1` (use_v2=false) | `V2_COMPAT` (use_v2 + compat) | `V2_NATIVE` (use_v2, no compat) |
|---|---|---|---|
| **V1-shape delivery** (`tier`) | ✅ legacy result | ✅ V2 rule, same outcome set | ✅ V2 rule, same outcome set |
| **V2-shape delivery** (`tier`,`risk`) | ✅ risk ignored (legacy) | ✅ risk honored | ✅ risk honored |
| **V1-shape knowledge** (`query`) | ✅ `DOC:K-LEGACY` | ✅ via adapter → `DOC:K-FAST` | ⚠️ served native `DOC:K-DEEP` (shape upgraded) |
| **V2-shape knowledge** (`query`,`intent`) | ❌ engine has no intent | ✅ adapter ignores extra intent (fast) | ✅ native deep |
| **World** (`x`,`delta`,`max_x`) | ✅ clamp | ✅ damp+clamp | ✅ damp+clamp |

Legend: ✅ works · ⚠️ works but behavior upgraded (verify client tolerates) ·
❌ unsupported (needs adapter / contract version).

**Rule:** never ship a breaking replacement before an additive path exists.
Old shapes get a compatibility adapter; new outcomes get a new contract version.

## Deprecation roadmap (dates + owners)

Today = 2026-06-12. Dates are illustrative gate *targets*, each gated on a metric.

| Item | Status | Deprecate (warn) | Sunset (remove) | Gate to advance | Owner |
|---|---|---|---|---|---|
| Delivery `V1` policy | ACTIVE | 2026-09-01 | 2026-12-01 | V1 traffic < 1% (canary serving-mode mix) | Delivery team (@delivery-lead) |
| World `V1` rules | ACTIVE | 2026-09-01 | 2026-12-01 | V1 traffic < 1%; no `bounded` regressions | World team (@world-lead) |
| Knowledge compatibility adapter (`V2_COMPAT`) | ACTIVE | 2026-10-01 | 2027-01-01 | V2_NATIVE share > 99%; all clients send `intent` | Knowledge team (@knowledge-lead) |
| Knowledge `V1` engine | ACTIVE | 2026-10-01 | 2027-01-01 | adapter sunset complete | Knowledge team (@knowledge-lead) |
| Delivery contract v1 (`Delivery_Policy`) | ACTIVE | when C2 adopted | TBD | all clients accept the wider vocabulary | Platform (@platform-arch) |

**Governance rules**
1. **Deprecate before sunset.** A deprecated path keeps working but emits a
   deprecation signal in the run log; sunset only after the gate metric is met.
2. **Additive before breaking.** New input shape → compatibility adapter; new
   outcome → new contract version. Never remove an old path before its
   replacement has drained traffic.
3. **Owner sign-off** (table above) is required to advance any item a stage.
4. **Metrics gate every advance:** canary divergence matches intent, serving-mode
   mix, rollback count, and preserved reliability invariants.
