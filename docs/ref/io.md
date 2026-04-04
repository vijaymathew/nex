# IO Libraries

This page documents the filesystem and stream libraries shipped under `lib/io`.

These are library classes, not core built-in classes. Load them with `intern`.

## Platform Scope

- `io/Path`: JVM interpreter, generated JVM, generated JavaScript/Node
- `io/Directory`: JVM interpreter, generated JVM, generated JavaScript/Node
- `io/Text_File`: JVM interpreter, generated JVM, generated JavaScript/Node
- `io/Binary_File`: JVM interpreter, generated JVM, generated JavaScript/Node
- Retired browser interpreter path: not supported

## Load

```nex
intern io/Path
intern io/Directory
intern io/Text_File
intern io/Binary_File
```

## `Path`

`Path` is the filesystem-oriented wrapper for files and directories.

```nex
class Path
create
  make(path: String)
feature
  exists(): Boolean
  is_file(): Boolean
  is_directory(): Boolean
  name(): String
  extension(): String
  name_without_extension(): String
  absolute(): Path
  normalize(): Path
  size(): Integer64
  modified_time(): Integer64
  parent(): ?Path
  child(name: String): Path
  create_file()
  create_directory()
  create_directories()
  delete()
  delete_tree()
  copy_to(target: Path)
  move_to(target: Path)
  read_text(): String
  write_text(text: String)
  append_text(text: String)
  list(): Array[Path]
  to_string(): String
end
```

Notes:
- `delete()` removes files only
- `delete()` on a directory raises an error
- `delete_tree()` removes files or directories recursively
- `copy_to()` copies files or directory trees
- `move_to()` renames or moves files/directories
- `extension()` returns the filename suffix without the dot, or `""` if none
- `name_without_extension()` strips the final extension from the filename
- `absolute()` returns an absolute `Path`
- `normalize()` removes redundant `.` and `..` segments where supported by the host runtime
- `size()` returns file or directory-entry byte size reported by the host runtime
- `modified_time()` returns last-modified time as milliseconds since the Unix epoch
- `list()` returns immediate children
- text I/O uses UTF-8

Example:

```nex
intern io/Path

let root: Path := create Path.make("tmp")
root.create_directories()

let file: Path := root.child("notes.txt")
file.write_text("hello")
file.append_text(" world")

print(file.read_text())
print(file.name())
print(file.extension())
print(file.name_without_extension())
print(file.absolute())
print(file.normalize())
print(root.list().size())
```

## `Text_File`

## `Directory`

`Directory` is a typed wrapper over `Path` for directory-oriented code.

```nex
class Directory
create
  make(path: String)
  from_path(path: Path)
feature
  exists(): Boolean
  create_directory()
  create_tree()
  delete()
  delete_tree()
  copy_to(target: Directory)
  move_to(target: Directory)
  name(): String
  parent(): ?Directory
  child_dir(name: String): Directory
  child_path(name: String): Path
  list(): Array[Path]
  directories(): Array[Directory]
  files(): Array[Path]
  absolute(): Directory
  normalize(): Directory
  to_string(): String
end
```

Notes:
- `Directory` delegates to `Path`; it does not introduce a separate filesystem model
- `exists()` is true only when the underlying path both exists and is a directory
- `directories()` returns immediate child directories
- `files()` returns immediate child files
- `copy_to()` copies the directory tree to the target directory path
- `move_to()` renames or moves the directory tree to the target directory path

Example:

```nex
intern io/Path
intern io/Directory

let root: Directory := create Directory.make("tmp")
root.create_tree()

let logs: Directory := root.child_dir("logs")
logs.create_tree()

let file: Path := logs.child_path("app.log")
file.write_text("started")

let archive: Directory := root.child_dir("archive")
logs.copy_to(archive)

print(root.directories().length)
print(logs.files().length)
print(archive.exists())
print(logs.absolute().to_string())
```

## `Text_File`

`Text_File` is a sequential text stream wrapper for line-oriented or append-style text I/O.

```nex
class Text_File
create
  open_read(path: Path)
  open_write(path: Path)
  open_append(path: Path)
feature
  read_line(): ?String
  write(text: String)
  write_line(text: String)
  close()
  to_string(): String
end
```

Notes:
- `open_write` truncates existing file content
- `open_append` preserves existing file content
- `read_line()` returns `nil` at end-of-file

Example:

```nex
intern io/Path
intern io/Text_File

let path: Path := create Path.make("log.txt")
let writer: Text_File := create Text_File.open_write(path)
writer.write_line("alpha")
writer.write_line("beta")
writer.close()

let reader: Text_File := create Text_File.open_read(path)
print(reader.read_line())
print(reader.read_line())
print(reader.read_line())
reader.close()
```

## `Binary_File`

`Binary_File` is the byte-oriented file wrapper.

Bytes are represented as `Array[Integer]`, with each element required to be in `0..255`.
The file maintains an explicit cursor. `seek(offset)` sets the absolute byte position,
and `position()` returns the current cursor offset.

```nex
class Binary_File
create
  open_read(path: Path)
  open_write(path: Path)
  open_append(path: Path)
feature
  read_all(): Array[Integer]
  read(count: Integer): Array[Integer]
  position(): Integer
  seek(offset: Integer)
  write(bytes: Array[Integer])
  close()
  to_string(): String
end
```

Example:

```nex
intern io/Path
intern io/Binary_File

let path: Path := create Path.make("data.bin")
let writer: Binary_File := create Binary_File.open_write(path)
writer.write([65, 66, 67])
writer.seek(1)
writer.write([90])
writer.close()

let reader: Binary_File := create Binary_File.open_read(path)
print(reader.read(2))
print(reader.position())
reader.seek(0)
print(reader.read_all())
reader.close()
```
