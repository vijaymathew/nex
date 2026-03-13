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
| Generated JavaScript / Node | Yes |
| Retired browser interpreter path | No |

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
- JSON integer -> `Integer` or `Integer64`
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
