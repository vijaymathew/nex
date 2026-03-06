# Print Cover Assets

This folder contains a print-wrap cover template for the 6x9 trim print edition.

## Generated file

- `beyond-code-cover.svg`: full-wrap cover (back + spine + front), including bleed and safe guides.
- `beyond-code-cover-spec.txt`: exported print dimensions and panel coordinates.

## Dimensions policy

- Trim size: `6 x 9 in`
- Bleed: `0.125 in` on all sides
- Full cover width: `2*trim_width + spine + 2*bleed`
- Full cover height: `trim_height + 2*bleed`

Spine width is computed from the current print PDF page count.
Default caliper in the script: `0.0025 in/page` (typical POD cream-paper estimate).

## Regenerate after page-count changes

From repo root:

```bash
docs/book/scripts/generate_cover.sh
```

Optional explicit inputs:

```bash
docs/book/scripts/generate_cover.sh \
  docs/book/_book/print/beyond-code-print.pdf \
  docs/book/assets/cover/beyond-code-cover.svg \
  docs/book/assets/cover/beyond-code-cover-spec.txt
```

## Export notes

Most print providers accept PDF for full-wrap covers.
Convert SVG to PDF using your preferred toolchain (Inkscape, Illustrator, Affinity, etc.) while preserving exact dimensions.
