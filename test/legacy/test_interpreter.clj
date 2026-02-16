(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])
(require '[clojure.pprint :as pp])

(println "=== Nex Interpreter Tests ===\n")

;; Test 1: Simple arithmetic
(println "Test 1: Simple arithmetic")
(let [code "print(3 + 4 * 2)"
      ast (p/ast code)]
  (println "Code:" code)
  (interp/run ast))

(println)

;; Test 2: Multiple arguments
(println "Test 2: Multiple arguments")
(let [code "print(1, 2, 3)"
      ast (p/ast code)]
  (println "Code:" code)
  (interp/run ast))

(println)

;; Test 3: Nested expressions
(println "Test 3: Nested expressions")
(let [code "print((5 + 3) * (10 - 2))"
      ast (p/ast code)]
  (println "Code:" code)
  (interp/run ast))

(println)

;; Test 4: Comparison operators
(println "Test 4: Comparison operators")
(let [code "print(5 > 3, 10 < 2, 7 = 7)"
      ast (p/ast code)]
  (println "Code:" code)
  (interp/run ast))

(println)

;; Test 5: Logical operators
(println "Test 5: Logical operators")
(let [code "print(true and false, true or false)"
      ast (p/ast code)]
  (println "Code:" code)
  (interp/run ast))

(println)

;; Test 6: Unary minus
(println "Test 6: Unary minus")
(let [code "print(-5, -(-10))"
      ast (p/ast code)]
  (println "Code:" code)
  (interp/run ast))

(println)

;; Test 7: String literals
(println "Test 7: String literals")
(let [code "print(\"Hello\", \"World\")"
      ast (p/ast code)]
  (println "Code:" code)
  (interp/run ast))

(println)

;; Test 8: Class definition
(println "Test 8: Class definition")
(let [code "class Point feature x: Integer y: Integer end"
      ast (p/ast code)
      ctx (interp/interpret ast)]
  (println "Code:" code)
  (println "Classes registered:" (keys @(:classes ctx))))

(println)
(println "All tests complete!")
