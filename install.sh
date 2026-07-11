#!/bin/bash
set -e

# Nex Language Installation Script
# Usage: ./install.sh [--install-deps] [--prefix DIR]

VERSION="0.2.0"
TARGET="jvm"
INSTALL_DEPS=false
INSTALL_PREFIX="${INSTALL_PREFIX:-/usr/local}"
BIN_DIR="$INSTALL_PREFIX/bin"
LIB_DIR="$INSTALL_PREFIX/lib/nex"
USER_DEPS_DIR="${HOME}/.nex/deps"

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

# Install Java
install_java() {
    echo "Installing Java..."

    case "$OS" in
        ubuntu|debian)
            sudo apt-get update -qq
            sudo apt-get install -y default-jdk
            ;;
        fedora)
            sudo dnf install -y java-latest-openjdk
            ;;
        centos|rhel)
            sudo yum install -y java-11-openjdk
            ;;
        arch|manjaro)
            sudo pacman -S --noconfirm jdk-openjdk
            ;;
        macos)
            if ! command -v brew &> /dev/null; then
                echo "Error: Homebrew not found. Install it from https://brew.sh"
                exit 1
            fi
            brew install openjdk
            ;;
        *)
            echo "Error: Unsupported OS for automatic Java installation: $OS"
            echo "Please install Java 11 or later manually:"
            echo "  https://adoptium.net/"
            exit 1
            ;;
    esac

    echo "  ✓ Java installed"
}

# Install Clojure CLI
install_clojure() {
    echo "Installing Clojure CLI..."

    if [[ "$OS" == "macos" ]]; then
        if ! command -v brew &> /dev/null; then
            echo "Error: Homebrew not found. Install it from https://brew.sh"
            exit 1
        fi
        brew install clojure/tools/clojure
    else
        # Linux installation
        local tmpdir=$(mktemp -d)
        cd "$tmpdir"
        curl -s -O https://download.clojure.org/install/linux-install-1.11.1.1208.sh
        chmod +x linux-install-1.11.1.1208.sh
        sudo ./linux-install-1.11.1.1208.sh
        cd - > /dev/null
        rm -rf "$tmpdir"
    fi

    echo "  ✓ Clojure CLI installed"
}

# Check and optionally install prerequisites
check_prerequisites() {
    echo "Checking prerequisites..."

    detect_os

    # Check Java
    if ! command -v java &> /dev/null; then
        if [[ "$INSTALL_DEPS" == true ]]; then
            install_java
        else
            echo ""
            echo "Java is not installed."
            read -p "Would you like to install it automatically? (y/n) " -n 1 -r
            echo
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                install_java
            else
                echo "Error: Java is required for JVM installation."
                echo "Install Java 11 or later from: https://adoptium.net/"
                exit 1
            fi
        fi
    else
        echo "  ✓ Java $(java -version 2>&1 | head -n 1)"
    fi

    # Check Clojure
    if ! command -v clojure &> /dev/null; then
        if [[ "$INSTALL_DEPS" == true ]]; then
            install_clojure
        else
            echo ""
            echo "Clojure CLI is not installed."
            read -p "Would you like to install it automatically? (y/n) " -n 1 -r
            echo
            if [[ $REPLY =~ ^[Yy]$ ]]; then
                install_clojure
            else
                echo "Error: Clojure CLI is required for JVM installation."
                echo "Install from: https://clojure.org/guides/install_clojure"
                exit 1
            fi
        fi
    else
        echo "  ✓ Clojure CLI"
    fi
    echo ""
}

# Build for target
build() {
    echo "Building Nex..."
    echo "  No build required for JVM (using Clojure CLI)"
    echo ""
}

# Create installation directories
setup_directories() {
    echo "Setting up installation directories..."

    sudo mkdir -p "$BIN_DIR"
    sudo mkdir -p "$LIB_DIR"

    echo "  ✓ Created $BIN_DIR"
    echo "  ✓ Created $LIB_DIR"
    echo ""
}

# Install files
install_files() {
    echo "Installing Nex files..."
    echo "  Cleaning previously installed managed files to avoid stale namespace conflicts..."

    # Remove previously installed managed content first so deleted/renamed source
    # files do not linger and shadow newer namespaces.
    sudo rm -rf "$LIB_DIR/src" "$LIB_DIR/grammar"
    sudo rm -f "$LIB_DIR/deps.edn"

    # Copy source files
    sudo cp -r src "$LIB_DIR/"
    sudo cp -r grammar "$LIB_DIR/"
    sudo cp deps.edn "$LIB_DIR/"

    echo "  ✓ Installed source files to $LIB_DIR"
    echo ""
}

install_shipped_libraries() {
    echo "Installing shipped Nex libraries to $USER_DEPS_DIR..."

    mkdir -p "$USER_DEPS_DIR"

    if [[ -d "lib" ]]; then
        shopt -s nullglob
        for entry in lib/*; do
            name="$(basename "$entry")"
            target="$USER_DEPS_DIR/$name"
            rm -rf "$target"
            cp -r "$entry" "$target"
            echo "  ✓ Installed library namespace $name"
        done
        shopt -u nullglob
    fi

    echo ""
}

# Install executable
install_executable() {
    echo "Installing nex executable..."

    sudo cp bin/nex "$BIN_DIR/nex"
    sudo chmod +x "$BIN_DIR/nex"

    # Update the NEX_HOME in the installed script
    sudo sed -i.bak "s|NEX_HOME=.*|NEX_HOME=\"$LIB_DIR\"|" "$BIN_DIR/nex"
    sudo rm -f "$BIN_DIR/nex.bak"

    echo "  ✓ Installed nex command to $BIN_DIR/nex"
    echo ""
}

# Verify installation
verify_installation() {
    echo "Verifying installation..."

    if ! command -v nex &> /dev/null; then
        echo "Warning: 'nex' command not found in PATH."
        echo "You may need to add $BIN_DIR to your PATH:"
        echo "  export PATH=\"$BIN_DIR:\$PATH\""
        echo ""
        return
    fi

    echo "  ✓ nex command is available"
    echo ""
}

# Main installation
main() {
    check_prerequisites
    build
    setup_directories
    install_files
    install_shipped_libraries
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
    echo "  Shipped lib: $USER_DEPS_DIR"
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
