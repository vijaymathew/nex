# Arrays and Maps in Nex

Nex provides built-in support for arrays and maps (dictionaries/hash tables) as parameterized generic types with convenient method-based syntax for access and initialization.

## Table of Contents

- [Arrays](#arrays)
- [Maps](#maps)
- [Element Access](#element-access)
- [Default Values](#default-values)
- [Built-in Methods](#built-in-methods)
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

Use the `at` method to read elements and `set` method to update them:

```nex
class Demo
  feature
    items: Array [Integer]

    demo() do
      let first := items.at(0)              -- Get first element
      let second := items.at(1)             -- Get second element
      let last := items.at(items.length - 1) -- Last element

      -- Update elements (returns new array)
      items := items.set(0, 99)             -- Set first element to 99
      items := items.set(1, 77)             -- Set second element to 77
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

Use the `at` method to read entries and `set` method to update them:

```nex
class Demo
  feature
    prices: Map [String, Decimal]

    demo() do
      let applePrice := prices.at("apple")
      let orangePrice := prices.at("orange")

      -- Update entries (returns new map)
      prices := prices.set("apple", 1.99)
      prices := prices.set("banana", 0.59)
    end
end
```

## Element Access

Both arrays and maps use method calls for element access: `at` for reading and `set` for writing.

### Array Access Methods

```nex
let items: Array [String] := ["a", "b", "c"]
let x := items.at(0)        -- Access by index
let y := items.at(i)        -- Variable index

-- Update array (returns new array)
items := items.set(0, "z")  -- Set first element
items := items.set(i, "x")  -- Set element at index i
```

**Generated Java:**
```java
ArrayList<String> items = new ArrayList<>(Arrays.asList("a", "b", "c"));
x = items.get(0);
y = items.get(i);
items.set(0, "z");
items.set(i, "x");
```

### Map Access Methods

```nex
let data: Map [String, Integer] := {"a": 1, "b": 2}
let x := data.at("a")          -- Access by key
let y := data.at(key)          -- Variable key

-- Update map (returns new map)
data := data.set("a", 99)      -- Set value for key "a"
data := data.set(key, value)   -- Set value for variable key
```

**Generated Java:**
```java
HashMap<String, Integer> data = new HashMap<>() {{ put("a", 1); put("b", 2); }};
x = data.get("a");
y = data.get(key);
data.put("a", 99);
data.put(key, value);
```

### Nested Access

You can chain method calls for nested structures:

```nex
class Grid
  feature
    matrix: Array [Array [Integer]]

    demo() do
      let cell := matrix.at(0).at(1)  -- Access nested array
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

  create
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

### Access Method Translation

**Nex:**
```nex
let x := arr.at(0)
let y := map.at("key")
arr := arr.set(0, 99)
map := map.set("key", 42)
```

**Java:**
```java
x = arr.get(0);
y = map.get("key");
arr.set(0, 99);
map.put("key", 42);
```

## Examples

### Example 1: Simple Array Usage

```nex
class NumberList
  feature
    numbers: Array [Integer]

  create
    make() do
      numbers := [1, 2, 3, 4, 5]
    end

  feature
    get_first() do
      print(numbers.at(0))
    end

    get_at(index: Integer) do
      print(numbers.at(index))
    end

    set_at(index: Integer, value: Integer) do
      numbers := numbers.set(index, value)
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

    public void set_at(int index, int value) {
        numbers.set(index, value);
    }
}
```

### Example 2: Simple Map Usage

```nex
class PriceList
  feature
    prices: Map [String, Decimal]

  create
    make() do
      prices := {"apple": 1.50, "banana": 0.75}
    end

  feature
    get_price(item: String) do
      print(prices.at(item))
    end

    update_price(item: String, new_price: Decimal) do
      prices := prices.set(item, new_price)
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

    public void update_price(String item, double new_price) {
        prices.put(item, new_price);
    }
}
```

### Example 3: Arrays and Maps Together

```nex
class Store
  feature
    items: Array [String]
    prices: Map [String, Decimal]

  create
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
        i >= items.length
      do
        let item := items.at(i)
        let price := prices.at(item)
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

  create
    make() do
      matrix := [[1, 2, 3], [4, 5, 6], [7, 8, 9]]
    end

  feature
    get_cell(row, col: Integer) do
      print(matrix.at(row).at(col))
    end

    set_cell(row, col, value: Integer) do
      let row_arr := matrix.at(row)
      let updated_row := row_arr.set(col, value)
      matrix := matrix.set(row, updated_row)
    end
end
```

### Example 5: Map of Arrays

```nex
class Categories
  feature
    items: Map [String, Array [String]]

  create
    make() do
      items := {
        fruits: ["apple", "banana"],
        vegetables: ["carrot", "broccoli"]
      }
    end

  feature
    get_category(name: String) do
      let category := items.at(name)
      print(category)
    end

    get_item(category: String, index: Integer) do
      print(items.at(category).at(index))
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

  create
    make() do
      students := []
      grades := {}
    end

  feature
    add_student(s: Student) do
      students := students.append(s)
    end

    get_grade(name: String) do
      print(grades.at(name))
    end

    set_grade(name: String, grade: Integer) do
      grades := grades.set(name, grade)
    end
end
```

## Method Parameters

Arrays and maps can be used as method parameter types:

```nex
class Processor
  feature
    process_list(items: Array [String]) do
      let first := items.at(0)
      print(first)
    end

    process_map(data: Map [String, Integer]) do
      let value := data.at("key")
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

      let x := items.at(0)
      let y := data.at("x")

      -- Update collections
      items := items.set(0, "z")
      data := data.set("x", 99)
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

  create
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

## Built-in Methods

### Array Methods

- `at(index)` - Get element at index
- `set(index, value)` - Set element at index (returns new array)
- `length` - Number of elements
- `append(elem)` - Add element to end
- `is_empty()` - Check if array is empty
- `first()` - Get first element
- `last()` - Get last element

### Map Methods

- `at(key)` - Get value for key
- `set(key, value)` - Set value for key (returns new map)
- `size()` - Number of entries
- `contains_key(key)` - Check if key exists
- `keys()` - Get array of all keys
- `values()` - Get array of all values
- `is_empty()` - Check if map is empty

## Limitations

1. **Bounds Checking**: Element access does not include automatic bounds checking in the generated code
2. **Immutability**: Arrays and maps are mutable in the underlying implementation
3. **No Slicing**: No built-in slice syntax like `arr.slice(1, 3)` (though a `slice` method exists)

## See Also

- [Generic Types](GENERICS.md) - Parameterized types
- [Type System](TYPES.md) - Basic types
- [Create Keyword](CREATE.md) - Object instantiation
- [Let Syntax](LET_SYNTAX.md) - Variable declarations
