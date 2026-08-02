# Project 4 — A Small Expression-Language Interpreter

Part II of *Contracts at Work*. A four-function calculator language with
variables and parentheses: a hand-written tokenizer, a recursive-descent
parser building a `union Expr` AST, and an evaluator — built as a library
another project can `intern`, not just a standalone REPL. (Project 7 embeds
`evaluate` as an HTTP endpoint.)

## Files

| File | Role |
|---|---|
| `expr.nex` | `Expr` (the AST union), `tokenize`, `Parser`, `eval_expr`/`evaluate` — the whole embeddable library. |
| `checks.nex` | Executable checks: precedence, parens, unary minus, variables, and both contract-violation paths. |
| `calc_repl.nex` | Interactive REPL: type an expression, `let name := expr`, or `quit`. |

## Running

```bash
nex checks.nex               # 11/11 checks, both backends
nex checks.nex --interpret
nex calc_repl.nex
```

## Status: works, both backends, one real language gotcha found along the way

Division by zero and an undefined variable are both `require`-clause
violations (`divide`/`lookup`, in `expr.nex`), caught with `rescue` in
`calc_repl.nex` and asserted directly in `checks.nex` — a caller gets a
labeled failure at the mistake, not a host exception three frames removed
from the bad input.

One real bug hit while writing `Parser.is_number`: **`#0`–`#9` char literals
parse as the ASCII control code with that numeric value, not the digit
glyph** — `#9` is a tab character (ASCII 9), not `'9'`. Minimal repro:

```nex
print(#9)   -- prints a tab, not "9"
```

`is_number` originally read `tok.char_at(0) >= #0 and tok.char_at(0) <= #9`,
which silently misclassified every numeric token as a variable reference —
manifesting as a `Precondition violation: known` (the `lookup` contract)
on input as simple as `1 + 2`, with no hint that the actual defect was in
tokenizing, not evaluating. Fixed by testing string membership instead
(`"0123456789".contains(tok.substring(0, 1))`), which sidesteps digit char
literals entirely. Worth a documentation note at minimum, since `#c`/`#a`
work exactly as expected for letters — only the digit range is a trap.
