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
