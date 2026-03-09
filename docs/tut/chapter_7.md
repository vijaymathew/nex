# Thinking with Functions

Chapter 6 showed how to define and call functions. This chapter is about how to think with them. The difference is significant. Knowing the syntax for a function definition is a matter of minutes. Developing the habit of reasoning about functions — what they require, what they guarantee, how they interact — is a matter of months. This chapter plants that habit early, because it shapes every programming decision that follows.


## A Function as a Contract

Every function makes two implicit commitments. It assumes certain things about its inputs — conditions that must be true for the function to behave as intended. And it promises certain things about its output — conditions that will be true when the function returns, given that the assumptions were met.

Consider a function that computes the average of two real numbers:

```
nex> function average(a, b: Real): Real
     do
       result := (a + b) / 2.0
     end
```

What does this function assume? Nothing particularly strong — any two real numbers will do. What does it guarantee? That the result is the arithmetic mean of the two inputs: `(a + b) / 2.0`.

Now consider a function that computes a percentage:

```
nex> function percentage(part, total: Real): Real
     do
       result := (part / total) * 100.0
     end
```

This function has a stronger assumption: `total` must not be zero. If `total` is zero, the division will fail. The function's guarantee — that it returns the percentage of `part` in `total` — only holds when the assumption is satisfied.

At this stage in the book, we state these assumptions and guarantees as comments or as clear reasoning in our heads. In Part V, we will write them as executable `require` and `ensure` clauses that Nex checks at runtime. The form is different; the thinking is identical.

The habit to build now is this: before writing a function body, ask two questions:

1. *What must be true of the inputs for this function to behave correctly?*
2. *What will be true of the output when this function returns?*

Answering these questions before writing the body is not wasted effort. It is often the fastest path to a correct body, because a clear statement of what the function must produce makes the code that produces it obvious.

These two questions have names in software engineering. The conditions that must be true of the inputs are called *preconditions*; the conditions that will be true of the output are called *postconditions*. In Part V of this book we will write these as executable Nex clauses — `require` for preconditions, `ensure` for postconditions — that the runtime checks automatically, firing an informative error the moment a violation occurs. For now, the discipline is to answer them in your head, or in a comment, before writing the body. The formal syntax changes nothing about the thinking. A programmer who has the habit of asking these questions before reaching for the keyboard is already doing design by contract; the Nex syntax simply makes that contract visible to the language itself.



## Pure Functions

A *pure function* is a function whose output depends only on its inputs and which has no observable effect on the world beyond returning a value. Given the same inputs, a pure function always returns the same output. It does not modify any variable outside itself, does not print anything, does not read from the console, does not write to a file.

The functions defined so far — `double`, `max`, `average`, `celsius_to_fahrenheit` — are all pure. Each takes values in and returns a value out. Nothing else changes as a result of calling them.

Pure functions have a property that makes them especially valuable: they are easy to reason about. The only thing that determines the output of a pure function is its inputs. To understand what `celsius_to_fahrenheit(100.0)` returns, you do not need to know what has been called before it, what variables exist in the session, or what the state of any external system is. You need only the inputs and the function's definition.

This property makes pure functions easy to test. Testing `celsius_to_fahrenheit` means choosing inputs and verifying outputs:

```
nex> print(celsius_to_fahrenheit(0.0))
32.0

nex> print(celsius_to_fahrenheit(100.0))
212.0

nex> print(celsius_to_fahrenheit(-40.0))
-40.0
```

No setup, no cleanup, no external dependencies. Each call is self-contained.



## Functions with Effects

Not all functions are pure. A function that prints output, reads from the console, or modifies a variable outside itself has an *effect* — an observable change to the world beyond its return value. The `greet` function from Chapter 6 is an effectful function: it prints to the output, which is a change to the outside world.

```
nex> function greet(name: String)
     do
       print("Hello, " + name)
     end
```

Effectful functions are necessary — a program that produces no effects produces no output and changes nothing, which makes it useless. But effects make functions harder to reason about and harder to test. The output of `greet` cannot be captured and checked programmatically the same way that the return value of `celsius_to_fahrenheit` can.

The practical discipline — which we will formalise in Part VI — is to separate computation from effect. Write pure functions to compute values, then write thin effectful functions that take those values and act on them. The computation can be tested directly; the effect layer is kept as simple as possible.

Compare these two approaches to a temperature reporting function:

```
nex> -- effectful throughout: harder to test
nex> function report_temperature(c: Real)
     do
       if c < 0.0 then
         print("Freezing: " + c.to_string + " deg C")
       elseif c < 15.0 then
         print("Cold: " + c.to_string + " deg C")
       else
         print("Mild or warm: " + c.to_string + " deg C")
       end
     end
```

```
nex> -- pure core, thin effect: easier to test
nex> function temperature_label(c: Real): String
     do
       if c < 0.0 then
         result := "Freezing"
       elseif c < 15.0 then
         result := "Cold"
       else
         result := "Mild or warm"
       end
     end

nex> function report_temperature(c: Real)
     do
       print(temperature_label(c) + ": " + c.to_string + " deg C")
     end
```

The second version separates the decision — which label applies to this temperature? — from the action — print the label. The decision is pure and can be tested exhaustively:

```
nex> print(temperature_label(-5.0))
Freezing

nex> print(temperature_label(10.0))
Cold

nex> print(temperature_label(20.0))
Mild or warm
```

The printing is left to the thin effectful wrapper, which is so simple it barely needs testing. This decomposition — pure core, effectful shell — is one of the most reliable habits in software engineering. The functions involved are often small. The benefit scales with the complexity of the system.



## Writing Functions That Are Easy to Test

A function is easy to test when its behaviour is fully determined by its inputs and its behaviour is stated precisely enough to verify. Several habits support this:

**One responsibility per function.** A function that computes a result and also prints it and also updates a global variable has three responsibilities mixed together. Testing the computation requires dealing with the printing and the global update. Separating these concerns — one function per responsibility — makes each piece independently testable.

**Inputs as parameters, not globals.** A function that reads variables from outside its own scope is harder to test, because testing it requires setting up that external state. A function that receives all its inputs as parameters can be called with any inputs you choose, without setup:

```
nex> -- harder to test: depends on external variable
nex> let tax_rate := 0.20
nex> function compute_tax(price: Real): Real
     do
       result := price * tax_rate
     end

nex> -- easier to test: all inputs are parameters
nex> function compute_tax(price, rate: Real): Real
     do
       result := price * rate
     end
```

The second version can be tested with any price and any rate. The first can only be tested with whatever `tax_rate` happens to be at the time.

**Output as return value, not side effect.** A function that communicates its result by assigning to an external variable, or by printing, makes that result difficult to capture and verify programmatically. A function that returns its result explicitly is testable directly:

```
nex> print(compute_tax(100.0, 0.20))
20.0
```

One line. No setup. The result is right there.

**Well-chosen examples.** When testing a function, choose inputs that cover distinct cases: the typical case, the boundary cases, and at least one case where the result is known precisely. For `compute_tax`, a typical case is a normal price and rate. A boundary case is a price of zero (the tax should also be zero). A known case might be price 100 and rate 0.10, where the result is exactly 10.0.

```
nex> print(compute_tax(100.0, 0.20))
20.0

nex> print(compute_tax(0.0, 0.20))
0.0

nex> print(compute_tax(100.0, 0.0))
0.0

nex> print(compute_tax(99.99, 0.10))
9.999
```

Each of these is a small, self-contained experiment. Together they provide evidence that the function behaves correctly across its range of inputs.



## Functions as Building Blocks

A program built from well-designed functions has a particular quality: the code that assembles the pieces is readable at a high level, without requiring the reader to follow the details of every piece. Consider a program that reads a temperature, converts it, classifies it, and reports it:

```
nex> function celsius_to_fahrenheit(c: Real): Real
     do
       result := c * 9.0 / 5.0 + 32.0
     end

nex> function temperature_label(c: Real): String
     do
       if c < 0.0 then
         result := "Freezing"
       elseif c < 15.0 then
         result := "Cold"
       elseif c < 25.0 then
         result := "Mild"
       else
         result := "Warm"
       end
     end

nex> function temperature_report(c: Real): String
     do
       let f := celsius_to_fahrenheit(c)
       let label := temperature_label(c)
       result := label + ": " + c.to_string + " deg C / " + f.to_string + " deg F"
     end
```

Now the top-level program is one line:

```
nex> print(temperature_report(22.0))
Mild: 22.0 deg C / 71.6 deg F
```

The reader of `temperature_report` does not need to know how Celsius-to-Fahrenheit conversion works or how temperature labels are determined. The function names carry that meaning. The body of `temperature_report` reads as a sequence of named steps rather than a block of arithmetic.

This is the promise of good function design: each function is a vocabulary word, and programs built from good vocabulary read like clear prose.



## Recognising When a Function Is Doing Too Much

A function that is hard to name is often doing too much. If the best name you can find is something like `process_and_format_and_print`, the function has at least three responsibilities, and each deserves its own function.

A function body that is longer than about ten to fifteen lines is a candidate for decomposition. Not every long function needs to be broken up — some computations are genuinely sequential and benefit from being in one place. But length is a signal worth examining.

A function that contains deeply nested conditionals or loops within loops is harder to test and harder to read. Extracting the inner loop or the innermost condition into its own named function often makes both the outer function and the extracted function clearer:

```
nex> -- before: inner loop mixed with outer logic
nex> function count_divisors(n: Integer): Integer
     do
       result := 0
       from
         let i := 1
       until
         i > n
       do
         if n % i = 0 then
           result := result + 1
         end
         i := i + 1
       end
     end

nex> -- after: inner check extracted
nex> function is_divisor(n, i: Integer): Boolean
     do
       result := n % i = 0
     end

nex> function count_divisors(n: Integer): Integer
     do
       result := 0
       from
         let i := 1
       until
         i > n
       do
         if is_divisor(n, i) then
           result := result + 1
         end
         i := i + 1
       end
     end
```

Both versions produce the same results. The second names the concept of divisibility explicitly, making the loop body read as: *for each i from 1 to n, if i is a divisor of n, count it*. The extracted function `is_divisor` is also independently testable, which is a benefit in itself.



## Summary

- Every function has implicit assumptions about its inputs and implicit guarantees about its outputs. Stating these — even informally — before writing the body is the fastest path to a correct implementation.
- A pure function's output depends only on its inputs. Pure functions are easier to reason about and easier to test than functions with effects.
- Separate computation from effect: write pure functions to produce values, and thin effectful functions to act on them. Test the computation directly; keep the effect layer simple.
- Functions are easy to test when they receive all their inputs as parameters, return results as values, and have one clear responsibility.
- Good function names are vocabulary. Programs built from well-named functions read like clear statements of what is happening, not like transcripts of how it is happening.
- A function that is hard to name, longer than necessary, or deeply nested is a signal to decompose. Extract inner logic into named functions; each extracted piece becomes independently testable.



## Exercises

**1.** The function `percentage(part, total: Real): Real` from Section 7.1 has an assumption: `total` must not be zero. Write a version called `safe_percentage` that returns `0.0` when `total` is zero and the normal percentage otherwise. Test it with `part = 50.0, total = 200.0` (expected: 25.0), `part = 0.0, total = 100.0` (expected: 0.0), and `part = 10.0, total = 0.0` (expected: 0.0).

**2.** Separate the following function into a pure computation and a thin effectful wrapper. The pure function should return a `String`; the effectful wrapper should print it.

```
function greet_by_time(hour: Integer)
do
  if hour < 12 then
    print("Good morning!")
  elseif hour < 18 then
    print("Good afternoon!")
  else
    print("Good evening!")
  end
end
```

Test the pure function with `hour = 9`, `hour = 14`, and `hour = 20`.

**3.** Define a function `is_prime(n: Integer): Boolean` that returns `true` if `n` is a prime number (divisible only by 1 and itself). Test it with `n = 2` (true), `n = 9` (false), `n = 17` (true), and `n = 1` (false, by convention). Write the function using `is_divisor` from Section 7.6 as a helper.

**4.** A function `count_vowels(s: String): Integer` should return the number of vowels (a, e, i, o, u, both upper and lowercase) in a string. Write it using `across` to iterate over the characters and a helper function `is_vowel(ch: Char): Boolean`. Test it with `"Hello"` (expected: 2), `"rhythm"` (expected: 0), and `"aeiou"` (expected: 5).

**5.\*** The collatz sequence starting from a positive integer `n` is defined as follows: if `n` is even, the next term is `n / 2`; if `n` is odd, the next term is `3 * n + 1`. The sequence continues until it reaches 1. For example, starting from 6: 6, 3, 10, 5, 16, 8, 4, 2, 1. Define a pure function `collatz_length(n: Integer): Integer` that returns the number of steps to reach 1 from `n`. Verify that `collatz_length(6)` is 8, `collatz_length(27)` is 111, and `collatz_length(1)` is 0.
