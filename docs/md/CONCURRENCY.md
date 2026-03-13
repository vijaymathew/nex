# Concurrency in Nex

Nex concurrency is based on three ideas:

- `spawn` creates a lightweight task
- `Task` represents the lifecycle and result of concurrent work
- `Channel[T]` is the coordination primitive for exchanging values between tasks

This document covers both the surface syntax and the semantics that the current implementation actually provides on each target.

## Model

Nex uses a task-and-channel model rather than thread primitives in the language surface.

The intended programming style is:

- start concurrent work with `spawn`
- communicate with `Channel[T]`
- synchronize explicitly with `Task.await`, `await_any`, `await_all`, and `select`

This keeps concurrency visible at the call site instead of hiding it behind ordinary object method calls.

## `spawn`

`spawn` starts a task and returns a `Task` value.

If the body assigns `result`, the static type is `Task[T]`:

```nex
let t: Task[Integer] := spawn do
  result := 40 + 2
end
```

If the body does not assign `result`, the type is plain `Task`:

```nex
let t: Task := spawn do
  print("background work")
end
```

### Spawn result typing

The typechecker infers the task result type from assignments to `result` inside the spawn body.

Rules:

- no `result` assignment -> `Task`
- one consistent result type -> `Task[T]`
- incompatible result assignments -> type error

Example:

```nex
let t: Task[String] := spawn do
  result := "done"
end
```

## `Task`

`Task` supports these operations:

- `await`
- `await(ms)`
- `cancel`
- `is_done`
- `is_cancelled`

### `await`

`await` blocks until the task completes and returns its result.

```nex
let t: Task[Integer] := spawn do
  result := 10
end

print(t.await)   -- 10
```

If the task fails, `await` re-raises the task failure.

### Timed `await`

`await(ms)` waits up to `ms` milliseconds.

Behavior:

- result available before timeout -> return the result
- timeout expires first -> return `nil`
- task fails before timeout -> raise the failure
- task was cancelled -> raise task-cancelled failure

Example:

```nex
let t: Task[Integer] := spawn do
  sleep(100)
  result := 1
end

print(t.await(10))   -- nil
```

### Cancellation

`cancel` requests cancellation of a task and returns `true` if the task was cancelled before it finished.

```nex
let t: Task := spawn do
  sleep(1000)
end

print(t.cancel)
print(t.is_cancelled)
```

Current semantics:

- cancellation is best-effort
- a successful cancellation marks the task as cancelled
- a later `await` raises `"Task cancelled"`

Nex does not currently expose a separate cancellation token API. Cancellation is task-level only.

### Task groups

Nex also provides two top-level helpers:

- `await_any([t1, t2, ...])`
- `await_all([t1, t2, ...])`

#### `await_any`

Waits for the first task in the group to complete and returns that task's result.

```nex
let t1: Task[Integer] := spawn do
  sleep(10)
  result := 1
end

let t2: Task[Integer] := spawn do
  result := 2
end

print(await_any([t1, t2]))   -- 2
```

Rules:

- argument must be `Array[Task[T]]`
- empty array is a runtime error
- if the first completed task failed, the failure is raised

#### `await_all`

Waits for all tasks and returns an array of results in input order.

```nex
print(await_all([t1, t2]))   -- [1, 2]
```

Rules:

- argument must be `Array[Task[T]]`
- result order matches task order in the input array
- if any awaited task fails, the failure is raised when that task is awaited

## `Channel[T]`

`Channel[T]` is a typed communication primitive.

Construction:

```nex
let ch1: Channel[Integer] := create Channel[Integer]
let ch2: Channel[Integer] := create Channel[Integer].with_capacity(2)
```

Capacity rules:

- `create Channel[T]` -> capacity `0` -> unbuffered channel
- `create Channel[T].with_capacity(n)` -> buffered channel of size `n`

Negative capacities are rejected.

### Unbuffered channels

An unbuffered channel is a rendezvous channel.

Behavior:

- `send(value)` waits for a receiver
- `receive` waits for a sender

Example:

```nex
let ch: Channel[Integer] := create Channel[Integer]

spawn do
  ch.send(42)
end

print(ch.receive)   -- 42
```

### Buffered channels

A buffered channel stores values up to its capacity.

Behavior:

- `send(value)` succeeds immediately if buffer has space
- `send(value)` blocks if buffer is full
- `receive` succeeds immediately if buffer has data
- `receive` blocks if buffer is empty

Example:

```nex
let ch: Channel[Integer] := create Channel[Integer].with_capacity(2)
ch.send(10)
ch.send(20)
print(ch.size)       -- 2
print(ch.capacity)   -- 2
```

### Channel operations

- `send(value)`
- `send(value, ms)`
- `try_send(value)`
- `receive`
- `receive(ms)`
- `try_receive`
- `close`
- `is_closed`
- `capacity`
- `size`

#### `send`

`send(value)` blocks until the value is accepted.

`send(value, ms)` waits up to `ms` milliseconds and returns:

- `true` on success
- `false` on timeout

#### `try_send`

Attempts an immediate send.

Returns:

- `true` if the send succeeds immediately
- `false` if it would block

#### `receive`

`receive` blocks until a value is available.

`receive(ms)` waits up to `ms` milliseconds and returns:

- the received value
- `nil` on timeout

#### `try_receive`

Attempts an immediate receive.

Returns:

- a value if available immediately
- `nil` if it would block

### Channel close semantics

`close` closes the channel for future sends.

Rules:

- further sends are rejected
- buffered values can still be received after close
- once the channel is closed and drained, `receive` raises an error

Example:

```nex
let ch: Channel[Integer] := create Channel[Integer].with_capacity(1)
ch.send(10)
ch.close
print(ch.receive)     -- 10
ch.receive            -- error: closed and empty
```

## `select`

`select` waits for whichever clause becomes ready first.

Current clause forms:

- channel receive clauses
- channel send clauses
- task completion clauses

Example:

```nex
select
  when jobs.receive as job then
    print(job)
  when control.send("tick") then
    print("sent")
  when worker.await as value then
    print(value)
  timeout 100 then
    print("timed out")
  else
    print("idle")
end
```

### Select semantics

`select` is implemented as repeated readiness probing.

For channels:

- receive clauses probe with `try_receive`
- send clauses probe with `try_send`

For tasks:

- task clauses must use `Task.await`
- readiness is determined by `Task.is_done`
- the clause only fires after the task has already completed
- once ready, `await` is used to consume the result

This is important:

- `select` does not block inside a task clause waiting for task completion
- instead, it polls task completion and preserves the same "first ready clause wins" model used for channels

### Clause ordering

Clauses are checked in source order on each probe cycle.

If multiple clauses are simultaneously ready, the first ready clause in source order is selected.

### `else`

If no clause is ready and an `else` clause exists, `else` runs immediately.

This means:

- `else` is not a fallback after timeout
- it is an immediate "nothing is ready right now" branch

### `timeout`

If there is no `else`, `select` can use a timeout:

```nex
select
  when ch.receive as x then
    print(x)
  timeout 100 then
    print("timeout")
end
```

Rules:

- timeout value must be a non-negative `Integer`
- timeout branch runs only if no clause became ready before the deadline
- if `else` is present, `else` wins immediately and `timeout` is never reached

## Typechecking rules

The typechecker enforces:

- `spawn` result consistency
- `await_any` / `await_all` require `Array[Task[T]]`
- `Task.await(ms)` timeout argument must be `Integer`
- `Channel.send(value, ms)` timeout argument must be `Integer`
- `Channel.receive(ms)` timeout argument must be `Integer`
- `select` task clauses support only `Task.await`
- `Task.await` inside `select` takes no arguments
- `send` clauses in `select` cannot bind an alias
- receive/task clauses may bind `as name`

## JVM implementation

On the Clojure interpreter and generated Java, Nex concurrency is backed by JVM threads.

### Interpreter

The interpreter uses:

- a shared cached thread pool created with `Executors.newCachedThreadPool()`
- `CompletableFuture` for task results
- lock-and-queue channel state

Task details:

- `spawn` submits work to the shared executor
- task result is collected from the spawn-local `result`
- cancellation maps to `CompletableFuture.cancel(true)`

Channel details:

- unbuffered channels maintain sender/receiver rendezvous queues
- buffered channels maintain a buffer plus waiting sender/receiver queues
- timed operations are implemented with blocking waits and deadlines

Select details:

- interpreter `select` loops
- probes each clause
- sleeps briefly between probe cycles when necessary

### Generated Java

Generated Java emits runtime helpers inside `NexRuntime`, including:

- `Task<T>`
- `Channel<T>`
- `spawnTask`
- `awaitAny`
- `awaitAll`

Generated Java `select` lowers to a polling loop:

- `try_send`
- `try_receive`
- `task.is_done()`
- `Thread.sleep(1)` between probe iterations when no clause is ready and there is no `else`

## JavaScript implementation

Generated JavaScript preserves the Nex source syntax but lowers concurrency to `async` / `await` and `Promise`-based helpers.

Generated runtime helpers include:

- `__nexTask`
- `__nexChannel`
- `__nexSpawn`
- `__nexAwaitAny`
- `__nexAwaitAll`
- `__nexSleep`

### Task model in generated JavaScript

- tasks are promise-backed
- `Task.await` lowers to `await task.await(...)`
- `await_any` lowers to `await __nexAwaitAny(...)`
- `await_all` lowers to `await __nexAwaitAll(...)`

Current generated JS implementation:

- `await_any` uses `Promise.race(tasks.map(task => task.await()))`
- `await_all` awaits tasks in input order and returns an array of results

### Channel model in generated JavaScript

Channels are promise-backed objects:

- unbuffered channels rendezvous through waiting sender/receiver queues
- buffered channels use an in-memory buffer plus waiting sender/receiver queues
- timed operations use timers and promise resolution/rejection

### Select in generated JavaScript

Generated JS `select` lowers to:

- a `while (true)` probe loop
- channel clauses via `try_send` / `try_receive`
- task clauses via `is_done()` followed by `await task.await()`
- `await __nexSleep(0)` between iterations when no clause is ready and there is no `else`

## Failure semantics

Task failure:

- failure inside a task is stored in the task
- `await` re-raises it
- `await_any` raises it if the first completed task failed
- `await_all` raises it when the failing task is awaited during collection

Channel failure:

- sending on a closed channel raises an error
- receiving from a closed and drained channel raises an error

Select failure:

- a selected task clause may raise if the completed task failed
- channel clauses follow channel operation semantics after selection

## Timing semantics

Timeouts are expressed in milliseconds as non-negative integers.

Current timeout behavior:

- task timeout -> return `nil`
- receive timeout -> return `nil`
- send timeout -> return `false`
- select timeout -> run timeout body

Timeouts are cooperative runtime timeouts, not real-time guarantees.

## Ordering guarantees

What Nex guarantees today:

- `await_all` returns results in input order
- buffered channels preserve FIFO buffer order
- unbuffered channel rendezvous respect the runtime's waiting queues
- `select` chooses the first ready clause in source order during a probe cycle

What Nex does not currently guarantee explicitly:

- fairness across all tasks and channels under heavy contention
- starvation freedom
- structured concurrency scopes
- cancellation propagation trees

## Current limitations

Significant missing pieces:

- no structured concurrency
- no parent-child task scope management
- no separate cancellation token API
- `select` cannot wait on arbitrary expressions; only supported channel/task forms
- no task-specific `select` combinators such as `await_first_error`
- no scheduler tuning API

The current system is already usable, but it is still a primitive-based concurrency model rather than a full structured concurrency framework.

## Recommended usage style

Use concurrency in Nex in this order of preference:

1. spawn pure or mostly-isolated work
2. exchange values through channels
3. use `select` only when you truly need multi-source readiness
4. prefer `await_all` for bulk joins and `await_any` for first-result wins
5. keep timeouts explicit at the synchronization point

That style matches the implementation and avoids relying on guarantees Nex does not currently claim.
