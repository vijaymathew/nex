# Common Patterns

By now the individual language features should feel familiar. The next step is to notice that many programs are built from the same small set of shapes.

A pattern is not a trick and not a rigid formula. It is a reusable structure of solution. Once you recognize a pattern, a new problem becomes less intimidating because part of its design is already known.


## The Accumulator Loop

This is the most common loop pattern in the book, and probably the most useful.

You keep a variable that summarizes the part of the input seen so far:

```
nex> function sum(items: Array[Integer]): Integer
     do
       result := 0
       across items as item do
         result := result + item
       end
     end
```

Examples:

- summing numbers
- counting matches
- computing a maximum
- building a frequency table

The question to ask is: *what single variable can summarize the processed prefix?* Once you have that variable, the body of the loop is often nearly obvious.


## Search Through a Sequence

Another recurring pattern is scanning until a target is found:

```
nex> function contains(items: Array[String], target: String): Boolean
     do
       result := false
       from
         let i := 0
       until
         i = items.length or result
       do
         if items.get(i) = target then
           result := true
         end
         i := i + 1
       end
     end
```

This pattern appears in:

- membership tests
- locating the first matching element
- checking whether any item satisfies a property

The key design choice is the stopping condition: stop when the answer is known, not one iteration later.


## Recursive Structure Matches Recursive Data

When data is nested like a tree, recursion often gives the cleanest code because the shape of the routine mirrors the shape of the data.

In this example, assume the node uses the same tree representation as Chapter 11: a map with a `"children"` entry containing an array of child nodes.

```
nex> function count_nodes(node: Map[String, Any]): Integer
     do
       result := 1
       across node.get("children") as child do
         result := result + count_nodes(child)
       end
     end
```

The pattern is:

1. solve the problem for the current node
2. solve the same problem for each child
3. combine the results

Whenever the data is self-similar, ask whether the algorithm should be self-similar too.


## Build a Class Around an Invariant

A good class often begins with one sentence:

"For every valid object of this class, the following must always hold..."

Examples:

- account balance is never negative
- counter is between zero and its limit
- task title is never empty

Once that invariant is known, the rest of the class becomes easier:

- constructors establish it
- methods preserve it
- callers can trust it

This is not only a contract technique. It is a design pattern for stable classes.


## Table-Driven Lookup

Sometimes a program chooses among a small set of known values based on a key. A map can express that relationship more clearly than a long chain of `if` statements.

Conceptually:

```
nex> let prices := {"apple": 3, "orange": 4, "pear": 5}
nex> prices.get("orange")
4
```

Instead of:

```
if item = "apple" then
  price := 3
elseif item = "orange" then
  price := 4
elseif item = "pear" then
  price := 5
end
```

this pattern stores the association directly as data rather than spelling it out in branching code.

The same idea appears in:

- menus
- small lookup tables
- configuration-driven behavior

Data is often clearer than branching.


## Result Objects Instead of Exceptions

When failure is expected and common, returning a `Result[V]` can be better than raising:

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

This pattern makes the two outcomes explicit:

- success with a value
- failure with an explanation

It is especially useful when callers are expected to branch on the outcome routinely.


## Wrapper at the Boundary

When using files, network access, or imported platform code, wrap the external behavior in a small class or routine with a clear contract.

The pattern is:

- boundary wrapper outside
- portable core logic inside

For example:

```
nex> function word_count(text: String): Integer
     do
       result := 0
       across text.split(" ") as word do
         if word /= "" then
           result := result + 1
         end
       end
     end

nex> class File_Word_Counter
       feature
         count_words_in(path: String): Integer
           require
             path_not_empty: path.length > 0
           do
             result := word_count(path_read_text(path))
           end
     end
```

Here `count_words_in` touches the outside world, but `word_count` stays plain Nex logic over a string.

This keeps the unreliable world at the edge and the reasoning-heavy code at the center.


## A Worked Example: Combining Patterns

Here is a simple word-report routine:

```
nex> function most_frequent_word(text: String): String
     require
       not_blank: text.trim().length > 0
     do
       let freq: Map[String, Integer] := {}
       let words := text.to_lower.split(" ")

       across words as word do
         if word /= "" then
           let count := freq.try_get(word, 0)
           freq.set(word, count + 1)
         end
       end

       result := freq.keys.get(0)
       across freq.keys as word do
         if freq.get(word) > freq.get(result) then
           result := word
         end
       end
     end
```

This small routine combines several patterns:

- accumulation into a map
- table-driven lookup for counts
- maximum search
- a precondition to exclude the meaningless empty case

Many real programs feel complicated only until you notice that they are simply a clean composition of two or three such patterns.


## Summary

- Patterns are reusable shapes of solution, not rigid formulas
- Accumulator loops summarize processed input
- Search loops stop as soon as the answer is known
- Recursive data often calls for recursive routines
- Good classes are organized around strong invariants
- Table-driven lookup is often clearer than long condition chains
- Wrappers isolate system boundaries from core logic
- Recognizing patterns reduces design effort and improves clarity


## Exercises

**1.** Identify which pattern from this chapter best describes each of these routines: `max_of`, `contains`, `word_frequencies`, `count_nodes`.

**2.** Rewrite a long `if ... elseif ... else` chain from one of your earlier examples as a table-driven lookup where possible.

**3.** Take a class of your own and write its core invariant in one sentence. Then inspect whether its methods really preserve that invariant.

**4.** Write a routine that counts how many strings in an array begin with a given prefix. Which pattern does it use?

**5.\*** Choose a larger problem, such as processing a grade book or inventory list, and describe which two or three patterns from this chapter you would combine to solve it.
