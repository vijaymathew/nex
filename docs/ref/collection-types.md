# Collection Types

This section covers the standard collection abstractions used throughout Nex code.
`Array`, `Map`, and `Set` are built-in collection types. `Stack[T]` is a common
generic collection class pattern built on top of `Array[T]`.

All built-in collection types inherit `Any`. Their `to_string`, `equals`, and
`clone` methods operate recursively: `to_string` renders nested structure,
`equals` performs deep structural equality, and `clone` performs a deep copy.

## `Array`

### Construction

```nex
[]
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `get` | `index: Integer` | `Any` | Read element at `index`. |
| `add` | `value: Any` | `Void` | Append value. |
| `add_at` | `index: Integer, value: Any` | `Void` | Insert value at index. |
| `put` | `index: Integer, value: Any` | `Void` | Replace element at index. |
| `length` | none | `Integer` | Number of elements. |
| `is_empty` | none | `Boolean` | True when array has no elements. |
| `contains` | `elem: Any` | `Boolean` | Membership test. |
| `index_of` | `elem: Any` | `Integer` | First index or `-1` if not found. |
| `remove` | `index: Integer` | `Any` | Remove element at index. |
| `reverse` | none | `Array[Any]` | Return reversed array. |
| `sort` | none | `Array[Any]` | Sort array in-place/runtime order. |
| `slice` | `start: Integer, end: Integer` | `Array[Any]` | Subrange from `start` to `end`. |
| `to_string` | none | `String` | Render the array and its nested values as text. |
| `equals` | `other: Any` | `Boolean` | Deep structural equality. |
| `clone` | none | `Array[T]` | Deep-copy the array and its nested values while preserving element type. |
| `cursor` | none | `ArrayCursor` | Create iterator. |

## `Map`

### Construction

```nex
{}
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `get` | `key: Any` | `Any` | Read value for key (fails if key missing). |
| `try_get` | `key: Any, default: Any` | `Any` | Read value or default if missing. |
| `put` | `key: Any, value: Any` | `Void` | Add/replace key-value entry. |
| `size` | none | `Integer` | Number of entries. |
| `is_empty` | none | `Boolean` | True when map has no entries. |
| `contains_key` | `key: Any` | `Boolean` | Key existence test. |
| `keys` | none | `Array[Any]` | Array of keys. |
| `values` | none | `Array[Any]` | Array of values. |
| `remove` | `key: Any` | `Any` | Delete entry by key. |
| `to_string` | none | `String` | Render the map and its nested values as text. |
| `equals` | `other: Any` | `Boolean` | Deep structural equality. |
| `clone` | none | `Map[K, V]` | Deep-copy the map and its nested keys and values while preserving key/value types. |
| `cursor` | none | `MapCursor` | Create entry iterator. |

## `Set`

### Construction

```nex
#{}
create Set[Integer].from_array([1, 2, 3])
```

Set literals use `#{...}`. The empty map literal remains `{}`.

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `contains` | `value: T` | `Boolean` | Membership test. |
| `union` | `other: Set[T]` | `Set[T]` | Set union. |
| `difference` | `other: Set[T]` | `Set[T]` | Elements in this set but not in `other`. |
| `intersection` | `other: Set[T]` | `Set[T]` | Common elements. |
| `symmetric_difference` | `other: Set[T]` | `Set[T]` | Elements in exactly one of the two sets. |
| `size` | none | `Integer` | Number of elements. |
| `is_empty` | none | `Boolean` | True when the set has no elements. |
| `to_string` | none | `String` | Render the set and its nested values as text. |
| `equals` | `other: Any` | `Boolean` | Deep structural equality. |
| `clone` | none | `Set[T]` | Deep-copy the set and its nested values while preserving element type. |
| `cursor` | none | `SetCursor` | Create iterator. |

## `Stack[T]`

`Stack[T]` is a generic last-in, first-out collection abstraction. It is not a
built-in primitive type; it is typically defined as a class using `Array[T]` for
storage.

### Typical Definition

```nex
class Stack [T]
  create
    make() do
      items := []
    end

  feature
    items: Array[T]

    push(value: T) do
      items.add(value)
    end

    pop(): T do
      result := items.get(items.length - 1)
      items.remove(items.length - 1)
    end

    peek(): T do
      result := items.get(items.length - 1)
    end

    is_empty(): Boolean do
      result := items.is_empty
    end

    size(): Integer do
      result := items.length
    end
end
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `push` | `value: T` | `Void` | Push value onto the top of the stack. |
| `pop` | none | `T` | Remove and return the top element. |
| `peek` | none | `T` | Return the top element without removing it. |
| `is_empty` | none | `Boolean` | True when the stack has no elements. |
| `size` | none | `Integer` | Number of stored elements. |

### Notes

- `pop` and `peek` usually require the stack to be non-empty.
- A contract-based version should add preconditions for these operations.
- `Stack[T]` is the canonical example of a user-defined generic collection in Nex.

## Examples

```nex
let xs: Array [Integer] := [10, 20]
xs.add(30)
xs.add_at(1, 15)                  -- [10, 15, 20, 30]
print(xs.get(2))                  -- 20
print(xs.index_of(30))            -- 3

let m: Map [String, String] := {"lang": "Nex"}
m.put("kind", "language")
print(m.get("lang"))              -- "Nex"
print(m.try_get("missing", "n/a")) -- "n/a"
print(m.contains_key("kind"))     -- true

let s1 := #{1, 2}
let s2 := #{2, 3}
print(s1.union(s2))               -- #{1, 2, 3}

let stack := create Stack[Integer].make
stack.push(10)
stack.push(20)
print(stack.peek)                 -- 20
```
