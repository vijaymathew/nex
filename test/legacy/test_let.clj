(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "=== Testing Local Variable Declarations ===\n")

;; Test 1: Simple let in method
(println "Test 1: Simple let in method")
(def code1 "
class Test
  feature
    run() do
      let x = 42
      print(x)
    end
end")

(let [ast (p/ast code1)
      ctx (interp/interpret ast)]
  (println "Code:")
  (println code1)
  (println "Classes registered:" (keys @(:classes ctx)))
  (println))

;; Test 2: Multiple lets
(println "Test 2: Multiple lets")
(def code2 "
class Test
  feature
    run() do
      let x = 10
      let y = 20
      print(x, y)
    end
end")

(let [ast (p/ast code2)
      ctx (interp/interpret ast)]
  (println "Code:")
  (println code2)
  (println "Classes registered:" (keys @(:classes ctx)))
  (println))

;; Test 3: Let with expressions
(println "Test 3: Let with expressions")
(def code3 "
class Test
  feature
    run() do
      let x = 5 + 3
      let y = x * 2
      print(x, y)
    end
end")

(let [ast (p/ast code3)
      ctx (interp/interpret ast)]
  (println "Code:")
  (println code3)
  (println "Classes registered:" (keys @(:classes ctx)))
  (println))

;; Test 4: Let anywhere in method body
(println "Test 4: Let anywhere in method body")
(def code4 "
class Test
  feature
    run() do
      print(1)
      let x = 2
      print(x)
      let y = 3
      print(y)
    end
end")

(let [ast (p/ast code4)
      ctx (interp/interpret ast)]
  (println "Code:")
  (println code4)
  (println "Classes registered:" (keys @(:classes ctx)))
  (println))

(println "\nAll tests complete!")
