# Exception Classes

When the language itself raises an exception — a failed contract, integer
division by zero, an index out of range, and so on — the value a `rescue`
block receives in `exception` is an object of one of the classes below, chosen
by the kind of failure. A handler can therefore tell failures apart with
`convert` or `match` instead of reading a message.

A value raised with `raise` is **not** wrapped: `raise "transient"` still hands
the handler the string `"transient"`, and `raise 42` the integer `42`.

These classes are defined in `lib/lang/exception.nex` (Definition of Nex,
Appendix B.7).

## Loading

A program that has a `rescue` anywhere, or that names one of these classes,
gets the library automatically, as if it had written:

```nex
intern lang/Exception
```

Writing that `intern` yourself also works.

A program that declares a class of its own with one of these names does not
get the library. Its handlers then receive the failure's message as a
`String`, as they did before the classes existed.

## Hierarchy

```text
Exception
├── Contract_Violation
│   ├── Precondition_Violation
│   ├── Postcondition_Violation
│   ├── Invariant_Violation
│   ├── Loop_Invariant_Violation
│   ├── Variant_Violation
│   ├── Assertion_Violation
│   └── Refinement_Violation
├── Division_by_Zero
├── Arithmetic_Overflow
├── Void_Access
├── Index_Out_Of_Bounds
├── Conversion_Error
├── No_Matching_Clause
├── Channel_Closed
└── Host_Exception
```

## `Exception`

The root of the built-in exceptions. A program may inherit it for its own
failures (see [Defining your own](#defining-your-own)).

### Construction

```nex
create Exception.make("something went wrong")
```

### Features

| Feature | Arguments | Returns | Description |
|---|---|---|---|
| `message` | none | `String` | The text the failure is reported with. |
| `to_string` | none | `String` | The message. Printing an exception, or joining it to a string with `+`, reads as the report does. |

## `Contract_Violation`

A failed assertion. Inherits `Exception`.

### Construction

```nex
create Contract_Violation.make("Precondition violation: pos")
create Contract_Violation.with_label("Precondition violation: pos", "pos")
```

The subclasses below inherit both constructors.

### Features

| Feature | Arguments | Returns | Description |
|---|---|---|---|
| `label` | none | `?String` | The failed assertion's label — `pos` for `require pos: x > 0`. `nil` for a bare `assert`, whose message names its source line instead. For a refinement, the refinement's name, or the field's when the violation is at a field assignment. |

### Subclasses

| Class | Raised by |
|---|---|
| `Precondition_Violation` | a `require` clause |
| `Postcondition_Violation` | an `ensure` clause |
| `Invariant_Violation` | a class invariant |
| `Loop_Invariant_Violation` | a loop invariant |
| `Variant_Violation` | a loop variant that is negative or does not decrease (`label` is `nil`) |
| `Assertion_Violation` | an `assert` |
| `Refinement_Violation` | a refinement type's predicate at a narrowing site (a typed `let`, parameter, return, or field assignment) |

## Other built-in exceptions

Each inherits `Exception` and adds no features of its own, except
`Host_Exception`.

| Class | Raised by | Example message |
|---|---|---|
| `Division_by_Zero` | integer `/` or `%` by zero | `Division by zero` |
| `Arithmetic_Overflow` | an `Integer` result outside the 64-bit range; `abs` of a fixed-width minimum | `Arithmetic overflow` |
| `Void_Access` | a call or field access on `nil` | `Used a value that is void (nil)` |
| `Index_Out_Of_Bounds` | an `Array` or `String` position outside its range (`get`, `char_at`, …) | `Index 5 out of bounds for length 1` |
| `Conversion_Error` | a conversion out of a fixed-width type's range (`to_byte`, `to_integer16`, …), a string that is not a number (`to_integer`, `to_real`), bytes that are not UTF-8 (`String.from_bytes`) | `Byte value must be in range 0..255, got 300` |
| `No_Matching_Clause` | a `match` with no `else` that no clause matches | `No matching clause in match` |
| `Channel_Closed` | a send on a closed channel, or a receive from a closed, drained one | `Cannot send on a closed channel` |
| `Host_Exception` | any other failure the host platform raises | e.g. `java.lang.StackOverflowError` |

A missing `Map` key is a `Precondition_Violation` with the label
`key_must_exist`, since `Map.get` states it as a precondition.

## `Host_Exception`

A failure raised by the host platform (the JVM) that has no more specific
class. Inherits `Exception`.

### Construction

```nex
create Host_Exception.with_host("boom", "java.lang.IllegalStateException")
```

### Features

| Feature | Arguments | Returns | Description |
|---|---|---|---|
| `host_class` | none | `String` | The host's own name for the failure, e.g. `java.lang.StackOverflowError`. |

## Handling exceptions

Test for a class with `convert`:

```nex
do
  account.withdraw(500.0)
rescue
  if convert exception to v: Precondition_Violation then
    print("refused: " + v.message)     -- refused: Precondition violation: enough
  else
    raise exception                    -- not ours: pass it on
  end
end
```

Or dispatch with `match` (`exception` has type `Any`, so give an `else`):

```nex
do
  print(totals.get(i) / count)
rescue
  match exception of
    Division_by_Zero then print("no items")
    Index_Out_Of_Bounds as e then print("bad index: " + e.message)
  else
    raise exception
  end
end
```

A contract violation's label says which assertion failed:

```nex
rescue
  if convert exception to c: Contract_Violation then
    if ?c.label as l then print("failed: " + l) end
  end
```

Re-raising a caught exception with `raise exception` reports its message if
nothing else catches it.

## Defining your own

Inherit `Exception`, delegate to its constructor, and raise an instance:

```nex
class Parse_Error
inherit Exception
create
  make(m: String) do
    Exception.make(m)
  end
end

do
  raise create Parse_Error.make("unexpected token")
rescue
  if convert exception to p: Parse_Error then
    print("parse failed: " + p.message)
  end
end
```

An uncaught one is reported through its `to_string`:
`Error: unexpected token`.

## Notes

- `print(exception)` for a built-in failure prints the message without
  quotes, because `exception` is an object, not a string.
- `exception` is typed `Any` in a `rescue` block, since `raise` accepts any
  value; narrow it with `convert` or `match` to reach `message`, `label` or
  `host_class`.
- On the tree-walking interpreter (`--interpret`), unbounded recursion stops
  the interpreter itself rather than raising a catchable `Host_Exception`.
