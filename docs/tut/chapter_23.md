# Modules and Files

Until now, most examples have fit in one file or one REPL session. Real programs do not. They are split into multiple classes, multiple files, and clear boundaries between parts of the system.

In Nex, the main tool for bringing code from another Nex file into the current program is `intern`.


## Why Split a Program

Splitting a program across files is not mainly about size. It is about design.

Put code in separate files when:

- it represents a distinct concept
- it can be understood independently
- it should be reused elsewhere
- keeping it separate makes the boundary clearer

A `Bank_Account` class, a `Transaction` class, and a `Report_Printer` class should not live in one file merely because they all belong to the same application. They are different concepts with different reasons to change.


## The `intern` Statement

Nex loads classes from other files with `intern`:

```
intern math/Calculator
```

This means: find the Nex file for `Calculator` under the `math` path and make that class available in the current program.

An alias may also be given:

```
intern math/Calculator as Calc
```

That allows the imported class to be referred to locally as `Calc`.


## How `intern` Resolves Files

`intern` searches in this order:

1. the directory of the currently loaded script, if the code came from a file
2. the REPL or process working directory
3. `~/.nex/deps`

For a path-qualified intern such as:

```text
intern net/Tcp_Socket
```

Nex tries these local layouts under each search root:

```text
Tcp_Socket.nex
tcp_socket.nex
lib/net/Tcp_Socket.nex
lib/net/tcp_socket.nex
lib/net/src/Tcp_Socket.nex
lib/net/src/tcp_socket.nex
```

Then it tries the same path forms under:

```text
~/.nex/deps
```

So all of these are valid examples:

```text
./lib/net/tcp_socket.nex
/some/project/lib/net/Tcp_Socket.nex
~/.nex/deps/net/tcp_socket.nex
~/.nex/deps/net/src/Tcp_Socket.nex
```

Exact-case filenames are checked first. If they are not found, Nex falls back to a lowercase filename such as `tcp_socket.nex`.


## A Simple Two-File Example

Suppose `math/Counter.nex` contains:

```
class Counter
  create
    make() do
      count := 0
    end
  feature
    count: Integer
    increment() do
      count := count + 1
    end
    value(): Integer do
      result := count
    end
end
```

Another file may use it with:

```
intern math/Counter

class Main
  create
    make() do
      let c := create Counter.make
      c.increment
      c.increment
      print(c.value)
    end
end
```

The point is not merely that the code compiles. The point is that `Counter` now has its own module boundary. It can be read, tested, and reused on its own.


## Aliases

Aliases are useful when:

- the imported name is long
- two imported classes would otherwise collide
- a local short name improves readability

Example:

```
intern geometry/Long_Polygon_Name as Polygon
```

Now:

```
let p := create Polygon.with_sides(5)
```

Use aliases sparingly. The goal is clarity, not abbreviation for its own sake.


## Designing Module Boundaries

A good file boundary often matches a good class boundary.

As a rough rule:

- one main class per file
- helper classes in their own files when they have an identity of their own
- unrelated utility code should not be stuffed into a random "misc" file

Ask of every file:

- what concept does this file define?
- what other files should know about it?
- what can remain private to this file's class or classes?

Files are design documents as much as storage containers.


## What Belongs Together

These belong together:

- a `Stack` class and helper routines whose only purpose is to support the stack

These do not:

- `Stack`, `Customer`, and `Image_Loader` in the same file

If you feel tempted to group code by when it was written rather than by what it means, stop and redesign.

The best modules reduce mental load. A reader opening a file should have a good guess what they are about to find.


## Intern and Contracts

Contracts become even more important once code is split across files.

When a class is used from another module, the reader of the calling code should not need to open the original file to know basic obligations and guarantees. Good preconditions, postconditions, and invariants make module boundaries trustworthy.

In a multi-file program, the contract is often the first and most important documentation of a class.


## A Worked Example: Splitting a Small Ledger

Imagine a program with these concepts:

- `Transaction`
- `Account`
- `Ledger_Report`

A clean structure would be:

`finance/Transaction.nex`

```
class Transaction
  create
    make(d: String, a: Real) do
      description := d
      amount := a
    end
  feature
    description: String
    amount: Real
end
```

`finance/Account.nex`

```
intern finance/Transaction

class Account
  create
    make(name: String) do
      owner := name
      entries := []
    end
  feature
    owner: String
    entries: Array[Transaction]
    add_entry(t: Transaction) do
      entries.add(t)
    end
    balance(): Real do
      result := 0.0
      across entries as entry do
        result := result + entry.amount
      end
    end
end
```

`finance/Ledger_Report.nex`

```
intern finance/Account

class Ledger_Report
  feature
    print_balance(a: Account) do
      print(a.owner + ": " + a.balance.to_string)
    end
end
```

Each file has one job. The design is visible in the file structure itself.


## Summary

- Split programs into files to express design boundaries, not merely to reduce length
- Use `intern path/Class_Name` to load Nex classes from other files
- Use `as` when a local alias improves clarity
- Good file boundaries usually follow good class boundaries
- Contracts make multi-file code easier to trust and reuse
- A clean module layout reduces coupling and mental overhead


## Exercises

**1.** Split a simple earlier example into two files: one defining a class and one using it with `intern`.

**2.** Take a class that currently does too much and divide it into two classes in two files. Explain why the new boundary is better.

**3.** Write a short example using `intern ... as ...` and show why the alias is helpful.

**4.** Sketch a directory layout for a small address-book program with classes `Contact`, `Address_Book`, and `Csv_Exporter`.

**5.\*** Choose a chapter 26-sized program idea of your own. Before writing any code, propose the file structure and justify each file in one sentence.
