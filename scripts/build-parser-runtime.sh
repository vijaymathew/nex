#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ROOT_DIR/src/nex/parser_js/grammar"
OUT_DIR="$ROOT_DIR/public/parser-runtime"

mkdir -p "$OUT_DIR"

build_one() {
  local name="$1"
  local in="$SRC_DIR/${name}.js"
  local out="$OUT_DIR/${name}.runtime.js"

  if [[ ! -f "$in" ]]; then
    echo "Missing source file: $in" >&2
    exit 1
  fi

  cp "$in" "$out"

  # Convert ESM-based ANTLR output into browser-runtime scripts loaded by index.html.
  perl -0777 -i -pe '
    s@import\s+antlr4\s+from\s+\x27antlr4\x27;@const antlr4Module = (window.shadow && window.shadow.js && window.shadow.js.require && window.shadow.js.require("module\$node_modules\$antlr4\$dist\$antlr4_web_cjs", {})) || window.antlr4;\nconst antlr4 = (antlr4Module && antlr4Module.default) ? antlr4Module.default : antlr4Module;@g;
    s@export\s+default\s+class\s+@class @g;
    s@import\s+nexlangListener\s+from\s+\x27\./nexlangListener\.js\x27;@const nexlangListener = window.__nexlangListener || window.nexlangListener;@g;
  ' "$out"

  cat >> "$out" <<'EOF'

;(function(){
  if (typeof CLASS_NAME !== 'undefined') {
    window.__CLASS_NAME = CLASS_NAME;
    window['module$nex$parser_js$grammar$CLASS_NAME'] = CLASS_NAME;
    window['module$nex$parser_js$grammar$CLASS_NAME.js'] = CLASS_NAME;
  }
})();
EOF

  perl -i -pe "s/CLASS_NAME/${name}/g" "$out"
  echo "Generated: ${out#$ROOT_DIR/}"
}

build_one "nexlangListener"
build_one "nexlangLexer"
build_one "nexlangParser"

echo "Parser runtime generation complete."
