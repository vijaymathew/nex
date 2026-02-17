# Nex Code Formatter

The Nex formatter (`nex.fmt`) is a tool for automatically formatting Nex source code according to standard conventions.

## Usage

### Format files in place

```bash
clojure -M:fmt file1.nex file2.nex ...
```

This will format the specified files and overwrite them with the formatted code.

### Check formatting without modifying files

```bash
clojure -M:fmt --check file.nex
```

This will check if the file is properly formatted without modifying it. Exits with code 0 if formatted, 1 if not.

## Formatting Rules

The formatter follows these conventions (matching the Emacs mode):

### Indentation
- **Indent size**: 2 spaces per level
- **No tabs**: Only spaces are used for indentation

### Class Structure
- `class` keyword at column 0
- Class-level keywords (`feature`, `constructors`, `inherit`, `invariant`) at column 0
- Members indented 2 spaces inside feature/constructor sections

### Methods and Constructors
- Method signature and `do` on same line when no contracts present:
  ```nex
  show() do
      print(x)
  end
  ```

- Method signature and `do` on separate lines when contracts present:
  ```nex
  increment(n: Integer)
    require
      positive: n > 0
    do
      let count := count + n
    ensure
      increased: count > old count
    end
  ```

### Visibility Modifiers
- `private` keyword before `feature`
- Selective visibility using `->` syntax:
  ```nex
  -> Friend, Helper feature
      show() do
          print(x)
      end
  ```

### Statements
- Block bodies indented 2 spaces relative to enclosing construct
- Contracts (`require`, `ensure`, `invariant`) indented 2 spaces
- Contract assertions indented 2 additional spaces

## Example

### Before Formatting
```nex
class Point
feature
x: Integer
y: Integer
-> Friend, Helper feature
show() do
print("Point(")
print(x)
print(",")
print(y)
print(")")
end
end
```

### After Formatting
```nex
class Point
feature
  x: Integer
  y: Integer

-> Friend, Helper feature
  show() do
      print("Point(")
      print(x)
      print(",")
      print(y)
      print(")")
  end
end
```

## Implementation

The formatter works by:
1. Parsing the Nex code into an AST
2. Walking the AST and generating formatted code
3. Applying consistent indentation rules
4. Preserving semantics while standardizing style

The formatter is implemented in `src/nex/fmt.clj` and can be used as both a library and CLI tool.

## Programmatic Usage

```clojure
(require '[nex.fmt :as fmt])

;; Format a string of code
(fmt/format-code "class Test feature x: Integer end")

;; Format a file
(fmt/format-file "path/to/file.nex")

;; Format and write back
(fmt/format-file-in-place "path/to/file.nex")
```

## Integration

The formatter can be integrated into:
- Build pipelines (use `--check` for CI/CD)
- Editor hooks (format on save)
- Pre-commit hooks (ensure consistent formatting)
- IDE plugins (format command)
