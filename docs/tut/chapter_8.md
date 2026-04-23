# Recursion

A function can call other functions — that much we established in Chapter 6. A function can also call itself. This is recursion, and it is one of the most powerful ideas in programming. It is also one of the most disorienting for beginners, because it seems circular: how can a function be defined in terms of itself? This chapter builds the mental model that makes recursive thinking natural.


## A Function That Calls Itself

Start with a familiar computation: summing the integers from 1 to n. Chapter 5 wrote this as a loop. Here is the recursive version:

```
nex> function sum_to(n: Integer): Integer do
       if n = 0 then
         result := 0
       else
         result := n + sum_to(n - 1)
       end
     end

nex> sum_to(5)
15
```

The body of `sum_to` calls `sum_to`. This is the defining feature of a recursive function. But notice that each call is made with a *smaller* argument. `sum_to(5)` calls `sum_to(4)`, which calls `sum_to(3)`, and so on. The calls do not continue forever because there is a stopping condition: when `n = 0`, the function returns `0` without making another call.

Every correct recursive function has exactly this structure: a *base case* that terminates the recursion, and a *recursive case* that reduces the problem and calls the function again. Without the base case, the function calls itself forever — the recursive equivalent of an infinite loop.



## Tracing a Recursive Call

The most effective way to understand recursion is to trace a call step by step. Here is `sum_to(4)` fully expanded:

```text
sum_to(4)
= 4 + sum_to(3)
= 4 + (3 + sum_to(2))
= 4 + (3 + (2 + sum_to(1)))
= 4 + (3 + (2 + (1 + sum_to(0))))
= 4 + (3 + (2 + (1 + 0)))
= 4 + (3 + (2 + 1))
= 4 + (3 + 3)
= 4 + 6
= 10
```

Each call suspends and waits for the result of the next call. When `sum_to(0)` returns `0`, the waiting calls can resolve from the inside out: `sum_to(1)` returns `1`, `sum_to(2)` returns `3`, and so on up to `sum_to(4)`, which returns `10`.

This expansion — called the *call stack* — builds up as the recursion descends and collapses as it returns. For deep recursion this stack can become large. We return to this practical concern in Section 8.7.



## Identifying the Base Case and the Recursive Case

Every recursive problem can be decomposed by asking two questions:

1. *What is the simplest version of this problem, the one that can be answered immediately without further recursion?* This is the base case.
2. *How can a problem of size n be expressed in terms of a problem of size n - 1 (or smaller)?* This is the recursive case.

For `sum_to`: the simplest version is `sum_to(0)`, which is `0`. A sum up to `n` is `n` plus the sum up to `n - 1`.

For factorial — the product of all integers from 1 to n:

```
nex> function factorial(n: Integer): Integer do
       if n = 0 then
         result := 1
       else
         result := n * factorial(n - 1)
       end
     end

nex> factorial(5)
120
```

Base case: `factorial(0)` is `1` (the empty product, by convention). Recursive case: `n!` is `n * (n-1)!`.

For counting down and printing:

```
nex> function count_down(n: Integer) do
       if n = 0 then
         print("Go!")
       else
         print(n)
         count_down(n - 1)
       end
     end

nex> count_down(3)
3
2
1
"Go!"
```

Base case: when `n = 0`, print `"Go!"` and stop. Recursive case: print `n`, then count down from `n - 1`.

The pattern is always the same. Find the simplest instance. Express the general case in terms of a simpler one. Trust that the simpler call will do its job.



## Recursion on Lists

Recursion becomes particularly natural when working with lists and other recursive data structures. An array can be thought of recursively: it is either empty, or it has a first element followed by the rest of the array. Many operations on arrays have elegant recursive expressions.

Consider summing the elements of an integer array. Before arrays are introduced formally in Chapter 9, we can write a recursive function that processes a string character by character — the same structural idea.

This is also a natural moment to introduce the `Char` type. A `Char` is a single character, written with a `#` prefix:

```
nex> let c: Char := #s

nex> let newline: Char := #newline

nex> let tab: Char := #tab
```

`Char` is distinct from `String` — `#s` is a single character value, not the one-character string `"s"`. Special characters are written by name: `#newline`, `#space`, `#tab`. The `to_string` method converts a `Char` to a `String` when needed for comparison or concatenation.

With that in hand:

```
nex> function count_char(s: String, ch: Char): Integer do
       if s.length = 0 then
         result := 0
       else
         let head := s.substring(0, 1)
         let tail := s.substring(1, s.length)
         let rest := count_char(tail, ch)
         if head = ch.to_string then
           result := 1 + rest
         else
           result := rest
         end
       end
     end

nex> count_char("mississippi", #s)
4
```

The base case is an empty string: no characters to count, result is `0`. The recursive case separates the first character (`head`) from the rest (`tail`), counts occurrences in `tail`, and adds 1 if `head` matches the target character.

Notice the structure: *process the first element, then recurse on the rest*. This head-and-tail decomposition is the canonical recursive pattern for sequences, and we will use it extensively in Chapters 9 and 11.



## Mutual Recursion

Two functions can be mutually recursive — each calling the other. A classic example is testing whether a number is even or odd without using `%`:

```
nex> declare function is_even(n: Integer): Boolean

nex> declare function is_odd(n: Integer): Boolean

nex> function is_even(n: Integer): Boolean do
       if n = 0 then
         result := true
       else
         result := is_odd(n - 1)
       end
     end

nex> function is_odd(n: Integer): Boolean do
       if n = 0 then
         result := false
       else
         result := is_even(n - 1)
       end
     end

nex> is_even(4)
true

nex> is_odd(7)
true
```

The first two lines are forward declarations. They tell the typechecker the
signatures of both functions before either body is checked. Without those
declarations, the first function body would refer to a function whose return
type is not yet known.

`is_even(4)` calls `is_odd(3)`, which calls `is_even(2)`, which calls `is_odd(1)`, which calls `is_even(0)`, which returns `true`. The base cases anchor both functions: zero is even and zero is not odd.

Mutual recursion is less common than direct recursion but appears naturally in parsers, state machines, and algorithms over tree structures. The same principles apply: every chain of calls must reach a base case, and each call must make progress toward it.



## When Recursion Is Clearer Than a Loop

Recursion and loops are interchangeable in the sense that anything computable by one is computable by the other. The question is which expresses the solution more clearly for a given problem.

Recursion tends to be clearer when:

**The problem is defined recursively.** Fibonacci numbers, tree traversal, and many mathematical sequences are defined in terms of smaller instances of themselves. A recursive function mirrors that definition directly:

```
nex> function fibonacci(n: Integer): Integer do
       if n <= 1 then
         result := n
       else
         result := fibonacci(n - 1) + fibonacci(n - 2)
       end
     end

nex> fibonacci(10)
55
```

The function body is almost identical to the mathematical definition: F(0) = 0, F(1) = 1, F(n) = F(n-1) + F(n-2). A loop-based Fibonacci requires two accumulator variables and careful bookkeeping. The recursive version states the definition and lets the language figure out the rest.

**The data structure is recursive.** Trees, nested lists, and hierarchically structured data are defined recursively — each node contains sub-nodes of the same type. Functions that process them naturally follow the same shape. We will see this clearly in Chapter 11.

**The solution has a clean decomposition.** Some problems break cleanly into: handle the base case, do something with the first element, recurse on the rest. When that decomposition exists and is natural, a recursive function expresses it with minimal noise.



## When a Loop Is Clearer Than Recursion

Recursion is not always the right choice. Loops tend to be clearer when:

**The iteration is straightforward.** Printing numbers from 1 to 10, summing an array, searching for an element — these have obvious loop forms. A recursive version adds the cognitive overhead of the call stack without adding clarity:

```
nex> -- loop: immediately clear
nex> from let i := 1 until i > 10 do print(i) i := i + 1 end

nex> -- recursion: more thought required
nex> function print_to(n, limit: Integer) do
       if n <= limit then
         print(n)
         print_to(n + 1, limit)
       end
     end
nex> print_to(1, 10)
```

The loop is shorter and more direct. Reach for a loop when the iteration pattern is simple and sequential.

**Stack depth is a concern.** Each recursive call occupies space on the call stack. For a recursion that descends thousands of levels, this stack space may be exhausted, producing a stack overflow error. A loop uses a fixed amount of space regardless of how many iterations it performs. For very deep or unbounded computations, a loop is safer:

```text
nex> -- do not run this
nex> -- this will fail for large n due to stack overflow
nex> sum_to(10000)
```

```
nex> -- this handles any n safely
nex> let total := 0
nex> from
       let i := 1
    until
       i > 10000
    do
       total := total + i
       i := i + 1
    end
nex> total
50005000
```

**Performance matters.** The naive recursive Fibonacci from Section 8.6 is correct but slow. `fibonacci(40)` requires computing `fibonacci(39)` and `fibonacci(38)`, each of which recomputes overlapping sub-problems. The number of calls grows exponentially with `n`. A loop-based version with two accumulator variables computes the same result in linear time. When a recursive solution recomputes the same sub-problems repeatedly, a loop — or a more advanced technique called memoisation, which stores previously computed results so the same sub-problem is not solved repeatedly — will be significantly faster.



## Thinking Recursively: A Discipline

Beginners often try to trace through the full execution of a recursive function to convince themselves it is correct. This works for small inputs but becomes impractical quickly. The more powerful discipline is to *trust the recursive call*.

When writing a recursive function, assume that the recursive call does what it is supposed to do for smaller inputs. Then ask: given that the recursive call works correctly, does the function as a whole work correctly for `n`?

For `sum_to(n)`:
- Assume `sum_to(n - 1)` correctly returns the sum of integers from 1 to `n - 1`.
- Then `n + sum_to(n - 1)` is the sum from 1 to `n`.
- The base case handles `n = 0` correctly.
- Therefore `sum_to` is correct.

This is the recursive analogue of mathematical induction, and it is the right way to verify a recursive function. You do not need to trace all the way down to the base case and back up again — you need to verify the base case and verify that the recursive step is correct given a correct sub-result.

The same discipline applies to writing recursive functions. Define the base case clearly. Then define the recursive case by asking: *if I had the answer for n - 1, how would I construct the answer for n?* Write that construction. Trust that the recursive call provides the sub-answer.



## Summary

- A recursive function calls itself with a smaller or simpler argument
- Every recursive function needs a base case — a condition under which it returns without further recursion — and a recursive case that makes progress toward the base case
- To understand a recursive function, trace the first few levels and trust the recursive call to handle the rest
- Recursion is clearest when the problem is defined recursively, when the data structure is recursive, or when the solution decomposes cleanly into a base case and a recursive step
- Loops are clearer for straightforward iteration, safer for very deep computations where stack overflow is a risk, and faster when a naive recursive solution recomputes overlapping sub-problems
- The right mental model for verifying recursion is inductive: verify the base case, then verify that the recursive step is correct assuming the sub-call works correctly



## Exercises

**1.** Write a recursive function `power(base, exp: Integer): Integer` that computes `base` raised to the power `exp` for non-negative `exp`. Do not use the `^` operator. Verify that `power(2, 10)` returns 1024 and `power(5, 0)` returns 1.

**2.** Write a recursive function `reverse_string(s: String): String` that returns the characters of `s` in reverse order. Use the head-and-tail pattern from Section 8.4: the reverse of a string is the reverse of its tail followed by its head. Verify that `reverse_string("hello")` returns `"olleh"` and `reverse_string("")` returns `""`.

**3.** The greatest common divisor (GCD) of two positive integers can be computed by Euclid's algorithm: `gcd(a, b)` is `a` when `b = 0`, and `gcd(b, a % b)` otherwise. Write a recursive function `gcd(a, b: Integer): Integer` and verify that `gcd(48, 18)` returns 6 and `gcd(100, 75)` returns 25.

**4.** Write a recursive function `count_digits(n: Integer): Integer` that returns the number of digits in a positive integer. The base case is a single-digit number (0–9). The recursive case removes the last digit with `/`. Verify that `count_digits(1)` returns 1, `count_digits(42)` returns 2, and `count_digits(10000)` returns 5.

**5.\*** The naive recursive Fibonacci from Section 8.6 is exponentially slow because it recomputes sub-problems. Write an alternative function `fibonacci_fast(n: Integer): Integer` using a loop and two accumulator variables `a` and `b`, where at each step `a` holds F(i) and `b` holds F(i+1). Verify it gives the same results as the recursive version for `n` from 0 to 10, then time both versions informally at the REPL by calling them with `n = 35` and observing the difference in response time.
