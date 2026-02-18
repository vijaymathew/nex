# Nex Quick Start Guide

Get up and running with Nex in minutes!

## One-Line Install (Linux/macOS)

### Fully Automatic (JVM)

Installs Java, Clojure, and Nex with no user interaction:

```bash
./install.sh jvm --install-deps
```

### Interactive (asks before installing)

```bash
./install.sh
```

## What Gets Installed

- **Executable**: `/usr/local/bin/nex`
- **Library**: `/usr/local/lib/nex`
- **Dependencies**: Java 11+, Clojure CLI (if using `--install-deps`)

## Your First Nex Program

### 1. Start the REPL

```bash
nex
```

### 2. Try Some Code

```nex
nex> print("Hello, Nex!")
Hello, Nex!

nex> let x := 42
42

nex> print(x * 2)
84
```

### 3. Create a Class

```nex
nex> class Point
...> feature
...>   x: Integer
...>   y: Integer
...> end
Point

nex> let p := create Point.make
Point
```

Type `:quit` to exit the REPL.

## Create Your First File

Create `hello.nex`:

```nex
class Greeter
feature
  name: String

  greet() do
    print("Hello, ")
    print(name)
    print("!")
  end

create
  make(n: String) do
    let name := n
  end
end

let greeter := create Greeter.make("World")
greeter.greet
```

### Run It

```bash
nex eval "$(cat hello.nex)"
```

### Compile to Java

```bash
nex compile java hello.nex HelloGreeter.java
```

### Compile to JavaScript

```bash
nex compile js hello.nex hello.js
```

## Common Commands

| Command | Purpose |
|---------|---------|
| `nex` | Start REPL |
| `nex compile java file.nex` | Compile to Java |
| `nex compile js file.nex` | Compile to JavaScript |
| `nex format file.nex` | Format code |
| `nex doc file.nex` | Generate docs |
| `nex eval 'code'` | Run code snippet |
| `nex help` | Show help |

## Key Language Features

### Design by Contract

```nex
class BankAccount
feature
  balance: Integer

  deposit(amount: Integer)
    require
      positive: amount > 0
    do
      let balance := balance + amount
    ensure
      increased: balance > old balance
    end
end
```

### Generics

```nex
class Stack [T]
feature
  items: Array [T]

  push(item: T) do
    -- implementation
  end

  pop(): T do
    -- implementation
  end
end
```

### Inheritance

```nex
class Animal
feature
  speak() do
    print("Animal sound")
  end
end

class Dog
inherit
  Animal
    redefine
      speak
    end
end
feature
  speak() do
    print("Woof!")
  end
end
```

## Platform-Specific Code

```nex
class FileHandler
feature
  read(path: String) do
    with "java" do
      -- Java file reading code
    end

    with "javascript" do
      -- JavaScript file reading code
    end
  end
end
```

## Next Steps

### Documentation
- 📖 [Full Installation Guide](../INSTALL.md)
- 🛠️ [CLI Reference](CLI.md)
- 📝 [Documentation Generator](DOCGEN.md)
- 🔧 [Development Guide](DEVELOPMENT.md)
- 🌐 [ClojureScript Support](CLOJURESCRIPT.md)

### Examples
Explore the `examples/` directory:
```bash
ls examples/*.nex
nex compile java examples/create_example.nex
```

### Editor Support
- **Emacs**: See `editor/emacs/` for nex-mode
- **Other editors**: Contributions welcome!

## Troubleshooting

### "nex: command not found"

Add to your PATH:
```bash
export PATH="/usr/local/bin:$PATH"
```

Add to `~/.bashrc` or `~/.zshrc` to make permanent.

### Dependencies Missing

Run installer with auto-install:
```bash
./install.sh jvm --install-deps
```

### On macOS: "brew: command not found"

Install Homebrew first:
```bash
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

Then run installer again.

## Getting Help

- Run `nex help` for command usage
- Check `docs/` directory for detailed documentation
- Report issues on GitHub

## Quick Reference Card

### Installation
```bash
./install.sh jvm --install-deps    # Automatic
./install.sh                       # Interactive
```

### Essential Commands
```bash
nex                                # REPL
nex compile java file.nex          # To Java
nex compile js file.nex            # To JavaScript
nex format src/                    # Format
nex doc src/ docs/                 # Generate docs
```

### REPL Commands
```nex
:help          # Show REPL help
:quit          # Exit REPL
```

### Sample Program
```nex
class Point
feature
  x: Integer
  y: Integer

create
  make(a: Integer, b: Integer)
    do
      let x := a
      let y := b
    end
end

let p := create Point.make(10, 20)
print(p.x)
```

That's it! You're ready to start programming in Nex! 🚀
