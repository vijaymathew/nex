# Loop Contracts

Chapters 16 through 18 introduced contracts for routines and classes. Loops need the same kind of precision. A loop often contains the main algorithm in a routine, and when the loop is wrong the whole routine is wrong with it.

The difficulty is that a loop works by stages. At the beginning, the job is unfinished. In the middle, part of the job is done and part remains. At the end, the whole job is complete. Loop contracts describe that changing state explicitly.

Nex supports two kinds of loop contract:

- a *loop invariant*, which must hold throughout the loop
- a *loop variant*, which must decrease toward termination


## The Shape of a Contracted Loop

A `from ... until ... do ... end` loop may include `invariant` and `variant` clauses:

```
nex> let sum := 0
nex> from
       let i := 0
     invariant
       index_in_range: i >= 0
       sum_non_negative: sum >= 0
     variant
       10 - i
     until
       i = 10
     do
       i := i + 1
       sum := sum + i
     end
```

The invariant describes what remains true every time control reaches the top of the loop body. The variant is a quantity that must get smaller as the loop progresses. It is evidence that the loop will finish.


## Loop Invariants

The hardest part of loop reasoning is finding the property that remains true while the loop works. Once that property is found, the rest of the reasoning is usually straightforward.

Consider summing the integers from `0` up to but not including `n`:

```
nex> function sum_to(n: Integer): Integer
     require
       non_negative: n >= 0
     do
       from
         let i := 0
         result := 0
       invariant
         index_in_range: 0 <= i and i <= n
         partial_sum_correct: result = (i * (i - 1)) / 2
       variant
         n - i
       until
         i = n
       do
         result := result + i
         i := i + 1
       end
     end
```

The key assertion is `partial_sum_correct`. It says that before each iteration, `result` already equals the sum of the integers from `0` to `i - 1`.

That may look technical, but it is simply a precise way to say what the loop has accomplished so far. The loop has not finished the whole sum. It has finished a prefix of it.

At loop exit, `i = n`. Substitute that fact into the invariant and you immediately learn what `result` means: it is the sum from `0` to `n - 1`.


## Invariants Are About Progress So Far

Beginners often try to write loop invariants that describe the final answer too early.

This is wrong:

```
invariant
  finished_sum: result = (n * (n - 1)) / 2
```

It cannot hold at the beginning unless the loop has already done all of its work.

A loop invariant should describe the relationship between:

- what part of the input has been processed
- what the current variables mean with respect to that processed part

That is why phrases like "the first `i` elements", "all items seen so far", and "everything before position `k`" appear so often in loop reasoning.


## Loop Variants

A variant is a quantity that decreases each iteration and cannot decrease forever. Its purpose is to justify termination.

In the previous example, `n - i` is a good variant:

- it starts non-negative
- each iteration increases `i`
- therefore `n - i` gets smaller
- when it reaches zero, the loop condition `i = n` is satisfied

Variants are especially useful in loops whose termination is not obvious from a quick glance.

For a reverse countdown:

```
nex> from
       let i := 10
     variant
       i
     until
       i = 0
     do
       print(i)
       i := i - 1
     end
10
9
8
7
6
5
4
3
2
1
```

The variant is simply `i`. It shrinks toward zero.


## Searching with a Loop Invariant

Here is a function that searches an array for a target value:

```
nex> function contains(items: Array[Integer], target: Integer): Boolean
     do
       from
         let i := 0
         result := false
       invariant
         index_in_range: 0 <= i and i <= items.length
       variant
         items.length - i
       until
         i = items.length or result
       do
         if items.get(i) = target then
           result := true
         end
         i := i + 1
       end
     end
```

The invariant above is intentionally weak in its second part. A stronger and more useful version would state:

- if `result` is `false`, then the target has not appeared in positions `0` through `i - 1`

Written informally, that is exactly the right insight. Writing it formally can be verbose, and that is normal. Loop invariants are not always elegant. Their job is not elegance. Their job is to say exactly what progress has been made.

When the loop exits:

- either `result` is `true`, so the target was found
- or `i = items.length`, and the invariant tells us the target never appeared in the scanned portion, which is now the whole array


## A Loop for Maximum

The maximum-finding loop from Chapter 16 becomes much clearer when you ask what `result` means after the first `i` elements have been scanned:

```
nex> function max_of(items: Array[Integer]): Integer
     require
       not_empty: items.length > 0
     do
       from
         let i := 1
         result := items.get(0)
       invariant
         scanned_prefix: 1 <= i and i <= items.length
       variant
         items.length - i
       until
         i = items.length
       do
         if items.get(i) > result then
           result := items.get(i)
         end
         i := i + 1
       end
     end
```

The essential informal invariant is simple:

`result` is the maximum of the elements in positions `0` through `i - 1`.

That one sentence explains the whole algorithm. If you cannot say something like that about a loop, you probably do not yet understand the loop well enough to trust it.


## Reading Existing Loops

Loop contracts are not only for writing new code. They are also for reading code that already exists.

Given an unfamiliar loop, ask:

1. What variables measure progress?
2. What part of the input has been processed?
3. What does each accumulator variable mean so far?
4. What quantity is moving toward termination?

Those questions usually reveal the intended invariant and variant even if they were never written down. Writing them explicitly simply makes the reasoning permanent and checkable.


## A Worked Example: Counting Occurrences

```
nex> function count_occurrences(items: Array[String], target: String): Integer
     do
       from
         let i := 0
         result := 0
       invariant
         index_in_range: 0 <= i and i <= items.length
       variant
         items.length - i
       until
         i = items.length
       do
         if items.get(i) = target then
           result := result + 1
         end
         i := i + 1
       end
     end
```

The key informal invariant is:

`result` equals the number of times `target` appears in positions `0` through `i - 1`.

At loop exit, `i = items.length`, so `result` counts occurrences in the whole array.

That is the general pattern of accumulator loops:

- choose a variable that measures how much input has been processed
- choose an accumulator that summarizes the processed portion
- write the invariant that connects them


## Summary

- Loop invariants describe what remains true throughout the loop
- Loop variants describe a quantity that decreases toward termination
- A good invariant explains what has been processed so far and what the current variables mean
- A good variant starts non-negative and decreases on every iteration
- The loop exit condition plus the invariant explains the final result
- Writing loop contracts is often the clearest way to understand a loop


## Exercises

**1.** Write a loop invariant for a routine that computes the product of all integers from `1` to `n`. Then implement the routine using `from ... until`.

**2.** Rewrite the linear search routine in Section 19.5 with a stronger explicit invariant in a sentence beneath the code block. You do not need formal mathematical notation; state clearly what is true about the scanned prefix.

**3.** Implement `index_of(items: Array[String], target: String): Integer` that returns the first index of `target` or `-1` if it is absent. Give the loop a variant and describe its invariant informally.

**4.** A loop scans an array from right to left. Suggest a natural variant for that loop and explain why it guarantees termination.

**5.\*** Consider a routine that removes duplicates from a sorted array. Describe the loop invariant for the standard two-index algorithm: one index reads the input, the other marks the end of the unique prefix built so far.
