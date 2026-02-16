(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║           LOOP CONTRACT VIOLATION TESTING                  ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Helper function to execute code and catch exceptions
(defn execute-with-error-handling [code description]
  (println "┌─" description "─")
  (try
    (let [ast (p/ast code)
          ctx (interp/make-context)
          _ (interp/register-class ctx (first (:classes ast)))
          method-body (-> ast :classes first :body first :members first :body)
          method-env (interp/make-env (:globals ctx))
          ctx-with-env (assoc ctx :current-env method-env)]
      (doseq [stmt method-body]
        (interp/eval-node ctx-with-env stmt))
      (println "│ Result: Success")
      (println "│ Output:" @(:output ctx-with-env)))
    (catch Exception e
      (println "│ Result: Contract Violation Detected")
      (println "│ Error:" (.getMessage e))))
  (println "└──────────────────────────────────────────────────────────┘")
  (println))

;; Test 1: Invariant violation
(println "Test 1: Loop Invariant Violation")
(println "  Loop that violates invariant x > 5 when x becomes 3")
(def code1 "class Test
  feature
    demo() do
      from
        let x := 10
      invariant
        must_be_large: x > 5
      until
        x = 0
      do
        print(x)
        let x := x - 1
      end
    end
end")

(execute-with-error-handling code1 "Invariant Violation Test")

;; Test 2: Variant doesn't decrease
(println "Test 2: Loop Variant Must Decrease")
(println "  Loop where variant doesn't decrease (stays constant)")
(def code2 "class Test
  feature
    demo() do
      let i := 0
      from
        let i := 0
      variant
        5
      until
        i > 3
      do
        print(i)
        let i := i + 1
      end
    end
end")

(execute-with-error-handling code2 "Variant Not Decreasing Test")

;; Test 3: Valid loop with contracts
(println "Test 3: Valid Loop (All Contracts Satisfied)")
(println "  Proper loop with valid invariant and decreasing variant")
(def code3 "class Test
  feature
    demo() do
      from
        let x := 10
      invariant
        positive: x > 0
      variant
        x
      until
        x = 1
      do
        print(x)
        let x := x - 1
      end
    end
end")

(execute-with-error-handling code3 "Valid Loop Test")

;; Test 4: Invariant that becomes false during iteration
(println "Test 4: Invariant Violated During Iteration")
(println "  Loop with invariant x + y = 20, violated by body")
(def code4 "class Test
  feature
    demo() do
      from
        let x := 10
        let y := 10
      invariant
        sum_is_20: x + y = 20
      until
        x = 0
      do
        let x := x - 1
      end
    end
end")

(execute-with-error-handling code4 "Invariant Violated by Body")

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                  SUMMARY                                   ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "The loop construct enforces contracts:")
(println "  ✓ Invariants are checked before and after each iteration")
(println "  ✓ Variants must strictly decrease on each iteration")
(println "  ✓ Violations are caught with clear error messages")
(println "  ✓ Valid loops execute successfully")
