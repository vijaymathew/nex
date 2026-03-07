# A Short Tutorial to Nex

This tutorial is a compact, linear introduction to Nex in small steps, with complete examples.

Unless noted otherwise, snippets are REPL-friendly fragments.

## 1. A First Program

Nex programs are built from class declarations, function declarations, and calls.

```nex
print("Hello, Nex")
```

Comments start with `--`:

```nex
-- This line is ignored by the compiler/interpreter
print("Hello again")
```

## 2. Values, Variables, and Types

Variables are introduced with `let` and assigned with `:=`.

```nex
let name: String := "Ada"
let age: Integer := 12
let height: Real := 1.52
let ok: Boolean := true
```

In the REPL, type annotations are optional by default:

```nex
let x := 10
let y := x + 5
```

Built-in scalar types include:

- `Integer`
- `Integer64`
- `Real`
- `Decimal`
- `Char`
- `Boolean`
- `String`

Detachable (nullable) types use a leading `?`:

```nex
let maybe_name: ?String := nil
```

## 3. Expressions and Operators

Arithmetic:

```nex
let a := 10 + 2 * 3
let b := (10 + 2) * 3
let c := 10 % 3
let d := 2 ^ 8
```

Comparison and boolean logic:

```nex
let e := a = b
let f := a /= b
let g := (a > 5) and (b < 40)
let h := not g
```

Operator precedence follows conventional order: unary, multiplicative, additive, comparison, equality, `and`, `or`.

## 4. Control Flow

### 4.1 Conditional execution

```nex
if age >= 18 then
  print("adult")
elseif age >= 13 then
  print("teen")
else
  print("child")
end
```

### 4.2 Expression-level choice

`when` is an expression, so it can appear on the right-hand side:

```nex
let category := when age >= 18 "adult" else "minor" end
```

### 4.3 Case analysis

```nex
case age of
  0, 1, 2 then print("small")
  3, 4, 5 then print("medium")
  else print("large")
end
```

Each `then` branch takes a statement. If you need multiple statements, use a scoped `do ... end` block.

## 5. Repetition

### 5.1 `from ... until ... do ... end`

```nex
from
  let i: Integer := 1
until
  i > 5
do
  print(i)
  i := i + 1
end
```

### 5.2 `repeat`

```nex
repeat 3 do
  print("tick")
end
```

### 5.3 `across`

```nex
across [10, 20, 30] as x do
  print(x)
end
```

`across` also works with strings and maps.

## 6. Functions

Top-level functions use `function`:

```nex
function greet(name: String)
do
  print("Hello, " + name)
end

function double(n: Integer): Integer
do
  result := n * 2
end
```

Call forms:

```nex
greet("Bob")
print(double(5))
```

Anonymous functions use `fn`:

```nex
let inc := fn (n: Integer): Integer do
  result := n + 1
end

print(inc(10))
```

## 7. Data Structures

### 7.1 Arrays

```nex
let xs: Array [Integer] := [1, 2, 3]
print(xs.get(0))
```

### 7.2 Maps

```nex
let m: Map [String, String] := {"name": "Nex", "kind": "language"}
print(m.get("name"))
```

Array and map literals are expressions, so they can be nested.

## 8. Classes and Objects

A class groups fields and methods.

```nex
class Counter
  create
    make(start: Integer) do
      this.value := start
    end

  feature
    value: Integer

    inc() do
      this.value := this.value + 1
    end

    current(): Integer do
      result := value
    end
end
```

Construction uses `create`:

```nex
let c: Counter := create Counter.make(10)
c.inc
print(c.current)
```

Parameterless calls may omit parentheses (`c.inc`, `c.current`).

## 9. Generic Classes

```nex
class Box [T]
  create
    make(initial: T) do
      this.value := initial
    end

  feature
    value: T

    get(): T do
      result := value
    end
end
```

With constraints:

```nex
class Dictionary [K -> Hashable, V]
  feature
    key: K
    value: V
end
```

## 10. Inheritance

```nex
class Animal
  feature
    name: String

    speak do
      print(name)
    end
  create
    named(name: String) do
	  this.name := name
	end
end

class Dog
  inherit Animal
  feature
    speak do
      print(name + " says woof")
    end
end

let a: A := create Animal.named("Ko")
a.speak -- "Ko"
let d: A := create Dog.named("Ki")
d.speak -- "Ki says woof"
```

Nex also supports adaptation in inheritance (`rename`, `redefine`) for precise reuse.

## 11. Design by Contract

Contracts are first-class in Nex.

```nex
class Wallet
  feature
    money: Real

    spend(amount: Real)
      require
        non_negative_amount: amount >= 0.0
        enough: amount <= money
      do
        this.money := money - amount
      ensure
        decreased: money = old money - amount
      end

    create
	   with_balance(amount: Real) do
	     money := amount
	   end

  invariant
    never_negative: money >= 0.0
end

let w: Wallet := create Wallet.with_balance(-10)
Error: Class invariant violation: never_negative

## 12. Debugger Quickstart

Enable debugger in REPL:

```text
:debug on
```

Set breakpoints and run:

```text
:break Wallet.spend
:break Wallet.spend if amount > 100
:break field:money
:tbreak Wallet.spend:42
```

At `dbg>` prompt:

```text
:where
:locals
:print money
:next
:continue
```

Watch values change:

```text
:watch money
:watch money if money > 100
:watches
```

Tune breakpoint hit behavior:

```text
:ignore 1 2   -- breakpoint[1] ignores first 2 hits
:every 1 3    -- breakpoint[1] pauses every 3rd hit
```

Control breakpoints without deleting:

```text
:disable 1
:enable 1
```

Pause on failures:

```text
:breakon exception on
:breakon contract on
:breakon contract filter invariant
```

Save and restore debugger state:

```text
:breaksave .nex-debug.edn
:breakload .nex-debug.edn
```

Script debugger commands from a file:

```text
:debugscript debug_commands.dbg
```

For full command reference, see `docs/md/DEBUGGER.md`.

let w: Wallet := create Wallet.with_balance(10.2)
w.spend(9)
w.money -- 1.1999999999999993
w.spend(2) -- Error: Precondition violation: enough
```

Use contracts to state assumptions (`require`), guarantees (`ensure`), and global consistency rules (`invariant`).

## 12. Errors and Recovery

```nex
let attempts := 0

do
  attempts := attempts + 1
  if attempts < 3 then
    raise "not ready"
  end
  print("ok")
rescue
  print("retrying")
  retry
end
```

`raise`, `rescue`, and `retry` provide structured recovery paths.

## 13. Modules and Interop

### 13.1 Import external Java/JavaScript symbols

```nex
import java.util.Scanner
import Math from './math.js'
```

### 13.2 Load Nex classes from other files

```nex
intern math/Calculator
intern math/Calculator as Calc
```

Use `intern` for Nex-to-Nex modularity and `import` for target-platform interop.

## 14. A Complete Example

```nex
class BankAccount
  create
    make(initial: Real) do
      this.balance := initial
    end

  feature
    balance: Real

    deposit(amount: Real)
      require
        positive: amount > 0.0
      do
        this.balance := balance + amount
      ensure
        grew: balance >= old balance
      end

    withdraw(amount: Real)
      require
        positive: amount > 0.0
        enough: amount <= balance
      do
        this.balance := balance - amount
      ensure
        shrank: balance = old balance - amount
      end

    show do
      print("balance = " + balance)
    end

  invariant
    never_negative: balance >= 0.0
end

let account := create BankAccount.make(100.0)
account.deposit(25.0)
account.withdraw(40.0)
account.show
```

## 15. Suggested Study Order

1. Write and run tiny programs from Sections 1-5.
2. Implement 3-5 utility functions (Section 6).
3. Build one small class with constructor + contracts (Sections 8 and 11).
4. Add one inheritance step (Section 10).
5. Split code into files and use `intern` (Section 13).

## 16. Exercises

1. Implement `max(a, b: Integer): Integer` using `if`.
2. Write a loop that prints numbers `10` down to `1`.
3. Define a generic `Pair [A, B]` class with `first` and `second`.
4. Add contracts to a `transfer(amount)` method between two accounts.
5. Create two files and load one class from the other with `intern`.
