# Generic Types in Nex

Nex supports generic types (also known as parameterized types or templates), allowing you to create reusable, type-safe data structures and algorithms. This feature is inspired by Eiffel's genericity and maps cleanly to Java generics.

## Table of Contents

- [Basic Syntax](#basic-syntax)
- [Type Parameters](#type-parameters)
- [Constrained Genericity](#constrained-genericity)
- [Multiple Type Parameters](#multiple-type-parameters)
- [Using Generic Classes](#using-generic-classes)
- [Examples](#examples)
- [Java Translation](#java-translation)
- [Best Practices](#best-practices)

## Basic Syntax

### Declaring a Generic Class

Use square brackets `[` `]` to declare type parameters:

```nex
class List [G]
  feature
    item: G
    count: Integer

    put(new_item: G) do
      let item := new_item
    end
end
```

**Generated Java:**
```java
public class List<G> {
    private G item = null;
    private int count = 0;

    public void put(G new_item) {
        item = new_item;
    }
}
```

## Type Parameters

Type parameters are placeholders for actual types that will be provided when the class is used.

### Common Naming Conventions

- **`G`** - Generic type (most common)
- **`T`** - Type
- **`E`** - Element
- **`K`** - Key
- **`V`** - Value
- **`R`** - Result

### Example: Generic Box

```nex
class Box [T]
  constructors
    make(initial: T) do
      let value := initial
    end

  feature
    value: T

    get() do
      print(value)
    end

    set(new_value: T) do
      let value := new_value
    end
end
```

## Constrained Genericity

Type parameters can be constrained to specific base classes using the `->` symbol. This ensures that all actual type arguments inherit from or implement the constraint.

### Syntax

```nex
class Sorted_List [G -> Comparable]
  feature
    items: G
end
```

This means that `G` must be a subtype of `Comparable`.

**Generated Java:**
```java
public class Sorted_List<G extends Comparable> {
    private G items = null;
}
```

### Why Use Constraints?

Constraints allow you to:
1. **Call methods** on the generic type that are defined in the constraint
2. **Ensure type safety** at compile time
3. **Document requirements** for type parameters

### Example: Sortable Collection

```nex
class Sorted_List [G -> Comparable]
  feature
    items: List [G]

    add(item: G) do
      -- Can use Comparable methods on item
      -- because G is constrained to Comparable
    end

    sort() do
      -- Sorting logic using comparisons
    end
end
```

## Multiple Type Parameters

Classes can have multiple type parameters, each with optional constraints.

### Syntax

```nex
class Hash_Table [G, KEY -> Hashable]
  feature
    value: G
    key: KEY
end
```

**Generated Java:**
```java
public class Hash_Table<G, KEY extends Hashable> {
    private G value = null;
    private KEY key = null;
}
```

### Example: Dictionary

```nex
class Dictionary [K -> Comparable, V]
  feature
    key: K
    value: V

    put(k: K, v: V) do
      let key := k
      let value := v
    end

    get(k: K) do
      print(value)
    end
end
```

## Using Generic Classes

To use a generic class, provide actual type arguments in square brackets.

### Basic Usage

```nex
class Main
  feature
    demo() do
      let my_cats: List [Cat] := create List
      let numbers: List [Integer] := create List
      let names: List [String] := create List
    end
end
```

### With Constructors

```nex
class Main
  feature
    demo() do
      let box: Box [Integer] := create Box.make(42)
      let cat_box: Box [Cat] := create Box.make(fluffy)
    end
end
```

### Nested Generic Types

```nex
class Container
  feature
    lists: List [List [Integer]]  -- List of lists
    map: Hash_Table [List [String], Integer]  -- Complex nesting
end
```

## Examples

### Complete Example: Generic Stack

```nex
class Stack [G]
  constructors
    make() do
      let count := 0
    end

  feature
    top: G
    count: Integer

    push(item: G)
      require
        valid: item /= 0
      do
        let top := item
        let count := count + 1
      ensure
        pushed: count > 0
      end

    pop()
      require
        not_empty: count > 0
      do
        let count := count - 1
      ensure
        decreased: count >= 0
      end

    peek() do
      print(top)
    end
end

class Main
  feature
    demo() do
      -- Create a stack of integers
      let int_stack: Stack [Integer] := create Stack.make()

      -- Create a stack of strings
      let str_stack: Stack [String] := create Stack.make()
    end
end
```

**Generated Java:**
```java
public class Stack<G> {
    private G top = null;
    private int count = 0;

    public Stack() {
        count = 0;
    }

    public void push(G item) {
        assert (item != 0) : "Precondition violation: valid";
        top = item;
        count = (count + 1);
        assert (count > 0) : "Postcondition violation: pushed";
    }

    public void pop() {
        assert (count > 0) : "Precondition violation: not_empty";
        count = (count - 1);
        assert (count >= 0) : "Postcondition violation: decreased";
    }

    public void peek() {
        System.out.println(top);
    }
}

public class Main {
    public void demo() {
        Stack<Integer> int_stack = new Stack();
        Stack<String> str_stack = new Stack();
    }
}
```

### Example: Constrained Generic for Sorting

```nex
class Sorted_List [G -> Comparable]
  feature
    items: G

    insert(item: G)
      require
        not_null: item /= 0
      do
        let items := item
        -- Insert in sorted order using comparisons
      end

    max() do
      print(items)
    end
end

class Main
  feature
    demo() do
      -- Book must inherit from Comparable
      let books: Sorted_List [Book] := create Sorted_List

      -- This would be a type error if Integer didn't implement Comparable
      let numbers: Sorted_List [Integer] := create Sorted_List
    end
end
```

### Example: Multiple Type Parameters

```nex
class Pair [F, S]
  constructors
    make(first: F, second: S) do
      let first := first
      let second := second
    end

  feature
    first: F
    second: S

    get_first() do
      print(first)
    end

    get_second() do
      print(second)
    end
end

class Main
  feature
    demo() do
      -- Pair of Integer and String
      let coord: Pair [Integer, String] := create Pair.make(10, "north")

      -- Pair of Cat and Dog
      let pets: Pair [Cat, Dog] := create Pair.make(fluffy, rover)
    end
end
```

## Java Translation

### Type Parameter Mapping

| Nex | Java |
|-----|------|
| `class List [G]` | `public class List<G>` |
| `[G -> Comparable]` | `<G extends Comparable>` |
| `[K, V]` | `<K, V>` |
| `[K -> Hashable, V]` | `<K extends Hashable, V>` |

### Basic Type Arguments

When basic Nex types are used as type arguments, they are automatically boxed:

| Nex Type Argument | Java Type Argument |
|-------------------|-------------------|
| `List [Integer]` | `List<Integer>` |
| `List [Integer64]` | `List<Long>` |
| `List [Real]` | `List<Float>` |
| `List [Decimal]` | `List<Double>` |
| `List [Char]` | `List<Character>` |
| `List [Boolean]` | `List<Boolean>` |
| `List [String]` | `List<String>` |

### Field Declarations

```nex
feature
  items: List [Cat]
  numbers: Stack [Integer]
```

**Translates to:**
```java
private List<Cat> items = null;
private Stack<Integer> numbers = null;
```

### Method Parameters

```nex
put(item: G) do
  -- ...
end
```

**Translates to:**
```java
public void put(G item) {
    // ...
}
```

### Local Variables

```nex
let my_list: List [Integer] := create List
```

**Translates to:**
```java
List<Integer> my_list = new List();
```

## Best Practices

### 1. Use Descriptive Type Parameter Names

**Good:**
```nex
class Map [Key, Value]
```

**Acceptable:**
```nex
class Map [K, V]
```

**Less Clear:**
```nex
class Map [T, U]
```

### 2. Apply Constraints When Needed

If your generic class needs to perform operations on the type parameter, use constraints:

```nex
-- Good: Constraint allows sorting
class Sorted_List [G -> Comparable]

-- Bad: Can't sort without constraint
class Sorted_List [G]
```

### 3. Document Type Parameter Requirements

```nex
-- Type parameter G must support comparison operations
class Sorted_List [G -> Comparable]
  feature
    -- ...
end
```

### 4. Use Generics for Collections

Generic types are ideal for collections and container classes:

```nex
class List [G]
class Stack [G]
class Queue [G]
class Tree [G]
class Hash_Table [K, V]
```

### 5. Consider Type Safety

Generic types provide compile-time type checking:

```nex
let cat_list: List [Cat] := create List
cat_list.put(fluffy)        -- OK: fluffy is a Cat
-- cat_list.put(5)          -- Type error: 5 is not a Cat
```

### 6. Avoid Over-Generification

Don't use generics when a specific type is always used:

**Bad:**
```nex
class IntegerList [G]  -- Always uses Integer
```

**Good:**
```nex
class IntegerList
  feature
    value: Integer
end
```

### 7. Combine with Contracts

Generic classes work well with Design by Contract:

```nex
class Bounded_Stack [G]
  feature
    max_size: Integer
    count: Integer

    push(item: G)
      require
        not_full: count < max_size
        not_null: item /= 0
      do
        let count := count + 1
      ensure
        increased: count > 0
      end
end
```

## Limitations

1. **Type Erasure**: Like Java, Nex generics use type erasure at runtime
2. **No Primitive Specialization**: Generic type arguments must be object types
3. **No Generic Arrays**: Cannot create arrays of generic types directly

## See Also

- [Type System](TYPES.md) - Basic types and type declarations
- [Inheritance](README.md#inheritance) - Class inheritance and polymorphism
- [Contracts](CONTRACTS.md) - Design by Contract features
- [Create Keyword](CREATE.md) - Object instantiation
