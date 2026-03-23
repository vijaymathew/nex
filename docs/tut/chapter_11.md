# Nested and Composite Structures

Chapters 9 and 10 introduced arrays and maps as separate tools. Real data rarely fits neatly into one or the other. A gradebook is not just an array of scores, nor just a map from names to numbers — it is a map from student names to arrays of scores. A directory is not just a list of contacts — it is a collection of records, each containing multiple named fields. This chapter is about combining arrays and maps to model data that has genuine structure, and about what that combination makes possible.


## Arrays of Maps

An array of maps is useful when you have a sequence of records, each with the same set of named fields. Consider a list of books:

```
nex> let books: Array[Map[String, Any]] := [
     {"title": "Dune", "author": "Frank Herbert", "year": 1965},
     {"title": "Neuromancer", "author": "William Gibson", "year": 1984},
     {"title": "Foundation", "author": "Isaac Asimov", "year": 1951}
  ]

nex> books.length
3

nex> books.get(0).get("title")
"Dune"

nex> books.get(1).get("author")
"William Gibson"
```

Each element of the array is a map from field name to field value. The value type is `Any` because different fields hold different types — strings for title and author, an integer for year.

Accessing a field in a record requires two steps: first retrieve the map from the array by index, then retrieve the value from the map by key. The chain `books.get(0).get("title")` reads naturally as: *the title of the first book*.

Iterating over all records follows the same pattern as iterating over any array:

```
nex> across books as book do
     print(book.get("title") + " (" + book.get("year").to_string + ")")
  end
"Dune (1965)"
"Neuromancer (1984)"
"Foundation (1951)"
```

To find records matching a condition — all books published before 1970, say:

```
nex> across books as book do
     if convert book.get("year") to year: Integer then
        if year < 1970 then
           print(book.get("title"))
        end
     end
  end
"Dune"
"Foundation"
```



## Maps of Arrays

A map of arrays is useful when you want to group items under named categories. Consider a timetable that maps each day of the week to a list of classes:

```
nex> let timetable: Map[String, Array[String]] := {
     "Monday":    ["Maths", "Physics", "History"],
     "Tuesday":   ["English", "Chemistry"],
     "Wednesday": ["Maths", "Biology", "PE"]
  }

nex> timetable.get("Monday")
["Maths", "Physics", "History"]

nex> timetable.get("Monday").length
3

nex> timetable.get("Tuesday").get(0)
"English"
```

Accessing the first class on Tuesday requires two steps: retrieve the array for `"Tuesday"`, then get its first element. The chain `timetable.get("Tuesday").get(0)` reads as: *the first class on Tuesday*.

To iterate over all days and their classes:

```
nex> across timetable.keys as day do
     print(day + ":")
     across timetable.get(day) as cls do
        print("  " + cls)
     end
  end
"Monday:"
  "Maths"
  "Physics"
  "History"
"Tuesday:"
  "English"
  "Chemistry"
"Wednesday:"
  "Maths"
  "Biology"
  "PE"
```

The outer loop iterates over days; the inner loop iterates over the classes for each day. This nested `across` pattern is the natural way to traverse a map of arrays.



## Building Nested Structures Programmatically

Nested structures are often built up incrementally rather than written as literals. The gradebook example: given a list of (student, score) pairs, build a map from each student to their array of scores.

```
nex> function build_gradebook(entries: Array[Map[String, Any]]): Map[String, Array[Integer]]
   do
     result := {}
     across entries as entry do
        if convert entry.get("name") to name: String then
           let current := result.try_get(name, [])
           if convert entry.get("score") to score: Integer then
              current.add(score)
              result.put(name, current)
           end
        end
     end
   end
```

```
nex> let entries: Array[Map[String, Any]] := [
     {"name": "Alice", "score": 85},
     {"name": "Bob",   "score": 72},
     {"name": "Alice", "score": 91},
     {"name": "Bob",   "score": 68},
     {"name": "Alice", "score": 88}
  ]

nex> let gradebook := build_gradebook(entries)
nex> gradebook.get("Alice")
[85, 91, 88]

nex> gradebook.get("Bob")
[72, 68]
```

The `try_get(name, [])` call retrieves the existing array for that student, or an empty array if the student has not appeared yet. Then `add` appends the new score, and `put` stores the updated array back. This try-get-modify-put sequence is the standard idiom for building maps of mutable values.



## When Flat Structures Are Enough

Nesting adds expressive power but also adds complexity. Before reaching for a nested structure, ask whether a flat one would serve equally well.

A flat structure is sufficient when:

- Each item has only one piece of associated data. A map from student name to a single score does not need an array as the value type.
- The relationships between items are simple and uniform. If every student has exactly three scores, three separate maps — one per assignment — may be clearer than one map of arrays.
- You need to sort, filter, or search across the whole collection. Flat arrays are easier to sort and filter than nested maps.

Nesting becomes necessary when:

- Items have a variable number of associated values. A student may have one score or twenty; an array of arrays handles both without special cases.
- Items have multiple named attributes of different types. A book record with title, author, and year cannot be represented as a single flat value.
- The data has genuine hierarchical structure — categories containing items, items containing sub-items — that would be obscured by flattening.

The rule of thumb: use the simplest structure that accurately represents the data. Nesting for its own sake makes code harder to read and harder to get right.



## Tree-Shaped Data

Some data is genuinely hierarchical: file systems with directories containing files and subdirectories, organisation charts with managers having direct reports, category trees with subcategories. These are tree-shaped — each node may contain other nodes of the same kind.

Nex does not have a built-in tree type. Trees are represented using maps, where each node is a map with a value field and a children field that holds an array of child nodes. Here is a simple representation of a file system fragment:

```
nex> let filesystem: Map[String, Any] := {
     "name": "root",
     "type": "dir",
     "children": [
        {
           "name": "documents",
           "type": "dir",
           "children": [
              {"name": "report.txt", "type": "file", "children": []},
              {"name": "notes.txt", "type": "file", "children": []}
           ]
        },
        {
           "name": "pictures",
           "type": "dir",
           "children": [
              {"name": "photo.jpg", "type": "file", "children": []}
           ]
        }
     ]
  }
```

Each node is a `Map[String, Any]`: a `"name"` field (string), a `"type"` field (string), and a `"children"` field (array of maps). Files have empty children arrays; directories have non-empty ones.



## Traversing a Tree

Traversing a tree — visiting every node — is a naturally recursive operation. The base case is a node with no children (a leaf). The recursive case processes the node and then recursively traverses each child.

```
nex> function print_tree(node: Map[String, Any], indent: Integer)
   do
     let padding: String := ""
     from let i := 0 until i >= indent do
        padding := padding + "  "
        i := i + 1
     end
     if convert node.get("name") to name: String then
        print(padding + name)
     end
     if convert node.get("children") to children: Array[Map[String, Any]] then
        across children as child do
           print_tree(child, indent + 1)
        end
     end
   end
```

```
nex> print_tree(filesystem, 0)
"root"
  "documents"
    "report.txt"
    "notes.txt"
  "pictures"
    "photo.jpg"
```

The function takes a node and an indentation level. It prints the node's name with the appropriate leading spaces, then recursively prints each child at one level deeper. The recursion terminates when `children` is empty — `across` on an empty array performs zero iterations, so no further calls are made.

This is the recursive structure from Chapter 8 applied to a tree: process the current element, then recurse on the rest. The difference from list recursion is that each node may have multiple children, not just one, so the recursion branches at each level.



## Searching a Tree

Finding a node by name requires the same recursive structure:

```
nex> function find_node(node: Map[String, Any], target: String): ?Map[String, Any]
   do
     if node.get("name") = target then
        result := node
     else
        result := nil
        across node.get("children") as child do
           let found := find_node(child, target)
           if found /= nil then
              result := found
           end
        end
     end
   end
```

```
nex> let found := find_node(filesystem, "notes.txt")
nex> if found /= nil then
     print(found.get("name"))
  end
"notes.txt"

nex> let missing := find_node(filesystem, "missing.txt")
nex> missing
nil
```

The return type is `?Map[String, Any]` — a detachable map, because the search may find nothing. The function returns the node if its name matches, otherwise searches the children and returns the first match found, or `nil` if none is found.

This pattern — returning `nil` to signal "not found" — is the right use of detachable types: the absence of a result is a meaningful outcome, not an error.



## A Worked Example: Category Totals

Consider an expense tracker where expenses are grouped by category, and categories can contain subcategories. Each node has a `"label"`, an `"amount"` (its own expenses, not including children), and a `"children"` array of subcategory nodes.

```
nex> let expenses := {
     "label": "Total",
     "amount": 0,
     "children": [
        {
           "label": "Housing",
           "amount": 1200,
           "children": [
              {"label": "Rent",       "amount": 900,  "children": []},
              {"label": "Utilities", "amount": 300, "children": []}
           ]
        },
        {
           "label": "Food",
           "amount": 150,
           "children": [
              {"label": "Groceries",  "amount": 400,  "children": []},
              {"label": "Dining out", "amount": 200, "children": []}
           ]
        }
     ]
  }
```

A function that computes the total amount for a node, including all descendants:

```
nex> function total_amount(node: Map[String, Any]): Integer
   do
     result := node.get("amount")
     across node.get("children") as child do
        result := result + total_amount(child)
     end
   end

nex> total_amount(expenses)
2950

nex> total_amount(expenses.get("children").get(0))
2400
```

The total for the root node is the sum of every amount in the tree: 0 (root) + 1200 (Housing) + 900 (Rent) + 300 (Utilities) + 150 (Food) + 400 (Groceries) + 200 (Dining out) = 3150. Work through it by hand before trusting the function — the recursive structure makes it easy to miscount.

```
nex> total_amount(expenses)
3150
```

The function is concise because the recursive structure of the data and the recursive structure of the function align perfectly. Each node's total is its own amount plus the sum of its children's totals — and that is exactly what the function computes.



## Summary

- Arrays of maps model sequences of records; each element is a map of named fields
- Maps of arrays model grouped data; each value is a list of items under a key
- Access chains — `collection.get(key_or_index).get(key_or_index)` — navigate into nested structures
- Build nested structures with the try-get-modify-put idiom: retrieve the existing inner value (or a default), modify it, put it back
- Use flat structures when items have one associated value and relationships are uniform; use nesting when items have variable numbers of values, multiple named attributes, or genuine hierarchical structure
- Trees are represented as maps with a children field containing an array of child nodes
- Tree traversal and search are naturally recursive: process the current node, recurse on each child; the base case is an empty children array
- Detachable return types (`?Type`) are the right way to signal "not found" from a search function



## Exercises

**1.** Given the `books` array from Section 11.1, write a function `books_by_author(books: Array[Map[String, Any]], author: String): Array[Map[String, Any]]` that returns a new array containing only the books by the given author. Test it by finding all books by a specific author in a list that includes duplicates.

**2.** Write a function `invert_index(books: Array[Map[String, Any]]): Map[String, Array[String]]` that takes the books array and returns a map from each author to an array of their book titles. For example, if Herbert wrote two books, `result.get("Frank Herbert")` should return an array of both titles.

**3.** Using the filesystem tree from Section 11.5, write a function `count_files(node: Map[String, Any]): Integer` that recursively counts the total number of file nodes (nodes whose `"type"` is `"file"`). Verify that `count_files(filesystem)` returns 3.

**4.** Write a function `tree_depth(node: Map[String, Any]): Integer` that returns the maximum depth of the tree rooted at `node`. A leaf node (empty children) has depth 0. A node with children has depth equal to 1 plus the maximum depth of its children. Test it on the filesystem tree (expected depth: 2) and a single-node tree (expected depth: 0).

**5.\*** Write a function `flatten(node: Map[String, Any]): Array[String]` that returns an array of the names of all nodes in the tree in depth-first order — that is, a node appears before its children, and children are listed in order. For the filesystem tree the result should be `["root", "documents", "report.txt", "notes.txt", "pictures", "photo.jpg"]`. Then write a second function `flatten_leaves(node: Map[String, Any]): Array[String]` that returns only the leaf nodes (files). Use `flatten` or the same recursive pattern as a starting point.
