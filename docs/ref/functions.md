# Built-in Functions

## `print`

```nex
print(...args)
```

Writes all arguments to interpreter output as a space-separated line. Returns `nil`.

## `println`

```nex
println(...args)
```

Same output behavior as `print` in the current interpreter. Returns `nil`.

## `type_of`

```nex
type_of(value): String
```

Returns the runtime type name.

## `type_is`

```nex
type_is(type_name: String, value): Boolean
```

Checks runtime type compatibility (including built-in inheritance relationships like scalar types implementing `Comparable`/`Hashable`, and cursor types matching `Cursor`).

## Examples

```nex
print("hello", 42, true)
println("done")

let x := 12
print(type_of(x))                 -- "Integer"
print(type_is("Comparable", x))   -- true
print(type_is("String", x))       -- false
```
