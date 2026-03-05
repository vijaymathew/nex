# Contracts In Nex

This document summarizes all contract-related features in Nex, including inheritance rules and test commands.

## Supported Contract Features

### 1) Feature Preconditions (`require`)
- Declared on methods/features and constructors.
- Checked before body execution.
- Violation message format: `Precondition violation: <label>`.

### 2) Feature Postconditions (`ensure`)
- Declared on methods/features and constructors.
- Checked after body execution.
- Violation message format: `Postcondition violation: <label>`.

### 3) Class Invariants (`invariant`)
- Declared at class level.
- Checked after constructor completion and after feature execution on objects.
- Violation message format: `Class invariant violation: <label>`.

### 4) Loop Contracts
- Loop invariant (`invariant`) is checked before and after each iteration.
- Loop variant (`variant`) is supported as a progress/decrease contract.

### 5) `old` In Postconditions
- `old` is supported in postconditions to reference pre-execution values.
- Using `old` outside postconditions is rejected.

## Inheritance Rules For Contracts

### Method/Feature Preconditions
- Effective inherited precondition rule:
- `<base-feature-require> OR <local-feature-require>`
- If only one side exists, that side is used.
- If both are missing, no precondition is enforced.

### Method/Feature Postconditions
- Effective inherited postcondition rule:
- `<base-feature-ensure> AND <local-feature-ensure>`
- If only one side exists, that side is used.
- If both are missing, no postcondition is enforced.

### Class Invariants
- Effective class invariant rule:
- `<base-invariants> AND <local-class-invariants>`
- `base-invariants` are collected recursively from all parent chains.
- Shared ancestors in diamond inheritance are deduplicated by ancestor class.

## Runtime vs Code Generators

### Interpreter Runtime
- Enforces `require`, `ensure`, class invariants, and loop contracts at execution time.
- Inherited contracts are composed with the rules above.

### Java Generator
- Emits `assert` checks for contracts (unless `:skip-contracts true`).
- Inherited method contracts are composed with:
- `require` as OR
- `ensure` as AND
- Effective class invariants (inherited + local, deduped) are emitted and checked.

### JavaScript Generator
- Emits `if (!cond) throw new Error(...)` checks (unless `:skip-contracts true`).
- Inherited method contracts are composed with:
- `require` as OR
- `ensure` as AND
- Effective class invariants (inherited + local, deduped) are emitted and checked.

## Contract Syntax Examples

```nex
class Account
feature
  balance: Integer

  deposit(amount: Integer)
    require
      positive: amount > 0
    do
      this.balance := balance + amount
    ensure
      increased: balance >= old balance
    end

invariant
  non_negative: balance >= 0
end
```

```nex
class Base
feature
  f(x: Integer)
    require
      base_ok: x > 0
    do
      print("base")
    ensure
      base_post: true
    end
end

class Child inherit Base
feature
  f(x: Integer)
    require
      child_ok: x < 0
    do
      print("child")
    ensure
      child_post: true
    end
end
```

For `Child.f`, effective precondition is `(base_ok) OR (child_ok)`, and effective postcondition is `(base_post) AND (child_post)`.

## Running Contract-Related Tests

Run from repo root:

### Runtime contract behavior
```bash
clojure -M:test -e "(require 'nex.inheritance-runtime-test) (clojure.test/run-tests 'nex.inheritance-runtime-test)"
clojure -M:test -e "(require 'nex.old-keyword-test) (clojure.test/run-tests 'nex.old-keyword-test)"
clojure -M:test -e "(require 'nex.loops-test) (clojure.test/run-tests 'nex.loops-test)"
clojure -M:test -e "(require 'nex.create-test) (clojure.test/run-tests 'nex.create-test)"
```

### Typechecker contract validation
```bash
clojure -M:test -e "(require 'nex.typechecker-test) (clojure.test/run-tests 'nex.typechecker-test)"
```

### Java/JS generator contract emission
```bash
clojure -M:test -e "(require 'nex.generator.java-test) (clojure.test/run-tests 'nex.generator.java-test)"
clojure -M:test -e "(require 'nex.generator.javascript_test) (clojure.test/run-tests 'nex.generator.javascript_test)"
```

### Convenience: broader suite including contract tests
```bash
clojure -M:test test/scripts/run_tests.clj
```

## Disabling Contracts In Generated Code

For production-style generation without emitted contract checks:

```clojure
(nex.generator.java/translate nex-code {:skip-contracts true})
(nex.generator.javascript/translate nex-code {:skip-contracts true})
```

