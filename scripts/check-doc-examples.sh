#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

status=0
all_docs=(docs)

check_absent() {
  local pattern=$1
  local description=$2
  shift 2
  if rg -n "$pattern" "$@" >/tmp/nex-doc-check.$$ 2>/dev/null; then
    echo "FAIL: $description"
    cat /tmp/nex-doc-check.$$
    status=1
  else
    echo "OK: $description"
  fi
  rm -f /tmp/nex-doc-check.$$
}

check_absent 'old other\.' \
  "no old-other-field examples in docs" \
  "${all_docs[@]}"

check_absent 'old completed_count' \
  "no old-query examples that rely on non-field snapshots in docs" \
  "${all_docs[@]}"

check_absent 'create Window\(' \
  "no create-Window-with-parens examples in docs" \
  "${all_docs[@]}"

check_absent 'create [A-Z][A-Za-z_0-9]*\.make\(\.\.\.\)' \
  "no placeholder constructor calls with ellipsis in docs" \
  "${all_docs[@]}"

# Count code fences in all docs. An odd count usually means a broken block.
while IFS= read -r file; do
  count=$( (rg -n '^```' "$file" 2>/dev/null || true) | wc -l | tr -d ' ' )
  if (( count % 2 != 0 )); then
    echo "FAIL: unbalanced fenced code blocks in $file ($count fences)"
    status=1
  fi
done < <(find docs -name '*.md' -print)

if (( status != 0 )); then
  exit "$status"
fi

echo "All doc example checks passed."
