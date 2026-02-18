(require '[nex.parser :as p])
(require '[nex.interpreter :as interp])

(println "=== Advanced Nex Interpreter Test ===\n")

;; Test: Class with methods
(println "Test: Class with method")
(def point-class-code "
class Point
  feature
    x: Integer
    y: Integer
  create
    make(px: Integer, py: Integer) do
      x := px
      y := py
    end
  feature
    distance() do
      print(x, y)
    end
end
")

(println "Code:")
(println point-class-code)

(let [ast (p/ast point-class-code)
      ctx (interp/interpret ast)]
  (println "Classes registered:" (keys @(:classes ctx)))
  (println "\nClass details:")
  (clojure.pprint/pprint (get @(:classes ctx) "Point")))

(println "\n=== Test Complete ===")
