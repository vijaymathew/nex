#!/bin/bash
# Test typed let in REPL

cd "$(dirname "$0")"

echo "Testing typed let syntax in REPL..."
echo ""

{
  echo "let x: Integer := 100"
  echo "print(x)"
  echo "let y := 200"
  echo "print(y)"
  echo "let z: String := \"hello\""
  echo "print(z)"
  echo ":quit"
} | clojure -M:repl | sed 's/^nex> *//' | \
    grep -v -E '^(WARNING:|Feb [0-9]|java\\.|\\s*at |╔|║|╚|Type :help|Goodbye!|$)'
