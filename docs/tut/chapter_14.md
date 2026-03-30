# Inheritance and Polymorphism

Chapter 13 established that a class should model one concept well. Sometimes concepts form families — a `Savings_Account` and a `Current_Account` are both bank accounts; a `Circle` and a `Rectangle` are both shapes. These families share common behaviour while differing in specifics. Inheritance is the mechanism for expressing that relationship in code.


## What Inheritance Is

Inheritance allows one class — the *subclass* — to extend another — the *superclass*. The subclass inherits all the fields and methods of the superclass and can add new ones or override existing ones.

The relationship is *is-a*: a `Circle` is a `Shape`. This is not just a programming convenience — it reflects a genuine conceptual relationship. If the is-a relationship does not hold, inheritance is probably the wrong tool.

In Nex, inheritance is declared with `inherit`:

```
nex> class Shape
       create
         make(c: String) do
           colour := c
         end
       feature
         colour: String
         describe(): String do
           result := "A " + colour + " shape"
         end
     end

nex> class Circle inherit Shape
       create
         make(c: String, r: Real) do
           Shape.make(c)
           radius := r
         end
       feature
         radius: Real
         area(): Real do
           result := 3.14159 * radius * radius
         end
         describe(): String do
           result := "A " + colour + " circle with radius " + radius.to_string
         end
     end

nex> class Rectangle inherit Shape
       create
         make(c: String, w, h: Real) do
           Shape.make(c)
           width := w
           height := h
         end
       feature
         width: Real
         height: Real
         area(): Real do
           result := width * height
         end
         describe(): String do
           result := "A " + colour + " rectangle (" + width.to_string + " x " + height.to_string + ")"
         end
     end
```

Both `Circle` and `Rectangle` inherit the `colour` field from `Shape`. Each has its own additional fields and overrides `describe`.

Public class constants are inherited as well. If a parent class defines:

```nex
class Shape
  feature
    DEFAULT_COLOUR = "black"
end
```

then child classes may use `DEFAULT_COLOUR` directly inside their own features, and external code may still refer to it with a class name such as `Shape.DEFAULT_COLOUR`.

This is useful for shared limits, default labels, configuration values, and other facts that belong to the class definition rather than to each object.



## The `super-class` Calls

When a subclass constructor runs, it must also initialise the fields inherited from the superclass. The `SuperClass.constructor_name(   )` call delegates to the superclass constructor:

```
make(c: String, r: Real) do
  Shape.make(c)   -- initialises colour in Shape
  radius := r     -- initialises radius in Circle
end
```

`Shape.make(c)` runs `Shape`'s `make` constructor, setting `colour := c`. Then the `Circle` constructor sets `radius := r`. Both fields are properly initialised.

`super` can also call an overridden superclass method from within an override:

```
describe(): String do
  result := Shape.describe + ", area: " + area.to_string
end
```

This builds on `Shape`'s `describe` rather than duplicating it.



## Overriding Methods

When a subclass defines a feature with the same name as a superclass feature, the subclass version *overrides* it. Calling `describe` on a `Circle` runs `Circle`'s version, not `Shape`'s:

```
nex> let c := create Circle.make("red", 5.0)
nex> c.describe
"A red circle with radius 5.0"

nex> let r := create Rectangle.make("blue", 4.0, 3.0)
nex> r.describe
"A blue rectangle (4.0 x 3.0)"
```



## Polymorphism

The most powerful consequence of inheritance is *polymorphism*: the ability to treat objects of different subclasses through a common superclass type.

An `Array[Shape]` can hold circles, rectangles, or any other shape subclass:

```
nex> let shapes: Array[Shape] := []
nex> shapes.add(create Circle.make("red", 5.0))
nex> shapes.add(create Rectangle.make("blue", 4.0, 3.0))
nex> shapes.add(create Circle.make("green", 2.0))

nex> across shapes as s do
       print(s.describe)
    end
"A red circle with radius 5.0"
"A blue rectangle (4.0 x 3.0)"
"A green circle with radius 2.0"
```

When `s.describe` is called, Nex dispatches to the correct `describe` for the actual runtime type of each object. This is *dynamic dispatch*: the method called is determined by the object's type at runtime, not by the declared type of the variable.

Polymorphism means code written against the superclass type works correctly with any subclass — including subclasses not yet written. Adding a `Triangle` that inherits `Shape` and overrides `describe` would work with the loop above without changing it.



## When Inheritance Is the Right Tool

Inheritance is appropriate when:

**The is-a relationship is genuine.** A `Circle` is a `Shape`. A `Savings_Account` is a `Bank_Account`. The subclass is a more specific version of the superclass concept.

**The subclass shares and specialises superclass behaviour.** It uses the inherited methods and overrides some to provide specialised behaviour. It does not ignore or neutralise what it inherits.

**You need polymorphism.** You want to write code against the superclass type and have it work correctly on any subclass instance.

Inheritance is not appropriate when:

**The relationship is has-a, not is-a.** A `Car` has an `Engine` — it does not extend `Engine`. Using inheritance to share code between conceptually unrelated classes produces fragile designs.

**You only want to reuse code.** If the goal is to avoid duplicating a few methods, *composition* — giving a class a field of another class type and delegating to its methods — is usually better.

**The subclass needs to override most of what it inherits.** A subclass that overrides everything is not a specialisation — it is a different class wearing a misleading name.



## Feature Override

A superclass can provide a default implementation for a method that subclasses override with specialised behaviour. This is useful when the superclass defines a general structure and the subclass fills in the details.

Every shape has an area, but "the area of a shape" has no general formula. The superclass provides a safe default:

```
nex> class Shape
       create
         make(c: String) do
           colour := c
         end
       feature
         colour: String
         area(): Real do result := 0.0 end
         describe(): String do
           result := "A " + colour + " shape with area " + area.to_string
         end
     end
```

`area` returns `0.0` by default. The `describe` method in `Shape` calls `area`, and subclasses override `area` with their own formula:

```
nex> class Circle inherit Shape
       create
         make(c: String, r: Real) do
           Shape.make(c)
           radius := r
         end
       feature
         radius: Real
         area(): Real do
           result := 3.14159 * radius * radius
         end
     end

nex> let c := create Circle.make("red", 5.0)
nex> c.describe
"A red shape with area 78.53975"
```

`describe` in `Shape` calls `area` — which runs `Circle`'s `area` because `c` is a `Circle`. The superclass defines the structure; the subclass fills in the detail. This is the *template method* pattern: a superclass method calls an overridable method whose behaviour varies by subclass.



## A Worked Example: An Account Hierarchy

```
nex> class Account
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
         withdraw(amount: Real): Boolean do
           if amount <= balance then
             balance := balance - amount
             result := true
           else
             result := false
           end
         end
         set_balance(new_balance: Real) do
           balance := new_balance
         end
         get_balance(): Real do
           result := balance
         end
         describe(): String do
           result := owner + ": " + balance.to_string
         end
     end

nex> class Savings_Account inherit Account
       create
         make(name: String, initial, rate: Real) do
           Account.make(name, initial)
           interest_rate := rate
         end
       feature
         interest_rate: Real
         apply_interest() do
           Account.set_balance(balance + balance * interest_rate)
         end
         describe(): String do
           result := Account.describe + " (savings, rate: " + interest_rate.to_string + ")"
         end
     end

nex> class Overdraft_Account inherit Account
       create
         make(name: String, initial, limit: Real) do
           Account.make(name, initial)
           overdraft_limit := limit
         end
       feature
         overdraft_limit: Real
         withdraw(amount: Real): Boolean do
           if balance - amount >= -overdraft_limit then
             Account.set_balance(balance - amount)
             result := true
           else
             result := false
           end
         end
         describe(): String do
           result := Account.describe + " (overdraft limit: " + overdraft_limit.to_string + ")"
         end
     end
```

```
nex> let accounts: Array[Account] := []
nex> accounts.add(create Account.make("Alice", 500.0))
nex> accounts.add(create Savings_Account.make("Bob", 1000.0, 0.02))
nex> accounts.add(create Overdraft_Account.make("Carol", 200.0, 500.0))

nex> across accounts as acc do
       print(acc.describe)
    end
"Alice: 500.0"
"Bob: 1000.0 (savings, rate: 0.02)"
"Carol: 200.0 (overdraft limit: 500.0)"
```

Each account type inherits `deposit` and `get_balance` from `Account`. `Savings_Account` adds `apply_interest`. `Overdraft_Account` overrides `withdraw` to permit negative balances within the limit. Both override `describe` using `Account.describe` to build on the base description. The array holds all three as `Account`; `describe` dispatches polymorphically.



## Summary

- `class SubClass inherit SuperClass` declares the relationship; the subclass inherits all fields and methods
- `SuperClass.constructor_name(   )` calls the superclass constructor from the subclass constructor
- `SuperClass.method_name(   )` calls an overridden superclass method from within an override
- Overriding replaces a superclass method with a subclass-specific version; dynamic dispatch calls the correct version at runtime
- Polymorphism allows objects of different subclasses to be treated through a common superclass type
- The template method pattern: a superclass method calls an overridable method; the superclass defines structure, subclasses fill in details via override
- Overriding methods must honour the superclass contract; preconditions should not be strengthened, postconditions should not be weakened
- Use inheritance for genuine is-a relationships and polymorphism; use composition for has-a relationships or code reuse without a conceptual relationship



## Exercises

**1.** Define a class `Animal` with a field `name: String`, a constructor `make`, and a method `sound(): String` that returns `"..."` by default. Define subclasses `Dog`, `Cat`, and `Cow` each overriding `sound`. Create an `Array[Animal]`, add one of each, and print each animal's name and sound.

**2.** Add a method `perimeter(): Real` to the `Shape` class with a default return of `0.0`. Override it in `Circle` (`2 * 3.14159 * radius`) and `Rectangle` (`2 * (width + height)`). Update `describe` in `Shape` to report both area and perimeter.

**3.** The `withdraw` method in `Overdraft_Account` overrides the one in `Account`. Does the override honour the Liskov Substitution Principle? Does it accept the same inputs? Does it make the same kind of promise — returning `true` on success and `false` on failure? What does a caller of `Account` need to know about `Overdraft_Account.withdraw`?

**4.** Define a class `Vehicle` with fields `make: String` and `speed: Real`, and methods `fuel_type(): String` and `max_speed(): Real` with sensible defaults. Define `Electric_Car` and `Petrol_Car` overriding those methods. Add `can_reach(distance, fuel: Real): Boolean` to each — `Petrol_Car` uses 10 litres per 100 km; `Electric_Car` uses 20 kWh per 100 km.

**5.\*** Define a `Logger` base class with a method `log(message: String)` that prints with a prefix. Define `File_Logger` that also appends to a `log_history: String` field, and `Silent_Logger` that discards all messages. Create an `Array[Logger]` with one of each and call `log("test")` on each. What does this demonstrate about polymorphism and swappable implementations?
