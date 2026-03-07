# Studio 6 — Evolution

## Subtitle

Evolving the reliable Studio 5 system into a long-lived platform.

::: {.note-studio}
**Studio Focus**
This studio chapter is hands-on: implement, verify behavior, and document tradeoffs as you iterate.
:::

## The Situation

Studio 5 made the architecture reliable: retries, idempotency, fallback behavior, and reproducible regression checks.

Now the challenge is not reliability alone. It is sustained evolution under new requirements, new clients, and changing policies.

Typical pressure points:

- new business rules that must coexist with legacy behavior
- API evolution without breaking existing consumers
- data migrations that must be reversible
- feature rollout risk in production environments

Studio 6 is the logical next step: evolve safely without losing reliability or architectural clarity.


## Engineering Brief

Deliver new capabilities while preserving Studio 5 trust guarantees.

Required outcomes:

- introduce versioned behavior behind stable contracts
- support compatibility mode for old clients
- add explicit migration path and rollback trigger
- preserve reliability checks from Studio 5
- define evolution governance (deprecation, sunset, ownership)

Implementation guidance:

- use additive changes before breaking replacements
- isolate v1/v2 differences behind strategy or adapter seam
- treat migration as code, not informal operations notes
- gate rollout with measurable signals and exit criteria


## Implementation In Nex

Suggested files:

- `evolution_types.nex`
- `delivery_evolution.nex`
- `knowledge_evolution.nex`
- `world_evolution.nex`
- `migration_runner.nex`
- `studio_6_main.nex`

If using the web IDE, place all classes in one file and run `App.run`.

### Shared Evolution Types

```nex
class Evolution_Result
feature
  status: String
  value: String
  mode: String
invariant
  status_present: status /= ""
  value_present: value /= ""
  mode_present: mode /= ""
end

class Rollout_Config
feature
  use_v2: Boolean
  compatibility_mode: Boolean
end
```

### Delivery Evolution (Policy V1 -> V2)

Continuation from Studio 5 delivery reliability flow.

```nex
class Delivery_Policy_V1
feature
  decide_priority(tier: String): String
    require
      tier_present: tier /= ""
    do
      if tier = "PREMIUM" then
        result := "FAST_TRACK"
      else
        result := "STANDARD_TRACK"
      end
    ensure
      known_result: result = "FAST_TRACK" or result = "STANDARD_TRACK"
    end
end

class Delivery_Policy_V2
feature
  decide_priority(tier: String; risk_score: Integer): String
    require
      tier_present: tier /= ""
      risk_non_negative: risk_score >= 0
    do
      if tier = "PREMIUM" and risk_score < 50 then
        result := "FAST_TRACK"
      elseif tier = "PREMIUM" then
        result := "SAFE_TRACK"
      else
        result := "STANDARD_TRACK"
      end
    ensure
      known_result:
        result = "FAST_TRACK" or
        result = "SAFE_TRACK" or
        result = "STANDARD_TRACK"
    end
end

class Delivery_Evolution_Service
feature
  v1: Delivery_Policy_V1
  v2: Delivery_Policy_V2
  config: Rollout_Config

  route_mode(tier: String; risk_score: Integer): Evolution_Result
    require
      tier_present: tier /= ""
      risk_non_negative: risk_score >= 0
    do
      let r: Evolution_Result := create Evolution_Result

      if config.use_v2 then
        r.status := "OK"
        r.value := v2.decide_priority(tier, risk_score)
        r.mode := "V2"
      else
        r.status := "OK"
        r.value := v1.decide_priority(tier)
        r.mode := "V1"
      end

      result := r
    ensure
      ok_status: result.status = "OK"
    end
end
```

### Knowledge Evolution (Compatibility Adapter)

Continuation from Studio 5 safe query + fallback logic.

```nex
class Knowledge_Query_V1
feature
  run(query: String): String
    require
      query_present: query /= ""
    do
      result := "DOC:K-LEGACY"
    ensure
      non_empty: result /= ""
    end
end

class Knowledge_Query_V2
feature
  run(query: String; intent: String): String
    require
      query_present: query /= ""
      intent_present: intent /= ""
    do
      if intent = "deep" then
        result := "DOC:K-DEEP"
      else
        result := "DOC:K-FAST"
      end
    ensure
      non_empty: result /= ""
    end
end

class Knowledge_Compatibility_Adapter
feature
  v2: Knowledge_Query_V2

  run_v1_shape(query: String): String
    require
      query_present: query /= ""
    do
      result := v2.run(query, "fast")
    ensure
      non_empty: result /= ""
    end
end

class Knowledge_Evolution_Service
feature
  v1: Knowledge_Query_V1
  v1_on_v2: Knowledge_Compatibility_Adapter
  config: Rollout_Config

  query(query: String): Evolution_Result
    require
      query_present: query /= ""
    do
      let r: Evolution_Result := create Evolution_Result

      if config.use_v2 and not config.compatibility_mode then
        r.status := "OK"
        r.value := "DOC:K-DEEP"
        r.mode := "V2_NATIVE"
      elseif config.use_v2 then
        r.status := "OK"
        r.value := v1_on_v2.run_v1_shape(query)
        r.mode := "V2_COMPAT"
      else
        r.status := "OK"
        r.value := v1.run(query)
        r.mode := "V1"
      end

      result := r
    ensure
      ok_status: result.status = "OK"
    end
end
```

### World Evolution (Feature Flagged Rule Extension)

Continuation from Studio 5 safe world-step behavior.

```nex
class World_Rules_V1
feature
  apply(x: Integer; delta: Integer; max_x: Integer): Integer
    require
      max_valid: max_x >= 0
    do
      let next: Integer := x + delta
      if next < 0 then
        result := 0
      elseif next > max_x then
        result := max_x
      else
        result := next
      end
    ensure
      bounded: result >= 0 and result <= max_x
    end
end

class World_Rules_V2
feature
  apply(x: Integer; delta: Integer; max_x: Integer): Integer
    require
      max_valid: max_x >= 0
    do
      -- New damping rule for safer high-velocity transitions.
      let effective_delta: Integer := delta
      if delta > 3 then
        effective_delta := 3
      elseif delta < -3 then
        effective_delta := -3
      end

      let next: Integer := x + effective_delta
      if next < 0 then
        result := 0
      elseif next > max_x then
        result := max_x
      else
        result := next
      end
    ensure
      bounded: result >= 0 and result <= max_x
    end
end

class World_Evolution_Service
feature
  v1: World_Rules_V1
  v2: World_Rules_V2
  config: Rollout_Config

  step(x: Integer; delta: Integer; max_x: Integer): Evolution_Result
    require
      max_valid: max_x >= 0
    do
      let r: Evolution_Result := create Evolution_Result
      let out_x: Integer := 0

      if config.use_v2 then
        out_x := v2.apply(x, delta, max_x)
        r.mode := "V2"
      else
        out_x := v1.apply(x, delta, max_x)
        r.mode := "V1"
      end

      r.status := "OK"
      r.value := "X=" + out_x
      result := r
    ensure
      ok_status: result.status = "OK"
    end
end
```

### Migration Runner + Rollback Trigger

```nex
class Migration_Runner
feature
  migrated_count: Integer

  run(canary_passed: Boolean): String
    do
      if canary_passed then
        migrated_count := migrated_count + 100
        result := "MIGRATION_CONTINUE"
      else
        result := "ROLLBACK"
      end
    ensure
      known_result: result = "MIGRATION_CONTINUE" or result = "ROLLBACK"
    end
end

class App
feature
  run() do
    let cfg: Rollout_Config := create Rollout_Config
    cfg.use_v2 := true
    cfg.compatibility_mode := true

    -- Delivery evolution
    let d1: Delivery_Policy_V1 := create Delivery_Policy_V1
    let d2: Delivery_Policy_V2 := create Delivery_Policy_V2
    let de: Delivery_Evolution_Service := create Delivery_Evolution_Service
    de.v1 := d1
    de.v2 := d2
    de.config := cfg

    let dr: Evolution_Result := de.route_mode("PREMIUM", 70)
    print("Delivery: mode=" + dr.mode + " value=" + dr.value)

    -- Knowledge evolution
    let k1: Knowledge_Query_V1 := create Knowledge_Query_V1
    let kv2: Knowledge_Query_V2 := create Knowledge_Query_V2
    let ka: Knowledge_Compatibility_Adapter := create Knowledge_Compatibility_Adapter
    ka.v2 := kv2

    let ke: Knowledge_Evolution_Service := create Knowledge_Evolution_Service
    ke.v1 := k1
    ke.v1_on_v2 := ka
    ke.config := cfg

    let kr: Evolution_Result := ke.query("contracts")
    print("Knowledge: mode=" + kr.mode + " value=" + kr.value)

    -- World evolution
    let w1: World_Rules_V1 := create World_Rules_V1
    let w2: World_Rules_V2 := create World_Rules_V2
    let we: World_Evolution_Service := create World_Evolution_Service
    we.v1 := w1
    we.v2 := w2
    we.config := cfg

    let wr: Evolution_Result := we.step(8, 6, 10)
    print("World: mode=" + wr.mode + " value=" + wr.value)

    -- Migration + rollback gate
    let m: Migration_Runner := create Migration_Runner
    m.migrated_count := 0
    print("Migration gate: " + m.run(true))
    print("Migration count: " + m.migrated_count)
    print("Rollback gate: " + m.run(false))
  end
end
```

Expected outcomes:

- v2 behavior can be rolled out without breaking v1 clients
- compatibility mode supports gradual client migration
- migration continues only when canary checks pass
- rollback condition is explicit and executable


## Studio Challenges

### Level 1 — One Evolution Path

- evolve one Studio 5 service from v1 to v2 behind a stable contract
- add compatibility mode
- prove old client behavior still works

### Level 2 — Coordinated Multi-Domain Evolution

- add v2 behavior for delivery, knowledge, and world
- define one shared rollout policy
- show canary/rollback behavior with run logs

### Level 3 — Future-Proofing Drill

- simulate two additional future changes
- evaluate if current seams absorb them cleanly
- define deprecation timeline and ownership


## Postmortem

Discuss with evidence:

- Which seam was most valuable for safe evolution?
- Which compatibility costs were worth paying?
- What should be deprecated next, and when?
- Which metrics should gate future rollouts?


## Deliverables

- versioning plan (v1/v2 behavior map)
- compatibility matrix (client version vs service mode)
- migration + rollback playbook with gate conditions
- updated Nex code and evolution run logs
- deprecation roadmap with dates and owners


## Exit Criteria

You are ready for Part IX if:

- critical services evolve through versioned seams, not rewrites
- compatibility behavior is explicit and tested
- migration and rollback are operationally defined
- reliability guarantees from Studio 5 remain intact during evolution
