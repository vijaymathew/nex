# Nex Documentation Generator

The Nex documentation generator (`nex.docgen`) automatically generates comprehensive Markdown documentation from Nex source files. It extracts information from notes, contracts, invariants, and type signatures to produce API documentation.

## Usage

### Generate to stdout

```bash
clojure -M:docgen source.nex
```

### Generate to file

```bash
clojure -M:docgen source.nex output.md
```

### Batch generation

```bash
# Generate docs for all Nex files
for file in src/**/*.nex; do
  output="docs/$(basename $file .nex).md"
  clojure -M:docgen "$file" "$output"
done
```

## Features

### 1. Class Documentation

Extracts and formats:
- Class name with generic parameters
- Class-level notes (description)
- Inheritance information
- Class invariants

### 2. Field Documentation

For each field, includes:
- Name and type
- Visibility (🌐 Public, 🔒 Private, 🔑 Selective)
- Field notes

Fields are organized by visibility level.

### 3. Method Documentation

For each method, includes:
- Signature with parameters and return type
- Visibility level
- Method notes (description)
- Parameter list with types
- Return type
- Pre-conditions (require clause)
- Post-conditions (ensure clause)

Methods are organized by visibility level.

### 4. Constructor Documentation

For each constructor, includes:
- Signature with parameters
- Parameter list with types
- Pre-conditions
- Post-conditions

### 5. Import and Intern Documentation

Lists all imports and interns at the top of the documentation.

## Example

### Source Code

```nex
class BankAccount
  note "A bank account with balance tracking"

feature
  balance: Integer note "Current balance in cents"
  owner: String note "Account owner name"

  deposit(amount: Integer)
    note "Deposit money into the account"
    require
      positive: amount > 0
    do
      let balance := balance + amount
    ensure
      increased: balance = old balance + amount
    end

private feature
  internal_id: String note "Internal tracking ID"

create
  make(initial: Integer, name: String)
    require
      non_negative: initial >= 0
    do
      let balance := initial
      let owner := name
    end

invariant
  non_negative: balance >= 0
end
```

### Generated Documentation

```markdown
# Class: BankAccount
A bank account with balance tracking

## Class Invariants

- **non_negative**: `balance >= 0`

## Fields

### Public Fields

- **balance**: `Integer` 🌐 Public
  Current balance in cents
- **owner**: `String` 🌐 Public
  Account owner name

### Private Fields

- **internal_id**: `String` 🔒 Private
  Internal tracking ID

## Constructors

### make(`initial: Integer`, `name: String`)
**Parameters:**
- `initial`: `Integer`
- `name`: `String`

**Pre-conditions:**
- **non_negative**: `initial >= 0`

## Methods

### Public Methods

### deposit(`amount: Integer`)
🌐 Public
Deposit money into the account

**Parameters:**
- `amount`: `Integer`

**Pre-conditions:**
- **positive**: `amount > 0`

**Post-conditions:**
- **increased**: `balance = old balance + amount`
```

## Output Format

The generator produces Markdown with the following structure:

### Class Header
- `# Class: ClassName [GenericParams]`
- Class description from `note`
- Inheritance information if present

### Class Invariants
- Listed as bullet points with labels and conditions
- Conditions formatted as code

### Fields Section
Organized by visibility:
- **Public Fields**
- **Private Fields**
- **Selectively Visible Fields**

Each field shows:
- Name and type
- Visibility icon and label
- Description from `note`

### Constructors Section
Each constructor shows:
- Signature with parameters
- Parameter list
- Pre-conditions
- Post-conditions

### Methods Section
Organized by visibility:
- **Public Methods**
- **Private Methods**
- **Selectively Visible Methods**

Each method shows:
- Signature (subsection header)
- Visibility
- Description from `note`
- Parameters (if any)
- Return type (if any)
- Pre-conditions (if any)
- Post-conditions (if any)

## Visibility Icons

- 🌐 **Public**: Available to all code
- 🔒 **Private**: Only available within the class
- 🔑 **Selective**: Available to specific classes only

## Expression Formatting

Contracts and invariants are automatically formatted:
- Binary operators: `x + y`, `a > b`, `p and q`
- Unary operators: `not x`, `-y`
- Method calls: `obj.method(args)`
- Old values: `old balance`
- Literals: numbers, strings, booleans

## Generic Parameters

Generic parameters are displayed with their constraints:
- `Stack [T]`
- `Map [K -> Comparable, V]`

## Best Practices

### 1. Write Good Notes

Use `note` to document the purpose and behavior:

```nex
deposit(amount: Integer)
  note "Deposits the specified amount into the account"
do
  ...
end
```

### 2. Label Your Contracts

Give meaningful labels to pre and post-conditions:

```nex
require
  positive_amount: amount > 0
  within_limit: balance + amount <= max_balance
```

### 3. Document All Public APIs

Focus on documenting:
- Public classes
- Public methods and fields
- Constructors
- Complex algorithms
- Non-obvious behavior

### 4. Keep Notes Concise

Notes should be brief summaries (1-2 sentences). Details go in separate documentation.

### 5. Use Contracts as Documentation

Well-written contracts serve as machine-checkable documentation:

```nex
pop(): T
  note "Removes and returns the top element"
  require
    not_empty: count() > 0
  ensure
    size_decreased: count() = old count() - 1
```

## Integration

The documentation generator integrates with:

- **Build Systems**: Run as part of your build pipeline
- **CI/CD**: Generate and publish docs automatically
- **Version Control**: Track documentation changes alongside code
- **Static Site Generators**: Include generated Markdown in your docs site

## Programmatic Usage

Use the generator from Clojure code:

```clojure
(require '[nex.docgen :as docgen])

;; Generate from file
(docgen/generate-doc-from-file "src/MyClass.nex")

;; Generate from source string
(docgen/generate-file-doc source-code)

;; Generate and save
(docgen/save-doc-to-file "src/MyClass.nex" "docs/MyClass.md")
```

## Limitations

1. **No Cross-References**: Links between classes must be added manually
2. **No Diagrams**: UML or other diagrams are not generated
3. **Single File**: Each Nex file generates one Markdown file
4. **Markdown Only**: No HTML, PDF, or other formats (use pandoc for conversion)

## Future Enhancements

Potential improvements:
- Cross-reference links between classes
- Search index generation
- HTML theme support
- Inheritance diagrams
- Example code extraction
- Multi-file index pages
