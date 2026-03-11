#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

find_shadow_cljs() {
  if [[ -x "$ROOT_DIR/node_modules/.bin/shadow-cljs" ]]; then
    printf '%s\n' "$ROOT_DIR/node_modules/.bin/shadow-cljs"
    return 0
  fi

  shopt -s nullglob
  local candidates=( "$HOME"/.npm/_npx/*/node_modules/.bin/shadow-cljs )
  shopt -u nullglob
  if (( ${#candidates[@]} > 0 )); then
    printf '%s\n' "${candidates[0]}"
    return 0
  fi

  return 1
}

if shadow_cljs_bin="$(find_shadow_cljs)"; then
  "$shadow_cljs_bin" compile test
else
  npx shadow-cljs compile test
fi

node target/test.js
