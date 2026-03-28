# Designing for Change

Managing complexity, as we saw in `Managing Complexity`, is the discipline of keeping a system understandable today. It is about drawing boundaries so that the cost of reasoning remains within our budget. But a system that is easy to understand today may still be prohibitively expensive to change tomorrow. Requirements evolve, technology stacks shift, and business models pivot. If every such change requires reaching across boundaries and rewriting stable logic, the system has failed a fundamental engineering test.

Designing for change is the practice of building "seams" into a system — locations where behavior can vary without breaking the contracts that surround them. The goal is not to predict the future, which is impossible, but to build a system that can absorb it.


## The Concept of Change Seams

A seam is a boundary where we can alter behavior without editing the code that uses that behavior. In a well-designed system, seams are placed at the points of highest likely volatility.

Consider a notification system. Today, it sends emails. Tomorrow, it might need to send SMS messages, push notifications, or Slack alerts. If the logic for "how to send an email" is hardcoded into the heart of the dispatch workflow, then adding SMS support requires modifying the dispatch workflow. The dispatch workflow is now coupled to the notification transport.

A seam decouples them. By defining a stable contract — `Notification_Transport.send(message)` — the dispatch workflow can remain entirely ignorant of how the message is delivered. It depends on the *interface* of the transport, not its implementation. When a new transport is added, we don't change the dispatch logic; we simply provide a different implementation of the seam.

Good seams isolate volatility. They allow the stable parts of the system (the "what") to remain unchanged while the volatile parts (the "how") evolve independently.


## Stable Contracts and the Cost of Evolution

The backbone of a change-safe system is the stable contract. A contract is an agreement about behavior, inputs, and outputs. When a contract is stable, the code on either side of it can change freely as long as the agreement is honored.

The most dangerous kind of change is the "breaking" change — an alteration to a contract that forces every consumer of that contract to change as well. In a large system, a single breaking change at a low level can trigger a cascade of updates that consumes weeks of engineering time.

To minimize this cost, we adopt a discipline of additive evolution:
- **Prefer additive fields:** When a data structure needs more information, add a new field rather than repurposing an old one.
- **Maintain deprecation windows:** Give consumers time to migrate to new interfaces before removing old ones.
- **Provide compatibility adapters:** If a contract must change, provide a layer that allows old clients to talk to the new implementation.

Without versioning discipline and contract stability, every "improvement" to the system becomes a migration crisis for the rest of the team.


## From Requirement to Flexible Design

Consider the requirement:
> *"We need to experiment with a new ranking algorithm for search results, but we must be able to switch back to the old one instantly if the metrics drop."*

Without a seam, the ranking logic is likely embedded in the search service. Switching algorithms means a code change, a deployment, and a high-risk transition.

With a seam, we design for variation:

1.  **Define the Port:** We create a stable interface, `Ranking_Strategy`, with a single operation: `rank(results: List[Document])`.
2.  **Implement the Variants:** We create `Legacy_Ranking` and `Experimental_Ranking`, both adhering to the `Ranking_Strategy` contract.
3.  **Introduce the Switch:** The search service is given an instance of `Ranking_Strategy` at runtime. Which implementation it gets is decided by a configuration setting or a feature flag.

The search service remains unchanged. Its contract is satisfied by any object that knows how to rank. We can now swap algorithms, run A/B tests, or roll back a failure without touching the core search orchestration.


## Implementation in Nex

In Nex, we use classes and features to define these seams. The `require` and `ensure` clauses make the contract explicit, ensuring that any new implementation of the seam honors the same behavioral guarantees as the old one.

```nex
-- The Seam Definition
deferred class Ranking_Strategy
feature
  rank(query: String, candidates: Array[String]): Array[String]
    require
      query_present: query /= ""
      has_candidates: candidates.size > 0
    do
    ensure
      results_match_input_size: result.size = candidates.size
    end
end

-- Variant 1: Legacy
class Legacy_Ranking
inherit Ranking_Strategy
feature
  rank(query: String, candidates: Array[String]): Array[String]
    do
      result := candidates
    end
end

-- Variant 2: Modern (ML-based)
class Modern_Ranking
inherit Ranking_Strategy
feature
  rank(query: String, candidates: Array[String]): Array[String]
    do
      -- Complex ranking logic...
      result := candidates -- placeholder
    end
end

-- The Consumer: Unchanged by variation
class Search_Service
create
  make(strategy: Ranking_Strategy) do
    this.strategy := strategy
  end
feature
  strategy: Ranking_Strategy

  fetch_from_index(q: String): Array[String]
    require
      query_present: q /= ""
    do
      result := ["DOC:A", "DOC:B"]
    ensure
      has_candidates: result.size > 0
    end

  execute_search(q: String): Array[String]
    require
      query_present: q /= ""
    do
      let initial_docs: Array[String] := fetch_from_index(q)
      result := strategy.rank(q, initial_docs)
    ensure
      has_candidates: result.size > 0
    end
end
```

The `deferred` class `Ranking_Strategy` acts as the port. The `Search_Service` depends only on this abstraction. Whether the `strategy` is an instance of `Legacy_Ranking` or `Modern_Ranking` is a configuration detail. The search logic is protected from the volatility of the ranking algorithm.


## Designing for Change Across the Three Systems

In the **delivery system**, the seam is the `Routing_Policy`. We can swap from a "shortest distance" policy to a "minimum fuel" policy or a "driver preference" policy without changing the dispatch engine that executes the routes.

In the **knowledge engine**, the seam is the `Document_Parser`. As new file formats are supported — PDF, Markdown, LaTeX — we add new parser implementations. The indexing pipeline remains unchanged because it interacts with the stable `Parsed_Content` contract.

In the **virtual world**, the seam is the `Entity_Behavior`. A "Player" and an "NPC" might share the same physical simulation rules but have different decision-making logic. By isolating behavior behind a seam, we can add new types of entities without modifying the physics engine.

In all three cases, the pattern is the same: identify the part that is likely to vary, wrap it in a stable contract, and allow the rest of the system to depend on the contract rather than the variation.


## Three Ways Design for Change Fails

**Premature Abstraction.** The most common mistake is building seams for variations that never happen. Every seam adds a layer of indirection and a small cost to reasoning. If you build five different "strategy" ports for logic that hasn't changed in three years, you have wasted complexity budget. The remedy is to add seams only when volatility is evidenced or highly probable.

**Leaky Abstractions.** A seam is useless if the contract requires the caller to know about the implementation. If `Ranking_Strategy` requires the caller to pass database-specific credentials, the seam has leaked infrastructure details into the domain. The remedy is to keep contracts focused on the *intent* of the operation, not the *mechanics* of the implementation.

**The "Big Bang" Migration.** Designing for change implies that the system will evolve. If a team introduces a new version of a service but provides no compatibility for old clients, they haven't designed for change — they've designed for disruption. The remedy is to make compatibility a first-class architectural requirement, using adapters and versioned interfaces to bridge the gap.


::: {.note-exercise}
**Quick Exercise**

Pick one part of your system that you expect to change in the next six months. Define the "Port" (the stable contract) that would allow that change to happen without affecting the surrounding code. 

1.  What is the name of the contract?
2.  What are its `require` and `ensure` conditions?
3.  What information must stay *out* of the contract to keep it implementation-agnostic?
:::

::: {.note-takeaways}
**Takeaways**

- Designing for change is about building seams where volatility can land without causing system-wide damage.
- A seam is a boundary that allows behavior to vary while the contract remains stable.
- Stable contracts are the prerequisite for safe evolution. Additive changes are almost always cheaper than breaking ones.
- Over-engineering is as dangerous as under-engineering. Build seams where change is likely, not where it is merely possible.
- Compatibility is not a release chore; it is an architectural discipline that allows a system to grow without leaving its users behind.
:::



*The next chapter, `Refactoring Without Fear`, examines the practical discipline of refactoring — how to move a system from its current structure to a better one while proving that its behavior remains unchanged.*
