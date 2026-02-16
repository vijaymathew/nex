(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "=== Testing Local Variable Evaluation ===\n")

;; Test the interpreter's let evaluation by manually calling eval-node
;; on method bodies

(println "Test 1: Simple let and print")
(let [code "class Test feature run() do let x = 42 print(x) end end"
      ast (p/ast code)
      ctx (interp/make-context)
      ;; Register the class
      _ (interp/register-class ctx (first (:classes ast)))
      ;; Get the method body
      method-body (-> ast :classes first :body first :members first :body)
      ;; Create a fresh environment for the method
      method-env (interp/make-env (:globals ctx))
      ;; Execute the method body
      ctx-with-env (assoc ctx :current-env method-env)]

  (println "Code:" code)
  (println "Executing method body...")

  (doseq [stmt method-body]
    (interp/eval-node ctx-with-env stmt))

  (println "Output:")
  (doseq [line @(:output ctx-with-env)]
    (println line))
  (println))

(println "Test 2: Multiple lets with expressions")
(let [code "class Test feature run() do let x = 5 + 3 let y = x * 2 print(x, y) end end"
      ast (p/ast code)
      ctx (interp/make-context)
      _ (interp/register-class ctx (first (:classes ast)))
      method-body (-> ast :classes first :body first :members first :body)
      method-env (interp/make-env (:globals ctx))
      ctx-with-env (assoc ctx :current-env method-env)]

  (println "Code:" code)
  (println "Executing method body...")

  (doseq [stmt method-body]
    (interp/eval-node ctx-with-env stmt))

  (println "Output:")
  (doseq [line @(:output ctx-with-env)]
    (println line))
  (println))

(println "Test 3: Let with complex expressions")
(let [code "class Test feature run() do let a = 10 let b = 20 let sum = a + b print(sum) end end"
      ast (p/ast code)
      ctx (interp/make-context)
      _ (interp/register-class ctx (first (:classes ast)))
      method-body (-> ast :classes first :body first :members first :body)
      method-env (interp/make-env (:globals ctx))
      ctx-with-env (assoc ctx :current-env method-env)]

  (println "Code:" code)
  (println "Executing method body...")

  (doseq [stmt method-body]
    (interp/eval-node ctx-with-env stmt))

  (println "Output:")
  (doseq [line @(:output ctx-with-env)]
    (println line))
  (println))

(println "Test 4: Let anywhere in method (interleaved with other statements)")
(let [code "class Test feature run() do print(1) let x = 2 print(x) let y = x + 1 print(y) end end"
      ast (p/ast code)
      ctx (interp/make-context)
      _ (interp/register-class ctx (first (:classes ast)))
      method-body (-> ast :classes first :body first :members first :body)
      method-env (interp/make-env (:globals ctx))
      ctx-with-env (assoc ctx :current-env method-env)]

  (println "Code:" code)
  (println "Executing method body...")

  (doseq [stmt method-body]
    (interp/eval-node ctx-with-env stmt))

  (println "Output:")
  (doseq [line @(:output ctx-with-env)]
    (println line))
  (println))

(println "All tests complete!")
