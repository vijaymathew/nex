# Nex Language Tests

This directory contains the test suite for the Nex programming language interpreter and code generator.

## Test Structure

Tests are organized following standard Clojure conventions:
- Test namespaces mirror source namespaces with a `-test` suffix
- Test files use underscores in place of dashes (e.g., `loops_test.clj` for `nex.loops-test`)

## Test Suites

### Core Language Features

- **loops_test.clj** (`nex.loops-test`)
  - Basic loops (from...until...do...end)
  - Loop invariants and variants
  - GCD algorithm
  - Nested loops
  - Loop with accumulator
  - **7 tests, 7 assertions**

- **if_conditions_test.clj** (`nex.if-conditions-test`)
  - If-then-else statements
  - Boolean conditions
  - Complex logical conditions
  - Nested if statements
  - **8 tests, 8 assertions**

- **scoped_blocks_test.clj** (`nex.scoped-blocks-test`)
  - Scoped blocks (do...end)
  - Variable shadowing
  - Multiple nesting levels
  - Scope isolation
  - **7 tests, 7 assertions**

### Language Features

- **param_syntax_test.clj** (`nex.param-syntax-test`)
  - Traditional parameter syntax
  - Grouped parameter syntax
  - Mixed parameter types
  - **5 tests, 30 assertions**

- **inheritance_test.clj** (`nex.inheritance-test`)
  - Simple inheritance parsing
  - Rename clause
  - Redefine clause
  - Multiple inheritance
  - **5 tests, 21 assertions**

- **inheritance_runtime_test.clj** (`nex.inheritance-runtime-test`)
  - Calling inherited methods
  - Method overriding
  - Multiple inheritance runtime behavior
  - Inheritance chains
  - **5 tests, 9 assertions**

### Built-in IO Types

- **io_test.clj** (`nex.io-test`)
  - Console: create, print, print_line, error, new_line, type detection
  - Process: create, getenv, setenv, command_line
  - Typechecker validation for Console and Process
  - Java and JavaScript code generation for built-in IO types

- **io_lib_test.clj** (`nex.io-lib-test`)
  - `io/Path`: create, probe, read/write, copy, move, recursive delete
  - `io/Directory`: create, child discovery, file/directory filtering
  - `io/Text_File`: open_read, open_write, read_line, write_line, close
  - `io/Binary_File`: open_read, open_write, read, read_all, write, close
  - JVM interpreter coverage for shipped `lib/io` library classes

- **time_lib_test.clj** (`nex.time-lib-test`)
  - `time/Duration`: construction and arithmetic
  - `time/Date_Time`: UTC field access, ISO formatting/parsing, arithmetic and comparison
  - JVM interpreter coverage for shipped `lib/time` library classes

- **regex_lib_test.clj** (`nex.regex-lib-test`)
  - `text/Regex`: compile, matching, search, replacement, splitting
  - JVM interpreter coverage for shipped `lib/text` library classes

## Running Tests

### Run All Tests

```bash
clojure -M:test run_tests.clj
```

### Run Integration Tests

Slow product-surface checks that shell out to external processes live in the
integration runner:

```bash
clojure -M:test test/scripts/run_integration_tests.clj
```

Add future slow namespaces directly to
`test/scripts/run_integration_tests.clj`.
The integration runner currently includes:
- slow CLI product-surface checks
- compiled REPL micro-workload performance checks
- compiled REPL long-session soak performance checks

### Run Compiled REPL Performance Harness

Use the performance harness to compare interpreter and compiled REPL latency on
representative interactive workloads:

```bash
clojure -M:test test/scripts/run_compiled_repl_perf.clj --iterations 25 --warmup 5
```

The harness exits non-zero if the current acceptance thresholds are exceeded.

### Run Compiled REPL Soak Performance Harness

Use the soak-style performance harness to compare interpreter and compiled REPL
latency on longer progressive sessions:

```bash
clojure -M:test test/scripts/run_compiled_repl_soak_perf.clj --iterations 8 --warmup 2
```

This harness covers longer sessions involving `:load`, `intern`, `import`,
closures, concurrency, and deopt/reopt boundaries. It exits non-zero if the
current acceptance thresholds are exceeded.

### Run Docs Example Checks

Interpreter-backed docs checks:

```bash
clojure -M:test test/scripts/check_tutorial_examples.clj
clojure -M:test test/scripts/check_book_examples.clj
```

Compiled-REPL-backed docs checks:

```bash
clojure -M:test test/scripts/check_compiled_tutorial_examples.clj
clojure -M:test test/scripts/check_compiled_book_examples.clj
```

### Run Specific Test Namespace

```bash
clojure -M:test -e "(require 'nex.loops-test) (clojure.test/run-tests 'nex.loops-test)"
```

### Run IO Tests

```bash
clojure -M:test -e "(require 'nex.io-test) (clojure.test/run-tests 'nex.io-test)"
```

### Run Tests in REPL

```clojure
(require '[clojure.test :as test])
(require 'nex.loops-test)
(test/run-tests 'nex.loops-test)
```

## Test Statistics

**Total: 234 tests with 561 assertions**
- All tests passing (4 pre-existing fmt_test failures unrelated to core features)

## Test Coverage

The test suite covers:
- ✓ Loop constructs with contracts
- ✓ Conditional statements
- ✓ Scoped blocks and variable shadowing
- ✓ Parameter syntax variations
- ✓ Inheritance (single and multiple)
- ✓ Method overriding and renaming
- ✓ Code generation to Java
- ✓ Type mapping
- ✓ Expression translation
- ✓ Statement translation
- ✓ Built-in Console IO (print, read, error)
- ✓ `lib/io` filesystem, text, and binary I/O
- ✓ Built-in Process (getenv, setenv, command_line)

## Adding New Tests

1. Create test file in appropriate directory (e.g., `test/nex/feature_test.clj`)
2. Use namespace `nex.feature-test`
3. Use `clojure.test` framework (`deftest`, `is`, `testing`)
4. Add to `run_tests.clj` to include in test suite
5. Follow existing test patterns for consistency
