# Classes

Every value encountered so far — integers, strings, arrays, maps — has a type, and that type determines what operations are available on the value. `"hello".length` works because strings have a `length` method. `[1, 2, 3].sort` works because arrays have a `sort` method. The type is not just a label; it is a bundle of data and behaviour.

Classes let you define your own types with the same structure: a bundle of data (fields) and behaviour (methods). Once a class is defined, you can create as many instances of it as you need, each carrying its own data, all sharing the same methods.


## Defining a Class

A class definition in Nex has two blocks: a `create` block containing constructors, and a `feature` block containing fields and methods:

```
nex> class Point
       create
         make(px, py: Real) do
           x := px
           y := py
         end
       feature
         x: Real
         y: Real
         distance_from_origin(): Real do
           result := ((x * x) + (y * y)) ^ 0.5
         end
     end
Class(es) registered: Point
```

This defines a class `Point` with a constructor named `make`, two fields `x` and `y`, and one method `distance_from_origin`.

Fields are declared inside `feature` as `name: Type` — no keyword needed. Methods are declared as `name(params): ReturnType do ... end`, also inside `feature`. The distinction between fields and methods is structural: fields have no parameter list or body; methods do.



## Creating Objects

Objects are created with `create`, naming both the class and the constructor:

```
nex> let p := create Point.make(3.0, 4.0)
nex> print(p.x)
3.0

nex> print(p.y)
4.0

nex> print(p.distance_from_origin)
5.0
```

`create Point.make(3.0, 4.0)` runs the `make` constructor with the given arguments, initialising both fields. The resulting object is assigned to `p`.

The `create` keyword appeared in Chapter 2 as `let con := create Console`. Now the mechanism is fully visible: `create` allocates a new instance and runs the named constructor.



## Constructors

A constructor is a named entry in the `create` block. Its body initialises the object's fields. A class may have more than one constructor with different names:

```
nex> class Point
       create
         origin() do
           x := 0.0
           y := 0.0
         end
         make(px, py: Real) do
           x := px
           y := py
         end
       feature
         x: Real
         y: Real
         distance_from_origin(): Real do
           result := ((x * x) + (y * y)) ^ 0.5
         end
     end
Class(es) registered: Point

nex> let p1 := create Point.origin
nex> print(p1.x)
0.0

nex> let p2 := create Point.make(3.0, 4.0)
nex> print(p2.distance_from_origin)
5.0
```

Named constructors communicate intent. `create Point.origin` clearly creates a point at the origin; `create Point.make(3.0, 4.0)` creates a point at specific coordinates. The name is part of the interface.



## Fields

Fields are the data a class carries. Each instance gets its own independent copy of every field:

```
nex> let p1 := create Point.make(1.0, 2.0)
nex> let p2 := create Point.make(4.0, 6.0)

nex> print(p1.x)
1.0

nex> print(p2.x)
4.0
```

Fields are read using dot notation: `obj.field_name`. Assigning to a field from outside the class is not permitted — fields are private to the class. If a field needs to change, the class provides a method:

```
nex> class Point
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
         distance_from_origin(): Real do
           result := ((x * x) + (y * y)) ^ 0.5
         end
     end
Class(es) registered: Point

nex> let p := create Point.make(1.0, 2.0)
nex> p.move(2.0, 2.0)
nex> print(p.x)
3.0
```

Classes can also define class-level constants directly in the `feature` block:

```
nex> class Layout
       feature
         HELLO: String = "hello"
         MAX_WIDTH = 450
         widened(): Integer do
           result := MAX_WIDTH + 10
         end
     end
Class(es) registered: Layout

nex> let layout := create Layout
nex> print(Layout.MAX_WIDTH)
450
nex> print(layout.widened)
460
```

`HELLO` and `MAX_WIDTH` are not per-object fields. They belong to the class itself. Their meaning is: these features are always equal to those values.

This is the Nex equivalent of a Java `static final` member.

The form is:

```nex
NAME: Type = expression
NAME = expression
```

If the type is omitted, Nex infers it from the value. `MAX_WIDTH = 450` is therefore an `Integer`.

Class constants are accessed from outside the class with the class name:

```nex
print(Layout.MAX_WIDTH)
```

Inside the class, they can be used directly by name:

```nex
widened(): Integer do
  result := MAX_WIDTH + 10
end
```

Because constants are not object state, they are not initialised by constructors and cannot be assigned to later.



## Detachable Fields

In strict type-checking mode, Nex requires that every field holding a non-basic type — any class, array, map, or other composite — must be initialised in the constructor. The basic types (`Integer`, `Real`, `Boolean`, `String`, `Char`) have well-defined defaults and can be left uninitialised. Everything else must be explicitly set.

Sometimes a field genuinely might not have a value at construction time. For these cases, use a *detachable* type, written with a leading `?`:

```
nex> class Person
       create
         make(n: String) do
           name := n
           email := nil
         end
       feature
         name: String
         email: ?String
         set_email(addr: String) do
           email := addr
         end
         describe(): String do
           if email /= nil then
             result := name + " <" + email + ">"
           else
             result := name + " (no email)"
           end
         end
     end
Class(es) registered: Person

nex> let p := create Person.make("Ada")
nex> print(p.describe)
Ada (no email)

nex> p.set_email("ada@example.com")
nex> print(p.describe)
Ada <ada@example.com>
```

`email` is declared as `?String` — a detachable string that may hold a value or `nil`. The constructor initialises it to `nil` explicitly. The `describe` method checks for `nil` before using it.

The rule: use a plain type when the field must always have a value; use `?Type` when absence is a meaningful state for this field.



## Methods

Methods are features that compute or act. They are declared inside `feature` with a parameter list and body:

```
name(params): ReturnType do
     
end
```

For methods with no return value, the return type and colon are omitted:

```
name(params) do
     
end
```

Methods access the object's own fields directly by name. Here is a `BankAccount` class:

```
nex> class BankAccount
       create
         make(name: String, initial: Real) do
           owner := name
           balance := initial
         end
       feature
         owner: String
         balance: Real
         deposit(amount: Real) do
           balance := balance + amount
         end
         withdraw(amount: Real) do
           balance := balance - amount
         end
         get_balance(): Real do
           result := balance
         end
         describe(): String do
           result := owner + ": " + balance.to_string
         end
     end
Class(es) registered: BankAccount

nex> let account := create BankAccount.make("Alice", 1000.0)
nex> account.deposit(500.0)
nex> account.withdraw(200.0)
nex> print(account.describe)
Alice: 1300.0
```



## The `this` Reference

Inside a method, `this` refers to the object on which the method was called. Most of the time you do not need it — fields and other methods are accessible directly by name. `this` is needed when a parameter name shadows a field name:

```
nex> class Point
       create
         make(x, y: Real) do
           this.x := x
           this.y := y
         end
       feature
         x: Real
         y: Real
     end
```

Here the constructor parameters are also named `x` and `y`. Inside the constructor, bare `x` refers to the parameter; `this.x` refers to the field. Without `this`, the assignment `x := x` would assign the parameter to itself and leave the field uninitialised.

`this` is also used when an object needs to pass itself as an argument:

```
nex> class Point
       create
         make(px, py: Real) do
           x := px
           y := py
         end
       feature
         x: Real
         y: Real
         distance_to(other: Point): Real do
           let dx := this.x - other.x
           let dy := this.y - other.y
           result := ((dx * dx) + (dy * dy)) ^ 0.5
         end
     end
Class(es) registered: Point

nex> let p1 := create Point.make(0.0, 0.0)
nex> let p2 := create Point.make(3.0, 4.0)
nex> print(p1.distance_to(p2))
5.0
```

In `distance_to`, `this.x` and `this.y` refer to the fields of the object the method was called on (`p1`), while `other.x` and `other.y` refer to the argument (`p2`).



## Uniform Access

Field reads and method calls use identical syntax:

```
nex> print(p.x)                      -- reads a field
3.0

nex> print(p.distance_from_origin)   -- calls a method
5.0
```

Both use `obj.name` notation. The caller cannot tell — and does not need to tell — whether `name` is a stored field or a computed method. This is *uniform access*.

It matters because it means a class can change its internal representation without breaking calling code. Consider `Circle`:

```
nex> class Circle
       create
         make(r: Real) do
           radius := r
         end
       feature
         radius: Real
         diameter(): Real do
           result := radius * 2.0
         end
         area(): Real do
           result := 3.14159 * radius * radius
         end
     end
Class(es) registered: Circle

nex> let c := create Circle.make(5.0)
nex> print(c.radius)
5.0

nex> print(c.diameter)
10.0
```

`c.radius` reads a stored field. `c.diameter` calls a computation. Both look identical at the call site. If the implementation later changes — storing `diameter` directly and computing `radius` — no call site changes.



## A Worked Example: A Simple Stack

```
nex> class Stack
       create
         make() do
           items := []
         end
       feature
         items: Array[Integer]
         push(value: Integer) do
           items.add(value)
         end
         pop(): Integer do
           result := items.get(items.length - 1)
           items.remove(items.length - 1)
         end
         peek(): Integer do
           result := items.get(items.length - 1)
         end
         is_empty(): Boolean do
           result := items.is_empty
         end
         size(): Integer do
           result := items.length
         end
     end
Class(es) registered: Stack

nex> let s := create Stack.make
nex> s.push(10)
nex> s.push(20)
nex> s.push(30)
nex> print(s.peek)
30

nex> print(s.pop)
30

nex> print(s.size)
1
```

`Stack` wraps an `Array[Integer]` and exposes only push, pop, peek, and size. The array is an implementation detail; the four methods are the interface. This is the essential move that classes make: bundle data with its governing operations and present a clean surface to the outside world.



## Summary

- A class has a `create` block (constructors) and a `feature` block (fields and methods)
- Constructors are named; `create ClassName.constructor_name(args)` creates an instance
- Fields are `name: Type` inside `feature`; class constants use `NAME: Type = value` or `NAME = value`; methods are `name(params): ReturnType do ... end`
- Fields are private — external code reads them with `obj.field` but cannot assign; changes go through methods
- In strict mode, non-basic fields must be initialised in the constructor; use `?Type` for fields that may legitimately be `nil`
- `this` refers to the current object; needed when a parameter name shadows a field, or to pass the object as an argument
- Uniform access: field reads and method calls use identical `obj.name` syntax



## Exercises

**1.** Define a class `Rectangle` with fields `width` and `height` (both `Real`) and a constructor `make`. Add methods `area(): Real`, `perimeter(): Real`, and `is_square(): Boolean`. Test with a 4.0 x 6.0 rectangle and a 5.0 x 5.0 square.

**2.** Define a class `Temperature` with a single field `celsius: Real`. Add methods `fahrenheit(): Real` and `kelvin(): Real`. Add `describe(): String` returning `"freezing"`, `"cold"`, `"mild"`, or `"warm"`. All derived values should be computed methods, not stored fields.

**3.** Define a class `StringStack` that behaves like `Stack` but holds `String` values. Use it to reverse a string by pushing each character and popping them all off.

**4.** Define a class `Accumulator` with fields `total: Real` and `count: Integer` (both initialised to `0`). Add `add(value: Real)`, `reset()`, and `average(): Real`. State the precondition for `average` as a comment.

**5.\*** Define a class `Queue` supporting `enqueue(value: Integer)`, `dequeue(): Integer`, `front(): Integer`, `is_empty(): Boolean`, and `size(): Integer`, backed by an `Array[Integer]`. Enqueue 1 through 5, dequeue and print each, and verify first-in-first-out order.
