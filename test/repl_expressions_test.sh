#!/bin/bash
# Test REPL expression evaluation

echo "Testing Nex REPL Expression Evaluation..."
echo ""

clean_output() {
    echo "$1" | sed 's/^nex> *//' | \
        grep -v -E '^(WARNING:|Feb [0-9]|java\\.|\\s*at |╔|║|╚|Type :help|Goodbye!|$)'
}

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
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "^3$" && \
   echo "$cleaned" | grep -q "^15$" && \
   echo "$cleaned" | grep -q "^6$"; then
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
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "^42$" && \
   echo "$cleaned" | grep -q '^"hello"$' && \
   echo "$cleaned" | grep -q "^true$" && \
   echo "$cleaned" | grep -q "^false$"; then
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
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "^true$" && \
   echo "$cleaned" | grep -q "^false$"; then
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
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "^15$" && \
   echo "$cleaned" | grep -q "^20$"; then
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
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "^50$" && \
   echo "$cleaned" | grep -q "^16$"; then
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
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q '^"test"$' && \
   echo "$cleaned" | grep -q '^"big"$'; then
    echo "✓ PASS: Statements still work"
else
    echo "✗ FAIL: Statements"
fi
echo ""

# Test 7: Chained member access
echo "Test 7: Chained member access"
echo "------------------------------"
cat > /tmp/test_chain.txt <<'EOF'
class A feature x: Integer create make(newX: Integer) do x := newX end end
class C feature a: A create make(newX: Integer) do a := create A.make(newX) end end
let c: C := create C.make(10)
c.a.x
:quit
EOF

output=$(cat /tmp/test_chain.txt | clojure -M:repl 2>&1)
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "Class(es) registered: A" && \
   echo "$cleaned" | grep -q "Class(es) registered: C" && \
   echo "$cleaned" | grep -q "^10$"; then
    echo "✓ PASS: Chained member access works"
else
    echo "✗ FAIL: Chained member access"
    echo "$output"
fi
echo ""

# Test 8: :load command
echo "Test 8: :load command"
echo "----------------------"
cat > /tmp/repl_load_test.nex <<'EOF'
class LoadTest
  feature
    demo() do
      print("loaded")
    end
end
EOF

cat > /tmp/test_load.txt <<'EOF'
:load /tmp/repl_load_test.nex
:classes
:quit
EOF

output=$(cat /tmp/test_load.txt | clojure -M:repl 2>&1)
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "Class(es) registered: LoadTest" && \
   echo "$cleaned" | grep -q "• LoadTest"; then
    echo "✓ PASS: :load command works"
else
    echo "✗ FAIL: :load command"
    echo "$output"
fi
echo ""

# Test 9: :typecheck with :load imports
echo "Test 9: Typecheck with loaded imports"
echo "--------------------------------------"
cat > /tmp/test_tcp_socket.nex <<'EOF'
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

class TcpSocket
  feature
    host: String
    port: Integer
    socket: Socket
    reader: BufferedReader
    writer: PrintWriter
    connected: Boolean

  create
    connect(host_value: String, port_value: Integer) do
      host := host_value
      port := port_value
      socket := create Socket.make(host_value, port_value)
      reader := create BufferedReader.make(create InputStreamReader.make(socket.getInputStream()))
      writer := create PrintWriter.make(socket.getOutputStream(), true)
      connected := true
    end

  feature
    is_connected(): Boolean do
      result := connected
    end
end
EOF

cat > /tmp/test_tcp_repl.txt <<'EOF'
:typecheck on
:load /tmp/test_tcp_socket.nex
let client: TcpSocket := create TcpSocket.connect("google.com", 80)
:quit
EOF

output=$(cat /tmp/test_tcp_repl.txt | clojure -M:repl 2>&1)
cleaned=$(clean_output "$output")
if echo "$cleaned" | grep -q "Type checking enabled" && \
   echo "$cleaned" | grep -q "Class(es) registered: TcpSocket" && \
   ! echo "$cleaned" | grep -q "Type error"; then
    echo "✓ PASS: Typecheck with loaded imports works"
else
    echo "✗ FAIL: Typecheck with loaded imports"
    echo "$output"
fi
echo ""

echo "All tests complete!"
rm -f /tmp/test*.txt
rm -f /tmp/repl_load_test.nex
rm -f /tmp/test_tcp_socket.nex
