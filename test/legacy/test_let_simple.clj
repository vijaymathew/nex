(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "=== Testing Local Variable Declarations (Executable) ===\n")

;; Note: Since we don't have object instantiation yet, we can't execute methods.
;; Let's test the AST transformation to verify let is working correctly.

(println "Test 1: Simple let statement")
(let [code "class Foo feature bar() do let x = 42 print(x) end end"
      ast (p/ast code)]
  (println "Code:" code)
  (println "\nAST:")
  (clojure.pprint/pprint ast)
  (println))

(println "Test 2: Multiple let statements")
(let [code "class Foo feature bar() do let x = 10 let y = 20 print(x, y) end end"
      ast (p/ast code)]
  (println "Code:" code)
  (println "\nAST (body only):")
  (clojure.pprint/pprint
    (-> ast :classes first :body first :members first :body))
  (println))

(println "Test 3: Let with expressions")
(let [code "class Foo feature bar() do let x = 5 + 3 let y = x * 2 print(y) end end"
      ast (p/ast code)]
  (println "Code:" code)
  (println "\nAST (body only):")
  (clojure.pprint/pprint
    (-> ast :classes first :body first :members first :body))
  (println))

(println "Test 4: Verify let creates proper AST node")
(let [code "class Foo feature bar() do let sum = 1 + 2 + 3 end end"
      ast (p/ast code)
      let-node (-> ast :classes first :body first :members first :body first)]
  (println "Code:" code)
  (println "\nLet node:")
  (clojure.pprint/pprint let-node)
  (println "\nNode type:" (:type let-node))
  (println "Variable name:" (:name let-node))
  (println "Value type:" (-> let-node :value :type))
  (println))

(println "All tests complete!")
