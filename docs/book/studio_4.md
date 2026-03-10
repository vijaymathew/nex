# Studio 4 — The Architecture Refactor {-}

## Subtitle

Evolving Studio 3 optimizations into stable component architecture.

::: {.note-studio}
**Studio Focus**
This studio chapter is hands-on: implement, verify behavior, and document tradeoffs as you iterate.
:::

## The Situation

Studio 3 improved performance by adding indexes and targeted access paths over the Studio 2 model classes.

Now a new problem appears: architecture fragility.

Typical symptoms:

- orchestration logic mixed with storage details
- duplicate workflow rules across modules
- direct dependency on concrete classes everywhere
- difficult integration testing due to tight coupling

Studio 4 is the natural next step: keep Studio 3 behavior gains, but move to explicit architectural boundaries.


## Engineering Brief

Refactor around components, ports, and contracts without changing externally visible behavior.

Required outcomes:

- preserve Studio 3 semantics and performance intent
- define explicit component boundaries
- introduce interface-style ports for collaboration
- isolate domain logic from infrastructure details
- keep dependencies pointing toward domain intent

Implementation guidance:

- start from existing V3 code paths
- create seams first, then move logic behind seams
- prove behavior parity with before/after run checks


## Implementation In Nex

Suggested files:

- `architecture_ports.nex`
- `delivery_arch_refactor.nex`
- `knowledge_arch_refactor.nex`
- `world_arch_refactor.nex`
- `studio_4_main.nex`

If using the web IDE, place all classes in one file and run `App.run`.

### Shared Ports (Interfaces By Contract)

These ports abstract over concrete V3 storage/index implementations from Studio 3.

```nex
class Task_Query_Port
feature
  status_of(task_id: String): String
    require
      id_present: task_id /= ""
    do
      result := "NOT_IMPLEMENTED"
    ensure
      non_empty: result /= ""
    end
end

class Doc_Query_Port
feature
  has_doc(doc_id: String): Boolean
    require
      id_present: doc_id /= ""
    do
      result := false
    ensure
      bool_result: result = true or result = false
    end
end

class World_Update_Port
feature
  move_by_id(object_id: String, delta: Integer): String
    require
      id_present: object_id /= ""
    do
      result := "NOT_IMPLEMENTED"
    ensure
      non_empty: result /= ""
    end
end
```

### Delivery Refactor (Using Studio 3 V3 Store)

Before (Studio 3): callers directly used `Delivery_Task_Store_V3`.

After (Studio 4): callers depend on `Task_Query_Port`; concrete store is adapted behind it.

```nex
class Delivery_Task_Store_V3
create
  make(k1: String, v1: String, k2: String, v2: String) do
    this.k1 := k1
    this.v1 := v1
    this.k2 := k2
    this.v2 := v2
  end
feature
  k1: String
  v1: String
  k2: String
  v2: String

  find_status(task_id: String): String
    require
      id_present: task_id /= ""
    do
      if task_id = k1 then
        result := v1
      elseif task_id = k2 then
        result := v2
      else
        result := "NOT_FOUND"
      end
    ensure
      non_empty: result /= ""
    end
end

class Delivery_Task_Adapter
create
  make(store: Delivery_Task_Store_V3) do
    this.store := store
  end
feature
  store: Delivery_Task_Store_V3

  status_of(task_id: String): String
    require
      id_present: task_id /= ""
    do
      result := store.find_status(task_id)
    ensure
      non_empty: result /= ""
    end
end

class Delivery_Workflow
create
  make(tasks: Delivery_Task_Adapter) do
    this.tasks := tasks
  end
feature
  tasks: Delivery_Task_Adapter

  dispatch_view(task_id: String): String
    require
      id_present: task_id /= ""
    do
      let st: String := tasks.status_of(task_id)
      if st = "NOT_FOUND" then
        result := "UNKNOWN_TASK"
      else
        result := "TASK_STATUS:" + st
      end
    ensure
      non_empty: result /= ""
    end
end
```

### Knowledge Refactor (Port + Validator Service)

```nex
class Doc_Index_V3
create
  make(dk1: String, dk2: String, dk3: String) do
    this.dk1 := dk1
    this.dk2 := dk2
    this.dk3 := dk3
  end
feature
  dk1: String
  dk2: String
  dk3: String

  has_doc(doc_id: String): Boolean
    require
      id_present: doc_id /= ""
    do
      result := doc_id = dk1 or doc_id = dk2 
	            or doc_id = dk3
    ensure
      bool_result: result = true or result = false
    end
end

class Doc_Query_Adapter
create
  make(idx: Doc_Index_V3) do
    this.idx := idx
  end
feature
  idx: Doc_Index_V3

  has_doc(doc_id: String): Boolean
    require
      id_present: doc_id /= ""
    do
      result := idx.has_doc(doc_id)
    ensure
      bool_result: result = true or result = false
    end
end

class Link_Validation_Service
create
  make(docs: Doc_Query_Adapter) do
    this.docs := docs
  end
feature
  docs: Doc_Query_Adapter

  validate(from_id, to_id: String): String
    require
      ids_present: from_id /= "" and to_id /= ""
    do
      if not docs.has_doc(from_id) then
        result := "MISSING_FROM"
      elseif not docs.has_doc(to_id) then
        result := "MISSING_TO"
      else
        result := "VALID"
      end
    ensure
      known_result:
        result = "VALID" or
        result = "MISSING_FROM" or
        result = "MISSING_TO"
    end
end
```

### World Refactor (Port + Use Case Service)

```nex
class World_Model_V3
create
  make(wk1: String, wx1: Integer) do
    this.wk1 := wk1
    this.wx1 := wx1
  end
feature
  wk1: String
  wx1: Integer

  move_by_id(object_id: String, delta: Integer): String
    require
      id_present: object_id /= ""
    do
      if object_id = wk1 then
        wx1 := wx1 + delta
        result := "UPDATED"
      else
        result := "NOT_FOUND"
      end
    ensure
      non_empty: result /= ""
    end
end

class World_Update_Adapter
create
  make(model: World_Model_V3) do
    this.model := model
  end
feature
  model: World_Model_V3

  move_by_id(object_id: String, delta: Integer): String
    require
      id_present: object_id /= ""
    do
      result := model.move_by_id(object_id, delta)
    ensure
      non_empty: result /= ""
    end
end

class World_Step_Service
create
  make(updater: World_Update_Adapter) do
    this.updater := updater
  end
feature
  updater: World_Update_Adapter

  apply_player_input(object_id: String, 
                     delta: Integer): String
    require
      id_present: object_id /= ""
    do
      result := updater.move_by_id(object_id, delta)
    ensure
      known_result: result = "UPDATED" 
	                or result = "NOT_FOUND"
    end
end
```

### Studio Driver (Behavior Parity Check)

```nex
class App
feature
  run() do
    -- Delivery setup
    let ds: Delivery_Task_Store_V3
      := create Delivery_Task_Store_V3.make(
           "T-1", "PENDING", "T-2", "IN_TRANSIT"
         )
    let da: Delivery_Task_Adapter
      := create Delivery_Task_Adapter.make(ds)
    let dw: Delivery_Workflow := 
	  create Delivery_Workflow.make(da)

    print(dw.dispatch_view("T-2"))   -- TASK_STATUS:IN_TRANSIT
    print(dw.dispatch_view("T-99"))  -- UNKNOWN_TASK

    -- Knowledge setup
    let di: Doc_Index_V3
      := create Doc_Index_V3.make("D-1", "D-2", "D-3")
    let dqa: Doc_Query_Adapter := 
	  create Doc_Query_Adapter.make(di)
    let lvs: Link_Validation_Service
      := create Link_Validation_Service.make(dqa)

    print(lvs.validate("D-1", "D-3"))   -- VALID
    print(lvs.validate("D-9", "D-3"))   -- MISSING_FROM

    -- World setup
    let wm: World_Model_V3 := 
	  create World_Model_V3.make("E-1", 10)
    let wua: World_Update_Adapter
      := create World_Update_Adapter.make(wm)
    let wss: World_Step_Service := 
	  create World_Step_Service.make(wua)

    print(wss.apply_player_input("E-1", 2))  -- UPDATED
    print(wss.apply_player_input("E-9", 2))  -- NOT_FOUND
  end
end
```

Expected outcome:

- same core behavior as Studio 3 refactored paths
- clearer component boundaries
- easier component-level testing due to port seams


## Studio Challenges

### Level 1 — One Architectural Seam

- pick one Studio 3 optimized path
- introduce a port + adapter boundary
- prove behavior parity on nominal and edge cases

### Level 2 — Three-Domain Architecture Pass

- apply port/adapter + service orchestration pattern to delivery, knowledge, and world
- remove at least one direct concrete dependency per domain
- document dependency direction before/after

### Level 3 — Alternative Architecture Experiment

- compare two styles:
  - port/adapter with thin services
  - feature modules with internal adapters
- evaluate maintainability and migration cost


## Postmortem

Discuss with evidence:

- Which seam reduced coupling the most?
- Which abstraction clarified behavior, and which added unnecessary ceremony?
- Which components are still doing too much?
- What is the next architecture risk if the system doubles again?


## Deliverables

- architecture diagram with components and dependency direction
- refactored Nex code using ports/adapters over Studio 3 V3 logic
- behavior-parity run log (before/after outputs)
- short ADR-style note with chosen architecture and rejected alternative


## Exit Criteria

You are ready for Part VII if:

- key use cases depend on ports, not concrete storage classes
- orchestration logic is separated from storage/index details
- cross-component contracts are explicit and testable
- behavior parity with Studio 3 is demonstrated
