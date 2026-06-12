# Programming with Nex — Exercise Solutions

Solutions to the end-of-chapter **Exercises** from *Programming with Nex*
(`../vijaymathew.github.io/nex/docs/programming-with-nex`). One sub-folder per
chapter.

## Layout

Each chapter folder holds a runnable `exercises.nex` that implements every coding
exercise and answers the discussion exercises in comments. Run one with:

```
cd chapter_NN
nex exercises.nex
```

Every file runs to completion (exit 0). Where an exercise asks for **console
input**, the input-reading is factored into a function and called with test
values, so the program runs non-interactively while still exercising the logic.
Where an exercise is **discussion only**, the answer is a comment beside a small
demonstration. Harder exercises (marked `*` in the book) are included.

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
