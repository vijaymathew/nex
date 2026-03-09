# What to Read Next

This book has been an introduction, not an ending.

If you have worked through the chapters, typed the examples, and solved some exercises, you now have more than a list of language features. You have seen a style of programming:

- program by stating obligations and guarantees
- design classes around stable meanings
- separate system boundaries from core logic
- make correctness an everyday concern rather than an afterthought

The next step is not to abandon that style, but to see where it appears elsewhere and how other writers develop it further.


## Where the Main Ideas Came From

Nex brings together ideas that appear across several traditions. Reading outside the language helps separate what is essential from what is merely syntactic.

From procedural programming:

- clear control flow
- small routines
- careful treatment of state

From object-oriented design:

- classes as models of concepts
- invariants
- interface-based reasoning

From formal methods:

- preconditions
- postconditions
- the habit of specifying before implementing

From practical software engineering:

- modularity
- tests
- explicit boundaries

Studying those traditions separately will deepen what you have learned here.


## Design by Contract and Meyer

If the contract chapters interested you most, the obvious next author is Bertrand Meyer.

The central references are:

- *Object-Oriented Software Construction*
- *Touch of Class*

These works develop Design by Contract far beyond the introductory level. They show how contracts affect inheritance, module boundaries, exception design, and the architecture of large systems.

Read them slowly. They are not quick books, but they repay serious study.


## Programming as a Teachable Discipline

If you appreciated the tutorial style of building ideas step by step, two books are especially valuable:

- *How to Design Programs* by Felleisen, Findler, Flatt, and Krishnamurthi
- *Think Python* by Allen Downey

Both are excellent on the craft of constructing programs from small parts and using examples to shape design.

*The C Programming Language* by Kernighan and Ritchie remains worth reading for another reason: economy. It is a model of concise technical writing. Even when the language differs, the style of explanation is instructive.


## Functional Ideas and SICP

If you want a deeper treatment of abstraction, program structure, and the relation between procedures and data, read:

- *Structure and Interpretation of Computer Programs*

SICP is not a Nex book, nor is it a contract-first book, but it trains the same underlying muscles:

- describing processes precisely
- separating mechanisms from interfaces
- seeing common patterns beneath specific code

It rewards rereading.


## Algorithms and Data Structures

This book introduced arrays, maps, recursion, and a few classical loop patterns, but it has not tried to be a full algorithms text.

For that, read:

- *Algorithms* by Robert Sedgewick and Kevin Wayne
- *Introduction to Algorithms* by Cormen, Leiserson, Rivest, and Stein

Sedgewick and Wayne are often the gentler next step. CLRS is broader and more formal.

As you read algorithm books, keep bringing the Nex discipline with you. Ask not only whether an algorithm works, but also:

- what are its preconditions?
- what loop invariants explain it?
- what data model makes its correctness easiest to state?


## Logic, Proof, and Program Correctness

Contracts are closely related to a broader body of thought about proving programs correct.

If that direction interests you, start with Hoare's classic paper on axiomatic programming. It is short and foundational. Even if you do not go fully into formal verification, it clarifies the relation between assertions and program reasoning.

Wirth's *Programming in Oberon* is also worth your time for its style: restrained, exact, and close to the machine without losing sight of design.


## Beyond the Language

A good language can encourage good habits, but it cannot replace them. Keep building the habits directly:

- write the contract before the body when the routine matters
- give every class one clear responsibility
- separate pure logic from environmental code
- test boundaries and edge cases
- refactor when names or file boundaries become muddy

These are language-independent skills. Nex is one place to practice them deliberately.


## Final Advice

Do not try to race through many advanced books at once. That usually produces a shelf of half-read ideas and very little working understanding.

Pick one direction based on what most interested you here:

- contracts and software design
- program construction and pedagogy
- algorithms
- abstraction and language ideas

Then write programs while you read. Passive reading creates the illusion of understanding. Working code reveals whether the understanding is real.


## Summary

- Nex sits at the intersection of procedural clarity, object-oriented design, and formal reasoning
- Meyer's books are the natural next step for Design by Contract
- HtDP, Think Python, and K&R are models of instructional programming writing
- SICP deepens abstraction and program structure
- Sedgewick and CLRS deepen algorithms and data structures
- Hoare and Wirth connect program construction with correctness and precision
- The important next step is continued practice, not only more reading


## Exercises

**1.** Choose one of the books named in this chapter and write a short paragraph on why it is the best next step for your current level and interests.

**2.** Revisit one program from earlier in the tutorial and identify which outside book would help you improve it most: a design book, an algorithms book, or a programming-methods book.

**3.** Pick one routine from Chapters 16 through 27 and restate it in Hoare-style language: what must be true before it runs, and what will be true after it runs?

**4.** Write a reading plan of three stages: one book for design, one for algorithms, and one for broader programming ideas.

**5.\*** Build one small original project in Nex after finishing the tutorial. Before writing code, list the contracts, classes, files, and tests you expect it to need. Then compare that plan with the final result.
