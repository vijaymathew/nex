# Nex Reference

This reference documents the interpreter-level built-ins currently defined in `src/nex/interpreter.clj`.

> **The published reference is not generated from these files.**
> The site at `vijaymathew.github.io/nex/docs/ref` is maintained by editing its
> HTML **directly**. It was once rendered from this Markdown with Quarto, but that
> link was cut long ago and the two have since diverged. The Quarto config
> (`_quarto.yml`) and `Makefile` have been deleted so that nobody re-renders the
> site from here and silently reverts hand-edits made to the HTML.
>
> When you change the reference, edit the published HTML. Update the Markdown here
> too if you want the two to agree — but nothing checks that they do.

## Contents

- [Built-in Functions](functions.md)
- [Foundational Classes](foundational-classes.md)
- [Scalar Types](scalar-types.md)
- [Collection Types](collection-types.md)
- [Cursor Types](cursor-types.md)
- [System Classes](system-classes.md)
- [Data Libraries](data.md)
- [IO Libraries](io.md)
- [Text Libraries](text.md)
- [Time Libraries](time.md)
- [Library Index](libraries.md)
- [Networking Libraries](networking.md)

## Conventions

- Method names are listed exactly as implemented.
- Signatures are shown in Nex-style pseudocode.
- `nil` means no useful return value.
