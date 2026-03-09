# The Standard Library

Nex is a small language, but the runtime still provides a practical set of built-in classes and services. This appendix gives a tutorial-oriented overview of what is available at the library level and how those pieces fit together.

The material here is drawn from the runtime behavior documented in `docs/ref` and the supporting guides under `docs/md`.


## Core Runtime Services

The most commonly used built-in service classes are:

- `Console`
- `File`
- `Process`
- `Window`
- `Turtle`
- `Image`

Together with the scalar and collection classes, they form the everyday standard environment of Nex programs.


## Console I/O

`Console` supports interactive text programs.

Construction:

```nex
let con := create Console
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
let con := create Console
con.print_line("What is your name?")
let name := con.read_line()
con.print_line("Hello, " + name)
```


## File Access

`File` supports basic file operations.

Construction:

```nex
let f := create File.open("notes.txt")
```

Useful operations:

- `read()`
- `write(content)`
- `append(content)`
- `exists()`
- `delete()`
- `lines()`
- `close()`

Example:

```nex
let f := create File.open("notes.txt")
f.write("line 1")
f.append("\nline 2")
print(f.lines())
```

Use file routines at the boundary of the system. Core logic should usually operate on strings, arrays, maps, and classes rather than on file handles directly.


## Process Information

`Process` exposes simple process-level state.

Construction:

```nex
let p := create Process
```

Useful operations:

- `getenv(name)`
- `setenv(name, value)`
- `command_line()`

Example:

```nex
let p := create Process
print(p.getenv("HOME"))
print(p.command_line())
```


## Graphics and Simple Visual Programs

Nex includes a lightweight graphics layer through `Window`, `Turtle`, and `Image`.

### `Window`

Use `Window` for drawing lines, rectangles, circles, text, and images.

```nex
let w := create Window.with_title("Demo", 640, 360)
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
let t := create Turtle.on_window(w)
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
let img := create Image.from_file("sprite.png")
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
| Files and environment | `File`, `Process` |
| Graphics | `Window`, `Turtle`, `Image` |

For exact method tables, see Appendix B and the files under `docs/ref/`.
