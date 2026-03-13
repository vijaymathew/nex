# Networking Libraries

These networking classes are JVM-only. They rely on Java imports from `java.net` and `java.io`, so they are not available in JavaScript, the browser IDE, or the Web REPL.

## `net/Tcp_Socket`

`Tcp_Socket` is a small blocking TCP client wrapper shipped as a Nex library under [`lib/net/tcp_socket.nex`](/home/vijay/Projects/nex/lib/net/tcp_socket.nex).

### Loading

```nex
intern net/Tcp_Socket
```

The loader accepts these common layouts:

- `lib/net/tcp_socket.nex`
- `lib/net/Tcp_Socket.nex`
- `lib/net/src/tcp_socket.nex`
- `~/.nex/deps/net/tcp_socket.nex`

### Construction

```nex
let sock: Tcp_Socket := create Tcp_Socket.make("example.com", 80)
```

Construction does not connect immediately. It only records the endpoint.

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `make` | `host: String, port: Integer` | `Tcp_Socket` | Create a disconnected wrapper for an endpoint. |
| `from_socket` | `open_socket: Socket` | `Tcp_Socket` | Wrap an already accepted JVM socket. |
| `connect` | none | `nil` | Open the TCP connection. |
| `connect` | `timeout_ms: Integer` | `Boolean` | Try to connect within the timeout. Returns `true` on success and raises on timeout or connection failure. |
| `is_connected` | none | `Boolean` | True after a successful `connect` and before `close`. |
| `send` | `text: String` | `nil` | Send text without a trailing newline. |
| `send_line` | `text: String` | `nil` | Send text followed by a newline. |
| `read_line` | none | `?String` | Read one line, or `nil` on end-of-stream. |
| `close` | none | `nil` | Close the connection. Safe to call repeatedly. |
| `to_string` | none | `String` | Return a descriptive endpoint string. |

### Contracts

- `make` requires a non-empty host and a port in `1..65535`
- `connect` requires the socket to be disconnected
- `connect(timeout_ms)` requires a non-negative timeout
- `send`, `send_line`, and `read_line` require the socket to be connected
- `close` guarantees the socket is disconnected on return

### Example

```nex
intern net/Tcp_Socket

let sock: Tcp_Socket := create Tcp_Socket.make("example.com", 80)
sock.connect()
sock.send_line("GET / HTTP/1.0")
sock.send_line("")
print(sock.read_line())
sock.close()
```

Timeout-aware connect:

```nex
let sock: Tcp_Socket := create Tcp_Socket.make("example.com", 80)
if sock.connect(500) then
  print("connected")
end
```

### Notes

- This wrapper is blocking. Reads and writes wait for the underlying Java socket.
- Connection failures and I/O failures are raised as runtime errors from the underlying JVM classes.
- `from_socket` is intended for use by `Server_Socket.accept`.

## `net/Server_Socket`

`Server_Socket` is a blocking TCP server wrapper shipped as a Nex library under [`lib/net/server_socket.nex`](/home/vijay/Projects/nex/lib/net/server_socket.nex).

### Loading

```nex
intern net/Server_Socket
```

`Server_Socket` internally interns `net/Tcp_Socket` so accepted clients are returned as `Tcp_Socket` values.

### Construction

```nex
let server: Server_Socket := create Server_Socket.make(8080)
```

Construction does not bind immediately. Use `0` to request an ephemeral port from the OS.

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `make` | `port: Integer` | `Server_Socket` | Create a disconnected wrapper for a local TCP port. |
| `open` | none | `nil` | Bind the server socket to the configured port. |
| `is_listening` | none | `Boolean` | True after a successful `open` and before `close`. |
| `accept` | none | `Tcp_Socket` | Block until a client connects, then return a wrapped client socket. |
| `accept` | `timeout_ms: Integer` | `Tcp_Socket` | Wait up to the timeout for a client. Raises on timeout or accept failure. |
| `close` | none | `nil` | Close the server socket. Safe to call repeatedly. |
| `to_string` | none | `String` | Return a descriptive local endpoint string. |

### Contracts

- `make` requires a port in `0..65535`
- `open` requires the server socket to be closed
- `accept` requires the server socket to be listening
- `accept(timeout_ms)` requires a non-negative timeout
- `close` guarantees the server socket is closed on return

### Example

```nex
intern net/Server_Socket

let server: Server_Socket := create Server_Socket.make(8080)
server.open()
let client: Tcp_Socket := server.accept()
client.send_line("hello")
client.close()
server.close()
```

Timeout-aware accept:

```nex
let server: Server_Socket := create Server_Socket.make(8080)
server.open()
let client: Tcp_Socket := server.accept(1000)
client.send_line("hello")
client.close()
server.close()
```

### Notes

- This wrapper is blocking. `accept` waits for an incoming connection.
- If `make(0)` is used, `open` updates `port` to the actual OS-assigned port.
- Connection failures, timeouts, and I/O failures are raised as runtime errors from the underlying JVM classes.
