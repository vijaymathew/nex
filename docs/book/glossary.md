# Glossary

This glossary defines core terms used throughout the book.

## Algorithm
A finite, explicit procedure that transforms inputs into outputs while meeting stated guarantees.

## Asymptotic Complexity
A description of how runtime or memory grows as input size grows (for example: `O(1)`, `O(log n)`, `O(n)`, `O(n log n)`, `O(n^2)`).

## Class Invariant
A condition that must hold for every valid object state of a class.

## Contract
A precise behavioral agreement in code, usually expressed with preconditions (`require`), postconditions (`ensure`), and invariants.

## Coupling
The degree to which one module depends on details of another.

## Data Model
A structured representation of entities, relationships, and constraints in a problem domain.

## Deduplication by Ancestor Class
When combining inherited items (such as invariants), each ancestor class contributes once even in multiple-inheritance graphs.

## Encapsulation
A design principle that hides internal representation and exposes stable behavior through clear interfaces.

## Feature (Method)
A callable behavior defined on a class.

## Inheritance
A mechanism where a class reuses and extends behavior or structure from one or more parent classes.

## Invariant
A property that must remain true across allowed state transitions.

## Multiple Inheritance
A form of inheritance where a class has more than one immediate parent class.

## Postcondition (`ensure`)
A condition that must be true after successful feature execution.

## Precondition (`require`)
A condition that must be true before feature execution is valid.

## Recursive Inheritance Merge
A rule where inherited contracts are collected transitively across parent classes.

## Refactor
A structural code change that preserves externally visible behavior while improving design qualities.

## Reliability
The ability of software to behave correctly under expected load and adverse conditions over time.

## Scaling
The behavior of a system as data volume, traffic, or operational complexity grows.

## Search Space
The set of candidate states or paths an algorithm may need to inspect.

## Time Complexity
How execution steps grow as input size grows.

## Transition Rule
A rule that defines which state changes are legal for an entity.
