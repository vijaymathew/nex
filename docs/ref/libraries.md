# Library Index

This section documents Nex libraries shipped under the repository `lib/` directory.

Unlike built-in runtime classes, these libraries may be platform-specific and must be loaded with `intern`.

## Categories

- [Data Libraries](data.md) - `data/Json` for JSON parsing and serialization
- [IO Libraries](io.md) - `io/Path`, `io/Directory`, `io/Text_File`, and `io/Binary_File` for filesystem and file I/O
- [Text Libraries](text.md) - `text/Regex` for reusable regular-expression matching and replacement
- [Time Libraries](time.md) - `time/Date_Time` and `time/Duration` for UTC timestamps and time spans
- [Networking Libraries](networking.md) - portable `Http_Client`, JVM/Node `Http_Server`, and JVM-only TCP wrappers under `lib/net`

## Conventions

- Library pages state their platform scope explicitly
- Load library classes with `intern path/Class_Name`
- File lookup follows the current `intern` resolver: loaded file directory, current working directory, then `~/.nex/deps`
