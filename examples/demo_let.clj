(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║       LOCAL VARIABLE DECLARATIONS DEMONSTRATION           ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Helper function to execute a method body
(defn execute-method [code]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (interp/register-class ctx (first (:classes ast)))
        method-body (-> ast :classes first :body first :members first :body)
        method-env (interp/make-env (:globals ctx))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt method-body]
      (interp/eval-node ctx-with-env stmt))
    @(:output ctx-with-env)))

;; Test 1: Simple Local Variable
(println "┌─ Test 1: Simple Local Variable ─────────────────────────┐")
(def code1 "class Test feature run() do
  let x = 42
  print(x)
end end")
(println "│ Code:")
(println "│   let x = 42")
(println "│   print(x)")
(print   "│ Output: ")
(doseq [line (execute-method code1)]
  (println line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 2: Multiple Local Variables
(println "┌─ Test 2: Multiple Local Variables ──────────────────────┐")
(def code2 "class Test feature run() do
  let x = 10
  let y = 20
  let z = 30
  print(x, y, z)
end end")
(println "│ Code:")
(println "│   let x = 10")
(println "│   let y = 20")
(println "│   let z = 30")
(println "│   print(x, y, z)")
(print   "│ Output: ")
(doseq [line (execute-method code2)]
  (println line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 3: Local Variables with Expressions
(println "┌─ Test 3: Local Variables with Expressions ──────────────┐")
(def code3 "class Test feature run() do
  let a = 5 + 3
  let b = a * 2
  let c = b - 4
  print(a, b, c)
end end")
(println "│ Code:")
(println "│   let a = 5 + 3")
(println "│   let b = a * 2")
(println "│   let c = b - 4")
(println "│   print(a, b, c)")
(print   "│ Output: ")
(doseq [line (execute-method code3)]
  (println line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 4: Complex Calculations
(println "┌─ Test 4: Complex Calculations ──────────────────────────┐")
(def code4 "class Test feature run() do
  let width = 10
  let height = 20
  let area = width * height
  let perimeter = 2 * (width + height)
  print(area, perimeter)
end end")
(println "│ Code:")
(println "│   let width = 10")
(println "│   let height = 20")
(println "│   let area = width * height")
(println "│   let perimeter = 2 * (width + height)")
(println "│   print(area, perimeter)")
(print   "│ Output: ")
(doseq [line (execute-method code4)]
  (println line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 5: Interleaved with Other Statements
(println "┌─ Test 5: Interleaved with Other Statements ─────────────┐")
(def code5 "class Test feature run() do
  print(1)
  let x = 2
  print(x)
  let y = x + 1
  print(y)
  let z = y * 2
  print(z)
end end")
(println "│ Code:")
(println "│   print(1)")
(println "│   let x = 2")
(println "│   print(x)")
(println "│   let y = x + 1")
(println "│   print(y)")
(println "│   let z = y * 2")
(println "│   print(z)")
(println "│ Output:")
(doseq [line (execute-method code5)]
  (println "│   " line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 6: Comparison with Assignment
(println "┌─ Test 6: Comparison with Assignment (:=) ───────────────┐")
(def code6 "class Test feature run() do
  let x = 10
  print(x)
  x := 20
  print(x)
end end")
(println "│ Code:")
(println "│   let x = 10      -- Declare new variable")
(println "│   print(x)")
(println "│   x := 20         -- Assign to existing variable")
(println "│   print(x)")
(println "│ Output:")
(doseq [line (execute-method code6)]
  (println "│   " line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 7: Boolean and Logical Operations
(println "┌─ Test 7: Boolean and Logical Operations ────────────────┐")
(def code7 "class Test feature run() do
  let a = 5
  let b = 10
  let greater = a > b
  let equal = a = b
  let result = greater or equal
  print(greater, equal, result)
end end")
(println "│ Code:")
(println "│   let a = 5")
(println "│   let b = 10")
(println "│   let greater = a > b")
(println "│   let equal = a = b")
(println "│   let result = greater or equal")
(println "│   print(greater, equal, result)")
(print   "│ Output: ")
(doseq [line (execute-method code7)]
  (println line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                    DEMONSTRATION COMPLETE                  ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Key Features:")
(println "  • let <var> = <expr>  -- Declares a new local variable")
(println "  • <var> := <expr>     -- Assigns to existing variable")
(println "  • Variables can be used in subsequent expressions")
(println "  • Local variables can be declared anywhere in method body")
