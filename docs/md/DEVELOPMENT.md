# Development Guide

This guide covers setting up a development environment for the Nex language implementation.

## Prerequisites

### Required
- **Java 17+** - For Clojure, ANTLR, and the JVM bytecode backend
- **Clojure CLI** - [Installation guide](https://clojure.org/guides/install_clojure)
- **Node.js 16+** - For ClojureScript builds
- **npm** - Comes with Node.js

### Optional
- **ANTLR4** - Only needed for generating JavaScript parsers (advanced use)
- **Emacs** - For the Nex major mode

## Initial Setup

1. **Clone the repository:**
   ```bash
   git clone <repository-url>
   cd nex
   ```

2. **Install Node.js dependencies:**
   ```bash
   npm install
   ```

3. **Build ClojureScript (optional):**
   ```bash
   npx shadow-cljs compile node
   ```

## Project Structure

```
nex/
├── src/
│   └── nex/
│       ├── interpreter.cljc     # Runtime interpreter (JVM & JS)
│       ├── parser.clj           # Parser entry point
│       ├── walker.cljc          # AST transformer
│       ├── typechecker.cljc     # Static checking
│       ├── lower.cljc           # Lowering to compiler IR/class specs
│       ├── ir.cljc              # Compiler IR
│       ├── repl.clj             # User-facing REPL
│       ├── fmt.clj              # Code formatter
│       ├── docgen.clj           # Documentation generator
│       ├── compiler/jvm/        # JVM bytecode compiler backend
│       │   ├── emit.clj         # ASM bytecode emission
│       │   ├── repl.clj         # Compiled REPL/session backend
│       │   ├── file.clj         # .nex -> .class/.jar pipeline
│       │   └── runtime.clj      # Runtime helpers for compiled code
│       └── generator/
│           └── javascript.clj   # JavaScript code generator
├── grammar/
│   └── nexlang.g4              # ANTLR grammar
├── test/
│   ├── js/                     # JavaScript/ClojureScript tests
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

**ClojureScript Tests:**
```bash
npm test                 # Run all JS tests
npm run test:cljs        # ClojureScript build test
npm run test:wrapper     # JavaScript wrapper test
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

**ClojureScript REPL:**
```bash
npx shadow-cljs cljs-repl node
```

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

## Working with ClojureScript

### Build Targets

**Development build (fast, unoptimized):**
```bash
npx shadow-cljs compile node
```

**Production build (optimized):**
```bash
npx shadow-cljs release node
```

**Watch mode (auto-recompile):**
```bash
npx shadow-cljs watch node
```

## CI and Branch Protection

GitHub Actions runs the repository CI workflow on every pull request. The
workflow currently does the following:

- runs the full JVM test suite with `clojure -M:test test/scripts/run_tests.clj`
- compiles the Node target with `npx shadow-cljs compile node`
- runs the JavaScript tests with `npm test`

The intended repository policy is to protect the default branch and require the
`Test and Build` status check before merge. If branch protection is not yet
configured on GitHub, enable it for the default branch and mark `Test and Build`
as a required check.

## Parser Development

The parser uses ANTLR4 for the JVM version. The grammar is in `grammar/nexlang.g4`.

### Modifying the Grammar

1. Edit `grammar/nexlang.g4`
2. The JVM parser will be regenerated automatically on next build
3. Update `src/nex/walker.cljc` if you added new AST nodes
4. Run tests to verify

### Generating JavaScript Parser (Advanced)

If you need a JavaScript parser:

1. **Download ANTLR4:**
   ```bash
   ./scripts/setup-antlr.sh
   ```

2. **Generate JavaScript parser:**
   ```bash
   java -jar antlr-4.13.1-complete.jar \
     -Dlanguage=JavaScript \
     grammar/nexlang.g4 \
     -o target/parser_js
   ```

3. **Note:** The generated ES6 modules require additional integration work.
   See `docs/md/CLOJURESCRIPT.md` for details.

## Git Workflow

### What Not to Commit

The following are automatically ignored (`.gitignore`):
- `target/` - Build outputs
- `.shadow-cljs/` - ClojureScript build cache
- `.cpcache/` - Clojure dependency cache
- `node_modules/` - npm dependencies
- `antlr-*.jar` - ANTLR JAR file (downloadable)
- Generated parser files in `src/nex/parser_js/`

### What to Commit

- Source code (`src/`, `grammar/`)
- Tests (`test/`)
- Documentation (`docs/`, `README.md`)
- Configuration (`deps.edn`, `shadow-cljs.edn`, `package.json`)
- Examples (`examples/`)
- Build scripts (`scripts/`)

## Common Tasks

### Add a New Language Feature

1. Update `grammar/nexlang.g4`
2. Add walker transformation in `src/nex/walker.cljc`
3. Implement or update static checking in `src/nex/typechecker.cljc`
4. Implement interpreter behavior in `src/nex/interpreter.cljc`
5. Add lowering support in `src/nex/lower.cljc` and `src/nex/ir.cljc`
6. Add JVM backend support under `src/nex/compiler/jvm/` if the feature should stay on the compiled path
7. Add JavaScript generator support in `src/nex/generator/javascript.clj` if relevant
8. Write tests in `test/nex/` and `test/nex/compiler/jvm/` as appropriate
9. Update documentation

### Add a New Builtin Function

1. Add to `builtin-functions` map in `src/nex/interpreter.cljc`
2. Add or update builtin typing in `src/nex/typechecker.cljc`
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
- `org.clojure/clojurescript` - ClojureScript compiler
- `clj-antlr` - ANTLR4 parser generator
- `clojure.java-time` - Date/time library
- `org.ow2.asm/asm` - JVM bytecode emission
- `org.jline/jline` - REPL line editing

### JavaScript Dependencies (managed by `package.json`)
- `antlr4` - ANTLR4 JavaScript runtime (optional)

## Troubleshooting

### "Cannot find ANTLR"
ANTLR is not committed to the repository. If you need it for generating parsers:
```bash
./scripts/setup-antlr.sh
```

### ClojureScript Build Fails
1. Clear build cache: `rm -rf .shadow-cljs target`
2. Reinstall npm packages: `rm -rf node_modules && npm install`
3. Rebuild: `npx shadow-cljs compile node`

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
- [Shadow-cljs User Guide](https://shadow-cljs.github.io/docs/UsersGuide.html)
- [ANTLR4 Documentation](https://github.com/antlr/antlr4/blob/master/doc/index.md)
- [Nex Language Reference](REFERENCE.md)
