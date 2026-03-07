# From Stories to Specifications

So far in this book, we have described our systems mostly through **stories**.

A robot trying to deliver packages in a building.
A knowledge engine trying to answer questions.
A virtual world struggling to simulate interactions.

Stories are powerful because they help us **understand the situation**.
They capture context, motivation, and human goals.

But stories alone are not enough for programming.

Computers cannot execute stories.

They execute **precise rules**.

The task of the programmer is to transform a narrative like:

> “The robot should deliver packages efficiently”

into something far more precise:

> “Given a map, a start location, and a destination, the system must compute a valid route.”

This transformation—from story to specification—is where software engineering truly begins.


## Turning Narratives Into Rules

Stories describe events and intentions.

Specifications describe **rules and behaviors**.

Consider the story of the delivery robot:

> A robot receives a delivery request and navigates through the building to reach the destination.

To turn this story into a specification, we begin asking structured questions:

* What information does the robot receive?
* What decision must the robot make?
* What outcome must the system guarantee?

By answering these questions, we gradually transform the story into something more precise.

For example:

**Narrative**

> The robot should navigate through the building.

**Specification**

> The robot receives a map of locations connected by traversable paths and must compute a route from its current location to a specified destination.

Notice what happened.

The story introduced the idea.
The specification introduced **formal elements** we can reason about.


## Identifying the Inputs

Every computational problem begins with **inputs**.

Inputs represent the information the system receives from the outside world.

For the delivery robot, possible inputs include:

* the map of the building
* the robot’s current location
* the destination location
* information about blocked paths

For the knowledge engine, inputs might include:

* the user’s query
* the collection of documents
* previous interaction history

For the virtual world simulation:

* the list of objects
* their positions
* the rules governing their interactions

Once we clearly identify the inputs, the problem becomes far easier to analyze.

Without clear inputs, algorithms have nothing to operate on.


## Defining the Outputs

If inputs describe what enters the system, **outputs** describe what the system must produce.

In the navigation problem, the output might be:

* a sequence of locations representing the route

In the knowledge engine, the output might be:

* a ranked list of documents

In the virtual world, the output might be:

* the updated positions and states of objects after a simulation step

The moment we clearly define outputs, we begin to understand what the algorithm must accomplish.


## Guarantees and Expectations

Beyond inputs and outputs, specifications must describe **guarantees**.

These guarantees define what the system promises to do.

For example:

**Navigation guarantee**

> If a path exists between the start and destination, the system must return a valid route.

**Search guarantee**

> The system must return documents ranked by estimated relevance to the query.

**Simulation guarantee**

> Objects must follow the interaction rules defined for their types.

Guarantees are important because they establish **what correctness means**.

Without them, we cannot determine whether a system behaves properly.


## The Limits of Stories

Stories help us imagine how a system should behave.

But stories often hide ambiguity.

Consider the narrative:

> The robot should take the best path.

What does “best” mean?

* shortest distance?
* fastest travel time?
* safest route?
* least crowded hallway?

Stories leave these questions unanswered.

Specifications force us to **make decisions explicit**.


## A First Glimpse of Contracts

As systems become more complex, specifications evolve into something even more powerful: **contracts**.

A contract describes the agreement between different parts of a system.

It typically answers three questions:

1. **What must be true before an operation begins?**
2. **What will the operation produce?**
3. **What conditions will remain true afterward?**

For example, imagine a function that computes a route.

We might describe its contract like this:

**Precondition**

> The map correctly represents connections between locations.

**Postcondition**

> If a path exists between the start and destination, the function returns a valid route.

**Invariant**

> Every step in the returned route corresponds to a valid connection in the map.

This structure allows programmers to reason about correctness with much greater clarity.

Later in this book, we will use contracts extensively to build systems that are **predictable, reliable, and easier to maintain**.


## Connection to Nex

Nex is designed to make this story-to-specification transition concrete.

In practice, that means you can encode the specification directly through:

- explicit structure for inputs and outputs
- contracts (`require`, `ensure`) for operation-level guarantees
- invariants for system-level consistency rules

This helps keep intent and implementation aligned as systems evolve.


::: {.note-exercise}
**Exercise**
Apply the section task and record your results before reading the solution notes.
:::

## Quick Exercise (6 Minutes)

Take one story sentence and convert it into a mini specification.

Template:

1. Story sentence: “The system should ...”
2. Inputs:
3. Outputs:
4. Guarantee:
5. One precondition, one postcondition, one invariant.

Example starter:

- Story: “The robot should deliver packages efficiently.”
- Guarantee rewrite: “Given a valid map, start, and destination, return a valid route if one exists.”

Keep algorithm choices out of this draft. Focus on behavior and correctness.


## From Story to Engineering

By the end of this chapter, we have transformed our narrative systems into something more precise:

* we know what information they receive
* we know what results they must produce
* we know the guarantees they must satisfy

The story is still important—it gives the system purpose.

But the specification gives it **structure**.

Programming lives in the space between those two things.

Stories inspire the system.
Specifications make it **buildable**.


::: {.note-takeaways}
**Takeaways**
Capture the key principles from this chapter and one action you will apply immediately.
:::

## Chapter Takeaways

- Stories provide context and goals; specifications provide executable precision.
- Every specification needs clear inputs, outputs, and guarantees.
- Ambiguous terms (“best,” “relevant,” “realistic”) must be made explicit.
- Contracts are a disciplined way to formalize expectations and correctness.
- Better specifications reduce design churn, testing ambiguity, and implementation drift.


In the next part of the book, we take the next engineering step.

Once we know **what problem we are solving**, we must decide how the program will **represent the world**.

This is the beginning of **data modeling**.

And as we will soon discover, the way we represent data often determines which algorithms are possible.

Part II opens with that exact question in Chapter 6: why software needs models before it needs optimization.
