# Chapter 29 — What to Read Next (Exercise answers)

Chapter 29's exercises are reflective and reading-oriented rather than code, so
the answers are written in prose here instead of as a runnable `.nex` file.

### 1. One book and why it is the best next step

*A Discipline of Programming* (Dijkstra) is the natural next step after a tour
built on Design by Contract: it makes the habit of reasoning about a program's
correctness *before and while* writing it — preconditions, postconditions, and
the weakest-precondition calculus — completely explicit. Everything Nex enforces
at run time (require / ensure / invariant) this book teaches you to derive on
paper, which is exactly the muscle to grow once the syntax is comfortable.

### 2. The outside book that would most help improve an earlier program

The Chapter 26 task manager. The book that would help most is a **design** book —
*Object-Oriented Software Construction* (Meyer) — because the program's weak
points are design questions, not algorithmic ones: where responsibilities should
split (Todo_Item vs Task_List vs storage), which assertions belong on the
constructor versus the invariant, and how to keep clients independent of the
representation. Meyer's treatment of contracts and class design speaks directly
to those decisions.

### 3. A routine from Chapters 16–27, restated Hoare-style

Take `average(items: Array[Real]): Real` (Chapter 16/17).

- **Precondition (what must hold before):** `items.length > 0` — the array is
  non-empty.
- **Postcondition (what will hold after):** the result is the arithmetic mean of
  the elements, and it lies between the smallest and largest element:
  `min(items) <= result <= max(items)`.

In Hoare-triple form: `{ items.length > 0 }  r := average(items)
{ min(items) <= r <= max(items) }`.

### 4. A three-stage reading plan

1. **Design:** *Object-Oriented Software Construction* (Meyer) — contracts,
   class design, and the substitution principle in depth.
2. **Algorithms:** *Introduction to Algorithms* (Cormen et al.) — the standard
   reference for the data structures and algorithms the tutorial only sampled.
3. **Broader programming ideas:** *The Pragmatic Programmer* (Hunt & Thomas) —
   habits, tooling, and judgement that cut across languages and domains.

### 5.* Plan for one small original project, then compare with the result

Project idea: a **command-line expense tracker**.

Planned up front:

- **Contracts:** `add_expense` requires a positive amount and a non-empty
  category; `Budget` keeps the invariant `spent <= limit` (or surfaces an
  over-budget result object); `monthly_total` ensures a non-negative result.
- **Classes:** `Expense` (data), `Ledger` (the collection of expenses),
  `Budget` (limit + invariant), `Report` (formatting).
- **Files:** one class per file, plus `main.nex` to wire them together, mirroring
  the Chapter 23 layout.
- **Tests:** add/total/over-budget happy paths, plus contract-failure tests for a
  negative amount and an empty category.

The comparison step — done *after* building — is the real exercise: note where the
plan was wrong (a responsibility that wanted splitting, a contract that was
weaker than intended, a file that never earned its place) and write down what the
gap taught you. That gap between plan and result is where the design judgement
the whole tutorial has been building toward actually compounds.
