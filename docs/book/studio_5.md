# Studio 5 — Reliability

## Subtitle

Hardening systems with contracts, testing, and debugging discipline.

## 1. The Situation

The systems are functionally complete but not trustworthy under real conditions.
Rare inputs and timing interactions expose correctness gaps.

Symptoms observed:

* intermittent failures
* hidden invariant violations
* bug fixes that trigger regressions

## 2. Engineering Brief

Build reliability infrastructure across all systems.

Required outcomes:

* enforce core invariants in code
* add a layered test strategy (unit + property-style checks)
* create reproducible debugging workflows for critical failures
* define release-quality gates

Implementation guidance:

* write tests for behavior, not implementation details
* prioritize high-risk invariants first
* require reproducibility before declaring bugs fixed

## 3. Studio Challenges

### Level 1 — Core Implementation

* Add invariant checks and focused tests for one system.
* Demonstrate one bug found and fixed.

### Level 2 — Design Improvement

* Build reliability harnesses for all three systems.
* Add failure-focused test cases from observed incidents.

### Level 3 — Exploration

* Use generative/property-style tests to discover non-obvious faults.
* Compare debugging strategies and time-to-root-cause.

## 4. Postmortem

Discuss:

* Which invariants provided highest reliability leverage?
* Which tests prevented future regressions?
* Which debugging practices improved team effectiveness most?

## Deliverables

* invariant catalog
* executable test suites with coverage notes
* debugging playbooks for top failure classes
