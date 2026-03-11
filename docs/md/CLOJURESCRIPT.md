# ClojureScript Support

The Nex language implementation supports ClojureScript, allowing Nex programs to run in JavaScript environments (Node.js and browsers).

## Current Status

✅ **Fully Supported:**
- Interpreter (eval-node)
- Context management (make-context)
- Class registration (register-class)
- All runtime features (objects, methods, contracts, invariants)

⚠️ **Partial Support:**
- Parser: The ANTLR4-based parser generates ES6 modules which require special handling in ClojureScript/Node.js environments

## Usage Patterns

### Pattern 1: Server-Side Parsing (Recommended)

Parse Nex code on the JVM and send the AST to the ClojureScript runtime:

**On the Server (Clojure/JVM):**
```clojure
(require '[nex.parser :as parser])
(require '[cheshire.core :as json])

;; Parse Nex code to AST
(def nex-ast (parser/ast nex-source-code))

;; Serialize AST to JSON
(def ast-json (json/generate-string nex-ast))

;; Send ast-json to client
```

**In the Browser/Node (ClojureScript):**
```clojure
(require '[nex.interpreter :as interp])

;; Receive AST from server
(def ast (js->clj (.parse js/JSON ast-json) :keywordize-keys true))

;; Create context and evaluate
(def ctx (interp/make-context))
(interp/eval-node ctx ast)
```

### Pattern 2: Pre-Compiled Classes

For production applications, pre-compile Nex classes and ship them as data:

```clojure
;; Build time: Compile Nex to data structures
(def compiled-classes
  {:Point {:fields [{:name "x" :type "Integer"}
                    {:name "y" :type "Integer"}]
           :constructors [...]
           :methods [...]}})

;; Runtime: Register pre-compiled classes
(require '[nex.interpreter :as interp])

(def ctx (interp/make-context))
(interp/register-class ctx "Point" (get compiled-classes :Point))
```

## Building

### Node.js Library

```bash
npx shadow-cljs compile node
```

Generates `target/nex.js` with exports:
- `makeContext()` - Create a new interpreter context
- `evalNode(ctx, ast)` - Evaluate an AST node
- `registerClass(ctx, name, classdef)` - Register a class definition

### Browser Application

```bash
npx shadow-cljs compile browser
```

Generates browser-compatible JavaScript in `public/js/`.

### Development

```bash
# Watch mode for development
npx shadow-cljs watch node

# REPL
npx shadow-cljs cljs-repl node
```

## Node.js Usage Example

### Using the JavaScript Wrapper (Recommended)

```javascript
const nex = require('nex-lang'); // or require('./nex-wrapper.js')

// Create context
const ctx = nex.makeContext();

// Assuming you have a pre-parsed AST
const ast = {
  type: "program",
  imports: [],
  interns: [],
  classes: [{
    type: "class",
    name: "Point",
    // ... rest of AST
  }],
  calls: []
};

// Evaluate
nex.evalNode(ctx, ast);

// Or evaluate a complete program and get a new context
const ctx2 = nex.evalProgram(ast);
```

### Using the ClojureScript Module Directly

```javascript
const nexCljs = require('./target/nex.js');

// Create context
const ctx = nexCljs.makeContext();

// Evaluate (note: evalNode is a multimethod, use .call())
nexCljs.evalNode.call(null, ctx, ast);
```

## Browser Usage Example

```html
<!DOCTYPE html>
<html>
<head>
  <script src="/js/main.js"></script>
</head>
<body>
  <script>
    // Fetch pre-parsed AST from server
    fetch('/api/nex-ast')
      .then(r => r.json())
      .then(ast => {
        const ctx = nex.core.makeContext();
        nex.core.evalNode(ctx, ast);
        // Use Nex classes in your app
      });
  </script>
</body>
</html>
```

## Parser Support (Advanced)

For applications that need runtime parsing in JavaScript, you can:

1. **Use the ANTLR4 JavaScript Runtime:**
   - The grammar is available in `grammar/nexlang.g4`
   - Download ANTLR4 (if not already present): `./scripts/setup-antlr.sh`
   - Generate JavaScript parser: `java -jar antlr-4.13.1-complete.jar -Dlanguage=JavaScript grammar/nexlang.g4 -o target/parser_js`
   - Bundle the generated parser with your application
   - Create a ClojureScript wrapper around it
   - Note: The generated ES6 modules may require additional bundling/transpilation

2. **Implement a Simple Parser:**
   - For restricted Nex syntax, implement a lightweight parser in ClojureScript
   - Use tools like Instaparse.js or create a hand-written parser

## Platform-Specific Code

Nex supports conditional compilation with the `with` statement:

```nex
with "java" do
  -- JVM-specific code
end

with "javascript" do
  -- JavaScript-specific code
end
```

When compiling to ClojureScript, only `with "javascript"` blocks are included.

## Testing

Run ClojureScript tests:

```bash
./test/scripts/run_browser_smoke_tests.sh
```

## Dependencies

**npm packages:**
- `antlr4` - ANTLR4 JavaScript runtime (if using parser)

**ClojureScript dependencies** (automatically managed):
- `org.clojure/clojurescript`
- `thheller/shadow-cljs`

## Troubleshooting

### "Cannot find module" errors

Make sure you've run `npm install` to install JavaScript dependencies.

### Parser not working

The parser generates ES6 modules which may not work directly with some module systems. Use Pattern 1 (server-side parsing) instead.

### Size concerns

The ClojureScript compiler performs dead code elimination. If you're not using certain features (e.g., contracts, invariants), they won't be included in the final bundle.

To optimize bundle size:
```bash
shadow-cljs release browser --config-merge '{:compiler-options {:optimizations :advanced}}'
```

## Future Enhancements

Planned improvements:
- Pure ClojureScript parser (no ANTLR dependency)
- Streaming AST evaluation
- WebAssembly backend for improved performance
- Service Worker support for offline parsing

## Related Documentation

- [Main README](../README.md)
- [Language Reference](REFERENCE.md)
- [With Statement](WITH.md)
