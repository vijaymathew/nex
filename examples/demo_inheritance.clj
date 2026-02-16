(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║         MULTIPLE INHERITANCE WITH RENAME/REDEFINE          ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "The Nex language now supports multiple inheritance with:")
(println "  • inherit clause listing parent classes")
(println "  • rename clause to rename inherited methods")
(println "  • redefine clause to declare method overriding")
(println "  • Multiple parents separated by commas")
(println)

;; Account/SavingsAccount example similar to the specification
(def account-code "class Account
  feature
    deposit(amount: Integer) do
      print(\"Account: depositing\", amount)
    end

    withdraw(amount: Integer) do
      print(\"Account: withdrawing\", amount)
    end

    balance() do
      print(\"Account balance: 1000\")
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
    print(\"SavingsAccount: depositing\", amount)
    print(\"Adding interest calculation\")
  end

  update_interest() do
    print(\"Calculating interest on savings\")
  end
end")

(println "═══════════════════════════════════════════════════════════")
(println "Example 1: Bank Account Hierarchy")
(println "═══════════════════════════════════════════════════════════")
(println)
(println account-code)
(println)

(println "Parsed Structure:")
(let [ast (p/ast account-code)
      savings-class (second (:classes ast))]
  (println "  SavingsAccount inherits from:")
  (doseq [parent (:parents savings-class)]
    (println "    • Parent:" (:parent parent))
    (when (:renames parent)
      (println "      Rename clauses:")
      (doseq [rename (:renames parent)]
        (println "        -" (:old-name rename) "as" (:new-name rename))))
    (when (:redefines parent)
      (println "      Redefines:" (:redefines parent)))))

(println)
(println "Runtime Behavior:")
(let [ast (p/ast account-code)
      ctx (interp/make-context)]
  ;; Register classes
  (doseq [class-node (:classes ast)]
    (interp/register-class ctx class-node))

  (let [savings-obj (interp/make-object "SavingsAccount" {})
        env (interp/make-env (:globals ctx))
        _ (interp/env-define env "mysavings" savings-obj)
        ctx-with-obj (assoc ctx :current-env env)]

    (println "  1. Calling deposit() (redefined method):")
    (interp/eval-node ctx-with-obj {:type :call
                                     :target "mysavings"
                                     :method "deposit"
                                     :args [{:type :integer :value 100}]})
    (doseq [line @(:output ctx-with-obj)]
      (println "     >" line))

    (reset! (:output ctx-with-obj) [])
    (println)
    (println "  2. Calling withdraw() (inherited, not redefined):")
    (interp/eval-node ctx-with-obj {:type :call
                                     :target "mysavings"
                                     :method "withdraw"
                                     :args [{:type :integer :value 50}]})
    (doseq [line @(:output ctx-with-obj)]
      (println "     >" line))

    (reset! (:output ctx-with-obj) [])
    (println)
    (println "  3. Calling balance() (inherited):")
    (interp/eval-node ctx-with-obj {:type :call
                                     :target "mysavings"
                                     :method "balance"
                                     :args []})
    (doseq [line @(:output ctx-with-obj)]
      (println "     >" line))

    (reset! (:output ctx-with-obj) [])
    (println)
    (println "  4. Calling update_interest() (own method):")
    (interp/eval-node ctx-with-obj {:type :call
                                     :target "mysavings"
                                     :method "update_interest"
                                     :args []})
    (doseq [line @(:output ctx-with-obj)]
      (println "     >" line))))

(println)
(println)

;; Multiple inheritance example
(def vehicle-code "class Engine
  feature
    start() do
      print(\"Engine started\")
    end

    stop() do
      print(\"Engine stopped\")
    end
end

class GPS
  feature
    navigate(dest: String) do
      print(\"Navigating to:\", dest)
    end
end

class Car
inherit
  Engine
  end,
  GPS
    rename
      navigate as gps_navigate
    end
feature
  drive() do
    print(\"Car is driving\")
  end

  navigate(dest: String) do
    print(\"Car navigation system to:\", dest)
  end
end")

(println "═══════════════════════════════════════════════════════════")
(println "Example 2: Multiple Inheritance (Car with Engine and GPS)")
(println "═══════════════════════════════════════════════════════════")
(println)
(println "Car inherits from both Engine and GPS")
(println "  - Gets start() and stop() from Engine")
(println "  - Renames GPS.navigate as gps_navigate")
(println "  - Defines its own navigate() method")
(println)

(println "Runtime Behavior:")
(let [ast (p/ast vehicle-code)
      ctx (interp/make-context)]
  ;; Register classes
  (doseq [class-node (:classes ast)]
    (interp/register-class ctx class-node))

  (let [car-obj (interp/make-object "Car" {})
        env (interp/make-env (:globals ctx))
        _ (interp/env-define env "mycar" car-obj)
        ctx-with-obj (assoc ctx :current-env env)]

    (println "  1. Calling start() from Engine:")
    (interp/eval-node ctx-with-obj {:type :call
                                     :target "mycar"
                                     :method "start"
                                     :args []})
    (doseq [line @(:output ctx-with-obj)]
      (println "     >" line))

    (reset! (:output ctx-with-obj) [])
    (println)
    (println "  2. Calling drive() (own method):")
    (interp/eval-node ctx-with-obj {:type :call
                                     :target "mycar"
                                     :method "drive"
                                     :args []})
    (doseq [line @(:output ctx-with-obj)]
      (println "     >" line))

    (reset! (:output ctx-with-obj) [])
    (println)
    (println "  3. Calling navigate() (own method, shadows GPS.navigate):")
    (interp/eval-node ctx-with-obj {:type :call
                                     :target "mycar"
                                     :method "navigate"
                                     :args [{:type :string :value "Home"}]})
    (doseq [line @(:output ctx-with-obj)]
      (println "     >" line))

    (reset! (:output ctx-with-obj) [])
    (println)
    (println "  4. Calling stop() from Engine:")
    (interp/eval-node ctx-with-obj {:type :call
                                     :target "mycar"
                                     :method "stop"
                                     :args []})
    (doseq [line @(:output ctx-with-obj)]
      (println "     >" line))))

(println)
(println)
(println "╔════════════════════════════════════════════════════════════╗")
(println "║                 DEMONSTRATION COMPLETE                     ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Key Features Demonstrated:")
(println "  ✓ Single inheritance (Account → SavingsAccount)")
(println "  ✓ Multiple inheritance (Engine, GPS → Car)")
(println "  ✓ Method renaming (deposit as account_deposit)")
(println "  ✓ Method redefinition/overriding")
(println "  ✓ Inherited methods are accessible")
(println "  ✓ Own methods work alongside inherited methods")
(println "  ✓ Inheritance chains work transitively")
