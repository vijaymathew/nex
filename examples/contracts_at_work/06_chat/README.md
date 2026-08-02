# Project 6 — A TCP Chat Server and Client

Part IV of *Contracts at Work*. Multiple clients connected to one server,
each message broadcast to everyone else. The concurrency design is "single
owner, no locks": one hub task is the only code that ever touches the list
of connected clients; every client-handling task only ever sends it events
over a channel.

## Files

| File | Role |
|---|---|
| `chat_protocol.nex` | `Chat_Protocol` — the one-line wire format (`"NAME: text\n"`), shared so server and client can't drift apart. |
| `chat_server.nex` | `Hub_Event` (union), `Chat_Hub` (the single owner of the client list), `handle_client` (free function — see below), `Chat_Server.accept_clients`. |
| `chat_server_main.nex` | Runs a real server on port 4321 until told to stop. **Must run under `--interpret`** — see below. |
| `chat_client.nex` | Interactive client: connects, sends a name, then a background task prints incoming lines while the main loop sends what you type. |
| `checks.nex` | Spins up a real server on an ephemeral port, connects two in-process clients, and checks what each side actually receives — the reliable, automated verification for this project. |

## Running

```bash
nex checks.nex --interpret   # 3/3 checks — reliable, automated, run this first

# to actually chat, in three terminals:
nex chat_server_main.nex --interpret
nex chat_client.nex --interpret   # terminal 2, name "alice"
nex chat_client.nex --interpret   # terminal 3, name "bob"
```

Everything here runs under `--interpret`. The client half works fine on the
JVM backend; the server half does not — see the first issue below.

## Status: works under `--interpret`; two real bugs found, one fixed in this project's own code

`checks.nex` is the trustworthy verification (3/3, deterministic, in-process,
both clients actually receive each other's broadcast correctly). Getting
there and building the standalone server/client surfaced two backend bugs,
plus one shutdown-ordering bug in this project's own client code:

1. **`spawn do ... end` inside a class method fails to lower on the JVM
   backend when its body calls a method on an object — even `this`, even
   with an explicit `this.` prefix.** Calling a *free* (top-level) function
   from the same spot works fine. Minimal repro (full detail in the book's
   top-level issues report): a `Worker` class whose `run()` spawns a task
   calling `do_work(...)`, another method on the same object, fails with
   `internal error in the compiled backend: Unable to infer expression type
   during lowering`; replacing the class method with an equivalent free
   function compiles and runs correctly. `handle_client` was originally a
   `Chat_Server` method for exactly this reason before being moved to the
   top level.
2. **`Server_Socket.accept()` is unusable on the JVM backend at all, for
   any accepted connection.** Root cause, traced to the library source:
   `Tcp_Socket.from_socket` (`lib/net/tcp_socket.nex`, called internally by
   every `accept`) unconditionally calls
   `open_socket.getInetAddress().getHostAddress()`, and that call fails on
   the compiled backend with `Attempting to call unbound fn:
   ...builtin-method-any-getHostAddress` — a missing runtime binding, not
   anything reachable from application code. The timeout-taking overload
   (`accept(timeout_ms)`) doesn't surface this as an error; it silently
   returns `nil`, which is what `chat_server_main.nex` first looked like
   before tracing it to this. **This is why the whole project runs under
   `--interpret`**: there is no application-level workaround for a missing
   binding in the standard library's own compiled-backend runtime.
3. **Not a language bug — a real bug in this project's own
   `chat_client.nex`, fixed rather than left in:** quitting closed the
   socket from the main loop while the background reader task could still
   be blocked inside `read_line()` on that same socket, which surfaced as
   an unhandled `Error: Stream closed` instead of a clean shutdown. Fixed
   with a `rescue` around the reader's read, treating a closed-out-from-
   under-it read the same as an ordinary disconnect.
