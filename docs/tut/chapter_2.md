# Values, Types, and Variables

Chapter 1 introduced the REPL and the first things you can do in it: print output, perform arithmetic, store results in variables. This chapter looks more carefully at the values those variables hold — what kinds of values exist in Nex, what operations each kind supports, and what happens when you need to convert one kind into another.


## Types

Every value in Nex has a type. A type is a classification that determines two things: what the value represents, and what operations are valid on it. The number `42` and the string `"42"` look similar in print, but they are different kinds of thing. You can multiply `42` by two. You cannot multiply `"42"` by two. The type is what makes this distinction precise.

Nex's four primary scalar types are:

- **`Integer`** — whole numbers, positive or negative: `0`, `42`, `-7`, `1000000`
- **`Real`** — numbers with a fractional part: `3.14`, `-0.5`, `1.0`, `2.718`
- **`Boolean`** — the truth values `true` and `false`
- **`String`** — sequences of characters enclosed in double quotes: `"hello"`, `"42"`, `""`

These four types cover the vast majority of what you will need in the early chapters. Two additional scalar types — `Integer64` for very large whole numbers, and `Decimal` for precise decimal arithmetic — exist for specialised purposes and are documented in Appendix B.



## Integers

An integer is a whole number. Integer arithmetic in Nex works as you would expect for addition, subtraction, and multiplication:

```
nex> 10 + 3
13

nex> 10 - 3
7

nex> 10 * 3
30
```

Division between two integers deserves attention. When you divide one integer by another using `/`, the result is always an integer — the fractional part is discarded:

```
nex> 10 / 3
3

nex> 7 / 2
3
```

This is called *integer division*. It is not a mistake; it is the defined behaviour for the `/` operator on integers. The `%` operator gives the remainder:

```
nex> 10 % 3
1

nex> 7 % 2
1
```

Together, `/` and `%` give you full information: `10 / 3` is `3` remainder `1`. If you want a fractional result, use `Real` values instead — Section 2.3 covers this.

Integers also have methods. Methods are operations invoked with dot notation:

```
nex> (-7).abs
7

nex> (3).max(8)
8

nex> (3).min(8)
3
```

The `abs` method returns the absolute value. The `max` and `min` methods return the larger and smaller of two values respectively. These are the same operations as arithmetic, just written differently — the dot notation makes explicit that the operation belongs to the value on its left.

Nex also provides bitwise operations on `Integer` values. These are mainly useful
for low-level work such as flags, masks, encodings, and compact state
representations. The interface uses method names rather than symbolic operators:

```nex
nex> (5).bitwise_left_shift(1)
10

nex> (6).bitwise_and(3)
2

nex> (5).bitwise_is_set(0)
true
```

The parentheses around integer literals matter. Without them, `5.bitwise_left_shift(1)`
would be read as the start of a real literal rather than as a method call. Bitwise
operations use 32-bit integer semantics; Appendix B lists the full set of methods.

One method worth knowing early is `pick`:

```
nex> (6).pick
4
```

`pick` returns a random integer in the range from zero up to but not including the value it is called on. `(6).pick` returns a random integer from 0 to 5. The result will differ each time you call it. This is useful for simulations and exercises, and we will use it in several places later in the book.



## Real Numbers

A `Real` value is a number with a fractional part. Write real literals with a decimal point:

```
nex> let pi := 3.14159
3.14159

nex> pi * 2.0
6.28318
```

Arithmetic on real numbers works as expected and always produces real results:

```
nex> 10.0 / 3.0
3.3333333333333335

nex> 7.0 / 2.0
3.5
```

Note the trailing digits in the first result. Real numbers in Nex, as in most programming languages, are represented internally as binary floating-point values. Most decimal fractions cannot be represented exactly in binary, so what you get is the closest representable approximation. For most purposes this is fine. For financial calculations requiring exact decimal arithmetic, use `Decimal` instead.

The `round` method converts a real number to the nearest integer:

```
nex> 3.6.round
4

nex> 3.2.round
3
```

Real numbers also have `abs`, `min`, and `max`:

```
nex> (-3.5).abs
3.5

nex> 1.2.max(4.7)
4.7
```



## Booleans

A `Boolean` value is either `true` or `false`. Booleans arise from comparisons:

```
nex> let x := 10
10

nex> x > 5
true

nex> x = 5
false

nex> x /= 5
true
```

In Nex, `=` tests equality and `/=` tests inequality. (The `:=` you have been using is assignment — a different operation entirely.)

Booleans can be combined with `and`, `or`, and `not`:

```
nex> true and false
false

nex> true or false
true

nex> not true
false

nex> x > 5 and x < 20
true
```

The `and` operator returns `true` only when both sides are `true`. The `or` operator returns `true` when at least one side is `true`. The `not` operator inverts a boolean value.


## Strings

A `String` is a sequence of characters. String literals are enclosed in double quotes:

```
nex> let greeting := "Hello, Nex"
Hello, Nex

nex> greeting.length
10
```

The `length` method returns the number of characters in the string.

Strings can be searched and inspected:

```
nex> greeting.contains("Nex")
true

nex> greeting.starts_with("Hello")
true

nex> greeting.index_of("N")
7
```

`index_of` returns the position of the first occurrence of its argument, counting from zero. If the argument is not found, it returns `-1`.

Substrings are extracted with `substring`, which takes a start index (inclusive) and an end index (exclusive):

```
nex> greeting.substring(0, 5)
Hello

nex> greeting.substring(7, 10)
Nex
```

Case conversion:

```
nex> greeting.to_upper
HELLO, NEX

nex> greeting.to_lower
hello, nex
```

Whitespace removal:

```
nex> let padded := "  hello  "
"  hello  "

nex> padded.trim
hello
```

Splitting a string into parts:

```
nex> let csv := "one,two,three"
 one,two,three

nex> csv.split(",")
[one, two, three]
```

`split` returns an array — we will work with arrays in Chapter 9.


## Type Annotations

When you write `let x := 10`, Nex infers that `x` is an `Integer` from the value on the right-hand side. Type inference is convenient, but writing the type explicitly is better practice:

```
nex> let x: Integer := 10
10

nex> let name: String := "Ada"
Ada

nex> let height: Real := 1.52
1.52

nex> let enrolled: Boolean := true
true
```

The annotation — the `: Type` after the variable name — makes your intention explicit. It means the variable is expected to hold a value of that type, and if you later accidentally assign the wrong type, Nex can tell you immediately. Type annotations also serve as documentation: a reader of your code knows what kind of value a variable holds without having to trace where it came from.

In the REPL, annotations are optional. In functions and class definitions — which we reach in Chapters 6 and 12 — they are required on parameters and return types. Building the habit now means less adjustment later.



## Type Conversion

Nex does not convert between types automatically in general. Each scalar type
provides conversion methods for cases where you need to change representation.
The main exception you have already seen is string concatenation with `+`: if
either operand is a string, the other operand is converted with `to_string`
internally.

Converting a number to a string:

```
nex> let age: Integer := 25
25

nex> let message: String := "Age: " + age
Age: 25

nex> message
Age: 25
```

Because the left operand is a string, Nex performs string concatenation and
converts `age` by calling its `to_string` method internally. Writing
`age.to_string` explicitly is still valid when you want to make the conversion
obvious.

Converting a string to a number:

```
nex> let s: String := "42"
42

nex> let n: Integer := s.to_integer
42

nex> n + 8
50
```

Real conversion works the same way:

```
nex> let r: Real := "3.14".to_real
3.14
```

The conversion methods only work when the string actually represents a value of the target type. Calling `.to_integer` on a string that does not contain a valid integer raises an exception:

```
nex> "hello".to_integer
Error: ...
```

This is not a design flaw — it is the language being honest. The string `"hello"` does not represent an integer, and there is no sensible integer value for Nex to return. The error fires immediately, at the point of the conversion, so you know exactly where the problem is. We return to error handling in Chapter 21; for now, the rule is simple: only call `.to_integer` and `.to_real` on strings you know contain valid numbers.



## Nil and Detachable Types

Every variable introduced so far holds a definite value. Nex enforces this by default: a variable of type `Integer` must hold an integer; it cannot hold "nothing." This guarantee is one of the most practical features of a typed language. It means that whenever you use a variable, you know it has a value.

Occasionally, however, a variable genuinely might not have a value — perhaps it represents a search result that might come back empty, or a field that has not yet been set. For these cases, Nex provides *detachable* types, written with a leading `?`:

```
nex> let maybe_name: ?String := nil
 nil

nex> maybe_name
nil
```

A detachable type can hold either a value of the declared type or `nil`. Before using a detachable variable, you must check that it is not nil:

```
nex> if maybe_name /= nil then
       print(maybe_name.length)
     end
```

This check is not optional. Calling a method on a nil value would produce an error, and Nex requires you to guard against it. The `?` in the type annotation is a signal to both the programmer and the system: this variable might be nil, and code that uses it must account for that possibility.

For most variables in early chapters, you will not need detachable types. They become more important when working with class fields and collections, which we reach in Chapters 12 and 9 respectively.



## Reading Input: A First Interactive Program

All the programs so far have been self-contained — they produce output but never ask the user for anything. Reading input requires a `Console` object:

```
nex> let con := create Console
```

`create Console` constructs a new console object and assigns it to `con`. The `create` keyword is how all objects are constructed in Nex; Chapter 12 explains it fully. For now, treat `let con := create Console` as a fixed incantation that gives you access to keyboard input.

The `read_line` method reads a line of text from the user and returns it as a string:

```
nex> let con := create Console
nex> con.print_line("What is your name?")
What is your name?
nex> let name := con.read_line
```

At the `read_line` call the program pauses and waits. Type your name and press Enter. Then:

```
nex> con.print_line("Hello, " + name + "!")
Hello, Ada!
```

Notice `con.print_line` rather than the bare `print` we used in Chapter 1. Both work; `print_line` adds a newline after the output, which is usually what you want for messages. The bare `print` function is a convenient shorthand available everywhere for quick output.

Here is the complete interactive program as you would enter it at the REPL:

```
nex> let con := create Console
nex> con.print_line("Enter your age:")
Enter your age:
nex> let input := con.read_line
nex> let age: Integer := input.to_integer
nex> let birth_year: Integer := 2025 - age
nex> con.print_line("You were born around " + birth_year.to_string)
You were born around 2000
```

This small program touches everything introduced in this chapter: a typed variable, string input, explicit type conversion from string to integer and back, and arithmetic on the result.



## Summary

- Every value in Nex has a type: `Integer`, `Real`, `Boolean`, or `String` cover most cases
- Integer division with `/` discards the fractional part; use `Real` values for fractional results
- Operations can be written as operators (`7 + 5`) or as methods (`7.plus(5)`); both are equivalent
- Type annotations on variables make intentions explicit and catch mistakes early
- Nex supports automatic string conversion during string concatenation with `+`; other conversions still use `.to_string`, `.to_integer`, `.to_real`, and related methods
- Calling `.to_integer` or `.to_real` on a non-numeric string raises an exception
- Detachable types (`?String`, `?Integer`) can hold `nil`; always check before using them
- `create Console` gives access to keyboard input via `read_line`



## Exercises

**1.** Define variables for the three sides of a right triangle — call them `a`, `b`, and `c` — with values `3`, `4`, and `5`. Verify that `a * a + b * b = c * c` by printing the result of the comparison.

**2.** The `Integer` method `pick` returns a random integer in `[0, n)`. At the REPL, call `100.pick` several times and observe the results. Then write an expression that produces a random integer between 1 and 10 inclusive.

**3.** Write a program that reads a temperature in Celsius from the console and prints the Fahrenheit equivalent. The formula is `F = C * 9.0 / 5.0 + 32.0`. Remember that `read_line` returns a `String`, so you will need to convert it before doing arithmetic.

**4.** What is the value of `"  Hello, Nex  ".trim.to_lower.length`? Work it out by hand first, then verify at the REPL by chaining the method calls. Note that methods can be chained: the result of one call becomes the receiver of the next.

**5.\*** The `Integer` method `abs` returns the absolute value of an integer. Without using `abs`, write an expression using `if ... then ... else ... end` that produces the absolute value of a variable `n`. Test it with both positive and negative values of `n`. Then confirm your result matches `n.abs`.
