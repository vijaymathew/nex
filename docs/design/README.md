# Design Book

This folder contains a short implementation-manual style book for Nex.

## Build

Run commands from `docs/design`.

- HTML: `make html`
- PDF: `make pdf`
- EPUB: `make epub`
- All: `make book`

## Main Files

- `_quarto.yml` - book configuration
- `styles.css` - HTML styling
- `index.md` - title page
- `introduction.md` and `chapter_*.md` - book content
- `COMPILED_REPL_STATUS.md` - current boundary of the experimental JVM compiled REPL backend
- `JVM_BYTECODE_COMPILER_PLAN.md` - implementation plan for the JVM bytecode compiler
- `USER_DEFINED_CLASSES_COMPILER_PLAN.md` - implementation checklist for compiled support of simple user-defined classes
- `DEFERRED_CLASSES_COMPILER_PLAN.md` - implementation checklist for compiled support of deferred classes and parent-typed virtual dispatch
