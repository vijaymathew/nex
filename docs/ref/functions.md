# Built-in Functions

## `print`

```nex
print(...args)
```

Writes all arguments to interpreter output as a space-separated line. Returns `nil`.

## `println`

```nex
println(...args)
```

Same output behavior as `print` in the current interpreter. Returns `nil`.

## `type_of`

```nex
type_of(value): String
```

Returns the runtime type name.

## `type_is`

```nex
type_is(type_name: String, value): Boolean
```

Checks runtime type compatibility (including `Any` as the universal root type,
built-in inheritance relationships like scalar types implementing
`Comparable`/`Hashable`, and cursor types matching `Cursor`).

## `sleep`

```nex
sleep(ms: Integer)
```

Blocks the current task for approximately `ms` milliseconds. Returns `nil`.

`ms` must be a non-negative integer.

## `http_get`

```nex
http_get(url: String): Http_Response
http_get(url: String, timeout_ms: Integer): Http_Response
```

Performs an HTTP GET request and returns an `Http_Response` object.

Rules:

- `url` must be a string
- `timeout_ms`, when provided, must be a non-negative integer
- network failures are raised as runtime errors

This primitive is intended to support `lib/net/http_client.nex`.

## `json_parse`

```nex
json_parse(text: String): Any
```

Parses JSON text into Nex values.

Returned value mapping:

- object -> `Map[String, Any]`
- array -> `Array[Any]`
- integer -> `Integer` or `Integer64`
- decimal/exponent -> `Real`
- `null` -> `nil`

This primitive is intended to support `lib/data/json.nex`.

## `json_stringify`

```nex
json_stringify(value: Any): String
```

Serializes Nex `Map`, `Array`, scalar values, and `nil` into JSON text.

This primitive is intended to support `lib/data/json.nex`.

## `http_server_create`

```nex
http_server_create(port: Integer)
```

Creates an opaque HTTP server handle used by `lib/net/http_server.nex`.

## `http_server_get`

```nex
http_server_get(handle, path: String, handler: Function)
```

Registers a GET route on an opaque server handle.

## `http_server_post`

```nex
http_server_post(handle, path: String, handler: Function)
```

Registers a POST route on an opaque server handle.

## `http_server_put`

```nex
http_server_put(handle, path: String, handler: Function)
```

Registers a PUT route on an opaque server handle.

## `http_server_delete`

```nex
http_server_delete(handle, path: String, handler: Function)
```

Registers a DELETE route on an opaque server handle.

## `http_server_start`

```nex
http_server_start(handle): Integer
```

Starts an opaque server handle and returns the bound port.

## `http_server_stop`

```nex
http_server_stop(handle)
```

Stops an opaque server handle.

## `http_server_is_running`

```nex
http_server_is_running(handle): Boolean
```

Reports whether an opaque server handle is currently listening.

## `http_post`

```nex
http_post(url: String, body_text: String): Http_Response
http_post(url: String, body_text: String, timeout_ms: Integer): Http_Response
```

Performs an HTTP POST request with a text body and returns an `Http_Response` object.

Rules:

- `url` must be a string
- `body_text` must be a string
- `timeout_ms`, when provided, must be a non-negative integer
- network failures are raised as runtime errors

This primitive is intended to support `lib/net/http_client.nex`.

## `await_any`

```nex
await_any(tasks: Array[Task[T]]): T
```

Waits for the first task in `tasks` to complete and returns its result.

Rules:

- `tasks` must be an array of tasks
- an empty array is an error
- if the first completed task failed, the failure is re-raised

## `await_all`

```nex
await_all(tasks: Array[Task[T]]): Array[T]
```

Waits for all tasks in `tasks` and returns an array of results in input order.

Rules:

- `tasks` must be an array of tasks
- if a task fails, the failure is re-raised when collecting results

## Examples

```nex
print("hello", 42, true)
println("done")

let x := 12
print(type_of(x))                 -- "Integer"
print(type_is("Comparable", x))   -- true
print(type_is("String", x))       -- false

let response: Http_Response := http_get("https://example.com")
print(response.status())

let fast: Task[Integer] := spawn do
  result := 2
end

let slow: Task[Integer] := spawn do
  sleep(10)
  result := 1
end

print(await_any([slow, fast]))    -- 2
print(await_all([slow, fast]))    -- [1, 2]
```
