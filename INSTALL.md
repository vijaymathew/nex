# Nex Language Installation Guide

This guide explains how to install the Nex language implementation on your system.

## Prerequisites

### For JVM Installation (Recommended)
- **Java 11 or later** - [Download](https://adoptium.net/)
- **Clojure CLI** - [Installation Guide](https://clojure.org/guides/install_clojure)

### For Node.js Installation
- **Node.js 16 or later** - [Download](https://nodejs.org/)
- **npm** (comes with Node.js)

**Note:** The Node.js installation has limited functionality. For full features (REPL, format, doc, compile), use the JVM installation.

## Quick Install

### Downloadable Bootstrap Script

Users can install Nex without cloning the repository by downloading the
bootstrap installer:

```bash
curl -fsSL -o bootstrap-install.sh https://raw.githubusercontent.com/vijaymathew/nex/main/bootstrap-install.sh
bash bootstrap-install.sh jvm --install-deps
```

The bootstrap script downloads a source archive from GitHub and then runs the
project's `install.sh`.

### JVM Installation (Default)

The installer will automatically detect missing dependencies and offer to install them:

```bash
./install.sh
```

or explicitly:

```bash
./install.sh jvm
```

**Automatic Dependency Installation** (no prompts):

```bash
./install.sh jvm --install-deps
```

This will automatically install Java and Clojure if they're not present.

To install into a custom prefix without exporting `INSTALL_PREFIX` first:

```bash
./install.sh jvm --prefix "$HOME/.local"
```

### Node.js Installation

```bash
./install.sh nodejs
```

With automatic dependency installation:

```bash
./install.sh nodejs --install-deps
```

## Supported Platforms

The installer supports automatic dependency installation on:

### Linux
- **Ubuntu/Debian** - Uses `apt-get`
- **Fedora** - Uses `dnf`
- **CentOS/RHEL** - Uses `yum`
- **Arch/Manjaro** - Uses `pacman`

### macOS
- **macOS** - Uses Homebrew (must be installed first)

### What Gets Installed

When using `--install-deps` flag:

**For JVM:**
- Java (OpenJDK 11 or later)
- Clojure CLI tools (latest stable)

**For Node.js:**
- Node.js (LTS version)
- npm (comes with Node.js)

## Installation Details

The installation script will:

1. **Check prerequisites** - Verify Java/Clojure or Node.js is installed
2. **Offer to install dependencies** - If missing (or auto-install with `--install-deps`)
3. **Build** - Compile ClojureScript if needed (Node.js only)
4. **Install files** to:
   - Executable: `/usr/local/bin/nex`
   - Library: `/usr/local/lib/nex`
5. **Verify** - Test that `nex` command is available

## Custom Installation Prefix

To install to a different location:

```bash
INSTALL_PREFIX=$HOME/.local ./install.sh
```

Or equivalently:

```bash
./install.sh --prefix "$HOME/.local"
```

Then add `$HOME/.local/bin` to your PATH:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

## Installation Examples

### Interactive Installation (with prompts)

If Java is not installed:

```bash
$ ./install.sh jvm

Nex Language Installer v0.1.0

Installation target: jvm
Install prefix: /usr/local
Auto-install dependencies: false

Checking prerequisites...

Java is not installed.
Would you like to install it automatically? (y/n) y
Installing Java...
  ✓ Java installed
  ✓ Clojure CLI

Building Nex for jvm...
  No build required for JVM (using Clojure CLI)

[...]

Nex Language Installed Successfully!
```

### Automatic Installation (no prompts)

```bash
$ ./install.sh jvm --install-deps

Nex Language Installer v0.1.0

Installation target: jvm
Install prefix: /usr/local
Auto-install dependencies: true

Checking prerequisites...
Installing Java...
  ✓ Java installed
Installing Clojure CLI...
  ✓ Clojure CLI installed

[...]

Nex Language Installed Successfully!
```

## Post-Installation

### Verify Installation

```bash
nex help
nex version
```

### Test the REPL

```bash
nex
```

You should see the Nex REPL prompt.

### Compile a Test File

```bash
# Preferred JVM backend
nex compile jvm examples/create_example.nex

# Deprecated legacy Java-source pipeline
nex compile java examples/create_example.nex

# Compile to JavaScript
nex compile js examples/create_example.nex
```

## Uninstallation

To remove Nex from your system:

```bash
sudo rm -rf /usr/local/bin/nex
sudo rm -rf /usr/local/lib/nex
```

Or if you used a custom prefix:

```bash
rm -rf $INSTALL_PREFIX/bin/nex
rm -rf $INSTALL_PREFIX/lib/nex
```

## Troubleshooting

### "nex: command not found"

The installation directory may not be in your PATH. Add it:

```bash
export PATH="/usr/local/bin:$PATH"
```

Add this line to your `~/.bashrc`, `~/.zshrc`, or equivalent shell configuration file.

### Permission Denied

The install script requires sudo to write to `/usr/local`. Either:

1. Run with sudo: `sudo ./install.sh`
2. Install to user directory: `INSTALL_PREFIX=$HOME/.local ./install.sh`

### Java/Clojure Not Found

**Option 1: Use automatic installation**
```bash
./install.sh jvm --install-deps
```

**Option 2: Install manually**

**Ubuntu/Debian:**
```bash
sudo apt-get install default-jdk
curl -O https://download.clojure.org/install/linux-install-1.11.1.1208.sh
chmod +x linux-install-1.11.1.1208.sh
sudo ./linux-install-1.11.1.1208.sh
```

**macOS (requires Homebrew):**
```bash
brew install openjdk clojure/tools/clojure
```

**Fedora:**
```bash
sudo dnf install java-latest-openjdk
# Then install Clojure CLI (see Ubuntu instructions)
```

**Arch/Manjaro:**
```bash
sudo pacman -S jdk-openjdk
# Then install Clojure CLI (see Ubuntu instructions)
```

### ClojureScript Build Fails (Node.js installation)

1. Clear caches:
   ```bash
   rm -rf .shadow-cljs target node_modules
   ```

2. Reinstall dependencies:
   ```bash
   npm install
   ```

3. Try building manually:
   ```bash
   npx shadow-cljs compile node
   ```

## Development Installation

If you're developing Nex itself, you don't need to install system-wide. Instead:

1. Set `NEX_HOME` environment variable:
   ```bash
   export NEX_HOME=/path/to/nex
   ```

2. Run directly from the repository:
   ```bash
   ./bin/nex help
   ```

3. Or add to PATH:
   ```bash
   export PATH="/path/to/nex/bin:$PATH"
   ```

## Next Steps

After installation:

1. **Read the Tutorial** - `docs/TUTORIAL.md` (if available)
2. **Try Examples** - Explore files in `examples/`
3. **Read the Language Reference** - `docs/REFERENCE.md` (if available)
4. **Configure Your Editor** - See `editor/` directory for editor integrations

## Getting Help

- Run `nex help` for command usage
- Check `docs/` directory for documentation
- Report issues on GitHub: [your-repo-url]

## Features by Installation Type

| Feature | JVM | Node.js |
|---------|-----|---------|
| REPL | ✅ | ❌ |
| Compile to Java (deprecated) | ✅ | ❌ |
| Compile to JavaScript | ✅ | ❌ |
| Format files | ✅ | ❌ |
| Generate docs | ✅ | ❌ |
| Eval code | ✅ | ❌* |
| Run pre-compiled AST | ✅ | ✅ |

*Node.js can evaluate pre-parsed AST but cannot parse Nex source code.

For full functionality, **use the JVM installation**.
