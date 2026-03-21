# Functions

Every program written so far has been a flat sequence of statements entered one at a time. This works for small experiments at the REPL, but it does not scale. Real programs have parts that need to be reused in multiple places, parts that are complex enough to deserve a name, and parts that can be developed and tested independently. Functions are the mechanism that provides all three of these things.

A function is a named, reusable piece of code that takes inputs, performs a computation, and — optionally — returns a result. Defining a function does not execute it. It gives the computation a name that can be invoked later, as many times as needed, with different inputs each time.


## Defining a Function

Functions are defined with the `function` keyword:

```
nex> function greet(name: String)
     do
       print("Hello, " + name)
     end
```

This defines a function named `greet` that takes one parameter, `name`, of type `String`. The body — the code between `do` and `end` — runs when the function is called. The function is now available in the REPL session.

Call it by name, passing an argument:

```
nex> greet("Ada")
"Hello, Ada"

nex> greet("Alan")
"Hello, Alan"
```

The same computation — constructing and printing a greeting — runs twice, with different inputs, without duplicating any code.



## Parameters and Arguments

A *parameter* is the name given to an input in the function definition. An *argument* is the value passed to the function when it is called. In `greet("Ada")`, `name` is the parameter and `"Ada"` is the argument.

Parameters are declared with their types. This is not optional:

```
nex> function add(a: Integer, b: Integer)
     do
       print(a + b)
     end

nex> add(3, 7)
10
```

Nex also supports grouped parameter syntax when multiple parameters share the same type:

```
nex> function add(a, b: Integer)
     do
       print(a + b)
     end
```

Both forms are equivalent. Grouped syntax is more concise when several parameters are of the same type.

Parameters are local to the function body — they exist only for the duration of the call, and they are independent of any variables outside the function with the same name. If you have a variable `a` in your REPL session and call `add(3, 7)`, the `a` inside `add` refers to the argument `3`, not to the session variable.



## Returning a Value

A function that only produces side effects — like printing — is useful, but a function that computes and *returns* a value is more versatile. The return value is assigned to the special variable `result`:

```
nex> function double(n: Integer): Integer
     do
       result := n * 2
     end

nex> double(5)
10

nex> let x := double(8)

nex> double(x)
32
```

The return type is declared after the parameter list, separated by a colon: `: Integer`. The body assigns to `result` to specify what the function returns. When the function finishes, whatever value `result` holds is returned to the caller.

Because `double` returns a value, it can be used anywhere an expression of the right type is expected: inside `print`, on the right-hand side of an assignment, or as an argument to another function call.

If a function body does not assign to `result`, the function returns no value — it is equivalent to a `Void` return. Attempting to use the return value of such a function as an expression will produce an error.



## The `result` Variable

The `result` variable is how Nex functions return values, and it behaves slightly differently from ordinary variables. It is pre-declared with the function's return type — you do not use `let` to introduce it. You simply assign to it:

```
nex> function max(a, b: Integer): Integer
     do
       if a >= b then
         result := a
       else
         result := b
       end
     end

nex> max(3, 7)
7

nex> max(10, 4)
10
```

`result` can be assigned more than once within a function body — the last assignment before the function returns is the value the caller receives. This is useful in functions with conditional logic, where different branches compute different return values.

A common pattern is to give `result` a default value at the start of the body, then update it if necessary:

```
nex> function describe(n: Integer): String
     do
       result := "other"
       if n < 0 then
         result := "negative"
       elseif n = 0 then
         result := "zero"
       elseif n > 0 then
         result := "positive"
       end
     end

nex> describe(-3)
"negative"

nex> describe(0)
"zero"

nex> describe(42)
"positive"
```

The default `"other"` is never actually returned here because every integer is either negative, zero, or positive — but having a default means the function always returns something meaningful even if the conditional logic has a gap.



## Multiple Parameters

Functions can take any number of parameters:

```
nex> function rectangle_area(width, height: Real): Real
     do
       result := width * height
     end

nex> rectangle_area(4.0, 5.0)
20.0
```

```
nex> function clamp(value, low, high: Integer): Integer
     do
       if value < low then
         result := low
       elseif value > high then
         result := high
       else
         result := value
       end
     end

nex> clamp(15, 0, 10)
10

nex> clamp(-3, 0, 10)
0

nex> clamp(7, 0, 10)
7
```

`clamp` constrains a value to a range — returning the lower bound if the value is below it, the upper bound if above it, and the value itself otherwise. This is a function that appears frequently in graphics, game logic, and data processing.



## Functions Calling Functions

A function body can call other functions. This is how larger computations are assembled from smaller ones:

```
nex> function square(n: Integer): Integer
     do
       result := n * n
     end

nex> function sum_of_squares(a, b: Integer): Integer
     do
       result := square(a) + square(b)
     end

nex> sum_of_squares(3, 4)
25
```

`sum_of_squares` delegates the squaring work to `square` and focuses only on the addition. Each function has a single, clear responsibility. This decomposition is the subject of Chapter 7; for now, the key observation is that calling a function inside another function is natural and encouraged.

## Forward Declarations

Sometimes a function needs to call another function whose body will be defined
later. If the typechecker sees the first definition before it has seen the
later function's signature, it does not yet know what type that call should
return. The solution is to declare the later function's signature first and
define its body afterwards.

In the REPL, this matters when static checking is enabled:

```
nex> :typecheck on
"Type checking enabled. Code will be validated before execution."
```

```
nex> function normalize_name(name: String): String

nex> function greet_user(name: String): String
     do
       result := "Hello, " + normalize_name(name)
     end

nex> function normalize_name(name: String): String
     do
       result := name.trim()
     end

nex> greet_user("  Vijay  ")
"Hello, Vijay"
```

The first line is a declaration only. It introduces the function name,
parameter type, and return type. The later full definition must match that
signature exactly.

This preserves static typechecking and keeps the program structure explicit.



## Anonymous Functions

A function defined with `function` has a name and exists for the duration of the session. Sometimes a function is needed only in one place, and giving it a name would be more ceremony than it is worth. For these cases, Nex provides anonymous functions using `fn`:

```
nex> let double := fn (n: Integer): Integer do
       result := n * 2
     end

nex> double(5)
10
```

An anonymous function is a value — it can be assigned to a variable, passed as an argument, or returned from another function. The syntax is the same as a named function, with `fn` replacing `function` and no name between `fn` and the parameter list.

Anonymous functions are most useful when a function needs to be passed to another function as an argument. Suppose you wanted a function that applies any integer transformation twice:

```
nex> function apply_twice(f: Function, n: Integer): Integer
     do
       result := f(f(n))
     end

nex> apply_twice(fn (n: Integer): Integer do result := n + 3 end, 10)
16
```

The anonymous function `fn (n: Integer): Integer do result := n + 3 end` adds 3 to its argument. `apply_twice` calls it twice, so `10` becomes `13` and then `16`.

This style — passing functions as arguments — becomes more powerful as programs grow. We return to it in Chapter 7.



## When to Write a Function

A function is worth writing whenever a computation has a name, when it is used in more than one place, or when naming it would make the code that calls it clearer. These three criteria are worth examining individually.

**A computation has a name.** If you find yourself adding a comment that says "compute the shipping cost" before a block of code, that block is a function waiting to be extracted. Naming it `shipping_cost` turns the comment into an executable label. The code that calls it becomes:

```
let cost := shipping_cost(weight, distance)
```

instead of several lines of arithmetic followed by an explanatory comment.

**A computation is used in more than one place.** Copying and pasting code to reuse it creates two problems: the copied code may later need to change, and if it does, every copy must be found and updated. A function changes in one place and the change applies everywhere it is called.

**Naming it makes the call site clearer.** Consider the expression:

```
if score >= 50 and attempts <= 3 then
```

versus:

```
if passed(score, attempts) then
```

The second is clearer at a glance — `passed` communicates what the condition means rather than asking the reader to evaluate it. The function earns its existence by making the code that calls it easier to read, even if the function body itself is simple.

The threshold for writing a function should be low. Functions are not reserved for complex code. A two-line function with a good name is often more valuable than a two-line comment explaining what the code does.



## A Worked Example: Temperature Converter

Here is a small library of related functions, built up one at a time:

```
nex> function celsius_to_fahrenheit(c: Real): Real
     do
       result := c * 9.0 / 5.0 + 32.0
     end

nex> function fahrenheit_to_celsius(f: Real): Real
     do
       result := (f - 32.0) * 5.0 / 9.0
     end

nex> function describe_temperature(c: Real): String
     do
       if c < 0.0 then
         result := "freezing"
       elseif c < 15.0 then
         result := "cold"
       elseif c < 25.0 then
         result := "mild"
       else
         result := "warm"
       end
     end
```

With these three functions defined, working with temperatures becomes readable:

```
nex> let boiling := 100.0

nex> celsius_to_fahrenheit(boiling)
212.0

nex> describe_temperature(boiling)
"warm"

nex> let body_temp_f := 98.6

nex> let body_temp_c := fahrenheit_to_celsius(body_temp_f)

nex> describe_temperature(body_temp_c)
"warm"
```

Each function does one thing. The code that uses them reads like a series of clear questions: what is this in Fahrenheit, how would we describe this temperature? The answers are delegated to functions that can be developed, tested, and read independently.



## Summary

- A function is defined with `function name(parameters): return_type do ... end`
- A function signature may be declared without a body when later definitions need forward references
- Parameters are declared with their types; multiple parameters of the same type can be grouped: `(a, b: Integer)`
- The return value is assigned to the special variable `result`; the function returns whatever `result` holds when the body finishes
- A function with no `result` assignment returns no value and should not be used as an expression
- Anonymous functions are defined with `fn` and can be assigned to variables or passed as arguments
- Write a function when a computation has a name, when it is used in more than one place, or when naming it makes the code that calls it clearer
- Functions calling other functions is natural and encouraged; decomposition is how manageable programs are built from simple pieces



## Exercises

**1.** Define a function `fahrenheit_to_kelvin(f: Real): Real` that converts a Fahrenheit temperature to Kelvin. The formula is: subtract 32, multiply by 5/9, then add 273.15. Test it by verifying that 32 deg F converts to 273.15 K and 212 deg F converts to 373.15 K.

**2.** Define a function `is_leap_year(year: Integer): Boolean` that returns `true` if `year` is a leap year. A year is a leap year if it is divisible by 4, except that years divisible by 100 are not leap years, unless they are also divisible by 400. Test with 2000 (true), 1900 (false), 2024 (true), and 2023 (false).

**3.** Define a function `digit_sum(n: Integer): Integer` that returns the sum of the digits of a non-negative integer. For example, `digit_sum(123)` should return `6`. Hint: use `%` to extract the last digit and `/` to remove it, repeating until the number is zero.

**4.** Define two functions: `min3(a, b, c: Integer): Integer` that returns the smallest of three integers, and `max3(a, b, c: Integer): Integer` that returns the largest. Then define `range3(a, b, c: Integer): Integer` that returns `max3 - min3` for the same three values. Write `range3` by calling `min3` and `max3` rather than duplicating their logic.

**5.\*** An anonymous function can be stored in a variable and called through that variable. Define a variable `transform` that holds an anonymous function from `Integer` to `Integer`. Assign it first to a doubling function, call it with several values, then reassign it to a squaring function and call it again with the same values. What does this demonstrate about functions as values?
