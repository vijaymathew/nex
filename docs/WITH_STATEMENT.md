# Selective Code Inclusion with `with`

The `with` statement allows you to write platform-specific code within a single Nex source file. Code within a `with` block is only included when generating code for the specified target platform.

## Syntax

```nex
with "target" do
  -- Platform-specific statements
end
```

Where `target` is one of:
- `"java"` - Code for Java generation
- `"javascript"` - Code for JavaScript generation

## Basic Example

```nex
class PlatformUtils
feature
  print_platform() do
      with "java" do
        print("Running on Java")
      end
      with "javascript" do
        print("Running on JavaScript")
      end
  end
end
```

### Generated Java

```java
public class PlatformUtils {
    public void print_platform() {
        System.out.print("Running on Java");
    }
}
```

### Generated JavaScript

```javascript
class PlatformUtils {
  print_platform() {
    console.log("Running on JavaScript");
  }
}
```

## Use Cases

### 1. Platform-Specific API Calls

Use different APIs available on each platform:

```nex
class DateTime
feature
  current_time_millis(): Integer64
    note "Get current time in milliseconds"
  do
      let result := 0
      with "java" do
        -- Would use System.currentTimeMillis() in real code
        let result := 42
      end
      with "javascript" do
        -- Would use Date.now() in real code
        let result := 99
      end
      result
  end
end
```

### 2. Platform-Specific Initialization

```nex
class FileSystem
feature
  initialize() do
      with "java" do
        print("Using java.io.File")
      end
      with "javascript" do
        print("Using Node.js fs module")
      end
  end
end
```

### 3. Platform-Specific Implementations

```nex
class Random
feature
  next_int(): Integer do
      let result := 0
      with "java" do
        -- Would use java.util.Random
        let result := 1
      end
      with "javascript" do
        -- Would use Math.random()
        let result := 2
      end
      result
  end
end
```

## Behavior

### During Interpretation

When interpreting Nex code (REPL or test execution), `with` statements are **skipped**. The interpreter does not execute code within any `with` block since it's not targeting a specific platform.

### During Code Generation

When generating code for a specific target:
- **Java Generator**: Only includes code from `with "java"` blocks
- **JavaScript Generator**: Only includes code from `with "javascript"` blocks
- Code from `with` blocks for other targets is completely omitted

### Multiple Targets

You can have multiple `with` statements for different targets in the same method:

```nex
method() do
  print("Common code")  -- Included in both
  with "java" do
    print("Java only")
  end
  with "javascript" do
    print("JS only")
  end
  print("More common")  -- Included in both
end
```

## Formatting

The formatter properly handles `with` statements:

```nex
with "java" do
  statement1
  statement2
end
```

## Best Practices

### 1. Keep Platform Code Minimal

Minimize the amount of platform-specific code. Most of your logic should be platform-independent:

```nex
-- GOOD: Only platform-specific part isolated
calculate(x: Integer): Integer do
  let result := x * 2
  with "java" do
    print("Java calc")
  end
  with "javascript" do
    print("JS calc")
  end
  result
end

-- BAD: Entire method duplicated
calculate(x: Integer): Integer do
  with "java" do
    let result := x * 2
    print("Java calc")
    result
  end
  with "javascript" do
    let result := x * 2
    print("JS calc")
    result
  end
end
```

### 2. Document Platform Differences

Use notes to explain why platform-specific code is needed:

```nex
get_time(): Integer64
  note "Platform-specific time implementation"
do
  with "java" do
    -- Java: System.currentTimeMillis()
  end
  with "javascript" do
    -- JavaScript: Date.now()
  end
end
```

### 3. Test Both Targets

When using `with` statements, generate and test code for both Java and JavaScript to ensure both implementations work correctly.

### 4. Avoid Deep Nesting

Don't nest `with` statements inside other control structures unnecessarily:

```nex
-- GOOD: with at method level
method() do
  with "java" do
    if x > 0 then
      print(x)
    end
  end
end

-- AVOID: unnecessary conditional around with
method() do
  if x > 0 then
    with "java" do
      print(x)
    end
  end
end
```

## Limitations

1. **No Mixed Code**: You cannot have a single statement that spans both platforms. Each `with` block is independent.

2. **No Runtime Selection**: The target is determined at code generation time, not at runtime.

3. **Interpreter Skips All**: The interpreter doesn't execute any `with` blocks, so code relying solely on `with` statements won't work in the REPL.

4. **Two Targets Only**: Currently supports only "java" and "javascript". Other targets are ignored.

## Integration

The `with` statement is fully integrated:

- **Grammar**: `with STRING do block end`
- **Parser/Walker**: Extracts target and body
- **Interpreter**: Skips all `with` blocks
- **Java Generator**: Includes only `with "java"` blocks
- **JavaScript Generator**: Includes only `with "javascript"` blocks
- **Formatter**: Formats `with` statements consistently
- **Emacs Mode**: Syntax highlighting for `with` keyword

## Example: Complete Cross-Platform Class

```nex
class PlatformUtils
  note "Cross-platform utility methods"

feature
  get_platform_name(): String do
      let result := ""
      with "java" do
        let result := "Java"
      end
      with "javascript" do
        let result := "JavaScript"
      end
      result
  end

  print_greeting() do
      print("Hello from ")
      with "java" do
        print("Java")
      end
      with "javascript" do
        print("JavaScript")
      end
  end
end
```

This generates clean, platform-specific code for each target without duplication in your source files.
