# Networking Libraries

The networking library surface under `lib/net` is target-specific:

- `Tcp_Socket` and `Server_Socket` are JVM-only.
- `Http_Client` is available in the JVM interpreter and in generated JVM and JavaScript code.
- `Http_Server` is available in the JVM interpreter and in generated JVM and Node.js code.
- `Http_Client` is not currently available in the browser/Web REPL ClojureScript interpreter.
- `Http_Server` is not available in the browser/Web REPL or browser JavaScript target.

## `net/Http_Client`

`Http_Client` is a small portable HTTP client wrapper shipped as a Nex library under [`lib/net/http_client.nex`](/home/vijay/Projects/nex/lib/net/http_client.nex). Its Nex methods are implemented on top of the built-in `http_get` and `http_post` functions.

### Loading

```nex
intern net/Http_Client
```

The loader accepts these common layouts:

- `lib/net/http_client.nex`
- `lib/net/Http_Client.nex`
- `lib/net/src/http_client.nex`
- `~/.nex/deps/net/http_client.nex`

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |
| Generated JavaScript / Node | Yes |
| Browser IDE interpreter | No |

### Construction

```nex
let client: Http_Client := create Http_Client.make()
```

### Response Values

Requests return `Http_Response`, a small value object with:

- `status(): Integer`
- `body(): String`
- `headers(): Map[String, String]`

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `make` | none | `Http_Client` | Create a client wrapper. |
| `get` | `url: String` | `Http_Response` | Perform an HTTP GET request. |
| `get` | `url: String, timeout_ms: Integer` | `Http_Response` | Perform an HTTP GET request with a timeout in milliseconds. |
| `post` | `url: String, body_text: String` | `Http_Response` | Perform an HTTP POST request with a text body. |
| `post` | `url: String, body_text: String, timeout_ms: Integer` | `Http_Response` | Perform an HTTP POST request with a text body and timeout in milliseconds. |

### Example

```nex
intern net/Http_Client

let client: Http_Client := create Http_Client.make()
let response: Http_Response := client.get("https://example.com")
print(response.status())
print(response.body())
```

Timeout-aware request:

```nex
let response: Http_Response := client.get("https://example.com", 500)
print(response.status())
```

### Notes

- The JVM interpreter uses Java's built-in HTTP client.
- Generated JavaScript uses `fetch`.
- The public Nex API lives in `lib/net/http_client.nex`; the host-specific request work lives in the runtime built-ins.
- Response headers are exposed as `Map[String, String]`, keeping the first value for each header name.
- Network failures are raised as runtime errors.

## `net/Http_Server`

`Http_Server` is a small HTTP server wrapper shipped as a Nex library under [`lib/net/http_server.nex`](/home/vijay/Projects/nex/lib/net/http_server.nex). Its Nex methods are implemented on top of runtime HTTP server built-ins.

### Loading

```nex
intern net/Http_Server
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |
| Generated JavaScript / Node | Yes |
| Browser IDE interpreter | No |
| Browser JavaScript target | No |

### Types

Route handlers receive `Http_Request` and return `Http_Server_Response`.

`Http_Request` exposes:

- `method(): String`
- `path(): String`
- `body(): String`
- `headers(): Map[String, String]`
- `params(): Map[String, String]`
- `query(): Map[String, String]`
- `param(name: String): String`
- `query_param(name: String): String`
- `wildcard(): String`

`Http_Server_Response` exposes:

- `status(): Integer`
- `body(): String`
- `headers(): Map[String, String]`

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `make` | `port: Integer` | `Http_Server` | Create a server wrapper. `0` requests an ephemeral port. |
| `get` | `path: String, handler: Function` | `nil` | Register a GET route handler. |
| `post` | `path: String, handler: Function` | `nil` | Register a POST route handler. |
| `put` | `path: String, handler: Function` | `nil` | Register a PUT route handler. |
| `delete` | `path: String, handler: Function` | `nil` | Register a DELETE route handler. |
| `start` | none | `nil` | Start listening. Updates `port` when the OS assigns an ephemeral port. |
| `stop` | none | `nil` | Stop the server. |
| `is_running` | none | `Boolean` | Return true when the server is listening. |
| `to_string` | none | `String` | Return a descriptive string showing the current port and run state. |

### Example

```nex
intern net/Http_Server

let server: Http_Server := create Http_Server.make(8080)

server.get("/hello/:name", fn(req: Http_Request): Http_Server_Response do
  result := create Http_Server_Response.text("hello " + req.param("name"))
end)

server.get("/search", fn(req: Http_Request): Http_Server_Response do
  result := create Http_Server_Response.text("q=" + req.query_param("q"))
end)

server.get("/files/*", fn(req: Http_Request): Http_Server_Response do
  result := create Http_Server_Response.text(req.wildcard())
end)

server.put("/items/:id", fn(req: Http_Request): Http_Server_Response do
  result := create Http_Server_Response.text("updated " + req.param("id"))
end)

server.start()
print(server.port)
```

### Notes

- Route patterns support exact paths, named parameters such as `:name`, and a trailing wildcard `*`.
- Supported verbs are `GET`, `POST`, `PUT`, and `DELETE`.
- Query strings are parsed into `req.query()` and `req.query_param("name")`.
- Route handlers are ordinary Nex function values.
- The JVM interpreter uses JDK `HttpServer`.
- Generated JavaScript support is intended for Node.js and uses the built-in `http` module.

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
