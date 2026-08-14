# Changelog

## Unreleased

## 0.3.5 - 2026-08-14

- **New: a constructor may delegate to another constructor of the same class
  with `this.ctor(...)`**, so shared initialization lives in one place
  instead of being duplicated across every constructor:
  `default do this.make(0) end`. Reaches only a constructor declared
  directly on the same class (never an inherited one — use `super.ctor(...)`
  or `ParentClass.ctor(...)` for that, as before). Implemented on both
  backends: the compiled backend invokes the target constructor directly on
  `this` (no `_parent_X` composition field to step through, and no generic
  type-argument translation, since the callee is the exact same — possibly
  generic — class as the caller); the interpreter reuses its existing
  parent-constructor dispatch machinery with the current class standing in
  for "parent". The existing void-safety check (every constructor must
  initialize all attachable fields) now recognizes that a constructor whose
  only initialization is a `this.ctor(...)` delegation still definitely
  initializes those fields, resolved transitively through chained
  delegations. See `docs/md/SYNTAX.md`.

- **New: an anonymous function's parameter and return types can be inferred
  from a target `Function(...)` type**, instead of always being spelled out
  on `fn` itself: `let is_big: Function(Box): Boolean := fn(b) do result :=
  b.v > 10 end`. Types are filled in positionally from the target wherever
  one is available at the point the literal appears — currently a typed
  `let`'s own declared type. Individual parameters may still be annotated
  explicitly and mixed with inferred ones. With no target type available,
  omitted parameter types are a clear compile-time error rather than a
  silent fallback (see the related fix below) — the same applies to a
  return type an inferred body needs (e.g. one that assigns `result`).
  Function-type parameter names have always been optional
  (`Function(Box): Boolean` needs no `b:`); this pairs with that. See
  `docs/md/SYNTAX.md`.

- **Fixed: an omitted parameter type on a method, constructor, or free
  function silently defaulted to `Any` instead of being rejected.** This was
  dead, untested legacy behavior — every real declaration in the language
  always specifies its parameter types — that also stood in the way of the
  anonymous-function inference above (an unannotated `fn` parameter needs to
  mean "infer this", not "this is Any"). An omitted type is now a
  compile-time error everywhere except an anonymous function with an
  inferrable target type.

- **Fixed: passing a free function by name where a `Function(...)`-typed
  value was expected** (e.g. `filter_items(is_rare_or_legendary)`, passing
  a `function`-declared routine rather than an `fn(...)` literal) failed
  typechecking with a confusing "Expected Function(...), got
  X_Function" — the typechecker never recognized a free function's
  generated wrapper class as compatible with a structural `Function(...)`
  target. Fixing that surfaced a second, deeper bug on the compiled
  backend: even once accepted by the typechecker, lowering had no code path
  to produce an actual value for a bare free-function-name reference, and
  silently lowered it to a null read — passing typechecking, then crashing
  at the first call with "Cannot invoke Void as a function". Fixed by
  teaching the compiled backend to resolve such a reference through the
  runtime's existing function-by-name registry (already used to let
  deoptimized closures call back into compiled top-level functions),
  instead of leaving the reference unresolved.

- **Fixed: a builtin scalar method (e.g. `.round`) called directly on a
  parenthesized expression that itself calls another method implicitly on
  `this`** (e.g. `(stats.value * rarity_multiplier).round` inside a method
  that reads `rarity_multiplier` as a bare identifier) failed lowering with
  an opaque "Unable to infer expression type during lowering", even though
  the equivalent `let r := (...); r.round` worked. Root cause: resolving a
  builtin scalar method's return type falls back to a self-contained
  typecheck of just that sub-expression, which wasn't seeded with the
  enclosing class context a bare implicit-`this` call needs to resolve.

- **Fixed: an undefined type named only in an anonymous function's parameter
  or return-type annotation** (`fn(item: Typo): Boolean do ... end`) wasn't
  caught by the dedicated undefined-type check that covers every other
  declaration position (fields, method/constructor parameters, return
  types, typed `let`s). It silently resolved as `Any` instead, and the
  first field access it enabled failed later with a confusing "Undefined
  field ... on Any" pointing at the wrong place instead of naming the real
  typo.

- **Fixed (Emacs mode): `ensure` was indented one level too deep when the
  method body's last statement before it was shaped like a bare,
  receiverless call** (e.g. `print()`), which the indentation logic
  couldn't distinguish from a parameterless method signature — both match
  the same regexp. The backward scan now also checks that a candidate
  signature line is genuinely followed by `do`/`require`, which a body
  statement never is.

## 0.3.4 - 2026-08-14

- **New: object-test syntax `?<expr> as <name>` for narrowing detachable
  (`?T`) values.** `if ?age as a then ... end` binds `a` to `age`'s attached
  value when `age` isn't `nil`. Unlike `x /= nil`, `<expr>` isn't limited to
  a bare identifier — `?p.age as a` and `?p?.age as a` narrow a dotted or
  safe-navigated chain directly — and `and`-chains of these guards narrow
  each conjunct in turn (`?p.age as a and ?q.age as b`). Supported on both
  the tree-walking interpreter and the JVM backend (the REPL falls back to
  the interpreter for it, with a warning). See `docs/md/SYNTAX.md`. Also
  fixes a related pre-existing bug: arithmetic on a value narrowed by the
  older `x /= nil` check (e.g. `age + 1` inside `if age /= nil then`) could
  fail typechecking even though comparisons on the same narrowed value
  worked — both now behave consistently.

- **New: `Process` can spawn and control child OS processes.**
  `create Process.command("ls -lat")` (shell-word split) or
  `create Process.command("ls", ["-lat"])` (explicit argv) build a child
  process that isn't started until `start()` is called, so
  `set_working_directory`/`set_redirect_error_to_output`/`setenv` can
  configure it first. Once started, a child exposes `is_alive`/`pid`,
  `wait`/`wait(timeout_ms)`/`exit_code`, `terminate`/`kill`, and
  line-oriented reads/writes on its stdin/stdout/stderr
  (`read_line`/`read_all`/`write`/`write_line`/`close_stdin`, plus
  `read_error_line`/`read_error_all`). `create Process`/`create
  Process.self` keep referring to the running Nex program itself, as
  before — `getenv`/`setenv`/`command_line` now also work meaningfully on
  a child (its own configured environment and launch argv). Every
  child-only method raises if called on the self process or before/after
  the wrong point in the child's lifecycle. See `docs/ref/system-classes.md`.

- **Fixed: `print`, `.to_string()`, and string concatenation (`"..." +
  collection`) ignored a class's `to_string` override for objects nested
  inside an `Array`/`Map`/`Set`.** A top-level object already honored the
  override; the same object nested inside a collection fell back to the
  generic `#<ClassName object>` placeholder on the interpreter, or leaked
  raw JVM identity (`#object[nex.file.x.Pt 0x14b620e5 "...@d79011a6"]`) on
  the compiled backend. All three entry points, on both backends, now
  recurse into nested collections and call the element's own `to_string`.

- **Fixed: a capture two or more closures deep never reached the
  innermost one on the compiled backend.** Only names a closure referenced
  directly were treated as captures; a name only needed by a closure
  nested inside *it* wasn't propagated outward, so the enclosing closure
  compiled as if it captured nothing and constructing the nested closure
  crashed with an unresolved name. Capture lists now propagate
  transitively through every enclosing scope. Fixes a related crash: a
  rebuilt deopt context merged its class table into a plain Java
  `HashMap`, which isn't a Clojure `Associative` — the first nested
  closure registered against it after a deopt threw a
  `ClassCastException`; it's now merged into the existing (Clojure) map
  instead.

- **Fixed: a `deferred` class's own routine calling a sibling deferred
  feature without `this.` (e.g. `helper()` instead of `this.helper()`)
  crashed at runtime on the compiled backend**, because such a call still
  tried to link directly against a method body that doesn't exist on that
  class. It now dispatches the same way an explicit `this.` call to an
  overridden method already did — through the object's outer/reflection
  bridge — so both call spellings behave identically.

## 0.3.3 - 2026-08-07

- **New: a Nex class can `inherit` a Java interface or a concrete Java
  class, not just another Nex class.** Inheriting an imported interface
  (`Runnable`, `Comparator`, `ActionListener`, ...) makes the class a real
  instance of it — a Java API that calls back through the interface (a
  `Thread`, `Collections.sort`, a Swing listener) reaches the Nex method
  directly. Inheriting a concrete Java class (`Thread`, `Exception`, ...)
  is compiled-backend only: the first statement of each constructor must be
  `super.new(args)` (or may be omitted if the Java class has a public
  no-arg constructor), matching one of the Java class's public constructors
  by arity, and every abstract method the Java type declares must be
  provided, checked by name and arity — the same precision Phase 1 already
  used for interfaces. Extending two Java classes at once, or a Java class
  together with a Nex one, is rejected at typecheck time (the JVM allows
  only one concrete superclass). Static members of an imported Java class
  (`Math.PI`, `Collections.sort(...)`) also now resolve correctly on the
  compiled backend, including when reached off a class that is only an
  interop placeholder.

  Along the way: a field typed with an imported Java type failed to compile
  on the JVM backend, and the interpreter's `java.lang.reflect.Proxy`
  bridge for interface dispatch lost mutable state across calls — both
  fixed.

- **New: `nex`'s `--classpath` and `--skip-contracts` flags.**
  `--classpath a.jar:b.jar` (colon-separated, matching `java -cp`) puts
  extra jars/dirs on the classpath for `nex app.nex` and
  `nex compile jvm app.nex` alike — compiling also shades them into the
  output jar. `--skip-contracts` lowers `require`/`ensure`/`invariant`
  checks (class and loop invariants) to no-ops for a trusted,
  perf-critical release build; bare `assert` always runs regardless, since
  there is no mode that strips it.

- **New: the `exit(status)` builtin.** Terminates the process immediately
  with the given exit status, equivalent to Java's `System.exit(status)`.
  Does not return. Available on both backends.

- **Fixed: a value stored into a compiled-native `Array`/`Map`/`Set` while
  statically typed `Any` could keep its interpreter-only representation
  instead of being converted to the compiled backend's own collection
  type.** An element read out of an `Array[Any]`, or a `Map` produced by
  `data/Json`'s `parse`, could be written into a literal, or passed to
  `add`/`add_at`/`set`/`put`, without running through the conversion every
  other storage path already applied — so a later method call on it
  (`.get()`, etc.) crashed on the compiled backend even though the same
  value worked fine as a plain local. Conversion now runs unconditionally
  at every point a boxed value is written into a compiled collection.

- **Fixed: `Server_Socket.accept()` was unusable on the compiled backend
  for any accepted connection**, traced to a missing runtime binding in
  `Tcp_Socket.from_socket`. It now works end-to-end.

- **Fixed: a `spawn` block or anonymous-function body inside an instance
  method couldn't call `this.method(...)`, a bare inherited method or
  field, or mutate object state through a captured `this` or other
  captured object, on the compiled backend.** All of these now work,
  removing the need to hoist such code out to free functions with
  manually captured locals.

- **Fixed: `Process.command_line()` didn't return the program's real
  command-line arguments on either backend.** A program's own `argv` —
  everything after the file name on the `nex` command line, distinct from
  `nex`'s own flags like `--interpret` — is now threaded through to both
  the interpreter and the compiled backend (including a jar run directly
  with `java -jar`), on both the in-process eval path and a standalone
  jar's own `main`.

- **Fixed: a `spawn` block's synthesized wrapper closure could collide,
  by class name, with an unrelated source-level `fn(...) do ... end`
  closure.** Both were generated as `AnonymousFunction_N` from two
  independent counters; whenever the counters reached the same `N`, the
  compiler's name-keyed table of anonymous class defs silently kept only
  one of the two, and calling the other crashed at runtime with an opaque
  "Method not found" error. `spawn`-generated wrappers now use a distinct
  `AnonymousSpawn_N` prefix.

- **Fixed: `super.method(...)`, `super.field`, and a `super` constructor
  call could type-check against the wrong target.** The generic call-
  resolution path walked the whole ancestor chain looking for any
  matching-arity member — constructors included — so an invalid `super`
  call (wrong arity, or naming a constructor only some ancestor happens to
  have) could pass type-checking and then crash lowering with an opaque
  internal error instead of a real type error. `super` now resolves
  through a dedicated path that mirrors lowering's actual semantics: the
  immediate parent's own feature methods and constructor only, never an
  ancestor further up, and never a fallback to the `Any` protocol.

- **Fixed: a `when ... then ... else ... end` expression's type inference
  couldn't see a bare field reference (`total_seconds` meaning
  `this.total_seconds`) in its condition or either branch.** It fell
  through to a generic fallback with no current-class context, so
  inference silently failed wherever a `when` expression relied on it.
  `when` now has its own inference path, mirroring how `if` already
  resolves its own branch environments.

- **Fixed: `==` and `!=` weren't recognized as relational operators by the
  compiled REPL's fast-path evaluator**, alongside the `=`/`/=` spellings
  it already handled.

## 0.3.2 - 2026-07-31

- **New: the `assert` statement.** `require`, `ensure`, and `invariant` state
  what holds at a routine's boundaries or around a loop; `assert` states what
  holds at one point *inside* a body, which no other clause could express.
  It takes either a named assertion in the same form as the other clauses —
  `assert non_empty: items.length > 0` — or a bare expression, `assert i < n`,
  whose failure reports the source line instead of a name. Several named
  assertions may sit under one `assert`, as with `require`. A failure raises the
  same contract violation the other clauses raise; assertions always run, and
  there is no mode that strips them. Both backends support it.

  `assert` is now a reserved word and can no longer be used as an identifier.

- **New: `super` is a proper keyword with its own, consistent semantics.**
  `super.method(...)`, `super.make(...)`, and `super.field := value` now
  resolve to the current class's one direct parent — non-virtual dispatch to
  the parent's own implementation, not whatever overrides it — the same way on
  both the interpreter and the compiler. Previously `super` was just an
  ordinary identifier that happened to spell "super"; consequently
  `super.field := value` was rejected unconditionally by the compiler, and the
  two backends could disagree on `super.method(...)` in ways nothing surfaced.
  Using `super` outside a method/constructor body, on a class with no parent,
  or on a class with more than one direct parent (ambiguous — call the
  intended parent by name instead, e.g. `Flyable.fly()`) is now a clear error
  on both backends.

  `super` is now a reserved word, alongside `this`.

- **Fixed: a generic method call on an unconstrained generic-typed receiver
  failed to compile.** `first.to_string()` on a `first: F` parameter of
  `Pair[F, S]` threw "Unsupported target call expression for lowering" — the
  compiler's `to_string`/`equals` fallback only recognised a receiver with a
  known class, not a bare generic parameter, even though the typechecker
  already allows the same call through the `Any` protocol. It now compiles.

- **Fixed: `retry`/`rescue` inside a free function saw `nil` instead of the
  real exception.** A free function invoked through the compiled REPL's
  reflection path wraps whatever it throws in `InvocationTargetException`;
  a `rescue` block reading the caught value directly saw that wrapper instead
  of the underlying contract violation or `raise`d value. The wrapper is now
  unwrapped down to the real cause before `rescue`/`retry` inspect it, as it
  already was for exceptions crossing other reflection boundaries.

- **Changed: `where` is now a soft keyword, matched the same way as `alias`.**
  Previously `where` was a reserved lexer token, whitelisted back in only for
  member access (`.where`) — so `let where := 5` and a field or method named
  `where` failed to parse even though the docs claimed `where` "stays available
  as an ordinary name." `where` is now recognised as a plain identifier
  everywhere, with the spelling checked only where it means something —
  immediately after `declare type id = ty` — the same pattern `alias` already
  used. A misspelling there (`whree`) is now reported by name instead of a bare
  parse error. `union` is unchanged: it remains usable only as a member name
  (`Set.union`), since no declaration rule accepts it in place of an
  identifier — unlike `where`, `union` was never free to redeclare, so nothing
  regresses.

- **Fixed: the REPL's compiled-backend fallback now recovers from a linking
  failure and reports it clearly.** A cell whose generated bytecode only fails
  to link the first time it actually runs (a `LinkageError`, surfaced through
  `InvocationTargetException`/`ExceptionInInitializerError` wrappers) used to
  either crash the REPL or silently fall back with an unhelpful decline
  reason. It now always re-runs on the tree-walking interpreter, and the
  fallback warning names the real cause.

- **Fixed: a `with "java"` block routed real Java method calls on an `Any`
  receiver into Nex's fixed Any-protocol dispatch table.** Because every
  interop value has the static type `Any`, a call like `builder.append(s)` on
  an `Any` holding a live `java.lang.StringBuilder` was mistaken for one of
  the handful of Any-protocol methods (`to_string`, `equals`, `length`, …) and
  crashed with an unbound-Var error at runtime instead of reaching the actual
  Java method. Inside a `with "java"` block, a method name outside that fixed
  set on an `Any` receiver is now always treated as host interop.

## 0.3.1 - 2026-07-24

- **New: top-level globals are readable from functions and classes.** A value
  bound by a top-level `let` can now be read inside any free function or class
  routine, not only from the top-level statements that follow it. Globals are
  read-only from this static world — assigning to one from a function or class
  body is a compile-time error — and a def-before-use rule rejects a program that
  could read a global before its `let` has run: every global a body reads must be
  initialized before the first top-level call into user code. Both backends
  support it; see the manual, §7.4.

- **Fixed: a chained method call on a global receiver failed to compile.** A body
  that read a global and then called through it more than once — for example
  `con.read_line.to_integer` — aborted with an "Unable to infer expression type
  during lowering" internal error. The compiler's fallback type inference now
  sees globals, so the whole chain resolves.

- **Fixed: a refinement type declared on an earlier REPL line is now enforced.**
  A `declare type Quantity = Integer where n: n > 0` on one line followed by
  `let q: Quantity := -1` on a later line silently accepted the invalid value:
  the narrowing check is injected at parse time, which saw only the current
  line's declarations. The REPL now re-applies refinement checks for types
  declared on earlier lines. In a script, where the whole program is one parse
  unit, this already worked.

- **Fixed: `a - 100` now parses as a subtraction.** Because `-` is the only
  binary operator with a prefix (unary) form, `a - 100` used to split into two
  statements — a parameterless call `a`, then a separate `-100` — while `a + 10`
  and the rest parsed as one expression. A bare identifier no longer stands as a
  statement-level call when a `-` follows: it is one subtraction, at the REPL and
  in scripts. To call a parameterless routine and then negate, write `a()` or
  `(a)`.

## 0.3.0 - 2026-07-23

- **New: class constants** — a feature written `NAME = expression`, with an
  initializer and no constructor, is a class-level constant shared by every
  instance and read through the class name (`Screen.WIDTH`). The initializer is
  any expression, not only a scalar, and it is **interned**: evaluated once for
  the whole run, so `Origin.POINT == Origin.POINT` holds by identity. A constant
  may reference an earlier constant of the same class or an inherited one; a
  forward or cyclic reference is a compile-time error.

- **New: `enum union`** — when every variant of a `union` is payload-free,
  prefixing the declaration with `enum` makes it an enumeration:

  ```nex
  enum union Color
    Red
    Green
    Blue
  end
  ```

  On top of the plain `union` desugaring (a sealed hierarchy plus `match`
  exhaustiveness), `enum` adds three things: members are interned constants on
  the type, so `Color.Red == Color.Red` holds with no allocation per use; members
  inherit `Comparable` and order by declaration order (`Color.Red < Color.Green`);
  and `Color.values` is an `Array[Color]` of every member in declaration order,
  each with an `ordinal`.

- **New: the `Any` protocol works end to end on the JVM.** Overriding
  `to_string`, `equals`, or `clone` on a user class now takes effect in compiled
  code — the lowerer routes to state-aware runtime helpers when the receiver's
  class declares none, and to the override when it does. `Set` and `Map` honour
  `equals`/`hash` overrides on the JVM with exact interpreter parity, so an
  object used as a key behaves the same on both backends.

- **Fixed: a `declare type` alias in a runtime type test never matched.**
  `convert` — and the `field: Type` patterns that desugar to it — tests a runtime
  type, and an alias names none, so the test silently always failed:

  ```nex
  declare type Count = Integer
  let x: Any := 5
  if convert x to y: Count then ... else ... end   -- always took the else
  ```

  Nothing warned: the checker sees `Count` as related to the value's type and
  accepts it. Aliases are now resolved to the type they name before either
  backend sees the convert.

  A **refinement** (`= Integer where n: n > 0`) cannot be resolved that way — its
  predicate is erased, so a test against it could only check `Integer` and would
  match values the refinement excludes. Rather than silently weaken it, a
  refinement in a type-test position is now rejected, with an error naming the
  base type to test and the guard to check the predicate in. Refinement checks at
  the real narrowing sites (typed `let`, parameter, return) are unchanged.

- **Fixed: class field names were global variables.** Declaring any class made
  every one of its field names — private ones included — a readable *and
  assignable* global initialized to nil:

  ```nex
  class Account
    feature balance: Integer
    create make(v: Integer) do balance := v end
  end
  print(balance)          -- typechecked; printed nil
  ```

  The first pass (`collect-class-info`) binds field names as variables so a
  constant's initializer can name a sibling constant (`B = A + 1`); it bound them
  into the caller's env, which at top level is the program's global scope. That
  is a void-safety hole in the place the language makes its strongest promise,
  and it quietly absorbed typos: any misspelling colliding with a field name
  anywhere in the program became nil instead of a compile error. The scope is now
  local to the pass and seeded with inherited *constants* only. Field access
  inside a class is unaffected — `check-class` binds fields properly, honouring
  visibility and generic substitution.

  Two things had come to lean on the leak, both now fixed at the root: lowering
  had no `:old` case and inferred `old balance` from an env with no class
  context, and `constant-nex-type` inferred a constant's initializer in the
  reader's scope rather than the declaring class's (so `print(Base.B)` with
  `B = A + 5` resolved `A` only via the leak).

- **Breaking: in a `match` field pattern, `:` means a type and `as` renames.**
  A field pattern's colon used to do three unrelated jobs — pin a field to a
  literal (`Move(dx: 0)`), narrow it to a type (`Ok(inner: Some(value))`), and,
  with a bare identifier, *rename* it (`Shipped(tracking: t)`). The rename reads
  exactly like the type annotation `x: T` is everywhere else in Nex while
  meaning the opposite, and it collided with the type form: dropping the parens
  from a working nested pattern silently flipped "test the type" into "rename to
  a local". A builtin type name in that position was not even spellable —
  `String` is a keyword token, not an identifier, so `when Err(s: String)` was a
  *syntax* error.

  Now the name left of the colon is always a field, `:` means only "this field
  has this type", and renaming moves to `as`, which already means "bind under
  this name" at clause level:

  ```nex
  when Shipped(tracking as t)   then track(t)         -- was: tracking: t
  when Box(content: Circle)     then use(content)     -- narrows and binds
  when Box(content: String)     then say(content)     -- builtins now spellable
  when Ok(inner: Some[Integer](value as x)) then use(x)
  ```

  A bare `field: Type` narrows the field and binds it under its own name; with
  sub-patterns you reach the value through them, so the field itself is not
  bound. Like a guard, a type pattern is a test and does not count toward
  exhaustiveness. To ignore a field, simply do not name it.

  **Literal field patterns are removed.** `Move(dx: 0)` was sugar for
  `Move(dx) if dx == 0`. It gave `:` a second meaning, and — alone among the ways
  of naming a field — it did not *bind* the field it named, so
  `when Ok(value: 10) then print(value)` printed nil. Write the guard:

  ```nex
  when Move(dx, dy) if dx = 0 and dy = 0 then stay()
  ```

  **Migration.** Every old spelling is rejected rather than reinterpreted, and
  each error names its replacement: `` `t` is not a type. To bind the field to a
  local named `t`, write `tracking as t` ``; for `field: _`, omit the field; for
  `field: <literal>`, the guard form.

- **New: operator aliases** — a class feature can bind itself to an arithmetic
  operator with an `alias` clause (`minus(other: Money): Money alias "-"`). The
  operator becomes exactly sugar for the call, so the feature's `require` and
  `ensure` clauses hold at the operator too: `a - b` checks `same_currency` just
  as `a.minus(b)` does. Aliases are inherited, so an operator declared on a
  deferred parent dispatches to a descendant's override.

  The mechanism is deliberately narrow. Only `+ - * / % ^` may be aliased — no
  new symbols can be invented — and only arithmetic: ordering already dispatches
  through `Comparable`'s `compare` and `=` through `equals`, so a class earns the
  comparison operators by inheriting `Comparable`, not by aliasing. An alias is
  consulted only after the numeric (and, for `+`, String) paths decline, so it
  can never shadow built-in arithmetic.

  Built-in `Integer`/`Real` arithmetic is unaffected at runtime: for a program
  that declares no alias, the emitted bytecode is byte-for-byte identical to
  before, and lowering costs one set-membership test per binary node.

  `alias` is a soft keyword: it means this only in a routine signature, and
  remains usable as the name of a variable, field, parameter, or routine
  (unlike `union` and `where`, which are soft only as member names). Nothing
  that parsed before this change stops parsing.

  Also fixed: `nex format` now preserves an `alias` clause instead of dropping it.

- **Fixed: constrained generics now compile.** `function f[T -> Bound](…)` could
  call the routines of `Bound` on a `T` only when `Bound` was a builtin such as
  `Comparable`; a user-class bound type-checked and ran on the interpreter but
  failed to compile with "Unsupported target call expression for lowering". The
  receiver is an ordinary Nex object at runtime, so it now lowers to the same
  dynamic dispatch any user-class call uses. Reading a *field* through a bound
  works as well, and a no-arg routine written without parentheses (`x.describe`)
  dispatches as a routine rather than being misread as a field access.

- **Fixed: a subclass constructor could leave an inherited field void.** Void
  safety checked that a constructor initializes the attachable fields a class
  *declares*, but not the ones it inherits: a subclass constructor that never
  reached the parent's left the parent's attachable field nil, in the one place
  the language promises it cannot be. A constructor that does not reach its
  parent's is now rejected, with an error naming the field — the constructor
  looks complete on its own, and what is missing comes from elsewhere in the
  file. Checking direct parents covers a chain, since each link's own check
  already guarantees it reaches the next. A detachable (`?A`) field may be void
  and forces nothing; neither does a builtin-typed field, which has a zero value.
  A subclass that declares no constructor of its own inherits the parent's, which
  already initialize.

- **Fixed: an undefined type name in an annotation was not reported.** A new
  validation pass runs once every class, alias, and import is known but before
  body checking, so an undefined type is named directly instead of spraying
  unrelated errors from the code that uses it. Error collection is bounded. A
  bare undefined *parent class* is left to the dedicated inheritance check, which
  gives a clearer message.

- **Fixed: a non-generic class inheriting an instantiated generic was not
  assignable to the parent type** — `class IntBox inherit Box[Integer]` did not
  satisfy a `Box[Integer]` parameter. The compiled backend now handles inherited
  generics directly as well, so these programs no longer fall back to the
  interpreter.

- **Fixed: `old` now compares structurally.** An `ensure` clause comparing `old
  state` to the current one compared by reference, so a mutated object looked
  unchanged.

- **Fixed: a user feature may be named with a `__` prefix.** The synthetic
  invariant method was emitted as `__invariant`, which a user feature of that
  name collided with. It is now `$invariant`; `$` is forbidden in identifiers by
  the grammar, so the synthetic name cannot collide with anything spellable.

- **Fixed: type-checker and intern ordering bugs**, including intern handling in
  the REPL. Invariants now compile to bytecode rather than round-tripping through
  the interpreter, and validation is gated on a class actually declaring one.
  Compiled output no longer embeds unreachable class definitions.

## 0.2.0 - 2026-07-11

- **New: `union` declarations** — a concise syntax for sum types. `union Name`
  followed by a list of named variants (`Placed(id: String, total: Real)`, or a
  bare name for a payload-free variant) declares a closed family of data
  variants. It desugars to the existing `sealed deferred class` parent plus one
  inheriting class per variant with an auto-generated `make` constructor, so
  construction, generics, `match`, and exhaustiveness checking are unchanged. A
  `union` declares data only; a variant needing methods, invariants, or a
  constructor contract stays in the explicit sealed-class form. `union` is a
  soft keyword (it remains usable as a member name such as `Set.union`).
- **New: refinement types** — `declare type Quantity = Integer where n: n > 0`
  narrows a base type by a predicate. A refinement is not a class: it is erased
  to the base representation (a `Quantity` *is* an `Integer`, usable in any
  arithmetic), and the predicate is checked only where a base value is narrowed
  in — a typed `let`, a parameter, a return, or a `convert`. Widening is free,
  operations on refinements yield the base type, and the checks are elided under
  `skip-contracts` like every other contract. `where` is a soft keyword.
- **New: richer pattern matching** — a `match` clause can now destructure a
  variant's fields by name (`when Placed(id, total)`), rename a field
  (`when Shipped(tracking: t)`), ignore one (`_`), require a literal field
  value, match nested patterns, and carry an `if` guard evaluated after the
  structural match. Guarded, literal, and nested clauses do not count toward
  exhaustiveness, so a variant covered only by such a clause still needs an
  unguarded clause, a wildcard, or `else`. Destructuring is pure walker sugar;
  guards, literals, and nesting run on the JVM and interpreter backends.
- **New: standard `Result` and `Option`** — `intern data/Result` and
  `intern data/Option` ship `Result[T, E]` (`Ok` / `Err`, with independent
  value and error types so errors thread up without rewrapping) and `Option[T]`
  (`Some` / `None`). Query and unwrap are methods (`is_ok`/`is_err`/`unwrap_or`,
  `is_some`/`is_none`/`get_or`); the transforming combinators are free functions
  (`result_map`/`result_and_then`/`result_map_err`,
  `option_map`/`option_and_then`/`option_filter`). Three enabling changes made
  this possible: `intern` now exports a library's free functions (not only its
  classes) to the typechecker and compiled backend; `match` dispatches on a
  generic sealed class's base name at runtime; and an exhaustive `match` with no
  `else` now satisfies definite assignment (a combinator whose whole body is
  such a match no longer needs a dummy default).
- Fixed a compiled-backend lowering failure ("Unable to infer expression type
  during lowering") on a nested `match` whose inner subject is a method call on
  a value of a generic type parameter (e.g. `ok.value.resolve(…)`); the program
  type-checked but could not be lowered, though it ran under `--interpret`.
- Fixed a `StackOverflowError` in the interpreter (`nex.types.builtins/nex-object?`)
  triggered by the same nested-match-over-a-recursive-method construct.
- **Breaking:** the **JavaScript backend has been removed**, leaving a single
  JVM implementation. The Nex→JavaScript generator (`nex.generator.javascript`),
  the ClojureScript/Node interpreter runtime (shadow-cljs, `package.json`,
  `nex-wrapper.js`, `bin/nex-node.js`, the platform-diff harness), and the
  `nex compile js` / `./install.sh nodejs` targets are all gone. The interpreter,
  typechecker, lowering, and runtime source moved from `.cljc` to `.clj` and the
  `#?(:cljs …)` reader conditionals were dropped. `nex compile` now accepts only
  the `jvm` target. The `import X from "…"` and `with "javascript"` surface
  syntax still parses but has no backend that consumes it.

- **Breaking:** function and method parameters are now **contravariant** and
  return types **covariant** (previously parameters were covariant). A function
  value or overriding routine may *widen* a parameter and *narrow* a return, but
  not the reverse — making conformance a sound, local check. Override conformance
  is now enforced at the definition site (an override that narrows a parameter or
  returns a non-conforming type is rejected there, naming the routine and
  position), which also closes a case where a non-conforming return override was
  previously accepted. Function-value assignment now enforces the same rule
  (it was previously checked too leniently). Generic signatures are resolved
  through inheritance before the check, so an override of a method inherited from
  e.g. `Container[Integer]` is verified with the type parameter substituted.
  To keep a covariant-style override, retain the wider parameter type and narrow
  inside the body with `convert`/`match`, or use generics. See
  `docs/md/VARIANCE.md`.
- **Breaking:** the `when` expression now requires `then` before the consequent,
  matching the `when ... then` shape used by `match`/`select` clauses.
  Write `when cond then a else b end` instead of `when cond a else b end`.
- **Breaking:** the remainder operator `%` is **truncated** (sign of the
  dividend, like C/Java) on every backend, for Integer and Real alike:
  `-7 % 3` is now `-1` everywhere. Previously the interpreter used floored
  (Python-style) semantics while compiled code truncated, so programs could
  observe different results per backend.
- **Breaking:** `convert` never changes numeric representation: a statically
  numeric-to-numeric conversion (`convert i to r: Real` with `i: Integer`, or
  the reverse) is now a compile-time error. An Integer already widens
  implicitly where a Real is expected, and `Real.round()` yields an Integer.
  At runtime, an `Any`-sourced convert to a different numeric class yields
  `false` on both backends (previously the compiled backend crashed).
- **Breaking:** `nex <file>.nex` runs on the compiled JVM backend **only**.
  A program outside the compiled subset is an error naming the unsupported
  construct; pass `--interpret` to run on the tree-walking interpreter
  explicitly. A runtime failure of the compiled program is reported as the
  program's outcome — the program is no longer silently re-executed under the
  interpreter (side effects now run exactly once).
- **Breaking:** the REPL's `:backend interpreter|compiled|status` commands are
  removed; the compiled backend is the only REPL backend. The debugger
  (`:debug on`, breakpoints, watchpoints) is unaffected.
- **Breaking:** `lib/time` and `lib/io/path` used the undeclared type name
  `Integer64`; those signatures now say `Integer` (which is 64-bit).
- Numeric conformance fixes, identical on both backends per the Definition's
  §B.3: NaN ordering follows IEEE (every `<` `<=` `>` `>=` against NaN is
  false); `MIN_LONG / -1` raises on overflow; integer division by zero reports
  "Division by zero" (compiled code previously leaked the host's "/ by zero");
  `5 == 5.0` is `true` (`==` coincides with `=` on scalars); the interpreter's
  32-bit bitwise operations no longer raise on values that overflow an int
  (`(1).bitwise_left_shift(31)` is `-2147483648` everywhere).
- Fixed `convert` bindings: the bound variable now lives in a reference slot
  (it is detachable), so a failed convert binds `nil` and yields `false`
  instead of crashing (NPE, or a VerifyError when the guarded branch narrowed
  the type).
- Fixed compiled-backend dispatch bugs: methods inherited from a generic
  parent no longer fail with `ClassNotFoundException` for the type parameter;
  calls on ordinary Nex values inside a `with "java"` block dispatch normally
  instead of being routed to host reflection; a method call on a nil receiver
  reports "Used a value that is void (nil)" instead of a raw JVM message.
- Closed the remaining compiled-subset gaps, so the whole standard library and
  every bundled example compile: named constructors on imported Java classes,
  `create Map` / `create Set`, the full builtin free-function return-type
  table (datetime/regex/path/file/json/http), paren-less builtin-method
  chains (`x.to_string`), and a compiler crash on `examples/apl.nex`.
- Internal: the builtin runtime — the per-type method table, free-function
  builtins, tasks/channels, heaps/atomics, Java interop — moved out of the
  interpreter into the engine-neutral `nex.types.builtins` and
  `nex.types.concurrency` namespaces (engine specifics injected via
  `set-engine-hooks!`). `nex.lower` no longer depends on `nex.interpreter`.
  The backend-alignment plan and remaining work are tracked in
  `docs/md/BACKEND_ALIGNMENT.md`.

## 0.1.1-beta - 2026-03-23

- Made the JVM-compiled backend the default REPL backend.
- Kept automatic interpreter fallback for unsupported inputs.
- Kept `:backend interpreter` as the explicit escape hatch.
- Added `.nex -> .class` and standalone shaded JAR compilation via `compile jvm`.
- Completed broad JVM compiler coverage across:
  - classes, inheritance, `super-calls`, generics, `convert`
  - contracts and exceptions
  - closures and higher-order functions
  - concurrency, channels, tasks, `select`
  - `import`, `intern`, and `with "java"`
- Added compiled REPL soak and parity coverage for long progressive sessions.
- Added interpreter and compiled validation for tutorial and book examples.
- Added micro and soak performance gates for the compiled REPL.
- Added a JVM bytecode translation reference to the design book.
