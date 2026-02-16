# Nex Language Examples

This directory contains example programs demonstrating various features of the Nex programming language.

## Examples

### Basic Features

- **demo_let.clj** - Local variable declarations using `let`
- **demo_contracts.clj** - Design by Contract with preconditions and postconditions

### Complete Features

- **demo_complete_dbc.clj** - Complete Design by Contract demonstration
  - Preconditions (`require`)
  - Postconditions (`ensure`)
  - Class invariants (`invariant`)
  - Date class example

- **demo_complete_inheritance.clj** - Complete inheritance example
  - Single and multiple inheritance
  - Rename and redefine clauses
  - Method overriding

### Specific Features

- **demo_gcd.clj** - GCD algorithm with loop contracts
  - Loop invariants
  - Loop variants
  - Termination conditions

- **demo_inheritance.clj** - Inheritance examples
  - Account/SavingsAccount hierarchy
  - Multiple inheritance (Car with Engine and GPS)

- **demo_param_syntax.clj** - Grouped parameter syntax
  - Traditional syntax: `method(a: Integer, b: Integer)`
  - Grouped syntax: `method(a, b: Integer)`

### Code Generation

- **demo_nex_to_java.clj** - Nex to Java translator demonstration
  - Shows translation of various Nex features to Java code
  - Multiple examples with output

## Running Examples

To run any example:

```bash
clojure examples/<example-file>.clj
```

For example:
```bash
clojure examples/demo_gcd.clj
clojure examples/demo_nex_to_java.clj
```

## Features Demonstrated

- ✓ Classes with fields and methods
- ✓ Constructors
- ✓ Single and multiple inheritance
- ✓ Method renaming and redefinition
- ✓ Design by Contract (preconditions, postconditions, invariants)
- ✓ Local variables (`let`)
- ✓ Control flow (if-then-else, loops, scoped blocks)
- ✓ Grouped parameter syntax
- ✓ Code generation (Nex to Java)
