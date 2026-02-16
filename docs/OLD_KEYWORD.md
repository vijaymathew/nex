# The `old` Keyword in Postconditions

## Overview

The `old` keyword allows postconditions to reference the values of object fields as they were **before** the method started executing. This is essential for expressing contracts that describe how a method should transform object state.

## Syntax

```nex
class ClassName
feature
  fieldName: Type
  methodName do
    -- method body that modifies fields
    ensure
      label: old fieldName <comparison> fieldName
  end
end
```

## Behavior

### Value Capture

When a method with postconditions is called:
1. **Before execution**: All object field values are captured as "old" values
2. **During execution**: The method modifies fields normally
3. **After execution**: Postconditions are evaluated with access to both:
   - `old fieldName` - the captured pre-execution value
   - `fieldName` - the current value after execution

### Rollback on Failure

If a postcondition fails:
1. An exception is thrown with details about the violation
2. **All object fields are rolled back** to their pre-execution values
3. The object state is as if the method was never called

This ensures that failed methods cannot leave objects in an invalid or partially-modified state.

## Examples

### Example 1: Simple Increment

```nex
class Counter
feature
  i: Integer
  increment do
    i := i + 1
    ensure
      i_incremented_by_one: old i = i - 1
  end
end
```

**Behavior:**
- If `i` was `5` before the call
- After `increment`, `i` will be `6`
- Postcondition checks: `old i = i - 1` → `5 = 6 - 1` → `5 = 5` → ✓ passes

### Example 2: Multiple Field Invariants

```nex
class Point
feature
  x: Integer
  y: Integer
  moveRight do
    x := x + 1
    ensure
      x_incremented: old x = x - 1
      y_unchanged: old y = y
  end
end
```

**Behavior:**
- Only `x` should change
- `y` must remain the same
- Both postconditions must pass

### Example 3: Postcondition Violation with Rollback

```nex
class Account
feature
  balance: Integer
  withdraw(amount: Integer) do
    balance := balance - amount
    ensure
      balance_non_negative: balance >= 0
  end
end
```

**Behavior:**
- If `balance` is `100` and `withdraw(150)` is called
- During execution: `balance` becomes `-50`
- Postcondition check fails: `-50 >= 0` → false
- **Rollback occurs**: `balance` is restored to `100`
- Exception is thrown: "Postcondition violation: balance_non_negative"

### Example 4: Complex Expression with `old`

```nex
class Rectangle
feature
  width: Integer
  height: Integer
  area: Integer
  updateArea do
    area := width * height
    ensure
      area_computed_correctly: area = width * height
      area_changed: old area /= area
  end
end
```

## Restrictions

### Only Valid in Postconditions

The `old` keyword can **only** be used in `ensure` blocks (postconditions). Using it anywhere else (including preconditions, method bodies, or class invariants) will result in an error:

```nex
class Bad
feature
  i: Integer
  wrong do
    let x := old i  -- ERROR: 'old' can only be used in postconditions
  end
end
```

### Only References Object Fields

The `old` keyword is designed to reference object fields (member variables). It captures the state of the object before method execution.

## Implementation Details

### Performance Considerations

- **Snapshot overhead**: When a method has postconditions, all object fields are copied before execution
- **Rollback cost**: If a postcondition fails, fields must be restored
- **Optimization**: If a method has no postconditions, no snapshot is taken (zero overhead)

### Thread Safety

The `old` value mechanism is thread-safe in single-threaded execution. For concurrent access, external synchronization is required.

## Comparison with Eiffel

The `old` keyword is inspired by Eiffel's design-by-contract features:

- **Similar**: Syntax and semantics match Eiffel's `old` keyword
- **Enhanced**: Nex automatically rolls back field changes on postcondition failure
- **Restriction**: In Nex, `old` is currently only supported for object fields, not for arbitrary expressions

## Testing

Comprehensive tests for the `old` keyword can be found in:
- `/test/nex/old_keyword_test.clj`

The test suite covers:
- ✓ Parsing of `old` expressions
- ✓ Correct value capture and evaluation
- ✓ Postcondition violation detection
- ✓ Automatic field rollback on failure
- ✓ Multiple field scenarios
- ✓ Error handling for misuse

## Future Enhancements

Potential improvements to the `old` keyword feature:

1. **Nested old expressions**: `old (old x)` for nested method calls
2. **Old in constructors**: Currently only supported in regular methods
3. **Selective snapshots**: Only snapshot fields referenced in postconditions
4. **Deep copying**: Handle mutable field types (arrays, objects) correctly
