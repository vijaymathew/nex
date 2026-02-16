# Selective Visibility Grammar Fix

## Issue

The selective visibility feature was failing to parse correctly because of a **grammar ambiguity** between:

1. **Generic parameters:** `class List [T, U]`
2. **Selective visibility:** `[Friend, Helper] feature`

### Problem Example

```nex
class Test
  [Friend, Helper] feature
    x: Integer
  end
end
```

Was being parsed as:

```nex
class Test[Friend, Helper]  -- Generics!
  feature
    x: Integer
  end
end
```

Instead of:

```nex
class Test
  {Friend, Helper} feature  -- Selective visibility!
    x: Integer
  end
end
```

### Root Cause

The ANTLR parser cannot distinguish between:
- `class Test [G]` (generic parameters)
- `class Test\n  [Friend, Helper] feature` (selective visibility)

Because the lexer ignores whitespace and newlines, both look identical to the parser.

## Solution

Changed the selective visibility syntax from **square brackets** `[]` to **curly braces** `{}`.

### Grammar Change

**Before:**
```antlr
visibilityModifier
    : PRIVATE
    | '[' IDENTIFIER (',' IDENTIFIER)* ']'
    ;
```

**After:**
```antlr
visibilityModifier
    : PRIVATE
    | '{' IDENTIFIER (',' IDENTIFIER)* '}'
    ;
```

### New Syntax

**Selective Visibility:**
```nex
class Account
  {Bank, Auditor} feature
    audit_log: String
    get_audit() do
      print(audit_log)
    end
end
```

**Generic Parameters (unchanged):**
```nex
class List [G]
  feature
    item: G
end
```

## Changes Made

### 1. Grammar (`grammar/nexlang.g4`)
- Changed `visibilityModifier` to use `{}` instead of `[]`

### 2. Walker (`src/nex/walker.clj`)
- Updated filter to exclude `{` and `}` instead of `[` and `]`
- Changed comment to reflect new syntax

### 3. Tests (`test/nex/visibility_test.clj`)
- Updated all test cases to use `{}` syntax
- All 12 visibility tests now pass

## Syntax Summary

### Selective Visibility

**Single Class:**
```nex
{Friend} feature
  helper() do
    print("Visible to Friend class only")
  end
end
```

**Multiple Classes:**
```nex
{Admin, Moderator, SuperUser} feature
  privileged_action() do
    print("Visible to specific classes")
  end
end
```

### Other Visibility Modifiers

**Public (default):**
```nex
feature
  public_method() do
    print("Visible to all")
  end
end
```

**Private:**
```nex
private feature
  internal_method() do
    print("Visible only within this class")
  end
end
```

## Code Generation

As noted by the user, **generated Java/JavaScript code does not enforce selective visibility** (as these languages may not support such fine-grained access control). The Nex interpreter and code generator verify selective visibility at the Nex language level.

### Generated Java

**Nex:**
```nex
class Account
  {Bank, Auditor} feature
    audit_log: String
end
```

**Generated Java:**
```java
public class Account {
    /* Visible to: Bank, Auditor */ String audit_log = null;
}
```

The generated code includes a **comment** documenting the visibility restriction, but Java cannot enforce it.

## Testing

All tests pass:
- **nex.visibility-test**: 12 tests, 34 assertions ✓
- **Complete test suite**: 66 tests, 248 assertions ✓

## Rationale for Curly Braces

**Why `{}` instead of other options?**

1. **Not conflicting with generics:** Square brackets `[]` are used for generics
2. **Not conflicting with parameters:** Parentheses `()` are used for method parameters
3. **Not conflicting with type parameters:** Angle brackets `<>` could be confused with comparison operators
4. **Semantic meaning:** Curly braces suggest a "set" or "group" of classes, which fits the selective visibility concept
5. **Familiar syntax:** Many languages use `{}` for sets, blocks, or groups

## Examples

### Multiple Selective Sections

```nex
class System
  {Admin} feature
    admin_only: Integer
    admin_action() do
      print("Admin only")
    end

  {User, Guest} feature
    user_visible: String
    public_info() do
      print("Visible to users and guests")
    end
end
```

### Mixed Visibility

```nex
class Account
  feature
    balance: Integer  -- Public

  private feature
    internal_id: String  -- Private

  {Bank, Auditor} feature
    audit_log: String  -- Selective
    log_transaction() do
      print(audit_log)
    end
end
```

## Backward Compatibility

**Breaking Change:** Code using the old `[Friend, Helper]` syntax will need to be updated to `{Friend, Helper}`.

**Migration:** Simply replace square brackets with curly braces in selective visibility declarations:
```bash
sed -i 's/\[\([A-Za-z, ]*\)\] feature/{\\1} feature/g' *.nex
```

## Conclusion

The grammar ambiguity is now resolved by using distinct syntax for:
- **Generic parameters:** `[T, U]`
- **Selective visibility:** `{Friend, Helper}`

This maintains clear semantics and eliminates parser confusion.
