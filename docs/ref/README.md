# Nex Reference

This reference documents the interpreter-level built-ins currently defined in `src/nex/interpreter.cljc`.

## Contents

- [Built-in Functions](functions.md)
- [Foundational Classes](foundational-classes.md)
- [Scalar Types](scalar-types.md)
- [Collection Types](collection-types.md)
- [Cursor Types](cursor-types.md)
- [System Classes](system-classes.md)
- [Data Libraries](data.md)
- [IO Libraries](io.md)
- [Time Libraries](time.md)
- [Library Index](libraries.md)
- [Networking Libraries](networking.md)
- [Graphics Classes](graphics-classes.md)

## Conventions

- Method names are listed exactly as implemented.
- Signatures are shown in Nex-style pseudocode.
- `nil` means no useful return value.
- Some behavior differs by runtime (`:clj` vs `:cljs`), especially for `Process` and platform-specific libraries under `lib/`.
