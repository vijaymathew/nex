# The `import` Statement

## Overview

The `import` statement allows you to use external Java and JavaScript classes/modules in your Nex programs. This enables interoperability with existing libraries and frameworks in the target language.

## Important Note

**Import statements are primarily for code generation.** Programs with external imports may not be interpretable in the Nex runtime, as the interpreter would need access to the Java/JavaScript runtime environment. However, the generated Java or JavaScript code will properly include these imports and work as expected.

## Syntax

### Java Imports
```nex
import package.name.ClassName
```

### JavaScript Imports
```nex
import Identifier from 'module-path'
import Identifier from "module-path"
```

## Examples

### Example 1: Using Java Scanner

**Nex Code:**
```nex
import java.util.Scanner

class JavaDemo
feature
  readInput do
     let s: Scanner := create Scanner.Scanner(System.in)
     print("Enter username")
     let userName: String = s.nextLine()
     print("Username is: " + userName)
  end
end
```

**Generated Java:**
```java
import java.util.Scanner;

public class JavaDemo {
    public void readInput() {
        Scanner s = new Scanner(System.in);
        System.out.print("Enter username");
        String userName = s.nextLine();
        System.out.print("Username is: " + userName);
    }
}
```

### Example 2: Using JavaScript Modules

**Nex Code:**
```nex
import Math from './utils.js'

class JSDemo
feature
   showPI do
      print(Math.PI)
   end
end
```

**Generated JavaScript:**
```javascript
import Math from './utils.js';

class JSDemo {
  constructor() {
  }

  showPI() {
    console.log(Math.PI);
  }
}
```

### Example 3: Multiple Java Imports

**Nex Code:**
```nex
import java.util.ArrayList
import java.util.HashMap
import java.io.File

class DataProcessor
feature
  process do
    let list: ArrayList := create ArrayList.ArrayList()
    let map: HashMap := create HashMap.HashMap()
  end
end
```

**Generated Java:**
```java
import java.util.ArrayList;
import java.util.HashMap;
import java.io.File;

public class DataProcessor {
    public void process() {
        ArrayList list = new ArrayList();
        HashMap map = new HashMap();
    }
}
```

### Example 4: Multiple JavaScript Imports

**Nex Code:**
```nex
import React from './react.js'
import Lodash from './lodash.js'
import Utils from './utils.js'

class WebComponent
feature
  render do
    print("Rendering with React")
  end
end
```

**Generated JavaScript:**
```javascript
import React from './react.js';
import Lodash from './lodash.js';
import Utils from './utils.js';

class WebComponent {
  constructor() {
  }

  render() {
    console.log("Rendering with React");
  }
}
```

## Creating External Class Instances

### Java Constructor Syntax

In Java, to create an instance of an imported class:

```nex
let scanner: Scanner := create Scanner.Scanner(System.in)
```

This is equivalent to Java's:
```java
Scanner scanner = new Scanner(System.in);
```

**Pattern:** `create ClassName.ClassName(args)`
- First `ClassName`: The class type
- Second `ClassName`: The constructor name (same as class for default constructor)
- `args`: Constructor arguments

### JavaScript Class Usage

For JavaScript, you can access static properties and methods:

```nex
import Math from './utils.js'

class Demo
feature
  show do
    print(Math.PI)
    let result := Math.sqrt(16)
  end
end
```

## Code Generation Behavior

### Java Generator
- **Processes:** Only imports without a `from` clause
- **Ignores:** JavaScript imports (those with `from 'path'`)
- **Output:** Standard Java import statements at the top of the file

### JavaScript Generator
- **Processes:** Only imports with a `from` clause
- **Ignores:** Java imports (those without `from`)
- **Output:** ES6 import statements at the top of the file

### Example: Mixed Imports

**Nex Code:**
```nex
import java.util.Scanner
import React from './react.js'
import java.io.File

class MixedExample
feature
  demo do
    print("Example")
  end
end
```

**Generated Java (ignores React):**
```java
import java.util.Scanner;
import java.io.File;

public class MixedExample {
    public void demo() {
        System.out.print("Example");
    }
}
```

**Generated JavaScript (ignores Java imports):**
```javascript
import React from './react.js';

class MixedExample {
  constructor() {
  }

  demo() {
    console.log("Example");
  }
}
```

## Combining with Intern

The `import` statement works alongside the `intern` statement:

```nex
import java.util.Scanner        -- External Java class
intern math/Calculator           -- Internal Nex class

class Application
feature
  run do
    let scanner: Scanner := create Scanner.Scanner(System.in)
    let calc := create Calculator
  end
end
```

**Key Differences:**

| Feature | `import` | `intern` |
|---------|----------|----------|
| Purpose | External Java/JS classes | Internal Nex classes |
| Interpretation | Not interpretable | Fully interpretable |
| Code Generation | Passes through imports | Generates full class code |
| File Format | .jar, .js | .nex |

## Common Use Cases

### 1. Java Standard Library

```nex
import java.util.ArrayList
import java.util.HashMap
import java.io.File
import java.time.LocalDateTime
```

### 2. Java Third-Party Libraries

```nex
import com.google.gson.Gson
import org.apache.commons.lang3.StringUtils
```

### 3. JavaScript Node Modules

```nex
import express from 'express'
import mongoose from 'mongoose'
import lodash from 'lodash'
```

### 4. JavaScript Local Modules

```nex
import Utils from './utils.js'
import Config from '../config.js'
import API from './api/client.js'
```

### 5. JavaScript React Components

```nex
import React from 'react'
import ReactDOM from 'react-dom'
import Component from './components/MyComponent.js'
```

## Limitations

### Current Limitations

1. **No wildcard imports:** Cannot use `import java.util.*` or `import * from './utils.js'`
2. **No selective imports:** Cannot use `import { useState } from 'react'`
3. **No import aliases:** Cannot use `import Scanner as InputScanner`
4. **No static imports:** Java static imports not supported
5. **Interpretation:** Programs with imports cannot be interpreted (code generation only)

### Future Enhancements

Potential improvements to the import system:

1. **Wildcard imports:** `import java.util.*`
2. **Selective imports:** `import { Component, useState } from 'react'`
3. **Import aliases:** `import Scanner as InputScanner`
4. **Static imports:** `import static java.lang.Math.PI`
5. **Dynamic imports:** Runtime import loading
6. **Type checking:** Validate imported class usage
7. **Auto-import suggestions:** IDE-like import suggestions

## Best Practices

### 1. Group Imports by Type

```nex
-- Java standard library
import java.util.ArrayList
import java.io.File

-- Third-party libraries
import com.google.gson.Gson

-- Local Nex classes
intern math/Calculator
intern utils/Logger

class Application
feature
  -- ...
end
```

### 2. Use Descriptive Variable Names

```nex
import java.util.Scanner

class Input
feature
  read do
    let inputScanner: Scanner := create Scanner.Scanner(System.in)
    -- Clear that this is a Scanner instance
  end
end
```

### 3. Document External Dependencies

```nex
-- Required: Java 11+ for java.time package
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class TimeTracker
feature
  getCurrentTime: String do
    -- Implementation
  end
end
```

### 4. Target-Specific Code

When writing code for specific targets, clearly indicate:

```nex
-- JAVA ONLY: This class requires Java standard library
import java.util.Scanner
import java.io.BufferedReader

class JavaFileReader
feature
  readFile(path: String) do
    -- Java-specific implementation
  end
end
```

```nex
-- JAVASCRIPT ONLY: This class requires Node.js modules
import fs from 'fs'
import path from 'path'

class NodeFileReader
feature
  readFile(filepath: String) do
    -- Node.js-specific implementation
  end
end
```

## Interpreter Behavior

When you interpret Nex code with import statements:

```nex
import java.util.Scanner

class Demo
feature
  test do
    print("Demo")
  end
end
```

**Interpreter behavior:**
- Imports are **stored** in the context
- Imports are **not executed** (no Java/JS runtime available)
- Classes are registered normally
- Methods without external dependencies can run
- Methods using imported classes will fail at runtime

**Recommendation:** Use imports only when targeting code generation, not interpretation.

## Testing

Tests for the `import` feature can be found in:
- `test/nex/import_test.clj`

The test suite covers:
- ✓ Parsing Java imports
- ✓ Parsing JavaScript imports with single/double quotes
- ✓ Multiple imports
- ✓ Mixed Java and JavaScript imports
- ✓ Java code generation with import filtering
- ✓ JavaScript code generation with import filtering
- ✓ Combining import with intern

## Comparison with Other Languages

### Java
```java
import java.util.Scanner;
import java.util.ArrayList;
```

**Nex equivalent:**
```nex
import java.util.Scanner
import java.util.ArrayList
```

### JavaScript (ES6)
```javascript
import React from 'react';
import { useState } from 'react';
import * as Utils from './utils.js';
```

**Nex equivalent (limited):**
```nex
import React from 'react'
-- Selective and wildcard imports not yet supported
```

### Python
```python
import math
from collections import Counter
```

**Nex equivalent (if Python were supported):**
```nex
-- Would need Python-specific import syntax
-- Currently only Java and JavaScript are supported
```

## Summary

- **Purpose:** Import external Java and JavaScript classes/modules
- **Syntax:**
  - Java: `import package.name.ClassName`
  - JavaScript: `import Identifier from 'path'`
- **Code Generation:** Automatically filtered by target language
- **Interpretation:** Not supported (code generation only)
- **Best Used With:** Generated code compilation, not interpretation
- **Combines With:** `intern` statement for Nex classes
