# Tutorial Build and Maintenance Guide

This folder contains the source for the tutorial edition and its build pipeline.

## 1) Prerequisites

- Quarto installed and available as `quarto`
- TeX distribution with `pdflatex` for PDF outputs

## 2) Build Commands

Run all commands from `docs/tut`.

- Build the tutorial site/book:

```bash
make book
```

- Build HTML only:

```bash
make html
```

- Build PDF only:

```bash
make pdf
```

- Build print PDF:

```bash
make print
```

- Clean generated output:

```bash
make clean
```

## 3) Recommended Workflow

1. Edit one or more tutorial chapters or style/config files.
2. Run the Nex example smoke test:

```bash
clojure -M:test test/scripts/check_docs_examples.clj --tut
```

3. Run `make book` for overall validation.
4. Run `make print` if you changed print-facing layout or typography.

## 4) Files You Will Usually Touch

- `_quarto.yml`
- `_quarto-print.yml`
- `styles.css`
- `preamble.tex`
- `preamble-print.tex`
- chapter and appendix `.md` files

## 5) Notes

- The docs smoke test runs fenced `nex` examples through the real Nex evaluation path.
