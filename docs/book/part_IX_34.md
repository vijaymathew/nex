# Working With AI Coding Assistants

The arrival of AI coding assistants represents one of the most significant shifts in the history of software development. For the first time, we have collaborators that can draft implementation, suggest refactors, and generate tests in seconds. But this speed comes with a profound risk: if we use AI to bypass the rigors of software engineering, we will simply build fragile systems faster.

The key to effective AI collaboration is not learning the right "magic words." It is applying the same engineering constraints to the AI that we apply to ourselves. When we treat the AI as a junior implementation partner working within a strictly defined architectural sandbox, it becomes a powerful accelerator. When we treat it as an oracle that defines our architecture, it becomes a source of systemic debt.


## AI as a Collaborator, Not an Authority

An AI assistant is an implementation engine, not a design authority. It is excellent at "filling in the blanks" once the boundaries, contracts, and invariants have been established. It is much less effective at determining what those boundaries should be.

The most common failure mode in AI-assisted development is the "vague prompt": *“Write a service that handles delivery task rerouting.”* This prompt provides no constraints. The AI will likely generate plausible-looking code that ignores your system’s existing state management, bypasses your domain invariants, and uses a different error-handling philosophy.

A successful collaborator provides context and constraints. The AI needs to know:
- **What is the goal?** (The high-level intent).
- **What are the boundaries?** (The existing interfaces it must use).
- **What are the invariants?** (The rules it must not break).
- **What are the acceptance criteria?** (The tests it must pass).


## Constraint-Driven Prompting

Instead of asking the AI to "write code," we should ask it to "solve a problem within these constraints." This is the discipline of constraint-driven prompting.

A robust prompt follows a structured pattern:
1.  **Task Objective:** Define exactly what the new behavior should be.
2.  **System Context:** Provide the existing class definitions and contracts.
3.  **Non-Negotiable Constraints:** Explicitly forbid certain types of changes (e.g., "Do not modify the `Task` class," "Use the existing `Logger` interface").
4.  **Verification Requirements:** Specify that the AI must also provide the unit tests or contract checks required to verify the new logic.

By anchoring the AI in your system's existing contracts, you force its output to be "born" into your architecture rather than imported as a foreign body.


## From Requirement to AI-Assisted Implementation

Consider the requirement:

> *"Add a fallback mechanism to our Knowledge Query Service so that if the primary retrieval fails, it returns a cached result."*

An undisciplined prompt might just ask for "fallback logic." A disciplined workflow looks like this:

1.  **Provide the Interface:** Give the AI the current `Knowledge_Query_Service` contract.
2.  **Define the Seam:** Tell the AI to implement a `Knowledge_Fallback_Wrapper` that implements the same interface but takes the original service as a dependency.
3.  **Specify Invariants:** "The wrapper must return a cached result only if the core service returns `UNAVAILABLE`. It must never return an empty string."
4.  **Request Parity Tests:** "Provide a test case showing that when the core service succeeds, the wrapper returns the exact same result."

The AI is now working in a very small, well-defined space. The resulting code is much more likely to be correct, maintainable, and aligned with your system’s existing patterns.


## Implementation in Nex

In Nex, our contracts and invariants are the perfect "anchors" for AI prompts. They translate our engineering intent into a form the AI can use to validate its own suggestions.

```nex
-- Current Interface (Context for the AI)
class Knowledge_Query_Service
feature
  query(q: String): String
    require
      query_present: q /= ""
    do
      -- Simple implementation
      if q = "contracts" then
        result := "DOC:K-101"
      else
        result := "UNAVAILABLE"
      end
    ensure
      non_empty: result /= ""
    end
end

-- AI-Generated Wrapper (Constrained by the interface)
class Knowledge_Fallback_Wrapper
create
  make(core: Knowledge_Query_Service) do
    this.core := core
  end
feature
  core: Knowledge_Query_Service

  query_safe(q: String): String
    require
      query_present: q /= ""
    do
      let raw: String := core.query(q)
      if raw = "UNAVAILABLE" then
        result := "DOC:CACHED-001"
      else
        result := raw
      end
    ensure
      non_empty: result /= ""
    end
end
```

By providing the `Knowledge_Query_Service` as the context, we ensure the AI understands the `require` and `ensure` expectations. The `Knowledge_Fallback_Wrapper` is then generated to respect those same expectations, preserving the system's trustworthiness.


## AI Workflow Across the Three Systems

In the **delivery system**, we might use an assistant to propose three different "fallback route" algorithms. We constrain the AI by providing the `Routing_Contract` and requiring that every proposal honor the "delivery window" invariant.

In the **knowledge engine**, we might ask the assistant to generate a new `Document_Parser` for a specialized file format. We constrain it by providing the `Parser_Interface` and a set of "adversarial" inputs it must handle safely.

In the **virtual world**, we might use an AI to draft a new set of "NPC interaction rules." We constrain it by providing the `Simulation_State` invariant, ensuring the AI doesn't generate rules that allow entities to enter illegal states.

In each case, the engineer’s role is to define the "sandbox" and the AI’s role is to explore the implementation within it.


## Three Ways AI Collaboration Fails

**Prompting without context.** Asking an AI to solve a problem without showing it the existing code is like asking a chef to cook in a kitchen they’ve never seen. The result will likely be incompatible with your environment. The remedy is to always include relevant interfaces, contracts, and a summary of your system's architecture in your prompts.

**Accepting the first draft.** AI output is a draft, not a final product. It often contains subtle "hallucinations" — calls to methods that don't exist or logic that almost, but not quite, satisfies the invariants. The remedy is to treat AI code with extreme skepticism until it has passed your full battery of contract checks and tests.

**Asking for too much at once.** Asking an AI to "redesign the entire dispatch system" is a recipe for a high-risk, unreviewable mess. The remedy is to scope your AI tasks to small, bounded changes — the same kind of slices you would use in a manual refactor.


::: {.note-exercise}
**Quick Exercise**

Pick a small feature you need to implement. Write an AI prompt that includes:
1.  The high-level goal.
2.  The existing Nex class and its contracts.
3.  Two explicit "non-negotiable" constraints.
4.  A requirement for a specific test case.

Compare the quality of the AI's output with what you might have gotten from a one-sentence prompt.
:::

::: {.note-takeaways}
**Takeaways**

- AI coding assistants are powerful implementation engines, but they require human-defined constraints to be effective.
- Prompting is not an art; it is an engineering discipline of defining goals, contexts, and boundaries.
- Contracts and interfaces are the essential "anchors" that keep AI-generated code aligned with your architecture.
- AI is best used for bounded implementation tasks, not for high-level architectural design.
- The faster an AI can write code, the more important your verification safety nets become.
:::



*The next chapter, `Reviewing AI-Generated Code`, moves from generation to validation: how to rigorously review AI-generated code to ensure it meets our standards for safety and correctness.*
