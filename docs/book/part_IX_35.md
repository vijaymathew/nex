# Reviewing AI-Generated Code

The power of an AI assistant is its ability to generate code at a speed that exceeds human writing. But in software engineering, writing code is only a fraction of the work. The more important work is ensuring that the code is safe, correct, and fits the architecture. In an AI-assisted workflow, the burden of this responsibility shifts from *authoring* to *reviewing*.

A common mistake in AI workflows is treating the output as "pre-verified." Because the code often looks clean, follows style guides, and compiles on the first try, there is a temptation to give it a superficial review. But AI-generated code can be subtly wrong in ways that manual code is not. It can satisfy the happy path while ignoring the edge cases, or it can introduce hidden couplings that only become apparent during a production incident. Review is the single most important line of defense.


## What to Review First: The Risk-First Hierarchy

When reviewing AI-generated code, don't start with the syntax or the variable names. Start with the behavior. A high-quality review follows a hierarchy of risk:

1.  **Contract Correctness:** Does the code satisfy the `require` and `ensure` conditions of its own class and every class it calls? This is the most critical check.
2.  **Invariant Preservation:** Does the code maintain the object-level and system-level invariants? AI is notorious for modifying state in ways that bypass the rules of the domain.
3.  **Failure Path Handling:** Does the code handle transient failures, timeouts, and invalid inputs? AI tends to be optimistic. You must be the pessimist.
4.  **Behavioral Regression:** Does the new code change the observable behavior of existing functions in unintended ways?
5.  **Maintainability and Style:** Only once the code is proven safe and correct do you worry about whether the code is "idiomatic."

If the code fails any of the first four checks, the fifth check doesn't matter. You shouldn't polish code that is behaviorally broken.


## The AI Review Checklist

To make review a routine discipline, use a focused checklist for every AI-generated patch:

- [ ] **Contract Alignment:** Does it honor the existing interfaces?
- [ ] **State Safety:** Are all state transitions legal and through defined operations?
- [ ] **Failure Resilience:** What happens if the network fails or the database is down? Is there retry logic, and is it bounded?
- [ ] **Hidden Coupling:** Does it import a class it shouldn't, or depend on an implementation detail?
- [ ] **Validation Evidence:** Did the AI also provide the tests that prove its work? Have you run them yourself?


## From Draft to Verified Implementation

Consider a requirement:
> *"The AI was asked to add retry logic to the delivery dispatch port."*

The AI provides a loop that retries on failure. A naive reviewer sees "it retries" and approves. A rigorous reviewer asks the following:

1.  **Is the retry bounded?** (If not, we could have an infinite loop).
2.  **Does it retry on *every* error?** (We should only retry on transient network errors, not on permanent domain errors like "invalid task ID").
3.  **Is the operation idempotent?** (If the first request actually reached the server but the response was lost, does the retry cause a duplicate delivery?)
4.  **Is the invariant preserved?** (Does the retry loop keep the task in the "DISPATCHING" state until it succeeds or finally fails?)

By asking these questions, you move from "it looks like it works" to "I have proven it is safe."


## Implementation in Nex

In Nex, our explicit failure paths and contract checks provide the perfect framework for this kind of review.

```nex
-- The AI-generated proposal for a dispatch port with retry
class Dispatch_Port
feature
  send(task_id: String): String
    require
      id_present: task_id /= ""
    do
      result := "SENT"
    ensure
      known_result:
        result = "SENT" or
        result = "TRANSIENT_FAILURE" or
        result = "PERMANENT_FAILURE"
    end
end

class Dispatch_With_Retry
create
  make(port: Dispatch_Port) do
    this.port := port
  end
feature
  port: Dispatch_Port

  send_task(task_id: String, max_attempts: Integer): String
    require
      id_present: task_id /= ""
      attempts_valid: max_attempts >= 1
    do
      let attempt: Integer := 1
      let status: String := "TRANSIENT_FAILURE"

      from
      until attempt > max_attempts or
            status = "SENT" or
            status = "PERMANENT_FAILURE" do
        status := port.send(task_id)
        attempt := attempt + 1
      end

      result := status
    ensure
      known_result:
        result = "SENT" or
        result = "TRANSIENT_FAILURE" or
        result = "PERMANENT_FAILURE"
    end
end
```

A reviewer looking at this Nex implementation can immediately see the logic's boundaries. They can see that the loop is bounded by `max_attempts`. They can see that it stops correctly on a `PERMANENT_FAILURE`. The `ensure` clause makes the expected outcomes explicit. The review task is transformed from "understanding the code" to "validating the contract."


## AI Review Across the Three Systems

In the **delivery system**, review focuses on state transition legality. Did the AI-generated code allow a task to skip a mandatory "verification" step?

In the **knowledge engine**, review focuses on ranking correctness. Did the AI's "optimized" ranking algorithm accidentally exclude documents that were highly relevant but didn't match a new heuristic?

In the **virtual world**, review focuses on determinism. Did the AI introduce a non-deterministic random number call into a simulation loop that must remain reproducible?

In each case, the reviewer is the guardian of the system's core principles.


## Three Ways AI Review Fails

**Style-First Review.** Spending 15 minutes debating a variable name while missing a fatal race condition is the most common review failure. The remedy is to follow the hierarchy of risk: contracts first, style last.

**No Regression Focus.** Assuming that because the new feature works, the old features still work. The remedy is to compare the observable behavior of the system before and after the patch, ideally using automated parity tests.

**The "Rubber Stamp" Problem.** Approving an AI-generated patch because you are in a hurry. The remedy is to acknowledge that AI code is "guilty until proven innocent." If you don't have time to review it properly, you don't have time to merge it.


## Quick Exercise

Take one recent AI-generated patch (even a small one) and apply the Risk-First Hierarchy:
1.  Check the contract (inputs/outputs).
2.  Check the invariants (state safety).
3.  Check the failure paths (what could go wrong?).
4.  Check for hidden coupling.

Did you find anything that a "style-only" review would have missed?


## Takeaways

- AI-generated code requires a more rigorous review than human code because its failure modes are often more subtle.
- Use a Risk-First Hierarchy: contracts and invariants are the top priority.
- Reviewing is not reading; it is validating behavior against expectations.
- A review checklist is the best tool for maintaining consistency and catching common AI optimistic biases.
- Review quality is the ultimate throttle on the speed of AI-assisted development.


*Chapter 36 closes the book with the final piece of the puzzle: the role of human judgment and accountability in a world where AI is doing more and more of the work.*
