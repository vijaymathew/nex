# Studio 3 — The Scaling Crisis {-}

## Subtitle

Refactoring Studio 2 models for scale without losing correctness.

::: {.note-studio}
**Studio Focus**
This studio chapter is hands-on: implement, verify behavior, and document tradeoffs as you iterate.
:::

## The Situation

Studio 2 gave us explicit entities, relationships, and invariants.

Now volume has grown to thousands of tasks, documents, links, and world objects. The model is still correct, but some operations are too slow because access paths are still scan-heavy.

Symptoms observed:

- latency spikes on common lookups
- repeated linear scans in hot paths
- update loops that do unnecessary work

This studio is a direct continuation of Studio 2: same core model classes, new access structures and algorithm choices.


## Engineering Brief

Keep the Studio 2 model semantics. Improve runtime behavior.

Required outcomes:

- identify one bottleneck operation per domain
- show a before/after refactor using Studio 2 classes
- measure operation-count improvement
- preserve behavior contracts and invariants

Implementation guidance:

- optimize access paths, not domain meaning
- use explicit failure statuses (`NOT_FOUND`, `NO_MATCH`, etc.)
- keep model classes intact; add indexing/organization layers around them


## Implementation In Nex

Use operation counts to compare baseline and refactored paths.

Suggested files:

- `delivery_scale_from_studio2.nex`
- `knowledge_scale_from_studio2.nex`
- `world_scale_from_studio2.nex`
- `studio_3_main.nex`

### Reused Studio 2 Models (Unchanged)

```nex
class Delivery_Task
create
  make(
    task_id: String,
    origin: String,
    destination: String,
    status: String,
    assigned_robot_id: String
  ) do
    this.task_id := task_id
    this.origin := origin
    this.destination := destination
    this.status := status
    this.assigned_robot_id := assigned_robot_id
  end
feature
  task_id: String
  origin: String
  destination: String
  status: String
  assigned_robot_id: String
invariant
  id_present: task_id /= ""
end

class Document
create
  make(doc_id: String, title: String, body: String) do
    this.doc_id := doc_id
    this.title := title
    this.body := body
  end
feature
  doc_id: String
  title: String
  body: String
invariant
  id_present: doc_id /= ""
end

class Doc_Link
create
  make(from_doc_id: String, to_doc_id: String, link_type: String) do
    this.from_doc_id := from_doc_id
    this.to_doc_id := to_doc_id
    this.link_type := link_type
  end
feature
  from_doc_id: String
  to_doc_id: String
  link_type: String
invariant
  endpoints_present: from_doc_id /= "" 
                     and to_doc_id /= ""
end

class World_Object
create
  make(object_id: String, x: Integer, vx: Integer) do
    this.object_id := object_id
    this.x := x
    this.vx := vx
  end
feature
  object_id: String
  x: Integer
  vx: Integer

  shift(delta: Integer) do
    x := x + delta
  end
invariant
  id_present: object_id /= ""
end

class Measure_Result
create
  make(value: String, steps: Integer) do
    this.value := value
    this.steps := steps
  end
feature
  value: String
  steps: Integer
invariant
  value_present: value /= ""
  non_negative_steps: steps >= 0
end
```

### Delivery Refactor: Task Lookup

Before (Studio 2-style collection usage): linear scan over `Delivery_Task` objects.

After: index layer for direct key access while keeping `Delivery_Task` as source object.

```nex
class Delivery_Task_Store_V2
create
  make(
    t1: Delivery_Task,
    t2: Delivery_Task,
    t3: Delivery_Task,
    t4: Delivery_Task,
    t5: Delivery_Task
  ) do
    this.t1 := t1
    this.t2 := t2
    this.t3 := t3
    this.t4 := t4
    this.t5 := t5
  end
feature
  t1: Delivery_Task
  t2: Delivery_Task
  t3: Delivery_Task
  t4: Delivery_Task
  t5: Delivery_Task

  find_status(task_id: String): Measure_Result
    require
      id_present: task_id /= ""
    do
      let steps: Integer := 1
      let value: String := "NOT_FOUND"
      if t1.task_id = task_id then
        value := t1.status
      elseif t2.task_id = task_id then
        steps := steps + 1
        value := t2.status
      elseif t3.task_id = task_id then
        steps := steps + 1
        value := t3.status
      elseif t4.task_id = task_id then
        steps := steps + 1
        value := t4.status
      elseif t5.task_id = task_id then
        steps := steps + 1
        value := t5.status
      else
        steps := steps + 1
      end

      result := create Measure_Result.make(value, steps)
    ensure
      bounded_steps: result.steps >= 1 
	                 and result.steps <= 5
    end
end

class Delivery_Task_Store_V3
create
  make(
    k1: String, v1: Delivery_Task,
    k2: String, v2: Delivery_Task,
    k3: String, v3: Delivery_Task,
    k4: String, v4: Delivery_Task,
    k5: String, v5: Delivery_Task
  ) do
    this.k1 := k1
    this.v1 := v1
    this.k2 := k2
    this.v2 := v2
    this.k3 := k3
    this.v3 := v3
    this.k4 := k4
    this.v4 := v4
    this.k5 := k5
    this.v5 := v5
  end
feature
  -- Index from task id to 
  -- task object (teaching-sized map form).
  k1: String
  v1: Delivery_Task
  k2: String
  v2: Delivery_Task
  k3: String
  v3: Delivery_Task
  k4: String
  v4: Delivery_Task
  k5: String
  v5: Delivery_Task

  find_status(task_id: String): Measure_Result
    require
      id_present: task_id /= ""
    do
      let value: String := "NOT_FOUND"
      if task_id = k1 then
        value := v1.status
      elseif task_id = k2 then
        value := v2.status
      elseif task_id = k3 then
        value := v3.status
      elseif task_id = k4 then
        value := v4.status
      elseif task_id = k5 then
        value := v5.status
      end

      result := create Measure_Result.make(value, 1)
    ensure
      constant_step: result.steps = 1
    end
end
```

### Knowledge Refactor: Link Validation + Document Lookup

Before: for each `Doc_Link`, document existence check scans document list.

After: use document index by `doc_id` and validate links against indexed lookups.

```nex
class Knowledge_Model_V2
create
  make(
    d1: Document,
    d2: Document,
    d3: Document,
    l1: Doc_Link,
    l2: Doc_Link
  ) do
    this.d1 := d1
    this.d2 := d2
    this.d3 := d3
    this.l1 := l1
    this.l2 := l2
  end
feature
  d1: Document
  d2: Document
  d3: Document
  l1: Doc_Link
  l2: Doc_Link

  validate_link(link: Doc_Link): Measure_Result
    do
      let value: String := "VALID"
      if (d1.doc_id = link.from_doc_id 
	      or d2.doc_id = link.from_doc_id 
		  or d3.doc_id = link.from_doc_id) and
         (d1.doc_id = link.to_doc_id 
		  or d2.doc_id = link.to_doc_id 
		  or d3.doc_id = link.to_doc_id) then
        value := "VALID"
      elseif not (d1.doc_id = link.from_doc_id 
	              or d2.doc_id = link.from_doc_id 
				  or d3.doc_id = link.from_doc_id) then
        value := "MISSING_FROM"
      else
        value := "MISSING_TO"
      end

      result := create Measure_Result.make(value, 2)
    ensure
      status_known:
        result.value = "VALID" or
        result.value = "MISSING_FROM" or
        result.value = "MISSING_TO"
    end
end

class Knowledge_Model_V3
create
  make(
    dk1: String, dv1: Document,
    dk2: String, dv2: Document,
    dk3: String, dv3: Document
  ) do
    this.dk1 := dk1
    this.dv1 := dv1
    this.dk2 := dk2
    this.dv2 := dv2
    this.dk3 := dk3
    this.dv3 := dv3
  end
feature
  dk1: String
  dv1: Document
  dk2: String
  dv2: Document
  dk3: String
  dv3: Document

  has_doc(doc_id: String): Boolean
    do
      result := doc_id = dk1 
	            or doc_id = dk2 
				or doc_id = dk3
    ensure
      bool_result: result = true 
	               or result = false
    end

  validate_link(link: Doc_Link): Measure_Result
    do
      let value: String := "VALID"
      if not has_doc(link.from_doc_id) then
        value := "MISSING_FROM"
      elseif not has_doc(link.to_doc_id) then
        value := "MISSING_TO"
      end

      result := create Measure_Result.make(value, 2)
    ensure
      status_known:
        result.value = "VALID" or
        result.value = "MISSING_FROM" or
        result.value = "MISSING_TO"
    end
end
```

### Virtual World Refactor: Targeted Update

Before: scan all `World_Object` entries to find target.

After: id index for direct target selection, then apply same position update semantics.

```nex
class World_Model_V2
create
  make(
    w1: World_Object,
    w2: World_Object,
    w3: World_Object,
    w4: World_Object
  ) do
    this.w1 := w1
    this.w2 := w2
    this.w3 := w3
    this.w4 := w4
  end
feature
  w1: World_Object
  w2: World_Object
  w3: World_Object
  w4: World_Object

  move_by_id(object_id: String,
             delta: Integer): Measure_Result
    require
      id_present: object_id /= ""
    do
      let value: String := "NOT_FOUND"
      let steps: Integer := 1
      if w1.object_id = object_id then
        w1.shift(delta)
        value := "UPDATED"
      elseif w2.object_id = object_id then
        steps := steps + 1
        w2.shift(delta)
        value := "UPDATED"
      elseif w3.object_id = object_id then
        steps := steps + 1
        w3.shift(delta)
        value := "UPDATED"
      elseif w4.object_id = object_id then
        steps := steps + 1
        w4.shift(delta)
        value := "UPDATED"
      else
        steps := steps + 1
      end

      result := create Measure_Result.make(value, steps)
    ensure
      bounded_steps: result.steps >= 1 
	                 and result.steps <= 4
    end
end

class World_Model_V3
create
  make(wk1: String, wv1: World_Object) do
    this.wk1 := wk1
    this.wv1 := wv1
  end
feature
  wk1: String
  wv1: World_Object

  move_by_id(object_id: String,
             delta: Integer): Measure_Result
    require
      id_present: object_id /= ""
    do
      let value: String := "NOT_FOUND"
      if object_id = wk1 then
        wv1.shift(delta)
        value := "UPDATED"
      end

      result := create Measure_Result.make(value, 1)
    ensure
      constant_step: result.steps = 1
    end
end
```

### Studio Driver (Before/After on Studio 2 Models)

```nex
class App
feature
  run() do
    -- Delivery tasks (Studio 2 model objects)
    let t1: Delivery_Task
      := create Delivery_Task.make(
           "T-1", "Hub-A", "Zone-1", "PENDING", "R-1"
         )
    let t2: Delivery_Task
      := create Delivery_Task.make(
           "T-2", "Hub-A", "Zone-2", "IN_TRANSIT", "R-2"
         )
    let t3: Delivery_Task
      := create Delivery_Task.make(
           "T-3", "Hub-B", "Zone-3", "FAILED", "R-3"
         )
    let t4: Delivery_Task
      := create Delivery_Task.make(
           "T-4", "Hub-B", "Zone-4", "DELIVERED", "R-4"
         )
    let t5: Delivery_Task
      := create Delivery_Task.make(
           "T-5", "Hub-C", "Zone-5", "PENDING", "R-5"
         )

    let d_v2: Delivery_Task_Store_V2 
	 := create Delivery_Task_Store_V2.make(t1, t2, t3, t4, t5)

    let d_v3: Delivery_Task_Store_V3 
	 := create Delivery_Task_Store_V3.make(
         t1.task_id, t1,
         t2.task_id, t2,
         t3.task_id, t3,
         t4.task_id, t4,
         t5.task_id, t5
       )

    let d2r: Measure_Result 
	 := d_v2.find_status("T-5")
    let d3r: Measure_Result 
	 := d_v3.find_status("T-5")
    print("Delivery V2 steps: " + d2r.steps)
    print("Delivery V3 steps: " + d3r.steps)

    -- Knowledge documents + links 
	-- (Studio 2 model objects)
    let doc1: Document
      := create Document.make("D-1", "Graphs", "Graph notes")
    let doc2: Document
      := create Document.make("D-2", "Trees", "Tree notes")
    let doc3: Document
      := create Document.make("D-3", "Maps", "Map notes")

    let link: Doc_Link
      := create Doc_Link.make("D-1", "D-3", "references")

    let k_v2: Knowledge_Model_V2 
	 := create Knowledge_Model_V2.make(doc1, doc2, doc3, link, link)

    let k_v3: Knowledge_Model_V3 
	 := create Knowledge_Model_V3.make(
         doc1.doc_id, doc1,
         doc2.doc_id, doc2,
         doc3.doc_id, doc3
       )

    let k2r: Measure_Result := k_v2.validate_link(link)
    let k3r: Measure_Result := k_v3.validate_link(link)
    print("Knowledge V2 status/steps: " + k2r.value 
	      + "/" + k2r.steps)
    print("Knowledge V3 status/steps: " + k3r.value 
	      + "/" + k3r.steps)

    -- World objects (Studio 2 model objects)
    let o1: World_Object := create World_Object.make("E-1", 1, 0)
    let o2: World_Object := create World_Object.make("E-2", 2, 0)
    let o3: World_Object := create World_Object.make("E-3", 3, 0)
    let o4: World_Object := create World_Object.make("E-4", 4, 0)

    let w_v2: World_Model_V2
      := create World_Model_V2.make(o1, o2, o3, o4)

    let w_v3: World_Model_V3
      := create World_Model_V3.make(o4.object_id, o4)

    let w2r: Measure_Result := w_v2.move_by_id("E-4", 3)
    let w3r: Measure_Result := w_v3.move_by_id("E-4", 3)
    print("World V2 steps: " + w2r.steps)
    print("World V3 steps: " + w3r.steps)
  end
end
```

Expected pattern:

- V2 keeps Studio 2 model semantics but uses scan-heavy access
- V3 keeps the same model objects and adds index layers for critical operations
- behavior stays consistent; operation counts improve


## Studio Challenges

### Level 1 — One Refactor

- choose one Studio 2 operation with repeated scans
- implement V2 baseline and V3 indexed refactor
- compare step counts on hit and miss cases

### Level 2 — Three-Domain Refactor

- implement before/after for delivery, knowledge, and world
- keep model classes unchanged
- document where indexes are derived from model identity

### Level 3 — Competing Strategies

- for one domain, compare two refactors:
  - index-heavy approach
  - batch/partition approach
- state when each approach wins


## Postmortem

Discuss with evidence:

- Which Studio 2 operation became the worst bottleneck and why?
- Which refactor changed asymptotic behavior versus constants only?
- Which invariant or contract protected correctness during optimization?
- Which optimization should be postponed until higher load?


## Deliverables

- Nex code showing V2 (baseline) and V3 (refactor) on Studio 2 model classes
- output logs with step-count comparisons
- one-page design note:
  - bottleneck operation
  - chosen refactor
  - correctness safeguards
  - limitations and next trigger point


## Exit Criteria

You are ready for Part V if:

- you can optimize access paths without changing domain meaning
- you can preserve model contracts while changing data organization
- you can justify changes with measured evidence, not intuition
- you can explain when the next redesign will be necessary
