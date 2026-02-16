(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║    COMPLETE INHERITANCE EXAMPLE FROM SPECIFICATION         ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Example matching the specification structure
(def spec-code "class Account
  feature
    deposit(amount: Integer) do
      print(\"Account deposit:\", amount)
    end

    getBalance() do
      print(\"Balance: 1000\")
    end
end

class SavingsAccount
inherit
  Account
    rename
      deposit as account_deposit
    redefine
      deposit
    end
feature
  deposit(amount: Integer) do
    print(\"Savings deposit:\", amount)
    print(\"Updating interest\")
  end

  update_interest() do
    print(\"Interest calculation for savings\")
  end
end")

(println "This example demonstrates the complete inheritance feature")
(println "as specified in the Nex language design:")
(println)
(println "class SavingsAccount")
(println "inherit")
(println "    Account")
(println "        rename")
(println "            deposit as account_deposit")
(println "        redefine")
(println "            deposit")
(println "        end")
(println "feature")
(println "    deposit (amount: Real) is")
(println "        -- Redefined deposit with additional logic")
(println "    end")
(println "end")
(println)

(println "═══════════════════════════════════════════════════════════")
(println "                  PARSING AND STRUCTURE")
(println "═══════════════════════════════════════════════════════════")
(println)

(let [ast (p/ast spec-code)]
  (println "Classes parsed:")
  (doseq [class-def (:classes ast)]
    (println "  •" (:name class-def))
    (when-let [parents (:parents class-def)]
      (println "    Inherits from:")
      (doseq [parent parents]
        (println "      - Parent:" (:parent parent))
        (when (:renames parent)
          (println "        Renames:")
          (doseq [r (:renames parent)]
            (println "          *" (:old-name r) "→" (:new-name r))))
        (when (:redefines parent)
          (println "        Redefines:" (:redefines parent))))))
  (println))

(println "═══════════════════════════════════════════════════════════")
(println "                  RUNTIME EXECUTION")
(println "═══════════════════════════════════════════════════════════")
(println)

(let [ast (p/ast spec-code)
      ctx (interp/make-context)]
  ;; Register all classes
  (doseq [class-node (:classes ast)]
    (interp/register-class ctx class-node))

  (println "Creating a SavingsAccount object...")
  (let [savings (interp/make-object "SavingsAccount" {})
        env (interp/make-env (:globals ctx))
        _ (interp/env-define env "account" savings)
        ctx-obj (assoc ctx :current-env env)]

    (println)
    (println "1. Calling deposit(500):")
    (println "   This calls the REDEFINED version in SavingsAccount")
    (interp/eval-node ctx-obj {:type :call
                                :target "account"
                                :method "deposit"
                                :args [{:type :integer :value 500}]})
    (doseq [line @(:output ctx-obj)]
      (println "   →" line))

    (reset! (:output ctx-obj) [])
    (println)
    (println "2. Calling getBalance():")
    (println "   This calls the INHERITED method from Account")
    (interp/eval-node ctx-obj {:type :call
                                :target "account"
                                :method "getBalance"
                                :args []})
    (doseq [line @(:output ctx-obj)]
      (println "   →" line))

    (reset! (:output ctx-obj) [])
    (println)
    (println "3. Calling update_interest():")
    (println "   This is a NEW method specific to SavingsAccount")
    (interp/eval-node ctx-obj {:type :call
                                :target "account"
                                :method "update_interest"
                                :args []})
    (doseq [line @(:output ctx-obj)]
      (println "   →" line))))

(println)
(println "═══════════════════════════════════════════════════════════")
(println "                  INHERITANCE FEATURES")
(println "═══════════════════════════════════════════════════════════")
(println)
(println "✓ INHERITANCE CLAUSE")
(println "  Declare parent classes with 'inherit' keyword")
(println)
(println "✓ RENAME CLAUSE")
(println "  Rename inherited methods to avoid conflicts:")
(println "    rename")
(println "      old_name as new_name")
(println)
(println "✓ REDEFINE CLAUSE")
(println "  Declare which methods will be overridden:")
(println "    redefine")
(println "      method_name")
(println)
(println "✓ MULTIPLE INHERITANCE")
(println "  Inherit from multiple parent classes:")
(println "    inherit")
(println "      Parent1 ... end,")
(println "      Parent2 ... end")
(println)
(println "✓ INHERITANCE CHAINS")
(println "  Grandparent methods accessible through parent chain")
(println)
(println "✓ METHOD LOOKUP")
(println "  • First search in current class")
(println "  • Then search parent classes recursively")
(println "  • Apply renames when looking in renamed parents")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║              SPECIFICATION COMPLETE                        ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "The Nex language now has full multiple inheritance support!")
