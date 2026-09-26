# Scalar Types

Built-in scalar classes: `String`, `Integer`, `Integer16`, `Integer32`, `Byte`, `Real`,
`Boolean`, `Char`. `Integer64` is another spelling of `Integer`.

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
`Integer64` is an alias: it names exactly the same type, so the two are interchangeable
everywhere (`let n: Integer64 := 5` and `let m: Integer := n` both typecheck). The
fixed-width siblings are `Integer16`, `Integer32` and `Byte`, below.
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
| `to_integer16` | none | `Integer16` | Convert to an `Integer16`; raises unless the value is in `-32768..32767`. |
| `to_integer32` | none | `Integer32` | Convert to an `Integer32`; raises unless the value is in `-2147483648..2147483647`. |
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
there is no implicit conversion in either direction.

- **Byte literals:** an integer literal with a `u8` suffix is a `Byte`: `12u8`, `0xFFu8`,
  `0b1010u8`, `0o17u8`, `1_0u8`. The value must be in `0..255`, and is checked when the
  program is parsed. (The suffix is `u8`, not `b`, because `b` is a hex digit.) An
  unsuffixed literal is always an `Integer`, so `let b: Byte := 65` is a type error;
  write `65u8`. There are no negative byte literals: `-5u8` is the `Integer` `-5`.
- **Making a `Byte` from a value:** `n.to_byte()` on an `Integer` (raises unless
  `0 <= n <= 255`), or reading one out of an `Array[Byte]`.
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
| `to_integer16` | none | `Integer16` | Widen to an `Integer16`. |
| `to_integer32` | none | `Integer32` | Widen to an `Integer32`. |
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
let magic: Array[Byte] := [0x89u8, 0x50u8, 0x4Eu8, 0x47u8]
print(bytes)                       -- [195, 169]
let b: Byte := bytes.get(0)
print(b.to_hex())                  -- "c3"
print(b.bitwise_not())             -- 60
let total: Integer := b + bytes.get(1)
print(total)                       -- 364
let narrowed: Byte := (total - 200).to_byte()
print(narrowed)                    -- 164
```

## `Integer16` and `Integer32`

`Integer16` and `Integer32` are signed fixed-width integers: `-32768..32767` and
`-2147483648..2147483647`. They are for binary formats, protocols and interop where a
value has an exact width. Like `Byte`, each is a distinct type related to `Integer` the
way `Integer` is related to `Real`.

- **No implicit conversion.** None of `Integer`, `Integer16`, `Integer32` and `Byte`
  converts to another without a call, in either direction. `let b: Integer32 := a` where
  `a` is an `Integer16` is a type error; write `a.to_integer32()`.
- **Literals.** An integer literal with an `i16` or `i32` suffix: `300i16`, `0xFFFFi32`,
  `1_000i32`. A negative one is written with a leading `-` and is a single literal of
  that type: `-5i16` is an `Integer16`, and `-32768i16` is valid. (Elsewhere `-x` on a
  sized variable promotes to `Integer`, like any arithmetic.) An unsuffixed literal is
  an `Integer`, so `let a: Integer16 := 5` is a type error; write `5i16`. A literal
  outside the type's range is rejected.
- **Conversions.** Narrowing is checked: `n.to_integer16()`, `n.to_integer32()` and
  `n.to_byte()` raise unless the value fits. Widening (`Byte` to `Integer16`/`Integer32`,
  `Integer16` to `Integer32`) also uses these methods and always succeeds. `x.to_integer()`
  gives the `Integer`.
- **Arithmetic** (`+ - * / % ^`, unary `-` on a variable) promotes to `Integer`, so the
  result of `a + a` is an `Integer` and cannot overflow the narrow type; narrow it back
  with `(a + a).to_integer16()`. Mixing with `Real` promotes to `Real`.
- **Comparison** (`= /= < <= > >=`) is between two values of the same type. Comparing
  across types needs a conversion first. `a.equals(x)` is true only when `x` has the same
  type and value.
- **`convert`** does not change representation, so `convert n to a: Integer16` on an
  `Integer` is rejected. A value keeps its type inside an `Any`: `type_of` reports
  `"Integer16"`, and `convert x to a: Integer16` succeeds only for one.
- **Bitwise methods** work on, and wrap to, the type's own width, and return the same
  type: `0i16.bitwise_not()` is `-1i16`, and `32767i16.bitwise_left_shift(1)` is `-2i16`.
  Right shift copies the sign bit; `bitwise_logical_right_shift` shifts in zeros. Bit
  indexes must be `0..15` (`Integer16`) or `0..31` (`Integer32`); shift counts must be
  non-negative, and shifting by the width or more clears every bit (except an arithmetic
  right shift of a negative value, which gives `-1`). `abs` raises for the
  minimum value, which has no positive counterpart. (`Integer`'s own bitwise methods
  keep their 32-bit behaviour.)
- **Java interop:** passed to a reflective Java call, an `Integer16` or `Integer32` goes
  as an integer, so it can be used wherever an `Integer` can.

Both types have the same methods. `T` below is the type itself.

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `to_string` | none | `String` | Base-10 text. |
| `to_string` | `base: Integer` | `String` | Text in `base` (`2`, `8`, `10`, or `16`; any other value raises). A negative value is rendered with a leading `-` and its magnitude. |
| `to_integer` | none | `Integer` | The value as an `Integer`. |
| `to_byte` | none | `Byte` | Raises unless the value is in `0..255`. |
| `to_integer16` | none | `Integer16` | Raises unless the value fits. |
| `to_integer32` | none | `Integer32` | Raises unless the value fits. |
| `abs` | none | `T` | Absolute value; raises for the minimum value. |
| `min` | `other: T` | `T` | Smaller of two values. |
| `max` | `other: T` | `T` | Larger of two values. |
| `bitwise_left_shift` | `n: Integer` | `T` | Left-shift, dropping bits shifted out. |
| `bitwise_right_shift` | `n: Integer` | `T` | Arithmetic right-shift (the sign bit is copied). |
| `bitwise_logical_right_shift` | `n: Integer` | `T` | Right-shift, shifting in zeros. |
| `bitwise_rotate_left` | `n: Integer` | `T` | Rotate bits left within the width. |
| `bitwise_rotate_right` | `n: Integer` | `T` | Rotate bits right within the width. |
| `bitwise_is_set` | `n: Integer` | `Boolean` | True if bit `n` is set. |
| `bitwise_set` | `n: Integer` | `T` | Value with bit `n` set to `1`. |
| `bitwise_unset` | `n: Integer` | `T` | Value with bit `n` cleared to `0`. |
| `bitwise_and` | `x: T` | `T` | Bitwise AND. |
| `bitwise_or` | `x: T` | `T` | Bitwise OR. |
| `bitwise_xor` | `x: T` | `T` | Bitwise XOR. |
| `bitwise_not` | none | `T` | Bitwise complement. |
| `equals` | `other: Any` | `Boolean` | True only for the same type and value. |
| `not_equals` | `other: Any` | `Boolean` | Inequality check. |
| `compare` | `other: Any` | `Integer` | Ordering as integer result. |
| `hash` | none | `Integer` | Hash code. |

```nex
let port: Integer16 := 8080i16
let offset: Integer32 := -70000i32
print(port.to_string(16))            -- "1f90"
print(offset.abs())                  -- 70000
print(port.bitwise_left_shift(4))    -- -1792 (129280 wrapped to 16 bits)
let wide: Integer := port + offset   -- arithmetic promotes to Integer
print(wide)                          -- -61920
let back: Integer16 := (wide + 61920 + 100).to_integer16()
print(back)                          -- 100
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
