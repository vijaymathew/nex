# Studio 2 — The Model Redesign {-}

## Subtitle

Refactoring data models so systems can evolve without hidden state corruption.

::: {.note-studio}
**Studio Focus**
This studio chapter is hands-on: implement, verify behavior, and document tradeoffs as you iterate.
:::

## The Situation

Studio 1 proved we could ship tiny vertical slices.

Now those slices are under stress.

As soon as requirements grow, model weaknesses appear:

- entity identity is unclear (`"task"` vs `"route"` vs `"request"` used interchangeably)
- relationships are implicit (IDs inside free-text fields)
- transition rules are scattered (status changes happen from many places)
- invalid states are easy to create and hard to detect

Part II gave us the tools to fix this:

- model entities explicitly
- define relationships with constraints
- shape data around real operations
- model change with legal transitions and invariants

Your job in Studio 2 is to redesign all three systems using those principles.


## Engineering Brief

Redesign the core data models for all three systems (delivery, knowledge, virtual world).

Required outcomes:

- explicit entities with stable identity
- explicit relationships with clear cardinality/constraints
- transition operations with contracts (`require`/`ensure`)
- invariants that make invalid states hard to represent
- migrated behavior examples from Studio 1

Implementation guidance:

- optimize for correctness and clarity before optimization
- keep model boundaries obvious
- make failure behavior explicit (not silent)


## Implementation In Nex

Use Nex to encode model assumptions directly in code.

Suggested files:

- `delivery_model_v2.nex`
- `knowledge_model_v2.nex`
- `world_model_v2.nex`
- `studio_2_main.nex`

### Delivery Model V2

Focus: identity + legal transitions + assignment relationship.

```nex
class Delivery_Task
feature
  task_id: String
  origin: String
  destination: String
  status: String
  assigned_robot_id: String

  assign(robot_id: String)
    require
      robot_present: robot_id /= ""
      can_assign: status = "PENDING" 
	              or status = "FAILED"
    do
      assigned_robot_id := robot_id
      status := "IN_TRANSIT"
    ensure
      assigned: assigned_robot_id = robot_id 
	            and status = "IN_TRANSIT"
    end

  complete()
    require
      in_transit: status = "IN_TRANSIT"
    do
      status := "DELIVERED"
    ensure
      delivered: status = "DELIVERED"
    end

  fail(reason: String)
    require
      reason_present: reason /= ""
      not_delivered: status /= "DELIVERED"
    do
      status := "FAILED"
    ensure
      failed: status = "FAILED"
    end
create
  pending(task_id: String, origin: String, 
          destination: String) do
    this.task_id := task_id
	this.origin := origin
	this.destination := destination
	this.status := "PENDING"
  end
invariant
  id_present: task_id /= ""
  endpoints_present: origin /= "" 
                     and destination /= ""
  valid_status:
    status = "PENDING" or status = "IN_TRANSIT" 
	         or status = "DELIVERED" 
			 or status = "FAILED"
  transit_has_assignment:
    status /= "IN_TRANSIT" 
	or assigned_robot_id /= ""
end
```

### Knowledge Model V2

Focus: relationship as first-class entity (`Doc_Link`) rather than ad hoc references.

```nex
class Document
feature
  doc_id: String
  title: String
  body: String
create
  make(doc_id: String, title: String) do
    this.doc_id := doc_id
	this.title := title
  end
invariant
  id_present: doc_id /= ""
  title_present: title /= ""
end

class Doc_Link
feature
  from_doc_id: String
  to_doc_id: String
  link_type: String

  is_valid(): Boolean do
    result :=
      from_doc_id /= "" and
      to_doc_id /= "" and
      from_doc_id /= to_doc_id and
      (link_type = "references" 
	   or link_type = "related" 
	   or link_type = "contradicts")
  end
create
  make(f: String, t: String, lt: String) do
    from_doc_id := f
	to_doc_id := t
	link_type := lt
  end
invariant
  endpoints_present: from_doc_id /= "" 
                     and to_doc_id /= ""
  no_self_link: from_doc_id /= to_doc_id
end
```

### Virtual World Model V2

Focus: deterministic update transition with explicit bounds.

```nex
class World_Object
feature
  object_id: String
  x: Integer
  vx: Integer

  step(max_x: Integer)
    require
      valid_bound: max_x >= 0
    do
      let next: Integer := x + vx
      if next < 0 then
        x := 0
      elseif next > max_x then
        x := max_x
      else
        x := next
      end
    ensure
      bounded_after_step: x >= 0 and x <= max_x
    end
create
  make(id: String, x: Integer, vx: Integer) do
    object_id := id
	this.x := x
	this.vx := vx
  end
invariant
  id_present: object_id /= ""
end
```

### Studio Driver

Use one driver to validate nominal + edge behavior.

```nex
class App
feature
  run() do
    -- Delivery
    let t: Delivery_Task := 
	  create Delivery_Task.pending("T001", "A", "C")
    t.assign("R7")
    print(t.status)              -- IN_TRANSIT
    t.complete
    print(t.status)              -- DELIVERED

    -- Knowledge
    let l: Doc_Link := 
	  create Doc_Link.make("D1", "D2", "references")
    print(l.is_valid)            -- true

    -- World
    let w: World_Object := 
	  create World_Object.make("0-1", 9, 4)
    w.step(10)
    print(w.x)                   -- 10 (clamped)
  end
end
```


## Studio Challenges

### Level 1 — Core Implementation

- Redesign one system model (delivery or knowledge or world).
- Encode at least 2 invariants and 2 transition operations.
- Run one nominal and two edge cases.

### Level 2 — Cross-System Model Consistency

- Redesign all three systems.
- Use a common modeling vocabulary in notes:
  - entity
  - relationship
  - invariant
  - transition
- Show one “before vs after” bug that the new model prevents.

### Level 3 — Exploration

Pick one experiment:

- **State-centric vs event-centric** transition handling for delivery reassignment.
- **Direct references vs link entities** in the knowledge model.
- **Single-step deterministic update vs queued events** in virtual world updates.

Evaluate:

- complexity impact
- testability impact
- failure-mode clarity


## Postmortem

Discuss:

- Which entity boundary decision removed the most confusion?
- Which relationship constraint prevented a real invalid state?
- Which transition rule was hardest to make explicit?
- Which tradeoff did you intentionally accept (and why)?

Use concrete evidence from your runs, not just opinions.


## Deliverables

- redesigned Nex models for delivery, knowledge, and world systems
- short modeling notes per system:
  - entities
  - relationships
  - invariants
  - transitions
- nominal + edge test output logs
- one before/after comparison showing reduced accidental complexity


## Exit Criteria

You are ready for Studio 3 if:

- you can explain identity vs state for each core entity
- invalid states are blocked by model rules (not just comments)
- transition legality is encoded in operations/contracts
- relationship constraints are explicit and testable
