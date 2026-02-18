(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║            NEX INTERPRETER DEMONSTRATION                  ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Test 1: Basic Arithmetic
(println "┌─ Test 1: Basic Arithmetic ──────────────────────────────┐")
(let [code "print(3 + 4 * 2, 10 - 6 / 2)"
      ast (p/ast code)]
  (println "│ Code:  " code)
  (print   "│ Output: ")
  (interp/run ast))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 2: Comparison and Logical Operations
(println "┌─ Test 2: Comparison and Logical Operations ─────────────┐")
(let [code "print(5 > 3, 2 < 1, true and false, true or false)"
      ast (p/ast code)]
  (println "│ Code:  " code)
  (print   "│ Output: ")
  (interp/run ast))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 3: Unary Operations
(println "┌─ Test 3: Unary Operations ───────────────────────────────┐")
(let [code "print(-42, -(-10), -(5 + 3))"
      ast (p/ast code)]
  (println "│ Code:  " code)
  (print   "│ Output: ")
  (interp/run ast))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 4: String Literals
(println "┌─ Test 4: String Literals ────────────────────────────────┐")
(let [code "print(\"Hello\", \"World\", \"from Nex!\")"
      ast (p/ast code)]
  (println "│ Code:  " code)
  (print   "│ Output: ")
  (interp/run ast))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 5: Complex Expressions
(println "┌─ Test 5: Complex Expressions ────────────────────────────┐")
(let [code "print((10 + 5) * 2 - 3 / 3)"
      ast (p/ast code)]
  (println "│ Code:  " code)
  (print   "│ Output: ")
  (interp/run ast))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 6: Class Definition
(println "┌─ Test 6: Class Definition ───────────────────────────────┐")
(def class-code "class Point
  feature
    x: Integer
    y: Integer
  create
    make(px: Integer, py: Integer) do
      x := px
      y := py
    end
  feature
    show() do
      print(x, y)
    end
end")

(let [ast (p/ast class-code)
      ctx (interp/interpret ast)]
  (println "│ Class 'Point' defined with:")
  (println "│   - Fields: x, y")
  (println "│   - Constructor: make(px, py)")
  (println "│   - Method: show()")
  (println "│")
  (println "│ Registered classes:" (keys @(:classes ctx))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 7: Operator Precedence
(println "┌─ Test 7: Operator Precedence ────────────────────────────┐")
(let [code "print(2 + 3 * 4, (2 + 3) * 4)"
      ast (p/ast code)]
  (println "│ Code:  " code)
  (print   "│ Output: ")
  (interp/run ast))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 8: Boolean Values
(println "┌─ Test 8: Boolean Values ─────────────────────────────────┐")
(let [code "print(true, false, 5 = 5, 3 /= 4)"
      ast (p/ast code)]
  (println "│ Code:  " code)
  (print   "│ Output: ")
  (interp/run ast))
(println "└──────────────────────────────────────────────────────────┘")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                    DEMONSTRATION COMPLETE                  ║")
(println "╚════════════════════════════════════════════════════════════╝")
