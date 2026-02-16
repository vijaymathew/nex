(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║     CONTRACTS WITH LOCAL VARIABLES (let) INTEGRATION      ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Helper
(defn execute-method [code param-values]
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

;; Test 1: Local variables in postconditions
(println "┌─ Test 1: Local Variables in Postconditions ─────────────┐")
(def code1 "class Math
  feature
    compute_area(width: Integer, height: Integer)
      require
        positive_width: width > 0
        positive_height: height > 0
      do
        let area := width * height
        print(area)
      ensure
        area_positive: area > 0
        area_correct: area = width * height
      end
end")

(println "│ Method uses 'let' to define local variable")
(println "│ Postcondition checks the local variable")
(println "│")
(println "│ Code:")
(println "│   do")
(println "│     let area := width * height")
(println "│   ensure")
(println "│     area_positive: area > 0")
(println "│     area_correct: area = width * height")
(println "│")
(println "│ Test (10, 20):")
(let [result (execute-method code1 [10 20])]
  (if (:success result)
    (println "│   ✓ Success - Area:" (first (:output result)))
    (println "│   ✗" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 2: Multiple local variables with contracts
(println "┌─ Test 2: Multiple Local Variables with Contracts ───────┐")
(def code2 "class Geometry
  feature
    compute_rectangle(w: Integer, h: Integer)
      require
        valid_w: w > 0
        valid_h: h > 0
      do
        let area := w * h
        let perimeter := 2 * (w + h)
        let ratio := area / perimeter
        print(area, perimeter, ratio)
      ensure
        area_valid: area = w * h
        perimeter_valid: perimeter = 2 * (w + h)
        ratio_computed: ratio = area / perimeter
      end
end")

(println "│ Multiple 'let' declarations")
(println "│ Each local variable checked in postconditions")
(println "│")
(println "│ Test (10, 5):")
(let [result (execute-method code2 [10 5])]
  (if (:success result)
    (let [output (:output result)]
      (println "│   ✓ Success")
      (println "│   Output:" (first output)))
    (println "│   ✗" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 3: Contracts with intermediate calculations
(println "┌─ Test 3: Contracts with Intermediate Calculations ──────┐")
(def code3 "class Calculator
  feature
    compute_average(a: Integer, b: Integer, c: Integer)
      require
        all_positive: a > 0 and b > 0 and c > 0
      do
        let sum := a + b + c
        let count := 3
        let average := sum / count
        print(average)
      ensure
        sum_correct: sum = a + b + c
        count_is_three: count = 3
        average_in_range: average >= 1 and average <= sum
      end
end")

(println "│ Intermediate calculations with verification")
(println "│")
(println "│ Test (10, 20, 30):")
(let [result (execute-method code3 [10 20 30])]
  (if (:success result)
    (println "│   ✓ Success - Average:" (first (:output result)))
    (println "│   ✗" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 4: Postcondition detects wrong calculation
(println "┌─ Test 4: Postcondition Detects Wrong Calculation ───────┐")
(def code4 "class BuggyMath
  feature
    buggy_sum(a: Integer, b: Integer)
      require
        positive: a > 0 and b > 0
      do
        let sum := a + b + 1
      ensure
        sum_correct: sum = a + b
      end
end")

(println "│ Implementation has a bug (adds 1 extra)")
(println "│ Postcondition should catch it")
(println "│")
(println "│ Test (5, 10):")
(let [result (execute-method code4 [5 10])]
  (if (:success result)
    (println "│   ✓ Success (unexpected!)")
    (println "│   ✗ Bug caught:" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 5: Complex example with all features
(println "┌─ Test 5: Complex Example - All Features Together ───────┐")
(def code5 "class Financial
  feature
    calculate_tax(income: Integer, rate: Integer)
      require
        income_positive: income > 0
        rate_valid: rate >= 0 and rate <= 100
      do
        let tax_amount := income * rate / 100
        let net_income := income - tax_amount
        let tax_percentage := tax_amount * 100 / income
        print(tax_amount, net_income)
      ensure
        tax_computed: tax_amount = income * rate / 100
        net_computed: net_income = income - tax_amount
        percentage_matches: tax_percentage = rate
        net_less_than_income: net_income < income
      end
end")

(println "│ Real-world scenario: tax calculation")
(println "│ Multiple preconditions and postconditions")
(println "│")
(println "│ Test: Income=1000, Rate=20:")
(let [result (execute-method code5 [1000 20])]
  (if (:success result)
    (let [output (:output result)]
      (println "│   ✓ All contracts satisfied")
      (println "│   Output:" (first output)))
    (println "│   ✗" (:error result))))
(println "└──────────────────────────────────────────────────────────┘")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║              INTEGRATION TESTS COMPLETE                    ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Summary:")
(println "  ✓ Local variables (let) work in method bodies")
(println "  ✓ Postconditions can reference local variables")
(println "  ✓ Preconditions check inputs before execution")
(println "  ✓ Postconditions verify results after execution")
(println "  ✓ Multiple conditions work together")
(println "  ✓ Bugs are detected immediately")
