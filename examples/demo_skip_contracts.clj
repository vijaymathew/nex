(require '[nex.generator.java :as java])

(def nex-code "
class Account
  feature
    balance: Integer

    deposit(amount: Integer)
      require
        positive: amount > 0
      do
        let balance := balance + amount
      ensure
        increased: balance >= 0
      end

    withdraw(amount: Integer)
      require
        sufficient: balance >= amount
        positive: amount > 0
      do
        let balance := balance - amount
      ensure
        valid: balance >= 0
      end

  invariant
    non_negative: balance >= 0
end")

(println "╔════════════════════════════════════════════════════════════╗")
(println "║          CONTRACT SKIPPING DEMONSTRATION                   ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

(println "NEX CODE:")
(println nex-code)
(println)

(println "═══════════════════════════════════════════════════════════")
(println "JAVA CODE WITH CONTRACTS (Development Build)")
(println "═══════════════════════════════════════════════════════════")
(println)
(println (java/translate nex-code))
(println)

(println "═══════════════════════════════════════════════════════════")
(println "JAVA CODE WITHOUT CONTRACTS (Production Build)")
(println "═══════════════════════════════════════════════════════════")
(println)
(println (java/translate nex-code {:skip-contracts true}))
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                     COMPARISON                             ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Development Build:")
(println "  ✓ Includes assert statements for preconditions")
(println "  ✓ Includes assert statements for postconditions")
(println "  ✓ Includes class invariant comment")
(println "  ✓ Runtime contract checking enabled")
(println)
(println "Production Build:")
(println "  ✓ No assert statements (better performance)")
(println "  ✓ No contract comments")
(println "  ✓ Cleaner, more compact code")
(println "  ✓ No runtime overhead from assertions")
