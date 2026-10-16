# Time Libraries

This page documents the date/time libraries shipped under `lib/time`. These are library classes, not core built-in classes. Load them with `intern`.

## `time/Duration`

`Duration` is a time span stored as a whole number of milliseconds, shipped as a Nex library under `lib/time/duration.nex`. It is an immutable value: arithmetic returns a new `Duration`.

### Loading

```nex
intern time/Duration
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Construction

```nex
let timeout: Duration := create Duration.seconds(30)
let sprint: Duration := create Duration.weeks(2)
```

Each constructor names its unit. Durations may be negative.

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `milliseconds` | `ms: Integer` | `Duration` | A span of `ms` milliseconds. |
| `seconds` | `n: Integer` | `Duration` | A span of `n` seconds. |
| `minutes` | `n: Integer` | `Duration` | A span of `n` minutes. |
| `hours` | `n: Integer` | `Duration` | A span of `n` hours. |
| `days` | `n: Integer` | `Duration` | A span of `n` 24-hour days. |
| `weeks` | `n: Integer` | `Duration` | A span of `n` 7-day weeks. |
| `total_milliseconds` | none | `Integer` | The span in milliseconds. |
| `total_seconds` | none | `Real` | The span in seconds, including the fractional part. |
| `plus` | `other: Duration` | `Duration` | The sum of the two spans. |
| `minus` | `other: Duration` | `Duration` | This span minus `other`; may be negative. |
| `to_string` | none | `String` | A descriptive string such as `"Duration(300000 ms)"`. |

### Example

```nex
intern time/Duration

let a: Duration := create Duration.minutes(5)
let b: Duration := create Duration.seconds(30)

print(a.plus(b).total_seconds())        -- 330.0
print(a.minus(b).total_milliseconds())  -- 270000
print(create Duration.days(1).total_milliseconds())  -- 86400000
print(a)                                -- Duration(300000 ms)
```

### Notes

- Day and week durations are fixed multiples of 24 hours; they do not account for calendar effects.
- There is no month or year duration, since those vary in length.

## `time/Date_Time`

`Date_Time` is a UTC instant stored as milliseconds since the Unix epoch, shipped as a Nex library under `lib/time/date_time.nex`. It is an immutable value: `add`, `subtract` and the truncation methods return a new `Date_Time`.

### Loading

```nex
intern time/Date_Time
```

### Support

| Target | Supported |
|---|---|
| JVM REPL / interpreter | Yes |
| Generated JVM code | Yes |

### Construction

```nex
let now: Date_Time := create Date_Time.now()
let launch: Date_Time := create Date_Time.make(2026, 3, 13, 10, 30, 0)
let parsed: Date_Time := create Date_Time.parse_iso("2026-03-13T10:30:00Z")
let epoch: Date_Time := create Date_Time.from_epoch_millis(0)
```

`intern time/Date_Time` also loads `time/Duration`.

### Parsing

`parse_iso` accepts:

- A UTC instant with a `Z` suffix: `"2026-03-13T10:30:00Z"`.
- A date-time with an offset, converted to UTC: `"2026-03-13T12:30:00+02:00"`.
- A local date-time with no zone, interpreted as UTC: `"2026-03-13T10:30:00"`.

### Methods

| Method | Arguments | Returns | Description |
|---|---|---|---|
| `now` | none | `Date_Time` | The current instant. |
| `from_epoch_millis` | `ms: Integer` | `Date_Time` | The instant `ms` milliseconds after 1970-01-01T00:00:00Z. |
| `parse_iso` | `text: String` | `Date_Time` | Parse an ISO-8601 date-time (see Parsing). Raises on malformed input. |
| `make` | `year, month, day, hour, minute, second: Integer` | `Date_Time` | The given UTC calendar date and time. Raises when a field is out of range. |
| `year` | none | `Integer` | The calendar year. |
| `month` | none | `Integer` | The month, `1` (January) to `12`. |
| `month_name` | none | `String` | The English month name, e.g. `"March"`. |
| `day` | none | `Integer` | The day of the month, `1` to `31`. |
| `weekday` | none | `Integer` | The ISO day of the week: `1` = Monday through `7` = Sunday. |
| `weekday_name` | none | `String` | The English day name, e.g. `"Friday"`. |
| `day_of_year` | none | `Integer` | The day of the year, `1` to `366`. |
| `hour` | none | `Integer` | The hour, `0` to `23`. |
| `minute` | none | `Integer` | The minute, `0` to `59`. |
| `second` | none | `Integer` | The second, `0` to `59`. |
| `epoch_millis` | none | `Integer` | Milliseconds since the Unix epoch. |
| `add` | `duration: Duration` | `Date_Time` | The instant `duration` later. |
| `subtract` | `duration: Duration` | `Date_Time` | The instant `duration` earlier. |
| `difference` | `other: Date_Time` | `Duration` | `this - other`: positive when this instant is later. |
| `truncate_to_day` | none | `Date_Time` | Midnight UTC at the start of the same day. |
| `truncate_to_hour` | none | `Date_Time` | The start of the same hour. |
| `is_before` | `other: Date_Time` | `Boolean` | True when this instant is strictly earlier than `other`. |
| `is_after` | `other: Date_Time` | `Boolean` | True when this instant is strictly later than `other`. |
| `format_iso` | none | `String` | ISO-8601 UTC text such as `"2026-03-13T10:30:00Z"`. |
| `to_string` | none | `String` | Same as `format_iso()`. |

### Example

```nex
intern time/Duration
intern time/Date_Time

let start: Date_Time := create Date_Time.make(2026, 3, 13, 10, 30, 0)
let later: Date_Time := start.add(create Duration.hours(2))

print(start.format_iso())                     -- "2026-03-13T10:30:00Z"
print(start.month_name())                     -- "March"
print(start.weekday())                        -- 5
print(start.weekday_name())                   -- "Friday"
print(start.day_of_year())                    -- 72
print(start.truncate_to_day().format_iso())   -- "2026-03-13T00:00:00Z"
print(start.truncate_to_hour().format_iso())  -- "2026-03-13T10:00:00Z"
print(later.hour())                           -- 12
print(later.difference(start).total_seconds())  -- 7200.0
print(start.is_before(later))                 -- true

let local: Date_Time := create Date_Time.parse_iso("2026-03-13T12:30:00+02:00")
print(local.format_iso())                     -- "2026-03-13T10:30:00Z"
```

### Notes

- All calendar fields are computed in UTC; there is no local time zone support.
- `format_iso()` includes fractional seconds only when they are non-zero, e.g. `"1970-01-01T00:00:01.500Z"`.
- Comparison uses `is_before` / `is_after` or `epoch_millis()`; equal instants are neither before nor after each other.
