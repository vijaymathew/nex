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

nex> 42
42

nex> 1 + 2
3

nex> 1 < 2
true

nex> "hello, world"
hello, world

nex> let x := 42
42

nex> x + 10
52

nex> print(x)
42

nex> :vars
Defined variables:
  • x = 42

nex>
nex> # Empty lines are ignored - press Enter to continue
nex> :quit
Goodbye!
```

**Expression Evaluation:**
- Literals and expressions are automatically evaluated and displayed
- Examples: `42`, `1 + 2`, `"hello"`, `1 < 2`
- Variables in expressions work: `x + 10`
- To see a variable's value alone, use: `print(x)` or `:vars`

**Exit the REPL:**
- Type `:quit`, `:q`, or `:exit`
- Press Ctrl+D (EOF)

**Note:** Empty lines do not exit the REPL - they are simply ignored.

### Compile

Compile Nex source to target languages (Java or JavaScript):

```bash
nex compile <target> <input.nex> [output]
```

**Targets:**
- `jvm` - Compile to a standalone JVM JAR using the bytecode backend
- `javascript` or `js` - Generate JavaScript source code

Prefer `jvm` for JVM deployment.

**Examples:**

```bash
# Preferred JVM backend
nex compile jvm MyClass.nex

# Preferred JVM backend
nex compile jvm MyClass.nex build/

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

### Run The Echo Server And Client

The repository includes small JVM-only networking examples under `examples/`:

```bash
# Terminal 1: start the server REPL and load the server example
nex
:load examples/echo_server.nex
create Echo_Server.make(4040)
```

In another terminal:

```bash
# Terminal 2: start the client REPL and load the client example
nex
:load examples/echo_client.nex
create Echo_Client.make("127.0.0.1", 4040, "hello")
```

The server accepts one client, echoes one line, and exits.

These examples use `intern net/Server_Socket` and `intern net/Tcp_Socket`, so they work on the JVM runtime and are not available in the JavaScript target.

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

### Extra JVM Dependencies

`bin/nex` does not fetch Java dependencies itself. It inherits whatever is already
on the JVM classpath of the `clojure` process it launches.

#### Start `bin/nex` with extra JARs

If you already have dependency jars on disk, put them on `CLASSPATH` before
starting the REPL:

```bash
export CLASSPATH="/opt/myapp/lib/postgresql-42.7.3.jar:/opt/myapp/lib/HikariCP-5.1.0.jar"
./bin/nex
```

Then Nex `import` can resolve classes from those jars:

```nex
import java.sql.DriverManager
import com.zaxxer.hikari.HikariDataSource
```

#### Compile a standalone JAR with extra Maven dependencies on the classpath

`nex compile jvm` shades the current JVM classpath into the generated output jar.
So if you want extra Maven dependencies packaged into the generated jar, launch
the compiler with those deps already on the classpath:

```bash
cd /path/to/nex

clojure -Sdeps '{:deps {com.h2database/h2 {:mvn/version "2.2.224"}
                        com.zaxxer/HikariCP {:mvn/version "5.1.0"}}}' \
  -M -m nex.compiler.jvm.file /path/to/app.nex /path/to/build
```

That produces `/path/to/build/app.jar`, and the shaded jar includes those Maven
dependencies because they were present on the compiler classpath.

For a repeatable project setup, put those dependencies in a local `deps.edn`
alias and run the compiler through that alias:

```clojure
;; deps.edn
{:aliases
 {:nex-build
  {:extra-deps {com.h2database/h2 {:mvn/version "2.2.224"}
                com.zaxxer/HikariCP {:mvn/version "5.1.0"}}}}}
```

```bash
cd /path/to/nex

clojure -A:nex-build \
  -M -m nex.compiler.jvm.file /path/to/app.nex /path/to/build
```

That uses the extra Maven dependencies from the local alias and includes them in
the generated shaded jar.

If you prefer to keep using `./bin/nex`, put those dependencies in a `deps.edn`
that the launched `clojure` process will see, then run:

```bash
./bin/nex compile jvm /path/to/app.nex /path/to/build
```

## Exit Codes

The `nex` command uses standard exit codes:

- `0` - Success
- `1` - Error (invalid arguments, file not found, compilation error, etc.)

Check exit codes in scripts:

```bash
if nex compile jvm MyClass.nex build/; then
    echo "Compilation successful"
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

echo "Compiling to standalone JVM jars..."
for file in src/*.nex; do
    basename=$(basename "$file" .nex)
    nex compile jvm "$file" target/
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
JARS := $(patsubst $(SRC_DIR)/%.nex,$(TARGET_DIR)/%.jar,$(NEX_FILES))

all: format docs compile

format:
	nex format $(SRC_DIR)

docs:
	nex doc $(SRC_DIR) $(DOCS_DIR)

compile: $(JARS)

$(TARGET_DIR)/%.jar: $(SRC_DIR)/%.nex
	@mkdir -p $(TARGET_DIR)
	nex compile jvm $< $(TARGET_DIR)

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
            nex compile jvm "$file" build/
          done
```

## Tips and Tricks

### Shell Alias

Add to your `.bashrc` or `.zshrc`:

```bash
alias nex-jvm='nex compile jvm'
alias nex-js='nex compile javascript'
alias nex-fmt='nex format'
```

Then use:

```bash
nex-jvm MyClass.nex
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
ls src/*.nex | entr nex compile jvm src/_ build/
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
