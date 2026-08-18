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
