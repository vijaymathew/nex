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
| `chat_server.nex` | `Hub_Event` (union), `Chat_Hub` (the single owner of the client list), `Chat_Server` (`handle_client`, `accept_clients`). |
| `chat_server_main.nex` | Runs a real server on port 4321 until told to stop. |
| `chat_client.nex` | Interactive client: connects, sends a name, then a background task prints incoming lines while the main loop sends what you type. |
| `checks.nex` | Spins up a real server on an ephemeral port, connects two in-process clients, and checks what each side actually receives. |

## Running

```bash
nex checks.nex             # 3/3 checks

# to actually chat, in three terminals:
nex chat_server_main.nex
nex chat_client.nex   # terminal 2, name "alice"
nex chat_client.nex   # terminal 3, name "bob"
```

Everything here runs on the default JVM backend.

## Status: works cleanly on the default JVM backend

This project originally needed `--interpret`, for a real, severe bug:
`Server_Socket.accept()` was unusable on the JVM backend for any accepted
connection at all, traced to `Tcp_Socket.from_socket` hitting a missing
runtime binding. Fixed upstream; confirmed directly against this project's own `checks.nex`,
which now passes on the default backend with no `--interpret` flag.

One design choice changed along with the fix. `handle_client` was
originally a free (top-level) function, not a `Chat_Server` method — a
`spawn do ... end` body couldn't call a method on the enclosing object,
even `this`, even with an explicit `this.` prefix, so it had to live
outside the class entirely. That's fixed too; `handle_client` is back to
being an ordinary method, called as `this.handle_client(...)` from inside
`spawn`, which reads better as a method of the thing it's handling clients
for.

One rough edge in this project's own code, unrelated to any of that and
still worth keeping fixed: quitting `chat_client.nex` used to close the
socket from the main loop while the background reader task could still be
blocked inside `read_line()` on it, surfacing as an unhandled `Error:
Stream closed`. Still handled with a `rescue` around the reader's read,
treating a closed-out-from-under-it read the same as an ordinary
disconnect.
