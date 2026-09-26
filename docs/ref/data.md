# Data Libraries

## `data/Json`

`Json` is a small JSON parser and serializer shipped as a Nex library under [`lib/data/json.nex`](/home/vijay/Projects/nex/lib/data/json.nex). Its methods are implemented on top of runtime `json_parse` and `json_stringify` primitives.

### Loading

```nex
intern data/Json
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Construction

```nex
let json: Json := create Json.make()
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `make` | none | `Json` | Create a JSON helper object. |
| `parse` | `text: String` | `Any` | Parse JSON text into Nex values. |
| `stringify` | `value: Any` | `String` | Serialize Nex values into JSON text. |

### Value Mapping

- JSON object -> `Map[String, Any]`
- JSON array -> `Array[Any]`
- JSON string -> `String`
- JSON integer -> `Integer`
- JSON decimal/exponent number -> `Real`
- JSON boolean -> `Boolean`
- JSON `null` -> `nil`

### Example

```nex
intern data/Json

let json: Json := create Json.make()
let root: Map[String, Any] := json.parse("{\"name\":\"nex\",\"count\":3,\"items\":[1,2]}")
print(root.get("name"))
print(json.stringify(root))
```

### Notes

- `parse` returns `Any`, so callers usually bind the result to `Map[String, Any]` or `Array[Any]` when they know the expected shape.
- `stringify` supports Nex `Map`, `Array`, scalar values, and `nil`.
- Sets are serialized as JSON arrays.

## `data/Sexpr`

`Sexpr` is a minimal s-expression parser and serializer shipped as a pure-Nex library under [`lib/data/sexpr.nex`](/home/vijay/Projects/nex/lib/data/sexpr.nex). Unlike `data/Json`, it does not lean on any runtime parsing primitive — the parser is a hand-rolled character-cursor recursive descent over the input string, and the AST is an ordinary `union` type.

### Loading

```nex
intern data/Sexpr
```

`intern data/Sexpr` brings the `Sexpr` type, its `Symbol`, `Int`, `Float`, `Str`, and `List` variants, the `Sexpr_Parser` class, and the `parse_sexpr_text` / `sexpr_to_string` functions into scope.

### Types

- `Sexpr` &mdash; union AST type.
- `Symbol(name: String)` &mdash; a bare identifier, e.g. `+` or `foo`.
- `Int(value: Integer)` &mdash; an integer literal.
- `Float(value: Real)` &mdash; a decimal literal (requires a digit on both sides of the `.`).
- `Str(value: String)` &mdash; a double-quoted string literal, with `\\`, `\"`, `\n`, `\t`, `\r` escapes.
- `List(items: Array[Sexpr])` &mdash; a parenthesized, whitespace-separated, recursively-nested sequence.

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Grammar

```
sexpr  := atom | list
list   := '(' sexpr* ')'
atom   := symbol | integer | float | string
symbol := any run of non-whitespace, non-paren, non-quote characters
```

Deliberately out of scope: comments, quote/quasiquote shorthand, dotted pairs, vectors.

### Functions

| Function | Signature | Description |
|---|---|---|
| `parse_sexpr_text` | `(text: String): Sexpr` | Parse `text` as a single s-expression. Trailing whitespace is allowed; any other trailing content raises. |
| `sexpr_to_string` | `(e: Sexpr): String` | Render a `Sexpr` back into s-expression text (round-trips `parse_sexpr_text` for any input using only the constructs above). |

Malformed input (an unterminated list or string, a stray `)`, empty input) raises rather than returning a partial result.

### Example

```nex
intern data/Sexpr

let e: Sexpr := parse_sexpr_text("(+ 1 (foo \"bar\" 2.5) -3)")
print(sexpr_to_string(e))

match e of
  List(items) then print(items.length)  -- 4
  else print("not a list")
end
```

### Notes

- `Sexpr_Parser` (constructed via `create Sexpr_Parser.make(text)`, driven with `.parse()`) is the class `parse_sexpr_text` wraps; use it directly for incremental/streaming parsing.
- Numeric tokens are classified by shape: a run of digits (optional leading `+`/`-`) is `Int`; the same with exactly one `.` and digits on both sides is `Float`; anything else is a `Symbol` — so operators like `+` and `-` parse as symbols, not numbers.

## `data/Byte_Array`

`Byte_Array` is a fixed-size, mutable sequence of `Byte`s stored in a real Java `byte[]`, one
byte per element. Use it for binary data where `Array[Byte]` (a list of boxed values, several
times larger) is too heavy, and to hand a real `byte[]` to Java code inside `with "java"`.
It is shipped as a Nex library under `lib/data/byte_array.nex`, on top of `byte_array_*`
runtime primitives.

### Loading

```nex
intern data/Byte_Array
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Construction

| Constructor | Arguments | Description |
|---|---|---|
| `make` | `size: Integer` | `size` zero-filled bytes; raises if `size` is negative. |
| `from_array` | `items: Array[Byte]` | A copy of an `Array[Byte]`. |
| `from_slice` | `source: Byte_Array, start: Integer, stop: Integer` | A copy of `source[start, stop)`; what `slice` uses. |
| `from_concat` | `first: Byte_Array, second: Byte_Array` | A new array: `first`'s bytes then `second`'s; what `concat` uses. |
| `from_java` | `raw: Any` | A copy of a Java `byte[]`; raises for anything else. |

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `length` | none | `Integer` | Number of elements. |
| `get` | `index: Integer` | `Byte` | The element at `index`; raises if it is outside `0..length-1`. |
| `set` | `index: Integer, value: Byte` | `Void` | Store `value` at `index`; raises if it is out of range. |
| `fill` | `value: Byte` | `Void` | Set every element to `value`. |
| `copy` | none | `Byte_Array` | A copy of the whole array. |
| `concat` | `other: Byte_Array` | `Byte_Array` | A new array: these bytes followed by `other`'s. |
| `copy_into` | `target: Byte_Array, target_offset: Integer` | `Void` | Copy all of these bytes into `target` starting at `target_offset`, overwriting what is there; raises unless they fit. |
| `slice` | `start: Integer, stop: Integer` | `Byte_Array` | A copy of the elements in `[start, stop)`; raises unless `0 <= start <= stop <= length`. |
| `index_of` | `value: Byte` | `Integer` | The first index holding `value`, or `-1`. |
| `contains` | `value: Byte` | `Boolean` | True if `value` occurs. |
| `to_array` | none | `Array[Byte]` | A boxed copy of the elements. |
| `to_java` | none | `Any` | The underlying Java `byte[]`, **shared, not copied**. |
| `equals` | `other: Any` | `Boolean` | True for a `Byte_Array` with the same bytes. `=` uses this. |
| `hash` | none | `Integer` | Hash of the contents. |
| `compare` | `other: Any` | `Integer` | Lexicographic order with each byte taken as unsigned; a proper prefix orders first. Raises unless `other` is a `Byte_Array`. |
| `to_hex` | none | `String` | Two lowercase hex digits per byte, e.g. `"c3a9"`. |
| `to_utf8_string` | none | `String` | Decode as UTF-8; raises if the bytes are not valid UTF-8. |
| `cursor` | none | `Byte_Array_Cursor` | A cursor over the bytes; this is what `across` uses. |
| `to_string` | none | `String` | `Byte_Array([1, 2, 3])`. |

### Notes

- The storage holds Java's signed bytes, but the interface is unsigned: `get` returns a `Byte`
  in `0..255` and `set` keeps the low 8 bits of its `Byte`. So `set(0, 200u8)` then `get(0)`
  gives `200`, while a Java caller sees `-56`.
- The size is fixed; there is no `add`. Use `Array[Byte]` when the length changes.
- `Byte_Array` is `Comparable`, so `<`, `<=`, `>`, `>=` work between two of them and an
  `Array[Byte_Array]` can be sorted.
- `across buf as b do ... end` iterates the bytes, and `b` is a `Byte` (not `Any`).
  `Byte_Array_Cursor` is the cursor class (`start`, `item`, `next`, `at_end`) that `cursor()`
  returns; use it directly for a manual `from c.start() until c.at_end() do ... end` loop.
- Everything copies except `to_java`, so two `Byte_Array`s never alias each other by accident.
  Writes through the array `to_java` returns are visible in the `Byte_Array`.

```nex
intern data/Byte_Array
import java.security.MessageDigest

let input: Byte_Array := create Byte_Array.from_array("abc".to_bytes())
let digest: Any := nil
with "java" do
  let md := MessageDigest.getInstance("SHA-256")
  digest := md.digest(input.to_java())
end
let out: Byte_Array := create Byte_Array.from_java(digest)
print(out.length())                     -- 32
print(out.slice(0, 4).to_array())       -- [186, 120, 22, 191]
```
