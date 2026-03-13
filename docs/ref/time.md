# Time Libraries

This page documents the date/time libraries shipped under `lib/time`.

These are library classes, not core built-in classes. Load them with `intern`.

## Platform Scope

- `time/Date_Time`: JVM interpreter, generated JVM, generated JavaScript/Node
- `time/Duration`: JVM interpreter, generated JVM, generated JavaScript/Node
- Retired browser interpreter path: not supported

## Load

```nex
intern time/Duration
intern time/Date_Time
```

## `Duration`

`Duration` is a time span stored as total milliseconds.

```nex
class Duration
create
  milliseconds(ms: Integer64)
  seconds(n: Integer)
  minutes(n: Integer)
  hours(n: Integer)
  days(n: Integer)
  weeks(n: Integer)
feature
  total_milliseconds(): Integer64
  total_seconds(): Real
  plus(other: Duration): Duration
  minus(other: Duration): Duration
  to_string(): String
end
```

Example:

```nex
intern time/Duration

let a: Duration := create Duration.minutes(5)
let b: Duration := create Duration.seconds(30)
print(a.plus(b).total_seconds())
```

## `Date_Time`

`Date_Time` is a UTC date-time value stored as epoch milliseconds.

```nex
class Date_Time
create
  now()
  from_epoch_millis(ms: Integer64)
  parse_iso(text: String)
  make(year, month, day, hour, minute, second: Integer)
feature
  year(): Integer
  month(): Integer
  month_name(): String
  day(): Integer
  weekday(): Integer
  weekday_name(): String
  day_of_year(): Integer
  hour(): Integer
  minute(): Integer
  second(): Integer
  epoch_millis(): Integer64
  add(duration: Duration): Date_Time
  subtract(duration: Duration): Date_Time
  difference(other: Date_Time): Duration
  truncate_to_day(): Date_Time
  truncate_to_hour(): Date_Time
  is_before(other: Date_Time): Boolean
  is_after(other: Date_Time): Boolean
  format_iso(): String
  to_string(): String
end
```

Notes:
- values are interpreted in UTC
- `format_iso()` returns an ISO-8601 string
- `difference(other)` returns `this - other`
- `weekday()` uses ISO numbering: `1 = Monday` through `7 = Sunday`
- truncation methods keep the result in UTC

Example:

```nex
intern time/Duration
intern time/Date_Time

let start: Date_Time := create Date_Time.make(2026, 3, 13, 10, 30, 0)
let later: Date_Time := start.add(create Duration.hours(2))

print(start.format_iso())
print(start.month_name())
print(start.weekday())
print(start.weekday_name())
print(start.day_of_year())
print(start.truncate_to_day().format_iso())
print(start.truncate_to_hour().format_iso())
print(later.hour())
print(later.difference(start).total_seconds())
```
