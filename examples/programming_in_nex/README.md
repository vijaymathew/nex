# Programming with Nex — Exercise Solutions and Chapter Examples

Two kinds of content from *Programming with Nex*
(`../vijaymathew.github.io/nex/docs/programming-with-nex`), one sub-folder per
chapter:

- `exercises.nex` — solutions to the end-of-chapter **Exercises**.
- `examples.nex` — the chapter's own **narrative code** (the examples shown
  and run *within* the chapter text, as opposed to the exercises at the end),
  collected into one self-checking program per chapter so the book's own
  examples stay verified as the language evolves. Chapter 29 has no code (a
  reflective, reading-plan chapter) and so has no `examples.nex`. A few
  syntax-reference appendices (A, B, C, D — placeholder-name grammar
  listings and API catalogs, not narrative code) and Appendix E (itself
  "Solutions to Selected Exercises") aren't reproduced here for the same
  reason `exercises.nex` doesn't reproduce other exercise-solutions content.

## Layout

Run either file the same way:

```
cd chapter_NN
nex exercises.nex
nex examples.nex
```

Every file runs to completion (exit 0). Where an exercise or example asks for
**console input**, the input-reading is factored into a function and called
with test values, so the program runs non-interactively while still
exercising the logic. Where an exercise is **discussion only**, the answer is
a comment beside a small demonstration. Harder exercises (marked `*` in the
book) are included.

A few `examples.nex` files rename or consolidate content the book itself
shows more than once under the same name -- a "before" and "after" version of
a function/class making a design point (only the final version is kept), or
the same name reintroduced for an unrelated demo elsewhere in the chapter
(disambiguated with a suffix, e.g. `double`/`double_sig`/`double_via_alias`
in `chapter_06`) -- since a single compiled program can't redeclare a name
the way a sequence of independent REPL inputs can. Chapter 23's
`intern math/Counter` and `intern finance/{Transaction,Account}` narrative
examples have real backing modules under `chapter_23/lib/`, the same way a
real multi-file Nex project would.

Some chapters span **multiple files** because the exercise is about that:

| Chapter | Extra files | Why |
|---|---|---|
| 23 Modules and Files | `temperature.nex`, `contact.nex`, `address_book.nex`, `csv_exporter.nex`, `long_descriptive_module_name.nex` | splitting code across files with `intern`, including `intern … as …` |
| 24 Interoperability | `pure_greeter.nex`, `host_system.nex` | isolating host (`import` / `with "java"`) work behind a contract |
| 26 A Complete Program | `todo_item.nex`, `task_list.nex`, `grade_book.nex` | the chapter's "split into files" exercise + an original program |
| 29 What to Read Next | `README.md` (no code) | reflective / reading-plan exercises |

## Notes on the Nex used here

These solutions were written against the `nex` CLI (the tree-walking
interpreter) and verified by running each file. A few non-obvious points that
shaped the code:

- String literals do **not** process backslash escapes; `#tab` / `#newline` and
  `Console.new_line` are used instead.
- Calling an object's own no-argument method needs an explicit receiver
  (`this.area`), and `case` needs a default `result :=` before it (the return
  checker does not treat `case` as exhaustive).
- `old` snapshots value-typed fields but not `.length` of a mutable array, so a
  size tracked for a postcondition is kept in an integer field.
- A `Map[Integer, …]` keyed from `String.length` cannot be looked up with an
  integer **literal** (`g.get(3)` misses it); iterate `g.keys` instead. This is
  noted inline in `chapter_10`.
