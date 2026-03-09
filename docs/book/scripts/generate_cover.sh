#!/usr/bin/env bash
set -euo pipefail

BOOK_PDF="${1:-_book/print/beyond-code-print.pdf}"
OUT_SVG="${2:-assets/cover/beyond-code-cover.svg}"
OUT_SPEC="${3:-assets/cover/beyond-code-cover-spec.txt}"

if [[ ! -f "$BOOK_PDF" ]]; then
  echo "Book PDF not found: $BOOK_PDF" >&2
  exit 1
fi

pages=$(pdfinfo "$BOOK_PDF" | awk '/^Pages:/ {print $2}')
if [[ -z "${pages:-}" ]]; then
  echo "Could not determine page count from: $BOOK_PDF" >&2
  exit 1
fi

trim_w=6
trim_h=9
bleed=0.125
safe=0.25

# Typical POD cream-paper estimate: ~0.0025 in per page.
caliper=0.0025
spine=$(awk -v p="$pages" -v c="$caliper" 'BEGIN{printf "%.3f", p*c}')
full_w=$(awk -v tw="$trim_w" -v s="$spine" -v b="$bleed" 'BEGIN{printf "%.3f", (2*tw)+s+(2*b)}')
full_h=$(awk -v th="$trim_h" -v b="$bleed" 'BEGIN{printf "%.3f", th+(2*b)}')

# Regions
back_x="$bleed"
spine_x=$(awk -v b="$bleed" -v tw="$trim_w" 'BEGIN{printf "%.3f", b+tw}')
front_x=$(awk -v b="$bleed" -v tw="$trim_w" -v s="$spine" 'BEGIN{printf "%.3f", b+tw+s}')
content_y="$bleed"

mkdir -p "$(dirname "$OUT_SVG")"
mkdir -p "$(dirname "$OUT_SPEC")"

cat > "$OUT_SVG" <<SVG
<svg xmlns="http://www.w3.org/2000/svg" width="${full_w}in" height="${full_h}in" viewBox="0 0 ${full_w} ${full_h}">
  <defs>
    <style>
      .bg-front { fill: #0f2438; }
      .bg-back { fill: #132b43; }
      .bg-spine { fill: #0b1a2a; }
      .guide { fill: none; stroke: #e36; stroke-width: 0.01; stroke-dasharray: 0.04 0.04; }
      .safe { fill: none; stroke: #2a7; stroke-width: 0.008; stroke-dasharray: 0.03 0.03; }
      .txt-title { font-family: 'Georgia', serif; font-size: 0.36px; font-weight: 700; fill: #f5f7fb; letter-spacing: 0.01em; }
      .txt-sub { font-family: 'Georgia', serif; font-size: 0.15px; fill: #d7e1ec; }
      .txt-spine { font-family: 'Georgia', serif; font-size: 0.12px; font-weight: 700; fill: #f5f7fb; letter-spacing: 0.03em; }
      .txt-meta { font-family: 'Georgia', serif; font-size: 0.095px; fill: #c8d2df; }
      .label { font-family: monospace; font-size: 0.08px; fill: #ffd7df; }
    </style>
  </defs>

  <!-- Bleed area background -->
  <rect x="0" y="0" width="${full_w}" height="${full_h}" fill="#0a1826"/>

  <!-- Back / Spine / Front panels -->
  <rect class="bg-back" x="${back_x}" y="${content_y}" width="${trim_w}" height="${trim_h}"/>
  <rect class="bg-spine" x="${spine_x}" y="${content_y}" width="${spine}" height="${trim_h}"/>
  <rect class="bg-front" x="${front_x}" y="${content_y}" width="${trim_w}" height="${trim_h}"/>

  <!-- Front text -->
  <text class="txt-title" x="$(awk -v x="$front_x" 'BEGIN{printf "%.3f", x+0.55}')" y="2.35">Beyond Code</text>
  <text class="txt-sub" x="$(awk -v x="$front_x" 'BEGIN{printf "%.3f", x+0.55}')" y="2.78">Building Software Systems That Last</text>
  <text class="txt-sub" x="$(awk -v x="$front_x" 'BEGIN{printf "%.3f", x+0.55}')" y="3.08">From Modeling and Algorithms to Reliability,</text>
  <text class="txt-sub" x="$(awk -v x="$front_x" 'BEGIN{printf "%.3f", x+0.55}')" y="3.30">Evolution, and AI-Era Engineering</text>
  <text class="txt-meta" x="$(awk -v x="$front_x" 'BEGIN{printf "%.3f", x+0.55}')" y="4.10">Vijay Mathew</text>

  <!-- Spine text -->
  <g transform="translate($(awk -v x="$spine_x" -v s="$spine" 'BEGIN{printf "%.3f", x+(s/2)}'),8.3) rotate(-90)">
    <text class="txt-spine" text-anchor="middle">BEYOND CODE</text>
    <text class="txt-meta" y="0.2" text-anchor="middle">Vijay Mathew</text>
  </g>

  <!-- Back copy placeholder -->
  <text class="txt-sub" x="$(awk -v x="$back_x" 'BEGIN{printf "%.3f", x+0.55}')" y="2.25">A practical guide to designing software systems</text>
  <text class="txt-sub" x="$(awk -v x="$back_x" 'BEGIN{printf "%.3f", x+0.55}')" y="2.47">that remain correct, reliable, and evolvable</text>
  <text class="txt-sub" x="$(awk -v x="$back_x" 'BEGIN{printf "%.3f", x+0.55}')" y="2.69">under real-world pressure.</text>
  <rect x="$(awk -v x="$back_x" 'BEGIN{printf "%.3f", x+4.2}')" y="6.95" width="1.25" height="1.05" fill="#f7f7f7" opacity="0.95"/>
  <text class="txt-meta" x="$(awk -v x="$back_x" 'BEGIN{printf "%.3f", x+4.25}')" y="8.13">Barcode</text>

  <!-- Trim and safe guides -->
  <rect class="guide" x="${bleed}" y="${bleed}" width="$(awk -v w="$full_w" -v b="$bleed" 'BEGIN{printf "%.3f", w-(2*b)}')" height="$(awk -v h="$full_h" -v b="$bleed" 'BEGIN{printf "%.3f", h-(2*b)}')"/>
  <rect class="safe" x="$(awk -v b="$bleed" -v s="$safe" 'BEGIN{printf "%.3f", b+s}')" y="$(awk -v b="$bleed" -v s="$safe" 'BEGIN{printf "%.3f", b+s}')" width="$(awk -v w="$full_w" -v b="$bleed" -v s="$safe" 'BEGIN{printf "%.3f", w-(2*(b+s))}')" height="$(awk -v h="$full_h" -v b="$bleed" -v s="$safe" 'BEGIN{printf "%.3f", h-(2*(b+s))}')"/>

  <!-- Panel boundaries -->
  <line class="guide" x1="${spine_x}" y1="${bleed}" x2="${spine_x}" y2="$(awk -v h="$full_h" -v b="$bleed" 'BEGIN{printf "%.3f", h-b}')"/>
  <line class="guide" x1="${front_x}" y1="${bleed}" x2="${front_x}" y2="$(awk -v h="$full_h" -v b="$bleed" 'BEGIN{printf "%.3f", h-b}')"/>

  <!-- Technical labels -->
  <text class="label" x="0.18" y="0.20">Trim: 6 x 9 in</text>
  <text class="label" x="0.18" y="0.33">Bleed: 0.125 in</text>
  <text class="label" x="0.18" y="0.46">Pages: ${pages}</text>
  <text class="label" x="0.18" y="0.59">Spine: ${spine} in (caliper ${caliper})</text>
  <text class="label" x="0.18" y="0.72">Full cover: ${full_w} x ${full_h} in</text>
</svg>
SVG

cat > "$OUT_SPEC" <<SPEC
Book: Beyond Code
PDF source: $BOOK_PDF
Pages: $pages
Trim size (W x H): ${trim_w} x ${trim_h} in
Bleed: ${bleed} in
Safe margin: ${safe} in
Caliper: ${caliper} in/page
Spine width: ${spine} in
Full cover size (W x H): ${full_w} x ${full_h} in
Back panel x-range: ${back_x} .. $(awk -v bx="$back_x" -v tw="$trim_w" 'BEGIN{printf "%.3f", bx+tw}')
Spine x-range: ${spine_x} .. $(awk -v sx="$spine_x" -v s="$spine" 'BEGIN{printf "%.3f", sx+s}')
Front panel x-range: ${front_x} .. $(awk -v fx="$front_x" -v tw="$trim_w" 'BEGIN{printf "%.3f", fx+tw}')
Panel y-range: ${content_y} .. $(awk -v cy="$content_y" -v th="$trim_h" 'BEGIN{printf "%.3f", cy+th}')
SPEC

echo "Generated cover SVG: $OUT_SVG"
echo "Generated cover spec: $OUT_SPEC"
echo "Pages=$pages Spine=${spine}in Full=${full_w}x${full_h}in"
