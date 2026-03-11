# Nex Mode for Emacs

A comprehensive Emacs major mode for editing Nex programming language files.

## Features

- ✅ **Syntax Highlighting**
  - Keywords: `class`, `feature`, `require`, `ensure`, `invariant`, etc.
  - Types: `Integer`, `String`, `Boolean`, `Real`
  - Built-in functions: `print`, `println`
  - Contract labels (e.g., `positive:`)
  - Comments, strings, numbers

- ✅ **Automatic Indentation**
  - Smart indentation based on Nex syntax
  - Customizable indent offset
  - Electric indentation on newline

- ✅ **REPL Integration**
  - Start Nex REPL from Emacs
  - Evaluate buffer, region, or file
  - Interactive help

- ✅ **Navigation**
  - Jump to beginning/end of class or method
  - Imenu support for quick navigation
  - Comment support

## Installation

### Option 1: Manual Installation

1. Copy `nex-mode.el` to your Emacs load path:
   ```bash
   cp nex-mode.el ~/.emacs.d/lisp/
   ```

2. Add to your `~/.emacs` or `~/.emacs.d/init.el`:
   ```elisp
   (add-to-list 'load-path "~/.emacs.d/lisp/")
   (require 'nex-mode)
   ```

3. Restart Emacs or evaluate the configuration.

### Option 2: Direct Load

Add to your Emacs configuration:
```elisp
(load-file "/path/to/nex/nex-mode.el")
```

### Option 3: Use-Package (Recommended)

If you use `use-package`:
```elisp
(use-package nex-mode
  :load-path "/path/to/nex/"
  :mode "\\.nex\\'"
  :config
  (setq nex-indent-offset 2))
```

## Configuration

### Customization Variables

```elisp
;; Set indentation offset (default: 2)
(setq nex-indent-offset 2)

;; REPL program (default: "nex")
(setq nex-repl-program "nex")

;; REPL arguments (default: '())
(setq nex-repl-arguments '())
```

### Custom Key Bindings

```elisp
;; Add custom keybindings
(add-hook 'nex-mode-hook
          (lambda ()
            (local-set-key (kbd "C-c C-t") 'nex-run-tests)))
```

## Usage

### Basic Editing

Open any `.nex` file and the mode will activate automatically:
```bash
emacs example.nex
```

### Key Bindings

| Key Binding | Command | Description |
|------------|---------|-------------|
| `C-c C-z` | `nex-repl` | Start Nex REPL |
| `C-c C-c` | `nex-eval-buffer` | Evaluate current buffer |
| `C-c C-r` | `nex-eval-region` | Evaluate selected region |
| `C-c C-l` | `nex-load-file` | Load current file in REPL |
| `C-c C-d` | `nex-repl-show-help` | Show REPL help |
| `C-M-a` | `nex-beginning-of-defun` | Jump to beginning of class/method |
| `C-M-e` | `nex-end-of-defun` | Jump to end of class/method |
| `TAB` | `nex-indent-line` | Indent current line |

### REPL Workflow

1. **Start REPL**: Press `C-c C-z` to open Nex REPL in a split window

2. **Evaluate Code**:
   - `C-c C-c` - Send entire buffer to REPL
   - `C-c C-r` - Send selected region to REPL
   - `C-c C-l` - Load current file in REPL

3. **Interactive Development**:
   - Edit your `.nex` file
   - Press `C-c C-c` to evaluate changes
   - See results immediately in REPL buffer

### Example Session

```nex
-- example.nex
class Calculator
  feature
    add(a, b: Integer) do
      print(a + b)
    end

    factorial(n: Integer) do
      from
        let i := 1
        let result := 1
      until
        i > n
      do
        let result := result * i
        let i := i + 1
      end
      print(result)
    end
end
```

1. Open the file: `M-x find-file RET example.nex RET`
2. Start REPL: `C-c C-z`
3. Evaluate buffer: `C-c C-c`
4. The class is now available in the REPL!

### Navigation with Imenu

Press `M-x imenu` to see a menu of all classes and methods in the current file. You can quickly jump to any definition.

For better navigation, consider using `imenu-list`:
```elisp
(use-package imenu-list
  :ensure t
  :bind ("C-'" . imenu-list-smart-toggle))
```

## Syntax Highlighting Examples

The mode provides comprehensive syntax highlighting:

```nex
-- Comments are highlighted
class BankAccount          -- 'class' is a keyword, 'BankAccount' is a type
  feature                  -- 'feature' is a keyword
    balance: Integer       -- 'Integer' is a type

    deposit(amount: Integer)
      require              -- Contract keyword
        positive: amount > 0    -- 'positive:' is a label
      do                   -- 'do' is a keyword
        let balance := balance + amount  -- 'let', ':=' are keywords
      ensure               -- Contract keyword
        increased: balance > 0
      end                  -- 'end' is a keyword

  invariant                -- Contract keyword
    non_negative: balance >= 0
end
```

## Indentation

The mode automatically indents code based on Nex syntax:

- Opening keywords (`class`, `do`, `then`, `else`, `from`, `require`, `ensure`, etc.) increase indentation
- Closing keywords (`end`, `else`) decrease indentation
- Press `TAB` to indent the current line
- Electric indentation on newline

Example:
```nex
class Point
  feature
    x: Integer
    y: Integer

    distance() do
      if x > 0 then
        print(x)
      else
        print(-x)
      end
    end
end
```

## Comments

- Line comments start with `--`
- Press `M-;` to insert or align a comment
- Works with all standard Emacs comment commands

## Tips and Tricks

### 1. Use Company Mode for Auto-completion

```elisp
(use-package company
  :ensure t
  :hook (nex-mode . company-mode))
```

### 2. Enable Line Numbers

```elisp
(add-hook 'nex-mode-hook 'display-line-numbers-mode)
```

### 3. Pair Parentheses Automatically

```elisp
(add-hook 'nex-mode-hook 'electric-pair-mode)
```

### 4. Enable Flycheck (if you add a linter later)

```elisp
(use-package flycheck
  :ensure t
  :hook (nex-mode . flycheck-mode))
```

### 5. Prettify Symbols

```elisp
(add-hook 'nex-mode-hook
          (lambda ()
            (setq-local prettify-symbols-alist
                        '((":=" . "←")
                          (">=" . "≥")
                          ("<=" . "≤")
                          ("/=" . "≠")))
            (prettify-symbols-mode 1)))
```

## Troubleshooting

### REPL doesn't start

Make sure Nex is installed and `nex` is in your PATH:
```bash
which nex
```

If Nex is in a different location, set:
```elisp
(setq nex-repl-program "/full/path/to/nex")
```

### Keybindings don't work (C-c C-c invokes Clojure REPL instead)

This happens when another mode (like clojure-mode) is activated for .nex files instead of nex-mode. To fix:

1. **Check which mode is active**: In your .nex file, check the mode line at the bottom of Emacs. It should say "Nex", not "Clojure" or something else.

2. **Ensure nex-mode is loaded**: Add this to your `~/.emacs.d/init.el`:
   ```elisp
   (load-file "/path/to/nex/editor/emacs/nex-mode.el")
   ```

3. **Force .nex files to use nex-mode**: Add this after loading nex-mode:
   ```elisp
   (add-to-list 'auto-mode-alist '("\\.nex\\'" . nex-mode))
   ```

4. **If clojure-mode is overriding**: Remove .nex from clojure-mode's file associations:
   ```elisp
   (setq auto-mode-alist
         (remove '("\\.nex\\'" . clojure-mode) auto-mode-alist))
   ```

5. **Reload Emacs configuration**: Either restart Emacs or run `M-x eval-buffer` in your init.el

6. **Verify**: Open a .nex file and check the mode line shows "Nex", then try `C-c C-z` to start the REPL.

### Indentation is wrong

You can manually indent a region:
- Select the region
- Press `C-M-\` (indent-region)

Or set custom indent offset:
```elisp
(setq nex-indent-offset 4)  ; Use 4 spaces instead of 2
```

### Syntax highlighting not working

Make sure font-lock-mode is enabled:
```elisp
M-x font-lock-mode
```

Or force refresh:
```elisp
M-x font-lock-fontify-buffer

Current mode support includes the newer concurrency surface as keywords/types:
- keywords: `spawn`, `select`, `timeout`
- types: `Set`, `Task`, `Channel`
- built-ins: `await_any`, `await_all`, `sleep`, `type_of`, `type_is`
```

## Contributing

To improve nex-mode:

1. Edit `nex-mode.el`
2. Reload it: `M-x eval-buffer`
3. Test your changes
4. Submit improvements!

## License

Same as Nex (see main project LICENSE)

## Acknowledgments

Inspired by Eiffel-mode and other language modes for Emacs.
