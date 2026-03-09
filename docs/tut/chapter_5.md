# Repetition

A program that can only execute each statement once is severely limited. Most useful programs repeat operations: process every item in a collection, keep asking for input until a valid answer is given, compute a result by applying the same transformation many times. This chapter introduces the three constructs Nex provides for repetition, and the habits that make loops correct by construction.


## The `from ... until ... do ... end` Loop

The primary loop in Nex is the `from ... until ... do ... end` loop. It has four parts:

- **`from`** — initialisation: code that runs once before the loop begins
- **`until`** — termination condition: a `Boolean` expression checked before each iteration
- **`do`** — body: code that runs on each iteration
- **`end`** — marks the end of the loop

```
nex> from
       let i := 1
    until
       i > 5
    do
       print(i)
       i := i + 1
    end
1
2
3
4
5
```

Read this as: *starting with `i` equal to 1, until `i` exceeds 5, print `i` and then increment it*. The termination condition is checked before each iteration. When `i` reaches 6, the condition `i > 6` becomes `true` and the loop stops without executing the body again.

The structure is more verbose than loops in some other languages, and deliberately so. The separation of initialisation, condition, and body into named sections makes each part explicitly visible. When you read a `from ... until ... do` loop, you immediately know where the setup is, what the stopping condition is, and what the body does. Nothing is implicit.



## How a Loop Executes

It is worth tracing through a loop step by step to build a precise mental model of how execution proceeds.

```
nex> from
       let total := 0
       let i := 1
    until
       i > 4
    do
       total := total + i
       i := i + 1
    end
nex> print(total)
10
```

The execution proceeds as follows:

1. **Initialisation:** `total` is set to `0`, `i` is set to `1`.
2. **Check condition:** `i > 4` is `false` (1 is not greater than 4). Enter body.
3. **Body:** `total` becomes `0 + 1 = 1`. `i` becomes `2`.
4. **Check condition:** `i > 4` is `false`. Enter body.
5. **Body:** `total` becomes `1 + 2 = 3`. `i` becomes `3`.
6. **Check condition:** `i > 4` is `false`. Enter body.
7. **Body:** `total` becomes `3 + 3 = 6`. `i` becomes `4`.
8. **Check condition:** `i > 4` is `false`. Enter body.
9. **Body:** `total` becomes `6 + 4 = 10`. `i` becomes `5`.
10. **Check condition:** `i > 4` is `true`. Loop ends.

After the loop, `total` holds `10` — the sum of 1 through 4. Tracing through a loop like this is tedious on paper but invaluable when a loop is not behaving as expected. The discipline of knowing exactly what state the loop is in at each step is what separates confident debugging from guessing.



## Common Loop Patterns

Several patterns appear repeatedly across many programs. Recognising them makes writing new loops easier.

### Counting

Counting from a starting value to an ending value is the most common loop pattern:

```
nex> from
       let i := 1
    until
       i > 10
    do
       print(i)
       i := i + 1
    end
```

Counting down is the same pattern with the direction reversed:

```
nex> from
       let i := 10
    until
       i < 1
    do
       print(i)
       i := i - 1
    end
```

### Accumulation

Accumulating a result by building it up one step at a time:

```
nex> from
       let product := 1
       let i := 1
    until
       i > 5
    do
       product := product * i
       i := i + 1
    end
nex> print(product)
120
```

This computes 5 factorial: 1 * 2 * 3 * 4 * 5 = 120. The accumulator (`product`) starts at the identity value for multiplication (1) and is multiplied by each successive value of `i`.

### Searching

Stopping early when a condition is met:

```
nex> let target := 7
=> 7

nex> let found := false
=> false

nex> from
       let i := 1
    until
       i > 10 or found
    do
       if i = target then
         found := true
       end
       i := i + 1
    end

nex> print(found)
true
```

The termination condition `i > 10 or found` stops the loop either when the range is exhausted or when the target is found, whichever comes first. This is more honest than looping to completion and checking afterward — it stops doing work as soon as the work is done.



## The `repeat` Loop

When you need to execute a block of code a fixed number of times without a counter variable, `repeat` is more concise than `from ... until ... do`:

```
nex> repeat 3 do
       print("hello")
    end
hello
hello
hello
```

`repeat n do ... end` executes the body exactly `n` times. The count must be a non-negative integer. There is no loop variable — if you need access to the iteration number, use `from ... until ... do` with an explicit counter instead.

`repeat` is most useful for simple repeated actions where the count matters but the iteration number does not:

```
nex> let con := create Console
nex> repeat 5 do
       con.print_line("")
    end





```



## The `across` Loop

The `across` loop iterates over a collection — an array, a string, or a map — visiting each element in turn:

```
nex> across [10, 20, 30] as x do
       print(x)
    end
10
20
30
```

The variable `x` is bound to each element successively. Arrays are introduced fully in Chapter 9; for now, the bracket syntax `[10, 20, 30]` creates a sequence of three integers.

`across` also works on strings, iterating over each character:

```
nex> across "hello" as ch do
       print(ch)
    end
h
e
l
l
o
```

And on maps, which we cover in Chapter 10.

The `across` loop is the right choice whenever you need to process every element of a collection in order. It is more direct than a `from ... until ... do` loop with an index variable, and it removes the possibility of off-by-one errors in the index management. Whenever you find yourself writing a loop whose body accesses elements of a collection by index, consider whether `across` would express the same intent more clearly.



## Off-by-One Errors

The most common loop mistake is the off-by-one error: a loop that runs one iteration too many or one too few. It is common enough to have its own name, and it is worth examining carefully.

Consider printing the numbers from 1 to 5. There are several ways to write the termination condition:

```
nex> -- correct: prints 1, 2, 3, 4, 5
nex> from let i := 1 until i > 5 do print(i) i := i + 1 end

nex> -- one too few: prints 1, 2, 3, 4
nex> from let i := 1 until i >= 5 do print(i) i := i + 1 end

nex> -- one too many: prints 1, 2, 3, 4, 5, 6
nex> from let i := 1 until i > 6 do print(i) i := i + 1 end
```

The difference between `i > 5` and `i >= 5` as the termination condition is a single character, but it changes which values the loop processes. When writing a loop, ask: what is the last value `i` should take? Then write the condition that allows that value but excludes the next one.

A useful check: trace through the loop mentally for the first and last expected iterations. Does the body execute for the first value? Does it execute for the last? Does the condition stop the loop after the last iteration and before executing one more? If all three answers are yes, the boundary conditions are correct.



## Infinite Loops

A loop whose termination condition never becomes `true` runs forever. This is almost always a mistake:

```
nex> -- do not run this
nex> from
       let i := 1
    until
       i > 5
    do
       print(i)
       -- forgot to increment i
    end
```

Without `i := i + 1` in the body, `i` stays at `1` forever, `i > 5` is always `false`, and the loop never terminates. If you accidentally run a loop like this in the REPL, interrupt it with `Ctrl-C`.

The condition for a terminating loop is that the body must make progress toward the termination condition on every iteration. For a counting loop, progress means the counter moves closer to its boundary. For a searching loop, progress means either the target is found or the search space shrinks. A body that does not change the variables involved in the termination condition cannot make progress, and the loop will not terminate.

Later in the book, when we introduce loop contracts, we will see a formal way to state and verify this progress requirement. For now, the discipline is: after writing a loop body, ask whether the body changes the variables in the termination condition in a way that will eventually make that condition true.



## Nested Loops

Loops can be nested — a loop inside a loop:

```
nex> from
       let i := 1
    until
       i > 3
    do
       from
         let j := 1
       until
         j > 3
       do
         print(i.to_string + "," + j.to_string)
         j := j + 1
       end
       i := i + 1
    end
1,1
1,2
1,3
2,1
2,2
2,3
3,1
3,2
3,3
```

The outer loop runs three times. On each run of the outer loop, the inner loop runs three times in full. Total iterations: nine. The inner loop's variables (`j`) are independent of the outer loop's variables (`i`) — each has its own counter, its own condition, its own body.

Nested loops are useful for working with two-dimensional structures: grids, tables, pairs of elements. The number of iterations multiplies: a loop of `m` iterations nested inside a loop of `n` iterations produces `m * n` total iterations. For large values of `m` and `n`, this grows quickly. We will return to this observation in Part III when we discuss algorithm cost.



## A Worked Example: Number Guessing Game

The following program combines a loop with conditional logic to make a simple interactive game. It generates a random target number and asks the player to guess it, giving feedback until the guess is correct.

```
nex> let con := create Console
nex> let target := 10.pick + 1
nex> let guess := 0
nex> let attempts := 0

nex> from
       -- nothing to initialise here
    until
       guess = target
    do
       con.print_line("Guess a number between 1 and 10:")
       guess := con.read_line.to_integer
       attempts := attempts + 1
       if guess < target then
         con.print_line("Too low")
       elseif guess > target then
         con.print_line("Too high")
       end
    end

nex> con.print_line("Correct! You took " + attempts.to_string + " attempts.")
```

Several things worth noting. The `from` section is empty — all variables are initialised before the loop. The termination condition `guess = target` becomes `true` as soon as the player guesses correctly. The body reads a line, converts it to an integer with `.to_integer`, increments the attempt counter, and gives directional feedback. After the loop, the number of attempts is reported.

This is a pattern you will see often: a loop that continues until some goal is achieved, where each iteration brings the program closer to that goal by taking input from the user or progressing through a computation.



## Summary

- `from ... until condition do ... end` is the primary loop: initialise in `from`, state the stopping condition in `until`, perform work in `do`
- The termination condition is checked before each iteration; when it is `true`, the loop does not execute its body
- `repeat n do     end` executes a body exactly `n` times when the iteration number is not needed
- `across collection as variable do     end` iterates over every element of an array, string, or map
- Off-by-one errors arise from incorrect boundary conditions; verify by tracing the first and last expected iterations
- A loop must make progress toward its termination condition on every iteration; a body that does not change the relevant variables will loop forever
- Nested loops execute their bodies `m * n` times for an outer loop of `m` iterations and an inner loop of `n` iterations



## Exercises

**1.** Write a loop that prints the squares of the integers from 1 to 10: `1, 4, 9, 16,    `. Use the pattern `i * i` for the square.

**2.** Write a loop that computes the sum of all even integers from 2 to 100 inclusive. Print the result. (The answer is 2550.)

**3.** Write a loop that reads integers from the console until the user enters `0`, then prints the count of positive numbers entered and the count of negative numbers entered. Do not count the `0` itself.

**4.** The Fibonacci sequence starts with 1 and 1, and each subsequent term is the sum of the two preceding terms: 1, 1, 2, 3, 5, 8, 13, 21,     Write a loop that prints the first 15 terms. You will need two variables to track the last two terms and a third to compute the next one.

**5.\*** Write a program using nested loops that prints a multiplication table for integers from 1 to 5. Each row should be on one line, with values separated by a tab character `"\t"`. The output should look like:

```
1	2	3	4	5
2	4	6	8	10
3	6	9	12	15
4	8	12	16	20
5	10	15	20	25
```

Use `print` (without a newline) to build each row, and `con.new_line` to end each row. You will need `create Console` for `new_line`.
