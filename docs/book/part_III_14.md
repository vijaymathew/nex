# Measuring Algorithm Behavior

An algorithm can be correct and still fail. Correctness means the algorithm produces the right answer for every valid input. It says nothing about how long that takes, or how much memory is consumed, or what happens when the inputs are larger or more numerous than the developer imagined when writing the code. A correct algorithm that takes thirty seconds to respond to a user query is not a usable algorithm. A correct algorithm that processes a hundred records perfectly and exhausts available memory on a hundred thousand is not a deployable one.

Algorithm engineering has two gates, and a candidate must pass both. The first gate is correctness — the subject of Chapters 11 through 13. The second is cost: how does the algorithm's resource consumption grow as inputs grow, and does that growth remain acceptable under the conditions the system will actually face? This chapter is about the second gate.


## What Cost Means

Cost is not a single number. It is a profile of several distinct quantities, each of which matters in different contexts and under different conditions.

**Latency** is the time a single operation takes from start to finish. For the delivery system, the relevant latency is how long route recomputation takes when a path becomes blocked and a new route must be found while the robot waits. For the knowledge engine, it is how long a single query takes to return ranked results. Latency determines whether a system feels responsive.

**Throughput** is the number of operations the system can complete per unit of time. A knowledge engine that handles one query per second correctly is not useful if the system receives a hundred queries per second. Throughput determines whether a system can sustain its intended load.

**Memory footprint** is the space the algorithm requires, both for its inputs and for any intermediate structures it constructs. An index that makes query latency acceptable may require more memory than the deployment environment provides. A traversal that accumulates visited nodes may grow unboundedly on large graphs.

**Worst-case behavior** is what happens when the algorithm encounters its most expensive possible input. An algorithm with excellent average performance and catastrophic worst-case performance is an algorithm that will occasionally fail in production in ways that are difficult to predict and reproduce.

**Tail behavior** is the distribution of costs at the high end — the P95 and P99 latencies, the requests that take far longer than the median. Average latency can be acceptable while the slowest five percent of requests take long enough to damage the user experience. Tail behavior is often more important than average behavior for systems with interactive users.


## Two Kinds of Reasoning

Understanding algorithmic cost requires two complementary forms of reasoning, neither of which is sufficient on its own.

**Asymptotic analysis** characterizes how cost grows as input size grows, independent of constants. An algorithm that examines every element of a collection once runs in time proportional to the size of the collection — O(N) in the standard notation. One that examines every pair of elements runs in O(N²). One that halves the remaining search space at each step runs in O(log N). These characterizations tell us which algorithms will remain acceptable as inputs scale and which will become unacceptable. An O(N²) algorithm on a collection that grows from thousands to millions of elements will increase its cost by a factor of a trillion; no constant-factor improvement to the implementation will rescue it.

**Empirical measurement** captures the behavior of a specific implementation on a specific workload. Asymptotic analysis suppresses constants, but constants matter: a linear scan over a small collection held entirely in cache may be faster in practice than a logarithmic lookup that requires following pointers through memory. The theoretical advantage of a more complex algorithm does not materialize until the input is large enough for the asymptotic term to dominate the constant. Without measurement, it is not possible to know where that threshold lies for a particular algorithm on a particular machine.

The discipline is to use asymptotic reasoning to avoid structurally bad choices, and to use measurement to validate that the remaining candidates behave acceptably on real workloads. Neither alone is sufficient. Asymptotic analysis without measurement is theory that may not match practice. Measurement without asymptotic analysis is benchmarking without the ability to predict behavior outside the cases measured.


## From Requirement to Cost Model

The following requirement will serve as a worked example:

> *"Return the top ten relevant documents quickly for every query."*

"Quickly" is not yet a specification, but it points toward one. The worked path below converts it into a cost model.

**Step 1: Identify candidate approaches and their asymptotic costs.** Two natural candidates are a full scan that scores all documents and selects the top ten, and an index-based approach that generates a small candidate set before scoring. The full scan is O(N) per query, where N is the total number of documents. The index-based approach is O(k) for scoring, where k is the size of the candidate set — and for most queries on a well-constructed index, k is much smaller than N. Asymptotic reasoning gives a strong preliminary reason to prefer the indexed approach at scale, without yet committing to any specific implementation.

**Step 2: State explicit performance targets.** "Quickly" must become a number: P95 query latency under 150 milliseconds, for example. A memory budget for the index. An update-time budget for incorporating new documents into the index. Without explicit targets, there is no way to determine whether a benchmark result is acceptable or whether an optimization has achieved its goal.

**Step 3: Design a benchmark that covers the real distribution.** A benchmark that tests only average queries on average-sized collections measures the performance of the typical case and nothing else. A complete benchmark must cover the normal case, the heavy-tail case (queries that match an unusually large fraction of the document collection), and the adversarial spike (sudden bursts of queries at rates above the expected average). Production behavior is determined by all three.

**Step 4: Define fallback behavior for the expensive case.** When a query matches a very large candidate set, the cost of the indexed approach approaches the cost of a full scan. Rather than allowing this to produce unbounded latency, the algorithm should apply a bounded heuristic mode when the candidate set exceeds a defined threshold — returning a partial but valid result with a declared status indicating that the response is bounded rather than complete. Fallback behavior must be designed, not improvised when the first spike occurs in production.

**Step 5: Define a redesign trigger.** Acceptable performance today may become unacceptable as the document collection grows. The cost model should include an explicit condition — a collection size, a query rate, a latency measurement — at which the current design is expected to stop meeting its targets and must be revisited. Without this, the system will be redesigned in response to a crisis rather than in anticipation of one.


## A Strategy Choice in Code

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
      known_strategy: result = "INDEXED" 
	                  or result = "LINEAR"
    end
end
```

The `estimate_linear` and `estimate_indexed` operations are simplified models of the two approaches' costs. `choose` selects between them based on a comparison of those estimates. The postcondition on `choose` guarantees that the result is always one of two declared strategy names — never an undefined value that a caller would have to interpret.

What matters most in this sketch is that the strategy selection is explicit and testable. A strategy choice buried in conditional logic inside the main algorithm is invisible: it cannot be reasoned about independently, cannot be tested in isolation, and cannot be swapped out when a new approach becomes available. An explicit strategy selector has all three properties. It makes the cost model a first-class element of the design rather than an assumption hidden in the implementation.


## Five Ways Cost Reasoning Fails

**Optimizing before correctness.** An algorithm that produces incorrect results faster is not an improvement. Every optimization changes the implementation, and every change to the implementation is an opportunity to introduce a correctness failure. The discipline is to establish and verify correctness guarantees first, and to optimize only under tests that would detect any regression in those guarantees. An optimization that passes all correctness tests can be trusted; one that predates them cannot.

**Treating asymptotic analysis as complete.** Big-O notation suppresses constants, lower-order terms, and the effects of memory hierarchy, cache behavior, and branch prediction — all of which matter on real hardware with real workloads. An algorithm with a better asymptotic complexity than its competitor may be slower in practice on the input sizes that actually occur. Asymptotic analysis is a necessary tool for avoiding structurally bad decisions. It is not a substitute for measurement.

**Benchmarking only typical inputs.** An algorithm that performs well on the average case and poorly on the adversarial case will produce acceptable measurements in the lab and unacceptable behavior in production. The adversarial case — the query that matches everything, the graph that contains a very long path, the burst of simultaneous requests — is the case that determines whether the system holds up under real conditions. Benchmark designs that omit it are not measuring the right thing.

**Ignoring tail latency.** The median query response time may be well within the target while the slowest five percent of queries take ten times as long. For a system with many users, the slowest five percent is not a rounding error — it is a population of users with a consistently poor experience. Designing to the median and ignoring the tail is designing for a performance profile that does not match the experience of all users.

**No capacity narrative.** An algorithm that meets its performance targets today on a collection of one million documents may fail to meet them next year on a collection of ten million. Without a model of how costs grow and an explicit threshold at which the current design must be reconsidered, the team will discover this failure in production. A capacity narrative — a documented projection of when current performance targets will be exceeded under expected growth — converts a future crisis into a scheduled engineering decision.


## Quick Exercise

Choose one algorithm in your system and write a cost brief with seven components: the primary operation whose cost you are characterizing, the expected input size range in production, an asymptotic estimate of the cost, the dominant constant factors you would expect to observe in measurement, a benchmark design covering normal, edge, and adversarial inputs, a fallback policy for the case where cost exceeds the acceptable threshold, and a redesign trigger condition.

Then apply this test: give the brief to a teammate and ask them to predict the failure mode — the condition under which the algorithm will first become unacceptable. If they cannot predict it from the brief, the brief is not yet a cost model. Find what is missing and add it.


## Takeaways

- Correctness and cost are dual constraints. An algorithm that passes only one gate is not ready for production.
- Cost is a profile, not a number: latency, throughput, memory footprint, worst-case behavior, and tail behavior each matter in different contexts.
- Asymptotic analysis identifies structurally bad choices. Empirical measurement validates that the remaining choices behave acceptably on real workloads. Both are required.
- Tail behavior is often more important than average behavior. Design to the distribution, not the median.
- A capacity narrative — a projection of when current performance targets will be exceeded — converts a future crisis into a scheduled decision.


*Part III has now built the algorithmic toolkit: a precise definition of what an algorithm is, the discipline of decomposition, recursive design over self-similar structure, and the cost reasoning that determines whether a correct algorithm is also a practical one. Part IV applies these tools to data structures — the concrete representations that make algorithms efficient at scale.*
