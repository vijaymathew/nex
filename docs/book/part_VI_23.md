# From Algorithms to Components

Part V gave us a toolkit: precise definitions of algorithms, decomposition discipline, recursive design, cost reasoning, and the core algorithm families that operate on organized data. Each of these tools was developed in relative isolation — an algorithm defined, a data structure chosen, a traversal specified. Part VI asks what it takes to assemble these pieces into software that holds together over time.

An algorithm can be correct and efficient and still fail in production. The failure is not in the algorithm itself but in how it is embedded in the surrounding system. When algorithms are not bounded by clear component interfaces, the system becomes a surface where every change is globally visible and every change carries global risk. A modification to the route computation logic must not require understanding the notification delivery code. A change to the task state model must not require re-reading the ranking algorithm. The discipline that makes these separations possible is component design.


## What a Component Is

A component is a unit of software with a defined boundary: a set of responsibilities it owns, a contract it exposes to callers, and an interior it is entitled to change without requiring callers to change. The boundary is not a line in a directory structure or a class in a file — it is a semantic boundary, defined by what the component is responsible for and what it chooses to reveal.

Two properties define a well-designed component boundary.

**High cohesion** means that everything inside the component belongs together — that the responsibilities it owns are related to a single, nameable concern. A component that computes routes and also formats user notifications and also manages database connections has three different reasons to change. When any one of them changes, the entire component must be understood and tested. High cohesion is what makes a component understandable in isolation: it does one thing, and everything inside it serves that one thing.

**Low coupling** means that the component's dependencies on other components are minimal and flow in a consistent direction. A route computation component that depends directly on the implementation details of a notification component cannot be tested, replaced, or evolved without involving the notification component. Low coupling is what makes a component changeable in isolation: the contract at the boundary is what the rest of the system depends on, and the implementation behind the contract can be modified freely.

These two properties reinforce each other. A component with high cohesion is easier to give a stable contract, because its responsibilities are clear. A component with a stable contract is easier to keep loosely coupled, because callers depend on the contract rather than on the implementation.


## Boundaries and Their Direction

When algorithms are assembled into a system, their natural organization suggests the component boundaries. The question to ask of any proposed boundary is not whether it is convenient but whether it groups a coherent responsibility and separates it from a different one.

For the delivery system, a requirement like "compute the best route and notify clients when it changes" contains three distinct concerns. Route computation is a pure algorithmic concern: given a graph and a pair of locations, produce an optimal path or report failure. Task state management is an entity-level concern from Chapter 7: what are the legal states of a delivery task, and what transitions are permitted? Notification delivery is a side-effect concern: given a message and a recipient, deliver the message. These three concerns have different reasons to change — the route algorithm might be replaced by a more efficient one, the task state model might acquire new statuses, the notification channel might change from push to pull — and their changes are independent. They belong in separate components.

The direction of dependency matters as much as the separation itself. The route computation component should not depend on the notification component — computing a route does not require knowing how notifications are delivered, and introducing that dependency would mean that changing the notification system requires reconsidering the route algorithm. The correct direction is for both to depend on a coordinator that orchestrates their interaction, while neither depends on the other directly.

This principle — that core domain logic should not depend on delivery, persistence, or presentation concerns — appears under different names in different design traditions, but its content is always the same: the direction of dependency should follow the direction of abstraction, from the concrete and changeable toward the stable and abstract.


## From Requirement to Component Design

Consider the requirement:

> *"Compute the best route and notify clients when the route changes."*

A naive implementation of this requirement places all its logic in a single service: compute the route, update the task, send the notification. This is not a component design — it is a procedure with multiple responsibilities. When the route algorithm changes, the notification logic must be read and understood to ensure the change does not affect it. When the notification channel changes, the route algorithm must be read for the same reason. Every change carries global cognitive cost.

A componentized design separates the concerns:

**`Route_Component`** owns the route computation algorithm and the rules that govern valid routes. Its contract is: given a start location and an end location, return a valid path or report that none exists. It knows nothing about tasks, notifications, or persistence.

**`Task_Component`** owns the task state model from Chapter 7: the legal states, the permitted transitions, and the invariants that must hold. Its contract is the set of transition operations defined in that model. It knows nothing about route computation or notification.

**`Notify_Component`** owns message delivery. Its contract is: given a task identifier and a message, deliver the message and return a status. It knows nothing about routes or tasks.

**`Delivery_Coordinator`** orchestrates the workflow. It calls `Route_Component` to compute a route, calls `Task_Component` to update task state, calls `Notify_Component` to send the notification, and handles the cases where any of these steps fails. It contains no algorithm logic and no domain rules — only the sequence of calls and the logic for handling their outcomes.

This separation means that the route algorithm can be replaced — upgraded from BFS to Dijkstra, or tuned for a new cost model — without touching the notification or task components. A failure in notification can be diagnosed without reading route computation code. Each component can be tested in isolation by providing a controlled implementation of its dependencies.


## A Component Design in Code

```nex
class Route_Component
feature
  compute(start_loc, end_loc: String): String
    require
      inputs_present: start_loc /= "" and end_loc /= ""
    do
      if start_loc = end_loc then
        result := start_loc
      elseif start_loc = "A" and end_loc = "C" then
        result := "A->B->C"
      else
        result := "UNREACHABLE"
      end
    ensure
      result_present: result /= ""
    end
end

class Notify_Component
feature
  send(task_id, message: String): String
    require
      inputs_present: task_id /= "" and message /= ""
    do
      result := "SENT"
    ensure
      status_known: result = "SENT" or result = "FAILED"
    end
end

class Delivery_Coordinator
create
  make(route: Route_Component, notify: Notify_Component) do
    this.route := route
    this.notify := notify
  end
feature
  route: Route_Component
  notify: Notify_Component

  reroute_and_notify(task_id, start_loc, end_loc: String): String
    require
      inputs_present:
        task_id /= "" and
        start_loc /= "" and
        end_loc /= ""
    do
      let p: String := route.compute(start_loc, end_loc)
      if p = "UNREACHABLE" then
        result := "NO_ROUTE"
      else
        result := notify.send(task_id, "route=" + p)
      end
    ensure
      known_result:
        result = "NO_ROUTE" or
        result = "SENT" or
        result = "FAILED"
    end
end
```

Read this sketch against the component design above. `Route_Component` and `Notify_Component` have no knowledge of each other — neither references the other's types or operations. `Delivery_Coordinator` holds references to both and calls them in sequence, but contains no route logic and no notification logic. The coordinator's contract — `reroute_and_notify` returns one of three declared values — is derivable from the contracts of its components: `NO_ROUTE` comes from `Route_Component`, `SENT` and `FAILED` come from `Notify_Component`.

This derivability is the signature of a well-designed coordinator. Its output contract is not an independent specification — it is a composition of the contracts of the components it calls. When `Route_Component`'s contract is understood and `Notify_Component`'s contract is understood, `Delivery_Coordinator`'s contract follows without additional information. A coordinator whose contract cannot be derived from its components' contracts is a coordinator that has absorbed logic it should have delegated.


## Components in the Three Systems

In the delivery system, route computation, task state management, and notification delivery are three components. A fourth component handles persistence — storing task state and route history — and a coordinator orchestrates the workflow. Each component is separately testable: route computation can be tested against a graph without involving notifications; notification delivery can be tested with mock messages without involving route computation.

In the knowledge engine, document retrieval, relevance ranking, and result rendering are three components. Retrieval takes a query and returns candidate documents. Ranking takes candidates and returns an ordered list. Rendering takes an ordered list and returns the representation the client receives. Each stage has a defined input and output, and each can be replaced or adjusted independently — a new ranking algorithm does not require rewriting retrieval, and a new rendering format does not require understanding the ranking logic.

In the virtual world, simulation logic, collision detection, and event output are three components. The simulation updates entity states each tick. Collision detection identifies entity pairs that overlap. Event output records the results of interactions for downstream processing. The separation matters because collision detection is computationally expensive and may need to be optimized independently of the simulation logic that drives entity movement.

In all three systems, the component boundaries follow the same principle: a component owns one concern, exposes a stable contract, and depends on the contracts of other components rather than their implementations.


## Three Ways Component Design Fails

**The utility blob.** A component that accumulates unrelated responsibilities over time — because each new feature was added to the most convenient existing module — becomes a module with no coherent identity and no stable contract. Its responsibilities are tangled, its changes are unpredictable, and testing it requires understanding everything it does. The remedy is to ask of each proposed addition: does this belong to this component's reason to change, or to a different one? If a different one, it belongs in a different component — even if creating that component requires more work than adding to the existing one.

**Leaky boundaries.** A component whose callers depend on its internal data structures, implementation choices, or intermediate steps rather than its declared contract has a boundary in name only. The contract is the component's commitment to its callers; internal details are not part of the commitment and must not be treated as such. Callers that reach through a component's boundary to access its internals are coupled to the implementation, not the contract, and will break when the implementation changes. The remedy is to design contracts that expose only what callers actually need and to resist the pressure to expose more for the sake of short-term convenience.

**Dependency direction inversions.** When a core domain component depends on a delivery, persistence, or presentation component — when the route algorithm imports the notification client, or when the ranking model directly queries the database — the domain is coupled to infrastructure. Testing the domain requires the infrastructure. Changing the infrastructure requires the domain. The correct direction is for infrastructure to depend on domain contracts, not the reverse. This inversion may require introducing an interface or adapter, but the cost of that indirection is small compared to the cost of domain code that cannot be tested or evolved independently of the systems it is deployed alongside.


## Quick Exercise

Choose one workflow in your system that currently mixes more than one concern — a function or module that computes, updates state, and produces output — and redesign it as a set of components with four parts: the core algorithm component and its contract, the state component and its contract, the side-effect component and its contract, and the coordinator that orchestrates them.

For each component, write the precondition and postcondition of its primary operation. Then identify one place in the current implementation where a dependency flows in the wrong direction — where a domain concern depends on an infrastructure detail — and describe what would need to change to invert it.


## Takeaways

- A component is a unit with a defined responsibility, a stable contract, and an interior it can change without requiring callers to change. The boundary is semantic, not syntactic.
- High cohesion and low coupling are not style preferences. They are the properties that determine whether a component can be understood, tested, and changed in isolation.
- A coordinator's contract should be derivable from the contracts of the components it orchestrates. A coordinator that has absorbed logic it should have delegated has compromised its own testability and clarity.
- Dependency direction matters as much as component separation. Domain logic must not depend on delivery, persistence, or presentation details. The direction of dependency should follow the direction of abstraction.
- Component boundaries make algorithmic quality into software quality. A correct algorithm embedded in a tangled system is not a reliable system.


*Chapter 24 examines functional thinking as a strategy for designing components that are composable and testable by construction — components whose behavior can be understood and verified from their types and contracts alone, without reference to shared state or execution order.*
