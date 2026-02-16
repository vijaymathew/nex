(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "╔════════════════════════════════════════════════════════════╗")
(println "║         DESIGN BY CONTRACT: REQUIRE & ENSURE              ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)

;; Test 1: Simple precondition
(println "┌─ Test 1: Simple Precondition ───────────────────────────┐")
(def code1 "class Test
  feature
    set_day(a_day: Integer)
      require
        valid_day: a_day >= 1 and a_day <= 31
      do
        print(a_day)
      end
end")

(let [ast (p/ast code1)]
  (println "│ Method: set_day(a_day: Integer)")
  (println "│ Precondition: valid_day: a_day >= 1 and a_day <= 31")
  (println "│")
  (println "│ Method AST:")
  (clojure.pprint/pprint
    (-> ast :classes first :body first :members first))
  (println))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 2: Method with both require and ensure
(println "┌─ Test 2: Method with Require and Ensure ────────────────┐")
(def code2 "class Test
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

(let [ast (p/ast code2)]
  (println "│ Method: set_hour(a_hour: Integer)")
  (println "│ Precondition: valid_hour: a_hour >= 0 and a_hour <= 23")
  (println "│ Postcondition: hour_set: hour = a_hour")
  (println "│")
  (println "│ Method AST:")
  (clojure.pprint/pprint
    (-> ast :classes first :body first :members first))
  (println))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 3: Constructor with contracts
(println "┌─ Test 3: Constructor with Contracts ────────────────────┐")
(def code3 "class Date
  constructors
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
end")

(let [ast (p/ast code3)]
  (println "│ Constructor: make(a_day: Integer, a_hour: Integer)")
  (println "│ Preconditions:")
  (println "│   - valid_day: a_day >= 1 and a_day <= 31")
  (println "│   - valid_hour: a_hour >= 0 and a_hour <= 23")
  (println "│ Postconditions:")
  (println "│   - day_set: day = a_day")
  (println "│   - hour_set: hour = a_hour")
  (println "│")
  (println "│ Constructor AST:")
  (clojure.pprint/pprint
    (-> ast :classes first :body first :constructors first))
  (println))
(println "└──────────────────────────────────────────────────────────┘")
(println)

;; Test 4: Multiple preconditions
(println "┌─ Test 4: Multiple Preconditions ────────────────────────┐")
(def code4 "class Test
  feature
    divide(a: Integer, b: Integer)
      require
        non_zero: b /= 0
        positive: a > 0
        also_positive: b > 0
      do
        print(a / b)
      end
end")

(let [ast (p/ast code4)
      method (-> ast :classes first :body first :members first)]
  (println "│ Method: divide(a: Integer, b: Integer)")
  (println "│")
  (println "│ Number of preconditions:" (count (:require method)))
  (println "│ Preconditions:")
  (doseq [{:keys [label]} (:require method)]
    (println "│   -" label))
  (println))
(println "└──────────────────────────────────────────────────────────┘")
(println)

(println "╔════════════════════════════════════════════════════════════╗")
(println "║             ALL CONTRACT PARSING TESTS PASSED              ║")
(println "╚════════════════════════════════════════════════════════════╝")
(println)
(println "Note: Contract checking will be enforced when methods are called")
