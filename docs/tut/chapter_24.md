# Interoperability

No practical language lives entirely by itself. Sooner or later a program needs a file system, a library, a host API, or an external service. Nex is designed for that reality. It can import host-platform symbols and it can target the JVM or JavaScript.

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

These imports are primarily meaningful when compiling or translating Nex programs to the target platform. They tell the JVM compiler or JavaScript generator which host symbols the program expects to use.


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

The repository supports JVM bytecode compilation and JavaScript translation.

From Clojure:

```clojure
(require '[nex.compiler.jvm.file :as jvm])
(require '[nex.generator.javascript :as js])

(println (js/translate nex-code))
```

And for files:

```clojure
(jvm/compile-jar "input.nex" "build/")
(js/translate-file "input.nex" "output.js")
```

This matters for design because the same Nex source may be aimed at different host environments. A routine that depends only on arrays, maps, strings, and user-defined classes is much easier to move, test, and trust than one tangled with host APIs at every step.


## Development Builds and Production Builds

Contracts are included in normal translated output. For production translation, the JavaScript generator supports `skip-contracts`:

```clojure
(js/translate nex-code {:skip-contracts true})
```

Likewise for JavaScript file translation:

```clojure
(js/translate-file "input.nex" "output.js" {:skip-contracts true})
```

This is an important design point. Contracts remain in the source as specification and documentation, but the runtime checking overhead can be removed for production output when desired.

Use this option deliberately. Development builds should usually keep contracts enabled.


## `with "java"` Blocks

On the JVM, Nex also supports:

```text
with "java" do
  ...
end
```

This marks a block whose body may use Java interop directly. In practice, that means method calls and class names inside the block may resolve against imported Java classes and host objects rather than only against ordinary Nex classes.

For example:

```text
import java.lang.System

with "java" do
  print(System.getProperty("java.version"))
end
```

This form is JVM-specific. It is useful when a small part of the program genuinely needs host behavior, but the surrounding design should remain ordinary Nex.

At present, this is primarily a compiled-JVM feature. It works well in JVM REPL sessions and on the compiled JVM path, but it is not supported by the interpreter-based file runner used by:

```text
nex some_file.nex
```

So if a file relies on `with "java"`, do not assume it will run correctly through the interpreter path. Use the compiled JVM route instead.

In REPL-oriented wrapper classes, `with "java"` is often the most practical way to isolate the host boundary. A common pattern is:

- keep Java calls inside `with "java"` blocks
- expose ordinary Nex routines outside those blocks
- if needed, store a Java-backed object in a field typed as `Any`, and only manipulate it inside `with "java"`


## A Small Interop-Oriented Design

In JVM REPL sessions, a practical style is to import a Java class and wrap it in a small Nex class immediately. For example, suppose we want efficient string assembly. We could expose `StringBuilder` everywhere, but a better design keeps that host type inside one Nex wrapper:

```text
import java.lang.StringBuilder

class Line_Buffer
create
  make() do
    with "java" do
      this.builder := create StringBuilder
    end
  end
feature
  builder: Any

  append_line(s: String) do
    with "java" do
      builder.append(s)
      builder.append("\n")
    end
  end

  text(): String do
    with "java" do
      result := builder.toString()
    end
  end
end

class Greeting_Report
  feature
    render(name: String): String do
      let buf := create Line_Buffer.make
      buf.append_line("Hello, " + name)
      buf.append_line("Welcome to Nex on the JVM.")
      result := buf.text()
    end
end
```

In a REPL session, this is convenient:

```text
nex> import java.lang.StringBuilder
nex> class Line_Buffer
       create
         make() do
           with "java" do
             this.builder := create StringBuilder
           end
         end
       feature
         builder: Any
         append_line(s: String) do
           with "java" do
             builder.append(s)
             builder.append("\n")
           end
         end
         text(): String do
           with "java" do
             result := builder.toString()
           end
         end
     end
nex> let b := create Line_Buffer.make
nex> b.append_line("alpha")
nex> b.append_line("beta")
nex> print(b.text())
alpha
beta
```

The design point is the important part: `StringBuilder` is confined to `Line_Buffer`. The rest of the program depends on ordinary Nex routines such as `append_line` and `text`, not on Java library details.


## Portability and Contracts

Contracts are especially valuable around interop because host libraries often sit outside the type and contract discipline of the Nex core.

If a wrapper routine imports or calls external functionality, its contract should state:

- what arguments are valid
- what it guarantees on success
- what exceptions may still arise from the environment

This makes the platform boundary explicit and safer.


## A Worked Example: Separating Core Logic from Host Access

Here is a complete JVM-oriented example. Suppose we want to print a short report about the current Java runtime. Reading system properties is host access. Formatting the report is ordinary program logic. We should separate those two concerns:

```text
import java.lang.System

function line(label, value: String): String
do
  result := label + ": " + value
end

function render_runtime_report(version, vendor, home: String): String
do
  result := line("Java version", version) + "\n"
  result := result + line("Java vendor", vendor) + "\n"
  result := result + line("Java home", home)
end

class Java_Runtime_Info
  feature
    property(name: String): String
      require
        valid_name: name /= ""
      do
        let value: ?String := nil
        with "java" do
          value := System.getProperty(name)
        end

        if value = nil then
          result := "<missing>"
        else
          result := value
        end
      end
end

class Runtime_Report_App
  feature
    run(): String do
      let info := create Java_Runtime_Info
      let version := info.property("java.version")
      let vendor := info.property("java.vendor")
      let home := info.property("java.home")
      result := render_runtime_report(version, vendor, home)
    end
end

let app := create Runtime_Report_App
print(app.run())
```

The design is deliberate:

- `Java_Runtime_Info.property` is the host boundary
- `render_runtime_report` and `line` are pure Nex logic
- `Runtime_Report_App` assembles the two

This makes the program easier to test. The formatting routines can be exercised with ordinary strings, while only `Java_Runtime_Info` depends on JVM interop.


## Summary

- `import` brings in host-platform symbols; `intern` brings in Nex classes
- Keep interop code near system boundaries rather than scattering it through core logic
- Nex can target JVM bytecode and JavaScript
- Production translation can omit runtime contract checks with `skip-contracts`
- Contracts are especially valuable around interop boundaries
- Portable core logic is easier to test, reason about, and reuse


## Exercises

**1.** Write a short example containing both an `intern` statement and an `import` statement. Explain the different role each plays.

**2.** Choose a small program idea and identify which parts should remain pure Nex logic and which parts belong to the host boundary.

**3.** Write a wrapper-class design for a clock or random-number service. State the contract of the main routine and explain what remains host-specific.

**4.** Explain when using `{:skip-contracts true}` is reasonable and when it is risky.

**5.\*** Take one earlier example, such as a report printer or configuration loader, and redesign it so that host-specific work is isolated in one class while the main computation remains platform-independent.
