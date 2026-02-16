(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║          LOCAL VARIABLE SYNTAX DEMONSTRATION              ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Show the correct syntax
(println "✓ CORRECT SYNTAX: let x := value")
(println "  Uses := (assignment operator)")
(println)

(def good-code "class Test feature run() do
  let x := 10
  let y := 20
  let sum := x + y
  print(sum)
end end")

(println "Example:")
(println "  let x := 10")
(println "  let y := 20")
(println "  let sum := x + y")
(println "  print(sum)")
(println)

(let [ast (p/ast good-code)
      ctx (interp/make-context)
      _ (interp/register-class ctx (first (:classes ast)))
      method-body (-> ast :classes first :body first :members first :body)
      method-env (interp/make-env (:globals ctx))
      ctx-with-env (assoc ctx :current-env method-env)]

  (doseq [stmt method-body]
    (interp/eval-node ctx-with-env stmt))

  (println "Output:" (first @(:output ctx-with-env))))

(println)
(println "────────────────────────────────────────────────────────────")
(println)

;; Show the incorrect syntax
(println "✗ INCORRECT SYNTAX: let x = value")
(println "  Using = instead of := is not allowed")
(println)

(println "Attempting to parse:")
(println "  let x = 42")
(println)

(try
  (p/parse "class Test feature run() do let x = 42 end end")
  (println "  [This should not print]")
  (catch Exception e
    (println "Result: Parse Error")
    (println "  " (.getMessage e))))

(println)
(println "────────────────────────────────────────────────────────────")
(println)

;; Show the difference between let and regular assignment
(println "COMPARISON: let vs regular assignment")
(println)

(def compare-code "class Test feature run() do
  let x := 10
  print(x)
  x := 20
  print(x)
end end")

(println "Code:")
(println "  let x := 10    -- Declares new variable")
(println "  print(x)")
(println "  x := 20        -- Assigns to existing variable")
(println "  print(x)")
(println)

(let [ast (p/ast compare-code)
      ctx (interp/make-context)
      _ (interp/register-class ctx (first (:classes ast)))
      method-body (-> ast :classes first :body first :members first :body)
      method-env (interp/make-env (:globals ctx))
      ctx-with-env (assoc ctx :current-env method-env)]

  (doseq [stmt method-body]
    (interp/eval-node ctx-with-env stmt))

  (println "Output:")
  (doseq [line @(:output ctx-with-env)]
    (println "  " line)))

(println)
(println "╔════════════════════════════════════════════════════════════╗")
(println "║  Both 'let' and regular assignment use ':=' operator      ║")
(println "║  The 'let' keyword makes declaration explicit             ║")
(println "╚════════════════════════════════════════════════════════════╝")
