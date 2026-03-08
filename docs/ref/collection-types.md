# Collection Types

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
| `cursor` | none | `MapCursor` | Create entry iterator. |

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
```
