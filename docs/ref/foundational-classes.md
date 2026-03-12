# Foundational Classes

## `Any`

Universal root class for all Nex values. User-defined classes may explicitly
write `inherit Any`.

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `to_string` | none | `String` | Return a user-facing string representation. |
| `equals` | `other: Any` | `Boolean` | Default equality. Arbitrary objects use identity-style equality unless they override it. |
| `clone` | none | `Any` | Clone the value. Built-in collections override this with deep-copy behavior. |

## `Function`

Built-in base class with deferred call-style methods.

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `call0` | none | `Any` | Invocation protocol for nullary function-like values. |
| `call1..call32` | `arg1..argN: Any` | `Any` | Invocation protocol for values that accept up to 32 arguments. |

## `Cursor`

Abstract iteration interface.

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `start` | none | `Void` | Reset iteration to the first position. |
| `item` | none | `Any` | Return current item. |
| `next` | none | `Void` | Advance to next position. |
| `at_end` | none | `Boolean` | Check whether iteration is complete. |

Concrete runtime cursor types are documented in [Cursor Types](cursor-types.md).

## `Comparable` (deferred)

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `compare` | `a: Any` | `Integer` | Return negative/zero/positive ordering result. |

## `Hashable` (deferred)

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `hash` | none | `Integer` | Return stable hash code for the value. |

## Examples

```nex
let s := "nex"
print(s.compare("next"))          -- negative / 0 / positive
print(s.hash())

let n := 42
print(n.compare(10))               -- positive
print(n.hash())
```
