# Project 7 — An HTTP JSON API + CLI Client

Part IV of *Contracts at Work*. Embeds Project 4's expression interpreter
behind `POST /evaluate`: request validation, and both of the interpreter's
contract violations (division by zero, an undefined variable) turned into a
400 response with a JSON error body instead of a crash.

## Files

| File | Role |
|---|---|
| `expr.nex` | Copy of Project 4's interpreter — the service embeds it unmodified, exactly the "library another project can `intern`" the outline calls for. |
| `calc_service.nex` | `Calc_Service` — registers the route; request handling is free functions, not methods (see below). |
| `calc_server_main.nex` | Runs the service on port 4322. **Must run under `--interpret`**. |
| `calc_client.nex` | Interactive CLI over `net/Http_Client`. |
| `checks.nex` | Starts the service on an ephemeral port, drives it with a real `Http_Client`, checks status codes and bodies. |

## Running

```bash
nex checks.nex --interpret        # 6/6 checks

nex calc_server_main.nex --interpret   # terminal 1
nex calc_client.nex --interpret        # terminal 2 — type an expression, or 'quit'
```

## Status: works under `--interpret`; three real issues found along the way

1. **A closure passed as a callback can't call a method on the enclosing
   object — same bug family as Project 6, different callback shape.**
   `server.post("/evaluate", fn(req) do result := this.handle_evaluate(req)
   end)` fails typechecking (`Method not found: handle_evaluate`) whether
   `this.` is explicit or omitted. Project 6 hit this with `spawn`; here it's
   an anonymous `fn` passed as a route handler. Fixed the same way: request
   handling (`handle_evaluate`, `parse_vars`, `json_response`) are free
   top-level functions, and the route closure captures a local `json`
   binding instead of reading `this.json` — capturing a plain local in a
   closure works fine, it's specifically method access on `this` inside one
   that doesn't.
2. **`Integer` has no `to_real()` method.** JSON integers parse as
   `Integer`; the interpreter's `evaluate` wants `Real`. Checked against the
   language reference — `Integer`'s method table has no real-conversion
   entry (`String.to_real()` exists; `Integer.to_real()` doesn't). Fixed
   with arithmetic promotion (`n + 0.0`) instead.
3. **Interning both `net/Http_Client` and `data/Json` in the same program
   breaks any method call on a `Map` returned by `json.parse(...)`** — not
   file-scoped, confirmed across a module boundary; not about actually
   *using* `Http_Client`, confirmed with `Http_Client.make()` never called
   at all. Minimal repro (4 lines): `intern net/Http_Client`, `intern
   data/Json`, `json.parse(...)`, `.get(...)` on the result — fails with
   `Method 'call0' uses Result but does not declare a return type`, an
   error that names neither library. `net/Http_Server` + `data/Json`
   together (`calc_service.nex`, checked 6/6) has no such problem — this is
   specific to the *client* library. `json.stringify(...)` to build the
   request body is unaffected; only reading a parsed Map's fields is. Since
   `calc_client.nex` unavoidably needs both libraries, the workaround is
   `text/Regex` with a fixed-lookbehind pattern to read the one field this
   project's own fixed response shape (`{"result":N}` / `{"error":"..."}`)
   ever needs, rather than touching `data/Json`'s parse result at all.
