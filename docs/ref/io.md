# IO Libraries

This page documents the filesystem and stream libraries shipped under `lib/io`. These are library classes, not core built-in classes. Load them with `intern`.

## `io/Path`

`Path` is the filesystem-oriented wrapper for files and directories, shipped as a Nex library under `lib/io/path.nex`. A `Path` only records a path string; creating one does not touch the filesystem. Its methods are implemented on top of runtime `__path_*` primitives.

### Loading

```nex
intern io/Path
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Construction

```nex
let file: Path := create Path.make("notes/today.txt")
```

The path may be relative or absolute. Relative paths resolve against the working directory of the running program.

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `make` | `path: String` | `Path` | Create a path wrapper. Does not create anything on disk. |
| `path` | none | `String` | The raw path string this value wraps (a field). |
| `exists` | none | `Boolean` | True when a file or directory exists at this path. |
| `is_file` | none | `Boolean` | True when the path exists and is a regular file. |
| `is_directory` | none | `Boolean` | True when the path exists and is a directory. |
| `name` | none | `String` | The last path segment, e.g. `"notes.txt"`. |
| `extension` | none | `String` | The filename suffix without the dot (`"txt"`), or `""` when there is none. |
| `name_without_extension` | none | `String` | The filename with its final extension removed (`"archive.tar.gz"` → `"archive.tar"`). |
| `absolute` | none | `Path` | An absolute form of this path. |
| `normalize` | none | `Path` | This path with redundant `.` and `..` segments removed. |
| `size` | none | `Integer` | Size in bytes as reported by the host; `0` when the path does not exist. |
| `modified_time` | none | `Integer` | Last-modified time in milliseconds since the Unix epoch; `0` when the path does not exist. |
| `parent` | none | `?Path` | The containing path, or `nil` when the path has no parent segment. |
| `child` | `name: String` | `Path` | A path for `name` inside this one. |
| `create_file` | none | `nil` | Create an empty file if none exists. The parent directory must exist. |
| `create_directory` | none | `nil` | Create this directory. The parent directory must exist. |
| `create_directories` | none | `nil` | Create this directory and any missing parents. |
| `delete` | none | `nil` | Delete a file. Does nothing when the path is missing; raises when it is a directory. |
| `delete_tree` | none | `nil` | Delete a file, or a directory and everything under it. |
| `copy_to` | `target: Path` | `nil` | Copy a file or a whole directory tree to `target`, replacing existing files. |
| `move_to` | `target: Path` | `nil` | Move or rename a file or directory tree to `target`. |
| `read_text` | none | `String` | Read the whole file as UTF-8 text. |
| `write_text` | `text: String` | `nil` | Write `text` as UTF-8, replacing any existing content. |
| `append_text` | `text: String` | `nil` | Append `text` as UTF-8, creating the file if needed. |
| `list` | none | `Array[Path]` | The immediate children of a directory; an empty array otherwise. |
| `to_string` | none | `String` | The raw path string. |

### Example

```nex
intern io/Path

let root: Path := create Path.make("tmp")
root.create_directories()

let file: Path := root.child("notes.txt")
file.write_text("hello")
file.append_text(" world")

print(file.read_text())               -- "hello world"
print(file.name())                    -- "notes.txt"
print(file.extension())               -- "txt"
print(file.name_without_extension())  -- "notes"
print(file.size())                    -- 11
print(file.parent())                  -- tmp

file.copy_to(root.child("backup.txt"))
print(root.list().length)             -- 2

root.delete_tree()
print(root.exists())                  -- false
```

### Notes

- `delete()` removes files only; use `delete_tree()` for directories.
- `move_to()` is implemented as a copy followed by `delete_tree()` of the source.
- `copy_to()` creates missing parent directories of the target.
- A leading dot does not start an extension: `".bashrc"` has extension `""`.
- Filesystem failures (missing parent directory, permission denied, reading a missing file) are raised as runtime errors.
- Text I/O always uses UTF-8.

## `io/Directory`

`Directory` is a typed wrapper over `Path` for directory-oriented code, shipped under `lib/io/directory.nex`. It delegates every operation to an underlying `Path`; it does not introduce a separate filesystem model.

### Loading

```nex
intern io/Directory
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Construction

```nex
let logs: Directory := create Directory.make("var/logs")
let same: Directory := create Directory.from_path(create Path.make("var/logs"))
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `make` | `path: String` | `Directory` | Create a directory wrapper from a path string. |
| `from_path` | `path: Path` | `Directory` | Wrap an existing `Path`. |
| `path` | none | `Path` | The underlying `Path` (a field). |
| `exists` | none | `Boolean` | True only when the path exists and is a directory. |
| `create_directory` | none | `nil` | Create this directory. The parent must exist. |
| `create_tree` | none | `nil` | Create this directory and any missing parents. |
| `delete` | none | `nil` | Delegates to `Path.delete()`, which raises for a directory; use `delete_tree()`. |
| `delete_tree` | none | `nil` | Delete the directory and everything under it. |
| `copy_to` | `target: Directory` | `nil` | Copy the directory tree to `target`. |
| `move_to` | `target: Directory` | `nil` | Move or rename the directory tree to `target`. |
| `name` | none | `String` | The directory name (last path segment). |
| `parent` | none | `?Directory` | The containing directory, or `nil` when there is no parent segment. |
| `child_dir` | `name: String` | `Directory` | A `Directory` for `name` inside this one. |
| `child_path` | `name: String` | `Path` | A `Path` for `name` inside this one, typically a file. |
| `list` | none | `Array[Path]` | All immediate children. |
| `directories` | none | `Array[Directory]` | Immediate child directories only. |
| `files` | none | `Array[Path]` | Immediate child files only. |
| `absolute` | none | `Directory` | An absolute form of this directory. |
| `normalize` | none | `Directory` | This directory with redundant `.` and `..` segments removed. |
| `to_string` | none | `String` | A descriptive string such as `"Directory(tmp/logs)"`. |

### Example

```nex
intern io/Path
intern io/Directory

let root: Directory := create Directory.make("tmp")
let logs: Directory := root.child_dir("logs")
logs.create_tree()

logs.child_path("app.log").write_text("started")
root.child_path("readme.txt").write_text("hi")

print(root.directories().length)   -- 1
print(root.files().length)         -- 1
print(logs.name())                 -- "logs"

let archive: Directory := root.child_dir("archive")
logs.copy_to(archive)
print(archive.exists())            -- true

root.delete_tree()
```

### Notes

- `intern io/Directory` also loads `io/Path`, so `Path` is available without a separate `intern`.
- `directories()` and `files()` are not recursive.
- The `Directory` methods take and return `Directory`; use `child_path()` or `path` to drop to `Path` for file operations.

## `io/Text_File`

`Text_File` is a sequential UTF-8 text stream for line-oriented reading, or for writing and appending, shipped under `lib/io/text_file.nex`. Use it instead of `Path.read_text()` / `write_text()` when a file is too large to hold in one string or is produced incrementally.

### Loading

```nex
intern io/Text_File
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Construction

```nex
let reader: Text_File := create Text_File.open_read(create Path.make("in.txt"))
let writer: Text_File := create Text_File.open_write(create Path.make("out.txt"))
let log:    Text_File := create Text_File.open_append(create Path.make("app.log"))
```

A file is opened by its constructor; the mode is fixed for the life of the handle.

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `open_read` | `path: Path` | `Text_File` | Open an existing file for reading. Raises when the file does not exist. |
| `open_write` | `path: Path` | `Text_File` | Open for writing, creating the file or truncating existing content. |
| `open_append` | `path: Path` | `Text_File` | Open for writing at the end, creating the file if needed and keeping existing content. |
| `path` | none | `Path` | The path this file was opened from (a field). |
| `read_line` | none | `?String` | The next line without its line terminator, or `nil` at end of file. |
| `write` | `text: String` | `nil` | Write `text` exactly as given. |
| `write_line` | `text: String` | `nil` | Write `text` followed by a newline (`\n`). |
| `close` | none | `nil` | Close the underlying stream. |
| `to_string` | none | `String` | A descriptive string such as `"Text_File(log.txt)"`. |

### Example

```nex
intern io/Path
intern io/Text_File

let path: Path := create Path.make("log.txt")

let writer: Text_File := create Text_File.open_write(path)
writer.write("alpha")
writer.write_line("!")
writer.write_line("beta")
writer.close()

let appender: Text_File := create Text_File.open_append(path)
appender.write_line("gamma")
appender.close()

let reader: Text_File := create Text_File.open_read(path)
let line: ?String := reader.read_line()
from until line = nil do
  print(line)                -- "alpha!", then "beta", then "gamma"
  line := reader.read_line()
end
reader.close()
```

### Notes

- Writes are flushed immediately, so content is visible to other readers before `close()`.
- `read_line()` recognises `\n`, `\r\n` and `\r` line endings.
- Reading from a handle opened for writing, or writing to one opened for reading, raises a runtime error.
- Always `close()` a handle when finished; closing releases the host file descriptor.

## `io/Binary_File`

`Binary_File` is the byte-oriented file wrapper, shipped under `lib/io/binary_file.nex`. It supports random access through an explicit cursor.

Bytes are represented as `Array[Byte]`; a `Byte` is an unsigned value in `0..255`. `String.to_bytes()` produces one, and a `Byte` literal is written with a `u8` suffix (`[0x89u8, 0x50u8]`); use `Integer.to_byte()` to convert a computed `Integer`.

### Loading

```nex
intern io/Binary_File
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Construction

```nex
let reader: Binary_File := create Binary_File.open_read(create Path.make("image.png"))
let writer: Binary_File := create Binary_File.open_write(create Path.make("out.bin"))
let log:    Binary_File := create Binary_File.open_append(create Path.make("events.bin"))
```

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `open_read` | `path: Path` | `Binary_File` | Open an existing file for reading, with the cursor at `0`. Raises when the file does not exist. |
| `open_write` | `path: Path` | `Binary_File` | Open for writing, creating the file or truncating it to zero length; cursor at `0`. |
| `open_append` | `path: Path` | `Binary_File` | Open for writing, creating the file if needed; the cursor starts at the current end of file. |
| `path` | none | `Path` | The path this file was opened from (a field). |
| `read` | `count: Integer` | `Array[Byte]` | Read up to `count` bytes from the cursor and advance it. Returns fewer bytes near end of file and `[]` at end of file. |
| `read_all` | none | `Array[Byte]` | The entire file contents, independent of the cursor. The cursor is not moved. |
| `position` | none | `Integer` | The current cursor offset in bytes. |
| `seek` | `offset: Integer` | `nil` | Move the cursor to the absolute byte `offset`. Raises when `offset` is negative. |
| `write` | `bytes: Array[Byte]` | `nil` | Write `bytes` at the cursor, overwriting existing bytes and extending the file as needed; advances the cursor. |
| `close` | none | `nil` | Close the underlying file. |
| `to_string` | none | `String` | A descriptive string such as `"Binary_File(data.bin)"`. |

### Example

```nex
intern io/Path
intern io/Binary_File

let path: Path := create Path.make("data.bin")

let writer: Binary_File := create Binary_File.open_write(path)
writer.write("ABC".to_bytes())
writer.seek(1)
writer.write("Z".to_bytes())       -- overwrite "B"
writer.close()

let appender: Binary_File := create Binary_File.open_append(path)
print(appender.position())         -- 3
appender.write([0x89u8, 0xFFu8])
appender.close()

let reader: Binary_File := create Binary_File.open_read(path)
print(reader.read(2))              -- [65, 90]
print(reader.position())           -- 2
print(reader.read(100))            -- [67, 137, 255]
print(reader.read(1))              -- []
print(reader.read_all())           -- [65, 90, 67, 137, 255]
reader.close()
```

### Notes

- `seek()` past the end of file is allowed; a following `write()` extends the file, padding the gap with zero bytes.
- Writing to a handle opened with `open_read` raises a runtime error.
- Decode bytes back to text with `String.from_bytes()`, which raises on invalid UTF-8.
