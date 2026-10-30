#!/bin/bash
set -e

# Nex Language Installation Script
# Usage: ./install.sh [--install-deps] [--prefix DIR]
#
# Run from either of:
#   - a prebuilt release folder (nex-<version>/, unpacked from the release
#     archive): installs it as it is; only Java 17+ is needed.
#   - a source checkout: builds that folder first (scripts/package-release.sh),
#     which also needs the Clojure CLI.

VERSION="0.6.7"
TARGET="jvm"
INSTALL_DEPS=false
INSTALL_PREFIX="${INSTALL_PREFIX:-/usr/local}"
BIN_DIR="$INSTALL_PREFIX/bin"
LIB_DIR="$INSTALL_PREFIX/lib/nex"

# The compiler emits Java 17 class files and the launcher passes
# --enable-native-access, which older JVMs reject at startup.
MIN_JAVA=17
# Older Clojure CLIs always write their classpath cache into the project
# directory (TDEPS-119, fixed in 1.11.1.1420), so they cannot run Nex from a
# read-only install such as /usr/local/lib/nex.
MIN_CLOJURE_CLI="1.11.1.1420"
CLOJURE_LINUX_INSTALLER="https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh"

# Work from this script's folder, wherever it was run from.
cd "$(dirname "$0")"

# A prebuilt release has nex.jar next to this script and no source.
if [[ -f nex.jar && -f classpath && ! -f deps.edn ]]; then
    PREBUILT=true
    RELEASE_DIR="$(pwd)"
else
    PREBUILT=false
    RELEASE_DIR="target/release/nex-$VERSION"
fi

# Command prefix for writing into the install directories: empty when the
# current user can write there, "sudo" otherwise (set by choose_sudo).
SUDO=""

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        jvm)
            TARGET="$1"
            shift
            ;;
        --install-deps)
            INSTALL_DEPS=true
            shift
            ;;
        --prefix)
            if [[ $# -lt 2 ]]; then
                echo "Error: --prefix requires a directory argument."
                exit 1
            fi
            INSTALL_PREFIX="$2"
            BIN_DIR="$INSTALL_PREFIX/bin"
            LIB_DIR="$INSTALL_PREFIX/lib/nex"
            shift 2
            ;;
        --help|-h)
            echo "Usage: ./install.sh [--install-deps] [--prefix DIR]"
            exit 0
            ;;
        *)
            echo "Warning: Unknown argument '$1'"
            shift
            ;;
    esac
done

echo "╔════════════════════════════════════════════════════════════╗"
echo "║              Nex Language Installer v$VERSION                 ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Validate target
if [[ "$TARGET" != "jvm" ]]; then
    echo "Error: Invalid target '$TARGET'. The only supported target is 'jvm'."
    echo "Usage: ./install.sh [--install-deps] [--prefix DIR]"
    exit 1
fi

echo "Installation target: $TARGET"
if [[ "$PREBUILT" == true ]]; then
    echo "Installing from: prebuilt release ($RELEASE_DIR)"
else
    echo "Installing from: source (built here)"
fi
echo "Install prefix: $INSTALL_PREFIX"
echo "Auto-install dependencies: $INSTALL_DEPS"
echo ""

# Detect OS and distribution
detect_os() {
    if [[ "$OSTYPE" == "linux-gnu"* ]]; then
        if [ -f /etc/os-release ]; then
            . /etc/os-release
            OS=$ID
            OS_VERSION=$VERSION_ID
        else
            OS="unknown"
        fi
    elif [[ "$OSTYPE" == "darwin"* ]]; then
        OS="macos"
    else
        OS="unknown"
    fi
}

# Run a command as root: directly when already root, else through sudo.
as_root() {
    if [[ $EUID -eq 0 ]]; then
        "$@"
    elif command -v sudo &> /dev/null; then
        sudo "$@"
    else
        echo "Error: '$*' needs administrator rights, and sudo is not available." >&2
        return 1
    fi
}

# True when DIR, or its nearest existing ancestor, is writable by this user.
can_write() {
    local dir="$1"
    while [[ ! -e "$dir" ]]; do
        dir="$(dirname "$dir")"
    done
    [[ -w "$dir" ]]
}

# Use sudo only when the install directories are not writable as-is, so that
# `--prefix "$HOME/.local"` works on accounts without administrator rights.
choose_sudo() {
    if can_write "$BIN_DIR" && can_write "$LIB_DIR"; then
        SUDO=""
    elif [[ $EUID -eq 0 ]]; then
        SUDO=""
    elif command -v sudo &> /dev/null; then
        SUDO="sudo"
    else
        echo "Error: cannot write to $INSTALL_PREFIX, and sudo is not available."
        echo "Install into your home directory instead:"
        echo "  ./install.sh --prefix \"\$HOME/.local\""
        exit 1
    fi
}

download() {
    local url="$1" output="$2"
    if command -v curl &> /dev/null; then
        curl -fsSL "$url" -o "$output"
    elif command -v wget &> /dev/null; then
        wget -qO "$output" "$url"
    else
        echo "Error: either curl or wget is required." >&2
        return 1
    fi
}

# True when version $1 is at least version $2 (dot-separated numbers).
version_at_least() {
    local IFS=.
    local -a have=($1) want=($2)
    local i a b
    for ((i = 0; i < ${#want[@]}; i++)); do
        a="${have[i]:-0}"
        b="${want[i]:-0}"
        ((10#$a > 10#$b)) && return 0
        ((10#$a < 10#$b)) && return 1
    done
    return 0
}

# Major version of the `java` on PATH, or 0 when there is no working Java
# (macOS ships a /usr/bin/java stub that exists but cannot run anything).
java_major_version() {
    local raw
    command -v java &> /dev/null || { echo 0; return; }
    raw="$(java -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -n 1)"
    [[ -z "$raw" ]] && { echo 0; return; }
    [[ "$raw" == 1.* ]] && raw="${raw#1.}"
    echo "${raw%%[.+_-]*}"
}

# Version of the `clojure` on PATH, or empty when there is none.
clojure_cli_version() {
    command -v clojure &> /dev/null || return 0
    clojure --version 2>/dev/null | sed -n 's/.*version \([0-9][0-9.]*\).*/\1/p' | head -n 1
}

# Ask before installing a dependency, unless --install-deps was given.
# Without a terminal to ask on, the answer is no.
confirm_install() {
    local what="$1"
    [[ "$INSTALL_DEPS" == true ]] && return 0
    [[ -t 0 ]] || return 1
    read -p "Would you like to install $what automatically? (y/n) " -n 1 -r
    echo
    [[ $REPLY =~ ^[Yy]$ ]]
}

# Install Java
install_java() {
    echo "Installing Java..."

    case "$OS" in
        ubuntu|debian|linuxmint|pop)
            as_root apt-get update -qq
            # Newest first: Java 25+ starts Nex fastest (see bin/nex).
            as_root apt-get install -y openjdk-25-jdk-headless \
                || as_root apt-get install -y openjdk-21-jdk-headless \
                || as_root apt-get install -y openjdk-17-jdk-headless
            ;;
        fedora)
            as_root dnf install -y java-25-openjdk-headless \
                || as_root dnf install -y java-21-openjdk-headless \
                || as_root dnf install -y java-latest-openjdk-headless
            ;;
        centos|rhel|rocky|almalinux)
            local pm=yum
            command -v dnf &> /dev/null && pm=dnf
            as_root "$pm" install -y java-25-openjdk-headless \
                || as_root "$pm" install -y java-21-openjdk-headless \
                || as_root "$pm" install -y java-17-openjdk-headless
            ;;
        arch|manjaro)
            as_root pacman -S --noconfirm jdk-openjdk
            ;;
        macos)
            if ! command -v brew &> /dev/null; then
                echo "Error: Homebrew not found. Install it from https://brew.sh"
                exit 1
            fi
            brew install openjdk
            # Homebrew's openjdk is keg-only; link it where macOS's java
            # launcher looks, as `brew info openjdk` recommends.
            as_root ln -sfn "$(brew --prefix)/opt/openjdk/libexec/openjdk.jdk" \
                /Library/Java/JavaVirtualMachines/openjdk.jdk
            ;;
        *)
            echo "Error: Unsupported OS for automatic Java installation: $OS"
            echo "Please install Java $MIN_JAVA or later manually:"
            echo "  https://adoptium.net/"
            exit 1
            ;;
    esac

    echo "  ✓ Java installed"
}

# Install Clojure CLI (the latest release)
install_clojure() {
    echo "Installing Clojure CLI..."

    if [[ "$OS" == "macos" ]]; then
        if ! command -v brew &> /dev/null; then
            echo "Error: Homebrew not found. Install it from https://brew.sh"
            exit 1
        fi
        if brew list clojure/tools/clojure &> /dev/null; then
            brew upgrade clojure/tools/clojure
        else
            brew install clojure/tools/clojure
        fi
    else
        # Installed next to nex, under the same prefix and with the same
        # rights; the nex launcher puts its own directory first on PATH.
        local tmpdir
        tmpdir="$(mktemp -d)"
        download "$CLOJURE_LINUX_INSTALLER" "$tmpdir/linux-install.sh"
        (cd "$tmpdir" && $SUDO bash ./linux-install.sh --prefix "$INSTALL_PREFIX")
        rm -rf "$tmpdir"
        export PATH="$BIN_DIR:$PATH"
    fi

    echo "  ✓ Clojure CLI installed"
}

ensure_java() {
    local v
    v="$(java_major_version)"
    if (( v >= MIN_JAVA )); then
        echo "  ✓ Java $v"
        return
    fi

    echo ""
    if (( v == 0 )); then
        echo "Java is not installed. Nex needs Java $MIN_JAVA or later."
    else
        echo "Java $v is installed, but Nex needs Java $MIN_JAVA or later."
    fi
    if ! confirm_install "Java"; then
        echo "Error: install Java $MIN_JAVA or later (https://adoptium.net/) and run the installer again,"
        echo "or re-run it with --install-deps."
        exit 1
    fi
    install_java

    hash -r
    v="$(java_major_version)"
    if (( v < MIN_JAVA )); then
        echo "Error: a newer Java was installed, but \`java\` still runs Java ${v}."
        echo "Make Java $MIN_JAVA or later the default (on Debian/Ubuntu:"
        echo "  sudo update-alternatives --config java"
        echo ") and run the installer again."
        exit 1
    fi
    echo "  ✓ Java $v"
}

ensure_clojure() {
    local v
    v="$(clojure_cli_version)"
    if [[ -n "$v" ]] && version_at_least "$v" "$MIN_CLOJURE_CLI"; then
        echo "  ✓ Clojure CLI $v"
        return
    fi

    echo ""
    if [[ -z "$v" ]]; then
        echo "Clojure CLI is not installed."
    else
        echo "Clojure CLI $v is too old; Nex needs $MIN_CLOJURE_CLI or later."
    fi
    if ! confirm_install "the latest Clojure CLI"; then
        echo "Error: install Clojure CLI $MIN_CLOJURE_CLI or later"
        echo "(https://clojure.org/guides/install_clojure) and run the installer again,"
        echo "or re-run it with --install-deps."
        exit 1
    fi
    install_clojure

    hash -r
    v="$(clojure_cli_version)"
    if [[ -z "$v" ]] || ! version_at_least "$v" "$MIN_CLOJURE_CLI"; then
        echo "Error: installed a new Clojure CLI, but \`clojure\` on PATH is still ${v:-missing}."
        echo "Put $BIN_DIR before other directories on your PATH and run the installer again."
        exit 1
    fi
    echo "  ✓ Clojure CLI $v"
}

# Check and optionally install prerequisites
check_prerequisites() {
    echo "Checking prerequisites..."

    detect_os
    ensure_java
    # Only a build from source needs the Clojure CLI.
    [[ "$PREBUILT" == true ]] || ensure_clojure
    echo ""
}

# Build the same folder a prebuilt release ships (scripts/package-release.sh):
# Nex AOT-compiled into nex.jar, its dependency jars, the launchers and the
# shipped libraries. The installed command then starts with plain `java`
# instead of loading the Clojure source on every run.
build() {
    echo "Building Nex (compiling ahead of time; this takes a minute)..."

    local log
    log="$(mktemp)"
    if ! bash scripts/package-release.sh --no-archives > "$log" 2>&1; then
        echo "Error: building Nex failed. The build output ends with:"
        tail -n 30 "$log" | sed 's/^/    /'
        rm -f "$log"
        exit 1
    fi
    rm -f "$log"

    echo "  ✓ Built $RELEASE_DIR"
    echo ""
}

# Create installation directories
setup_directories() {
    echo "Setting up installation directories..."

    $SUDO mkdir -p "$BIN_DIR"
    $SUDO mkdir -p "$LIB_DIR"

    echo "  ✓ Created $BIN_DIR"
    echo "  ✓ Created $LIB_DIR"
    echo ""
}

# Install files
install_files() {
    echo "Installing Nex files..."

    # Re-running the installed copy's own install.sh into the same prefix
    # has nothing to copy (and must not delete what it is about to copy).
    if [[ "$(cd "$RELEASE_DIR" && pwd -P)" == "$(cd "$LIB_DIR" && pwd -P)" ]]; then
        echo "  ✓ Nex is already in $LIB_DIR"
        echo ""
        return
    fi

    # Remove what earlier installers put here first, so deleted or renamed
    # files do not linger: older ones copied the Clojure source as well.
    $SUDO rm -rf "$LIB_DIR/src" "$LIB_DIR/grammar" "$LIB_DIR/deps" \
        "$LIB_DIR/bin" "$LIB_DIR/lib"
    $SUDO rm -f "$LIB_DIR/deps.edn" "$LIB_DIR/nex.jar" "$LIB_DIR/classpath" \
        "$LIB_DIR/aot-training.nex" "$LIB_DIR/LICENSE" "$LIB_DIR/README.md" \
        "$LIB_DIR/install.sh" "$LIB_DIR/install.ps1"

    $SUDO cp -R "$RELEASE_DIR/." "$LIB_DIR/"

    echo "  ✓ Installed Nex to $LIB_DIR"
    echo ""
}

# Install executable: a link to the launcher inside the installed folder,
# which finds that folder by following the link.
install_executable() {
    echo "Installing nex executable..."

    $SUDO rm -f "$BIN_DIR/nex"
    $SUDO ln -s "$LIB_DIR/bin/nex" "$BIN_DIR/nex"

    echo "  ✓ Installed nex command to $BIN_DIR/nex"
    echo ""
}

# Verify installation
# Run a real program through the installed command, so a broken install is
# reported here rather than on the user's first program.
verify_installation() {
    echo "Verifying installation..."

    local tmpdir out status=0
    tmpdir="$(mktemp -d)"
    echo 'print(6 * 7)' > "$tmpdir/hello.nex"
    out="$("$BIN_DIR/nex" "$tmpdir/hello.nex" 2>&1)" || status=$?
    rm -rf "$tmpdir"

    if [[ $status -ne 0 ]] || ! grep -qx '42' <<< "$out"; then
        echo ""
        echo "Error: nex was installed to $BIN_DIR/nex, but it could not run a test program."
        echo "Its output was:"
        sed 's/^/    /' <<< "$out"
        echo ""
        echo "Please report this at https://github.com/vijaymathew/nex/discussions"
        exit 1
    fi

    echo "  ✓ nex runs programs"
    echo ""
}

# Main installation
main() {
    choose_sudo
    check_prerequisites
    [[ "$PREBUILT" == true ]] || build
    setup_directories
    install_files
    install_executable
    verify_installation

    echo "╔════════════════════════════════════════════════════════════╗"
    echo "║          Nex Language Installed Successfully!              ║"
    echo "╚════════════════════════════════════════════════════════════╝"
    echo ""
    echo "Installation details:"
    echo "  Target:      $TARGET"
    echo "  Executable:  $BIN_DIR/nex"
    echo "  Library:     $LIB_DIR"
    echo ""
    echo "Try it out:"
    echo "  nex                  # Start REPL"
    echo "  nex help             # Show help"
    echo "  nex eval 'print(42)' # Evaluate code"
    echo ""

    if ! command -v nex &> /dev/null; then
        echo "Note: Add $BIN_DIR to your PATH to use 'nex' from anywhere:"
        echo "  export PATH=\"$BIN_DIR:\$PATH\""
        echo ""
        echo "Add this line to your ~/.bashrc or ~/.zshrc to make it permanent."
        echo ""
    fi
}

main
