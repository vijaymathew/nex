# Writing Robust Code

Chapter 21 introduced exceptions. This chapter is about using them well. The question is not whether programs fail; they do. The question is where a failure should be detected, how far it should travel, and what meaning the program should preserve when it handles it.

Robust code is not code that never fails. It is code that fails in the right place, for the right reason, without blurring the difference between a broken contract and a difficult world.


## Distinguishing Kinds of Failure

Most failures in a program fall into one of three categories. Naming the category is often the first step toward the correct design.

Caller mistakes.

These are contract violations. The caller passed an invalid argument or called a routine in the wrong state. Use `require`.

Routine bugs.

These appear as violated postconditions or invariants. They are defects in the implementation or design.

Environmental failures.

These come from the outside world: missing files, unavailable services, corrupt input, exhausted resources. Use exceptions, recovery logic, or propagation.

A robust design keeps these categories separate.


## Fail Fast on Programmer Errors

When a routine is called incorrectly, the worst response is often to continue.

Suppose a stack `pop` is called on an empty stack. Returning a special fake value may let the program continue for a while, but it has also hidden the original mistake. The program now contains both the original bug and whatever secondary damage the fake value causes later.

A precondition is the correct response:

```
pop(): G
  require
    not_empty: items.length > 0
  do
    result := items.get(items.length - 1)
    items.remove(items.length - 1)
  end
```

Failing fast on programmer error is not harshness. It is clarity.


## Be Tolerant at the System Boundary

At the edge of the program, the world is uncertain.

A user may type bad data. A file may be absent. A request may time out. The program should often handle these situations gracefully because they are part of normal operation, not evidence that the programmer misunderstood the interface.

The pattern is:

1. inspect uncertain input at the boundary
2. convert it into a clean internal form
3. use contracts inside the system

For example, if user input is supposed to become a positive integer:

```
nex> function prompt_for_count(): Integer
     do
       let raw := "5"
       let value := raw.to_integer
       if value <= 0 then
         raise "count must be positive"
       end
       result := value
     end
```

Once `prompt_for_count` returns successfully, internal routines can reasonably require `count > 0` rather than repeatedly validating it.


## Recovery Should Preserve Truth

One of the easiest ways to make code fragile is to recover in a way that lies about what happened.

Suppose a file load fails and a rescue block returns an empty string:

```
rescue
  result := ""
end
```

This may be acceptable if the routine's meaning is genuinely "give me the file contents, or an empty document if none exists." But if callers interpret the result as "the file was loaded successfully and happened to be empty," the recovery has destroyed information. The program may now continue on a false premise.

Robust recovery preserves truth. If the distinction matters, represent it honestly:

- return a result object with success or failure
- raise the exception upward
- or log and return a documented fallback with a contractually clear meaning


## Guarding Against Partial Updates

A routine that changes several pieces of state should be careful not to leave the system half-updated if something goes wrong in the middle.

Consider:

```
transfer_to(other: Account, amount: Real)
  do
    withdraw(amount)
    other.deposit(amount)
  end
```

If `withdraw` succeeds but `other.deposit` fails because `other` is in an invalid state, the system has been changed only halfway.

The best defense is good design:

- strong preconditions
- sound invariants on both objects
- small routines with simple effects

Sometimes a routine should perform all checks first, then execute the state changes only once it knows the operation is safe.


## Simple Defensive Patterns

Several habits make Nex programs more robust.

Normalize inputs early.

Convert text to structured values near the boundary.

Keep contracts sharp.

Do not blur caller errors and runtime failures.

Use detachable types only when absence is meaningful.

Do not use `?Type` merely to avoid deciding what should really be required.

Make invalid states unrepresentable where possible.

A class with a strong invariant reduces the space of possible bugs.

Prefer small routines.

A short routine with one purpose is easier to specify and recover around than a long routine with mixed responsibilities.


## Using Result Objects Instead of Exceptions

Chapter 15 introduced a generic `Result[V]` class. That pattern is often useful in robust code.

Instead of raising an exception, a routine may return either a value or an explanation of failure:

```
nex> class Result [V]
       create
         success(v: V) do
           value := v
           error := nil
           ok := true
         end
         failure(msg: String) do
           value := nil
           error := msg
           ok := false
         end
       feature
         value: ?V
         error: ?String
         ok: Boolean
         is_ok(): Boolean do
           result := ok
         end
     end
```

A routine may then choose:

```
nex> function safe_divide(a, b: Real): Result[Real]
     do
       if b = 0.0 then
         result := create Result[Real].failure("division by zero")
       else
         result := create Result[Real].success(a / b)
       end
     end
```

This style is often preferable when failure is expected and common rather than exceptional.


## A Worked Example: Reading Configuration Safely

Suppose a program wants configuration text, but can continue with defaults if no file is available.

```
nex> function load_configuration(path: String): String
     require
       path_not_empty: path.length > 0
     do
       raise "file missing"
     rescue
       print("using built-in defaults: " + exception.to_string)
       result := "theme=light%ntimeout=30"
     end
```

This routine is robust for three reasons:

- the path itself is still a contract obligation
- the missing file is treated as an environmental failure
- the fallback value has a documented meaning

The routine is not pretending the file load succeeded. It is deliberately choosing a default configuration instead.


## Summary

- Robust code separates caller errors, routine bugs, and environmental failures
- Programmer mistakes should fail fast through contracts
- Uncertain external conditions should be handled at system boundaries
- Recovery should preserve the truth about what happened
- Partial updates are dangerous; good contracts and small routines reduce that risk
- Result objects are often useful when failure is expected
- Robustness comes from clear boundaries, not from hiding every error


## Exercises

**1.** Take one routine from an earlier chapter and classify its possible failures into caller mistakes, routine bugs, and environmental failures.

**2.** Rewrite `safe_divide` so that it uses a precondition instead of a result object. Which design is better, and in what context?

**3.** Design a routine `load_score(text: String): Integer` that accepts user input, validates it, and returns a positive integer score. Decide where validation ends and where contracts begin.

**4.** Write a short paragraph explaining why a silent fallback can sometimes be more dangerous than a visible failure.

**5.\*** A program updates two files so they should always match. Sketch a robust design for this operation. Where would you put contracts, where would you use exceptions, and how would you reduce the chance of leaving the files inconsistent?
