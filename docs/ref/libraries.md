# Library Index

This section documents Nex libraries shipped under the repository `lib/` directory.

Unlike built-in runtime classes, these libraries may be platform-specific and must be loaded with `intern`.

## Categories

- [Networking Libraries](networking.md) - JVM-only TCP client and server wrappers under `lib/net`

## Conventions

- Library pages state their platform scope explicitly
- Load library classes with `intern path/Class_Name`
- File lookup follows the current `intern` resolver: loaded file directory, current working directory, then `~/.nex/deps`
