# Solutions to Selected Exercises

This appendix gives worked solutions or solution sketches for a small set of the exercises marked with an asterisk. They are not the only correct answers. Their purpose is to show a reasonable style of solution in Nex.


## Chapter 16, Exercise 5

Question: should `transfer_to(other, amount)` require `other /= this`?

One answer is yes:

- a transfer to the same account is probably a caller mistake
- the contract should reject meaningless calls early

Another answer is no:

- transferring to the same account is harmless
- the operation can simply become a no-op

The better choice depends on the intended interface. If the routine models a real movement of funds between distinct accounts, make it a precondition:

```nex
transfer_to(other: Account, amount: Real)
  require
    positive_amount: amount > 0.0
    enough: amount <= balance
    different_account: other /= this
  do
    withdraw(amount)
    other.deposit(amount)
  end
```

If the interface is meant to be tolerant and mathematical rather than operational, allowing a no-op is also defensible. The key is to decide deliberately.


## Chapter 17, Exercise 5

For

```nex
sort(items: Array[Integer]): Array[Integer]
```

useful postconditions are:

- result length matches input length
- result is in non-decreasing order
- result contains the same elements as the input

In prose:

```nex
ensure
  same_length: result.length = items.length
```

The order and element-preservation properties are harder to state compactly in the current surface syntax, but they should still be part of the design and of the tests.


## Chapter 18, Exercise 5

An `Interval` class should satisfy:

```nex
class Interval
  create
    make(a, b: Integer) do
      start := a
      finish := b
    end
  feature
    start: Integer
    finish: Integer
  invariant
    ordered: start <= finish
end
```

This invariant changes every mutating routine. Any method that extends, shrinks, or merges intervals must preserve `start <= finish`. That forces each routine to think about both endpoints together, not independently.


## Chapter 19, Exercise 5

For duplicate removal in a sorted array, a good invariant is:

"The segment from index `0` through `write` contains the unique elements from the already scanned prefix of the input, in sorted order."

That invariant explains the whole two-index algorithm:

- `read` scans the input
- `write` marks the end of the deduplicated prefix

Because the input is sorted, it is enough to compare each new element with the last kept unique element.


## Chapter 20, Exercise 5

Suppose an earlier routine was:

```nex
function first(items: Array[String]): String
do
  result := items.get(0)
end
```

Contract-first redesign:

```nex
function first(items: Array[String]): String
  require
    not_empty: items.length > 0
  ensure
    result_is_first_element: result = items.get(0)
  do
    result := items.get(0)
  end
```

The contract reveals what the earlier version left implicit:

- the array must not be empty
- the routine is not just returning some string, but the first element specifically


## Chapter 21, Exercise 5

A `File_Cache` design can separate contract and environment like this:

```nex
class File_Cache
  create
    make() do
      cache := {}
    end
  feature
    cache: Map[String, String]

    load(path: String): String
      require
        path_not_empty: path.length > 0
      do
        if cache.contains_key(path) then
          result := cache.get(path)
        else
          raise "file missing"
        end
      rescue
        result := "default contents"
      end
end
```

The path itself is a caller obligation. The missing file is an environmental condition handled by rescue. In a fuller implementation, the routine would attempt a real file read before falling back.


## Chapter 22, Exercise 5

If two files must stay synchronized, the design should avoid partial updates.

One reasonable approach:

1. require valid input data before beginning
2. prepare both new file contents first
3. write to temporary files
4. replace the originals only after both temporary writes succeed
5. if any write fails, raise or recover without swapping either original

The contracts belong on the routine inputs and internal consistency. Exceptions belong on actual file-system failures.


## Chapter 23, Exercise 5

A chapter-26-sized program might be a grade book:

- `school/Student.nex` for one student's identity
- `school/Course_Record.nex` for one student's scores in one course
- `school/Grade_Book.nex` for the collection and summary logic
- `school/Report_Printer.nex` for output formatting

Each file has one clear reason to change. That is the main test of a sound file structure.


## Chapter 24, Exercise 5

Take a configuration loader.

Poor design:

- core business logic reads files directly
- fallback logic is duplicated everywhere

Better design:

- `Config_Source` handles file or host access
- `Configuration` stores parsed settings
- the rest of the program depends only on the resulting configuration object

That keeps host-specific code at the edge and preserves a portable center.


## Chapter 25, Exercise 5

Suppose a weak postcondition for `without_last(s)` only says the result is one character shorter.

Tests should then include:

- `"a"` becomes `""`
- `"cat"` becomes `"ca"`
- `"Nex"` becomes `"Ne"`

These tests catch implementations that return any string of the right length but not the right prefix.


## Chapter 26, Exercise 5

A small library checkout tracker would naturally use:

- `Book`
- `Member`
- `Loan`
- `Library`

Contracts would express:

- a book cannot be loaned twice at once
- a loan always has a valid book and member
- a return operation can only apply to an active loan

Tests would check the full sequence:

1. add books and members
2. create a loan
3. reject a duplicate checkout
4. return the book
5. allow a new checkout


## Chapter 27, Exercise 5

For an inventory list, combine:

- an accumulator loop to total stock
- table-driven dispatch with a map from item names to counts
- a class invariant to ensure counts never go negative

Most larger programs are built from a few such patterns layered carefully rather than from entirely new techniques.


## How to Use These Solutions

- Compare the contracts, not just the code shape.
- Ask whether the solution puts the right property in the right place: precondition, postcondition, invariant, test, or rescue logic.
- Rewrite each solution in your own style after understanding it.

The goal of a solutions appendix is not to end thought, but to sharpen it.
