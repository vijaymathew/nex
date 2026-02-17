(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║         DESIGN BY CONTRACT DEMONSTRATION                  ║")
(println "║         require (preconditions) & ensure (postconditions) ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Helper function
(defn execute-method-with-contracts [code param-values]
  (let [ast (p/ast code)
        ctx (interp/make-context)
        _ (interp/register-class ctx (first (:classes ast)))
        method-def (-> ast :classes first :body first :members first)
        method-env (interp/make-env (:globals ctx))
        _ (when-let [params (:params method-def)]
            (doseq [[param val] (map vector params param-values)]
              (interp/env-define method-env (:name param) val)))
        ctx-with-env (assoc ctx :current-env method-env)]
    (try
      (when-let [require-assertions (:require method-def)]
        (interp/check-assertions ctx-with-env require-assertions "Precondition"))
      (doseq [stmt (:body method-def)]
        (interp/eval-node ctx-with-env stmt))
      (when-let [ensure-assertions (:ensure method-def)]
        (interp/check-assertions ctx-with-env ensure-assertions "Postcondition"))
      {:success true :output @(:output ctx-with-env)}
      (catch Exception e
        {:success false :error (.getMessage e)}))))

;; Test 1: Basic Precondition
(println "┌─ Test 1: Basic Precondition ────────────────────────────┐")
(println "│ Setting a day value with validation")
(println "│")
(def code1 "class Calendar
  feature
    set_day(a_day: Integer)
      require
        valid_day: a_day >= 1 and a_day <= 31
      do
        print(a_day)
      end
end")

(println "│ Code:")
(println "│   require")
(println "│     valid_day: a_day >= 1 and a_day <= 31")
(println "│")
(println "│ Test with valid value (15):")
(let [result (execute-method-with-contracts code1 [15])]
  (if (:success result)
    (println "│   ✓ Success - Output:" (first (:output result)))
    (println "│   ✗ Failed -" (:error result))))
(println "│")
(println "│ Test with invalid value (50):")
(let [result (execute-method-with-contracts code1 [50])]
  (if (:success result)
    (println "│   ✓ Success")
    (println "│   ✗ Contract violation:" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 2: Precondition + Postcondition
(println "┌─ Test 2: Precondition + Postcondition ──────────────────┐")
(println "│ Setting hour with both input validation and result check")
(println "│")
(def code2 "class Calendar
  feature
    set_hour(a_hour: Integer)
      require
        valid_hour: a_hour >= 0 and a_hour <= 23
      do
        hour := a_hour
      ensure
        hour_set: hour = a_hour
      end
end")

(println "│ Code:")
(println "│   require")
(println "│     valid_hour: a_hour >= 0 and a_hour <= 23")
(println "│   do")
(println "│     let hour := a_hour")
(println "│   ensure")
(println "│     hour_set: hour = a_hour")
(println "│")
(println "│ Test with valid value (15):")
(let [result (execute-method-with-contracts code2 [15])]
  (if (:success result)
    (println "│   ✓ Both precondition and postcondition passed")
    (println "│   ✗ Failed -" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 3: Multiple Preconditions
(println "┌─ Test 3: Multiple Preconditions ────────────────────────┐")
(println "│ Division with multiple safety checks")
(println "│")
(def code3 "class Math
  feature
    safe_divide(a: Integer, b: Integer)
      require
        non_zero: b /= 0
        positive_a: a > 0
        positive_b: b > 0
      do
        print(a / b)
      end
end")

(println "│ Code:")
(println "│   require")
(println "│     non_zero: b /= 0")
(println "│     positive_a: a > 0")
(println "│     positive_b: b > 0")
(println "│")
(println "│ Test (10, 2) - all conditions satisfied:")
(let [result (execute-method-with-contracts code3 [10 2])]
  (if (:success result)
    (println "│   ✓ Success - Result:" (first (:output result)))
    (println "│   ✗ Failed -" (:error result))))
(println "│")
(println "│ Test (10, 0) - division by zero:")
(let [result (execute-method-with-contracts code3 [10 0])]
  (if (:success result)
    (println "│   ✓ Success")
    (println "│   ✗ Contract violation:" (:error result))))
(println "│")
(println "│ Test (-5, 2) - negative dividend:")
(let [result (execute-method-with-contracts code3 [-5 2])]
  (if (:success result)
    (println "│   ✓ Success")
    (println "│   ✗ Contract violation:" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 4: Postcondition Violation
(println "┌─ Test 4: Postcondition Violation (Bug Detection) ───────┐")
(println "│ Method with implementation bug caught by postcondition")
(println "│")
(def code4 "class BuggyClass
  feature
    buggy_set(value: Integer)
      require
        valid: value >= 0
      do
        let result := 999
      ensure
        correct: result = value
      end
end")

(println "│ Code (buggy implementation):")
(println "│   do")
(println "│     let result := 999  -- Bug: wrong value!")
(println "│   ensure")
(println "│     correct: result = value")
(println "│")
(println "│ Test with value 42:")
(let [result (execute-method-with-contracts code4 [42])]
  (if (:success result)
    (println "│   ✓ Success (unexpected!)")
    (println "│   ✗ Postcondition caught the bug:" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 5: Complex Example
(println "┌─ Test 5: Complex Example - Rectangle ───────────────────┐")
(println "│ Setting dimensions with multiple constraints")
(println "│")
(def code5 "class Rectangle
  feature
    set_dimensions(w: Integer, h: Integer)
      require
        positive_width: w > 0
        positive_height: h > 0
        not_too_large: w < 1000 and h < 1000
      do
        let width := w
        let height := h
        let area := w * h
      ensure
        width_correct: width = w
        height_correct: height = h
        area_computed: area = w * h
      end
end")

(println "│ Code:")
(println "│   require")
(println "│     positive_width: w > 0")
(println "│     positive_height: h > 0")
(println "│     not_too_large: w < 1000 and h < 1000")
(println "│   ensure")
(println "│     width_correct: width = w")
(println "│     height_correct: height = h")
(println "│     area_computed: area = w * h")
(println "│")
(println "│ Test (100, 50):")
(let [result (execute-method-with-contracts code5 [100 50])]
  (if (:success result)
    (println "│   ✓ All preconditions and postconditions passed")
    (println "│   ✗ Failed -" (:error result))))
(println "│")
(println "│ Test (2000, 50) - too large:")
(let [result (execute-method-with-contracts code5 [2000 50])]
  (if (:success result)
    (println "│   ✓ Success")
    (println "│   ✗ Contract violation:" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║                  DEMONSTRATION COMPLETE                    ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Key Takeaways:")
(println "  • require = preconditions (caller's responsibility)")
(println "  • ensure = postconditions (method's responsibility)")
(println "  • Contracts document assumptions explicitly")
(println "  • Contract violations indicate bugs immediately")
(println "  • Multiple conditions can be checked together")
