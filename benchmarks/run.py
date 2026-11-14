#!/usr/bin/env python3
"""Run the Nex benchmarks against Java, Python and Clojure.

Each benchmark is the same program in every language (benchmarks/<lang>/),
printing its result and then its work's own time in microseconds. Every run
is a fresh process. The script prints three tables: the whole-run times
(including startup); each as a multiple of the baseline language's (Java
unless --baseline says otherwise); and the work alone, as timed by each
program, as a multiple of the baseline's. The fastest language in each row is
marked, and a result that differs from the baseline's is flagged.

    python3 benchmarks/run.py                      # everything, 3 runs each
    python3 benchmarks/run.py --only fib,loop      # some benchmarks
    python3 benchmarks/run.py --langs nex,java     # some languages
    python3 benchmarks/run.py --nex bin/nex        # a Nex other than `nex`
    python3 benchmarks/run.py --markdown           # the tables as Markdown

A language whose tools are not installed is skipped. See benchmarks/README.md.
"""

import argparse
import os
import shutil
import statistics
import subprocess
import sys
import tempfile
import time

ROOT = os.path.dirname(os.path.abspath(__file__))

BENCHMARKS = [
    ("startup", "an empty program (the cost of starting)"),
    ("fib", "recursive calls: fib(35)"),
    ("loop", "integer arithmetic: 50M loop iterations"),
    ("sort", "sort 2M integers with a comparator function"),
    ("sort_natural", "sort 2M integers by natural order"),
    ("map", "string-keyed map: 500k puts and gets"),
    ("concat", "string concatenation: 5M"),
    ("call_function", "free function calls: 20M"),
    ("call_method", "method calls on one object: 20M"),
    ("call_poly2", "polymorphic calls over 2 classes: 20M"),
    ("call_poly4", "polymorphic calls over 4 classes: 20M"),
    ("call_closure", "closure calls: 20M"),
    ("char_at", "string indexing: 90M char_at on ASCII text"),
    ("char_at_wide", "string indexing: 90M char_at on text with emoji"),
]

LANGS = ["nex", "java", "python", "clojure"]


def find_nex(explicit):
    """The nex command to run: --nex, $NEX, `nex` on the PATH, or this
    checkout's bin/nex."""
    for candidate in (explicit, os.environ.get("NEX")):
        if candidate:
            return os.path.abspath(candidate) if os.sep in candidate else shutil.which(candidate)
    return shutil.which("nex") or (
        os.path.join(ROOT, "..", "bin", "nex") if os.path.exists(os.path.join(ROOT, "..", "bin", "nex")) else None)


def setup(args, work_dir):
    """{lang: function(benchmark) -> argv}, for each requested language whose
    tools are present; prints why any other is skipped."""
    wanted = args.langs.split(",") if args.langs else LANGS
    commands = {}
    for lang in wanted:
        if lang == "nex":
            nex = find_nex(args.nex)
            if nex:
                commands["nex"] = lambda b, nex=nex: [nex, os.path.join(ROOT, "nex", b + ".nex")]
            else:
                print("skipping nex: no `nex` found (use --nex PATH)", file=sys.stderr)
        elif lang == "java":
            if shutil.which("javac") and shutil.which("java"):
                out = os.path.join(work_dir, "java")
                os.makedirs(out, exist_ok=True)
                subprocess.run(["javac", "-d", out, os.path.join(ROOT, "java", "Bench.java")], check=True)
                commands["java"] = lambda b, out=out: ["java", "-cp", out, "Bench", b]
            else:
                print("skipping java: java/javac not found", file=sys.stderr)
        elif lang == "python":
            commands["python"] = lambda b: [sys.executable, os.path.join(ROOT, "python", b + ".py")]
        elif lang == "clojure":
            if shutil.which("clojure"):
                commands["clojure"] = lambda b: ["clojure", "-M", os.path.join(ROOT, "clojure", "bench.clj"), b]
            else:
                print("skipping clojure: the `clojure` CLI not found", file=sys.stderr)
        else:
            sys.exit(f"unknown language {lang!r}; choose from {', '.join(LANGS)}")
    return commands


def time_runs(argv, runs, timeout, cwd):
    """[(milliseconds, result, work milliseconds)] for RUNS runs of ARGV, or
    [(None, error message, None)] if a run fails. A program prints its result
    and then the time its work took, in microseconds, by its own clock."""
    results = []
    for _ in range(runs):
        start = time.perf_counter()
        try:
            proc = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, cwd=cwd)
        except subprocess.TimeoutExpired:
            return [(None, f"timed out after {timeout}s", None)]
        elapsed = (time.perf_counter() - start) * 1000
        if proc.returncode != 0:
            message = ((proc.stderr or proc.stdout).strip().splitlines() or ["failed"])[-1]
            return [(None, message, None)]
        lines = [l.strip() for l in proc.stdout.strip().splitlines() if l.strip()]
        try:
            work = int(lines[-1]) / 1000
        except (IndexError, ValueError):
            return [(None, "no work time on the last line of output", None)]
        results.append((elapsed, lines[-2] if len(lines) > 1 else "", work))
    return results


def fmt_ms(ms):
    return f"{ms / 1000:.2f} s" if ms >= 1000 else f"{ms:.0f} ms"


def fmt_ratio(r):
    """A time as a multiple of the baseline's: 1.0x is as fast, 0.5x twice
    as fast, 3.0x three times as slow."""
    return f"{r:.1f}x" if r < 10 else f"{r:.0f}x"


def render(title, header, rows, markdown):
    """Print one table. A cell given as (text, best?) is bold when best?."""
    def text(cell, md):
        if isinstance(cell, tuple):
            t, best = cell
            return f"**{t}**" if (best and md) else (f"{t} *" if best else t)
        return cell
    print()
    if markdown:
        print(f"**{title}**")
        print()
        print("| " + " | ".join(header) + " |")
        print("|" + "|".join(["---"] + ["--:"] * (len(header) - 1)) + "|")
        for row in rows:
            print("| " + " | ".join(text(c, True) for c in row) + " |")
    else:
        print(title)
        cells = [[text(c, False) for c in row] for row in rows]
        widths = [max(len(r[i]) for r in cells + [header]) for i in range(len(header))]
        for row in [header] + cells:
            print("  ".join(c.ljust(w) for c, w in zip(row, widths)))


def tables(names, langs, medians, work_medians, notes, baseline, markdown):
    """The results as three tables: the whole-run times; each as a multiple
    of BASELINE's; and the work alone (each program's own timing of it, so
    without startup and compilation), as a multiple of BASELINE's. The fastest
    language in a row is marked."""
    header = ["benchmark"] + langs

    def mark_best(values):
        known = {l: v for l, v in values.items() if v is not None}
        best = min(known.values()) if known else None
        return {l: (v is not None and v == best) for l, v in values.items()}

    time_rows = []
    for name in names:
        values = {l: medians.get((name, l)) for l in langs}
        best = mark_best(values)
        time_rows.append([name] + [
            (fmt_ms(values[l]) + notes.get((name, l), ""), best[l]) if values[l] is not None
            else notes.get((name, l), "-")
            for l in langs])
    render("Time per run (median, fresh process; fastest marked)", header, time_rows, markdown)

    if baseline not in langs:
        return

    ratio_rows = []
    for name in names:
        base = medians.get((name, baseline))
        values = {l: (medians[(name, l)] / base if base and medians.get((name, l)) is not None else None)
                  for l in langs}
        best = mark_best(values)
        ratio_rows.append([name] + [(fmt_ratio(values[l]), best[l]) if values[l] is not None else "-"
                                    for l in langs])
    render(f"Whole run, as a multiple of {baseline}'s time (lower is faster)", header, ratio_rows, markdown)

    work_rows = []
    for name in names:
        if name == "startup":
            continue
        base = work_medians.get((name, baseline))
        values = {l: (work_medians[(name, l)] / base if base and work_medians.get((name, l)) is not None else None)
                  for l in langs}
        best = mark_best(values)
        work_rows.append([name] + [(f"{fmt_ratio(values[l])} ({fmt_ms(work_medians[(name, l)])})", best[l])
                                   if values[l] is not None else "-"
                                   for l in langs])
    render(f"Work only (timed by each program itself, so without startup), as a multiple of {baseline}'s",
           header, work_rows, markdown)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--runs", type=int, default=3, help="runs per benchmark and language (default 3)")
    parser.add_argument("--only", help="comma-separated benchmark names")
    parser.add_argument("--langs", help="comma-separated languages: " + ",".join(LANGS))
    parser.add_argument("--nex", help="the nex command to run (default: `nex` on the PATH)")
    parser.add_argument("--baseline", default="java", help="language the ratios are relative to (default java)")
    parser.add_argument("--timeout", type=int, default=600, help="seconds allowed per run (default 600)")
    parser.add_argument("--markdown", action="store_true", help="print the tables as Markdown")
    parser.add_argument("--list", action="store_true", help="list the benchmarks and exit")
    args = parser.parse_args()

    if args.list:
        for name, what in BENCHMARKS:
            print(f"{name:14} {what}")
        return

    known = dict(BENCHMARKS)
    names = args.only.split(",") if args.only else [n for n, _ in BENCHMARKS]
    for n in names:
        if n not in known:
            sys.exit(f"unknown benchmark {n!r}; see --list")

    if hasattr(os, "getloadavg"):
        load = os.getloadavg()[0]
        cpus = os.cpu_count() or 1
        if load > cpus / 2:
            print(f"warning: load average is {load:.1f} on {cpus} CPUs; timings will be noisy", file=sys.stderr)

    with tempfile.TemporaryDirectory(prefix="nex-bench-") as work_dir:
        commands = setup(args, work_dir)
        langs = [l for l in LANGS if l in commands]
        if not langs:
            sys.exit("no language to run")
        print(f"{args.runs} run(s) each; median time per run, fresh process", file=sys.stderr)

        medians, work_medians, notes = {}, {}, {}
        for name in names:
            outputs = {}
            for lang in langs:
                print(f"  {name} / {lang} ...", file=sys.stderr, flush=True)
                # Run from an empty directory, so the Clojure CLI does not pick
                # up this repository's deps.edn.
                results = time_runs(commands[lang](name), args.runs, args.timeout, work_dir)
                if results[0][0] is None:
                    notes[(name, lang)] = f"error: {results[0][1]}"
                    continue
                medians[(name, lang)] = statistics.median(ms for ms, _, _ in results)
                work_medians[(name, lang)] = statistics.median(w for _, _, w in results)
                outputs[lang] = {out for _, out, _ in results}
            expected = outputs.get(args.baseline) or next(iter(outputs.values()), set())
            for lang, outs in outputs.items():
                if outs != expected:
                    notes[(name, lang)] = f" (result {'/'.join(sorted(outs))} differs)"

    tables(names, langs, medians, work_medians, notes, args.baseline, args.markdown)


if __name__ == "__main__":
    main()
