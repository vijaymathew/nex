# Development Guide

This guide covers setting up a development environment for the Nex language implementation.

## Prerequisites

### Required
- **Java 17+** - For Clojure, ANTLR, and the JVM bytecode backend
- **Clojure CLI** - [Installation guide](https://clojure.org/guides/install_clojure)

### Optional
- **Emacs** - For the Nex major mode

## Initial Setup

1. **Clone the repository:**
   ```bash
   git clone <repository-url>
   cd nex
   ```

That is all that is required — the JVM toolchain resolves dependencies from
`deps.edn` on first use.

## Project Structure

```
nex/
├── src/
│   └── nex/
│       ├── interpreter.clj     # Runtime interpreter
│       ├── parser.clj           # Parser entry point
│       ├── walker.clj          # AST transformer
│       ├── typechecker.clj     # Static checking
│       ├── lower.clj           # Lowering to compiler IR/class specs
│       ├── ir.clj              # Compiler IR
│       ├── repl.clj             # User-facing REPL
│       ├── fmt.clj              # Code formatter
│       ├── docgen.clj           # Documentation generator
│       └── compiler/jvm/        # JVM bytecode compiler backend
│           ├── emit.clj         # ASM bytecode emission
│           ├── repl.clj         # Compiled REPL/session backend
│           ├── file.clj         # .nex -> .class/.jar pipeline
│           └── runtime.clj      # Runtime helpers for compiled code
├── grammar/
│   └── nexlang.g4              # ANTLR grammar
├── test/
│   ├── nex/                    # Clojure JVM tests
│   └── scripts/                # Test runners, docs checks, perf gates
├── docs/                        # Documentation
├── examples/                    # Example Nex programs
└── editor/
    └── emacs/                  # Emacs major mode

```

## Development Workflow

### Running Tests

**JVM Tests:**
```bash
clojure -M:test test/scripts/run_tests.clj
```

**Integration Tests:**
```bash
clojure -M:test test/scripts/run_integration_tests.clj
```

**Single JVM Test:**
```bash
clojure -M:test -e "(require 'nex.loops-test) (clojure.test/run-tests 'nex.loops-test)"
```

### REPL Development

**Start Nex REPL with the launcher:**
```bash
./bin/nex
```

**Start Nex REPL through Clojure directly:**
```bash
clojure -M:repl
```

The REPL now defaults to the compiled JVM backend. Unsupported inputs fall back
to the interpreter automatically. Use `:backend interpreter` inside the REPL if
you need the tree-walking fallback explicitly.

### Code Formatting

Format Nex code:
```bash
clojure -M:fmt examples/stack.nex
```

### Documentation Generation

Generate Markdown docs from Nex source:
```bash
clojure -M:docgen examples/stack.nex docs/Stack.md
```

## CI and Branch Protection

GitHub Actions runs the repository CI workflow on every pull request. The
workflow currently does the following:

- runs the full JVM test suite with `clojure -M:test test/scripts/run_tests.clj`

The intended repository policy is to protect the default branch and require the
`Test and Build` status check before merge. If branch protection is not yet
configured on GitHub, enable it for the default branch and mark `Test and Build`
as a required check.

## Parser Development

The parser uses ANTLR4 for the JVM version. The grammar is in `grammar/nexlang.g4`.

### Modifying the Grammar

1. Edit `grammar/nexlang.g4`
2. The JVM parser will be regenerated automatically on next build
3. Update `src/nex/walker.clj` if you added new AST nodes
4. Run tests to verify

## Git Workflow

### What Not to Commit

The following are automatically ignored (`.gitignore`):
- `target/` - Build outputs
- `.cpcache/` - Clojure dependency cache
- `antlr-*.jar` - ANTLR JAR file (downloadable)

### What to Commit

- Source code (`src/`, `grammar/`)
- Tests (`test/`)
- Documentation (`docs/`, `README.md`)
- Configuration (`deps.edn`)
- Examples (`examples/`)
- Build scripts (`scripts/`)

## Common Tasks

### Add a New Language Feature

1. Update `grammar/nexlang.g4`
2. Add walker transformation in `src/nex/walker.clj`
3. Implement or update static checking in `src/nex/typechecker.clj`
4. Implement interpreter behavior in `src/nex/interpreter.clj`
5. Add lowering support in `src/nex/lower.clj` and `src/nex/ir.clj`
6. Add JVM backend support under `src/nex/compiler/jvm/` if the feature should stay on the compiled path
7. Write tests in `test/nex/` and `test/nex/compiler/jvm/` as appropriate
8. Update documentation

### Add a New Builtin Function

1. Add to `builtin-functions` map in `src/nex/interpreter.clj`
2. Add or update builtin typing in `src/nex/typechecker.clj`
3. Implement the function logic
4. Add specialized lowering/emission in the JVM backend if needed
5. Add tests
6. Document in language reference

### Fix a Bug

1. Write a failing test that reproduces the bug
2. Fix the bug
3. Verify all tests pass
4. Commit with descriptive message

## Dependencies

### JVM Dependencies (managed by `deps.edn`)
- `org.clojure/clojure` - Clojure language
- `clj-antlr` - ANTLR4 parser generator
- `clojure.java-time` - Date/time library
- `org.clojure/data.json` - JSON support
- `org.ow2.asm/asm` - JVM bytecode emission
- `org.jline/jline` - REPL line editing

## Troubleshooting

### "Cannot find ANTLR"
ANTLR is not committed to the repository. If you need it for generating parsers:
```bash
./scripts/setup-antlr.sh
```

### Tests Fail After Grammar Change
The walker, typechecker, interpreter, and compiler lowering may need updates to
handle new AST node types. Check the walker transformations first, then the
compiled eligibility/lowering path if the feature is meant to stay compiled.

## Code Style

- Follow Clojure style conventions
- Use 2-space indentation
- Keep functions focused and small
- Write docstrings for public functions
- Add type hints where performance matters

## Resources

- [Clojure Guides](https://clojure.org/guides)
- [ANTLR4 Documentation](https://github.com/antlr/antlr4/blob/master/doc/index.md)
- [Nex Language Reference](REFERENCE.md)
