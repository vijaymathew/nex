# Proposal: Open features (`extend … end`)

Status: **draft**. Version 1 covers every Nex class, user-defined and built-in.
Phase 2 adds imported Java classes.

## Summary

Add one top-level construct, `extend`, that adds features to an existing class
from anywhere in a program, including from a module that does not own the class:

```nex
extend Shape
feature
  area(): Real
    deferred
    ensure non_negative: result >= 0.0
    end
end

extend Circle
feature
  area: Real do result := 3.14159 * radius * radius end
end
```

An extended feature is an ordinary feature of its target. Callers write
`s.area`, as for any other feature. Contracts are inherited along the class
hierarchy. The target's invariant is checked around every call. A `deferred`
extended feature must be implemented by every concrete heir in the program,
and the compiler checks this.

`extend` has one syntax for every target. Behind it are two implementation
paths:

- **Classes with a Nex definition** (user classes, `union` variants, sealed
  parents, and Nex classes that inherit a Java class). A whole-program pass,
  run after `intern`s are resolved, merges each extension into its target's
  class definition. The type checker, interpreter and JVM backend then see
  ordinary features. This is the strategy `union` used: it desugars to a
  sealed class hierarchy and changes nothing downstream.
- **Built-in types** (`String`, `Integer`, `Array [T]`, `Map [K, V]`, …, and the
  roots `Any` and `Comparable`). Their values are plain JVM values whose methods
  live in `builtin-type-methods` (`src/nex/types/builtins.clj`); they have no
  class definition to merge into. Each extension is compiled to an ordinary
  function and recorded in an **extension table**, which every built-in method
  lookup consults.

Phase 2 reuses the built-in path for imported Java classes.

## Motivation: the expression problem in Nex today

Nex closes each half of the expression problem with a different construct, so
a program gets one direction for free and pays for the other.

**`union` / `sealed`: new operations are cheap, new variants are not.** A new
operation is a new function with a `match`, and the compiler checks the match
is exhaustive. A new variant means editing the `union` and every exhaustive
`match` over it. If the union lives in a library, you cannot add one.

**Deferred classes: new variants are cheap, new operations are not.** A new
variant is a new heir. A new operation means adding a deferred feature to the
parent and a body to every heir, which you cannot do to a library's classes.

Two workarounds exist; neither is good enough to recommend as the main answer.

*Fallback `match`.* Make the base a non-sealed deferred class, and have each
extension's function handle its own cases and pass the rest to the previous
version:

```nex
function eval1(e: Expr): Integer do
  match e of
    Lit(value)       then result := value
    Add(left, right) then result := eval1(left) + eval1(right)
  else
    raise "eval1: unknown variant"
  end
end

function eval2(e: Expr): Integer do
  match e of
    Neg(inner) then result := -eval2(inner)
  else
    result := eval1(e)
  end
end
```

The compiler can no longer check exhaustiveness: a missing case is a runtime
error. Worse, recursion is closed. `eval2` on `Add(Neg(Lit 1), Lit 2)` calls
`eval1`, which recurses into `eval1`, which does not know `Neg`, and raises.

*Object algebras.* Encode the data type as a generic deferred class with one
feature per variant, and combine extensions with multiple inheritance. This is
type-safe, but programs must be written against the algebra
(`alg.add(alg.lit(1), alg.lit(2))`) rather than against values, which is a heavy
style to teach.

**Built-in types have the same problem.** Adding a convenience to `String` or
`Array [T]` today means a free function, which breaks uniform access: callers
write `is_palindrome(s)` for the new operation but `s.reverse` for the built-in
one, and the operation does not show up where a reader looks for features of
`String`.

Open features fix both directions while keeping static checking: a new
operation is an `extend` block; a new variant is a new class; and the compiler
checks that every concrete class implements every deferred operation, across the
whole program.

## Syntax

`extend` is a **soft keyword**, recognized only at the start of a top-level
declaration, the same way `alias` and `where` are (see `grammar/nexlang.g4`,
`aliasClause`). Existing code that uses `extend` as an identifier keeps working.

The body reuses `featureSection`, so everything that can be written in a class's
`feature` section can be written here, minus the restrictions listed under
[Restrictions](#restrictions-in-version-1):

```antlr
extendDecl
    : EXTEND_SOFT typeName genericParams?
      noteClause?
      featureSection+
      END
    ;
```

`extendDecl` joins the alternatives of `program`, next to `classDecl` and
`unionDecl`.

### Examples

A deferred operation on a user hierarchy, with a contract:

```nex
extend Shape
feature
  area(): Real
    deferred
    ensure non_negative: result >= 0.0
    end
end
```

Implementations, which may live in the same module or any other:

```nex
extend Circle
feature
  area: Real do result := 3.14159 * radius * radius end
end

extend Rect
feature
  area: Real do result := width * height end
end
```

A default body. Heirs may override it but need not:

```nex
extend Shape
feature
  describe: String do
    result := "shape with area " + area.to_string
  end
end
```

A query answered by an attribute, just as an heir can answer a parent's
deferred query with a field today (`docs/md/SYNTAX.md`, "Redeclaring queries as
attributes"). No `extend` is needed for such a class:

```nex
class Square_Tile
  inherit Shape
  feature
    area: Real          -- answers Shape's extended `area`
  create
    make(a: Real) do area := a end
end
```

A generic user class:

```nex
extend Box [T]
feature
  is_same(other: Box [T]): Boolean do
    result := value = other.value
  end
end
```

Built-in targets, including generic ones and constrained ones:

```nex
extend String
feature
  is_palindrome: Boolean do
    result := this.to_lower = this.to_lower.reverse
  end
end

extend Array [T -> Comparable]
feature
  maximum: T
    require non_empty: not is_empty
    do
      result := this.get(0)
      across this as x do
        if x > result then result := x end
      end
    end
end

print("Level".is_palindrome)      -- true
print([3, 9, 4].maximum)          -- 9
```

## Semantics

These rules apply to every target. Rules that apply only to built-ins are in
[Built-in targets](#built-in-targets).

### An extended feature is a feature

An extended feature is a real feature of its target and of the target's heirs.
It is called, inherited, redefined, passed through constrained generics
(`[T -> Shape]`) and dispatched exactly like a feature written in the class. A
reader cannot tell from a call site where a feature was declared.

### Introducing and redefining

The rule is the one classes follow today. A feature name that is new to the
target and all its ancestors **introduces** a feature. A name that the target
inherits, whether that ancestor declared it in its class body or in an
extension, **redefines** it, and must conform to the inherited signature.

So `extend Shape … area … end` introduces `area`, and `extend Circle … area … end`
redefines it.

### Contracts

`require` and `ensure` on an extended feature are checked on every call, like
those of any feature. Along an inheritance chain they combine as today:
preconditions are OR-ed and postconditions AND-ed
(`lookup-method-with-inheritance`, `src/nex/interpreter.clj`). A redefinition
in `extend Circle` therefore inherits the `non_negative` postcondition declared
in `extend Shape`, and may weaken the precondition or strengthen the
postcondition, never the reverse.

This is where open features fit Nex in particular: **the contract travels with
the operation**. Whoever adds an implementation in another module is held to
the guarantee the operation's author wrote.

### Invariants

The target's class invariant is checked on entry to and exit from a qualified
call to an extended feature, as for any feature. An extension cannot break an
invariant without the runtime reporting it.

### Coverage

A `deferred` extended feature must be implemented by **every concrete heir of
the target in the program**. The implementation may come from:

- the heir's own class body,
- an `extend` block on the heir or on an intermediate ancestor, or
- an attribute of the same name (a query answered by a field).

A `deferred` heir is exempt; its concrete heirs are not. A missing
implementation is a compile error that names the heir, the feature, and where
the feature was declared, and says how to fix it:

```
Type error: Class 'Triangle' does not implement deferred feature 'area',
declared by 'extend Shape' at shapes/area.nex, line 2. Add 'extend Triangle
feature area ... end', give 'area' a default body in 'extend Shape', or
declare 'Triangle' 'deferred'.
```

(A deferred routine is written with parentheses, `area(): Real deferred`, as
in a class; its implementations may drop them, `area: Real do ... end`.)

Three patterns cover the cases where not every heir should implement an
operation:

1. **Strict.** Declare it `deferred` on the parent. Every concrete heir must
   implement it. Use this when the operation makes sense for every variant.
2. **Default.** Give the parent's extension a body. Heirs override it when they
   have something better; the rest inherit the default, which may compute a
   general answer or `raise`. A default that raises gives up the static check
   for heirs you did not cover, so use it deliberately.
3. **Partial.** Extend only the classes that have the operation, with no
   declaration on the parent. A value typed as the parent has no such feature;
   callers narrow first with `match` or an object test (`?s as c`).

### Visibility of the target's features

An extension is written by a client of its target, not by its author. Its bodies
see exactly what any client sees: the target's public features. A `private`
feature of the target is not visible inside an extension.

### Conflicts

Each of these is a compile error:

- An extension introduces a name that the target already declares itself. An
  extension cannot replace a class's own feature. (Redefining an *inherited*
  feature in an extension on an heir is allowed; see above.)
- Two extensions introduce the same name on the same target.
- Two extensions on the same target declare overloads of the same name and
  arity.
- For a built-in target, the name already exists in that type's method table or
  in `Any`'s.

### Scope

An extended feature is in scope wherever the module that declares it is part of
the resolved program, by the same rule that brings interned classes into scope.
At runtime the feature belongs to the class, so it dispatches correctly for every
object of that class, whichever module created the object.

### Restrictions in version 1

An extension adds operations, not state or structure:

- no fields (an object's layout is fixed by its class),
- no constructors (`create` sections),
- no `once` features (they need per-object storage),
- no `invariant` clause,
- no `inherit` clause,
- no `private` feature sections.

Helpers an extension needs can be free functions.

## Built-in targets

### Concrete built-ins

`String`, `Integer`, `Byte`, `Integer16`, `Integer32`, `Real`, `Char`,
`Boolean`, `Array [T]`, `Map [K, V]`, `Set [T]`, `Map_Entry [K, V]`,
`Min_Heap [T]`, `Task [T]`, `Channel [T]`, the atomic types, and the system
types `Console`, `Process` and `Program` can be extended. Because no class can
inherit from them, there are no heirs to dispatch over:

- `deferred` is rejected on a built-in target, because nothing could ever
  implement it.
- Each extended feature becomes a generated function,
  `__ext_<Type>__<feature>__<arity>`, whose first parameter `__this` is the
  receiver (`nex.extend/builtin-extension-functions`).
- In the body, `this` is the receiver, and the type's features (its own, `Any`'s,
  and those its extensions add) may be used without a receiver, as in a class:
  `to_lower`, `get(0)`, `squared`. To keep that unambiguous, a local or
  parameter may not have the name of one of those features, just as a local
  may not hide a field in a class.
- `this` cannot be reassigned. `Integer` and `String` are values, so an
  extension cannot change the receiver itself, but it may call mutating built-in
  features on it (`this.add(x)` on an `Array`).
- A built-in feature cannot be redefined by an extension (see
  [Conflicts](#conflicts)).

### Numeric types

An extension on `Real` does **not** apply to `Integer` values. In Nex, an
`Integer` passed where a `Real` is expected is converted, not treated as a
subtype at runtime (`numeric-subtype-runtime?`, `src/nex/types/typeinfo.clj`).
Extend each numeric type that needs the feature. (A hint to add `extend Integer`
when only `extend Real` declares a feature is not implemented yet.)

### `Any` and `Comparable`

`Any` is the root of every class, and user classes inherit `Comparable`. An
extension on either reaches both kinds of value: `5.bracketed` and
`dog.bracketed` both run `extend Any`'s `bracketed`.

- An extension on `Any` or `Comparable` must give a body. `deferred` on `Any`
  would require every type in the program, including every built-in, to
  implement the feature. Whether `deferred` on `Comparable` is useful enough to
  allow is an [open question](#open-questions).
- **A class cannot redefine such a feature.** A class declaring a feature that an
  extension of `Any` or `Comparable` adds is a compile error. On the compiled
  backend a call of the feature is resolved to the extension's function
  statically, so a class's own version could not take over as a redefinition
  does. Making it redefinable needs dynamic dispatch on the receiver, and is
  left for later.

### Generic built-ins

`extend Array [T]` declares `T` for the extension's bodies, with the type's own
parameter names (`Map [K, V]`). A constraint (`extend Array [T -> Comparable]`)
makes the extension's features available only on arrays whose element type
satisfies it; calling `maximum` on an `Array [Dog]` whose `Dog` is not
`Comparable` is a type error. The constraint must be a plain type name, as for
constrained generics today.

## How this solves the expression problem

Take three modules:

- **A** declares `deferred class Shape` and `class Circle inherit Shape`.
- **B** interns A and adds an operation: `extend Shape … area deferred …` and
  `extend Circle … area …`.
- **C** interns A and adds a variant: `class Triangle inherit Shape`.

Each module typechecks on its own. A program that interns **B and C** combines a
new operation with a new variant that neither module knows about, and gets:

```
error: Class 'Triangle' does not implement deferred feature 'area'
```

The program fixes it with a short glue extension:

```nex
intern shapes/B
intern shapes/C

extend Triangle
feature
  area: Real do result := base * height / 2.0 end
end
```

Both directions are now open, neither requires editing existing code, and the
compiler still guarantees that every operation is defined on every variant.

The tradeoff is that the check is whole-program: two libraries that are each
correct on their own can need glue code when combined. This is inherent in
static checking of both directions, and the error says exactly what is missing.

## Implementation (version 1)

### Front end

- **Grammar** (`grammar/nexlang.g4`): add `extendDecl` to `program`, with the
  soft keyword matched as an `IDENTIFIER` whose text is `extend`.
- **Walker** (`src/nex/walker.clj`): turn `extendDecl` into an `:extension` node
  `{:type :extension :target "Shape" :generic-params … :body [feature-sections]}`.
  `handle-program` collects these into a new `:extensions` key of the program
  map, alongside `:classes` and `:functions`. Reject the restricted members (fields,
  `create`, `once`, `private`) here, with a message per case.
- **Interns** (`src/nex/intern.clj`): `resolve-interned` also gathers
  `:extensions` from every interned module, stamped with their source file, so
  an extension in a library is applied like the library's classes.

### The `apply-extensions` pass

A new namespace, `nex.extend`, holds one pass, `apply-extensions`, which runs
after interns are merged and before type checking. It sorts extensions by target:

- **Targets with a Nex definition**: append the extension's feature sections to
  the target's class definition. Tag every added member with
  `:extension {:file … :line …}`. The type checker uses the tag for diagnostics
  and to check the body with client-only visibility; the backends ignore it.
- **Built-in targets**: build the extension table, keyed by built-in type name
  and then by feature name and arity, and generate one function per feature,
  taking the receiver first.
- **Unknown targets**: report "cannot extend unknown class 'X'".

Each of the three places that merge interns today calls the pass:

- the file compiler, `src/nex/compiler/jvm/file.clj` (where `resolve-interned` is
  merged into the program),
- the compiled REPL, `augment-ast-with-modules` in
  `src/nex/compiler/jvm/repl.clj`,
- the interpreter's runtime `intern`.

### Type checker

- **Coverage** needs no new check. After the merge, a deferred extended feature
  is a deferred feature of the target, and
  `check-deferred-methods-implemented!` (`src/nex/typechecker.clj`) already
  rejects a concrete class that leaves one unimplemented. Only its message
  changes: when the deferred declaration carries an `:extension` tag, say where
  it was declared and suggest an `extend` block, as in [Coverage](#coverage).
- **Visibility**: check extension bodies as a client of the target: only
  members for which `public-member?` holds are visible, the same filter a
  call from outside the class gets.
- **Conflicts**: add the checks listed in [Conflicts](#conflicts).
- **Built-in signatures**: register each built-in extension's signature next to
  the built-in ones (`register-builtin-type-signatures!`), so calls on built-in
  receivers typecheck with no other change.

### Interpreter

- Targets with a Nex definition need nothing new: `lookup-method-with-inheritance`
  walks class definitions, which now contain the extended features, and already
  combines inherited contracts.
- Built-in targets: evaluating a generated function records it in the context's
  extension table, by type, feature and arity. `call-builtin-method` checks the
  table, for the value's own type, then `Comparable` when the value is
  comparable, then `Any`. A user object reaches `Any`'s and `Comparable`'s
  extension features the same way when its class has no such feature.

### JVM backend

- Targets with a Nex definition: no new dispatch. Calls on Nex objects go through
  one dispatch interface per routine name and arity
  (`nex/dispatch/F_<name>$<arity>`, `dispatch-interface-name` in
  `src/nex/compiler/jvm/emit.clj`). An extended method is emitted like any other,
  so the class implements that interface.
- Built-in targets: the type checker registers each generated function's feature
  as a method of its type, and records every call that resolves to one
  (`:extension-calls` of `tc/type-check`). Before lowering, those calls are
  rewritten into ordinary calls of the generated functions, receiver first
  (`nex.extend/rewrite-extension-calls`), so lowering sees only function calls.
  There is no runtime table on this backend.
- **Source positions.** The JVM records one source file per class, and a stack
  frame names the file of its method's class. A routine an `extend` block in
  another file adds to a class is therefore moved, after emission, into a
  companion class `<Class>$Src<N>` whose source file is the extension's: a
  static method taking the object first, which the class's own same-named
  method forwards to (`split-foreign-methods`, `src/nex/compiler/jvm/emit.clj`).
  Dispatch and call sites are unchanged, and the body moves as is, since an
  instance method's `this` and arguments occupy the same local slots as the
  static method's. The same applies to free functions of interned modules,
  which are compiled into the program's class, and to closures in either,
  whose classes take their file from the code they are written in. `nex`'s
  error trace reports a companion's frames as its class's routine
  (`in Circle.area`) and skips the forwarder's frame; a raw JVM trace (a jar run
  directly) shows the companion with the right file and line. (Compiling an
  interned module's classes with their own file, rather than the entry file's,
  came with this: before it, a frame in any interned class named no file.)

### REPL

- An `extend` input whose target an earlier input (or the same one) declares is
  folded into that class: the input becomes a redefinition of the class with
  the features added (`fold-session-extensions`, `src/nex/repl.clj`). Both
  backends then take it in as they take any class redefinition. On the compiled
  backend, `dependent-session-classes` (`src/nex/compiler/jvm/repl.clj`)
  recompiles the classes that depend on it.
- The extensions of a module an input interns are applied with the module, to
  its own classes or to the session's; interning the module again changes
  nothing.
- An input that interns a module is type-checked with the session, as a file's
  interns are checked with the file. Otherwise a module adding a class to a
  hierarchy another module extended could leave that class without a deferred
  feature, unnoticed until the feature is called.
- Coverage is checked for the classes an input declares or redefines, not for
  heirs declared by earlier inputs, so `extend Shape feature area(): Real
  deferred end` can come before the inputs that implement `area`. Calling an
  unimplemented feature on such an heir is a runtime error.
- Objects created before the input behave as they do after a class
  redefinition in the REPL today: on the interpreter they see the new
  features, while on the compiled backend they keep their old class.
- An `extend` input on a built-in updates the session's extension table
  (with built-in targets, not yet supported).

### Caches

The class cache added in #288 must key a class on its merged definition (after
extensions), so that a class compiled without an extension is not reused for a
program that extends it.

## Phase 2: Java classes

Imported Java classes have no Nex definition, and their bytecode cannot be
changed. Phase 2 extends them the way Kotlin and C# do, with **static
extensions**, built on the version 1 built-in path:

```nex
import java.lang.StringBuilder
import java.util.List

extend StringBuilder
feature
  is_blank: Boolean do result := length() = 0 end
end

extend List
feature
  describe: String do result := "list of " + size().to_string end
end
```

- The target names the class through an `import`, as any reference to it does.
  Classes and interfaces (`List`) can both be extended.
- Each feature becomes a generated function taking the receiver first
  (`nex.extend/java-extension-functions`), exactly as for a built-in type. In
  the body, `this` is the receiver and the class's public methods may be called
  without one (`length()`); a local may not have the name of one of them.
- **Static choice.** On the compiled backend, a call reaches the extension of
  its receiver's static type, or, when that type is a Java class with no such
  feature, of the nearest of its supertypes that has one (a reflective walk of
  its superclasses and interfaces, at compile time). The interpreter walks the
  receiver's runtime class the same way.
- **Decision: one class per hierarchy.** A feature (name and arity) may be added
  to one class of a Java hierarchy only; adding it to `List` and `LinkedList`
  is an error. The proposal had imagined choosing among several by the
  receiver's runtime class, the most specific first. That needs a runtime type
  test in the generated code, and choosing by static type on one backend and by
  runtime class on the other would make them disagree. With one class per
  hierarchy the two choices are always the same. Subclasses still get the
  ancestor's feature. (`convert` to a Java type, which did not work when this
  was decided, now does; runtime selection could be built on it later.)
- **Rules:**
  - no `deferred`: Java classes cannot be required to implement anything;
  - no generic parameters (`extend LinkedList [T]`): Nex does not write Java
    types with type arguments;
  - `require` and `ensure` are checked, as for any routine; there are no
    invariants, because Java classes have none;
  - a public Java method with the same name and arity, inherited ones
    included, is a compile error, so a Java method is never silently shadowed;
  - the Java classes that are the runtime form of Nex values (`ArrayList`,
    `HashMap`, `LinkedHashSet`, `String`, the boxed numbers) cannot be extended,
    since that would change the Nex type too: the error says which Nex type to
    extend instead. `java.lang.Object` cannot either; `extend Any` reaches every
    value.
- **Already covered by version 1:** a Nex class that inherits a Java class
  (`class My_Thread inherit Thread`) has a Nex definition, so extending it takes
  the class path.

Phase 2 limits, from Java interop as it is today rather than from extensions:

- Nex has no Java subtyping: a `StringBuilder` cannot be assigned to a
  `CharSequence` variable, so a supertype's feature is reached through a
  subtype-typed receiver, or a value a Java method returns.
- A Java method whose result is an object is typed `Any`, so a feature cannot
  simply return the receiver's class (`append(...)` chains); the result can be
  narrowed with `convert ... to b: StringBuilder`. Primitive and `String`
  results have their Nex types (`size() * 2` is an `Integer`).

Open questions:

- How to treat extensions that differ from a Java overload only in parameter
  types.
- How generic Java types would be written and checked, once Nex can write them.

## Non-goals

- Adding parents to an existing class (`extend X inherit Y`). Useful, but it
  interacts with invariants and with `sealed`, and deserves its own proposal.
- Multiple dispatch, i.e. choosing an implementation by the runtime types of
  several arguments.
- Private helper features inside an extension.
- Extending function types.

## Decisions

1. **Adopting an existing method: allowed.** If `Circle` already has its own
   `area`, and a later `extend Shape` introduces `area`, `Circle.area` becomes a
   redefinition of it, checked for conformance like any redefinition, and
   inherits its contract.
2. **Extensions that supply the same feature: a conflict.** A class gets each
   feature (name and arity) from one place: its own body or one extension. Two
   extensions giving the same class the same feature are rejected, whichever
   modules they are in. Extensions on different classes of one hierarchy
   (`extend Shape` and `extend Polygon` both giving `describe` a body) follow
   the normal redefinition rule.

## Open questions

1. **`deferred` on `Comparable`.** Allowing it would require every comparable
   built-in to implement the feature through an extension.
2. **Tools.** How `nex doc` and the language server show extended features, for
   example listing an extended `String.is_palindrome` in completions and docs
   for `String`, marked with its declaring module.

## Testing plan

- **Type checker:**
  - coverage errors, including a deferred intermediate class, an attribute
    answering an extended query, and the three-module glue example;
  - every conflict in [Conflicts](#conflicts), including clashes with built-in
    names;
  - client-only visibility in extension bodies;
  - rejection of each restricted member;
  - `deferred` on a concrete built-in;
  - constrained generic built-in targets.
- **Both backends:** the same programs run on the interpreter and the JVM,
  covering:
  - user classes and union variants;
  - contract inheritance through extensions;
  - invariant checking around extended features;
  - built-ins, including `Array [T]` and `Any`.
- **REPL:** an extension cell after objects already exist, an extension on a
  built-in, and redefinition of a class that has extensions.
- **Phase 2:** a separate list covering runtime selection across a Java
  hierarchy, clashes with Java methods, and contracts on Java targets.

Tests follow the layout of the existing suites in `test/nex/`.
