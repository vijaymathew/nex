# Nex for Visual Studio Code

Support for [Nex](https://schemer.in/nex), the language where contracts,
invariants and explicit guarantees are the ordinary way code is written.

## Features

- **Syntax highlighting** for keywords, types, contracts and their labels,
  strings, character and number literals (including `12u8`, `300i16`,
  `0xFF`), and comments.
- **Errors as you type.** The same syntax and type errors `nex app.nex`
  reports, underlined while you edit and listed in the Problems panel. An
  error inside a file you `intern` is shown on the `intern` line, with a link
  to where it is.
- **Formatting** with `nex format`: *Format Document* (Shift+Alt+F), or turn
  on `editor.formatOnSave` for Nex files.
- **Hover** over a name to see its type, or the signature, note and
  `require`/`ensure` contracts of the feature or function it calls; over a
  class, its note and invariant.
- **Go to Definition** (F12, or Ctrl+click) for features, constructors,
  classes, functions, locals, parameters and globals, into interned files
  and the standard library too.
- **Completion**: after `x.` the features of x's type (with their
  signatures and contracts), after `create C.` C's constructors, the classes
  and built-in types where a type is expected, and elsewhere the locals,
  parameters, features, globals, functions and keywords in scope.
- **Outline** of classes, constructors, features, functions, unions and
  top-level `let`s, in the Outline view, breadcrumbs and *Go to Symbol*
  (Ctrl+Shift+O).
- **Snippets** for classes, routines with `require`/`ensure`, loops with an
  invariant and variant, `across`, `match`, `union` and more.
- **Run** the current file with the ▷ button in the editor title bar.
- **REPL** in the integrated terminal:

  | Command | Key | What it does |
  |---|---|---|
  | Nex: Start REPL | | Opens a `nex` REPL |
  | Nex: Send Selection to REPL | Shift+Enter | Sends the selection, or the current line and moves down |
  | Nex: Load File into REPL | | Saves the file and `:load`s it |

## Requirements

A Nex that has the `nex lsp` command (any release after 0.6.4), with `nex`
on your `PATH` (see the
[install guide](https://github.com/vijaymathew/nex/blob/main/INSTALL.md)).
If it is somewhere else, set `nex.path` to its full path.

## Settings

| Setting | Default | |
|---|---|---|
| `nex.path` | `nex` | The `nex` command |
| `nex.languageServer.enable` | `true` | Run `nex lsp` for errors, hover, go to definition, completion, formatting and the outline |

Interned files are read from disk, so after changing one, save it to update
the errors shown in the files that intern it.

## Building the extension

```sh
cd editor/vscode
npm install
npm run package        # writes nex-<version>.vsix
code --install-extension nex-*.vsix
```

To try changes without packaging, open `editor/vscode` in VS Code and press
F5.
