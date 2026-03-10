# Built-in Types and Operations

This appendix summarizes the interpreter-level built-ins documented in `docs/ref`. It is not intended to replace the full reference pages, but to give one compact place to look up the core types used throughout the tutorial.


## Global Built-in Functions

| Name | Signature | Purpose |
|---|---|---|
| `print` | `print(...args)` | Write values to interpreter output. |
| `println` | `println(...args)` | Line-oriented output; currently same behavior as `print` in the interpreter. |
| `type_of` | `type_of(value): String` | Return runtime type name. |
| `type_is` | `type_is(type_name: String, value): Boolean` | Check runtime type compatibility. |


## Foundational Protocol Types

| Type | Main Features | Notes |
|---|---|---|
| `Function` | `call0` through `call32` | Invocation protocol for function-like values. |
| `Comparable` | `compare` | Ordering support for scalars and other comparable values. |
| `Hashable` | `hash` | Required for map keys. |
| `Cursor` | `start`, `item`, `next`, `at_end` | Iteration protocol used by `across`. |


## Scalar Types

### `String`

Common operations:

- `length`
- `index_of`
- `substring(start, end)`
- `to_upper`, `to_lower`
- `to_integer`, `to_integer64`, `to_real`, `to_decimal`
- `contains`, `starts_with`, `ends_with`
- `trim`, `replace`
- `char_at`
- `split`
- `compare`, `hash`, `cursor`

Example:

```nex
let s: String := "  Nex  "
print(s.trim().to_upper())   -- "NEX"
print(s.split(" "))          -- array of pieces
```

### `Integer`

Common operations:

- `to_string`
- `abs`, `min`, `max`
- `pick`
- `bitwise_left_shift`, `bitwise_right_shift`, `bitwise_logical_right_shift`
- `bitwise_rotate_left`, `bitwise_rotate_right`
- `bitwise_is_set`, `bitwise_set`, `bitwise_unset`
- `bitwise_and`, `bitwise_or`, `bitwise_xor`, `bitwise_not`
- `plus`, `minus`, `times`, `divided_by`
- `compare`, `hash`

Bitwise operations use 32-bit integer semantics. Bit `0` is the least-significant
bit. When calling a bitwise method on an integer literal, wrap the literal in
parentheses:

```nex
print((5).bitwise_left_shift(1))   -- 10
print((6).bitwise_and(3))          -- 2
print((5).bitwise_is_set(0))       -- true
```

### `Integer64`

Common operations parallel `Integer`:

- `to_string`
- `abs`, `min`, `max`
- arithmetic operations
- `compare`, `hash`

### `Real`

Common operations:

- `to_string`
- `abs`, `min`, `max`, `round`
- arithmetic operations
- `compare`, `hash`

### `Decimal`

Common operations:

- `to_string`
- `abs`, `min`, `max`, `round`
- arithmetic operations
- `compare`, `hash`

### `Boolean`

Common operations:

- `to_string`
- `and`, `or`, `not`
- `compare`, `hash`

### `Char`

Common operations:

- `to_string`
- `to_upper`, `to_lower`
- `compare`, `hash`


## Collection Types

### `Array[T]`

Construction:

```nex
[]
```

Main methods:

| Method | Purpose |
|---|---|
| `get(index)` | Read element at index. |
| `add(value)` | Append value. |
| `add_at(index, value)` | Insert value. |
| `put(index, value)` | Replace value at index. |
| `length` | Element count. |
| `is_empty` | Check emptiness. |
| `contains(elem)` | Membership test. |
| `index_of(elem)` | First index or `-1`. |
| `remove(index)` | Remove and return element. |
| `reverse` | Return reversed array. |
| `sort` | Sort array. |
| `slice(start, end)` | Subrange. |
| `cursor` | Iterator for `across`. |

### `Map[K, V]`

Construction:

```nex
{}
```

Main methods:

| Method | Purpose |
|---|---|
| `get(key)` | Read value for key. |
| `try_get(key, default)` | Read value or fallback. |
| `put(key, value)` | Add or replace an entry. |
| `size` | Number of entries. |
| `is_empty` | Check emptiness. |
| `contains_key(key)` | Membership by key. |
| `keys` | Array of keys. |
| `values` | Array of values. |
| `remove(key)` | Delete entry. |
| `cursor` | Iterator over entries. |

### `Set[T]`

Construction:

```text
create Set[Integer]
create Set[Integer].from_array([1, 2, 3])
{1, 2, 3}
```

Notes:

- Non-empty brace literals such as `{1, 2, 3}` create sets.
- The empty literal `{}` creates an empty map, not an empty set.

Main methods:

| Method | Purpose |
|---|---|
| `contains(value)` | Membership test. |
| `union(other)` | Set union. |
| `difference(other)` | Elements in this set but not in `other`. |
| `intersection(other)` | Common elements. |
| `symmetric_difference(other)` | Elements that occur in exactly one set. |
| `size` | Number of elements. |
| `is_empty` | Check emptiness. |
| `cursor` | Iterator for `across`. |

### `Stack[T]`

`Stack[T]` is not a built-in collection type. It is the standard tutorial example
of a user-defined generic collection class built on top of `Array[T]`.

Typical operations:

| Method | Purpose |
|---|---|
| `push(value)` | Add an element to the top. |
| `pop()` | Remove and return the top element. |
| `peek()` | Return the top element without removing it. |
| `is_empty()` | Check emptiness. |
| `size()` | Number of stored elements. |


## Cursor Types

Concrete cursor classes:

- `ArrayCursor`
- `StringCursor`
- `MapCursor`
- `SetCursor`

They implement the `Cursor` protocol and are usually used indirectly through `across`.


## System Classes

### `Console`

Construction:

```text
create Console
```

Main methods:

- `print`
- `print_line`
- `read_line`
- `error`
- `new_line`
- `read_integer`
- `read_real`

### `File`

Construction:

```text
create File.open(path)
```

Main methods:

- `read`
- `write`
- `append`
- `exists`
- `delete`
- `lines`
- `close`

### `Process`

Construction:

```text
create Process
```

Main methods:

- `getenv`
- `setenv`
- `command_line`


## Graphics Classes

### `Window`

Construction:

```text
create Window
create Window.with_title(title)
create Window.with_title(title, width, height)
```

Common operations:

- opening and closing windows
- drawing lines, rectangles, circles, and text
- drawing images
- setting color, font size, and background
- refreshing the surface

### `Turtle`

Construction:

```text
create Turtle.on_window(window)
```

Common operations:

- `forward`, `backward`
- `left`, `right`
- `penup`, `pendown`
- `color`, `pensize`, `speed`
- `goto`, `circle`
- `begin_fill`, `end_fill`
- `xpos`, `ypos`

### `Image`

Construction:

```text
create Image.from_file(path)
```

Main methods:

- `width`
- `height`


## Practical Notes

- All scalar types are modeled as `Comparable` and `Hashable`.
- Array element count is `length`; map entry count is `size`.
- Built-in method names in this appendix match the interpreter-level names.
- Some system behavior differs between JVM and JavaScript runtimes, especially for file and process access.

For the fuller per-type reference, see the pages under `docs/ref/`.
