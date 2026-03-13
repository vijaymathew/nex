# Interoperability

No practical language lives entirely by itself. Sooner or later a program needs a file system, a library, a graphical surface, or an external service. Nex is designed for that reality. It can import host-platform symbols and it can be translated to Java or JavaScript.

The important question is not whether interop exists, but where it should live in the design. This chapter is about that boundary.


## Importing Platform Symbols

Nex supports `import` statements at the top level.

For Java:

```
import java.util.Scanner
```

For JavaScript modules:

```
import Math from './math.js'
```

These imports are primarily meaningful when translating Nex programs to the target platform. They tell the generated Java or JavaScript code which host symbols it should import.


## `import` Versus `intern`

`intern` loads Nex classes from Nex files.

File resolution follows the current Nex loader:

1. the loaded file's directory
2. the current working directory
3. `~/.nex/deps`

For path-qualified classes, Nex checks `lib/<path>/...` layouts and also accepts lowercase filenames such as `tcp_socket.nex`.

`import` names external Java or JavaScript symbols.

This distinction should remain sharp:

- use `intern` for Nex-to-Nex modularity
- use `import` for host-platform interop

Confusing the two leads to confused architecture. A Nex class is part of your program's design. An imported host symbol is part of the surrounding environment.


## Keeping the Boundary Small

The best interop style is usually not to scatter host calls everywhere. Instead:

1. isolate host-specific code near the boundary
2. keep the core logic in ordinary Nex classes
3. wrap external behavior behind Nex routines with clear contracts

For example, rather than calling platform I/O throughout a program, define a small Nex service class whose job is "read configuration" or "write report." The rest of the program then depends on that service's contract, not on host details.


## Translation Targets

The repository supports translation from Nex to Java and JavaScript.

From Clojure:

```clojure
(require '[nex.generator.java :as java])
(require '[nex.generator.javascript :as js])

(println (java/translate nex-code))
(println (js/translate nex-code))
```

And for files:

```clojure
(java/translate-file "input.nex" "Output.java")
(js/translate-file "input.nex" "output.js")
```

This matters for design because the same Nex source may be aimed at different host environments. A routine that depends only on arrays, maps, strings, and user-defined classes is much easier to move, test, and trust than one tangled with host APIs at every step.


## Development Builds and Production Builds

Contracts are included in normal translated output. For production translation, the generators support `skip-contracts`:

```clojure
(java/translate nex-code {:skip-contracts true})
(js/translate nex-code {:skip-contracts true})
```

Likewise for files:

```clojure
(java/translate-file "input.nex" "Output.java" {:skip-contracts true})
(js/translate-file "input.nex" "output.js" {:skip-contracts true})
```

This is an important design point. Contracts remain in the source as specification and documentation, but the runtime checking overhead can be removed for production output when desired.

Use this option deliberately. Development builds should usually keep contracts enabled.


## A Small Interop-Oriented Design

Suppose a program needs random numbers from the host platform. A poor design would let random generation spread everywhere in the core logic. A better design wraps it:

```
import Math from './math.js'

class Die
  feature
    roll(): Integer do
      -- host-backed random value would be wrapped here
      result := 1
    end
end
```

The example is schematic, but the design point is real: the interop boundary is inside `Die`, not scattered through the rest of the game or simulation.

Then other classes depend on `roll(): Integer`, not on the host library directly.


## Portability and Contracts

Contracts are especially valuable around interop because host libraries often sit outside the type and contract discipline of the Nex core.

If a wrapper routine imports or calls external functionality, its contract should state:

- what arguments are valid
- what it guarantees on success
- what exceptions may still arise from the environment

This makes the platform boundary explicit and safer.


## A Worked Example: Separating Core Logic from Host Access

Imagine a word-counting application that eventually reads text from a file. Its core logic should not know about files at all:

```
nex> function word_frequencies(text: String): Map[String, Integer]
     do
       result := {}
       let words := text.to_lower.split(" ")
       across words as w do
         let count := result.try_get(w, 0)
         result.put(w, count + 1)
       end
     end
```

That routine is pure Nex logic and can run anywhere.

A separate wrapper could handle the host interaction of obtaining the text. The wrapper may use `import` or another platform mechanism, but the core counting routine remains portable and easy to test.

This is usually the right architectural split:

- platform code at the edge
- language-idiomatic logic in the center


## Summary

- `import` brings in host-platform symbols; `intern` brings in Nex classes
- Keep interop code near system boundaries rather than scattering it through core logic
- Nex can be translated to Java and JavaScript
- Production translation can omit runtime contract checks with `skip-contracts`
- Contracts are especially valuable around interop boundaries
- Portable core logic is easier to test, reason about, and reuse


## Exercises

**1.** Write a short example containing both an `intern` statement and an `import` statement. Explain the different role each plays.

**2.** Choose a small program idea and identify which parts should remain pure Nex logic and which parts belong to the host boundary.

**3.** Write a wrapper-class design for a clock or random-number service. State the contract of the main routine and explain what remains host-specific.

**4.** Explain when using `{:skip-contracts true}` is reasonable and when it is risky.

**5.\*** Take one earlier example, such as a report printer or configuration loader, and redesign it so that host-specific work is isolated in one class while the main computation remains platform-independent.
