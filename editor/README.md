# Editor Support for Nex

This directory contains editor integrations for the Nex programming language.

## Available Editors

### Emacs

A comprehensive Emacs major mode is available in [`emacs/nex-mode.el`](emacs/nex-mode.el).

**Features:**
- ✓ Syntax highlighting for all Nex keywords and constructs
- ✓ Automatic indentation
- ✓ REPL integration (evaluate buffer, region, or file)
- ✓ Navigation support (jump to classes/methods)
- ✓ Imenu support for quick navigation
- ✓ Comment support

**Installation:**

```elisp
;; Add to ~/.emacs.d/init.el
(load-file "/path/to/nex/editor/emacs/nex-mode.el")

;; Optional: Auto-load for .nex files (if not using auto-mode-alist)
(add-to-list 'auto-mode-alist '("\\.nex\\'" . nex-mode))
```

**Usage:**
- Open any `.nex` file to activate the mode automatically
- `C-c C-z` - Start Nex REPL
- `C-c C-c` - Evaluate buffer in REPL
- `C-c C-r` - Evaluate region in REPL
- `C-c C-l` - Load file in REPL
- `C-c C-d` - Show REPL help

**Documentation:**
See [docs/EMACS.md](../docs/EMACS.md) for complete documentation.

## Planned Editor Support

We're planning to add support for the following editors:

- **VS Code** - Language server and extension
- **Vim/Neovim** - Syntax highlighting and LSP support
- **IntelliJ IDEA** - Plugin for JetBrains IDEs
- **Sublime Text** - Syntax package

## Contributing

Contributions for new editor integrations are welcome! If you'd like to add support for your favorite editor:

1. Create a subdirectory: `editor/your-editor/`
2. Implement the integration
3. Add documentation
4. Update this README
5. Submit a pull request

## Language Server Protocol (LSP)

We're planning to implement a Language Server Protocol (LSP) server for Nex, which will provide:
- Syntax checking
- Auto-completion
- Go to definition
- Find references
- Hover information
- Code formatting
- Contract validation

The LSP server will enable consistent IDE support across all editors that support LSP.

## Directory Structure

```
editor/
├── README.md           # This file
├── emacs/             # Emacs integration
│   └── nex-mode.el    # Emacs major mode
├── vscode/            # (Planned) VS Code extension
├── vim/               # (Planned) Vim/Neovim plugin
└── lsp/               # (Planned) Language Server Protocol implementation
```

## See Also

- [Main README](../README.md) - Project overview
- [Emacs Documentation](../docs/EMACS.md) - Complete Emacs mode guide
- [Examples](../examples/) - Example Nex programs to try in your editor
