# Let Statement Syntax

Nex supports both typed and untyped `let` statements for local variable declarations.

## Syntax

### Untyped Let (Original Syntax)

```nex
let variable_name := expression
```

**Examples:**
```nex
let x := 10
let name := "Alice"
let result := x + y
let active := true
```

### Typed Let (New Syntax)

```nex
let variable_name: Type := expression
```

**Examples:**
```nex
let x: Integer := 10
let name: String := "Alice"
let result: Integer := x + y
let active: Boolean := true
let rate: Real := 3.14
```

## Supported Types

- `Integer` - Whole numbers
- `String` - Text values
- `Boolean` - true or false
- `Real` - Floating-point numbers

## Benefits of Typed Let

### 1. Better Code Generation

When translating to Java, typed let produces proper variable declarations:

**Untyped:**
```nex
let x := 10
```
↓ Translates to:
```java
x = 10;  // Assignment
```

**Typed:**
```nex
let x: Integer := 10
```
↓ Translates to:
```java
int x = 10;  // Declaration
```

### 2. Self-Documenting Code

Explicit types make code easier to understand:

```nex
-- Less clear
let count := 0
let rate := 0.05
let name := getValue()

-- More clear
let count: Integer := 0
let rate: Real := 0.05
let name: String := getValue()
```

### 3. Better IDE Support

Type annotations enable:
- Better syntax highlighting in editors
- More accurate code completion
- Earlier error detection (in future versions)

## Backward Compatibility

Both syntaxes are fully supported and can be mixed:

```nex
class Calculator
  feature
    calculate() do
      -- Typed declarations
      let x: Integer := 10
      let y: Integer := 20

      -- Untyped declarations
      let temp := x + y

      -- Mixed usage works perfectly!
      let result: Integer := temp * 2
      print(result)
    end
end
```

## Use Cases

### Use Typed Let When:

✓ Generating code to other typed languages (Java, TypeScript, etc.)
✓ Writing library code that others will use
✓ Documenting expected types for complex logic
✓ Working in a team where type clarity is important

### Use Untyped Let When:

✓ Prototyping or experimenting
✓ Type is obvious from context
✓ Writing quick scripts or demos
✓ Type inference would be sufficient

## Complete Example

```nex
class BankAccount
  feature
    balance: Integer

    process_transaction(amount: Integer, is_deposit: Boolean) do
      -- Typed let for clarity
      let old_balance: Integer := balance
      let fee: Integer := 5

      -- Untyped let when obvious
      let net_amount := amount - fee

      if is_deposit then
        let balance: Integer := balance + net_amount
        print("Deposited:", net_amount)
      else
        -- Type helps document intent
        let new_balance: Integer := balance - net_amount

        if new_balance >= 0 then
          let balance := new_balance
          print("Withdrawn:", net_amount)
        else
          print("Insufficient funds")
        end
      end

      print("Old balance:", old_balance)
      print("New balance:", balance)
    end
end
```

## In Loops

Typed let works everywhere untyped let works:

```nex
from
  let i: Integer := 1
  let sum: Integer := 0
invariant
  sum >= 0
until
  i > 10
do
  let sum: Integer := sum + i
  let i: Integer := i + 1
end
```

## In REPL

Both syntaxes work in the interactive REPL:

```nex
nex> let x: Integer := 100
=> 100

nex> print(x)
100

nex> let y := 200
=> 200

nex> let sum: Integer := x + y
=> 300
```

## Technical Notes

### For Interpreter Users

The interpreter ignores type annotations - both syntaxes behave identically at runtime. Types are purely for documentation and code generation.

### For Java Translator Users

- **Typed let**: Generates variable declarations with types
- **Untyped let**: Generates assignments (assumes variable exists)

When translating to Java, use typed let for the first declaration of each variable to get proper Java code.

### For Language Implementers

The grammar rule for `localVarDecl`:
```antlr
localVarDecl
    : LET IDENTIFIER (':' type)? ASSIGN expression
    ;
```

The AST includes an optional `var-type` field:
```clojure
{:type :let
 :name "x"
 :var-type "Integer"  ; nil if untyped
 :value {:type :integer :value 42}}
```

## See Also

- [Language Reference](README.md) - Full language documentation
- [Examples](examples/typed_let_demo.clj) - Working examples
- [Tests](test/nex/typed_let_test.clj) - Comprehensive test suite
