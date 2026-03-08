# System Classes

## `Console`

### Construction

```nex
create Console
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `print` | `msg: Any` | `Void` | Write message without extra formatting. |
| `print_line` | `msg: Any` | `Void` | Write message as a line. |
| `read_line` | `prompt?: String` | `String` | Read one line from input. |
| `error` | `msg: Any` | `Void` | Write to error output. |
| `new_line` | none | `Void` | Emit blank line. |
| `read_integer` | none | `Integer` | Read and parse integer. |
| `read_real` | none | `Real` | Read and parse real number. |

## `File`

### Construction

```nex
create File.open(path)
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `read` | none | `String` | Read full file as string. |
| `write` | `content: Any` | `Void` | Overwrite file with content. |
| `append` | `content: Any` | `Void` | Append content to file. |
| `exists` | none | `Boolean` | Check file existence. |
| `delete` | none | `Void` | Delete file. |
| `lines` | none | `Array[String]` | Read file as array of lines. |
| `close` | none | `Void` | No-op placeholder for compatibility. |

## `Process`

### Construction

```nex
create Process
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `getenv` | `name: String` | `String` | Read environment variable (empty if missing). |
| `setenv` | `name: String, value: String` | `Void` | Set environment variable (platform dependent). |
| `command_line` | none | `Array[String]` | Return command-line arguments. |

## Examples

```nex
let con := create Console
con.print_line("Enter your name:")
let name := con.read_line()
con.print_line("Hello, " + name)

let f := create File.open("notes.txt")
f.write("line 1")
f.append("\nline 2")
print(f.exists())
print(f.lines())

let p := create Process
print(p.getenv("HOME"))
print(p.command_line())
```
