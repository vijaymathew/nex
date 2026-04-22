# Nex Syntax Reference

This appendix is a compact reference to the main Nex constructs used throughout the tutorial. It is derived from `docs/md/SYNTAX.md` and the grammar in `grammar/nexlang.g4`.


## Lexical Basics

Comments:

```nex
-- single-line comment
```

Common literal forms:

```nex
42
3.14
"hello"
true
false
nil
#a
```

Real literals must include at least one digit after the decimal point. Valid
examples include `4.5`, `10.0`, `.5`, and `12.0e-3`. Forms such as `10.` and
`12.e-3` are not valid.


## Variables and Assignment

Declaration:

```nex
let name: String := "Ada"
let count: Integer := 0
```

Assignment:

```text
count := count + 1
this.balance := this.balance + 10.0
```


## Expressions and Operators

Arithmetic:

```text
+  -  *  /  %  ^
```

Comparison:

```text
=  /=  <  <=  >  >=
```

Boolean:

```text
and  or  not
```

Parentheses may be used to control grouping:

```text
(a + b) * c
```


## Control Flow

`if`:

```text
if condition then
  print("yes")
elseif other_condition then
  print("maybe")
else
  print("no")
end
```

`when`:

```text
let label: String := when age >= 18 "adult" else "minor" end
```

`case`:

```text
case direction of
  "up"   then print("going up")
  "down" then print("going down")
  else        print("still")
end
```


## Loops

`from ... until ... do`:

```nex
from
  let i: Integer := 0
until
  i = 10
do
  print(i)
  i := i + 1
end
```

With loop contracts:

```nex
from
  let i: Integer := 0
invariant
  in_range: i >= 0
variant
  10 - i
until
  i = 10
do
  i := i + 1
end
```

`repeat`:

```text
repeat 3 do
  print("hello")
end
```

`across`:

```nex
across [1, 2, 3] as x do
  print(x)
end
```


## Functions

Function declaration:

```nex
function max(a, b: Integer): Integer do
  if a >= b then
    result := a
  else
    result := b
  end
end
```

Anonymous function:

```nex
let f: Function := fn (x: Integer): Integer do
  result := x * 2
end
```

Mutually recursive functions must declare their signatures before their bodies:

```nex
function is_even(n: Integer): Boolean
function is_odd(n: Integer): Boolean

function is_even(n: Integer): Boolean do
  if n = 0 then
    result := true
  else
    result := is_odd(n - 1)
  end
end

function is_odd(n: Integer): Boolean do
  if n = 0 then
    result := false
  else
    result := is_even(n - 1)
  end
end
```


## Collections

Array:

```nex
let xs: Array[Integer] := [1, 2, 3]
xs.add(4)
print(xs.get(0))
```

Map:

```nex
let m: Map[String, Integer] := {"a": 1, "b": 2}
m.put("c", 3)
print(m.get("a"))
```

Set:

```nex
let seen: Set[Integer] := #{1, 2, 3}
let empty: Set[Integer] := #{}
let from_items: Set[Integer] := create Set[Integer].from_array([1, 2, 2, 3])
print(seen.union(#{3, 4}))
```

Use `#{...}` for set literals. The literal `{}` remains the empty map.


## Classes

Class skeleton:

```nex
class Point
  create
    make(px, py: Real) do
      x := px
      y := py
    end
  feature
    x: Real
    y: Real
    move(dx, dy: Real) do
      x := x + dx
      y := y + dy
    end
end
```

Class constants:

```nex
class Layout
  feature
    HELLO: String = "hello"
    MAX_WIDTH = 450
end
```

External access uses the class name:

```text
print(Layout.MAX_WIDTH)
```

Object creation:

```text
let p := create Point.make(3.0, 4.0)
```


## Inheritance and Generics

Inheritance:

```text
class Dog inherit Animal
  feature
    speak do
      print(name + " says woof")
    end
end
```

Generic class:

```text
class Stack [G]
  create
    make() do
      items := []
    end
  feature
    items: Array[G]
end
```

Generic constraint:

```text
class Sorted_List [G -> Comparable]
  create
    make() do
      items := []
    end
  feature
    items: Array[G]
end
```


## Contracts

Preconditions, postconditions, invariants:

```nex
class Wallet
  feature
    money: Real

    spend(amount: Real)
      require
        enough: amount <= money
      do
        money := money - amount
      ensure
        decreased: money = old money - amount
      end

  invariant
    non_negative: money >= 0.0
end
```

## Concurrency

Spawn a task:

```nex
let t: Task[Integer] := spawn do
  result := 1 + 2
end
```

Communicate with channels:

```nex
let ch: Channel[String] := create Channel[String].with_capacity(1)
ch.send("ready")
print(ch.receive)
```

Select across multiple channel operations:

```nex
let ch1: Channel[String] := create Channel.with_capacity(1)
let ch2: Channel[String] := create Channel.with_capacity(1)
ch1.send("ready")

select
when ch1.receive() as msg then
  print(msg)
when ch2.send("tick") then
  print("sent")
end
```


## Error Handling

Raise:

```text
raise "not ready"
```

Scoped recovery:

```nex
do
  print("trying")
rescue
  print(exception)
  retry
end
```

Routine-level rescue:

```nex
function load_default(): String do
  raise "missing"
rescue
  result := "fallback"
end
```


## Modularity and Interop

Load Nex classes:

```text
intern math/Calculator
intern math/Calculator as Calc
```

Resolution order is:

1. the loaded file's directory
2. the current working directory
3. `~/.nex/deps`

Path-qualified classes also support `lib/<path>/Class.nex`, lowercase filenames such as `tcp_socket.nex`, and the matching `src/` variants.

Import host symbols:

```text
import java.util.Scanner
import Math from './math.js'
```


## Notes

- `result` is the implicit return variable in functions and query methods.
- `old` is available in postconditions to refer to entry-state values, but it is
  not a deep immutable snapshot. Be careful with in-place mutation of values
  such as arrays.
- `this` refers to the current object.
- `nil` is used for detachable or absent values.
- Arrays use `length`; maps and sets use `size`.

For a fuller tutorial presentation, return to Chapters 1 through 29. For
implementation-level details, see `grammar/nexlang.g4`.
