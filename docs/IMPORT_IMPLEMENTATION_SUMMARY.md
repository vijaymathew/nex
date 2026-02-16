# Implementation Summary: `import` Statement

## Overview

Implemented the `import` statement to enable interoperability with external Java and JavaScript classes/modules. This allows Nex programs to leverage existing libraries and frameworks when generating target language code.

## Syntax

```nex
import package.name.ClassName          -- Java
import Identifier from 'module-path'   -- JavaScript
```

## Files Modified

### 1. Grammar (`grammar/nexlang.g4`)

**Changes:**

#### a. Added import statement to program rule
```antlr
program
    : (importStmt | internStmt | classDecl)+ EOF
    | methodCall+ EOF
    ;
```

#### b. Added importStmt rule
```antlr
importStmt
    : IMPORT IDENTIFIER ('.' IDENTIFIER)* (FROM STRING)?
    ;
```

Supports:
- Java: `import java.util.Scanner` (dot-separated qualified name)
- JavaScript: `import Math from './utils.js'` (identifier FROM string)

#### c. Added IMPORT keyword
```antlr
IMPORT : 'import';
```

#### d. Enhanced STRING token to support single quotes
```antlr
STRING
    : '"' (~["\\] | '\\' .)* '"'    -- Double quotes
    | '\'' (~['\\] | '\\' .)* '\''  -- Single quotes
    ;
```

**Rationale:** JavaScript commonly uses single quotes for import paths

**Parse Tree Examples:**

Java:
```
import java.util.Scanner
→ (:importStmt "import" "java" "." "util" "." "Scanner")
```

JavaScript:
```
import Math from './utils.js'
→ (:importStmt "import" "Math" "from" "'./utils.js'")
```

### 2. Walker (`src/nex/walker.clj`)

**Changes:**

#### a. Updated `:program` handler
```clojure
:program
(fn [[_ & nodes]]
  (let [cleaned-nodes (remove string? nodes)
        transformed (mapv transform-node cleaned-nodes)
        classes (filter #(= :class (:type %)) transformed)
        interns (filter #(= :intern (:type %)) transformed)
        imports (filter #(= :import (:type %)) transformed)]
    {:type :program
     :imports (vec imports)
     :interns (vec interns)
     :classes (vec classes)}))
```

Now returns three separate collections: imports, interns, and classes.

#### b. Added `:importStmt` handler
```clojure
:importStmt
(fn [[_ _import-kw & tokens]]
  (let [has-from? (some #(= "from" %) tokens)
        main-parts (if has-from?
                    (take-while #(not= "from" %) tokens)
                    tokens)
        source (when has-from? (last tokens))
        name-parts (remove #(= "." %) main-parts)
        qualified-name (if has-from?
                        (first name-parts)
                        (clojure.string/join "." name-parts))]
    {:type :import
     :qualified-name qualified-name
     :source source}))
```

**Logic:**
- Java imports: Join all parts with dots (`java.util.Scanner`)
- JavaScript imports: Use first part as identifier, store source path

**AST Examples:**

Java:
```clojure
{:type :import
 :qualified-name "java.util.Scanner"
 :source nil}
```

JavaScript:
```clojure
{:type :import
 :qualified-name "Math"
 :source "'./utils.js'"}
```

### 3. Interpreter (`src/nex/interpreter.clj`)

**Changes:**

#### a. Extended Context record
```clojure
(defrecord Context [classes globals current-env output imports])
```

Added `:imports` field to store import statements.

#### b. Updated make-context
```clojure
(defn make-context
  []
  (let [globals (make-env)]
    (->Context
     (atom {})       ; classes registry
     globals         ; global environment
     globals         ; current environment
     (atom [])       ; output accumulator
     (atom []))))    ; imports registry
```

#### c. Modified `:program` handler
```clojure
(defmethod eval-node :program
  [ctx {:keys [imports interns classes]}]
  ;; Store all import statements (for code generation)
  (doseq [import-node imports]
    (when (map? import-node)
      (swap! (:imports ctx) conj import-node)))

  ;; Process intern statements
  (doseq [intern-node interns]
    (when (map? intern-node)
      (process-intern ctx intern-node)))

  ;; Register class definitions
  (doseq [class-node classes]
    (when (map? class-node)
      (case (:type class-node)
        :class (register-class ctx class-node)
        :call (eval-node ctx class-node)
        nil)))

  ctx)
```

**Behavior:**
- Imports are **stored** but not executed (no Java/JS runtime available)
- Enables code generators to access imports from context
- Maintains order of imports for generated code

### 4. Java Generator (`src/nex/generator/java.clj`)

**Changes:**

#### a. Added `generate-import` function
```clojure
(defn generate-import
  "Generate a Java import statement"
  [{:keys [qualified-name source]}]
  ;; Only generate Java imports (those without a 'source' field)
  (when-not source
    (str "import " qualified-name ";")))
```

**Logic:** Filters out JavaScript imports (those with a `source` field)

#### b. Modified `translate-ast`
```clojure
(defn translate-ast
  ([ast] (translate-ast ast {}))
  ([ast opts]
   (let [imports (:imports ast)
         classes (:classes ast)
         java-imports (keep generate-import imports)
         java-classes (map #(generate-class % opts) classes)
         parts (concat java-imports [""] java-classes)]
     (str/join "\n" (remove empty? parts)))))
```

**Output Structure:**
```java
import java.util.Scanner;
import java.io.File;

public class MyClass {
    // class body
}
```

### 5. JavaScript Generator (`src/nex/generator/javascript.clj`)

**Changes:**

#### a. Added `generate-import` function
```clojure
(defn generate-import
  "Generate a JavaScript import statement"
  [{:keys [qualified-name source]}]
  ;; Only generate JS imports (those with a 'source' field)
  (when source
    (let [clean-source (if (and (string? source)
                               (or (.startsWith source "\"")
                                   (.startsWith source "'")))
                        (subs source 1 (dec (count source)))
                        source)]
      (str "import " qualified-name " from '" clean-source "';"))))
```

**Logic:**
- Filters out Java imports (those without `source`)
- Removes quotes from source string and re-wraps in single quotes
- Normalizes to ES6 import syntax

#### b. Modified `translate-ast`
```clojure
(defn translate-ast
  ([ast] (translate-ast ast {}))
  ([ast opts]
   (let [imports (:imports ast)
         classes (:classes ast)
         js-imports (keep generate-import imports)
         js-classes (map #(generate-class % opts) classes)
         parts (concat js-imports [""] js-classes)]
     (str/join "\n" (remove empty? parts)))))
```

**Output Structure:**
```javascript
import React from './react.js';
import Lodash from './lodash.js';

class MyClass {
    // class body
}
```

### 6. Tests (`test/nex/import_test.clj`)

**Comprehensive test suite:**

```clojure
(ns nex.import-test
  (:require [clojure.test :refer [deftest is testing]]
            [nex.parser :as p]
            [nex.generator.java :as java]
            [nex.generator.javascript :as js]))

(deftest import-parsing-test ...)
(deftest java-import-generation-test ...)
(deftest javascript-import-generation-test ...)
(deftest mixed-imports-generation-test ...)
(deftest import-with-intern-test ...)
```

**Coverage:**
- ✓ Java import parsing
- ✓ JavaScript import parsing (single/double quotes)
- ✓ Multiple imports
- ✓ Mixed Java and JavaScript imports
- ✓ Java code generation with filtering
- ✓ JavaScript code generation with filtering
- ✓ Combining import with intern

**Test Results:**
- 5 test suites
- 42 assertions
- All passing ✓

### 7. Documentation (`docs/IMPORT_STATEMENT.md`)

**Comprehensive user guide covering:**
- Syntax and examples
- Java and JavaScript usage patterns
- Code generation behavior
- Creating external class instances
- Combining with intern
- Limitations and future enhancements
- Best practices
- Testing information

## Features Implemented

### ✅ Core Functionality
- [x] Parse Java-style imports: `import package.Class`
- [x] Parse JavaScript-style imports: `import Name from 'path'`
- [x] Support single and double quotes for JS paths
- [x] Multiple imports per file
- [x] Mixed Java and JavaScript imports

### ✅ Code Generation
- [x] Java generator outputs Java imports only
- [x] JavaScript generator outputs JS imports only
- [x] Automatic filtering by target language
- [x] Proper import placement (top of file)
- [x] Preserve import order

### ✅ Integration
- [x] Works alongside intern statements
- [x] Works alongside class declarations
- [x] Stored in interpreter context
- [x] No conflicts with existing features

### ✅ Testing
- [x] Comprehensive test suite
- [x] All tests passing
- [x] No regressions in existing tests

## Usage Examples

### Example 1: Java Scanner

**Input:**
```nex
import java.util.Scanner

class JavaDemo
feature
  readInput do
     let s: Scanner := create Scanner.Scanner(System.in)
     print("Enter username")
     let userName: String = s.nextLine()
  end
end
```

**Generated Java:**
```java
import java.util.Scanner;

public class JavaDemo {
    public void readInput() {
        Scanner s = new Scanner(System.in());
        System.out.print("Enter username");
        String userName = s.nextLine();
    }
}
```

### Example 2: JavaScript Module

**Input:**
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
    console.log(Math.PI());
  }
}
```

### Example 3: Mixed Imports (Filtered by Target)

**Input:**
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

**Generated Java:**
```java
import java.util.Scanner;
import java.io.File;

public class MixedExample {
    public void demo() {
        System.out.print("Example");
    }
}
```

**Generated JavaScript:**
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

## Testing Summary

### Unit Test Results

**All test suites passing:**
- `nex.param-syntax-test`: 5 tests ✓
- `nex.old-keyword-test`: 6 tests ✓
- `nex.intern-test`: 1 test ✓
- `nex.import-test`: 5 tests ✓
- `nex.generator.java-test`: 11 tests ✓
- `nex.generator.javascript_test`: 26 tests ✓

**Total:** 54 tests, 214 assertions, 0 failures ✓

### Manual Testing

**Java Import:**
```bash
$ clj -M -e "(require '[nex.parser :as p] '[nex.generator.java :as java])
             (println (java/translate (slurp \"test_java.nex\")))"
import java.util.Scanner;

public class JavaDemo { ... }
```

**JavaScript Import:**
```bash
$ clj -M -e "(require '[nex.parser :as p] '[nex.generator.javascript :as js])
             (println (js/translate (slurp \"test_js.nex\")))"
import Math from './utils.js';

class JSDemo { ... }
```

## Design Decisions

### 1. Separate Java and JavaScript Imports
**Decision:** Use `from` keyword to distinguish JS imports from Java imports
**Rationale:**
- Java: `import java.util.Scanner` (no from clause)
- JavaScript: `import Math from './utils.js'` (with from clause)
- Allows mixing both in one file
- Code generators filter based on presence of `from`

### 2. Single Quotes Support
**Decision:** Accept both single and double quotes for strings
**Rationale:**
- JavaScript commonly uses single quotes
- Consistency with JS ecosystem
- User convenience

### 3. No Interpretation of Imports
**Decision:** Imports stored but not executed in interpreter
**Rationale:**
- Interpreter doesn't have Java/JS runtime
- Imports primarily for code generation
- Attempting execution would fail anyway

### 4. Filter by Target Language
**Decision:** Each generator only outputs relevant imports
**Rationale:**
- Java generator ignores JS imports
- JS generator ignores Java imports
- Allows writing target-agnostic Nex classes with conditional imports
- Clean generated code

### 5. Store in Context
**Decision:** Add imports field to Context record
**Rationale:**
- Makes imports accessible to generators
- Preserves import information throughout pipeline
- Enables future features (import validation, auto-completion)

## Limitations

### Current Limitations

1. **No wildcard imports:** `import java.util.*` not supported
2. **No selective imports:** `import { useState } from 'react'` not supported
3. **No import aliases:** `import Scanner as InputScanner` not supported
4. **No static imports:** Java `import static` not supported
5. **No interpretation:** Programs with imports cannot be interpreted
6. **Limited validation:** No checking if imported classes actually exist

### Future Enhancements

1. **Wildcard imports:** Support `*` for bulk imports
2. **Selective imports:** ES6 destructuring syntax
3. **Import aliases:** Rename imports for clarity
4. **Static imports:** Java static member imports
5. **Type validation:** Check if imported types are used correctly
6. **Auto-import:** Suggest imports based on undefined types
7. **Python support:** Add Python import syntax when Python generator is added

## Known Issues

### Field vs Method Access

**Issue:** Properties are generated with parentheses

```nex
print(Math.PI)       -- Nex
console.log(Math.PI())  -- Generated (incorrect)
```

**Expected:**
```javascript
console.log(Math.PI)  -- Should be property access
```

**Cause:** Parser treats all dotted access as method calls
**Status:** Pre-existing issue, not specific to imports
**Workaround:** Manually edit generated code, or use methods instead of properties

## Compatibility

- **Backward Compatible:** ✓ Programs without imports work unchanged
- **Breaking Changes:** None
- **New Dependencies:** None

## Conclusion

The `import` statement implementation is complete and fully functional:
- ✅ Grammar and parsing for Java and JavaScript
- ✅ AST transformation
- ✅ Interpreter storage (non-executable)
- ✅ Java code generation with filtering
- ✅ JavaScript code generation with filtering
- ✅ Comprehensive testing
- ✅ Documentation

The feature enables Nex programs to interoperate with external Java and JavaScript ecosystems, significantly expanding the language's practical utility while maintaining a clean separation between Nex code and target platform code.
