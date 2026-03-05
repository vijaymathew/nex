# Part VI — Building Real Software — Object-Oriented Thinking

## 25. Object-Oriented Thinking

## Chapter Purpose

This chapter deepens the reader's engineering judgment by connecting problem framing to implementation choices in Nex.

## Narrative Setup

The delivery network, knowledge engine, and virtual world each expose a new failure mode that can only be resolved by improving system design, not by patching isolated code.

## Learning Goals

By the end of this chapter, the reader should be able to:

* explain and apply **responsibility allocation**
* reason about **collaboration protocols**
* design and evaluate solutions around **behavior-centric design**

## Section Outline

### 1. Conceptual Foundation

* Define the central idea in practical engineering terms.
* Contrast beginner intuition with production realities.
* Show how the idea appears in all three running systems.

### 2. Worked Design Path

* Start from an ambiguous requirement.
* Derive a structured model/algorithm/interface step by step.
* Discuss tradeoffs, failure modes, and explicit assumptions.

### 3. Nex Implementation Sketch

* Identify key Nex classes/functions needed.
* Draft contracts (`require`, `ensure`, invariants) where relevant.
* Show a minimal but extensible implementation skeleton.

### 4. Common Mistakes and Recovery

* List high-frequency design mistakes for this topic.
* Provide diagnostics to detect each mistake early.
* Provide refactoring moves that restore correctness and clarity.

### 5. Reflection and Checkpoint

* What changed in our model of the system?
* What decisions are still provisional?
* What evidence do we have that the design works?

## Studio Exercises

* **Core**: implement the minimal version needed for one system.
* **Extension**: generalize to all three systems with shared abstractions.
* **Stress Test**: construct adversarial inputs and validate behavior.

## Assessment Signals

* correctness under normal and edge conditions
* explicit handling of assumptions and invariants
* quality of decomposition and naming
* ability to explain why this design was chosen over alternatives

## Forward Link

This chapter prepares the next chapter by establishing the abstractions and evidence needed for larger-scale design decisions.
