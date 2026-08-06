# Tight Core, Open Edge — The Complete Capstone

The running order-and-fulfillment system from Appendix B of the book
*Tight Core, Open Edge*, split into the files the appendix's sections
already suggest: a constrained core the compiler proves correct, an open
pricing edge that grows by adding data, a contract membrane holding the two
apart and together, fulfillment routing by unification and search, and a
thin imperative shell. Each file depends only on the ones above it in the
list below — leaf types first, orchestration and shell last.

## Files

| File | Appendix section | Role |
|---|---|---|
| `leaf_types.nex` | B.1 | `Money` (amount + currency, `+`/`-` via `alias`, comparisons via `Comparable`), and `Sku`/`Email_Address`/`Tracking_Id`/`Instant` as structural aliases. |
| `order_core.nex` | B.2 | `Quantity` (a positive `Integer`), `Line_Item`, `sum_of`, the `Order` state machine (`Draft` → `Placed` → `Shipped`) and `ship`. |
| `boundary.nex` | B.1 | A concrete boundary parser: `Order_Request`/`Raw_Line_Item` (untrusted input) and `parse_order`/`parse_line_item`/`parse_quantity`, each validating one thing. The book leaves this parser as "given, with the exact interface the rest of the code relies on" — this file is that interface, filled in. |
| `pricing.nex` | B.4 | The open pricing edge: `Spec[T]`/`Over_Amount`, `Price_Expr`/`Base`/`Percent_Off`/`When`/`Floored`. |
| `membrane.nex` | B.5 | `admit_quote` — the one gate a price must pass through to become a `Placed` order. |
| `effects.nex` | B.6 | `Effect` (a union of what should happen to the world) and `Placement`. |
| `routing.nex` | B.7 | `Term`, `Substitution`, `unify`/`unify_atom`/`unify_compound`, and `route` — fulfillment as unification and search over route facts. |
| `orchestration.nex` | B.8 | `place_order`/`plan_fulfillment` — one pure function threading a request through parse, price, admit, route, and effect-decision. |
| `shell.nex` | B.9 | `perform`/`handle_request`, plus small in-memory fakes for `Payment_Gateway`/`Mailer`/`Order_Store`/`Warehouse_Api` so the shell is exercisable without a real payment processor, mail server, database, or carrier API. |
| `check.nex` | B.1–B.10 | Executable checks for every region, bottom-up: leaf-type arithmetic and contracts, the core's invariants, the pricing edge, the membrane's admission rules, unification, routing, and five end-to-end orchestration scenarios (discount + route, no discount, boundary rejection, unfulfillable zone, below-floor quote). |

`intern` is transitive, so `check.nex` only interns `Shell`; that pulls in
every file above it. `data/Result` (the book's `Result[T, E]` from B.3) is
the standard library's, via `intern data/Result` — not hand-rolled here.

## Running

```bash
nex check.nex              # 54/54 checks, exits 0; exits 1 (with a FAIL line) on any regression
```

## Notes on fidelity to the book

- The appendix leaves `Money`, `Sku`/`Email_Address`/`Tracking_Id`/`Instant`,
  and the boundary parser (`parse_order`) as "assumed, with the exact
  interface the rest of the code relies on" so the listing reads as
  architecture rather than currency arithmetic or string parsing.
  `leaf_types.nex` and `boundary.nex` are that interface, made concrete and
  runnable.
- Likewise `Payment_Gateway`, `Mailer`, `Order_Store`, and `Warehouse_Api`
  are unspecified collaborator types passed into `handle_request` in the
  book; `shell.nex` gives them small in-memory implementations that record
  what they were asked to do, so `check.nex` can assert on the shell's
  effects without any real infrastructure.
- Everything else — `order_core.nex`, `pricing.nex`, `membrane.nex`,
  `effects.nex`, `routing.nex`, `orchestration.nex` — is the book's listing
  verbatim.

## Backend

`nex check.nex` runs on the default (compiled JVM) backend and is green:
54/54. `nex check.nex --interpret` does not run: the tree-walking
interpreter's `intern <Name>` requires a class literally named `<Name>` to
exist in `<name>.nex` (`intern Order_Core` demands a class `Order_Core`
inside `order_core.nex`), which none of these multi-class modules satisfy —
a known interpreter-only limitation, not a defect in this example. The
default CLI path is compiled-only for exactly this kind of program.

## A backend gap this example steered around

`declare type Quantity = Integer where n: n > 0` (`order_core.nex`) is only
auto-checked by the runtime when the narrowing site is in the *same file*
as the `declare type` — the predicate is not (yet) re-checked when the type
is reached through `intern`. `parse_quantity` in `boundary.nex` therefore
validates the sign explicitly (`if n > 0 then ... else ...`) rather than
assigning into a `Quantity`-typed local and catching a precondition
violation, which is what the same code would do if `Quantity` were declared
locally.
