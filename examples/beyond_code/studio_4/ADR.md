# ADR-001 — Ports & Adapters over the Studio 3 V3 stores

**Status:** Accepted · **Context:** Studio 4 (Beyond Code) · **Supersedes:**
direct use of Studio 3 V3 storage classes.

## Architecture diagram

```
              ┌──────────────────────────── domain / use cases ───────────────────────────┐
              │                                                                             │
   caller ───▶│  Delivery_Workflow        Link_Validation_Service     World_Step_Service   │
              │        │                          │                          │              │
              └────────┼──────────────────────────┼──────────────────────────┼─────────────┘
                       │ depends on               │ depends on               │ depends on
                       ▼                          ▼                          ▼
                ┌─────────────┐           ┌──────────────┐          ┌──────────────────┐
   ports ──────▶│Task_Query_  │           │ Doc_Query_   │          │ World_Update_    │   (abstractions)
                │   Port      │           │   Port       │          │   Port           │
                └──────△──────┘           └──────△───────┘          └────────△─────────┘
                       │ inherit                 │ inherit                   │ inherit
                ┌──────┴───────────┐     ┌───────┴──────────┐      ┌─────────┴──────────┐
  adapters ────▶│Delivery_Task_    │     │ Doc_Query_       │      │ World_Update_      │
                │   Adapter        │     │   Adapter        │      │   Adapter          │
                └──────┬───────────┘     └───────┬──────────┘      └─────────┬──────────┘
                       │ wraps                   │ wraps                     │ wraps
                       ▼                         ▼                           ▼
   Studio 3 ───▶ Delivery_Store_V3       Knowledge_Model_V3          World_Model_V3      (unchanged)
   V3 stores         (Map by id)            (Map by id)                 (Map by id)
```

**Dependency rule:** every arrow from a use case points at a **port**
(abstraction). Concrete storage is reached only *downward through an adapter*.
No domain/use-case class names a storage class. Storage knows nothing of use
cases.

## Decision

Adopt **ports & adapters with thin orchestration services** as the structure for
the three Studio 3 optimized paths:

- One **port** per collaboration need (`Task_Query_Port` / `Doc_Query_Port` /
  `World_Update_Port`) — a contracted base class.
- One **adapter** per concrete store, `inherit`ing its port and delegating to the
  **unchanged** Studio 3 V3 implementation.
- One **service/workflow** per use case, depending only on the port.
- A **composition root** per domain (`*_Arch_Refactor.wire`) is the single place
  concrete classes are named and wired.

## Rationale (evidence)

- **Behavior parity preserved:** `studio_4_main.nex` runs every call before
  (direct V3) and after (through the port) and asserts equality — `PASS` on
  delivery, knowledge, and world.
- **Decoupling achieved:** each use case depends on an abstraction; one concrete
  dependency removed per domain. The knowledge validation *rule* also moved out
  of storage into a service.
- **Testability achieved:** `level3_feature_modules.nex` runs `Delivery_Workflow`
  over a `Stub_Task_Port` with zero storage — isolated use-case testing the seam
  makes possible.

## Rejected alternative — Feature modules with internal adapters

A single cohesive `Delivery_Feature_Module` that owns its store and folds the
adapter step inside (no shared port). **Identical behavior** (parity `PASS`), and
genuinely *less ceremony*.

Rejected because the brief's exit criteria require use cases to depend on **ports,
not concrete storage**, and substitutability/isolated testing are first-class
goals here:

- a feature module cannot be unit-tested without its real store (no seam to stub);
- it cannot swap storage implementations without edits;
- a shared abstraction (e.g. one `Doc_Query_Port` reused by validation *and* a
  future search use case) is impossible when each module re-internalizes it.

The feature-module style remains the right call for a small, stable, single-impl
feature; it loses specifically when substitutability or shared abstractions
matter — which is the situation Studio 4 targets. Migration B→A (extracting the
seam later) is real work; A→B (collapsing) is trivial — so we pay the seam cost
up front.

## Consequences

- More classes and a wiring layer; the composition roots will need to grow into a
  proper composition/DI strategy if use cases multiply (noted as the next risk).
- Adapters that only forward (delivery) look like ceremony until a second port
  implementation exists — which the stub already shows arriving.
- The V3 stores still expose Studio-3-era methods (e.g. `validate_link`) kept for
  the parity check; a follow-up should delete what the services now own.

## Assumptions that must hold

1. Port contracts are the stable boundary — changing a port ripples to every
   adapter and service.
2. Adapters preserve the V3 semantics they wrap (incl. the performance intent);
   they adapt shape, not meaning.
3. Storage stays free of use-case logic; new rules go in services, not stores.
