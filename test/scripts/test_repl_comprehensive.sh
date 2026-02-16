#!/bin/bash
# Comprehensive test for Nex REPL

cd "$(dirname "$0")"

echo "╔════════════════════════════════════════════════════════════╗"
echo "║           NEX REPL COMPREHENSIVE TEST                      ║"
echo "╚════════════════════════════════════════════════════════════╝"
echo ""

# Test various REPL features
{
  echo "print(42)"
  echo "let x := 10"
  echo "print(x)"
  echo "let y := x + 5"
  echo "print(y)"
  echo "if x > 5 then print(\"big\") else print(\"small\") end"
  cat <<'EOF'
class Point
  feature
    x: Integer
    y: Integer

    distance() do
      print(x * x + y * y)
    end
end
EOF
  echo ":classes"
  echo ":vars"
  echo ":quit"
} | clojure -M:repl
