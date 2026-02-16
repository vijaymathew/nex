# Arrays and Maps in Nex

Nex provides built-in support for arrays and maps (dictionaries/hash tables) as parameterized generic types with convenient syntax for access and initialization.

## Table of Contents

- [Arrays](#arrays)
- [Maps](#maps)
- [Subscript Access](#subscript-access)
- [Default Values](#default-values)
- [Java Translation](#java-translation)
- [Examples](#examples)

## Arrays

### Declaration

Arrays are declared using the `Array` type with a type parameter:

```nex
class Container
  feature
    items: Array [String]        -- Array of strings
    numbers: Array [Integer]      -- Array of integers
    points: Array [Point]         -- Array of Point objects
end
```

### Default Value

Array fields are automatically initialized to empty arrays (`[]`):

```nex
class Container
  feature
    items: Array [String]  -- Initialized to []
end
```

**Generated Java:**
```java
public class Container {
    private ArrayList<String> items = new ArrayList<>();
}
```

### Array Literals

Create arrays using square bracket syntax:

```nex
class Demo
  feature
    demo() do
      let numbers := [1, 2, 3, 4, 5]
      let names := ["Alice", "Bob", "Charlie"]
      let empty := []
    end
end
```

### Accessing Elements

Use subscript notation with square brackets:

```nex
class Demo
  feature
    items: Array [Integer]

    demo() do
      let first := items[0]          -- Get first element
      let second := items[1]         -- Get second element
      let last := items[items.size - 1]  -- Last element
    end
end
```

## Maps

### Declaration

Maps are declared using the `Map` type with two type parameters (key and value):

```nex
class Store
  feature
    prices: Map [String, Decimal]         -- String keys, Decimal values
    counts: Map [Integer, Integer]        -- Integer keys, Integer values
    lookup: Map [String, Array [String]]  -- String keys, Array values
end
```

### Default Value

Map fields are automatically initialized to empty maps (`{}`):

```nex
class Store
  feature
    data: Map [String, Integer]  -- Initialized to {}
end
```

**Generated Java:**
```java
public class Store {
    private HashMap<String, Integer> data = new HashMap<>();
}
```

### Map Literals

Create maps using curly brace syntax:

```nex
class Demo
  feature
    demo() do
      -- String keys with colon syntax
      let ages := {"Alice": 30, "Bob": 25}

      -- Identifier keys (converted to strings)
      let config := {name: "App", version: 1}

      -- Empty map
      let empty := {}
    end
end
```

### Accessing Entries

Use subscript notation with square brackets:

```nex
class Demo
  feature
    prices: Map [String, Decimal]

    demo() do
      let applePrice := prices["apple"]
      let orangePrice := prices["orange"]
    end
end
```

## Subscript Access

Both arrays and maps use the same subscript syntax `obj[key]` for element access.

### Array Subscript

```nex
let items: Array [String] := ["a", "b", "c"]
let x := items[0]       -- Access by index
let y := items[i]       -- Variable index
```

**Generated Java:**
```java
ArrayList<String> items = new ArrayList<>(Arrays.asList("a", "b", "c"));
x = items.get(0);
y = items.get(i);
```

### Map Subscript

```nex
let data: Map [String, Integer] := {"a": 1, "b": 2}
let x := data["a"]      -- Access by key
let y := data[key]      -- Variable key
```

**Generated Java:**
```java
HashMap<String, Integer> data = new HashMap<>() {{ put("a", 1); put("b", 2); }};
x = data.get("a");
y = data.get(key);
```

### Nested Access

You can chain subscript operations:

```nex
class Grid
  feature
    matrix: Array [Array [Integer]]

    demo() do
      let cell := matrix[0][1]  -- Access nested array
    end
end
```

## Default Values

| Type | Default Value | Java Equivalent |
|------|---------------|-----------------|
| `Array [T]` | `[]` | `new ArrayList<>()` |
| `Map [K, V]` | `{}` | `new HashMap<>()` |

### Example

```nex
class DataStore
  feature
    items: Array [String]           -- Automatically []
    lookup: Map [String, Integer]   -- Automatically {}

  constructors
    make() do
      -- Fields already initialized!
      print(items)   -- Prints empty array
      print(lookup)  -- Prints empty map
    end
end
```

## Java Translation

### Type Mappings

| Nex Type | Java Type |
|----------|-----------|
| `Array [String]` | `ArrayList<String>` |
| `Array [Integer]` | `ArrayList<Integer>` |
| `Array [T]` | `ArrayList<T>` |
| `Map [String, Integer]` | `HashMap<String, Integer>` |
| `Map [K, V]` | `HashMap<K, V>` |

### Array Literal Translation

**Nex:**
```nex
let numbers := [1, 2, 3]
```

**Java:**
```java
numbers = new ArrayList<>(Arrays.asList(1, 2, 3));
```

### Map Literal Translation

**Nex:**
```nex
let ages := {"Alice": 30, "Bob": 25}
```

**Java:**
```java
ages = new HashMap<>() {{ put("Alice", 30); put("Bob", 25); }};
```

### Subscript Translation

**Nex:**
```nex
let x := arr[0]
let y := map["key"]
```

**Java:**
```java
x = arr.get(0);
y = map.get("key");
```

## Examples

### Example 1: Simple Array Usage

```nex
class NumberList
  feature
    numbers: Array [Integer]

  constructors
    make() do
      numbers := [1, 2, 3, 4, 5]
    end

  feature
    get_first() do
      print(numbers[0])
    end

    get_at(index: Integer) do
      print(numbers[index])
    end
end
```

**Generated Java:**
```java
public class NumberList {
    private ArrayList<Integer> numbers = new ArrayList<>();

    public NumberList() {
        numbers = new ArrayList<>(Arrays.asList(1, 2, 3, 4, 5));
    }

    public void get_first() {
        System.out.println(numbers.get(0));
    }

    public void get_at(int index) {
        System.out.println(numbers.get(index));
    }
}
```

### Example 2: Simple Map Usage

```nex
class PriceList
  feature
    prices: Map [String, Decimal]

  constructors
    make() do
      prices := {"apple": 1.50, "banana": 0.75}
    end

  feature
    get_price(item: String) do
      print(prices[item])
    end
end
```

**Generated Java:**
```java
public class PriceList {
    private HashMap<String, Double> prices = new HashMap<>();

    public PriceList() {
        prices = new HashMap<>() {{ put("apple", 1.5); put("banana", 0.75); }};
    }

    public void get_price(String item) {
        System.out.println(prices.get(item));
    }
}
```

### Example 3: Arrays and Maps Together

```nex
class Store
  feature
    items: Array [String]
    prices: Map [String, Decimal]

  constructors
    make() do
      items := ["apple", "banana", "orange"]
      prices := {"apple": 1.50, "banana": 0.75, "orange": 1.25}
    end

  feature
    show_inventory() do
      let i := 0
      from
        i := 0
      until
        i >= items.size
      do
        let item := items[i]
        let price := prices[item]
        print(item)
        print(": ")
        print(price)
        i := i + 1
      end
    end
end
```

### Example 4: Nested Structures

```nex
class Grid
  feature
    matrix: Array [Array [Integer]]

  constructors
    make() do
      matrix := [[1, 2, 3], [4, 5, 6], [7, 8, 9]]
    end

  feature
    get_cell(row, col: Integer) do
      print(matrix[row][col])
    end
end
```

### Example 5: Map of Arrays

```nex
class Categories
  feature
    items: Map [String, Array [String]]

  constructors
    make() do
      items := {
        fruits: ["apple", "banana"],
        vegetables: ["carrot", "broccoli"]
      }
    end

  feature
    get_category(name: String) do
      let category := items[name]
      print(category)
    end

    get_item(category: String, index: Integer) do
      print(items[category][index])
    end
end
```

### Example 6: Type-Safe Collections

```nex
class Student
  feature
    name: String
    grade: Integer
end

class Classroom
  feature
    students: Array [Student]
    grades: Map [String, Integer]

  constructors
    make() do
      students := []
      grades := {}
    end

  feature
    add_student(s: Student) do
      -- students.add(s) would be added in a real implementation
    end

    get_grade(name: String) do
      print(grades[name])
    end
end
```

## Method Parameters

Arrays and maps can be used as method parameter types:

```nex
class Processor
  feature
    process_list(items: Array [String]) do
      let first := items[0]
      print(first)
    end

    process_map(data: Map [String, Integer]) do
      let value := data["key"]
      print(value)
    end
end
```

## Local Variables

```nex
class Demo
  feature
    demo() do
      let items: Array [String] := ["a", "b", "c"]
      let data: Map [String, Integer] := {"x": 1, "y": 2}

      let x := items[0]
      let y := data["x"]
    end
end
```

## Best Practices

### 1. Use Type Annotations

```nex
-- Good: Explicit type
let items: Array [String] := ["a", "b"]

-- Also valid: Type inference
let items := ["a", "b"]
```

### 2. Initialize in Constructors

```nex
class Store
  feature
    items: Array [String]

  constructors
    make() do
      items := []  -- Initialize to empty
    end
end
```

### 3. Choose Appropriate Key Types

```nex
-- Good: String keys for flexibility
let config: Map [String, String]

-- Good: Integer keys for indexing
let cache: Map [Integer, Data]
```

### 4. Use Nested Types When Needed

```nex
-- Complex data structures
let grid: Array [Array [Integer]]
let lookup: Map [String, Array [Student]]
```

## Limitations

1. **Bounds Checking**: Subscript access does not include automatic bounds checking in the generated code
2. **Immutability**: Arrays and maps are mutable by default
3. **No Slicing**: No built-in slice syntax like `arr[1:3]`

## See Also

- [Generic Types](GENERICS.md) - Parameterized types
- [Type System](TYPES.md) - Basic types
- [Create Keyword](CREATE.md) - Object instantiation
- [Let Syntax](LET_SYNTAX.md) - Variable declarations
