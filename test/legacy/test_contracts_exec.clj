(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║      CONTRACT EXECUTION TESTS (Manual Verification)       ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Helper function to manually execute a method with contracts
(defn execute-method-with-contracts [code param-values]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (interp/register-class ctx (first (:classes ast)))
        method-def (-> ast :classes first :body first :members first)
        method-env (interp/make-env (:globals ctx))
        ;; Bind parameters
        _ (when-let [params (:params method-def)]
            (doseq [[param val] (map vector params param-values)]
              (interp/env-define method-env (:name param) val)))
        ctx-with-env (assoc ctx :current-env method-env)]

    (try
      ;; Check preconditions
      (when-let [require-assertions (:require method-def)]
        (println "  Checking preconditions...")
        (interp/check-assertions ctx-with-env require-assertions "Precondition")
        (println "  ✓ Preconditions passed"))

      ;; Execute body
      (println "  Executing method body...")
      (doseq [stmt (:body method-def)]
        (interp/eval-node ctx-with-env stmt))

      ;; Check postconditions
      (when-let [ensure-assertions (:ensure method-def)]
        (println "  Checking postconditions...")
        (interp/check-assertions ctx-with-env ensure-assertions "Postcondition")
        (println "  ✓ Postconditions passed"))

      (println "  ✓ Method executed successfully")
      {:success true :output @(:output ctx-with-env)}

      (catch Exception e
        (println "  ✗ Error:" (.getMessage e))
        {:success false :error (.getMessage e)}))))

;; Test 1: Valid precondition
(println "┌─ Test 1: Valid Precondition ────────────────────────────┐")
(def code1 "class Test
  feature
    set_day(a_day: Integer)
      require
        valid_day: a_day >= 1 and a_day <= 31
      do
        print(a_day)
      end
end")

(println "│ Method: set_day(15)")
(println "│ Expected: Pass (15 is between 1 and 31)")
(println "│")
(let [result (execute-method-with-contracts code1 [15])]
  (when (:success result)
    (println "│ Output:" (first (:output result)))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 2: Invalid precondition
(println "┌─ Test 2: Invalid Precondition ──────────────────────────┐")
(println "│ Method: set_day(50)")
(println "│ Expected: Fail (50 is not between 1 and 31)")
(println "│")
(execute-method-with-contracts code1 [50])
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 3: Valid postcondition
(println "┌─ Test 3: Valid Postcondition ───────────────────────────┐")
(def code3 "class Test
  feature
    set_hour(a_hour: Integer)
      require
        valid_hour: a_hour >= 0 and a_hour <= 23
      do
        let hour := a_hour
      ensure
        hour_set: hour = a_hour
      end
end")

(println "│ Method: set_hour(15)")
(println "│ Expected: Pass (hour correctly set to 15)")
(println "│")
(execute-method-with-contracts code3 [15])
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 4: Invalid postcondition
(println "┌─ Test 4: Invalid Postcondition ─────────────────────────┐")
(def code4 "class Test
  feature
    buggy_set(a_hour: Integer)
      require
        valid_hour: a_hour >= 0 and a_hour <= 23
      do
        let hour := 99
      ensure
        hour_set: hour = a_hour
      end
end")

(println "│ Method: buggy_set(15)")
(println "│ Expected: Fail (hour set to 99, not 15)")
(println "│")
(execute-method-with-contracts code4 [15])
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 5: Multiple preconditions
(println "┌─ Test 5: Multiple Preconditions ────────────────────────┐")
(def code5 "class Test
  feature
    divide(a: Integer, b: Integer)
      require
        non_zero: b /= 0
        positive_a: a > 0
        positive_b: b > 0
      do
        print(a / b)
      end
end")

(println "│ Method: divide(10, 2)")
(println "│ Expected: Pass (all conditions met)")
(println "│")
(let [result (execute-method-with-contracts code5 [10 2])]
  (when (:success result)
    (println "│ Output:" (first (:output result)))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 6: One precondition fails
(println "┌─ Test 6: One Precondition Fails ────────────────────────┐")
(println "│ Method: divide(10, 0)")
(println "│ Expected: Fail (b is zero)")
(println "│")
(execute-method-with-contracts code5 [10 0])
(println "└──────────────────────────────────────────────────────────┘")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║           CONTRACT EXECUTION TESTS COMPLETE                ║")
(println "╚════════════════════════════════════════════════════════════╝")
