# Invariants

Preconditions describe what must hold before a routine call. Postconditions describe what must hold after it. But some properties are not tied to one routine. They must hold for an object throughout its entire useful life.

Such a property is an *invariant*.

An invariant captures the essential consistency of a class. If the invariant fails, the object is no longer in a valid state. That makes invariants the deepest contracts in object-oriented design.


## A Simple Example

Consider a bank account:

```
nex> class Bank_Account
       create
         make(initial: Real) do
           balance := initial
         end
       feature
         balance: Real
         deposit(amount: Real)
           require
             positive_amount: amount > 0.0
           do
             balance := balance + amount
           end
         withdraw(amount: Real)
           require
             positive_amount: amount > 0.0
             enough: amount <= balance
           do
             balance := balance - amount
           end
       invariant
         never_negative: balance >= 0.0
     end
Class(es) registered: Bank_Account
```

The invariant says that every valid account has a non-negative balance. This is not just a rule for `deposit` or `withdraw`. It is a rule for the class itself.

When you write an invariant, you are answering the question: *what must always be true of an object of this class when no routine is in the middle of changing it?*


## What an Invariant Means

An invariant is not checked once and forgotten. It is part of the class contract.

Informally:

- a constructor must establish the invariant
- every exported routine must preserve it

That means an object begins life valid and remains valid after every routine call.

Suppose a bad constructor creates an invalid account:

```
nex> let a := create Bank_Account.make(-10.0)
Error: Class invariant violation: never_negative
```

The failure appears at construction time because the object never reached a legal state.


## Invariants Express Class Meaning

Well-chosen invariants capture the meaning of a class.

For a `Rectangle`, the invariant might be:

- width is non-negative
- height is non-negative

For a `Date`, the invariant might be:

- month is between 1 and 12
- day is within the legal range for that month

For a `Stack`, the invariant might be:

- `items` is not `nil`
- `items.length <= capacity`

Notice that these are not accidental properties. They are the rules without which the object would stop making sense.

If a supposed invariant could be removed without changing the class's concept, it may not belong there.


## Avoiding Trivial Invariants

An invariant should say something important. These are poor invariants:

```
invariant
  always_true: count = count
```

```
invariant
  array_exists: items /= nil
```

The second may be worth stating in some designs, but by itself it is weak. A stack with a non-`nil` array and a negative logical size would still be nonsense.

Better:

```
invariant
  items_exist: items /= nil
  capacity_non_negative: capacity >= 0
  size_in_range: items.length <= capacity
```

Together these describe a coherent state.


## Invariants and Mutating Routines

Inside a routine body, a class may temporarily violate its own invariant while it is rearranging state. What matters is that the invariant is restored by the time the routine finishes.

For example, imagine a class storing a sum and a count:

```
nex> class Running_Average
       create
         make() do
           total := 0.0
           count := 0
         end
       feature
         total: Real
         count: Integer
         add(value: Real)
           do
             total := total + value
             count := count + 1
           end
         average(): Real
           require
             has_values: count > 0
           do
             result := total / count.to_real
           end
       invariant
         count_non_negative: count >= 0
     end
Class(es) registered: Running_Average
```

If `add` updates `total` first and `count` second, there is a brief moment in the middle when the object is only partially updated. That is acceptable. The invariant is about stable states at routine boundaries, not every intermediate machine step.


## Invariants and Collaboration

One class often holds references to other objects. Then invariants become more interesting.

Consider a library loan:

```
nex> class Loan
       create
         make(book_title, borrower_name: String) do
           title := book_title
           borrower := borrower_name
           returned := false
         end
       feature
         title: String
         borrower: String
         returned: Boolean
         mark_returned()
           do
             returned := true
           end
       invariant
         title_not_empty: title.length > 0
         borrower_not_empty: borrower.length > 0
     end
Class(es) registered: Loan
```

The invariant does not try to state every fact about the world. It states only what must always hold inside a valid `Loan` object.

That boundary matters. An invariant should usually avoid depending on remote external state that can change for reasons the class does not control. The more local the invariant, the stronger and more maintainable it is.


## How Invariants Relate to Preconditions and Postconditions

The three kinds of contracts fit together.

The invariant is the stable truth about the class.

The precondition says when a routine may begin.

The postcondition says what the routine must ensure when it ends.

In practice:

- preconditions may assume the invariant already holds
- routine bodies may rely on both the invariant and the precondition
- postconditions and the restored invariant must both hold on exit

For example, in `withdraw(amount)`:

- the invariant tells us `balance >= 0.0`
- the precondition tells us `amount <= balance`
- the body computes the new balance
- the postcondition states the exact change
- the invariant confirms the object remains valid

That is a complete chain of reasoning.


## A Worked Example: A Bounded Counter

Here is a class whose central meaning is captured by its invariant:

```
nex> class Bounded_Counter
       create
         make(max: Integer) do
           limit := max
           count := 0
         end
       feature
         limit: Integer
         count: Integer
         increment()
           require
             below_limit: count < limit
           do
             count := count + 1
           ensure
             advanced: count = old count + 1
           end
         reset()
           do
             count := 0
           ensure
             cleared: count = 0
           end
       invariant
         limit_non_negative: limit >= 0
         count_non_negative: count >= 0
         count_within_limit: count <= limit
     end
Class(es) registered: Bounded_Counter
```

The invariant tells us what the class *is*: a counter that never goes below zero and never exceeds its limit.

The routines then become easy to understand:

- `increment` requires room to advance
- `reset` returns to the lower bound

The class design is sound because its contracts align with its concept.


## Summary

- An invariant states what must always be true of a valid object
- Constructors establish the invariant; routines must preserve it
- Good invariants capture the essential meaning of a class
- Trivial invariants are not useful; meaningful ones describe consistency
- An invariant applies at routine boundaries, not necessarily at every internal step
- Preconditions, postconditions, and invariants form one connected design
- A class with a clear invariant is easier to trust and extend


## Exercises

**1.** Add an invariant to the `Stack[G]` class from Chapter 17 stating that `items` is never `nil`. Then decide whether that invariant alone is strong enough to describe a valid stack.

**2.** Define a class `Rectangle` with fields `width` and `height`, a constructor `make(w, h: Real)`, a method `area(): Real`, and invariants that prevent impossible rectangles.

**3.** Define a class `Student_Record` with fields `name: String`, `scores: Array[Integer]`, and `average(): Real`. Write at least two invariants that describe when a record is valid.

**4.** A class `Time_Of_Day` has fields `hour`, `minute`, and `second`. Write invariants that guarantee the time is always legal. Then sketch constructors or setters that would preserve those invariants.

**5.\*** Design a class `Interval` with fields `start` and `finish`. Write its invariant and explain in a paragraph how the invariant changes the design of any method that extends, shrinks, or merges intervals.
