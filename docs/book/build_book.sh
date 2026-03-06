#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$ROOT_DIR/_book"
mkdir -p "$OUT_DIR"

cd "$ROOT_DIR"

chapters=(
  prologue.md
  part_I_1.md part_I_2.md part_I_3.md part_I_4.md part_I_5.md
  studio_1.md
  part_II_6.md part_II_7.md part_II_8.md part_II_9.md part_II_10.md
  studio_2.md
  part_III_11.md part_III_12.md part_III_13.md part_III_14.md
  algorithm_lab_1.md
  part_IV_15.md part_IV_16.md part_IV_17.md part_IV_18.md
  studio_3.md
  part_V_19.md part_V_20.md part_V_21.md part_V_22.md
  algorithm_lab_2.md
  part_VI_23.md part_VI_24.md part_VI_25.md part_VI_26.md
  studio_4.md
  part_VII_27.md part_VII_28.md part_VII_29.md part_VII_30.md
  studio_5.md
  part_VIII_31.md part_VIII_32.md part_VIII_33.md
  studio_6.md
  part_IX_34.md part_IX_35.md part_IX_36.md
  epilogue.md
)

title="Beyond Code -- Building Software Systems That Last"
subtitle=""
author="Nex Book Team"

# HTML
pandoc "${chapters[@]}" \
  --standalone \
  --toc \
  --toc-depth=3 \
  --number-sections \
  --metadata title="$title" \
  --metadata subtitle="$subtitle" \
  --metadata author="$author" \
  --css styles.css \
  --output "$OUT_DIR/book.html"

# PDF
pdf_engine="lualatex"
pdf_args=(
  --toc
  --toc-depth=2
  --number-sections
  --metadata "title=$title"
  --metadata "subtitle=$subtitle"
  --metadata "author=$author"
  --pdf-engine="$pdf_engine"
  --variable documentclass=scrbook
  --variable classoption=oneside
  --variable classoption=openany
  --variable fontsize=11pt
  --variable mainfont="TeX Gyre Pagella"
  --variable sansfont="TeX Gyre Heros"
  --variable monofont="Inconsolata"
  --include-in-header=preamble.tex
  --output "$OUT_DIR/book.pdf"
)

set +e
pandoc "${chapters[@]}" "${pdf_args[@]}"
pdf_exit=$?
set -e

if [[ $pdf_exit -ne 0 ]]; then
  echo "PDF build with lualatex failed, retrying with pdflatex fallback..."
  pandoc "${chapters[@]}" \
    --toc \
    --toc-depth=2 \
    --number-sections \
    --metadata title="$title" \
    --metadata subtitle="$subtitle" \
    --metadata author="$author" \
    --pdf-engine=pdflatex \
    --variable documentclass=scrbook \
    --variable classoption=oneside \
    --variable classoption=openany \
    --variable fontsize=11pt \
    --include-in-header=preamble.tex \
    --output "$OUT_DIR/book.pdf"
fi

# EPUB
pandoc "${chapters[@]}" \
  --toc \
  --toc-depth=2 \
  --number-sections \
  --metadata title="$title" \
  --metadata subtitle="$subtitle" \
  --metadata author="$author" \
  --output "$OUT_DIR/book.epub"

echo "Built outputs in: $OUT_DIR"
