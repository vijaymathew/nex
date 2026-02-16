# Nex Project Structure

## Overview

Nex is an Eiffel-based programming language with Design by Contract support, implemented in Clojure with ANTLR for parsing.

## Directory Structure

```
nex/
├── src/                    # Source code
│   └── nex/
│       ├── parser.clj      # ANTLR parser integration
│       ├── walker.clj      # AST transformation
│       ├── interpreter.clj # Runtime interpreter
│       └── generator/      # Code generators
│           └── java.clj    # Nex to Java translator
│
├── test/                   # Test suite
│   ├── README.md          # Test documentation
│   └── nex/
│       ├── loops_test.clj
│       ├── if_conditions_test.clj
│       ├── scoped_blocks_test.clj
│       ├── param_syntax_test.clj
│       ├── inheritance_test.clj
│       ├── inheritance_runtime_test.clj
│       └── generator/
│           └── java_test.clj
│
├── examples/               # Example programs
│   ├── README.md          # Examples documentation
│   ├── demo_gcd.clj       # GCD with loop contracts
│   ├── demo_complete_dbc.clj
│   ├── demo_complete_inheritance.clj
│   ├── demo_inheritance.clj
│   ├── demo_nex_to_java.clj
│   ├── demo_param_syntax.clj
│   ├── demo_let.clj
│   └── demo_contracts.clj
│
├── grammar/                # ANTLR grammar
│   └── nexlang.g4         # Nex language grammar
│
├── deps.edn               # Clojure dependencies
├── run_tests.clj          # Test runner
└── PROJECT_STRUCTURE.md   # This file
```

## Core Components

### Parser (`src/nex/parser.clj`)
- ANTLR integration
- Parses Nex source code into parse trees
- Entry point: `(ast "nex-code")`

### Walker (`src/nex/walker.clj`)
- Transforms ANTLR parse trees to AST
- Data-driven transformation using node handlers
- Handles all language constructs

### Interpreter (`src/nex/interpreter.clj`)
- Runtime execution of Nex programs
- Environment management with lexical scoping
- Contract checking (preconditions, postconditions, invariants)
- Built-in functions
- Method lookup with inheritance

### Java Generator (`src/nex/generator/java.clj`)
- Translates Nex to Java code
- Handles inheritance, contracts, control flow
- Type mapping (Integer→int, String→String, etc.)

## Language Features

### Basic Features
- Classes with fields and methods
- Constructors
- Local variables (`let`)
- Method calls
- Expressions (binary/unary operators)

### Control Flow
- If-then-else statements
- Loops (from...invariant...variant...until...do...end)
- Scoped blocks (do...end)

### Object-Oriented Features
- Single inheritance
- Multiple inheritance (with rename/redefine)
- Method overriding
- Field access

### Design by Contract
- Preconditions (`require`)
- Postconditions (`ensure`)
- Class invariants (`invariant`)
- Loop invariants and variants

### Advanced Features
- Grouped parameter syntax (`method(a, b: Integer)`)
- Method renaming in inheritance
- Method redefinition declarations
- Lexical scoping with variable shadowing

## Running the Project

### Run Tests
```bash
clojure -M:test run_tests.clj
```

### Run Examples
```bash
clojure examples/demo_gcd.clj
clojure examples/demo_nex_to_java.clj
```

### Use in REPL
```clojure
(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])
(require '[nex.generator.java :as java])

;; Parse and interpret
(def ast (p/ast "class MyClass ... end"))
(def ctx (interp/make-context))
(interp/eval-node ctx ast)

;; Translate to Java
(java/translate "class MyClass ... end")
```

## Development

### Adding New Features
1. Update grammar in `grammar/nexlang.g4`
2. Add walker handler in `src/nex/walker.clj`
3. Add interpreter support in `src/nex/interpreter.clj`
4. Add code generator support in `src/nex/generator/java.clj`
5. Write tests in `test/nex/`
6. Add examples in `examples/`

### Testing Conventions
- Test files use `_test.clj` suffix (e.g., `loops_test.clj`)
- Test namespaces use `-test` suffix (e.g., `nex.loops-test`)
- All tests use `clojure.test` framework
- Tests mirror source directory structure

## Documentation

- [Test Documentation](test/README.md)
- [Examples Documentation](examples/README.md)
- Language features documented in test files
- Examples serve as living documentation
