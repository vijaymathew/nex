# *Programming with Nex*
### A Practical Introduction to Software Engineering Through a Contract-First Language

---

## Preface

Why Nex. How to use this book. How to use the REPL. A note on contracts as first-class citizens.

---

## Part I: Getting Started

### Chapter 1: Your First Programs
The REPL as a laboratory. Printing output. Comments. The edit-run cycle. What it means for a program to be correct.

### Chapter 2: Values and Variables
Scalar types: Integer, Real, String, Boolean. The `let` declaration. Assignment with `:=`. Type annotations — when to write them and when to omit them. Nil and detachable types.

### Chapter 3: Expressions
Arithmetic operators and precedence. Comparison operators. Boolean logic: `and`, `or`, `not`. String concatenation. What an expression is versus what a statement is.

### Chapter 4: Making Decisions
`if ... then ... elseif ... else ... end`. The `when` expression for inline choices. `case` for multiple-value branching. Writing conditions that are easy to read and reason about.

### Chapter 5: Repetition
The `from ... until ... do ... end` loop and how to read it. `repeat` for counted repetition. `across` for iterating collections. Common loop patterns: counting, accumulating, searching.

---

## Part II: Functions and Structure

### Chapter 6: Functions
Defining functions with `function`. Parameters, return types, and the `result` variable. Calling functions. Anonymous functions with `fn`. When to break code into functions.

### Chapter 7: Thinking with Functions
Functions as units of reasoning. What a function requires and what it guarantees — informally. Pure functions versus functions with effects. Writing functions that are easy to test.

### Chapter 8: Recursion
Thinking recursively. The base case and the recursive case. Recursive functions on numbers and on lists. When recursion is clearer than a loop and when it is not.

---

## Part III: Organising Data

### Chapter 9: Arrays
Creating and indexing arrays. Common operations: `get`, `add`, `size`. Iterating with `across`. Arrays as ordered sequences. What arrays are good for and where they fall short.

### Chapter 10: Maps
Key-value storage. `get`, `put`, and membership checks. Choosing keys. Iterating over maps. When to use a map instead of a class.

### Chapter 11: Nested and Composite Structures
Arrays of maps. Maps of arrays. When flat structures are enough and when nesting is necessary. Building and traversing tree-shaped data by hand.

---

## Part IV: Classes and Objects

### Chapter 12: Classes
Defining a class. Fields, methods, and class constants. The `create` constructor. Object creation with `create`. The `this` reference. Uniform access — why `obj.field` and `obj.method` look the same.

### Chapter 13: Designing Classes Well
One class, one responsibility. What belongs inside a class and what belongs outside. The difference between data and behavior. Classes as models of real things.

### Chapter 14: Inheritance
Extending a class with `inherit`. Redefining methods. Inherited public constants. Renaming to resolve conflicts. When inheritance is the right tool and when it is not. Polymorphism and substitution.

### Chapter 15: Generic Classes
Parameterised classes with `[T]`. Writing classes that work for any type. Type constraints with `->`. The standard collection classes as examples of good generic design.

---

## Part V: Design by Contract

### Chapter 16: Preconditions
What a precondition is. Writing `require` clauses. Naming preconditions. What happens when a precondition is violated. The caller's responsibility.

### Chapter 17: Postconditions
What a postcondition is. Writing `ensure` clauses. The `old` keyword for comparing before and after. The routine's responsibility. Writing postconditions that are precise enough to be useful.

### Chapter 18: Invariants
Class invariants with `invariant`. What an invariant means and when it is checked. Designing invariants that capture the essential properties of a class. The relationship between invariants, preconditions, and postconditions.

### Chapter 19: Loop Contracts
Loop invariants — what must be true at every iteration. Loop variants — what must decrease to guarantee termination. Writing verifiable loops. Reading existing loops with contracts as guides.

### Chapter 20: Contracts as Design
Contract-first design: writing the contract before writing the body. Using contracts to find bugs. Using contracts as documentation. The relationship between contracts and tests.

---

## Part VI: Errors and Recovery

### Chapter 21: Errors and Exceptions
What an error is versus what a contract violation is. Raising exceptions with `raise`. Catching with `rescue`. Retrying with `retry`. When to recover and when to fail fast.

### Chapter 22: Writing Robust Code
Anticipating failure. Distinguishing caller errors from environmental failures. The relationship between preconditions and exception handling. Patterns for recovery that do not mask bugs.

---

## Part VII: Working at Scale

### Chapter 23: Modules and Files
Splitting programs across files. Loading Nex classes with `intern`. Aliasing with `as`. Designing module boundaries. What belongs in one file versus several.

### Chapter 24: Interoperability
Importing Java and JavaScript symbols with `import`. Using platform libraries from Nex. Generating Java and JavaScript from Nex source. The production build and the `skip-contracts` option.

### Chapter 25: Testing Your Programs
Writing tests in Nex. The relationship between contracts and tests. What a good test covers that a contract does not. Building a test suite. Running tests from the command line.

---

## Part VIII: Putting It Together

### Chapter 26: A Complete Program — Step by Step
A single worked example built from scratch: problem statement, data model, algorithm, contracts, tests. The full arc of a small but complete program.

### Chapter 27: Common Patterns
Accumulator loops. Recursive descent. State machines in classes. Table-driven dispatch. Each pattern presented as a reusable structure with a Nex implementation.

### Chapter 28: What to Read Next
Where Nex ideas appear in other languages. The relationship between Nex contracts and Hoare logic. Recommended reading for algorithms, design, and software engineering. The companion book.

---

## Appendices

**Appendix A: Nex Syntax Reference** — Complete grammar on a few pages. All keywords, operators, and constructs.

**Appendix B: Built-in Types and Operations** — All methods on Integer, Real, String, Boolean, Array, Map, with signatures and contracts.

**Appendix C: The Standard Library** — Available modules, their contents, and example usage.

**Appendix D: The Debugger** — All REPL debugger commands, with a worked debugging session.

**Appendix E: Solutions to Selected Exercises** — Solutions for the exercises marked with an asterisk throughout the book.

---

## Design Principles for the Book Itself

A few commitments worth stating before writing begins:

**Every chapter fits in one sitting.** Chapters are short. An experienced developer should be able to read one in thirty minutes; a student in an hour, with REPL work.

**Every concept is introduced once.** Nothing is previewed vaguely and defined later. If a concept is needed, it is defined where it first appears.

**Every example runs.** No pseudocode, no ellipses standing in for real code. Every snippet that appears in a chapter can be typed into the REPL and will produce the output shown.

**Contracts appear from Chapter 16, but the attitude appears from Chapter 1.** Even before formal contracts are introduced, the book asks "what does this function require?" and "what does it guarantee?" informally. By the time `require` and `ensure` are introduced, the reader has been thinking in those terms for fifteen chapters.

**Exercises are graduated.** Each chapter ends with three to five exercises: one that confirms the chapter's main concept, one that applies it in a new context, and one marked with an asterisk that extends it into something the chapter did not cover.
