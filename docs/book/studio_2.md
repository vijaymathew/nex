# Studio 2 — The Model Redesign

## Subtitle

Refactoring data models so the systems can grow without collapsing.

## 1. The Situation

The first versions work for demos but break when data becomes richer.
Entities are unclear, relationships are brittle, and updates are hard to reason about.

Symptoms observed:

* duplicated or ambiguous state
* ad-hoc references between objects
* features that require scattered changes

## 2. Engineering Brief

Redesign the core data models for all three systems.

Required outcomes:

* define explicit entities and relationships
* remove at least one accidental coupling per system
* add invariants that preserve model consistency
* migrate existing examples to the new model

Implementation guidance:

* optimize for understandable structure first
* keep model boundaries explicit
* make invalid states hard to represent

## 3. Studio Challenges

### Level 1 — Core Implementation

* Redesign one system model and migrate code.
* Prove old behaviors still work.

### Level 2 — Design Improvement

* Redesign all three systems with a consistent modeling vocabulary.
* Add model-validation checks.

### Level 3 — Exploration

* Compare state-centric and event-centric model variants.
* Evaluate extensibility under a new feature request.

## 4. Postmortem

Discuss:

* Which redesign choices reduced complexity most?
* Which invariants prevented real bugs?
* What tradeoffs were accepted and why?

## Deliverables

* revised model diagrams or structured notes
* migrated Nex code with invariants
* before/after complexity notes
