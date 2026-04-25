# The Standard Library

Nex is a small language, but the runtime still provides a practical set of built-in classes and services. This appendix gives a tutorial-oriented overview of what is available at the library level and how those pieces fit together.

The material here is drawn from the runtime behavior documented in `docs/ref` and the supporting guides under `docs/md`.


## Core Runtime Services

The most commonly used built-in service classes are:

- `Console`
- `Process`
- `Task`
- `Channel`

For filesystem and file I/O, use the `lib/io` library:

- `intern io/Path`
- `intern io/Directory`
- `intern io/Text_File`
- `intern io/Binary_File`

Together with the scalar and collection classes, they form the everyday standard environment of Nex programs.

Other important shipped libraries include:

- `time/Date_Time`
- `time/Duration`
- `text/Regex`
- `net/Http_Client`
- `net/Http_Server`


## Console I/O

`Console` supports interactive text programs.

Construction:

```nex
let con: Console := create Console
```

Useful operations:

- `print(msg)`
- `print_line(msg)`
- `flush()`
- `read_line(prompt?)`
- `error(msg)`
- `read_integer()`
- `read_real()`

Example:

```nex
let con: Console := create Console
con.print_line("What is your name?")
let name: String := con.read_line()
con.print_line("Hello, " + name)
```


## File Access

Filesystem operations live in the `io` library.

`Path` is the main entry point for filesystem probing and convenience file operations.

Construction:

```nex
intern io/Path

let p: Path := create Path.make("notes.txt")
```

Useful operations:

- `exists()`
- `is_file()`
- `is_directory()`
- `size()`
- `modified_time()`
- `read_text()`
- `write_text(text)`
- `append_text(text)`
- `copy_to(target)`
- `move_to(target)`
- `delete()`
- `delete_tree()`

Example:

```nex
intern io/Path

let src: Path := create Path.make("notes.txt")
src.write_text("line 1")
src.append_text("\nline 2")

let copy: Path := create Path.make("notes_copy.txt")
src.copy_to(copy)
print(copy.read_text())

let moved: Path := create Path.make("notes_moved.txt")
copy.move_to(moved)
print(moved.exists())
print(moved.size())
print(moved.modified_time())
```

For sequential text and binary access, use `Text_File` and `Binary_File`.

If your code is directory-oriented, use `Directory` as a thin wrapper over `Path`.

```text
intern io/Path
intern io/Directory

let root_path: Path := create Path.make("tmp")
if root_path.exists() then
  root_path.delete_tree()
end

let root: Directory := create Directory.make("tmp")
root.create_tree()

let data: Directory := root.child_dir("data")
data.create_tree()

let file: Path := data.child_path("items.txt")
file.write_text("one\ntwo")

let backup: Directory := root.child_dir("backup")
data.copy_to(backup)
print(backup.exists())

print(root.directories().length)
print(data.files().length)
```

```text
intern io/Path
intern io/Text_File

let path: Path := create Path.make("notes.txt")
let writer: Text_File := create Text_File.open_write(path)
writer.write_line("alpha")
writer.write_line("beta")
writer.close()
```

Use file routines at the boundary of the system. Core logic should usually operate on strings, arrays, maps, and classes rather than on filesystem objects directly.


## Process Information

`Process` exposes simple process-level state.

Construction:

```nex
let p: Process := create Process
```

Useful operations:

- `getenv(name)`
- `setenv(name, value)`
- `command_line()`

Example:

```nex
let p: Process := create Process
print(p.getenv("HOME"))
print(p.command_line())
```


## Tasks and Channels

Use `spawn` to start concurrent work and `Channel[T]` to move values between
tasks.

```nex
let jobs: Channel[String] := create Channel[String].with_capacity(2)

let worker: Task := spawn do
  let item := jobs.receive
  print("worker saw " + item)
end

jobs.send("compile docs")
worker.await
```

Channels can be buffered or unbuffered. Unbuffered channels synchronize sender
and receiver directly; buffered channels allow a limited number of queued
values. `select` lets one task wait on several channel operations at once.


## Time And Scheduling

Use `time/Date_Time` and `time/Duration` for UTC timestamps, scheduling offsets, and log formatting.

```nex
intern time/Duration
intern time/Date_Time

let started_at: Date_Time := create Date_Time.now()
let next_run: Date_Time := started_at.add(create Duration.minutes(15))
let weekly_cutoff: Date_Time := started_at.add(create Duration.weeks(1))

print("started at " + started_at.format_iso())
print("month=" + started_at.month_name())
print("weekday=" + started_at.weekday())
print("weekday-name=" + started_at.weekday_name())
print("day-of-year=" + started_at.day_of_year())
print("next run at " + next_run.truncate_to_hour().format_iso())
print("weekly cutoff " + weekly_cutoff.truncate_to_day().format_iso())
```

This is a better fit for logging and scheduling code than manually building timestamp strings.


## Pattern Matching And Text Cleanup

Use `text/Regex` when string operations alone are too weak for validation, token extraction, or cleanup.

```nex
intern text/Regex

let word: Regex := create Regex.compile_with_flags("[a-z]+", "i")
print(word.matches("Nex"))
print(word.find("123 Nex 456"))
print(word.find_all("one two THREE"))

let comma: Regex := create Regex.compile(",")
print(comma.split("a,b,c"))
print(word.replace("v1 test v2", "#"))
```

This is useful for:

- validating input formats
- extracting tokens from mixed text
- splitting delimited text
- performing cleanup and rewrite passes

Keep regex usage near parsing and validation boundaries. Higher-level domain logic should usually work on already structured values.


## HTTP and Network Services

The `net` library provides client and server building blocks when a Nex program
needs to talk over HTTP.

```nex
intern net/Http_Client

let client: Http_Client := create Http_Client.make()
let sample: Http_Response := create Http_Response.make(
  200,
  "ok",
  {"content-type": "text/plain"}
)

print(sample.status())
print(sample.body())
```

For server-side code, `Http_Server` and related request/response classes let a
program register handlers and return structured responses. This is host-backed
functionality, so exact behavior can differ between runtimes.


## Collections as Library Foundations

Much of the practical "standard library" feel of Nex comes from `Array`, `Map`,
and `Set`.

Use arrays for:

- ordered sequences
- stacks and queues
- accumulation of results

Use maps for:

- lookups by key
- counters and tables
- grouped data

Use sets for:

- membership tests
- removing duplicates
- set algebra such as union and intersection

These classes are generic and work with user-defined classes just as naturally as with built-in scalar values.


## Cursors and `across`

The `across` loop depends on cursor types behind the scenes:

- `ArrayCursor`
- `StringCursor`
- `MapCursor`
- `SetCursor`

You will usually not construct these directly. Their practical value is that they make one iteration form work uniformly across strings, arrays, and maps.


## Library Design Advice

Use the runtime library in layers.

At the core:

- plain functions
- classes with contracts
- arrays, maps, and sets

At the edge:

- console I/O
- files
- environment access
- imported host-platform code

This separation keeps the program testable and helps contracts remain meaningful.


## What Is Not Here

Nex does not try to ship a huge standard library inside the tutorial material. The core design assumes that:

- the language itself stays compact
- built-in services cover common educational and practical needs
- larger integration needs are handled through `intern` and `import`

That is why Chapter 24 matters. The standard library is enough to be productive, but it is not meant to be the whole world.


## Quick Reference

| Area | Main Built-ins |
|---|---|
| Output and input | `print`, `println`, `Console` |
| Text | `String`, `Char` |
| Numbers | `Integer`, `Integer64`, `Real`, `Decimal` |
| Collections | `Array`, `Map`, `Set` |
| Type introspection | `type_of`, `type_is` |
| Concurrency | `spawn`, `Task`, `Channel`, `select` |
| Files and environment | `Process`, `io/Path`, `io/Directory`, `io/Text_File`, `io/Binary_File` |
| Time and scheduling | `time/Date_Time`, `time/Duration` |
| Text processing | `text/Regex` |
| Networking | `net/Http_Client`, `net/Http_Server` |

For exact method tables, see Appendix B and the files under `docs/ref/`.
