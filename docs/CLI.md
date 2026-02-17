# Nex Command-Line Interface

The `nex` command provides a unified interface for all Nex language tools.

## Installation

See [INSTALL.md](../INSTALL.md) for installation instructions.

## Commands

### Interactive REPL

Start the Nex Read-Eval-Print Loop:

```bash
nex
```

**Example session:**
```nex
nex> print("Hello, World!")
Hello, World!

nex> let x := 42
42

nex> print(x + 10)
52

nex> :quit
Goodbye!
```

### Compile

Compile Nex source to target languages (Java or JavaScript):

```bash
nex compile <target> <input.nex> [output]
```

**Targets:**
- `java` - Generate Java source code
- `javascript` or `js` - Generate JavaScript source code

**Examples:**

```bash
# Compile to Java (output to stdout)
nex compile java MyClass.nex

# Compile to Java (save to file)
nex compile java MyClass.nex MyClass.java

# Compile to JavaScript
nex compile js MyClass.nex MyClass.js
```

**Generated Code:**

The generated code includes:
- Design by Contract assertions
- Javadoc/JSDoc comments from `note` clauses
- Platform-specific implementations for `with` statements

### Format

Format Nex source files with consistent style:

```bash
nex format <file|directory>
```

**Examples:**

```bash
# Format a single file (modifies in-place)
nex format MyClass.nex

# Format all .nex files in a directory
nex format src/

# Format with subdirectories
nex format .
```

**Formatting Rules:**
- 2-space indentation
- Spaces only (no tabs)
- Consistent keyword alignment
- Contract clause indentation

### Documentation

Generate Markdown documentation from Nex source:

```bash
nex doc <file|directory> [output]
```

**Examples:**

```bash
# Generate docs to stdout
nex doc MyClass.nex

# Generate docs to file
nex doc MyClass.nex docs/MyClass.md

# Generate docs for all files in directory
nex doc src/ docs/
```

**Generated Documentation Includes:**
- Class overview with `note` descriptions
- Class invariants
- Constructor signatures with contracts
- Method signatures with parameters, return types, and contracts
- Field definitions with visibility
- Visibility indicators (🌐 Public, 🔒 Private, 🔑 Selective)

See [DOCGEN.md](DOCGEN.md) for detailed documentation generator guide.

### Evaluate

Evaluate a Nex code snippet:

```bash
nex eval '<code>'
```

**Examples:**

```bash
# Print to console
nex eval 'print("Hello, World!")'

# Compute expression
nex eval 'print(2 + 2)'

# Define and use variables
nex eval 'let x := 10 print(x * 2)'
```

**Note:** The eval command creates a temporary file, parses it, and executes it. For complex programs, use the REPL or create a `.nex` file.

### Help

Display help information:

```bash
nex help
```

Shows:
- Command usage
- Available targets
- Example commands
- Environment variables

### Version

Display version information:

```bash
nex version
```

## Environment Variables

### NEX_HOME

The Nex installation directory. Set automatically during installation.

```bash
export NEX_HOME=/usr/local/lib/nex
```

During development, you can set this to your project directory:

```bash
export NEX_HOME=/path/to/nex/repo
./bin/nex help
```

## Exit Codes

The `nex` command uses standard exit codes:

- `0` - Success
- `1` - Error (invalid arguments, file not found, compilation error, etc.)

Check exit codes in scripts:

```bash
if nex compile java MyClass.nex MyClass.java; then
    echo "Compilation successful"
    javac MyClass.java
else
    echo "Compilation failed"
    exit 1
fi
```

## Integration Examples

### Build Script

```bash
#!/bin/bash
# Build all Nex files

set -e

echo "Formatting Nex files..."
nex format src/

echo "Generating documentation..."
nex doc src/ docs/

echo "Compiling to Java..."
for file in src/*.nex; do
    basename=$(basename "$file" .nex)
    nex compile java "$file" "target/${basename}.java"
done

echo "Build complete!"
```

### Makefile

```makefile
.PHONY: all format docs compile clean

SRC_DIR = src
DOCS_DIR = docs
TARGET_DIR = target

NEX_FILES := $(wildcard $(SRC_DIR)/*.nex)
JAVA_FILES := $(patsubst $(SRC_DIR)/%.nex,$(TARGET_DIR)/%.java,$(NEX_FILES))

all: format docs compile

format:
	nex format $(SRC_DIR)

docs:
	nex doc $(SRC_DIR) $(DOCS_DIR)

compile: $(JAVA_FILES)

$(TARGET_DIR)/%.java: $(SRC_DIR)/%.nex
	@mkdir -p $(TARGET_DIR)
	nex compile java $< $@

clean:
	rm -rf $(TARGET_DIR) $(DOCS_DIR)
```

### CI/CD (GitHub Actions)

```yaml
name: Build Nex Project

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v2

      - name: Install Nex
        run: |
          ./install.sh jvm

      - name: Format Check
        run: |
          nex format src/
          git diff --exit-code

      - name: Generate Docs
        run: nex doc src/ docs/

      - name: Compile
        run: |
          for file in src/*.nex; do
            nex compile java "$file"
          done
```

## Tips and Tricks

### Shell Alias

Add to your `.bashrc` or `.zshrc`:

```bash
alias nex-java='nex compile java'
alias nex-js='nex compile javascript'
alias nex-fmt='nex format'
```

Then use:

```bash
nex-java MyClass.nex
nex-js MyClass.nex
nex-fmt src/
```

### Watch Mode (with entr)

Auto-format on file changes:

```bash
# Install entr first: apt-get install entr / brew install entr
ls src/*.nex | entr nex format src/_
```

Auto-compile on changes:

```bash
ls src/*.nex | entr nex compile java src/_
```

### Batch Operations

Format all files:

```bash
find . -name "*.nex" -exec nex format {} \;
```

Generate all docs:

```bash
for file in src/*.nex; do
    nex doc "$file" "docs/$(basename "$file" .nex).md"
done
```

## Troubleshooting

### Command Not Found

If `nex` is not found after installation:

```bash
# Check if it's installed
ls -l /usr/local/bin/nex

# Add to PATH if needed
export PATH="/usr/local/bin:$PATH"

# Or use full path
/usr/local/bin/nex help
```

### Permission Denied

If you get permission denied:

```bash
# Check executable bit
ls -l /usr/local/bin/nex

# Fix if needed
sudo chmod +x /usr/local/bin/nex
```

### Java/Clojure Not Found

The JVM version requires Java and Clojure:

```bash
# Check Java
java -version

# Check Clojure
clojure -h

# Install if missing (Ubuntu/Debian)
sudo apt-get install default-jdk
curl -O https://download.clojure.org/install/linux-install.sh
chmod +x linux-install.sh
sudo ./linux-install.sh
```

## See Also

- [INSTALL.md](../INSTALL.md) - Installation guide
- [DEVELOPMENT.md](DEVELOPMENT.md) - Development guide
- [DOCGEN.md](DOCGEN.md) - Documentation generator
- [REFERENCE.md](REFERENCE.md) - Language reference (if available)
