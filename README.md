# Nex Programming Language

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/logo/nex-logo-concept-4-contract-orbit-dark.svg">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/logo/nex-logo-concept-4-contract-orbit-light.svg">
    <img src="docs/assets/logo/nex-logo-concept-4-contract-orbit-light.svg" alt="Nex logo" width="160" />
  </picture>
</p>

Nex is a programming language designed to make good software engineering habits the path of least resistance — where contracts, invariants, and explicit behavioral guarantees are not add-ons but the natural way code is written. It compiles to the JVM and to JavaScript, bringing specification-quality discipline to production environments on both platforms.

Nex is also a serious tool for teaching software engineering. Because contracts are woven into the language rather than bolted on, students learn to think about preconditions, postconditions, and invariants not as formalism but as ordinary practice — the way professional engineers think about code before they write it.

---

## What Makes Nex Distinctive

Most languages treat correctness as an afterthought: you write code, then add tests, then hope the documentation stays current. Nex inverts this. The contract — what a routine requires, what it guarantees, what must always remain true — is written in the same place as the code it governs, in the same syntax, and is enforced at runtime.

This has consequences that go beyond style.

**Contracts are executable specifications.** A `require` clause is not a comment that might drift out of date. It is a runtime check that fires at the call boundary. When a precondition is violated, the failure is reported at the point of the violation — at the caller who failed to meet their obligation — rather than at some downstream consequence that is harder to diagnose.

**Invariants enforce class-level consistency.** An `invariant` block on a class asserts conditions that must hold after every operation on that class. The runtime checks these automatically. A class whose invariant can be violated by any operation on it has a structural defect, and Nex makes that defect visible immediately rather than silently allowing inconsistent state to propagate.

**Loop contracts prove termination.** A loop `variant` is an expression that must decrease with every iteration and never fall below zero. Writing one is a commitment that the loop terminates — not as a belief but as a verified property. Combined with loop invariants, this gives loops the same specification discipline as methods.

**Contracts can be stripped for production.** The Java and JavaScript translators support a `skip-contracts` flag that removes all contract checks from the generated output. Development builds run with full checking; production builds run without the overhead. The specification remains in the source as documentation and can be re-enabled for debugging at any time.

**The language is designed for clarity at every level of abstraction.** From high-level system design — where classes represent domain entities and their invariants — down to individual routines, Nex's English-like syntax keeps intent visible. This makes Nex well-suited to contexts where code must be read and reasoned about carefully: education, specification-first development, and code review.

---

## Language at a Glance

### Design by Contract

```nex
class Savings_Account
inherit
  Account
    rename
      deposit as account_deposit
    redefine
      deposit
    end
create
  make(opening_balance: Real, current_interest_rate: Real) do
    balance := opening_balance
    interest_rate := current_interest_rate
  end
feature
  interest_rate: Real

  deposit(amount: Real)
    require
      positive_amount: amount > 0.0
    do
      balance := balance + amount
      update_interest()
    ensure
      balance_increased: balance >= old balance
    end

  update_interest() do
    let interest: Real := balance * interest_rate
    balance := balance + interest
  end

invariant
  valid_balance: balance >= 0.0
  valid_rate: interest_rate >= 0.0 and interest_rate <= 1.0
end
```

The `require` clause states the caller's obligation. The `ensure` clause states the routine's guarantee. The `invariant` block states what must be true of every instance at all times. None of these are comments — they are checked at runtime and reported with their label when violated.

### Loop Contracts

```nex
class Math
  feature
    gcd(a, b: Integer) do
      let x: Integer := 0
      let y: Integer := 0
      from
        x := a
        y := b
      invariant
        x_positive: x > 0
        y_positive: y > 0
      variant
        x + y
      until
        x = y
      do
        if x > y then
          x := x - y
        else
          y := y - x
        end
      end
      print(x)
    end
end
```

The `variant` expression decreases with every iteration and is verified to remain non-negative. The loop invariant is checked at the start of each iteration. Together they constitute a proof of partial correctness and termination.

### Exception Handling

```nex
class HttpClient
  feature
    fetch(url: String) do
      let attempts := 0
      do
        attempts := attempts + 1
        if attempts < 3 then
          raise "connection timeout"
        else
          print("success on attempt " + attempts)
        end
      rescue
        print("attempt " + attempts + " failed: " + exception)
        retry
      end
    end
end
```

`raise` throws any value as an exception. The `rescue` block catches it, binding the value to `exception`. `retry` re-executes the `do` block from the beginning. If `rescue` completes without `retry`, the exception is rethrown. This model applies uniformly to both scoped `do...end` blocks and method bodies.

### Generic Types

```nex
class Box [T]
  create
    make(initial: T) do
      value := initial
    end
  feature
    value: T

    set(new_value: T) do
      value := new_value
    end

    print_value do
      print(value)
    end
end

let int_box: Box [Integer] := create Box.make(42)
int_box.print_value   -- Prints: 42
int_box.set(100)
int_box.print_value   -- Prints: 100

let str_box: Box [String] := create Box.make("hello")
str_box.print_value   -- Prints: hello
```

---

## Code Generation

Nex translates to Java and JavaScript. The generated output is readable and idiomatic in the target language.

### Java Output

```nex
class Account
  feature
    balance: Real

    deposit(amount: Real)
      require
        positive: amount > 0.0
      do
        balance := balance + amount
      ensure
        increased: balance >= 0.0
      end
end
```

```java
public class Account {
    public double balance = 0.0;

    public void deposit(double amount) {
        assert (amount > 0.0) : "Precondition violation: positive";
        balance = (balance + amount);
        assert (balance >= 0.0) : "Postcondition violation: increased";
    }
}
```

### JavaScript Output

```javascript
class Account {
  constructor() {
    this.balance = 0;
  }

  /** @param {number} amount */
  deposit(amount) {
    if (!((amount > 0))) throw new Error("Precondition violation: positive");
    this.balance = (this.balance + amount);
    if (!((this.balance >= 0))) throw new Error("Postcondition violation: increased");
  }
}
```

Contract checks are included in development builds. Pass `{:skip-contracts true}` to the translator for production output with no overhead.

---

## Getting Started

### Prerequisites

- Java 11 or higher
- [Clojure CLI tools](https://clojure.org/guides/install_clojure) (version 1.11+)

### Installation

```bash
git clone https://github.com/vijaymathew/nex.git
cd nex

# Install with automatic dependency resolution
./install.sh jvm --install-deps

# Verify
nex help
```

Supported platforms: Ubuntu, Debian, Fedora, CentOS, RHEL, Arch, Manjaro, macOS.

For manual installation and platform-specific instructions, see [INSTALL.md](INSTALL.md).

### Interactive REPL

```bash
nex
```

```
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

nex> :help      -- available commands
nex> :classes   -- list defined classes
nex> :vars      -- list defined variables
nex> :quit      -- exit
```

### Browser IDE

```bash
npx shadow-cljs compile browser
npx serve public
```

See [docs/md/WEB_IDE.md](docs/md/WEB_IDE.md) for setup and usage.

---

## Usage

### Running the Interpreter

```clojure
(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

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
          x := x - y
        else
          y := y - x
        end
      end
      print(x)
    end
end")

(def ast (p/ast code))
(def ctx (interp/make-context))
(interp/register-class ctx (first (:classes ast)))
```

### Translating to Java

```clojure
(require '[nex.generator.java :as java])

;; Development build — contracts included
(println (java/translate nex-code))

;; Production build — contracts removed
(println (java/translate nex-code {:skip-contracts true}))

;; File translation
(java/translate-file "input.nex" "Output.java")
(java/translate-file "input.nex" "Output.java" {:skip-contracts true})
```

### Translating to JavaScript

```clojure
(require '[nex.generator.javascript :as js])

(println (js/translate nex-code))
(println (js/translate nex-code {:skip-contracts true}))

(js/translate-file "input.nex" "output.js")
(js/translate-file "input.nex" "output.js" {:skip-contracts true})
```

### Running Tests

```bash
clojure -M:test test/scripts/run_tests.clj
```

### Running Examples

```bash
clojure examples/demo_gcd.clj
clojure examples/demo_complete_dbc.clj
clojure examples/demo_complete_inheritance.clj
clojure examples/demo_nex_to_java.clj
clojure examples/demo_nex_to_javascript.clj
```

---

## Language Reference

### Contract Mechanisms

| Construct | Keyword | Scope | Checked |
|---|---|---|---|
| Precondition | `require` | Method entry | Before execution |
| Postcondition | `ensure` | Method exit | After execution |
| Class invariant | `invariant` | Class | After every operation |
| Loop invariant | `invariant` | Loop body | Each iteration |
| Loop variant | `variant` | Loop | Each iteration, must decrease |

### Type System

- **Primitive types:** `Integer`, `Real`, `String`, `Boolean`
- **Generic types:** Parameterized classes with optional constraints — `List [G]`, `Map [K -> Hashable, V]`
- **Nil-safety:** Types are non-nullable by default. Detachable references use `?T` and require nil-guards before feature access.
- **Uniform access:** Fields and parameterless methods share the same call syntax — `obj.field` and `obj.method` are indistinguishable to the caller.

### Object-Oriented Features

- Single and multiple inheritance with `inherit`
- Method renaming with `rename` to resolve conflicts
- Explicit method redefinition with `redefine`
- Deferred classes and features for abstract design
- Explicit object construction with `create`

### Control Flow

- Conditionals: `if ... then ... elseif ... then ... else ... end`
- Loops: `from ... invariant ... variant ... until ... do ... end`
- Scoped blocks: `do ... end` with lexical scoping and variable shadowing
- Exceptions: `raise`, `rescue`, `retry`

---

## Project Structure

```
nex/
├── src/nex/
│   ├── parser.clj              # ANTLR parser integration
│   ├── walker.cljc             # AST transformation
│   ├── interpreter.cljc        # Runtime interpreter
│   ├── typechecker.cljc        # Static type checker
│   ├── debugger.clj            # REPL debugger
│   └── generator/
│       ├── java.clj            # Java code generator
│       └── javascript.clj      # JavaScript (ES6+) code generator
├── test/
│   ├── nex/                    # Test suites by feature
│   │   ├── loops_test.clj
│   │   ├── if_conditions_test.clj
│   │   ├── inheritance_test.clj
│   │   └── generator/
│   │       ├── java_test.clj
│   │       └── javascript_test.clj
│   └── scripts/                # Test runners
├── examples/                   # Annotated example programs
├── docs/
│   ├── md/                     # Language documentation
│   └── book/                   # Accompanying textbook sources
├── editor/
│   └── emacs/
│       └── nex-mode.el         # Emacs major mode
├── grammar/
│   └── nexlang.g4              # ANTLR grammar definition
├── deps.edn
└── README.md
```

---

## Documentation

| Document | Description |
|---|---|
| [Syntax on a Postcard](docs/md/SYNTAX.md) | Core language syntax and quick reference |
| [Tutorial](docs/md/TUTORIAL.md) | Step-by-step introduction used by the Web IDE |
| [Web IDE Guide](docs/md/WEB_IDE.md) | Browser-based IDE setup and usage |
| [CLI Guide](docs/md/CLI.md) | Command-line options and workflows |
| [Debugger Guide](docs/md/DEBUGGER.md) | REPL debugger commands |
| [Arrays and Maps](docs/md/ARRAYS_MAPS.md) | Collection operations and examples |
| [Emacs Support](docs/md/EMACS.md) | Emacs mode setup and key bindings |
| [Development Notes](docs/md/DEVELOPMENT.md) | Architecture and contribution notes |

---

## Editor Support

### Emacs

A full major mode is included with syntax highlighting, automatic indentation, REPL integration, and Imenu navigation support.

```elisp
;; Add to ~/.emacs.d/init.el
(load-file "/path/to/nex/editor/emacs/nex-mode.el")
```

Key bindings:

| Key | Action |
|---|---|
| `C-c C-z` | Start Nex REPL |
| `C-c C-c` | Evaluate buffer in REPL |
| `C-c C-r` | Evaluate region in REPL |

See [docs/md/EMACS.md](docs/md/EMACS.md) for full configuration options.

Support for VS Code, Vim, and other editors is planned. Contributions welcome.

---

## Roadmap

| Status | Item |
|---|---|
| Done | Java code generator |
| Done | JavaScript (ES6+) code generator |
| Done | Browser Web IDE |
| Planned | TypeScript code generator |
| Planned | Python code generator |
| Planned | LSP server for IDE integration |
| Planned | Standard library |
| Planned | Package manager |
| Planned | Compile-time contract checking |
| Planned | Proof obligation generation |

---

## Contributing

Contributions are welcome. Please read the [Contributing Guidelines](CONTRIBUTING.md) before submitting a pull request. The [Development Notes](docs/md/DEVELOPMENT.md) describe the interpreter architecture and code generation pipeline.

---

## License

This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed with this file, you can obtain one at [https://mozilla.org/MPL/2.0/](https://mozilla.org/MPL/2.0/).

---

## Acknowledgments

Nex draws on Bertrand Meyer's Design by Contract philosophy as realized in the Eiffel programming language, and on the tradition of specification-first software engineering that runs from Hoare logic through to modern dependent type systems. The goal is to make that tradition accessible — not as academic formalism, but as the ordinary practice of writing code that says what it means.

---

*Built with Clojure and ANTLR.*
