# Inheritance and Polymorphism

Chapter 13 established that a class should model one concept well. Sometimes concepts form families — a `SavingsAccount` and a `CurrentAccount` are both bank accounts; a `Circle` and a `Rectangle` are both shapes. These families share common behaviour while differing in specifics. Inheritance is the mechanism for expressing that relationship in code.


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
           super.make(c)
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
           super.make(c)
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



## The `super` Call

When a subclass constructor runs, it must also initialise the fields inherited from the superclass. The `super.constructor_name(   )` call delegates to the superclass constructor:

```
make(c: String, r: Real) do
  super.make(c)   -- initialises colour in Shape
  radius := r     -- initialises radius in Circle
end
```

`super.make(c)` runs `Shape`'s `make` constructor, setting `colour := c`. Then the `Circle` constructor sets `radius := r`. Both fields are properly initialised.

`super` can also call an overridden superclass method from within an override:

```
describe(): String do
  result := super.describe + ", area: " + area.to_string
end
```

This builds on `Shape`'s `describe` rather than duplicating it.



## Overriding Methods

When a subclass defines a feature with the same name as a superclass feature, the subclass version *overrides* it. Calling `describe` on a `Circle` runs `Circle`'s version, not `Shape`'s:

```
nex> let c := create Circle.make("red", 5.0)
nex> c.describe
A red circle with radius 5.0

nex> let r := create Rectangle.make("blue", 4.0, 3.0)
nex> r.describe
A blue rectangle (4.0 x 3.0)
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
A red circle with radius 5.0
A blue rectangle (4.0 x 3.0)
A green circle with radius 2.0
```

When `s.describe` is called, Nex dispatches to the correct `describe` for the actual runtime type of each object. This is *dynamic dispatch*: the method called is determined by the object's type at runtime, not by the declared type of the variable.

Polymorphism means code written against the superclass type works correctly with any subclass — including subclasses not yet written. Adding a `Triangle` that inherits `Shape` and overrides `describe` would work with the loop above without changing it.



## When Inheritance Is the Right Tool

Inheritance is appropriate when:

**The is-a relationship is genuine.** A `Circle` is a `Shape`. A `SavingsAccount` is a `BankAccount`. The subclass is a more specific version of the superclass concept.

**The subclass shares and specialises superclass behaviour.** It uses the inherited methods and overrides some to provide specialised behaviour. It does not ignore or neutralise what it inherits.

**You need polymorphism.** You want to write code against the superclass type and have it work correctly on any subclass instance.

Inheritance is not appropriate when:

**The relationship is has-a, not is-a.** A `Car` has an `Engine` — it does not extend `Engine`. Using inheritance to share code between conceptually unrelated classes produces fragile designs.

**You only want to reuse code.** If the goal is to avoid duplicating a few methods, *composition* — giving a class a field of another class type and delegating to its methods — is usually better.

**The subclass needs to override most of what it inherits.** A subclass that overrides everything is not a specialisation — it is a different class wearing a misleading name.



## Abstract Methods

Sometimes a superclass wants to declare the shape of an operation without providing an implementation, because there is no sensible default. Every shape has an area, but there is no general formula for "the area of a shape."

Nex allows declaring a method as `abstract`:

```
nex> class Shape
       create
         make(c: String) do
           colour := c
         end
       feature
         colour: String
         area(): Real is abstract
         describe(): String do
           result := "A " + colour + " shape with area " + area.to_string
         end
     end
```

`area` is declared abstract — no body, just a signature. Any concrete subclass must implement it. The `describe` method in `Shape` calls `area` safely, knowing the concrete subclass will supply the implementation.

```
nex> class Circle inherit Shape
       create
         make(c: String, r: Real) do
           super.make(c)
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
A red circle with area 78.53975
```

`describe` in `Shape` calls `area` — which runs `Circle`'s `area` because `c` is a `Circle`. The superclass defines the structure; the subclass fills in the detail. This pattern — a concrete superclass method calling an abstract method — is called the *template method* pattern.

A class with at least one abstract method cannot be instantiated directly. Only concrete subclasses that implement all abstract methods can be created.



## Inheritance and Contracts

When a subclass overrides a method, the override should honour the same contract as the superclass method: it should accept at least the same inputs and guarantee at least as much. This is the *Liskov Substitution Principle*: wherever a superclass instance is expected, a subclass instance should be usable without breaking anything.

In Nex, this idea is not only informal. Contract inheritance follows explicit rules.

### Precondition Inheritance

For an overridden feature, the effective precondition is:

`<base-feature-require> or <local-feature-require>`

Either side may be absent. The practical meaning is that a child class may *weaken* the precondition. It may accept everything the parent accepted, and possibly more.

For example:

```text
class Account
feature
  withdraw(amount: Real)
    require
      enough: amount <= balance
    do
      balance := balance - amount
    end
end

class Overdraft_Account
inherit Account
feature
  withdraw(amount: Real)
    require
      within_limit: amount <= balance + overdraft_limit
    do
      balance := balance - amount
    end
end
```

The effective precondition of `Overdraft_Account.withdraw` is:

`(amount <= balance) or (amount <= balance + overdraft_limit)`

So any call valid for `Account` remains valid for `Overdraft_Account`.

### Postcondition Inheritance

For an overridden feature, the effective postcondition is:

`<base-feature-ensure> and <local-feature-ensure>`

Either side may be absent. The practical meaning is that a child class may *strengthen* the postcondition, but it may not drop promises that the parent already made.

If a parent routine promises that `area >= 0.0`, then every child implementation must still guarantee `area >= 0.0`. A child may add a stronger fact, but not a weaker one.

### Invariant Inheritance

For a child class, the effective class invariant is:

`<base-invariants> and <local-class-invariants>`

`base-invariants` includes the invariants of all immediate parent classes, and those parent invariants already include their own inherited invariants recursively.

For example:

```text
class Account
invariant
  non_negative_balance: balance >= 0.0
end

class Savings_Account
inherit Account
invariant
  non_negative_rate: interest_rate >= 0.0
end
```

The effective invariant of `Savings_Account` is:

`(balance >= 0.0) and (interest_rate >= 0.0)`

This means subclass objects must satisfy everything required of parent objects, plus their own additional consistency rules.

### Multiple Inheritance

With multiple inheritance, Nex combines:

- inherited preconditions with `or`
- inherited postconditions with `and`
- inherited class invariants with `and`

Inherited invariant contributions are collected recursively and deduped by ancestor class, so the same ancestor contract is not applied twice through different parent paths.

The rule to remember is simple:

- children may accept more
- children must promise at least as much
- child objects must satisfy all inherited validity rules

If `Shape.area` promises to return a non-negative real number, then every subclass `area` must also return a non-negative real. A subclass that returns a negative area or raises an exception where the superclass would not has violated the contract that clients of `Shape` rely on.

When overriding a method: read the superclass method's contract first. In Nex, the language combines inherited contracts in exactly the way behavioral subtyping requires.



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
         get_balance(): Real do
           result := balance
         end
         describe(): String do
           result := owner + ": " + balance.to_string
         end
     end

nex> class SavingsAccount inherit Account
       create
         make(name: String, initial, rate: Real) do
           super.make(name, initial)
           interest_rate := rate
         end
       feature
         interest_rate: Real
         apply_interest() do
           balance := balance + balance * interest_rate
         end
         describe(): String do
           result := super.describe + " (savings, rate: " + interest_rate.to_string + ")"
         end
     end

nex> class OverdraftAccount inherit Account
       create
         make(name: String, initial, limit: Real) do
           super.make(name, initial)
           overdraft_limit := limit
         end
       feature
         overdraft_limit: Real
         withdraw(amount: Real): Boolean do
           if balance - amount >= -overdraft_limit then
             balance := balance - amount
             result := true
           else
             result := false
           end
         end
         describe(): String do
           result := super.describe + " (overdraft limit: " + overdraft_limit.to_string + ")"
         end
     end
```

```
nex> let accounts: Array[Account] := []
nex> accounts.add(create Account.make("Alice", 500.0))
nex> accounts.add(create SavingsAccount.make("Bob", 1000.0, 0.02))
nex> accounts.add(create OverdraftAccount.make("Carol", 200.0, 500.0))

nex> across accounts as acc do
       print(acc.describe)
    end
Alice: 500.0
Bob: 1000.0 (savings, rate: 0.02)
Carol: 200.0 (overdraft limit: 500.0)
```

Each account type inherits `deposit` and `get_balance` from `Account`. `SavingsAccount` adds `apply_interest`. `OverdraftAccount` overrides `withdraw` to permit negative balances within the limit. Both override `describe` using `super.describe` to build on the base description. The array holds all three as `Account`; `describe` dispatches polymorphically.



## Summary

- `class SubClass inherit SuperClass` declares the relationship; the subclass inherits all fields and methods
- `super.constructor_name(   )` calls the superclass constructor from the subclass constructor
- `super.method_name(   )` calls an overridden superclass method from within an override
- Overriding replaces a superclass method with a subclass-specific version; dynamic dispatch calls the correct version at runtime
- Polymorphism allows objects of different subclasses to be treated through a common superclass type
- Abstract methods (`name(): ReturnType is abstract`) declare a signature without an implementation; subclasses must implement them; classes with abstract methods cannot be instantiated
- The template method pattern: a concrete superclass method calls an abstract method; the superclass defines structure, subclasses fill in details
- Overriding methods must honour the superclass contract; preconditions should not be strengthened, postconditions should not be weakened
- Use inheritance for genuine is-a relationships and polymorphism; use composition for has-a relationships or code reuse without a conceptual relationship



## Exercises

**1.** Define an abstract class `Animal` with a field `name: String`, a constructor `make`, and an abstract method `sound(): String`. Define subclasses `Dog`, `Cat`, and `Cow` each implementing `sound`. Create an `Array[Animal]`, add one of each, and print each animal's name and sound.

**2.** Add an abstract method `perimeter(): Real` to the `Shape` class. Implement it in `Circle` (`2 * 3.14159 * radius`) and `Rectangle` (`2 * (width + height)`). Update `describe` in `Shape` to report both area and perimeter.

**3.** The `withdraw` method in `OverdraftAccount` overrides the one in `Account`. Does the override honour the Liskov Substitution Principle? Does it accept the same inputs? Does it make the same kind of promise — returning `true` on success and `false` on failure? What does a caller of `Account` need to know about `OverdraftAccount.withdraw`?

**4.** Define a class `Vehicle` with fields `make: String` and `speed: Real`, and abstract methods `fuel_type(): String` and `max_speed(): Real`. Define `ElectricCar` and `PetrolCar` implementing the abstract methods. Add `can_reach(distance, fuel: Real): Boolean` to each — `PetrolCar` uses 10 litres per 100 km; `ElectricCar` uses 20 kWh per 100 km.

**5.\*** Define a `Logger` base class with a method `log(message: String)` that prints with a prefix. Define `FileLogger` that also appends to a `log_history: String` field, and `SilentLogger` that discards all messages. Create an `Array[Logger]` with one of each and call `log("test")` on each. What does this demonstrate about polymorphism and swappable implementations?
