# Nex Quick Start Guide

Get up and running with Nex in 5 minutes!

## Installation

```bash
# Clone the repository
git clone https://github.com/yourusername/nex.git
cd nex

# Verify Clojure is installed
clojure --version
# Should show Clojure CLI version 1.11.0 or higher
```

## Your First Nex Program

### Option A: Using the Interactive REPL (Easiest!)

The fastest way to try Nex is with the interactive REPL:

```bash
./nex-repl
# or
clojure -M:repl
```

Try these commands:

```nex
nex> print("Hello, Nex!")
"Hello, Nex!"

nex> let x := 10
=> 10

nex> let y := x + 20
=> 30

nex> print(y)
30

nex> if y > 15 then print("big") else print("small") end
"big"

nex> :help
[Shows help and commands]

nex> :quit
Goodbye!
```

The REPL is perfect for:
- Learning Nex syntax interactively
- Testing code snippets quickly
- Prototyping classes and methods
- Exploring language features

See [examples/demo_repl.md](examples/demo_repl.md) for more examples.

### Option B: Create a Nex File

### 1. Create a Simple Class

Create a file called `hello.nex`:

```nex
class Greeter
  feature
    name: String

  feature
    greet() do
      print("Hello, World!")
    end

    greet_person(person: String) do
      print("Hello, ", person, "!")
    end
end
```

### 2. Run the Interpreter

```bash
clojure -M
```

In the REPL:

```clojure
(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

;; Load your Nex code
(def code (slurp "hello.nex"))

;; Parse and execute
(def ast (p/ast code))
(def ctx (interp/make-context))
(interp/register-class ctx (first (:classes ast)))

;; Create an object and call methods
(def greeter (interp/make-object "Greeter" {}))
(def env (interp/make-env (:globals ctx)))
(interp/env-define env "greeter" greeter)
(def ctx-with-obj (assoc ctx :current-env env))

;; Call the greet method
(interp/eval-node ctx-with-obj {:type :call
                                 :target "greeter"
                                 :method "greet"
                                 :args []})
;; Output: ["Hello, World!"]
```

### 3. Translate to Java

```clojure
(require '[nex.generator.java :as java])

;; Translate your Nex code to Java
(println (java/translate code))
```

**Output:**
```java
public class Greeter {
    private String name;

    public void greet() {
        print("Hello, World!");
    }
    public void greet_person(String person) {
        print("Hello, ", person, "!");
    }
}
```

## Try the Examples

### GCD Algorithm
```bash
clojure examples/demo_gcd.clj
```

See the Euclidean GCD algorithm with loop contracts in action.

### Design by Contract
```bash
clojure examples/demo_complete_dbc.clj
```

Explore preconditions, postconditions, and class invariants.

### Inheritance
```bash
clojure examples/demo_complete_inheritance.clj
```

Learn about single and multiple inheritance with method renaming.

### Java Translation
```bash
clojure examples/demo_nex_to_java.clj
```

See how Nex code translates to Java across various features.

## Run the Tests

```bash
clojure -M:test run_tests.clj
```

All 47 tests should pass!

## Key Language Concepts

### Design by Contract

```nex
class BankAccount
  feature
    balance: Integer

    withdraw(amount: Integer)
      require
        sufficient_funds: balance >= amount
        positive_amount: amount > 0
      do
        let balance := balance - amount
      ensure
        balance_decreased: balance < old_balance
      end
end
```

### Loops with Contracts

```nex
from
  let i := 1
invariant
  positive: i > 0
variant
  10 - i
until
  i > 10
do
  print(i)
  let i := i + 1
end
```

### Multiple Inheritance

```nex
class Duck
inherit
  Flyable
  end,
  Swimmable
  end
feature
  quack() do
    print("Quack!")
  end
end
```

## Editor Support

### Emacs Users

If you use Emacs, load the Nex mode for syntax highlighting and REPL integration:

```elisp
;; Add to ~/.emacs.d/init.el
(load-file "/path/to/nex/nex-mode.el")
```

Then open any `.nex` file:
- Automatic syntax highlighting
- `C-c C-z` to start REPL
- `C-c C-c` to evaluate buffer

See [EMACS.md](EMACS.md) for full documentation.

## Next Steps

1. **Read the [Full README](README.md)** for complete feature overview
2. **Explore [Examples](examples/README.md)** for more complex programs
3. **Check [Project Structure](PROJECT_STRUCTURE.md)** to understand the codebase
4. **Review [Tests](test/README.md)** to see comprehensive feature coverage
5. **Set up [Editor Support](EMACS.md)** for a better development experience

## Need Help?

- Check the examples in the `examples/` directory
- Look at test files in `test/nex/` for usage patterns
- Read the grammar definition in `grammar/nexlang.g4`

## Common Patterns

### Creating and Using Objects

```clojure
;; Parse code
(def ast (p/ast your-nex-code))
(def ctx (interp/make-context))

;; Register class
(interp/register-class ctx (first (:classes ast)))

;; Create object
(def obj (interp/make-object "ClassName" {}))

;; Set up environment
(def env (interp/make-env (:globals ctx)))
(interp/env-define env "myobj" obj)
(def ctx-with-obj (assoc ctx :current-env env))

;; Call method
(interp/eval-node ctx-with-obj {:type :call
                                 :target "myobj"
                                 :method "method_name"
                                 :args []})
```

### Translating to Java

```clojure
;; From string
(java/translate "class MyClass ... end")

;; From file
(java/translate-file "input.nex" "Output.java")

;; Just get the string
(def java-code (java/translate nex-code))
(println java-code)
```

Happy coding with Nex! 🚀
