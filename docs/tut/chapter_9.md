# Arrays

Every program so far has worked with individual values — one integer, one string, one boolean at a time. Most real problems involve collections: a list of scores, a sequence of names, a series of measurements. This chapter introduces the array, Nex's primary ordered collection, and the operations for creating, accessing, and processing arrays.


## What an Array Is

An array is an ordered sequence of values, all of the same type. The order matters — position zero comes before position one, position one before position two — and each position holds exactly one value. The number of elements in an array is its *length*.

Array literals are written with square brackets, elements separated by commas:

```
nex> let scores := [85, 92, 78, 95, 60]

nex> scores.length
5
```

The type of `scores` is inferred as `Array[Integer]` — an array whose elements are integers. An array of strings would be `Array[String]`, an array of reals `Array[Real]`, and so on. All elements must be of the same type; an array cannot mix integers and strings.

An empty array requires an explicit type annotation, because there are no elements from which the type can be inferred:

```
nex> let empty: Array[Integer] := []

nex> empty.length
0
```



## Accessing Elements

Elements are accessed by their index, which counts from zero:

```
nex> let scores := [85, 92, 78, 95, 60]

nex> scores.get(0)
85

nex> scores.get(1)
92

nex> scores.get(4)
60
```

The first element is at index 0, the last at index `length - 1`. Accessing an index outside this range raises an exception:

```
nex> scores.get(5)
Error: index out of bounds
```

This is the array equivalent of calling `.to_integer` on a non-numeric string — the language enforces the boundary rather than returning a silent wrong answer. The precondition for array access is that the index must be in the range `[0, length - 1]`.

The last element is accessed with `scores[scores.length - 1]`, 

```
nex> scores.get(scores.length - 1)
60

nex> scores.get(0)
85
```



## Modifying Elements

Individual elements can be updated by assigning to an indexed position:

```
nex> scores.put(2, 80)
nex> scores
[85, 92, 80, 95, 60]
```

The array itself is mutable — its elements can change — but its length cannot. An array created with five elements always has five elements. Adding or removing elements requires the operations in Section 9.5.



## Iterating with `across`

The most natural way to process every element of an array is `across`:

```
nex> let scores := [85, 92, 78, 95, 60]

nex> across scores as s do
       print(s)
    end
85
92
78
95
60
```

The loop variable `s` is inferred as `Integer` from the element type of `scores`. No annotation needed.

`across` visits every element in order, from index 0 to index `length - 1`. It is the right choice when you need every element and do not need the index. When the index matters — for example, to print `"Score 1: 85"` — use a `from ... until ... do` loop with an explicit counter:

```
nex> from
       let i := 0
    until
       i >= scores.length
    do
       print("Score " + (i + 1).to_string + ": " + scores.get(i).to_string)
       i := i + 1
    end
Score 1: 85
Score 2: 92
Score 3: 78
Score 4: 95
Score 5: 60
```

Note the termination condition `i >= scores.length` and the starting index `0`. The last valid index is `scores.length - 1`, so the loop runs while `i < scores.length` — equivalently, until `i >= scores.length`.



## Growing and Shrinking Arrays

Arrays can be extended with `add`:

```
nex> let names: Array[String] := ["Alice", "Bob"]

nex> names.add("Carol")
nex> names
[Alice, Bob, Carol]

nex> names.add("David")
nex> names
[Alice, Bob, Carol, David]

nex> names.length
4
```

The `add` method appends one element to the end of the array and increases its length by one.

Elements can be removed by index with `remove`:

```
nex> names.remove(names.length - 1)
nex> names
[Alice, Bob, Carol]

nex> names.remove(1)
nex> names
[Alice, Carol]
```

`remove(i)` removes the element at index `i` and shifts all subsequent elements one position to the left. After `remove(1)`, what was at index 2 is now at index 1.

Elements can be inserted at a specific position with `add_at`:

```
nex> names.add_at(1, "Bob")
nex> names
[Alice, Bob, Carol]
```

`add_at(i, value)` inserts `value` at index `i` and shifts all existing elements from index `i` onward one position to the right.



## Common Array Operations

**Checking membership:**

```
nex> let primes := [2, 3, 5, 7, 11]

nex> primes.contains(5)
true

nex> primes.contains(4)
false
```

**Finding the index of an element:**

```
nex> primes.index_of(7)
3

nex> primes.index_of(9)
-1
```

`index_of` returns `-1` when the element is not found, consistent with `String.index_of`.

**Slicing — extracting a sub-array:**

```
nex> let scores := [85, 92, 78, 95, 60]

nex> scores.slice(1, 4)
[92, 78, 95]
```

`slice(start, end)` returns a new array containing elements from index `start` up to but not including index `end`. The original array is unchanged.

**Reversing:**

```
nex> scores.reverse
[60, 95, 78, 92, 85]
```

**Sorting:**

```
nex> scores.sort
[60, 78, 85, 92, 95]
```

`sort` returns a sorted array. Sorting works on any array whose element type implements `Comparable` — integers, reals, strings, and characters all do.



## Building Arrays with Loops

Often an array is built up element by element rather than written as a literal. Start with an empty array and call `add` inside a loop:

```
nex> let squares: Array[Integer] := []

nex> from
       let i := 1
    until
       i > 10
    do
       squares.add(i * i)
       i := i + 1
    end

nex> squares
[1, 4, 9, 16, 25, 36, 49, 64, 81, 100]
```

This pattern — empty array, loop, `add` — is the standard way to construct arrays programmatically. It appears constantly in real programs.



## Arrays and Functions

Arrays are values like any other and can be passed to and returned from functions:

```
nex> function sum(arr: Array[Integer]): Integer
     do
       result := 0
       across arr as x do
         result := result + x
       end
     end

nex> sum([10, 20, 30, 40])
100

nex> sum([])
0
```

```
nex> function maximum(arr: Array[Integer]): Integer
     do
       result := arr.get(0)
       across arr as x do
         if x > result then
           result := x
         end
       end
     end

nex> maximum([3, 7, 1, 9, 4])
9
```

`maximum` initialises `result` to the first element (index 0) and then updates it whenever a larger element is found. This is the accumulator pattern from Chapter 5, applied to arrays. Note that `maximum` has an implicit precondition: the array must not be empty, because `arr.get(0)` on an empty array raises an exception. In Part V we will write this as a `require` clause; for now, state it in a comment.

Functions can also return arrays:

```
nex> function filter_above(arr: Array[Integer], threshold: Integer): Array[Integer]
     do
       result := []
       across arr as x do
         if x > threshold then
           result.add(x)
         end
       end
     end

nex> filter_above([85, 92, 78, 95, 60], 80)
[85, 92, 95]
```

`filter_above` builds a new array containing only the elements that exceed the threshold. The original array is untouched.



## Thinking About Array Preconditions

Several array operations have preconditions worth stating explicitly:

- `arr.get(i)` requires `i >= 0 and i < arr.length`
- `arr.get(0)` and `arr.get(arr.length - 1)` require `arr.length > 0`
- `maximum` (as written above) requires `arr.length > 0`
- `remove(i)` requires `i >= 0 and i < arr.length`
- `add_at(i, v)` requires `i >= 0 and i <= arr.length`

These are the same kind of assumptions that `percentage` had in Chapter 7 — conditions that must be true for the operation to behave correctly. When writing a function that calls any of these operations, ask whether the caller can guarantee the precondition, or whether the function needs to check it first.

This question — *who is responsible for ensuring the precondition?* — is one of the central questions of software design. Part V returns to it in depth.



## A Worked Example: Statistics

Here is a small collection of functions that compute basic statistics on an array of real numbers:

```
nex> function mean(arr: Array[Real]): Real
     do
       result := 0.0
       across arr as x do
         result := result + x
       end
       result := result / arr.length.to_real
     end

nex> function variance(arr: Array[Real]): Real
     do
       let m := mean(arr)
       result := 0.0
       across arr as x do
         let diff := x - m
         result := result + diff * diff
       end
       result := result / arr.length.to_real
     end

nex> function std_dev(arr: Array[Real]): Real
     do
       result := variance(arr) ^ 0.5
     end
```

```
nex> let data := [2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0]

nex> mean(data)
5.0

nex> variance(data)
4.0

nex> std_dev(data)
2.0
```

Each function does one thing. `variance` calls `mean` rather than recomputing it. `std_dev` calls `variance`. The final results are clean because this particular dataset was chosen to have integer-valued statistics — a useful trick when writing examples you want to verify by hand.

All three functions have the same implicit precondition: the array must not be empty. An empty array would cause `arr.length.to_real` to produce `0.0` in the denominator of `mean`, resulting in a division by zero. Noting this before writing the body — as the habit from Chapter 7 prescribes — would have identified the assumption immediately.



## Summary

- An array is an ordered sequence of values of the same type, written `[v1, v2, v3]`
- Elements are accessed by zero-based index: `arr.get(0)` is the first element, `arr.get(arr.length - 1)` is the last
- Accessing an out-of-bounds index raises an exception; the precondition for `arr.get(i)` is `0 <= i < arr.length`
- `across arr as x do` iterates over every element in order; the element type is inferred
- `add`, `add_at`, `remove`, and `put` grow and shrink arrays
- `contains`, `index_of`, `slice`, `reverse`, and `sort` are common query and transformation operations
- Arrays are values: they can be passed to and returned from functions
- Build arrays programmatically with an empty array and `add` inside a loop
- Many array operations have preconditions — especially non-emptiness and valid index bounds — that callers are responsible for satisfying



## Exercises

**1.** Write a function `min_element(arr: Array[Integer]): Integer` that returns the smallest element in a non-empty array. Test it with `[3, 7, 1, 9, 4]` (expected: 1) and `[42]` (expected: 42).

**2.** Write a function `count_above(arr: Array[Integer], threshold: Integer): Integer` that returns the number of elements strictly greater than `threshold`. Test it with `[85, 92, 78, 95, 60]` and threshold `80` (expected: 3).

**3.** Write a function `running_totals(arr: Array[Integer]): Array[Integer]` that returns a new array where each element is the sum of all elements up to and including that position. For example, `running_totals([1, 2, 3, 4])` should return `[1, 3, 6, 10]`.

**4.** Write a function `is_sorted(arr: Array[Integer]): Boolean` that returns `true` if the array is sorted in non-decreasing order and `false` otherwise. An empty array and a single-element array are both considered sorted. Test it with `[1, 2, 3, 4]` (true), `[1, 3, 2, 4]` (false), and `[]` (true).

**5.\*** Write a function `merge(a, b: Array[Integer]): Array[Integer]` that takes two arrays sorted in ascending order and returns a new array containing all elements of both, also in ascending order. For example, `merge([1, 3, 5], [2, 4, 6])` should return `[1, 2, 3, 4, 5, 6]`. Use two index variables to walk through both arrays simultaneously, adding the smaller current element at each step with `add`, then adding any remaining elements from whichever array is not yet exhausted. This is one half of the merge sort algorithm.
