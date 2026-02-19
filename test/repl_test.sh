#!/bin/bash
# Test REPL exit behavior

echo "Testing Nex REPL exit behavior..."
echo ""

clean_output() {
    echo "$1" | sed 's/^nex> *//' | \
        grep -v -E '^(WARNING:|Feb [0-9]|java\\.|\\s*at |╔|║|╚|Type :help|Goodbye!|$)'
}

# Test 1: Empty lines should not exit
echo "Test 1: Empty lines should not cause exit"
echo "----------------------------------------"
cat > /tmp/test1.txt <<'EOF'


print("still running")

:quit
EOF

output=$(cat /tmp/test1.txt | clojure -M:repl 2>&1)
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "still running"; then
    echo "✓ PASS: Empty lines do not exit REPL"
else
    echo "✗ FAIL: REPL exited on empty line"
    echo "$output"
fi
echo ""

# Test 2: :quit should exit
echo "Test 2: :quit command should exit"
echo "----------------------------------"
cat > /tmp/test2.txt <<'EOF'
print("before quit")
:quit
print("after quit")
EOF

output=$(cat /tmp/test2.txt | clojure -M:repl 2>&1)
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "before quit" && ! echo "$cleaned" | grep -q "after quit"; then
    echo "✓ PASS: :quit exits REPL"
else
    echo "✗ FAIL: :quit did not work as expected"
    echo "$output"
fi
echo ""

# Test 3: :q should exit (alias)
echo "Test 3: :q alias should exit"
echo "-----------------------------"
cat > /tmp/test3.txt <<'EOF'
print("before q")
:q
print("after q")
EOF

output=$(cat /tmp/test3.txt | clojure -M:repl 2>&1)
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "before q" && ! echo "$cleaned" | grep -q "after q"; then
    echo "✓ PASS: :q exits REPL"
else
    echo "✗ FAIL: :q did not work as expected"
fi
echo ""

# Test 4: EOF (Ctrl+D) should exit
echo "Test 4: EOF should exit gracefully"
echo "-----------------------------------"
cat > /tmp/test4.txt <<'EOF'
print("before EOF")
EOF

output=$(cat /tmp/test4.txt | clojure -M:repl 2>&1)
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "before EOF"; then
    echo "✓ PASS: EOF exits REPL gracefully"
else
    echo "✗ FAIL: EOF did not exit gracefully"
fi
echo ""

# Test 5: Multiple empty lines
echo "Test 5: Multiple consecutive empty lines"
echo "-----------------------------------------"
cat > /tmp/test5.txt <<'EOF'




print("survived empty lines")
:quit
EOF

output=$(cat /tmp/test5.txt | clojure -M:repl 2>&1)
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "survived empty lines"; then
    echo "✓ PASS: Multiple empty lines handled correctly"
else
    echo "✗ FAIL: REPL exited on multiple empty lines"
fi
echo ""

echo "All tests complete!"
rm -f /tmp/test*.txt
