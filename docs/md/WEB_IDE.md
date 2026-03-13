# Nex Web IDE

## Status

The browser-based Nex Web IDE has been retired as a supported workflow.

The main reason is maintenance cost. Keeping a full in-browser parser, interpreter,
editor UI, graphics runtime, and browser-specific concurrency/runtime behavior in sync
with the JVM interpreter and both code generators was no longer a good tradeoff.

## Supported Alternatives

Use one of these instead:

1. CLI REPL
2. JVM interpreter
3. generated Java
4. generated JavaScript

Start with:

- [README.md](../../README.md)
- [CLI Guide](CLI.md)
- [A Short Tutorial to Nex](TUTORIAL.md)
- [Syntax on a Postcard](SYNTAX.md)

## Transitional Notes

- The browser build files still remain in the repository temporarily.
- The page under `public/index.html` now serves a retirement notice instead of the IDE.
- The sync script still exists for updating the website checkout during this transition:

```bash
./scripts/sync-browser-ide.sh
```

## Future Direction

If Nex gets a browser-facing experience again, it should be a much simpler surface:

- documentation-first
- example-driven
- ideally based on generated JavaScript rather than a full in-browser interpreter/editor stack
