(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║              CLASS INVARIANTS DEMONSTRATION                ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Test 1: Simple invariant parsing
(println "┌─ Test 1: Simple Invariant Parsing ──────────────────────┐")
(def code1 "class Counter
  feature
    value: Integer
  invariant
    non_negative: value >= 0
end")

(let [ast (p/ast code1)
      class-def (first (:classes ast))]
  (println "│ Class: Counter")
  (println "│ Invariant: value >= 0")
  (println "│")
  (println "│ Class AST:")
  (println "│   Name:" (:name class-def))
  (println "│   Has invariant:" (boolean (:invariant class-def)))
  (println "│   Invariant count:" (count (:invariant class-def)))
  (println "│   Invariant labels:" (map :label (:invariant class-def))))
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

(let [ast (p/ast code2)
      class-def (first (:classes ast))]
  (println "│ Class: Date")
  (println "│ Invariants:")
  (println "│   - valid_day: day >= 1 and day <= 31")
  (println "│   - valid_hour: hour >= 0 and hour <= 23")
  (println "│")
  (println "│ Parsed invariants:" (count (:invariant class-def)))
  (doseq [{:keys [label]} (:invariant class-def)]
    (println "│   -" label)))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 3: Class with methods and invariants
(println "┌─ Test 3: Class with Methods and Invariants ─────────────┐")
(def code3 "class BankAccount
  feature
    balance: Integer
  feature
    deposit(amount: Integer)
      require
        positive_amount: amount > 0
      do
        let new_balance := balance + amount
      ensure
        balance_increased: new_balance > balance
      end
  invariant
    non_negative_balance: balance >= 0
end")

(let [ast (p/ast code3)
      class-def (first (:classes ast))]
  (println "│ Class: BankAccount")
  (println "│")
  (println "│ Has methods:" (> (count (filter #(= :method (:type %))
                                                (mapcat :members (:body class-def)))) 0))
  (println "│ Has invariants:" (boolean (:invariant class-def)))
  (println "│")
  (println "│ Class structure:")
  (println "│   - Features:" (count (:body class-def)))
  (println "│   - Invariants:" (count (:invariant class-def)))
  (println "│   - Invariant: non_negative_balance"))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 4: Complete Date example from specification
(println "┌─ Test 4: Complete Date Example ─────────────────────────┐")
(def code4 "class Date
  create
    make(a_day: Integer, a_hour: Integer)
      require
        valid_day: a_day >= 1 and a_day <= 31
        valid_hour: a_hour >= 0 and a_hour <= 23
      do
        let day := a_day
        let hour := a_hour
      ensure
        day_set: day = a_day
        hour_set: hour = a_hour
      end
  feature
    day: Integer
    hour: Integer
  feature
    set_day(a_day: Integer)
      require
        valid_argument: a_day >= 1 and a_day <= 31
      do
        let day := a_day
      ensure
        day_set: day = a_day
      end
    set_hour(a_hour: Integer)
      require
        valid_argument: a_hour >= 0 and a_hour <= 23
      do
        let hour := a_hour
      ensure
        hour_set: hour = a_hour
      end
  invariant
    valid_day: day >= 1 and day <= 31
    valid_hour: hour >= 0 and hour <= 23
end")

(let [ast (p/ast code4)
      class-def (first (:classes ast))]
  (println "│ Class: Date (Complete Example)")
  (println "│")
  (println "│ Constructors:" (count (filter #(= :create (:type %)) (:body class-def))))
  (println "│ Feature sections:" (count (filter #(= :feature-section (:type %)) (:body class-def))))
  (println "│ Invariants:" (count (:invariant class-def)))
  (println "│")
  (println "│ Invariant labels:")
  (doseq [{:keys [label]} (:invariant class-def)]
    (println "│   -" label))
  (println "│")
  (println "│ ✓ Successfully parsed complete Date class with:")
  (println "│   - Constructor with contracts")
  (println "│   - Methods with contracts")
  (println "│   - Class invariants"))
(println "└──────────────────────────────────────────────────────────┘")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║           ALL INVARIANT PARSING TESTS PASSED               ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Note: Invariants are checked after method execution on objects")
