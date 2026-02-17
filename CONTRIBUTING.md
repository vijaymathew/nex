# Contributing to Nex

Thank you for your interest in contributing to Nex! This document provides guidelines and instructions for contributing to the project.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [How Can I Contribute?](#how-can-i-contribute)
- [Development Setup](#development-setup)
- [Coding Standards](#coding-standards)
- [Testing](#testing)
- [Submitting Changes](#submitting-changes)
- [Documentation](#documentation)

## Code of Conduct

By participating in this project, you agree to maintain a respectful and collaborative environment. Be kind, constructive, and professional in all interactions.

## How Can I Contribute?

### Reporting Bugs

Before creating a bug report:
- Check the [existing issues](https://github.com/your-repo/nex/issues) to avoid duplicates
- Verify the bug exists in the latest version

When reporting a bug, include:
- **Clear description** of the issue
- **Steps to reproduce** the behavior
- **Expected vs actual behavior**
- **Code examples** that demonstrate the issue
- **Nex version** (`nex --version`)
- **Environment** (OS, Java version, etc.)

### Suggesting Enhancements

Enhancement suggestions are welcome! Please include:
- **Clear description** of the proposed feature
- **Use cases** and examples
- **Why this enhancement would be useful** to most users
- **Possible implementation approach** (if you have ideas)

### Areas We Need Help

- **Language features**: New syntax, operators, or constructs
- **Standard library**: Built-in classes and methods
- **Editor support**: VS Code, Vim, IntelliJ plugins
- **Code generators**: Improvements to Java/JavaScript output
- **Documentation**: Examples, tutorials, API docs
- **Testing**: More comprehensive test cases
- **Bug fixes**: Check issues labeled `good-first-issue`

## Development Setup

### Prerequisites

- Java 11 or later
- Clojure CLI tools (version 1.10.3 or later)
- Git

### Getting Started

1. **Fork and clone the repository**:
   ```bash
   git clone https://github.com/YOUR-USERNAME/nex.git
   cd nex
   ```

2. **Verify your setup**:
   ```bash
   # Run the test suite
   clojure -M:test test/scripts/run_tests.clj

   # Start the REPL
   clojure -M:repl
   ```

3. **Install locally** (optional):
   ```bash
   ./install.sh
   ```

### Project Structure

```
nex/
├── src/nex/              # Core implementation
│   ├── parser.clj        # Parser (lexer + AST builder)
│   ├── interpreter.clj   # Runtime interpreter
│   ├── typechecker.clj   # Static type checker
│   ├── fmt.clj           # Code formatter
│   └── generator/        # Code generators
│       ├── java.clj      # Java code generator
│       └── javascript.clj # JavaScript code generator
├── test/nex/             # Test suite
├── docs/                 # Documentation
├── editor/               # Editor integrations
│   └── emacs/            # Emacs mode
└── examples/             # Example programs
```

## Coding Standards

### Clojure Code Style

- **Indentation**: 2 spaces, no tabs
- **Line length**: Keep lines under 100 characters when possible
- **Naming**:
  - Use `kebab-case` for functions and variables
  - Use `snake_case` only when matching Nex language conventions
  - Predicates end with `?` (e.g., `is-numeric-type?`)
  - Side-effecting functions use `!` (e.g., `reset-state!`)

- **Documentation**:
  - Add docstrings to public functions
  - Use `;; Comments` for implementation notes
  - Explain *why*, not just *what*

Example:
```clojure
(defn check-binary-op
  "Check the type of a binary operation.
  Returns the result type or throws a type error."
  [env {:keys [operator left right] :as expr}]
  ;; Numeric operators return the left operand's type
  (case operator
    ("+" "-" "*" "/") (check-numeric-operation env left right)
    ...))
```

### Nex Language Code

- **Indentation**: 2 spaces
- **Naming**:
  - Classes: `PascalCase`
  - Methods/variables: `snake_case`
  - Constants: `UPPER_CASE`
- **Contracts**: Include `require` and `ensure` for public methods
- **Comments**: Use `--` for line comments

## Testing

### Running Tests

```bash
# Run all tests
clojure -M:test test/scripts/run_tests.clj

# Run specific test namespace
clojure -M:test -n nex.parser-test

# Run with verbose output
clojure -M:test test/scripts/run_tests.clj --verbose
```

### Writing Tests

Tests use Clojure's `clojure.test` framework:

```clojure
(ns nex.my-feature-test
  (:require [clojure.test :refer [deftest testing is]]
            [nex.parser :as p]
            [nex.generator.java :as java]))

(deftest my-feature-test
  (testing "Description of what you're testing"
    (let [code "class Test
                  feature
                    demo() do
                      print(42)
                    end
                end"
          java-code (java/translate code)]
      (is (str/includes? java-code "System.out.println(42)"))
      (is (str/includes? java-code "public class Test")))))
```

### Test Guidelines

- **One concept per test**: Each test should verify one specific behavior
- **Descriptive names**: Use `feature-name-test` format
- **Clear assertions**: Make it obvious what's being tested
- **Test edge cases**: Empty inputs, nil values, invalid syntax
- **Test contracts**: Verify that pre/post conditions work correctly

## Submitting Changes

### Before You Submit

1. **Run tests**: Ensure all tests pass
   ```bash
   clojure -M:test test/scripts/run_tests.clj
   ```

2. **Format code**: Run the formatter if applicable
   ```bash
   nex format src/
   ```

3. **Update documentation**: If you added/changed features
   - Update relevant docs in `docs/`
   - Add examples if appropriate
   - Update README if needed

4. **Add tests**: New features need test coverage

### Pull Request Process

1. **Create a feature branch**:
   ```bash
   git checkout -b feature/my-feature
   # or
   git checkout -b fix/bug-description
   ```

2. **Make your changes**:
   - Write clear, focused commits
   - Use descriptive commit messages
   - Keep commits atomic (one logical change per commit)

3. **Commit message format**:
   ```
   Brief summary (50 chars or less)

   More detailed explanation if needed. Wrap at 72 characters.
   Explain the problem this commit solves and why this approach
   was chosen.

   - Bullet points are fine
   - Reference issues: Fixes #123
   ```

4. **Push to your fork**:
   ```bash
   git push origin feature/my-feature
   ```

5. **Create a Pull Request**:
   - Provide a clear description of the changes
   - Reference related issues
   - Explain the motivation and context
   - List any breaking changes

### PR Review Process

- Maintainers will review your PR
- Address any feedback or requested changes
- Once approved, your PR will be merged
- Your contribution will be acknowledged in release notes

## Documentation

### Types of Documentation

1. **API Documentation**: Docstrings in source code
2. **Feature Documentation**: Files in `docs/` directory
3. **Examples**: Working code in `examples/` directory
4. **README**: High-level overview and getting started guide

### Documentation Guidelines

- **Be clear and concise**: Avoid jargon when possible
- **Show examples**: Code examples are more helpful than long explanations
- **Keep it current**: Update docs when you change functionality
- **Test examples**: Ensure example code actually works

### Adding New Documentation

When adding a new language feature, create a corresponding doc file:

```bash
# Create doc file
touch docs/MY_FEATURE.md

# Add to docs/README.md index
```

Format:
```markdown
# Feature Name

Brief description of what this feature does.

## Syntax

\`\`\`nex
-- Example syntax
\`\`\`

## Examples

### Basic Example
\`\`\`nex
-- Working example code
\`\`\`

### Advanced Example
\`\`\`nex
-- More complex example
\`\`\`

## See Also
- Related feature 1
- Related feature 2
```

## Questions?

If you have questions about contributing:
- Open an [issue](https://github.com/your-repo/nex/issues) with the `question` label
- Check existing [discussions](https://github.com/your-repo/nex/discussions)

Thank you for contributing to Nex! 🎉
