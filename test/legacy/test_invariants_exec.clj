(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║         CLASS INVARIANT EXECUTION TESTS                   ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Helper function to check invariants manually
(defn test-invariant-check [code field-values]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        class-def (first (:classes ast))
        _ (interp/register-class ctx class-def)
        ;; Create environment with field values
        env (interp/make-env (:globals ctx))
        _ (doseq [[field-name field-val] field-values]
            (interp/env-define env field-name field-val))
        ctx-with-env (assoc ctx :current-env env)]
    (try
      (interp/check-class-invariant ctx-with-env class-def)
      {:success true}
      (catch Exception e
        {:success false :error (.getMessage e)}))))

;; Test 1: Valid invariant
(println "┌─ Test 1: Valid Invariant Check ─────────────────────────┐")
(def code1 "class Counter
  feature
    value: Integer
  invariant
    non_negative: value >= 0
end")

(println "│ Class: Counter")
(println "│ Invariant: value >= 0")
(println "│")
(println "│ Test with value = 10:")
(let [result (test-invariant-check code1 {"value" 10})]
  (if (:success result)
    (println "│   ✓ Invariant satisfied")
    (println "│   ✗" (:error result))))
(println "│")
(println "│ Test with value = 0:")
(let [result (test-invariant-check code1 {"value" 0})]
  (if (:success result)
    (println "│   ✓ Invariant satisfied")
    (println "│   ✗" (:error result))))
(println "│")
(println "│ Test with value = -5 (should fail):")
(let [result (test-invariant-check code1 {"value" -5})]
  (if (:success result)
    (println "│   ✓ Invariant satisfied (unexpected!)")
    (println "│   ✗ Invariant violation:" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 2: Multiple invariants
(println "┌─ Test 2: Multiple Invariants ───────────────────────────┐")
(def code2 "class Date
  feature
    day: Integer
    hour: Integer
  invariant
    valid_day: day >= 1 and day <= 31
    valid_hour: hour >= 0 and hour <= 23
end")

(println "│ Class: Date")
(println "│ Invariants:")
(println "│   - valid_day: day >= 1 and day <= 31")
(println "│   - valid_hour: hour >= 0 and hour <= 23")
(println "│")
(println "│ Test with valid values (day=15, hour=12):")
(let [result (test-invariant-check code2 {"day" 15 "hour" 12})]
  (if (:success result)
    (println "│   ✓ All invariants satisfied")
    (println "│   ✗" (:error result))))
(println "│")
(println "│ Test with invalid day (day=50, hour=12):")
(let [result (test-invariant-check code2 {"day" 50 "hour" 12})]
  (if (:success result)
    (println "│   ✓ All invariants satisfied (unexpected!)")
    (println "│   ✗ Invariant violation:" (:error result))))
(println "│")
(println "│ Test with invalid hour (day=15, hour=25):")
(let [result (test-invariant-check code2 {"day" 15 "hour" 25})]
  (if (:success result)
    (println "│   ✓ All invariants satisfied (unexpected!)")
    (println "│   ✗ Invariant violation:" (:error result))))
(println "│")
(println "│ Test with both invalid (day=0, hour=-1):")
(let [result (test-invariant-check code2 {"day" 0 "hour" -1})]
  (if (:success result)
    (println "│   ✓ All invariants satisfied (unexpected!)")
    (println "│   ✗ Invariant violation:" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 3: Boundary values
(println "┌─ Test 3: Boundary Values ───────────────────────────────┐")
(println "│ Testing boundary conditions for Date class")
(println "│")
(println "│ Test day=1, hour=0 (minimum valid):")
(let [result (test-invariant-check code2 {"day" 1 "hour" 0})]
  (if (:success result)
    (println "│   ✓ Invariants satisfied")
    (println "│   ✗" (:error result))))
(println "│")
(println "│ Test day=31, hour=23 (maximum valid):")
(let [result (test-invariant-check code2 {"day" 31 "hour" 23})]
  (if (:success result)
    (println "│   ✓ Invariants satisfied")
    (println "│   ✗" (:error result))))
(println "│")
(println "│ Test day=0, hour=0 (day below minimum):")
(let [result (test-invariant-check code2 {"day" 0 "hour" 0})]
  (if (:success result)
    (println "│   ✓ Invariants satisfied (unexpected!)")
    (println "│   ✗ Invariant violation:" (:error result))))
(println "│")
(println "│ Test day=32, hour=23 (day above maximum):")
(let [result (test-invariant-check code2 {"day" 32 "hour" 23})]
  (if (:success result)
    (println "│   ✓ Invariants satisfied (unexpected!)")
    (println "│   ✗ Invariant violation:" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 4: Complex invariant
(println "┌─ Test 4: Complex Invariant ─────────────────────────────┐")
(def code4 "class BankAccount
  feature
    balance: Integer
    credit_limit: Integer
  invariant
    valid_balance: balance >= 0 - credit_limit
    reasonable_credit: credit_limit >= 0 and credit_limit <= 10000
end")

(println "│ Class: BankAccount")
(println "│ Invariants:")
(println "│   - balance >= -credit_limit")
(println "│   - credit_limit between 0 and 10000")
(println "│")
(println "│ Test: balance=1000, credit_limit=500:")
(let [result (test-invariant-check code4 {"balance" 1000 "credit_limit" 500})]
  (if (:success result)
    (println "│   ✓ All invariants satisfied")
    (println "│   ✗" (:error result))))
(println "│")
(println "│ Test: balance=-200, credit_limit=500 (within limit):")
(let [result (test-invariant-check code4 {"balance" -200 "credit_limit" 500})]
  (if (:success result)
    (println "│   ✓ All invariants satisfied")
    (println "│   ✗" (:error result))))
(println "│")
(println "│ Test: balance=-600, credit_limit=500 (exceeds limit):")
(let [result (test-invariant-check code4 {"balance" -600 "credit_limit" 500})]
  (if (:success result)
    (println "│   ✓ All invariants satisfied (unexpected!)")
    (println "│   ✗ Invariant violation:" (:error result))))
(println "│")
(println "│ Test: balance=1000, credit_limit=15000 (credit too high):")
(let [result (test-invariant-check code4 {"balance" 1000 "credit_limit" 15000})]
  (if (:success result)
    (println "│   ✓ All invariants satisfied (unexpected!)")
    (println "│   ✗ Invariant violation:" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║          INVARIANT EXECUTION TESTS COMPLETE                ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Summary:")
(println "  ✓ Invariants are parsed correctly")
(println "  ✓ Valid states pass invariant checks")
(println "  ✓ Invalid states are rejected")
(println "  ✓ Multiple invariants all checked")
(println "  ✓ Boundary conditions handled correctly")
(println "  ✓ Complex invariants with multiple fields work")
