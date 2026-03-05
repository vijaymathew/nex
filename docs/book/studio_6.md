# Studio 6 — Evolution

## Subtitle

Extending mature systems safely under new requirements.

## 1. The Situation

New product requirements arrive after the architecture has stabilized.
The challenge is no longer initial construction; it is controlled evolution without regression.

Examples of change pressure:

* new delivery constraints and routing policies
* richer knowledge queries and ranking behavior
* new virtual-world interaction types

## 2. Engineering Brief

Implement significant feature growth while preserving correctness and design integrity.

Required outcomes:

* evaluate impact before coding
* design extension points instead of ad-hoc patches
* keep existing contracts/tests passing
* deliver new capability with minimal architectural drift

Implementation guidance:

* prefer additive changes behind stable interfaces
* use refactoring and tests as migration safety nets
* record deprecation and compatibility decisions explicitly

## 3. Studio Challenges

### Level 1 — Core Implementation

* Add one meaningful feature to one system without breaking old behavior.
* Update contracts and tests accordingly.

### Level 2 — Design Improvement

* Add parallel features across all three systems using shared extension patterns.
* Measure impact on complexity and maintainability.

### Level 3 — Exploration

* Simulate two future requirement changes and stress-test adaptability.
* Compare upfront extensibility design vs just-in-time refactoring.

## 4. Postmortem

Discuss:

* What made evolution easy or hard?
* Which earlier design decisions paid off most?
* What architectural debt remains and how should it be managed?

## Deliverables

* feature impact analysis
* updated Nex implementations with passing tests
* evolution roadmap with prioritized refactors
