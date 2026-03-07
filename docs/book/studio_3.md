# Studio 3 — The Scaling Crisis

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

If using the web IDE, place all classes in one file and run `App.run`.

### Reused Studio 2 Models (Unchanged)

```nex
class Delivery_Task
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
feature
  doc_id: String
  title: String
  body: String
invariant
  id_present: doc_id /= ""
end

class Doc_Link
feature
  from_doc_id: String
  to_doc_id: String
  link_type: String
invariant
  endpoints_present: from_doc_id /= "" and to_doc_id /= ""
end

class World_Object
feature
  object_id: String
  x: Integer
  vx: Integer
invariant
  id_present: object_id /= ""
end

class Measure_Result
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
      let r: Measure_Result := create Measure_Result
      r.value := "NOT_FOUND"
      r.steps := 0

      r.steps := r.steps + 1
      if t1.task_id = task_id then
        r.value := t1.status
      elseif t2.task_id = task_id then
        r.steps := r.steps + 1
        r.value := t2.status
      elseif t3.task_id = task_id then
        r.steps := r.steps + 1
        r.value := t3.status
      elseif t4.task_id = task_id then
        r.steps := r.steps + 1
        r.value := t4.status
      elseif t5.task_id = task_id then
        r.steps := r.steps + 1
        r.value := t5.status
      else
        r.steps := r.steps + 1
      end

      result := r
    ensure
      bounded_steps: result.steps >= 1 and result.steps <= 5
    end
end

class Delivery_Task_Store_V3
feature
  -- Index from task id to task object (teaching-sized map form).
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
      let r: Measure_Result := create Measure_Result
      r.steps := 1

      if task_id = k1 then
        r.value := v1.status
      elseif task_id = k2 then
        r.value := v2.status
      elseif task_id = k3 then
        r.value := v3.status
      elseif task_id = k4 then
        r.value := v4.status
      elseif task_id = k5 then
        r.value := v5.status
      else
        r.value := "NOT_FOUND"
      end

      result := r
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
feature
  d1: Document
  d2: Document
  d3: Document
  l1: Doc_Link
  l2: Doc_Link

  validate_link(link: Doc_Link): Measure_Result
    do
      let r: Measure_Result := create Measure_Result
      r.steps := 2

      if (d1.doc_id = link.from_doc_id or d2.doc_id = link.from_doc_id or d3.doc_id = link.from_doc_id) and
         (d1.doc_id = link.to_doc_id or d2.doc_id = link.to_doc_id or d3.doc_id = link.to_doc_id) then
        r.value := "VALID"
      elseif not (d1.doc_id = link.from_doc_id or d2.doc_id = link.from_doc_id or d3.doc_id = link.from_doc_id) then
        r.value := "MISSING_FROM"
      else
        r.value := "MISSING_TO"
      end

      result := r
    ensure
      status_known:
        result.value = "VALID" or
        result.value = "MISSING_FROM" or
        result.value = "MISSING_TO"
    end
end

class Knowledge_Model_V3
feature
  dk1: String
  dv1: Document
  dk2: String
  dv2: Document
  dk3: String
  dv3: Document

  has_doc(doc_id: String): Boolean
    do
      result := doc_id = dk1 or doc_id = dk2 or doc_id = dk3
    ensure
      bool_result: result = true or result = false
    end

  validate_link(link: Doc_Link): Measure_Result
    do
      let r: Measure_Result := create Measure_Result
      r.steps := 2

      if not has_doc(link.from_doc_id) then
        r.value := "MISSING_FROM"
      elseif not has_doc(link.to_doc_id) then
        r.value := "MISSING_TO"
      else
        r.value := "VALID"
      end

      result := r
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
feature
  w1: World_Object
  w2: World_Object
  w3: World_Object
  w4: World_Object

  move_by_id(object_id: String; delta: Integer): Measure_Result
    require
      id_present: object_id /= ""
    do
      let r: Measure_Result := create Measure_Result
      r.value := "NOT_FOUND"
      r.steps := 0

      r.steps := r.steps + 1
      if w1.object_id = object_id then
        w1.x := w1.x + delta
        r.value := "UPDATED"
      elseif w2.object_id = object_id then
        r.steps := r.steps + 1
        w2.x := w2.x + delta
        r.value := "UPDATED"
      elseif w3.object_id = object_id then
        r.steps := r.steps + 1
        w3.x := w3.x + delta
        r.value := "UPDATED"
      elseif w4.object_id = object_id then
        r.steps := r.steps + 1
        w4.x := w4.x + delta
        r.value := "UPDATED"
      else
        r.steps := r.steps + 1
      end

      result := r
    ensure
      bounded_steps: result.steps >= 1 and result.steps <= 4
    end
end

class World_Model_V3
feature
  wk1: String
  wv1: World_Object

  move_by_id(object_id: String; delta: Integer): Measure_Result
    require
      id_present: object_id /= ""
    do
      let r: Measure_Result := create Measure_Result
      r.steps := 1

      if object_id = wk1 then
        wv1.x := wv1.x + delta
        r.value := "UPDATED"
      else
        r.value := "NOT_FOUND"
      end

      result := r
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
    let t1: Delivery_Task := create Delivery_Task
    let t2: Delivery_Task := create Delivery_Task
    let t3: Delivery_Task := create Delivery_Task
    let t4: Delivery_Task := create Delivery_Task
    let t5: Delivery_Task := create Delivery_Task

    t1.task_id := "T-1"; t1.status := "PENDING"
    t2.task_id := "T-2"; t2.status := "IN_TRANSIT"
    t3.task_id := "T-3"; t3.status := "FAILED"
    t4.task_id := "T-4"; t4.status := "DELIVERED"
    t5.task_id := "T-5"; t5.status := "PENDING"

    let d_v2: Delivery_Task_Store_V2 := create Delivery_Task_Store_V2
    d_v2.t1 := t1; d_v2.t2 := t2; d_v2.t3 := t3; d_v2.t4 := t4; d_v2.t5 := t5

    let d_v3: Delivery_Task_Store_V3 := create Delivery_Task_Store_V3
    d_v3.k1 := t1.task_id; d_v3.v1 := t1
    d_v3.k2 := t2.task_id; d_v3.v2 := t2
    d_v3.k3 := t3.task_id; d_v3.v3 := t3
    d_v3.k4 := t4.task_id; d_v3.v4 := t4
    d_v3.k5 := t5.task_id; d_v3.v5 := t5

    let d2r: Measure_Result := d_v2.find_status("T-5")
    let d3r: Measure_Result := d_v3.find_status("T-5")
    print("Delivery V2 steps: " + d2r.steps)
    print("Delivery V3 steps: " + d3r.steps)

    -- Knowledge documents + links (Studio 2 model objects)
    let doc1: Document := create Document
    let doc2: Document := create Document
    let doc3: Document := create Document
    doc1.doc_id := "D-1"; doc1.title := "Graphs"
    doc2.doc_id := "D-2"; doc2.title := "Trees"
    doc3.doc_id := "D-3"; doc3.title := "Maps"

    let link: Doc_Link := create Doc_Link
    link.from_doc_id := "D-1"
    link.to_doc_id := "D-3"
    link.link_type := "references"

    let k_v2: Knowledge_Model_V2 := create Knowledge_Model_V2
    k_v2.d1 := doc1; k_v2.d2 := doc2; k_v2.d3 := doc3; k_v2.l1 := link

    let k_v3: Knowledge_Model_V3 := create Knowledge_Model_V3
    k_v3.dk1 := doc1.doc_id; k_v3.dv1 := doc1
    k_v3.dk2 := doc2.doc_id; k_v3.dv2 := doc2
    k_v3.dk3 := doc3.doc_id; k_v3.dv3 := doc3

    let k2r: Measure_Result := k_v2.validate_link(link)
    let k3r: Measure_Result := k_v3.validate_link(link)
    print("Knowledge V2 status/steps: " + k2r.value + "/" + k2r.steps)
    print("Knowledge V3 status/steps: " + k3r.value + "/" + k3r.steps)

    -- World objects (Studio 2 model objects)
    let o1: World_Object := create World_Object
    let o2: World_Object := create World_Object
    let o3: World_Object := create World_Object
    let o4: World_Object := create World_Object
    o1.object_id := "E-1"; o1.x := 1
    o2.object_id := "E-2"; o2.x := 2
    o3.object_id := "E-3"; o3.x := 3
    o4.object_id := "E-4"; o4.x := 4

    let w_v2: World_Model_V2 := create World_Model_V2
    w_v2.w1 := o1; w_v2.w2 := o2; w_v2.w3 := o3; w_v2.w4 := o4

    let w_v3: World_Model_V3 := create World_Model_V3
    w_v3.wk1 := o4.object_id; w_v3.wv1 := o4

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
