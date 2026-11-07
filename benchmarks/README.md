# Benchmarks

The same small programs in Nex, Java, Python and Clojure, and a script that
runs them all and compares them, with Java as the baseline.

```bash
python3 benchmarks/run.py                     # everything, 3 runs each
python3 benchmarks/run.py --only fib,loop     # some benchmarks
python3 benchmarks/run.py --langs nex,java    # some languages
python3 benchmarks/run.py --nex bin/nex       # this checkout instead of the installed nex
python3 benchmarks/run.py --runs 5 --markdown # more runs, tables as Markdown (as below)
python3 benchmarks/run.py --baseline python   # ratios relative to another language
python3 benchmarks/run.py --list              # what each benchmark does
```

## Results

Each number is a language's time as a multiple of Java's: **1.0x** is as fast
as Java, 2.0x takes twice as long, 0.5x half as long. The fastest language in
each row is in bold. Measured on 2026-11-07, 5 runs each (median), on an Intel
Core i5-1135G7 (4 cores, 8 threads, 15 GB RAM) running Linux Mint 22.2, with
Nex 0.7.0, OpenJDK 25.0.4.1, Python 3.12.3 and Clojure 1.12.4.

### The work itself

Each program times its own work with its language's monotonic clock, so these
leave out starting the language and compiling the program. They include the
JIT compiler warming up, which is part of any program's run. The time in
parentheses is the work's own time.

| benchmark | nex | java | python | clojure |
|---|--:|--:|--:|--:|
| fib | 3.4x (119 ms) | **1.0x (36 ms)** | 30x (1.06 s) | 1.4x (51 ms) |
| loop | 1.3x (196 ms) | **1.0x (154 ms)** | 22x (3.40 s) | 1.1x (173 ms) |
| sort | **0.9x (765 ms)** | 1.0x (866 ms) | 6.3x (5.48 s) | 1.0x (881 ms) |
| sort_natural | **1.0x (828 ms)** | 1.0x (850 ms) | 1.8x (1.54 s) | 1.0x (890 ms) |
| map | 2.1x (298 ms) | **1.0x (143 ms)** | 2.2x (308 ms) | 3.2x (461 ms) |
| concat | 2.8x (223 ms) | **1.0x (80 ms)** | 7.8x (628 ms) | 5.1x (408 ms) |
| call_function | 1.3x (87 ms) | **1.0x (65 ms)** | 22x (1.42 s) | 1.2x (78 ms) |
| call_method | 1.6x (103 ms) | **1.0x (64 ms)** | 29x (1.85 s) | 1.3x (85 ms) |
| call_poly2 | 1.6x (130 ms) | **1.0x (82 ms)** | 29x (2.36 s) | 1.6x (133 ms) |
| call_poly4 | 1.5x (179 ms) | **1.0x (121 ms)** | 20x (2.40 s) | 3.6x (432 ms) |
| call_closure | 1.4x (106 ms) | **1.0x (78 ms)** | 20x (1.57 s) | 1.2x (90 ms) |

- **Nex runs within 0.9–1.6x of Java** on loops, sorting, and function,
  method, polymorphic and closure calls. On those it is 7–30x faster than
  Python, except natural-order sorting, where Python's built-in sort keeps it
  to 1.8x.
- **Its weakest spots** are `fib` (3.4x), whose deep recursion is slower to
  warm up than Java's, and building strings (`concat`, 2.8x; `map`, 2.1x,
  since its keys are built with `+`). It is level with Python on `map` and
  nearly 3x faster on `concat`.
- **Against Clojure**, Nex is within about 0.3x on most benchmarks, faster on
  strings, maps and polymorphic calls over four classes, and slower on `fib`.

### The whole run

A fresh process each time, timed from start to exit, so this includes
starting the language. That makes it what you wait for when you run a short
script.

| benchmark | nex | java | python | clojure |
|---|--:|--:|--:|--:|
| startup | 35x (635 ms) | 1.0x (18 ms) | **0.5x (10 ms)** | 42x (759 ms) |
| fib | 14x | **1.0x** | 19x | 14x |
| loop | 4.9x | **1.0x** | 20x | 5.2x |
| sort | 1.7x | **1.0x** | 6.1x | 1.9x |
| sort_natural | 1.8x | **1.0x** | 1.8x | 1.9x |
| map | 5.7x | **1.0x** | 1.8x | 7.3x |
| concat | 9.1x | **1.0x** | 6.0x | 11x |
| call_function | 9.3x | **1.0x** | 17x | 10x |
| call_method | 10.0x | **1.0x** | 22x | 10.0x |
| call_poly2 | 8.7x | **1.0x** | 23x | 8.9x |
| call_poly4 | 6.2x | **1.0x** | 17x | 8.3x |
| call_closure | 7.9x | **1.0x** | 16x | 8.5x |

Nex and Clojure each start a JVM and load a runtime before running anything,
which takes about 0.6–0.8 s here against 10–20 ms for Python and Java. For
these short programs that startup is most of the whole run, so the ratios
here are much larger than for the work itself. The longer a program runs, the
closer its whole-run ratio gets to the work ratio above.

## How times are measured

Every run is a fresh process. The script records two times for each:

- **The whole run:** from starting the process to its exit, measured by the
  script.
- **The work:** each program reads its language's monotonic clock just before
  and after its work, and prints the difference in microseconds after its
  result. That leaves out startup and compilation. Nex reads the JVM's
  `System.nanoTime` through `with "java"`, as Java and Clojure do; Python uses
  `time.perf_counter_ns`.

The tables show the median of the runs, 3 by default (`--runs`).

Each program prints a result computed from all its work, so no language can
skip any of it, and the script checks that every language prints the same
one. A cell marked `differs` means a program is not doing the same work as
the others.

Run on an otherwise idle machine: the script warns when the load average is
above half the number of CPUs. JVM languages suffer most from a busy machine,
since their JIT compiler needs CPU time of its own.

The script needs Python 3. A language whose tools are missing (`nex`, `java`
and `javac`, `clojure`) is skipped. It uses the `nex` on your `PATH` unless
you pass `--nex` or set `NEX`.

## The benchmarks

| Name | What it measures |
|---|---|
| `startup` | An empty program: the cost of starting the language |
| `fib` | Recursive function calls: `fib(35)` |
| `loop` | Integer arithmetic: 50M iterations of `*`, `+` and `%` |
| `sort` | Sorting 2M integers with a comparator function |
| `sort_natural` | Sorting 2M integers by natural order |
| `map` | A string-keyed map: 500k keys built with `+`, put, then read back |
| `concat` | String concatenation: 5M strings from a literal and a number |
| `call_function` | 20M calls of a free function |
| `call_method` | 20M method calls on one object |
| `call_poly2` | 20M calls of a deferred method over objects of 2 classes |
| `call_poly4` | The same over 4 classes, past what the JVM inlines |
| `call_closure` | 20M calls of a closure (`fn` value) |

## The programs

- `nex/<name>.nex`: one file per benchmark.
- `python/<name>.py`: one file per benchmark, with the work inside a function,
  where Python's local variables are fastest.
- `java/Bench.java`: all of them, chosen by name (`java Bench fib`).
- `clojure/bench.clj`: all of them, chosen by name, written as
  performance-minded Clojure usually is (type hints, `loop`/`recur` on
  primitive longs, protocols and records, a transient map). Its arithmetic,
  like Nex's, is checked for overflow.

To add a benchmark, write it in each language, printing the same result and
then its work time in microseconds, and add its name to `BENCHMARKS` in
`run.py`.
