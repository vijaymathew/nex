# Nex Command-Line Executables

This directory contains the command-line interface for the Nex language.

## Files

### `nex` (Linux, macOS) and `nex.cmd` (Windows)
Launchers for the Nex CLI. Each finds Java and the Nex installation, puts
Nex's jars (and any `--classpath` entries) on the JVM classpath, and starts
`nex.cli` (`src/nex/cli.clj`), which handles the commands below for both.

`nex` runs either a prebuilt release (`nex.jar` in its parent folder, built by
`scripts/package-release.sh`) or a source checkout (through the Clojure CLI).
`nex.cmd` runs a prebuilt release only. Keep `nex.cmd`'s line endings CRLF.

**Features:**
- REPL
- Compile to standalone JVM jar
- Format Nex files
- Generate documentation
- Evaluate code snippets

**Usage:**
```bash
./nex help
./nex
./nex compile jvm file.nex
./nex format file.nex
./nex doc file.nex output.md
./nex eval 'print("Hello")'
```

## Running Without Installation

Set `NEX_HOME` to the project root:

```bash
export NEX_HOME=/path/to/nex
./bin/nex help
```

Or use the helper scripts in the project root:

```bash
# From project root
./bin/nex help
```

## After Installation

After running `./install.sh`, the `nex` executable will be installed to `/usr/local/bin/nex` (or your custom prefix).

You can then run:

```bash
nex help
nex
nex compile jvm MyClass.nex
```

from anywhere in your system.

## Environment Variables

- **NEX_HOME** - Installation directory (default: auto-detected)

## Development

When developing Nex, run executables directly from this directory:

```bash
cd /path/to/nex
./bin/nex help
```

The scripts will auto-detect NEX_HOME based on their location.
