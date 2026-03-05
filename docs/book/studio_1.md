# Studio 1 — Our First Tiny System

## Subtitle

Building the smallest working versions of all three systems.

## 1. The Situation

The team has finished Part I and has problem statements, example scenarios, and edge-case notes.
Now they must ship a tiny end-to-end version of each system to test whether the problem framing is actually usable.

Systems in scope:

* delivery scheduler (single robot, tiny map)
* note organizer (small collection, simple lookup)
* virtual world (few entities, deterministic update loop)

## 2. Engineering Brief

Build minimal vertical slices that run from input to output.

Required outcomes:

* define a minimal model for each system
* implement one core operation per system
* encode at least one explicit contract per operation
* demonstrate behavior on nominal and edge inputs

Implementation guidance:

* prefer clarity over generality
* keep architecture deliberately small
* document assumptions that might break at scale

## 3. Studio Challenges

### Level 1 — Core Implementation

* Implement the tiny system for one domain.
* Add executable examples and expected results.

### Level 2 — Cross-System Generalization

* Implement tiny versions for all three domains.
* Identify one shared abstraction across them.

### Level 3 — Exploration

* Replace one design choice with an alternative and compare outcomes.
* Record what changed in complexity and failure behavior.

## 4. Postmortem

Discuss:

* Which assumptions were validated?
* Which assumptions were false?
* What information was missing from the original problem statement?
* What should be tightened before model redesign?

## Deliverables

* runnable Nex code for all tiny systems
* short design notes (inputs, outputs, guarantees)
* edge-case checklist with observed behavior
