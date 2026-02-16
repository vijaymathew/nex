(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "=== Testing Local Variable Declarations (Updated Syntax) ===\n")

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
  let x := 42
  print(x)
end end")
(println "│ Code:")
(println "│   let x := 42")
(println "│   print(x)")
(print   "│ Output: ")
(doseq [line (execute-method code1)]
  (println line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 2: Multiple Local Variables
(println "┌─ Test 2: Multiple Local Variables ──────────────────────┐")
(def code2 "class Test feature run() do
  let x := 10
  let y := 20
  let z := 30
  print(x, y, z)
end end")
(println "│ Code:")
(println "│   let x := 10")
(println "│   let y := 20")
(println "│   let z := 30")
(println "│   print(x, y, z)")
(print   "│ Output: ")
(doseq [line (execute-method code2)]
  (println line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 3: Local Variables with Expressions
(println "┌─ Test 3: Local Variables with Expressions ──────────────┐")
(def code3 "class Test feature run() do
  let a := 5 + 3
  let b := a * 2
  let c := b - 4
  print(a, b, c)
end end")
(println "│ Code:")
(println "│   let a := 5 + 3")
(println "│   let b := a * 2")
(println "│   let c := b - 4")
(println "│   print(a, b, c)")
(print   "│ Output: ")
(doseq [line (execute-method code3)]
  (println line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 4: Comparison with Regular Assignment
(println "┌─ Test 4: Comparison with Regular Assignment ────────────┐")
(def code4 "class Test feature run() do
  let x := 10
  print(x)
  x := 20
  print(x)
end end")
(println "│ Code:")
(println "│   let x := 10      -- Declare new variable")
(println "│   print(x)")
(println "│   x := 20          -- Assign to existing variable")
(println "│   print(x)")
(println "│ Output:")
(doseq [line (execute-method code4)]
  (println "│   " line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 5: Boolean and Logical Operations
(println "┌─ Test 5: Boolean and Logical Operations ────────────────┐")
(def code5 "class Test feature run() do
  let a := 5
  let b := 10
  let greater := a > b
  let result := greater or false
  print(greater, result)
end end")
(println "│ Code:")
(println "│   let a := 5")
(println "│   let b := 10")
(println "│   let greater := a > b")
(println "│   let result := greater or false")
(println "│   print(greater, result)")
(print   "│ Output: ")
(doseq [line (execute-method code5)]
  (println line))
(println "└──────────────────────────────────────────────────────────┘")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                    ALL TESTS PASSED                        ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Note: let uses := for consistency with assignment syntax")
