# Nex Language Installation Guide

This guide explains how to install the Nex language implementation on your system.

## Prerequisites

- **Java 17 or later** - [Download](https://adoptium.net/)
- To build from source only: **Clojure CLI 1.11.1.1420 or later** -
  [Installation Guide](https://clojure.org/guides/install_clojure). The
  prebuilt release and the bootstrap script's default install need no Clojure.

The installer checks both versions and, with `--install-deps` (or after asking),
installs or upgrades whichever is missing or too old.

## Quick Install

### Prebuilt Release (Linux, macOS, Windows)

Each GitHub release has a prebuilt Nex that needs only **Java 17 or later**:
no Clojure CLI and no build step. The same files run on every platform.

1. Download `nex-<version>.zip` (Windows) or `nex-<version>.tar.gz` (Linux,
   macOS) from the [Releases page](https://github.com/vijaymathew/nex/releases).
2. Unpack it anywhere, for example `C:\Tools\nex-<version>` or
   `~/.local/share/nex-<version>`.
3. Put its `bin` folder on your `PATH`, or link the launcher into a folder
   already on it:

```bash
# Linux, macOS
tar -xzf nex-<version>.tar.gz -C ~/.local/share
ln -s ~/.local/share/nex-<version>/bin/nex ~/.local/bin/nex
nex version
```

```bat
:: Windows (cmd); or add the bin folder to PATH in System Settings
set "PATH=C:\Tools\nex-<version>\bin;%PATH%"
nex version
```

`nex` uses the Java in `JAVA_HOME` when that is set, and `java` on `PATH`
otherwise. On Windows, quote a `--classpath` list, since `;` separates its
entries: `nex app.nex --classpath "a.jar;b.jar"`.

### Downloadable Bootstrap Script (Linux, macOS)

Users can install Nex without cloning the repository by downloading the
bootstrap installer:

```bash
curl -fsSL -o bootstrap-install.sh https://raw.githubusercontent.com/vijaymathew/nex/main/bootstrap-install.sh
bash bootstrap-install.sh jvm --install-deps
```

By default the bootstrap script downloads the latest prebuilt release, checks
it against the release's `SHA256SUMS`, and runs the `install.sh` inside it,
which installs it under `/usr/local` (or `--prefix`). Only Java 17+ is
needed. `--version 0.6.5` picks a release other than the latest.

To build and install from source instead (which also needs the Clojure CLI):

```bash
bash bootstrap-install.sh --from-source                 # the main branch
bash bootstrap-install.sh --from-source --ref v0.6.4    # a tag, branch or commit
```

A release that has no prebuilt archive (0.6.4 and earlier) is built from
source automatically, after saying so.

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

- Java (the newest of OpenJDK 25, 21 or 17 that your system packages; 25+ starts Nex fastest)
- Clojure CLI tools (latest release), under the same prefix as `nex`

## Installation Details

The installation script will:

1. **Check prerequisites** - Verify Java 17+ and Clojure CLI 1.11.1.1420+ are installed
2. **Offer to install dependencies** - If missing or too old (or auto-install with `--install-deps`)
3. **Build** - Compile Nex ahead of time (`clojure -M:dist`, see
   `scripts/build-dist.clj`) into `nex.jar` plus its dependency jars. The
   installed `nex` runs these with plain `java`, so the Clojure CLI is needed
   only to install, and every run skips compiling Nex's own source.
4. **Install files** to:
   - Library: `/usr/local/lib/nex`, laid out exactly like a prebuilt release
     (`scripts/package-release.sh` builds both)
   - Executable: `/usr/local/bin/nex`, a link to `/usr/local/lib/nex/bin/nex`
5. **Verify** - Run a test program with the installed `nex`.

`sudo` is used only when the install directories are not writable by you, so a
`--prefix` inside your home directory needs no administrator rights.

## Startup Cache

After the first program you run, `nex` builds a JVM class-data cache in the
background, in `~/.cache/nex` (or `$XDG_CACHE_HOME/nex`), which roughly halves
startup again: an AOT cache on Java 25 or later, an AppCDS archive on Java
17-24. Nothing waits for it and nothing is printed about it. A new Nex or Java
version gets a new cache automatically; old ones can be deleted at any time.
To run without it, set `NEX_JVM_CACHE=off`. The Windows launcher does not
build this cache yet.

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

Nex Language Installer v0.6.4

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

Nex Language Installer v0.6.4

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
nex compile jvm examples/create_example.nex
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

Each user's startup cache and shipped libraries can be removed too:

```bash
rm -rf ~/.cache/nex ~/.nex
```

## Troubleshooting

### "nex: command not found"

The installation directory may not be in your PATH. Add it:

```bash
export PATH="/usr/local/bin:$PATH"
```

Add this line to your `~/.bashrc`, `~/.zshrc`, or equivalent shell configuration file.

### Permission Denied

Installing into `/usr/local` needs `sudo`; the installer uses it automatically
when it is available. Without administrator rights, install into your home
directory instead:

```bash
./install.sh --prefix "$HOME/.local"
```

Java itself still has to be installed system-wide, so on an account without
`sudo` ask an administrator for Java 17 or later first.

### Java/Clojure Not Found

**Option 1: Use automatic installation**
```bash
./install.sh jvm --install-deps
```

**Option 2: Install manually**

**Ubuntu/Debian:**
```bash
sudo apt-get install openjdk-21-jdk-headless   # or openjdk-17-jdk-headless
curl -fsSLO https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
sudo bash linux-install.sh
```

**macOS (requires Homebrew):**
```bash
brew install openjdk clojure/tools/clojure
```

**Fedora:**
```bash
sudo dnf install java-21-openjdk-headless
# Then install Clojure CLI (see Ubuntu instructions)
```

**Arch/Manjaro:**
```bash
sudo pacman -S jdk-openjdk
# Then install Clojure CLI (see Ubuntu instructions)
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

## Features

| Feature | JVM |
|---------|-----|
| REPL | ✅ |
| Compile to a standalone JVM jar | ✅ |
| Format files | ✅ |
| Generate docs | ✅ |
| Eval code | ✅ |
