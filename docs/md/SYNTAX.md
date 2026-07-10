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

Real literals must include at least one digit after the decimal point. Valid
examples: `4.5`, `10.0`, `.5`, `12.0e-3`. Invalid examples: `10.`, `12.e-3`.

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

Integer literals can also be written with explicit bases:

```nex
let flags: Integer := 0b1111_0000
let perms: Integer := 0o755
let color: Integer := 0xFF_AA_33
```

Use lowercase prefixes `0b`, `0o`, and `0x`. `_` may be used as a digit separator.

## Comparing Things

```nex
x = y                            -- equal?
x /= y                           -- not equal?
x == y                           -- same object?
x != y                           -- different object?
x < y    x <= y                  -- less than? less or equal?
x > y    x >= y                  -- greater than? greater or equal?
x and y                          -- both true?
x or y                           -- at least one true?
not x                            -- flip true/false
```

## Comparison

Nex has two kinds of equality:

- `=` and `/=` compare by value
- `==` and `!=` compare by identity

Value equality checks whether two values have the same contents:

```nex
print([1, 2] = [1, 2])           -- true
print([1, 2] /= [1, 2])          -- false
print("abc" = "abc")             -- true
```

For objects, `=`/`/=` use the class's `equals` method. By default that is a
structural, field-by-field comparison, but a class may override `equals` (and the
matching `hash`) to define its own value equality. `==`/`!=` ignore any override
and always compare object identity.

Identity equality checks whether two variables refer to the same runtime object:

```nex
let a := [1, 2]
let b := a
let c := [1, 2]

print(a == b)                    -- true
print(a == c)                    -- false
print(a != c)                    -- true
```

For scalar values like numbers, booleans, characters, strings, and `nil`, `==`
and `!=` compare the values directly:

```nex
print(1 == 1)                    -- true
print("x" != "y")                -- true
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
let label: String := when age >= 18 then "adult" else "minor" end
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
function greet(name: String) do
  print("Hello, " + name + "!")
end

greet("Bob")
```

A function that gives back a value:

```nex
function double(n: Integer): Integer do
  result := n * 2
end

print(double(5))                 -- 10
```

For mutually recursive functions, declare the signatures first and define the
bodies afterwards:

```nex
declare function is_even(n: Integer): Boolean
declare function is_odd(n: Integer): Boolean

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

The later definition must match the earlier declaration exactly.

### No default arguments

Nex has no default parameter values. A call must pass exactly as many
arguments as the function (or method) declares.

Free functions cannot be overloaded either: each function name must be
unique, so you cannot define two `greet` functions that differ only in the
number of parameters. If you need variants, give them distinct names.

Class **methods**, however, *can* be overloaded by arity (see
[Optional arguments via method overloading](#optional-arguments-via-method-overloading)
under Classes), which is the idiomatic way to get optional-argument
ergonomics in Nex.

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
- `await_any([t1, t2, ...])` waits for the first task to finish and returns its result
- `await_all([t1, t2, ...])` waits for all tasks and returns an array of results

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

Use `select` to wait on multiple channel operations or completed tasks:

```nex
select
  when jobs.receive as job then
    print(job)
  when worker.await as value then
    print(value)
  when control.receive as signal then
    print(signal)
  timeout 1000 then
    print("timed out")
  else
    print("idle")
end
```

`select` probes its clauses using `try_send` / `try_receive` for channels and `is_done` for tasks. Task clauses must use `Task.await`; they fire only when the task has already completed. If no clause is ready and there is no `else`, `select` waits until one becomes ready.

JavaScript target note:
- generated JavaScript uses Promise-based semantics
- Nex source syntax stays the same
- `spawn` lowers to async task code
- `Task.await`, `Channel.send`, and `Channel.receive` lower to `await ...` in generated JavaScript

For full concurrency semantics and runtime details, see [CONCURRENCY.md](CONCURRENCY.md).

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

## Optional arguments via method overloading

Nex has no default parameter values, but a class may define several methods
with the **same name and different numbers of parameters**. The right one is
chosen by the number of arguments at the call site. Have the shorter version
forward to the longer one to supply a default:

```nex
class Greeter
  feature
    greet(name: String): String do
      result := greet(name, "!")        -- forward with a chosen default
    end

    greet(name: String, punct: String): String do
      result := "Hello, " + name + punct
    end
end

let g: Greeter := create Greeter
print(g.greet("Ann"))              -- "Hello, Ann!"
print(g.greet("Bob", "."))         -- "Hello, Bob."
```

Overloads are distinguished only by the *number* of arguments, not their
types, so you cannot have two same-name methods with the same arity that
differ only in parameter type. (Free functions cannot be overloaded at all —
see [Functions](#functions).)

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

## Once Fields

A field declared with `once` can be set in a constructor but never reassigned afterward.
The typechecker enforces this at compile time; the interpreter enforces it at runtime.

```nex
class Point
  feature
    once x: Integer
    once y: Integer
  create
    make(px: Integer, py: Integer) do
      x := px
      y := py
    end
end

let p: Point := create Point.make(3, 7)
-- p.x and p.y are now permanently 3 and 7
```

Attempting to assign a `once` field outside a constructor is a compile-time error:

```nex
class Box
  feature
    once value: Integer
  create
    make(v: Integer) do value := v end
  feature
    overwrite(v: Integer) do
      value := v     -- error: 'value' is a once field
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

## Sealed Classes

A `sealed` modifier closes a class hierarchy. Only classes defined together with the sealed class can extend it. The typechecker knows the complete set of subclasses and can verify that every variant is handled.

A sealed class must be declared `deferred` — it cannot be instantiated directly, and the typechecker rejects a `sealed` class that is not also `deferred`. (Otherwise a bare instance of the parent would be a runtime value that an exhaustive `match` over its subclasses does not cover.)

```nex
sealed deferred class Result
end

class Ok
  inherit Result
  feature value: Integer
  create make(v: Integer) do value := v end
end

class Err
  inherit Result
  feature msg: String
  create make(m: String) do msg := m end
end
```

## Sum Types (`union`)

Writing a sealed hierarchy by hand is verbose: a `sealed deferred class` parent plus a full `class … inherit … feature … create make` for every variant. The `union` form is concise sugar for exactly that shape.

```nex
union Order
  Draft
  Placed(id: String, total: Real)
  Shipped(tracking: String, at: Date)
end
```

This desugars to a `sealed deferred class Order` parent and one ordinary class per variant. Each variant's payload becomes `feature` fields plus an auto-generated `make` constructor, so construction and matching are the same as for hand-written sealed classes:

```nex
let o: Order := create Placed.make("A-100", 42.0)

match o of
  when Draft   as d then print("draft")
  when Placed  as p then print(p.id)       -- payloads are ordinary fields
  when Shipped as s then print(s.tracking)
end
```

- A variant with no payload (`Draft`) still gets a nullary `make`, so `create Draft.make()` works.
- Generic parameters carry through to every variant: `union Result[T]` gives `Ok`/`Err` that inherit `Result[T]`.
- Because a `union` *is* a sealed hierarchy after desugaring, `match` exhaustiveness is checked exactly as in the previous section — a missing variant is a compile-time error.

The `union` form is deliberately data-only. When a variant needs its own contracts, invariants, or methods, write the explicit `sealed deferred class` form above; both compile to the same thing.

## Standard Result and Option

The standard library provides two sealed sum types for error handling and
optional values, imported with `intern`:

```nex
intern data/Result
intern data/Option

let r: Result[Integer, String] := create Ok[Integer, String].make(10)
let doubled: Result[Integer, String] :=
  result_map(r, fn (x: Integer): Integer do result := x * 2 end)
print(doubled.unwrap_or(0))          -- 20

let o: Option[Integer] := create Some[Integer].make(7)
print(o.get_or(0))                   -- 7
```

- `Result[T, E]` is `Ok(value: T)` or `Err(error: E)`; `Option[T]` is
  `Some(value: T)` or `None`.
- Query/unwrap are methods: `is_ok()`, `is_err()`, `unwrap_or(fallback)` on
  `Result`; `is_some()`, `is_none()`, `get_or(fallback)` on `Option`.
- Transforming combinators are free functions (they introduce a fresh type
  parameter): `result_map`, `result_and_then`, `result_map_err`; `option_map`,
  `option_and_then`, `option_filter`. `and_then` is the bind that chains fallible
  steps and short-circuits on the first `Err`/`None`.
- Construct with explicit type arguments (`create Ok[Integer, String].make(…)`),
  since Nex does not yet infer generic arguments at construction.

## Match Statement

`match` dispatches on the runtime type of an expression. Used with a sealed parent class it becomes an exhaustive type switch — every variant must be handled or the typechecker rejects the program:

```nex
match r of
  when Ok as ok then
    print(ok.value)
  when Err as err then
    print(err.msg)
end
```

- Each `when ClassName as var then` clause binds `var` to the matched object, typed as `ClassName`.
- The typechecker verifies all sealed subclasses are covered. A missing variant is a compile-time error.
- An `else` branch covers remaining cases and suppresses the exhaustiveness check:

```nex
match r of
  when Ok as ok then
    print(ok.value)
  else
    print("not ok")
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
let add: Function(a: Integer, b: Integer): Integer :=
  fn (a, b: Integer): Integer do result := a + b end
print(add(3, 4))                -- 7
```

Bare `Function` is still valid and compatible with any typed function value:

```nex
let f: Function := fn (n: Integer): Integer do result := n * 2 end
```

## Type Aliases

`declare type` binds a name to any type expression — most commonly used to
name a function signature for reuse:

```nex
declare type Transformer = Function(n: Integer): Integer

let double: Transformer := fn (n: Integer): Integer do result := n * 2 end
let square: Transformer := fn (n: Integer): Integer do result := n * n end
```

Any type can be aliased, not just function types:

```nex
declare type Matrix = Array[Array[Real]]
```

## Refinement Types

A `declare type` with a `where` clause is a **refinement type**: an existing type
narrowed by a boolean predicate, without declaring a class.

```nex
declare type Quantity   = Integer where n: n > 0
declare type Percentage = Real    where p: p >= 0.0 and p <= 100.0
declare type NonEmpty   = String  where s: s.length() > 0
```

`where n: <expr>` binds the value under test to `n` (any name) and gives a
boolean predicate over it, mirroring the `label: condition` shape of contracts.

A refinement is its base type with a checked constraint — **not** a class: no
fields, no constructor, no wrapper. A `Quantity` *is* an `Integer` at runtime, so
it interoperates freely with its base:

```nex
let q: Quantity := 5          -- checked: raises if the value is not > 0
let total: Integer := q + 10  -- free: a Quantity is an Integer
```

The predicate is enforced wherever a value is **narrowed** into the refinement —
a `let` of that type, a parameter of that type (checked at the call boundary),
and a return of that type. Widening (`Quantity` → `Integer`) is always free.

```nex
function debit(amount: Quantity): Integer do   -- amount checked on entry
  result := amount
end
```

Notes and current limits:

- Operations do not propagate the refinement: `q + q` is an `Integer`. Flow the
  result back into a refinement-typed binding to re-check it.
- Fields, `convert` targets, `?R` detachable bindings, and `distinct` nominal
  newtypes are not yet checked/supported — use a class where you need those.
- The predicate should be side-effect free.

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

Generic functions use the same bracket syntax after the function name:

```nex
function first[T](values: Array[T]): T do
  result := values.get(0)
end

print(first([10, 20, 30]))      -- 10
print(first(["a", "b", "c"]))   -- "a"
```

Multiple generic parameters are allowed:

```nex
function pick_or_default[K, V](present: Boolean, value: V, fallback: V): V do
  result := when present then value else fallback end
end
```

Anonymous functions can also declare generic parameters explicitly:

```nex
let id: Function := fn[T](x: T): T do
  result := x
end

print(id(42))                   -- 42
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
