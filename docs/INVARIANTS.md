# Class Invariants

## Overview

**Class invariants** are conditions that must hold true for all instances of a class at all stable times (i.e., before and after any public operation). They are a key component of Design by Contract and help ensure object consistency.

## Concept

An invariant is a property that:
- Must be established by constructors
- Must be preserved by all public methods
- May be temporarily violated during method execution
- Must be restored before the method returns

## Syntax

Class invariants are declared at the end of a class definition using the `invariant` keyword:

```nex
class ClassName
  feature
    -- fields and methods
  invariant
    label1: boolean_expression
    label2: boolean_expression
    ...
end
```

## Position in Class

The `invariant` clause appears:
- **After** all feature sections and constructor sections
- **Before** the final `end` keyword of the class

```nex
class MyClass
  create
    -- constructors here
  feature
    -- fields here
  feature
    -- methods here
  invariant      -- ← Invariant clause here
    -- assertions here
end              -- ← Class ends here
```

## Examples

### Example 1: Simple Invariant

```nex
class Counter
  feature
    value: Integer

  feature
    increment() do
      value := value + 1
    end

  invariant
    non_negative: value >= 0
end
```

### Example 2: Multiple Invariants

```nex
class Date
  feature
    day: Integer
    hour: Integer

  invariant
    valid_day: day >= 1 and day <= 31
    valid_hour: hour >= 0 and hour <= 23
end
```

### Example 3: Complex Invariants

```nex
class BankAccount
  feature
    balance: Integer
    credit_limit: Integer

  invariant
    valid_balance: balance >= 0 - credit_limit
    reasonable_credit: credit_limit >= 0 and credit_limit <= 10000
end
```

### Example 4: Complete Date Class

The complete example from the specification:

```nex
class Date
  create
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

  invariant
    valid_day: day >= 1 and day <= 31
    valid_hour: hour >= 0 and hour <= 23
end
```

## When Invariants Are Checked

Class invariants are checked at the following times:

### 1. After Constructor Execution
```nex
-- Preconditions checked
-- Constructor body executes
-- Postconditions checked
-- Class invariant checked ← HERE
```

### 2. After Method Execution
```nex
-- Preconditions checked
-- Method body executes
-- Postconditions checked
-- Class invariant checked ← HERE
```

### Not Checked During
- Private method execution
- Temporary states within method bodies
- Constructor body execution (only at the end)

## Invariant Violations

When a class invariant is violated, an exception is thrown with:
- Contract type: "Class invariant"
- The label of the failed assertion
- The condition that failed

**Example error:**
```
Class invariant violation: valid_day
```

## Relationship with Contracts

Invariants work together with preconditions and postconditions:

```nex
class Example
  feature
    value: Integer

  feature
    set_value(v: Integer)
      require              -- 1. Check precondition
        positive: v > 0
      do                   -- 2. Execute body
        value := v
      ensure               -- 3. Check postcondition
        value_set: value = v
      end                  -- 4. Check invariant (implicit)

  invariant
    non_negative: value >= 0
end
```

Execution order:
1. **Precondition**: Checked on entry (caller's responsibility)
2. **Method body**: Executed
3. **Postcondition**: Checked on exit (method's responsibility)
4. **Class invariant**: Checked on exit (class's responsibility)

## Design Principles

### 1. Strong Enough to Ensure Correctness
Invariants should capture the essential properties:
```nex
-- Good: Captures the essential constraint
invariant
  valid_range: value >= min and value <= max

-- Too weak: Doesn't ensure correctness
invariant
  has_value: value /= 0
```

### 2. Not Too Strong
Avoid over-constraining:
```nex
-- Too strong: Unnecessarily restrictive
invariant
  exact_value: value = 42

-- Better: Allows valid states
invariant
  in_range: value >= 0 and value <= 100
```

### 3. Express Class Properties
Invariants should express properties of the class, not implementation details:
```nex
-- Good: Expresses a logical property
invariant
  count_matches_size: count = items.size()

-- Less good: Implementation detail
invariant
  uses_array: internal_storage /= null
```

## Best Practices

### 1. Label Meaningfully
```nex
-- Good
invariant
  non_negative_balance: balance >= 0
  within_credit_limit: balance >= -credit_limit

-- Bad
invariant
  check1: balance >= 0
  inv2: balance >= -credit_limit
```

### 2. One Concept Per Assertion
```nex
-- Good: Separate concerns
invariant
  valid_day: day >= 1 and day <= 31
  valid_month: month >= 1 and month <= 12

-- Less clear: Mixed concerns
invariant
  valid_date: day >= 1 and day <= 31 and month >= 1 and month <= 12
```

### 3. Document the Intent
```nex
invariant
  positive_balance: balance >= 0
    -- Balance cannot be negative; overdrafts use credit_limit

  reasonable_credit: credit_limit <= 10000
    -- Credit limit capped to minimize risk
```

## Common Patterns

### Range Constraints
```nex
invariant
  in_range: value >= min_value and value <= max_value
```

### Non-Null References
```nex
invariant
  has_owner: owner /= null
```

### Size Consistency
```nex
invariant
  size_matches: size = items.count()
```

### Relationship Between Fields
```nex
invariant
  width_height_consistency: area = width * height
```

### Ordering Constraints
```nex
invariant
  start_before_end: start_time <= end_time
```

## Implementation Details

### Grammar

```antlr
classDecl
    : CLASS IDENTIFIER classBody invariantClause? END
    ;

invariantClause
    : INVARIANT assertion+
    ;

assertion
    : IDENTIFIER ':' expression
    ;

INVARIANT : 'invariant';
```

### AST Representation

Classes include an optional `:invariant` field:

```clojure
{:type :class
 :name "Date"
 :body [...]
 :invariant [{:label "valid_day"
              :condition {:type :binary ...}}
             {:label "valid_hour"
              :condition {:type :binary ...}}]}
```

### Interpreter Behavior

```clojure
;; After method execution
(when-let [ensure-assertions (:ensure method-def)]
  (check-assertions ctx ensure-assertions "Postcondition"))

;; Check class invariant
(check-class-invariant ctx class-def)
```

## Testing

Run invariant tests:

```bash
# Test invariant parsing
clojure -M test_invariants.clj

# Test invariant checking
clojure -M test_invariants_exec.clj
```

## Benefits

1. **Object Consistency**: Ensures objects remain in valid states
2. **Documentation**: Clearly states what makes an object valid
3. **Early Bug Detection**: Catches consistency violations immediately
4. **Maintainability**: Makes implicit assumptions explicit
5. **Refactoring Safety**: Invariants verify correctness after changes

## Comparison with Other Languages

### Eiffel
Nex's invariant syntax matches Eiffel exactly:
- Same keyword: `invariant`
- Same position: End of class
- Same label syntax: `label: condition`

### Differences from Assertions
Unlike simple assertions:
- Invariants are checked automatically after public operations
- They express class-level properties, not local conditions
- They are part of the class contract, not the method contract

## Future Enhancements

Potential improvements:
- **Inherited Invariants**: Combine invariants from parent classes
- **Conditional Invariants**: Invariants that apply only in certain states
- **Performance Optimization**: Disable invariant checking in production
- **Invariant Inference**: Automatically derive simple invariants
- **Weaker Invariants for Subclasses**: Allow controlled weakening in inheritance

## References

- Bertrand Meyer, "Object-Oriented Software Construction"
- Design by Contract principles
- Eiffel invariant semantics
