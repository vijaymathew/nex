# Cursor Types

Cursors implement the `Cursor` protocol: `start`, `item`, `next`, `at_end`.

## `ArrayCursor`

Created by: `arr.cursor()`

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `start` | none | `Void` | Reset index to `0`. |
| `item` | none | `Any` | Return current array element; throws at end. |
| `next` | none | `Void` | Advance index by one if not at end. |
| `at_end` | none | `Boolean` | True when index is beyond last element. |

## `StringCursor`

Created by: `str.cursor()`

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `start` | none | `Void` | Reset index to `0`. |
| `item` | none | `Char` | Return current character; throws at end. |
| `next` | none | `Void` | Advance index by one if not at end. |
| `at_end` | none | `Boolean` | True when index is beyond last character. |

## `MapCursor`

Created by: `map.cursor()`

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `start` | none | `Void` | Refresh key snapshot and reset index. |
| `item` | none | `Array[Any]` | Return `[key, value]`; throws at end. |
| `next` | none | `Void` | Advance index by one if not at end. |
| `at_end` | none | `Boolean` | True when index is beyond key snapshot. |

## Examples

```nex
let xs := [1, 2, 3]
let c := xs.cursor()
from c.start() until c.at_end() do
  print(c.item())
  c.next()
end

let m := {"name": "Ada", "lang": "Nex"}
let mc := m.cursor()
from mc.start() until mc.at_end() do
  let pair := mc.item()
  print(pair.get(0) + ": " + pair.get(1))
  mc.next()
end
```
