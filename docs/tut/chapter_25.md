# Testing Your Programs

Contracts improve correctness, but they do not remove the need for tests.

A contract states what must always be true. A test chooses specific examples and checks that the program behaves correctly on them. Good software uses both.


## What Tests Add

A contract can tell us:

- the balance decreases by `amount`
- the result is non-negative
- the stack is not empty before `pop`

But a test can still reveal problems that contracts may not express directly or completely:

- a boundary case the programmer forgot
- a wrong algorithm that satisfies a weak postcondition
- an interaction between several routines
- a regression after later changes

Tests are the concrete examples that keep abstractions honest.


## Test Small Things First

The best starting point is the smallest meaningful unit.

For a function:

```
nex> function square(x: Integer): Integer
     do
       result := x * x
     end
```

simple tests might be:

```
nex> square(0)
0

nex> square(5)
25

nex> square(-3)
9
```

The point is not the print statements themselves. The point is to choose cases that exercise the routine's meaning:

- a neutral case
- a typical case
- an edge or surprising case


## A Tiny Test Harness in Nex

Nex does not need a large testing framework before you can begin writing useful tests. A simple helper routine already goes a long way:

```
nex> function assert_equal_integer(actual, expected: Integer, label: String)
     do
       if actual /= expected then
         raise "test failed: " + label
       end
     end
```

You can add a boolean helper in the same style:

```
nex> function assert_true(condition: Boolean, label: String)
     do
       if not condition then
         raise "test failed: " + label
       end
     end
```

Then:

```
nex> function test_square()
     do
       assert_equal_integer(square(0), 0, "square zero")
       assert_equal_integer(square(5), 25, "square positive")
       assert_equal_integer(square(-3), 9, "square negative")
       print("square tests passed")
     end
```

This is not elaborate, but it is enough to establish the core habit: expected behavior should be checked automatically, not only by eye.


## Testing Contracts

Contracts and tests reinforce each other.

Tests should include valid cases that satisfy the precondition and confirm the promised behavior.

They should also include deliberate invalid calls when appropriate, to confirm that contract violations occur where expected.

One direct way to test an expected failure is to use `rescue`:

```
nex> function test_empty_stack_pop()
     do
       let s := create Stack[Integer]
       let reached_unexpected_success: Boolean := false
       s.pop()
       reached_unexpected_success := true
       raise "test failed: empty pop should fail"
     rescue
       if reached_unexpected_success then
         raise "test failed: empty pop should fail"
       else
         print("empty-pop test passed")
       end
     end
```

This pattern distinguishes the two cases:

- if `pop()` raises immediately, the test passes
- if execution reaches the explicit `raise`, then the expected contract failure did not happen

For a stack:

- create a new stack
- push one element, then pop it
- push several elements and check last-in, first-out behavior
- deliberately call `pop` on an empty stack and confirm the precondition fails

The valid tests check behavior. The invalid test checks the interface boundary.


## Testing Classes Through Sequences

Class tests are often more meaningful as sequences of operations than as isolated calls.

For `Account`:

1. create with initial balance `100.0`
2. deposit `25.0`
3. withdraw `40.0`
4. check that the balance is `85.0`

In Nex:

```
nex> function test_account()
     do
       let a := create Account.make(100.0)
       a.deposit(25.0)
       a.withdraw(40.0)
       if a.balance /= 85.0 then
         raise "test failed: account sequence"
       end
       print("account tests passed")
     end
```

The sequence matters because the behavior of later operations depends on earlier ones.


## Choosing Good Test Cases

Choose tests that represent different categories of behavior:

Normal cases.

The routine works under ordinary expected inputs.

Boundary cases.

Empty arrays, one-element arrays, zero, maximum allowed value, minimum allowed value.

Error cases.

Inputs that should violate a contract or trigger a controlled failure.

Representative combinations.

For classes, sequences of operations that exercise state changes.

One of the most common beginner mistakes is to test only happy-path examples. Real confidence comes from boundary and failure cases.


## Organizing Tests

As programs grow, tests should be kept separate from the main code where possible.

One reasonable structure is:

- source files under `src/` or application directories
- Nex examples and tutorial code in their own files
- host-side or repository-level automated tests under `test/`

This repository already includes automated test commands for the implementation itself:

```bash
clojure -M:test test/scripts/run_tests.clj
```

For your own Nex tutorial programs, a similar habit is useful: create test routines, group them clearly, and run them together.


## A Worked Example: Testing a Frequency Counter

Return to the word-frequency routine:

```
nex> function word_frequencies(text: String): Map[String, Integer]
     do
       result := {}
       let words := text.to_lower.split(" ")
       across words as w do
         if w /= "" then
           let count := result.try_get(w, 0)
           result.set(w, count + 1)
         end
       end
     end
```

A useful test routine:

```
nex> function test_word_frequencies()
     do
       let freq := word_frequencies("To be or not to be")
       assert_equal_integer(freq.get("to"), 2, "count of to")
       assert_equal_integer(freq.get("be"), 2, "count of be")
       assert_equal_integer(freq.get("or"), 1, "count of or")

       let spaced := word_frequencies("to  be")
       assert_equal_integer(spaced.get("to"), 1, "count of to with repeated spaces")
       assert_equal_integer(spaced.get("be"), 1, "count of be with repeated spaces")
       assert_equal_integer(spaced.try_get("", 0), 0, "no empty token from repeated spaces")

       print("word frequency tests passed")
     end
```

The `if w /= ""` guard matters. A naive `split(" ")` creates empty pieces for repeated spaces, so a good test should make that behavior explicit instead of leaving it accidental.

This test now checks several representative behaviors:

- ordinary repeated words
- case-insensitive counting
- repeated spaces without creating an empty-string key

Tests grow naturally by exploring the routine's meaning.


## Summary

- Contracts and tests serve different purposes and are both necessary
- Tests should cover normal, boundary, and failure cases
- A small handmade assertion routine is enough to begin
- Class tests are often best written as sequences of operations
- Weak specifications should be reinforced with targeted tests
- Good test organization keeps checking repeatable and easy to run


## Exercises

**1.** Write a tiny assertion routine for strings and use it to test a `reverse(s: String): String` function.

**2.** Write tests for the `Stack[G]` class that cover push, pop, peek, and the empty-stack precondition.

**3.** Design a test sequence for `Bank_Account` that checks deposit, withdrawal, and one invalid call.

**4.** Improve `test_word_frequencies` with at least two additional cases that probe boundary or formatting behavior.

**5.\*** Pick one routine whose contract is weaker than its true intent. Write a set of tests that would catch an incorrect implementation even if the current postcondition would not.
