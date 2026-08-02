# Project 7 — An HTTP JSON API + CLI Client

Part IV of *Contracts at Work*. Embeds Project 4's expression interpreter
behind `POST /evaluate`: request validation, and both of the interpreter's
contract violations (division by zero, an undefined variable) turned into a
400 response with a JSON error body instead of a crash.

## Files

| File | Role |
|---|---|
| `expr.nex` | Copy of Project 4's interpreter — the service embeds it unmodified, exactly the "library another project can `intern`" the outline calls for. |
| `calc_service.nex` | `Calc_Service` — registers the route and handles requests as ordinary methods. |
| `calc_server_main.nex` | Runs the service on port 4322. |
| `calc_client.nex` | Interactive CLI over `net/Http_Client`. |
| `checks.nex` | Starts the service on an ephemeral port, drives it with a real `Http_Client`, checks status codes and bodies. |

## Running

```bash
nex checks.nex                    # 6/6 checks

nex calc_server_main.nex   # terminal 1
nex calc_client.nex        # terminal 2 — type an expression, or 'quit'
```

Everything here runs on the default JVM backend.

## Status: works cleanly on the default JVM backend; one issue still shapes the client

Two of the three issues originally hit here are fixed upstream (see the
book's top-level issues report), and the code was updated to match rather
than keeping the old workaround around:

1. **A closure passed as a callback couldn't call a method on the
   enclosing object.** Fixed. `handle_evaluate`, `parse_vars`, and
   `json_response` are back to being ordinary `Calc_Service` methods,
   called as `this.handle_evaluate(req)` from the route closure, instead
   of free top-level functions threading a captured `json` local through.
2. **`Integer` had no `to_real()` method.** Also fixed — `to_real_any` now
   calls `n.to_real()` directly instead of promoting with `n + 0.0`.

The third is still real, and still shapes `calc_client.nex`: **interning
both `net/Http_Client` and `data/Json`, then calling `.get()` on a Map
`json.parse(...)` produced, still fails inside a `function ... do ... end`
body** — `Method 'call0' uses Result but does not declare a return type`,
an error naming neither library. This turned out to be narrower than first
understood: identical code as bare top-level script statements does *not*
fail (confirmed directly by moving it out of a function), which is why
`checks.nex` — written as top-level statements — has always called
`.get()` on parsed responses without issue, while `calc_client.nex`, whose
logic lives inside `main()`, still can't. `calc_client.nex` reads the
service's response with a fixed-lookbehind `text/Regex` instead, on the
one field its own fixed response shape (`{"result":N}` / `{"error":"..."}`)
ever needs, rather than touching `data/Json`'s parse result inside a
function at all.
