# Design by Contract: `require` and `ensure`

## Overview

Nex supports **Design by Contract** (DbC), a programming methodology that allows you to specify preconditions and postconditions on methods and constructors. This helps ensure program correctness by making assumptions explicit and verifying them at runtime.

## Key Concepts

### Preconditions (`require`)
- **What**: Conditions that must be true when a method/constructor is called
- **When checked**: Before the method body executes
- **Responsibility**: The caller must ensure preconditions are satisfied
- **Failure**: Indicates a bug in the calling code

### Postconditions (`ensure`)
- **What**: Conditions that must be true when a method/constructor returns
- **When checked**: After the method body executes
- **Responsibility**: The method implementation must ensure postconditions are satisfied
- **Failure**: Indicates a bug in the method implementation

## Syntax

### Method with Contracts

```nex
method_name(params)
    require
        label1: boolean_expression
        label2: boolean_expression
        ...
    do
        -- method body
    ensure
        label3: boolean_expression
        label4: boolean_expression
        ...
    end
```

### Constructor with Contracts

```nex
constructor_name(params)
    require
        label1: boolean_expression
        ...
    do
        -- constructor body
    ensure
        label1: boolean_expression
        ...
    end
```

## Examples

### Example 1: Simple Precondition

```nex
class Calendar
  feature
    set_day(a_day: Integer)
      require
        valid_day: a_day >= 1 and a_day <= 31
      do
        day := a_day
      end
end
```

### Example 2: Precondition and Postcondition

```nex
class Calendar
  feature
    set_hour(a_hour: Integer)
      require
        valid_hour: a_hour >= 0 and a_hour <= 23
      do
        hour := a_hour
      ensure
        hour_set: hour = a_hour
      end
end
```

### Example 3: Constructor with Contracts

```nex
class Date
  constructors
    make(a_day: Integer, a_hour: Integer)
      require
        valid_day: a_day >= 1 and a_day <= 31
        valid_hour: a_hour >= 0 and a_hour <= 23
      do
        day := a_day
        hour := a_hour
      ensure
        day_set: day = a_day
        hour_set: hour = a_hour
      end
end
```

### Example 4: Multiple Preconditions

```nex
class Math
  feature
    divide(a: Integer, b: Integer)
      require
        non_zero: b /= 0
        positive_a: a > 0
        positive_b: b > 0
      do
        result := a / b
      ensure
        positive_result: result > 0
      end
end
```

## Complete Example: Date Class

Here's the full example from the specification:

```nex
class Date
  constructors
    make(a_day: Integer, a_hour: Integer)
      require
        valid_day: a_day >= 1 and a_day <= 31
        valid_hour: a_hour >= 0 and a_hour <= 23
      do
        day := a_day
        hour := a_hour
      ensure
        day_set: day = a_day
        hour_set: hour = a_hour
      end

  feature
    day: Integer
    hour: Integer

  feature
    set_day(a_day: Integer)
      require
        valid_argument: a_day >= 1 and a_day <= 31
      do
        day := a_day
      ensure
        day_set: day = a_day
      end

    set_hour(a_hour: Integer)
      require
        valid_argument: a_hour >= 0 and a_hour <= 23
      do
        hour := a_hour
      ensure
        hour_set: hour = a_hour
      end
end
```

## Contract Checking Behavior

### When Contracts Are Checked

1. **Preconditions**: Checked immediately before method body execution
   - All parameters are available
   - Object fields are available
   - Caller's context is NOT available

2. **Postconditions**: Checked immediately after method body execution
   - All parameters are still available
   - Object fields reflect final state
   - Local variables from method body are available

### Contract Violation

When a contract is violated, an exception is thrown with:
- The contract type ("Precondition" or "Postcondition")
- The label of the failed assertion
- The condition that failed

**Example error:**
```
Precondition violation: valid_day
```

### Best Practices

1. **Label Meaningfully**: Use descriptive labels that explain what's being checked
   - Good: `valid_day`, `non_zero`, `positive_balance`
   - Bad: `check1`, `test`, `condition`

2. **Keep Conditions Simple**: Each assertion should check one logical condition
   ```nex
   -- Good
   require
     non_zero: b /= 0
     positive: b > 0

   -- Less clear
   require
     valid: b /= 0 and b > 0
   ```

3. **Don't Repeat Yourself**: Use meaningful names to avoid duplication
   ```nex
   -- Good
   require
     valid_day: a_day >= 1 and a_day <= 31

   -- Avoid
   require
     greater_than_zero: a_day >= 1
     less_than_32: a_day <= 31
   ```

4. **Postconditions Should Verify Intent**: Check that the method did what it promised
   ```nex
   set_value(v: Integer)
     do
       value := v
     ensure
       value_set: value = v  -- Verify the intent
     end
   ```

## Implementation Details

### Grammar

```antlr
methodDecl
    : IDENTIFIER '(' paramList? ')' requireClause? DO block ensureClause? END
    ;

constructorDecl
    : IDENTIFIER '(' paramList? ')' requireClause? DO block ensureClause? END
    ;

requireClause
    : REQUIRE assertion+
    ;

ensureClause
    : ENSURE assertion+
    ;

assertion
    : IDENTIFIER ':' expression
    ;

REQUIRE : 'require';
ENSURE  : 'ensure';
```

### AST Representation

Methods and constructors include optional `:require` and `:ensure` fields:

```clojure
{:type :method
 :name "set_day"
 :params [{:name "a_day" :type "Integer"}]
 :require [{:label "valid_day"
            :condition {:type :binary ...}}]
 :body [...]
 :ensure [{:label "day_set"
           :condition {:type :binary ...}}]}
```

### Interpreter Behavior

```clojure
;; Before method execution
(when-let [require-assertions (:require method-def)]
  (check-assertions ctx require-assertions "Precondition"))

;; Execute method body
(execute-body ...)

;; After method execution
(when-let [ensure-assertions (:ensure method-def)]
  (check-assertions ctx ensure-assertions "Postcondition"))
```

## Testing

Run the contract tests:

```bash
# Test contract parsing
clojure -M test_contracts.clj

# Test contract execution
clojure -M test_contracts_exec.clj
```

## Benefits of Design by Contract

1. **Documentation**: Contracts serve as executable documentation
2. **Debugging**: Contract violations pinpoint exactly where bugs occur
3. **Reliability**: Explicit assumptions prevent subtle bugs
4. **Maintenance**: Easier to understand and modify code safely
5. **Testing**: Contracts complement unit tests by running continuously

## Comparison with Other Languages

### Eiffel
Nex's contract syntax is inspired by Eiffel, which pioneered DbC:
- Same keywords: `require`, `ensure`
- Same label syntax: `label: condition`
- Similar placement in method declarations

### Differences from Assertions
Unlike simple assertions:
- Contracts are part of the method signature
- They document the method's interface
- They distinguish between caller responsibilities (require) and implementer responsibilities (ensure)

## Future Enhancements

Potential improvements:
- **Class Invariants**: Conditions that always hold for an object
- **Loop Invariants**: Conditions maintained by loops
- **Old Values**: Access to parameter values before method execution (`old a_day`)
- **Result**: Access to return value in postconditions
- **Inheritance**: Contract inheritance and strengthening/weakening rules
- **Optimization**: Disable contract checking in production builds

## References

- Bertrand Meyer, "Object-Oriented Software Construction" (2nd edition)
- Eiffel Programming Language documentation
- Design by Contract principles
