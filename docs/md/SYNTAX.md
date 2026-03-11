# Nex Syntax on a Postcard

## Comments

```nex
-- This is a comment. The computer ignores it.
-- Use comments to explain your code to other humans!
```

## Values and Variables

```nex
let name: String := "Alice"      -- a piece of text (String)
let age: Integer := 10           -- a whole number (Integer)
let height: Real := 4.5          -- a decimal number (Real)
let likes_cats: Boolean := true  -- true or false (Boolean)
```

## Printing

```nex
print("Hello, world!")
print(name)
print("I am " + age + " years old")
```

## Math

```nex
let sum: Integer := 3 + 4       -- 7
let diff: Integer := 10 - 3     -- 7
let product: Integer := 5 * 6   -- 30
let quotient: Integer := 20 / 4 -- 5
let remainder: Integer := 10 % 3 -- 1
let power: Integer := 2 ^ 8     -- 256
```

## Comparing Things

```nex
x = y                            -- equal?
x /= y                           -- not equal?
x < y    x <= y                  -- less than? less or equal?
x > y    x >= y                  -- greater than? greater or equal?
x and y                          -- both true?
x or y                           -- at least one true?
not x                            -- flip true/false
```

## Choosing: if / then / else

```nex
if age >= 18 then
  print("You can vote!")
elseif age >= 13 then
  print("You are a teenager")
else
  print("You are a kid")
end
```

## Inline Choice: when

```nex
let label: String := when age >= 18 "adult" else "minor" end
```

## Loops: from / until

```nex
from let i: Integer := 1 until i > 5 do
  print(i)
  i := i + 1
end
-- prints 1 2 3 4 5
```

## Repeat

```nex
repeat 3 do
  print("hello!")
end
-- prints hello! three times
```

## Across

Iterate over any collection (Array, String, Map) using its cursor:

```nex
across [10, 20, 30] as x do
  print(x)
end
-- prints 10 20 30
```

Strings iterate by character:

```nex
across "abc" as ch do
  print(ch)
end
-- prints #a #b #c
```

Maps iterate as `[key, value]` pairs:

```nex
across {"name": "Alice", "age": "10"} as pair do
  print(pair.get(0))
end
```

## Functions

```nex
function greet(name: String)
do
  print("Hello, " + name + "!")
end

greet("Bob")
```

A function that gives back a value:

```nex
function double(n: Integer): Integer
do
  result := n * 2
end

print(double(5))                 -- 10
```

## Arrays

```nex
let colors: Array [String] := ["red", "green", "blue"]
print(colors.get(0))            -- "red"
colors.add("yellow")            -- add to the end
print(colors.length)            -- 4
```

## Maps

```nex
let pet: Map [String, String] := {"name": "Max", "kind": "dog"}
print(pet.get("name"))            -- "Max"
pet.put("kind", "cat")            -- update a value
```

## Sets

Use a set when you want an unordered collection of distinct values.

```nex
let evens: Set[Integer] := #{0, 2, 4}
print(evens.contains(2))          -- true
```

An empty set uses the explicit set literal syntax:

```nex
let empty: Set[Integer] := #{}
```

You can also build a set from an array. Duplicate elements are removed:

```nex
let numbers: Set[Integer] := create Set[Integer].from_array([1, 2, 2, 3])
print(numbers.size)               -- 3
```

Sets support the usual set operations:

```nex
let a: Set[Integer] := #{1, 2, 3}
let b: Set[Integer] := #{3, 4}

print(a.union(b))                 -- #{1, 2, 3, 4}
print(a.intersection(b))          -- #{3}
print(a.difference(b))            -- #{1, 2}
```

## Concurrency: spawn, Task, and Channel

Use `spawn` to start a lightweight task:

```nex
let t: Task[Integer] := spawn do
  result := 1 + 2
end

print(t.await)                    -- 3
print(t.is_done)                  -- true (after completion)
```

If the spawn body does not assign `result`, the type is plain `Task`:

```nex
let t: Task := spawn do
  print("background work")
end
```

Task operations:
- `await` waits until the task finishes
- `await(ms)` waits up to `ms` milliseconds and returns `nil` on timeout
- `cancel` requests task cancellation and returns `true` if the task was cancelled before finishing
- `is_done` reports whether the task has finished
- `is_cancelled` reports whether the task was cancelled

Use `Channel[T]` to communicate between tasks:

```nex
let ch: Channel[Integer] := create Channel[Integer]

spawn do
  ch.send(42)
end

print(ch.receive)                 -- 42
ch.close
print(ch.is_closed)               -- true
```

Use `.with_capacity(n)` for buffered channels:

```nex
let ch: Channel[Integer] := create Channel[Integer].with_capacity(2)
ch.send(10)
ch.send(20)
print(ch.size)                    -- 2
print(ch.capacity)                -- 2
```

Channel operations:
- `send(value)` blocks until accepted
- `send(value, ms)` waits up to `ms` milliseconds and returns `true` on success, `false` on timeout
- `receive` blocks until a value is available
- `receive(ms)` waits up to `ms` milliseconds and returns `nil` on timeout
- `try_send(value)` returns `true` if the send succeeds immediately, otherwise `false`
- `try_receive` returns a value if one is immediately available, otherwise `nil`
- `close` prevents future sends; buffered values may still be received
- `is_closed`, `size`, and `capacity` report channel state

Use `select` to wait on multiple channel operations:

```nex
select
  when jobs.receive as job then
    print(job)
  when control.receive as signal then
    print(signal)
  timeout 1000 then
    print("timed out")
  else
    print("idle")
end
```

`select` probes its clauses using `try_send` / `try_receive`. If no clause is ready and there is no `else`, it waits until one becomes ready.

JavaScript target note:
- generated JavaScript uses Promise-based semantics
- Nex source syntax stays the same
- `spawn` lowers to async task code
- `Task.await`, `Channel.send`, and `Channel.receive` lower to `await ...` in generated JavaScript

## Classes

A class bundles data and actions together:

```nex
class Pet
  feature
    name: String
    sound: String

  create
    make(name: String, sound: String) do
	  this.name := name
	  this.sound := sound
	end

  feature
    speak do
       print(name + " says " + sound)
     end
end
```

## Creating Objects

```nex
let cat: Pet := create Pet.make("Mimi", "meow")
cat.speak                        -- "Mimi says meow"
```

## Constructors

A constructor sets up an object when it is created:

```nex
class Circle
  create
    make(r: Real) do
      radius := r
    end

  feature
    radius: Real

    area(): Real do
      result := 3.14159 * (radius ^ 2)
    end
end

let c: Circle := create Circle.make(5.0)
print(c.area)                    -- 78.53975
```

## Inheritance

A class can build on another class:

```nex
class Animal
  feature
    name: String
    speak do print(name) end
  create
    with_name(n: String) do name := n end
end

class Dog
  inherit Animal
  feature
    speak do
      print(name + " says Woof!")
    end
  create
    with_name(n: String) do Animal.with_name(n) end
end
```

## Design by Contract

Tell Nex what must be true before, after, and always:

```nex
class Wallet
  feature
    money: Real

    spend(amount: Real)
      require                    -- must be true BEFORE
        enough: amount <= money
      do
        money := money - amount
      ensure                     -- must be true AFTER
        less: money = old money - amount
      end

  invariant                      -- must ALWAYS be true
    not_negative: money >= 0.0
end
```

## Error Handling

```nex
let attempts: Integer := 0
do
  attempts := attempts + 1
  if attempts < 3 then
    raise "not ready yet"
  end
  print("done on attempt " + attempts)
rescue
  print("failed, trying again...")
  retry                        -- jump back to do and try again
end
```

`raise` throws an error. `rescue` catches it (the value is in `exception`).
`retry` jumps back to the `do` block and runs it again from the top.

## Case / Of

```nex
case direction of
  "up"    then print("going up")
  "down"  then print("going down")
  else         print("standing still")
end
```

## Scoped Blocks

```nex
let x: Integer := 10
do
  let x: Integer := 99          -- shadows the outer x
  print(x)                      -- 99
end
print(x)                        -- 10
```

## Type Conversion: convert

```nex
convert <value> to <name>:<Type>
```

- Returns `true` if conversion succeeds, else `false`.
- On success, `<name>` is bound to the converted value.
- On failure, `<name>` is bound to `nil`.
- Conversion follows Java-style related-type rules:
  `<Type>` must be a supertype or subtype of the runtime type of `<value>`.

```nex
do
  convert vehicle_1 to my_car:Car
  -- my_car is visible in this block
end
```

```nex
if convert vehicle_1 to my_car:Car then
  my_car.sound_horn
end
```

## Anonymous Functions

```nex
let add: Function := fn (a, b: Integer): Integer do result := a + b end
print(add(3, 4))                -- 7
```

## Generics

```nex
class Box [T]
  feature
    value: T
  create
    make(v: T) do value := v end
end

let b: Box [Integer] := create Box[Integer].make(42)
b.value -- 42
```

## Assignment

```nex
x := 10                         -- set an existing variable
let y: Integer := 20            -- create a new variable
this.name := "Nex"              -- set a field inside a method
```

## That's it!

Nex reads like English: `if...then...end`, `from...until...do...end`, `repeat...do...end`, `across...as...do...end`, `class...feature...end`.
Write what you mean, and Nex will check that you mean what you write.
