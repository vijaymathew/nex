# Your First Programs

The best way to learn a programming language is to use it. Not to read about it, not to watch someone else use it, but to type things and see what happens. This chapter introduces the Nex REPL — the interactive environment where you will spend the first part of this book — and the first programs you will write in it.


## The REPL

REPL stands for Read-Eval-Print Loop. The name describes exactly what it does: it reads what you type, evaluates it, prints the result, and waits for you to type something else. This loop continues until you tell it to stop.

Start the Nex REPL by running:

```bash
nex
```

Or, if you are using the browser-based IDE, click the REPL tab. Either way, you will see a prompt:

```
nex>
```

That prompt is an invitation. The REPL is waiting for input.

Think of the REPL as a laboratory. A laboratory is not a place where you go to demonstrate things you already know. It is a place where you try things to find out what happens. The right attitude at a REPL is curiosity rather than certainty. Type something. See what it does. If it does not do what you expected, that is interesting — it means there is something to learn.



## Printing Output

The simplest thing a Nex program can do is print something. Try this:

```
nex> print("Hello, Nex")
"Hello, Nex"
```

The `print` function takes a value and writes it to the output. The text `"Hello, Nex"` is a *string* — a sequence of characters enclosed in double quotes. The REPL prints the result on the next line and then shows the prompt again, waiting for more input.

Try a few variations:

```
nex> print("Hello, world")
"Hello, world"

nex> print("What is your name?")
"What is your name?"
```

Numbers do not need quotes:

```
nex> print(42)
42

nex> print(3.14)
3.14
```

Integers can also be written in binary, octal, or hexadecimal:

```
nex> 0b1010
10

nex> 0o10
8

nex> 0xFF
255
```

Use lowercase prefixes `0b`, `0o`, and `0x`. You may insert `_` between digits to make long literals easier to read: `0b1111_0000`, `1_000_000`, `0xFF_AA_33`.

The difference between `print(42)` and `print("42")` matters. The first prints the number forty-two. The second prints a string that happens to contain the characters four and two. They may look the same in the output, but they are different things, and that difference will matter later.



## Comments

A comment is a line — or part of a line — that the interpreter ignores. Comments are for human readers, not for the computer.

In Nex, comments start with `--`:

```
nex> -- This line does nothing
nex> print("But this one does")
"But this one does"
```

Everything from `--` to the end of the line is a comment. You can put a comment at the end of a line that also contains code:

```
nex> print("Hello") -- greet the user
"Hello"
```

Comments become important as programs get longer. A comment that explains *why* a piece of code does what it does is more useful than one that merely describes what it does — the code already says what it does.



## Arithmetic

The REPL can evaluate arithmetic expressions directly. Try these:

```
nex> 10 + 3
13

nex> 10 - 3
7

nex> 10 * 3
30

nex> 10 / 3
3
```

The last result may surprise you. In Nex, dividing one integer by another produces an integer — the fractional part is discarded. This is called *integer division*. If you want the fractional part, use real numbers:

```
nex> 10.0 / 3.0
3.3333333333333335
```

The `%` operator gives the remainder after division:

```
nex> 10 % 3
1
```

And `^` raises a number to a power:

```
nex> 2 ^ 8
256
```

Parentheses control the order of evaluation:

```
nex> 2 + 3 * 4
14

nex> (2 + 3) * 4
20
```

Without parentheses, multiplication and division are evaluated before addition and subtraction — the conventional mathematical order of precedence. When in doubt, add parentheses. They cost nothing and make intentions clear.



## Variables

A variable is a name for a value. You introduce a variable in Nex with `let`:

```
nex> let x := 10
10

nex> x
10
```

The `:=` symbol means *becomes*. After `let x := 10`, the name `x` refers to the value `10`. The REPL confirms the assignment by printing `10`.

Variables can be used in expressions:

```
nex> let y := x + 5
15

nex> y
15

nex> x + y
25
```

Once a variable is introduced with `let`, you can update it using plain `:=` without `let`:

```
nex> x := 20
20

nex> x
20
```

The distinction matters: `let x := 10` introduces a new variable named `x`. `x := 20` updates an existing variable. Using `let` when you mean to update, or omitting it when you mean to introduce, will cause an error.

Variable names in Nex are case-sensitive. `x`, `X`, and `total_cost` are three different names. By convention, variable names use lowercase letters with underscores between words: `total_cost`, `start_location`, `item_count`.



## Types

Every value in Nex has a type — a classification that determines what operations are valid on it. The basic scalar types are:

- `Integer` — whole numbers: `0`, `42`, `-7`
- `Integer` also supports explicit base prefixes: `0b1010`, `0o755`, `0xFF`
- `Integer64` — large whole numbers that may exceed the `Integer` range
- `Real` — numbers with a fractional part: `3.14`, `-0.5`, `1.0`
- `Decimal` — decimal numbers for precise base-10 arithmetic
- `Char` — a single character: `#a`, `#65`
- `Boolean` — the values `true` and `false`
- `String` — sequences of characters: `"hello"`, `"42"`, `""`

You can declare the type of a variable explicitly:

```
nex> let name: String := "Ada"
"Ada"

nex> let age: Integer := 12
12

nex> let height: Real := 1.52
1.52

nex> let enrolled: Boolean := true
true

nex> let initial: Char := #65
#A

nex> #10024
-- will print sparkles if your console supports unicode
```

In the REPL, type annotations are optional by default — Nex infers the type from the value on the right-hand side. But writing them out is a good habit. It makes your intentions explicit, catches mistakes early, and will become essential when we start writing functions and classes.

If you enable strict checking with `:typecheck on`, type annotations on REPL `let` bindings should be treated as mandatory. In that mode, being explicit about the intended type keeps later checks predictable and avoids ambiguous interactive state.

You cannot mix types arbitrarily. Arithmetic operators still require numeric
operands, for example. But string concatenation is special: if either side of
`+` is a string, Nex concatenates the values and converts the non-string side by
calling its `to_string` method internally.



## String Operations

Strings can be joined together with `+`:

```
nex> let greeting := "Hello, " + name
"Hello, Ada"

nex> greeting
"Hello, Ada"
```

If either side of `+` is a string, Nex performs string concatenation. A
non-string operand is converted internally using `to_string`:

```
nex> "Age: " + age
"Age: 12"
```

This is one of the most common operations in any program: constructing a message
from a mix of fixed text and variable values. You can still write `.to_string`
explicitly when you want to make that conversion visible in the code.



## REPL Commands

The REPL has a small set of built-in commands for managing your session. They start with a colon:

```
nex> :help
```

This prints the list of available commands. The ones you will use most often are:

```
nex> :vars
Defined variables:
  • x = 20
  • y = 15
  • name = Ada
  • age = 12
  • height = 1.52
  • enrolled = true
  • greeting = Hello, Ada
```

`:vars` shows every variable currently defined in your session, with its current value. This is useful when you lose track of what you have defined.

```
nex> :clear
Context cleared.
```

`:clear` removes all variables and class definitions from the session. The REPL starts fresh. Use this when you want to begin a new experiment without the accumulated state of the previous one.

```
nex> :quit
Goodbye!
```

`:quit` ends the session.



## Reading What the REPL Tells You

When something goes wrong, the REPL reports it. Learning to read these reports is a skill, and it is worth developing early.

If you use a variable that has not been defined:

```
nex> print(total)
Error: undefined variable: total
```

The error names the problem — *undefined variable* — and identifies which name caused it. The fix is either to define `total` before using it, or to check whether you have mistyped the name.

If you mistype a keyword or construct something the parser cannot understand:

```
nex> pint("hello")
Error: undefined variable: pint
```

Nex interpreted `pint` as a variable name, found no such variable, and reported it. The error message is technically accurate — `pint` is indeed an undefined variable — but the underlying cause is a typo. When an error message seems puzzling, re-read the line you typed and look for the discrepancy between what you wrote and what you intended.

The discipline of reading error messages carefully rather than guessing at a fix is one of the most valuable habits a programmer can develop. The error message is the system's best attempt to tell you precisely what went wrong. It is almost always worth reading it fully before changing anything.



## A First Complete Program

Everything in this chapter so far has been fragments — individual expressions and statements entered one at a time. Here is a small but complete program: it introduces several variables, performs some computation, and prints a result.

```
nex> let celsius := 100.0
100.0

nex> let fahrenheit := celsius * 9.0 / 5.0 + 32.0
212.0

nex> celsius + " degrees Celsius is " + fahrenheit + " degrees Fahrenheit"
"100.0 degrees Celsius is 212.0 degrees Fahrenheit"
```

Try it with a different starting value. Change `100.0` to `0.0` and recompute `fahrenheit`. Change it to `37.0` — normal body temperature in Celsius — and see what Fahrenheit temperature you get.

This is the edit-run cycle: write something, observe the result, adjust, repeat. At the REPL, each step takes seconds. The faster this cycle runs, the faster you learn.



## Summary

This chapter introduced the fundamental tools for working in Nex:

- `print` outputs a value to the screen
- Comments begin with `--` and are ignored by the interpreter
- Arithmetic follows conventional precedence; parentheses override it
- `let name := value` introduces a variable; `name := value` updates one
- The basic scalar types are `Integer`, `Integer64`, `Real`, `Decimal`, `Char`, `Boolean`, and `String`
- In the REPL, type annotations are optional by default, but with `:typecheck on` you should write them explicitly on `let` bindings
- Strings are joined with `+`
- REPL commands — `:vars`, `:clear`, `:quit` — manage the session
- Error messages are information; read them before changing anything



## Exercises

**1.** At the REPL, compute the number of seconds in a week. Use variables to hold the number of seconds in a minute, minutes in an hour, hours in a day, and days in a week. Print the result with a descriptive message.

**2.** The area of a circle with radius *r* is `pi * r * r`. Define a variable `radius` with the value `5.0` and a variable `pi` with the value `3.14159`. Compute and print the area.

**3.** Define two string variables, `first_name` and `last_name`, and print a greeting that uses both: `"Hello, Ada Lovelace"` (or whatever names you choose). Then use `:vars` to confirm both variables are in the session.

**4.** What happens if you type `let x := 10` and then `let x := 20`? Does the second `let` update the existing variable or introduce a new one? Try it and observe the result.

**5.\*** The formula to convert a temperature from Fahrenheit to Celsius is `(F - 32) * 5 / 9`. Write the inverse of the conversion program from Section 1.10: start with a Fahrenheit temperature, convert it to Celsius, and print both values in a readable message. Verify your result by converting 32 deg F (the freezing point of water) and 98.6 deg F (body temperature).
