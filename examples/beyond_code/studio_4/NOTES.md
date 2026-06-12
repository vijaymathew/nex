# Studio 4 — Architecture Notes (Levels 1–3)

**The Architecture Refactor.** Studio 3 made the hot paths fast (V3 indexes).
Studio 4 keeps those gains but fixes *architecture fragility*: orchestration was
tangled with storage, and callers depended on concrete V3 classes. We refactor
around **components, ports, and contracts** without changing externally visible
behavior — proven by a before/after parity log.

## Files

| File | Role |
|---|---|
| `Measure_Result.nex` | Carried from Studio 3 (V3 stores still return it). |
| `architecture_ports.nex` | The three ports: `Task_Query_Port`, `Doc_Query_Port`, `World_Update_Port`. |
| `delivery_arch_refactor.nex` | V3 store (unchanged) + `Delivery_Task_Adapter` + `Delivery_Workflow`. |
| `knowledge_arch_refactor.nex` | V3 model (unchanged) + `Doc_Query_Adapter` + `Link_Validation_Service`. |
| `world_arch_refactor.nex` | V3 model (unchanged) + `World_Update_Adapter` + `World_Step_Service`. |
| `studio_4_main.nex` | **Levels 1 & 2** driver: before/after parity on all three domains. |
| `level3_feature_modules.nex` | **Level 3**: port/adapter vs feature-module experiment. |
| `ADR.md` | Architecture diagram + ADR-style decision note (deliverable). |

Run: `nex studio_4_main.nex`, `nex level3_feature_modules.nex`.

Each refactor module exposes an entry/composition-root class (e.g.
`Delivery_Arch_Refactor`) named so `intern` resolves the file; loading it
registers the V3 store, the adapter, and the workflow, and (transitively) the
ports and `Measure_Result`. The V3 stores are **carried forward unchanged** from
Studio 3 (real `Map`-by-id indexes returning `Measure_Result`), so Studio 3
semantics and performance intent are literally preserved — only the layers
*around* them change.

## The pattern (one dependency direction)

```
   use case            port (abstraction)         adapter            Studio 3 V3
  ----------          --------------------       ---------          ------------
  Delivery_Workflow ----> Task_Query_Port  <|---- Delivery_Task_Adapter ----> Delivery_Store_V3
  Link_Validation_  ----> Doc_Query_Port   <|---- Doc_Query_Adapter      ----> Knowledge_Model_V3
    Service
  World_Step_       ----> World_Update_Port<|---- World_Update_Adapter   ----> World_Model_V3
    Service
```

- A **port** is an interface-by-contract: a tiny base class stating what a
  collaborator needs, with a contracted default (a loud sentinel) that adapters
  override. Nex inheritance gives real polymorphism — a port-typed field
  dispatches to whatever adapter is plugged in.
- An **adapter** `inherit`s a port and delegates to a concrete V3 store, doing
  only the impedance-matching (e.g. reading `.value` off the store's
  `Measure_Result`).
- A **service / workflow** is the use case. It depends on the **port type only**;
  it never names a storage class. That is the seam.

---

# Level 1 — One Architectural Seam (Delivery)

`Delivery_Workflow.dispatch_view` is the use case. Before, a caller used
`Delivery_Store_V3` directly and formatted the status inline. After:

```
Delivery_Workflow ----> Task_Query_Port <|---- Delivery_Task_Adapter ----> Delivery_Store_V3
```

The workflow holds a `Task_Query_Port` field. The store is reachable only through
the adapter behind that port. **Parity** (from `studio_4_main.nex`):

```
status T-2:  before TASK_STATUS:IN_TRANSIT  ==  after TASK_STATUS:IN_TRANSIT   [OK]
missing T-9: before UNKNOWN_TASK             ==  after UNKNOWN_TASK              [OK]
```

`dispatch_view` is the *only* place the NOT_FOUND→UNKNOWN_TASK presentation rule
lives now; the store no longer knows about "views," and the workflow no longer
knows about maps.

---

# Level 2 — Three-Domain Architecture Pass

Same pattern across all three domains; each removes ≥1 direct concrete
dependency. **Dependency direction, before → after:**

| Domain | Before (concrete dependency) | After (depends on) | Concrete dep removed |
|---|---|---|---|
| Delivery | `Delivery_Workflow` → `Delivery_Store_V3` | `Delivery_Workflow` → `Task_Query_Port` | the store class |
| Knowledge | validation rule lived *inside* `Knowledge_Model_V3` | `Link_Validation_Service` → `Doc_Query_Port` | the model class (and the rule left storage) |
| World | caller → `World_Model_V3.move_by_id` | `World_Step_Service` → `World_Update_Port` | the model class |

The knowledge case is the most telling: in Studio 3 the *validation workflow*
(`validate_link`'s from/to logic) lived in the storage class. Studio 4 moves that
rule into `Link_Validation_Service`, which composes two `has_doc` port calls. The
rule is now reusable against any `Doc_Query_Port` (another store, a stub, a remote
index) and the storage class is back to doing only storage.

**Parity (all domains):**

```
KNOWLEDGE valid D1->D3   before VALID         == after VALID          [OK]
KNOWLEDGE missing D9->D3 before MISSING_FROM  == after MISSING_FROM    [OK]
WORLD     move E-2       before UPDATED       == after UPDATED         [OK]
WORLD     move E-9       before NOT_FOUND     == after NOT_FOUND        [OK]
=> all domains: Studio 4 (via ports) matches Studio 3 (direct): PASS
```

---

# Level 3 — Alternative Architecture Experiment

Two architectures for the delivery use case (`level3_feature_modules.nex`),
identical behavior, compared:

| | Style A — port/adapter + thin service | Style B — feature module w/ internal adapter |
|---|---|---|
| Structure | 3 classes (port, adapter, workflow) + shared port | 1 cohesive class owning its store |
| Caller depends on | the **port** (abstraction) | the **module** (concrete component) |
| Swap storage impl | yes — substitute another adapter behind the port | no — edit the module |
| Unit-test the use case | yes — run the workflow over a **stub port**, no storage | no — store is internal |
| Ceremony / LOC | higher | lower |
| Migration cost | paid **up front** | paid **later** (extracting the seam) |

**Measured evidence:**
- Behavior parity A vs B: `PASS` (identical `dispatch_view` output).
- Style A's `Delivery_Workflow` runs unchanged over a `Stub_Task_Port` returning
  canned data — the use case is exercised with **zero real storage**. Style B has
  no seam to substitute, so the same isolated test is impossible without editing
  the module.

**When each wins.** Feature modules (B) are the right amount of structure for a
small, stable feature with one implementation — less to read, faster to ship.
Port/adapter (A) earns its ceremony when a use case needs **substitutable
storage** or **isolated tests**, or when several services must share one
abstraction (e.g. `Doc_Query_Port` reused by validation *and* search). Migrating
B→A later is real work (extract the port, split the class); A→B is just
collapsing — so when substitutability is even *plausibly* coming, A is cheaper
over the life of the code.

---

# Postmortem (with evidence)

- **Which seam reduced coupling the most?** Knowledge. It did two things at once:
  introduced the `Doc_Query_Port` seam *and* relocated the validation rule out of
  `Knowledge_Model_V3` into a service. The storage class lost a responsibility,
  and the rule became reusable/stub-testable — the biggest structural change for
  the smallest behavior change (parity held exactly: VALID / MISSING_FROM /
  MISSING_TO unchanged).
- **Which abstraction clarified behavior; which added ceremony?** The ports
  *clarified*: each names exactly one collaboration need (`status_of`, `has_doc`,
  `move_by_id`) and the contract states it. The delivery **adapter** is the
  thinnest layer — it only reads `.value` — so for delivery alone it flirts with
  ceremony; it earns its place the moment a second `Task_Query_Port` implementation
  (a stub, a remote) appears, which Level 3 demonstrates.
- **Which components still do too much?** The Studio 3 V3 stores still carry
  Studio-3-era surface area (e.g. `Knowledge_Model_V3` still *exposes*
  `validate_link` even though the service now owns that rule — kept only for the
  parity check). In a follow-up, `validate_link` would be deleted from storage.
- **Next architecture risk if the system doubles again?** The **composition
  roots** (`*_Arch_Refactor.wire`) and the `studio_4_main` wiring will sprawl as
  use cases multiply — wiring becomes its own tangle. The next move is a real
  composition/DI layer (or per-feature modules that wire themselves), plus
  splitting workflows from presentation (today `dispatch_view` mixes lookup with
  the `TASK_STATUS:` formatting).

---

# Tradeoffs intentionally accepted

- **V3 stores are carried forward verbatim** (not re-interned across directories)
  so Studio 4 runs standalone while preserving Studio 3 semantics. They still
  return `Measure_Result`; the adapters discard the step count to meet the simpler
  port contracts (the performance *intent* is preserved; the *measurement* is just
  not surfaced through the port).
- **Ports are concrete base classes with sentinel defaults**, not `deferred`
  abstract types — simpler, and the default keeps an unwired port honest (loud
  `NOT_IMPLEMENTED`, never a plausible answer). Adapters override via `inherit`.
- **Presentation still rides inside the delivery workflow** (`TASK_STATUS:` /
  `UNKNOWN_TASK`). Splitting a presenter is deferred — noted as a next step.
