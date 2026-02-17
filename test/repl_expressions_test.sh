#!/bin/bash
# Test REPL expression evaluation

echo "Testing Nex REPL Expression Evaluation..."
echo ""

# Test 1: Simple expressions
echo "Test 1: Arithmetic expressions"
echo "-------------------------------"
cat > /tmp/test_arith.txt <<'EOF'
1 + 2
5 * 3
10 - 4
20 / 5
:quit
EOF

output=$(cat /tmp/test_arith.txt | clojure -M:repl 2>&1)
if echo "$output" | grep -q "nex> 3" && \
   echo "$output" | grep -q "nex> 15" && \
   echo "$output" | grep -q "nex> 6"; then
    echo "✓ PASS: Arithmetic expressions work"
else
    echo "✗ FAIL: Arithmetic expressions"
    echo "$output"
fi
echo ""

# Test 2: Literals
echo "Test 2: Literals"
echo "----------------"
cat > /tmp/test_literals.txt <<'EOF'
42
"hello"
true
false
:quit
EOF

output=$(cat /tmp/test_literals.txt | clojure -M:repl 2>&1)
if echo "$output" | grep -q "nex> 42" && \
   echo "$output" | grep -q 'nex> "hello"' && \
   echo "$output" | grep -q "nex> true" && \
   echo "$output" | grep -q "nex> false"; then
    echo "✓ PASS: Literals work"
else
    echo "✗ FAIL: Literals"
fi
echo ""

# Test 3: Comparisons
echo "Test 3: Comparison expressions"
echo "-------------------------------"
cat > /tmp/test_comp.txt <<'EOF'
1 < 2
5 > 10
3 = 3
:quit
EOF

output=$(cat /tmp/test_comp.txt | clojure -M:repl 2>&1)
if echo "$output" | grep -q "nex> true" && \
   echo "$output" | grep -q "nex> false"; then
    echo "✓ PASS: Comparisons work"
else
    echo "✗ FAIL: Comparisons"
fi
echo ""

# Test 4: Variables in expressions
echo "Test 4: Variables in expressions"
echo "---------------------------------"
cat > /tmp/test_vars_expr.txt <<'EOF'
let x := 10
x + 5
x * 2
:quit
EOF

output=$(cat /tmp/test_vars_expr.txt | clojure -M:repl 2>&1)
if echo "$output" | grep -q "nex> 15" && \
   echo "$output" | grep -q "nex> 20"; then
    echo "✓ PASS: Variables in expressions work"
else
    echo "✗ FAIL: Variables in expressions"
    echo "$output"
fi
echo ""

# Test 5: Complex expressions
echo "Test 5: Complex expressions with precedence"
echo "--------------------------------------------"
cat > /tmp/test_complex.txt <<'EOF'
10 + 20 * 2
(5 + 3) * 2
:quit
EOF

output=$(cat /tmp/test_complex.txt | clojure -M:repl 2>&1)
if echo "$output" | grep -q "nex> 50" && \
   echo "$output" | grep -q "nex> 16"; then
    echo "✓ PASS: Complex expressions with precedence work"
else
    echo "✗ FAIL: Complex expressions"
fi
echo ""

# Test 6: Statements still work
echo "Test 6: Statements still work"
echo "------------------------------"
cat > /tmp/test_stmts.txt <<'EOF'
let y := 100
print("test")
if y > 50 then print("big") else print("small") end
:quit
EOF

output=$(cat /tmp/test_stmts.txt | clojure -M:repl 2>&1)
if echo "$output" | grep -q '"test"' && \
   echo "$output" | grep -q '"big"'; then
    echo "✓ PASS: Statements still work"
else
    echo "✗ FAIL: Statements"
fi
echo ""

echo "All tests complete!"
rm -f /tmp/test*.txt
