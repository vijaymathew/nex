# Nex Language Examples

Example programs demonstrating the features of the Nex programming language.
Every example here is a plain Nex script — run one with:

```bash
nex examples/basics_example.nex
```

## Language tour

Each of these is self-contained and prints its results, so the quickest way to
read one is to run it alongside the source.

- **basics_example.nex** — arithmetic, comparison and logical operators,
  strings, operator precedence, and a first class
- **let_example.nex** — local variables: `let` to declare (typed or inferred),
  `:=` to assign
- **gcd_example.nex** — the full loop construct (`from` / `invariant` /
  `variant` / `until` / `do`) and grouped parameter syntax, `gcd(a, b: Integer)`
- **contracts_example.nex** — Design by Contract end to end: `require`,
  `ensure` (including `old`), and class `invariant`, with each violation caught
  and reported
- **inheritance_example.nex** — single inheritance, redefining a feature,
  calling the parent's version via `Parent.feature(...)`, inherited contracts,
  and multiple inheritance
- **create_example.nex** — the `create` clause: several creation procedures per
  class, default initialization, and creation preconditions
- **parameterless_calls_example.nex** — why `counter.increment` takes no
  parentheses, and why that makes fields and functions interchangeable
- **generics_example.nex** — `Box [T]`, `Pair [F, S]`, and constrained
  parameters, `Sorted_List [G -> Comparable]`
- **arrays_maps_example.nex** — `Array [T]` and `Map [K, V]`, their literal
  syntax, and nesting them

## Data structures

- **stack.nex** — a generic array-backed stack with full contracts
- **apl.nex** — array-oriented operations in the APL style

## Library and tooling

- **io_text_example.nex**, **io_binary_example.nex** — file I/O
- **echo_server.nex**, **echo_client.nex** — TCP sockets
- **http_server.nex**, **http_client_to_server.nex** — HTTP
- **notes_example.nex** — the `note` clause for feature metadata
- **with_example.nex** — the `with` statement
- **formatted_example.nex** — canonical layout produced by `nex format`
- **docgen_comprehensive.nex** — doc comments consumed by `nex doc`
- **emacs_demo.nex** — a syntax-highlighting sampler for `nex-mode.el`
- **demo_repl.md** — a walkthrough of an interactive REPL session

## Longer collections

- **programming_in_nex/** — worked solutions to the end-of-chapter exercises in
  *Programming with Nex*, one directory per chapter
- **beyond_code/** — larger programs that go past single-feature demos

## Running

```bash
nex examples/<file>.nex              # compiled JVM backend (the default)
nex examples/<file>.nex --interpret  # tree-walking interpreter
nex compile jvm examples/<file>.nex  # build a standalone jar
```
