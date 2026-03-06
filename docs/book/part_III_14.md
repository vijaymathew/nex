# Part III — The Shape of Algorithms — Measuring Algorithm Behavior

## 14. Measuring Algorithm Behavior

An algorithm can be correct and still fail in production.

Why? Because correctness alone does not guarantee acceptable behavior under real load.

Algorithm engineering always has two gates:

1. correctness
2. cost behavior at expected and adverse scale

This chapter focuses on the second gate.

---

## What To Measure

Performance is a profile, not a single number.

You typically care about:

- latency (single-operation response time)
- throughput (work per time unit)
- memory footprint
- worst-case behavior
- tail behavior (P95/P99)

System priorities differ:

### Delivery

- route recomputation latency during disruptions

### Knowledge

- query latency and ranking throughput

### Virtual World

- stable per-tick budget and bounded spikes

---

## Asymptotics + Measurement

Asymptotic analysis is necessary, but insufficient.

Use Big-O to compare growth trends and avoid structurally bad choices.
Use benchmarks/profiling to capture constants, cache effects, and real workload shape.

Example:

- linear scan may be faster for very small `N`
- indexed lookup dominates as `N` grows

Good decisions require both model and evidence.

---

## Worked Design Path

Requirement:

> “Return top 10 relevant documents quickly for every query.”

### Step 1: Candidate approaches

- full scan and score all docs
- indexed candidate generation then score subset

### Step 2: Cost hypothesis

- full scan: often `O(N)` per query
- index-first: closer to `O(k)` scoring where `k << N` for many workloads

### Step 3: Explicit targets

- P95 latency (for example, <150ms)
- memory budget for index
- update-time budget for ingest

### Step 4: Benchmark plan

- normal case distribution
- heavy-tail queries
- adversarial spike case

### Step 5: Fallback behavior

When candidate set explodes:

- apply bounded heuristic mode
- return partial but valid result with declared status

---

## Nex Implementation Sketch

```nex
class Strategy_Selector
feature
  estimate_linear(n: Integer): Integer
    require
      non_negative_n: n >= 0
    do
      result := n
    ensure
      non_negative_result: result >= 0
    end

  estimate_indexed(k: Integer): Integer
    require
      non_negative_k: k >= 0
    do
      result := k
    ensure
      non_negative_result: result >= 0
    end

  choose(n, k: Integer): String
    require
      valid_inputs: n >= 0 and k >= 0
    do
      if estimate_indexed(k) < estimate_linear(n) then
        result := "INDEXED"
      else
        result := "LINEAR"
      end
    ensure
      known_strategy: result = "INDEXED" or result = "LINEAR"
    end
end
```

This is intentionally simple, but it shows a key habit: strategy selection is explicit and testable.

---

## Measurement Checklist

Before claiming an algorithm is fast enough, answer:

1. What is the real input size distribution?
2. What are normal, edge, and adversarial cases?
3. Which metric is the actual bottleneck (latency, throughput, memory, tail)?
4. What does worst-case behavior look like?
5. What fallback keeps behavior bounded under stress?

Without these answers, “fast enough” is an assumption.

---

## Common Mistakes

### Mistake 1: Optimizing before correctness

Symptom:

- fast but invalid outputs

Recovery:

- lock correctness guarantees first
- optimize under contract-preserving tests

### Mistake 2: Treating Big-O as full truth

Symptom:

- ignores constants and workload realities

Recovery:

- pair asymptotics with empirical measurement

### Mistake 3: Benchmarking toy inputs only

Symptom:

- production behavior surprises team

Recovery:

- benchmark realistic and adversarial distributions

### Mistake 4: Ignoring tail latency

Symptom:

- acceptable average, poor user experience

Recovery:

- monitor P95/P99 and outlier paths

### Mistake 5: No capacity narrative

Symptom:

- unknown breakpoints under growth

Recovery:

- document expected growth envelope and redesign trigger

---

## Quick Exercise (12 Minutes)

Pick one algorithm and create a cost brief:

1. primary operation
2. expected input range
3. asymptotic estimate
4. dominant constant factors
5. benchmark design (normal + edge + spike)
6. fallback policy
7. redesign trigger condition

Share it with a teammate. If they cannot predict failure mode, your brief is incomplete.

---

## Connection to Nex

Nex keeps cost-related decisions close to code and contracts, making strategy assumptions visible and easier to validate.

The transferable lesson is language-independent: measure behavior against model assumptions before scale forces emergency redesign.

---

## Chapter Takeaways

- Correctness and cost are dual constraints.
- Asymptotic reasoning guides; measurement validates.
- Tail behavior matters as much as average behavior.
- Strategy choices should be explicit, testable, and workload-driven.
- Capacity planning is part of algorithm design.

---

Part III gave us the algorithmic toolkit: definition, decomposition, recursion, and cost reasoning.

Part IV now focuses on data structures that make these algorithms practical at scale.
