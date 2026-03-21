# Expressions

Every computation in a program is built from expressions. Understanding what an expression is — and how expressions are evaluated — is the foundation for everything that follows. This chapter examines expressions carefully: how they are constructed, how Nex decides the order in which to evaluate them, and how they differ from the statements that surround them.


## What an Expression Is

An expression is a piece of code that produces a value. `42` is an expression — it produces the integer forty-two. `3 + 4` is an expression — it produces seven. `x > 5` is an expression — it produces either `true` or `false`. `"hello".length` is an expression — it produces an integer.

Expressions can be combined into larger expressions. `(3 + 4) * 2` is an expression built from two smaller expressions. `x > 5 and x < 20` is an expression built from two comparison expressions joined by `and`. The value of a compound expression depends on the values of its parts.

A statement, by contrast, is an instruction that causes something to happen. `print("hello")` is a statement — it causes output to be written. `let x := 10` is a statement — it causes a variable to be introduced. `x := x + 1` is a statement — it causes a variable to be updated. Statements do things; expressions produce values.

The distinction matters because expressions and statements can be combined in specific ways. The right-hand side of an assignment must be an expression — something that produces a value. The body of a `print` call must be an expression. A condition in an `if` statement must be an expression that produces a `Boolean`. Putting a statement where an expression is required, or an expression where a statement is required, is a structural error.

In practice, the boundary between expressions and statements in Nex is clean. If you are asking "what does this produce?", you are thinking about an expression. If you are asking "what does this do?", you are thinking about a statement.



## Arithmetic Expressions

The arithmetic operators in Nex are:

| Operator | Meaning | Example | Result |
|---|---|---|---|
| `+` | Addition | `10 + 3` | `13` |
| `-` | Subtraction | `10 - 3` | `7` |
| `*` | Multiplication | `10 * 3` | `30` |
| `/` | Division | `10 / 3` | `3` |
| `%` | Remainder | `10 % 3` | `1` |
| `^` | Exponentiation | `2 ^ 8` | `256` |
| `-` (unary) | Negation | `-7` | `-7` |

Try these at the REPL:

```
nex> 2 + 3 * 4
14

nex> (2 + 3) * 4
20

nex> 2 ^ 10
1024

nex> -5 + 3
-2
```

The unary minus — a `-` applied to a single value rather than between two values — negates its operand. `-5` is not the subtraction of five from nothing; it is the integer negative five.



## Operator Precedence

When an expression contains more than one operator, Nex must decide which to evaluate first. The rules that govern this are called *operator precedence*. They follow the conventions of ordinary mathematics:

1. Unary minus (`-`) — highest precedence
2. Exponentiation (`^`)
3. Multiplication, division, remainder (`*`, `/`, `%`)
4. Addition, subtraction (`+`, `-`)
5. Comparison (`<`, `<=`, `>`, `>=`)
6. Equality (`=`, `/=`)
7. Logical `and`
8. Logical `or` — lowest precedence

Operators at a higher level in this list bind more tightly than those lower down. So `2 + 3 * 4` is evaluated as `2 + (3 * 4)`, giving `14`, not `(2 + 3) * 4`, which would give `20`.

Verify a few cases at the REPL:

```
nex> 2 + 3 * 4
14

nex> 10 - 2 - 3
5

nex> 2 ^ 3 ^ 2
64
```

The second example shows *left associativity*: when the same operator appears twice, Nex evaluates left to right. `10 - 2 - 3` is `(10 - 2) - 3`, which is `5`, not `10 - (2 - 3)`, which would be `11`.

The third example shows that `^` is also left-associative in Nex: `2 ^ 3 ^ 2` is `(2 ^ 3) ^ 2 = 8 ^ 2 = 64`. This is worth knowing because some languages treat exponentiation as right-associative, which would give `2 ^ (3 ^ 2) = 2 ^ 9 = 512` — a very different result. If you intend right-to-left evaluation, write the parentheses explicitly.

**The practical rule:** when in doubt, use parentheses. Parentheses are free and make your intentions unambiguous. An expression that requires the reader to recall the precedence table to understand it is an expression that should have parentheses.



## Comparison Expressions

Comparison operators produce `Boolean` values:

| Operator | Meaning | Example | Result |
|---|---|---|---|
| `=` | Equal | `5 = 5` | `true` |
| `/=` | Not equal | `5 /= 3` | `true` |
| `<` | Less than | `3 < 5` | `true` |
| `<=` | Less than or equal | `5 <= 5` | `true` |
| `>` | Greater than | `5 > 3` | `true` |
| `>=` | Greater than or equal | `3 >= 5` | `false` |

Try these:

```
nex> 10 = 10
true

nex> 10 = 11
false

nex> 10 /= 11
true

nex> 3 < 5
true

nex> 5 <= 5
true
```

Comparison operators sit below arithmetic operators in the precedence table, so arithmetic is evaluated first:

```
nex> 2 + 3 = 5
true

nex> 2 + 3 > 4
true
```

Both of these evaluate the arithmetic expression first — `2 + 3` becomes `5` — then perform the comparison.

Strings can also be compared. String comparison is lexicographic — the same ordering you would find in a dictionary:

```
nex> "apple" < "banana"
true

nex> "zebra" > "ant"
true

nex> "cat" = "cat"
true
```

Lexicographic order compares strings character by character, using the character values. Uppercase letters have lower values than lowercase letters in this ordering, so `"Zoo" < "ant"` is `true`. When comparing strings for order in user-facing contexts, be aware of this distinction.



## Boolean Expressions

Boolean expressions combine `true` and `false` values using `and`, `or`, and `not`.

**`and`** is `true` only when both sides are `true`:

```
nex> true and true
true

nex> true and false
false

nex> false and false
false
```

**`or`** is `true` when at least one side is `true`:

```
nex> true or false
true

nex> false or false
false
```

**`not`** inverts a boolean:

```
nex> not true
false

nex> not false
true
```

These operators are most useful for combining comparison expressions:

```
nex> let age := 17

nex> age >= 13 and age <= 17
true

nex> age < 13 or age > 17
false
```

The first expression checks whether `age` falls within a range. The second checks whether it falls outside. Both are single expressions that produce a single `Boolean` value.

Precedence applies to boolean operators too. `and` binds more tightly than `or`, so:

```
nex> true or false and false
true
```

This evaluates as `true or (false and false)`, which is `true or false`, which is `true`. If you meant `(true or false) and false`, which would be `false`, you must write the parentheses. When `and` and `or` appear in the same expression, always add parentheses to make the grouping explicit.



## String Concatenation

Strings are joined with `+`:

```
nex> let first := "Hello"

nex> let second := "Nex"

nex> first + ", " + second + "!"
"Hello, Nex!"
```

The `+` operator on strings is concatenation — it produces a new string that is the two operands joined end to end. It is left-associative, so `"a" + "b" + "c"` is `("a" + "b") + "c"`, which is `"abc"`.

If either operand of `+` is a string, Nex performs string concatenation. Any
non-string operand is converted by calling its `to_string` method internally:

```
nex> let count: Integer := 3

nex> "Found " + count + " results"
"Found 3 results"
```

This pattern — arithmetic value incorporated into a message — appears constantly
in real programs. You can still write `.to_string` explicitly when that makes
the intent clearer, especially in longer chained expressions.



## Expressions Involving Method Calls

Method calls are expressions. The call `n.abs` produces a value — the absolute value of `n` — just as `n * 2` does. This means method calls can appear anywhere an expression is expected:

```
nex> let n := -5

nex> n.abs * 2
10

nex> n.abs = 5
true
```

And method calls can be chained — the value produced by one call becomes the receiver of the next:

```
nex> "  hello  ".trim.to_upper.length
5
```

Reading left to right: take the string `"  hello  "`, trim the whitespace to get `"hello"`, convert to uppercase to get `"HELLO"`, then get the length, which is `5`. Each step in the chain is an expression that produces a value; the final value is what `print` receives.

Chains like this are readable when each step has a clear, single purpose. When a chain becomes long or includes conditional logic, it is usually clearer to break it into named intermediate variables.



## Building Complex Expressions

Real programs rarely deal with simple two-operand expressions. More often, expressions are built from several parts. Consider computing the body mass index (BMI) — a person's weight in kilograms divided by the square of their height in metres:

```
nex> let weight: Real := 70.0

nex> let height: Real := 1.75

nex> let bmi: Real := weight / (height * height)

nex> "BMI: " + bmi.round.to_string
"BMI: 23"
```

The expression `weight / (height * height)` is a single expression with three sub-expressions. The parentheses ensure the multiplication happens before the division. Without them, `/` and `*` have equal precedence and are evaluated left to right: `weight / height * height` would give `weight / height` first, then multiply by `height` again — wrong.

The expression `bmi.round.to_string` chains two method calls. `round` converts the real number to the nearest integer; `to_string` converts that integer to a string suitable for concatenation.



## Expressions and Statements Together

The distinction between expressions and statements governs how Nex programs are structured. An assignment statement has an expression on the right:

```
let result := weight / (height * height)
```

A `print` call takes an expression as its argument:

```
print("BMI: " + bmi.round.to_string)
```

The condition of an `if` statement is a `Boolean` expression:

```
if age >= 18 then
  print("adult")
end
```

In each case, the expression does the computing and the statement does the acting. Understanding which side of this line a piece of code belongs to is a reliable guide to where it can go and what it is allowed to do.

A common source of confusion for beginners is treating a statement as though it produced a value. `let x := 10` does not produce a value; it performs an action. You cannot write `print(let x := 10)` and expect it to print `10`. The assignment is a statement, not an expression — it belongs on a line of its own, not inside another expression.



## Summary

- An expression produces a value. A statement performs an action. The right-hand side of an assignment and the arguments to `print` and other calls must be expressions.
- Arithmetic operators follow conventional mathematical precedence: exponentiation before multiplication, multiplication before addition.
- When the precedence is unclear, use parentheses. They make intentions unambiguous.
- Comparison operators (`=`, `/=`, `<`, `<=`, `>`, `>=`) produce `Boolean` values. Arithmetic is evaluated before comparisons.
- Boolean operators `and`, `or`, and `not` combine `Boolean` values. `and` binds more tightly than `or`; use parentheses when both appear in the same expression.
- String concatenation uses `+`. If either operand is a string, the other is converted with `to_string` automatically.
- Method calls are expressions and can be chained. The value produced by one call becomes the receiver of the next.



## Exercises

**1.** Without running any code, determine the value of each expression, then verify at the REPL. For the last one, remember that `^` is left-associative in Nex:

   - `2 + 3 * 4 - 1`
   - `(2 + 3) * (4 - 1)`
   - `10 / 3 + 10 % 3`
   - `2 ^ 2 ^ 3`

**2.** Write a single boolean expression that is `true` when a variable `n` is both greater than zero and even (divisible by two with no remainder). Test it with several values of `n`.

**3.** The quadratic formula gives the roots of `a*x*x + b*x + c = 0` as `(-b + sqrt) / (2 * a)` and `(-b - sqrt) / (2 * a)`, where `sqrt` is the square root of `b*b - 4 * a * c`. Nex does not have a built-in square root, but `n ^ 0.5` computes it for positive `n`. For `a = 1.0`, `b = -5.0`, `c = 6.0`, compute both roots and print them. (The answers should be `3.0` and `2.0`.)

**4.** Write an expression that takes a string variable `s` and produces a new string that is `s` trimmed, with its first character uppercased and the rest lowercased. Hint: use `substring` to separate the first character from the rest, and `to_upper`/`to_lower` on each part, then concatenate. Test it on `"  hELLO wORLD  "`.

**5.\*** The expression `a and not b or not a and b` represents *exclusive or* — it is `true` when exactly one of `a` and `b` is `true`, and `false` when both are the same. Verify this by testing all four combinations of `true` and `false` for `a` and `b`. Then add parentheses to make the precedence explicit, and confirm that the result is unchanged.
