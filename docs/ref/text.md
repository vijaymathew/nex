# Text Libraries

This page documents text-processing libraries shipped under `lib/text`.

These are library classes, not core built-in classes. Load them with `intern`.

## Platform Scope

- `text/Regex`: JVM interpreter, generated JVM, generated JavaScript/Node
- Retired browser interpreter path: not supported

## Load

```nex
intern text/Regex
```

## `Regex`

`Regex` is a reusable regular-expression wrapper.

```nex
class Regex
create
  compile(pattern: String)
  compile_with_flags(pattern: String, flags: String)
feature
  pattern: String
  flags: String
  matches(text: String): Boolean
  find(text: String): ?String
  find_all(text: String): Array[String]
  replace(text: String, replacement: String): String
  split(text: String): Array[String]
  to_string(): String
end
```

Supported flags:
- `i` case-insensitive
- `m` multiline

Example:

```nex
intern text/Regex

let rx: Regex := create Regex.compile_with_flags("[a-z]+", "i")
print(rx.matches("Nex"))
print(rx.find("123 Nex 456"))
print(rx.find_all("one two THREE"))
print(rx.replace("v1 v2", "#"))
print(rx.split("a,b,c"))
```
