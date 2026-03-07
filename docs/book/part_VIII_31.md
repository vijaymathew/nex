# Managing Complexity

Part VII gave us the tools to make software trustworthy: contracts that make assumptions explicit, invariants that enforce object-level consistency, tests that provide evidence, and debugging that addresses causes. A system built with these tools is more reliable than one built without them. But reliability at a moment in time is not the same as reliability over time. As requirements grow, as teams expand, as integrations multiply, a system faces a different class of challenge — not whether the current behavior is correct, but whether the system can be understood and changed without destroying what is already correct.

This is the challenge of complexity, and it requires a different kind of engineering discipline.


## What Complexity Actually Costs

Complexity is not the same as size. A large system can be low-complexity if its pieces are well-bounded and independently understandable. A small system can be high-complexity if every piece is tangled with every other and no change is local. The cost of complexity is not the number of lines — it is the cost of reasoning about behavior.

That cost appears in specific, measurable ways. When a change to one module requires understanding the behavior of three others before the first can be safely modified, the cost of reasoning is high. When a new developer cannot make a confident change until they have spent weeks reading the codebase, the cost of onboarding is high. When a routine edit has a non-negligible probability of introducing an incident in an unrelated part of the system, the cost of change has exceeded what the system can afford.

Every system has something like a complexity budget. The budget is not a fixed quantity — it varies with team size, documentation quality, test coverage, and the experience of the developers involved. But when the budget is exceeded, the symptoms are reliable: changes become fragile, incidents follow routine edits, and the velocity of development slows not because the features are difficult but because the system is.

Managing complexity is not a refactoring task that can be deferred until convenient. It is an ongoing engineering practice — the accumulation of structural decisions that determine whether a system's complexity stays within its budget as it grows.


## Layered Reasoning

The most practical tool for managing complexity in a growing system is layered architecture: the organization of code into strata with defined responsibilities and a rule about the direction in which dependencies flow.

A three-layer model serves most systems well.

**The domain layer** contains the business rules, invariants, and core policies of the system. It is the layer that knows what the system is for — what a delivery task is, what transitions are legal, what the routing rules require, what makes a task eligible for rerouting. The domain layer does not know how data is stored, how messages are delivered, or how the user interface presents information. It knows the domain.

**The application layer** contains the orchestration of use cases — the workflows that accomplish goals by calling domain objects and coordinating their interactions. A dispatch workflow that computes a route, assigns it to a task, and signals notification is application layer logic. The application layer knows the domain layer's interfaces; it does not know the infrastructure layer's implementation details.

**The infrastructure layer** contains the adapters, transports, and storage implementations that connect the system to the outside world. It knows how to write a record to a database, how to send a message over a network, how to render a response for a client. It depends on the domain and application layers — not the reverse.

The rule that makes this structure useful is the direction of dependency: all dependencies point inward, toward the domain. Infrastructure depends on application, which depends on domain. The domain depends on nothing outside itself. This means the domain can be tested without infrastructure, reasoned about without knowing the deployment context, and changed without considering the impact on transport or storage implementations. It means that swapping one infrastructure implementation for another — replacing one database with a different one, adding a new notification channel — requires changing only the infrastructure layer. The domain and application layers are untouched.

When this rule is violated — when the domain layer imports an infrastructure detail, or when the application layer reaches directly into storage implementation — the benefit of layering disappears. The layers exist as a naming convention but not as a reasoning boundary.


## From Requirement to Layered Design

Consider the requirement:

> *"Add priority rerouting for premium tasks without breaking normal dispatch."*

Without layered structure, the rerouting decision — is this task eligible for priority treatment? — may be implemented wherever it is most convenient: in the adapter that receives the rerouting request, in the storage layer that retrieves the task, or scattered across several handlers that each check eligibility independently. When the eligibility rules change — when a new tier is added, when the conditions for rerouting are revised — every location that encodes the rule must be found and updated. The probability of an inconsistency is proportional to the number of locations.

With layered structure, the eligibility decision belongs in the domain layer. It is a business rule — a claim about what the system's policy is — and business rules belong in the layer that contains business rules. The application layer calls the domain's eligibility check and orchestrates the rerouting workflow based on the result. The infrastructure layer executes the storage and notification effects.

When the eligibility rules change, the domain layer changes. The application layer changes only if the workflow structure changes. The infrastructure layer does not change. The impact of the requirement change is contained to the layer that owns the relevant concern.


## A Layered Design in Code

```nex
class Task
feature
  task_id: String
  tier: String
  status: String
invariant
  id_present: task_id /= ""
  valid_tier: tier = "STANDARD" or tier = "PREMIUM"
end

class Reroute_Policy
feature
  should_reroute(t: Task): Boolean
    require
      task_present: t.task_id /= ""
    do
      result := t.tier = "PREMIUM" and t.status = "IN_TRANSIT"
    ensure
      bool_result: result = true or result = false
    end
end

class Dispatch_App_Service
feature
  policy: Reroute_Policy

  process_reroute(t: Task): String
    require
      task_present: t.task_id /= ""
    do
      if policy.should_reroute(t) then
        result := "REROUTE_TRIGGERED"
      else
        result := "NO_REROUTE"
      end
    ensure
      known_result: result = "REROUTE_TRIGGERED" or result = "NO_REROUTE"
    end
end
```

`Task` and `Reroute_Policy` are domain layer. `Dispatch_App_Service` is application layer. There is no infrastructure layer in this sketch — no storage, no notification, no transport — and that absence is intentional. The domain and application layers contain the logic that determines what the system does. The infrastructure layer, when added, contains the code that executes it. The separation is visible in what each layer imports: `Dispatch_App_Service` depends on `Reroute_Policy` and `Task`; nothing here depends on a database or a message queue.

`Reroute_Policy.should_reroute` encodes the eligibility rule: a task must be `PREMIUM` tier and `IN_TRANSIT` status to qualify for rerouting. When this rule changes — when a new tier is introduced, when rerouting becomes available to `STANDARD` tasks above a certain age — this is the single location that must change. The application service `process_reroute` does not encode the rule; it calls the policy and acts on the result. A developer reading `process_reroute` can understand the workflow without understanding the policy logic, and a developer changing the policy logic can do so without reading the workflow.

The invariant on `Task` — `valid_tier: tier = "STANDARD" or tier = "PREMIUM"` — is a domain constraint. It asserts that the tier field always holds one of the two declared values. When a new tier is introduced, this invariant must be updated, which forces the developer to consider every piece of code that depends on the tier field being one of the current values. The invariant is a change-impact prompt as well as a correctness check.


## Complexity in the Three Systems

In the delivery system, the dispatch logic is the domain. Route computation, task state transitions, and rerouting eligibility are domain concerns. The scheduler that triggers dispatch workflows and the notification system that delivers updates are infrastructure. When dispatch logic leaks into the scheduler or the notification handler, changes to dispatch policy require reading and potentially modifying infrastructure code — code that exists to execute effects, not to encode decisions.

In the knowledge engine, document retrieval and relevance ranking are domain concerns. The index data structure is infrastructure. When ranking logic leaks into the index implementation — when the decision of which documents are relevant is made inside the code that retrieves them — ranking policy and storage implementation are tangled. Changing the ranking algorithm requires understanding the index, and changing the index requires understanding the ranking algorithm.

In the virtual world, the rules that govern entity transitions and interaction outcomes are domain concerns. The collision detection algorithm is application logic. The rendering pipeline is infrastructure. When update logic is interwoven with rendering — when the decision of what happens at a tick is made inside the code that draws the result — neither can be tested independently, and changing the rendering format requires understanding the simulation logic.

In all three systems, the complexity problems are the same in form: a decision that should be localized to one layer has been implemented in another, and the two concerns are tangled enough that neither can change without involving the other.


## Three Ways Complexity Management Fails

**Layer bypasses.** Code in the infrastructure or interface layer that modifies domain state directly — without going through the domain object's defined operations — bypasses the domain's invariants and contracts. The domain's guarantees are no longer reliable because they can be violated by code outside the domain. Debugging requires not only reading domain code but reading every piece of infrastructure code that might touch domain state. The remedy is to route all state changes through the domain layer's operations, which means the infrastructure layer must call into the application layer, which calls into the domain.

**The shared utility dumping ground.** A module that accumulates unrelated logic — because each piece was too small to warrant its own module and the existing one was the most convenient destination — becomes a module without coherent identity or stable interface. It has no single reason to change and no single owner. Changes to it require understanding everything it does, and developers avoid moving logic out of it because the refactoring cost is high. The remedy is to split by domain responsibility rather than by convenience: each module should have a coherent identity that its name accurately reflects.

**Cyclic dependencies.** When module A depends on module B and module B depends on module A, neither can be changed, compiled, or deployed without the other. The cycle is an architectural symptom: the two modules have not been separated at a semantically coherent boundary, and the dependency structure reflects tangled responsibilities rather than intentional design. The remedy is to extract an interface or shared abstraction that both modules depend on, breaking the cycle by introducing a dependency that points in one direction only.


## Quick Exercise

Choose one subsystem in your system and map its current module structure with four parts: the modules and their declared responsibilities, the dependencies between them and the direction each flows, one dependency that points in the wrong direction — from application toward domain is correct; from domain toward infrastructure is not — and one interface or abstraction that, if introduced, would break the incorrect dependency and correct the flow.

For each module, ask: does this module's behavior need to change when domain rules change, when the orchestration workflow changes, or when the infrastructure implementation changes? A module that would need to change for all three reasons is a module whose responsibilities span more than one layer.


## Takeaways

- Complexity is the cost of reasoning about behavior, not the count of lines or modules. A large system can be low-complexity; a small system can be high-complexity. The difference is the quality of the boundaries.
- Every system has a complexity budget. When it is exceeded, fragile changes, long onboarding, and rising incident rates follow. Managing complexity is not a deferred task — it is an ongoing structural practice.
- Layered architecture contains change impact: domain rules change in the domain layer, orchestration changes in the application layer, infrastructure changes in the infrastructure layer. Each layer is comprehensible in isolation because it depends only on what is inward of it.
- Dependencies must flow inward. A domain layer that imports infrastructure, or an application layer that bypasses the domain, destroys the separation that makes layering useful.
- The three failure modes — layer bypasses, utility dumping grounds, and cyclic dependencies — all have the same root cause: responsibilities that were not assigned to a coherent layer before the code was written.


*Chapter 32 examines how to design boundaries that absorb future change safely — the discipline of identifying the seams at which a system is most likely to change and structuring those seams so that changes on one side do not force changes on the other.*
