# Maps

An array is the right structure when you have an ordered sequence of values and access them by position. But many problems call for a different kind of lookup: given a name, find a phone number; given a word, find its definition; given a product code, find its price. These are not positional lookups — they are lookups by *key*. The map is the structure for this.


## What a Map Is

A map stores associations between keys and values. Each key maps to exactly one value. Given a key, a map returns the associated value in constant time — it does not matter whether the map has ten entries or ten thousand.

Map literals are written with curly braces, each entry as `key: value`, entries separated by commas:

```
nex> let capitals := {"France": "Paris", "Japan": "Tokyo", "Brazil": "Brasília"}

nex> capitals.get("France")
Paris

nex> capitals.get("Japan")
Tokyo
```

The type of `capitals` is `Map[String, String]` — a map from string keys to string values. The key type and value type can be any types that support equality comparison. Common combinations are `Map[String, Integer]`, `Map[String, String]`, and `Map[Integer, String]`.

An empty map requires a type annotation:

```
nex> let prices: Map[String, Real] := {}
```

Note that `{}` is the empty map literal, just as `[]` is the empty array literal.



## Adding and Updating Entries

The `put` method adds a new entry or replaces an existing one:

```
nex> let scores: Map[String, Integer] := {}

nex> scores.put("Alice", 92)
nex> scores.put("Bob", 78)
nex> scores.put("Carol", 85)
nex> scores
{Alice: 92, Bob: 78, Carol: 85}

nex> scores.put("Alice", 95)
nex> scores
{Alice: 95, Bob: 78, Carol: 85}
```

If the key `"Alice"` already exists, `put` replaces its value. If it does not exist, `put` creates a new entry. There is no separate "insert" and "update" — `put` handles both.



## Reading Values

The `get` method retrieves the value associated with a key:

```
nex> scores.get("Bob")
78
```

If the key does not exist, `get` raises an exception. This is the map equivalent of an out-of-bounds array access — the precondition for `get` is that the key must be present. When you are not certain a key exists, check first with `contains_key`:

```
nex> scores.contains_key("Bob")
true

nex> scores.contains_key("David")
false

nex> if scores.contains_key("David") then
       print(scores.get("David"))
    else
       print("David not found")
    end
David not found
```

The `try_get` method provides a more concise alternative — it returns a default value when the key is absent, avoiding the exception entirely:

```
nex> scores.try_get("David", 0)
0

nex> scores.try_get("Alice", 0)
95
```

`try_get` is the right choice when a missing key has a sensible default. `get` is the right choice when a missing key represents a genuine error that should be caught immediately.



## Removing Entries

The `remove` method deletes an entry by key:

```
nex> scores.remove("Bob")

nex> scores
{Alice: 95, Carol: 85}
```

Like `get`, `remove` raises an exception if the key does not exist. Check with `contains_key` first if the key's presence is not guaranteed.



## Querying a Map

Several methods provide information about a map's contents without modifying it:

```
nex> scores.size
2

nex> scores.is_empty
false
```

Note the naming difference from arrays: maps use `size` where arrays use `length`. Both return the number of elements, but the method names differ. It is worth being deliberate about which you are calling.

`keys` and `values` return the map's keys and values as arrays:

```
nex> scores.keys
[Alice, Carol]

nex> scores.values
[95, 85]
```

The order of keys and values in these arrays reflects the map's internal ordering, which may not match the insertion order. If you need entries in a specific order, sort the keys array first and look up each value:

```
nex> let sorted_keys := scores.keys.sort
nex> across sorted_keys as k do
       print(k + ": " + scores.get(k).to_string)
    end
Alice: 95
Carol: 85
```



## Iterating with `across`

`across` iterates over a map's entries. Each element bound by the loop variable is a two-element array of type `Array[Any]`, where index 0 is the key and index 1 is the value. Because the element type is `Any`, you may need to be mindful of types when using the values in typed contexts:

```
nex> let capitals := {"France": "Paris", "Japan": "Tokyo", "Brazil": "Brasília"}

nex> across capitals as entry do
       print(entry.get(0) + " -> " + entry.get(1))
    end
France -> Paris
Japan -> Tokyo
Brazil -> Brasilia
```

When you only need the keys or only the values, iterate over `map.keys` or `map.values` instead:

```
nex> across capitals.keys as country do
       print(country)
    end
France
Japan
Brazil
```



## Building Maps with Loops

Like arrays, maps are often built programmatically. The pattern is an empty map and `put` inside a loop:

```
nex> let word_lengths: Map[String, Integer] := {}

nex> let words := ["apple", "fig", "banana", "kiwi"]

nex> across words as w do
       word_lengths.put(w, w.length)
    end

nex> word_lengths
{apple: 5, fig: 3, banana: 6, kiwi: 4}
```

This builds a map from each word to its length. The loop body calls `put` once per word; each call either adds a new entry or — if a word appeared before — replaces it.



## Maps and Functions

Maps are values and can be passed to and returned from functions:

```
nex> function invert(m: Map[String, String]): Map[String, String]
     do
       result := {}
       across m as entry do
         result.put(entry.get(1), entry.get(0))
       end
     end

nex> let capitals := {"France": "Paris", "Japan": "Tokyo", "Brazil": "Brasília"}
nex> let by_capital := invert(capitals)
nex> by_capital.get("Tokyo")
Japan
```

`invert` builds a new map that swaps keys and values. This has an implicit precondition: the values in the original map must all be distinct, otherwise some entries will silently overwrite others in the result. A note in a comment — or, in Part V, a `require` clause — should state this assumption.



## Choosing Between Arrays and Maps

Arrays and maps are both collections, but they suit different problems. The question to ask is: *how will this data be accessed?*

If access is by position — "give me the third element", "give me the last element", "process every element in order" — an array is the right choice. Arrays preserve insertion order and support efficient positional access.

If access is by identity — "give me the score for Alice", "does this word appear in the dictionary?", "what is the price of product X?" — a map is the right choice. Maps support efficient key-based lookup regardless of how many entries they contain.

A common pattern is to use both together: an array to preserve order and a map to enable fast lookup. For example, a list of students in class order (array) alongside a map from student name to their record (map). The array answers "who is the fifth student?"; the map answers "what are Alice's grades?".



## A Worked Example: Word Frequency Counter

A word frequency counter reads a string of text and counts how many times each word appears. Maps are the natural structure: the keys are words, the values are counts.

```
nex> function word_frequencies(text: String): Map[String, Integer]
     do
       result := {}
       let words := text.to_lower.split(" ")
       across words as w do
         let count := result.try_get(w, 0)
         result.put(w, count + 1)
       end
     end
```

```
nex> let text := "to be or not to be that is the question to be to"

nex> let freq := word_frequencies(text)
nex> freq.get("to")
4

nex> freq.get("be")
3

nex> freq.get("question")
1
```

The key line is `result.try_get(w, 0)` — it retrieves the current count for word `w`, or `0` if the word has not been seen yet. Then `put` stores the incremented count. This try-get-then-put pattern is the standard idiom for accumulating counts in a map.

To find the most frequent word:

```
nex> function most_frequent(freq: Map[String, Integer]): String
     do
       result := ""
       let started := false
       across freq as entry do
         if not started then
           result := entry.get(0)
           started := true
         else
           if freq.get(entry.get(0)) > freq.get(result) then
             result := entry.get(0)
           end
         end
       end
     end

nex> most_frequent(freq)
to
```

`most_frequent` initialises `result` with the first key and scans all keys, updating `result` whenever a more frequent word is found. This is the same maximum-finding pattern from Chapter 9, applied to map values instead of array elements. The implicit precondition is the same: the map must not be empty.



## Summary

- A map stores key-value associations; keys are unique and each maps to exactly one value
- Map literals use curly braces: `{"key": value,    }`; the empty map is `{}`
- `put(key, value)` adds or replaces an entry; `get(key)` retrieves a value; `remove(key)` deletes an entry
- `get` and `remove` raise exceptions when the key is absent; use `contains_key` to check first, or `try_get(key, default)` to provide a fallback
- Maps use `size` (not `length`) for the element count
- `across map as entry do` iterates over entries; each entry is a two-element array — `entry.get(0)` is the key, `entry.get(1)` is the value; or iterate over `map.keys` or `map.values` directly
- Build maps programmatically with an empty map and `put` inside a loop
- Use an array when access is positional; use a map when access is by key
- The try-get-then-put pattern — `try_get(key, default)` followed by `put(key, new_value)` — is the standard idiom for accumulating values in a map



## Exercises

**1.** Write a function `char_frequencies(s: String): Map[Char, Integer]` that returns a map from each character in `s` to the number of times it appears. Test it on `"mississippi"` — verify that `#m` maps to 1, `#i` maps to 4, `#s` maps to 4, and `#p` maps to 2.

**2.** Write a function `group_by_length(words: Array[String]): Map[Integer, Array[String]]` that groups words by their length. For example, `group_by_length(["cat", "dog", "elephant", "ox", "ant"])` should return `{3: [cat, dog, ant], 8: [elephant], 2: [ox]}`. Use `try_get` with an empty array as the default, append the word to the array, and put it back.

**3.** Write a function `histogram(freq: Map[String, Integer])` that prints a simple text histogram. For each key in sorted order, print the key followed by a bar of `#` characters equal to its frequency. For example, a map `{"a": 3, "b": 1, "c": 2}` should print:

```
a: ###
b: #
c: ##
```

**4.** Two maps can be merged by combining their entries. Write a function `merge_maps(a, b: Map[String, Integer]): Map[String, Integer]` that returns a new map containing all entries from both. If a key appears in both maps, the values should be summed. Test it with `{"a": 1, "b": 2}` and `{"b": 3, "c": 4}` — the result should be `{"a": 1, "b": 5, "c": 4}`.

**5.\*** Write a function `is_anagram(s, t: String): Boolean` that returns `true` if `s` and `t` are anagrams of each other — that is, if they contain exactly the same characters with the same frequencies, ignoring case. Use `char_frequencies` from Exercise 1 as a helper. Two maps are equal when they have the same keys with the same values; check this by verifying that every key in the first map appears in the second with the same count, and that both maps have the same size. Test with `"listen"` and `"silent"` (true), `"hello"` and `"world"` (false), and `"Astronomer"` and `"Moon starer"` (true, after removing spaces).
