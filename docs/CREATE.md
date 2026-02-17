# Object Creation with the `create` Keyword

Nex uses the `create` keyword for object instantiation, following Eiffel's creation syntax. This provides a clean, explicit way to create new instances of classes.

## Basic Syntax

### Default Constructor

To create an object with default initialization (all fields set to their default values):

```nex
let obj := create ClassName
```

Or with explicit type annotation:

```nex
let obj: ClassName := create ClassName
```

### Named Constructor

To create an object using a user-defined constructor:

```nex
let obj := create ClassName.constructor_name(arguments)
```

## Examples

### Simple Object Creation

```nex
class Point
  feature
    x: Integer
    y: Integer
end

class Main
  feature
    demo() do
      -- Create with default initialization (x=0, y=0)
      let p: Point := create Point
      print(p)
    end
end
```

**Generated Java:**
```java
public class Point {
    private int x = 0;
    private int y = 0;
}

public class Main {
    public void demo() {
        Point p = new Point();
        System.out.println(p);
    }
}
```

### Using Named Constructors

```nex
class Account
  constructors
    with_balance(initial: Integer) do
      let balance := initial
    end

    with_owner(owner_name: String, initial: Integer) do
      let owner := owner_name
      let balance := initial
    end

  feature
    balance: Integer
    owner: String
end

class Main
  feature
    demo() do
      -- Create with initial balance
      let savings: Account := create Account.with_balance(1000)

      -- Create with owner and balance
      let checking: Account := create Account.with_owner("Alice", 5000)
    end
end
```

**Generated Java:**
```java
public class Account {
    private int balance = 0;
    private String owner = null;

    public Account(int initial) {
        balance = initial;
    }

    public Account(String owner_name, int initial) {
        owner = owner_name;
        balance = initial;
    }
}

public class Main {
    public void demo() {
        Account savings = new Account(1000);
        Account checking = new Account("Alice", 5000);
    }
}
```

### Constructors with Contracts

Constructors can have preconditions and postconditions:

```nex
class Rectangle
  constructors
    make(w, h: Integer)
      require
        positive_width: w > 0
        positive_height: h > 0
      do
        let width := w
        let height := h
      ensure
        width_set: width = w
        height_set: height = h
      end

  feature
    width: Integer
    height: Integer

    area() do
      print(width * height)
    end
end

class Main
  feature
    demo() do
      -- This will work
      let r: Rectangle := create Rectangle.make(10, 20)
      r.area()  -- Prints: 200

      -- This would violate the precondition
      -- let bad: Rectangle := create Rectangle.make(-5, 10)
    end
end
```

## Default Initialization

When using `create ClassName` without a constructor, all fields are initialized to their default values:

| Type | Default Value |
|------|---------------|
| **Integer** | `0` |
| **Integer64** | `0L` |
| **Real** | `0.0f` |
| **Decimal** | `0.0` |
| **Char** | `#0` |
| **Boolean** | `false` |
| **String** | `""` |

### Example

```nex
class Person
  feature
    name: String
    age: Integer
    height: Decimal
    active: Boolean
end

class Main
  feature
    demo() do
      -- All fields get default values:
      -- name = null, age = 0, height = 0.0, active = false
      let p: Person := create Person
    end
end
```

## Multiple Parameters

Constructors can accept multiple parameters with different types:

```nex
class Employee
  constructors
    make(name: String, age: Integer, salary: Decimal, active: Boolean) do
      let name := name
      let age := age
      let salary := salary
      let active := active
    end

  feature
    name: String
    age: Integer
    salary: Decimal
    active: Boolean
end

class Main
  feature
    demo() do
      let emp: Employee := create Employee.make("Bob", 30, 75000.50, true)
    end
end
```

## Constructor Naming Conventions

Constructor names should be descriptive and follow these conventions:

- Use `make` for basic construction: `make(x, y: Integer)`
- Use `with_` prefix for specific initialization: `with_balance(amount: Integer)`
- Use `from_` prefix for conversion: `from_string(s: String)`
- Use specific domain names: `for_user(user_id: Integer)`

### Examples

```nex
class Date
  constructors
    -- Basic constructor
    make(d, m, y: Integer) do
      let day := d
      let month := m
      let year := y
    end

    -- Current date
    today() do
      -- Implementation to get current date
    end

    -- From string
    from_string(date_str: String) do
      -- Parse date string
    end

  feature
    day: Integer
    month: Integer
    year: Integer
end
```

## Type Inference

You can omit the type annotation in `let` statements:

```nex
class Point
  feature
    x: Integer
    y: Integer
end

class Main
  feature
    demo() do
      -- Without type annotation
      let p := create Point

      -- With type annotation (recommended for clarity)
      let q: Point := create Point
    end
end
```

## Create in Expressions

The `create` expression can be used anywhere an expression is expected:

```nex
class Node
  constructors
    make(val: Integer) do
      let value := val
    end

  feature
    value: Integer
    next: Node
end

class Main
  feature
    demo() do
      -- Create in let statement
      let first: Node := create Node.make(1)

      -- Create in assignment
      let first.next := create Node.make(2)

      -- Create in method arguments (if methods accepted parameters)
      -- process(create Node.make(3))
    end
end
```

## Comparison with Other Languages

### Java
```java
// Java
Point p = new Point();
Account acc = new Account(1000);
```

### Nex
```nex
-- Nex
let p: Point := create Point
let acc: Account := create Account.with_balance(1000)
```

### Eiffel
```eiffel
-- Eiffel
create p
create acc.make_with_balance(1000)
```

## Best Practices

1. **Always use type annotations for clarity:**
   ```nex
   let account: Account := create Account.with_balance(1000)
   ```

2. **Give constructors meaningful names:**
   ```nex
   -- Good
   create Account.with_initial_balance(1000)

   -- Less clear
   create Account.init(1000)
   ```

3. **Use contracts to enforce creation invariants:**
   ```nex
   constructors
     with_balance(initial: Integer)
       require
         non_negative: initial >= 0
       do
         let balance := initial
       end
   ```

4. **Initialize all fields in constructors:**
   ```nex
   constructors
     make(x, y: Integer) do
       let x := x
       let y := y
       -- Good: both fields initialized
     end
   ```

## Implementation Details

### Parser

The `create` keyword is recognized as a primary expression in the grammar:

```antlr
createExpression
    : CREATE IDENTIFIER ('.' IDENTIFIER '(' argumentList? ')')?
    ;
```

### AST Structure

The walker produces:
```clojure
{:type :create
 :class-name "Account"
 :constructor "with_balance"  ; optional
 :args [1000]}
```

### Java Generation

- `create ClassName` → `new ClassName()`
- `create ClassName.constructor(args)` → `new ClassName(args)`

### Interpreter

The interpreter:
1. Looks up the class definition
2. Initializes all fields with default values
3. If a constructor is specified, executes it with the provided arguments
4. Checks the class invariant
5. Returns the created object

## See Also

- [Type System](TYPES.md) - Default values for all types
- [Constructors](README.md#constructors) - Defining constructors
- [Contracts](CONTRACTS.md) - Using require/ensure with constructors
- [Let Syntax](LET_SYNTAX.md) - Variable declarations with create
