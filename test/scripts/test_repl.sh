#!/bin/bash
# Test script for Nex REPL

cd "$(dirname "$0")"

# Test basic REPL functionality with commands
echo "Testing Nex REPL..."
echo ""

# Send test commands to REPL
{
  echo "print(42)"
  echo "let x := 10"
  echo "print(x)"
  echo "let y := x + 5"
  echo "print(y)"
  echo ":quit"
} | clojure -M:repl
