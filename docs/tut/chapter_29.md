# Concurrency with Tasks and Channels

So far the tutorial has treated a Nex program as one thread of control: a function calls another, a method updates an object, and the next statement waits for the previous one to finish. Many real systems do not have that luxury. A server may need to wait for incoming requests while also writing logs. A pipeline may need one stage to produce data while another stage consumes it. A user interface may need background work without freezing the screen.

Nex approaches concurrency with a small set of explicit tools:

- `spawn` starts a task
- `Task` represents work in progress
- `Channel[T]` moves values between tasks
- `select` waits for whichever communication or task completion becomes ready first

The design goal is clarity. Concurrency is difficult enough already. The language should help you say what is running concurrently, how results are collected, and where coordination happens.


## Why Tasks Instead of Shared Objects

There are two broad ways to structure concurrent programs.

One style shares mutable objects and synchronises access with locks, monitors, or similar mechanisms. That can work, but it pushes a great deal of reasoning burden onto the programmer. Every shared variable becomes a potential source of races, stale reads, or deadlocks.

Nex takes a different starting point:

- start work in explicit tasks
- communicate through channels
- wait at explicit coordination points

This does not remove all concurrency problems, but it keeps the structure visible. When you read the code, you can see where work is launched and where data crosses from one task to another.


## Starting a Task with `spawn`

`spawn do ... end` starts a new task and returns a `Task` value.

```
let t: Task[Integer] := spawn do
  result := 40 + 2
end

print(t.await)
```

The body of the task runs concurrently with the caller. If the body assigns to `result`, the task has a result type such as `Task[Integer]`. If the body does not assign to `result`, the type is just `Task`.

```
let t: Task := spawn do
  print("background work")
end

t.await
```

A task is a value. You can store it in a variable, return it from a routine, place it in an array, or pass it to another routine. That matters because concurrency should compose. We do not want a special hidden execution model that only works in toy cases.


## What `await` Means

`await` is the point where the caller synchronises with a task.

```
let t: Task[String] := spawn do
  result := "report ready"
end

let msg: String := t.await
print(msg)
```

A few points are important.

First, `await` blocks until the task finishes, unless you use the timeout form that we will see later.

Second, if the task failed by raising an exception, `await` re-raises that failure. The error is not lost just because the work happened concurrently.

Third, `await` is an explicit boundary. Before it, the task may still be running. After it, the task has either completed successfully or failed.

That explicit boundary is one of the central design ideas in Nex concurrency. Synchronisation should be visible in the source code.


## Checking Task State

Tasks also support status queries.

```
let t: Task[Integer] := spawn do
  result := 10 * 10
end

print(t.is_done)
print(t.await)
print(t.is_done)
```

In real code, `is_done` is most useful when combined with `select`, or when you need a non-blocking readiness test before choosing the next step.

Tasks can also be cancelled:

```
let t: Task := spawn do
  sleep(10)
end

print(t.cancel)
print(t.is_cancelled)
```

Cancellation is a request, not magic. It tells the runtime that the task's result is no longer wanted. The exact behavior depends on the target runtime, which we will discuss later in this chapter.

If a cancelled task is later awaited, `await` raises a task-cancelled failure rather than returning a normal result.


## Waiting with a Timeout

Sometimes indefinite waiting is not acceptable. A program may want to give up after a bounded interval.

Tasks support a timed form of `await`:

```
let t: Task[Integer] := spawn do
  sleep(5)
  result := 7
end

print(t.await(1))
print(t.await(50))
```

If the task completes within the timeout, `await(ms)` returns the task result. If it does not, it returns `nil`.

This design keeps the meaning simple:

- `await()` means wait as long as needed
- `await(ms)` means wait up to `ms` milliseconds

The timeout form is especially useful at system boundaries, where a stalled background action should not block the entire program indefinitely.


## Channels: Communication Between Tasks

Launching tasks is only half of the problem. The other half is communication.

Nex uses typed channels for that purpose.

```
let ch: Channel[Integer] := create Channel[Integer]

let producer: Task := spawn do
  ch.send(42)
end

print(ch.receive)
producer.await
```

A `Channel[T]` carries values of type `T`.

In the example above:

- the producer task sends an integer
- the main thread receives it
- both sides agree on the element type because the channel itself is typed

That type information matters. Concurrency already adds one level of indirection. The language should not also force the programmer to guess what kind of value may arrive.


## Unbuffered and Buffered Channels

The default constructor creates an unbuffered channel:

```
let ch: Channel[Integer] := create Channel[Integer]
```

An unbuffered channel is a rendezvous point:

- `send` waits until some receiver is ready
- `receive` waits until some sender is ready

This is a very strong coordination mechanism. The send and the receive meet at one point in time.

Sometimes that is too strict. One side may need to run ahead a little. For that, Nex supports buffered channels:

```
let ch: Channel[Integer] := create Channel[Integer].with_capacity(2)
ch.send(10)
ch.send(20)
print(ch.size)
print(ch.capacity)
```

For a buffered channel:

- `send` succeeds immediately when the buffer has space
- `send` waits only when the buffer is full
- `receive` succeeds immediately when a buffered value exists
- `receive` waits only when the buffer is empty

This gives you a controlled queue between tasks.


## Non-Blocking Channel Operations

Blocking is sometimes right and sometimes not. When you need to probe a channel without waiting, Nex provides `try_send` and `try_receive`.

```
let ch: Channel[Integer] := create Channel[Integer].with_capacity(1)
print(ch.try_send(5))
print(ch.try_send(6))
print(ch.try_receive)
print(ch.try_receive)
```

The meaning is:

- `try_send(v)` returns `true` if the value was sent immediately, otherwise `false`
- `try_receive` returns a value if one is immediately available, otherwise `nil`

These operations are the building blocks for more flexible coordination logic, including `select`.


## Closing a Channel

Channels can be closed.

```
let ch: Channel[Integer] := create Channel[Integer].with_capacity(2)
ch.send(1)
ch.close
print(ch.is_closed)
print(ch.receive)
```

After `close`:

- future sends are rejected
- buffered values, if any, can still be received
- once a closed channel is drained, later `receive` attempts fail

Closing matters because streams of values are rarely infinite in practice. A reader often needs a definite signal that no more messages are coming.


## Coordinating Several Tasks with `await_any` and `await_all`

A single task is useful, but real programs often launch groups of related tasks.

`await_any` waits for the first task in a group to complete:

```
let slow: Task[Integer] := spawn do
  sleep(20)
  result := 10
end

let fast: Task[Integer] := spawn do
  result := 20
end

print(await_any([slow, fast]))
```

`await_all` waits for every task and returns their results in input order:

```
let a: Task[Integer] := spawn do
  result := 1
end

let b: Task[Integer] := spawn do
  result := 2
end

let results: Array[Integer] := await_all([a, b])
print(results.get(0))
print(results.get(1))
```

These operations keep the coordination logic explicit without forcing the programmer to hand-write loops over task arrays every time.


## `select`: Waiting for Whatever Becomes Ready

When several channels or tasks may become ready, the right tool is `select`.

```
let ch: Channel[String] := create Channel[String].with_capacity(1)
ch.send("done")

select
  when ch.receive as msg then
    print(msg)
  else
    print("nothing ready")
end
```

A `select` statement probes its clauses in order and chooses one that is ready.

If several clauses are ready at the same time, the earlier clause wins. Clause order therefore expresses priority, not just layout.

For channels:

- `when ch.receive as x then ...` fires when a value can be received immediately
- `when ch.send(v) then ...` fires when the send can proceed immediately

For tasks:

- `when task.await as x then ...` fires only when the task is already done

This last point is important. A task clause in `select` is a readiness check, not a blocking wait hidden inside the clause. The clause is eligible only when the task has completed.

That keeps the logic of `select` consistent: it chooses among ready operations.


## `select` with `timeout`

A `select` can also wait up to a bounded interval.

```
let ch: Channel[String] := create Channel[String]

select
  when ch.receive as msg then
    print(msg)
  timeout 5 then
    print("timed out")
end
```

This means:

- if some clause becomes ready before the timeout, run it
- otherwise run the timeout branch

A timeout branch is different from `else`.

- `else` means do not wait at all
- `timeout n` means wait up to `n` milliseconds

That distinction matters. One is a non-blocking probe. The other is a bounded wait.


## A Small Pipeline Example

The following example shows tasks and channels working together.

```
let input: Channel[Integer] := create Channel[Integer].with_capacity(4)
let output: Channel[Integer] := create Channel[Integer].with_capacity(4)

let worker: Task := spawn do
  let v: Integer := input.receive
  output.send(v * v)
end

input.send(9)
print(output.receive)
worker.await
```

Even in this tiny example, the roles are clear:

- the channel defines how values move
- the task defines what work happens concurrently
- the main thread decides when to send, receive, and await completion

That is the kind of clarity we want in larger programs too.

For a longer-running pipeline, `close` is usually part of the design as well. One stage eventually needs to signal that no more values will be sent, so downstream stages know when to stop receiving.


## Designing with Concurrency in Mind

A few design habits help immediately.

Prefer message passing to shared mutable state.

If two tasks need to coordinate, a channel is often clearer than a shared object updated from both sides.

Keep task boundaries meaningful.

Do not spawn tasks for tiny operations that would be simpler inline. Concurrency has overhead. Use it where there is real independent work or waiting.

Make waiting points explicit.

A call to `await`, `receive`, or `select` is a design decision. It says where control may pause.

Use timeouts at boundaries.

Background work, I/O-like coordination, and external services are common places where bounded waiting is healthier than waiting forever.

Test concurrency in small pieces.

A small producer-consumer example is easier to trust than a large concurrent design written in one pass.


## Target Semantics

The Nex source syntax is the same across targets, but the implementation strategy differs.

On the JVM:

- `spawn` uses an executor-backed task runtime
- `await` can block normally
- channels use blocking coordination underneath

In generated JavaScript:

- tasks and channels are implemented with Promise-based semantics
- generated JavaScript lowers concurrency operations to `async` and `await`
- the language-level meaning remains the same, even though JavaScript itself does not support ordinary blocking threads

This is a useful example of a general Nex idea: keep the source model stable while allowing the runtime implementation to fit the host platform.

What stays stable is the source-level meaning of the operations. Timing, scheduling, fairness, and timeout precision remain runtime concerns rather than guarantees of the language surface.

For a fuller discussion of the semantics and implementation details, see the repository's concurrency guide in `docs/md/CONCURRENCY.md`.


## Summary

- `spawn` starts explicit concurrent work and returns a `Task`
- `await` is the explicit point where a caller synchronises with a task
- `Channel[T]` moves typed values between concurrent activities
- unbuffered channels rendezvous; buffered channels decouple producers and consumers
- `try_send` and `try_receive` support non-blocking coordination
- `await_any` and `await_all` coordinate groups of tasks
- `select` chooses among ready task and channel operations
- `else` means no waiting; `timeout` means bounded waiting
- Nex keeps the source model stable across JVM and JavaScript targets


## Exercises

**1.** Write a task that computes the sum of the integers from `1` to `100`, returns the answer through `result`, and print it using `await`.

**2.** Create a buffered `Channel[String]` with capacity `3`. Send three words into it, print `size`, then receive and print the words in order.

**3.** Write two tasks that each send a message on a channel. Use `select` to receive whichever message becomes ready first.

**4.** Write a small example that uses `await_all` to collect three independent computations.

**5.** Extend the pipeline example so that it processes several values rather than one. Decide where `close` should happen and how the receiving side knows when to stop.

**6.\*** Design a tiny two-stage pipeline, such as "read values, transform them, collect results," using two channels and at least one worker task. Explain where blocking may happen and why.
