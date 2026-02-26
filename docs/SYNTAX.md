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
print(colors.at(0))              -- "red"
colors.push("yellow")            -- add to the end
print(colors.size)               -- 4
```

## Maps

```nex
let pet: Map [String, String] := {name: "Max", kind: "dog"}
print(pet.at("name"))            -- "Max"
pet.set("kind", "cat")           -- update a value
```

## Classes

A class bundles data and actions together:

```nex
class Pet
  feature
    name: String
    sound: String

    speak do
      print(name + " says " + sound)
    end
end
```

## Creating Objects

```nex
let cat: Pet := create Pet
cat.name := "Mimi"
cat.sound := "meow"
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
      result := 3.14159 * radius ^ 2
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
end

class Dog
  inherit Animal
  feature
    speak do
      print(name + " says Woof!")
    end
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
  retry                          -- jump back to do and try again
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

## Anonymous Functions

```nex
let add: fn := fn (a, b: Integer): Integer do result := a + b end
print(add(3, 4))                -- 7
```

## Generics

```nex
class Box [T]
  feature
    value: T
end

let b: Box [Integer] := create Box [Integer]
b.value := 42
```

## Assignment

```nex
x := 10                         -- set an existing variable
let y: Integer := 20            -- create a new variable
this.name := "Nex"              -- set a field inside a method
```

## That's it!

Nex reads like English: `if...then...end`, `from...until...do...end`, `class...feature...end`.
Write what you mean, and Nex will check that you mean what you write.
