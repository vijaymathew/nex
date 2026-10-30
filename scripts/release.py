#!/usr/bin/env python3
"""Prepare a Nex release: set the new version everywhere, and date the
changelog.

    scripts/release.py 0.6.6          # a given version
    scripts/release.py patch          # 0.6.5 -> 0.6.6 (or minor, major)
    scripts/release.py patch --dry-run
    scripts/release.py 0.7.0 --date 2026-11-02
    scripts/release.py --notes 0.6.6  # print a release's changelog section

It changes:

  - the version in bin/nex, install.sh, src/nex/cli.clj, the REPL banner
    (src/nex/repl.clj), the Emacs mode and the VS Code extension
    (package.json and package-lock.json);
  - the usage examples in INSTALL.md, bootstrap-install.sh and install.ps1
    that name the latest release ("--ref v0.6.5", "-Version 0.6.5", the
    sample installer output, ...). Mentions of a version as a fact
    ("0.6.4 and earlier", "0.6.5 or later") are left alone;
  - CHANGELOG.md: what is under "## Unreleased" becomes the new version's
    section, dated, and a fresh empty "## Unreleased" stays on top.

Every change is checked before any file is written: if a file no longer
looks the way this script expects, it stops and changes nothing. Nothing is
committed; the script prints the steps that follow.
"""

import argparse
import datetime
import difflib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VERSION = re.compile(r"^\d+\.\d+\.\d+$")


def fail(message):
    sys.exit("release.py: " + message)


def read(path):
    with open(os.path.join(ROOT, path), encoding="utf-8") as f:
        return f.read()


def current_version():
    m = re.search(r'^VERSION="(\d+\.\d+\.\d+)"$', read("bin/nex"), re.M)
    if not m:
        fail('bin/nex has no VERSION="x.y.z" line')
    return m.group(1)


def next_version(current, wanted):
    major, minor, patch = (int(n) for n in current.split("."))
    if wanted == "major":
        return f"{major + 1}.0.0"
    if wanted == "minor":
        return f"{major}.{minor + 1}.0"
    if wanted == "patch":
        return f"{major}.{minor}.{patch + 1}"
    if not VERSION.match(wanted):
        fail(f"{wanted!r} is not a version (x.y.z) or major, minor, patch")
    return wanted


def as_tuple(version):
    return tuple(int(n) for n in version.split("."))


# Each rule: (file, pattern, replacement, how many matches to expect).
# In patterns, OLD stands for the current version, escaped; in
# replacements, NEW for the new one.
def version_rules():
    return [
        ("bin/nex", r'^VERSION="OLD"$', 'VERSION="NEW"', 1),
        ("install.sh", r'^VERSION="OLD"$', 'VERSION="NEW"', 1),
        ("src/nex/cli.clj", r'^\(def version "OLD"\)$', '(def version "NEW")', 1),
        ("editor/emacs/nex-mode.el", r"^;; Version: OLD$", ";; Version: NEW", 1),
        ("editor/vscode/package.json", r'^  "version": "OLD",$', '  "version": "NEW",', 1),
        # The extension's own entries in the lockfile, not its dependencies'.
        ("editor/vscode/package-lock.json", r'^  "version": "OLD",$', '  "version": "NEW",', 1),
        ("editor/vscode/package-lock.json",
         r'("packages": \{\n    "": \{\n      "name": "[^"]*",\n      "version": ")OLD(")',
         r"\g<1>NEW\g<2>", 1),
        # Usage examples that name the latest release.
        ("INSTALL.md", r"-Version OLD\b", "-Version NEW", 2),
        ("INSTALL.md", r"--version OLD\b", "--version NEW", 1),
        ("INSTALL.md", r"--ref vOLD\b", "--ref vNEW", 1),
        ("INSTALL.md", r"Nex Language Installer vOLD\b", "Nex Language Installer vNEW", 2),
        ("bootstrap-install.sh", r"e\.g\. OLD\b", "e.g. NEW", 1),
        ("bootstrap-install.sh", r"e\.g\. vOLD\b", "e.g. vNEW", 1),
        ("bootstrap-install.sh", r"--version OLD\b", "--version NEW", 1),
        ("bootstrap-install.sh", r"--ref vOLD\b", "--ref vNEW", 1),
        ("bootstrap-install.sh", r"such as OLD\.", "such as NEW.", 1),
        ("install.ps1", r"-Version OLD\b", "-Version NEW", 1),
        ("install.ps1", r"such as OLD\b", "such as NEW", 1),
    ]


def apply_rules(texts, old, new):
    for path, pattern, replacement, expected in version_rules():
        regex = re.compile(pattern.replace("OLD", re.escape(old)), re.M)
        text = texts.setdefault(path, read(path))
        found = len(regex.findall(text))
        if found != expected:
            fail(f"{path}: expected {expected} match(es) of {pattern!r} for {old}, "
                 f"found {found}; update version_rules() in this script")
        texts[path] = regex.sub(replacement.replace("NEW", new), text)


def banner(texts, old, new):
    """The REPL banner is a box 60 characters wide inside: re-centre the
    version line, so the box stays square whatever the version's length."""
    path = "src/nex/repl.clj"
    text = texts.setdefault(path, read(path))
    line = re.compile(r'^(\s*\(println ")║(\s*)NEX REPL v' + re.escape(old) + r'(\s*)║("\))$', re.M)
    m = line.search(text)
    if not m or len(line.findall(text)) != 1:
        fail(f"{path}: no banner line \"NEX REPL v{old}\"")
    width = len(m.group(2)) + len(f"NEX REPL v{old}") + len(m.group(3))
    label = f"NEX REPL v{new}"
    if len(label) > width:
        fail(f"{path}: \"{label}\" does not fit in the banner")
    left = len(m.group(2))
    if left + len(label) > width:
        left = (width - len(label)) // 2
    inner = " " * left + label + " " * (width - left - len(label))
    texts[path] = text[:m.start()] + m.group(1) + "║" + inner + "║" + m.group(4) + text[m.end():]


def changelog(texts, new, date, allow_empty):
    path = "CHANGELOG.md"
    text = texts.setdefault(path, read(path))
    m = re.search(r"^## Unreleased\n", text, re.M)
    if not m:
        fail(f"{path} has no \"## Unreleased\" heading")
    if re.search(rf"^## {re.escape(new)}\b", text, re.M):
        fail(f"{path} already has a section for {new}")
    following = re.search(r"^## ", text[m.end():], re.M)
    notes = text[m.end():m.end() + following.start()] if following else text[m.end():]
    if not notes.strip() and not allow_empty:
        fail(f"nothing under \"## Unreleased\" in {path}: write the release notes "
             "first, or pass --allow-empty")
    texts[path] = text[:m.end()] + f"\n## {new} - {date}\n" + text[m.end():]
    return notes.strip()


def main():
    parser = argparse.ArgumentParser(
        description="Set a new Nex version everywhere and date the changelog.")
    parser.add_argument("version", help="the new version (x.y.z), or major, minor or patch")
    parser.add_argument("--date", default=datetime.date.today().isoformat(),
                        help="the release date for the changelog (default: today)")
    parser.add_argument("--dry-run", action="store_true",
                        help="show the changes without writing them")
    parser.add_argument("--allow-empty", action="store_true",
                        help="release even if the changelog has no unreleased notes")
    args = parser.parse_args()

    old = current_version()
    new = next_version(old, args.version)
    if as_tuple(new) <= as_tuple(old):
        fail(f"{new} is not after the current version, {old}")
    try:
        datetime.date.fromisoformat(args.date)
    except ValueError:
        fail(f"--date {args.date!r} is not a date (YYYY-MM-DD)")

    originals = {}
    texts = {}
    apply_rules(texts, old, new)
    banner(texts, old, new)
    notes = changelog(texts, new, args.date, args.allow_empty)
    for path in texts:
        originals[path] = read(path)

    changed = [p for p in sorted(texts) if texts[p] != originals[p]]
    if args.dry_run:
        for path in changed:
            sys.stdout.writelines(difflib.unified_diff(
                originals[path].splitlines(True), texts[path].splitlines(True),
                "a/" + path, "b/" + path))
        print(f"\n(dry run: {old} -> {new}; {len(changed)} files would change)")
        return

    for path in changed:
        with open(os.path.join(ROOT, path), "w", encoding="utf-8") as f:
            f.write(texts[path])
    print(f"Nex {old} -> {new}. Changed:")
    for path in changed:
        print("  " + path)
    if not notes:
        print("\nThe changelog section for this release is empty.")
    print(f"""
Next:
  git diff                                   # check the changes
  git commit -am "release v{new}"
  git tag v{new} && git push && git push origin v{new}
  gh release create v{new} --title "Nex {new}" --notes-file <(scripts/release.py --notes {new})

Publishing the GitHub release runs the Release workflow, which builds the
archives and attaches them.""")


def print_notes(version):
    """The changelog section of `version`, for release notes."""
    text = read("CHANGELOG.md")
    m = re.search(rf"^## {re.escape(version)}\b.*\n", text, re.M)
    if not m:
        fail(f"CHANGELOG.md has no section for {version}")
    following = re.search(r"^## ", text[m.end():], re.M)
    section = text[m.end():m.end() + following.start()] if following else text[m.end():]
    print(section.strip())


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--notes":
        print_notes(sys.argv[2])
    else:
        main()
