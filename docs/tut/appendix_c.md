# The Standard Library

Nex is a small language, but the runtime still provides a practical set of built-in classes and services. This appendix gives a tutorial-oriented overview of what is available at the library level and how those pieces fit together.

The material here is drawn from the runtime behavior documented in `docs/ref` and the supporting guides under `docs/md`.


## Core Runtime Services

The most commonly used built-in service classes are:

- `Console`
- `Process`
- `Window`
- `Turtle`
- `Image`

For filesystem and file I/O, use the `lib/io` library:

- `intern io/Path`
- `intern io/Directory`
- `intern io/Text_File`
- `intern io/Binary_File`

Together with the scalar and collection classes, they form the everyday standard environment of Nex programs.


## Console I/O

`Console` supports interactive text programs.

Construction:

```nex
let con: Console := create Console
```

Useful operations:

- `print(msg)`
- `print_line(msg)`
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

Filesystem operations lives in the `io` library.

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

```nex
intern io/Path
intern io/Directory

let root: Directory := create Directory.make("tmp")
root.create_tree()

let data: Directory := root.child_dir("data")
data.create_tree()

let file: Path := data.child_path("items.txt")
file.write_text("one\ntwo")

print(root.directories().length)
print(data.files().length)
```

```nex
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


## Graphics and Simple Visual Programs

Nex includes a lightweight graphics layer through `Window`, `Turtle`, and `Image`.

### `Window`

Use `Window` for drawing lines, rectangles, circles, text, and images.

```nex
let w: Window := create Window.with_title("Demo", 640, 360)
w.show()
w.bgcolor("white")
w.set_color("blue")
w.draw_rect(30, 30, 120, 80)
w.draw_text("Nex", 40, 70)
w.refresh()
```

### `Turtle`

Use `Turtle` for turtle-graphics style drawing.

```nex
let t: Turtle := create Turtle.on_window(w)
t.color("red")
t.forward(80)
t.right(120)
t.forward(80)
t.right(120)
t.forward(80)
```

### `Image`

Use `Image` to load pictures for drawing in a window.

```nex
let img: Image := create Image.from_file("sprite.png")
w.draw_image(img, 220, 100)
```


## Collections as Library Foundations

Much of the practical "standard library" feel of Nex comes from `Array` and `Map`.

Use arrays for:

- ordered sequences
- stacks and queues
- accumulation of results

Use maps for:

- lookups by key
- counters and tables
- grouped data

These classes are generic and work with user-defined classes just as naturally as with built-in scalar values.


## Cursors and `across`

The `across` loop depends on cursor types behind the scenes:

- `ArrayCursor`
- `StringCursor`
- `MapCursor`

You will usually not construct these directly. Their practical value is that they make one iteration form work uniformly across strings, arrays, and maps.


## Library Design Advice

Use the runtime library in layers.

At the core:

- plain functions
- classes with contracts
- arrays and maps

At the edge:

- console I/O
- files
- environment access
- graphics
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
| Collections | `Array`, `Map` |
| Type introspection | `type_of`, `type_is` |
| Files and environment | `Process`, `io/Path`, `io/Directory`, `io/Text_File`, `io/Binary_File` |
| Graphics | `Window`, `Turtle`, `Image` |

For exact method tables, see Appendix B and the files under `docs/ref/`.
