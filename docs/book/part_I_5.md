# From Stories to Specifications

Every system in this book began as a story. A robot navigates a building to deliver packages. A knowledge engine answers questions by searching a document collection. A virtual world simulates the interactions of objects governed by rules. These stories are genuinely useful — they communicate intent, establish context, and give purpose to the engineering work that follows. Without them, we would not know what we were building or why.

But a story cannot be executed. A computer does not understand intent or purpose. It executes precise rules operating on precisely defined inputs, producing precisely defined outputs. The task that bridges these two things — the story that communicates what the system should do and the rules that a machine can follow — is specification: the transformation of a narrative into a formal description of behavior.

This transformation is where software engineering begins. Not in the choice of algorithm, not in the selection of data structure, but in the earlier and harder work of asking: what, precisely, is the system supposed to do?


## What Specification Requires

A story describes events and intentions. A specification describes rules and behaviors. The difference is precision — not as a stylistic preference but as a requirement imposed by the nature of computation. Anything left vague in a specification will be resolved by the implementation, which means it will be resolved by whoever writes the code, without necessarily consulting the people who understood the purpose of the system.

Consider the story:

> *The robot should navigate through the building.*

This sentence communicates an intention clearly enough for a human reader. For a programmer, it raises questions that the sentence does not answer: what information does the robot have about the building? What counts as navigation? What does the system guarantee about the route it produces? Until these questions are answered, the programmer cannot write code — they can only guess, and the guesses will differ from one programmer to the next.

A specification answering these questions might say:

> *The robot receives a map of locations connected by traversable paths, a current location, and a destination. The system must compute a sequence of locations from the current location to the destination, using only traversable connections, or report that no such route exists.*

Every word in this specification does work. "Map of locations connected by traversable paths" defines the input structure. "Current location and destination" names the other inputs. "Sequence of locations" defines the output structure. "Using only traversable connections" states a correctness condition. "Or report that no such route exists" declares the failure behavior. The story introduced the idea; the specification introduced formal elements we can reason about and, eventually, test against.



## Inputs: What the System Receives

Every computational problem begins with inputs — the information available to the system when it must produce a result. Identifying the inputs precisely is the first concrete step of specification work, because an algorithm cannot operate on information it does not have, and an algorithm that assumes information that will not always be available will fail on the inputs where that information is absent.

For the delivery system, the inputs to the navigation problem include the map of the building, the robot's current location, the destination, and the current state of each path — whether it is traversable or blocked. Each of these is a separate input with a separate type. The map is a structure. The locations are identifiers. The path states are properties of edges. Writing them down separately forces a question that the story left implicit: what is the format of each, and what constraints must each satisfy to be valid?

For the knowledge engine, the inputs to a search operation include the query — the expression of what the user is looking for — the document collection, and whatever history or context is relevant to the ranking. The query is a string, but a string with constraints: it must be non-empty, and its format must be one the system knows how to process. The document collection has its own structure. History, if it is used, must be defined: what exactly is recorded, in what form, and how far back?

For the virtual world, the inputs to a simulation step include the current state of all objects — their positions, their types, their current status — and the interaction rules that govern what happens when objects of certain types encounter each other. The rules are themselves a kind of input: they define the system's behavior, and they can change.

In each case, the act of listing the inputs is also an act of discovery. Questions that the story left unanswered must be answered before the list can be completed.



## Outputs: What the System Must Produce

If inputs describe what enters the system, outputs describe what the system must deliver. Defining the output precisely is the second step of specification work, because the output definition is where the system's success condition lives — it is what we check when we ask whether the system worked correctly.

For the navigation problem, the output is a route: a sequence of locations from the start to the destination. But "a sequence of locations" is not yet precise enough. The sequence must use only traversable connections — a sequence that teleports from one location to another is not a valid route. The first element must be the start location and the last must be the destination. And if no valid route exists, the output must say so explicitly, in a form that the caller can distinguish from a valid route.

Each of these constraints is a separate claim about the output, and each must be stated. An output specification that says only "a sequence of locations" permits outputs that would fail for the caller who depends on the first element being the start, or the caller who cannot distinguish an empty route from a failure.

For the knowledge engine, the output of a search is a ranked list of document identifiers. The ranking is ordered by relevance — but relevance must itself be defined. The output specification must state what criterion was used to order the results, so that callers can interpret the ordering correctly and tests can verify that the ordering satisfies the criterion.



## Guarantees: What the System Promises

Beyond inputs and outputs, a specification states guarantees — the promises that the system makes about what it will do for any input satisfying the preconditions. Guarantees are what define correctness. Without a stated guarantee, we cannot say whether the system behaved correctly, because we have not said what correct behavior is.

For the navigation problem:

> *If a path exists between the start and destination, the system returns a sequence of traversable connections from start to destination. If no path exists, the system returns a declared failure status.*

For the knowledge engine:

> *The system returns a ranked list of documents from the collection, ordered by estimated relevance to the query. A query that matches no documents returns an explicit empty result.*

For the virtual world:

> *After each simulation step, every object satisfies the invariants for its type. Objects interact only according to the rules defined for their type pairing.*

Each guarantee is a testable claim. A system that returns a sequence with a non-traversable connection has violated the navigation guarantee. A system that returns null instead of an empty result has violated the knowledge engine guarantee. The test does not need to know how the system works — it only needs the guarantee to know what to check.



## The Ambiguity That Stories Leave

Stories leave decisions unmade. This is not a deficiency — it is a feature of narrative that makes stories easy to understand and share. But every unmade decision in a story becomes a question that the implementation must answer, and when the implementation answers questions that the specification should have answered, the result is software whose behavior depends on implementation choices that were never reviewed, agreed upon, or tested.

The word "best" is the clearest example. The story says the robot should take the best path. The specification must decide what best means: shortest total distance, fewest connections, least time, least energy, greatest reliability. These are different objectives and they lead to different routes on most graphs. A specification that inherits "best" from the story without resolving it has deferred the decision to the implementation, where it will be resolved once, quietly, and almost certainly without the knowledge of the people who depend on the system to optimize the right thing.

"Relevant" in the knowledge engine story has the same character. "Realistic" in the virtual world story has the same character. Any adjective in a story that carries evaluative weight — best, relevant, realistic, efficient, appropriate — must be replaced in the specification with a definition: the specific criterion by which the adjective will be evaluated and the specific method by which it will be computed.

Making these decisions explicit is not bureaucratic overhead. It is the work that prevents the system from silently optimizing the wrong objective and the team from discovering the disagreement only when it becomes a production incident.



## A First Look at Contracts

As the systems in this book grow more complex, specifications will evolve into contracts — formal statements of the agreement between different parts of the system about what each part requires and what each part guarantees. Contracts are specifications made executable: they can be checked at runtime, violated visibly, and used as the basis for tests.

A contract for the navigation function captures three things. The precondition states what must be true before the function is called: the map correctly represents the connections between locations, the start and destination are locations that exist in the map. The postcondition states what the function guarantees when it returns: if a path exists, the returned route is a valid connected sequence from start to destination; if no path exists, the returned status is the declared failure value. The invariant states a property that holds throughout and after the call: every step in the route corresponds to a connection that existed in the map at the time of the call.

This structure will appear throughout the rest of the book, in every design chapter and every code sketch. The precondition, postcondition, and invariant are not formalism for its own sake — they are the tools by which informal expectations become statements that can be reasoned about, tested against, and maintained as systems change. Every contract in the chapters ahead is a specification that was made precise enough to be enforced.



## Quick Exercise

Take one story sentence from your system and transform it into a mini-specification with four parts: the inputs the operation receives, the output it must produce, the guarantee it makes about the output for valid inputs, and one precondition, one postcondition, and one invariant.

Start with the story sentence:

> *"The system should deliver packages efficiently."*

Write out what efficient means in terms of a measurable criterion. State what information the system must have to apply that criterion. State what a correct output looks like and how you would verify it. Then find one word in your specification draft that is still ambiguous — a word that could be interpreted differently by two reasonable readers — and replace it with a definition.



## Takeaways

- Stories communicate intent; specifications define behavior. Both are necessary, and neither replaces the other — but software is built from specifications, not stories.
- Every specification needs three elements stated precisely: the inputs the system receives, the outputs it must produce, and the guarantees it makes about the relationship between them.
- Ambiguous terms in stories — best, relevant, efficient, realistic — become correctness failures in code. The specification's job is to replace them with definitions.
- Contracts formalize specifications into executable agreements: preconditions state caller obligations, postconditions state routine guarantees, invariants state properties that must hold throughout. This structure appears everywhere in the chapters ahead.



*Part I has now established the full arc from problem understanding to precise specification: stories that identify the situation, problem statements that define the computational task, edge cases that test the boundaries of the definition, and specifications that transform intent into formal claims. Part II opens with the next question: before algorithms can operate on a problem, the world must be represented. Chapter 6 asks why software needs models before it needs optimization.*
