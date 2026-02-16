# Local Variable Declarations (`let`)

## Overview

The `let` statement allows you to declare local variables anywhere within a method body. This provides explicit variable declaration, making code more readable and maintainable.

## Syntax

```nex
let <variable_name> := <expression>
```

Note: The `let` statement uses `:=` (assignment operator) for consistency with the regular assignment syntax.

## Examples

### Basic Usage

```nex
class Example
  feature
    demo() do
      let x := 42
      print(x)        -- Output: 42
    end
end
```

### Multiple Variables

```nex
class Example
  feature
    demo() do
      let width := 10
      let height := 20
      let area := width * height
      print(area)     -- Output: 200
    end
end
```

### Complex Expressions

```nex
class Example
  feature
    demo() do
      let a := 5 + 3
      let b := a * 2
      let c := b - 4
      print(c)        -- Output: 12
    end
end
```

### Boolean and Logical Operations

```nex
class Example
  feature
    demo() do
      let x := 5
      let y := 10
      let greater := x > y
      let equal := x = y
      let result := greater or equal
      print(result)   -- Output: false
    end
end
```

### Interleaved with Other Statements

Local variables can be declared anywhere in the method body:

```nex
class Example
  feature
    demo() do
      print(1)
      let x := 2
      print(x)
      let y := x + 1
      print(y)
    end
end
```

## Difference from Assignment (`:=`)

### `let` - Local Variable Declaration
- **Purpose**: Explicitly declare a new local variable
- **Syntax**: `let x := value`
- **Scope**: Creates a new binding in the current scope
- **Can shadow**: Can shadow outer scope variables

### `:=` (without `let`) - Assignment
- **Purpose**: Assign a value to an existing variable
- **Syntax**: `x := value`
- **Scope**: Searches up the scope chain for the variable
- **Falls back**: If variable doesn't exist, creates it (auto-definition)

### Example Comparison

```nex
class Example
  feature
    demo() do
      let x := 10      -- Declare new variable x
      print(x)         -- Output: 10

      x := 20          -- Assign to existing variable x
      print(x)         -- Output: 20

      let y := 30      -- Declare new variable y
      y := 40          -- Assign to existing variable y
      print(y)         -- Output: 40
    end
end
```

## Semantic Difference

While both use `:=`, the presence of `let` keyword changes the meaning:

```nex
let x := 42    -- Declaration: "Create a new variable x with value 42"
x := 42        -- Assignment: "Set existing variable x to 42 (or create if needed)"
```

The `let` keyword makes the intent explicit and prevents accidental shadowing or typos from creating unintended global variables.

## Implementation Details

### Grammar Changes

Added to `nexlang.g4`:

```antlr
statement
    : assignment
    | methodCall
    | localVarDecl
    ;

localVarDecl
    : LET IDENTIFIER ASSIGN expression
    ;

LET : 'let';
```

### AST Node

The walker transforms `let` statements into AST nodes:

```clojure
{:type :let
 :name "variable_name"
 :value <expression-ast>}
```

### Interpreter Behavior

The interpreter evaluates `let` statements by:
1. Evaluating the expression on the right side
2. Creating a new binding in the current environment
3. Returning the value (for consistency)

```clojure
(defmethod eval-node :let
  [ctx {:keys [name value]}]
  (let [val (eval-node ctx value)]
    (env-define (:current-env ctx) name val)
    val))
```

## Scoping Rules

- Local variables are scoped to the method they're declared in
- Variables declared with `let` are visible to all subsequent statements in the same method
- Variables can reference previously declared variables in their initialization expressions
- Each method execution gets its own environment

## Benefits

1. **Explicit Declaration**: Makes it clear when a new variable is being introduced
2. **Better Readability**: Code is more self-documenting
3. **Prevents Accidental Globals**: Enforces local scope
4. **Supports Complex Calculations**: Variables can build on each other
5. **Consistent Syntax**: Uses `:=` like regular assignment for familiarity

## Limitations

Currently, `let` variables:
- Cannot be declared outside of method bodies
- Do not support destructuring
- Do not support pattern matching
- Cannot be declared at class level (use fields for that)

## Testing

Run the demonstration:

```bash
clojure -M test_let_updated.clj
```

## Bug Fixes

During implementation, several issues were discovered and fixed:

1. **Identifier Transformation**: Identifiers in expressions were not being converted to proper AST nodes
   - Fixed: Updated walker to recognize identifiers and wrap them in `{:type :identifier :name "..."}`

2. **Binary Operator Handler**: The `or`/`and` operators were including the keyword in the operand list
   - Fixed: Filter out string keywords when using fixed operators

3. **Environment Lookup with Falsy Values**: Variables with `false` values couldn't be looked up
   - Fixed: Changed from `if-let` with `get` to using `contains?` check

## Future Enhancements

Potential improvements:
- Block-level scoping with nested `let` blocks
- Destructuring: `let [x, y] := point`
- Type annotations: `let x: Integer := 42`
- Const declarations: `const PI := 3.14159`
- Pattern matching in let: `let Point(x, y) := p`
