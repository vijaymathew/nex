# Collection Types

This section covers the standard collection abstractions used throughout Nex code.
`Array`, `Map`, `Set`, and `Min_Heap[T]` are built-in collection types.
`Stack[T]` is a common generic collection class pattern built on top of
`Array[T]`.

All built-in collection types inherit `Any`. Their `to_string`, `equals`, and
`clone` methods operate recursively: `to_string` renders nested structure,
`equals` performs deep structural equality, and `clone` performs a deep copy.

`Set` membership and dedup, and `Map` key lookup, compare by Nex value equality —
the same `equals`/`hash` used by the `=` operator — so a class that overrides
`equals`/`hash` is matched by value as an element or key.

Each method table below includes a **Complexity** column, given as `time / space`
(average case for `Map`/`Set`, as with any hash table). `nex file.nex` compiles
to JVM bytecode; a tree-walking interpreter is used as a fallback and an easy to 
understand reference implementation. The two backends agree on every operation's 
complexity except `Map.remove` and `Set.remove`: `O(1)` average on the compiled 
backend (backed by a real hash table), `O(n)` on the interpreter (which keeps a
separate insertion-order list that removal must also filter).

## `Array`

### Construction

```nex
[]
create Array.filled(3, 0)
create Array[String].filled(2, "x")
```

- `[]` creates an empty array literal.
- `create Array.filled(size, value)` creates a new array of length `size` where
  each element is initialized to `value`.
- `size` must be a non-negative `Integer`.
- The element type is inferred from `value`, or checked against `Array[T]` when
  the target array type is declared explicitly.

### Methods

| Method | Arguments | Returns | Description | Complexity |
|---|---|---|---|---|
| `get` | `index: Integer` | `Any` | Read element at `index`. | `O(1) / O(1)` |
| `add` | `value: Any` | `Void` | Append value. | `O(1) amortized / O(1)` |
| `add_at` | `index: Integer, value: Any` | `Void` | Insert value at index. | `O(n) / O(1)` |
| `length` | none | `Integer` | Number of elements. | `O(1) / O(1)` |
| `is_empty` | none | `Boolean` | True when array has no elements. | `O(1) / O(1)` |
| `contains` | `elem: Any` | `Boolean` | Membership test. | `O(n) / O(1)` |
| `index_of` | `elem: Any` | `Integer` | First index or `-1` if not found. | `O(n) / O(1)` |
| `remove` | `index: Integer` | `Void` | Remove element at index. | `O(n) / O(1)` |
| `reverse` | none | `Array[Any]` | Return reversed array. | `O(n) / O(n)` |
| `set` | `index: Integer, value: Any` | `Void` | Replace element at index. | `O(1) / O(1)` |
| `sort` | none | `Array[Any]` | Return a new array sorted by built-in order or `Comparable.compare`. | `O(n log n) / O(n)` |
| `sort` | `compareFn: Function(a: Any, b: Any): Integer` | `Array[Any]` | Return a new array sorted using `compareFn(a, b) -> Integer`. | `O(n log n) / O(n)` |
| `slice` | `start: Integer, end: Integer` | `Array[T]` | Subrange `[start, end)`. Negative indices count from the end: `-1` is one before the last element. Out-of-bounds values are clamped. | `O(n) / O(n)` |
| `take` | `n: Integer` | `Array[T]` | First `n` elements. Returns the whole array if `n` ≥ length, empty if `n` ≤ 0. | `O(n) / O(n)` |
| `drop` | `n: Integer` | `Array[T]` | All elements after the first `n`. Returns empty if `n` ≥ length, the whole array if `n` ≤ 0. | `O(n) / O(n)` |
| `take_last` | `n: Integer` | `Array[T]` | Last `n` elements. Returns the whole array if `n` ≥ length, empty if `n` ≤ 0. | `O(n) / O(n)` |
| `drop_last` | `n: Integer` | `Array[T]` | All elements except the last `n`. Returns empty if `n` ≥ length, the whole array if `n` ≤ 0. | `O(n) / O(n)` |
| `concat` | `other: Array[T]` | `Array[T]` | Return a new array containing this array followed by `other`. | `O(n + m) / O(n + m)` |
| `to_string` | none | `String` | Render the array and its nested values as text. | `O(n) / O(n)`, deep* |
| `equals` | `other: Any` | `Boolean` | Deep structural equality. | `O(n) / O(1)`, deep* |
| `clone` | none | `Array[T]` | Deep-copy the array and its nested values while preserving element type. | `O(n) / O(n)`, deep* |
| `cursor` | none | `ArrayCursor` | Create iterator. | `O(1) / O(1)` to create |

\* "Deep" means the cost scales with the array's total nested size, not just its element count, since these recurse into any nested `Array`/`Map`/`Set`/object elements.

- `sort()` requires elements to be built-in sortable scalars or `Comparable`.
- `sort(compareFn)` uses the provided comparator function instead. The
  comparator must return an `Integer`: negative when `a < b`, positive when
  `a > b`, `0` when equal.

## `Map`

### Construction

```nex
{}
```

### Methods

| Method | Arguments | Returns | Description | Complexity |
|---|---|---|---|---|
| `get` | `key: Any` | `Any` | Read value for key (fails if key missing). | `O(1) avg / O(1)` |
| `try_get` | `key: Any, default: Any` | `Any` | Read value or default if missing. | `O(1) avg / O(1)` |
| `set` | `key: Any, value: Any` | `Void` | Add/replace key-value entry. | `O(1) avg / O(1)` |
| `put` | `key: Any, value: Any` | `Void` | Alias for `set`. | `O(1) avg / O(1)` |
| `size` | none | `Integer` | Number of entries. | `O(1) / O(1)` |
| `is_empty` | none | `Boolean` | True when map has no entries. | `O(1) / O(1)` |
| `contains_key` | `key: Any` | `Boolean` | Key existence test. | `O(1) avg / O(1)` |
| `keys` | none | `Array[Any]` | Array of keys. | `O(n) / O(n)` |
| `values` | none | `Array[Any]` | Array of values. | `O(n) / O(n)` |
| `remove` | `key: Any` | `Void` | Delete entry by key. | `O(1) avg / O(1)`†  |
| `to_string` | none | `String` | Render the map and its nested values as text. | `O(n) / O(n)`, deep* |
| `equals` | `other: Any` | `Boolean` | Deep structural equality. | `O(n) / O(1)`, deep* |
| `clone` | none | `Map[K, V]` | Deep-copy the map and its nested keys and values while preserving key/value types. | `O(n) / O(n)`, deep* |
| `cursor` | none | `MapCursor` | Create entry iterator. | `O(n) / O(n)` to create† , then `O(1) / O(1)` per step |

\* "Deep" means the cost scales with the total nested size of keys/values, not just entry count.
† `remove` and `cursor` are the two operations where the backends diverge: `remove` is `O(1)` average on the compiled backend but `O(n)` on the interpreter (see the note above); `cursor` creation is `O(n)` on both, since it snapshots every key up front rather than iterating lazily.

## `Set`

### Construction

```nex
#{}
create Set[Integer].from_array([1, 2, 3])
```

Set literals use `#{...}`. The empty map literal remains `{}`.

### Methods

| Method | Arguments | Returns | Description | Complexity |
|---|---|---|---|---|
| `contains` | `value: T` | `Boolean` | Membership test. | `O(1) avg / O(1)` |
| `add` | `value: T` | `Void` | Add a value in place. A duplicate (by value equality) is a no-op. | `O(1) avg / O(1)` |
| `remove` | `value: T` | `Void` | Remove the element equal to `value` in place, if present. | `O(1) avg / O(1)`† |
| `union` | `other: Set[T]` | `Set[T]` | Set union. | `O(n + m) avg / O(n + m)` |
| `difference` | `other: Set[T]` | `Set[T]` | Elements in this set but not in `other`. | `O(n) avg / O(n)` |
| `intersection` | `other: Set[T]` | `Set[T]` | Common elements. | `O(n) avg / O(n)` |
| `symmetric_difference` | `other: Set[T]` | `Set[T]` | Elements in exactly one of the two sets. | `O(n + m) avg / O(n + m)` |
| `size` | none | `Integer` | Number of elements. | `O(1) / O(1)` |
| `is_empty` | none | `Boolean` | True when the set has no elements. | `O(1) / O(1)` |
| `to_array` | none | `Array[T]` | Copy the set's elements into a new array, in insertion order. | `O(n) / O(n)` |
| `to_string` | none | `String` | Render the set and its nested values as text. | `O(n) / O(n)`, deep* |
| `equals` | `other: Any` | `Boolean` | Deep structural equality. | `O(n) / O(1)`, deep* |
| `clone` | none | `Set[T]` | Deep-copy the set and its nested values while preserving element type. | `O(n) / O(n)`, deep* |
| `cursor` | none | `SetCursor` | Create iterator. | `O(n) / O(n)` to create† , then `O(1) / O(1)` per step |

\* "Deep" means the cost scales with the total nested size of elements, not just element count.
† `remove` and `cursor` are the two operations where the backends diverge, the same way as `Map` above: `remove` is `O(1)` average on the compiled backend but `O(n)` on the interpreter; `cursor` creation is `O(n)` on both, since it snapshots every element up front. (`n` above is the size of the receiver, `m` the size of `other`.)

## `Min_Heap[T]`

### Construction

```nex
create Min_Heap.empty
create Min_Heap[Integer].empty
create Min_Heap[Box].from_comparator(compare_boxes)
```

- `create Min_Heap.empty` creates an empty heap that uses natural ordering.
- `empty()` is intended for element types that already support ordering:
  built-in sortable scalars or classes implementing `Comparable`.
- For non-`Comparable` element types, use `from_comparator(...)`.
- `from_comparator(compare)` expects a `Function(a: T, b: T): Integer` comparator:
  negative when the first value is smaller, positive when larger, `0` when equal.

### Methods

| Method | Arguments | Returns | Description | Complexity |
|---|---|---|---|---|
| `insert` | `value: T` | `Void` | Insert a value into the heap. | `O(log n) / O(1)` |
| `extract_min` | none | `T` | Remove and return the smallest value. Fails if empty. | `O(log n) / O(1)` |
| `try_extract_min` | none | `?T` | Remove and return the smallest value, or `nil` if empty. | `O(log n) / O(1)` |
| `peek` | none | `T` | Return the smallest value without removing it. Fails if empty. | `O(1) / O(1)` |
| `try_peek` | none | `?T` | Return the smallest value, or `nil` if empty. | `O(1) / O(1)` |
| `size` | none | `Integer` | Number of stored elements. | `O(1) / O(1)` |
| `is_empty` | none | `Boolean` | True when the heap has no elements. | `O(1) / O(1)` |

Same binary-heap algorithm on both backends — no divergence.

### Example

```nex
let failure: Min_Heap[Integer] := create Min_Heap.empty
failure.insert(0)
failure.insert(3)
failure.insert(1)
print(failure.peek)              -- 0
print(failure.extract_min)       -- 0
print(failure.try_extract_min)   -- 1
print(failure.try_extract_min)   -- 3
print(failure.try_extract_min)   -- nil
```

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

| Method | Arguments | Returns | Description | Complexity |
|---|---|---|---|---|
| `push` | `value: T` | `Void` | Push value onto the top of the stack. | `O(1) amortized / O(1)` |
| `pop` | none | `T` | Remove and return the top element. | `O(1) / O(1)` |
| `peek` | none | `T` | Return the top element without removing it. | `O(1) / O(1)` |
| `is_empty` | none | `Boolean` | True when the stack has no elements. | `O(1) / O(1)` |
| `size` | none | `Integer` | Number of stored elements. | `O(1) / O(1)` |

`pop`/`peek` are `O(1)`, not `O(n)`, because they always touch the array's last
index, which never shifts — unlike `Array.remove` at an arbitrary index.

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
m.set("kind", "language")
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
