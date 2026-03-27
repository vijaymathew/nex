# Why Software Needs Models

Part I taught us to write better problem statements. Given a system to build, we can now describe its inputs, its required behavior, and its constraints with enough precision that implementation can begin. That is genuine progress.

It is not enough.

Teams that move directly from specification to code — even from a good specification — reliably produce systems that are brittle. The code compiles. The tests pass. The demo looks right. Then scale arrives, or requirements change, or two features interact in a way nobody anticipated, and the system begins to crack. New fixes introduce new inconsistencies. The codebase becomes a map of past decisions that nobody fully remembers making.

The missing layer is a **model**.


## What a Model Is

A model is not code. It is the structured account of what exists in a system, how those things relate to one another, and what rules govern how they can change.

The distinction matters. Code answers the question: *how does the machine execute this?* A model answers a different question: *what is the system actually about?*

Consider what happens without one. Two teams, working from the same specification, represent the same concept in subtly different ways. Edge behavior ends up depending on call order rather than on explicit rules. Performance degrades because nobody thought carefully about how data would actually be accessed. Every new feature lands as a local patch, adding complexity without adding clarity.

A model prevents this by forcing structure to become explicit before implementation decisions harden. It sits between specification and code as a third, distinct layer:

- The **specification** answers: *what must happen?*
- The **model** answers: *what exists, and how can it change?*
- The **algorithms** answer: *how do we compute efficiently within that model?*

When the middle layer is weak or absent, the other two layers drift apart. The specification describes a system that the code does not quite implement. The relationship can be found only by reading the code very carefully — if it can be found at all.


## The Same Structure in Three Domains

Our three running systems look very different on the surface. Modeling them reveals that the same underlying structure appears in all three — and that the natural first model for each is too narrow.

**The delivery network.** A first pass might model only "find a route from A to B." This captures the nominal case and nothing else. A stronger model includes locations, the connections between them, the dynamic state of each connection (open or blocked), and the robot's current position and assigned task. With this model, route selection becomes a query against explicit state rather than a hardcoded behavior. Blocking a corridor is a state change; replanning is a response to that change. The system becomes something we can reason about.

**The knowledge engine.** A first pass might model only "query string maps to result list." Quality degrades quickly under this model because there is no representation of what quality depends on. A stronger model includes documents, the terms and tags that characterize them, relevance signals, and a representation of confidence or uncertainty. With this model, ranking becomes a computation over explicit structure — one that can be adjusted, debugged, and improved.

**The virtual world.** A first pass might model only "update all objects each frame." Interactions become chaotic because there is no account of what governs them. A stronger model includes entity identity, entity state, typed interaction rules, and the boundaries of each update step. With this model, correctness can be reasoned about one transition at a time, rather than observed at runtime and hoped for.

The domains differ. The modeling move is the same: name stable structure before writing algorithms over it.


## From Ambiguous Requirement to Model

The path from a vague requirement to a working model has a shape. Walking through it concretely is more useful than describing it in the abstract.

Start with this requirement:

> *"The robot should choose good routes and avoid getting stuck."*

This sentence is useful in a product conversation. It is nearly useless for implementation — "good" and "stuck" carry no technical meaning. The five-step path below converts it into something buildable.

**Step 1: Separate nouns from verbs.** Nouns suggest entities that need to be modeled. Verbs suggest operations that act on them. From the requirement above: nouns include robot, route, location, path, blockage; verbs include choose, avoid, move, re-plan. This separation is the first act of modeling.

**Step 2: Define state, not just behavior.** For each noun, ask what properties it has that can change over time. A minimal state model for the robot system:

- `Location`: an identifier
- `Path`: a source, a destination, a status
- `Robot`: a current location and a destination
- `Map`: a set of locations and paths

This is still small. It is already more useful than the original sentence.

**Step 3: Add invariants early.** Before writing any algorithm, write down the truths that must hold at all times. Examples for this system: every path endpoint must reference a known location; the robot's current location must exist in the map; any returned route must use only open paths. These invariants are not implementation details. They are the system's reliability backbone, and they belong in the model.

**Step 4: Make change explicit.** Most real failures happen during transitions, not in static state. What transitions are possible? A path becomes blocked. The robot receives a new destination. A replan request either succeeds or fails. A model that does not represent transitions will behave correctly in demos and incorrectly in production.

**Step 5: Delay algorithm commitment.** At this stage, do not choose between BFS, Dijkstra, A*, or any other pathfinding strategy. The right algorithm depends on access patterns and performance requirements that the model has not yet fully revealed. Choosing early is choosing blind.


## A Model in Code

The following sketch shows how this model might be expressed in Nex. The point is not the specific syntax — it is the correspondence between the model we described and the structure of the code.

```nex
class Path
feature
  from_loc: String
  to_loc: String
  open: Boolean

create
  make(from_loc, to_loc: String) do
    this.from_loc := from_loc
	this.to_loc := to_loc
	open := true
  end

invariant
  endpoints_present: from_loc /= "" and to_loc /= ""
end

class Robot_State
feature
  current: String
  destination: String

create
  make(current, destination: String) do
    this.current := current
	this.destination := destination
  end

feature
  request_replan(has_open_route: Boolean): String
    require
      current_known: current /= ""
      destination_known: destination /= ""
    do
      if current = destination then
        result := "ARRIVED"
      elseif has_open_route then
        result := "ROUTE_FOUND"
      else
        result := "UNREACHABLE"
      end
    ensure
      status_defined: result = "ARRIVED" 
	                  or result = "ROUTE_FOUND" 
					  or result = "UNREACHABLE"
    end
invariant
  state_valid: current /= "" and destination /= ""
end
```

This sketch is intentionally small. What it does is more important than what it omits: it defines state explicitly, expresses behavior in terms of that state, and encodes guarantees as verifiable contracts. The pathfinding algorithm is not here yet. It can be added later, in any form, without disturbing the model. The same approach applies to the knowledge engine and the virtual world: model first, optimize second.


## Five Ways Modeling Goes Wrong

Knowing the pattern does not prevent all mistakes. The following five failures appear regularly enough to be worth naming.

**Modeling operations before entities.** The symptom is a codebase full of utility functions with no clear home — data scattered across modules, ownership unclear. The recovery is to list stable entities first and attach operations to explicit state.

**Ignoring boundaries.** The symptom is unclear borders between subsystems, with the same concept represented differently in different places. The recovery is to define model boundaries in writing and make every conversion point explicit.

**Missing invariants.** The symptom is bugs described as "impossible states" — situations the code was never designed to handle because nobody wrote down what was always supposed to be true. The recovery is to express invariants at the class and operation level, and to fail fast when they are violated.

**Premature algorithm lock-in.** The symptom is an architecture that cannot adapt because an early performance guess became a structural assumption. The recovery is to keep the model stable while treating the algorithm as a replaceable component.

**Treating the model as documentation only.** The symptom is diagrams that exist and code that disagrees with them. A model that lives only in a design document is not enforced by anything. The recovery is to encode model assumptions in types, contracts, and tests — and to keep them synchronized with the implementation.


::: {.note-exercise}
**Quick Exercise**

Choose one of the three running systems and produce a minimal model draft with four parts:

1. **Entities** — three to six things the system needs to track
2. **Relationships** — how those entities connect to one another
3. **Invariants** — two or three rules that must always hold
4. **Transitions** — two or three important ways the state can change

Then ask two questions about your current implementation of that system: which parts of the implementation are not represented in this model? And which parts of the model are not currently enforced in code?

The gap between those two answers is where reliability work should start.
:::

::: {.note-takeaways}
**Takeaways**

- A model is the missing layer between specification and implementation: it describes what exists and how it can change, independently of how the machine executes it.
- Stable entities, explicit boundaries, invariants, and transitions are the core elements of any model.
- The right algorithm depends on the model. Choose algorithms after the model is clear, not before.
- Most failures at scale are model failures before they are code failures.
- A model that exists only in documentation is not enforced. Encode model assumptions in types, contracts, and tests.
:::



*Chapter 7 develops the first modeling primitive in depth: entities. We will distinguish identity from state, assign responsibilities, and choose representations that remain stable as the system grows.*
