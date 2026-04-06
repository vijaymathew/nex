# System Classes

## `Console`

### Construction

```nex
create Console
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `print` | `msg: Any` | `Void` | Write message without extra formatting. |
| `print_line` | `msg: Any` | `Void` | Write message as a line. |
| `read_line` | `prompt?: String` | `String` | Read one line from input. |
| `error` | `msg: Any` | `Void` | Write to error output. |
| `new_line` | none | `Void` | Emit blank line. |
| `read_integer` | none | `Integer` | Read and parse integer. |
| `read_real` | none | `Real` | Read and parse real number. |

## `Process`

### Construction

```nex
create Process
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `getenv` | `name: String` | `String` | Read environment variable (empty if missing). |
| `setenv` | `name: String, value: String` | `Void` | Set environment variable (platform dependent). |
| `command_line` | none | `Array[String]` | Return command-line arguments. |

## `Task`

`Task` values are returned by `spawn`.

### Construction

```nex
let t: Task[Integer] := spawn do
  result := 42
end
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `await` | none | `T` | Wait until the task completes and return its result. |
| `await` | `ms: Integer` | `?T` | Wait up to `ms` milliseconds and return `nil` on timeout. |
| `cancel` | none | `Boolean` | Request cancellation of the task. |
| `is_done` | none | `Boolean` | True if the task has finished. |
| `is_cancelled` | none | `Boolean` | True if the task was cancelled. |

### Notes

- If a task fails, `await` re-raises the failure.
- If a cancelled task is awaited, a task-cancelled failure is raised.
- `await(ms)` returns `nil` only for timeout; failure and cancellation still raise.

## `Channel[T]`

`Channel[T]` is Nex's built-in coordination primitive for exchanging values between tasks.

### Construction

```nex
create Channel[Integer]
create Channel[Integer].with_capacity(2)
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `send` | `value: T` | `Void` | Send a value, blocking until accepted. |
| `send` | `value: T, ms: Integer` | `Boolean` | Send with timeout; returns `false` on timeout. |
| `try_send` | `value: T` | `Boolean` | Attempt immediate send. |
| `receive` | none | `T` | Receive a value, blocking until available. |
| `receive` | `ms: Integer` | `?T` | Receive with timeout; returns `nil` on timeout. |
| `try_receive` | none | `?T` | Attempt immediate receive. |
| `close` | none | `Void` | Close the channel for future sends. |
| `is_closed` | none | `Boolean` | True if the channel is closed. |
| `capacity` | none | `Integer` | Channel capacity. |
| `size` | none | `Integer` | Number of buffered values. |

### Notes

- `create Channel[T]` creates an unbuffered channel.
- `create Channel[T].with_capacity(n)` creates a buffered channel with capacity `n`.
- Sends on a closed channel fail.
- Buffered values remain receivable after `close`.
- Once a channel is closed and drained, `receive` fails.

## `Atomic_Integer`

### Construction

```nex
create Atomic_Integer.make(0)
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `load` | none | `Integer` | Read the current value. |
| `store` | `value: Integer` | `Void` | Replace the current value. |
| `compare_and_set` | `expected: Integer, update: Integer` | `Boolean` | Replace the value only if it currently matches `expected`. |
| `get_and_add` | `delta: Integer` | `Integer` | Return the old value, then add `delta`. |
| `add_and_get` | `delta: Integer` | `Integer` | Add `delta`, then return the new value. |
| `increment` | none | `Integer` | Increment by 1 and return the new value. |
| `decrement` | none | `Integer` | Decrement by 1 and return the new value. |

## `Atomic_Integer64`

### Construction

```nex
create Atomic_Integer64.make(0)
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `load` | none | `Integer64` | Read the current value. |
| `store` | `value: Integer64` | `Void` | Replace the current value. |
| `compare_and_set` | `expected: Integer64, update: Integer64` | `Boolean` | Replace the value only if it currently matches `expected`. |
| `get_and_add` | `delta: Integer64` | `Integer64` | Return the old value, then add `delta`. |
| `add_and_get` | `delta: Integer64` | `Integer64` | Add `delta`, then return the new value. |
| `increment` | none | `Integer64` | Increment by 1 and return the new value. |
| `decrement` | none | `Integer64` | Decrement by 1 and return the new value. |

## `Atomic_Boolean`

### Construction

```nex
create Atomic_Boolean.make(false)
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `load` | none | `Boolean` | Read the current value. |
| `store` | `value: Boolean` | `Void` | Replace the current value. |
| `compare_and_set` | `expected: Boolean, update: Boolean` | `Boolean` | Replace the value only if it currently matches `expected`. |

## `Atomic_Reference[T]`

### Construction

```nex
create Atomic_Reference[String].make("ready")
create Atomic_Reference.make(nil)
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `load` | none | `?T` | Read the current reference value. |
| `store` | `value: ?T` | `Void` | Replace the current reference value. |
| `compare_and_set` | `expected: ?T, update: ?T` | `Boolean` | Replace the value only if it currently matches `expected`. |

### Notes

- `Atomic_Reference[T]` can store `nil`.
- `compare_and_set` for `Atomic_Reference[T]` uses Nex value equality at the language level.

## Examples

```nex
let con := create Console
con.print_line("Enter your name:")
let name := con.read_line()
con.print_line("Hello, " + name)

intern io/Path
let path: Path := create Path.make("notes.txt")
path.write_text("line 1")
path.append_text("\nline 2")
print(path.exists())
print(path.read_text())

let p := create Process
print(p.getenv("HOME"))
print(p.command_line())

let t: Task[Integer] := spawn do
  result := 42
end
print(t.await)

let ch: Channel[Integer] := create Channel[Integer].with_capacity(1)
print(ch.try_send(7))
print(ch.receive)

let ai := create Atomic_Integer.make(0)
print(ai.increment)
print(ai.compare_and_set(1, 5))
print(ai.load)

let ref: Atomic_Reference[String] := create Atomic_Reference.make("a")
print(ref.compare_and_set("a", "b"))
print(ref.load)
```
