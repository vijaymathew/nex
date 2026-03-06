(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║           GCD ALGORITHM WITH LOOP CONTRACTS                ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "This demonstrates the complete loop construct with:")
(println "  • Initialization (from)")
(println "  • Loop invariants (invariant)")
(println "  • Loop variant (variant)")
(println "  • Exit condition (until)")
(println "  • Loop body (do...end)")
(println)

;; GCD algorithm exactly as shown in the specification
(def gcd-code "class Math
  feature
    gcd(a: Integer, b: Integer) do
      from
        let x := a
        let y := b
      invariant
        x_positive: x > 0
        y_positive: y > 0
      variant
        x + y
      until
        x = y
      do
        if x > y then
          x := x - y
        else
          y := y - x
        end
      end
      print(x)
    end
end")

(println "═══════════════════════════════════════════════════════════")
(println "                      CODE")
(println "═══════════════════════════════════════════════════════════")
(println)
(println gcd-code)
(println)

(println "═══════════════════════════════════════════════════════════")
(println "                      EXECUTION")
(println "═══════════════════════════════════════════════════════════")
(println)

(defn execute-gcd [a b]
  (let [ast (p/ast gcd-code)
        ctx (interp/make-context)
        _ (interp/register-class ctx (first (:classes ast)))
        method-def (-> ast :classes first :body first :members first)
        method-env (interp/make-env (:globals ctx))
        _ (do
            (interp/env-define method-env "a" a)
            (interp/env-define method-env "b" b))
        ctx-with-env (assoc ctx :current-env method-env)]
    (doseq [stmt (:body method-def)]
      (interp/eval-node ctx-with-env stmt))
    (first @(:output ctx-with-env))))

(println "Test Case 1: gcd(48, 18)")
(println "  result:" (execute-gcd 48 18))
(println "  Expected: 6")
(println)

(println "Test Case 2: gcd(24, 18)")
(println "  result:" (execute-gcd 24 18))
(println "  Expected: 6")
(println)

(println "Test Case 3: gcd(100, 35)")
(println "  result:" (execute-gcd 100 35))
(println "  Expected: 5")
(println)

(println "Test Case 4: gcd(17, 19)")
(println "  result:" (execute-gcd 17 19))
(println "  Expected: 1 (coprime)")
(println)

(println "Test Case 5: gcd(54, 24)")
(println "  result:" (execute-gcd 54 24))
(println "  Expected: 6")
(println)

(println)
(println "═══════════════════════════════════════════════════════════")
(println "                   CONTRACT ENFORCEMENT")
(println "═══════════════════════════════════════════════════════════")
(println)
(println "Throughout execution:")
(println "  ✓ Invariants (x > 0, y > 0) are checked before and after")
(println "    each iteration")
(println "  ✓ Variant (x + y) strictly decreases on each iteration")
(println "  ✓ Loop terminates when x = y (the GCD)")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                 DEMONSTRATION COMPLETE                     ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "The Nex language now has complete loop support with:")
(println "  ✓ Initialization clause (from)")
(println "  ✓ Loop invariants (invariant)")
(println "  ✓ Loop variants (variant)")
(println "  ✓ Termination condition (until)")
(println "  ✓ All contracts are enforced at runtime")
