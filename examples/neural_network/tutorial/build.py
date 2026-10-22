#!/usr/bin/env python3
"""Build the tutorial PDF and check that its code is right.

    python3 build.py            # assemble, check, run, typeset
    python3 build.py --no-pdf   # everything except typesetting

The tutorial (tutorial.tex) is the source of truth for the project's code.
This script:

  1. pulls every code chunk out of tutorial.tex, in order, and replays them
     the way a reader would (new file, add at the end, add before a given
     line, continue after the previous chunk);
  2. at every "try it" script and every "run this" point, copies the files
     as they are at that point of the tutorial into a fresh directory and
     runs the program there with `nex`, saving the output so the PDF shows
     the real thing. A failing program stops the build;
  3. compares the files the tutorial builds with the ones in the parent
     directory (examples/neural_network), and reports any difference;
  4. runs pdflatex twice to produce neural_network_tutorial.pdf.
"""

import os
import re
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
BUILD = os.path.join(HERE, "build")
OUT = os.path.join(BUILD, "out")
TEX = os.path.join(HERE, "tutorial.tex")

# Files from the project that the tutorial's programs need but does not
# build itself (the trained model, used by the play.nex chapter).
SUPPORT = ["model.json"]

CHUNK = re.compile(
    r"\\begin\{(newfile|append|addbefore|tightaddbefore|continued|tryit)\}"
    r"((?:\{[^{}]*\})+)[ \t]*\n(.*?)\n[ \t]*\\end\{\1\}"
    r"|\\runoutput\{([^{}]*)\}\{([^{}]*)\}",
    re.S)
ARG = re.compile(r"\{([^{}]*)\}")


def fail(msg):
    sys.exit("build.py: " + msg)


class Files:
    """The project's files as they stand at some point in the tutorial."""

    def __init__(self):
        self.text = {}      # file name -> list of lines
        self.cursor = {}    # file name -> line index after the last chunk

    def newfile(self, name, lines):
        if name in self.text:
            fail(f"newfile {name}: it already exists")
        self.text[name] = list(lines)
        self.cursor[name] = len(lines)

    def append(self, name, lines):
        body = self._get(name)
        body.extend([""] + lines)
        self.cursor[name] = len(body)

    def insert(self, name, marker, lines, blank=True):
        body = self._get(name)
        want = marker.split("|")
        at = [i for i in range(len(body) - len(want) + 1)
              if body[i:i + len(want)] == want]
        if len(at) != 1:
            fail(f"insert into {name}: marker {marker!r} found {len(at)} times")
        new = lines + ([""] if blank else [])
        body[at[0]:at[0]] = new
        self.cursor[name] = at[0] + len(lines)

    def cont(self, name, lines):
        body = self._get(name)
        at = self.cursor[name]
        body[at:at] = [""] + lines
        self.cursor[name] = at + 1 + len(lines)

    def _get(self, name):
        if name not in self.text:
            fail(f"{name} is used before its newfile chunk")
        return self.text[name]

    def write_to(self, directory):
        os.makedirs(directory, exist_ok=True)
        for name, lines in self.text.items():
            with open(os.path.join(directory, name), "w") as f:
                f.write("\n".join(lines) + "\n")


def run(stage_files, directory, program, label, extra):
    """Run `nex program` on a snapshot of the files; save its output."""
    if os.path.exists(directory):
        shutil.rmtree(directory)
    stage_files.write_to(directory)
    for name, lines in extra.items():
        with open(os.path.join(directory, name), "w") as f:
            f.write("\n".join(lines) + "\n")
    for name in SUPPORT:
        src = os.path.join(PROJECT, name)
        if os.path.exists(src):
            shutil.copy(src, directory)
    args = program.split()
    # A line "% input: ..." just before \runoutput gives stdin; not used yet.
    proc = subprocess.run([os.environ.get("NEX", "nex")] + args, cwd=directory, capture_output=True,
                          text=True, timeout=900)
    output = proc.stdout + proc.stderr
    with open(os.path.join(OUT, label + ".txt"), "w") as f:
        f.write(output.rstrip("\n") + "\n")
    if proc.returncode != 0 and not label.endswith("_fails"):
        print(output)
        fail(f"`nex {program}` ({label}) exited with {proc.returncode}")
    print(f"  ran {program:<28} -> out/{label}.txt")


def main():
    tex = open(TEX).read()
    if os.path.exists(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(OUT)

    files = Files()
    tries = {}       # try script name -> lines (kept with every later stage)
    stage = 0
    for m in CHUNK.finditer(tex):
        if m.group(4) is not None:                 # \runoutput{program}{label}
            stage += 1
            run(files, os.path.join(BUILD, f"stage{stage:02d}"),
                m.group(4), m.group(5), tries)
            continue
        kind, args = m.group(1), ARG.findall(m.group(2))
        lines = m.group(3).split("\n")
        lines = [l.rstrip() for l in lines]
        name = args[0]
        if kind == "newfile":
            files.newfile(name, lines)
        elif kind == "append":
            files.append(name, lines)
        elif kind == "addbefore":
            files.insert(name, args[2], lines, blank=True)
        elif kind == "tightaddbefore":
            files.insert(name, args[2], lines, blank=False)
        elif kind == "continued":
            files.cont(name, lines)
        elif kind == "tryit":
            tries[name] = lines
            stage += 1
            run(files, os.path.join(BUILD, f"stage{stage:02d}"),
                name, name.replace(".nex", ""), tries)

    final = os.path.join(BUILD, "final")
    files.write_to(final)
    print("\nComparing the files the tutorial builds with the project:")
    clean = True
    for name in sorted(files.text):
        mine = os.path.join(final, name)
        theirs = os.path.join(PROJECT, name)
        if not os.path.exists(theirs):
            print(f"  {name}: not in the project")
            clean = False
            continue
        diff = subprocess.run(["diff", "-u", theirs, mine],
                              capture_output=True, text=True).stdout
        if diff:
            print(f"  {name}: DIFFERS")
            print(diff)
            clean = False
        else:
            print(f"  {name}: identical")
    project_nex = {f for f in os.listdir(PROJECT) if f.endswith(".nex")}
    for name in sorted(project_nex - set(files.text)):
        print(f"  {name}: in the project but not built by the tutorial")
        clean = False
    if not clean and "--sync" in sys.argv:
        for name in files.text:
            shutil.copy(os.path.join(final, name), os.path.join(PROJECT, name))
        print("  (--sync: copied the tutorial's versions into the project)")

    if "--no-pdf" in sys.argv:
        return
    for _ in range(2):
        proc = subprocess.run(
            ["pdflatex", "-interaction=nonstopmode", "-halt-on-error",
             "-output-directory", BUILD, TEX],
            cwd=HERE, capture_output=True, text=True)
        if proc.returncode != 0:
            print(proc.stdout[-4000:])
            fail("pdflatex failed")
    shutil.copy(os.path.join(BUILD, "tutorial.pdf"),
                os.path.join(HERE, "neural_network_tutorial.pdf"))
    print("\nwrote tutorial/neural_network_tutorial.pdf")


if __name__ == "__main__":
    main()
