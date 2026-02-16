# Nex to JavaScript Translator

Complete guide to translating Nex code to JavaScript (ES6+).

## Table of Contents
- [Overview](#overview)
- [Quick Start](#quick-start)
- [Type Mappings](#type-mappings)
- [Class Translation](#class-translation)
- [Inheritance](#inheritance)
- [Generic Types](#generic-types)
- [Arrays and Maps](#arrays-and-maps)
- [Contracts](#contracts)
- [Visibility](#visibility)
- [Examples](#examples)
- [Production Builds](#production-builds)

## Overview

The Nex to JavaScript translator converts Nex source code into modern JavaScript (ES6+) with:
- **ES6 Classes**: Uses native JavaScript class syntax
- **JSDoc Annotations**: Type information preserved as JSDoc comments
- **Error-Based Contracts**: Runtime assertions using `throw new Error()`
- **Single Inheritance**: Extends one parent class
- **Modern Features**: Uses `let`, `const`, template literals, and ES6+ syntax

## Quick Start

### From Clojure REPL

```clojure
(require '[nex.generator.javascript :as js])

;; Translate Nex code to JavaScript
(def nex-code
  "class Point
  feature
    x: Integer
    y: Integer

    show() do
      print(x + \",\" + y)
    end
  end")

;; With contracts (development)
(println (js/translate nex-code))

;; Without contracts (production)
(println (js/translate nex-code {:skip-contracts true}))

;; Translate a file
(js/translate-file "input.nex" "output.js")

;; Production build without contracts
(js/translate-file "input.nex" "output.js" {:skip-contracts true})
```

### Running the Demo

```bash
clojure examples/demo_nex_to_javascript.clj
```

## Type Mappings

### Basic Types

| Nex Type     | JavaScript Type | JSDoc Type | Default Value |
|--------------|-----------------|------------|---------------|
| `Integer`    | `number`        | `number`   | `0`           |
| `Integer64`  | `number`        | `number`   | `0`           |
| `Real`       | `number`        | `number`   | `0.0`         |
| `Decimal`    | `number`        | `number`   | `0.0`         |
| `Char`       | `string`        | `string`   | `'\0'`        |
| `Boolean`    | `boolean`       | `boolean`  | `false`       |
| `String`     | `string`        | `string`   | `null`        |

### Collection Types

| Nex Type              | JavaScript Type | Default Value  |
|-----------------------|-----------------|----------------|
| `Array [T]`           | `Array`         | `[]`           |
| `Map [K, V]`          | `Map`           | `new Map()`    |

### Type Annotations

Nex type information is preserved as JSDoc comments:

**Nex:**
```nex
add(a, b: Integer) do
  print(a + b)
end
```

**JavaScript:**
```javascript
/**
 * @param {number} a
 * @param {number} b
 */
add(a, b) {
  console.log((a + b));
}
```

## Class Translation

### Basic Class

**Nex:**
```nex
class Person
  feature
    name: String
    age: Integer

    greet() do
      print("Hello")
    end
end
```

**JavaScript:**
```javascript
class Person {
  constructor() {
    this.name = null;
    this.age = 0;
  }

  greet() {
    console.log("Hello");
  }
}
```

### Class with Constructor

**Nex:**
```nex
class Point
  constructors
    make(x, y: Integer) do
      x := x
      y := y
    end

  feature
    x: Integer
    y: Integer
end
```

**JavaScript:**
```javascript
class Point {
  /**
   * @param {number} x
   * @param {number} y
   */
  constructor(x, y) {
    this.x = 0;
    this.y = 0;
    x = x;
    y = y;
  }
}
```

### Field Initialization

All fields are automatically initialized in the constructor with their default values before executing the constructor body.

## Inheritance

JavaScript supports single inheritance with the `extends` keyword.

**Nex:**
```nex
class Animal
  feature
    name: String

    speak() do
      print("Animal speaks")
    end
end

class Dog
inherit
  Animal
  end
feature
  breed: String

  bark() do
    print("Woof!")
  end
end
```

**JavaScript:**
```javascript
class Animal {
  constructor() {
    this.name = null;
  }

  speak() {
    console.log("Animal speaks");
  }
}

class Dog extends Animal {
  constructor() {
    this.breed = null;
  }

  bark() {
    console.log("Woof!");
  }
}
```

**Note:** Multiple inheritance in Nex translates to single inheritance in JavaScript (only the first parent is extended; additional parents are not supported in JavaScript).

## Generic Types

Generic types are documented using JSDoc `@template` tags.

### Simple Generic

**Nex:**
```nex
class Stack [G]
  feature
    items: Array [G]
    top: G

    push(item: G) do
      items := items
    end
end
```

**JavaScript:**
```javascript
/**
 * @template G
 */
class Stack {
  constructor() {
    this.items = [];
    this.top = null;
  }

  /**
   * @param {G} item
   */
  push(item) {
    items = items;
  }
}
```

### Constrained Generic

**Nex:**
```nex
class Sorted_List [G -> Comparable]
  feature
    items: Array [G]
end
```

**JavaScript:**
```javascript
/**
 * @template G extends Comparable
 */
class Sorted_List {
  constructor() {
    this.items = [];
  }
}
```

### Multiple Type Parameters

**Nex:**
```nex
class Pair [K, V]
  feature
    key: K
    value: V
end
```

**JavaScript:**
```javascript
/**
 * @template K, V
 */
class Pair {
  constructor() {
    this.key = null;
    this.value = null;
  }
}
```

## Arrays and Maps

### Array Declaration

**Nex:**
```nex
class Container
  feature
    items: Array [String]
    numbers: Array [Integer]
end
```

**JavaScript:**
```javascript
class Container {
  constructor() {
    this.items = [];
    this.numbers = [];
  }
}
```

### Array Literals

**Nex:**
```nex
let nums := [1, 2, 3, 4, 5]
let names := ["Alice", "Bob", "Charlie"]
```

**JavaScript:**
```javascript
let nums = [1, 2, 3, 4, 5];
let names = ["Alice", "Bob", "Charlie"];
```

### Map Declaration

**Nex:**
```nex
class Store
  feature
    prices: Map [String, Decimal]
    inventory: Map [String, Integer]
end
```

**JavaScript:**
```javascript
class Store {
  constructor() {
    this.prices = new Map();
    this.inventory = new Map();
  }
}
```

### Map Literals

**Nex:**
```nex
let ages := {"Alice": 30, "Bob": 25}
let config := {name: "App", version: 1}
```

**JavaScript:**
```javascript
let ages = new Map([["Alice", 30], ["Bob", 25]]);
let config = new Map([[name, "App"], [version, 1]]);
```

### Subscript Access

Subscript access works for both arrays and maps using a ternary operator:

**Nex:**
```nex
let x := items[0]
let y := data["key"]
```

**JavaScript:**
```javascript
let x = items.get ? items.get(0) : items[0];
let y = data.get ? data.get("key") : data["key"];
```

The generated code checks if the target has a `.get` method (Map) and uses it, otherwise uses array subscript notation.

### Nested Structures

**Nex:**
```nex
class Matrix
  feature
    data: Array [Array [Integer]]

  constructors
    make() do
      data := [[1, 2, 3], [4, 5, 6]]
    end
end
```

**JavaScript:**
```javascript
class Matrix {
  constructor() {
    this.data = [];
    data = [[1, 2, 3], [4, 5, 6]];
  }
}
```

## Contracts

### Preconditions and Postconditions

Contracts are enforced using `throw new Error()` statements.

**Nex:**
```nex
class Account
  feature
    balance: Integer

    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        balance := balance + amount
      ensure
        increased: balance >= 0
      end
end
```

**JavaScript (with contracts):**
```javascript
class Account {
  constructor() {
    this.balance = 0;
  }

  /**
   * @param {number} amount
   */
  deposit(amount) {
    if (!((amount > 0))) throw new Error("Precondition violation: positive");
    balance = (balance + amount);
    if (!((balance >= 0))) throw new Error("Postcondition violation: increased");
  }
}
```

### Class Invariants

Class invariants are documented as comments:

**Nex:**
```nex
class Account
  feature
    balance: Integer

  invariant
    non_negative: balance >= 0
end
```

**JavaScript:**
```javascript
class Account {
  // Class invariant: non_negative

  constructor() {
    this.balance = 0;
  }
}
```

### Loop Invariants and Variants

**Nex:**
```nex
from
  let i := 1
invariant
  i_positive: i > 0
variant
  n - i
until
  i > n
do
  print(i)
  i := i + 1
end
```

**JavaScript:**
```javascript
let i = 1;
while (!((i > n))) {
  // Invariant: i_positive
  // Variant: (n - i)
  console.log(i);
  i = (i + 1);
}
```

## Visibility

### Public Features

All members under `feature` are public by default:

**Nex:**
```nex
class Account
  feature
    balance: Integer
    deposit(amount: Integer) do
      balance := balance + amount
    end
end
```

**JavaScript:**
```javascript
class Account {
  constructor() {
    this.balance = 0;
  }

  deposit(amount) {
    balance = (balance + amount);
  }
}
```

### Private Features

Private members use an underscore `_` prefix naming convention:

**Nex:**
```nex
class Account
  private feature
    balance: Integer
    validate() do
      print(balance)
    end

  feature
    get_balance() do
      validate
      print(balance)
    end
end
```

**JavaScript:**
```javascript
class Account {
  constructor() {
    this._balance = 0;
  }

  _validate() {
    console.log(this._balance);
  }

  get_balance() {
    validate();
    console.log(balance);
  }
}
```

**Note:** JavaScript doesn't have true private visibility (until ES2022 with `#` syntax). The underscore prefix is a naming convention indicating the member should be treated as private.

### Selective Visibility

Selective visibility (visible to specific classes) is not supported in JavaScript and translates to public:

**Nex:**
```nex
[Friend, Helper] feature
  helper_method() do
    print("helper")
  end
```

**JavaScript:**
```javascript
helper_method() {
  console.log("helper");
}
```

## Examples

### Complete Banking Example

**Nex:**
```nex
class BankAccount
  private feature
    balance: Decimal
    account_number: String

  constructors
    make(number: String, initial: Decimal)
      require
        positive_initial: initial >= 0
      do
        account_number := number
        balance := initial
      ensure
        balance_set: balance = initial
      end

  feature
    deposit(amount: Decimal)
      require
        positive_amount: amount > 0
      do
        balance := balance + amount
      ensure
        balance_increased: balance >= 0
      end

    withdraw(amount: Decimal)
      require
        positive_amount: amount > 0
        sufficient_funds: balance >= amount
      do
        balance := balance - amount
      ensure
        balance_decreased: balance >= 0
      end

    get_balance() do
      print(balance)
    end

  invariant
    non_negative_balance: balance >= 0
end
```

**JavaScript:**
```javascript
class BankAccount {
  // Class invariant: non_negative_balance

  /**
   * @param {string} number
   * @param {number} initial
   */
  constructor(number, initial) {
    this._balance = 0.0;
    this._account_number = null;
    if (!((initial >= 0))) throw new Error("Precondition violation: positive_initial");
    account_number = number;
    balance = initial;
    if (!((balance === initial))) throw new Error("Postcondition violation: balance_set");
  }

  /**
   * @param {number} amount
   */
  deposit(amount) {
    if (!((amount > 0))) throw new Error("Precondition violation: positive_amount");
    balance = (balance + amount);
    if (!((balance >= 0))) throw new Error("Postcondition violation: balance_increased");
  }

  /**
   * @param {number} amount
   */
  withdraw(amount) {
    if (!((amount > 0))) throw new Error("Precondition violation: positive_amount");
    if (!((balance >= amount))) throw new Error("Precondition violation: sufficient_funds");
    balance = (balance - amount);
    if (!((balance >= 0))) throw new Error("Postcondition violation: balance_decreased");
  }

  get_balance() {
    console.log(this._balance);
  }
}
```

### Generic Stack Example

**Nex:**
```nex
class Stack [T]
  feature
    items: Array [T]
    count: Integer

  constructors
    make() do
      items := []
      count := 0
    end

  feature
    push(item: T) do
      items := items
      count := count + 1
    end

    is_empty() do
      print(count = 0)
    end
end
```

**JavaScript:**
```javascript
/**
 * @template T
 */
class Stack {
  constructor() {
    this.items = [];
    this.count = 0;
    items = [];
    count = 0;
  }

  /**
   * @param {T} item
   */
  push(item) {
    items = items;
    count = (count + 1);
  }

  is_empty() {
    console.log((count === 0));
  }
}
```

## Production Builds

For production deployment, omit contracts to improve performance:

```clojure
;; Development build (with contracts)
(js/translate-file "account.nex" "account.js")

;; Production build (without contracts)
(js/translate-file "account.nex" "account.js" {:skip-contracts true})
```

When `:skip-contracts` is `true`:
- No precondition checks (`require`)
- No postcondition checks (`ensure`)
- No class invariant comments
- No loop invariant/variant comments
- Smaller, faster code

## Operator Mappings

| Nex Operator | JavaScript Operator | Description           |
|--------------|---------------------|-----------------------|
| `and`        | `&&`                | Logical AND           |
| `or`         | `\|\|`              | Logical OR            |
| `not`        | `!`                 | Logical NOT           |
| `=`          | `===`               | Equality (strict)     |
| `/=`         | `!==`               | Inequality (strict)   |
| `>`          | `>`                 | Greater than          |
| `<`          | `<`                 | Less than             |
| `>=`         | `>=`                | Greater or equal      |
| `<=`         | `<=`                | Less or equal         |
| `+`          | `+`                 | Addition              |
| `-`          | `-`                 | Subtraction           |
| `*`          | `*`                 | Multiplication        |
| `/`          | `/`                 | Division              |

**Note:** Nex uses `=` for equality which translates to JavaScript's strict equality `===` (not `==`).

## Control Flow

### If-Then-Else

**Nex:**
```nex
if x > 5 then
  print("big")
else
  print("small")
end
```

**JavaScript:**
```javascript
if ((x > 5)) {
  console.log("big");
} else {
  console.log("small");
}
```

### Loops

**Nex:**
```nex
from
  let i := 1
until
  i > 10
do
  print(i)
  i := i + 1
end
```

**JavaScript:**
```javascript
let i = 1;
while (!((i > 10))) {
  console.log(i);
  i = (i + 1);
}
```

### Scoped Blocks

**Nex:**
```nex
do
  let x := 10
  print(x)
end
```

**JavaScript:**
```javascript
{
  let x = 10;
  console.log(x);
}
```

## Testing

Run the JavaScript generator test suite:

```bash
clojure -M:test -e "(require '[clojure.test :as test])
                     (require 'nex.generator.javascript_test)
                     (test/run-tests 'nex.generator.javascript_test)"
```

**Test Coverage:**
- 26 tests
- 73 assertions
- All tests passing

## API Reference

### Functions

#### `translate`
```clojure
(translate nex-code)
(translate nex-code {:skip-contracts true})
```
Translates Nex source code to JavaScript.

#### `translate-file`
```clojure
(translate-file "input.nex" "output.js")
(translate-file "input.nex" "output.js" {:skip-contracts true})
```
Translates a Nex file to JavaScript and saves it.

#### `translate-ast`
```clojure
(translate-ast ast)
(translate-ast ast {:skip-contracts true})
```
Translates a parsed Nex AST to JavaScript.

#### `print-translation`
```clojure
(print-translation nex-code)
```
Prints a nicely formatted translation with headers.

### Options

All translation functions accept an optional `opts` map:

| Option            | Type    | Default | Description                              |
|-------------------|---------|---------|------------------------------------------|
| `:skip-contracts` | boolean | `false` | Omit all contract checks                 |

## Limitations

1. **Multiple Inheritance**: JavaScript only supports single inheritance, so only the first parent in multiple inheritance is extended.

2. **True Private Members**: Uses naming convention (underscore prefix) rather than true private fields until ES2022 `#` syntax is more widely supported.

3. **Selective Visibility**: Not supported in JavaScript; translates to public.

4. **Old Expression**: Not yet implemented for postconditions.

5. **Type Safety**: JavaScript is dynamically typed; type information is preserved only as JSDoc comments.

## Best Practices

1. **Use TypeScript**: Consider using TypeScript instead of vanilla JavaScript for better type safety.

2. **Contract Strategy**:
   - Use contracts during development for debugging
   - Disable contracts in production for performance

3. **JSDoc Comments**: The generated JSDoc comments provide IDE autocomplete and type checking.

4. **Testing**: Always test generated JavaScript code in your target environment.

5. **ES6+ Features**: The generated code uses modern JavaScript features (classes, let, arrow functions). Ensure your target environment supports ES6 or use a transpiler like Babel.

## Future Enhancements

- TypeScript output option
- ES2022 private fields (`#field`)
- Optional null checks
- Async/await support
- Module system (import/export)
- Decorators for contracts

## See Also

- [Java Generator](TYPES.md#java-translation)
- [Generic Types](GENERICS.md)
- [Arrays and Maps](ARRAYS_MAPS.md)
- [Design by Contract](README.md#design-by-contract)
