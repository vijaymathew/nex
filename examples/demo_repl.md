# Nex REPL Demo

This file demonstrates using the Nex REPL for interactive development.

## Starting the REPL

```bash
# Option 1: Using the launcher script
./nex-repl

# Option 2: Using Clojure CLI directly
clojure -M:repl
```

## Basic Examples

### Simple Expressions and Variables

```nex
nex> print(42)
42

nex> let x := 10
=> 10

nex> print(x)
10

nex> let y := x + 5
=> 15

nex> print(y)
15
```

### Conditional Logic

```nex
nex> if x > 5 then print("big") else print("small") end
"big"
```

### Loops

```nex
nex> from let i := 1 until i > 5 do print(i) let i := i + 1 end
1
2
3
4
5
```

### Defining Classes

```nex
nex> class Point
...    feature
...      x: Integer
...      y: Integer
...
...      distance() do
...        print(x * x + y * y)
...      end
...  end
Class(es) registered: Point
```

### Design by Contract

```nex
nex> class BankAccount
...    feature
...      balance: Integer
...
...      withdraw(amount: Integer)
...        require
...          sufficient: balance >= amount
...        do
...          let balance := balance - amount
...        ensure
...          decreased: balance >= 0
...        end
...  end
Class(es) registered: BankAccount
```

## REPL Commands

```nex
nex> :help
[Shows help message]

nex> :classes
Defined classes:
  • Point
  • BankAccount

nex> :vars
Defined variables:
  • x = 10
  • y = 15
  • i = 6

nex> :clear
Context cleared.

nex> :quit
Goodbye!
```

## Tips

1. **Multi-line input**: The REPL automatically detects when you need to continue input
   - Start a `class` definition and press Enter
   - Continue on the `...  ` prompt
   - The REPL knows when to stop reading

2. **Variables persist**: Variables defined with `let` remain available across REPL inputs

3. **Classes persist**: Classes defined in the REPL can be used until you `:clear`

4. **Error recovery**: If you make an error, just try again - the context is preserved

5. **Quick testing**: Perfect for trying out Nex syntax and testing small code snippets

## Example Session: GCD Algorithm

```nex
nex> class Math
...    feature
...      gcd(a, b: Integer) do
...        from
...          let x := a
...          let y := b
...        until
...          x = y
...        do
...          if x > y then
...            let x := x - y
...          else
...            let y := y - x
...          end
...        end
...        print(x)
...      end
...  end
Class(es) registered: Math

nex> :classes
Defined classes:
  • Math

nex> :quit
Goodbye!
```
