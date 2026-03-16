# Contracts as Design

The last four chapters presented contracts as a way to check code. That is valuable, but it is not the main reason they matter. Their deeper value is that they help you decide what the code should be before you write it.

When you write a routine body first, the implementation tends to drag the design behind it. When you write the contract first, the design leads and the body follows. This changes the central question from "how should I code this?" to "what exactly should this routine require and guarantee?"


## Starting with the Interface

Suppose we want a routine that transfers money between accounts. Before writing any code, we can write the contract:

```
transfer_to(other: Account, amount: Real)
  require
    positive_amount: amount > 0.0
    enough: amount <= balance
    different_account: other /= this
  ensure
    sender_lost_amount: balance = old balance - amount
end
```

Even without a body, this is already useful. It answers the main design questions immediately:

- what counts as a valid call
- what exact effect the routine must have

Often the body becomes obvious once the contract is clear:

```
do
  withdraw(amount)
  other.deposit(amount)
end
```

The contract did not decorate the implementation after the fact. It produced the implementation. In a fuller design, the destination account's effect is also part of the intended behavior, but the most direct `old` expressions in Nex are on the current object's own fields, so the written postcondition stays focused on the source account.


## Contracts Expose Missing Concepts

Sometimes the attempt to write a contract reveals that the design is still missing a decision.

Suppose a `Document` class stores text and a cursor position. You begin to specify a routine:

```
move_cursor(offset: Integer)
  ensure
    cursor_changed: cursor = old cursor + offset
end
```

Immediately a problem appears. What if the new cursor would fall before the start of the text or after its end? The postcondition forces the question. The routine cannot stay vague. It either needs:

- a precondition limiting valid offsets
- or a different design, such as clamping to legal bounds

Contracts expose underspecified behavior early, before it has spread into many callers. That is one of their greatest strengths.


## Contracts as Documentation

A contract is documentation that the runtime can check.

Compare these two styles.

Comment-only documentation:

```
-- withdraws money if enough is available
withdraw(amount: Real) do
  balance := balance - amount
end
```

Contract-based documentation:

```
withdraw(amount: Real)
  require
    positive_amount: amount > 0.0
    enough: amount <= balance
  do
    balance := balance - amount
  ensure
    decreased: balance = old balance - amount
  end
```

The second version is better in three ways:

- it is precise
- it cannot silently drift away from the code
- it tells both caller and implementer what matters

Comments still have a place, but when a property can be made executable, it should be.


## Contracts Help Find Bugs Earlier

A contract violation often points to the exact design boundary that failed.

If a postcondition fails, the routine is wrong.

If a precondition fails, the caller is wrong.

If an invariant fails, the class design or one of its routines has allowed an invalid state.

That localization is not merely convenient. It changes debugging from a search through many functions into an investigation of one broken promise.


## Contracts and Tests

Contracts and tests support each other, but they are not the same thing.

Contracts state general truths that should hold for every call.

Tests choose specific examples and verify that the system behaves correctly on them.

For example, this postcondition:

```
ensure
  decreased: balance = old balance - amount
```

states a universal property of `withdraw`.

A test might choose three cases:

- withdrawing `10.0` from `100.0`
- withdrawing the entire balance
- trying to withdraw too much and expecting a precondition violation

The contract defines the law. The tests exercise representative situations.

Write contracts to capture permanent rules. Write tests to exercise interesting cases, combinations, and regressions.


## A Contract-First Routine

Let us design a routine that removes the last character from a string:

Step 1: write the contract.

```
function without_last(s: String): String
  require
    not_empty: s.length > 0
  ensure
    one_shorter: result.length = s.length - 1
end
```

Step 2: ask whether the contract is strong enough.

It is not. A routine returning `"xxxxx"` for any input string of length six would satisfy `one_shorter`.

Step 3: strengthen the contract.

```
function without_last(s: String): String
  require
    not_empty: s.length > 0
  ensure
    one_shorter: result.length = s.length - 1
end
```

Nex does not yet give us a rich string slice notation to state the exact prefix relation elegantly, so we may decide that the current postcondition is useful but incomplete. That is still a design success. It tells us exactly where the specification is strong and where tests must carry more of the burden.

That is a realistic engineering judgment.


## Contracts Improve Class Boundaries

A class with strong contracts can hide its representation more confidently.

Callers of a `Stack` do not need to know whether the stack uses an array, a linked structure, or two arrays internally. They only need the contract of `push`, `pop`, `peek`, and `size`.

This is the real meaning of abstraction: not merely "hide the fields," but "publish reliable behavior."

Without contracts, encapsulation is weaker. The implementation is hidden, but the behavior is still vague. With contracts, the interface becomes a real boundary.


## A Worked Example: Designing a Small Set Class

Suppose we want a set of strings with no duplicates. Begin with its central contract idea:

- `add(word)` should ensure the word is present afterward
- adding the same word twice should not create duplicates

One possible design:

```
nex> class Word_Set
       create
         make() do
           items := []
         end
       feature
         items: Array[String]
         has(word: String): Boolean do
           result := items.contains(word)
         end
         add(word: String)
           require
             not_empty: word.length > 0
           do
             if not has(word) then
               items.add(word)
             end
           ensure
             word_present: has(word)
           end
         size(): Integer do
           result := items.length
         end
       invariant
         storage_exists: items /= nil
     end
```

The postcondition on `add` is the important part. It says what the routine must achieve, not how. The body then chooses one reasonable implementation.

If later we replace `Array[String]` with a map or a tree, the contract can remain the same. That is contract-first design doing its proper job.


## Summary

- Contracts are most powerful when written before the implementation
- A clear contract often makes the body obvious
- Writing a contract exposes missing decisions and weak class designs
- Contracts are executable documentation
- Contracts and tests are complementary, not interchangeable
- Strong contracts create strong abstraction boundaries
- Contract-first design keeps attention on behavior rather than mechanism


## Exercises

**1.** Design the contract for a routine `append_line(path: String, text: String)` before writing its body. What belongs in the precondition, and what belongs in error handling instead?

**2.** Write a contract-first design for `front(queue: Queue[String]): String`. Include any necessary preconditions and at least one postcondition.

**3.** Consider a class `Timer` with methods `start`, `stop`, and `elapsed`. Write the contracts you would want before implementing any of the methods.

**4.** For the `Word_Set` class in Section 20.8, strengthen the specification of `add` by adding a postcondition about `size`. Explain why the postcondition must account for both the "already present" and "new word" cases.

**5.\*** Choose a routine from Chapters 1 through 15 that you wrote or imagined earlier without contracts. Redesign it contract-first. Show the old idea, the new contract, and one thing the contract revealed that the earlier design had left vague.
