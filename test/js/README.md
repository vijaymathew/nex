# JavaScript/ClojureScript Tests

This directory contains tests for the ClojureScript build of the Nex interpreter.

## Test Files

### test_cljs.js
Main test suite for the ClojureScript build. Tests:
- Context creation (`makeContext`)
- Class registration (`registerClass`)
- AST evaluation (`evalNode`)

Run with (from project root):
```bash
node test/js/test_cljs.js
```

### test_wrapper.js
Tests the JavaScript wrapper (`nex-wrapper.js`) which provides a more JavaScript-friendly API.

Run with (from project root):
```bash
node test/js/test_wrapper.js
```

### test_cljs2.js
Debug test for exploring the ClojureScript exports and multimethod calling conventions.

Run with (from project root):
```bash
node test/js/test_cljs2.js
```

## Prerequisites

1. Build the ClojureScript target:
   ```bash
   npx shadow-cljs compile node
   ```

2. Install Node.js dependencies:
   ```bash
   npm install
   ```

## Notes

- These tests use pre-parsed AST structures (no parsing required)
- The ClojureScript build does not include the parser (ANTLR4 is JVM-only)
- For full documentation, see `docs/CLOJURESCRIPT.md`
