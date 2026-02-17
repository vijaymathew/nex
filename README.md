# Nex Programming Language

**A high-level language designed to support software design and implementation from a single source.**

Nex is an Eiffel-inspired programming language that combines elegant, English-like syntax with powerful Design by Contract features. It's ideal for both human developers and LLM-based code generation, bridging the gap between software specification and implementation.

## Key Features

### 🎯 Design from Single Source
- **Unified Design & Implementation**: Write specifications and code together
- **Design by Contract**: Built-in support for preconditions, postconditions, and invariants
- **Self-documenting**: Contract clauses serve as both specification and runtime checks

### 🤖 LLM-Friendly Syntax
- **English-like Keywords**: `feature`, `require`, `ensure`, `invariant`, `from`, `until`
- **Natural Flow**: `if...then...else...end`, `from...until...do...end`
- **Explicit Intent**: Clear syntax makes LLM code generation more reliable

### 💎 Modern Language Features
- **Generic Types**: Parameterized classes with constraints (`List [G]`, `Map [K -> Hashable, V]`)
- **Arrays & Maps**: Built-in collections with subscript access (`arr[0]`, `map["key"]`)
- **Multiple Inheritance**: With rename and redefine clauses
- **Lexical Scoping**: Scoped blocks with variable shadowing
- **Loop Contracts**: Invariants and variants for verified iteration
- **Grouped Parameters**: `method(a, b: Integer)` syntax
- **Parameterless Calls**: Methods without parameters can omit parentheses (`obj.show`)
- **Object Creation**: Explicit `create` keyword for instantiation
- **Code Generation**: Translate Nex to Java and JavaScript (more targets coming)

## Quick Start

### Prerequisites
- [Clojure CLI tools](https://clojure.org/guides/install_clojure) (version 1.11+)
- Java 11 or higher

### Installation

**Automatic Installation** (installs Java & Clojure if needed):

```bash
# Clone the repository
git clone https://github.com/vijaymathew/nex.git
cd nex

# One-line install with automatic dependencies
./install.sh jvm --install-deps

# Or interactive install (asks before installing dependencies)
./install.sh

# Verify installation
nex help
```

**Supported Platforms:** Ubuntu, Debian, Fedora, CentOS, RHEL, Arch, Manjaro, macOS

For detailed installation instructions, see [INSTALL.md](INSTALL.md) or [Quick Start Guide](docs/QUICK_START.md).

**Development Setup (without installation):**

```bash
# Verify Clojure is installed
clojure -M -e '(println "Nex is ready!")'

# Set NEX_HOME for local development
export NEX_HOME=$(pwd)
```

## Usage

### 1. Interactive REPL

The easiest way to get started is with the Nex REPL:

```bash
# After installation
nex

# Or without installation (from project directory)
clojure -M:repl
```

**Quick REPL Tour:**

```nex
nex> print("Hello, Nex!")
"Hello, Nex!"

nex> let x := 10
=> 10

nex> if x > 5 then print("big") else print("small") end
"big"

nex> class Point
...    feature
...      x: Integer
...      y: Integer
...  end
Class(es) registered: Point

nex> :help    # Show available commands
nex> :classes # List defined classes
nex> :vars    # List defined variables
nex> :quit    # Exit the REPL
```

The REPL supports:
- ✓ Interactive expression evaluation
- ✓ Persistent variables across inputs
- ✓ Multi-line class definitions (automatic detection)
- ✓ All Nex language features (loops, conditionals, contracts)
- ✓ Helpful commands (`:help`, `:classes`, `:vars`, `:clear`)

See [examples/demo_repl.md](examples/demo_repl.md) for more examples.

### 2. Running the Interpreter Programmatically

Execute Nex code from Clojure:

```bash
# From Clojure REPL
clojure -M
```

```clojure
(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

;; Parse and execute Nex code
(def code "
class Math
  feature
    gcd(a, b: Integer) do
      from
        let x := a
        let y := b
      until
        x = y
      do
        if x > y then
          let x := x - y
        else
          let y := y - x
        end
      end
      print(x)
    end
end")

;; Create interpreter context
(def ast (p/ast code))
(def ctx (interp/make-context))

;; Register class and execute
(interp/register-class ctx (first (:classes ast)))
```

### 3. Running the Java Translator

Translate Nex code to Java:

```bash
clojure -M
```

```clojure
(require '[nex.generator.java :as java])

;; Translate Nex code to Java
(def nex-code "
class Account
  feature
    balance: Integer

    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        let balance := balance + amount
      ensure
        increased: balance >= 0
      end
end")

;; Generate Java code (with contracts for development)
(println (java/translate nex-code))

;; Generate Java code WITHOUT contracts (for production)
(println (java/translate nex-code {:skip-contracts true}))

;; Or translate a file
(java/translate-file "input.nex" "Output.java")

;; Production build without contracts
(java/translate-file "input.nex" "Output.java" {:skip-contracts true})
```

**Output:**
```java
public class Account {
    private int balance;

    public void deposit(int amount) {
        assert (amount > 0) : "Precondition violation: positive";
        balance = (balance + amount);
        assert (balance >= 0) : "Postcondition violation: increased";
    }
}
```

### 4. Running the JavaScript Translator

Translate Nex code to modern JavaScript (ES6+):

```bash
clojure -M
```

```clojure
(require '[nex.generator.javascript :as js])

;; Translate Nex code to JavaScript
(def nex-code "
class Account
  feature
    balance: Integer

    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        balance := balance + amount
      ensure
        increased: balance >= 0
      end
end")

;; Generate JavaScript code (with contracts for development)
(println (js/translate nex-code))

;; Generate JavaScript code WITHOUT contracts (for production)
(println (js/translate nex-code {:skip-contracts true}))

;; Or translate a file
(js/translate-file "input.nex" "output.js")

;; Production build without contracts
(js/translate-file "input.nex" "output.js" {:skip-contracts true})
```

**Output:**
```javascript
class Account {
  constructor() {
    this.balance = 0;
  }

  /**
   * @param {number} amount
   */
  deposit(amount) {
    if (!((amount > 0))) throw new Error("Precondition violation: positive");
    balance = (balance + amount);
    if (!((balance >= 0))) throw new Error("Postcondition violation: increased");
  }
}
```

### 5. Running Tests

The test suite includes 47 tests covering all language features:

```bash
# Run all tests
clojure -M:test test/scripts/run_tests.clj

# Run specific test suite
clojure -M:test -e "
(require '[clojure.test :as test])
(require 'nex.loops-test)
(test/run-tests 'nex.loops-test)
"
```

**Expected Output:**
```
╔════════════════════════════════════════════════════════════╗
║                    RUNNING ALL TESTS                       ║
╚════════════════════════════════════════════════════════════╝

Testing nex.loops-test
Testing nex.if-conditions-test
...

Ran 47 tests containing 111 assertions.
0 failures, 0 errors.

Total tests: 47
Passed: 111
Failed: 0
Errors: 0
```

### 6. Running Examples

Explore language features through comprehensive examples:

```bash
# GCD algorithm with loop contracts
clojure examples/demo_gcd.clj

# Design by Contract demonstration
clojure examples/demo_complete_dbc.clj

# Multiple inheritance
clojure examples/demo_complete_inheritance.clj

# Nex to Java translation
clojure examples/demo_nex_to_java.clj

# Nex to JavaScript translation
clojure examples/demo_nex_to_javascript.clj

# Grouped parameter syntax
clojure examples/demo_param_syntax.clj

# All other examples
clojure examples/demo_inheritance.clj
clojure examples/demo_contracts.clj
clojure examples/demo_let.clj
```

## Language Overview

### Example: Bank Account with Design by Contract

```nex
class SavingsAccount
inherit
  Account
    rename
      deposit as account_deposit
    redefine
      deposit
    end
feature
  balance: Integer
  interest_rate: Real

  deposit(amount: Integer)
    require
      positive_amount: amount > 0
    do
      let balance := balance + amount
      update_interest()
    ensure
      balance_increased: balance >= old_balance
      amount_recorded: balance = old_balance + amount
    end

  update_interest() do
    let interest := balance * interest_rate
    let balance := balance + interest
  end

invariant
  valid_balance: balance >= 0
  valid_rate: interest_rate >= 0 and interest_rate <= 1
end
```

### Example: Loop with Contracts

```nex
class Math
  feature
    gcd(a, b: Integer) do
      from
        let x := a
        let y := b
      invariant
        x_positive: x > 0
        y_positive: y > 0
      variant
        x + y
      until
        x = y
      do
        if x > y then
          let x := x - y
        else
          let y := y - x
        end
      end
      print(x)
    end
end
```

## Language Features

### Design by Contract
- **Preconditions** (`require`): Conditions that must be true before method execution
- **Postconditions** (`ensure`): Conditions guaranteed after method execution
- **Class Invariants** (`invariant`): Conditions that always hold for class instances
- **Loop Invariants**: Conditions maintained throughout loop execution
- **Loop Variants**: Expressions that decrease with each iteration (proving termination)

### Object-Oriented Programming
- **Classes** with fields and methods
- **Constructors** for object initialization
- **Single Inheritance** with `inherit` clause
- **Multiple Inheritance** with comma-separated parents
- **Method Renaming**: Avoid naming conflicts
- **Method Redefinition**: Explicit override declarations

### Control Flow
- **If-Then-Else**: `if condition then ... else ... end`
- **Loops**: `from init invariant inv variant var until condition do ... end`
- **Scoped Blocks**: `do ... end` with lexical scoping

### Type System
- **Basic Types**: Integer, String, Boolean, Real
- **Type Annotations**: Explicit parameter and field types
- **Grouped Parameters**: `method(a, b, c: Integer)`

### Modern Features
- **Local Variables**: `let x := value` or `let x: Type := value` (optional type annotations)
- **Variable Shadowing**: Inner scopes can shadow outer variables
- **Method Calls**: `object.method(args)`
- **Expressions**: Full support for arithmetic, logical, and comparison operators

## Project Structure

```
nex/
├── src/nex/              # Interpreter and code generation
│   ├── parser.clj        # ANTLR parser integration
│   ├── walker.clj        # AST transformation
│   ├── interpreter.clj   # Runtime interpreter
│   └── generator/
│       ├── java.clj      # Java code generator
│       └── javascript.clj # JavaScript (ES6+) code generator
├── test/                 # Test suite (73 tests, 184 assertions)
│   ├── nex/              # Organized test files
│   │   ├── loops_test.clj
│   │   ├── if_conditions_test.clj
│   │   ├── inheritance_test.clj
│   │   └── generator/
│   │       ├── java_test.clj
│   │       └── javascript_test.clj
│   ├── scripts/          # Test runners and utilities
│   │   ├── run_tests.clj
│   │   └── test_*.sh
│   └── legacy/           # Archived test files
├── examples/             # Example programs and demos
│   ├── demo_gcd.clj
│   ├── demo_nex_to_java.clj
│   ├── demo_nex_to_javascript.clj
│   └── ... (8 more demos)
├── docs/                 # Documentation
│   ├── JAVASCRIPT.md
│   ├── GENERICS.md
│   ├── ARRAYS_MAPS.md
│   ├── CREATE.md
│   ├── TYPES.md
│   └── ... (15 more docs)
├── editor/               # Editor integrations
│   └── emacs/
│       └── nex-mode.el   # Emacs major mode
├── grammar/
│   └── nexlang.g4        # ANTLR grammar definition
├── README.md             # This file
├── LICENSE               # License information
├── deps.edn              # Clojure dependencies
└── nex-repl              # REPL launcher script
```

## Documentation

- **[Project Structure](docs/PROJECT_STRUCTURE.md)** - Detailed project organization
- **[Type System](docs/TYPES.md)** - Basic types and default initialization
- **[Generic Types](docs/GENERICS.md)** - Parameterized classes with constraints
- **[Arrays and Maps](docs/ARRAYS_MAPS.md)** - Collections with subscript access
- **[Let Syntax Guide](docs/LET_SYNTAX.md)** - Typed and untyped let statements
- **[Create Keyword](docs/CREATE.md)** - Object instantiation and constructors
- **[Parameterless Calls](docs/PARAMETERLESS_CALLS.md)** - Calling methods without parentheses
- **[JavaScript Translator](docs/JAVASCRIPT.md)** - Nex to JavaScript (ES6+) translation
- **[Test Documentation](test/README.md)** - Test suite overview
- **[Examples Documentation](examples/README.md)** - Example programs guide

## Editor Support

### Emacs

A comprehensive Emacs major mode is available with:
- ✓ Syntax highlighting for all Nex keywords and constructs
- ✓ Automatic indentation
- ✓ REPL integration (evaluate buffer, region, or file)
- ✓ Navigation support (jump to classes/methods)
- ✓ Imenu support for quick navigation
- ✓ Comment support

**Installation:**
```elisp
;; Add to ~/.emacs.d/init.el
(load-file "/path/to/nex/editor/emacs/nex-mode.el")
```

**Usage:**
- Open any `.nex` file to activate the mode automatically
- `C-c C-z` - Start Nex REPL
- `C-c C-c` - Evaluate buffer in REPL
- `C-c C-r` - Evaluate region in REPL

See **[docs/EMACS.md](docs/EMACS.md)** for complete documentation and configuration options.

### Other Editors

Editor support for VS Code, Vim, and other editors is planned. Contributions welcome!

## Why Nex?

### For Software Design
- Write specifications and implementation together
- Contracts serve as executable documentation
- Single source of truth for design and code

### For LLM Code Generation
- Clear, English-like syntax reduces ambiguity
- Explicit contracts help LLMs understand intent
- Structured format makes generation more reliable

### For Developers
- Catch bugs early with runtime contract checking
- Self-documenting code through contracts
- Multiple inheritance with conflict resolution
- Generate production code (Java, JavaScript, more coming)

## Roadmap

- [x] JavaScript code generator (ES6+)
- [ ] TypeScript code generator
- [ ] Python code generator
- [ ] C++ code generator
- [ ] IDE support (LSP server)
- [ ] Standard library
- [ ] Package manager
- [ ] Compile-time contract checking
- [ ] Proof obligations generation

## Contributing

Contributions are welcome! Please read our **[Contributing Guidelines](CONTRIBUTING.md)** and submit pull requests.

## License

  This Source Code Form is subject to the terms of the Mozilla Public
  License, v. 2.0. If a copy of the MPL was not distributed with this
  file, You can obtain one at https://mozilla.org/MPL/2.0/.

## Acknowledgments

Nex is inspired by Eiffel's Design by Contract philosophy and modern language design principles.

---

**Built with Clojure and ANTLR** | **Designed for clarity and correctness**
