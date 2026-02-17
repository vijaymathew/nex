# Nex Type System

Nex provides a comprehensive set of basic types with automatic default initialization.

## Basic Types

| Nex Type | Size | Java Equivalent | Description | Default Value |
|----------|------|-----------------|-------------|---------------|
| **Integer** | 32-bit | `int` | Signed integer | `0` |
| **Integer64** | 64-bit | `long` | Signed 64-bit integer | `0L` |
| **Real** | 32-bit | `float` | Single-precision floating-point | `0.0f` |
| **Decimal** | 64-bit | `double` | Double-precision floating-point | `0.0` |
| **Char** | 8-bit | `char` | Single character | `#0` |
| **Boolean** | - | `boolean` | true or false | `false` |
| **String** | - | `String` | Text string | `""` |

## Automatic Initialization

All fields declared with basic types are **automatically initialized** to their default values when translated to Java.

### Example

**Nex Code:**
```nex
class Account
  feature
    balance: Decimal
    transactions: Integer64
    active: Boolean
    owner: String
end
```

**Generated Java:**
```java
public class Account {
    private double balance = 0.0;
    private long transactions = 0L;
    private boolean active = false;
    private String owner = null;
}
```

## Type Usage

### Field Declarations

```nex
class Example
  feature
    count: Integer
    big_number: Integer64
    temperature: Real
    precise_value: Decimal
    initial: Char
    enabled: Boolean
    description: String
end
```

### Method Parameters

```nex
class Calculator
  feature
    calculate(x: Integer, y: Decimal) do
      print(x + y)
    end

    process(count: Integer64, active: Boolean, name: String) do
      print(count)
    end
end
```

### Typed Let Statements

```nex
class Demo
  feature
    example() do
      let count: Integer := 42
      let price: Decimal := 99.99
      let code: Char := #A
      let enabled: Boolean := true
      print(count)
    end
end
```

## Type Mappings

### Nex → Java

- **Integer** → `int` (32-bit signed integer)
- **Integer64** → `long` (64-bit signed integer)
- **Real** → `float` (32-bit IEEE 754 floating-point)
- **Decimal** → `double` (64-bit IEEE 754 floating-point)
- **Char** → `char` (16-bit Unicode character in Java, 8-bit in Nex)
- **Boolean** → `boolean` (true or false)
- **String** → `String` (object reference)

### Default Values

When fields are declared without explicit initialization:

```nex
class Test
  feature
    x: Integer        -- Automatically initialized to 0
    y: Decimal        -- Automatically initialized to 0.0
    active: Boolean   -- Automatically initialized to false
end
```

Translates to:

```java
public class Test {
    private int x = 0;
    private double y = 0.0;
    private boolean active = false;
}
```

## Precision Considerations

### Integer vs Integer64

Use **Integer** for most counting and indexing operations. Use **Integer64** when you need to store very large numbers (beyond ±2 billion).

```nex
class Statistics
  feature
    count: Integer           -- Suitable for most counters
    total_bytes: Integer64   -- For large file sizes
end
```

### Real vs Decimal

Use **Real** (32-bit) for graphics, game development, and when memory is a concern. Use **Decimal** (64-bit) for scientific calculations, financial data, and when precision is critical.

```nex
class Data
  feature
    temperature: Real    -- 32-bit is sufficient for temperatures
    price: Decimal       -- 64-bit for financial precision
end
```

## Examples

### Complete Class with All Types

```nex
class AllTypes
  feature
    -- Integers
    age: Integer
    population: Integer64

    -- Floating-point
    temperature: Real
    balance: Decimal

    -- Character and Boolean
    grade: Char
    active: Boolean

    -- String
    name: String

    demonstrate() do
      let x: Integer := 42
      let y: Decimal := 3.14159
      let c: Char := #A
      let flag: Boolean := true

      print("Integer:", x)
      print("Decimal:", y)
      print("Char:", c)
      print("Boolean:", flag)
    end
end
```

### Financial Application

```nex
class BankAccount
  feature
    balance: Decimal           -- Precise financial calculations
    transaction_count: Integer64  -- Handle many transactions
    active: Boolean
    account_number: String

    deposit(amount: Decimal)
      require
        positive: amount > 0
      do
        let balance := balance + amount
        let transaction_count := transaction_count + 1
      ensure
        increased: balance > 0
      end
end
```

### Game Entity

```nex
class Player
  feature
    health: Integer      -- Simple counter
    position_x: Real     -- Coordinates (32-bit sufficient)
    position_y: Real
    speed: Real
    alive: Boolean
    name: String

    move(dx: Real, dy: Real) do
      let position_x := position_x + dx * speed
      let position_y := position_y + dy * speed
    end
end
```

## Type Safety

Nex enforces type declarations at the language level. All fields must have explicit types, and all parameters must be typed:

```nex
class Typed
  feature
    value: Integer   -- Type required

    process(x: Integer) do  -- Parameter type required
      let y: Integer := x + 1  -- Can optionally type local variables
      let z := x * 2  -- Or use type inference
    end
end
```

## Implementation Notes

### Interpreter

The Nex interpreter currently doesn't enforce numeric types strictly (values are represented as Clojure numbers), but type declarations are preserved in the AST for code generation.

### Java Generator

The Java code generator:
1. Maps Nex types to appropriate Java primitive or object types
2. Automatically initializes all fields with default values
3. Uses correct Java type syntax for method parameters and returns
4. Generates proper typed let statements with declarations

## Migration from Older Code

If you have older Nex code that used generic types, update to the new specific types:

**Before:**
```nex
class Old
  feature
    value: Integer  -- Was using Integer for everything
    price: Real     -- Was using Real for all floats
end
```

**After:**
```nex
class New
  feature
    value: Integer    -- 32-bit integer (correct)
    big: Integer64    -- Use for large counts
    price: Decimal    -- Use Decimal for precision
    temp: Real        -- Use Real for graphics/games
end
```

## See Also

- [Let Syntax Guide](LET_SYNTAX.md) - Using typed let statements
- [Language Reference](README.md) - Full language documentation
- [Examples](examples/) - Working code examples
