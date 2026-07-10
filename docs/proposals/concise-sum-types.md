# Proposal: Concise sum-type syntax (`union … end`)

Addresses **Deficiency #1** in `docs/language-notes-from-book.md`:

> Every sum type is a `sealed deferred class` plus N full subclasses
> (`inherit` / `feature` / `create make`). What is one line in F#/Rust/Haskell
> is ~20 lines in Nex. **Proposal:** a concise ADT syntax with inline variant
> payloads that desugars to the sealed-class form.

## Summary

Add one surface construct, `union`, that reads as a list of named variants with
inline payloads and **desugars, in the walker, to the exact sealed-class AST Nex
already produces today**. The type checker, interpreter, JVM backend and JS
backend see nothing new: they receive ordinary `:class` nodes. `match`,
`create X.make(...)`, generics, and exhaustiveness checking all work unchanged
because the generated classes are byte-for-byte the classes a user writes by
hand now.

(`union` here denotes a *tagged* / discriminated union — every value carries its
variant tag, which is exactly what the sealed-class hierarchy already provides.
It is not a C-style untyped memory overlay.)

This keeps the change tightly scoped to the two front-end layers
(`grammar/nexlang.g4` and `src/nex/walker.cljc`) and leaves the "open edge" — the
explicit `sealed deferred class` form — fully available for anything the concise
form deliberately does not cover.

## Before / after

Today (the book's order model, abbreviated):

```nex
sealed deferred class Order
end

class Draft
  inherit Order
end

class Placed
  inherit Order
  feature
    id: String
    total: Real
  create make(i: String, t: Real) do id := i total := t end
end

class Shipped
  inherit Order
  feature
    tracking: String
    at: Date
  create make(tr: String, a: Date) do tracking := tr at := a end
end
```

Proposed:

```nex
union Order
  Draft
  Placed(id: String, total: Real)
  Shipped(tracking: String, at: Date)
end
```

Both compile to the **same** program. Construction and matching are unchanged:

```nex
let o: Order := create Placed.make("A-100", 42.0)

match o of
  when Draft   as d then print("draft")
  when Placed  as p then print(p.id)          -- payloads are ordinary fields
  when Shipped as s then print(s.tracking)
end
```

Generics carry through the parent to every variant:

```nex
union Result[T]
  Ok(value: T)
  Err(msg: String)
end
```

Recursive variants work because desugaring produces normal classes that name the
parent type:

```nex
union Tree[T]
  Leaf(value: T)
  Node(left: Tree[T], right: Tree[T])
end
```

## Desugaring rules

For a declaration `union P[G…]` with variants `V₁ … Vₙ`:

1. **Parent** → `sealed deferred class P[G…]` with an empty body. This is the
   exact shape the type checker already recognizes as a closed hierarchy
   (`SYNTAX.md` §"Sealed Classes"; the checker requires `sealed` ⟹ `deferred`).
2. **Each variant `Vᵢ`** → `class Vᵢ inherit P[G…]` with:
   - one `feature` field per payload entry `name: Type` (in declaration order);
   - one auto-generated `create make(name₁: Type₁, …) do name₁ := name₁ … end`
     constructor. Because the parameter names equal the field names, the body is
     a straight `field := field` per payload — the assignment target resolves as
     a field of the current object.
   - A **payload-free variant** (`Draft`) becomes a class with an empty feature
     section and a nullary `make` constructor, matching how singleton variants
     are written by hand today.
3. Variant classes inherit `P` with the parent's generic arguments applied
   (`inherit P[G…]`), so `Result[T]`'s `Ok` is `class Ok inherit Result[T]`.

Nothing else is generated. No methods, no invariants, no contracts are
synthesized on the variants.

### Constructor parameter naming

Generated `make` uses the payload field names verbatim as parameter names
(`make(id: String, total: Real)`), rather than the mangled short names in the
hand-written example above. This is both clearer at call sites and avoids a name
table. Assignment `id := id` targets the field (left of `:=` is a member of the
current object); the parameter supplies the value.

## Where it lives in the code

The whole feature is two front-end edits; **no backend, type-checker, or
interpreter change is required.**

### 1. Grammar — `grammar/nexlang.g4`

Add `unionDecl` to the top-level `program` alternation (line 10) and define:

```antlr
unionDecl
    : UNION IDENTIFIER genericParams? noteClause? unionVariant+ END
    ;

unionVariant
    : IDENTIFIER ('(' paramList? ')')?
    ;

UNION : 'union';
```

`paramList` and `genericParams` already exist and already produce the right
sub-trees, so variant payloads reuse the existing parameter machinery (including
`?T` optional types).

**`union` is a soft keyword.** It must stay usable as an ordinary identifier
because `Set` already exposes a `union` method (`s.union(other)`). Reserving it
globally breaks that call. So `UNION` is admitted wherever a member name is
expected:

```antlr
memberAccess
    : QMARK? '.' (IDENTIFIER | UNION) ('(' argumentList? ')')?
    ;
assignment
    : IDENTIFIER ASSIGN expression
    | primary '.' (IDENTIFIER | UNION) ASSIGN expression
    ;
```

clj-antlr surfaces a matched keyword token as its literal string, so the existing
`memberAccess`/`assignment` walkers pick up `"union"` with no change. `union`
remains reserved only as the leading token of a top-level declaration and as a
bare variable name (a variable literally named `union` is disallowed — an
acceptable, documented trade-off).

### 2. Walker — `src/nex/walker.cljc`

Add a `:unionDecl` handler that returns a single node carrying the generated
classes, mirroring how anonymous functions already emit a `:class-def`
(`walker.cljc:544`) and how free functions contribute `function-classes`
(`walker.cljc:403`):

```clojure
:unionDecl
(fn [[_ _union-kw name & rest]]
  (let [generic-params (find-child :genericParams rest)
        note           (find-child :noteClause rest)
        variants       (filter #(node-is? :unionVariant %) rest)
        gp             (when generic-params (transform-node generic-params))
        parent-args    (generic-params->args gp)          ; [G…] for inherit
        parent {:type :class :name (token-text name)
                :sealed? true :deferred? true
                :generic-params gp :parents nil
                :note (when note (transform-node note))
                :body [] :invariant nil}
        variant-classes
        (mapv (fn [v] (union-variant->class v (token-text name) parent-args))
              variants)]
    {:type :union
     :name (token-text name)
     :classes (into [parent] variant-classes)}))
```

`union-variant->class` builds an ordinary `:class` node whose `:body` is exactly
a `[{:type :feature-section …} {:type :constructors …}]` pair — the same shapes
produced at `walker.cljc:549` and `walker.cljc:612`. A payload-free variant emits
an empty feature section and a nullary constructor.

Then, in the `:program` handler (`walker.cljc:373`), flatten `:union` nodes into
`all-classes` alongside `classes` and `function-classes`:

```clojure
union-classes (mapcat :classes (filter #(= :union (:type %)) transformed))
all-classes   (vec (concat classes function-classes union-classes))
```

Because everything downstream consumes `:classes`, that one line is the entire
integration surface.

## Scope boundaries (the "open edge")

The concise form intentionally covers **data-only** sum types. Anything richer
stays in the existing `sealed deferred class` form, which remains fully valid:

- **Behavior / deferred methods on the parent.** Nex's style (and the book's
  functional core, deficiencies #3/#8) favors free functions that `match` over a
  union rather than methods spread across variants. The concise form therefore
  synthesizes no methods; if you want per-variant method overrides, write the
  explicit classes.
- **Per-variant contracts or invariants.** A variant that needs a constructor
  `require`/`ensure` (e.g. "positive quantity") drops to the explicit form — and
  that is exactly the territory of Deficiency #2 (refinement subtypes), which
  should be solved separately rather than bolted onto this syntax.
- **Positional payloads.** Payloads must be `name: Type`; positional-only tuples
  (`Placed(String, Real)`) are rejected, because Nex accesses payloads as named
  fields and pattern matching is type-only (no destructuring — Deficiency #4).

These boundaries are deliberate: they keep the desugaring a pure syntactic
rewrite with no new semantics, which is why the backends need no changes.

## Interactions and edge cases

- **Exhaustiveness.** Unchanged. The checker discovers variants by scanning
  classes whose parent is the sealed class; generated variants are indistinguish­
  able from hand-written ones, so `match` exhaustiveness "just works."
- **Name collisions.** A variant name that duplicates an existing top-level class
  is caught by the existing duplicate-class diagnostics, since both land in
  `all-classes`.
- **`skip-contracts`, docgen, formatter.** These operate on `:class` nodes and
  are unaffected. `fmt.clj`/`docgen.clj` may later be taught to *re-fold* the
  generated classes back into `union` form for display, but that is optional
  polish, not required for correctness.
- **Error reporting.** Since variants become real classes, a type error inside a
  payload type points at the variant. Consider threading the original `union`
  source span onto generated nodes so messages read naturally; not required for a
  first cut.

## Suggested rollout

1. Grammar + walker desugaring + `:program` flattening.
2. Golden tests: assert the AST of a `union` equals the AST of the equivalent
   hand-written `sealed deferred class` set (a structural-equality test is the
   strongest possible guarantee that no backend behavior changed).
3. Runtime tests: construct each variant and exhaustively `match`, on both the
   JVM and JS backends.
4. Docs: a "Sum Types" section in `docs/md/SYNTAX.md` presenting `union` as the
   primary form and the `sealed deferred class` form as the escape hatch.
5. Migrate the book's Part II domain model to `union` and delete the removed
   boilerplate (this is the original motivation and the best real-world test).

## Alternative syntaxes considered

- **`sealed class P` + `variant` sections.** Reuses the existing `sealed class`
  keyword and adds only a `variant` member kind, and it leaves an obvious slot for
  an optional shared `feature`/method section. Slightly more verbose than
  `union`; worth adopting instead if shared behavior on the parent turns out to
  be common.
- **Extend `declare type P = A | B | …`.** Elegant and F#-like, but `declare type`
  currently means a structural *alias* (`:type-alias`), whereas union variants are
  *nominal* classes. Overloading it would blur alias vs. nominal-type semantics.
- **Keyword `choice` / `data` / `sum` / `enum`.** Pure spelling choice. `union`
  was chosen; `enum` would be misleading (these carry payloads).
