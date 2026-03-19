# Nex Command-Line Executables

This directory contains the command-line interface for the Nex language.

## Files

### `nex` (JVM version)
Bash script that provides the full Nex CLI using the JVM/Clojure runtime.

**Features:**
- REPL
- Compile to Java / standalone JVM jar / JavaScript
- Format Nex files
- Generate documentation
- Evaluate code snippets

**Usage:**
```bash
./nex help
./nex
./nex compile java file.nex
./nex compile jvm file.nex
./nex format file.nex
./nex doc file.nex output.md
./nex eval 'print("Hello")'
```

### `nex-node.js` (Node.js version)
Node.js script that provides limited Nex functionality using the ClojureScript runtime.

**Limitations:**
- Cannot parse Nex source (requires JVM)
- Cannot compile (requires JVM)
- Cannot format (requires JVM)
- Cannot generate docs (requires JVM)
- Can only execute pre-parsed AST

**Usage:**
```bash
./nex-node.js help
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

After running `./install.sh`, the appropriate executable will be installed to `/usr/local/bin/nex` (or your custom prefix).

You can then run:

```bash
nex help
nex
nex compile java MyClass.nex
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
