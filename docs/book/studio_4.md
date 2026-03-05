# Studio 4 — The Architecture Refactor

## Subtitle

Turning working code into maintainable system architecture.

## 1. The Situation

Features exist, but the codebase is becoming fragile.
Changes in one area break distant modules.
Responsibilities are unclear and interfaces leak implementation details.

Symptoms observed:

* high coupling
* duplicated business logic
* hard-to-test components

## 2. Engineering Brief

Refactor architecture around modules, boundaries, and contracts.

Required outcomes:

* define component boundaries per system
* isolate domain logic from infrastructure concerns
* formalize module interfaces with explicit contracts
* keep behavior stable while improving structure

Implementation guidance:

* prioritize seam creation before large rewrites
* refactor incrementally with safety checks
* make dependencies point inward to domain intent

## 3. Studio Challenges

### Level 1 — Core Implementation

* Refactor one subsystem into clear components.
* Add interface-level tests.

### Level 2 — Design Improvement

* Apply architectural pattern consistently across all three systems.
* Remove at least one cyclic dependency.

### Level 3 — Exploration

* Prototype an alternate architecture and compare maintainability impact.
* Evaluate migration cost and long-term benefits.

## 4. Postmortem

Discuss:

* Which boundaries were most valuable?
* Which abstractions improved comprehension vs added ceremony?
* What remains risky in the current architecture?

## Deliverables

* module map with responsibility statements
* refactored Nex code with contracts
* change log showing preserved behavior
