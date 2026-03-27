# Studio 5 — Reliability {-}

## Subtitle

Hardening the Studio 4 architecture for failure, recovery, and operational trust.

::: {.note-studio}
**Studio Focus**
This studio chapter is hands-on: implement, verify behavior, and document tradeoffs as you iterate.
:::

## The Situation

Studio 4 gave us clear architectural seams: ports, adapters, and services.

Now reliability work becomes practical because failure handling can be localized at those seams.

Current symptoms:

- intermittent failures at integration boundaries
- retries that duplicate side effects
- hard-to-reproduce incidents
- regressions after urgent patches

Studio 5 is the logical continuation: keep Studio 4 architecture, add reliability guarantees around it.


## Engineering Brief

Build reliability mechanisms on top of Studio 4 components without breaking behavior contracts.

Required outcomes:

- explicit failure semantics for key operations
- retry policy with bounded attempts
- idempotency for repeatable operations
- reproducible failure tests and regression checks
- minimal observability hooks for diagnosis

Implementation guidance:

- enforce reliability at service/port boundaries
- keep domain invariants intact under failure paths
- separate transient failures from permanent failures
- make each retry policy explicit and testable


## Implementation In Nex

Suggested files:

- `reliability_types.nex`
- `delivery_reliability.nex`
- `knowledge_reliability.nex`
- `world_reliability.nex`
- `studio_5_main.nex`

### Shared Reliability Types

```nex
class Op_Result
create
  make(status: String, value: String, attempts: Integer) do
    this.status := status
    this.value := value
    this.attempts := attempts
  end
feature
  status: String
  value: String
  attempts: Integer
invariant
  status_present: status /= ""
  value_present: value /= ""
  attempts_non_negative: attempts >= 0
end

class Reliability_Log
create
  make(initial_event: String) do
    this.last_event := initial_event
  end
feature
  last_event: String

  record(event: String)
    require
      event_present: event /= ""
    do
      last_event := event
    ensure
      persisted: last_event = event
    end
end
```

### Delivery Reliability: Retry + Idempotent Dispatch

Continuation from Studio 4 `Delivery_Workflow` idea.

```nex
class Delivery_Port
create
  make(fail_until_attempt: Integer) do
    this.fail_until_attempt := fail_until_attempt
  end
feature
  -- Simulate transient failures for first N attempts.
  fail_until_attempt: Integer

  send_dispatch(task_id: String, attempt: Integer): String
    require
      id_present: task_id /= ""
      attempt_valid: attempt >= 1
    do
      if attempt <= fail_until_attempt then
        result := "TRANSIENT_FAILURE"
      else
        result := "SENT"
      end
    ensure
      known_result:
        result = "SENT" or
        result = "TRANSIENT_FAILURE" or
        result = "PERMANENT_FAILURE"
    end
end

class Delivery_Reliability_Service
create
  make(
    port: Delivery_Port,
    log: Reliability_Log,
    last_dispatched_task: String
  ) do
    this.port := port
    this.log := log
    this.last_dispatched_task := last_dispatched_task
  end
feature
  port: Delivery_Port
  log: Reliability_Log
  last_dispatched_task: String

  dispatch_with_retry(task_id: String, 
                      max_attempts: Integer): Op_Result
    require
      id_present: task_id /= ""
      max_attempts_valid: max_attempts >= 1
    do
      -- Idempotency guard: repeated same task 
	  -- dispatch request.
      if last_dispatched_task = task_id then
        result := create Op_Result.make(
          "IDEMPOTENT_OK",
          "ALREADY_SENT",
          0
        )
      else
        let a: Integer := 1
        let final_status: String := "TRANSIENT_FAILURE"

        from
        until a > max_attempts or final_status = "SENT" do
          final_status := port.send_dispatch(task_id, a)
          log.record("delivery_attempt=" + a)
          a := a + 1
        end

        if final_status = "SENT" then
          last_dispatched_task := task_id
          result := create Op_Result.make("OK", "SENT", a - 1)
        else
          result := 
		   create Op_Result.make("FAILED", final_status, a - 1)
        end
      end
    ensure
      known_status:
        result.status = "OK" or
        result.status = "FAILED" or
        result.status = "IDEMPOTENT_OK"
    end
end
```

### Knowledge Reliability: Safe Query Fallback

Continuation from Studio 4 query/validation services.

```nex
class Knowledge_Port
create
  make(query_available: Boolean) do
    this.query_available := query_available
  end
feature
  query_available: Boolean

  fetch_top(query: String): String
    require
      query_present: query /= ""
    do
      if query_available then
        result := "DOC:K-101"
      else
        result := "UNAVAILABLE"
      end
    ensure
      non_empty: result /= ""
    end
end

class Knowledge_Reliability_Service
create
  make(port: Knowledge_Port, log: Reliability_Log) do
    this.port := port
    this.log := log
  end
feature
  port: Knowledge_Port
  log: Reliability_Log

  safe_query(query: String): Op_Result
    require
      query_present: query /= ""
    do
      let raw: String := port.fetch_top(query)

      if raw = "UNAVAILABLE" then
        log.record("knowledge_fallback_used")
        result := 
		 create Op_Result.make("DEGRADED", "DOC:CACHED-001", 1)
      else
        result := create Op_Result.make("OK", raw, 1)
      end
    ensure
      known_status: result.status = "OK" 
	                or result.status = "DEGRADED"
    end
end
```

### World Reliability: Guarded Update + Recovery Status

Continuation from Studio 4 world update service.

```nex
class World_Port
create
  make(object_id: String, x: Integer, max_x: Integer) do
    this.object_id := object_id
    this.x := x
    this.max_x := max_x
  end
feature
  object_id: String
  x: Integer
  max_x: Integer

  move(delta: Integer): String
    do
      let next: Integer := x + delta
      if next < 0 then
        x := 0
      elseif next > max_x then
        x := max_x
      else
        x := next
      end
      result := "UPDATED"
    ensure
      bounded: x >= 0 and x <= max_x
    end
end

class World_Reliability_Service
create
  make(port: World_Port, log: Reliability_Log) do
    this.port := port
    this.log := log
  end
feature
  port: World_Port
  log: Reliability_Log

  safe_step(delta: Integer): Op_Result
    do
      let status: String := port.move(delta)
      log.record("world_step_delta=" + delta)

      result := create Op_Result.make("OK", status, 1)
    ensure
      known_status: result.status = "OK"
    end
end
```

### Reliability Driver + Regression Checks

```nex
class App
feature
  run() do
    let l: Reliability_Log := 
	 create Reliability_Log.make("init")

    -- Delivery reliability run
    let dp: Delivery_Port := create Delivery_Port.make(2)
    let dr: Delivery_Reliability_Service
      := create Delivery_Reliability_Service.make(dp, l, "NONE")

    let d1: Op_Result := dr.dispatch_with_retry("T-7", 3)
    print("Delivery result: " + d1.status + ", attempts=" + d1.attempts)

    let d2: Op_Result := dr.dispatch_with_retry("T-7", 3)
    print(
      "Delivery idempotent result: " +
      d2.status +
      ", value=" + d2.value
    )

    -- Knowledge reliability run
    let kp: Knowledge_Port := create Knowledge_Port.make(false)
    let kr: Knowledge_Reliability_Service
      := create Knowledge_Reliability_Service.make(kp, l)

    let k1: Op_Result := kr.safe_query("contracts")
    print("Knowledge result: " + k1.status + ", value=" + k1.value)

    -- World reliability run
    let wp: World_Port := create World_Port.make("E-1", 9, 10)
    let wr: World_Reliability_Service
      := create World_Reliability_Service.make(wp, l)

    let w1: Op_Result := wr.safe_step(5)
    print(
      "World result: " + w1.status +
      ", value=" + w1.value +
      ", x=" + wp.x
    )

    -- Simple regression assertions
    if d1.status = "OK" and
       d1.attempts = 3 and
       d2.status = "IDEMPOTENT_OK" and
       k1.status = "DEGRADED" and
       wp.x = 10 then
      print("RELIABILITY_CHECK:PASS")
    else
      print("RELIABILITY_CHECK:FAIL")
    end
  end
end
```

Expected outcomes:

- delivery handles transient failures with bounded retry
- repeated dispatch request is idempotent
- knowledge service degrades gracefully with fallback
- world updates preserve invariants under extreme deltas


## Studio Challenges

### Level 1 — Single Reliability Mechanism

- choose one Studio 4 service
- add either retry, idempotency, or fallback logic
- add one regression check proving behavior under failure

### Level 2 — Three-Domain Reliability Pass

- implement one reliability mechanism per domain:
  - delivery: retry/idempotency
  - knowledge: degraded fallback
  - world: invariant-preserving recovery
- show failure-path run logs

### Level 3 — Incident Drill

- define one simulated production incident per domain
- capture reproduction steps
- patch through service boundary
- add regression checks to prevent recurrence


## Postmortem

Discuss with evidence:

- Which reliability mechanism produced the largest stability gain?
- Which failures should not be retried?
- Which fallback behavior is acceptable vs dangerous?
- Which diagnostics made root-cause fastest?


## Deliverables

- reliability policy sheet (retry, idempotency, fallback, non-retryable failures)
- refactored Nex reliability services and run outputs
- incident playbook with reproduction + fix + regression tests
- release gate checklist for reliability sign-off


## Exit Criteria

You are ready for Part VIII if:

- key operations have explicit failure semantics
- retry logic is bounded and test-backed
- idempotent behaviors are enforced where side effects exist
- degraded-mode behavior is intentional and contract-defined
- regression checks cover critical incident classes
