# Making Decisions

Programs would not be very useful if they always did the same thing regardless of their inputs. The ability to choose between different courses of action — based on conditions that are evaluated at runtime — is what makes programs responsive to the world they operate in. This chapter covers the three constructs Nex provides for conditional execution: the `if` statement for general branching, the `when` expression for inline choices, and `case` for selecting among multiple specific values.


## The `if` Statement

The simplest form of a conditional executes a block of code only when a condition is true:

```
nex> let temperature := 35

nex> if temperature > 30 then
       print("hot")
     end
hot
```

The condition — `temperature > 30` — is a `Boolean` expression. If it evaluates to `true`, the code between `then` and `end` runs. If it evaluates to `false`, nothing happens and execution continues after `end`.

The `else` branch provides an alternative when the condition is false:

```
nex> let score := 45

nex> if score >= 50 then
       print("pass")
     else
       print("fail")
     end
fail
```

Exactly one of the two branches runs — either the `then` branch or the `else` branch, never both, never neither.

For more than two cases, use `elseif`:

```
nex> let score := 72

nex> if score >= 90 then
       print("A")
    elseif score >= 80 then
       print("B")
    elseif score >= 70 then
       print("C")
    elseif score >= 60 then
       print("D")
    else
       print("F")
    end
C
```

Nex evaluates the conditions from top to bottom and executes the first branch whose condition is `true`. Once a branch is taken, the remaining conditions are not evaluated. This matters: if `score` is `95`, the first condition `score >= 90` is `true`, and the grade is `A` — the conditions `score >= 80` and `score >= 70` are never checked, even though they are also true.

This top-to-bottom evaluation means the order of conditions matters. If you reversed the conditions above — testing `score >= 60` first — every score of 60 or above would produce `D`, because the first matching condition wins. When writing a chain of `elseif` conditions, always arrange them from most specific to least specific, or from highest to lowest, depending on what makes the logic clear.



## Conditions Worth Writing

A condition is a `Boolean` expression, and the quality of the condition determines how easy the surrounding code is to read and verify. Some habits make conditions significantly clearer.

**Name intermediate results.** A condition that performs substantial computation before reaching the comparison is harder to read than one that names its parts:

```
nex> let age := 22

nex> let has_licence := true

nex> let can_drive := age >= 17 and has_licence

nex> if can_drive then
       print("You may drive")
    end
You may drive
```

The variable `can_drive` gives a name to what the condition means. A reader does not have to evaluate `age >= 17 and has_licence` in their head — the name explains it. This is especially valuable when the same condition appears in more than one place.

**Avoid double negatives.** A condition like `not (score < 50)` is harder to parse than `score >= 50`. They are logically equivalent, but one requires mental effort to unpack:

```
nex> -- prefer this
nex> if score >= 50 then print("pass") end
pass

nex> -- over this
nex> if not (score < 50) then print("pass") end
pass
```

**Keep conditions positive when possible.** An `if` with a `then` branch that does nothing exists only to reach the `else`:

```
nex> -- awkward
nex> if score < 50 then
       -- nothing
    else
       print("pass")
    end
pass

nex> -- clearer
nex> if score >= 50 then
       print("pass")
    end
pass
```

If the positive condition does not exist — if you genuinely only care about the `false` case — an `else`-only structure is sometimes the clearest option. But reaching for it first, before considering whether the condition can be restated positively, often leads to harder-to-read code.



## Nested Conditions

Conditions can be nested — an `if` can appear inside another `if`:

```
nex> let age := 20

nex> let has_ticket := true

nex> if age >= 18 then
       if has_ticket then
         print("Welcome")
       else
         print("No ticket")
       end
    else
       print("Under age")
    end
Welcome
```

Nesting works, but it should be used with restraint. Two levels of nesting are often the limit of what a reader can comfortably track. Beyond that, a compound condition with `and` or `or` is usually clearer:

```
nex> if age >= 18 and has_ticket then
       print("Welcome")
    elseif age < 18 then
       print("Under age")
    else
       print("No ticket")
    end
Welcome
```

Both versions produce the same results, but the flat version is easier to read because the conditions are stated directly rather than implied by the nesting structure.



## The `when` Expression

The `if` statement executes blocks of code. Sometimes what you want is not to execute code but to produce a value based on a condition. Nex provides the `when` expression for this purpose:

```
nex> let age := 20

nex> let category := when age >= 18 "adult" else "minor" end

nex> category
adult
```

`when` is an expression — it produces a value, which can be assigned to a variable, passed to a function, or used anywhere else an expression is expected. The form is:

```
when condition value_if_true else value_if_false end
```

Both the `value_if_true` and `value_if_false` must be expressions of compatible types. Nex will not allow a `when` expression where one branch produces an `Integer` and the other produces a `String`.

`when` is most useful for short, inline choices where a full `if ... then ... else ...  end` would interrupt the flow of the surrounding code. Compare:

```
nex> -- with if
nex> let label: String := ""
nex> if score >= 50 then
       label := "pass"
    else
       label := "fail"
    end

nex> -- with when
nex> let label := when score >= 50 "pass" else "fail" end
```

The `when` version is more concise and makes clear that the only purpose of the conditional is to choose a value. Use `when` for simple value selection and `if` for anything that involves multiple statements or more complex logic.



## `case` for Multiple Values

When a condition tests the same variable against several specific values, a chain of `elseif` comparisons is repetitive and harder to read than necessary:

```
nex> let day := "Monday"

nex> if day = "Saturday" or day = "Sunday" then
       print("weekend")
    elseif day = "Monday" or day = "Friday" then
       print("edge of week")
    else
       print("midweek")
    end
edge of week
```

The `case` construct handles this more cleanly:

```
nex> case day of
       "Saturday", "Sunday" then print("weekend")
       "Monday", "Friday" then print("edge of week")
       else print("midweek")
    end
edge of week
```

`case` evaluates the expression after `of` — here `day` — and compares it against each comma-separated list of values. The first list that contains a matching value determines which branch runs. The `else` branch at the end catches any value not matched by a preceding branch; it is optional, but omitting it means that unmatched values produce no output and no error, which can hide mistakes.

`case` works with any type that supports equality comparison — integers, strings, booleans, and others:

```
nex> let code: Integer := 2

nex> case code of
       0, 1 then print("low")
       2, 3 then print("medium")
       4, 5 then print("high")
       else print("out of range")
    end
medium
```

Each `then` branch takes a single statement. If you need to perform multiple operations in a branch, use a `do     end` block:

```
nex> case code of
       0, 1 then do
         print("low")
         print("consider increasing")
       end
       else print("not low")
    end
```

We cover `do ... end` blocks fully in Chapter 12. For now, the rule is: use them when a `case` branch needs more than one statement.



## Choosing the Right Construct

Three constructs for conditional logic might seem like two too many. Each has a clear home:

**Use `if`** when the branches contain statements — when you need to perform actions (print output, update variables, call functions) based on a condition. `if` is the general-purpose conditional.

**Use `when`** when you need to choose a value based on a condition and assign it or use it inline. `when` is an expression, not a statement — it produces something rather than doing something.

**Use `case`** when you are comparing a single expression against several specific values. `case` is more readable than a chain of `elseif` equality checks and makes the set of expected values explicit.

A practical test: if you find yourself writing `if x = a ... elseif x = b ...  elseif x = c`, reach for `case`. If you find yourself writing `let result := if     then v1 else v2 end`, reach for `when`. For everything else, `if` is the right tool.



## A Worked Example: Tax Brackets

Tax calculations are a classic example of tiered conditional logic. Suppose a simplified income tax has three brackets:

- Income up to 10,000: taxed at 10%
- Income from 10,001 to 50,000: taxed at 20%
- Income above 50,000: taxed at 30%

```
nex> let income: Real := 35000.0

nex> let tax: Real := 0.0

nex> if income <= 10000.0 then
       tax := income * 0.10
    elseif income <= 50000.0 then
       tax := income * 0.20
    else
       tax := income * 0.30
    end

nex> "Tax: " + tax.to_string
Tax: 7000.0
```

Notice that the conditions are ordered from lowest to highest bracket, and each condition only needs to test the upper bound. Because Nex evaluates conditions top to bottom and stops at the first match, a value of `35000.0` falls through the first condition (`income <= 10000.0` is false) and matches the second (`income <= 50000.0` is true). We do not need to write `income > 10000.0 and income <= 50000.0` for the middle bracket — the first condition already handled everything up to `10000.0`.

This is a common pattern with tiered conditions: order them so that each branch only needs to state its upper bound, trusting that the lower bound is implied by the failure of all preceding conditions.



## Summary

- `if condition then ... end` executes a block when the condition is `true`
- `if ... then ... else ... end` chooses between two blocks
- `if ... then ... elseif ... then ... else ... end` selects among multiple conditions, evaluated top to bottom; the first matching condition wins
- Conditions are easier to read when intermediate results are named, double negatives are avoided, and nesting is kept shallow
- `when condition value else value end` is an expression that produces a value based on a condition; use it for inline value selection
- `case expression of value, value then ... end` matches a single expression against specific values; use it instead of repeated equality checks
- Order `elseif` conditions from most specific to least specific; the order determines which branch runs for overlapping conditions



## Exercises

**1.** Write an `if` statement that prints `"positive"`, `"negative"`, or `"zero"` depending on the value of a variable `n`. Test it with `n = 5`, `n = -3`, and `n = 0`.

**2.** Rewrite the following `if` chain using `case`:

```
if day = "Mon" or day = "Tue" or day = "Wed" or day = "Thu" or day = "Fri" then
  print("weekday")
else
  print("weekend")
end
```

**3.** Write a `when` expression that assigns the string `"even"` or `"odd"` to a variable `parity` based on whether `n % 2 = 0`. Print the result.

**4.** The Fizz-Buzz problem: for a given integer `n`, print `"FizzBuzz"` if `n` is divisible by both 3 and 5, `"Fizz"` if divisible only by 3, `"Buzz"` if divisible only by 5, and the number itself otherwise. Write the `if` statement and test it with `n = 15`, `n = 9`, `n = 10`, and `n = 7`.

**5.\*** A simplified shipping cost calculator: orders under 10.0 kg cost 5.0 per kg; orders from 10.0 to 50.0 kg cost 4.0 per kg; orders above 50.0 kg cost 3.0 per kg. Write a program that reads a weight from the console, computes the shipping cost, and prints a message like `"Weight: 25.0 kg, Cost: 100.0"`. Test it with weights of `5.0`, `25.0`, and `75.0` kg, and verify the results by hand before running the program.
