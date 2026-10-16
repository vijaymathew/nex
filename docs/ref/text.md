# Text Libraries

This page documents the text-processing libraries shipped under `lib/text`. These are library classes, not core built-in classes. Load them with `intern`.

## `text/Regex`

`Regex` is a reusable regular-expression wrapper, shipped as a Nex library under `lib/text/regex.nex`. Patterns use Java regular-expression syntax and are validated when the `Regex` is created.

### Loading

```nex
intern text/Regex
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Construction

```nex
let words: Regex := create Regex.compile("[a-z]+")
let any_case: Regex := create Regex.compile_with_flags("[a-z]+", "i")
```

An invalid pattern raises a runtime error at construction, so a `Regex` value always holds a usable pattern.

### Flags

| Flag | Meaning |
|---|---|
| `i` | Case-insensitive matching. |
| `m` | Multiline: `^` and `$` match at line boundaries, not only at the ends of the input. |

Flags combine by concatenation, e.g. `"im"`. Other characters in the flags string are ignored.

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `compile` | `pattern: String` | `Regex` | Create a regex with no flags. |
| `compile_with_flags` | `pattern: String, flags: String` | `Regex` | Create a regex with the given flags (see Flags). |
| `pattern` | none | `String` | The source pattern (a field). |
| `flags` | none | `String` | The flags string, `""` when none (a field). |
| `matches` | `text: String` | `Boolean` | True when the entire `text` matches the pattern (not just a substring). |
| `find` | `text: String` | `?String` | The first match anywhere in `text`, or `nil` when there is none. |
| `find_all` | `text: String` | `Array[String]` | Every non-overlapping match, left to right. |
| `replace` | `text: String, replacement: String` | `String` | Replace every match with `replacement`; `$1`, `$2`, … refer to capture groups. |
| `split` | `text: String` | `Array[String]` | Split `text` around matches of the pattern. Trailing empty strings are dropped. |
| `to_string` | none | `String` | The pattern in slash form, e.g. `"Regex(/[a-z]+/i)"`. |

### Example

```nex
intern text/Regex

let rx: Regex := create Regex.compile_with_flags("[a-z]+", "i")
print(rx.matches("Nex"))                  -- true
print(rx.find("123 Nex 456"))             -- "Nex"
print(rx.find_all("one two THREE"))       -- ["one", "two", "THREE"]
print(rx.replace("v1 v2", "#"))           -- "#1 #2"

let digits: Regex := create Regex.compile("[0-9]+")
print(digits.matches("123abc"))           -- false (whole-string match)
print(digits.find("no digits"))           -- nil

let comma: Regex := create Regex.compile(",\\s*")
print(comma.split("a, b,c"))              -- ["a", "b", "c"]

let email: Regex := create Regex.compile("(\\w+)@(\\w+)")
print(email.replace("ann@home", "$2:$1")) -- "home:ann"
```

### Notes

- `find()` and `find_all()` return the whole match, not individual capture groups.
- Backslashes must be doubled inside Nex string literals: the pattern `\s` is written `"\\s"`.
- Compile a `Regex` once and reuse it; each method call applies the stored pattern and flags.
