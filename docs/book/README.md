# Book Build and Maintenance Guide

This folder contains the source for the book and the build pipeline for HTML, PDF, EPUB, print PDF, and cover assets.

## 1) Prerequisites

- Quarto installed and available as `quarto`
- TeX distribution with `pdflatex` (for PDF outputs)
- `pdfinfo` (used by cover generation)

## 2) Build Commands

Run all commands from `docs/book`.

- Build full book (HTML + PDF + EPUB):

```bash
make book
```

- Build HTML only:

```bash
make html
```

- Build standard PDF only:

```bash
make pdf
```

- Build EPUB only:

```bash
make epub
```

- Build print edition PDF profile:

```bash
make print
```

- Build full-wrap cover template (front + spine + back):

```bash
make cover
```

- Clean generated outputs/cache:

```bash
make clean
```

### Output locations

- HTML site: `_book/index.html`
- Standard PDF: `_book/beyond-code.pdf`
- EPUB: `_book/beyond-code.epub`
- Print PDF: `_book/print/beyond-code-print.pdf`
- Cover assets: `assets/cover/`

Note: long auto-generated intermediate filenames are cleaned automatically by `Makefile`.

## 3) Book Structure and Chapter Ordering

Primary config:

- `_quarto.yml` (main web/pdf/epub build)

Print-specific overrides:

- `_quarto-print.yml` (print profile)

To add/reorder chapters:

1. Edit chapter lists under `book.chapters`.
2. Keep `_quarto.yml` and `_quarto-print.yml` aligned unless you intentionally want a profile-specific difference.

## 4) Styling and Typesetting

### Web (HTML/EPUB) style

- `styles.css`

Use this file for:

- typography, spacing, links, code block appearance
- icon/callout styles (`.note-lab`, `.note-studio`, `.note-exercise`, `.note-takeaways`)

Icons are in `assets/icons/`.

### PDF/Print style

- `preamble.tex` (standard PDF)
- `preamble-print.tex` (print PDF)
- `frontmatter-print.tex` (print front matter and page-style transitions)

Use these files for:

- page geometry and header/footer behavior
- code listing layout and wrapping
- figure/table caption and float policy

## 5) Cover Workflow (Print)

Generator:

- `scripts/generate_cover.sh`

Outputs:

- `assets/cover/beyond-code-cover.svg`
- `assets/cover/beyond-code-cover-spec.txt`

`beyond-code-cover-spec.txt` records trim, bleed, spine width, and panel coordinates for print handoff.

## 6) Bibliography and Citations

Bibliography file:

- `references.bib`

References chapter:

- `references.md`

Configured in `_quarto.yml` via:

- `bibliography: references.bib`

### Add a citation

1. Add a BibTeX entry to `references.bib`.
2. Cite in chapter markdown using `[@citation_key]`.
3. Rebuild with `make book` or `make print`.

## 7) Glossary and Index Maintenance

- Glossary: `glossary.md`
- Index of terms: `index_terms.md`

Use `glossary.md` for definitions.
Use `index_terms.md` as a curated reader index with links to relevant chapters.

If you add major concepts (for example contracts, invariants, complexity topics), update both files.

## 8) Navigation and Reader-facing TOC

- `toc.md` is a human-friendly roadmap page.

When adding/removing major sections, update `toc.md` for consistency with the canonical chapter list in `_quarto.yml`.

## 9) Recommended Editing Workflow

1. Edit one or more chapter/style/config files.
2. Run the Nex example smoke test:

```bash
clojure -M:test test/scripts/check_docs_examples.clj --book
```

3. Recheck the same examples under the compiled REPL backend:

```bash
clojure -M:test test/scripts/check_compiled_book_examples.clj
```

4. Run `make book` for quick overall validation.
5. Run `make print` for print-specific checks.
6. If page count changed and you use printed cover assets, run `make cover`.

## 10) Troubleshooting

- Missing PDF tools: ensure `pdflatex` and TeX packages are installed.
- Broken links warnings: run build and fix unresolved `.md`/anchor links.
- Print layout issues: inspect `preamble-print.tex` first (margins, listings, float policy).
- Cover size mismatch: re-run `make print` then `make cover` so spine width uses current page count.
