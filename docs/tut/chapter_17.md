# Postconditions

If a precondition says what a caller must provide, a postcondition says what the routine must deliver.

A routine without a postcondition tells us how it works only by showing its body. A routine with a postcondition tells us what it guarantees even before we study its implementation. That is why postconditions are more than runtime checks. They are executable specifications.


## The Idea

Here is a deposit routine without a postcondition:

```
nex> deposit(amount: Real)
       require
         positive_amount: amount > 0.0
       do
         balance := balance + amount
       end
```

The body suggests the intended effect, but the routine does not state its guarantee explicitly. A postcondition adds that missing half:

```
nex> deposit(amount: Real)
       require
         positive_amount: amount > 0.0
       do
         balance := balance + amount
       ensure
         increased: balance = old balance + amount
       end
```

Read `ensure` as "after this routine finishes successfully, the following must be true."

The label `increased` names the guarantee. The expression compares the new value of `balance` with its value before the routine began.


## The `old` Keyword

Postconditions often need to talk about both the state after execution and the state before execution. That is the role of `old`.

```
ensure
  decreased: balance = old balance - amount
```

`old balance` means the value of `balance` at routine entry. Without `old`, many useful guarantees would be impossible to state cleanly.

In practice, the simplest use of `old` in Nex is on fields of the current object, or on expressions built from those fields. That is the style used throughout this tutorial.

For example:

```
nex> class Counter
       create
         make() do
           count := 0
         end
       feature
         count: Integer
         increment()
           do
             count := count + 1
           ensure
             advanced: count = old count + 1
           end
     end
Class(es) registered: Counter
```

The postcondition does not describe the code line by line. It describes the observable result: after `increment`, the count is exactly one larger than before.


## Routine Responsibility

A precondition is the caller's responsibility. A postcondition is the routine's responsibility.

That distinction matters. If a call satisfies its precondition but violates its postcondition, the bug is inside the routine or in code it calls. The fault is not with the caller.

Suppose we write:

```
nex> class Counter
       create
         make() do
           count := 0
         end
       feature
         count: Integer
         increment()
           do
             count := count + 2
           ensure
             advanced: count = old count + 1
           end
     end
Class(es) registered: Counter
```

Then:

```
nex> let c := create Counter.make
nex> c.increment
Error: Postcondition violation: advanced
```

The postcondition catches the bug at the exact routine that introduced it.


## Weak and Strong Postconditions

Not all postconditions are equally useful.

This one is almost worthless:

```
ensure
  changed: balance /= old balance
```

It says only that the balance changed. It does not say by how much or in which direction. A deposit routine that subtracted the amount instead of adding it could still satisfy this contract.

This one is better:

```
ensure
  increased: balance = old balance + amount
```

It is precise. It rules out the wrong implementations and captures the intended effect exactly.

As a rule, prefer postconditions that express the specific relation between inputs, old state, and new state. A weak postcondition documents very little and catches very few bugs.


## Return Values and Postconditions

Postconditions apply to query routines as well as to commands.

Consider a function that returns the larger of two integers:

```
nex> function max(a, b: Integer): Integer
     do
       if a >= b then
         result := a
       else
         result := b
       end
     ensure
       result_is_one_argument: result = a or result = b
       result_is_at_least_a: result >= a
       result_is_at_least_b: result >= b
     end
```

The postconditions specify the meaning of the result:

- it must equal one of the arguments
- it must not be smaller than either argument

The body could be rewritten in many ways, but any correct implementation must satisfy those rules.


## Postconditions and Helper Routines

Good postconditions make routines easier to compose.

Return to the `Account` class:

```
nex> class Account
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
           ensure
             increased: balance = old balance + amount
           end
         withdraw(amount: Real)
           require
             positive_amount: amount > 0.0
             enough: amount <= balance
           do
             balance := balance - amount
           ensure
             decreased: balance = old balance - amount
           end
         transfer_to(other: Account, amount: Real)
           require
             positive_amount: amount > 0.0
             enough: amount <= balance
             different_account: other /= this
           do
             withdraw(amount)
             other.deposit(amount)
           ensure
             sender_lost_amount: balance = old balance - amount
           end
     end
Class(es) registered: Account
```

The postconditions on `withdraw` and `deposit` explain why `transfer_to` is trustworthy. The postcondition on `transfer_to` then states the exact effect on the source account. The corresponding effect on the destination account is guaranteed indirectly through the contract of `deposit`.

Contracts accumulate. A system of small precise routines is easier to reason about than a system of large vague ones.


## Choosing the Right Level of Detail

A postcondition should describe what matters to the caller. It should not repeat every assignment from the body.

For example, this is too implementation-specific:

```
ensure
  x_set: x = px
  y_set: y = py
  local_was_used: true
```

The last line says nothing useful. A caller of a point constructor cares that the point's coordinates equal the arguments, not whether the implementation used a temporary local variable along the way.

By contrast, for a sorting routine the right postcondition is not "the routine compared elements many times." It is something like:

- the result has the same length as the input
- the result is in non-decreasing order
- every original element still appears in the result

That is what the caller needs to know.


## A Worked Example: Removing the Last Stack Element

Here is a stack with both preconditions and postconditions:

```
nex> class Stack [G]
       create
         make() do
           items := []
         end
       feature
         items: Array[G]
         push(value: G)
           do
             items.add(value)
           ensure
             size_increased: items.length = old items.length + 1
             last_is_value: items.get(items.length - 1) = value
           end
         pop(): G
           require
             not_empty: items.length > 0
           do
             result := items.get(items.length - 1)
             items.remove(items.length - 1)
           ensure
             size_decreased: items.length = old items.length - 1
             returned_old_last: result = old items.get(old items.length - 1)
           end
     end
Class(es) registered: Stack
```

The postcondition on `pop` is particularly good because it specifies both effects:

- the stack becomes shorter by one
- the returned value is exactly the element that used to be last

That is the behavior the caller cares about. The internal array representation is almost irrelevant.


## Summary

- A postcondition states what a routine guarantees after successful completion
- `ensure` clauses define the routine's responsibility
- `old` refers to values from routine entry and is essential for expressing state change
- Strong postconditions describe precise effects, not vague change
- Query routines can and should have postconditions too
- Good postconditions make routines easier to compose and reason about
- A postcondition should describe the externally meaningful result, not internal implementation trivia


## Exercises

**1.** Add a postcondition to the `average(items: Array[Real]): Real` routine from Chapter 16. The postcondition should state at least one useful fact about the result relative to the input.

**2.** Define a class `Lamp` with a Boolean field `is_on`, a constructor that starts it in the `false` state, and methods `switch_on` and `switch_off`. Add postconditions showing the effect of each routine.

**3.** Write a function `absolute_value(x: Integer): Integer` and give it a postcondition strong enough to rule out both `result := x` and `result := -x` as universally correct implementations.

**4.** Extend the `Queue[G]` class so that `enqueue(value: G)` has a postcondition describing the new size and the value at the back of the queue.

**5.\*** A routine `sort(items: Array[Integer]): Array[Integer]` returns a new sorted array. Write three postconditions for it: one about length, one about order, and one about preservation of elements. You do not need to implement the routine; focus on the specification.
