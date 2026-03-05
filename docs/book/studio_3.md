# Studio 3 — The Scaling Crisis

## Subtitle

Surviving growth with better data structures and algorithm choices.

## 1. The Situation

Data volume has increased to thousands of objects.
The same operations that worked earlier now become slow, inconsistent, or unresponsive.

Symptoms observed:

* latency spikes
* repeated linear scans
* expensive updates on critical paths

## 2. Engineering Brief

Redesign data access and core algorithms for scale.

Required outcomes:

* identify top bottleneck operations
* replace at least one naive structure with a better fit
* provide complexity expectations for critical paths
* validate performance improvement on representative workloads

Implementation guidance:

* measure before and after
* preserve correctness while changing internals
* justify each structure/algorithm choice

## 3. Studio Challenges

### Level 1 — Core Implementation

* Optimize one bottleneck in one system.
* Show measurable improvement.

### Level 2 — Design Improvement

* Optimize all three systems around their dominant operations.
* Add regression checks for both performance and correctness.

### Level 3 — Exploration

* Compare two competing algorithms under varied input distributions.
* Explain when each approach wins.

## 4. Postmortem

Discuss:

* Which assumptions about scale were wrong?
* Which optimizations were meaningful vs premature?
* Which complexity arguments matched observed behavior?

## Deliverables

* benchmark scenarios and results
* updated Nex implementations
* algorithm/structure selection rationale
