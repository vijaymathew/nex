# Foundational Classes

## `Any`

Universal root class for all Nex values. User-defined classes may explicitly
write `inherit Any`.

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `to_string` | none | `String` | Return a user-facing string representation. |
| `equals` | `other: Any` | `Boolean` | Value equality used by the `=` and `/=` operators. The default compares structurally (deep, field-by-field); a class may override it to define its own equality. |
| `hash` | none | `Integer` | Hash code, used to place values as `Set` elements and `Map` keys. The default is structural and consistent with the default `equals`. A class that overrides `equals` should also override `hash` so that equal values hash equal (the type checker warns otherwise). |
| `clone` | none | `Any` | Clone the value. Built-in collections override this with deep-copy behavior. |

The `=`/`/=` operators use `equals` (value equality); `==`/`!=` always compare
object identity and are never overridable.

## `Function`

Built-in base class for all function values. Functions are first-class values
that can be stored in variables, passed as arguments, and returned from other
functions.

### Type signatures

A function-typed variable or parameter may carry a full signature annotation:

```nex
let add: Function(a: Integer, b: Integer): Integer :=
  fn (a, b: Integer): Integer do result := a + b end
```

Parameters names in the signature are optional; the positional-only form is
also valid:

```nex
let compare: Function(Integer, Integer): Integer :=
  fn (x, y: Integer): Integer do result := x - y end
```

Bare `Function` remains valid and is compatible with any typed function value
— it behaves like an unconstrained function type:

```nex
let f: Function := fn (n: Integer): Integer do result := n * 2 end
```

Subtype compatibility: a function value conforms to a function-typed slot when it
accepts *at least* the arguments the slot supplies and returns *at most* what the
slot promises. **Parameters are contravariant** and the **return type is
covariant**. So a `Function(a: A)` value satisfies a `Function(a: B)` parameter
when `B` is a subclass of `A` (a handler for the wider type can stand in for one
of the narrower type), and a function returning a subclass satisfies a slot
expecting the superclass. The reverse directions are rejected.

### Type aliases

Use `declare type` to name a function signature (or any type) for reuse:

```nex
declare type Transformer = Function(n: Integer): Integer
declare type Comparator  = Function(a: Integer, b: Integer): Integer

let double: Transformer := fn (n: Integer): Integer do result := n * 2 end
```

`declare type` can alias any type expression, not just function types:

```nex
declare type IntPair = Array[Integer]
```

### Protocol methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `call0` | none | `Any` | Invocation protocol for nullary function-like values. |
| `call1..call32` | `arg1..argN: Any` | `Any` | Invocation protocol for values that accept up to 32 arguments. |

## `Cursor`

Abstract iteration interface.

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `start` | none | `Void` | Reset iteration to the first position. |
| `item` | none | `Any` | Return current item. |
| `next` | none | `Void` | Advance to next position. |
| `at_end` | none | `Boolean` | Check whether iteration is complete. |

Concrete runtime cursor types are documented in [Cursor Types](cursor-types.md).

## `Comparable` (deferred)

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `compare` | `a: Any` | `Integer` | Return negative/zero/positive ordering result. |

A class that inherits `Comparable` and defines `compare` gets the `<`, `<=`, `>`,
and `>=` operators on its values.

`Comparable` is also the most common *generic bound*. Writing `[T -> Comparable]`
constrains a type parameter, and the body may then use the bound's routines — here,
the ordering operators — on a value of type `T`:

```nex
function largest[T -> Comparable](xs: Array[T], seed: T): T do
  result := seed
  across xs as x do
    if x > result then
      result := x
    end
  end
end

print(largest([3, 9, 4], 0))          -- 9
print(largest(["a", "z", "m"], ""))   -- "z"
```

Any class may serve as a bound, not only the built-in ones; a routine deferred in
the bound dispatches to the runtime subclass.

## `Hashable` (deferred)

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `hash` | none | `Integer` | Return stable hash code for the value. |

## Examples

```nex
let s := "nex"
print(s.compare("next"))          -- negative / 0 / positive
print(s.hash())

let n := 42
print(n.compare(10))               -- positive
print(n.hash())
```

## Operators and the Routines They Denote

Every operator in Nex is shorthand for a routine, so a class earns an operator by
defining the routine behind it.

| Operator | Routine | How a class gets it |
|---|---|---|
| `=` `/=` | `equals` | Override `equals` (and `hash`) — see `Any`, above. |
| `==` `!=` | none | Reference identity; not overridable. |
| `<` `<=` `>` `>=` | `compare` | Inherit `Comparable` and define `compare`. |
| `+` `-` `*` `/` `%` `^` | any one-argument routine | Bind it with an `alias` clause (below). |

### `alias`

A one-argument routine may bind itself to an arithmetic operator with an `alias`
clause. The operator is then exactly sugar for the call, so the routine's
contracts hold at the operator too.

```nex
class Money
  inherit
    Comparable
  feature
    once amount: Integer
    once currency: String

    minus(other: Money): Money
      alias "-"
      require
        same_currency: currency = other.currency
      do
        result := create Money.make(amount - other.amount, currency)
      end

    compare(other: Any): Integer do
      if convert other to m: Money then
        result := amount - m.amount
      else
        raise "Money.compare: not a Money"
      end
    end
  create
    make(a: Integer, c: String) do amount := a  currency := c end
end

let owed := create Money.make(100, "USD")
let paid := create Money.make(30, "USD")
print((owed - paid).amount)          -- 70, via minus
print(owed > paid)                   -- true, via compare
print(owed - create Money.make(5, "EUR"))
                                     -- Precondition violation: same_currency
```

Only `+ - * / % ^` may be aliased — no new operator symbols can be invented — and
only arithmetic: ordering goes through `Comparable` and equality through `equals`,
not through an alias. An alias is never consulted for numeric operands (or, for
`+`, strings), so no class can change what `+` means on `Integer` or `Real`.
Aliases are inherited: a routine aliased in a deferred class gives the operator to
every heir, dispatching to the heir's implementation.

`alias` is a soft keyword: it means this only in the position shown, and remains
usable as an ordinary name for a variable, field, parameter, or routine.

## Class Modifiers

### `once`

A field declared with `once` can be assigned in a constructor but never reassigned afterward. The typechecker enforces this at compile time.

```nex
class Point
  feature
    once x: Integer
    once y: Integer
  create
    make(px: Integer, py: Integer) do
      x := px
      y := py
    end
end
```

Assigning a `once` field outside a constructor is a compile-time error:

```nex
class Box
  feature
    once value: Integer
  create
    make(v: Integer) do value := v end
  feature
    overwrite(v: Integer) do
      value := v     -- error: 'value' is a once field
    end
end
```

### `deferred`

A `deferred` class cannot be instantiated directly. It defines an interface — methods that subclasses are expected to override.

```nex
deferred class Shape
  feature
    area(): Real do end        -- overridden by each subclass
end
```

### `sealed deferred`

A `sealed deferred` class is both abstract and closed: only classes defined alongside it can inherit from it. The typechecker tracks all known subclasses and verifies exhaustive handling in `match` statements.

```nex
sealed deferred class Result
end

class Ok
  inherit Result
  feature value: Integer
  create make(v: Integer) do value := v end
end

class Err
  inherit Result
  feature msg: String
  create make(m: String) do msg := m end
end
```

A `match` on a sealed type must cover every variant or supply an `else` branch — a missing variant is a compile-time error:

```nex
match r of
  when Ok as ok then print(ok.value)
  when Err as err then print(err.msg)
end
```
