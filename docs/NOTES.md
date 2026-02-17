# Documentation with Notes

The `note` keyword allows you to attach documentation strings to classes, fields, and methods in Nex code. These notes are preserved during code generation and output as Javadoc (Java) or JSDoc (JavaScript) comments.

## Syntax

### Class Notes

```nex
class ClassName
  note "Description of the class"

feature
  ...
end
```

### Field Notes

```nex
field_name: Type note "Description of the field"
```

### Method Notes

```nex
method_name(params)
  note "Description of the method"
do
  ...
end
```

## Complete Example

```nex
class BankAccount
  note "A bank account with balance tracking"

feature
  balance: Integer note "Current balance in cents"
  owner: String note "Account owner name"

  get_balance(): Integer
    note "Returns the current balance"
  do
      balance
  end

  deposit(amount: Integer)
    note "Deposit money into the account"
    require
      positive: amount > 0
    do
      let balance := balance + amount
    end

private feature
  internal_id: String note "Internal identifier"

constructors
  make(initial: Integer, name: String) do
      let balance := initial
      let owner := name
      let internal_id := "ACC-001"
  end
end
```

## Code Generation

### Java Output

Notes are converted to Javadoc comments:

```java
/**
 * A bank account with balance tracking
 */
public class BankAccount {
    /**
     * Current balance in cents
     */
    private int balance = 0;

    /**
     * Account owner name
     */
    private String owner = null;

    /**
     * Returns the current balance
     */
    public int get_balance() {
        return balance;
    }

    /**
     * Deposit money into the account
     */
    public void deposit(int amount) {
        assert (amount > 0); // Precondition
        balance = balance + amount;
    }
}
```

### JavaScript Output

Notes are converted to JSDoc comments:

```javascript
/**
 * A bank account with balance tracking
 */
class BankAccount {
  constructor(initial, name) {
    this.balance = initial;
    this.owner = name;
    this.internal_id = "ACC-001";
  }

  /**
   * Returns the current balance
   * @returns {number}
   */
  get_balance() {
    return this.balance;
  }

  /**
   * Deposit money into the account
   * @param {number} amount
   */
  deposit(amount) {
    console.assert(amount > 0, "Precondition");
    this.balance = this.balance + amount;
  }
}
```

## Formatting

The `nex.fmt` formatter properly formats notes:

- Class notes appear indented on the line after the class declaration
- Field notes appear inline after the type
- Method notes appear indented on the line after the method signature

Example formatted output:

```nex
class Point
  note "Represents coordinates in space"
feature
  x: Integer note "x coordinate"
  y: Integer note "y coordinate"

  show()
    note "Display coordinates"
    do
      print(x)
      print(y)
    end
end
```

## Best Practices

1. **Be Concise**: Keep notes brief and focused. One or two sentences is usually sufficient.

2. **Document Intent**: Explain *why* something exists, not just *what* it is.

3. **Use for Public API**: Focus on documenting public features and methods that external code will use.

4. **Complement Contracts**: Notes describe behavior; contracts enforce it. Use both together.

5. **Avoid Redundancy**: Don't repeat information that's obvious from the code itself.

## Integration

Notes are fully integrated into the Nex toolchain:

- **Parser**: Extracts notes and includes them in the AST
- **Formatter**: Preserves and formats notes consistently
- **Java Generator**: Outputs Javadoc comments
- **JavaScript Generator**: Outputs JSDoc comments
- **Emacs Mode**: Syntax highlighting for `note` keyword
