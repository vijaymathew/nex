# Scalar Types

Built-in scalar classes: `String`, `Integer`, `Byte`, `Real`, `Boolean`, `Char`.

All scalar classes are modeled as inheriting `Any` and implementing
`Comparable` and `Hashable`.

## `String`

A double-quoted string interprets backslash escapes: `\n` (newline), `\t` (tab),
`\r` (carriage return), `\0` (null), `\\` (backslash), `\"` (double quote), and
`\u{H...}` (a Unicode code point in hex); any other escape is an error. A
single-quoted string is raw — every character, backslash included, is literal —
which is convenient for regular-expression patterns and paths: `'\d+'`, `'C:\dir'`.

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
| `to_bytes` | none | `Array[Byte]` | UTF-8 bytes of the string. |
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

`to_bytes()` uses UTF-8 encoding and returns an `Array[Byte]` with byte values in `0..255`.

```nex
let bytes: Array[Byte] := "cat".to_bytes()
print(bytes) -- [99, 97, 116]
```

`create String.from_bytes(bytes)` is the inverse: it decodes an `Array[Byte]` as UTF-8
and returns the `String`. It raises if the bytes are not valid UTF-8, rather than
substituting replacement characters, so `create String.from_bytes(s.to_bytes())` always
gives back `s` exactly.

```nex
let bytes: Array[Byte] := "héllo".to_bytes()
let text: String := create String.from_bytes(bytes)
print(text) -- "héllo"
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
| `to_string` | none | `String` | Convert to string (base 10). |
| `to_string` | `base: Integer` | `String` | Convert to string in `base` (`2`, `8`, `10`, or `16`; any other value raises). A negative value is rendered with a leading `-` followed by its unsigned magnitude in that base. |
| `abs` | none | `Integer` | Absolute value. |
| `min` | `other: Integer` | `Integer` | Smaller of two values. |
| `max` | `other: Integer` | `Integer` | Larger of two values. |
| `pick` | none | `Integer` | Random integer in `[0, self)`. |
| `to_byte` | none | `Byte` | Convert to a `Byte`; raises unless the value is in `0..255`. |
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

```nex
let n := 113
print(n.to_string(2))  -- "1110001"
print(n.to_string(16)) -- "71"
```

## `Byte`

`Byte` is an unsigned 8-bit integer (range `0..255`). It is the element type of
`String.to_bytes()` and of binary file I/O (`Array[Byte]`).

`Byte` is a distinct type, related to `Integer` the way `Integer` is related to `Real`:
there is no implicit conversion in either direction, and there is no `Byte` literal.

- **Making a `Byte`:** `n.to_byte()` on an `Integer` (raises unless `0 <= n <= 255`), or
  by reading one out of an `Array[Byte]`. An integer literal is an `Integer` even where a
  `Byte` is expected, so `let b: Byte := 65` is a type error; write `(65).to_byte()`.
- **Getting an `Integer`:** `b.to_integer()`. Assigning a `Byte` to an `Integer` variable
  or passing it to an `Integer` parameter is a type error.
- **Arithmetic** (`+ - * / % ^`, unary `-`) promotes to `Integer`, so the result of
  `b + b` is an `Integer` and cannot overflow a `Byte`; narrow it back with
  `(b + b).to_byte()`. Mixing with `Real` promotes to `Real`.
- **Comparison** (`= /= < <= > >=`) is between two `Byte`s. Comparing a `Byte` with an
  `Integer` needs `b.to_integer()` first, just as comparing an `Integer` with a `Real`
  needs a conversion. `b.equals(x)` is true only when `x` is a `Byte` of the same value.
- **`convert`** does not change representation, so `convert n to b: Byte` on an
  `Integer` is rejected; use `to_byte()`. A `Byte` held in an `Any` keeps its type:
  `type_of` reports `"Byte"` and `convert a to b: Byte` succeeds.
- **Bitwise methods** work on 8 bits and return a `Byte`, unlike `Integer`'s 32-bit
  bitwise operations: `(195).to_byte().bitwise_not()` is `60`. Bit indexes for
  `bitwise_is_set`, `bitwise_set` and `bitwise_unset` must be in `0..7`; shift counts
  must be non-negative, and shifting by 8 or more clears every bit.
- **Java interop:** a `Byte` passed to a Java `int` or `long` parameter is passed as an
  integer. Passed to a Java `byte` parameter, it is the same 8 bits read as signed, so
  `200` arrives as `-56`.

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `to_string` | none | `String` | Base-10 text. |
| `to_string` | `base: Integer` | `String` | Text in `base` (`2`, `8`, `10`, or `16`; any other value raises). |
| `to_integer` | none | `Integer` | The value, `0..255`. |
| `to_char` | none | `Char` | The character with this code point. |
| `to_hex` | none | `String` | Two lowercase hex digits, e.g. `"0a"`. |
| `min` | `other: Byte` | `Byte` | Smaller of two values. |
| `max` | `other: Byte` | `Byte` | Larger of two values. |
| `bitwise_left_shift` | `n: Integer` | `Byte` | Left-shift, dropping bits shifted out of the top. |
| `bitwise_right_shift` | `n: Integer` | `Byte` | Right-shift. |
| `bitwise_logical_right_shift` | `n: Integer` | `Byte` | Same as `bitwise_right_shift`; a `Byte` is never negative. |
| `bitwise_rotate_left` | `n: Integer` | `Byte` | Rotate bits left within 8 bits. |
| `bitwise_rotate_right` | `n: Integer` | `Byte` | Rotate bits right within 8 bits. |
| `bitwise_is_set` | `n: Integer` | `Boolean` | True if bit `n` (`0..7`) is set. |
| `bitwise_set` | `n: Integer` | `Byte` | Value with bit `n` set to `1`. |
| `bitwise_unset` | `n: Integer` | `Byte` | Value with bit `n` cleared to `0`. |
| `bitwise_and` | `x: Byte` | `Byte` | Bitwise AND. |
| `bitwise_or` | `x: Byte` | `Byte` | Bitwise OR. |
| `bitwise_xor` | `x: Byte` | `Byte` | Bitwise XOR. |
| `bitwise_not` | none | `Byte` | Complement within 8 bits (`255 - self`). |
| `equals` | `other: Any` | `Boolean` | True only for a `Byte` of the same value. |
| `not_equals` | `other: Any` | `Boolean` | Inequality check. |
| `compare` | `other: Any` | `Integer` | Ordering as integer result. |
| `hash` | none | `Integer` | Hash code. |

```nex
let bytes: Array[Byte] := "é".to_bytes()
print(bytes)                       -- [195, 169]
let b: Byte := bytes.get(0)
print(b.to_hex())                  -- "c3"
print(b.bitwise_not())             -- 60
let total: Integer := b + bytes.get(1)
print(total)                       -- 364
let narrowed: Byte := (total - 200).to_byte()
print(narrowed)                    -- 164
```

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

A `Char` literal is `#` followed by either a letter/symbol (`#a`, `#Z`, `#!`), one of the named controls `#nul`, `#space`, `#newline`, `#tab`, `#return`, or a run of digits — but that digit form is a **Unicode code point**, not the digit character itself: `#65` is `#A` (ASCII 65), and `#c` is the letter `c`. This means `#0` through `#9` name the ASCII *control* codes 0–9 (`#9` is a tab, `#0` is NUL), not the glyphs `'0'`–`'9'` — there is no bare literal for a digit glyph; write its code point instead (`#48` for `'0'` through `#57` for `'9'`), or index into a string (`"0123456789".char_at(9)`).

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
