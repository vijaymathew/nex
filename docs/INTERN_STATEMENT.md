# The `intern` Statement

## Overview

The `intern` statement allows you to load and use classes from external Nex files, enabling modular code organization and reuse across your project.

## Syntax

```nex
intern path/ClassName as Alias
intern path/ClassName
```

- **path**: The module path where the class file is located (e.g., `math`, `utils/helpers`)
- **ClassName**: The name of the class to import
- **as Alias** (optional): An alias to use for the imported class

## File Search Strategy

When you use `intern path/ClassName`, the interpreter searches for `ClassName.nex` in the following locations (in order):

1. **Current directory**: `./ClassName.nex`
2. **Local libs directory**: `./libs/path/src/ClassName.nex`
3. **User deps directory**: `~/.nex/deps/path/src/ClassName.nex`

The first file found is loaded and interpreted.

## Examples

### Example 1: Basic Import

**File: `./libs/math/src/Calculator.nex`**
```nex
class Calculator
feature
  add(a: Integer, b: Integer): Integer do
    let result := a + b
  end

  multiply(a: Integer, b: Integer): Integer do
    let result := a * b
  end
end
```

**File: `main.nex`**
```nex
intern math/Calculator

class Main
feature
  run() do
    let calc := create Calculator
    print(calc.add(5, 3))
  end
end
```

### Example 2: Import with Alias

```nex
intern math/Calculator as Calc

class Main
feature
  run() do
    let calculator := create Calc
    print(calculator.multiply(4, 7))
  end
end
```

When using an alias:
- The class is available under the alias name (`Calc`)
- The class is also available under its original name (`Calculator`)

### Example 3: Multiple Imports

```nex
intern math/Calculator as Calc
intern utils/Logger
intern data/structures/Stack

class Main
feature
  run() do
    let calc := create Calc
    let logger := create Logger
    let stack := create Stack
    print("All classes loaded!")
  end
end
```

### Example 4: Deep Path

```nex
intern org/example/utils/StringHelper

class Main
feature
  process(text: String) do
    let helper := create StringHelper
    -- Use helper methods
  end
end
```

This searches for:
1. `./StringHelper.nex`
2. `./libs/org/example/utils/src/StringHelper.nex`
3. `~/.nex/deps/org/example/utils/src/StringHelper.nex`

## Directory Structure

### Local Project Libraries

For local development, organize your libraries in the `libs` directory:

```
your-project/
├── main.nex
├── libs/
│   ├── math/
│   │   └── src/
│   │       ├── Calculator.nex
│   │       └── Factorial.nex
│   └── utils/
│       └── src/
│           ├── Logger.nex
│           └── Helper.nex
```

Usage:
```nex
intern math/Calculator
intern math/Factorial
intern utils/Logger
```

### Shared User Dependencies

For shared libraries across projects, use `~/.nex/deps/`:

```
~/.nex/
└── deps/
    ├── collections/
    │   └── src/
    │       ├── List.nex
    │       └── Map.nex
    └── io/
        └── src/
            └── FileReader.nex
```

Usage:
```nex
intern collections/List
intern collections/Map
intern io/FileReader
```

### Current Directory (Development)

For quick prototyping or single-file dependencies:

```
your-project/
├── main.nex
├── Helper.nex
└── Util.nex
```

Usage:
```nex
intern any/Helper  -- Finds ./Helper.nex
intern foo/Util    -- Finds ./Util.nex
```

Note: The path component is ignored when searching the current directory; only the class name matters.

## Behavior

### Class Registration

When an `intern` statement is executed:

1. The external file is located using the search strategy
2. The file is parsed and interpreted
3. All classes in the file are registered in the context
4. If an alias is provided, the imported class is also registered under the alias name

### Namespace

- All classes share a global namespace
- If a class name conflicts, the later registration overwrites the earlier one
- Aliases do not create separate namespaces; they're just additional names for the same class

### Order of Execution

`intern` statements are processed before class declarations in the same file:

```nex
intern math/Calculator as Calc

class Main
feature
  run() do
    let calc := create Calc  -- Calculator is already available
    print(calc.add(1, 2))
  end
end
```

### Dependencies

If an interned file itself contains `intern` statements, they are processed recursively:

**File: `libs/advanced/src/AdvancedMath.nex`**
```nex
intern math/Calculator

class AdvancedMath
  inherit Calculator
feature
  power(base: Integer, exp: Integer): Integer do
    -- implementation
  end
end
```

**File: `main.nex`**
```nex
intern advanced/AdvancedMath

class Main
feature
  run() do
    let math := create AdvancedMath
    print(math.add(2, 3))  -- From Calculator
    print(math.power(2, 3))  -- From AdvancedMath
  end
end
```

## Error Handling

### File Not Found

If the class file cannot be found in any of the search locations:

```nex
intern missing/NotFound
```

**Error:**
```
Cannot find intern file for missing/NotFound
Searched locations:
  - ./NotFound.nex
  - ./libs/missing/src/NotFound.nex
  - ~/.nex/deps/missing/src/NotFound.nex
```

### Class Not Found in File

If the file exists but doesn't contain the expected class:

```nex
intern math/Calculator
```

Where `Calculator.nex` contains:
```nex
class Different
  -- ...
end
```

**Error:**
```
Class Calculator not found in file ./libs/math/src/Calculator.nex
```

## Best Practices

### 1. Organize by Functionality

```
libs/
├── math/
│   └── src/
│       ├── Calculator.nex
│       ├── Statistics.nex
│       └── Geometry.nex
├── collections/
│   └── src/
│       ├── List.nex
│       ├── Map.nex
│       └── Set.nex
└── io/
    └── src/
        ├── FileReader.nex
        └── FileWriter.nex
```

### 2. Use Aliases for Clarity

```nex
intern collections/List as LinkedList
intern collections/Map as HashMap

class DataProcessor
feature
  process() do
    let list := create LinkedList
    let map := create HashMap
    -- Clear intent about which implementations are used
  end
end
```

### 3. Group Related Imports

```nex
-- Math utilities
intern math/Calculator
intern math/Statistics
intern math/Geometry

-- Data structures
intern collections/List
intern collections/Map

-- I/O operations
intern io/FileReader
intern io/FileWriter

class Application
feature
  -- ...
end
```

### 4. Avoid Circular Dependencies

Don't create circular import chains:

❌ **Bad:**
```
A.nex: intern B
B.nex: intern C
C.nex: intern A  -- Circular!
```

✓ **Good:**
```
common/Base.nex: -- No imports
A.nex: intern common/Base
B.nex: intern common/Base
C.nex: intern A, intern B
```

## Comparison with Other Languages

### Java
```java
import com.example.math.Calculator;
// Nex equivalent: intern math/Calculator
```

### Python
```python
from math import Calculator
# Nex equivalent: intern math/Calculator

from math import Calculator as Calc
# Nex equivalent: intern math/Calculator as Calc
```

### TypeScript
```typescript
import { Calculator } from './math/Calculator'
// Nex equivalent: intern math/Calculator
```

## Future Enhancements

Potential improvements to the `intern` system:

1. **Wildcard imports**: `intern math/*` to import all classes from a directory
2. **Selective imports**: `intern math/Calculator{add, multiply}` to import only specific methods
3. **Version management**: `intern math/Calculator@1.2.3` for versioned dependencies
4. **Package manifests**: `deps.nex` file to declare all project dependencies
5. **Remote imports**: `intern github:user/repo/path/Class` for remote dependencies

## Implementation Details

The `intern` statement is processed by:

1. **Grammar** (`grammar/nexlang.g4`): Parses `intern path/Class [as Alias]`
2. **Walker** (`src/nex/walker.clj`): Creates AST node with `:type :intern`
3. **Interpreter** (`src/nex/interpreter.clj`):
   - `find-intern-file`: Searches the three locations
   - `process-intern`: Loads, parses, and registers the class
   - `:program` handler: Processes interns before classes

## Testing

Tests for the `intern` feature can be found in:
- `test/nex/intern_test.clj`

The test suite covers:
- ✓ Parsing with and without aliases
- ✓ Deep path parsing
- ✓ Multiple imports
- ✓ Error handling for missing files
- ✓ Error handling for missing classes
