# Scalar Types

Built-in scalar classes: `String`, `Integer`, `Real`, `Boolean`, `Char`.

All scalar classes are modeled as inheriting `Any` and implementing
`Comparable` and `Hashable`.

## `String`

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `length` | none | `Integer` | Number of characters. |
| `index_of` | `ch: String` | `Integer` | First index of `ch`, or `-1`. |
| `substring` | `start: Integer, end: Integer` | `String` | Slice from `start` (inclusive) to `end` (exclusive). |
| `to_upper` | none | `String` | Uppercase copy. |
| `to_lower` | none | `String` | Lowercase copy. |
| `to_integer` | none | `Integer` | Parse integer value. |
| `to_real` | none | `Real` | Parse floating-point value. |
| `contains` | `substr: String` | `Boolean` | True if substring exists. |
| `starts_with` | `prefix: String` | `Boolean` | True if string starts with prefix. |
| `ends_with` | `suffix: String` | `Boolean` | True if string ends with suffix. |
| `trim` | none | `String` | Remove leading/trailing whitespace. |
| `replace` | `old: String, new: String` | `String` | Replace all occurrences of `old`. |
| `char_at` | `idx: Integer` | `Char` | Character at index. |
| `chars` | none | `Array[Char]` | New array of characters in string order. |
| `to_bytes` | none | `Array[Integer]` | UTF-8 bytes of the string. |
| `split` | `delim: String` | `Array[String]` | Split into an array. |
| `plus` | `other: Any` | `String` | Concatenate with `other`. |
| `equals` | `other: Any` | `Boolean` | Equality check. |
| `not_equals` | `other: Any` | `Boolean` | Inequality check. |
| `less_than` | `other: String` | `Boolean` | Lexicographic `<`. |
| `less_than_or_equal` | `other: String` | `Boolean` | Lexicographic `<=`. |
| `greater_than` | `other: String` | `Boolean` | Lexicographic `>`. |
| `greater_than_or_equal` | `other: String` | `Boolean` | Lexicographic `>=`. |
| `compare` | `other: Any` | `Integer` | Ordering as integer result. |
| `hash` | none | `Integer` | Hash code. |
| `cursor` | none | `StringCursor` | Create character iterator. |

Laws:

- `s.chars().length = s.length`
- `s.chars().get(i) = s.char_at(i)` for every valid `i`

Examples:

```nex
let xs: Array[Char] := "cat".chars()
print(xs)        -- [#c, #a, #t]
print(xs.length) -- 3
print(xs.get(1)) -- #a
```

`to_bytes()` uses UTF-8 encoding and returns an `Array[Integer]` with byte values in `0..255`.

```nex
let bytes: Array[Integer] := "cat".to_bytes()
print(bytes) -- [99, 97, 116]
```

## `Integer`

`Integer` is a signed 64-bit integer (range `-2^63 .. 2^63-1`) on every backend.
Arithmetic is *checked*: `+`, `-`, `*`, unary `-`, and `^` raise on overflow, and
`/` and `%` raise on a zero divisor.

Bitwise operations use 32-bit integer semantics. Bit index `0` is the least-significant
bit. For method calls on integer literals, wrap the literal in parentheses:
`(5).bitwise_left_shift(1)`.

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `to_string` | none | `String` | Convert to string. |
| `abs` | none | `Integer` | Absolute value. |
| `min` | `other: Integer` | `Integer` | Smaller of two values. |
| `max` | `other: Integer` | `Integer` | Larger of two values. |
| `pick` | none | `Integer` | Random integer in `[0, self)`. |
| `bitwise_left_shift` | `n: Integer` | `Integer` | Left-shift by `n` bit positions. |
| `bitwise_right_shift` | `n: Integer` | `Integer` | Arithmetic right-shift by `n` bit positions. |
| `bitwise_logical_right_shift` | `n: Integer` | `Integer` | Logical right-shift by `n` bit positions. |
| `bitwise_rotate_left` | `n: Integer` | `Integer` | Rotate bits left by `n` positions. |
| `bitwise_rotate_right` | `n: Integer` | `Integer` | Rotate bits right by `n` positions. |
| `bitwise_is_set` | `n: Integer` | `Boolean` | True if bit `n` is set. |
| `bitwise_set` | `n: Integer` | `Integer` | Return value with bit `n` set to `1`. |
| `bitwise_unset` | `n: Integer` | `Integer` | Return value with bit `n` cleared to `0`. |
| `bitwise_and` | `other: Integer` | `Integer` | Bitwise AND. |
| `bitwise_or` | `other: Integer` | `Integer` | Bitwise OR. |
| `bitwise_xor` | `other: Integer` | `Integer` | Bitwise XOR. |
| `bitwise_not` | none | `Integer` | Bitwise complement. |
| `plus` | `other: Integer` | `Integer` | Addition. |
| `minus` | `other: Integer` | `Integer` | Subtraction. |
| `times` | `other: Integer` | `Integer` | Multiplication. |
| `divided_by` | `other: Integer` | `Real` | Division. |
| `equals` | `other: Any` | `Boolean` | Equality check. |
| `not_equals` | `other: Any` | `Boolean` | Inequality check. |
| `less_than` | `other: Integer` | `Boolean` | Numeric `<`. |
| `less_than_or_equal` | `other: Integer` | `Boolean` | Numeric `<=`. |
| `greater_than` | `other: Integer` | `Boolean` | Numeric `>`. |
| `greater_than_or_equal` | `other: Integer` | `Boolean` | Numeric `>=`. |
| `compare` | `other: Any` | `Integer` | Ordering as integer result. |
| `hash` | none | `Integer` | Hash code. |

## `Real`

Real literals must include at least one digit after the decimal point. Valid
forms include `3.14`, `10.0`, `.5`, and `12.0e-3`. Forms such as `10.` and
`12.e-3` are not valid real literals.

`Real` is an IEEE-754 double on every backend, in representation *and* arithmetic.
Division by zero follows IEEE rather than raising: `1.0 / 0.0` is `Infinity`,
`-1.0 / 0.0` is `-Infinity`, and `0.0 / 0.0` is `NaN`. (Integer division by zero,
by contrast, raises.)

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `to_string` | none | `String` | Convert to string. |
| `abs` | none | `Real` | Absolute value. |
| `min` | `other: Real` | `Real` | Smaller of two values. |
| `max` | `other: Real` | `Real` | Larger of two values. |
| `round` | none | `Integer` | Round to nearest integer. |
| `to_fixed` | `places: Integer` | `Real` | Round to `places` decimal places. |
| `is_nan` | none | `Boolean` | True if the value is `NaN`. |
| `is_infinite` | none | `Boolean` | True if the value is `±Infinity`. |
| `is_finite` | none | `Boolean` | True if the value is neither `NaN` nor infinite. |
| `plus` | `other: Real` | `Real` | Addition. |
| `minus` | `other: Real` | `Real` | Subtraction. |
| `times` | `other: Real` | `Real` | Multiplication. |
| `divided_by` | `other: Real` | `Real` | Division. |
| `equals` | `other: Any` | `Boolean` | Equality check. |
| `not_equals` | `other: Any` | `Boolean` | Inequality check. |
| `less_than` | `other: Real` | `Boolean` | Numeric `<`. |
| `less_than_or_equal` | `other: Real` | `Boolean` | Numeric `<=`. |
| `greater_than` | `other: Real` | `Boolean` | Numeric `>`. |
| `greater_than_or_equal` | `other: Real` | `Boolean` | Numeric `>=`. |
| `compare` | `other: Any` | `Integer` | Ordering as integer result. |
| `hash` | none | `Integer` | Hash code. |

## `Boolean`

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `to_string` | none | `String` | Convert to string. |
| `and` | `other: Boolean` | `Boolean` | Logical conjunction. |
| `or` | `other: Boolean` | `Boolean` | Logical disjunction. |
| `not` | none | `Boolean` | Logical negation. |
| `equals` | `other: Any` | `Boolean` | Equality check. |
| `not_equals` | `other: Any` | `Boolean` | Inequality check. |
| `compare` | `other: Any` | `Integer` | Ordering as integer result. |
| `hash` | none | `Integer` | Hash code. |

## `Char`

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `to_string` | none | `String` | Convert to one-character string. |
| `to_upper` | none | `Char` | Uppercase character. |
| `to_lower` | none | `Char` | Lowercase character. |
| `compare` | `other: Any` | `Integer` | Ordering as integer result. |
| `hash` | none | `Integer` | Hash code. |

## Examples

```nex
let s := "  Nex  "
print(s.trim().to_upper())        -- "NEX"
print(s.contains("ex"))           -- true

let n: Integer := 7
print(n.plus(5))                  -- 12
print(n.pick())                   -- random integer in [0, 7)
print(5.bitwise_left_shift(1))    -- 10
print(6.bitwise_and(3))           -- 2

let r: Real := 3.6
print(r.round())                  -- 4

let b: Boolean := true
print(b.and(false))               -- false
```
