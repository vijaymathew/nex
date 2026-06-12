# Studio 6 — Evolution Notes (Levels 1–3)

**Evolution.** Studio 5 made the system reliable. Studio 6 evolves it under new
rules and new clients **without breaking anyone**: versioned behavior behind
stable contracts, compatibility mode for old clients, reversible migration with
rollback, and governance for what gets deprecated.

## Files

| File | Role |
|---|---|
| `evolution_types.nex` | `Evolution_Result` (tags the serving `mode`), `Rollout_Config`. |
| `delivery_evolution.nex` | `Delivery_Policy` contract + V1/V2 + service (strategy seam + canary). |
| `knowledge_evolution.nex` | V1/V2 engines + `Knowledge_Compatibility_Adapter` + service. |
| `world_evolution.nex` | `World_Rules` contract + V1/V2 (damping) + service. |
| `migration_runner.nex` | Reversible, canary-gated batch migration + rollback. |
| `studio_6_main.nex` | **Levels 1 & 2** driver + canary/rollback run log. |
| `level3_future_proofing.nex` | **Level 3** future-proofing drill. |
| `VERSIONING_PLAN.md` | v1/v2 behavior map, compatibility matrix, deprecation roadmap. |
| `MIGRATION_PLAYBOOK.md` | Migration + rollback playbook with gate conditions. |

Run: `nex studio_6_main.nex`, `nex level3_future_proofing.nex`.

## The seams

- **Strategy seam (delivery, world):** a stable contract base
  (`Delivery_Policy.decide(tier, risk)`, `World_Rules.apply(...)`) that **both**
  versions satisfy. The version is selected by `Rollout_Config`; the service's
  external operation is unchanged. Adding a version = a new subclass.
- **Compatibility adapter (knowledge):** V2 changed the request *shape* (added
  `intent`). `Knowledge_Compatibility_Adapter.run_v1_shape(query)` maps the old
  shape onto the new engine (`intent="fast"`), so old clients keep working during
  the migration window.
- **Mode tagging:** every `Evolution_Result` carries the `mode` that served it
  (`V1` / `V2` / `V2_COMPAT` / `V2_NATIVE`), so a rollout is observable.

> Fix vs the chapter: the chapter's `V2_NATIVE` path returned a baked constant
> `"DOC:K-DEEP"`; here it calls the real engine `v2.run(query, "deep")`, and a
> `mode_matches_config` postcondition ties the served mode to the config.

---

# Level 1 — One Evolution Path (Delivery)

`Delivery_Evolution_Service.route_mode(tier, risk)` is the **stable** client
contract. Behind it, V1 (legacy: premium→fast, risk ignored) or V2 (risky
premium→safe) is selected by config. Measured:

```
PREMIUM risk=70  V1: mode=V1 value=FAST_TRACK     <- old client behavior unchanged
PREMIUM risk=70  V2: mode=V2 value=SAFE_TRACK     <- new rule
canary PREMIUM risk=70 : DIVERGE:FAST_TRACK->SAFE_TRACK
canary PREMIUM risk=10 : AGREE:FAST_TRACK
canary STANDARD risk=10: AGREE:STANDARD_TRACK
```

Old-client behavior is **proven preserved**: under the V1 config, every result
matches the legacy mapping. `canary_compare` runs both versions and reports
exactly where V2 changes behavior (risky premiums) — the rollout signal.

---

# Level 2 — Coordinated Multi-Domain Evolution

One shared `Rollout_Config(use_v2=true, compatibility_mode=true)` →
`serving_mode = V2_COMPAT` drives all three domains:

```
delivery : mode=V2        value=SAFE_TRACK
knowledge: mode=V2_COMPAT value=DOC:K-FAST   (old shape on the new engine)
world    : mode=V2        value=X=5          (V2 damping: delta 6 -> 3, vs V1 X=8)
knowledge (compat OFF):  mode=V2_NATIVE value=DOC:K-DEEP
```

**Migration as code — canary/rollback run log:**

```
batch 1 (canary pass): MIGRATION_CONTINUE  count=100
batch 2 (canary pass): MIGRATION_CONTINUE  count=200
batch 3 (canary FAIL): ROLLBACK            count=200 (reverted to checkpoint)
batch 4 (canary pass): MIGRATION_CONTINUE  count=300
committed batches=3 final count=300
```

The migration is **reversible**: a failed canary reverts the optimistic batch to
the last committed checkpoint *exactly* (postcondition `reverts_on_rollback`).
**Reliability intact:** the world `bounded` invariant holds under both versions —
`step(2, 999, 10)` gives `X=10` (V1) and `X=5` (V2), both in range. Gate:
`EVOLUTION_CHECK:PASS`.

---

# Level 3 — Future-Proofing Drill

Two simulated future changes, evaluated against the existing seam
(`level3_future_proofing.nex`):

| Change | What | Absorbed? |
|---|---|---|
| **1 — new logic, same vocabulary** | `Delivery_Policy_V3`: tighter risk thresholds, still FAST/SAFE/STANDARD | **Yes, free.** A new subclass drops into the same `route_v1(policy, …)` with no service/contract change. |
| **2 — new outcome value** | `PRIORITY_TRACK` for a VIP tier | **No, by design.** A subclass of the old contract is rejected — Nex enforces the inherited base postcondition (LSP: a subclass may not *widen* a postcondition). The governed fix is a **new contract version** `Delivery_Policy_C2` admitting PRIORITY_TRACK; old-contract clients are untouched. |

Evidence:
```
PREMIUM risk=40  V1=FAST_TRACK V2=FAST_TRACK V3=SAFE_TRACK     (V3 absorbed)
old contract rejected new outcome -> Postcondition violation: known_result
governed contract C2, VIP -> PRIORITY_TRACK : ACCEPTED
```

**Lesson:** the strategy seam absorbs new *logic* within the existing outcome set
for free. A new *outcome vocabulary* cannot be smuggled in via subclassing — it
requires an explicit, additive **contract version**, which is a governed,
backward-compatible change. A new *input shape* (cf. knowledge's `intent`) needs
a compatibility adapter, not just a new strategy. Both are governance events, not
silent edits — see `VERSIONING_PLAN.md` for the deprecation roadmap and owners.

---

# Postmortem (with evidence)

- **Most valuable seam for safe evolution?** The **stable strategy contract**
  (`Delivery_Policy`/`World_Rules`). It let V2 ship behind an unchanged client
  operation, let V3 drop in for free (Level 3 Change 1), and — via the enforced
  base postcondition — *stopped* an out-of-vocabulary outcome from leaking
  (Change 2). One seam delivered both flexibility and a guardrail.
- **Compatibility costs worth paying?** The knowledge `Knowledge_Compatibility_Adapter`:
  one tiny class that maps the old request shape to the new engine. It costs a
  permanent indirection and a "fast" default intent old clients didn't choose, but
  it buys a **zero-break migration window** — clearly worth it until clients move
  to the native V2 shape (then deprecate it; see roadmap).
- **What to deprecate next, and when?** `V1` delivery/world rules and the
  knowledge compatibility adapter, once canary shows old-client traffic has
  drained. Concrete dates/owners: `VERSIONING_PLAN.md` deprecation roadmap.
- **Metrics that should gate future rollouts?** (1) **canary divergence rate**
  (`canary_compare` DIVERGE vs AGREE) — expected, but must match the intended
  change set; (2) **serving-mode mix** (share of V2_COMPAT vs V2_NATIVE — drives
  the deprecation clock); (3) **rollback count** per migration window; (4)
  preserved **reliability invariants** (world `bounded`, Studio 5 checks) — any
  breach blocks rollout.

---

# Tradeoffs / notes

- **Stable contract uses the superset signature** (`decide(tier, risk)`): V1
  ignores `risk`, so the *input* evolution is additive and needs no adapter. A
  genuinely new input (knowledge's `intent`) is a *shape* change and does.
- **Subclasses cannot widen postconditions** (verified): this is a feature — it
  forces new outcome vocabularies through a governed contract version rather than
  a quiet subclass. It also means the base contract's `known_result` is the real
  boundary of "what this service may ever return."
- **Migration state is in-memory** (`Migration_Runner`); a real migration needs a
  durable checkpoint/cursor, but the apply→canary→commit/rollback shape and its
  contracts are identical.
- **Reliability composition:** the evolution services keep contracted results
  (`Evolution_Result`, `ok_status`, world `bounded`); a Studio 5 reliability
  service (retry/idempotency/fallback) wraps an evolution service unchanged —
  the seams stack.
