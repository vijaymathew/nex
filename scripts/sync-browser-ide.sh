#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PUBLIC_DIR="$ROOT_DIR/public"
DEFAULT_TARGET="$ROOT_DIR/../vijaymathew.github.io/nex"
TARGET_DIR="${1:-$DEFAULT_TARGET}"

usage() {
  cat <<EOF
Usage:
  ./scripts/sync-browser-ide.sh [target-dir]

Sync the Browser IDE assets from public/ into a website checkout.

Default target:
  $DEFAULT_TARGET

This copies only:
  - public/index.html          -> <target>/index.html
  - public/js/                 -> <target>/js/
  - public/parser-runtime/     -> <target>/parser-runtime/

It also removes known stray artifacts from previous incorrect syncs:
  - <target>/cljs-runtime
  - <target>/main.js
  - <target>/manifest.edn
  - <target>/nexlangLexer.runtime.js
  - <target>/nexlangListener.runtime.js
  - <target>/nexlangParser.runtime.js
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

require_path() {
  local path="$1"
  if [[ ! -e "$path" ]]; then
    echo "Missing required path: $path" >&2
    exit 1
  fi
}

require_path "$PUBLIC_DIR/index.html"
require_path "$PUBLIC_DIR/js"
require_path "$PUBLIC_DIR/parser-runtime"

if [[ ! -d "$TARGET_DIR" ]]; then
  echo "Target directory does not exist: $TARGET_DIR" >&2
  exit 1
fi

if [[ ! -f "$TARGET_DIR/index.html" ]]; then
  echo "Target does not look like a Browser IDE deploy directory: $TARGET_DIR" >&2
  exit 1
fi

mkdir -p "$TARGET_DIR/js" "$TARGET_DIR/parser-runtime"

rm -rf \
  "$TARGET_DIR/cljs-runtime" \
  "$TARGET_DIR/main.js" \
  "$TARGET_DIR/manifest.edn" \
  "$TARGET_DIR/nexlangLexer.runtime.js" \
  "$TARGET_DIR/nexlangListener.runtime.js" \
  "$TARGET_DIR/nexlangParser.runtime.js"

rsync -a "$PUBLIC_DIR/js/" "$TARGET_DIR/js/"
rsync -a "$PUBLIC_DIR/parser-runtime/" "$TARGET_DIR/parser-runtime/"
rsync -a "$PUBLIC_DIR/index.html" "$TARGET_DIR/index.html"

echo "Synced Browser IDE assets to: $TARGET_DIR"
