# Preconditions

Up to this point, many of our routines have relied on silent assumptions. A stack's `pop` routine assumes the stack is non-empty. A withdrawal routine assumes the amount is positive and not larger than the balance. A search routine that begins with `items.get(0)` assumes the array is not empty.

Before Chapter 16, those assumptions were informal. Now they become part of the program.

A *precondition* states what must be true before a routine is called. If the precondition is not satisfied, the caller is wrong. The routine is not required to continue, recover, or guess what was meant. It may stop immediately with a contract violation.


## The Idea

Consider a small `Wallet` class:

```
nex> class Wallet
       create
         make(initial: Real) do
           money := initial
         end
       feature
         money: Real
         spend(amount: Real) do
           money := money - amount
         end
     end
```

This class works mechanically, but the routine `spend` is underspecified. What if `amount` is negative? What if `amount` is larger than `money`? The body says what the routine does, but not when it is valid to call it.

A precondition makes that explicit:

```
nex> class Wallet
       create
         make(initial: Real) do
           money := initial
         end
       feature
         money: Real
         spend(amount: Real)
           require
             non_negative_amount: amount >= 0.0
             enough: amount <= money
           do
             money := money - amount
           end
     end
```

Now the routine says two things:

- what it requires of the caller
- what computation it performs once those requirements are met

That separation is the beginning of disciplined software design.


## Reading a Precondition

Read `require` as "this routine may be called only if...":

```
spend(amount: Real)
  require
    non_negative_amount: amount >= 0.0
    enough: amount <= money
  do
    money := money - amount
  end
```

The labels `non_negative_amount` and `enough` are names for the individual assertions. They matter. When a contract fails, Nex reports the assertion by name. A good assertion name tells the reader what rule was intended.

Bad names:

- `check1`
- `test`
- `condition`

Good names:

- `amount_positive`
- `customer_exists`
- `index_in_bounds`
- `source_has_funds`

The expression after the colon is the actual rule. The name is documentation; the expression is enforcement.


## Caller Responsibility

When a routine has a precondition, satisfying it is the caller's job.

That point is easy to say and easy to forget. Many beginners write routines that both require something and also try to defend themselves against the caller violating it:

```
nex> withdraw(amount: Real): Boolean
       require
         enough: amount <= balance
       do
         if amount <= balance then
           balance := balance - amount
           result := true
         else
           result := false
         end
       end
```

The `if` duplicates the contract. If `amount > balance`, the call is already invalid. The body does not need to ask again. Duplicating the check weakens the design because it blurs the boundary between correct use and incorrect use.

The cleaner version:

```
nex> withdraw(amount: Real)
       require
         non_negative_amount: amount >= 0.0
         enough: amount <= balance
       do
         balance := balance - amount
       end
```

Now the routine is simpler and its interface is sharper. Either the caller meets the contract, or the call is rejected.


## A First Useful Example

Here is a routine that returns the largest element of an integer array:

```
nex> function max_of(items: Array[Integer]): Integer
     require
       not_empty: items.length > 0
     do
       result := items.get(0)
       across items as item do
         if item > result then
           result := item
         end
       end
     end
```

Without the precondition, the call `items.get(0)` is suspicious. With the precondition, the suspicion is removed. The routine states exactly why that access is safe.

```
nex> max_of([4, 8, 2, 9, 1])
9
```

If the caller violates the contract:

```
nex> max_of([])
Error: Precondition violation: not_empty
```

The failure occurs at the boundary where the mistake was made. That is the practical value of contracts: errors are reported where they originate, not later after damage has spread.


## Preconditions Are Not Input Validation

It is important to distinguish two different situations.

Situation 1: the caller has made a programming error.

Situation 2: the outside world has provided uncertain data.

Preconditions are for the first case. They describe the obligations between one routine and another inside a program.

Suppose a routine computes the square root of a number:

```
nex> function positive_root(x: Real): Real
     require
       non_negative: x >= 0.0
     do
       result := x ^ 0.5
     end
```

This is appropriate if `positive_root` is an internal routine in a program whose callers are expected to know the rule.

But if the number came from a file, a network request, or a user typing at a prompt, that is not a contract problem yet. The program must inspect the external data and decide what to do. The uncertainty belongs at the program boundary. Once the data has been accepted, internal routines can rely on contracts.

In short:

- use `require` for programmer obligations
- use ordinary control flow for uncertain real-world input


## Strengthening a Design with Preconditions

Consider a bounded stack:

```
nex> class Bounded_Stack [G]
       create
         make(limit: Integer) do
           max_size := limit
           items := []
         end
       feature
         max_size: Integer
         items: Array[G]
         push(value: G)
           require
             not_full: items.length < max_size
           do
             items.add(value)
           end
         pop(): G
           require
             not_empty: items.length > 0
           do
             result := items.get(items.length - 1)
             items.remove(items.length - 1)
           end
         size(): Integer do
           result := items.length
         end
     end
```

Earlier, Chapter 15 showed a version of `push` that silently ignored extra insertions. That design is convenient but weak. It hides mistakes. A caller can believe the item was pushed even when it was not.

The version above is stricter and better. If the stack is full, the caller learns immediately that the call was invalid. A routine should not quietly proceed when its fundamental assumptions have been broken.


## Writing Good Preconditions

A good precondition has three properties.

It is necessary. Do not require more than the routine actually needs.

It is precise. Avoid vague conditions such as `valid_input: true`.

It is checkable. The caller should be able to know whether the obligation is satisfied.

For example, this precondition is too weak:

```
require
  okay: amount /= 0.0
```

This one is better:

```
require
  positive_amount: amount > 0.0
  enough: amount <= balance
```

The second version says exactly what the routine needs and nothing more.

Another common mistake is to require a consequence instead of a cause. If a routine sorts an array, the precondition should not be `array_is_sorted`. That would describe the result, not the starting obligation. Preconditions talk about the state before execution.


## A Worked Example: Transfer Between Accounts

Here is a simple account class:

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
         transfer_to(other: Account, amount: Real)
           require
             positive_amount: amount > 0.0
             enough: amount <= balance
             different_account: other /= this
           do
             withdraw(amount)
             other.deposit(amount)
           end
     end
```

`transfer_to` expresses its rules cleanly:

- the amount must be positive
- the source account must contain enough funds
- the destination must not be the same object

The body is then straightforward.

```
nex> let checking := create Account.make("Checking", 150.0)
nex> let savings  := create Account.make("Savings", 80.0)

nex> checking.transfer_to(savings, 50.0)
nex> checking.balance
100.0

nex> savings.balance
130.0
```

The routine is short because the contract does most of the explanatory work.


## Summary

- A precondition states what must be true before a routine may be called
- `require` clauses define the caller's obligations, not the routine's promises
- If a precondition is violated, the caller is wrong; the routine is not required to compensate
- Good precondition names document intent and improve error messages
- Preconditions should be necessary, precise, and checkable
- Use contracts for programming obligations inside the system, not for uncertain external input
- A routine with a clear precondition usually has a simpler body


## Exercises

**1.** Write a function `average(items: Array[Real]): Real` with an appropriate precondition. The routine should return the arithmetic mean of the array elements. Test it on a non-empty array, then deliberately violate the contract with `[]`.

**2.** Define a class `Temperature_Log` with an array field `readings: Array[Real]`, a constructor that starts with an empty array, and a method `latest(): Real` with a precondition that the log is non-empty. Add a method `record(value: Real)` that appends a reading.

**3.** Rewrite the `Queue[G]` class from Chapter 15 so that `dequeue` and `front` each have a precondition `not_empty`. Remove any duplicated emptiness checks from the method bodies.

**4.** Consider a function `substring(s: String, start, finish: Integer): String`. Write a plausible set of preconditions for it. Explain in one or two sentences why each is the caller's responsibility.

**5.\*** A routine `transfer_to(other: Account, amount: Real)` already requires `amount > 0.0` and `amount <= balance`. Is `other /= this` always a necessary precondition? Argue both sides, then decide whether it should be a contract or simply an allowed no-op in your design.
