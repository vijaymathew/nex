#!/bin/bash
set -e

# Nex Language Installation Script
# Usage: ./install.sh [jvm|nodejs] [--install-deps]

VERSION="0.1.0"
TARGET="${1:-jvm}"
INSTALL_DEPS=false
INSTALL_PREFIX="${INSTALL_PREFIX:-/usr/local}"
BIN_DIR="$INSTALL_PREFIX/bin"
LIB_DIR="$INSTALL_PREFIX/lib/nex"

# Parse arguments
for arg in "$@"; do
    if [[ "$arg" == "--install-deps" ]]; then
        INSTALL_DEPS=true
    elif [[ "$arg" != "jvm" && "$arg" != "nodejs" ]]; then
        if [[ "$arg" != "$1" ]]; then  # Not the target argument
            echo "Warning: Unknown argument '$arg'"
        fi
    fi
done

echo "╔════════════════════════════════════════════════════════════╗"
echo "║              Nex Language Installer v$VERSION                 ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Validate target
if [[ "$TARGET" != "jvm" && "$TARGET" != "nodejs" ]]; then
    echo "Error: Invalid target '$TARGET'. Must be 'jvm' or 'nodejs'."
    echo "Usage: ./install.sh [jvm|nodejs] [--install-deps]"
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

# Install Node.js and npm
install_nodejs() {
    echo "Installing Node.js..."

    case "$OS" in
        ubuntu|debian)
            # Install NodeSource repository
            curl -fsSL https://deb.nodesource.com/setup_lts.x | sudo -E bash -
            sudo apt-get install -y nodejs
            ;;
        fedora)
            sudo dnf install -y nodejs npm
            ;;
        centos|rhel)
            # Install NodeSource repository
            curl -fsSL https://rpm.nodesource.com/setup_lts.x | sudo bash -
            sudo yum install -y nodejs
            ;;
        arch|manjaro)
            sudo pacman -S --noconfirm nodejs npm
            ;;
        macos)
            if ! command -v brew &> /dev/null; then
                echo "Error: Homebrew not found. Install it from https://brew.sh"
                exit 1
            fi
            brew install node
            ;;
        *)
            echo "Error: Unsupported OS for automatic Node.js installation: $OS"
            echo "Please install Node.js 16+ manually:"
            echo "  https://nodejs.org/"
            exit 1
            ;;
    esac

    echo "  ✓ Node.js installed"
}

# Check and optionally install prerequisites
check_prerequisites() {
    echo "Checking prerequisites..."

    detect_os

    if [[ "$TARGET" == "jvm" ]]; then
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
    else
        # Check Node.js
        if ! command -v node &> /dev/null; then
            if [[ "$INSTALL_DEPS" == true ]]; then
                install_nodejs
            else
                echo ""
                echo "Node.js is not installed."
                read -p "Would you like to install it automatically? (y/n) " -n 1 -r
                echo
                if [[ $REPLY =~ ^[Yy]$ ]]; then
                    install_nodejs
                else
                    echo "Error: Node.js is required for Node.js installation."
                    echo "Install Node.js 16+ from: https://nodejs.org/"
                    exit 1
                fi
            fi
        else
            echo "  ✓ Node.js $(node --version)"
        fi

        if ! command -v npm &> /dev/null; then
            echo "Error: npm is not installed but Node.js is."
            echo "This is unusual. Please reinstall Node.js from: https://nodejs.org/"
            exit 1
        else
            echo "  ✓ npm $(npm --version)"
        fi
    fi
    echo ""
}

# Build for target
build() {
    echo "Building Nex for $TARGET..."

    if [[ "$TARGET" == "jvm" ]]; then
        echo "  No build required for JVM (using Clojure CLI)"
    else
        echo "  Installing npm dependencies..."
        npm install --silent

        echo "  Compiling ClojureScript..."
        npx shadow-cljs release node

        if [[ ! -f "target/nex.js" ]]; then
            echo "Error: ClojureScript build failed. target/nex.js not found."
            exit 1
        fi

        echo "  ✓ ClojureScript build complete"
    fi
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

    # Copy source files
    sudo cp -r src "$LIB_DIR/"
    sudo cp -r grammar "$LIB_DIR/"
    sudo cp deps.edn "$LIB_DIR/"

    echo "  ✓ Installed source files to $LIB_DIR"

    if [[ "$TARGET" == "nodejs" ]]; then
        sudo cp target/nex.js "$LIB_DIR/"
        sudo cp nex-wrapper.js "$LIB_DIR/"
        sudo cp package.json "$LIB_DIR/"

        # Copy node_modules if they exist
        if [[ -d "node_modules" ]]; then
            sudo cp -r node_modules "$LIB_DIR/"
        fi

        echo "  ✓ Installed Node.js runtime files"
    fi
    echo ""
}

# Install executable
install_executable() {
    echo "Installing nex executable..."

    if [[ "$TARGET" == "jvm" ]]; then
        sudo cp bin/nex "$BIN_DIR/nex"
        sudo chmod +x "$BIN_DIR/nex"

        # Update the NEX_HOME in the installed script
        sudo sed -i.bak "s|NEX_HOME=.*|NEX_HOME=\"$LIB_DIR\"|" "$BIN_DIR/nex"
        sudo rm -f "$BIN_DIR/nex.bak"
    else
        sudo cp bin/nex-node.js "$BIN_DIR/nex"
        sudo chmod +x "$BIN_DIR/nex"

        # Update the NEX_HOME in the installed script
        sudo sed -i.bak "s|NEX_HOME=.*|NEX_HOME=\"$LIB_DIR\"|" "$BIN_DIR/nex"
        sudo rm -f "$BIN_DIR/nex.bak"
    fi

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
