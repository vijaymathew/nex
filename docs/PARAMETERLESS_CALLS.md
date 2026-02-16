# Parameterless Method Calls

Nex allows methods with no parameters to be called without parentheses, making code more natural and readable. This is a common feature in languages like Ruby, Scala, and Kotlin.

## Basic Syntax

### Method Declaration

Method declarations **always** require parentheses, even for parameterless methods:

```nex
class Point
  feature
    show() do        -- Parentheses required in declaration
      print("Hello")
    end
end
```

### Method Call

Method calls can **optionally** omit parentheses when the method has no parameters:

```nex
class Main
  feature
    demo() do
      show           -- No parentheses (preferred style)
      show()         -- Parentheses still work
    end
end
```

## Examples

### Simple Parameterless Call

```nex
class Point
  private feature
    x: Integer
    y: Integer

  constructors
    make(newx, newy: Integer) do
      x := newx
      y := newy
    end

  feature
    show() do
      print(x)
      print(":")
      print(y)
    end
end

class Main
  feature
    demo() do
      let p: Point := create Point.make(10, 20)
      p.show       -- Prints "10:20"
    end
end
```

**Generated Java:**
```java
public class Point {
    private int x = 0;
    private int y = 0;

    public Point(int newx, int newy) {
        x = newx;
        y = newy;
    }

    public void show() {
        System.out.println(x);
        System.out.println(":");
        System.out.println(y);
    }
}

public class Main {
    public void demo() {
        Point p = new Point(10, 20);
        p.show();    // Java always needs parentheses
    }
}
```

### Chained Parameterless Calls

```nex
class Account
  feature
    balance: Integer
    name: String

  feature
    show_balance() do
      print("Balance: ")
      print(balance)
    end

    show_name() do
      print("Name: ")
      print(name)
    end

    show_all() do
      show_name       -- No parentheses
      show_balance    -- No parentheses
    end
end
```

### Mixed Calls

Methods with and without parameters can be mixed naturally:

```nex
class Calculator
  feature
    result: Integer

    clear() do
      result := 0
    end

    show() do
      print(result)
    end

    add(x: Integer) do
      result := result + x
    end

    demo() do
      clear          -- No params, no parens
      add(5)         -- Has params, needs parens
      add(10)        -- Has params, needs parens
      show           -- No params, no parens
    end
end
```

### With Design by Contract

Parameterless methods work seamlessly with contracts:

```nex
class Account
  feature
    balance: Integer

    validate()
      require
        non_negative: balance >= 0
      do
        print("Account is valid")
      ensure
        still_valid: balance >= 0
      end

    check() do
      validate    -- Call without parentheses
    end
end
```

## Style Guide

### When to Omit Parentheses

**DO omit parentheses** for:
- Query methods (getters): `p.get_x`, `account.balance`
- Display methods: `obj.show`, `obj.display`
- State check methods: `obj.validate`, `obj.verify`
- Simple actions: `obj.reset`, `obj.clear`

**DO use parentheses** for:
- Methods that take parameters: `obj.add(5)`, `obj.set_name("Alice")`
- For clarity when the method name might be ambiguous
- When you want to emphasize it's a method call

### Examples of Good Style

```nex
-- Good: Query methods without parens
let x := point.get_x
let y := point.get_y

-- Good: Display methods without parens
account.show
logger.flush

-- Good: State operations without parens
calculator.clear
buffer.reset

-- Good: Methods with parameters always use parens
point.move(10, 20)
account.deposit(100)
```

## Comparison with Other Languages

### Ruby
```ruby
# Ruby
obj.show        # No parentheses
obj.show()      # Parentheses optional
obj.add(5)      # Parentheses required for parameters
```

### Scala
```scala
// Scala
obj.show        // No parentheses
obj.show()      // Parentheses optional
obj.add(5)      // Parentheses required
```

### Nex
```nex
-- Nex
obj.show        -- No parentheses
obj.show()      -- Parentheses optional
obj.add(5)      -- Parentheses required
```

### Java (for comparison)
```java
// Java
obj.show();     // Parentheses always required
obj.add(5);     // Parentheses always required
```

## Benefits

### 1. **Readability**

Code reads more naturally without unnecessary parentheses:

```nex
-- More readable
account.show
calculator.clear
logger.flush

-- vs
account.show()
calculator.clear()
logger.flush()
```

### 2. **Uniform Access Principle**

Allows treating parameterless methods like properties:

```nex
-- Looks like property access
let x := point.get_x
let balance := account.balance

-- But they're actually method calls
```

### 3. **Fluent Interfaces**

Makes method chains more readable:

```nex
builder.reset
builder.add_header
builder.add_footer
builder.build
```

### 4. **Backward Compatibility**

Parentheses still work, so existing code doesn't break:

```nex
obj.show()      -- Still valid
obj.show        -- Also valid
```

## Important Notes

### Method Declarations

Method **declarations** always need parentheses, even for parameterless methods:

```nex
class Point
  feature
    -- CORRECT: Declaration with parentheses
    show() do
      print("Hello")
    end

    -- INCORRECT: Declaration without parentheses
    -- show do
    --   print("Hello")
    -- end
end
```

### Distinguishing Fields from Methods

In Nex, fields and parameterless methods can both be accessed without parentheses:

```nex
class Point
  feature
    x: Integer              -- Field

    get_x() do              -- Parameterless method
      print(x)
    end

    demo() do
      print(x)              -- Field access
      get_x                 -- Method call (no parens)
    end
end
```

The context and naming conventions help distinguish between the two.

## Java Translation

When Nex code with parameterless calls is translated to Java, the parentheses are always added:

**Nex Input:**
```nex
p.show
obj.reset
calculator.clear
```

**Java Output:**
```java
p.show();
obj.reset();
calculator.clear();
```

## See Also

- [Method Declarations](README.md#methods) - Defining methods
- [Let Syntax](LET_SYNTAX.md) - Variable declarations
- [Contracts](CONTRACTS.md) - Using contracts with methods
- [Examples](examples/parameterless_calls_example.nex) - More examples
