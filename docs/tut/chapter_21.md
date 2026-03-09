# Errors and Exceptions

Contracts are for broken obligations between parts of a program. Exceptions are for failures that can happen even when everyone uses the interface correctly.

If a caller violates `amount <= balance`, that is a contract problem. If the network is down, the file is missing, or the external service is temporarily unavailable, those are environmental failures. The program may need to report them, recover from them, or retry.

Nex provides three related constructs:

- `raise` to signal an exception
- `rescue` to handle it
- `retry` to run the protected block again


## Raising an Exception

The simplest form is `raise <expression>`:

```
nex> raise "network unavailable"
Error: network unavailable
```

The raised value becomes the current exception. In a `rescue` block, it is available through the special name `exception`.

Raising an exception stops the current protected block immediately. Control passes to the nearest enclosing `rescue`.


## A Scoped Rescue Block

The general form is:

```
do
  print("trying")
rescue
  print("recovering from " + exception.to_string)
end
```

Example:

```
nex> do
       print("before")
       raise "something went wrong"
       print("after")
     rescue
       print("rescued: " + exception.to_string)
     end
before
rescued: something went wrong
```

The line `print("after")` never runs because `raise` aborts the protected block.


## Retrying

Some failures are temporary. In such cases, a rescue block may try again with `retry`.

```
nex> let attempts := 0

nex> do
       attempts := attempts + 1
       if attempts < 3 then
         raise "not ready yet"
       end
       print("done on attempt " + attempts.to_string)
     rescue
       print("failed: " + exception.to_string)
       retry
     end
failed: not ready yet
failed: not ready yet
done on attempt 3
```

`retry` jumps back to the start of the `do` block and runs it again.

This is powerful, but it must be used carefully. A retry with no progress toward success becomes an infinite loop in disguise.


## Rescue Inside Routines

Constructors, methods, and functions may also have `rescue` clauses:

```
nex> function read_config(path: String): String
     do
       raise "file not found"
     rescue
       result := "default-config"
     end
```

The routine body is attempted. If an exception occurs, the rescue clause runs.

The routine should still return to a meaningful state. A rescue clause that simply swallows every error without restoring a sensible result is usually a design mistake.


## Exceptions Versus Preconditions

Suppose we have:

```
withdraw(amount: Real)
  require
    positive_amount: amount > 0.0
    enough: amount <= balance
  do
    balance := balance - amount
  end
```

Should `withdraw` raise an exception when the balance is too small instead of using a precondition?

Usually, no.

Insufficient balance in this design is a caller error. The routine's legal input space is "positive amounts no larger than the balance." Anything else is an invalid call and should fail as a contract violation.

Use exceptions when the failure is not a misuse of the routine but a condition arising during normal correct use:

- file system denied access
- remote server timed out
- image file was corrupt

This distinction keeps designs honest. If you use exceptions for contract failures, callers can become sloppy because the interface no longer states clear obligations.


## Recovery Should Be Specific

A rescue block should handle the failure in a way that makes sense for the surrounding routine.

Poor rescue:

```
rescue
  print("error")
end
```

This loses information and often leaves the computation in an unknown state.

Better rescue:

```
rescue
  print("could not load settings: " + exception.to_string)
  result := default_settings()
end
```

Or, if the routine cannot continue meaningfully:

```
rescue
  print("fatal: " + exception.to_string)
  raise exception
end
```

Recovery should either:

- repair the situation
- substitute a safe fallback
- or report and re-raise

Anything else tends to hide bugs.


## A Retry Loop with Limits

Unbounded retry is dangerous. Give it a stopping rule.

```
nex> function connect_with_retry(): String
     do
       let attempts := 0
       do
         attempts := attempts + 1
         if attempts < 3 then
           raise "temporary connection error"
         end
         result := "connected"
       rescue
         if attempts < 3 then
           retry
         else
           raise exception
         end
       end
     end
```

This routine retries twice, then gives up. The rescue logic is controlled and explicit.


## A Worked Example: Parsing a Positive Integer

Here is a small example that separates routine obligations from environmental uncertainty:

```
nex> function parse_positive(text: String): Integer
     require
       not_empty: text.length > 0
     do
       let value := text.to_integer
       if value <= 0 then
         raise "number must be positive"
       end
       result := value
     rescue
       raise "invalid positive integer: " + text
     end
```

The routine uses a precondition for one issue and an exception for another:

- empty input is a caller error here, so it is a precondition
- malformed or unacceptable content after conversion is handled as an exception

That balance is not arbitrary. It reflects the routine's role in the design. If the caller is expected to pass non-empty strings, make it a contract. If the content may legitimately fail to parse, raise or handle an exception.


## Summary

- Exceptions are for failures that can occur during otherwise valid execution
- `raise` signals an exception; `rescue` handles it; `retry` tries the protected block again
- Contract violations and exceptions are different kinds of failure and should not be confused
- A rescue block should repair, substitute, or re-raise, not merely hide the error
- Retry should usually have a clear stopping condition
- Good error handling preserves meaning rather than blurring it


## Exercises

**1.** Write a `do ... rescue ... end` block that raises `"too small"` until a counter reaches 5, then prints `"ok"`. Use `retry`.

**2.** Define a function `safe_reciprocal(x: Real): Real` that raises an exception when `x = 0.0`. Then wrap a call in a rescue block that prints a fallback message.

**3.** Rewrite a routine of your own choosing so that it distinguishes clearly between a contract violation and an exception-producing environmental failure.

**4.** Improve the `connect_with_retry` routine so that it prints the attempt number each time it retries.

**5.\*** Design a small class `File_Cache` whose `load(path: String): String` routine first tries to read from memory, then from disk, and uses rescue logic to recover from a missing file by returning a built-in default. State what should be a precondition, what should be an exception, and why.
