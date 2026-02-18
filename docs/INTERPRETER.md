# Nex Interpreter

A tree-walking interpreter for the Nex language, built using a data-driven approach similar to the walker.

## Architecture

The interpreter consists of three main components:

### 1. Environment Management (`Environment` record)
- **Lexical scoping**: Variables are resolved through parent scope chains
- **Mutable bindings**: Uses Clojure atoms for variable storage
- **Operations**:
  - `env-lookup`: Search for variables up the scope chain
  - `env-define`: Define a new variable in the current scope
  - `env-set!`: Update an existing variable

### 2. Runtime Context (`Context` record)
- **Class registry**: Stores all class definitions
- **Global environment**: Root scope for global variables
- **Current environment**: Points to the active execution scope
- **Output accumulator**: Captures print statement output

### 3. Node Evaluation (`eval-node` multimethod)
Dispatches on AST node type (`:type` key) to evaluate different language constructs.

## Supported Features

### Literals
- **Integers**: `42`, `-10`
- **Reals**: `3.14`, `-2.5e10`
- **Booleans**: `true`, `false`
- **Characters**: `#A`, `#65`
- **Strings**: `"hello world"`

### Operators

#### Binary Operators
- **Arithmetic**: `+`, `-`, `*`, `/`
- **Comparison**: `=`, `/=`, `<`, `<=`, `>`, `>=`
- **Logical**: `and`, `or`

All operators respect proper precedence and associativity as defined in the grammar.

#### Unary Operators
- **Negation**: `-expr`

### Expressions
- Arithmetic expressions with proper precedence
- Parenthesized expressions
- Nested expressions
- Variable references

### Statements
- **Assignment**: `x := 42` (auto-defines if variable doesn't exist)
- **Method calls**: `print(1, 2, 3)` or `obj.method(args)`

### Classes
Classes can be defined with:
- **Fields**: Typed attributes (`x: Integer`)
- **Constructors**: Named initialization methods (under the `create` section)
- **Methods**: Instance methods that can access fields

Example:
```nex
class Point
  feature
    x: Integer
    y: Integer
  create
    make(px: Integer, py: Integer) do
      x := px
      y := py
    end
  feature
    distance() do
      print(x, y)
    end
end
```

### Built-in Functions
- **`print(...)`**: Print values separated by spaces
- **`println(...)`**: Print values with newline (currently same as print)

## Design Decisions

### 1. Data-Driven Approach
Like the walker, the interpreter uses multimethods for extensibility:
```clojure
(defmulti eval-node
  (fn [ctx node]
    (cond
      (map? node) (:type node)
      :else :literal)))
```

### 2. Explicit Context Threading
The context is passed explicitly through all evaluation calls rather than using dynamic binding. This makes data flow explicit and enables easier testing.

### 3. Immutable Data Structures
Everything except variable bindings and the class registry uses immutable data structures. Mutations are isolated to atoms within environments.

### 4. Auto-definition of Variables
Assignment automatically defines variables if they don't exist. This simplifies the language for beginners while still supporting proper scoping.

### 5. Output Accumulation
Rather than printing directly to stdout, output is accumulated in the context. This enables:
- Testing without I/O side effects
- Capturing output for display or further processing
- Running multiple interpretations without interference

## API

### Main Functions

**`interpret [ast]`**
- Interprets an AST and returns the context
- Use this when you need to inspect the final state

**`interpret-and-get-output [ast]`**
- Interprets an AST and returns output as a vector of strings
- Use this for testing or batch processing

**`run [ast]`**
- Interprets an AST and prints output to stdout
- Convenience function for REPL use

### Example Usage

```clojure
(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

;; Simple evaluation
(-> "print(3 + 4)" p/ast interp/run)
;; Output: 7

;; Get output programmatically
(-> "print(1, 2, 3)" p/ast interp/interpret-and-get-output)
;; => ["1 2 3"]

;; Inspect final context
(let [ctx (-> "class Foo end" p/ast interp/interpret)]
  (keys @(:classes ctx)))
;; => ("Foo")
```

## Current Limitations

1. **No object instantiation**: Classes can be defined but not instantiated yet
2. **No constructors execution**: Constructor bodies are parsed but not callable
3. **No method dispatch**: Object methods are parsed but not callable yet
4. **No arrays/maps**: Literal syntax exists in grammar but not implemented
5. **No control flow**: No if/while/for statements yet
6. **Integer division**: Uses Clojure's ratio type, may need adjustment

## Future Enhancements

### Short-term
- [ ] Object instantiation (`make Point(3, 5)`)
- [ ] Method calls on objects (`point.distance()`)
- [ ] Array and map literals
- [ ] Control flow (if, while, for)

### Medium-term
- [ ] Type checking
- [ ] Error reporting with line numbers
- [ ] Standard library functions
- [ ] Module system

### Long-term
- [ ] Garbage collection for objects
- [ ] JIT compilation
- [ ] Foreign function interface
- [ ] Debugger support

## Testing

Run the test suite:
```bash
clojure -M test_interpreter.clj
```

Run the comprehensive demo:
```bash
clojure -M demo.clj
```

## Implementation Notes

### Operator Precedence
Precedence is handled by the parser/grammar, so the interpreter just evaluates the tree structure. This keeps the interpreter simple.

### Error Handling
The interpreter throws `ex-info` exceptions with context maps for debugging. All errors include relevant information about what went wrong.

### Performance
As a tree-walking interpreter, performance is not the primary goal. Future optimizations could include:
- Bytecode compilation
- Constant folding
- Tail call optimization
- Inline caching for method lookups
